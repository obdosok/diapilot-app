package com.diapilot.core.physio

import com.diapilot.core.hybrid.*
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.sqrt

enum class ForecastArmSelection { LEGACY_V11, PHYSIO_V1, COMPARE }
enum class EvidenceStatus {
    SOURCE_MISSING, NO_EXPOSURES, NOT_IDENTIFIABLE, INSUFFICIENT, NULL_COMPATIBLE,
    HYPOTHESIS, PRELIMINARY, SUPPORTED, CONFLICTING,
}
enum class ForecastUse { MEDIAN, UNCERTAINTY_ONLY, NOT_USED }
enum class EvidenceProvenanceV1 { NONE, RETROSPECTIVE_DESCRIPTIVE, PROSPECTIVE_CHECKPOINT, MIXED }

data class PosteriorV1(val median: Double, val p10: Double, val p90: Double, val identifyingEpisodes: Int, val independentDays: Int, val lastUpdateMs: Long?) {
    init { require(p10 <= median && median <= p90); require(identifyingEpisodes >= 0 && independentDays >= 0) }
}

data class FactorEvidenceV1(
    val factor: String,
    val targetParameter: String,
    val effectPercent: PosteriorV1,
    val status: EvidenceStatus,
    val comparisonWindow: String,
    val confounders: List<String>,
    val rawExposures: Int = 0,
    val usableEpisodes: Int = 0,
    val minimumPracticalEffectPercent: Double = 5.0,
    val rationale: String = "",
    val dataSource: String = "",
    val coverageFraction: Double = 0.0,
    val exclusions: Map<String, Int> = emptyMap(),
    val neededForNextStatus: String = "",
    val forecastUse: ForecastUse = ForecastUse.NOT_USED,
    val permutationP: Double? = null,
    val stableAcrossFolds: Boolean = false,
    val heldOutDirectionalStable: Boolean = false,
    val evidenceProvenance: EvidenceProvenanceV1 = EvidenceProvenanceV1.NONE,
    val prospectiveEpisodes: Int = 0,
    val retrospectiveEpisodes: Int = 0,
    val promotionStage: PromotionStageV1 = PromotionStageV1.DESCRIPTIVE_ONLY,
    val promotionReason: String = "support gates not passed",
    val prospectiveLossImprovementFraction: Double? = null,
    val typedEffect: EffectPayloadV1? = null,
) {
    init {
        require(rawExposures >= 0 && usableEpisodes >= 0 && prospectiveEpisodes >= 0 && retrospectiveEpisodes >= 0 && coverageFraction in 0.0..1.0)
        if (forecastUse == ForecastUse.MEDIAN) require(status == EvidenceStatus.SUPPORTED || promotionStage in setOf(PromotionStageV1.PROMOTED_MEDIAN,PromotionStageV1.MONITORED))
        typedEffect?.let {
            val expected=when(dataSource){
                else->PhysioHypothesisRegistryV1.definitions.firstOrNull { d -> d.id==dataSource }?.executableSpec?.parameterTarget
            }
            require(it.target == expected)
        }
        if (status == EvidenceStatus.SOURCE_MISSING) require(rawExposures == 0 && usableEpisodes == 0)
        if (status == EvidenceStatus.NO_EXPOSURES) require(rawExposures == 0)
        if (status == EvidenceStatus.NULL_COMPATIBLE) require(
            effectPercent.independentDays >= 5 && effectPercent.p10 >= -minimumPracticalEffectPercent &&
                effectPercent.p90 <= minimumPracticalEffectPercent,
        ) { "null-compatible requires adequate support and a narrow practically-null interval" }
        if (status == EvidenceStatus.SUPPORTED) require(
            effectPercent.independentDays >= 5 && effectPercent.p10 * effectPercent.p90 > 0.0 &&
                stableAcrossFolds && heldOutDirectionalStable && permutationP != null && permutationP <= 0.05 &&
                prospectiveEpisodes > 0 && evidenceProvenance in setOf(EvidenceProvenanceV1.PROSPECTIVE_CHECKPOINT, EvidenceProvenanceV1.MIXED),
        ) { "supported factor must pass preregistered gates" }
    }
}

data class HypothesisDefinitionV1(
    val id: String, val group: String, val factor: String, val targetParameter: String,
    val rationale: String, val minimumPracticalEffectPercent: Double,
) {
    val executableSpec: ExecutableHypothesisSpecV1 get() = executableHypothesisSpecV1(id, targetParameter, minimumPracticalEffectPercent)
}

object PhysioHypothesisRegistryV1 {
    val definitions = listOf(
        HypothesisDefinitionV1("activity_same_isf", "ISF/context", "same-day activity", "ISF", "activity may alter insulin sensitivity", 8.0),
        HypothesisDefinitionV1("activity_same_effective", "ISF/context", "activity during the food/insulin action window", "effective insulin response", "activity may increase glucose disposal and the apparent response to active insulin; mixed episodes are attribution-only, not pure ISF", 8.0),
        HypothesisDefinitionV1("activity_previous_isf", "ISF/context", "previous-day activity", "ISF", "delayed activity association", 8.0),
        HypothesisDefinitionV1("sleep_isf", "ISF/context", "sleep duration/fragmentation/timing", "ISF", "sleep may alter insulin sensitivity", 8.0),
        HypothesisDefinitionV1("sleep_background", "basal/background/hepatic", "sleep duration/fragmentation/timing", "background", "sleep may alter non-insulin background", 10.0),
        HypothesisDefinitionV1("hypo_0_4_isf", "ISF/context", "hypo 0–4h", "ISF", "counterregulation may confound effective ISF", 10.0),
        HypothesisDefinitionV1("hypo_4_12_isf", "ISF/context", "hypo 4–12h", "ISF", "delayed counterregulation may confound effective ISF", 10.0),
        HypothesisDefinitionV1("hypo_night_isf", "ISF/context", "previous-night hypo", "ISF", "night hypo may affect next-day response", 10.0),
        HypothesisDefinitionV1("glucose_level_isf", "ISF/context", "glucose level", "effective ISF", "response may depend on starting glucose", 8.0),
        HypothesisDefinitionV1("dose_tail", "insulin kinetics", "insulin dose", "insulin tail", "dose may alter apparent tail", 10.0),
        HypothesisDefinitionV1("tod_peak", "insulin kinetics", "time of day", "insulin onset/peak/tail", "kinetics may vary cyclically", 10.0),
        HypothesisDefinitionV1("tod_onset", "insulin kinetics", "time of day", "insulin onset", "onset may vary cyclically", 10.0),
        HypothesisDefinitionV1("tod_tail", "insulin kinetics", "time of day", "insulin tail", "tail may vary cyclically", 10.0),
        HypothesisDefinitionV1("insulin_product_kinetics", "insulin kinetics", "insulin product", "insulin potency/kinetics", "recorded product changes may alter action", 10.0),
        HypothesisDefinitionV1("cartridge_kinetics", "insulin kinetics", "cartridge age", "insulin onset/peak/tail", "recorded cartridge age may alter delivery kinetics", 10.0),
        HypothesisDefinitionV1("protein_absorption", "food absorption/macros", "protein", "absorption tail", "protein may alter late absorption", 10.0),
        HypothesisDefinitionV1("fat_absorption", "food absorption/macros", "fat", "absorption t50/tail", "fat may delay absorption", 10.0),
        HypothesisDefinitionV1("duration_absorption", "food absorption/macros", "intake duration", "absorption onset/t50", "distributed intake changes timing", 10.0),
        HypothesisDefinitionV1("alcohol_background", "basal/background/hepatic", "alcohol", "hepatic background", "alcohol may confound late background", 10.0),
        HypothesisDefinitionV1("basal_background", "basal/background/hepatic", "basal dose/age/product", "basal mismatch/background", "basal mismatch changes background", 10.0),
        HypothesisDefinitionV1("hepatic_hypo", "basal/background/hepatic", "recent/night hypo", "hepatic/counterregulatory background", "counterregulation may mimic weaker insulin", 10.0),
        HypothesisDefinitionV1("sensor_epoch", "sensor/data quality", "sensor epoch", "sensor/process residual", "sensor transitions may alter residuals", 8.0),
        HypothesisDefinitionV1("sensor_bias", "sensor/data quality", "sensor epoch", "sensor bias", "meter-paired epochs may identify observation bias", 8.0),
        HypothesisDefinitionV1("injection_site", "sensor/data quality", "injection site", "insulin kinetics", "recorded delivery site may alter kinetics", 10.0),
        HypothesisDefinitionV1("cartridge_heat", "insulin kinetics", "cartridge heat exposure", "insulin potency/kinetics", "heat is only identifiable with timestamped exposure and cartridge context", 10.0),
        HypothesisDefinitionV1("stress_illness_background", "basal/background/hepatic", "stress or illness", "hepatic/background", "recorded stress or illness may alter background", 10.0),
        HypothesisDefinitionV1("sensor_compression_lag", "sensor/data quality", "sensor lag/compression/calibration/gaps", "sensor/process residual", "sensor artifacts can mimic physiological residual", 8.0),
    )

    fun emptyMatrix(sourceAvailable: Set<String> = emptySet()): List<FactorEvidenceV1> = definitions.map { d ->
        FactorEvidenceV1(
            factor = d.factor, targetParameter = d.targetParameter,
            effectPercent = PosteriorV1(0.0, 0.0, 0.0, 0, 0, null),
            status = if (d.id in sourceAvailable) EvidenceStatus.NO_EXPOSURES else EvidenceStatus.SOURCE_MISSING,
            comparisonWindow = "none", confounders = emptyList(),
            minimumPracticalEffectPercent = d.minimumPracticalEffectPercent,
            rationale = d.rationale, dataSource = d.id, neededForNextStatus = "collect exposures",
        )
    }
}

data class VarianceLedgerV1(
    val carbAmount: Double, val foodTiming: Double, val insulinTiming: Double,
    val backgroundHepatic: Double, val basalMismatch: Double, val sensorProcess: Double,
) { init { listOf(carbAmount, foodTiming, insulinTiming, backgroundHepatic, basalMismatch, sensorProcess).forEach { require(it >= 0.0 && it.isFinite()) } } }

/**
 * The base variance ledgers the physio arm starts from, HEALTHY and DEGRADED.
 *
 * They were literals inside `app/PhysioRuntime`, which meant the band could not
 * be reproduced off-device at all: `tools` can compose the interval (the maths
 * is here) but had no way to obtain the six numbers it composes from without
 * copying them across the module boundary. Copying is how `HarnessFoods`
 * drifted from `FoodSources`, so the numbers move here instead — same values,
 * same order, one definition.
 *
 * This unblocks measuring what `macroTimingUncertaintyExtraV1` actually does to
 * the corridor (W-09), which is a safety question the harness could not touch.
 */
val PHYSIO_BASE_VARIANCE_HEALTHY_V1 = VarianceLedgerV1(.18, .16, .12, .18, .12, .24)

/** Used when closed-loop maintenance reports itself unhealthy. */
val PHYSIO_BASE_VARIANCE_DEGRADED_V1 = VarianceLedgerV1(.25, .25, .22, .30, .25, .35)

data class IntervalVarianceContributionsV1(
    val carbAmount: Double,
    val foodTiming: Double,
    val insulinTiming: Double,
    val backgroundHepatic: Double,
    val basalMismatch: Double,
    val sensorProcess: Double,
) {
    /** Central 80% (p10/p90) Gaussian approximation from independent 1-sigma channels. */
    fun halfWidth(): Double = 1.2815515655446004 * sqrt(
        carbAmount * carbAmount + foodTiming * foodTiming + insulinTiming * insulinTiming +
            backgroundHepatic * backgroundHepatic + basalMismatch * basalMismatch +
            sensorProcess * sensorProcess,
    )
}

fun conditionalVarianceV1(base:VarianceLedgerV1,modifiers:List<AppliedModifierV1>,activeContextIds:Set<String>):VarianceLedgerV1 {
    fun width(e:EffectPayloadV1)=abs(e.high-e.low)/2.0
    fun normalized(e:EffectPayloadV1)=when(e.target.unit){"fraction"->width(e);"minute"->width(e)/180.0;"mmol/L/hour"->width(e);"mmol/L"->width(e);else->0.0}
    fun extra(vararg targets:ParameterTargetV1)=sqrt(modifiers.filter{it.hypothesisId in activeContextIds&&it.effect.target in targets}.sumOf{normalized(it.effect)*normalized(it.effect)})
    fun combine(a:Double,b:Double)=sqrt(a*a+b*b).coerceAtMost(.8)
    return base.copy(
        foodTiming=combine(base.foodTiming,extra(ParameterTargetV1.FOOD_ONSET,ParameterTargetV1.FOOD_PEAK,ParameterTargetV1.FOOD_TAIL)),
        insulinTiming=combine(base.insulinTiming,extra(ParameterTargetV1.ISF_MULTIPLIER,ParameterTargetV1.INSULIN_ONSET,ParameterTargetV1.INSULIN_PEAK,ParameterTargetV1.INSULIN_TAIL,ParameterTargetV1.INSULIN_POTENCY)),
        backgroundHepatic=combine(base.backgroundHepatic,extra(ParameterTargetV1.BACKGROUND_RATE)),
        basalMismatch=combine(base.basalMismatch,extra(ParameterTargetV1.BASAL_MISMATCH)),
        sensorProcess=combine(base.sensorProcess,extra(ParameterTargetV1.SENSOR_BIAS,ParameterTargetV1.SENSOR_PROCESS_VARIANCE)),
    )
}

fun intervalContributionsV1(
    point: HybridForecastPoint,
    variance: VarianceLedgerV1,
    macroTimingExtraFraction: Double = 0.0,
): IntervalVarianceContributionsV1 {
    val progress = sqrt(point.minutes.coerceAtLeast(0) / 60.0)
    return IntervalVarianceContributionsV1(
        carbAmount = abs(point.foodDelta) * variance.carbAmount,
        foodTiming = abs(point.foodDelta) * (variance.foodTiming + macroTimingExtraFraction) *
            sqrt(point.minutes.coerceIn(0, 180) / 180.0),
        insulinTiming = abs(point.insulinActualDelta + point.insulinScenarioDelta) *
            variance.insulinTiming,
        backgroundHepatic = variance.backgroundHepatic * progress,
        basalMismatch = variance.basalMismatch * progress,
        sensorProcess = variance.sensorProcess * progress,
    )
}

/** Unpromoted/unknown macros affect timing uncertainty, never the median. */
fun macroTimingUncertaintyExtraV1(events: List<HybridFoodEvent>, promoted: Boolean): Double {
    if (promoted || events.isEmpty()) return 0.0
    val weighted = events.sumOf { event ->
        val macroSignal = if (event.proteinG == null && event.fatG == null) 0.08 else
            (0.02 * ((event.proteinG ?: 0.0) / 15.0 + (event.fatG ?: 0.0) / 15.0)).coerceAtMost(0.18)
        event.carbsG.coerceAtLeast(0.0) * macroSignal
    }
    return (weighted / events.sumOf { it.carbsG.coerceAtLeast(0.0) }.coerceAtLeast(1.0)).coerceIn(0.0, 0.20)
}

/**
 * The two background coefficients PHYSIO zeroes, carried as MEASURED values.
 *
 * `personModelAt` zeroes `activityDirect`, `sleep`, `sleepDebt` and
 * `hoursSinceWake` because they were fitted inside a different factorization and
 * may not enter PHYSIO's median un-measured. Two of them have now been measured
 * on this person's own quiet segments, and this type is how the measurement
 * reaches the model — as an artifact parameter, never as a constant in the
 * engine. Absent (null) reproduces today's behaviour exactly: all four zero.
 *
 * UNITS follow `HybridJointCoefficients`: the engine accumulates `rate30` in
 * 5-minute steps as `rate30 * 5/30` and scales the total by `backgroundScale`,
 * so with the shipped `backgroundScale = 0.5` the per-hour background is
 * numerically equal to `rate30`. Both fields are therefore read as mmol/L per
 * hour at `backgroundScale = 0.5`.
 *
 * THE PREDICTOR IS A PROXY AND THE CAUSE IS NOT ESTABLISHED. Measured
 * on 366 non-overlapping quiet segments over 35 days: the deficit is present
 * while asleep AND while awake early in the basal insulin cycle, and absent only when
 * awake late in it. Sleep and basal phase cannot be separated on this data —
 * the user injects basal inside a 96-minute window every day and the corpus holds no
 * daytime sleep at all. `sleep` is used because it is the wiring the engine
 * already has, not because the cause is known.
 */
data class BackgroundCoefficientsV1(
    val sleepMmolPerHour: Double,
    val hoursSinceWakeMmolPerHourPerHour: Double,
    /** Identity of the segment SET the pair was fitted on. The promotion ledger
     * keys its source-evidence hash on this, so a refit on new segments is new
     * evidence rather than a silently decaying old one. */
    val evidenceIdentity: String,
) {
    init {
        require(sleepMmolPerHour.isFinite() && kotlin.math.abs(sleepMmolPerHour) <= 1.0) {
            "sleep background coefficient outside the physiological bound"
        }
        require(hoursSinceWakeMmolPerHourPerHour.isFinite() && kotlin.math.abs(hoursSinceWakeMmolPerHourPerHour) <= 0.10) {
            "hours-since-wake background coefficient outside the physiological bound"
        }
        require(evidenceIdentity.isNotBlank())
    }
}

/** Immutable experimental artifact. Patient-specific point estimates must come from a versioned artifact, never runtime guesses. */
data class PhysioArtifactV1(
    val artifactId: String,
    val baseMechanics: HybridPersonModel,
    val globalCs: PosteriorV1,
    val globalIsf: PosteriorV1,
    val isfSin24: Double = 0.0,
    val isfCos24: Double = 0.0,
    val isfStateLogOffset: Double = 0.0,
    val factors: List<FactorEvidenceV1> = emptyList(),
    val variance: VarianceLedgerV1,
    val macroTiming: MacroTimingParamsV1 = MacroTimingParamsV1(),
    val bounds: PhysioBoundsV1 = PhysioBoundsV1(),
    val promotedModifiers: List<AppliedModifierV1> = emptyList(),
    val conflictFlags: List<String> = emptyList(),
    /** Null keeps the four coefficients zeroed, which is the shipped behaviour. */
    val backgroundCoefficients: BackgroundCoefficientsV1? = null,
    /**
     * The user pinned ISF by hand, so no learner may scale it.
     *
     * Decided after a wiring audit measured the user's hand-set value
     * reaching the model scaled by a promoted effect: `closed_episode_global_isf` was
     * multiplying the manual value by its promoted effect. The mechanism was
     * working as designed; the design was wrong about whose number wins.
     */
    val isfPinnedByHand: Boolean = false,
    /**
     * What the learner WOULD apply, carried even while the pin stands.
     *
     * Kept so the alternative is visible rather than merely disabled — a
     * suppressed learner that cannot be inspected is how one ends up trusting a
     * pinned value long after the body has moved. Null means the learner has
     * nothing to say (no promotion, no support).
     */
    val learnedIsfAlternative: PosteriorV1? = null,
) {
    init {
        require(artifactId.isNotBlank())
        require(globalCs.p10 >= bounds.globalCsMmolPerLGMin && globalCs.p90 <= bounds.globalCsMmolPerLGMax) {
            "global CS posterior must remain inside physiological bounds (mmol/L/g)"
        }
        require(globalIsf.p10 >= bounds.isfMmolPerLUmin && globalIsf.p90 <= bounds.isfMmolPerLUmax) {
            "ISF posterior must remain inside physiological bounds (mmol/L/U)"
        }
        require(baseMechanics.insulin.onsetMin in bounds.insulinOnsetMinRange)
        require(baseMechanics.insulin.peakMin in bounds.insulinPeakMinRange)
        require(baseMechanics.insulin.tailDurationMin in bounds.insulinTailMinRange)
        require(baseMechanics.food.defaultShape.delayMin in bounds.absorptionOnsetMinRange)
        require(baseMechanics.food.defaultShape.peakMin in bounds.absorptionPeakMinRange)
        require(baseMechanics.food.defaultShape.durationMin in bounds.absorptionTailMinRange)
    }

    fun effectiveIsf(hour: Double): Double {
        val supportedOffset = if (globalIsf.identifyingEpisodes > 0) isfStateLogOffset else 0.0
        return exp(kotlin.math.ln(globalIsf.median) + isfSin24 * sin(2 * PI * hour / 24) + isfCos24 * cos(2 * PI * hour / 24) + supportedOffset)
            .coerceIn(globalIsf.p10, globalIsf.p90)
    }

    /** Context posterior width applies only while its registered exposure is active. */
    fun varianceAt(activeContextIds:Set<String>):VarianceLedgerV1 = conditionalVarianceV1(variance,promotedModifiers,activeContextIds)

    /** Reuses production timing mechanics; amplitude policy is selected explicitly by the engine. */
    fun personModelAt(hour: Double, activeContextIds:Set<String> = emptySet()): HybridPersonModel {
        val active=promotedModifiers.filter{it.hypothesisId in activeContextIds}
        fun fractions(target:ParameterTargetV1)=active.filter{it.effect.target==target}.sumOf{it.effect.median}
        val isf = (effectiveIsf(hour)*(1.0+fractions(ParameterTargetV1.ISF_MULTIPLIER))).coerceIn(bounds.isfMmolPerLUmin,bounds.isfMmolPerLUmax)
        val f = baseMechanics.food
        return baseMechanics.copy(
            modelVersion = "PHYSIO_V1:${artifactId}",
            personModelId = "physio:${artifactId}",
            insulin = baseMechanics.insulin.copy(isf = isf, isfLow = minOf(globalIsf.p10,isf), isfHigh = maxOf(globalIsf.p90,isf)),
            food = f.copy(
                globalFactor = globalCs.median,
            ),
            // Legacy activity/sleep coefficients were fitted as part of a
            // different factorization. They cannot silently enter PHYSIO's
            // median before their versioned promotion gates pass.
            activity = baseMechanics.activity.copy(iobGamma = 0.0, foodGamma = 0.0),
            joint = baseMechanics.joint.copy(coefficients = baseMechanics.joint.coefficients.copy(
                // activityDirect and sleepDebt stay zeroed — not measured here.
                activityDirect = 0.0, sleepDebt = 0.0,
                sleep = backgroundCoefficients?.sleepMmolPerHour ?: 0.0,
                hoursSinceWake = backgroundCoefficients?.hoursSinceWakeMmolPerHourPerHour ?: 0.0,
            )),
            // PHYSIO intervals are generated from the typed variance ledger
            // below. Retaining legacy corridor terms would double-count food,
            // process and ISF uncertainty.
            uncertainty = baseMechanics.uncertainty.copy(
                sigmaPerSqrtHour = 0.0,
                foodFraction = 0.0,
                unknownFoodExtraFraction = 0.0,
                includeActiveBolusIsf = false,
            ),
        )
    }

    private fun active(ids:Set<String>)=promotedModifiers.filter{it.hypothesisId in ids}
    fun decorateBolus(event:HybridBolusEvent,ids:Set<String>):HybridBolusEvent {
        val a=active(ids)
        fun minutes(t:ParameterTargetV1)=a.filter{it.effect.target==t}.sumOf{it.effect.median}
        fun fraction(t:ParameterTargetV1)=a.filter{it.effect.target==t}.sumOf{it.effect.median}
        return event.copy(contextIds=ids,onsetOffsetMin=minutes(ParameterTargetV1.INSULIN_ONSET).coerceIn(-30.0,30.0),
            peakOffsetMin=minutes(ParameterTargetV1.INSULIN_PEAK).coerceIn(-60.0,60.0),tailOffsetMin=minutes(ParameterTargetV1.INSULIN_TAIL).coerceIn(-120.0,120.0),
            potencyMultiplier=(1+fraction(ParameterTargetV1.INSULIN_POTENCY)).coerceIn(.5,1.5),processVarianceFraction=event.processVarianceFraction)
    }
    fun decorateFood(event:HybridFoodEvent,ids:Set<String>):HybridFoodEvent {
        val a=active(ids)
        fun minutes(t:ParameterTargetV1)=a.filter{it.effect.target==t}.sumOf{it.effect.median}
        val background=a.filter{it.effect.target==ParameterTargetV1.BACKGROUND_RATE}.sumOf{it.effect.median}.coerceIn(-1.0,1.0)
        return event.copy(contextIds=ids,onsetOffsetMin=minutes(ParameterTargetV1.FOOD_ONSET).coerceIn(-60.0,60.0),
            peakOffsetMin=minutes(ParameterTargetV1.FOOD_PEAK).coerceIn(-120.0,120.0),tailOffsetMin=minutes(ParameterTargetV1.FOOD_TAIL).coerceIn(-240.0,240.0),backgroundRateOffsetMmolPerHour=background)
    }
    fun decorateBasal(event:HybridBasalEvent,ids:Set<String>):HybridBasalEvent {
        val fraction=active(ids).filter{it.effect.target==ParameterTargetV1.BASAL_MISMATCH}.sumOf{it.effect.median}
        return event.copy(contextIds=ids,actionMultiplier=(1+fraction).coerceIn(.5,1.5))
    }
    fun decorateState(state:HybridForecastState,anchorIds:Set<String>):HybridForecastState {
        val a=active(anchorIds)
        val rate=a.filter{it.effect.target==ParameterTargetV1.BACKGROUND_RATE}.sumOf{it.effect.median}.coerceIn(-1.0,1.0)
        val sensorBias=a.filter{it.effect.target==ParameterTargetV1.SENSOR_BIAS}.sumOf{it.effect.median}.coerceIn(-2.0,2.0)
        val sensorSigma=a.filter{it.effect.target==ParameterTargetV1.SENSOR_PROCESS_VARIANCE}.sumOf{abs(it.effect.median)}.coerceIn(0.0,1.0)
        return state.copy(contextFlags=anchorIds,backgroundRateOffsetMmolPerHour=rate,sensorBiasMmol=sensorBias,sensorProcessSigmaMmol=sensorSigma)
    }
}

data class AppliedModifierV1(val hypothesisId:String,val effect:EffectPayloadV1,val promotionRevision:Int,val promotedKnownAtMs:Long,val evaluationHash:String){
    init {
        val definition=PhysioHypothesisRegistryV1.definitions.firstOrNull{it.id==hypothesisId}
            ?: error("unknown runtime hypothesis $hypothesisId")
        require(definition.executableSpec.parameterTarget==effect.target){"typed effect ${effect.target} cannot be applied to ${definition.executableSpec.parameterTarget}"}
        require(promotionRevision>0&&promotedKnownAtMs>=0&&evaluationHash.isNotBlank())
    }
}

class PhysioForecastEngine(private val artifact: PhysioArtifactV1) {
    fun intervalContributions(point: HybridForecastPoint, macroExtra: Double = 0.0, activeContextIds:Set<String> = emptySet()): IntervalVarianceContributionsV1 {
        return intervalContributionsV1(point, artifact.varianceAt(activeContextIds), macroExtra)
    }

    fun forecast(state: HybridForecastState, hypotheticalInsulinUnits: Double = 0.0): HybridForecastResult {
        val decorated=artifact.decorateState(state.copy(
            foodHistory=state.foodHistory.map{artifact.decorateFood(it,it.contextIds)},
            bolusHistory=state.bolusHistory.map{artifact.decorateBolus(it,it.contextIds)},
            basalHistory=state.basalHistory.map{artifact.decorateBasal(it,it.contextIds)},
        ),state.contextFlags)
        val hour = java.time.Instant.ofEpochMilli(state.nowMs).atZone(java.time.ZoneOffset.UTC).hour.toDouble()
        val median = HybridForecastEngine(
            artifact.personModelAt(hour,state.contextFlags),
            artifact.macroTiming,
        ).forecast(decorated, hypotheticalInsulinUnits)
        val macroExtra = macroTimingUncertaintyExtraV1(decorated.foodHistory, artifact.macroTiming.promoted)
        val activeOwnerIds = state.contextFlags + decorated.foodHistory.flatMap { it.contextIds } +
            decorated.bolusHistory.flatMap { it.contextIds } + decorated.basalHistory.flatMap { it.contextIds }
        return median.copy(points = median.points.map { point ->
            val baseWidth = intervalContributions(point, macroExtra,activeOwnerIds).halfWidth()
            val width=sqrt(baseWidth*baseWidth+decorated.sensorProcessSigmaMmol*decorated.sensorProcessSigmaMmol)
            point.copy(low = point.scenario - width, high = point.scenario + width)
        })
    }
}

data class CausalSnapshotV1(val state: HybridForecastState, val evidenceRevisionIds: List<String>) {
    fun canonicalHash(): String {
        val body = buildString {
            append(state.nowMs).append('|')
            state.glucoseHistory.forEach { append(it.tsMs).append(':').append(it.mmol).append(';') }
            state.foodHistory.forEach { append(it.tsMs).append(':').append(it.carbsG).append(':').append(it.durationMin).append(':').append(it.proteinG).append(':').append(it.fatG).append(':').append(it.macroProvenance).append(':').append(it.recipeKey).append(':').append(it.physicalForm).append(':').append(it.carbClass).append(':').append(it.contextIds.sorted()).append(';') }
            state.bolusHistory.forEach { append(it.tsMs).append(':').append(it.units).append(':').append(it.contextIds.sorted()).append(';') }
            state.basalHistory.forEach { append(it.tsMs).append(':').append(it.units).append(':').append(it.contextIds.sorted()).append(';') }
            evidenceRevisionIds.sorted().forEach { append(it).append(';') }
            state.contextFlags.sorted().forEach { append("ctx:").append(it).append(';') }
        }
        return MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class ParallelForecastLedgerRowV1(
    val runId: String, val anchorMs: Long, val engine: String, val artifactId: String,
    val inputHash: String, val selectionAtRun: ForecastArmSelection, val createdAtMs: Long,
)
