package com.diapilot.core.hybrid

import com.diapilot.core.analysis.FoodKineticFeaturesV2

const val MINUTE_MS: Long = 60_000L

data class HybridBolusEvent(
    val tsMs: Long,
    val units: Double,
    /** Owner-event metadata: which promoted modifiers this event is under. */
    val contextIds: Set<String> = emptySet(),
    val onsetOffsetMin: Double = 0.0,
    val peakOffsetMin: Double = 0.0,
    val tailOffsetMin: Double = 0.0,
    val potencyMultiplier: Double = 1.0,
    val processVarianceFraction: Double = 0.0,
)

data class HybridBasalEvent(
    val tsMs: Long,
    val units: Double,
    val contextIds: Set<String> = emptySet(),
    val actionMultiplier: Double = 1.0,
    val processVarianceFraction: Double = 0.0,
)

data class HybridFoodEvent(
    val tsMs: Long,
    val carbsG: Double,
    val text: String = "",
    val components: Map<String, Double> = emptyMap(),
    /** Intake spread. Zero means the whole serving starts at [tsMs].
     * Beer over an hour is represented as many small causal starts rather
     * than forcing its physiology into an artificially slow food kernel. */
    val durationMin: Double = 0.0,
    /** Causally known meal-level macros. Null means unknown at the anchor. */
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val macroProvenance: String? = null,
    /** Structured, name-independent carb/form prior. */
    val kineticFeatures: FoodKineticFeaturesV2? = null,
    /** Explicit treatment semantics, not a linguistic food class. Rescue
     * dextrose has its own short causal curve and never joins a mixed meal. */
    val rescueTreatment: Boolean = false,
    /** Causal food-dynamics routing. A recipe key is a memory key, never an
     * amplitude coefficient. Physical/composition class is the fallback for
     * unseen meals. */
    val recipeKey: String? = null,
    val physicalForm: String? = null,
    val carbClass: String? = null,
    /** PHYSIO-only owner-event timing residuals. Intake duration remains a
     * separate input spread and is never encoded in these offsets. */
    val contextIds: Set<String> = emptySet(),
    val onsetOffsetMin: Double = 0.0,
    val peakOffsetMin: Double = 0.0,
    val tailOffsetMin: Double = 0.0,
    val timingVarianceFraction: Double = 0.0,
    val backgroundRateOffsetMmolPerHour: Double = 0.0,
)

data class HybridGlucosePoint(
    val tsMs: Long,
    val mmol: Double,
)

data class HybridForecastState(
    val nowMs: Long,
    val glucoseHistory: List<HybridGlucosePoint>,
    val foodHistory: List<HybridFoodEvent> = emptyList(),
    val bolusHistory: List<HybridBolusEvent> = emptyList(),
    val basalHistory: List<HybridBasalEvent> = emptyList(),
    val activityExposure: Double = 0.0,
    val asleep: Double = 0.0,
    val sleepDebtHours: Double = 0.0,
    val hoursSinceWake: Double = 0.0,
    /** Minutes since the last measured glucose fact. Zero for live CGM.
     * Open-loop propagation increases the forecast band with this value; an
     * estimated current state must never inherit the confidence of a sensor
     * packet that arrived just now. */
    val observationAgeMin: Double = 0.0,
    /** Causally available typed exposure ids; Legacy ignores them. */
    val contextFlags: Set<String> = emptySet(),
    /** Anchor/state and observation-process modifiers, never event owners. */
    val backgroundRateOffsetMmolPerHour: Double = 0.0,
    val basalReferenceScaleMultiplier: Double = 1.0,
    val sensorBiasMmol: Double = 0.0,
    val sensorProcessSigmaMmol: Double = 0.0,
)

data class HybridShape(
    val delayMin: Double,
    val peakMin: Double,
    val durationMin: Double,
)

/** Observable landmarks of the exact food CDF used by the forecast. */
data class HybridFoodTiming(
    val onsetMin: Double,
    val peakMin: Double,
    /** Time by which 90% of the modeled carbohydrate response has arrived. */
    val plateauMin: Double,
    /** Last non-zero modeled trace. Kept for censoring/episode closure only. */
    val tailEndMin: Double = plateauMin,
    /**
     * When half the modelled rise has arrived.
     *
     * Added because [peakMin] is an argmax over the arrival RATE and
     * a meal's rate here is genuinely two-humped, so it is not monotone in the
     * inputs: a 40 g dish reported 55 -> 72 -> 59 -> 93 minutes for 0, 10, 20 and
     * 40 g of fat. This is a LEVEL crossing like [onsetMin] and [plateauMin],
     * hence monotone by construction — 74 -> 86 -> 98 -> 122 on the same probe.
     *
     * Defaults to [plateauMin] so no existing constructor changes behaviour.
     */
    val medianArrivalMin: Double = plateauMin,
)


data class HybridTail(
    val gain: Double,
    val onsetMin: Double,
    val peakMin: Double,
    val durationMin: Double,
)

data class HybridFoodPrototype(
    val group: String,
    val tokens: Set<String>,
)

data class HybridCdfKnot(
    val minute: Double,
    val fraction: Double,
)

/** A personal whole-dish profile learned from repeated observed episodes. */
data class HybridProgressiveFoodProfile(
    val tier: String,
    val factor: Double,
    val onsetMin: Double,
    val ratePeakMin: Double,
    val effectEndMin: Double,
    val cdfKnots: List<HybridCdfKnot>,
)

data class HybridRuntimeParams(
    val horizonMin: Int = 180,
    val stepMin: Int = 5,
)

data class HybridInsulinParams(
    val isf: Double,
    val isfLow: Double,
    val isfHigh: Double,
    val onsetMin: Double,
    val peakMin: Double,
    val shortDurationMin: Double,
    val tailDurationMin: Double,
    val tailWeight: Double,
    val tailWeightPerUnit: Double = 0.0,
    val tailReferenceUnits: Double = 2.0,
    /**
     * Optional v12.1 empirical cumulative action curve.
     *
     * When present it REPLACES the parametric fields above in
     * `HybridForecastEngine.insulinCdf`, including their dose dependence:
     * `tailWeightPerUnit` and `tailReferenceUnits` are not read while knots are
     * set, so a measured or hand-entered curve acts over the same duration for
     * every dose size. Recorded limitation (audit M10), pinned by
     * `InsulinKnotsDoseIndependenceTest`.
     */
    val actionCdfKnots: List<HybridCdfKnot> = emptyList(),
)

data class HybridFoodParams(
    val globalFactor: Double,
    val calibration: Double = 1.0,
    val defaultShape: HybridShape,
    // ---- TUNING THAT TRAVELS WITH THE MODEL ----------------------------
    //
    // The gastric queue and the carb-type spread used to be reachable only as
    // arguments to `physioForecastEngine`, and there are FOURTEEN call sites in
    // the app. Threading a value through fourteen places is how one gets missed
    // — and a missed one does not fail, it quietly computes the old model under
    // the new label (discipline #7).
    //
    // Carrying them on the person model instead means every call site picks
    // them up for free.
    //
    // AN EARLIER VERSION OF THIS COMMENT SAID THEY «enter the artifact hash».
    // They do not. `HybridModelStore` installs the tuned model under the
    // artifact's ORIGINAL sha, so `physio_parallel_runs` rows do NOT tell two
    // tunings apart, and the PHYSIO artifact cache did not even notice the
    // change until `PhysioRuntime.ArtifactCacheKey.tuningIdentity` was added.
    // Editing the artifact JSON itself is a different matter — that really does
    // produce new bytes and a new sha.
    //
    // null = exactly what ships today. These are overrides, not new defaults.
    val emptyingKcalPerHourOverride: Double? = null,
    val carbSievingOverride: Double? = null,
    val carbSpreadOverride: Double? = null,
    // Time axis of the population carb triangles; 1.0 ships. Reachable only as
    // a sweep argument until now, which is why a measured off-default value was
    // never applied anywhere and must not be read as a property of the user's gut.
    /**
     * The three population triangles, when the user has set them.
     *
     * ON THE MODEL, not threaded through fourteen call sites — same reason
     * `carbTimeScaleOverride` lives here. `physioForecastEngine` resolves the
     * precedence in one place: an explicit argument is a SWEEP and wins, then
     * this, then what ships.
     */
    val carbTrianglesOverride: CarbTrianglesV1? = null,
    val carbTimeScaleOverride: Double? = null,
    /** Scalar on `grams x CS`; 1.0 ships. See `HybridForecastEngine.foodAmpScale`. */
    val foodAmpOverride: Double? = null,
    /** Length of the food tail alone; 1.0 ships. See `foodTailScale`. */
    val foodTailScaleOverride: Double? = null,
)

data class HybridActivityParams(
    val iobGamma: Double,
    val tauMin: Double,
    val foodGamma: Double,
    val foodTauMin: Double,
)

data class HybridBasalParams(
    val onsetMin: Double,
    val peakMin: Double,
    val durationMin: Double,
    val referenceUnits24h: Double,
    val sensitivityMmolPerActionUnit: Double,
    val scale: Double,
)

data class HybridJointCoefficients(
    val intercept: Double = 0.0,
    val sin24: Double = 0.0,
    val cos24: Double = 0.0,
    val sin12: Double = 0.0,
    val cos12: Double = 0.0,
    val stateReversion: Double = 0.0,
    val activityDirect: Double = 0.0,
    val sleep: Double = 0.0,
    val sleepDebt: Double = 0.0,
    val hoursSinceWake: Double = 0.0,
)

data class HybridJointParams(
    val coefficients: HybridJointCoefficients,
    val targetGlucose: Double,
    val backgroundScale: Double = 1.0,
)

data class HybridTrendParams(
    val windowMin: Int,
    val tauMin: Double,
    val weight60: Double,
    val weight120: Double,
    val weight180: Double,
)

data class HybridUncertaintyParams(
    val sigmaPerSqrtHour: Double,
    val foodFraction: Double,
    val unknownFoodExtraFraction: Double,
    /** Widen the interval for ISF uncertainty of boluses already on board. */
    val includeActiveBolusIsf: Boolean = false,
)

/**
 * A portable, trained artifact. The runtime contains no patient-specific
 * defaults: every physiological coefficient enters through this object.
 */
data class HybridPersonModel(
    val schemaVersion: Int,
    val modelVersion: String,
    val personModelId: String,
    val runtime: HybridRuntimeParams,
    val insulin: HybridInsulinParams,
    val food: HybridFoodParams,
    val activity: HybridActivityParams,
    val basal: HybridBasalParams,
    val joint: HybridJointParams,
    val trend: HybridTrendParams,
    val uncertainty: HybridUncertaintyParams,
) {
    init {
        require(schemaVersion == 1) { "Unsupported person-model schema: $schemaVersion" }
        require(modelVersion.isNotBlank()) { "modelVersion is required" }
        require(personModelId.isNotBlank()) { "personModelId is required" }
        require(runtime.horizonMin > 0 && runtime.stepMin > 0)
        require(runtime.horizonMin % runtime.stepMin == 0)
        require(insulin.isf > 0.0)
        require(insulin.isfLow > 0.0 && insulin.isfHigh >= insulin.isfLow)
        require(insulin.onsetMin >= 0.0)
        require(insulin.onsetMin < insulin.peakMin)
        require(insulin.peakMin < insulin.shortDurationMin)
        require(insulin.shortDurationMin <= insulin.tailDurationMin)
        require(insulin.tailWeight in 0.0..1.0)
        if (insulin.actionCdfKnots.isNotEmpty()) {
            require(insulin.actionCdfKnots.size >= 2)
            require(insulin.actionCdfKnots.all {
                it.minute.isFinite() && it.fraction.isFinite() && it.fraction in 0.0..1.0
            })
            require(insulin.actionCdfKnots.zipWithNext().all { (left, right) ->
                right.minute > left.minute && right.fraction >= left.fraction
            }) { "Insulin action CDF knots must be monotone" }
            require(insulin.actionCdfKnots.first().fraction == 0.0)
            require(insulin.actionCdfKnots.last().fraction == 1.0)
        }
        require(food.globalFactor >= 0.0 && food.calibration > 0.0)
        require(activity.tauMin > 0.0 && activity.foodTauMin > 0.0)
        require(trend.windowMin > 0 && trend.tauMin > 0.0)
        require(uncertainty.sigmaPerSqrtHour >= 0.0)
    }
}

data class HybridForecastPoint(
    val minutes: Int,
    val tsMs: Long,
    val baseline: Double,
    val scenario: Double,
    val low: Double,
    val high: Double,
    val foodDelta: Double,
    val insulinActualDelta: Double,
    val insulinScenarioDelta: Double,
    val residualDrift: Double,
    val backgroundDelta: Double,
    val activityDirectDelta: Double,
)

/**
 * THE LOWEST GLUCOSE THIS MODEL IS ALLOWED TO PREDICT.
 *
 * There was no floor at all, and the integrator has no term that stops it: food
 * is finite, insulin action is not bounded below by the glucose it has left to
 * remove. A pulled database showed hundreds of stored forecast points
 * below zero, with a minimum far below the alert range, some of which belonged to
 * `hypo_alert` runs — the pass that decides whether to wake the user.
 *
 * The value matches `RapidFallNotifier`, which has clamped its own projection at
 * 2.0 for the same reason. It sits well BELOW the alert threshold of 3.9, so
 * clamping cannot change whether an alarm fires — only how deep the line claims
 * to go.
 *
 * ⚠ Clamping alone would be worse than the bug. A trajectory that reaches this
 * floor is not predicting a severe hypo; it is announcing that the model has
 * left the region where it means anything. So the floor is COUNTED and reported
 * ([HybridForecastResult.floorClampedPoints]), and the arm that draws it turns
 * that into a health reason rather than a silently prettier number.
 */
const val HYBRID_FLOOR_MMOL = 2.0

data class HybridForecastResult(
    val modelVersion: String,
    val personModelId: String,
    val anchorMmol: Double,
    val hypotheticalInsulinUnits: Double,
    val points: List<HybridForecastPoint>,
    /** How many points hit [HYBRID_FLOOR_MMOL]. Non-zero means the model broke down. */
    val floorClampedPoints: Int = 0,
)

// Food-factor semantics are explicit because legacy group/component/profile
// factors are absolute mmol/L/g, while PHYSIO permits timing memory but owns a
// single physiological carbohydrate sensitivity.
/** Versioned timing adapter. Zero coefficients are the unpromoted safe state. */
data class MacroTimingParamsV1(
    val version: String = "macro-timing-v1-unpromoted",
    val promoted: Boolean = false,
    val proteinDelayMinPer10g: Double = 0.0,
    val proteinTailMinPer10g: Double = 0.0,
    val fatPeakMinPer10g: Double = 0.0,
    val fatTailMinPer10g: Double = 0.0,
) {
    init {
        require(version.isNotBlank())
        require(listOf(proteinDelayMinPer10g,proteinTailMinPer10g,fatPeakMinPer10g,fatTailMinPer10g).all { it.isFinite() && it in 0.0..30.0 }) {
            "promoted macro timing shifts are bounded to 0..30 min per 10 g"
        }
        if (!promoted) require(listOf(proteinDelayMinPer10g,proteinTailMinPer10g,fatPeakMinPer10g,fatTailMinPer10g).all { it == 0.0 })
    }
}

/** Exogenous facts available at the start of one blind open-loop step. */
data class HybridOpenLoopContext(
    val tsMs: Long,
    val activityExposure: Double = 0.0,
    val asleep: Double = 0.0,
    val sleepDebtHours: Double = 0.0,
    val hoursSinceWake: Double = 0.0,
)

data class HybridOpenLoopPoint(
    val tsMs: Long,
    val mmol: Double,
    val foodDelta: Double,
    val insulinDelta: Double,
    val backgroundDelta: Double,
)

data class HybridOpenLoopScore(
    val n: Int,
    val mae: Double?,
    val rmse: Double?,
    /** actual - predicted, matching the Python audit. */
    val bias: Double?,
    val endError: Double?,
)
