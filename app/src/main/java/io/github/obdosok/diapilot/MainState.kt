package io.github.obdosok.diapilot

/**
 * UI state assembly: [UiState] and the [loadState] pass that builds it
 * from the store. Split out of MainActivity — the activity hosts, this
 * file computes.
 */

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.LabeledMeal
import com.diapilot.core.collector.MealEvent
import com.diapilot.core.collector.Reading
import com.diapilot.core.collector.relabelMeal
import io.github.obdosok.diapilot.collect.CollectorService
import io.github.obdosok.diapilot.collect.MealNotifier
import io.github.obdosok.diapilot.data.AdaptiveIsfRuntime
import io.github.obdosok.diapilot.data.CalibratedGlucose
import io.github.obdosok.diapilot.data.DailyDiscrepancyRuntime
import io.github.obdosok.diapilot.data.FoodCalculationRegistry
import io.github.obdosok.diapilot.data.FoodEraSettings
import io.github.obdosok.diapilot.data.ForecastLedger
import io.github.obdosok.diapilot.data.Forecaster
import io.github.obdosok.diapilot.data.ModelCalibration
import io.github.obdosok.diapilot.data.HealthConnectSync
import io.github.obdosok.diapilot.data.HistoryFoodProjectionCache
import io.github.obdosok.diapilot.data.HybridFoodReadout
import io.github.obdosok.diapilot.data.HybridRuntimeMetrics
import io.github.obdosok.diapilot.data.PhysioForecastRegistry
import io.github.obdosok.diapilot.data.HybridWhatIfProfile
import io.github.obdosok.diapilot.data.LedgerRetention
import io.github.obdosok.diapilot.data.ManualInsulinRuntime
import io.github.obdosok.diapilot.data.MeterCalCache
import io.github.obdosok.diapilot.data.MinuteCalCache
import io.github.obdosok.diapilot.data.PhysioRuntime
import io.github.obdosok.diapilot.data.PhysioTuning
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.SqliteCollectorStore
import io.github.obdosok.diapilot.data.Stores
import io.github.obdosok.diapilot.data.TwinCache
import io.github.obdosok.diapilot.data.Units
import io.github.obdosok.diapilot.data.displayHistory
import io.github.obdosok.diapilot.data.tir24h
import io.github.obdosok.diapilot.data.trustedHistory
import io.github.obdosok.diapilot.i18n.StatusText
import io.github.obdosok.diapilot.i18n.localized
import io.github.obdosok.diapilot.ui.AnalysisScreen
import io.github.obdosok.diapilot.ui.GlucoseChart
import io.github.obdosok.diapilot.ui.parseMealParts
import io.github.obdosok.diapilot.ui.theme.DiaPilotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class UiState(
    val readings: Long = -1,
    val insulin: Long = -1,
    val unlabeled: List<MealEvent> = emptyList(),
    val topLabels: List<Pair<Long, String>> = emptyList(),
    val chartFrom: Long = 0,
    val chartTo: Long = 0,
    val chartNavActive: Boolean = false,
    val chartReadings: List<GlucosePoint> = emptyList(),
    val chartBoluses: List<BolusPoint> = emptyList(),
    val chartMeals: List<MealEvent> = emptyList(),
    val labeled: List<LabeledMeal> = emptyList(),
    val lastReading: Reading? = null,
    val lastBolus: BolusPoint? = null,
    val iobUnits: Double? = null,
    val iobTsMs: Long = 0,
    /** Bottom-lane curves over the chart window: what is still to come from the
     *  insulin (units, from THIS body's kernel) and from the food (grams). */
    val iobSeries: List<Pair<Long, Double>> = emptyList(),
    val cobSeries: List<Pair<Long, Double>> = emptyList(),
    /** False = the IOB lane is the textbook curve, the kernel can't say yet. */
    val iobIsPersonal: Boolean = false,
    val prediction: List<com.diapilot.core.twin.PredictedPoint> = emptyList(),
    val comparisonPrediction: List<com.diapilot.core.twin.PredictedPoint> = emptyList(),
    val minuteReadings: List<GlucosePoint> = emptyList(),
    val lastMinute: Reading? = null,
    val annotations: List<com.diapilot.core.collector.Annotation> = emptyList(),
    val frequentNotes: List<String> = emptyList(),
    val carbsByFood: Map<String, Double> = emptyMap(),
    // Composite memory: meal name -> its component names from past LLM
    // splits (e.g. a restaurant meal name -> its list of component dishes).
    val mealComponents: Map<String, List<String>> = emptyMap(),
    // Recipe splits: normKey → [(component, count, gramsPerUnit)]. A composite
    // logs under its own name; these ride as editable composition metadata.
    val compositeParts: Map<String, List<Triple<String, Int, Double?>>> = emptyMap(),
    // Recent food notes verbatim — one-off dishes (e.g. "restaurant, cold soup")
    // never reach labels/grams dictionaries but should still autocomplete.
    val recentFoodTexts: List<String> = emptyList(),
    // Verbatim note -> its logged grams (lowercase key). Comma-named dishes
    // (e.g. "restaurant, soup") can't live in carbsByFood — normalization
    // cuts at the comma and both restaurant dishes would collide on one key.
    val recentFoodCarbs: Map<String, Double> = emptyMap(),
    val photoByFood: Map<String, String> = emptyMap(),
    val hcGranted: Boolean? = null,  // null = Health Connect unavailable
    val stepsGranted: Boolean? = null,  // steps read permission (added later than HR/sleep)
    /** The model has been off from reality AND the meter calibration is old —
     *  suggest a fingerstick (fixes calibration, resolves the divergence). */
    // Merged activity bouts (HR + steps + manual notes, deduped) over the
    // chart window — drawn as a lane so the user SEES what the model counts.
    val activityWindows: List<com.diapilot.core.analysis.ActivityWindow> = emptyList(),
    /** Does the MODEL actually apply an activity effect right now? The lane may
     *  only draw the post-bout reach when it does — otherwise the screen shows
     *  the user a drain the calculation never applied (activityDrop = 0 on
     *  this body until the calibrated detector leaves the coefficient unlearned). */
    val activityEffectActive: Boolean = false,
    val deltaMmol: Double? = null,
    // Acceleration nuance from the smoothed momentum: "accelerating" /
    // "decelerating" / "turning in ~N min" — the raw arrow can't say this.
    val trendNuance: com.diapilot.core.twin.TrendNuance? = null,
    // Unlogged-meal suggestions (detected rise / meal-sized dose without a
    // note, dish predicted from the hour). Deliberately NON-intrusive: the
    // Today screen shows only a counter; accept/edit/decline live in a dialog.
    val foodSuggestions: List<com.diapilot.core.analysis.FoodSuggestion> = emptyList(),
    // Last canonical composition per dish (normalized name → recall). A repeat dish
    // is repeat composition, so every quick-add path reuses it instead of
    // making the user retype the split.
    val compositionByFood: Map<String, com.diapilot.core.analysis.DishRecall> = emptyMap(),
    // Glucose is moving fast right now — a meter check taken now calibrates
    // blood against lagged interstitial fluid (a false offset), so the meter
    // composer warns.
    val movingFast: Boolean = false,
    // A clean window to CAPTURE calibration data (flat, no active insulin,
    // food settled) + what the model most lacks. Never a dose/eat suggestion.
    val calibrationWindow: com.diapilot.core.analysis.CalibrationWindow? = null,
    val historyNotes: List<com.diapilot.core.collector.Annotation> = emptyList(),
    val labelByOnset: Map<Long, String> = emptyMap(),
    val historyMeals: List<MealEvent> = emptyList(),
    val historyBoluses: List<BolusPoint> = emptyList(),
    val historyMeter: List<com.diapilot.core.collector.Reading> = emptyList(),
    /** Calibrated sensor trace over the history window: lets a meal the
     *  detector never fired on still say what glucose actually did. */
    val historyReadings: List<com.diapilot.core.collector.GlucosePoint> = emptyList(),
    /** The deconvolution's recovered observation per meal, keyed by session
     *  start — what the DISH did once the insulin is added back, as opposed to
     *  what glucose did. Diagnostic: only the uncensored ones are shown. */
    val mealObservations: Map<Long, com.diapilot.core.analysis.MealObservation> = emptyMap(),
    /** Meals the model could not account for, and the answers already given.
     *  Both come STRAIGHT from the twin — the dossier compares a raw rise with a
     *  recovered one, so it must be built on the scale that build used. */
    val mealMarks: List<com.diapilot.core.analysis.MealMark> = emptyList(),
    val chartBasals: List<BolusPoint> = emptyList(),
    val historyBasals: List<BolusPoint> = emptyList(),
    val historyDays: Int = 7,
    val historyAnchorMs: Long? = null,
    val foodLabels: List<String> = emptyList(),
    val bgAtShot: Map<Long, Double> = emptyMap(),  // ts of bolus/basal -> BG then
    val foodMemory: Map<String, com.diapilot.core.analysis.FoodMemory> = emptyMap(),
    val similarFood: Map<String, List<String>> = emptyMap(),  // label -> similar labels
    val typicalMeals: List<String> = emptyList(),  // repeated meals around this hour
    /**
     * The ISF the FORECAST is using right now, with where it came from.
     *
     * Shown on the main screen because the applied value is not the one
     * written in the artifact. It is the artifact's number, ramped by
     * independent days, decayed with a 21-day half-life, possibly overridden
     * in Settings, and possibly re-based by the hour of day. Four
     * transformations between "what was measured" and "what is dividing the
     * user's carbohydrate right now", and none of them was visible.
     *
     * Null when no model is installed — nothing rather than a number from a
     * model nobody is running.
     */
    val appliedIsf: Pair<Double, String>? = null,
    val statusLines: List<String> = emptyList(),  // the always-current one-liner
    val sensitivityLine: String? = null,           // autosens sentence (collapsed detail)
    /** The ISF the live forecast actually applied, factors spelled out. */
    val appliedIsfLine: String? = null,
    // The forecast's own trust label + human reasons (engine-produced).
    val forecastHealth: com.diapilot.core.twin.ForecastHealth? = null,
    val forecastCheck: String? = null,  // hour-ago forecast vs the fact
    val correctionCandidate: BolusPoint? = null,
    val meterCalLine: String? = null,   // active meter-check correction, if any
    // What-if simulator inputs: the personal kernel + carbs scale let the UI
    // superpose a hypothetical bolus/meal onto the ready prediction.
    val twinKernel: List<com.diapilot.core.analysis.KernelPoint> = emptyList(),
    /** Retrospective observation-space profile per visible bolus. It keeps the
     * hour-specific Physio ISF but does not alter live forecast or alerts. */
    val counterfactualKernels:Map<Long,List<com.diapilot.core.analysis.KernelPoint>> = emptyMap(),
    val counterfactualLandmarks:Map<Long,Triple<Double,Double,Double>> = emptyMap(),
    val carbSensMmolPerGram: Double? = null,
    val forecastKernelScale: Double = 1.0,
    val hybridWhatIfProfile: HybridWhatIfProfile? = null,
    val comparisonWhatIfProfile: HybridWhatIfProfile? = null,
    val selectedArmWhatIf: Boolean = false,
    /** Per-note food response from the installed v11 artifact. This replaces
     * detector rise/time numbers in history cards. */
    val hybridFoodReadouts: Map<Long, HybridFoodReadout> = emptyMap(),
    /** Prospective one-hour v11 misses, not detector-selected meal dossiers. */
    // Lab toggle state: the main-screen forecast is showing the fingerprint
    // food model (Phase 1) instead of the live one.
    val insulinDiaMin: Double = 300.0,
    val insulinPeakMin: Double = 75.0,
    // Basal reminder: suggested units + usual time label, when due.
    val basalDue: Pair<Double, String>? = null,
    val hypoQuick: Boolean = false,   // show the one-tap dextrose button
    val hypoTablets: Int = 0,         // tablets already logged this episode
    val mgdl: Boolean = true,         // display units (storage stays mmol/L)
    val rangeLo: Double = 3.9,
    val targetMmol: Double = 5.55,
    val rangeHi: Double = 10.0,
    val dayStats: DayStats? = null,   // 24h summary strip under the status
)

/** Keep the already-renderable History slice while a cheap live refresh reads
 * only Today fields. This makes tab switches instant and avoids rebuilding the
 * same seven-day projection for every five-minute CGM packet. Explicit History
 * navigation and data-edit handlers still request a full slice. */
internal fun UiState.withHistoryFrom(previous:UiState)=copy(
    historyNotes=previous.historyNotes,labelByOnset=previous.labelByOnset,
    historyMeals=previous.historyMeals,historyBoluses=previous.historyBoluses,
    historyMeter=previous.historyMeter,historyReadings=previous.historyReadings,
    mealObservations=previous.mealObservations,
    mealMarks=previous.mealMarks,historyBasals=previous.historyBasals,
    historyDays=previous.historyDays,historyAnchorMs=previous.historyAnchorMs,
    bgAtShot=previous.bgAtShot,hybridFoodReadouts=previous.hybridFoodReadouts,
    foodMemory=previous.foodMemory,
)

/**
 * REUSE THE PREVIOUS INSTANCE WHEN THE CONTENT DID NOT CHANGE.
 *
 * Reported by users: History kept flickering and recomputing every minute.
 *
 * The data was never lost — [withHistoryFrom] carries it across the cheap
 * refreshes. What flickered was the REBUILD. `LabelScreen` remembers its whole
 * item list keyed on the history collections, and `remember` compares by
 * equality of the keys; a five-minute safety refresh re-read the same seven days
 * from SQLite and produced fresh List instances with identical contents. Equal
 * by `==`, yes — but the read itself, the sort, the continuation attachment and
 * the per-row model line all ran again, on the main thread, during composition.
 * A journal that redraws itself while being read is not a journal.
 *
 * So after any history pass, each collection is compared with the one already on
 * screen and the OLD instance is kept when they are equal. `remember` then holds,
 * the list is not rebuilt, and the screen does not move. The comparison is a
 * structural equality over data classes — cheap next to what it prevents.
 *
 * This is a stability fix, NOT a caching one: when the content really changed,
 * the new instance goes through exactly as before.
 */
internal fun UiState.stabiliseHistoryAgainst(previous: UiState): UiState {
    fun <T> keep(fresh: T, old: T): T = if (fresh == old) old else fresh
    return copy(
        historyNotes = keep(historyNotes, previous.historyNotes),
        labelByOnset = keep(labelByOnset, previous.labelByOnset),
        historyMeals = keep(historyMeals, previous.historyMeals),
        historyBoluses = keep(historyBoluses, previous.historyBoluses),
        historyMeter = keep(historyMeter, previous.historyMeter),
        historyReadings = keep(historyReadings, previous.historyReadings),
        mealObservations = keep(mealObservations, previous.mealObservations),
        mealMarks = keep(mealMarks, previous.mealMarks),
        historyBasals = keep(historyBasals, previous.historyBasals),
        bgAtShot = keep(bgAtShot, previous.bgAtShot),
        hybridFoodReadouts = keep(hybridFoodReadouts, previous.hybridFoodReadouts),
        foodMemory = keep(foodMemory, previous.foodMemory),
    )
}

// THE NUMBER AND THE CHART, WITHOUT THE MODEL.
//
// Reported by users: if the app is left closed for a while and then opened,
// the home screen's chart and glucose number are stale by exactly how long it
// was closed, and only catch up after ten to twenty seconds.
//
// That is the resume path showing the state Compose remembered from last time
// while the FULL `loadState` runs: it rebuilds the chart, IOB, COB, the food
// memory, the day summary, the status lines and the forecast before anything
// reaches the screen. The readings themselves were never late — a broadcast
// receiver kept writing them while the app was away — so the display was stale
// against a database that was already correct.
//
// This reads the two things the user is actually looking at, and nothing
// else: the newest reading and the chart window. Two queries. The full pass
// replaces it a moment later with the analytics.
/**
 * THE PREVIEW MUST BE ON THE USER'S BLOOD SCALE, like everything else on the screen.
 *
 * It published RAW `store.readings` while the full pass a few hundred lines
 * below builds the same series through `displayHistory`, which applies the meter
 * lens. For the ten seconds between them the chart therefore drew TWO lines a
 * whole calibration apart, offset by tens of mg/dL — and the big number above
 * them was the raw one.
 *
 * That offset is not cosmetic. The measured sensor-below-meter median is
 * +1.33 mmol and it is WORST at the low end, which is exactly where a number
 * read off the screen turns into a decision. Ten seconds of a reading that is
 * two mmol low is ten seconds of the wrong answer to "is this going hypo".
 *
 * The lens is arithmetic over points already in memory, so this costs nothing
 * the preview was buying speed with — the slow part of the full pass is the
 * model, never this.
 */
internal fun loadLivePreview(
    previous: UiState,
    store: CollectorStore,
    context: android.content.Context,
    now: Long = System.currentTimeMillis(),
): UiState {
    val last = CalibratedGlucose.lastReading(store, context)
        ?: store.lastSensorReading() ?: return previous
    // THE WINDOW TRAVELS WITH THE POINTS. Publishing readings without
    // `chartFrom`/`chartTo` left them at their zero defaults on a cold start,
    // so the chart drew an empty viewport labelled with the epoch date, and
    // held it until the full pass finished: a fresh reading above a 1970
    // chart. Before the preview existed the
    // screen at least showed the PREVIOUS window; a partial state that is
    // internally inconsistent is worse than a stale one that is whole.
    val from = now - 72L * 3_600_000L
    val chart = displayHistory(store, context, from, now)
    if (chart.isEmpty()) return previous
    // Nothing newer than what is already shown: leave the instance alone rather
    // than publish an identical state and invalidate every reader (A-35).
    if (previous.lastReading?.tsMs == last.tsMs &&
        previous.chartReadings.size == chart.size &&
        previous.chartFrom == from
    ) {
        return previous
    }
    // Only while the live window is showing. When the user has navigated to a
    // date, the anchored window belongs to that view and this must not drag
    // it back to now — the preview would silently undo the navigation.
    if (previous.chartNavActive || ChartNav.anchorMs != null) return previous
    // THE LAST FORECAST THIS APP SHOWED, rather than an empty chart.
    //
    // See [ForecastLedger.lastShownPrediction]. On a cold process `previous`
    // carries nothing, so this frame used to draw history alone with the
    // window ending at "now" — a few seconds with no forecast line. The line
    // is replaced by the live pass about a second later; this only decides
    // what the first frame shows in the meantime.
    //
    // Carried over rather than re-read when `previous` already has one: a
    // resumed process holds the real thing, and the stored run can only be
    // older than it.
    val stored = previous.prediction.ifEmpty {
        (store as? SqliteCollectorStore)?.let { sqlite ->
            runCatching {
                ForecastLedger.lastShownPrediction(
                    sqlite.readableDatabase, "main", last.tsMs,
                )
            }.getOrNull()
        }.orEmpty()
    }
    return previous.copy(
        lastReading = last,
        chartReadings = chart,
        chartFrom = from,
        prediction = stored,
        // The window must reach the end of that line, or it is drawn off-screen
        // to the right and the frame looks exactly as empty as before.
        chartTo = maxOf(now, stored.lastOrNull()?.tsMs ?: now),
    )
}

/**
 * Raw History facts for the first frame.  Detailed model receipts are an
 * enhancement and must never hold the whole journal behind a minute-long
 * calculation.  The normal [loadState] pass replaces this preview when its
 * projections are ready.
 */
internal fun loadHistoryPreview(
    previous:UiState,
    store:CollectorStore,
    now:Long=System.currentTimeMillis(),
):UiState {
    val historyTo=HistoryNav.anchorMs?.let{minOf(it+24L*3_600_000L,now)}?:now
    val days=HistoryNav.days.coerceIn(7,60)
    val historyFrom=historyTo-days*24L*3_600_000L
    return previous.copy(
        historyNotes=store.annotations(historyFrom,historyTo),
        labelByOnset=store.labeledMeals(limit=2_000).associate{it.event.onsetMs to it.labelName},
        historyMeals=store.meals(historyFrom,historyTo),
        historyBoluses=store.bolusesAll(historyFrom,historyTo),
        historyMeter=store.meterReadings(historyFrom,historyTo),
        mealMarks=store.mealMarks().filter{it.onsetMs in historyFrom..historyTo}.sortedByDescending{it.onsetMs},
        historyBasals=store.basalEvents(historyFrom,historyTo),
        historyDays=days,
        historyAnchorMs=HistoryNav.anchorMs,
    )
}

/** 24-hour summary for the header strip. */
data class DayStats(
    val tirPct: Int,
    val insulinUnits: Double,
    val carbsG: Double,
    val meanMmol: Double,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val kcal: Double? = null,
    val nutritionMeals: Int = 0,
)

// One-tap hypo treatment: each tap grows the same note ("dextrose ×N"),
// grams follow along. Ordinary food at the DB level — the rescue flag
// (preBg < 4.2) already keeps such episodes out of the calibration.
internal const val DEXTROSE_PREFIX = com.diapilot.core.analysis.RESCUE_NOTE_PREFIX

/** Chart history navigation (ephemeral UI state): the date the 3-day window is
 *  anchored to, or null for the live edge. A singleton so [loadState]'s many
 *  call sites don't each need the parameter. */
internal object ChartNav {
    @Volatile
    var anchorMs: Long? = null
}

/** History-tab paging window (ephemeral UI state): how many days back the list
 *  loads. Grows as the user scrolls to the bottom; a date jump sets it to reach
 *  that date. */
internal object HistoryNav {
    @Volatile
    var days: Int = 7

    /** Date jump: load a window ENDING just after this date instead of at now,
     *  so browsing half a year back loads a week there, not everything since. */
    @Volatile
    var anchorMs: Long? = null
}
/** Re-export of the ONE definition in core — this module had a second copy of
 *  the literal, and a button that writes grams into the database must never be
 *  able to drift from the model that reads them. See
 *  [com.diapilot.core.analysis.DEXTROSE_TABLET_G] for why the value is wrong and
 *  why it has not been changed. */
internal const val DEXTROSE_TABLET_G = com.diapilot.core.analysis.DEXTROSE_TABLET_G

internal fun parseDextroseCount(content: String): Int =
    Regex("""×(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 1

/**
 * Render-only line bridging. NFC history backfills the main line at 15-minute
 * spacing; the chart breaks a line at gaps over 12 min (GlucoseChart.GAP_MS),
 * so those points show as loose dots. Linearly fill each span that a
 * backfilled point borders (13–17 min) with 5-minute points, so it reads as a
 * continuous line. Purely cosmetic: this touches only chartReadings — it is
 * never written back and the twin trains on store.readings() directly.
 */
private fun bridgeBackfillSpans(
    pts: List<GlucosePoint>,
    nfcTs: Set<Long>,
    minGapMs: Long = 12L * 60_000,   // == GlucoseChart.GAP_MS: the break point
    maxGapMs: Long = 17L * 60_000,   // NFC 15-min spacing + jitter; longer stays broken
    stepMs: Long = 5L * 60_000,
): List<GlucosePoint> {
    if (pts.size < 2 || nfcTs.isEmpty()) return pts
    val out = ArrayList<GlucosePoint>(pts.size + 16)
    for (i in pts.indices) {
        out.add(pts[i])
        if (i == pts.lastIndex) continue
        val a = pts[i]
        val b = pts[i + 1]
        val gap = b.tsMs - a.tsMs
        if (gap > minGapMs && gap <= maxGapMs && (a.tsMs in nfcTs || b.tsMs in nfcTs)) {
            var t = a.tsMs + stepMs
            while (t < b.tsMs - 60_000) {
                val f = (t - a.tsMs).toDouble() / gap
                out.add(GlucosePoint(t, a.mmol + (b.mmol - a.mmol) * f))
                t += stepMs
            }
        }
    }
    return out
}

/**
 * Runs [block] immediately — the same thing `run { }` does, except that this one
 * is NOT inline, so the block compiles to its own method instead of being folded
 * into the caller.
 *
 * THAT IS THE ENTIRE POINT, and it is measured. ART refused to compile
 * `loadState` at all — «Method exceeds compiler instruction limit: 17872» — so
 * the whole state pass ran INTERPRETED. The first attempt at a fix hoisted the
 * two largest arguments (`hybridFoodReadouts`, 91 lines, and `statusLines`, 61)
 * into private functions and moved the counter by ten instructions: 17862 to
 * 17872. The reason is instructive — both were already arguments to `timed()`,
 * a non-inline local function, so the compiler had ALREADY outlined them. The
 * blocks that actually sit inside `loadState` are the `run { }` ones, because
 * `run` is inline.
 *
 * So the fix is not to restructure the data flow — every one of these blocks
 * closes over a different handful of the pass's fifty locals, and threading
 * those through parameter lists would be a large, risky diff for a mechanical
 * problem. Swapping the inline `run` for a non-inline call outlines the body
 * with no plumbing at all, and the cost is one lambda allocation against a
 * thirty-nine-second pass.
 */
private fun <T> outlined(block: () -> T): T = block()

internal suspend fun loadState(
    store: CollectorStore,
    context: android.content.Context,
    // false = use the cached model as-is (no heavy build) for an instant
    // first frame; the next refresh rebuilds.
    allowBuild: Boolean = true,
    // The cold Today frame does not need History-only cards and anomaly
    // receipts.  Their state arrives in the normal pass immediately after it.
    includeHistory: Boolean = true,
): UiState {
    val perfStartedMs = android.os.SystemClock.elapsedRealtime()
    var perfLapMs = perfStartedMs
    fun lap(label: String) {
        val current = android.os.SystemClock.elapsedRealtime()
        val elapsed = current - perfLapMs
        if (elapsed >= 100) android.util.Log.i("MainStatePerf", "$label in $elapsed ms")
        perfLapMs = current
    }
    fun <T> timed(label: String, block: () -> T): T {
        val started = android.os.SystemClock.elapsedRealtime()
        return block().also {
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            if (elapsed >= 100) {
                android.util.Log.i("MainStatePerf", "$label in $elapsed ms")
            }
        }
    }
    val now = System.currentTimeMillis()
    // Stage-10 History receipts are retrospective and expensive. Restore the
    // disk snapshot before Compose asks for History. A changed source key keeps
    // stable receipts and causes only the 72-hour hot tail to be recomputed.
    (store as? SqliteCollectorStore)?.let { sqlite ->
        val key=FoodCalculationRegistry.sourceKey(sqlite,context,now)
        FoodCalculationRegistry.restore(context,key)
    }
    // Meal detection belongs to TreatmentsPollWorker. Running it from every
    // screen refresh duplicated the same scan while the app was foregrounded.
    // The chart always loads a 3-DAY window (light on battery, the bottom
    // navigator reads well at this span). To browse OLDER history the user
    // picks a date (ChartNav.anchorMs) and the SAME 3-day window slides there —
    // no bulk 14-day load. Live view = window ending now.
    val navAnchor = ChartNav.anchorMs
    val chartToBound = if (navAnchor != null) minOf(navAnchor + 36L * 3_600_000, now) else now
    val chartWindow = chartToBound - 72L * 60 * 60_000
    val activityWindow = chartWindow
    // History-tab list window. Live: ends at now. Date-jump: ends ~1 day after
    // the picked date, so the window is a WEEK there (not everything since).
    // Paging (scroll to bottom) grows [days] older within the same end.
    val historyTo = HistoryNav.anchorMs?.let { minOf(it + 24L * 3_600_000, now) } ?: now
    val historyFrom = historyTo - HistoryNav.days.coerceIn(7, 60) * 24L * 3_600_000

    // Forward simulation from the freshest reading (fresh = within 20 min),
    // insulin + currently-absorbing food.
    val lastMain = store.lastSensorReading()
    // Minute promotion: when the rolling minute→main calibration holds, a
    // fresher OOP2 point (on the calibrated scale) becomes "the last
    // reading" — the header, the hypo button and the prediction anchor gain
    // up to 4 minutes of freshness. The 5-minute stream stays the truth for
    // analytics and the twin's training.
    val minuteCal = MinuteCalCache.get(store, context)
    val lastPromoted = run {
        val lm = store.lastMinuteReading()
        if (minuteCal != null && lm != null && lastMain != null &&
            lm.tsMs > lastMain.tsMs + 90_000 && now - lm.tsMs < 10 * 60_000
        ) {
            val mmol = minuteCal.apply(lm.mmol)
            lastMain.copy(
                tsMs = lm.tsMs,
                mmol = mmol,
                mgdl = mmol * com.diapilot.core.analysis.MGDL_PER_MMOL_F,
                source = "minute_cal",
            )
        } else lastMain
    }
    // Meter-check lens, two layers: a standing per-sensor correction from
    // repeated checks (sensors that read high their whole life) + the
    // freshest check's residual decaying over ~6h. Read-time only —
    // storage stays untouched.
    // Window bounded by the sensor's life when the NFC scan told us it:
    // checks against the previous sensor must not calibrate this one.
    val meterCal = MeterCalCache.get(store, context)
    val meterCalTimeline = MeterCalCache.timeline(store, meterCal)
    fun meterCalAt(tsMs: Long) = meterCalTimeline.lastOrNull {
        tsMs in it.validFromMs..it.validUntilMs
    }
    fun applyMeterLens(p: GlucosePoint): GlucosePoint =
        meterCalAt(p.tsMs)?.apply(p) ?: p
    val calibratedSensorLast = if (meterCal != null && lastPromoted != null) {
        val mmol = meterCal.correctedAt(lastPromoted.tsMs, lastPromoted.mmol)
        lastPromoted.copy(mmol = mmol, mgdl = mmol * com.diapilot.core.analysis.MGDL_PER_MMOL_F)
    } else lastPromoted
    // A fingerstick is a real glucose fact, not merely a calibration command.
    // During a sensor outage it becomes the new open-loop anchor immediately.
    // Never run it through the sensor lens: it is already the blood value.
    val latestMeter = store.meterReadings(now - 36L * 3_600_000, now).lastOrNull()
    val last = listOfNotNull(calibratedSensorLast, latestMeter).maxByOrNull { it.tsMs }

    // Meter-calibrated sensor readings over [fromMs, now] — the SAME scale as
    // the chart line, the header and the live forecast (which anchors on the
    // calibrated `last`). Anything that forecasts from historical readings and
    // is drawn over the chart (the retro line, the "forecast an hour ago" receipt)
    // must anchor here, else it floats on the raw scale and reads high/low by
    // the calibration (×0.80 +31 → a raw 250 draws at 250 next to a calibrated
    // 231 actual).
    fun calibratedReadings(fromMs: Long): List<GlucosePoint> {
        val raw = store.readings(fromMs, now)
        if (meterCalTimeline.isEmpty()) return raw
        val meterTs = store.meterReadings(fromMs, now).mapTo(HashSet()) { it.tsMs }
        return raw.map { if (it.tsMs in meterTs) it else applyMeterLens(it) }
    }
    // TIMED SEPARATELY because the surrounding lap hid it. `anchors+calibration+
    // model` read dramatically higher than the SAME pass with allowBuild=false
    // — and the only difference between those two paths is this line.
    // Inferring a cost by subtracting two laps is exactly the reasoning
    // discipline #1 says to replace with a printed number.
    val model = if (allowBuild) timed("twin build (allowBuild)") {
        TwinCache.get(store, context)
    } else timed("twin forecast-only") {
        TwinCache.getForForecast(store, context)
    }
    // Reuse the already-built production deconvolution for per-episode History
    // receipts. This is an O(n) projection, not a second expensive rebuild.
    HybridRuntimeMetrics.installLiveFoodObservations(
        model?.fingerprintCorpus ?: emptyList(),
    )
    lap("anchors+calibration+model")

    /**
     * ONE insulin curve behind every IOB the user can see — header number,
     * status line, watch, chart lane. They disagreed: the lane read this body's
     * kernel while the numbers read the textbook (DIA from settings, peak 75),
     * so the same dose was 0 in one place and 30% in another.
     *
     * The kernel wins because it is this person's: the learned half-effect
     * lands at 65 min, not 75, and there is no more drop after ~2h20 where the
     * textbook trickles to 5 h — which is what the user says they observe.
     * Falls back to the textbook when the kernel has not learned a drop yet.
     *
     * NOT used by detectCalibrationWindow on purpose: that is a GATE («is it
     * safe to take a clean check»), and for a gate the conservative curve is
     * the right one — the personal curve clears the insulin two hours sooner,
     * and a bad calibration shifts every number on the screen.
     */
    // The current IOB is reused in three UI sections.  Calculate it once;
    // previously each section repeated the same database read and curve sum.
    val currentIob = timed("  iob surface") {
        HybridRuntimeMetrics.surfaceIobUnits(store, context, now)
    }
    // FIRST `activeFoods` CALL REMOVED.
    //
    // It fed exactly one thing — the COB number in the header — and that
    // number has moved to the same physio engine as the COB lane and the
    // line. Removing it also removes the question around `noteAnchoredFood`,
    // which had to be hand-restored here after the legacy layer was torn
    // down: the setting and the parameter had different defaults, and the
    // shipped behavior once flipped without a single measurement.
    //
    // What goes away besides the lines: on EVERY screen rebuild this call
    // read hundreds of labeled meals, more for `labelByOnset`, hours of
    // detector meals and months of boluses for `labelStats` — and all of it
    // was discarded, because with `noteAnchoredFood = true` the detector-meal
    // loop is empty and `profile`, `twoPhase` and `borrowedTtp` are always
    // null. The screen, which used to "take several minutes to open", was
    // paying for branches the parameter had disabled.
    // Diurnal ISF: the LIVE prediction uses the kernel scaled to this hour's
    // sensitivity (evening units are weaker than morning ones).
    val liveKernel = model?.let {
        com.diapilot.core.analysis.scaleKernel(
            it.kernel,
            com.diapilot.core.analysis.todIsfFactor(
                it.byTod, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
            ),
        )
    } ?: emptyList()
    // ONE engine for every consumer — diverge here and validation lies.
    //
    // THE FORWARD LINE IS AN EDITION FEATURE, and this is where the store
    // edition loses it. `forecastResult` null makes `prediction` empty, and an
    // empty prediction is already the app's "nothing to draw" state: the chart
    // draws no line and no band, the What-if panel is not composed, and
    // `statusSummary` receives null horizons and writes no sentence about
    // later. Nothing downstream substitutes a zero for the missing line —
    // every consumer of `prediction` tests it for emptiness.
    //
    // The RETROSPECTIVE uses of the same engine are untouched: the stored
    // forecast a long-press replays, the day decomposition and the measured
    // insulin curve all still build and read `model` above.
    //
    // THE UNCALIBRATED INSTALL LOSES IT IN THE SAME PLACE. Until the first-run
    // pages are completed the only person model on the phone is the bundled
    // example, so `ModelCalibration.forecastAllowed` — the edition gate with
    // that fact folded in — keeps `prediction` empty, and the Today card says
    // why instead of the chart drawing a line nobody entered.
    val forecastResult = if (ModelCalibration.forecastAllowed(context, store) &&
        last != null && now - last.tsMs <= 36L * 3_600_000 && model != null
    ) {
        timed("  Forecaster.forecast") { Forecaster.forecast(
            store, model, now,
            anchorTsMs = last.tsMs, anchorMmol = last.mmol,
            minutePoints = if (minuteCal != null && last.source != "meter") {
                store.minuteReadings(last.tsMs - 15L * 60_000, last.tsMs)
                    .map { com.diapilot.core.collector.GlucosePoint(it.tsMs, minuteCal.apply(it.mmol)) }
            } else emptyList(),
            recordAs = "main",
            // Lab toggle: main-screen display can show the fingerprint variant.
        ) }
    } else null
    val prediction = forecastResult?.points ?: emptyList()
    // The forward glucose line is three hours, but active food/insulin can
    // legitimately outlive it. Keep the auxiliary COB/IOB rails visible until
    // the SAME hybrid kernels say the effects are over instead of cutting both
    // curves vertically at the current-time boundary.
    val chartEffectsTo = if (
        navAnchor == null && true
    ) {
        maxOf(
            prediction.lastOrNull()?.tsMs ?: now,
            HybridRuntimeMetrics
                .activeInsulinEndMs(store, now, context) ?: now,
            HybridRuntimeMetrics.activeFoodEndMs(store, now) ?: now,
        )
    } else chartToBound
    lap("live forecast")
    // Score due ledger points opportunistically — cheap, incremental, IO thread.
    // The fact is scored through `meterCal`, the SAME lens the forecast anchored
    // on; scoring raw against calibrated silently subtracts the calibration.
    // Scoring/learning maintenance is queued after the complete UiState below.

    // THE "CHECK" ON THE HOUR-AGO FORECAST WAS REMOVED.
    //
    // It had been disabled in place — `val forecastCheck = if (true) null else
    // run { ... }` — and in that state remained the second and last caller of
    // `activeFoods`, i.e. the last live reader of the concept layer on this
    // screen. The disabled branch showed nothing to the user while still
    // keeping the layer in the build.
    //
    // It also computed with the LEGACY model: `predictWithCorridor` over the
    // kernel and corridor, not the physio engine that draws the line. If this
    // line is needed again, bring it back on the same engine as everything
    // else, not by resurrecting this branch.
    //
    // The `forecastCheck` field in UiState stays: `TodayScreen` reads it
    // alongside `meterCalLine`, and it is simply always null now.
    val forecastCheck: String? = null

    // Hindsight line for the chart: what the twin promised for each moment
    // an hour earlier — the forecast's track record drawn next to the fact.
    val retroCal = java.util.Calendar.getInstance()
    val retroHourOf: (Long) -> Int = { ts ->
        retroCal.timeInMillis = ts
        retroCal.get(java.util.Calendar.HOUR_OF_DAY)
    }

    // The deviation card compares actual-vs-model — it MUST speak the same
    // numbers as the header, or an out-of-range reading contradicts a header
    // showing an in-range one. So feed it the SAME meter-calibrated readings
    // the chart draws, and make the latest point the header's own `last`
    // (calibrated + minute-promoted) — the raw grid can sit well below the
    // promoted value.
    // THE DEVIATION DETECTOR IS GONE, by the user's decision.
    // Its card left the main screen before the computation was removed —
    // `state.deviation` had no reader at all, so every draw paid for a number
    // nobody saw. And the number was wrong in a way that mattered: it built its
    // OWN corridor from kernel+Corridor, i.e. the base twin, so "reality left
    // the corridor" was judged against a line that is not the one on
    // screen. Discipline #7, in production. The honest replacement is the
    // corridor of the SHOWN forecast, which is now what the ledger stores.
    // Shared history, fetched ONCE: these used to be six separate
    // full-table scans re-run inside the UiState builder on every
    // 5-second refresh tick.
    val annAll = if(includeHistory) store.annotations(0, now)
        else store.annotations(now-72L*3_600_000L,now)
    val labeledAll500 = store.labeledMeals(limit = 500)
        .filter { it.event.onsetMs >= FoodEraSettings.current().startMs }
    // Auto-label: a detected rise with a confident food note (grams, tight
    // window) is almost certainly THAT meal — apply it instead of nagging the
    // user to confirm. Only touches still-unlabeled meals, reversible via
    // relabel, rescue dextrose excluded (a low-treatment isn't a meal rise).
    store.unlabeledMeals(sinceMs = now - 48L * 3_600_000).forEach { meal ->
        val note = annAll.filter {
            it.estCarbs != null && it.content.isNotBlank() &&
                // Head-based context/activity guard: a "walk · 150 min" note must
                // never become a meal label (exact-match let the suffix leak).
                !com.diapilot.core.analysis.isContextNote(it.content) &&
                it.tsMs in (meal.onsetMs - 45L * 60_000)..(meal.onsetMs + 20L * 60_000)
        }.minByOrNull { kotlin.math.abs(it.tsMs - meal.onsetMs) }
        if (note != null) {
            // The SESSION is the unit of prediction: two dishes eaten within
            // the gap window raised this together — label with the composed
            // name (e.g. "soup + meatballs"), not whichever note sat nearest to
            // the detector's onset (usually the SECOND course).
            val session = com.diapilot.core.analysis.sessionOf(
                com.diapilot.core.analysis.groupMealSessions(annAll), note.id,
            )
            val name = session?.takeIf { it.isComposite }?.composedName ?: note.content.trim()
            store.setMealLabel(meal.onsetMs, store.getOrCreateLabel(name))
        }
    }
    val recentUnlabeled = store.unlabeledMeals(sinceMs = now - 48L * 3_600_000)
    // Only LOGGED food blocks a clean-correction suggestion. A DETECTED "meal"
    // near a correction is exactly the case worth confirming (the detector's
    // false rise off a high the user corrected) — surfacing it is the whole
    // point of the tag, which now feeds the kernel (trustCorrectionTag).
    val loggedFoodOnsets = annAll.filter {
        it.tsMs >= now - 8L * 3_600_000 && (it.kind == "food" || it.estCarbs != null)
    }.map { it.tsMs }.distinct()
    val correctionCandidate = com.diapilot.core.analysis.correctionConfirmationCandidate(
        nowMs = now,
        readings = store.readings(now - 8L * 3_600_000, now),
        boluses = store.boluses(now - 8L * 3_600_000, now),
        foodOnsetsMs = loggedFoodOnsets,
        activityWindows = model?.activityWindows.orEmpty(),
    )
    // Unlogged-meal suggestions; user-declined ones stay declined (prefs).
    val foodSuggestions = run {
        val dismissed = Settings.foodSuggestDismissed(context)
        com.diapilot.core.analysis.suggestFood(
            nowMs = now,
            notes = annAll,
            detectedMeals = store.unlabeledMeals(sinceMs = now - 3L * 3_600_000),
            boluses = store.boluses(now - 4L * 3_600_000, now),
        ).filter { "fs${it.triggerTsMs}" !in dismissed }
    }
    // Trend for the delta + arrow. PRIMARY source is the meter-calibrated
    // 5-min MAIN grid (exactly the chart line): it is the FROZEN real-time
    // record. The 1-min oop2 stream RETROACTIVELY REVISES its recent history
    // (each packet re-decodes ~15 min), and during a rapid drop that revision
    // overshot upward — its 5-min diff then painted a phantom drop on a truly
    // flat sensor. The grid froze the live values. It is 5-min spaced — the
    // natural basis for "change over 5 min". The minute stream is only a
    // fallback for sparse-grid gaps (warmup).
    val trendReadout = run {
        // SENSOR points only: a fresh fingerstick inside the series would
        // fabricate a 5-min jump (meter is truth for CALIBRATION, not a CGM
        // time-series sample — per external review).
        val mainPts = store.sensorReadings(now - 16L * 60_000, now).let { raw ->
            if (meterCalTimeline.isEmpty()) raw else raw.map(::applyMeterLens)
        }
        com.diapilot.core.twin.gridTrendReadout(mainPts, now)
            ?: run {
                val mp = if (minuteCal != null) {
                    store.minuteReadings(now - 15L * 60_000, now)
                        .map { com.diapilot.core.collector.GlucosePoint(it.tsMs, minuteCal.apply(it.mmol)) }
                } else emptyList()
                com.diapilot.core.twin.trendReadout(mp, now)
            }
    }
    // Activity lane for the chart — THE MODEL'S OWN WINDOWS, sliced to the
    // pannable range. It used to re-detect them here with the DEFAULT thresholds
    // while the model ran calibrated ones, so the lane drew bouts the model did
    // not believe in (a live check found most detected era windows were
    // phantom). The lane's whole purpose is "is yesterday's walk already
    // counted?", which is a lie unless it shows exactly what the model counted.
    val chartActivity = run {
        // model.activityWindows only covers the last 30 days (actWindows30), so
        // panning further back would slice it to an EMPTY list and the lane would
        // read "no walk happened" over real history. Use the model's own windows
        // where it has them, and detect with the SAME definition beyond that —
        // never a different definition, which is the desync this change removes.
        val modelCoverFromMs = now - 30L * 24 * 3_600_000
        val fromModel = model?.activityWindows
            ?.takeIf { activityWindow >= modelCoverFromMs }
            ?.filter { it.endMs >= activityWindow && it.startMs <= chartToBound }
        // calibrated=true unconditionally — the MODEL now always builds its
        // windows calibrated (TwinCache), so the fallback must match or a cold
        // chart would draw legacy windows the model would never confirm. The
        // activityModelV2 toggle governs the forecast's exercise-DROP, not what
        // counts as a bout.
        fromModel ?: com.diapilot.core.analysis.detectActivityWindows(
            hr = store.heartRate(activityWindow, chartToBound),
            steps = store.steps(activityWindow, chartToBound),
            notes = store.annotations(activityWindow, chartToBound),
            calibrated = true,
        )
    }
    lap("receipts+deviation+suggestions+activity")
    val whatIfAnchor = forecastResult?.points?.firstOrNull()?.tsMs
    val selectedWhatIfProfile = whatIfAnchor
        ?.let(PhysioForecastRegistry::physioWhatIf)
    val chartBolusRows=store.bolusesAll(chartWindow,chartToBound)
    val physioForCounterfactual=PhysioRuntime.artifact(store,now)
    val counterfactualPeople=chartBolusRows.filter{it.units>0&&!com.diapilot.core.analysis.isPrimePurpose(it.purpose)}.mapNotNull{b->
        val cal=java.util.Calendar.getInstance().apply{timeInMillis=b.tsMs}
        val hour=cal.get(java.util.Calendar.HOUR_OF_DAY)+cal.get(java.util.Calendar.MINUTE)/60.0
        val person=physioForCounterfactual?.personModelAt(hour)
            ?:HybridRuntimeMetrics.model()
        person?.let{b.tsMs to (b to it)}
    }.toMap()
    val counterfactualKernels=counterfactualPeople.mapValues{(_,pair)->
        com.diapilot.core.hybrid.physioForecastEngine(pair.second).insulinKernelPoints(pair.first.units)
    }
    val counterfactualLandmarks=counterfactualPeople.mapValues{(_,pair)->
        com.diapilot.core.hybrid.physioForecastEngine(pair.second).insulinLandmarks(pair.first.units).let{
            Triple(it.onsetMin,it.ratePeakMin,it.effectEndMin)
        }
    }
    val result = UiState(
        readings = store.count("glucose_readings"),
        insulin = store.count("insulin_events"),
        activityWindows = chartActivity,
        activityEffectActive = (model?.activityDropPerMin ?: 0.0) > 0.0,
        unlabeled = recentUnlabeled,
        topLabels = labeledAll500.groupBy { it.labelName }.values
            .sortedByDescending { it.size }
            .map { rows -> rows.first().labelId to rows.first().labelName }
            .filterNot { it.second in com.diapilot.core.analysis.SysLabels.ALL }.take(8),
        chartFrom = chartWindow,
        // History view ends at the browsed window; live view extends to the
        // forecast edge so the prediction is visible.
        chartTo = if (navAnchor != null) chartToBound else chartEffectsTo,
        chartNavActive = navAnchor != null,
        chartReadings = timed("chartReadings") {
            val pts = displayHistory(
                store, context, chartWindow, chartToBound,
            )
            // Bridge NFC 15-min backfill spans into a line (cosmetic only).
            bridgeBackfillSpans(pts, store.backfilledReadingTimestamps(chartWindow, chartToBound))
        },
        // Audit view on purpose: an air shot must stay tappable on the chart
        // (to unmark it); analytics/prediction pull boluses() separately.
        // IOB + COB lanes. Both are «what is still to come», sampled on the same
        // 5-min grid as the chart. IOB uses the LEARNED kernel when it has one —
        // the textbook curve (DIA 5h, peak 75) is a population guess, and this
        // body's own half-effect lands nearer 65 min.
        iobSeries = timed("iobSeries") {
            HybridRuntimeMetrics.surfaceIobSeries(
                store, context, chartWindow, chartEffectsTo,
            )
        },
        // Always personal now: the only engine left derives its curve from the
        // user's own measured landmarks. The flag stays because the chart legend
        // reads it, not because there is still a population arm to distinguish from.
        iobIsPersonal = true,
        // ONE SOURCE FOR COB, NO FALLBACKS.
        //
        // There used to be a ladder of three branches here, and the bottom two
        // were dead. Control only reached them when there was NEITHER a
        // physio artifact NOR a model from the registry — i.e. on a device
        // with no model at all.
        //
        // They were dead unnoticeably, and expensively: both later branches
        // computed COB from a DIFFERENT food model than the line (the bottom
        // one via the concept corpus through `predictKinetics`), and this
        // mismatch was measured and published twice as a property of the
        // app — a tracked bug describing COB and the line diverging by tens
        // of minutes across a large share of records. The fallback path was
        // what got measured. On screen, COB and the line now read one model.
        //
        // A failure now looks like a failure: an empty row, not a number from
        // a different model (same principle as "a model failure should no
        // longer look like knowledge").
        cobSeries = timed("cobSeries") {
            HybridRuntimeMetrics.physioCobSeries(store, chartWindow, chartEffectsTo)
        },
        chartBoluses = chartBolusRows,
        chartMeals = store.meals(chartWindow, chartToBound),
        prediction = prediction,
        comparisonPrediction = emptyList(),
        // Calibrated when the fit holds — the grey trace then overlays the
        // main curve on the same scale instead of floating on its own.
        minuteReadings = store.minuteReadings(now - 12L * 3_600_000, now).let { raw ->
            minuteCal?.let { com.diapilot.core.analysis.calibrateMinuteStream(raw, it) } ?: raw
        }.let { l -> meterCal?.let { c -> l.map(c::apply) } ?: l },
        lastMinute = store.lastMinuteReading(),
        annotations = store.annotations(chartWindow, chartToBound),
        frequentNotes = store.topAnnotationTexts(),
        // Known grams per dish name (latest wins) — repeat entries prefill
        // without another LLM call. Two sources: whole notes with grams, and
        // per-component composition-marker lines mined from cached analyses
        // (a composite breakfast teaches "bread = 12 g" without separate notes).
        carbsByFood = outlined {
            val all = annAll
            // Keys are NORMALIZED (composite meals step 1): a component from a
            // Vision composition line (e.g. "Buckwheat (cooked, ~150 g)") and
            // the user's own shorter name for it collapse into one dictionary
            // entry, so grams learned in one combo transfer to every other.
            // Latest sighting wins.
            val latest = mutableMapOf<String, Pair<Long, Double>>()
            fun learn(rawName: String, g: Double, tsMs: Long) {
                val key = com.diapilot.core.analysis.normalizeFoodName(rawName)
                if (key.isEmpty() || g <= 0) return
                if ((latest[key]?.first ?: Long.MIN_VALUE) <= tsMs) latest[key] = tsMs to g
            }
            all.forEach { n ->
                n.analysis?.let { a ->
                    com.diapilot.core.analysis.parseComponentsEstimate(a).forEach { (name, g) ->
                        learn(name, g, n.tsMs)
                    }
                }
            }
            all.filter { it.estCarbs != null && it.content.isNotBlank() }.forEach { n ->
                // Multi-part notes ("buckwheat, salad") carry the TOTAL grams —
                // attributing the sum to each part would poison the
                // dictionary, so only single-dish notes teach here.
                if (!n.content.contains(',')) learn(n.content, n.estCarbs!!, n.tsMs)
            }
            // Library grams (user-curated reference) OVERRIDE derived ones —
            // a corrected value in the food library beats the latest note.
            // A composite dish without explicit grams contributes its LIVE
            // recipe sum: fix "bread" once, every dish containing it follows.
            val derived = latest.mapValues { it.value.second }
            val library = (store as? SqliteCollectorStore)
                ?.foodLibrary().orEmpty()
                .mapNotNull { e ->
                    val g = e.grams ?: e.components.takeIf { it.isNotEmpty() }?.let { comps ->
                        val known = comps.map { c ->
                            (c.grams ?: com.diapilot.core.analysis.lookupFoodGrams(derived, c.name))
                                ?.times(c.count)
                        }
                        if (known.all { it != null }) known.filterNotNull().sum() else null
                    }
                    g?.let {
                        com.diapilot.core.analysis.normalizeFoodName(e.name)
                            .takeIf { k -> k.isNotEmpty() }?.to(it)
                    }
                }
                .toMap()
            derived + library
        },
        recentFoodTexts = annAll
            .filter {
                it.kind == "food" && it.content.isNotBlank() &&
                    !com.diapilot.core.analysis.isRescueNote(it.content) &&
                    now - it.tsMs <= 60L * 24 * 3_600_000
            }
            .sortedByDescending { it.tsMs }
            .map { it.content.trim() }
            .distinctBy { it.lowercase() }
            .take(60),
        recentFoodCarbs = outlined {
            val byText = mutableMapOf<String, Pair<Long, Double>>()
            annAll.forEach { n ->
                val g = n.estCarbs ?: return@forEach
                if (n.kind != "food" || n.content.isBlank() || g <= 0) return@forEach
                val key = n.content.trim().lowercase()
                if ((byText[key]?.first ?: Long.MIN_VALUE) <= n.tsMs) byText[key] = n.tsMs to g
            }
            byText.mapValues { it.value.second }
        },
        // Meal-name -> component list for one-tap chips, mined per SESSION:
        // two photos in one meal (different names!) pool their components,
        // so any member's name recalls the whole meal. A note's components
        // come from its Vision split; in a composite session a note without
        // a split contributes its own name. Latest session per key wins.
        mealComponents = outlined {
            val byKey = mutableMapOf<String, Pair<Long, List<String>>>()
            com.diapilot.core.analysis.groupMealSessions(annAll).forEach { s ->
                val comps = s.notes.flatMap { n ->
                    val parsed = n.analysis
                        ?.let { com.diapilot.core.analysis.parseComponentsEstimate(it) }
                        .orEmpty()
                        .map { com.diapilot.core.analysis.normalizeFoodName(it.first) }
                        .filter { it.isNotEmpty() }
                    if (parsed.isNotEmpty()) parsed
                    else if (s.isComposite) {
                        listOf(com.diapilot.core.analysis.normalizeFoodName(n.content))
                            .filter { it.isNotEmpty() }
                    } else emptyList()
                }.distinct()
                if (comps.size < 2) return@forEach
                s.notes.map { com.diapilot.core.analysis.normalizeFoodName(it.content) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .forEach { key ->
                        if ((byKey[key]?.first ?: Long.MIN_VALUE) <= s.startMs) {
                            byKey[key] = s.startMs to comps
                        }
                    }
            }
            // Library recipes are the CURATED source of a dish's split — they
            // override the history-derived guess, so picking a saved recipe
            // in the composer offers exactly its real composition.
            val fromLibrary = (store as? SqliteCollectorStore)
                ?.foodLibrary().orEmpty()
                .filter { it.components.isNotEmpty() }
                .mapNotNull { e ->
                    val key = com.diapilot.core.analysis.normalizeFoodName(e.name)
                        .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    key to e.components.map { it.name }.distinct()
                }
                .toMap()
            byKey.mapValues { it.value.second } + fromLibrary
        },
        // Library recipes as (component, count, gramsPerUnit). A picked
        // composite logs under its OWN NAME (stable label → its own curve), and
        // these components ride along as composition-marker metadata (the base future
        // decomposition reads) with editable per-instance portions — so the
        // label never fragments into a comma-list, but 1 vs 2 slices of bread still
        // adjusts the recorded grams.
        compositeParts = (store as? SqliteCollectorStore)
            ?.foodLibrary().orEmpty()
            .filter { it.components.isNotEmpty() }
            .mapNotNull { e ->
                val key = com.diapilot.core.analysis.normalizeFoodName(e.name)
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                key to e.components.map { Triple(it.name, it.count.coerceAtLeast(1), it.grams) }
            }.toMap(),
        // Latest photo per dish name: entering a known dish lets the carbs
        // estimate SEE the actual plate instead of guessing from the name.
        photoByFood = annAll
            .filter { it.mediaRef != null && it.content.isNotBlank() }
            .groupBy { it.content }
            .mapValues { (_, ns) -> ns.maxBy { it.tsMs }.mediaRef!! },
        hcGranted = if (HealthConnectSync.clientOrNull(context) == null) null
        else HealthConnectSync.hasPermissions(context),
        stepsGranted = if (HealthConnectSync.clientOrNull(context) == null) null
        else HealthConnectSync.hasStepsPermission(context),
        // Delta over the LAST 5 MINUTES of the calibrated minute stream,
        // recomputed every refresh — the old mixed-source 5-min pair could
        // flip the sign when the scales disagreed.
        // Trend from the SMOOTHED momentum (quadratic fit over ~15 min), NOT a
        // two-point diff of noisy 1-min samples — the arrow stops flickering
        // and matches the forecast. Falls back to a two-point delta when the
        // minute stream is too thin.
        deltaMmol = trendReadout?.delta5Mmol
            ?: store.readings(now - 25 * 60_000, now).takeLast(2)
                .takeIf { it.size == 2 }?.let { it[1].mmol - it[0].mmol },
        trendNuance = trendReadout?.nuance,
        foodSuggestions = foodSuggestions,
        compositionByFood = com.diapilot.core.analysis.lastCompositions(annAll),
        movingFast = outlined {
            val recent = if (minuteCal != null) {
                store.minuteReadings(now - 20L * 60_000, now)
                    .map { com.diapilot.core.collector.GlucosePoint(it.tsMs, minuteCal.apply(it.mmol)) }
            } else store.readings(now - 20L * 60_000, now)
            val r = com.diapilot.core.analysis.sensorRateNear(recent, now)
            r != null && kotlin.math.abs(r) > com.diapilot.core.analysis.FLAT_RATE_MMOL_PER_MIN
        },
        calibrationWindow = outlined {
            if (last == null || now - last.tsMs > 15 * 60_000 || model == null) return@outlined null
            // Unknown IOB is not the same as zero IOB. Since A-26 the surface
            // resolver returns null rather than a population-curve guess, and
            // «no insulin on board» is exactly the claim a calibration window
            // rests on — so absent that fact there is no window to offer.
            val iobForWindow = currentIob ?: return@outlined null
            val recent = if (minuteCal != null) {
                store.minuteReadings(now - 20L * 60_000, now)
                    .map { com.diapilot.core.collector.GlucosePoint(it.tsMs, minuteCal.apply(it.mmol)) }
            } else store.readings(now - 20L * 60_000, now)
            val rate = com.diapilot.core.analysis.sensorRateNear(recent, now)
            val lastFoodMs = (
                store.annotations(now - 12L * 3_600_000, now)
                    .filter { it.kind == "food" || it.estCarbs != null }.map { it.tsMs } +
                    store.meals(now - 12L * 3_600_000, now).map { it.onsetMs }
                ).maxOrNull()
            val lastBolusMs = store.boluses(now - 12L * 3_600_000, now).maxOfOrNull { it.tsMs }
            com.diapilot.core.analysis.detectCalibrationWindow(
                rateMmolPerMin = rate,
                currentMmol = last.mmol,
                iobUnits = iobForWindow,
                minSinceLastFood = lastFoodMs?.let { (now - it) / 60_000.0 },
                minSinceLastBolus = lastBolusMs?.let { (now - it) / 60_000.0 },
                isfEffectiveEpisodes = model.effectiveEpisodes,
                hasCarbRatio = model.carbSens != null,
                rangeLo = Settings.rangeLoMmol(context),
                rangeHi = Settings.rangeHiMmol(context),
            )
        },
        historyNotes = if (includeHistory) store.annotations(historyFrom, historyTo) else emptyList(),
        // 2000, not 300: the history tab now date-jumps months back, and a
        // window there must still find its labels.
        labelByOnset = if(includeHistory)store.labeledMeals(limit = 2000).associate { it.event.onsetMs to it.labelName }else emptyMap(),
        historyMeals = if(includeHistory)store.meals(historyFrom, historyTo)else emptyList(),
        // History is the audit view — air shots stay visible (with their chip).
        historyBoluses = if(includeHistory)store.bolusesAll(historyFrom, historyTo)else emptyList(),
        historyMeter = if(includeHistory)store.meterReadings(historyFrom, historyTo)else emptyList(),
        // SENSOR only, on the display lens: a fingerstick in the series would
        // fabricate a jump, and the number must match the header the user sees.
        historyReadings = if (includeHistory) timed("historyReadings") {
            val meterTs = store.meterReadings(historyFrom, historyTo)
                .mapTo(HashSet()) { it.tsMs }
            displayHistory(store, context, historyFrom, historyTo)
                .filter { it.tsMs !in meterTs }
        } else emptyList(),
        // Already built for the food model — the history just reads it. Empty
        // until the twin exists (fresh install, cache not warm): the lines then
        // simply don't render.
        mealObservations = if(includeHistory)model?.fingerprintCorpus.orEmpty().associateBy { it.onsetMs }else emptyMap(),
        // The marks the BUILD applied, not a fresh read of the table: the screen must show
        // the same answers the model shown to the user was computed with.
        mealMarks = if(includeHistory)model?.mealMarks.orEmpty().sortedByDescending { it.onsetMs }else emptyList(),
        chartBasals = store.basalEvents(chartWindow, chartToBound),
        historyBasals = if(includeHistory)store.basalEvents(historyFrom, historyTo)else emptyList(),
        historyDays = HistoryNav.days,
        historyAnchorMs = HistoryNav.anchorMs,
        // Quick-pick dishes: what the user actually eats AT THIS HOUR floats
        // to the front (breakfast foods in the morning), frequency breaks ties.
        foodLabels = outlined {
            val labeledAll = labeledAll500
            val cal = java.util.Calendar.getInstance()
            fun hourOf(ts: Long): Int {
                cal.timeInMillis = ts; return cal.get(java.util.Calendar.HOUR_OF_DAY)
            }
            val nowH = hourOf(now)
            val hoursByLabel = labeledAll.groupBy({ it.labelName }, { hourOf(it.event.onsetMs) })
            com.diapilot.core.analysis.labelStats(labeledAll)
                .sortedWith(
                    compareByDescending<com.diapilot.core.analysis.LabelStats> { s ->
                        hoursByLabel[s.name].orEmpty().count { h ->
                            kotlin.math.abs(((h - nowH + 12).mod(24)) - 12) <= 2
                        }
                    }.thenByDescending { it.count },
                )
                .take(10).map { it.name }
        },
        // COMPOSER INPUT, NOT SCREEN STATE — so it is not recomputed on the
        // cheap refreshes.
        //
        // It reads the WHOLE food era (every dose, every note, `labelStats`, then
        // one `foodMemory` per label) and ran on EVERY pass, including the one
        // that fires right after a write — the moment the composer has just
        // closed and nothing on screen reads it. `withHistoryFrom` now carries
        // it across, exactly as it carries the History collections, so the
        // composer still opens with data and the write path stops paying for it.
        foodMemory = if (!includeHistory) emptyMap() else timed("foodMemory") {
            val labeledAll = labeledAll500
            val allBoluses = store.boluses(FoodEraSettings.current().startMs, now)
            val allNotes = annAll.filter { it.tsMs >= FoodEraSettings.current().startMs }
            val stats = com.diapilot.core.analysis.labelStats(labeledAll)
            val memKernel = model?.kernel ?: emptyList()
            stats.mapNotNull {
                com.diapilot.core.analysis.foodMemory(
                    it.name, labeledAll, allBoluses, allNotes, memKernel,
                )
            }.associateBy { it.label }
        },
        similarFood = outlined {
            val stats = com.diapilot.core.analysis.labelStats(labeledAll500)
            // Two bridges, unioned: response shape (rise/ttp — needs the
            // dish's own history) and shared recipe components (works for a
            // dish NEVER eaten: a new beer inherits prior beer experience).
            val lib = (store as? SqliteCollectorStore)
                ?.foodLibrary().orEmpty()
            fun norm(s: String) = com.diapilot.core.analysis.normalizeFoodName(s)
            val recipeByName = lib.filter { it.components.isNotEmpty() }
                .associate { e ->
                    e.name to e.components.map { norm(it.name) }.filter { it.isNotEmpty() }.toSet()
                }
            val dishNames = (stats.map { it.name } + lib.map { it.name }).distinct()
            val componentsByDish = dishNames.associateWith { n ->
                recipeByName[n]
                    ?: parseMealParts(n)
                        .takeIf { it.size > 1 }?.keys
                        ?.map { norm(it) }?.filter { it.isNotEmpty() }?.toSet()
                    ?: setOfNotNull(norm(n).takeIf { it.isNotEmpty() })
            }
            dishNames.associateWith { n ->
                val byResponse =
                    if (stats.any { it.name == n }) {
                        com.diapilot.core.analysis.similarFoods(n, stats).map { it.name }
                    } else emptyList()
                val byRecipe = com.diapilot.core.analysis
                    .similarByComponents(n, componentsByDish)
                (byResponse + byRecipe).distinct().filter { it != n }
            }
        },
        // "Breakfast" without typing: meals the user repeatedly logged around
        // this hour. Strict on purpose: >=3 repeats on >=3 DIFFERENT days —
        // two beers one evening must not brand 2 a.m. as beer o'clock.
        typicalMeals = outlined {
            val cal = java.util.Calendar.getInstance()
            fun hourOf(ts: Long): Int {
                cal.timeInMillis = ts; return cal.get(java.util.Calendar.HOUR_OF_DAY)
            }
            fun dayOf(ts: Long): Long = ts / (24 * 3_600_000)
            val nowH = hourOf(now)
            store.annotations(now - 60L * 24 * 3_600_000, now)
                .filter {
                    (it.kind == "food" || it.estCarbs != null) && it.content.isNotBlank() &&
                        it.content.lowercase() !in com.diapilot.core.analysis.CONTEXT_TAGS &&
                        kotlin.math.abs(((hourOf(it.tsMs) - nowH + 12).mod(24)) - 12) <= 2
                }
                .groupBy { it.content }
                .filterValues { ns -> ns.size >= 3 && ns.distinctBy { dayOf(it.tsMs) }.size >= 3 }
                .entries.sortedByDescending { it.value.size }
                .take(2).map { it.key }
        },
        bgAtShot = if (includeHistory) timed("bgAtShot") {
            // BG at shot time: the deciding context for the purpose tag — so it
            // must read on the SAME scale as the chart and the header, or it
            // quietly misinforms about why a correction was given (a dose
            // logged against an uncalibrated reading looked smaller than it
            // really was). Sensor points only: the meter lens corrects
            // the sensor, and applying it to a fingerstick would corrupt the one
            // reading that is already ground truth. Pre-epoch points stay raw —
            // this sensor's fit says nothing about an older sensor's data.
            // EXACTLY the chart's lens (see the chart series above): meterCal with
            // no epoch gate. Gating it here instead looked more principled and was
            // worse — 13 of the last week's 41 shots are pre-epoch, so the list
            // mixed two scales with no marker AND disagreed with the chart the user
            // is comparing it against; worse still, the epoch came from `model`, so
            // the same historical shot showed one number before the twin built and
            // another after. A display must be consistent with what it sits next to.
            val weekReadings = store.sensorReadings(now - 7L * 24 * 3_600_000, now)
            val shots = store.boluses(now - 7L * 24 * 3_600_000, now) +
                store.basalEvents(now - 7L * 24 * 3_600_000, now)
            shots.mapNotNull { s ->
                weekReadings.minByOrNull { kotlin.math.abs(it.tsMs - s.tsMs) }
                    ?.takeIf { kotlin.math.abs(it.tsMs - s.tsMs) < 15 * 60_000 }
                    ?.let { s.tsMs to (meterCal?.correctedAt(it.tsMs, it.mmol) ?: it.mmol) }
            }.toMap()
        } else emptyMap(),
        // (Periodic observations moved to the Analysis screen.)
        labeled = store.labeledMeals(limit = 20).filter { it.event.onsetMs >= FoodEraSettings.current().startMs },
        lastReading = last,
        lastBolus = store.boluses(now - 7L * 24 * 3_600_000, now).lastOrNull(),
        // Local IOB: xDrip's number went stale the day doses moved to the
        // pen NFC scan — our store is the only place that sees them all.
        // THIS BODY's curve, same as the chart lane (see iobAt).
        iobUnits = currentIob,
        iobTsMs = now,
        mgdl = Units.isMgdl(context),
        rangeLo = Settings.rangeLoMmol(context),
        targetMmol = Settings.targetMmol(context),
        rangeHi = Settings.rangeHiMmol(context),
        forecastCheck = forecastCheck,
        correctionCandidate = correctionCandidate,
        meterCalLine = meterCal?.takeIf { it.isActive(now) }?.let { c ->
            val mgdlPref = Units.isMgdl(context)
            val text = context.localized()
            val parts = mutableListOf<String>()
            if (c.nChecks >= 2) {
                val delta = com.diapilot.core.analysis.fmtBgDelta(c.interceptMmol, mgdlPref)
                parts += if (c.slope != 1.0) {
                    text.resources.getQuantityString(
                        R.plurals.main_state_meter_cal_checks_slope, c.nChecks, c.slope, delta, c.nChecks,
                    )
                } else {
                    text.resources.getQuantityString(R.plurals.main_state_meter_cal_checks, c.nChecks, delta, c.nChecks)
                }
            }
            c.transient?.let { t ->
                val off = t.offsetAt(now)
                if (kotlin.math.abs(off) >= 0.1) {
                    parts += text.getString(
                        R.string.main_state_meter_cal_fading,
                        com.diapilot.core.analysis.fmtBgDelta(off, mgdlPref),
                    )
                }
            }
            if (parts.isEmpty()) null
            else text.getString(R.string.main_state_meter_cal_prefix, parts.joinToString(" · "))
        },
        twinKernel = liveKernel,
        counterfactualKernels=counterfactualKernels,
        counterfactualLandmarks=counterfactualLandmarks,
        carbSensMmolPerGram =
            if (true) {
                HybridRuntimeMetrics.carbSensitivityMmolPerGram()
            } else {
                model?.carbSens?.mmolPerGram
            },
        // Autosens actually applied to the live forecast — so "What if" scales
        // its insulin increment by the SAME effective sensitivity, not the bare
        // ToD kernel (they used to diverge by up to ±20% on drift days).
        forecastKernelScale = forecastResult?.kernelScale ?: 1.0,
        hybridWhatIfProfile = selectedWhatIfProfile,
        comparisonWhatIfProfile = null,
        // Historical field name; semantically this now means that the selected
        // arm has its matching hybrid What-if profile. It must not be gated by
        // the retired hybridV11Main rollout flag when Physio is selected.
        selectedArmWhatIf = selectedWhatIfProfile != null,
        hybridFoodReadouts = if (includeHistory) timed("hybridFoodReadouts") {
            val notes=store.annotations(historyFrom,historyTo).filter{com.diapilot.core.analysis.isFoodNote(it)}
            run{
                val artifact=PhysioRuntime.artifact(store,now)
                if(artifact==null)emptyMap() else {
                    (store as? SqliteCollectorStore)?.let { sqlite ->
                        // THE CODE IS PART OF THE MODEL, and it was missing here.
                        // This identity held only the ARTIFACT, so a card stayed
                        // cached across app updates that changed how it is
                        // computed. Found after shipping gastric sieving, the
                        // cluster pipe, the prior-realised fix and the neighbour
                        // subtraction: scrolling back in history surfaced cards
                        // still written by the build from before all four,
                        // showing stale numbers.
                        // Same lesson as the Stage9 cache version, one screen
                        // over: version a stored answer by the model that made
                        // it. The build name is included as well as the algo tag
                        // because a display change need not bump the algo tag,
                        // and a stale card is worse than a recomputed one.
                        val projectionIdentity = listOf(
                            "physio-history-v4-no-dish-curves",
                            artifact.globalCs.median,
                            artifact.baseMechanics.food.defaultShape,
                            artifact.macroTiming,
                            com.diapilot.core.twin.FORECAST_ALGO_VERSION_FP_SHADOW,
                            BuildConfig.VERSION_NAME,
                            // THE TUNING IS PART OF THE MODEL TOO (A-31).
                            //
                            // The settings screen can now move ISF, the insulin
                            // landmarks, the gastric queue and the trust ramp.
                            // Every one of those changes the amplitude and the
                            // timing this card prints — and without this line
                            // the card would keep serving the numbers computed
                            // under the PREVIOUS tuning, exactly as it kept
                            // serving a stale amplitude for an earlier logged
                            // dish. The comment above says "version a stored
                            // answer by the model that made it"; a tuning IS
                            // the model that made it.
                            PhysioTuning.identity(
                                PhysioTuning.read(context),
                            ),
                        ).joinToString("|")
                        HistoryFoodProjectionCache.getOrCompute(
                            sqlite, projectionIdentity, notes,
                        ) { missing ->
                            HybridRuntimeMetrics.foodReadoutsForModel(
                                // Food amplitude/timing does not depend on the
                                // insulin time-of-day ISF; one engine is valid
                                // for every cache miss in this batch.
                                artifact.personModelAt(12.0,emptySet()),missing,
                                macroTiming=artifact.macroTiming,physioArtifact=artifact,
                            )
                        }
                    } ?: HybridRuntimeMetrics.foodReadoutsForModel(
                        artifact.personModelAt(12.0,emptySet()),notes,
                        macroTiming=artifact.macroTiming,physioArtifact=artifact,
                    )
                }
            }
        } else emptyMap(),
        insulinDiaMin =
            HybridRuntimeMetrics.physioInsulinLandmarks(store,now)?.effectEndMin
                ?: Settings.insulinDiaMin(context),
        insulinPeakMin =
            HybridRuntimeMetrics.physioInsulinLandmarks(store,now)?.ratePeakMin
                ?: Settings.insulinPeakMin(context),
        // Basal reminder: learned from the logging habit itself — the median
        // time-of-day of past basal entries. Due when >20h since the last
        // shot and the usual hour is near (or up to 3h overdue).
        basalDue = outlined {
            val p = com.diapilot.core.PersonalParams.DEFAULT
            val basals = store.basalEvents(now - 14L * 24 * 3_600_000, now)
            if (basals.size < 2) return@outlined null
            val lastTs = basals.maxOf { it.tsMs }
            if (now - lastTs < p.basalDueAfterH * 3_600_000L) return@outlined null
            val cal = java.util.Calendar.getInstance()
            fun minuteOfDay(ts: Long): Int {
                cal.timeInMillis = ts
                return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            }
            // Two entries already make a habit IF their times agree
            // (circularly) — waiting for a third just costs a day of reminders.
            if (basals.size == 2) {
                val (a, b) = basals.map { minuteOfDay(it.tsMs) }
                val d = kotlin.math.abs(a - b).let { minOf(it, 1440 - it) }
                if (d > p.basalPairAgreeMin) return@outlined null
            }
            val usual = basals.map { minuteOfDay(it.tsMs) }.sorted().let { it[it.size / 2] }
            val nowMin = minuteOfDay(now)
            val win = -p.basalWindowBackMin..p.basalWindowFwdMin
            val diff = nowMin - usual
            val inWindow = diff in win || diff + 1440 in win || diff - 1440 in win
            if (!inWindow) return@outlined null
            val units = basals.map { it.units }.sorted().let { it[it.size / 2] }
            units to "%02d:%02d".format(usual / 60, usual % 60)
        },
        dayStats = timed("dayStats") {
            val day = trustedHistory(
                store, context, now - 24L * 3_600_000, now,
            )
            if (day.isEmpty()) null else {
                val lo = Settings.rangeLoMmol(context)
                val hi = Settings.rangeHiMmol(context)
                val mean = day.sumOf { it.mmol } / day.size
                val dayNotes = store.annotations(now - 24L * 3_600_000, now)
                val nutritionNotes = dayNotes.mapNotNull { note ->
                    com.diapilot.core.analysis.parseFoodNutrition(note.analysis)
                        .takeIf { it.proteinG != null || it.fatG != null || it.kcal != null }
                }
                val nutrition = nutritionNotes.fold(com.diapilot.core.analysis.FoodNutrition()) {
                    total, n -> total + n
                }
                val tir = tir24h(store, context, now, lo, hi)
                DayStats(
                    tirPct = tir?.inRange ?: 0,
                    insulinUnits = store.boluses(now - 24L * 3_600_000, now).sumOf { it.units },
                    carbsG = dayNotes.mapNotNull { it.estCarbs }.sum(),
                    meanMmol = mean,
                    proteinG = nutrition.proteinG,
                    fatG = nutrition.fatG,
                    kcal = nutrition.kcal,
                    nutritionMeals = nutritionNotes.size,
                )
            }
        },
        hypoQuick = outlined {
            last?.takeIf { now - it.tsMs < 15 * 60_000 }?.let { current ->
                val delta = store.readings(now - 25 * 60_000, now).takeLast(2)
                    .takeIf { it.size == 2 }?.let { it[1].mmol - it[0].mmol }
                val lo = Settings.rangeLoMmol(context)
                current.mmol < lo + 0.1 ||
                    (current.mmol < lo + 1.6 && (delta ?: 0.0) <= -0.25)
            } ?: false
        },
        hypoTablets = store.annotations(now - 30 * 60_000, now)
            .firstOrNull { com.diapilot.core.analysis.isRescueNote(it.content) }
            ?.let { parseDextroseCount(it.content) } ?: 0,
        appliedIsf = outlined {
            // Asked of the SAME object the forecast used, at the SAME hour —
            // not re-derived. A privately re-computed ISF beside the line it is
            // supposed to explain is how the two drift apart (discipline #7).
            val hour = java.util.Calendar.getInstance().let {
                it.timeInMillis = now
                it.get(java.util.Calendar.HOUR_OF_DAY) +
                    it.get(java.util.Calendar.MINUTE) / 60.0
            }
            val text = context.localized()
            PhysioRuntime.artifact(store, now)
                ?.personModelAt(hour, emptySet())
                ?.let { it.insulin.isf to text.getString(R.string.main_state_isf_source_hour, hour.toInt()) }
                ?.let { (isf, src) ->
                val tuned = ManualInsulinRuntime.params(context).isfMmolPerU
                if (tuned != null) tuned to text.getString(R.string.main_state_isf_source_manual, src) else isf to src
            }
        },
        statusLines = timed("statusLines") {
            val lastBolus = store.boluses(now - 7L * 24 * 3_600_000, now).lastOrNull()
            val localIob = currentIob
            // Latest food inside the absorption window: a food note wins (it
            // has the portion and grams), else a detected meal's label.
            val foodNote = store.annotations(now - 4L * 3_600_000, now)
                .filter {
                    it.content.isNotBlank() &&
                        it.content.lowercase() !in com.diapilot.core.analysis.CONTEXT_TAGS &&
                        (it.kind == "food" || it.estCarbs != null)
                }
                .maxByOrNull { it.tsMs }
            val labelByOnset4h = store.labeledMeals(limit = 100)
                .filter { it.event.onsetMs >= FoodEraSettings.current().startMs }
                .associate { it.event.onsetMs to it.labelName }
            val foodMeal = store.meals(now - 4L * 3_600_000, now)
                .filter { labelByOnset4h[it.onsetMs] !in com.diapilot.core.analysis.SysLabels.ALL }
                .maxByOrNull { it.onsetMs }
            val foodTs = foodNote?.tsMs ?: foodMeal?.onsetMs
            // The forecast headline points at the SETTLE moment — where the
            // modeled line flattens (insulin+food played out) — instead of a
            // fixed +60 min that lands mid-action.
            val future = prediction.filter { it.tsMs > now }
            val settleIdx = com.diapilot.core.analysis.settleIndex(future.map { it.mmol })
            val predPt = settleIdx?.let { future[it] }
            StatusText.lines(context, com.diapilot.core.analysis.statusSummary(
                com.diapilot.core.analysis.StatusInput(
                    iobUnits = localIob,
                    lastBolusUnits = lastBolus?.units,
                    lastBolusAgeMin = lastBolus?.let { (now - it.tsMs) / 60_000 },
                    foodLabel = foodNote?.content
                        ?: foodMeal?.let { labelByOnset4h[it.onsetMs] ?: context.localized().getString(R.string.main_state_generic_food_label) },
                    foodAgeMin = foodTs?.let { (now - it) / 60_000 },
                    foodCarbs = foodNote?.estCarbs,
                    // What is LEFT of everything eaten, from the same absorption
                    // curves the forecast draws — the two must agree on screen.
                    // Sums ALL active meals, not just the headline one, so a
                    // dessert on top of dinner shows up.
                    cobGrams = HybridRuntimeMetrics
                        .cobGrams(store, now)?.takeIf { it > 0.0 },
                    predMmolIn60 = predPt?.mmol,
                    // The header quotes the INNER (≈50%) band — "half the
                    // time you land here". The honest p90/p10 outer band
                    // stays on the chart; quoting it in text read as
                    // "could be 114–304", which is alarm, not information.
                    predLoIn60 = predPt?.loMid,
                    predHiIn60 = predPt?.hiMid,
                    predSettleMin = predPt?.let { (it.tsMs - now) / 60_000 },
                    predSettled = settleIdx != null && settleIdx < future.size - 1,
                    mgdl = Units.isMgdl(context),
                ),
            ))
        },
        forecastHealth = forecastResult?.health,
        // Autosens sentence — a secondary "model detail" (collapsed in the UI).
        // Read off the forecast instead of recomputed beside it. This used to call
        // computeAutosens a SECOND time and could differ from the applied dial four
        // independent ways: UNCALIBRATED readings, a window ending at `now` instead
        // of the ANCHOR, no `nAnchors >= 6` gate, and it quoted the RAW ratio while
        // the forecast applies `appliedRatio()` — which clamps. So the app could
        // announce a sensitivity it had never used. Same reason the ISF line below
        // exists at all. Bonus: one fewer 14-hour recompute per state build.
        sensitivityLine = forecastResult?.appliedIsf?.autosensFactor?.let { r ->
            val text = context.localized()
            when {
                r >= 1.25 -> text.getString(R.string.main_state_sensitivity_stronger, r)
                r <= 0.8 -> text.getString(R.string.main_state_sensitivity_weaker, r)
                else -> null
            }
        },
        // Layer B, step 1: the sensitivity THIS forecast ran on, with the factors
        // that produced it. Observability only — nothing here changes a number,
        // and it never suggests a dose.
        appliedIsfLine = forecastResult?.appliedIsf?.let { isf ->
            val mgdl = Units.isMgdl(context)
            val text = context.localized()
            fun isfText(v: Double) =
                if (mgdl) text.getString(R.string.main_state_isf_unit_mgdl, v * 18.0)
                else text.getString(R.string.main_state_isf_unit_mmol, v)
            val factors = buildList {
                if (kotlin.math.abs(isf.todFactor - 1.0) >= 0.02) {
                    add(text.getString(R.string.main_state_isf_factor_tod, isf.todFactor))
                }
                if (kotlin.math.abs(isf.autosensFactor - 1.0) >= 0.02) {
                    add(text.getString(R.string.main_state_isf_factor_autosens, isf.autosensFactor))
                }
            }
            if (factors.isEmpty()) text.getString(R.string.main_state_applied_isf_plain, isfText(isf.effectiveMmolPerU))
            else text.getString(
                R.string.main_state_applied_isf_factors,
                isfText(isf.effectiveMmolPerU), isfText(isf.baseMmolPerU), factors.joinToString(" × "),
            )
        },
    )
    android.util.Log.i(
        "MainStatePerf",
        "loadState allowBuild=$allowBuild in " +
            "${android.os.SystemClock.elapsedRealtime() - perfStartedMs} ms; " +
            "chart=${result.chartReadings.size}, prediction=${result.prediction.size}, " +
            "iob=${result.iobSeries.size}, includeHistory=$includeHistory, " +
            "historyNotes=${result.historyNotes.size}, historyMeals=${result.historyMeals.size}, " +
            "historyBoluses=${result.historyBoluses.size}, historyReadings=${result.historyReadings.size}",
    )
    // Nothing below is required to render the graph or History. Keep the whole
    // maintenance bundle off the foreground path and serialize it with Closed
    // Episodes so their SQLite writes cannot contend with each other.
    if(allowBuild) (store as? SqliteCollectorStore)?.let { sqlite ->
        UiMaintenanceQueue.schedule {
            val db=sqlite.writableDatabase
            // THROTTLED, AND THE TIMER IS WHY. This bundle was left running on
            // every full refresh because it «was never measured to cost
            // anything» — then the timer put beside it to check that very
            // assumption printed `maintenance scoring in 10614 ms`. Eleven
            // seconds against the same SQLite file the screen reads from.
            //
            // Scoring is inherently retrospective: a forecast made for h=60
            // cannot be scored until an hour of reality has happened, so asking
            // several times a minute can only re-answer what it just answered.
            // Five minutes matches the interval the closed-episode pass already
            // uses, so the two maintenance clocks agree instead of each being
            // chosen separately.
            //
            // Per-call timers stay: the bundle is five different jobs and
            // nothing yet says which of them owns the eleven seconds. Throttling
            // buys the time back; it does not explain it, and a later session
            // should not have to re-derive that distinction.
            val sinceScoring=android.os.SystemClock.elapsedRealtime()-lastScoringMs
            if(lastScoringMs!=0L&&sinceScoring<SCORING_EVERY_MS){
                return@schedule
            }
            lastScoringMs=android.os.SystemClock.elapsedRealtime()
            val tBundle=android.os.SystemClock.elapsedRealtime()
            fun <T> stage(label:String,block:()->T):T {
                val t0=android.os.SystemClock.elapsedRealtime()
                return block().also{
                    val ms=android.os.SystemClock.elapsedRealtime()-t0
                    if(ms>=200)android.util.Log.i("MainStatePerf","  maintenance $label in $ms ms")
                }
            }
            stage("dropRetiredLedgers"){LedgerRetention.dropRetiredLedgers(context,sqlite)}
            // The sweep is also wired into `TreatmentsPollWorker`, and in the
            // whole recorded history of this device it never emitted a single
            // log line — so «growth is capped» rested on a worker nobody had
            // seen fire. It is self-throttled to once a day and fails open;
            // calling it from here as well costs nothing and makes the cap a
            // fact rather than an intention.
            stage("ledgerRetention"){LedgerRetention.runIfDue(context,sqlite)}
            stage("ledgerReclaim"){LedgerRetention.reclaimOnce(context,sqlite)}
            stage("dailyDiscrepancy"){DailyDiscrepancyRuntime.update(store,db,now)}
            stage("adaptiveIsf"){
                AdaptiveIsfRuntime.refreshIfDue(context,store,now)
            }
            val tScoring=android.os.SystemClock.elapsedRealtime()-tBundle
            // THROTTLED, AND ONLY THIS ONE. A live check showed the
            // closed-episode pass costing several seconds (`reconcile` alone
            // taking most of that, over several windows) and it was running on EVERY full
            // state refresh. It sits on a background queue, so it never blocked
            // a frame — but it hammers the same SQLite file the screen reads
            // from, which is exactly the contention that made every other timing
            // unreadable during the ledger sweep this morning.
            //
            // Its inputs are closed insulin episodes: they appear hours apart,
            // never seconds apart, so re-deriving them several times a minute
            // cannot find anything new. Ten minutes loses no evidence and no
            // learning — it only stops the same derivation being repeated.
            //
            // The other calls above are NOT throttled: they were never measured
            // to cost anything, and `scoreDue` reaching the ledger promptly is
            // the point of a ledger. The timer beside them exists so the next
            // session can see whether that assumption still holds instead of
            // inheriting it.
            // The throttle now lives inside the runtime, shared by all three
            // entry points — a gate here covered only this one.
            if(tScoring>=100)android.util.Log.i(
                "MainStatePerf","maintenance scoring in $tScoring ms",
            )
        }
    }
    return result
}

/** How often the ledger-scoring bundle may run. See the note at its call. */
private const val SCORING_EVERY_MS = 5L * 60_000L

/** Process-scoped: a fresh process scores immediately, which is right. */
@Volatile
private var lastScoringMs = 0L

/** Latest-wins serial queue for scoring and observational maintenance. */
internal object UiMaintenanceQueue {
    private val executor=java.util.concurrent.ThreadPoolExecutor(
        1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1),
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    fun schedule(block:()->Unit){
        try{executor.execute{runCatching(block).onFailure{android.util.Log.w("UiMaintenance","failed open",it)}}}
        catch(t:Throwable){android.util.Log.w("UiMaintenance","schedule failed open",t)}
    }
}
