package com.diapilot.core.physio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class CheckpointKind { CARRIED_BASELINE, PROVISIONAL_60, PROVISIONAL_120, RECONCILED, INVALIDATED }
enum class DiscrepancyFactorV1 {
    IDENTIFIED_ISF, INSULIN_TIMING_KERNEL, CARB_AMOUNT_PROVENANCE, FOOD_ABSORPTION_TIMING_TAIL,
    INTAKE_DURATION, ACTIVITY_TODAY, ACTIVITY_PREVIOUS_DAY, SLEEP, HYPO_COUNTERREGULATORY_BACKGROUND,
    BASAL_MISMATCH, GLUCOSE_LEVEL_DOSE_DEPENDENCE, ALCOHOL_HEPATIC, SENSOR_PROCESS,
    PROTEIN_FAT_LATE_TAIL, STRESS_ILLNESS, INJECTION_SITE_LIPOHYPERTROPHY,
    CARTRIDGE_AGE_CHANGE_PRODUCT_HEAT, SENSOR_AGE_CHANGE_LAG_COMPRESSION_CALIBRATION,
    DATA_GAPS,
    JOINT_CONFOUNDED, UNRESOLVED,
}

data class ResponseObservationV1(
    val episodeId: String,
    val eventStartMs: Long,
    val knownAtMs: Long,
    val horizonMin: Int,
    val predictedDelta: Double,
    val observedDelta: Double,
    val hasFood: Boolean,
    val hasBolus: Boolean,
    val insulinIdentifying: Boolean,
    val contamination: Set<String> = emptySet(),
)

data class DailyResponseStateV1(
    val effectiveResponsePercent: PosteriorV1,
    val identifiedIsfPercent: PosteriorV1,
    val identifiedIsfStatus: EvidenceStatus,
)

data class DailyCheckpointV1(
    val checkpointId: String,
    val supersedesId: String?,
    val kind: CheckpointKind,
    val knownAtMs: Long,
    val state: DailyResponseStateV1,
    val evidenceIds: List<String>,
    val closed: Boolean = false,
)

object DailyResponseEstimatorV1 {
    private const val DAY_MS=86_400_000L
    private const val HALF_LIFE_DAYS=2.0
    private const val MAX_ISF_PERCENTAGE_POINT_UPDATE_PER_IDENTIFYING_DAY=15.0

    fun carryForward(state:DailyResponseStateV1,asOfMs:Long):DailyResponseStateV1 = state.copy(
        effectiveResponsePercent=transition(state.effectiveResponsePercent,asOfMs,6.0),
        identifiedIsfPercent=transition(state.identifiedIsfPercent,asOfMs,4.0),
    )

    private fun transition(prior:PosteriorV1,asOfMs:Long,processPerSqrtDay:Double):PosteriorV1 {
        val last=prior.lastUpdateMs ?: return prior.copy(lastUpdateMs=asOfMs)
        if(asOfMs<=last||asOfMs==Long.MAX_VALUE) return prior
        val ageDays=(asOfMs-last).toDouble()/DAY_MS
        val decay=kotlin.math.exp(-kotlin.math.ln(2.0)*ageDays/HALF_LIFE_DAYS)
        val median=prior.median*decay
        val oldHalf=max(prior.median-prior.p10,prior.p90-prior.median)
        val half=sqrt(oldHalf*oldHalf+processPerSqrtDay*processPerSqrtDay*ageDays)
        return prior.copy(median=median,p10=median-half,p90=median+half,lastUpdateMs=asOfMs)
    }

    fun update(baseline: DailyResponseStateV1, observations: List<ResponseObservationV1>, knownAtMs: Long): DailyResponseStateV1 {
        val carried=carryForward(baseline,knownAtMs)
        val available = observations.filter { it.knownAtMs <= knownAtMs && it.predictedDelta != 0.0 }
            .groupBy{it.eventStartMs}.values.map{rows->rows.maxBy{it.horizonMin}}
        fun posterior(rows: List<ResponseObservationV1>, prior: PosteriorV1, wide: Double, previousLastUpdateMs:Long?): PosteriorV1 {
            if (rows.isEmpty()) return prior
            val values = rows.map {
                val direction = if (it.predictedDelta >= 0.0) 1.0 else -1.0
                100.0 * (it.observedDelta - it.predictedDelta) * direction / max(0.2, abs(it.predictedDelta))
            }.map{it.coerceIn(-80.0,80.0)}.sorted()
            val robust = if(values.size>=5) values.drop(1).dropLast(1) else values
            val sampleMean = robust.average()
            val priorWeight=prior.identifyingEpisodes.coerceIn(0,5).toDouble()
            val mean=(sampleMean*robust.size+prior.median*priorWeight)/(robust.size+priorWeight).coerceAtLeast(1.0)
            val sd = if (values.size > 1) sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)) else wide
            val totalN=prior.identifyingEpisodes+rows.size
            val half = max(wide / sqrt(totalN.coerceAtLeast(1).toDouble()), 1.64 * sd / sqrt(values.size.toDouble()))
            val priorDay=previousLastUpdateMs?.div(DAY_MS)
            val newDays=rows.map{it.eventStartMs/DAY_MS}.distinct().count{it!=priorDay}
            return PosteriorV1(mean, mean-half, mean+half,totalN,prior.independentDays+newDays,knownAtMs)
        }
        val effective = posterior(available, carried.effectiveResponsePercent, 30.0, baseline.effectiveResponsePercent.lastUpdateMs)
        val identifying = available.filter { it.insulinIdentifying && it.contamination.isEmpty() && !it.hasFood }
        val rawIsf = posterior(identifying, carried.identifiedIsfPercent, 20.0, baseline.identifiedIsfPercent.lastUpdateMs)
        val addedDays=(rawIsf.independentDays-carried.identifiedIsfPercent.independentDays).coerceAtLeast(1)
        val maxStep=MAX_ISF_PERCENTAGE_POINT_UPDATE_PER_IDENTIFYING_DAY*addedDays
        val boundedMedian=rawIsf.median.coerceIn(carried.identifiedIsfPercent.median-maxStep,carried.identifiedIsfPercent.median+maxStep)
        val shift=boundedMedian-rawIsf.median
        val isf=rawIsf.copy(median=boundedMedian,p10=rawIsf.p10+shift,p90=rawIsf.p90+shift)
        val directionStable=carried.identifiedIsfPercent.identifyingEpisodes==0||
            carried.identifiedIsfPercent.median==0.0||isf.median==0.0||carried.identifiedIsfPercent.median*isf.median>0
        val supported=identifying.isNotEmpty()&&isf.identifyingEpisodes>=6&&isf.independentDays>=3&&directionStable&&isf.p10*isf.p90>0
        return DailyResponseStateV1(
            effective,
            isf,
            if (identifying.isEmpty() && available.isNotEmpty()) EvidenceStatus.NOT_IDENTIFIABLE
            else if(supported) EvidenceStatus.SUPPORTED
            else if(isf.identifyingEpisodes>=3&&isf.independentDays>=2&&directionStable) EvidenceStatus.PRELIMINARY
            else EvidenceStatus.INSUFFICIENT,
        )
    }

    fun appendCheckpoint(history: List<DailyCheckpointV1>, next: DailyCheckpointV1): List<DailyCheckpointV1> {
        require(history.none { it.checkpointId == next.checkpointId })
        require(history.lastOrNull()?.knownAtMs?.let { next.knownAtMs >= it } != false)
        require(next.supersedesId == history.lastOrNull()?.checkpointId)
        return history + next
    }
}

data class CandidateContributionV1(
    val factor: String,
    val medianPoints: Double,
    val lowPoints: Double,
    val highPoints: Double,
    val supported: Boolean,
    val correlationGroup: String? = null,
    val hypotheses: List<String> = emptyList(),
    /** Separate from attribution support: promotion changes forecasts, support explains observations. */
    val promotedToForecastMedian: Boolean = false,
)

data class DecompositionV1(
    val observedShiftPoints: Double,
    val unique: List<CandidateContributionV1>,
    val jointConfoundedPoints: Double,
    val unresolvedPoints: Double,
) {
    val explainedPoints get() = unique.sumOf { it.medianPoints } + jointConfoundedPoints
    val explainedShare get() = if (observedShiftPoints == 0.0) 0.0 else explainedPoints / observedShiftPoints
}

data class ChangeAssessmentV1(
    val parameter: String,
    val changePercent: PosteriorV1,
    val probabilityPositive: Double,
    val practicalThresholdPercent: Double,
    val practicalThresholdCrossed: Boolean,
    val versus: String,
)

fun detectChangeV1(parameter: String, current: PosteriorV1, baseline: PosteriorV1, practicalThresholdPercent: Double, versus: String): ChangeAssessmentV1 {
    val median = current.median - baseline.median
    val low = current.p10 - baseline.p90
    val high = current.p90 - baseline.p10
    val scale = max(1e-6, (high - low) / 3.28)
    val probabilityPositive = 1.0 / (1.0 + kotlin.math.exp(-1.7 * median / scale))
    return ChangeAssessmentV1(parameter, PosteriorV1(median, low, high, current.identifyingEpisodes, current.independentDays, current.lastUpdateMs), probabilityPositive,
        practicalThresholdPercent, low > practicalThresholdPercent || high < -practicalThresholdPercent, versus)
}

fun explanationCoverageTextV1(d: DecompositionV1): String {
    val share = if (d.observedShiftPoints == 0.0) 0.0 else d.explainedShare * 100.0
    return "Available factors explain approximately %+.0f percentage points, or %.0f%% of the observed shift; about %+.0f points remain unresolved/confounded."
        .format(d.explainedPoints, share, d.unresolvedPoints)
}

fun decomposeDiscrepancyV1(observedShiftPoints: Double, candidates: List<CandidateContributionV1>): DecompositionV1 {
    val supported = candidates.filter { it.supported }
    val correlated = supported.filter { it.correlationGroup != null }.groupBy { it.correlationGroup }
    val joint = correlated.values.filter { it.size > 1 }.sumOf { group -> group.sumOf { it.medianPoints } }
    val jointMembers = correlated.values.filter { it.size > 1 }.flatten().toSet()
    val unique = supported.filterNot { it in jointMembers }
    val explained = unique.sumOf { it.medianPoints } + joint
    return DecompositionV1(observedShiftPoints, unique, joint, observedShiftPoints - explained)
}
