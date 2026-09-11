package com.diapilot.core.physio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Versioned, deliberately broad physiological guard rails; values are in the documented units. */
data class PhysioBoundsV1(
    val version: String = "physio-bounds-v1",
    val globalCsMmolPerLGMin: Double = 0.03,
    val globalCsMmolPerLGMax: Double = 0.60,
    val globalCsMaxRelativeChangePerDay: Double = 0.05,
    val isfMmolPerLUmin: Double = 0.50,
    val isfMmolPerLUmax: Double = 8.00,
    val isfMaxRelativeChangePerIdentifyingDay: Double = 0.15,
    val insulinOnsetMinRange: ClosedFloatingPointRange<Double> = 0.0..60.0,
    val insulinPeakMinRange: ClosedFloatingPointRange<Double> = 20.0..180.0,
    val insulinTailMinRange: ClosedFloatingPointRange<Double> = 120.0..600.0,
    val absorptionOnsetMinRange: ClosedFloatingPointRange<Double> = 0.0..120.0,
    val absorptionPeakMinRange: ClosedFloatingPointRange<Double> = 10.0..240.0,
    val absorptionTailMinRange: ClosedFloatingPointRange<Double> = 30.0..720.0,
) {
    init {
        require(version.isNotBlank())
        require(globalCsMmolPerLGMin > 0 && globalCsMmolPerLGMax > globalCsMmolPerLGMin)
        require(isfMmolPerLUmin > 0 && isfMmolPerLUmax > isfMmolPerLUmin)
        require(globalCsMaxRelativeChangePerDay in 0.0..0.20)
        require(isfMaxRelativeChangePerIdentifyingDay in 0.0..0.30)
    }

    fun boundedIsf(previous: Double, proposed: Double, identifyingDaysElapsed: Int): Double {
        val rate = isfMaxRelativeChangePerIdentifyingDay * identifyingDaysElapsed.coerceAtLeast(1)
        return proposed.coerceIn(previous * (1.0 - rate), previous * (1.0 + rate))
            .coerceIn(isfMmolPerLUmin, isfMmolPerLUmax)
    }
}

enum class ImplementationStatusV1 { EXECUTABLE, MEASUREMENT_PATH_MISSING }
enum class EstimatorFamilyV1 { STATE_ISF, TIMING_KERNEL, BACKGROUND, SENSOR_QUALITY }
enum class ParameterTargetV1(val unit:String,val displayReference:Double) {
    GLOBAL_CS_SCALE("fraction",1.0),
    ISF_MULTIPLIER("fraction",1.0),
    EFFECTIVE_RESPONSE_ONLY("fraction",1.0),
    INSULIN_ONSET("minute",30.0), INSULIN_PEAK("minute",60.0), INSULIN_TAIL("minute",180.0), INSULIN_POTENCY("fraction",1.0),
    FOOD_ONSET("minute",60.0), FOOD_PEAK("minute",120.0), FOOD_TAIL("minute",240.0),
    BACKGROUND_RATE("mmol/L/hour",.5), BASAL_MISMATCH("fraction",1.0),
    SENSOR_BIAS("mmol/L",1.0), SENSOR_PROCESS_VARIANCE("fraction",1.0),
}
/**
 * The two background coefficients PHYSIO zeroes, governed as a BASE parameter.
 *
 * WHY IT IS NOT A MODIFIER HYPOTHESIS. `personModelAt` zeroes `activityDirect`,
 * `sleep`, `sleepDebt` and `hoursSinceWake` because they were fitted inside a
 * different factorization. Two of them have now been measured on this person's
 * own quiet segments, and what they would replace is not «no effect» but an
 * older estimate of the SAME quantity — the shipped asset's own coefficients.
 * That is exactly the frame of [ApplicationPolicyV1.MEASURED_BASE] and of the
 * decision settled for it.
 *
 * WHY IT CARRIES NO TYPED PERCENT EFFECT. The promotion machinery expresses an
 * effect as a percentage of `ParameterTargetV1.displayReference`. For
 * BACKGROUND_RATE that reference is 0.5 mmol/L/h, so the measured
 * `sleep = -0.4445` would read as -88.9% against caps that run 25-35%. And
 * `EffectPayloadV1` carries ONE target, while this is a PAIR whose members are
 * collinear — measured directly, adding `hoursSinceWake` moves `sleep` from
 * -0.3714 to -0.5064. Promoting one without the other is the double count the
 * pair exists to avoid. So the coefficients travel as
 * [BackgroundCoefficientsV1] on the artifact, atomically, and this contract
 * governs only the lifecycle around them.
 *
 * DECAY IS A CLIFF, NOT A FADE — decided by the user. Fading
 * `sleep` toward zero returns the model to the known-wrong zeroed state, which
 * is the failure mode already documented for the ISF path (2.40 -> 2.29 with
 * the corrections unchanged). So [agedEffect] is never applied here; instead
 * the evidence goes stale as a whole and [demoteIfStale] drops the pair, and a
 * refit publishes a NEW `evidenceIdentity` rather than propping up an old one.
 *
 * RAMP-IN IS INERT ON PURPOSE. The pair is collinear, so a partially applied
 * pair is not a smaller correction — it is a different and worse one than
 * either endpoint. `rampInDays = 1`.
 *
 * THE EXPOSURE PREDICATE BELOW IS STRUCTURALLY UNREACHABLE and therefore inert:
 * predicates are evaluated only for rows of [PhysioHypothesisRegistryV1], and
 * this contract — like `global_cs` and `closed_episode_global_isf` — is not one.
 * It is NOT a claim that short sleep is the cause. The cause is NOT established:
 * on 366 quiet segments the deficit shows while asleep AND while awake early in
 * the basal cycle, and sleep cannot be separated from basal phase on data where
 * every basal dose falls inside a 96-minute window and daytime sleep never
 * occurs.
 */
object ZeroedBackgroundCoefficientsContractV1 {
    const val ID = "zeroed_background_coefficients"
    val definition = HypothesisDefinitionV1(
        ID, "basal/background/hepatic", "background coefficients PHYSIO zeroes",
        "background", "sleep and hours-since-wake measured on the user's own quiet segments", 10.0,
    )
    val spec: ExecutableHypothesisSpecV1 get() = definition.executableSpec

    /**
     * Promotion additionally REQUIRES the hypo-alert shift to be measured and
     * recorded in the same revision.
     *
     * Not a veto and not a block: the user lifted the alert veto,
     * and that reversed the ORDER, not the burden — the model is
     * corrected first and the alarms are refitted after. What stands is the
     * obligation to MEASURE what moved and say so. The background moves EVERY
     * forecast, the alert path included, so a revision that cannot name its
     * alert shift has not finished.
     */
    const val ALERT_SHIFT_EVIDENCE_KEY = "hypo_alert_shift_min"

    /**
     * THE SEGMENT GATES ARE IN-SAMPLE, SO THEY CANNOT BE THE WHOLE GATE.
     *
     * `minimumIndependentEpisodes = 100` and `minimumIndependentDays = 20` are
     * passed by the very 366 segments the pair was fitted on. That is the same
     * ring the SSE table had — a constrained fit judged on its own data — only
     * here it would govern APPLICATION. Left alone it would let the pair go live
     * on the first pass, before its pre-registered prediction had been tested
     * even once, which makes the pre-registration decorative.
     *
     * So promotion additionally requires [MINIMUM_PROSPECTIVE_DAYS] days of the
     * arm's OWN paired ledger, collected AFTER the coefficients were fixed, and
     * a verdict from [prospectiveVerdict] against the numbers pre-registered
     * below. The segment gates stay: they are about sample size, not about
     * whether the prediction held.
     *
     * AND THIS IS ALSO WHAT REPLACES THE RAMP. `rampInDays = 1` is deliberate —
     * a partly applied collinear pair is a different and worse correction, not a
     * smaller one — but an inert ramp gives up the property that a bad effect
     * acts weakly while the ledger watches it. A full-strength SHADOW arm
     * accumulated before promotion is the substitute. The two decisions are one
     * decision; do not relax either alone.
     *
     * WHY FOURTEEN DAYS. The per-day quiet differential measured directly has
     * sd ≈ 0.33 mmol (nine days, excluding one outlier day whose n = 6 and whose
     * differential was −6.30). A median over fourteen days therefore carries
     * SE ≈ 1.25·0.33/√14 ≈ 0.11, which separates the predicted +0.08 from either
     * falsification band by about three standard errors. Days with fewer than
     * [MINIMUM_PAIRED_ANCHORS_PER_DAY] paired anchors do not count — the same
     * measurement showed days of n=2 carrying equal weight in a nine-day median.
     */
    const val MINIMUM_PROSPECTIVE_DAYS = 14
    const val MINIMUM_PAIRED_ANCHORS_PER_DAY = 20

    /** Pre-registered BEFORE the arm was built or run. Differential
     *  is PHYSIO−LEGACY at h=180, median over days, windows clean forward. */
    const val PREDICTED_QUIET_DIFFERENTIAL = 0.082
    const val PREDICTED_FOOD_DIFFERENTIAL = -0.790
    const val PREDICTED_INSULIN_DIFFERENTIAL = 0.535

    /** Declared in advance: above this the pair OVER-corrected, and that is a
     *  falsification rather than «almost». */
    const val QUIET_OVERCORRECTION_BAND = 0.40
    /** Below this the pair under-corrected and the mechanism is not the one
     *  claimed. */
    const val QUIET_UNDERCORRECTION_BAND = -0.30

    enum class ProspectiveVerdictV1 { INSUFFICIENT, CONFIRMED, OVERCORRECTED, UNDERCORRECTED }

    /**
     * The pre-registered test, as code so it cannot be re-read generously later.
     * Input is one quiet PHYSIO−LEGACY differential per day, already restricted
     * to days carrying [MINIMUM_PAIRED_ANCHORS_PER_DAY] paired anchors.
     */
    fun prospectiveVerdict(quietDifferentialPerDay: List<Double>): ProspectiveVerdictV1 {
        if (quietDifferentialPerDay.size < MINIMUM_PROSPECTIVE_DAYS) return ProspectiveVerdictV1.INSUFFICIENT
        val sorted = quietDifferentialPerDay.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        return when {
            median > QUIET_OVERCORRECTION_BAND -> ProspectiveVerdictV1.OVERCORRECTED
            median < QUIET_UNDERCORRECTION_BAND -> ProspectiveVerdictV1.UNDERCORRECTED
            else -> ProspectiveVerdictV1.CONFIRMED
        }
    }

    /**
     * The source-evidence hash the promotion ledger must key on.
     *
     * It carries the SEGMENT SET, not just the fitted numbers, so a refit on new
     * quiet segments is NEW evidence rather than an old one propped up. Without
     * the set in the key the pair would look unchanged to the ledger while its
     * support silently aged out from under it.
     */
    fun sourceEvidenceIdentity(pair: BackgroundCoefficientsV1): String = listOf(
        "zbc", pair.evidenceIdentity,
        String.format(java.util.Locale.ROOT, "%.6f", pair.sleepMmolPerHour),
        String.format(java.util.Locale.ROOT, "%.6f", pair.hoursSinceWakeMmolPerHourPerHour),
    ).joinToString(":")

    /**
     * The reason a stale cliff MUST record, verbatim and greppable.
     *
     * Dropping the pair returns the model to the zeroed state we currently
     * believe is wrong, so the cliff cannot be quiet: it goes to the ledger with
     * this string, verbatim. «The model was better yesterday» with no
     * explanation is the worst kind of regression.
     */
    const val STALE_CLIFF_REASON =
        "measured background coefficients dropped whole: evidence stale beyond three half-lives; " +
            "PHYSIO returns to the zeroed coefficients, which direct measurement says are wrong"
}

enum class ApplicationPolicyV1 {
    MEDIAN, UNCERTAINTY_ONLY, ATTRIBUTION_ONLY,

    /**
     * A direct measurement of a BASE parameter, not a modifier hypothesis.
     *
     * The distinction is the one [PromotionLifecycleV1] was missing. Every other
     * contract here proposes an EFFECT — «sleep lowers ISF by 8%» — and for those
     * the mandatory shadow cycle is exactly right: the association is unproven,
     * the alternative is «no effect», and a prospective loss gate settles it.
     *
     * `closed_episode_global_isf` is not that. It measures ISF itself, from the
     * user's own closed corrections, and the thing it would replace is not
     * «no effect» but an older estimate of the SAME quantity with worse
     * provenance. Measured directly: seven observation-proven corrections on
     * one calibration say 2.55, while the incumbent came from a corpus the
     * project's own rule forbids for sensitivity (pre-food-era episodes, where
     * the «no logged carbs» gate passes vacuously). Making the measurement prove
     * itself against that is discipline #8 applied in one direction only.
     *
     * So this policy applies once the SUPPORT gates pass — six independent
     * episodes over three days, scatter within bounds — and keeps every other
     * safeguard: the effect is capped at `maxMedianEffectPercent`, ramped over
     * `rampInDays`, decayed, and revoked the moment its evidence is withdrawn.
     * It is a shorter road, not an unguarded one.
     */
    MEASURED_BASE,
}

sealed interface EffectPayloadV1 {
    val target:ParameterTargetV1
    val median:Double
    val low:Double
    val high:Double
    data class Fraction(override val target:ParameterTargetV1,override val median:Double,override val low:Double,override val high:Double):EffectPayloadV1 {
        init { require(target.unit=="fraction" && low<=median&&median<=high) }
    }
    data class Minutes(override val target:ParameterTargetV1,override val median:Double,override val low:Double,override val high:Double):EffectPayloadV1 {
        init { require(target.unit=="minute" && low<=median&&median<=high) }
    }
    data class Rate(override val target:ParameterTargetV1,override val median:Double,override val low:Double,override val high:Double):EffectPayloadV1 {
        init { require(target.unit=="mmol/L/hour" && low<=median&&median<=high) }
    }
    data class Bias(override val target:ParameterTargetV1,override val median:Double,override val low:Double,override val high:Double):EffectPayloadV1 {
        init { require(target.unit=="mmol/L" && low<=median&&median<=high) }
    }
}

fun EffectPayloadV1.scaled(scale:Double):EffectPayloadV1=when(this){
    is EffectPayloadV1.Fraction->copy(median=median*scale,low=low*scale,high=high*scale)
    is EffectPayloadV1.Minutes->copy(median=median*scale,low=low*scale,high=high*scale)
    is EffectPayloadV1.Rate->copy(median=median*scale,low=low*scale,high=high*scale)
    is EffectPayloadV1.Bias->copy(median=median*scale,low=low*scale,high=high*scale)
}
enum class SourceFieldV1 {
    STEPS, SLEEP_SESSION, CGM, BOLUS, BASAL, FOOD_MACROS, INTAKE_DURATION, CARB_EVIDENCE,
    ALCOHOL_EVIDENCE, SENSOR_SOURCE, INSULIN_PRODUCT, INJECTION_SITE, CARTRIDGE_EVENT,
    STRESS_ILLNESS, WEATHER_EXPOSURE,
}
enum class KnownAtRuleV1 { EVENT_KNOWN_AT, ANNOTATION_ANALYSIS_KNOWN_AT, EVIDENCE_REVISION_KNOWN_AT, COLLECTION_TIME }
enum class ExposurePredicateV1 {
    SAME_DAY_ACTIVITY, PREVIOUS_DAY_ACTIVITY, SHORT_SLEEP, HYPO_0_4H, HYPO_4_12H, PREVIOUS_NIGHT_HYPO,
    HIGH_STARTING_GLUCOSE, LARGE_BOLUS, TIME_OF_DAY, HIGH_PROTEIN, HIGH_FAT, LONG_INTAKE,
    ALCOHOL_PRESENT, BASAL_AGE_MISMATCH, SENSOR_EPOCH_CHANGE, SENSOR_ANOMALY, PRODUCT_CHANGE, INJECTION_SITE_CHANGE,
    CARTRIDGE_AGE_OR_CHANGE, STRESS_OR_ILLNESS, HEAT_EXPOSURE_WITH_CARTRIDGE_CONTEXT,
    /**
     * Every logged meal, with whatever else was on board.
     *
     * Deliberately the widest predicate here, and it is what distinguishes
     * [EpisodeAmplitudeContractV1] from `global_cs`: that one needs an isolated
     * dish and has never had enough of them, this one takes the episode as it
     * happened. A permissive exposure is only safe because the verdict comes
     * from the prospective gate rather than from the corpus.
     */
    ANY_MEAL,
}

fun executableHypothesisSpecV1(id: String, target: String, practical: Double): ExecutableHypothesisSpecV1 {
    val commonExclusions = setOf("future knownAt", "sensor gap", "overlapping unmodelled event")
    val route = when (id) {
        "global_cs" -> listOf(SourceFieldV1.CARB_EVIDENCE,SourceFieldV1.CGM,SourceFieldV1.BOLUS,SourceFieldV1.BASAL) to ExposurePredicateV1.HIGH_PROTEIN to EstimatorFamilyV1.BACKGROUND to KnownAtRuleV1.EVIDENCE_REVISION_KNOWN_AT
        // The second amplitude route. Same source fields as `global_cs` — it is
        // the same parameter — but the exposure is EVERY logged meal rather than
        // an isolated one, which is the whole difference between the two.
        "activity_same_isf", "activity_same_effective" -> listOf(SourceFieldV1.STEPS, SourceFieldV1.CGM, SourceFieldV1.BOLUS) to ExposurePredicateV1.SAME_DAY_ACTIVITY to EstimatorFamilyV1.STATE_ISF to KnownAtRuleV1.COLLECTION_TIME
        "activity_previous_isf", "activity_previous_effective" -> listOf(SourceFieldV1.STEPS, SourceFieldV1.CGM, SourceFieldV1.BOLUS) to ExposurePredicateV1.PREVIOUS_DAY_ACTIVITY to EstimatorFamilyV1.STATE_ISF to KnownAtRuleV1.COLLECTION_TIME
        "sleep_isf", "sleep_background" -> listOf(SourceFieldV1.SLEEP_SESSION, SourceFieldV1.CGM) to ExposurePredicateV1.SHORT_SLEEP to (if (id.endsWith("background")) EstimatorFamilyV1.BACKGROUND else EstimatorFamilyV1.STATE_ISF) to KnownAtRuleV1.COLLECTION_TIME
        // Inert filler predicate — see the contract's own note. This row is not
        // in the registry, so nothing ever evaluates it.
        ZeroedBackgroundCoefficientsContractV1.ID -> listOf(SourceFieldV1.CGM,SourceFieldV1.SLEEP_SESSION,SourceFieldV1.BASAL,SourceFieldV1.BOLUS) to ExposurePredicateV1.SHORT_SLEEP to EstimatorFamilyV1.BACKGROUND to KnownAtRuleV1.COLLECTION_TIME
        "hypo_0_4_isf", "hypo_0_4_effective" -> listOf(SourceFieldV1.CGM) to ExposurePredicateV1.HYPO_0_4H to EstimatorFamilyV1.STATE_ISF to KnownAtRuleV1.COLLECTION_TIME
        "hypo_4_12_isf", "hypo_4_12_effective" -> listOf(SourceFieldV1.CGM) to ExposurePredicateV1.HYPO_4_12H to EstimatorFamilyV1.STATE_ISF to KnownAtRuleV1.COLLECTION_TIME
        "hypo_night_isf", "hypo_night_effective", "hepatic_hypo" -> listOf(SourceFieldV1.CGM) to ExposurePredicateV1.PREVIOUS_NIGHT_HYPO to (if (id=="hepatic_hypo") EstimatorFamilyV1.BACKGROUND else EstimatorFamilyV1.STATE_ISF) to KnownAtRuleV1.COLLECTION_TIME
        "glucose_level_isf" -> listOf(SourceFieldV1.CGM,SourceFieldV1.BOLUS) to ExposurePredicateV1.HIGH_STARTING_GLUCOSE to EstimatorFamilyV1.STATE_ISF to KnownAtRuleV1.COLLECTION_TIME
        "dose_tail" -> listOf(SourceFieldV1.BOLUS,SourceFieldV1.CGM) to ExposurePredicateV1.LARGE_BOLUS to EstimatorFamilyV1.TIMING_KERNEL to KnownAtRuleV1.EVENT_KNOWN_AT
        "tod_peak","tod_onset","tod_tail" -> listOf(SourceFieldV1.BOLUS,SourceFieldV1.CGM) to ExposurePredicateV1.TIME_OF_DAY to EstimatorFamilyV1.TIMING_KERNEL to KnownAtRuleV1.EVENT_KNOWN_AT
        "insulin_product_kinetics" -> listOf(SourceFieldV1.INSULIN_PRODUCT,SourceFieldV1.CGM,SourceFieldV1.BOLUS) to ExposurePredicateV1.PRODUCT_CHANGE to EstimatorFamilyV1.TIMING_KERNEL to KnownAtRuleV1.EVENT_KNOWN_AT
        "protein_absorption" -> listOf(SourceFieldV1.FOOD_MACROS,SourceFieldV1.CGM) to ExposurePredicateV1.HIGH_PROTEIN to EstimatorFamilyV1.TIMING_KERNEL to KnownAtRuleV1.ANNOTATION_ANALYSIS_KNOWN_AT
        "fat_absorption" -> listOf(SourceFieldV1.FOOD_MACROS,SourceFieldV1.CGM) to ExposurePredicateV1.HIGH_FAT to EstimatorFamilyV1.TIMING_KERNEL to KnownAtRuleV1.ANNOTATION_ANALYSIS_KNOWN_AT
        "duration_absorption" -> listOf(SourceFieldV1.INTAKE_DURATION,SourceFieldV1.CGM) to ExposurePredicateV1.LONG_INTAKE to EstimatorFamilyV1.TIMING_KERNEL to KnownAtRuleV1.EVIDENCE_REVISION_KNOWN_AT
        "alcohol_background" -> listOf(SourceFieldV1.ALCOHOL_EVIDENCE,SourceFieldV1.CGM) to ExposurePredicateV1.ALCOHOL_PRESENT to EstimatorFamilyV1.BACKGROUND to KnownAtRuleV1.EVIDENCE_REVISION_KNOWN_AT
        "basal_background" -> listOf(SourceFieldV1.BASAL,SourceFieldV1.INSULIN_PRODUCT,SourceFieldV1.CGM) to ExposurePredicateV1.BASAL_AGE_MISMATCH to EstimatorFamilyV1.BACKGROUND to KnownAtRuleV1.EVENT_KNOWN_AT
        "sensor_epoch","sensor_bias" -> listOf(SourceFieldV1.SENSOR_SOURCE,SourceFieldV1.CGM) to ExposurePredicateV1.SENSOR_EPOCH_CHANGE to EstimatorFamilyV1.SENSOR_QUALITY to KnownAtRuleV1.COLLECTION_TIME
        "sensor_compression_lag" -> listOf(SourceFieldV1.CGM) to ExposurePredicateV1.SENSOR_ANOMALY to EstimatorFamilyV1.SENSOR_QUALITY to KnownAtRuleV1.COLLECTION_TIME
        "cartridge_kinetics" -> listOf(SourceFieldV1.CARTRIDGE_EVENT,SourceFieldV1.CGM,SourceFieldV1.BOLUS) to ExposurePredicateV1.CARTRIDGE_AGE_OR_CHANGE to EstimatorFamilyV1.TIMING_KERNEL to KnownAtRuleV1.EVENT_KNOWN_AT
        "injection_site" -> listOf(SourceFieldV1.INJECTION_SITE,SourceFieldV1.CGM,SourceFieldV1.BOLUS) to ExposurePredicateV1.INJECTION_SITE_CHANGE to EstimatorFamilyV1.TIMING_KERNEL to KnownAtRuleV1.EVENT_KNOWN_AT
        "cartridge_heat" -> listOf(SourceFieldV1.WEATHER_EXPOSURE,SourceFieldV1.CARTRIDGE_EVENT,SourceFieldV1.CGM) to ExposurePredicateV1.HEAT_EXPOSURE_WITH_CARTRIDGE_CONTEXT to EstimatorFamilyV1.TIMING_KERNEL to KnownAtRuleV1.EVENT_KNOWN_AT
        "stress_illness_background" -> listOf(SourceFieldV1.STRESS_ILLNESS,SourceFieldV1.CGM) to ExposurePredicateV1.STRESS_OR_ILLNESS to EstimatorFamilyV1.BACKGROUND to KnownAtRuleV1.EVENT_KNOWN_AT
        else -> error("Every preregistered hypothesis must have an executable specification: $id")
    }
    val sources = route.first.first.first
    val predicate = route.first.first.second
    val family = route.first.second
    val knownAt = route.second
    val parameterTarget=when(id){
        "global_cs"->ParameterTargetV1.GLOBAL_CS_SCALE
        "activity_same_isf","activity_previous_isf","sleep_isf","hypo_0_4_isf","hypo_4_12_isf","hypo_night_isf","glucose_level_isf"->ParameterTargetV1.ISF_MULTIPLIER
        "activity_same_effective","activity_previous_effective","hypo_0_4_effective","hypo_4_12_effective","hypo_night_effective"->ParameterTargetV1.EFFECTIVE_RESPONSE_ONLY
        "dose_tail"->ParameterTargetV1.INSULIN_TAIL
        "tod_peak"->ParameterTargetV1.INSULIN_PEAK
        "tod_onset"->ParameterTargetV1.INSULIN_ONSET
        "tod_tail"->ParameterTargetV1.INSULIN_TAIL
        "insulin_product_kinetics"->ParameterTargetV1.INSULIN_POTENCY
        "protein_absorption"->ParameterTargetV1.FOOD_TAIL
        "fat_absorption"->ParameterTargetV1.FOOD_PEAK
        "duration_absorption"->ParameterTargetV1.FOOD_ONSET
        "basal_background"->ParameterTargetV1.BASAL_MISMATCH
        "sensor_epoch","sensor_compression_lag"->ParameterTargetV1.SENSOR_PROCESS_VARIANCE
        "sensor_bias"->ParameterTargetV1.SENSOR_BIAS
        "cartridge_kinetics","injection_site","cartridge_heat"->ParameterTargetV1.INSULIN_POTENCY
        else->ParameterTargetV1.BACKGROUND_RATE
    }
    val policy=when {
        parameterTarget==ParameterTargetV1.EFFECTIVE_RESPONSE_ONLY->ApplicationPolicyV1.ATTRIBUTION_ONLY
        parameterTarget==ParameterTargetV1.SENSOR_PROCESS_VARIANCE->ApplicationPolicyV1.UNCERTAINTY_ONLY
        else->ApplicationPolicyV1.MEDIAN
    }
    val base=ExecutableHypothesisSpecV1(sources.toSet(), knownAt, predicate, family, target, parameterTarget, policy, commonExclusions,
        practicalBoundPercent=practical,implementationStatus=ImplementationStatusV1.EXECUTABLE)
    return if(id==ZeroedBackgroundCoefficientsContractV1.ID)base.copy(
        outcomeTarget="background coefficients measured on quiet segments",parameterTarget=ParameterTargetV1.BACKGROUND_RATE,
        applicationPolicy=ApplicationPolicyV1.MEASURED_BASE,
        // Segments and DAYS, because the danger here is the day-state confound:
        // twenty days is where one anomalous day can no longer move the median,
        // and a hundred non-overlapping 30-minute segments is ~50 hours of quiet
        // observation. Both far below what the corpus already holds (366/35) and
        // far above the generic 8/5 default, which would gate on nothing.
        minimumIndependentEpisodes=100,minimumIndependentDays=20,
        // Inert: there is no percent to cap or to ramp. decayHalfLifeDays feeds
        // `demoteIfStale`, whose cliff is three half-lives — thirty days here.
        maxMedianEffectPercent=10.0,decayHalfLifeDays=10.0,rampInDays=1,
    ) else base
}
enum class PromotionStageV1 {
    DESCRIPTIVE_ONLY, UNCERTAINTY_ELIGIBLE, SHADOW_CANDIDATE, PROSPECTIVE_STABLE,
    PROMOTED_MEDIAN, MONITORED, DEMOTED,
}

data class ExecutableHypothesisSpecV1(
    val sourceFields: Set<SourceFieldV1>,
    val knownAtRule: KnownAtRuleV1,
    val exposurePredicate: ExposurePredicateV1,
    val estimatorFamily: EstimatorFamilyV1,
    val outcomeTarget: String,
    val parameterTarget: ParameterTargetV1,
    val applicationPolicy: ApplicationPolicyV1,
    val exclusionRules: Set<String>,
    val minimumIndependentEpisodes: Int = 8,
    val minimumIndependentDays: Int = 5,
    val practicalBoundPercent: Double,
    val maxMedianEffectPercent: Double = practicalBoundPercent * 3.0,
    val decayHalfLifeDays: Double = 14.0,
    val rampInDays: Int = 7,
    val implementationStatus: ImplementationStatusV1 = ImplementationStatusV1.EXECUTABLE,
) {
    init {
        require(sourceFields.isNotEmpty() && outcomeTarget.isNotBlank())
        require(minimumIndependentEpisodes > 0 && minimumIndependentDays > 0)
        require(practicalBoundPercent > 0 && maxMedianEffectPercent >= practicalBoundPercent)
        require(decayHalfLifeDays > 0 && rampInDays > 0)
    }
}

data class PromotionStateV1(
    val hypothesisId: String,
    val revision: Int,
    val stage: PromotionStageV1,
    val boundedEffectPercent: Double,
    val reason: String,
    val knownAtMs: Long,
    val previousRevision: Int? = null,
    val effect: EffectPayloadV1? = null,
    val evaluationHash: String? = null,
    val sourceEvidenceHash: String? = null,
)

/** Pure append-only state transition. Forecast promotion remains stricter than descriptive support. */
object PromotionLifecycleV1 {
    fun next(
        spec: ExecutableHypothesisSpecV1,
        evidence: FactorEvidenceV1,
        prior: PromotionStateV1?,
        prospectiveLossImprovement: Double?,
        nowMs: Long,
    ): PromotionStateV1 {
        val revision = (prior?.revision ?: 0) + 1
        val conflict = evidence.status == EvidenceStatus.CONFLICTING
        val proposed = evidence.effectPercent.median.coerceIn(-spec.maxMedianEffectPercent, spec.maxMedianEffectPercent)
        val ramp = (evidence.effectPercent.independentDays.toDouble() / spec.rampInDays).coerceIn(0.0, 1.0)
        val bounded = proposed * ramp
        val (stage, reason) = when {
            spec.implementationStatus != ImplementationStatusV1.EXECUTABLE -> PromotionStageV1.DESCRIPTIVE_ONLY to "measurement path missing"
            spec.applicationPolicy == ApplicationPolicyV1.ATTRIBUTION_ONLY -> PromotionStageV1.DESCRIPTIVE_ONLY to "target is attribution-only"
            conflict -> PromotionStageV1.DEMOTED to "conflict/drift gate failed"
            evidence.status != EvidenceStatus.SUPPORTED -> PromotionStageV1.DESCRIPTIVE_ONLY to "support gates not passed"
            prior == null || prior.stage == PromotionStageV1.DESCRIPTIVE_ONLY -> PromotionStageV1.SHADOW_CANDIDATE to "supported association; mandatory shadow cycle"
            prospectiveLossImprovement == null -> PromotionStageV1.SHADOW_CANDIDATE to "supported association; prospective loss gate pending"
            prospectiveLossImprovement <= 0.0 -> PromotionStageV1.DEMOTED to "prospective loss did not improve"
            prior.stage !in setOf(PromotionStageV1.PROSPECTIVE_STABLE, PromotionStageV1.PROMOTED_MEDIAN, PromotionStageV1.MONITORED) ->
                PromotionStageV1.PROSPECTIVE_STABLE to "prospective loss/stability gate passed; one audit cycle required"
            else -> PromotionStageV1.PROMOTED_MEDIAN to "bounded ramp-in after repeated prospective gate"
        }
        return PromotionStateV1(evidence.dataSource, revision, stage, if (stage == PromotionStageV1.PROMOTED_MEDIAN) bounded else 0.0, reason, nowMs, prior?.revision)
    }

    fun agedEffect(state: PromotionStateV1, spec: ExecutableHypothesisSpecV1, asOfMs: Long): Double {
        if (state.stage !in setOf(PromotionStageV1.PROMOTED_MEDIAN, PromotionStageV1.MONITORED)) return 0.0
        val days = ((asOfMs - state.knownAtMs).coerceAtLeast(0L)).toDouble() / 86_400_000.0
        return state.boundedEffectPercent * exp(-ln(2.0) * days / spec.decayHalfLifeDays)
    }
}

data class SourceInventoryEntryV1(
    val source: SourceFieldV1,
    val available: Boolean,
    val rowCount: Int,
    val coverageFraction: Double,
    val knownAtSemantics: String,
    val path: String,
    val collectorImplemented: Boolean = true,
    val structuredIngestionImplemented: Boolean = true,
    val lastKnownAtMs: Long? = null,
) {
    init { require(rowCount >= 0 && coverageFraction in 0.0..1.0); if (!available) require(rowCount == 0) }
}

data class ResidualAllocationV1(
    val channel: String,
    val medianPoints: Double,
    val lowPoints: Double,
    val highPoints: Double,
    val attributionEligible: Boolean,
    val varianceContribution: Double,
) { init { require(lowPoints <= medianPoints && medianPoints <= highPoints); require(varianceContribution >= 0.0) } }

data class PhysioSanityReportV1(
    val csMin: Double,
    val csMax: Double,
    val isfMin: Double,
    val isfMax: Double,
    val maxCsDayChangeFraction: Double,
    val maxIsfDayChangeFraction: Double,
    val boundHits: Int,
    val modelDataConflicts: List<String>,
    val residualFractions: Map<String, Double>,
)

