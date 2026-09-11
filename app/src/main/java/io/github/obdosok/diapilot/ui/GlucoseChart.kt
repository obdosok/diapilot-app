package io.github.obdosok.diapilot.ui

import com.diapilot.core.analysis.effectEndMs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.PathEffect
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.MealEvent
import com.diapilot.core.twin.PredictedPoint
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.i18n.localized
import io.github.obdosok.diapilot.i18n.resolve
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

internal fun conditionalBolusScenarioLabel(units: Double): io.github.obdosok.diapilot.i18n.UiText =
    io.github.obdosok.diapilot.i18n.UiText.res(
        R.string.glucose_chart_conditional_bolus_scenario,
        units
    )

/** Target glycemic range, mmol/L (chart band; not medical advice — display only). */
private const val RANGE_LO = 3.9
private const val RANGE_HI = 10.0

/** Break the curve where consecutive readings are further apart than this. */
private const val GAP_MS = 12 * 60_000L

private const val HOUR_MS = 3_600_000L
private const val MIN_SPAN_MS = 1 * HOUR_MS

private enum class ChartTool { DATE, NOW }

@Composable
private fun ChartToolButton(
    tool: ChartTool,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        Modifier
            .size(34.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick)
            .padding(7.dp),
    ) {
        val sw = size.minDimension * 0.09f
        when (tool) {
            ChartTool.DATE -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(size.width * .12f, size.height * .20f),
                    size = androidx.compose.ui.geometry.Size(size.width * .76f, size.height * .68f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .10f),
                    style = Stroke(sw),
                )
                drawLine(color, Offset(size.width * .12f, size.height * .40f), Offset(size.width * .88f, size.height * .40f), sw)
                listOf(.36f, .64f).forEach { x ->
                    drawLine(color, Offset(size.width * x, size.height * .10f), Offset(size.width * x, size.height * .29f), sw, StrokeCap.Round)
                }
            }
            ChartTool.NOW -> {
                drawLine(color, Offset(size.width * .10f, size.height * .50f), Offset(size.width * .82f, size.height * .50f), sw, StrokeCap.Round)
                drawLine(color, Offset(size.width * .58f, size.height * .27f), Offset(size.width * .82f, size.height * .50f), sw, StrokeCap.Round)
                drawLine(color, Offset(size.width * .58f, size.height * .73f), Offset(size.width * .82f, size.height * .50f), sw, StrokeCap.Round)
                drawLine(color.copy(alpha = .45f), Offset(size.width * .90f, size.height * .15f), Offset(size.width * .90f, size.height * .85f), sw)
            }
        }
    }
}

/**
 * Retrospective open-loop counterfactual: add back the selected bolus' learned
 * negative impulse response to the observed CGM. Other events stay untouched.
 * The line stops at the learned kernel horizon; beyond it physiology is not
 * identifiable from superposition alone.
 */
internal fun withoutBolusSeries(
    readings: List<GlucosePoint>,
    bolus: BolusPoint,
    kernel: List<com.diapilot.core.analysis.KernelPoint>,
    scale: Double = 1.0,
): List<GlucosePoint> {
    if (
        kernel.isEmpty() ||
            bolus.units <= 0.0 ||
            com.diapilot.core.analysis.isPrimePurpose(bolus.purpose)
    )
        return emptyList()
    val sorted = readings.sortedBy { it.tsMs }
    val horizonMs = (kernel.maxOf { it.tauMin } * 60_000).toLong()
    val anchor = sorted.lastOrNull { it.tsMs <= bolus.tsMs && bolus.tsMs - it.tsMs <= 20 * 60_000L }
    val affected =
        sorted
            .asSequence()
            .filter { it.tsMs > bolus.tsMs && it.tsMs <= bolus.tsMs + horizonMs }
            .map { p ->
                val age = (p.tsMs - bolus.tsMs) / 60_000.0
                val insulinDelta =
                    bolus.units * (com.diapilot.core.analysis.kernelAt(kernel, age) ?: 0.0) * scale
                p.copy(mmol = p.mmol - insulinDelta)
            }
            .toList()
    return listOfNotNull(anchor) + affected
}

internal data class CounterfactualInsulinAudit(
    val observedFall: Double,
    val explainedLow: Double,
    val explainedMedian: Double,
    val explainedHigh: Double,
    val unexplainedLowering: Double,
    val materiallyUnderExplained: Boolean,
)

/**
 * Audits the selected bolus without pretending that every residual is insulin.
 * We measure the actual fall from the local post-shot maximum to the subsequent
 * trough, and compare it with the full ISF uncertainty interval of the exact
 * kernel used to draw the counterfactual. A positive residual after the strong
 * boundary is evidence that this episode is not explained by the current
 * insulin profile; it is deliberately not assigned to food or to an ISF cause.
 */
internal fun counterfactualInsulinAudit(
    readings: List<GlucosePoint>,
    bolus: BolusPoint,
    kernel: List<com.diapilot.core.analysis.KernelPoint>,
    landmarks: Triple<Double, Double, Double>,
): CounterfactualInsulinAudit? {
    if (kernel.isEmpty() || bolus.units <= 0.0) return null
    val peakEnd = bolus.tsMs + ((landmarks.second + 15.0) * 60_000).toLong()
    val endMs = bolus.tsMs + (minOf(landmarks.third, landmarks.second + 150.0) * 60_000).toLong()
    val start =
        readings.filter { it.tsMs in bolus.tsMs..peakEnd }.maxByOrNull { it.mmol } ?: return null
    val end =
        readings.filter { it.tsMs > start.tsMs && it.tsMs <= endMs }.minByOrNull { it.mmol }
            ?: return null
    if (end.tsMs - start.tsMs < 15 * 60_000L) return null
    fun value(tau: Double, which: Int): Double {
        val p = kernel.lastOrNull { it.tauMin <= tau } ?: return 0.0
        return when (which) {
            -1 -> p.q1;
            1 -> p.q3;
            else -> p.median
        }
    }
    val a0 = (start.tsMs - bolus.tsMs) / 60_000.0
    val a1 = (end.tsMs - bolus.tsMs) / 60_000.0
    fun action(which: Int) =
        (-bolus.units * (value(a1, which) - value(a0, which))).coerceAtLeast(0.0)
    val actions = listOf(action(-1), action(0), action(1))
    val low = actions.minOrNull() ?: 0.0;
    val high = actions.maxOrNull() ?: 0.0
    val observed = (start.mmol - end.mmol).coerceAtLeast(0.0)
    val residual = (observed - high).coerceAtLeast(0.0)
    return CounterfactualInsulinAudit(observed, low, actions[1], high, residual, residual >= 0.8)
}

/**
 * Glucose curve with target band, dose-labeled insulin diamonds and
 * meal-onset markers. Interactive viewport over [fromMs, toMs]:
 * pinch to zoom (1h..full range), drag to pan into the past,
 * double-tap to snap back to "now" at the initial span.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GlucoseChart(
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    meals: List<MealEvent>,
    fromMs: Long,
    toMs: Long,
    initialSpanMs: Long = 24 * HOUR_MS,
    prediction: List<PredictedPoint> = emptyList(),
    comparisonPrediction: List<PredictedPoint> = emptyList(),
    /**
     * LONG-PRESS: the same moment, forecast twice.
     *
     * [inspectStored] is what the app actually drew then, straight from the
     * ledger — six horizons, the model and the coefficients of that day.
     * [inspectReplay] is today's model placed at that same anchor with its
     * knowledge cut there, so it is blind to everything logged afterwards.
     *
     * The gap between REPLAY and the real trace is «did the model get better».
     * The gap between STORED and a replay of the OLD coefficients is the
     * discipline-#7 acceptance test: if the stand cannot reproduce what the
     * device drew, it is measuring a model that never ran.
     */
    inspectStored: List<PredictedPoint> = emptyList(),
    inspectReplay: List<PredictedPoint> = emptyList(),
    whatIf: List<PredictedPoint> = emptyList(),
    comparisonWhatIf: List<PredictedPoint> = emptyList(),
    // Anchor-relative fire times of the What-if doses — where each hypothetical
    // injection turns on. Drawn as a syringe tick on the what-if branch.
    whatIfDoseMarks: List<Long> = emptyList(),
    // Planned walk from the inline What-if panel. It is deliberately distinct
    // from measured activityWindows: dashed = scenario, solid = observed fact.
    whatIfActivityWindow: Pair<Long, Long>? = null,
    minuteReadings: List<GlucosePoint> = emptyList(),
    meterChecks: List<GlucosePoint> = emptyList(),
    annotations: List<Annotation> = emptyList(),
    basals: List<BolusPoint> = emptyList(),
    // Merged activity bouts (HR+steps+notes, deduped) — drawn as a bottom
    // lane so what the exercise model counts is VISIBLE, incl. auto-detected
    // walks the user never logged.
    activityWindows: List<com.diapilot.core.analysis.ActivityWindow> = emptyList(),
    /** Draw the post-bout REACH (core.ACTIVITY_EFFECT_TAIL_MIN) after each bout.
     *  Only when the model actually applies an activity effect — with the learned
     *  coefficient at 0 the calculation applies nothing, and drawing a tail then
     *  shows the user an effect that is not in the forecast. */
    activityEffectActive: Boolean = false,
    // "What's still coming": insulin (units) and food (grams) still to act. Each is
    // drawn in the bottom lane on its OWN scale — units and grams share no axis,
    // and forcing them onto the mmol axis would invite reading one against the
    // other. The shape and the emptying moment are the information here.
    iobSeries: List<Pair<Long, Double>> = emptyList(),
    cobSeries: List<Pair<Long, Double>> = emptyList(),
    modifier: Modifier = Modifier,
    canvasHeight: androidx.compose.ui.unit.Dp = 180.dp,
    labelByOnset: Map<Long, String> = emptyMap(),
    mgdl: Boolean = false,
    rangeLo: Double = RANGE_LO,
    rangeHi: Double = RANGE_HI,
    targetMmol: Double? = null,
    insulinDiaMin: Double = 300.0,
    insulinPeakMin: Double = 75.0,
    // Learned cumulative mmol/L per unit used by retrospective food
    // deconvolution. Empty means the app cannot honestly draw «without shot».
    counterfactualKernels: Map<Long, List<com.diapilot.core.analysis.KernelPoint>> = emptyMap(),
    counterfactualLandmarks: Map<Long, Triple<Double, Double, Double>> = emptyMap(),
    onTagBolus: (tsMs: Long, purpose: String?) -> Unit = { _, _ -> },
    onDeleteNote: (id: Long) -> Unit = {},
    onDeleteBasal: (tsMs: Long) -> Unit = {},
    // Full edit parity with the History modals (dose, delete, basal units+time).
    onEditBolusUnits: (tsMs: Long, units: Double) -> Unit = { _, _ -> },
    onDeleteBolus: (tsMs: Long) -> Unit = {},
    onUpdateBasal: (oldTs: Long, newTs: Long, units: Double) -> Unit = { _, _, _ -> },
    onOpenNote: (id: Long) -> Unit = {},
    onExplainFood: (id: Long) -> Unit = {},
    focusTs: Long? = null,
    onFocusHandled: () -> Unit = {},
    // History navigation: browse a 3-day window anchored on a chosen date.
    // navActive = currently viewing history (not live); onHistoryNav(date) sets
    // it, onHistoryNav(null) returns to live.
    navActive: Boolean = false,
    onHistoryNav: (Long?) -> Unit = {},
    /** Long-press anywhere on the chart: «what did the app forecast HERE, and
     *  what actually happened». Long-press rather than tap so the existing
     *  selection popup is untouched. */
    onInspectForecast: (tsMs: Long) -> Unit = {},
) {
    val line = MaterialTheme.colorScheme.primary
    val bolusColor = MaterialTheme.colorScheme.tertiary
    val mealColor = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bandFill = line.copy(alpha = 0.08f)
    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(fontSize = 10.sp, color = textColor)
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // Viewport: anchorTo == null means "follow live data edge".
    var anchorTo by remember { mutableStateOf<Long?>(null) }
    var spanMs by remember { mutableStateOf(initialSpanMs.coerceAtMost(toMs - fromMs)) }
    var widthPx by remember { mutableStateOf(1f) }
    var heightPx by remember { mutableStateOf(1f) }
    var padLeftPx by remember { mutableStateOf(0f) }
    var selection by remember { mutableStateOf<ChartSelection?>(null) }
    var selPos by remember { mutableStateOf(Offset.Zero) }
    var scrubTs by remember { mutableStateOf<Long?>(null) }
    var hiddenBolusTs by rememberSaveable { mutableStateOf<Long?>(null) }

    val fullSpan = (toMs - fromMs).coerceAtLeast(MIN_SPAN_MS)
    val viewTo = (anchorTo ?: toMs).coerceIn(minOf(fromMs + spanMs, toMs), toMs)
    val viewFrom = viewTo - spanMs

    // Jump request from the history screen: center a 3h viewport on the
    // event, then a fading halo shows WHICH mark you jumped to — landing
    // on a busy chart without a pointer meant hunting for it.
    var highlightTs by remember { mutableStateOf<Long?>(null) }
    val highlightAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(focusTs) {
        focusTs?.let { ts ->
            spanMs = (3 * HOUR_MS).coerceAtMost(fullSpan)
            val newTo = (ts + spanMs / 2).coerceIn(minOf(fromMs + spanMs, toMs), toMs)
            anchorTo = if (newTo >= toMs) null else newTo
            highlightTs = ts
            // NOT the place to animate: onFocusHandled() nulls focusTs,
            // which restarts THIS effect and would cancel the animation
            // one frame in — the halo then never fades.
            onFocusHandled()
        }
    }
    androidx.compose.runtime.LaunchedEffect(highlightTs) {
        if (highlightTs != null) {
            highlightAlpha.snapTo(1f)
            highlightAlpha.animateTo(
                0f,
                androidx.compose.animation.core.tween(durationMillis = 2500),
            )
            highlightTs = null
        }
    }

    // Gesture handlers must not capture the composition-time snapshot: the
    // pointerInput blocks are keyed on Unit (a data refresh keyed on the
    // window would CANCEL a drag mid-scrub — loadState ticks every 5 s), so
    // the closures outlive recompositions and every value they read has to
    // flow through rememberUpdatedState.
    val fromMsLive by androidx.compose.runtime.rememberUpdatedState(fromMs)
    val toMsLive by androidx.compose.runtime.rememberUpdatedState(toMs)
    val readingsLive by androidx.compose.runtime.rememberUpdatedState(readings)
    val bolusesLive by androidx.compose.runtime.rememberUpdatedState(boluses)
    val mealsLive by androidx.compose.runtime.rememberUpdatedState(meals)
    val annotationsLive by androidx.compose.runtime.rememberUpdatedState(annotations)
    val basalsLive by androidx.compose.runtime.rememberUpdatedState(basals)
    val labelByOnsetLive by androidx.compose.runtime.rememberUpdatedState(labelByOnset)
    fun liveFullSpan(): Long = (toMsLive - fromMsLive).coerceAtLeast(MIN_SPAN_MS)
    fun liveViewTo(): Long =
        (anchorTo ?: toMsLive).coerceIn(minOf(fromMsLive + spanMs, toMsLive), toMsLive)
    fun tsAtX(xPx: Float): Long {
        val vTo = liveViewTo()
        val vFrom = vTo - spanMs
        return vFrom + ((xPx - padLeftPx) / widthPx * spanMs).toLong()
    }

    Column(modifier = modifier) {
        // Fixed-scale chips: one tap to a predictable window (Libre/Dexcom
        // pattern) — faster than pinching for the everyday glance.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                    stringResource(R.string.glucose_chart_span_3h) to 3 * HOUR_MS,
                    stringResource(R.string.glucose_chart_span_6h) to 6 * HOUR_MS,
                    stringResource(R.string.glucose_chart_span_24h) to 24 * HOUR_MS,
                    stringResource(R.string.glucose_chart_span_3d) to 72 * HOUR_MS,
                )
                .forEach { (label, span) ->
                    val selected = spanMs == span.coerceAtMost(fullSpan)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.background(
                                    if (selected)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    else Color.Transparent,
                                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                )
                                .clickable { spanMs = span.coerceAtMost(fullSpan) }
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            if (anchorTo != null) {
                ChartToolButton(
                    ChartTool.NOW,
                    contentDescription = stringResource(R.string.glucose_chart_cd_return_to_now),
                    onClick = { anchorTo = null },
                )
            }
            // History navigation: 📅 jumps the 3-day window to any past date;
            // "live" returns to the live edge.
            var showDatePicker by remember { mutableStateOf(false) }
            ChartToolButton(
                ChartTool.DATE,
                contentDescription = stringResource(R.string.glucose_chart_cd_pick_date),
                active = navActive,
                onClick = { showDatePicker = true },
            )
            if (navActive) {
                ChartToolButton(
                    ChartTool.NOW,
                    contentDescription = stringResource(R.string.glucose_chart_cd_return_to_now),
                    onClick = { onHistoryNav(null) },
                )
            }
            if (showDatePicker) {
                HistoryDatePicker(
                    onPick = {
                        showDatePicker = false;
                        it?.let(onHistoryNav)
                    },
                    onDismiss = { showDatePicker = false },
                )
            }
        }
        hiddenBolusTs?.let { ts ->
            boluses
                .firstOrNull { it.tsMs == ts }
                ?.let { b ->
                    val lm = counterfactualLandmarks[ts]
                    val audit = lm?.let {
                        counterfactualInsulinAudit(
                            readings,
                            b,
                            counterfactualKernels[ts].orEmpty(),
                            it
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                lm?.let {
                                    stringResource(
                                        R.string.glucose_chart_dashed_line_with_landmarks,
                                        conditionalBolusScenarioLabel(b.units).resolve(),
                                        it.first,
                                        it.second,
                                        it.third,
                                    )
                                }
                                    ?: stringResource(
                                        R.string.glucose_chart_dashed_line,
                                        conditionalBolusScenarioLabel(b.units).resolve()
                                    ),
                                style = MaterialTheme.typography.labelSmall,
                                color = bolusColor,
                            )
                            audit?.let { a ->
                                Text(
                                    if (a.materiallyUnderExplained)
                                        stringResource(
                                            R.string.glucose_chart_audit_underexplained,
                                            a.explainedLow,
                                            a.explainedHigh,
                                            a.observedFall,
                                            a.unexplainedLowering,
                                        )
                                    else
                                        stringResource(
                                            R.string.glucose_chart_audit_explained,
                                            a.observedFall,
                                            a.explainedLow,
                                            a.explainedHigh,
                                        ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color =
                                        if (a.materiallyUnderExplained)
                                            MaterialTheme.colorScheme.error
                                        else textColor,
                                )
                                if (a.materiallyUnderExplained)
                                    Text(
                                        stringResource(R.string.glucose_chart_audit_caveat),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textColor,
                                    )
                            }
                        }
                        TextButton(onClick = { hiddenBolusTs = null }) {
                            Text(stringResource(R.string.glucose_chart_restore))
                        }
                    }
                }
        }
        Box(modifier = Modifier.fillMaxWidth().height(canvasHeight),) {
            Canvas(
                modifier =
                    Modifier.matchParentSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (zoom != 1f) {
                                    spanMs =
                                        (spanMs / zoom)
                                            .toLong()
                                            .coerceIn(MIN_SPAN_MS, liveFullSpan())
                                }
                                if (pan.x != 0f) {
                                    val dt = (-pan.x / widthPx * spanMs).toLong()
                                    val newTo =
                                        ((anchorTo ?: toMsLive) + dt).coerceIn(
                                            minOf(fromMsLive + spanMs, toMsLive),
                                            toMsLive
                                        )
                                    anchorTo = if (newTo >= toMsLive) null else newTo
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            // Long-press scrubber: crosshair follows the finger for
                            // continuous precise reading (Libre-style). RELEASING
                            // freezes the spot into a selection with its popup —
                            // scrub to aim, lift to inspect.
                            var lastScrub: Offset? = null
                            detectDragGesturesAfterLongPress(
                                onDragStart = { off ->
                                    lastScrub = off
                                    scrubTs = tsAtX(off.x)
                                },
                                onDrag = { change, _ ->
                                    lastScrub = change.position
                                    scrubTs = tsAtX(change.position.x)
                                },
                                onDragEnd = {
                                    scrubTs = null
                                    lastScrub?.let { off ->
                                        val vTo = liveViewTo()
                                        selPos = off
                                        selection =
                                            selectTapped(
                                                context,
                                                off,
                                                padLeftPx,
                                                widthPx,
                                                vTo - spanMs,
                                                vTo,
                                                readingsLive,
                                                bolusesLive,
                                                mealsLive,
                                                annotationsLive,
                                                basalsLive,
                                                labelByOnsetLive,
                                                mgdl,
                                            )
                                    }
                                },
                                onDragCancel = { scrubTs = null },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { off ->
                                    // Trading-style: zoom ×2 around the tapped moment;
                                    // at max zoom, snap back to the initial live view.
                                    if (spanMs > MIN_SPAN_MS) {
                                        val center = tsAtX(off.x)
                                        spanMs = (spanMs / 2).coerceAtLeast(MIN_SPAN_MS)
                                        val newTo =
                                            (center + spanMs / 2).coerceIn(
                                                minOf(fromMsLive + spanMs, toMsLive),
                                                toMsLive
                                            )
                                        anchorTo = if (newTo >= toMsLive) null else newTo
                                    } else {
                                        anchorTo = null
                                        spanMs = initialSpanMs.coerceAtMost(liveFullSpan())
                                    }
                                },
                                onLongPress = { off -> onInspectForecast(tsAtX(off.x)) },
                                onTap = { off ->
                                    val vTo = liveViewTo()
                                    selPos = off
                                    selection =
                                        selectTapped(
                                            context,
                                            off,
                                            padLeftPx,
                                            widthPx,
                                            vTo - spanMs,
                                            vTo,
                                            readingsLive,
                                            bolusesLive,
                                            mealsLive,
                                            annotationsLive,
                                            basalsLive,
                                            labelByOnsetLive,
                                            mgdl,
                                        )
                                },
                            )
                        },
            ) {
                val padL = with(density) { 28.dp.toPx() }
                val padB = with(density) { 16.dp.toPx() }
                val w = size.width - padL
                val h = size.height - padB
                if (w <= 0 || h <= 0) return@Canvas
                widthPx = w
                heightPx = h
                padLeftPx = padL

                val visible = readings.filter { it.tsMs in viewFrom..viewTo }
                val selectedBolus = hiddenBolusTs?.let { ts ->
                    boluses.firstOrNull { it.tsMs == ts }
                }
                val withoutSelected =
                    selectedBolus
                        ?.let {
                            withoutBolusSeries(
                                readings,
                                it,
                                counterfactualKernels[it.tsMs].orEmpty()
                            )
                        }
                        .orEmpty()
                        .filter { it.tsMs in viewFrom..viewTo }
                val visiblePred = prediction.filter { it.tsMs in viewFrom..viewTo }
                val visibleCompare = comparisonPrediction.filter { it.tsMs in viewFrom..viewTo }
                val visibleCompareWhatIf = comparisonWhatIf.filter { it.tsMs in viewFrom..viewTo }
                val dataMax =
                    maxOf(
                        visible.maxOfOrNull { it.mmol } ?: 10.0,
                        visiblePred.maxOfOrNull { it.hi } ?: 0.0,
                        visibleCompare.maxOfOrNull { it.hi } ?: 0.0,
                        withoutSelected.maxOfOrNull { it.mmol } ?: 0.0,
                    )
                val maxBg = max(14.0, ceil(dataMax + 1))
                val minBg = 2.0
                fun x(ts: Long) =
                    padL + w * (ts - viewFrom).toFloat() / (viewTo - viewFrom).toFloat()
                fun y(bg: Double) = (h * (1 - (bg - minBg) / (maxBg - minBg))).toFloat()

                // Target band — a wash, never a saturated block.
                drawRect(
                    color = bandFill,
                    topLeft = Offset(padL, y(rangeHi)),
                    size = androidx.compose.ui.geometry.Size(w, y(rangeLo) - y(rangeHi)),
                )

                // Activity lane: thin strips along the bottom for merged bouts
                // (HR + steps + manual notes — exactly what the model consumes).
                // Answers "did it count my walk?" at a glance.
                val laneH = with(density) { 4.dp.toPx() }
                activityWindows
                    .filter { it.endMs > viewFrom && it.startMs < viewTo }
                    .forEach { aw ->
                        // The REACH first (fainter, behind), only when the model
                        // actually applies an effect — one definition of "how far a
                        // walk reaches", shared with the regime and the food corpus.
                        if (activityEffectActive) {
                            val tailEnd = aw.effectEndMs()
                            if (tailEnd > aw.endMs) {
                                val t0 = x(maxOf(aw.endMs, viewFrom))
                                val t1 = x(minOf(tailEnd, viewTo))
                                if (t1 > t0) {
                                    drawRoundRect(
                                        color = bolusColor.copy(alpha = 0.18f),
                                        topLeft = Offset(t0, h - laneH),
                                        size =
                                            androidx.compose.ui.geometry.Size(
                                                (t1 - t0).coerceAtLeast(1f),
                                                laneH,
                                            ),
                                        cornerRadius =
                                            androidx.compose.ui.geometry.CornerRadius(laneH / 2),
                                    )
                                }
                            }
                        }
                        val x0 = x(maxOf(aw.startMs, viewFrom))
                        val x1 = x(minOf(aw.endMs, viewTo))
                        drawRoundRect(
                            color = bolusColor.copy(alpha = 0.45f),
                            topLeft = Offset(x0, h - laneH),
                            size =
                                androidx.compose.ui.geometry.Size(
                                    (x1 - x0).coerceAtLeast(laneH),
                                    laneH,
                                ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(laneH / 2),
                        )
                    }
                whatIfActivityWindow?.let { (startMs, endMs) ->
                    if (endMs > viewFrom && startMs < viewTo) {
                        val x0 = x(maxOf(startMs, viewFrom))
                        val x1 = x(minOf(endMs, viewTo))
                        val width = (x1 - x0).coerceAtLeast(laneH)
                        drawRoundRect(
                            color = mealColor.copy(alpha = 0.10f),
                            topLeft = Offset(x0, h - laneH),
                            size = androidx.compose.ui.geometry.Size(width, laneH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(laneH / 2),
                        )
                        drawRoundRect(
                            color = mealColor.copy(alpha = 0.75f),
                            topLeft = Offset(x0, h - laneH),
                            size = androidx.compose.ui.geometry.Size(width, laneH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(laneH / 2),
                            style =
                                androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.5f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
                                ),
                        )
                    }
                }

                // IOB / COB lanes — "how much is still coming" from the insulin and
                // from the food. Filled areas along the bottom, each normalized to
                // its OWN max in view: units and grams have no common axis, and the
                // question they answer is "how much is left and when does it end",
                // not "which is bigger".
                run {
                    // A STRIP, not a second plot. At 28% these lanes covered
                    // 50-105 mg/dl — the hypo band, the most important part of the
                    // chart — and read as data rather than as context. 12% keeps the
                    // shape and the emptying moment legible while leaving the glucose
                    // curve the canvas it needs.
                    val laneH = h * 0.055f
                    val laneGap = with(density) { 2.dp.toPx() }
                    fun drawLane(
                        series: List<Pair<Long, Double>>,
                        color: Color,
                        baseline: Float,
                    ) {
                        val pts = series.filter { it.first in viewFrom..viewTo }
                        val peak = pts.maxOfOrNull { it.second } ?: return
                        if (peak <= 0.0 || pts.size < 2) return
                        fun yOf(v: Double) = baseline - (v / peak).toFloat() * laneH
                        drawPath(
                            Path().apply {
                                moveTo(x(pts.first().first), baseline)
                                pts.forEach { (t, v) -> lineTo(x(t), yOf(v)) }
                                lineTo(x(pts.last().first), baseline)
                                close()
                            },
                            color = color.copy(alpha = 0.10f),
                        )
                        var prev: Offset? = null
                        pts.forEach { (t, v) ->
                            val p = Offset(x(t), yOf(v))
                            prev?.let {
                                drawLine(color.copy(alpha = 0.55f), it, p, strokeWidth = 1.5f)
                            }
                            prev = p
                        }
                    }
                    // Separate rails: COB above, IOB below. They used to share a
                    // baseline and looked like two contradictory IOB curves.
                    drawLane(cobSeries, Color(0xFF66BB6A), h - laneH - laneGap)
                    drawLane(iobSeries, bolusColor, h)
                }

                // Hairline gridlines + y ticks at values that are ROUND in the
                // display units: every 50 mg/dl (xDrip-style) or every 3 mmol.
                val mgdlPerMmol = com.diapilot.core.analysis.MGDL_PER_MMOL_F.toDouble()
                val tickPairs: List<Pair<Double, String>> =
                    if (mgdl) {
                        generateSequence(50.0) { it + 50.0 }
                            .map { it / mgdlPerMmol to "%.0f".format(it) }
                            .takeWhile { it.first < maxBg }
                            .filter { it.first > minBg }
                            .toList()
                    } else {
                        listOf(3.0, 6.0, 9.0, 12.0, 15.0, 18.0)
                            .filter { it > minBg && it < maxBg }
                            .map { it to "%.0f".format(it) }
                    }
                for ((tickMmol, text) in tickPairs) {
                    val ty = y(tickMmol)
                    drawLine(grid, Offset(padL, ty), Offset(padL + w, ty), strokeWidth = 1f)
                    val layout = measurer.measure(text, tickStyle)
                    drawText(
                        measurer,
                        text,
                        Offset(padL - layout.size.width - 6f, ty - layout.size.height / 2f),
                        tickStyle
                    )
                }

                // The target: a thin dashed line at the ideal BG the hints aim at
                // — the eye needs the "100" anchor, not just the green band.
                targetMmol
                    ?.takeIf { it in minBg..maxBg }
                    ?.let { t ->
                        val ty = y(t)
                        drawLine(
                            line.copy(alpha = 0.55f),
                            Offset(padL, ty),
                            Offset(padL + w, ty),
                            strokeWidth = with(density) { 1.5.dp.toPx() },
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                        )
                    }

                // X grid step adapts to the zoom level.
                val stepMs =
                    when {
                        spanMs <= 3 * HOUR_MS -> 30 * 60_000L
                        spanMs <= 9 * HOUR_MS -> HOUR_MS
                        spanMs <= 30 * HOUR_MS -> 6 * HOUR_MS
                        else -> 12 * HOUR_MS
                    }
                val fmt =
                    SimpleDateFormat(
                        if (spanMs > 30 * HOUR_MS) "d.MM HH:mm" else "HH:mm",
                        Locale.getDefault()
                    )
                var t = viewFrom - viewFrom % stepMs + stepMs
                while (t < viewTo) {
                    val tx = x(t)
                    drawLine(grid, Offset(tx, 0f), Offset(tx, h), strokeWidth = 1f)
                    val label = fmt.format(Date(t))
                    val layout = measurer.measure(label, tickStyle)
                    drawText(
                        measurer,
                        label,
                        Offset(tx - layout.size.width / 2f, h + 2f),
                        tickStyle
                    )
                    t += stepMs
                }

                // Which DAY am I looking at: the left-edge date in the top-left
                // corner, and a fresh date label right after each midnight line
                // inside the viewport — panning across midnight hands the corner
                // over naturally (the next day's label slides in from its line).
                run {
                    val dayFmtChart = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
                    val calDay =
                        java.util.Calendar.getInstance().apply {
                            timeInMillis = viewFrom
                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }
                    var lastLabelEndX = padL
                    // Left-edge day, pinned to the corner.
                    val corner = dayFmtChart.format(Date(viewFrom))
                    val cl = measurer.measure(corner, tickStyle)
                    drawText(measurer, corner, Offset(padL + 4f, 2f), tickStyle)
                    lastLabelEndX = padL + 4f + cl.size.width + 12f
                    // Midnights inside the viewport.
                    calDay.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    while (calDay.timeInMillis < viewTo) {
                        val mx = x(calDay.timeInMillis)
                        val dl = dayFmtChart.format(Date(calDay.timeInMillis))
                        val ml = measurer.measure(dl, tickStyle)
                        drawLine(
                            grid,
                            Offset(mx, 0f),
                            Offset(mx, h),
                            strokeWidth = 2f,
                        )
                        if (mx + 4f > lastLabelEndX) {
                            drawText(measurer, dl, Offset(mx + 4f, 2f), tickStyle)
                            lastLabelEndX = mx + 4f + ml.size.width + 12f
                        }
                        calDay.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }
                }

                // Per-minute OOP2 stream: thin translucent trace under the main
                // curve, on the SAME calibrated scale (minute-cal + meter-cal in
                // MainState). It "leads" the white 5-min line purely by cadence —
                // 1-min points reach now, the 5-min grid is promoted later — so a
                // turn shows here first. Its recent tail can bulge during a rapid
                // move (the stream retroactively revises ~15 min; the frozen 5-min
                // grid, and the trend/delta computed from it, don't).
                if (minuteReadings.isNotEmpty()) {
                    val minuteStroke =
                        Stroke(
                            width = with(density) { 1.5.dp.toPx() },
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    val mPath = Path()
                    var mPrev: GlucosePoint? = null
                    for (p in
                        minuteReadings
                            .filter { it.tsMs in viewFrom..viewTo }
                            .sortedBy { it.tsMs }) {
                        if (mPrev == null || p.tsMs - mPrev.tsMs > 3 * 60_000L) {
                            mPath.moveTo(x(p.tsMs), y(p.mmol))
                        } else {
                            mPath.lineTo(x(p.tsMs), y(p.mmol))
                        }
                        mPrev = p
                    }
                    drawPath(mPath, mealColor.copy(alpha = 0.6f), style = minuteStroke)
                }

                // Glucose curve — 2dp, round caps, broken at data gaps.
                val stroke =
                    Stroke(
                        width = with(density) { 2.dp.toPx() },
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                val path = Path()
                var prev: GlucosePoint? = null
                for (p in visible.sortedBy { it.tsMs }) {
                    if (prev == null || p.tsMs - prev.tsMs > GAP_MS) {
                        path.moveTo(x(p.tsMs), y(p.mmol))
                    } else {
                        path.lineTo(x(p.tsMs), y(p.mmol))
                    }
                    prev = p
                }
                drawPath(path, line, style = stroke)

                // Counterfactual after removing exactly one bolus. Dashed and
                // deliberately unbanded: this is a decomposition lens over the
                // observed trace, not a claim that the body would follow it.
                if (withoutSelected.size > 1) {
                    val noShot = Path();
                    var noShotPrev: GlucosePoint? = null
                    for (p in withoutSelected.sortedBy { it.tsMs }) {
                        if (noShotPrev == null || p.tsMs - noShotPrev.tsMs > GAP_MS)
                            noShot.moveTo(x(p.tsMs), y(p.mmol))
                        else noShot.lineTo(x(p.tsMs), y(p.mmol))
                        noShotPrev = p
                    }
                    drawPath(
                        noShot,
                        bolusColor,
                        style =
                            Stroke(
                                width = with(density) { 2.dp.toPx() },
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                            )
                    )
                }

                // Hindsight forecast: the hour-old promise drawn as a thin dashed
                // trace over the fact — the gap IS the model's error, visible.
                // Stretches where events landed INSIDE the forecast's hour (food,
                // a shot — unknowable at forecast time) are muted: the model
                // wasn't wrong there, it was blindsided.
                if (inspectStored.size > 1 || inspectReplay.size > 1) {
                    val inspectStroke =
                        Stroke(
                            width = with(density) { 2.dp.toPx() },
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                        )
                    fun draw(
                        series: List<PredictedPoint>,
                        color: androidx.compose.ui.graphics.Color
                    ) {
                        val path = Path()
                        var started = false
                        series
                            .filter { it.tsMs in viewFrom..viewTo }
                            .sortedBy { it.tsMs }
                            .forEach { pt ->
                                if (!started) {
                                    path.moveTo(x(pt.tsMs), y(pt.mmol));
                                    started = true
                                } else path.lineTo(x(pt.tsMs), y(pt.mmol))
                            }
                        if (started) drawPath(path, color, style = inspectStroke)
                    }
                    // Stored first, so the replay draws over it where they agree.
                    draw(inspectStored, bolusColor.copy(alpha = 0.55f))
                    draw(inspectReplay, line)
                }

                fun bgAt(ts: Long): Double? =
                    readings
                        .filter { it.tsMs in viewFrom..viewTo }
                        .minByOrNull { kotlin.math.abs(it.tsMs - ts) }
                        ?.takeIf { kotlin.math.abs(it.tsMs - ts) < 20 * 60_000 }
                        ?.mmol

                // Insulin shots: diamond hanging just under the curve at injection
                // time (Libre-style) — the eye finds them along the line it is
                // already reading, not in a separate lane at the floor. Falls back
                // to the baseline when there is no curve around. Dose labels
                // stagger on two levels to dodge collisions.
                val dR = with(density) { 5.dp.toPx() }
                val ringR = dR + with(density) { 2.dp.toPx() }
                val hang = with(density) { 16.dp.toPx() }
                val doseStyle = TextStyle(fontSize = 10.sp, color = textColor)
                var lastLabelEnd = Float.NEGATIVE_INFINITY
                var level = 0
                for (b in boluses.sortedBy { it.tsMs }) {
                    if (b.tsMs !in viewFrom..viewTo) continue
                    val cx = x(b.tsMs)
                    val cy = bgAt(b.tsMs)?.let { (y(it) + hang).coerceAtMost(h - dR) } ?: (h - dR)
                    val isAir = com.diapilot.core.analysis.isPrimePurpose(b.purpose)
                    fun diamond(r: Float): Path =
                        Path().apply {
                            moveTo(cx, cy - r);
                            lineTo(cx + r, cy);
                            lineTo(cx, cy + r);
                            lineTo(cx - r, cy);
                            close()
                        }
                    drawPath(diamond(ringR), surface)
                    if (isAir) {
                        // Air shot (new-cartridge purge): hollow, faded — visibly
                        // "not insulin in the body", still tappable to unmark.
                        drawPath(
                            diamond(dR),
                            bolusColor.copy(alpha = 0.5f),
                            style = Stroke(width = with(density) { 1.5.dp.toPx() }),
                        )
                    } else {
                        drawPath(diamond(dR), bolusColor)
                    }
                    val doseText =
                        if (b.units % 1.0 == 0.0) "%.0f".format(b.units) else "%.1f".format(b.units)
                    val style =
                        if (isAir) doseStyle.copy(color = textColor.copy(alpha = 0.5f))
                        else doseStyle
                    val layout = measurer.measure(doseText, style)
                    val lx = cx - layout.size.width / 2f
                    level = if (lx <= lastLabelEnd) (level + 1) % 2 else 0
                    // Label under the diamond (the curve lives above it); flip up
                    // when the diamond sits at the very bottom.
                    val below = cy + ringR + 2f + layout.size.height * level
                    val ly =
                        if (below + layout.size.height <= h) below
                        else cy - ringR - layout.size.height * (1 + level) - 2f
                    drawText(measurer, doseText, Offset(lx, ly), style)
                    lastLabelEnd = lx + layout.size.width + 6f
                }

                // Forecast branch: two-layer uncertainty + dashed median. The
                // faint outer band is the honest p90/p10 ("rarely worse than
                // this"); the denser inner band is p75/p25 ("half the time you
                // are here") — the outer alone at full weight read as a scream.
                if (visiblePred.size > 1) {
                    val bandPath = Path()
                    visiblePred.forEachIndexed { i, p ->
                        if (i == 0) bandPath.moveTo(x(p.tsMs), y(p.hi))
                        else bandPath.lineTo(x(p.tsMs), y(p.hi))
                    }
                    visiblePred.reversed().forEach { p -> bandPath.lineTo(x(p.tsMs), y(p.lo)) }
                    bandPath.close()
                    // The full calculated corridor remains visible as honest limits,
                    // but is no longer a giant filled cone. The filled area below
                    // is its inner half for legibility (not a probability claim).
                    val outerStroke =
                        Stroke(
                            width = with(density) { 1.dp.toPx() },
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                        )
                    val upperOuter = Path()
                    val lowerOuter = Path()
                    visiblePred.forEachIndexed { i, p ->
                        if (i == 0) {
                            upperOuter.moveTo(x(p.tsMs), y(p.hi))
                            lowerOuter.moveTo(x(p.tsMs), y(p.lo))
                        } else {
                            upperOuter.lineTo(x(p.tsMs), y(p.hi))
                            lowerOuter.lineTo(x(p.tsMs), y(p.lo))
                        }
                    }
                    drawPath(upperOuter, line.copy(alpha = 0.22f), style = outerStroke)
                    drawPath(lowerOuter, line.copy(alpha = 0.22f), style = outerStroke)

                    val midPath = Path()
                    visiblePred.forEachIndexed { i, p ->
                        if (i == 0) midPath.moveTo(x(p.tsMs), y(p.hiMid))
                        else midPath.lineTo(x(p.tsMs), y(p.hiMid))
                    }
                    visiblePred.reversed().forEach { p -> midPath.lineTo(x(p.tsMs), y(p.loMid)) }
                    midPath.close()
                    drawPath(midPath, line.copy(alpha = 0.12f))

                    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    val predStroke =
                        Stroke(
                            width = with(density) { 2.dp.toPx() },
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = dash,
                        )
                    val predPath = Path()
                    visiblePred.forEachIndexed { i, p ->
                        if (i == 0) predPath.moveTo(x(p.tsMs), y(p.mmol))
                        else predPath.lineTo(x(p.tsMs), y(p.mmol))
                    }
                    drawPath(predPath, line, style = predStroke)
                    if (visibleCompare.size > 1) {
                        val comparePath = Path()
                        visibleCompare.forEachIndexed { i, p ->
                            if (i == 0) comparePath.moveTo(x(p.tsMs), y(p.mmol))
                            else comparePath.lineTo(x(p.tsMs), y(p.mmol))
                        }
                        drawPath(
                            comparePath,
                            mealColor.copy(alpha = .85f),
                            style =
                                Stroke(
                                    width = with(density) { 2.dp.toPx() },
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)),
                                )
                        )
                    }

                    // "Now" marker at the anchor.
                    val nx = x(visiblePred.first().tsMs)
                    drawLine(grid, Offset(nx, 0f), Offset(nx, h), strokeWidth = 2f)
                }
                if (visibleCompareWhatIf.size > 1) {
                    val p = Path()
                    visibleCompareWhatIf.forEachIndexed { i, point ->
                        if (i == 0) p.moveTo(x(point.tsMs), y(point.mmol))
                        else p.lineTo(x(point.tsMs), y(point.mmol))
                    }
                    drawPath(
                        p,
                        mealColor.copy(alpha = .55f),
                        style =
                            Stroke(
                                width = with(density) { 1.5.dp.toPx() },
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f))
                            )
                    )
                }

                // What-if overlay: the hypothetical-dose branch next to the real
                // forecast — same dash, food color, so "what will be" and "what
                // could be" read as siblings. Its own inner (p75/p25) band comes
                // along: a bare line promises more certainty than the model has.
                // Outer band omitted — two full cones on one chart is mud.
                val visibleWhatIf = whatIf.filter { it.tsMs in viewFrom..viewTo }
                if (visibleWhatIf.size > 1) {
                    val wiBand = Path()
                    visibleWhatIf.forEachIndexed { i, p ->
                        if (i == 0) wiBand.moveTo(x(p.tsMs), y(p.hiMid))
                        else wiBand.lineTo(x(p.tsMs), y(p.hiMid))
                    }
                    visibleWhatIf.reversed().forEach { p -> wiBand.lineTo(x(p.tsMs), y(p.loMid)) }
                    wiBand.close()
                    drawPath(wiBand, mealColor.copy(alpha = 0.10f))

                    val wiStroke =
                        Stroke(
                            width = with(density) { 2.dp.toPx() },
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                        )
                    val wiPath = Path()
                    visibleWhatIf.forEachIndexed { i, p ->
                        if (i == 0) wiPath.moveTo(x(p.tsMs), y(p.mmol))
                        else wiPath.lineTo(x(p.tsMs), y(p.mmol))
                    }
                    drawPath(wiPath, mealColor, style = wiStroke)
                }

                // Where each hypothetical dose turns on: a short vertical tick on the
                // what-if branch plus a 💉 glyph. This is what makes "dose it later"
                // and a second dose legible — the curve alone can't say WHEN the bend
                // was caused.
                if (visibleWhatIf.size > 1) {
                    val syrStyle = TextStyle(fontSize = 12.sp)
                    for (mark in whatIfDoseMarks) {
                        if (mark !in viewFrom..viewTo) continue
                        val near =
                            whatIf.minByOrNull { kotlin.math.abs(it.tsMs - mark) } ?: continue
                        val mx = x(mark)
                        val my = y(near.mmol)
                        val tick = with(density) { 10.dp.toPx() }
                        drawLine(
                            mealColor,
                            Offset(mx, my - tick),
                            Offset(mx, my + tick),
                            strokeWidth = with(density) { 1.5.dp.toPx() },
                        )
                        drawCircle(mealColor, with(density) { 2.5.dp.toPx() }, Offset(mx, my))
                        val layout = measurer.measure("💉", syrStyle)
                        drawText(
                            layout,
                            topLeft =
                                Offset(mx - layout.size.width / 2f, my - tick - layout.size.height),
                        )
                    }
                }

                // Mark by MEANING, not by data source. Food is food however it was
                // recorded: notes about food render as hollow food-colored rings ON
                // the curve at eating time ("awaiting the curve's confirmation");
                // once a detection exists nearby, the meal dot represents the event
                // and the note marker is skipped. Flags on top are reserved for
                // true context (sleep, injection site, dawn note…).
                val flagR = with(density) { 4.dp.toPx() }
                val ringStroke =
                    androidx.compose.ui.graphics.drawscope.Stroke(
                        width = with(density) { 2.dp.toPx() },
                    )
                fun isFoodContent(s: String): Boolean {
                    // Match on the HEAD before "·": "walk · 150 min" is an
                    // activity note with a duration suffix, not a dish.
                    val head = s.substringBefore('·').trim().lowercase()
                    return s.isNotBlank() &&
                        head !in com.diapilot.core.analysis.CONTEXT_TAGS &&
                        head !in com.diapilot.core.analysis.ACTIVITY_TAGS &&
                        s.lowercase() !in com.diapilot.core.analysis.SysLabels.ALL
                }
                // Only the note a detected meal actually MERGED with hides behind
                // the meal glyph. A second food note nearby (breakfast logged 30
                // min before the tea that got the detection) keeps its own mark —
                // it is real food that simply never produced its own rise.
                val anchorNoteIds =
                    meals
                        .mapNotNull { m ->
                            if (labelByOnset[m.onsetMs] in com.diapilot.core.analysis.SysLabels.ALL)
                                null
                            else
                                annotations
                                    .filter {
                                        isFoodContent(it.content) &&
                                            it.tsMs in
                                                (m.onsetMs - 90 * 60_000)..(m.onsetMs + 30 * 60_000)
                                    }
                                    .minByOrNull { kotlin.math.abs(it.tsMs - m.onsetMs) }
                                    ?.id
                        }
                        .toSet()
                for (a in annotations) {
                    if (a.tsMs !in viewFrom..viewTo) continue
                    val isFood = isFoodContent(a.content)
                    if (isFood) {
                        if (a.id in anchorNoteIds) continue // the meal glyph marks it
                        val bg = bgAt(a.tsMs) ?: continue
                        // Undetected food is still food — same plate glyph; the
                        // colored ring around it is what says "no detected rise".
                        val c = Offset(x(a.tsMs), y(bg))
                        val gStyle = TextStyle(fontSize = 13.sp)
                        // Rescue dextrose is not a dish — candy glyph, not a plate.
                        val glyph =
                            if (com.diapilot.core.analysis.isRescueNote(a.content)) "🍬" else "🍽"
                        val layout = measurer.measure(glyph, gStyle)
                        val r = with(density) { 6.dp.toPx() }
                        drawCircle(surface, r, c)
                        drawCircle(mealColor, r, c, style = ringStroke)
                        drawText(
                            measurer,
                            glyph,
                            Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f),
                            gStyle,
                        )
                    } else {
                        val ax = x(a.tsMs)
                        drawLine(textColor, Offset(ax, 0f), Offset(ax, flagR * 3), strokeWidth = 2f)
                        drawPath(
                            Path().apply {
                                moveTo(ax, 0f);
                                lineTo(ax + flagR * 2, flagR);
                                lineTo(ax, flagR * 2);
                                close()
                            },
                            textColor,
                        )
                    }
                }

                // Basal insulin: tall hollow bar at the baseline with a one-letter
                // "basal" label — clearly distinct from the bolus diamonds.
                val basalStyle = TextStyle(fontSize = 10.sp, color = textColor)
                for (b in basals) {
                    if (b.tsMs !in viewFrom..viewTo) continue
                    val bx = x(b.tsMs)
                    val barH = with(density) { 18.dp.toPx() }
                    drawLine(
                        line,
                        Offset(bx, h),
                        Offset(bx, h - barH),
                        strokeWidth = with(density) { 3.dp.toPx() }
                    )
                    val label =
                        context
                            .localized()
                            .getString(R.string.glucose_chart_basal_short_label, b.units)
                    val layout = measurer.measure(label, basalStyle)
                    drawText(
                        measurer,
                        label,
                        Offset(bx - layout.size.width / 2f, h - barH - layout.size.height - 2f),
                        basalStyle
                    )
                }

                // Meal dots sit at the EATING moment when a merged food note anchors
                // it (the detection onset is when BG reacted, not when food went in);
                // detector-only meals stay at their onset.
                val rOuter = with(density) { 7.dp.toPx() }
                for (m in meals) {
                    val label = labelByOnset[m.onsetMs]
                    val sys = label in com.diapilot.core.analysis.SysLabels.ALL
                    val note =
                        if (sys) null
                        else
                            annotations
                                .filter {
                                    isFoodContent(it.content) &&
                                        it.tsMs in
                                            (m.onsetMs - 90 * 60_000)..(m.onsetMs + 30 * 60_000)
                                }
                                .minByOrNull { kotlin.math.abs(it.tsMs - m.onsetMs) }
                    val ts = note?.tsMs ?: m.onsetMs
                    if (ts !in viewFrom..viewTo) continue
                    val bg = note?.let { bgAt(it.tsMs) } ?: m.preBg
                    val c = Offset(x(ts), y(bg))
                    // Every event draws as its glyph — food is a plate, not an
                    // anonymous dot; sys rises keep their own icons.
                    val isDex = com.diapilot.core.analysis.isRescueNote(label ?: note?.content)
                    val sysKey = com.diapilot.core.analysis.SysLabels.keyOf(label)
                    val glyph =
                        when {
                            isDex -> "🍬"
                            sysKey == com.diapilot.core.analysis.SysLabels.DAWN -> "🌅"
                            sysKey == com.diapilot.core.analysis.SysLabels.SPORT -> "💪"
                            sysKey == com.diapilot.core.analysis.SysLabels.UNKNOWN -> "❓"
                            sysKey == com.diapilot.core.analysis.SysLabels.CONTINUATION -> "↩"
                            else -> "🍽"
                        }
                    val gStyle = TextStyle(fontSize = 12.sp)
                    val layout = measurer.measure(glyph, gStyle)
                    drawCircle(surface, rOuter, c)
                    drawCircle(
                        if (sys) textColor.copy(alpha = 0.7f) else mealColor.copy(alpha = 0.85f),
                        rOuter,
                        c,
                        style = ringStroke,
                    )
                    drawText(
                        measurer,
                        glyph,
                        Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f),
                        gStyle,
                    )
                }

                // Meter checks: the fingerstick truth at its OWN value — a blood
                // drop the user can always find, shown even when the check was
                // too noisy (fast-changing sugar) to feed the calibration.
                for (mc in meterChecks) {
                    if (mc.tsMs !in viewFrom..viewTo) continue
                    val c = Offset(x(mc.tsMs), y(mc.mmol))
                    drawCircle(surface, with(density) { 8.dp.toPx() }, c)
                    val gStyle = TextStyle(fontSize = 12.sp)
                    val layout = measurer.measure("🩸", gStyle)
                    drawText(
                        measurer,
                        "🩸",
                        Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f),
                        gStyle,
                    )
                }

                // Selection marker: a frozen crosshair (same visual language as
                // the live scrubber) — hairline + soft glow, not a wide band.
                selection?.let { sel ->
                    if (sel.tsMs in viewFrom..viewTo) {
                        val sx = x(sel.tsMs)
                        val glowW = with(density) { 10.dp.toPx() }
                        drawRect(
                            color = line.copy(alpha = 0.06f),
                            topLeft = Offset(sx - glowW / 2, 0f),
                            size = androidx.compose.ui.geometry.Size(glowW, h),
                        )
                        drawLine(
                            line.copy(alpha = 0.8f),
                            Offset(sx, 0f),
                            Offset(sx, h),
                            strokeWidth = with(density) { 1.5.dp.toPx() },
                        )
                        // A tick at the top so the marker reads as "pinned".
                        drawPath(
                            Path().apply {
                                moveTo(sx - 8f, 0f);
                                lineTo(sx + 8f, 0f);
                                lineTo(sx, 12f);
                                close()
                            },
                            line.copy(alpha = 0.8f),
                        )
                    }
                }

                // History-jump halo: a fading glow column + ring around the
                // landed-on mark, so the eye finds it before the glow dies.
                highlightTs?.let { ts ->
                    val a = highlightAlpha.value
                    if (a > 0.01f && ts in viewFrom..viewTo) {
                        val hx = x(ts)
                        val glowW = with(density) { 26.dp.toPx() }
                        drawRect(
                            color = mealColor.copy(alpha = 0.18f * a),
                            topLeft = Offset(hx - glowW / 2, 0f),
                            size = androidx.compose.ui.geometry.Size(glowW, h),
                        )
                        bgAt(ts)?.let { bg ->
                            drawCircle(
                                mealColor.copy(alpha = 0.9f * a),
                                with(density) { 13.dp.toPx() },
                                Offset(hx, y(bg)),
                                style = ringStroke,
                            )
                        }
                    }
                }

                // Scrubber crosshair: vertical hairline + value bubble that follow
                // the finger during a long-press drag.
                scrubTs?.let { ts ->
                    if (ts in viewFrom..viewTo) {
                        val sx = x(ts)
                        drawLine(textColor, Offset(sx, 0f), Offset(sx, h), strokeWidth = 2f)
                        val near =
                            readings
                                .minByOrNull { kotlin.math.abs(it.tsMs - ts) }
                                ?.takeIf { kotlin.math.abs(it.tsMs - ts) < 15 * 60_000 }
                        val label =
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts)) +
                                (near?.let {
                                    " · " + com.diapilot.core.analysis.fmtBg(it.mmol, mgdl)
                                } ?: "")
                        val bubbleStyle = TextStyle(fontSize = 12.sp, color = textColor)
                        val layout = measurer.measure(label, bubbleStyle)
                        val bx =
                            (sx - layout.size.width / 2f).coerceIn(
                                padL,
                                padL + w - layout.size.width
                            )
                        val pad = with(density) { 4.dp.toPx() }
                        drawRect(
                            color = surface.copy(alpha = 0.92f),
                            topLeft = Offset(bx - pad, 0f),
                            size =
                                androidx.compose.ui.geometry.Size(
                                    layout.size.width + pad * 2,
                                    layout.size.height + pad,
                                ),
                        )
                        drawText(measurer, label, Offset(bx, pad / 2), bubbleStyle)
                        near?.let {
                            drawCircle(
                                line,
                                with(density) { 4.dp.toPx() },
                                Offset(x(it.tsMs), y(it.mmol))
                            )
                        }
                    }
                }
            }

            // Tap popup floats NEXT TO the tapped spot (tooltip, not a footer):
            // above the finger when there's room, below otherwise, clamped into
            // the chart. One row per element, inline actions where supported.
            selection?.let { sel ->
                var confirmNote by remember(sel) { mutableStateOf<Long?>(null) }
                var confirmBasal by remember(sel) { mutableStateOf<Long?>(null) }
                var purposeFor by remember(sel) { mutableStateOf<SelRef.Bolus?>(null) }
                var cardSize by
                    remember(sel) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                androidx.compose.material3.ElevatedCard(
                    onClick = { selection = null },
                    modifier =
                        Modifier.widthIn(max = 320.dp)
                            .onSizeChanged { cardSize = it }
                            // Invisible for the single frame before measurement — the
                            // offset needs the size to anchor correctly.
                            .alpha(
                                if (cardSize == androidx.compose.ui.unit.IntSize.Zero) 0f else 1f
                            )
                            .offset {
                                val boxW = (padLeftPx + widthPx).toInt()
                                val px =
                                    (selPos.x - cardSize.width / 2f)
                                        .toInt()
                                        .coerceIn(0, (boxW - cardSize.width).coerceAtLeast(0))
                                val above =
                                    selPos.y - cardSize.height - with(density) { 20.dp.toPx() }
                                val py =
                                    (if (above >= 0f) above
                                        else selPos.y + with(density) { 20.dp.toPx() })
                                        .toInt()
                                        .coerceIn(
                                            0,
                                            (heightPx.toInt() - cardSize.height).coerceAtLeast(0)
                                        )
                                androidx.compose.ui.unit.IntOffset(px, py)
                            },
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        // The corner ✕ CLOSES the popup — the instinct everyone
                        // has. Destructive actions are trash cans, never ✕.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                "✕",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier.pointerInput(Unit) {
                                            detectTapGestures { selection = null }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        sel.items.forEach { item ->
                            // Actionable rows LOOK actionable: a tinted pill with a
                            // chevron. Plain rows (forecast, analysis) stay flat
                            // text. Everything a row can do lives in its dialog —
                            // no in-row buttons to misfire on.
                            val onRowTap: (() -> Unit)? =
                                when (val r = item.ref) {
                                    is SelRef.Note -> ({
                                            selection = null;
                                            onOpenNote(r.id)
                                        })
                                    is SelRef.Bolus -> ({ purposeFor = r })
                                    is SelRef.Basal -> ({ confirmBasal = r.tsMs })
                                    null -> null
                                }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(vertical = 1.dp)
                                        .then(
                                            if (onRowTap == null) Modifier
                                            else
                                                Modifier.background(
                                                        MaterialTheme.colorScheme.onSurface.copy(
                                                            alpha = 0.06f
                                                        ),
                                                        androidx.compose.foundation.shape
                                                            .RoundedCornerShape(8.dp),
                                                    )
                                                    .pointerInput(item.ref) {
                                                        detectTapGestures { onRowTap() }
                                                    },
                                        )
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                if (onRowTap != null) {
                                    Text(
                                        "›",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            (item.ref as? SelRef.Note)?.let { note ->
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        selection = null;
                                        onExplainFood(note.id)
                                    }
                                ) {
                                    Text(stringResource(R.string.glucose_chart_explain_food))
                                }
                            }
                        }
                    }
                }
                purposeFor?.let { b ->
                    // Same modal as History: purpose (highlighted), editable dose,
                    // delete — one editor for the same entity everywhere.
                    var doseText by
                        remember(b.tsMs) {
                            mutableStateOf(
                                boluses
                                    .firstOrNull { it.tsMs == b.tsMs }
                                    ?.let { "%.1f".format(it.units) } ?: "",
                            )
                        }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { purposeFor = null },
                        title = { Text(stringResource(R.string.glucose_chart_bolus_dialog_title)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = doseText,
                                    onValueChange = { doseText = it },
                                    label = {
                                        Text(
                                            stringResource(R.string.glucose_chart_dose_units_label)
                                        )
                                    },
                                    singleLine = true,
                                )
                                Text(
                                    stringResource(R.string.glucose_chart_purpose_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                androidx.compose.foundation.layout.FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    com.diapilot.core.analysis.BolusPurpose.entries
                                        .map { it.key }
                                        .forEach { p ->
                                            val current =
                                                com.diapilot.core.analysis.BolusPurpose.canonical(
                                                    b.purpose
                                                )
                                            androidx.compose.material3.FilterChip(
                                                selected = current == p,
                                                onClick = {
                                                    onTagBolus(
                                                        b.tsMs,
                                                        if (current == p) null else p
                                                    )
                                                    purposeFor = null;
                                                    selection = null
                                                },
                                                label = {
                                                    Text(
                                                        io.github.obdosok.diapilot.i18n.TokenText
                                                            .bolusPurpose(context, p)!!
                                                    )
                                                },
                                            )
                                        }
                                }
                                val canDeconvolve =
                                    counterfactualKernels[b.tsMs].orEmpty().isNotEmpty() &&
                                        !com.diapilot.core.analysis.isPrimePurpose(
                                            boluses.firstOrNull { it.tsMs == b.tsMs }?.purpose
                                        )
                                androidx.compose.material3.TextButton(
                                    enabled = canDeconvolve,
                                    onClick = {
                                        hiddenBolusTs =
                                            if (hiddenBolusTs == b.tsMs) null else b.tsMs
                                        purposeFor = null;
                                        selection = null
                                    },
                                ) {
                                    Text(
                                        if (hiddenBolusTs == b.tsMs)
                                            stringResource(
                                                R.string.glucose_chart_restore_actual_line
                                            )
                                        else
                                            stringResource(
                                                R.string.glucose_chart_hide_bolus_conditionally
                                            ),
                                    )
                                }
                                if (!canDeconvolve)
                                    Text(
                                        stringResource(R.string.glucose_chart_no_personal_curve),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        purposeFor = null;
                                        selection = null
                                        onDeleteBolus(b.tsMs)
                                    }
                                ) {
                                    Text(
                                        stringResource(R.string.glucose_chart_delete_bolus),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    doseText
                                        .replace(',', '.')
                                        .toDoubleOrNull()
                                        ?.takeIf { it > 0 }
                                        ?.let {
                                            onEditBolusUnits(b.tsMs, it)
                                        }
                                    purposeFor = null;
                                    selection = null
                                }
                            ) {
                                Text(stringResource(R.string.glucose_chart_save))
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { purposeFor = null }) {
                                Text(stringResource(R.string.glucose_chart_cancel))
                            }
                        },
                    )
                }
                confirmNote?.let { id ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmNote = null },
                        title = { Text(stringResource(R.string.glucose_chart_delete_note_title)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    confirmNote = null;
                                    selection = null;
                                    onDeleteNote(id)
                                }
                            ) {
                                Text(
                                    stringResource(R.string.glucose_chart_delete_confirm),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { confirmNote = null }
                            ) {
                                Text(stringResource(R.string.glucose_chart_cancel))
                            }
                        },
                    )
                }
                confirmBasal?.let { ts ->
                    // Full basal editor — same as History: units + exact time,
                    // delete tucked inside, not the only option.
                    var bUnits by
                        remember(ts) {
                            mutableStateOf(
                                basals
                                    .firstOrNull { it.tsMs == ts }
                                    ?.let { "%.0f".format(it.units) } ?: "",
                            )
                        }
                    var bTs by remember(ts) { mutableStateOf(ts) }
                    val bFmt = remember {
                        java.text.SimpleDateFormat("HH:mm, d MMM", java.util.Locale.getDefault())
                    }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmBasal = null },
                        title = { Text(stringResource(R.string.glucose_chart_basal_dialog_title)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = bUnits,
                                    onValueChange = { bUnits = it },
                                    label = {
                                        Text(
                                            stringResource(R.string.glucose_chart_dose_units_label)
                                        )
                                    },
                                    singleLine = true,
                                )
                                Text(
                                    "🕐 ${bFmt.format(java.util.Date(bTs))} ✎",
                                    modifier =
                                        Modifier.pointerInput(Unit) {
                                            detectTapGestures {
                                                io.github.obdosok.diapilot.ui.pickDateTime(
                                                    context,
                                                    bTs
                                                ) {
                                                    bTs = it
                                                }
                                            }
                                        },
                                )
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        confirmBasal = null;
                                        selection = null;
                                        onDeleteBasal(ts)
                                    }
                                ) {
                                    Text(
                                        stringResource(R.string.glucose_chart_delete_basal),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    bUnits
                                        .replace(',', '.')
                                        .toDoubleOrNull()
                                        ?.takeIf { it > 0 }
                                        ?.let {
                                            onUpdateBasal(ts, bTs, it)
                                        }
                                    confirmBasal = null;
                                    selection = null
                                }
                            ) {
                                Text(stringResource(R.string.glucose_chart_save))
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { confirmBasal = null }
                            ) {
                                Text(stringResource(R.string.glucose_chart_cancel))
                            }
                        },
                    )
                }
            }
        }

        // Minimap: the full range as one compressed curve with the viewport
        // window — drag to pan, tap to jump (the xDrip pattern).
        Canvas(
            modifier =
                Modifier.fillMaxWidth()
                    .height(44.dp)
                    .padding(top = 4.dp)
                    // Unit keys: the 5-second data reload changes fromMs/toMs,
                    // which would otherwise tear down an in-progress gesture and
                    // snap the viewport back. Read the live values instead.
                    .pointerInput(Unit) {
                        detectTapGestures { off ->
                            val ts =
                                fromMsLive + (off.x / size.width * (toMsLive - fromMsLive)).toLong()
                            val newTo =
                                (ts + spanMs / 2).coerceIn(
                                    minOf(fromMsLive + spanMs, toMsLive),
                                    toMsLive
                                )
                            anchorTo = if (newTo >= toMsLive) null else newTo
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val dt = (drag.x / size.width * (toMsLive - fromMsLive)).toLong()
                            val newTo =
                                (liveViewTo() + dt).coerceIn(
                                    minOf(fromMsLive + spanMs, toMsLive),
                                    toMsLive
                                )
                            anchorTo = if (newTo >= toMsLive) null else newTo
                        }
                    },
        ) {
            val mw = size.width
            val mh = size.height
            val all = readings
            if (all.isEmpty()) return@Canvas
            val levels = all.map { it.mmol }.sorted()
            val p05 = levels[(levels.lastIndex * 0.05).toInt()]
            val p95 = levels[(levels.lastIndex * 0.95).toInt()]
            val center = (p05 + p95) / 2.0
            val span = maxOf(4.0, p95 - p05 + 1.5)
            val minBg = maxOf(2.0, center - span / 2.0)
            val maxBg = minBg + span
            fun mx(ts: Long) = mw * (ts - fromMs).toFloat() / (toMs - fromMs).toFloat()
            fun my(bg: Double) = (mh * (1 - (bg - minBg) / (maxBg - minBg))).toFloat()

            drawRoundRect(
                color = textColor.copy(alpha = 0.045f),
                size = androidx.compose.ui.geometry.Size(mw, mh),
                cornerRadius =
                    androidx.compose.ui.geometry.CornerRadius(with(density) { 8.dp.toPx() }),
            )
            // Day boundaries: a faint vertical at each local midnight, so the
            // 3-day overview reads as days, not one long smear.
            run {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = fromMs
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                if (cal.timeInMillis < fromMs) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                val dashed =
                    androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(4f, 4f),
                        0f,
                    )
                while (cal.timeInMillis <= toMs) {
                    val x = mx(cal.timeInMillis)
                    drawLine(
                        textColor.copy(alpha = 0.55f),
                        Offset(x, 0f),
                        Offset(x, mh),
                        strokeWidth = with(density) { 1.2.dp.toPx() },
                        pathEffect = dashed,
                    )
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }

            val mPath = Path()
            var mPrev: GlucosePoint? = null
            // Downsample: one point per minimap pixel-ish is plenty.
            val step = ((toMs - fromMs) / mw.toInt().coerceAtLeast(1)).coerceAtLeast(60_000L)
            var nextTs = 0L
            for (p in all.sortedBy { it.tsMs }) {
                if (p.tsMs < nextTs) continue
                nextTs = p.tsMs + step
                if (mPrev == null || p.tsMs - mPrev.tsMs > GAP_MS + step) {
                    mPath.moveTo(mx(p.tsMs), my(p.mmol))
                } else {
                    mPath.lineTo(mx(p.tsMs), my(p.mmol))
                }
                mPrev = p
            }
            drawPath(
                mPath,
                line.copy(alpha = 0.82f),
                style =
                    Stroke(
                        width = with(density) { 1.8.dp.toPx() },
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
            )

            // Viewport window.
            val wx0 = mx(viewFrom)
            val wx1 = mx(viewTo)
            drawRoundRect(
                line.copy(alpha = 0.11f),
                Offset(wx0, 0f),
                androidx.compose.ui.geometry.Size((wx1 - wx0).coerceAtLeast(2f), mh),
                cornerRadius =
                    androidx.compose.ui.geometry.CornerRadius(with(density) { 6.dp.toPx() }),
            )
            drawLine(
                line.copy(alpha = 0.9f),
                Offset(wx0, 3f),
                Offset(wx0, mh - 3f),
                strokeWidth = 2f
            )
            drawLine(
                line.copy(alpha = 0.9f),
                Offset(wx1, 3f),
                Offset(wx1, mh - 3f),
                strokeWidth = 2f
            )
        }
        // No legend: the colors are learnable, and both the scrubber and the
        // tap popups name what's under the finger.
    }
}

/** What a popup row refers to, so it can carry inline actions. */
sealed class SelRef {
    data class Bolus(val tsMs: Long, val purpose: String?) : SelRef()
    data class Note(val id: Long) : SelRef()
    data class Basal(val tsMs: Long) : SelRef()
}

data class SelLine(val tsMs: Long, val text: String, val ref: SelRef? = null)

data class ChartSelection(val tsMs: Long, val items: List<SelLine>)

/**
 * Everything around the tapped moment (±~15 min at the current zoom):
 * annotations, meals, boluses, basal and the nearest curve value — one line
 * each, so vertically stacked elements all show up together.
 */
private fun selectTapped(
    context: android.content.Context,
    off: Offset,
    padL: Float,
    w: Float,
    viewFrom: Long,
    viewTo: Long,
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    meals: List<MealEvent>,
    annotations: List<Annotation>,
    basals: List<BolusPoint>,
    labelByOnset: Map<Long, String> = emptyMap(),
    mgdl: Boolean = false,
): ChartSelection? {
    if (off.x < padL) return null
    val span = (viewTo - viewFrom).toFloat()
    val tapTs = viewFrom + ((off.x - padL) / w * span).toLong()
    val thresholdMs = (span * 24f / w).toLong().coerceAtLeast(5 * 60_000L)
    val range = (tapTs - thresholdMs)..(tapTs + thresholdMs)
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val text = context.localized()

    val lines = mutableListOf<SelLine>()
    annotations.filter { it.tsMs in range }.forEach {
        lines.add(
            SelLine(
                it.tsMs,
                "🏷 ${fmt.format(Date(it.tsMs))} · ${io.github.obdosok.diapilot.i18n.TokenText.noteTag(context, it.content)}" +
                    (it.analysis?.let { a -> " — ${a.lineSequence().firstOrNull().orEmpty()}" } ?: ""),
                SelRef.Note(it.id),
            ),
        )
    }
    val includedMealOnsets = mutableListOf<Long>()
    // Meals match at their DISPLAY position (eating time when a food note
    // anchors it) — the tap target must sit where the dot is drawn.
    fun isFood(s: String): Boolean =
        s.isNotBlank() &&
            !com.diapilot.core.analysis.isContextNote(s) &&
            s.lowercase() !in com.diapilot.core.analysis.SysLabels.ALL
    meals.forEach { m ->
        val label = labelByOnset[m.onsetMs]
        val sys = label in com.diapilot.core.analysis.SysLabels.ALL
        val note = if (sys) null else annotations.filter {
            isFood(it.content) &&
                it.tsMs in (m.onsetMs - 90 * 60_000)..(m.onsetMs + 30 * 60_000)
        }.minByOrNull { kotlin.math.abs(it.tsMs - m.onsetMs) }
        val displayTs = note?.tsMs ?: m.onsetMs
        if (displayTs !in range) return@forEach
        val emoji = when (com.diapilot.core.analysis.SysLabels.keyOf(label)) {
            com.diapilot.core.analysis.SysLabels.DAWN -> "🌅"
            com.diapilot.core.analysis.SysLabels.SPORT -> "💪"
            com.diapilot.core.analysis.SysLabels.UNKNOWN -> "❓"
            else -> "🍽"
        }
        val timesPart = if (note != null && m.onsetMs - note.tsMs > 3 * 60_000) {
            text.getString(
                R.string.glucose_chart_sel_meal_ate_rise,
                emoji, fmt.format(Date(note.tsMs)), fmt.format(Date(m.onsetMs)),
            )
        } else if (sys) {
            "$emoji ${fmt.format(Date(displayTs))} · ${io.github.obdosok.diapilot.i18n.FoodText.mealLabel(context, label!!)}"
        } else {
            "$emoji ${fmt.format(Date(displayTs))}"
        }
        // The food row is PURE food (what it did to BG); its bolus always
        // gets its own row below — one consistent shape, with actions.
        lines.add(
            SelLine(
                displayTs,
                timesPart +
                    " · " + text.getString(
                        R.string.glucose_chart_sel_meal_bg_change,
                        com.diapilot.core.analysis.fmtBg(m.preBg, mgdl),
                        com.diapilot.core.analysis.fmtBg(m.peakBg, mgdl),
                        m.timeToPeakMin,
                    ) +
                    (if (m.bolusUnits == null) " · " + text.getString(R.string.glucose_chart_sel_no_bolus) else ""),
            ),
        )
        includedMealOnsets.add(m.onsetMs)
    }
    // Boluses: tapped directly, or PAIRED with an included meal — the shot
    // that covered the food belongs in the same popup even when the tap
    // landed on the plate, not the diamond.
    val shownBoluses = mutableSetOf<Long>()
    fun addBolusRow(b: BolusPoint) {
        if (!shownBoluses.add(b.tsMs)) return
        lines.add(
            SelLine(
                b.tsMs,
                text.getString(R.string.glucose_chart_sel_bolus_row, fmt.format(Date(b.tsMs)), b.units) +
                    (b.purpose?.let { p -> " · ${io.github.obdosok.diapilot.i18n.TokenText.bolusPurpose(context, p)}" } ?: ""),
                SelRef.Bolus(b.tsMs, b.purpose),
            ),
        )
    }
    boluses.filter { it.tsMs in range }.forEach(::addBolusRow)
    includedMealOnsets.forEach { onset ->
        boluses.filter { it.tsMs in (onset - 45L * 60_000)..(onset + 20L * 60_000) }
            .forEach(::addBolusRow)
    }
    basals.filter { it.tsMs in range }.forEach {
        lines.add(
            SelLine(
                it.tsMs,
                text.getString(R.string.glucose_chart_sel_basal_row, fmt.format(Date(it.tsMs)), it.units),
                SelRef.Basal(it.tsMs),
            ),
        )
    }

    if (lines.isEmpty()) {
        // Fallback: the curve itself.
        val nearest = readings.filter { it.tsMs in viewFrom..viewTo }
            .minByOrNull { kotlin.math.abs(it.tsMs - tapTs) }
            ?.takeIf { kotlin.math.abs(it.tsMs - tapTs) <= thresholdMs }
            ?: return null
        return ChartSelection(
            nearest.tsMs,
            listOf(
                SelLine(
                    nearest.tsMs,
                    "${fmt.format(Date(nearest.tsMs))} · " +
                        "${com.diapilot.core.analysis.fmtBg(nearest.mmol, mgdl)} " +
                        io.github.obdosok.diapilot.i18n.unitLabel(mgdl),
                ),
            ),
        )
    }
    val sorted = lines.sortedBy { it.tsMs }
    return ChartSelection(sorted.first().tsMs, sorted)
}


/** Material date picker for jumping the chart to a past day. Future dates are
 *  disabled; the chart then shows the 3-day window ending that evening. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
internal fun HistoryDatePicker(
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = now,
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= now
        },
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onPick(state.selectedDateMillis) }) {
                Text(stringResource(R.string.glucose_chart_show))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.glucose_chart_cancel)) }
        },
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
}
