package io.github.obdosok.diapilot.data

import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.analysis.mealConceptComponents
import com.diapilot.core.hybrid.HybridFoodEvent
import com.diapilot.core.hybrid.HybridForecastEngine
import org.json.JSONObject
import java.io.InputStream
import kotlin.math.roundToInt

data class HybridFoodObservation(
    val status: String,
    val amplitudeMmol: Double?,
    val onsetMin: Int?,
    val levelMaxMin: Int?,
    val plateauMin: Int?,
    val observedWindowMin: Int?,
    val levelMaxCensored: Boolean,
    val plateauCensored: Boolean,
    val sensorUnstable: Boolean,
    val insulinSubtracted: Boolean = true,
    val method: String = "CGM after insulin subtraction",
    val confidence: Double? = null,
    val latePhaseObserved: Boolean = !plateauCensored,
    val neighbourMmol: Double = 0.0,
    val neighbourResolved: Boolean = true,
    val neighbourQuality: String? = null,
    /** The recovered checkpoint curve — see [com.diapilot.core.analysis.MealObservation.curveTaus]. */
    val curveTaus: List<Double> = emptyList(),
    val curveMmol: List<Double> = emptyList(),
)

/** What a History card's amplitude/timing is based on (stored in the projection cache). */
object FoodBasis {
    const val DISH_PROFILE = "dish_profile"
    const val GLOBAL_CS = "global_cs"
    const val MACROS_DURATION = "macros_duration"
}

/** The carb kinetics of a dish as the History card prints them (rendered in the UI language). */
data class FoodKineticsLine(
    val fastPct: Int,
    val mediumPct: Int,
    val slowPct: Int,
    val form: String,
    /** Where the model's trace tail ends, when it runs well past the plateau. */
    val tailEndMin: Int?,
)

data class HybridFoodReadout(
    val amplitudeMmol: Double,
    val onsetMin: Int,
    val peakMin: Int,
    /**
     * When half the modelled rise has arrived.
     *
     * The card shows THIS rather than [peakMin]. Measured on the shipped engine:
     * for a fixed carbohydrate amount the reported peak jumps around
     * non-monotonically as fat rises, because the arrival curve is two-humped
     * and the argmax jumps between humps. Half-arrival is a level crossing and
     * stays monotone.
     */
    val halfArrivalMin: Int = peakMin,
    val durationMin: Int,
    val group: String,
    val componentBased: Boolean,
    val observed: HybridFoodObservation? = null,
    val carbsG: Double? = null,
    val modelLabel: String = "Legacy v11",
    val amplitudeBasis: String = FoodBasis.DISH_PROFILE,
    val timingBasis: String = FoodBasis.DISH_PROFILE,
    val timingTemplateId: String? = null,
    val timingSource: String? = null,
    val kinetics: FoodKineticsLine? = null,
    /** Protein/fat of the note, so the card can show macros and calories — the
     *  user judges the LLM's parse by them. Null when the note carries no macro line. */
    val proteinG: Double? = null,
    val fatG: Double? = null,
    // The MODEL's cumulative rise (mmol) sampled on [com.diapilot.core.analysis.DECONV_CHECKPOINTS]
    // — the same grid the recovered fact curve uses, so the card can draw the
    // two as comparable spark rows. Cluster-adjusted when the dish is in a meal.
    /** What the CHART draws: the dish's own curve when it has one, the mixture
     *  otherwise. */
    val modelCurveMmol: List<Double> = emptyList(),
    /**
     * The COMPOSITION route alone, always — the same meal read from its carbs,
     * fat and calories with the learned dish curve deliberately withheld.
     *
     * Empty when the dish has no pool, because then it is the same line as
     * [modelCurveMmol] and drawing it twice would invent an agreement.
     *
     * The point of carrying both is that the card can put memory, composition
     * and what actually happened side by side. The whole «dish or macros»
     * argument was fought this week on 29 episodes inside a thin arm, half of
     * it unanswerable; on the card it accumulates on every meal.
     */
    val mixtureCurveMmol: List<Double> = emptyList(),
    /**
     * WHAT THE CLUSTER ACTUALLY DID TO THIS DISH — null when it stood alone.
     *
     * Review finding: every History card was built from the dish's OWN curve
     * while the forecast beside it drew the dish inside a shared gastric pipe.
     * For a light dish eaten shortly after a heavy one those are not close:
     * its own curve says half arrived quickly, the pipe says it waited behind
     * the earlier dish's calories. The card was quietly showing a
     * counterfactual.
     *
     * Three separate quantities, deliberately not merged:
     *  - [halfArrivalMin] / [durationMin] — the dish alone, unchanged;
     *  - [clusterHalfArrivalMin] / [clusterDurationMin] — what was applied;
     *  - [clusterPriorRealised] — how much of everything eaten before it had
     *    already arrived when this dish landed, which is the reason for the gap.
     */
    val clusterMembers: Int = 1,
    val clusterHalfArrivalMin: Int? = null,
    val clusterDurationMin: Int? = null,
    val clusterPriorRealised: Double? = null,
)

/**
 * Shared physiological readouts derived from the installed v11 artifact.
 *
 * Forecast, What-if, chart, widget, watch and companion must use these
 * values instead of rebuilding a second textbook insulin curve from prefs.
 */
object HybridRuntimeMetrics {
    private const val FOOD_LOOKBACK_MS = 12L * 60L * 60_000L
    @Volatile
    private var bundledObservationsByTs: Map<Long, HybridFoodObservation> = emptyMap()
    @Volatile
    private var liveObservationsByTs: Map<Long, HybridFoodObservation> = emptyMap()

    /** Current observational decoration is deliberately not persisted with a
     * deterministic food projection; Stage10 may publish a newer receipt. */
    internal fun currentFoodObservation(tsMs: Long): HybridFoodObservation? = observationAt(tsMs)

    fun installFoodObservations(input: InputStream) {
        val root = JSONObject(input.bufferedReader(Charsets.UTF_8).readText())
        require(root.getInt("schema_version") == 1)
        val rows = root.getJSONArray("observations")
        val method=root.optString("method","CGM after insulin subtraction")
        bundledObservationsByTs = (0 until rows.length()).associate { index ->
            val row = rows.getJSONObject(index)
            row.getLong("ts_ms") to HybridFoodObservation(
                status = row.getString("status"),
                amplitudeMmol = row.optDouble("observed_amplitude_mmol", Double.NaN)
                    .takeUnless(Double::isNaN),
                onsetMin = row.optDouble("observed_onset_min", Double.NaN)
                    .takeUnless(Double::isNaN)?.roundToInt(),
                levelMaxMin = row.optDouble("observed_level_max_min", Double.NaN)
                    .takeUnless(Double::isNaN)?.roundToInt(),
                plateauMin = row.optDouble("observed_plateau_min", Double.NaN)
                    .takeUnless(Double::isNaN)?.roundToInt(),
                observedWindowMin = row.optDouble("observed_window_min", Double.NaN)
                    .takeUnless(Double::isNaN)?.roundToInt(),
                levelMaxCensored = row.getBoolean("level_max_censored"),
                plateauCensored = row.getBoolean("plateau_censored"),
                sensorUnstable = row.getBoolean("sensor_unstable"),
                insulinSubtracted = true,
                method = method,
            )
        }
    }

    /** Current note-anchored meal observations from TwinCache's production
     * deconvolution. The insulin kernel has already been removed (added back to
     * the observed glucose trace). Whole-meal rows only: component rows are not
     * independent observations of the full meal shown in History. */
    fun installLiveFoodObservations(
        corpus:List<com.diapilot.core.analysis.MealObservation>,
    ) {
        liveObservationsByTs=corpus.asSequence().filter{it.component==null}
            .groupBy{it.onsetMs}.mapValues{(_,rows)->
                val row=rows.maxByOrNull{it.confidence}!!
                HybridFoodObservation(
                    status=when {
                        !row.neighbourResolved->"overlap_unresolved"
                        row.peakObserved&&row.tailObserved->"deconvolved_peak_and_tail"
                        row.peakObserved->"deconvolved_peak"
                        else->"deconvolved_censored"
                    },
                    amplitudeMmol=row.peakRise+(if(row.tailObserved)row.tailRise else 0.0),
                    onsetMin=row.onsetLagMin?.roundToInt(),
                    levelMaxMin=row.ttpMin.roundToInt(),
                    // MealObservation carries whether the late phase was seen,
                    // but not a defensible exact plateau minute.
                    plateauMin=null,
                    observedWindowMin=null,
                    levelMaxCensored=!row.peakObserved,
                    plateauCensored=true,
                    sensorUnstable=false,
                    insulinSubtracted=true,
                    method="live note-anchored deconvolution; insulin kernel removed",
                    confidence=row.confidence,
                    latePhaseObserved=row.tailObserved,
                    curveTaus=row.curveTaus,
                    curveMmol=row.curveMmol,
                    neighbourMmol=row.neighbourMmol,
                    neighbourResolved=row.neighbourResolved,
                    neighbourQuality=row.neighbourQuality,
                )
            }
    }

    // Preserve the bundled audit's richer plateau/window measurement, but merge
    // current overlap diagnostics into it. Choosing either map wholesale hid the
    // neighbour defect on every older meal present in the bundled artifact.
    private fun observationAt(tsMs:Long):HybridFoodObservation? {
        val bundled=bundledObservationsByTs[tsMs]
        val live=liveObservationsByTs[tsMs]
        if(bundled==null)return live
        if(live==null)return bundled
        return bundled.copy(
            status=if(live.status=="overlap_unresolved")live.status else bundled.status,
            confidence=live.confidence?:bundled.confidence,
            latePhaseObserved=bundled.latePhaseObserved||live.latePhaseObserved,
            neighbourMmol=live.neighbourMmol,
            neighbourResolved=live.neighbourResolved,
            neighbourQuality=live.neighbourQuality,
            method="${bundled.method}; live overlap audit",
        )
    }

    fun model() = HybridShadowRegistry.model()

    fun iobUnits(
        store: CollectorStore,
        tsMs: Long,
    ): Double? {
        val person = model() ?: return null
        val engine = HybridForecastEngine(person)
        val lookbackMs = (person.insulin.tailDurationMin * 60_000.0).toLong()
        return store.boluses(tsMs - lookbackMs, tsMs)
            .asSequence()
            .filter {
                it.units > 0.0 && !com.diapilot.core.analysis.isPrimePurpose(it.purpose)
            }
            .sumOf { bolus ->
                val ageMin = (tsMs - bolus.tsMs) / 60_000.0
                bolus.units * engine.insulinRemainingFraction(ageMin, bolus.units)
            }
    }

    /**
     * PHYSIO IOB is deliberately built from the same artifact CDF as the
     * selected PHYSIO forecast.  ISF/context do not change an IOB *fraction*,
     * so a neutral person context is sufficient here; the measured action
     * curve is still the exact shared one.
     */
    fun physioIobUnits(store: CollectorStore, tsMs: Long): Double? {
        val person=PhysioRuntime.artifact(store,tsMs)?.personModelAt(12.0,emptySet())?:return null
        return iobWithPerson(store,tsMs,person)
    }

    private fun iobWithPerson(store:CollectorStore,tsMs:Long,person:com.diapilot.core.hybrid.HybridPersonModel):Double {
        val engine=HybridForecastEngine(person)
        val lookbackMs=(person.insulin.tailDurationMin*60_000.0).toLong()
        return store.boluses(tsMs-lookbackMs,tsMs).asSequence()
            .filter { it.units>0.0&&!com.diapilot.core.analysis.isPrimePurpose(it.purpose) }
            .sumOf { bolus->bolus.units*engine.insulinRemainingFraction((tsMs-bolus.tsMs)/60_000.0,bolus.units) }
    }

    /**
     * IOB on a chart grid with one bolus read and one engine instance.
     *
     * Calling [iobUnits] for every five-minute point used to turn a 72-hour
     * chart into 865 SQLite queries during every screen refresh.  This is the
     * same formula over the same lookback windows; only the input is fetched
     * once.
     */
    fun iobSeries(
        store: CollectorStore,
        fromMs: Long,
        toMs: Long,
        stepMs: Long = 5L * 60_000L,
    ): List<Pair<Long, Double>> {
        val person = model() ?: return emptyList()
        val engine = HybridForecastEngine(person)
        val lookbackMs = (person.insulin.tailDurationMin * 60_000.0).toLong()
        val boluses = store.boluses(fromMs - lookbackMs, toMs)
            .filter {
                it.units > 0.0 && !com.diapilot.core.analysis.isPrimePurpose(it.purpose)
            }
        return (fromMs..toMs step stepMs).map { tsMs ->
            tsMs to boluses.asSequence()
                .filter { it.tsMs in (tsMs - lookbackMs)..tsMs }
                .sumOf { bolus ->
                    val ageMin = (tsMs - bolus.tsMs) / 60_000.0
                    bolus.units * engine.insulinRemainingFraction(ageMin, bolus.units)
                }
        }
    }

    /** Chart-grid counterpart of [physioIobUnits]. One artifact/engine and one
     * bolus query are reused for the complete grid. */
    fun physioIobSeries(
        store:CollectorStore,fromMs:Long,toMs:Long,stepMs:Long=5L*60_000L,
    ):List<Pair<Long,Double>> {
        val tArt = android.os.SystemClock.elapsedRealtime()
        val person=PhysioRuntime.artifact(store,toMs)?.personModelAt(12.0,emptySet())?:return emptyList()
        val tArt1 = android.os.SystemClock.elapsedRealtime()
        if (tArt1 - tArt >= 200) android.util.Log.i(
            "ForecastPerf", "iob: artifact ${tArt1 - tArt} ms",
        )
        val engine=HybridForecastEngine(person)
        val lookbackMs=(person.insulin.tailDurationMin*60_000.0).toLong()
        val boluses=store.boluses(fromMs-lookbackMs,toMs).filter{it.units>0.0&&!com.diapilot.core.analysis.isPrimePurpose(it.purpose)}
        return (fromMs..toMs step stepMs).map{ts->ts to boluses.asSequence()
            .filter{it.tsMs in (ts-lookbackMs)..ts}
            .sumOf{it.units*engine.insulinRemainingFraction((ts-it.tsMs)/60_000.0,it.units)}}
    }

    /**
     * ONE DOOR TO THE MODEL FOR EVERYONE WHO COMPUTES COB.
     *
     * Set up after a review finding: the forecast line kept dropping while
     * the badge next to it reported a much smaller carbs-on-board number.
     * The numbers were computed three different ways, and none of them
     * matched the line:
     *
     * | reader | food decorated | stomach queue |
     * |---|---|---|
     * | the line (`Forecaster`) | yes | **shared across all dishes** (`state.foodHistory`) |
     * | COB ribbon on the chart | yes | each dish ALONE (`listOf(decorated)`) |
     * | COB number in the header | **no** | each dish ALONE |
     *
     * The caloric queue is the STOMACH, and there is only one. Two dishes
     * eaten close together share it, so the later one queues BEHIND the
     * earlier one and arrives later. The line knows this (`foodDelta` gets
     * the whole `foodHistory`), while both COB readers computed each dish as
     * if the stomach were empty. Hence the mismatch: the line was acting on
     * a meaningfully larger amount of future arrival than the badge reported.
     *
     * The hour and the contexts belong here too: the forecast builds the
     * model as `personModelAt(current hour, contextFlags)`, while every
     * reader used `personModelAt(12.0, emptySet())`. This does not change COB
     * numerically (the hour only moves ISF), but it does affect neighboring
     * readers, and there is no reason to keep two doors for that.
     */
    internal class CobLens(
        val artifact: com.diapilot.core.physio.PhysioArtifactV1,
        val engine: com.diapilot.core.hybrid.HybridForecastEngine,
        /** Decorated and causally visible at request time, IN TIME ORDER. */
        val cluster: List<com.diapilot.core.hybrid.HybridFoodEvent>,
    )

    /**
     * The model, engine and CLUSTER exactly as `Forecaster` builds them.
     *
     * `knownAtMs` cuts off notes the device did not yet know about at [tsMs] —
     * the same causal question as `HybridShadow.causalFoodNotes`, and without
     * it the history ribbon would score the past with today's knowledge.
     */
    internal fun cobLens(store: CollectorStore, tsMs: Long): CobLens? {
        val artifact = PhysioRuntime.artifact(store, tsMs) ?: return null
        val ids = DailyDiscrepancyRuntime.currentFactorIds(store, tsMs)
        val hour = java.util.Calendar.getInstance()
            .apply { timeInMillis = tsMs }.get(java.util.Calendar.HOUR_OF_DAY).toDouble()
        val person = artifact.personModelAt(hour, ids) ?: return null
        val cluster = cobEvents(store, tsMs - FOOD_LOOKBACK_MS, tsMs)
            .filter { (event, knownAt) -> event.tsMs <= tsMs && knownAt <= tsMs }
            // Contexts are taken FROM THE EVENT, not the current ones: the
            // forecast does the same (`HybridShadow` line 492). The meal
            // remembers what context it happened in.
            .map { (event, _) -> artifact.decorateFood(event, event.contextIds) }
            .sortedBy { it.tsMs }
        return CobLens(
            artifact,
            com.diapilot.core.hybrid.physioForecastEngine(person, artifact.macroTiming),
            cluster,
        )
    }

    /**
     * COB ON THE PHYSIO ARM — the same food the forecast line is drawing.
     *
     * IOB grew a physio branch before COB did, so for a while this arm's chart
     * showed carbohydrate-on-board computed by the LEGACY food model — back
     * then `HybridForecastEngine(person)` with no policies meant the dish
     * dictionary plus a flat 30 g/h with no calories anywhere — right beside a
     * forecast built from the macro
     * mixture, the caloric queue and (where the user has taught one) the dish's own
     * measured curve. Two models answering "how much is still on board", one
     * pixel apart.
     *
     * The KDoc a hundred lines above this one describes the identical failure
     * being fixed for insulin. This is the other half of it.
     *
     * The tuning knobs ride on the person model, so the queue set in Settings
     * now reaches this number too — before, moving kcal/h changed the
     * forecast and left COB untouched.
     */
    fun physioCobSeries(
        store: CollectorStore,
        fromMs: Long,
        toMs: Long,
        stepMs: Long = 5L * 60_000L,
    ): List<Pair<Long, Double>> {
        val artifact = PhysioRuntime.artifact(store, toMs) ?: return emptyList()
        val person = artifact.personModelAt(12.0, emptySet()) ?: return emptyList()
        val engine = com.diapilot.core.hybrid.physioForecastEngine(person, artifact.macroTiming)
        val events = cobEvents(store, fromMs - FOOD_LOOKBACK_MS, toMs)
        val grid = (fromMs..toMs step stepMs).toList()
        if (events.isEmpty()) return grid.map { it to 0.0 }
        // ONE PASS PER EVENT, NOT ONE STOMACH SIMULATION PER PIXEL.
        //
        // The first version asked `foodRemainingFraction` at every grid point.
        // On the physio arm that is not a lookup: the appearance CDF replays the
        // gastric queue MINUTE BY MINUTE from the meal's start to the requested
        // age, and `rawFoodCdf` re-evaluates up to 24 intake slices inside each
        // of those minutes. Over a 72-hour chart at five-minute steps that is
        // ~900 points x events x hundreds of minutes x 24 — tens of millions of
        // inner steps, and the user watched it happen: the screen could hang
        // for minutes after opening the app.
        //
        // `clusteredFoodCdfTimeline` exists for exactly this and says so in its
        // own KDoc — it keeps the queue's state and snapshots it at the
        // requested bins, turning 1+2+...+N work into one sweep. Same numbers,
        // same engine, same cluster treatment; only the traversal changes.
        // The learned-curve lookup that used to sit here went with the one in
        // the forecast. COB and the line still read the SAME food
        // model — which is the property this block exists to hold — and that
        // model is now the macro mixture and the caloric queue for every dish.
        // THE CLUSTER CHANGES AS THE RIBBON PROGRESSES, AND THAT IS A CAUSAL QUESTION.
        //
        // This used to be `listOf(decorated)` — each dish was computed as if
        // the stomach were empty. The line does not compute it that way:
        // `foodDelta` gets the whole `foodHistory`, i.e. the shared caloric
        // queue. They diverged on the same screen (see [cobLens]).
        //
        // The full list cannot simply be substituted: at point t the cluster
        // is the set of dishes that have already HAPPENED by t and that the
        // device already KNEW about. Otherwise the history ribbon would score
        // the past with today's knowledge. The set only changes at moments of
        // `max(tsMs, knownAt)`, so the grid is cut at those points, and within
        // each piece the queue is replayed exactly once — the property
        // `clusteredFoodCdfTimeline` exists for.
        val decorated = events
            .map { (event, knownAt) -> artifact.decorateFood(event, event.contextIds) to knownAt }
            .sortedBy { (event, knownAt) -> maxOf(event.tsMs, knownAt) }
        fun visibleAt(tsMs: Long) = decorated
            .filter { (event, knownAt) -> event.tsMs <= tsMs && knownAt <= tsMs }
            .map { (event, _) -> event }
        val spans = grid.groupBy { visibleAt(it).size }
        val out = HashMap<Long, Double>(grid.size)
        for ((_, points) in spans) {
            val cluster = visibleAt(points.first())
            if (cluster.isEmpty()) { points.forEach { out[it] = 0.0 }; continue }
            val perEvent = cluster.map { event ->
                event to engine.clusteredFoodCdfTimeline(
                    event, cluster, points.map { (it - event.tsMs) / 60_000.0 },
                )
            }
            points.forEachIndexed { i, tsMs ->
                out[tsMs] = perEvent.sumOf { (event, cdf) ->
                    event.carbsG * (1.0 - cdf[i]).coerceIn(0.0, 1.0)
                }
            }
        }
        return grid.map { it to (out[it] ?: 0.0) }
    }


    /**
     * The ONE IOB every surface must show — the curve that produced the
     * forecast standing beside it.
     *
     * Previously five surfaces resolved this independently and three
     * different answers were reachable at once: the artifact curve for the
     * number on the screen, kernel-or-population for the chart directly under
     * it, and the population DIA-300 curve for widget, watch and the Ask Claude
     * context. The user reported the consequence directly: seeing a high
     * reading while IOB says insulin is still active leads to expecting the
     * reading to keep falling — and when it does not, a correction that was
     * actually needed gets skipped. A tail three times longer than the
     * forecast's is not a cautious fallback; it is a different model
     * answering the same question.
     *
     * The population fallback is REMOVED rather than made consistent. With no
     * measured curve there is no honest IOB to show, and null renders as
     * nothing — which is what «unknown» looks like. A number from a model
     * nobody is running looks like knowledge.
     */
    fun surfaceIobUnits(
        store: CollectorStore,
        context: android.content.Context,
        tsMs: Long,
    ): Double? {
        physioIobUnits(store, tsMs)?.let { return it }
        return iobUnits(store, tsMs)
    }

    /** Chart-grid counterpart of [surfaceIobUnits], same precedence, so the
     * rail and the number above it can never disagree. */
    fun surfaceIobSeries(
        store: CollectorStore,
        context: android.content.Context,
        fromMs: Long,
        toMs: Long,
        stepMs: Long = 5L * 60_000L,
    ): List<Pair<Long, Double>> {
        physioIobSeries(store, fromMs, toMs, stepMs)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        return iobSeries(store, fromMs, toMs, stepMs)
    }

    fun physioInsulinLandmarks(store:CollectorStore,asOfMs:Long):com.diapilot.core.hybrid.HybridInsulinLandmarks? =
        PhysioRuntime.artifact(store,asOfMs)?.personModelAt(12.0,emptySet())?.let{HybridForecastEngine(it).insulinLandmarks()}

    fun carbSensitivityMmolPerGram(): Double? =
        model()?.food?.let { it.globalFactor * it.calibration }

    fun insulinPeakMin(): Double? = model()?.let{HybridForecastEngine(it).insulinLandmarks().ratePeakMin}

    fun insulinDurationMin(): Double? = model()?.let{HybridForecastEngine(it).insulinLandmarks().effectEndMin}

    /**
     * Last modeled action point of insulin that is still on board at [nowMs].
     * Used by the chart so its IOB rail does not stop at "now" while the same
     * insulin continues to affect the forward glucose forecast.
     */
    fun activeInsulinEndMs(
        store: CollectorStore,
        nowMs: Long,
        context: android.content.Context? = null,
    ): Long? {
        val artifactTail = PhysioRuntime.artifact(store, nowMs)
            ?.personModelAt(12.0, emptySet())?.insulin?.tailDurationMin
        val tailMin = artifactTail ?: model()?.insulin?.tailDurationMin ?: return null
        val tailMs = (tailMin * 60_000.0).toLong()
        return store.boluses(nowMs - tailMs, nowMs)
            .asSequence()
            .filter {
                it.units > 0.0 && !com.diapilot.core.analysis.isPrimePurpose(it.purpose)
            }
            .map { it.tsMs + tailMs }
            .filter { it > nowMs }
            .maxOrNull()
    }

    private fun foodEvent(
        tsMs: Long,
        text: String,
        carbsG: Double?,
        analysis: String?,
    ): HybridFoodEvent? {
        // MOVED TO core so the laptop stand feeds the engine the
        // SAME event the phone does instead of rebuilding one (discipline #7).
        return com.diapilot.core.hybrid.hybridFoodEventFromNote(tsMs, text, carbsG, analysis)
    }

    /**
     * THE LIVE FOOD-ADMISSION RULE — `internal`, so a test guards it.
     *
     * The same invariant used to be checked through `FoodSources.causalFoods`,
     * which was removed along with the rest of that layer once it had no
     * production caller left. A test guarding a function nobody uses guards
     * nothing, so the checks moved here — this is the path food actually
     * takes into COB.
     *
     * The difference from the removed function is DELIBERATE: a note without
     * `carbsKnownAtMs` is not accepted here at all, whereas the old function
     * returned it with `knownAtMs = Long.MAX_VALUE`. The latter represents
     * "known never"; the former is a refusal — the device in
     * `HybridShadow.causalFoodNotes` does the same.
     */
    internal fun cobEvents(
        store: CollectorStore,
        fromMs: Long,
        toMs: Long,
    ): List<Pair<HybridFoodEvent, Long>> =
        store.annotations(fromMs, toMs).mapNotNull { note ->
            if (!com.diapilot.core.analysis.isFoodNote(note)) return@mapNotNull null
            val event = foodEvent(note.tsMs, note.content, note.estCarbs, note.analysis)
                ?: return@mapNotNull null
            // Null means provenance is unknown, not "known at intake". Strict
            // causal runtime/evaluation excludes it; a legacy sensitivity path
            // must opt in explicitly elsewhere.
            note.carbsKnownAtMs?.let { event to it }
        }

    /**
     * Last modeled plateau of food that has already been logged and is still
     * absorbing. This is derived from the exact same event metadata and food
     * timing function as COB and the forecast (including intake duration).
     */
    fun activeFoodEndMs(store: CollectorStore, nowMs: Long): Long? {
        val person = model() ?: return null
        val engine = HybridForecastEngine(person)
        return cobEvents(store, nowMs - FOOD_LOOKBACK_MS, nowMs)
            .asSequence()
            .filter { (event, knownAt) -> event.tsMs <= nowMs && knownAt <= nowMs }
            .map { (event, _) ->
                event.tsMs + (engine.foodTiming(event).tailEndMin * 60_000.0).toLong()
            }
            .filter { it > nowMs }
            .maxOrNull()
    }

    /**
     * THE COB NUMBER IN THE HEADER — ON THE SAME ENGINE AS THE COB RIBBON AND THE LINE.
     *
     * This used to be `HybridForecastEngine(model())` — the model from the
     * registry — while the COB ribbon under the chart computes `physioCobSeries`
     * with the physio engine and the artifact's macro timing. Two different
     * models on one screen, and the one in the header was NOT the one the line
     * is drawn from. An earlier review already named this pair (the COB number
     * in the header and the COB on the chart), but only the ribbon was
     * reconciled at the time.
     *
     * Returns null when there is no artifact: a refusal must look like a
     * refusal, not a number from a different model.
     */
    fun cobGrams(store: CollectorStore, tsMs: Long): Double? {
        val lens = cobLens(store, tsMs) ?: return null
        // THE SAME CLUSTER AS THE LINE. `foodRemainingFraction` asks about one
        // dish in an empty stomach and used to sit here; the shared queue
        // delays arrival, so "how much has not arrived yet" it was undercounting.
        return lens.cluster.sumOf { event ->
            val ageMin = (tsMs - event.tsMs) / 60_000.0
            val arrived = lens.engine
                .clusteredFoodCdfTimeline(event, lens.cluster, listOf(ageMin))
                .first()
            event.carbsG * (1.0 - arrived).coerceIn(0.0, 1.0)
        }
    }
    fun foodReadout(
        text: String,
        carbsG: Double?,
        analysis: String?,
        tsMs: Long? = null,
    ): HybridFoodReadout? {
        val person = model() ?: return null
        val engine = HybridForecastEngine(person)
        return foodReadout(engine, text, carbsG, analysis, tsMs)
    }

    /** Transparent isolated-meal receipt for an explicitly selected model arm. */
    fun foodReadoutForModel(
        person: com.diapilot.core.hybrid.HybridPersonModel,
        text: String,
        carbsG: Double?,
        analysis: String?,
        tsMs: Long? = null,
        macroTiming: com.diapilot.core.hybrid.MacroTimingParamsV1 = com.diapilot.core.hybrid.MacroTimingParamsV1(),
        physioArtifact: com.diapilot.core.physio.PhysioArtifactV1? = null,
    ): HybridFoodReadout? {
        // One constructor — see [physioForecastEngine]. This is the single-row
        // receipt behind "How it was calculated"; built by hand it showed a fatty dish
        // by different rules than the card above it.
        val engine=com.diapilot.core.hybrid.physioForecastEngine(person,macroTiming)
        val baseEvent=foodEvent(0L,text,carbsG,analysis)?:return null
        val event=if(physioArtifact!=null){
            physioArtifact.decorateFood(classifyPhysioFood(baseEvent),emptySet())
        }else baseEvent
        return foodReadout(engine,event,tsMs).copy(
            modelLabel="Physio v1",
            amplitudeBasis=FoodBasis.GLOBAL_CS,
            timingBasis=FoodBasis.MACROS_DURATION,
        )
    }

    /**
     * History projection for one immutable model arm.
     *
     * [HybridForecastEngine] precomputes its kernels in the constructor.  The
     * previous History path constructed one engine per row, which scaled
     * linearly with the row count and became noticeably slow.  A food receipt
     * does not depend on insulin ISF,
     * so all rows with the same artifact/context can safely share one engine.
     */
    fun foodReadoutsForModel(
        person: com.diapilot.core.hybrid.HybridPersonModel,
        notes: List<com.diapilot.core.collector.Annotation>,
        macroTiming: com.diapilot.core.hybrid.MacroTimingParamsV1 = com.diapilot.core.hybrid.MacroTimingParamsV1(),
        physioArtifact: com.diapilot.core.physio.PhysioArtifactV1? = null,
    ): Map<Long, HybridFoodReadout> {
        val engine = com.diapilot.core.hybrid.physioForecastEngine(person, macroTiming)
        return notes.asSequence()
            .filter { com.diapilot.core.analysis.isFoodNote(it) }
            .mapNotNull { note ->
                val base = foodEvent(0L, note.content, note.estCarbs, note.analysis)
                    ?: return@mapNotNull null
                val decorated = if (physioArtifact != null) {
                    physioArtifact.decorateFood(classifyPhysioFood(base), emptySet())
                } else base
                val event = decorated
                Triple(note, event, foodReadout(engine, event, note.tsMs).copy(
                    modelLabel = "Physio v1",
                    amplitudeBasis = FoodBasis.GLOBAL_CS,
                    timingBasis = FoodBasis.MACROS_DURATION,
                ))
            }
            .toList()
            .let { rows -> withClusterCurves(engine, rows) }
            .associate { (note, _, readout) -> note.id to readout }
    }

    /**
     * Fill in what the shared gastric pipe did to each dish.
     *
     * The events above are built at tsMs=0 because a standalone receipt does
     * not need a clock; a cluster does, so they are rebuilt here at their real
     * times. Landmarks are read off the APPLIED curve by level crossing — the
     * same estimator on both sides, so "alone" and "in the meal" readings
     * compare two measurements of one kind rather than a template against a curve.
     */
    private fun withClusterCurves(
        engine: HybridForecastEngine,
        rows: List<Triple<com.diapilot.core.collector.Annotation, HybridFoodEvent, HybridFoodReadout>>,
    ): List<Triple<com.diapilot.core.collector.Annotation, HybridFoodEvent, HybridFoodReadout>> {
        val timed = rows.map { (note, event, _) -> event.copy(tsMs = note.tsMs) }
        // 10-minute grid to 14 hours: fine enough for a card, and the whole row
        // is one pass of the queue rather than one simulation per point.
        val ages = (0..84).map { it * 10.0 }
        return rows.mapIndexed { index, (note, event, readout) ->
            val self = timed[index]
            val cluster = engine.mealClusterAssessment(self, timed)
            if (cluster.memberIds.size <= 1) return@mapIndexed Triple(note, event, readout)
            val curve = engine.clusteredFoodCdfTimeline(self, timed, ages)
            val modelCurve = engine.clusteredFoodCdfTimeline(
                self, timed, com.diapilot.core.analysis.DECONV_CHECKPOINTS,
            ).map { it * readout.amplitudeMmol }
            fun cross(f: Double): Int? =
                curve.indexOfFirst { it >= f }.takeIf { it >= 0 }?.let { ages[it].toInt() }
            // ONLY THIS MEAL'S EARLIER MEMBERS. Filtering the whole batch by
            // timestamp counted an earlier meal and a later, unrelated snack as
            // "the previous dish", and since both were long finished the card
            // read the earlier dish as nearly fully absorbed beside a current
            // dish that was under half arrived. The user spotted the
            // inconsistency: a high absorption percentage claimed while the
            // current dish still had a substantial caloric queue against it.
            val memberIds = cluster.memberIds.toSet()
            val prior = timed.filter {
                it.tsMs < self.tsMs && "${it.tsMs}:${it.carbsG}" in memberIds
            }
            val realised = if (prior.isEmpty()) null else {
                val grams = prior.sumOf { it.carbsG }
                if (grams <= 0.0) null else prior.sumOf { p ->
                    engine.clusteredFoodCdf(p, timed, (self.tsMs - p.tsMs) / 60_000.0) * p.carbsG
                } / grams
            }
            Triple(
                note, event,
                readout.copy(
                    modelCurveMmol = modelCurve,
                    clusterMembers = cluster.memberIds.size,
                    clusterHalfArrivalMin = cross(.50),
                    clusterDurationMin = cross(.90),
                    clusterPriorRealised = realised,
                ),
            )
        }
    }

    /** Apply the same Stage-8 recipe/form hierarchy used by the live forecast.
     * History receipts previously passed only personModelAt(), losing this
     * event-owned metadata and silently falling back to legacy 25/95/160. */
    private fun classifyPhysioFood(event:HybridFoodEvent):HybridFoodEvent {
        return event.copy(physicalForm=null,carbClass=null)
    }

    private fun foodReadout(
        engine: HybridForecastEngine,
        text: String,
        carbsG: Double?,
        analysis: String?,
        tsMs: Long?,
    ): HybridFoodReadout? {
        val event = foodEvent(0L, text, carbsG, analysis) ?: return null
        return foodReadout(engine,event,tsMs)
    }

    private fun foodReadout(
        engine: HybridForecastEngine,
        event: HybridFoodEvent,
        tsMs: Long?,
    ): HybridFoodReadout {
        val isComposite = event.components.isNotEmpty()
        val amplitude = engine.foodAmplitude(event).first
        val timing = engine.foodTiming(event)
        return HybridFoodReadout(
            amplitudeMmol = amplitude,
            onsetMin = timing.onsetMin.roundToInt(),
            peakMin = timing.peakMin.roundToInt(),
            halfArrivalMin = timing.medianArrivalMin.roundToInt(),
            durationMin = timing.plateauMin.roundToInt(),
            group = if(event.kineticFeatures!=null)"feature-mixture-v2" else "neutral-fallback-v2",
            componentBased = isComposite,
            observed = tsMs?.let(::observationAt),
            carbsG = event.carbsG,
            proteinG = event.proteinG ?: event.kineticFeatures?.proteinG,
            fatG = event.fatG ?: event.kineticFeatures?.fatG,
            modelCurveMmol = com.diapilot.core.analysis.DECONV_CHECKPOINTS.map { tau ->
                amplitude * engine.foodCdf(event, tau)
            },
            // There is no second route to draw any more: every dish takes the
            // macro mixture (or the prior when its macros are unknown), so the
            // «this is what the mixture would have said» comparison line has
            // nothing to compare against.
            mixtureCurveMmol = emptyList(),
            timingTemplateId=if(event.kineticFeatures!=null)"continuous-mixture" else "base-default",
            timingSource=if(event.kineticFeatures!=null)"structured-feature-mixture-v2"
                else "physiological_prior",

            kinetics=event.kineticFeatures?.normalized()?.let{
                // Kept compact: the user reads the three percentages as an
                // input control, but not the provenance tag — that moved off
                // the card. Form stays: liquid-vs-solid is a decision input too.
                FoodKineticsLine(
                    fastPct=Math.round(it.fastFraction*100).toInt(),
                    mediumPct=Math.round(it.mediumFraction*100).toInt(),
                    slowPct=Math.round(it.slowFraction*100).toInt(),
                    form=it.physicalForm.name,
                    tailEndMin=if(timing.tailEndMin>timing.plateauMin+30)Math.round(timing.tailEndMin).toInt() else null,
                )
            },
        )
    }
}
