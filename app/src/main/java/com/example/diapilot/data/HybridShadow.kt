package com.example.diapilot.data

import com.diapilot.core.analysis.mealConceptComponents
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.hybrid.HybridBasalEvent
import com.diapilot.core.hybrid.HybridBolusEvent
import com.diapilot.core.hybrid.HybridFoodEvent
import com.diapilot.core.hybrid.HybridForecastEngine
import com.diapilot.core.hybrid.HybridForecastState
import com.diapilot.core.hybrid.HybridGlucosePoint
import com.diapilot.core.hybrid.HybridOpenLoopContext
import com.diapilot.core.hybrid.robustGlucoseState
import com.diapilot.core.hybrid.slopeBridgeDelta
import com.diapilot.core.hybrid.HybridPersonModel
import com.diapilot.core.hybrid.MacroTimingParamsV1
import com.diapilot.core.hybrid.PERSONAL_EMPTYING_KCAL_PER_HOUR_V1
import com.diapilot.core.physio.VarianceLedgerV1
import com.diapilot.core.physio.PhysioArtifactV1
import com.diapilot.core.physio.intervalContributionsV1
import com.diapilot.core.physio.macroTimingUncertaintyExtraV1
import com.diapilot.core.hybrid.MINUTE_MS
import com.diapilot.core.twin.ForecastResult
import com.diapilot.core.twin.PredictedPoint
import java.io.InputStream
import kotlin.math.exp
import kotlin.math.min

// shadow-9: robust state/bridge plus full causal insulin on displayed curves.
//
// shadow-13. The tag had gone a long stretch without moving while the food
// model changed under it several times — the caloric queue moved to a
// derived cap, the carb triangles were introduced, fat stopped entering as
// fast carbohydrate, and the dish dictionary plus the learned per-dish
// curves were removed together with the second arm. Every quality or
// coverage number read off the previous shadow tag is therefore computed on
// a MIXTURE of several generations, which is exactly what the shadow-tag
// rule exists to prevent.
//
// It is bumped now rather than at any of those four moments because the ledger
// starts recording the SHOWN arm again in this same change; a generation that
// begins here is clean from its first row. The usual cost of a bump — the
// paired A/B resets — does not apply: the prospective pairs are going away.
const val HYBRID_V11_SHADOW_ALGO_VERSION =
    "forecast-v11-kotlin-shadow-13-macro-queue-single-arm"

private fun foodDurationMin(analysis: String?): Double =
    analysis?.lineSequence()
        ?.firstOrNull { it.startsWith("META_DURATION_MIN:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.replace(',', '.')?.toDoubleOrNull()
        ?.coerceIn(0.0, 240.0) ?: 0.0

object HybridShadowRegistry {
    @Volatile
    private var installed: HybridPersonModel? = null
    @Volatile private var latestPhysioWhatIf: HybridWhatIfProfile? = null
    @Volatile
    private var installedSha256: String? = null

    fun install(input: InputStream) {
        install(HybridPersonModelJson.read(input))
    }

    fun install(model: HybridPersonModel, sha256: String? = null) {
        installed = model
        installedSha256 = sha256
        latestPhysioWhatIf = null
    }

    fun model(): HybridPersonModel? = installed
    fun modelSha256(): String? = installedSha256

    fun updateWhatIf(
        anchorTsMs: Long,
        activityExposure: Double,
        baseline: List<com.diapilot.core.hybrid.HybridForecastPoint>,
        modelOverride: HybridPersonModel? = null,
        /**
         * WHICH SLOT TO WRITE TO — NO DEFAULT VALUE, DELIBERATELY.
         *
         * This used to default to `false`, and the physio arm did not pass the
         * flag: the profile landed in the legacy slot, `latestPhysioWhatIf`
         * stayed null forever, and "What if" was computed on a different
         * engine with a different ISF while the line was drawn from physio.
         * The user spotted this visually before any test caught it.
         *
         * A test on slot routing does NOT catch this class of bug — it calls
         * this function directly and passes the flag itself (confirmed by
         * mutation testing: dropping `physio = true` on the production call
         * site does not fail the test). So the guard is structural: with no
         * default, the compiler requires an answer at every call site.
         */
        physio: Boolean,
        macroTiming: MacroTimingParamsV1 = MacroTimingParamsV1(),
        /** Glucometer lens slope; see `HybridWhatIfProfile.displayScale`. */
        displayScale: Double = 1.0,
    ) {
        val model = modelOverride ?: installed ?: return
        val profile = HybridWhatIfProfile(
            anchorTsMs = anchorTsMs,
            // WHAT-IF MUST BE THE SAME ENGINE AS THE FORECAST. It was not:
            // this built its own with the shipped defaults, so a fatty meal's
            // What-if line and its forecast line ran different rules.
            engine = if (physio) com.diapilot.core.hybrid.physioForecastEngine(model, macroTiming)
            else HybridForecastEngine(
                model,
                macroTiming,
            ),
            activityExposure = activityExposure,
            activityTauMin = model.activity.tauMin,
            foodTauMin = model.activity.foodTauMin,
            iobGamma = model.activity.iobGamma,
            foodGamma = model.activity.foodGamma,
            activityDirect = model.joint.coefficients.activityDirect,
            backgroundScale = model.joint.backgroundScale,
            baseline = baseline,
            displayScale = displayScale,
        )
        if (physio) latestPhysioWhatIf = profile
    }

    // `whatIf()` — THE LEGACY SLOT — WAS REMOVED: it had not a single reader.
    // That is exactly what made the neighboring bug invisible: the physio arm,
    // by not passing `physio = true`, published its profile HERE, into a slot
    // nobody reads from. The data went nowhere, and the screen fell back to
    // the twin's legacy core. A defect with no reader does not even produce a
    // wrong answer — it produces silence, and silence goes unnoticed.
    /**
     * FRESHNESS, NOT CLOCK EQUALITY.
     *
     * This used to be `it.anchorTsMs == anchorTsMs`, and the match almost
     * never happened: the profile is published at the moment the forecast is
     * computed, while the screen looks it up by the first point of the shown
     * series — a value that on the device moved EVERY second and a half.
     * Requiring exact equality with a moving target is a guaranteed miss.
     *
     * The cost of a miss was not cosmetic: `null` sent "What if" to the twin's
     * legacy core, with a DIFFERENT ISF, while the line was drawn from physio.
     * The user spotted the discrepancy visually before any test did; on the
     * device, before the fix, the log read "profile found false · arm
     * LEGACY-CORE" on every frame.
     *
     * The point of the check was to avoid feeding in a profile from an OLD
     * forecast, and that is preserved: the tolerance is one recompute
     * interval. A profile older than ten minutes is still rejected.
     */
    const val WHAT_IF_FRESH_MS = 10L * 60_000

    fun physioWhatIf(anchorTsMs: Long): HybridWhatIfProfile? =
        latestPhysioWhatIf?.takeIf {
            kotlin.math.abs(it.anchorTsMs - anchorTsMs) <= WHAT_IF_FRESH_MS
        }
}

data class HybridWhatIfProfile(
    val anchorTsMs: Long,
    private val engine: HybridForecastEngine,
    private val activityExposure: Double,
    private val activityTauMin: Double,
    private val foodTauMin: Double,
    private val iobGamma: Double,
    private val foodGamma: Double,
    private val activityDirect: Double,
    private val backgroundScale: Double,
    private val baseline: List<com.diapilot.core.hybrid.HybridForecastPoint>,
    /**
     * GLUCOMETER LENS SLOPE — because "What if" is laid on the CALIBRATED line.
     *
     * The screen computes `p.mmol + d`, where `p` is the forecast drawn after
     * the lens (`TwinCache.calibrateReadings`: `scale * raw + intercept`), and
     * `d` used to arrive here in the model's RAW mmol. A model change of
     * `delta` costs `scale * delta` on the screen, so "What if" was
     * underreporting its own effect by the lens's scale factor — requiring
     * meaningfully more insulin units than actually needed.
     *
     * The user reported this discrepancy — the tool suggesting more units
     * than they judged necessary from experience — and had been dosing less
     * than the app suggested ever since. It was explained at the time by an
     * ISF that was too weak, which has since been corrected — but this
     * multiplier remained.
     *
     * The lens offset (`intercept`) is deliberately NOT applied: it already
     * sits inside `p.mmol`, and only the slope is added to the difference.
     */
    private val displayScale: Double = 1.0,
) {
    fun insulinDelta(
        horizonMin: Double,
        units: Double,
        offsetMin: Double,
        activityOffsetMin: Double? = null,
        activityDurationMin: Double = 0.0,
        activityIntensity: Double = 0.0,
    ): Double {
        val age = horizonMin - offsetMin
        if (age <= 0.0) return 0.0
        val exposureAtDose = activityExposure * exp(-offsetMin / activityTauMin)
        // A user-controlled intervention is not an observation to be shrunk by
        // the empirical horizon backbone. Draw the complete learned personal
        // kernel; uncertainty remains in the surrounding forecast corridor.
        val base = displayScale * engine.hypotheticalInsulinDelta(
            horizonMin = age,
            units = units,
            activityExposure = exposureAtDose,
        )
        val activityOffset = activityOffsetMin ?: return base
        if (activityDurationMin <= 0.0 || activityIntensity <= 0.0) return base
        val baseAvg = (exposureAtDose + exposureAtDose * exp(-age / activityTauMin)) / 2.0
        val extraAvg = plannedAverageExposure(
            offsetMin, horizonMin, activityOffset, activityDurationMin,
            activityIntensity, activityTauMin,
        )
        val oldMultiplier = 1.0 + iobGamma * minOf(1.5, baseAvg)
        val newMultiplier = 1.0 + iobGamma * minOf(1.5, baseAvg + extraAvg)
        return base * newMultiplier / oldMultiplier
    }

    fun insulinUncertainty(
        horizonMin: Double,
        units: Double,
        offsetMin: Double,
        activityOffsetMin: Double? = null,
        activityDurationMin: Double = 0.0,
        activityIntensity: Double = 0.0,
    ): Double = engine.insulinIsfUncertainty(
        insulinDelta(
            horizonMin, units, offsetMin, activityOffsetMin,
            activityDurationMin, activityIntensity,
        ),
    )

    /** Also on the SCREEN scale — see `displayScale`. A hypothetical meal is
     *  laid on the same calibrated line as a hypothetical injection. */
    fun foodDelta(horizonMin: Double, carbsG: Double): Double =
        displayScale * engine.hypotheticalFoodDelta(horizonMin, carbsG, activityExposure)

    /** Difference from the already-computed v11 baseline caused by a planned
     * activity bout. Uses the artifact's learned direct, IOB and food terms. */
    fun activityDelta(
        horizonMin: Double,
        offsetMin: Double,
        durationMin: Double,
        intensity: Double,
    ): Double {
        if (horizonMin <= offsetMin || durationMin <= 0.0 || intensity <= 0.0) return 0.0
        val point = baseline.minByOrNull { kotlin.math.abs(it.minutes - horizonMin) }
            ?: return 0.0

        val insulinBaseAvg = (activityExposure +
            activityExposure * exp(-horizonMin / activityTauMin)) / 2.0
        val insulinExtraAvg = plannedAverageExposure(
            0.0, horizonMin, offsetMin, durationMin, intensity, activityTauMin,
        )
        val oldInsulinMultiplier = 1.0 + iobGamma * minOf(1.5, insulinBaseAvg)
        val newInsulinMultiplier =
            1.0 + iobGamma * minOf(1.5, insulinBaseAvg + insulinExtraAvg)
        val insulinChange = point.insulinActualDelta *
            (newInsulinMultiplier / oldInsulinMultiplier - 1.0)

        val foodBaseAvg = (activityExposure +
            activityExposure * exp(-horizonMin / foodTauMin)) / 2.0
        val foodExtraAvg = plannedAverageExposure(
            0.0, horizonMin, offsetMin, durationMin, intensity, foodTauMin,
        )
        val oldFoodMultiplier = maxOf(0.25, 1.0 - foodGamma * minOf(1.5, foodBaseAvg))
        val newFoodMultiplier = maxOf(
            0.25,
            1.0 - foodGamma * minOf(1.5, foodBaseAvg + foodExtraAvg),
        )
        val foodChange = point.foodDelta * (newFoodMultiplier / oldFoodMultiplier - 1.0)

        var directChange = 0.0
        var minute = 0.0
        while (minute < horizonMin) {
            val step = minOf(5.0, horizonMin - minute)
            val exposure = plannedExposureAt(
                minute + step / 2.0, offsetMin, durationMin, intensity, activityTauMin,
            )
            directChange += -activityDirect * exposure * step / 30.0 * backgroundScale
            minute += step
        }
        // THE SAME SCALE AS `insulinDelta` — the screen adds both to the same
        // calibrated line, and they must not drift apart.
        return displayScale * (insulinChange + foodChange + directChange)
    }

    private fun plannedAverageExposure(
        fromMin: Double,
        toMin: Double,
        offsetMin: Double,
        durationMin: Double,
        intensity: Double,
        tauMin: Double,
    ): Double {
        if (toMin <= fromMin) return 0.0
        var weighted = 0.0
        var minute = fromMin
        while (minute < toMin) {
            val step = minOf(5.0, toMin - minute)
            weighted += plannedExposureAt(
                minute + step / 2.0, offsetMin, durationMin, intensity, tauMin,
            ) * step
            minute += step
        }
        return weighted / (toMin - fromMin)
    }

    private fun plannedExposureAt(
        minute: Double,
        offsetMin: Double,
        durationMin: Double,
        intensity: Double,
        tauMin: Double,
    ): Double {
        if (minute <= offsetMin || tauMin <= 0.0) return 0.0
        val activeFor = minOf(durationMin, minute - offsetMin)
        val accumulated = intensity * tauMin / 60.0 * (1.0 - exp(-activeFor / tauMin))
        val sinceEnd = maxOf(0.0, minute - offsetMin - durationMin)
        return accumulated * exp(-sinceEnd / tauMin)
    }
}

data class HybridShadowRun(
    val result: ForecastResult,
    val inputHash: String,
    /** Ledger dedup key: what this run KNEW, without the clock. A byproduct of
     *  the run, never a computation of its own — see where it is built. */
    val dedupHash: String,
    /** The forecast starts here. In open loop this is wall-clock `now`, not
     * the older glucose fact from which the state was propagated. */
    val producedAnchorTsMs: Long,
    val producedAnchorMmol: Double,
    /** Exact affine raw-to-forecast coordinate transform used at target time. */
    val scoringCalibration: com.diapilot.core.analysis.MeterCalibration,
    /**
     * Which model produced this run, carried ON the run.
     *
     * The ledger label used to be chosen at the call site from a constant, and
     * at one point the call site and the model parted company: the row said
     * `FORECAST_ALGO_VERSION` while the line on screen came from here. A tag
     * that travels with the run cannot drift from what produced it.
     */
    val algorithmVersion: String = HYBRID_V11_SHADOW_ALGO_VERSION,
)

data class HybridProspectiveWhatIfRun(
    val bolusTsMs: Long,
    val anchorTsMs: Long,
    val anchorMmol: Double,
    val units: Double,
    val modelVersion: String,
    val personModelId: String,
    val modelSha256: String,
    val algorithmVersion: String,
    val inputHash: String,
    val points: List<com.diapilot.core.hybrid.HybridForecastPoint>,
)

/** Stable identity of food facts shared by comparison arms. Model-owned
 * timing templates and learned offsets are intentionally excluded. */
internal fun causalFoodSnapshotSignature(events: List<HybridFoodEvent>): String =
    events.joinToString(",") {
        "${it.tsMs}:${it.carbsG}:${it.durationMin}:${it.proteinG}:${it.fatG}:" +
            "${it.macroProvenance}:${it.recipeKey}:${it.physicalForm}:${it.carbClass}:" +
            "${it.text}:${it.components.toSortedMap()}:${it.contextIds.sorted()}"
    }

/**
 * Causal app adapter for the pure v11 runtime.
 *
 * The bundled artifact was trained in the raw CGM coordinate system. We run
 * there and map every predicted bound through DiaPilot's current meter lens
 * before putting it beside the live forecast in the prospective ledger.
 */
object HybridShadow {
    fun forecast(
        store: CollectorStore,
        diaPilotModel: TwinCache.Model,
        anchorTsMs: Long,
        anchorMmol: Double,
        /** The situation this anchor sits in. A property of the DAY, not of a
         *  model — see `RegimeV1` — so both arms read one classifier instead
         *  of this arm inheriting the other's answer. */
        regime: com.diapilot.core.twin.Regime,
        /** Non-null when the plausibility gate de-trusted the anchor as a
         *  sensor artifact. Supplied by the caller from the same anchor window
         *  the legacy arm used to gate on; null = plausible or gate off. */
        sensorSuspect: com.diapilot.core.analysis.SuspectReason?,
        /** Wall-clock knowledge cutoff. It can be newer than the latest CGM
         * anchor: food/insulin entered between sensor packets is still known
         * and must affect the forecast immediately. */
        knowledgeTsMs: Long = anchorTsMs,
        /** Experimental display/What-if contract. Alerts keep the frozen,
         * measured incumbent until this causal branch earns prospective data. */
        fullCausalInsulinDisplay: Boolean = false,
        personModelOverride: HybridPersonModel? = null,
        physioVariance: VarianceLedgerV1? = null,
        physioMacroTiming: MacroTimingParamsV1 = MacroTimingParamsV1(),
        contextFlags: Set<String> = emptySet(),
        physioArtifact: PhysioArtifactV1? = null,
        publishFoodCalculations: Boolean = fullCausalInsulinDisplay,
        /** Only the SELECTED arm's displayed run may publish the interactive
         * What-if profile. Candidate shadow runs need the full-causal display
         * contract for pairing but must never overwrite what the user's
         * slider computes with an unpromoted model (A-03). */
        publishWhatIf: Boolean = fullCausalInsulinDisplay,
    ): HybridShadowRun? {
        val personModel = personModelOverride ?: HybridShadowRegistry.model() ?: return null
        // One extra observation window lets both ends of the trend use a
        // robust local estimate instead of whichever noisy packet happened to
        // land exactly 60 minutes ago.
        val observationWindowMin = 15

        val rawHistory = store.sensorReadings(
            anchorTsMs - (personModel.trend.windowMin + observationWindowMin) * MINUTE_MS,
            anchorTsMs,
        ).sortedBy { it.tsMs }
        val rawSensorAnchor = rawHistory.lastOrNull { it.tsMs <= anchorTsMs }
        val meterAnchor = store.meterReadings(
            anchorTsMs - 2L * MINUTE_MS,
            anchorTsMs + 2L * MINUTE_MS,
        ).minByOrNull { kotlin.math.abs(it.tsMs - anchorTsMs) }
            ?.takeIf { kotlin.math.abs(it.tsMs - anchorTsMs) <= 2L * MINUTE_MS }
        val sourceTsMs = meterAnchor?.tsMs ?: rawSensorAnchor?.tsMs ?: return null
        if (knowledgeTsMs - sourceTsMs > 36L * 60L * MINUTE_MS) return null

        // v11 was learned in raw-CGM coordinates. A fingerstick is already in
        // blood-glucose coordinates, so invert the persistent affine lens for
        // the simulation and map the result back below. The display shift then
        // also preserves the current transient correction exactly.
        val sourceRawMmol = if (meterAnchor != null &&
            diaPilotModel.foodRiseScale != 0.0 &&
            meterAnchor.tsMs >= diaPilotModel.calEpochStartMs
        ) {
            (meterAnchor.mmol - diaPilotModel.meterInterceptMmol) /
                diaPilotModel.foodRiseScale
        } else {
            rawSensorAnchor?.mmol ?: anchorMmol
        }
        // A meter is ground truth.  A sensor packet is an observation of a
        // latent state: use a causal robust line so a single ±50 mg/dL packet
        // cannot translate the whole three-hour forecast.  A sustained slope
        // across several packets is preserved by the Theil-Sen estimate.
        val robustSource = robustGlucoseState(
            rawHistory.map { HybridGlucosePoint(it.tsMs, it.mmol) },
            sourceTsMs,
            observationWindowMin,
        )
        val stableSourceRawMmol = if (meterAnchor != null) sourceRawMmol
            else robustSource?.levelMmol ?: sourceRawMmol

        val foodLookback = maxOf(
            12L * 60L * MINUTE_MS,
            knowledgeTsMs - sourceTsMs + 12L * 60L * MINUTE_MS,
        )
        val causalFoodNotes = store.annotations(knowledgeTsMs - foodLookback, knowledgeTsMs)
            .asSequence()
            .filter { it.kind == "food" && it.tsMs <= knowledgeTsMs }
            .mapNotNull { note ->
                val evidence = store.carbEvidenceKnownAt(note.id, knowledgeTsMs)
                val legacyKnownAt = note.carbsKnownAtMs
                when {
                    evidence != null -> Triple(note, evidence.input.totalCarbsG, evidence)
                    note.estCarbs != null && legacyKnownAt != null && legacyKnownAt <= knowledgeTsMs -> Triple(note, note.estCarbs, null)
                    else -> null
                }
            }
            .toList()
        // Build the causal facts once, before either arm decorates them with
        // model-owned timing/modifier state.  The paired-run hash must answer
        // "did both arms see the same facts?", not "did both models transform
        // those facts identically?".  Hashing timingTemplate below made every
        // legitimate Legacy-vs-Physio comparison fail as soon as Physio gained
        // a personal food template.
        val causalFood = causalFoodNotes.map { (note, causalCarbs, evidence) ->
                val analysisKnownAt = note.analysisKnownAtMs
                val causalAnalysis = note.analysis.takeIf {
                    analysisKnownAt != null && analysisKnownAt <= knowledgeTsMs
                }
                val nutrition = com.diapilot.core.analysis.parseFoodNutrition(causalAnalysis)
                // Python v11 routes an atomic dish through its emergent food
                // prototype. mealConceptComponents() also manufactures a
                // one-item component for UI pooling; feeding that to runtime
                // bypassed the smoothie prototype and collapsed every atomic
                // dish onto unknown_component/default timing.
                val components = if (
                    com.diapilot.core.analysis.hasCanonicalComposition(causalAnalysis)
                ) {
                    mealConceptComponents(causalAnalysis, note.content, causalCarbs)
                        .groupingBy { it.first }
                        .fold(0.0) { total, item -> total + item.second }
                } else emptyMap()
                // contextIds are inert here: no modifiers — see PhysioRuntime.promoted
                val ownerIds=emptySet<String>()
                // Recipe is an explicit evidence identity only. Physiological
                // routing never scans the human title.
                val recipeKey=evidence?.input?.recipeVersion?.takeIf{it.isNotBlank()}
                val baseEvent=HybridFoodEvent(
                    tsMs = note.tsMs,
                    carbsG = causalCarbs ?: 0.0,
                    text = note.content,
                    components = components,
                    durationMin = evidence?.input?.intakeDurationMin ?: foodDurationMin(causalAnalysis),
                    proteinG = nutrition.proteinG,
                    fatG = nutrition.fatG,
                    macroProvenance = causalAnalysis?.let { "annotation_analysis_known_at_v1" },
                    kineticFeatures = com.diapilot.core.analysis.parseFoodKineticsV2(causalAnalysis,nutrition.proteinG,nutrition.fatG),
                    rescueTreatment = note.content.trim().startsWith(com.diapilot.core.analysis.RESCUE_NOTE_PREFIX,ignoreCase=true),
                    recipeKey=recipeKey,
                    contextIds=ownerIds,
                )
                baseEvent
            }
        // THE PER-DISH CURVE LOOKUP LIVED HERE AND IS GONE.
        //
        // A dish the model had learned used to hand the forecast its own
        // pooled onset/peak/tail, overriding the macro mixture. The intended
        // description of the model is macros, calories and a variable ISF —
        // recognised dishes supply WHAT went in, never HOW FAST. Timing is the
        // triangles, the macro shares and the caloric queue, for every meal.
        val food = causalFood.map { event ->
            if (physioArtifact != null) physioArtifact.decorateFood(event, event.contextIds)
            else event
        }
        val evidenceRevisionIds = causalFoodNotes.asSequence()
            .mapNotNull { it.third?.let { evidence -> "${evidence.evidenceId}:${evidence.revision}" } }
            .sorted().toList()
        val causalBoluses = store.boluses(
            sourceTsMs - 6L * 60L * MINUTE_MS,
            knowledgeTsMs,
        )
            .map { point ->
                // contextIds are inert here: no modifiers — see PhysioRuntime.promoted
                val ownerIds=emptySet<String>()
                HybridBolusEvent(point.tsMs, point.units, contextIds=ownerIds)
            }
        val boluses = causalBoluses.map { event ->
            if (physioArtifact != null) physioArtifact.decorateBolus(event, event.contextIds) else event
        }
        val basalLookback = personModel.basal.durationMin.toLong() * MINUTE_MS
        val causalBasals = store.basalEvents(anchorTsMs - basalLookback, knowledgeTsMs)
            .map { point ->
                // contextIds are inert here: no modifiers — see PhysioRuntime.promoted
                val ownerIds=emptySet<String>()
                HybridBasalEvent(point.tsMs, point.units, contextIds=ownerIds)
            }
        val basals = causalBasals.map { event ->
            if (physioArtifact != null) physioArtifact.decorateBasal(event, event.contextIds) else event
        }
        val activityExposure = activityExposure(
            store,
            knowledgeTsMs,
            personModel.activity.tauMin,
        )
        val (asleep, sleepDebt, hoursSinceWake) = sleepFeatures(store, knowledgeTsMs)
        // ONE constructor — see [physioForecastEngine]. The `physioArm` fork
        // that used to sit here was dead: both call sites in `Forecaster` passed
        // true, and both only reach this function with a non-null artifact.
        val engine = com.diapilot.core.hybrid.physioForecastEngine(personModel, physioMacroTiming)
        val trendStartTsMs = sourceTsMs - personModel.trend.windowMin * MINUTE_MS
        val stableTrendStart = robustGlucoseState(
            rawHistory.map { HybridGlucosePoint(it.tsMs, it.mmol) },
            trendStartTsMs,
            observationWindowMin,
        )?.levelMmol
        val sourceHistory = buildList {
            if (stableTrendStart != null) {
                add(HybridGlucosePoint(trendStartTsMs, stableTrendStart))
            }
            add(HybridGlucosePoint(sourceTsMs, stableSourceRawMmol))
        }
        val baseInitialState = HybridForecastState(
            nowMs = sourceTsMs,
            glucoseHistory = sourceHistory,
            foodHistory = food,
            bolusHistory = boluses,
            basalHistory = basals,
            contextFlags = contextFlags,
        )
        val initialState=if(physioArtifact!=null)physioArtifact.decorateState(baseInitialState,contextFlags)else baseInitialState
        val openLoopPoints = if (knowledgeTsMs - sourceTsMs >= MINUTE_MS) {
            val steps = store.steps(sourceTsMs - 360L * MINUTE_MS, knowledgeTsMs)
            val sleeps = store.sleepSessions(sourceTsMs - 48L * 60L * MINUTE_MS, knowledgeTsMs)
                .sortedBy { it.startMs }
            fun exposureAt(tsMs: Long): Double {
                val oldest = tsMs - 360L * MINUTE_MS
                var total = 0.0
                for (bucket in steps) {
                    val durationMin = (bucket.endMs - bucket.startMs).toDouble() / MINUTE_MS
                    if (durationMin <= 0.0) continue
                    val overlap = maxOf(
                        0L,
                        minOf(bucket.endMs, tsMs) - maxOf(bucket.startMs, oldest),
                    )
                    if (overlap <= 0L) continue
                    val rate = min(180.0, bucket.count / durationMin)
                    if (rate >= 40.0) {
                        val lag = maxOf(
                            0.0,
                            (tsMs - minOf(bucket.endMs, tsMs)).toDouble() / MINUTE_MS,
                        )
                        total += overlap.toDouble() / MINUTE_MS *
                            min(2.0, rate / 60.0) * exp(-lag / personModel.activity.tauMin)
                    }
                }
                return total / 60.0
            }
            fun sleepAt(tsMs: Long): Triple<Double, Double, Double> {
                val asleepNow = if (sleeps.any { tsMs in it.startMs..it.endMs }) 1.0 else 0.0
                val previousSleep = sleeps.lastOrNull { it.endMs <= tsMs }
                    ?: return Triple(asleepNow, 0.0, 0.0)
                val durationH = (previousSleep.endMs - previousSleep.startMs) / 3_600_000.0
                return Triple(
                    asleepNow,
                    maxOf(0.0, 8.0 - durationH),
                    min(24.0, (tsMs - previousSleep.endMs) / 3_600_000.0),
                )
            }
            val contexts = buildList {
                var cursor = sourceTsMs
                while (cursor + personModel.runtime.stepMin * MINUTE_MS <= knowledgeTsMs) {
                    val sleep = sleepAt(cursor)
                    add(
                        HybridOpenLoopContext(
                            tsMs = cursor,
                            activityExposure = exposureAt(cursor),
                            asleep = sleep.first,
                            sleepDebtHours = sleep.second,
                            hoursSinceWake = sleep.third,
                        ),
                    )
                    cursor += personModel.runtime.stepMin * MINUTE_MS
                }
            }
            engine.openLoop(initialState, contexts)
        } else {
            emptyList()
        }
        val currentRaw = openLoopPoints.lastOrNull()?.mmol ?: stableSourceRawMmol
        val state = initialState.copy(
            nowMs = knowledgeTsMs,
            glucoseHistory = sourceHistory + HybridGlucosePoint(knowledgeTsMs, currentRaw),
            activityExposure = activityExposure,
            asleep = asleep,
            sleepDebtHours = sleepDebt,
            hoursSinceWake = hoursSinceWake,
            observationAgeMin =
                (knowledgeTsMs - sourceTsMs).toDouble() / MINUTE_MS,
        )
        val hybrid = engine.forecast(state)
        if (publishFoodCalculations && fullCausalInsulinDisplay && physioArtifact != null) {
            val p60=hybrid.points.minByOrNull{kotlin.math.abs(it.minutes-60)}
            val p180=hybrid.points.minByOrNull{kotlin.math.abs(it.minutes-180)}
            FoodCalculationRegistry.update(causalFoodNotes.zip(food).associate { (raw,event) ->
                val (note,_,evidence)=raw
                val timing=engine.foodTiming(event)
                val cluster=engine.mealClusterAssessment(event,food)
                val amplitude=engine.foodAmplitude(event).first
                val features=event.kineticFeatures?.normalized()
                val kineticsSummary=features?.let{
                    val args=arrayOf<Any>(
                        "%.0f".format(it.fastFraction*100),"%.0f".format(it.mediumFraction*100),"%.0f".format(it.slowFraction*100),
                        it.physicalForm.name,"%.0f".format(it.confidence*100),it.provenance,
                    )
                    if(timing.tailEndMin>timing.plateauMin+30)
                        com.example.diapilot.i18n.UiText.res(com.example.diapilot.R.string.hybrid_shadow_kinetics_tail,*args,"%.0f".format(timing.tailEndMin))
                    else com.example.diapilot.i18n.UiText.res(com.example.diapilot.R.string.hybrid_shadow_kinetics,*args)
                }?:com.example.diapilot.i18n.UiText.res(com.example.diapilot.R.string.hybrid_shadow_kinetics_none)
                note.id to FoodCalculationV1(
                    annotationId=note.id,eventTsMs=note.tsMs,dish=note.content,
                    model="PHYSIO_V1:${physioArtifact.artifactId}",carbsG=event.carbsG,
                    carbsProvenance=evidence?.input?.source?.name ?: (note.carbsSource ?: "LEGACY_UNKNOWN"),
                    proteinG=event.proteinG,fatG=event.fatG,durationMin=event.durationMin,
                    globalCsMedian=physioArtifact.globalCs.median,globalCsLow=physioArtifact.globalCs.p10,globalCsHigh=physioArtifact.globalCs.p90,
                    totalAmplitudeMmol=amplitude,
                    timingSource=if(features!=null)"structured-feature-mixture-v2" else "physiological_prior",
                    timingTemplateId=if(features!=null)"continuous-mixture" else "base-default",
                    onsetMin=timing.onsetMin,ratePeakMin=timing.peakMin,halfArrivalMin=timing.medianArrivalMin,effectEndMin=timing.plateauMin,
                    timingUncertaintyMin=35.0,
                    foodContribution60=engine.foodContribution(event,state.nowMs,state.nowMs+60L*MINUTE_MS,state.activityExposure,food),
                    foodContribution180=engine.foodContribution(event,state.nowMs,state.nowMs+180L*MINUTE_MS,state.activityExposure,food),
                    insulinContribution60=p60?.insulinActualDelta ?: 0.0,backgroundContribution60=(p60?.backgroundDelta?:0.0)+(p60?.residualDrift?:0.0),
                    isfMedian=physioArtifact.globalIsf.median,isfLow=physioArtifact.globalIsf.p10,isfHigh=physioArtifact.globalIsf.p90,
                    uncertaintySummary="grams/provenance + food timing + insulin kernel/ISF + background/basal + sensor/process; correlated remainder is unresolved",
                    globalCsOnlineEpisodes=physioArtifact.globalCs.identifyingEpisodes,
                    globalCsOnlineDays=physioArtifact.globalCs.independentDays,
                    globalCsEvidencePolicy="causal structured evidence known at forecast time; retrospective development rows never count as online promotion evidence",
                    kineticsSummary=kineticsSummary,
                    timingScope=cluster.scope.name,
                    clusterCarbsG=cluster.totalCarbsG.takeIf{cluster.memberIds.size>1},
                    clusterMembers=cluster.memberIds.size,
                    clusterReason=cluster.reason,
                    nextMealAtMs=cluster.nextMealAtMs,
                    realisedFractionAtNext=cluster.realisedFractionAtNext,
                )
            })
        }
        val fullInsulinPoints = if (fullCausalInsulinDisplay) {
            hybrid.points.map { point ->
                val fullInsulin = engine.fullKnownInsulinDelta(
                    state,
                    point.minutes.toDouble(),
                )
                val correction = fullInsulin - point.insulinActualDelta
                val extraBand = if (personModel.uncertainty.includeActiveBolusIsf) {
                    maxOf(
                        0.0,
                        engine.insulinIsfUncertainty(fullInsulin) -
                            engine.insulinIsfUncertainty(point.insulinActualDelta),
                    )
                } else 0.0
                point.copy(
                    baseline = point.baseline + correction,
                    scenario = point.scenario + correction,
                    low = point.low + correction - extraBand,
                    high = point.high + correction + extraBand,
                    insulinActualDelta = fullInsulin,
                )
            }
        } else hybrid.points
        val macroExtra = macroTimingUncertaintyExtraV1(food, physioMacroTiming.promoted)
        val activeOwnerIds=contextFlags+food.flatMap{it.contextIds}+boluses.flatMap{it.contextIds}+basals.flatMap{it.contextIds}
        val selectedVariance=physioArtifact?.varianceAt(activeOwnerIds)?:physioVariance
        val displayHybridPoints = if (selectedVariance == null) fullInsulinPoints else {
            fullInsulinPoints.map { point ->
                val baseWidth = intervalContributionsV1(point, selectedVariance, macroExtra).halfWidth()
                val width=kotlin.math.sqrt(baseWidth*baseWidth+state.sensorProcessSigmaMmol*state.sensorProcessSigmaMmol)
                point.copy(low = point.scenario - width, high = point.scenario + width)
            }
        }
        // Only a displayed full-causal forecast may publish the profile used
        // by the interactive UI. Background/internal/hypo runs deliberately
        // use the incumbent and must never race with or overwrite it — and a
        // CANDIDATE run, which also displays full-causal for pairing, must not
        // either: it was overwriting the profile with an unpromoted ISF/CS on
        // every main pass.

        fun calibrated(tsMs: Long, rawMmol: Double): Double =
            diaPilotModel.calibrateReadings(listOf(GlucosePoint(tsMs, rawMmol)))
                .firstOrNull()?.mmol ?: rawMmol
        // Keep the line connected to the visible latest reading at t=0, but
        // let its sensor-only residual lose authority smoothly.  The long-term
        // target therefore comes from food/insulin/activity and the robust
        // glucose state, not from one noisy minute packet.
        val stableDisplayedAnchor = calibrated(sourceTsMs, stableSourceRawMmol)
        val observationResidual = anchorMmol - stableDisplayedAnchor
        val observationResidualTauMin = 15.0

        val filteredPoints = displayHybridPoints.map { point ->
            val horizonMin = (point.tsMs - knowledgeTsMs).toDouble() / MINUTE_MS
            val residual = observationResidual *
                exp(-maxOf(0.0, horizonMin) / observationResidualTauMin)
            val mmol = calibrated(point.tsMs, point.baseline) + residual
            val lo = calibrated(point.tsMs, point.low) + residual
            val hi = calibrated(point.tsMs, point.high) + residual
            PredictedPoint(
                tsMs = point.tsMs,
                mmol = mmol,
                lo = minOf(lo, hi),
                hi = maxOf(lo, hi),
                loMid = mmol - (mmol - minOf(lo, hi)) * 0.5,
                hiMid = mmol + (maxOf(lo, hi) - mmol) * 0.5,
                // The addends go WITHOUT the lens and without the observation
                // residual — see `PredictedPoint.foodDelta`. This is what the engine computed.
                foodDelta = point.foodDelta,
                insulinDelta = point.insulinActualDelta,
                driftDelta = point.residualDrift,
                backgroundDelta = point.backgroundDelta,
            )
        }
        val bridgeDurationMin = 30.0
        val bridgeStepMin = personModel.runtime.stepMin.toDouble()
        val firstForecast = filteredPoints.firstOrNull {
            kotlin.math.abs((it.tsMs - knowledgeTsMs).toDouble() / MINUTE_MS - bridgeStepMin) < 0.1
        }
        val displayedSlope = robustSource?.let { source ->
            calibrated(sourceTsMs + MINUTE_MS, stableSourceRawMmol + source.slopeMmolPerMin) -
                calibrated(sourceTsMs, stableSourceRawMmol)
        }
        val points = if (firstForecast == null || displayedSlope == null) filteredPoints else {
            filteredPoints.map { point ->
                val horizonMin = (point.tsMs - knowledgeTsMs).toDouble() / MINUTE_MS
                val bridge = slopeBridgeDelta(
                    horizonMin = horizonMin,
                    durationMin = bridgeDurationMin,
                    stepMin = bridgeStepMin,
                    anchorMmol = anchorMmol,
                    firstForecastMmol = firstForecast.mmol,
                    observedSlopeMmolPerMin = displayedSlope,
                )
                point.copy(
                    mmol = point.mmol + bridge,
                    lo = point.lo + bridge,
                    hi = point.hi + bridge,
                    loMid = point.loMid + bridge,
                    hiMid = point.hiMid + bridge,
                )
            }
        }
        if (publishWhatIf) {
            // THE PUBLICATION KEY = THE LOOKUP KEY.
            //
            // Publication used to key on knowledgeTsMs — "now" — while the
            // screen looks the profile up by the first point of the SHOWN
            // forecast, i.e. by the time of the last reading. The two clocks,
            // which can be anywhere from seconds to five minutes apart,
            // almost never matched, so `physioWhatIf` returned null and
            // What-if silently fell back to the twin's legacy core — with a
            // DIFFERENT ISF. The user spotted this visually before any test
            // did: the line and "What if" were computed differently. On the
            // device before the fix, the log read "profile found false · arm
            // LEGACY-CORE".
            //
            // Now the key is the first point of the same series the screen
            // will draw, so what gets compared is not two times but one value.
            HybridShadowRegistry.updateWhatIf(
                points.firstOrNull()?.tsMs ?: knowledgeTsMs,
                activityExposure,
                displayHybridPoints,
                modelOverride = personModel,
                // THE CAUSE, NOT THE SYMPTOM. The flag was not being passed, so
                // the physio arm's profile was always written to the LEGACY
                // slot, and latestPhysioWhatIf stayed null forever — so "What
                // if" was computed on a different engine with a different ISF
                // while the line was drawn from physio. One word.
                physio = true,
                macroTiming = physioMacroTiming,
                // The lens slope, not a fixed unit: the screen lays these
                // deltas on the calibrated line. See `HybridWhatIfProfile.displayScale`.
                displayScale = diaPilotModel.foodRiseScale,
            )
        }
        // THE LEDGER DEDUP HASH — A BYPRODUCT, NOT A GOAL.
        //
        // `Forecaster` used to build it itself, and for that it called
        // `FoodSources.activeFoods` — the entire legacy food layer — on
        // EVERY pass and for no other reason. Computing the model just for a
        // hash that guards a row about a different model is a closed loop,
        // and a "properly computed hash" would only have reinforced it: it
        // would have legitimized a computation that exists only for itself.
        //
        // Here the same quantities are already computed — the forecast was
        // computed from them.
        //
        // THE CLOCK DOES NOT ENTER HERE, unlike `inputHash` below: the hash
        // must change when NEW KNOWLEDGE appears, not when a minute passes.
        // With `knowledgeTsMs` inside it, it would change on every pass and
        // would deduplicate nothing.
        val dedupHash = listOf(
            causalFoodSnapshotSignature(causalFood),
            causalBoluses.joinToString(",") { "${it.tsMs}:${it.units}" },
            causalBasals.joinToString(",") { "${it.tsMs}:${it.units}" },
        ).joinToString("|").hashCode().toString(16)
        val inputHash = listOf(
            sourceTsMs,
            sourceRawMmol,
            knowledgeTsMs,
            rawHistory.joinToString(",") { "${it.tsMs}:${it.mmol}" },
            causalFoodSnapshotSignature(causalFood),
            evidenceRevisionIds.joinToString(","),
            causalBoluses.joinToString(",") { "${it.tsMs}:${it.units}:${it.contextIds.sorted()}" },
            causalBasals.joinToString(",") { "${it.tsMs}:${it.units}:${it.contextIds.sorted()}" },
            activityExposure,
            asleep,
            sleepDebt,
            hoursSinceWake,
            fullCausalInsulinDisplay,
            contextFlags.sorted().joinToString(","),
        ).joinToString("|").hashCode().toString(16)
        // THIS ARM JUDGES ITSELF.
        //
        // health and healthReasons used to arrive from liveMetadata, i.e. from
        // the legacy twin, and two of its five reasons were properties of a
        // DIFFERENT model: the legacy core's episode count and legacy
        // corridor. On the device this produced "effectively zero fresh
        // corrections" on nearly EVERY pass — the overwhelming majority of
        // runs in a day came back LIMITED — even though physio does not use
        // that core, and the corridor draws its own, computed a dozen lines
        // above.
        //
        // The threshold for the alert does not change: HypoAlertNotifier
        // rejects STALE and INSUFFICIENT_DATA, and lets LIMITED through.
        // What is being fixed is what the app SAYS ABOUT ITSELF, not when it rings.
        val ownHalfWidth60 = points.minByOrNull {
            kotlin.math.abs(it.tsMs - (anchorTsMs + 60L * 60_000))
        }?.let { p -> (p.hi - p.lo) / 2.0 } ?: 0.0
        // THE CORRIDOR IS ITS OWN TOO, AND THIS IS NOT COSMETIC.
        //
        // The `corridor` field used to arrive from the legacy template: a
        // parametric width from a DIFFERENT model, next to the band this arm
        // drew itself. The field has no live reader right now (the ledger
        // writes regime and health, the chart takes hi/lo from the points),
        // but "nobody reads it" is not "it's fine to lie" — that is exactly
        // how numbers that later get quoted come into being.
        //
        // The fit is simple and calibrated: w(Δt) = w0 + k·√Δt by least
        // squares through the half-widths of the arm's OWN points. Asymmetry
        // and an internal band are not claimed — for physio they live in the
        // points themselves.
        val ownCorridor = run {
            val xs = ArrayList<Double>(points.size)
            val ys = ArrayList<Double>(points.size)
            for (p0 in points) {
                val dt = (p0.tsMs - anchorTsMs) / 60_000.0
                if (dt <= 0.0) continue
                xs.add(kotlin.math.sqrt(dt))
                ys.add((p0.hi - p0.lo) / 2.0)
            }
            if (xs.size < 2) com.diapilot.core.twin.Corridor(w0 = ownHalfWidth60, k = 0.0)
            else {
                val n = xs.size
                val mx = xs.sum() / n
                val my = ys.sum() / n
                var sxy = 0.0
                var sxx = 0.0
                for (i in 0 until n) {
                    sxy += (xs[i] - mx) * (ys[i] - my)
                    sxx += (xs[i] - mx) * (xs[i] - mx)
                }
                val k = if (sxx > 0.0) sxy / sxx else 0.0
                // A band cannot narrow with distance, and w0 cannot be negative:
                // a least-squares line through a noisy band can produce both.
                val kk = k.coerceAtLeast(0.0)
                com.diapilot.core.twin.Corridor(
                    w0 = (my - kk * mx).coerceAtLeast(0.0),
                    k = kk,
                )
            }
        }
        val ownVerdict = com.diapilot.core.twin.ForecastHealthV1.evaluate(
            anchorAgeMs = knowledgeTsMs - anchorTsMs,
            evidenceAvailable = true,
            effectiveEvidence = (physioArtifact?.globalIsf?.identifyingEpisodes ?: 0).toDouble(),
            evidenceCount = physioArtifact?.globalIsf?.identifyingEpisodes ?: 0,
            // THE ACTUAL width at 60, NOT `ownCorridor.halfWidth(60.0)`.
            // The fit below is a summary over the whole horizon, and at 60 it
            // gives a smoothed number; the trust verdict must judge the band
            // the arm actually DREW, or the exact defect that health was
            // split by arm to fix repeats. The difference is small — which
            // makes it easier to fold one into the other unnoticed.
            corridorHalfWidth60 = ownHalfWidth60,
            // Physio does not blend a momentum tail, so it says so. Inert
            // either way while `minuteStreamAvailable` is true: the reason
            // fires only when BOTH are false. Named rather than inherited so
            // the inertness is visible instead of accidental.
            momentumAvailable = false,
            minuteStreamAvailable = true,
            sensorSuspect = sensorSuspect?.toString(),
        ).let { v ->
            // THE GLUCOSE FLOOR — THIS IS A REASON, NOT COSMETICS.
            //
            // A trajectory that reaches HYBRID_FLOOR_MMOL does not predict
            // severe hypoglycemia — it reports that the model has gone
            // outside the range where it means anything. Clamping it and
            // staying silent would replace an absurd number with a plausible
            // one, i.e. hide a refusal; so it goes into the reasons and drops
            // the status to LIMITED.
            if (hybrid.floorClampedPoints == 0) v else com.diapilot.core.twin.ForecastHealthV1.Verdict(
                health = com.diapilot.core.twin.ForecastHealth.LIMITED,
                reasons = v.reasons + com.diapilot.core.twin.HealthReason.FloorClamped(
                    com.diapilot.core.hybrid.HYBRID_FLOOR_MMOL, hybrid.floorClampedPoints,
                ),
            )
        }
        android.util.Log.i(
            "HybridShadow",
            "health: physio ${ownVerdict.health} (reasons ${ownVerdict.reasons.size}) · " +
                "corridor@60 %.2f".format(java.util.Locale.ROOT, ownHalfWidth60),
        )
        return HybridShadowRun(
            result = ForecastResult(
                points = points,
                // THIS ARM ASSEMBLES ITS RESULT ENTIRELY ITSELF.
                //
                // This used to be `liveMetadata.copy(...)` — a template taken
                // from the legacy twin — and everything copy did not list
                // arrived from a DIFFERENT model: regime, corridor, momentum,
                // sensor verdict. The same class of defect as the inherited
                // `health`, only quieter: the fields are not highlighted, they
                // are simply invisible.
                regime = regime,
                corridor = ownCorridor,
                momentumUsed = false,
                modelVersion = "${personModel.modelVersion}-kotlin",
                health = ownVerdict.health,
                healthReasons = ownVerdict.reasons,
                kernelScale = 1.0,
                // The sensitivity THIS forecast integrated, read off the very
                // model it ran on. Leaving it null blanked the app's own
                // "the forecast used sensitivity X" line for every v11/PHYSIO
                // run — the card that exists precisely so the app cannot
                // announce a sensitivity it never used.
                //
                // No decomposition is claimed: v11 has no autosens dial, and a
                // PHYSIO context/circadian modifier is already folded into
                // personModel.insulin.isf by personModelAt(). When such a
                // modifier is first promoted, split it out here and give it its
                // own label — do NOT reuse todFactor, which the UI renders as
                // "time of day".
                appliedIsf = com.diapilot.core.twin.AppliedIsf(
                    baseMmolPerU = personModel.insulin.isf,
                    todFactor = 1.0,
                    autosensFactor = 1.0,
                    effectiveMmolPerU = personModel.insulin.isf,
                ),
                sensorSuspect = sensorSuspect,
            ),
            inputHash = inputHash,
            dedupHash = dedupHash,
            producedAnchorTsMs = knowledgeTsMs,
            producedAnchorMmol = points.firstOrNull()?.mmol ?: anchorMmol,
            scoringCalibration = com.diapilot.core.analysis.MeterCalibration(
                slope = diaPilotModel.foodRiseScale,
                interceptMmol = diaPilotModel.meterInterceptMmol,
                nChecks = 0,
                transient = null,
                validFromMs = diaPilotModel.calEpochStartMs,
            ),
        )
    }


    /**
     * Delegates to [com.diapilot.core.analysis.ActivityExposureV1] — the same
     * function the off-device stand now calls. The arithmetic used to live here
     * only, which is exactly why every harness measurement ran with an exposure
     * of zero while production passed a real one.
     */
    internal fun activityExposure(
        store: CollectorStore,
        tsMs: Long,
        tauMin: Double,
    ): Double {
        val oldest = FoodEraSettings.current().clampFrom(
            tsMs - com.diapilot.core.analysis.ActivityExposureV1.LOOKBACK_MIN * MINUTE_MS,
        )
        return com.diapilot.core.analysis.ActivityExposureV1.at(
            store.steps(oldest, tsMs), tsMs, tauMin, oldest,
        )
    }

    internal fun sleepFeatures(
        store: CollectorStore,
        tsMs: Long,
    ): Triple<Double, Double, Double> {
        val sessions = store.sleepSessions(FoodEraSettings.current().clampFrom(tsMs - 48L * 60L * MINUTE_MS), tsMs)
            .sortedBy { it.startMs }
        val asleep = if (sessions.any { tsMs in it.startMs..it.endMs }) 1.0 else 0.0
        val previous = sessions.lastOrNull { it.endMs <= tsMs }
            ?: return Triple(asleep, 0.0, 0.0)
        val durationHours = (previous.endMs - previous.startMs) / 3_600_000.0
        val debt = maxOf(0.0, 8.0 - durationHours)
        val hoursSinceWake = min(24.0, (tsMs - previous.endMs) / 3_600_000.0)
        return Triple(asleep, debt, hoursSinceWake)
    }
}
