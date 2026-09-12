package io.github.obdosok.diapilot

/**
 * The Today tab: hero number, status lines, deviation card, chart.
 */

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.LabeledMeal
import com.diapilot.core.collector.MealEvent
import com.diapilot.core.collector.Reading
import com.diapilot.core.collector.relabelMeal
import com.diapilot.core.collector.scanMeals
import io.github.obdosok.diapilot.collect.CollectorService
import io.github.obdosok.diapilot.collect.MealNotifier
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker
import io.github.obdosok.diapilot.data.DishDialogRuntime
import io.github.obdosok.diapilot.data.FoodCalculationRegistry
import io.github.obdosok.diapilot.data.FoodCalculationV1
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.i18n.FoodText
import io.github.obdosok.diapilot.i18n.TokenText
import io.github.obdosok.diapilot.i18n.TwinText
import io.github.obdosok.diapilot.i18n.unitLabel
import io.github.obdosok.diapilot.ui.AnalysisScreen
import io.github.obdosok.diapilot.ui.AnnotationEditor
import io.github.obdosok.diapilot.ui.GlucoseChart
import io.github.obdosok.diapilot.ui.theme.DiaPilotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------- Today tab

private data class WhatIfTrajectorySummary(
    val atHour: String,
    val minimum: String,
    val atThreeHours: String,
    val risk: String?,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun TodayScreen(
    state: UiState,
    onAddNote: (Long, String, String?, String, Double?) -> Unit = { _, _, _, _, _ -> },
    onAddFoodWithAnalysis: (Long, String, String?, Double?, String) -> Unit = { _, _, _, _, _ -> },
    onQuickDextrose: () -> Unit = {},
    onConnectHc: () -> Unit = {},
    onOpenLabel: () -> Unit = {},
    onAddBasal: (Long, Double) -> Unit = { _, _ -> },
    onTagBolus: (Long, String?) -> Unit = { _, _ -> },
    onEditBolusUnits: (Long, Double) -> Unit = { _, _ -> },
    onDeleteBolus: (Long) -> Unit = {},
    onUpdateBasal: (Long, Long, Double) -> Unit = { _, _, _ -> },
    onDeleteNoteById: (Long) -> Unit = {},
    onDeleteBasal: (Long) -> Unit = {},
    onUpdateNote: (com.diapilot.core.collector.Annotation, Long, String, String?) -> Unit = { _, _, _, _ -> },
    onDeleteNote: (com.diapilot.core.collector.Annotation) -> Unit = {},
    focusTs: Long? = null,
    onFocusHandled: () -> Unit = {},
    onInspectForecast: (Long) -> Unit = {},
    inspectStored: List<com.diapilot.core.twin.PredictedPoint> = emptyList(),
    inspectReplay: List<com.diapilot.core.twin.PredictedPoint> = emptyList(),
    onHistoryNav: (Long?) -> Unit = {},
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = LocalAppGraph.current
    val now = System.currentTimeMillis()
    // What-if simulator: superpose a hypothetical bolus/meal onto the live
    // prediction. A research lens, NOT a bolus calculator — the app never
    // solves for a dose, the user drags and reads. Hoisted here because the
    // overlay is drawn by the chart further down the layout.
    var showFoodSuggest by remember { mutableStateOf(false) }
    var foodCalculation by remember { mutableStateOf<FoodCalculationV1?>(null) }
    // The arrow is derived from the SHOWN delta, with no hysteresis of its own.
    //
    // There used to be a two-read confirm here to stop the glyph twitching, but
    // it required the SAME new name twice in a row: a delta hovering on a
    // threshold (−2 / −5 mg/dl straddles the Flat↔FortyFiveDown edge) alternates
    // the raw name, so the confirm never landed and the arrow STUCK on a stale
    // ↗ next to a negative delta for many minutes.
    //
    // It is also obsolete: it was added when the arrow rode the noisy 1-min
    // stream. Since 64c6b59 the delta comes from the frozen 5-min grid (moves
    // once per 5 min; the fallback is a smoothed ~15-min fit), so there is
    // nothing left to twitch. Deriving the glyph straight from the delta also
    // restores the invariant trendName() documents — header, widget, watch and
    // notification must never disagree — which only this hysteresis broke.
    val trendArrowName = com.diapilot.core.trendName(state.deltaMmol)
    // Dose 1 is always present; dose 2 is revealed on demand — it models a
    // common two-step play ("inject a bit less now, add more later").
    // Each dose carries its own forward OFFSET (minutes past now), so
    // "just inject later" is dose 1 with a non-zero offset. The kernel is an
    // impulse response, so a delayed dose is just its curve shifted — the math
    // lives in core/whatif, pinned by arithmetic tests.
    var wiUnits by remember { mutableStateOf(0f) }
    var wiOffset by remember { mutableStateOf(0f) }
    var wiSecond by remember { mutableStateOf(false) }
    var wiUnits2 by remember { mutableStateOf(0f) }
    var wiOffset2 by remember { mutableStateOf(0f) }
    var wiActivity by remember { mutableStateOf(false) }
    var wiActivityOffset by remember { mutableStateOf(0f) }
    var wiActivityDuration by remember { mutableStateOf(60f) }
    var wiActivityIntensity by remember { mutableStateOf(1f) }
    // Diagnostic lens: available even when the model is LIMITED so its error
    // can be watched as new data arrive. Trust changes the warning, not access.
    val insulinForecastTrusted =
        state.forecastHealth == com.diapilot.core.twin.ForecastHealth.TRUSTED
    val hasWhatIfEngine =
        (state.selectedArmWhatIf && state.hybridWhatIfProfile != null) ||
            state.twinKernel.isNotEmpty()
    val activityWhatIfAvailable =
        state.selectedArmWhatIf && state.hybridWhatIfProfile != null
    val whatIfDoses = remember(wiUnits, wiOffset, wiSecond, wiUnits2, wiOffset2) {
        buildList {
            if (wiUnits > 0f) add(com.diapilot.core.whatif.WhatIfDose(wiUnits.toDouble(), wiOffset.toDouble()))
            if (wiSecond && wiUnits2 > 0f) {
                add(com.diapilot.core.whatif.WhatIfDose(wiUnits2.toDouble(), wiOffset2.toDouble()))
            }
        }
    }
    val showWhatIf = whatIfDoses.isNotEmpty() || (wiActivity && activityWhatIfAvailable)
    val whatIfPred = remember(
        state.prediction,
        whatIfDoses,
        showWhatIf,
        state.selectedArmWhatIf,
        state.hybridWhatIfProfile,
        state.twinKernel,
        state.forecastKernelScale,
        hasWhatIfEngine,
        wiActivity,
        wiActivityOffset,
        wiActivityDuration,
        wiActivityIntensity,
    ) {
        if (!showWhatIf || state.prediction.isEmpty() ||
            !hasWhatIfEngine
        ) {
            emptyList()
        } else {
            val anchor = state.prediction.first().tsMs
            state.prediction.map { p ->
                val tau = (p.tsMs - anchor) / 60_000.0
                // × the live forecast's autosens so "What if" and the real
                // forecast agree on today's insulin sensitivity, not just the
                // time-of-day kernel. Superposition of all active doses.
                val hybrid = state.hybridWhatIfProfile
                    .takeIf { state.selectedArmWhatIf }
                val dIns = if (hybrid != null) {
                    whatIfDoses.sumOf {
                        hybrid.insulinDelta(
                            tau, it.units, it.offsetMin,
                            activityOffsetMin = wiActivityOffset.toDouble().takeIf { wiActivity },
                            activityDurationMin = wiActivityDuration.toDouble(),
                            activityIntensity = wiActivityIntensity.toDouble(),
                        )
                    }
                } else {
                    com.diapilot.core.whatif.insulinDeltaAt(
                        state.twinKernel, tau, whatIfDoses, state.forecastKernelScale,
                    )
                }
                val insulinBandExtra = if (hybrid != null) {
                    whatIfDoses.sumOf {
                        hybrid.insulinUncertainty(
                            tau, it.units, it.offsetMin,
                            activityOffsetMin = wiActivityOffset.toDouble().takeIf { wiActivity },
                            activityDurationMin = wiActivityDuration.toDouble(),
                            activityIntensity = wiActivityIntensity.toDouble(),
                        )
                    }
                } else 0.0
                val dActivity = if (hybrid != null && wiActivity) {
                    hybrid.activityDelta(
                        tau,
                        wiActivityOffset.toDouble(),
                        wiActivityDuration.toDouble(),
                        wiActivityIntensity.toDouble(),
                    )
                } else 0.0
                val d = dIns + dActivity
                com.diapilot.core.twin.PredictedPoint(
                    p.tsMs, p.mmol + d,
                    p.lo + d - insulinBandExtra,
                    p.hi + d + insulinBandExtra,
                    loMid = p.loMid + d - insulinBandExtra * 0.5,
                    hiMid = p.hiMid + d + insulinBandExtra * 0.5,
                )
            }
        }
    }
    // Fire-time markers for the chart: where each active dose turns on. Offset 0
    // coincides with the "now" line but the injection glyph still reads clearly.
    val whatIfDoseMarks = remember(state.prediction, whatIfDoses, showWhatIf) {
        if (!showWhatIf || state.prediction.isEmpty()) emptyList()
        else {
            val anchor = state.prediction.first().tsMs
            whatIfDoses.map { anchor + (it.offsetMin * 60_000L).toLong() }
        }
    }
    // Chart popup → full note editor, right here in a dialog.
    var editNoteId by remember { mutableStateOf<Long?>(null) }
    editNoteId?.let { id ->
        val note = (state.annotations + state.historyNotes).firstOrNull { it.id == id }
        if (note == null) {
            editNoteId = null
        } else {
            androidx.compose.ui.window.Dialog(onDismissRequest = { editNoteId = null }) {
                AnnotationEditor(
                    annotation = note,
                    onSave = { ts, t, m -> editNoteId = null; onUpdateNote(note, ts, t, m) },
                    onDelete = { editNoteId = null; onDeleteNote(note) },
                    onCancel = { editNoteId = null },
                    foodLabels = state.foodLabels,
                    carbsByFood = state.carbsByFood,
                    recentFoodTexts = state.recentFoodTexts,
                    foodMemories = state.foodMemory,
                    recentFoodCarbs = state.recentFoodCarbs,
                )
            }
        }
    }
    // RECORDING THE "What if" INTENT WAS REMOVED.
    //
    // This used to freeze the curve the user was looking at, to later compare
    // it against the actual bolus given. The mechanism was clean — the slider
    // itself never became physiological evidence — but the result had no
    // reader at all: `quality`, `decisionSummary` and
    // `prospectiveInsulinProfile` were called from nowhere. Seven tables
    // accumulated an answer to a question nobody was asking.
    val comparisonWhatIfPred = remember(state.comparisonPrediction, state.comparisonWhatIfProfile, whatIfDoses, showWhatIf, wiActivity, wiActivityOffset, wiActivityDuration, wiActivityIntensity) {
        val profile = state.comparisonWhatIfProfile
        if (!showWhatIf || profile == null || state.comparisonPrediction.isEmpty()) emptyList() else {
            val anchor = state.comparisonPrediction.first().tsMs
            state.comparisonPrediction.map { p ->
                val tau = (p.tsMs - anchor) / 60_000.0
                val dIns = whatIfDoses.sumOf { profile.insulinDelta(tau, it.units, it.offsetMin,
                    activityOffsetMin = wiActivityOffset.toDouble().takeIf { wiActivity },
                    activityDurationMin = wiActivityDuration.toDouble(), activityIntensity = wiActivityIntensity.toDouble()) }
                val dActivity = if (wiActivity) profile.activityDelta(tau, wiActivityOffset.toDouble(), wiActivityDuration.toDouble(), wiActivityIntensity.toDouble()) else 0.0
                val d = dIns + dActivity
                p.copy(mmol = p.mmol + d, lo = p.lo + d, hi = p.hi + d, loMid = p.loMid + d, hiMid = p.hiMid + d)
            }
        }
    }
    val whatIfActivityWindow = remember(
        state.prediction, wiActivity, wiActivityOffset, wiActivityDuration,
    ) {
        if (!wiActivity || state.prediction.isEmpty()) null else {
            val anchor = state.prediction.first().tsMs
            val start = anchor + (wiActivityOffset * 60_000L).toLong()
            start to (start + (wiActivityDuration * 60_000L).toLong())
        }
    }
    // The chart is glued to the bottom (right above the nav bar) and absorbs
    // whatever height the info block leaves it: the deviation card or the
    // hypo button shrink the chart instead of pushing the screen into a
    // scroll. The top block is measured for real (onSizeChanged), so the fit
    // is exact on any density. The column stays scrollable only as the
    // pull-to-refresh anchor — its content never exceeds the viewport.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val boxHpx = with(density) { maxHeight.toPx() }
        var topPx by remember { mutableStateOf(-1) }
        var whatIfPx by remember { mutableStateOf(0) }
        LaunchedEffect(state.prediction.isNotEmpty(), hasWhatIfEngine) {
            if (state.prediction.isEmpty() || !hasWhatIfEngine) whatIfPx = 0
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.onSizeChanged { topPx = it.height },
        ) {
        state.lastReading?.let { r ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    // Arrow and delta come from the SAME number, so the header
                    // can never contradict itself (↗ next to −5 mg/dl).
                    com.diapilot.core.analysis.fmtBg(r.mmol, state.mgdl) +
                        com.diapilot.core.trendGlyph(trendArrowName).let { if (it.isEmpty()) "" else " $it" },
                    style = MaterialTheme.typography.displaySmall,
                )
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(
                        buildString {
                            state.deltaMmol?.let {
                                append(com.diapilot.core.analysis.fmtBgDelta(it, state.mgdl))
                            }
                            append("  ${unitLabel(state.mgdl)}")
                            val ageMin = (now - r.tsMs) / 60_000
                            if (ageMin >= com.diapilot.core.PersonalParams.DEFAULT.ageNoiseMin) {
                                append(" · ")
                                append(stringResource(R.string.today_screen_reading_age, ageMin))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Acceleration nuance — what the arrow alone can't tell.
                    state.trendNuance?.let {
                        Text(
                            TwinText.nuance(
                                androidx.compose.ui.platform.LocalContext.current, it,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
        // The always-current narrative: state now + where the model points.
        // Replaces the old raw insulin line — same facts, in words.
        // The ISF the line above was drawn with. Small, one line, and next to
        // the forecast rather than three screens away in Settings — because the
        // applied value is not the written one (ramped by independent days,
        // decayed, possibly overridden, possibly re-based by hour) and none of
        // that was visible anywhere.
        state.appliedIsf?.let { (isf, src) ->
            Text(
                stringResource(R.string.today_screen_isf_now, isf, src),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.statusLines.forEachIndexed { i, line ->
            Text(
                line,
                style = if (i == 0) MaterialTheme.typography.bodyMedium
                else MaterialTheme.typography.bodySmall,
                color = if (i == 0) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.correctionCandidate?.let { b ->
            androidx.compose.material3.AssistChip(
                onClick = { onTagBolus(b.tsMs, com.diapilot.core.analysis.BolusPurpose.CORRECTION.key) },
                label = {
                    Text(stringResource(R.string.today_screen_confirm_correction, b.units))
                },
            )
            Text(
                stringResource(R.string.today_screen_correction_candidate_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // F-05: pending dish-recognition moves — the composer's questions as
        // turns of a conversation. The note is ALREADY SAVED; "yes" appends the
        // dish's accepted structure as a dated revision and teaches the
        // wording as an alias, "no" silences this wording for this dish.
        run {
            DishDialogRuntime.load(context)
            DishDialogRuntime.moves.toList().forEach { move ->
                androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(10.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                    ) {
                        if (move.kind == DishDialogRuntime.KIND_ASSUMPTION) {
                            Text(
                                stringResource(
                                    R.string.today_screen_dish_assumption_head,
                                    move.noteContent, move.question,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (move.dishId.isNotBlank()) {
                                    stringResource(
                                        R.string.today_screen_dish_assumption_note_with_id, move.dishId,
                                    )
                                } else {
                                    stringResource(R.string.today_screen_dish_assumption_note)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            var answer by remember(move.tsMs) { mutableStateOf("") }
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                            ) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = answer, onValueChange = { answer = it },
                                    modifier = Modifier.weight(1f), singleLine = true,
                                    placeholder = { Text(stringResource(R.string.today_screen_dish_assumption_placeholder)) },
                                )
                                androidx.compose.material3.FilledTonalButton(
                                    enabled = answer.isNotBlank(),
                                    onClick = {
                                        DishDialogRuntime.answerAssumption(
                                            context, graph.store, move, answer,
                                        )
                                        onRefresh()
                                    },
                                ) { Text(stringResource(R.string.today_screen_dish_assumption_save)) }
                                androidx.compose.material3.TextButton(onClick = {
                                    DishDialogRuntime.dismiss(context, move)
                                }) { Text("✕") }
                            }
                        } else {
                            Text(
                                stringResource(
                                    R.string.today_screen_dish_confirm_head,
                                    move.noteContent, move.question,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.today_screen_dish_confirm_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                            ) {
                                androidx.compose.material3.FilledTonalButton(onClick = {
                                    DishDialogRuntime.confirmDish(
                                        context, graph.store, move,
                                    )
                                    onRefresh()
                                }) { Text(stringResource(R.string.today_screen_dish_confirm_yes)) }
                                androidx.compose.material3.TextButton(onClick = {
                                    DishDialogRuntime.rejectDish(context, move)
                                }) { Text(stringResource(R.string.today_screen_dish_confirm_no)) }
                                androidx.compose.material3.TextButton(onClick = {
                                    DishDialogRuntime.dismiss(context, move)
                                }) { Text("✕") }
                            }
                        }
                    }
                }
            }
        }
        // Unlogged-meal suggestions — deliberately just a COUNTER here (the
        // user's call: quick-log hints must not crowd the screen). Everything
        // actionable lives in the dialog: accept / edit / decline.
        if (state.foodSuggestions.isNotEmpty()) {
            Text(
                stringResource(R.string.today_screen_food_suggestions_badge, state.foodSuggestions.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showFoodSuggest = true },
            )
        }
        if (showFoodSuggest && state.foodSuggestions.isNotEmpty()) {
            FoodSuggestDialog(
                suggestions = state.foodSuggestions,
                onAccept = { s, name, grams ->
                    // A repeat dish is repeat composition: reuse the last
                    // canonical composition (with ITS carbs — the two must agree)
                    // so accepting a suggestion doesn't leave the split to be
                    // retyped in the editor.
                    val recall = com.diapilot.core.analysis.recallComposition(name, state.compositionByFood)
                    if (recall != null) {
                        onAddFoodWithAnalysis(s.triggerTsMs, recall.dish, null, recall.grams ?: grams, recall.analysis)
                    } else {
                        onAddNote(s.triggerTsMs, name, null, "food", grams)
                    }
                    Settings.dismissFoodSuggestion(context, s.triggerTsMs)
                    onRefresh()
                },
                onDecline = { s ->
                    Settings.dismissFoodSuggestion(context, s.triggerTsMs)
                    onRefresh()
                },
                onDismiss = { showFoodSuggest = false },
            )
        }
        // Model details, collapsed by default — the sensitivity dial, the
        // hour-ago forecast receipt and the active meter lens are useful but
        // secondary; the header stays about NOW + where it's heading.
        val details = listOfNotNull(state.forecastCheck, state.meterCalLine)
        state.calibrationWindow?.let { cw ->
            val need = when (cw.need) {
                com.diapilot.core.analysis.CalibrationNeed.ISF ->
                    stringResource(R.string.today_screen_calibration_need_isf)
                com.diapilot.core.analysis.CalibrationNeed.CARB_RATIO ->
                    stringResource(R.string.today_screen_calibration_need_carb_ratio)
                com.diapilot.core.analysis.CalibrationNeed.BOTH ->
                    stringResource(R.string.today_screen_calibration_need_both)
            }
            val correctionLabel = TokenText.bolusPurpose(context, com.diapilot.core.analysis.BolusPurpose.CORRECTION)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(R.string.today_screen_calibration_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    // Never a prompt to dose or eat — only to LOG cleanly IF
                    // the user acts on their own (the app's hard rule).
                    Text(
                        stringResource(R.string.today_screen_calibration_hint, correctionLabel, need),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        // Context + 24h summary on ONE compact line — every saved header line
        // is chart pixels.
        val bodyLine = buildList {
            state.dayStats?.let { s ->
                add("TIR ${s.tirPct}%")
                add(stringResource(R.string.today_screen_mean_bg, com.diapilot.core.analysis.fmtBg(s.meanMmol, state.mgdl)))
                add(stringResource(R.string.today_screen_insulin_carbs_summary, s.insulinUnits, s.carbsG))
            }
        }
        if (bodyLine.isNotEmpty()) {
            Text(
                bodyLine.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.dayStats?.takeIf { it.nutritionMeals > 0 }?.let { s ->
            Text(
                buildList {
                    s.kcal?.let { add(stringResource(R.string.today_screen_kcal, it)) }
                    s.proteinG?.let { add(stringResource(R.string.today_screen_protein, it)) }
                    s.fatG?.let { add(stringResource(R.string.today_screen_fat, it)) }
                    add(
                        pluralStringResource(
                            R.plurals.today_screen_nutrition_meals, s.nutritionMeals, s.nutritionMeals,
                        ),
                    )
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.readings == 0L) {
            Text(
                stringResource(R.string.today_screen_no_data_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Basal reminder: the usual hour arrived and nothing is logged yet —
        // one tap records the usual dose (editable in history afterwards).
        state.basalDue?.let { (units, usualTime) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.today_screen_basal_due, usualTime),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { onAddBasal(now, units) },
                    ) { Text(stringResource(R.string.today_screen_log_basal_button, units)) }
                }
            }
        }

        // One-tap dextrose while low or falling fast: the hands are shaky,
        // the entry must be a single thumb press. Repeat taps grow the count.
        if (state.hypoQuick) {
            androidx.compose.material3.Button(
                onClick = onQuickDextrose,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(
                    if (state.hypoTablets == 0) {
                        stringResource(R.string.today_screen_dextrose_first, DEXTROSE_TABLET_G)
                    } else {
                        stringResource(
                            R.string.today_screen_dextrose_more,
                            state.hypoTablets, state.hypoTablets * DEXTROSE_TABLET_G,
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        if (state.hcGranted == false) {
            TextButton(onClick = onConnectHc) { Text(stringResource(R.string.today_screen_connect_health_connect)) }
        } else if (state.stepsGranted == false) {
            // HR/sleep granted long ago, but steps were added later. The in-app
            // permission request is silently throttled by Health Connect after
            // a couple of tries, so open HC settings directly — the reliable
            // path to toggle the steps read permission by hand.
            val ctx = LocalContext.current
            TextButton(onClick = {
                onConnectHc()
                try {
                    ctx.startActivity(
                        android.content.Intent(
                            androidx.health.connect.client.HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Exception) {}
            }) { Text(stringResource(R.string.today_screen_allow_steps)) }
        }
        }
        if (state.prediction.isNotEmpty() && hasWhatIfEngine) {
            val whatIfSummary = if (whatIfPred.isEmpty()) null else {
                val anchor = state.prediction.first().tsMs
                val horizon = whatIfPred.filter {
                    it.tsMs in anchor..(anchor + 180L * 60_000)
                }
                val p60 = horizon.minByOrNull {
                    kotlin.math.abs(it.tsMs - (anchor + 60L * 60_000))
                }
                val p180 = horizon.minByOrNull {
                    kotlin.math.abs(it.tsMs - (anchor + 180L * 60_000))
                }
                val minimum = horizon.minByOrNull { it.mmol }
                val lowerMinimum = horizon.minByOrNull { it.loMid }
                fun value(point: com.diapilot.core.twin.PredictedPoint?) = point?.let {
                    com.diapilot.core.analysis.fmtBg(it.mmol, state.mgdl)
                } ?: "—"
                fun interval(point: com.diapilot.core.twin.PredictedPoint?) = point?.let {
                    "${com.diapilot.core.analysis.fmtBg(it.mmol, state.mgdl)} " +
                        "(${com.diapilot.core.analysis.fmtBg(it.loMid, state.mgdl)}–" +
                        "${com.diapilot.core.analysis.fmtBg(it.hiMid, state.mgdl)})"
                } ?: "—"
                @Composable
                fun after(point: com.diapilot.core.twin.PredictedPoint?): String {
                    val minutes = point?.let { ((it.tsMs - anchor) / 60_000L).toInt() } ?: 0
                    return if (minutes < 60) {
                        stringResource(R.string.today_screen_after_minutes, minutes)
                    } else {
                        stringResource(R.string.today_screen_after_hours_minutes, minutes / 60, minutes % 60)
                    }
                }
                // THE FLOOR IS A MODEL FAILURE, NOT A PREDICTED MINIMUM.
                //
                // Found by external review. The integrator has no term that
                // stops the fall: food is finite, while insulin action has no
                // lower bound. So the line used to drift into the impossible
                // — stored points well below zero were found in the
                // database, with implausibly negative minimums. A floor
                // `HYBRID_FLOOR_MMOL` = 2.0 is now in place, and it saves
                // from the absurd — but the screen printed "minimum 2.0" in
                // the same font as a real number.
                //
                // A trajectory pinned to the floor has left the region where
                // the model means anything. Showing it as knowledge is worse
                // than saying the minimum is unknown: 2.0 looks like a
                // severe hypo and calls for action the model does not
                // actually support.
                val floored = minimum != null &&
                    minimum.mmol <= com.diapilot.core.hybrid.HYBRID_FLOOR_MMOL + 1e-6
                val risk = when {
                    floored ->
                        stringResource(R.string.today_screen_whatif_risk_floored)
                    minimum != null && minimum.mmol < state.rangeLo ->
                        stringResource(
                            R.string.today_screen_whatif_risk_center_below, value(minimum), after(minimum),
                        )
                    lowerMinimum != null && lowerMinimum.loMid < state.rangeLo ->
                        stringResource(
                            R.string.today_screen_whatif_risk_lower_below,
                            com.diapilot.core.analysis.fmtBg(lowerMinimum.loMid, state.mgdl),
                            after(lowerMinimum),
                        )
                    else -> null
                }
                WhatIfTrajectorySummary(
                    atHour = interval(p60),
                    minimum = if (floored) stringResource(R.string.today_screen_whatif_below_model_boundary)
                    else "${value(minimum)} ${after(minimum)}",
                    atThreeHours = value(p180),
                    risk = risk,
                )
            }
            InlineWhatIfPanel(
                units = wiUnits,
                onUnits = { wiUnits = it },
                second = wiSecond,
                units2 = wiUnits2,
                offset2 = wiOffset2,
                onAddSecond = {
                    wiSecond = true
                    if (wiOffset2 == 0f) wiOffset2 = 60f
                },
                onUnits2 = { wiUnits2 = it },
                onOffset2 = { wiOffset2 = it },
                onRemoveSecond = {
                    wiSecond = false
                    wiUnits2 = 0f
                    wiOffset2 = 0f
                },
                onReset = {
                    wiUnits = 0f
                    wiOffset = 0f
                    wiSecond = false
                    wiUnits2 = 0f
                    wiOffset2 = 0f
                    wiActivity = false
                },
                activity = wiActivity,
                activityAvailable = activityWhatIfAvailable,
                activityOffset = wiActivityOffset,
                activityDuration = wiActivityDuration,
                activityIntensity = wiActivityIntensity,
                onToggleActivity = { wiActivity = !wiActivity },
                onActivityOffset = { wiActivityOffset = it },
                onActivityDuration = { wiActivityDuration = it },
                onActivityIntensity = { wiActivityIntensity = it },
                trajectory = whatIfSummary,
                existingIob = state.iobUnits,
                trusted = insulinForecastTrusted,
                modifier = Modifier.onSizeChanged { whatIfPx = it.height },
            )
        }
        if (state.chartReadings.isNotEmpty()) {
            // Whatever the info block left us, minus the chart's own chrome
            // (range chips + minimap + column spacing).
            val chromeDp = 86.dp
            val canvasH = with(density) {
                val topH = if (topPx >= 0) topPx.toFloat() else boxHpx * 0.34f
                (boxHpx - topH - whatIfPx).toDp() - chromeDp
            }.coerceAtLeast(140.dp)
            GlucoseChart(
                readings = state.chartReadings,
                boluses = state.chartBoluses,
                meals = state.chartMeals,
                fromMs = state.chartFrom,
                toMs = state.chartTo,
                initialSpanMs = 6L * 3_600_000,
                prediction = state.prediction,
                comparisonPrediction = state.comparisonPrediction,
                whatIf = whatIfPred,
                comparisonWhatIf = comparisonWhatIfPred,
                whatIfDoseMarks = whatIfDoseMarks,
                whatIfActivityWindow = whatIfActivityWindow,
                activityWindows = state.activityWindows,
                activityEffectActive = state.activityEffectActive,
                iobSeries = state.iobSeries,
                cobSeries = state.cobSeries,
                minuteReadings = state.minuteReadings,
                meterChecks = state.historyMeter.map {
                    com.diapilot.core.collector.GlucosePoint(it.tsMs, it.mmol)
                },
                annotations = state.annotations,
                basals = state.chartBasals,
                canvasHeight = canvasH,
                labelByOnset = state.labelByOnset,
                mgdl = state.mgdl,
                rangeLo = state.rangeLo,
                rangeHi = state.rangeHi,
                targetMmol = state.targetMmol,
                insulinDiaMin = state.insulinDiaMin,
                insulinPeakMin = state.insulinPeakMin,
                counterfactualKernels = state.counterfactualKernels,
                counterfactualLandmarks = state.counterfactualLandmarks,
                onTagBolus = onTagBolus,
                onEditBolusUnits = onEditBolusUnits,
                onDeleteBolus = onDeleteBolus,
                onUpdateBasal = onUpdateBasal,
                onDeleteNote = onDeleteNoteById,
                onDeleteBasal = onDeleteBasal,
                onOpenNote = { editNoteId = it },
                onExplainFood = { id -> foodCalculation=FoodCalculationRegistry.get(id) },
                focusTs = focusTs,
                onFocusHandled = onFocusHandled,
                onInspectForecast = onInspectForecast,
                inspectStored = inspectStored,
                inspectReplay = inspectReplay,
                navActive = state.chartNavActive,
                onHistoryNav = onHistoryNav,
            )
        }
        foodCalculation?.let { c ->
            val unknown = stringResource(R.string.today_screen_calc_unknown)
            val proteinText = c.proteinG?.let { com.diapilot.core.analysis.fmtOneDecimal(it) } ?: unknown
            val fatText = c.fatG?.let { com.diapilot.core.analysis.fmtOneDecimal(it) } ?: unknown
            androidx.compose.material3.AlertDialog(
                onDismissRequest={foodCalculation=null},
                title={Text(stringResource(R.string.today_screen_calc_title, c.dish))},
                text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(stringResource(R.string.today_screen_calc_model, c.model),style=MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.today_screen_calc_carbs, c.carbsG, c.carbsProvenance))
                    Text(stringResource(R.string.today_screen_calc_macros, proteinText, fatText, c.durationMin))
                    Text(stringResource(R.string.today_screen_calc_global_cs, c.globalCsMedian, c.globalCsLow, c.globalCsHigh))
                    Text(
                        stringResource(
                            R.string.today_screen_calc_causal_evidence,
                            c.globalCsOnlineEpisodes, c.globalCsOnlineDays, c.globalCsEvidencePolicy,
                        ),
                        style=MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(
                            R.string.today_screen_calc_food_amplitude,
                            c.totalAmplitudeMmol, c.foodContribution60, c.foodContribution180,
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.today_screen_calc_timing,
                            c.timingSource, c.timingTemplateId, c.onsetMin, c.halfArrivalMin,
                            c.effectEndMin, c.timingUncertaintyMin,
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.today_screen_calc_curve_choice,
                            c.kineticsSummary.resolve(androidx.compose.ui.platform.LocalContext.current),
                        ),
                        style=MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(
                            R.string.today_screen_calc_insulin_isf,
                            c.insulinContribution60, c.isfMedian, c.isfLow, c.isfHigh,
                        ),
                    )
                    Text(stringResource(R.string.today_screen_calc_background, c.backgroundContribution60))
                    Text(
                        stringResource(
                            R.string.today_screen_calc_uncertainty,
                            FoodCalculationRegistry.uncertaintyText(
                                androidx.compose.ui.platform.LocalContext.current, c,
                            ),
                        ),
                        style=MaterialTheme.typography.bodySmall,
                    )
                }},
                confirmButton={TextButton(onClick={foodCalculation=null}){Text(stringResource(R.string.today_screen_close))}},
            )
        }
        }
    }
}

@Composable
private fun InlineWhatIfPanel(
    units: Float,
    onUnits: (Float) -> Unit,
    second: Boolean,
    units2: Float,
    offset2: Float,
    onAddSecond: () -> Unit,
    onUnits2: (Float) -> Unit,
    onOffset2: (Float) -> Unit,
    onRemoveSecond: () -> Unit,
    onReset: () -> Unit,
    activity: Boolean,
    activityAvailable: Boolean,
    activityOffset: Float,
    activityDuration: Float,
    activityIntensity: Float,
    onToggleActivity: () -> Unit,
    onActivityOffset: (Float) -> Unit,
    onActivityDuration: (Float) -> Unit,
    onActivityIntensity: (Float) -> Unit,
    trajectory: WhatIfTrajectorySummary?,
    existingIob: Double?,
    trusted: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = units > 0f || (second && units2 > 0f) || activity
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.today_screen_whatif_add_units, units),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(76.dp),
            )
            androidx.compose.material3.Slider(
                value = units,
                onValueChange = onUnits,
                valueRange = 0f..10f,
                steps = 19,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.today_screen_whatif_second_dose),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onAddSecond)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
            if (activityAvailable) {
                Text(
                    if (activity) "🚶✓" else "🚶＋",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (activity) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onToggleActivity)
                        .padding(horizontal = 7.dp, vertical = 12.dp),
                )
            }
            if (active) {
                Text(
                    "×",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onReset)
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
        if (second) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.today_screen_whatif_more_units, units2),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(76.dp),
                )
                androidx.compose.material3.Slider(
                    value = units2,
                    onValueChange = onUnits2,
                    valueRange = 0f..10f,
                    steps = 19,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "×",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onRemoveSecond)
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.today_screen_whatif_after_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp),
                )
                listOf(30f, 60f, 90f, 120f).forEach { minutes ->
                    androidx.compose.material3.FilterChip(
                        selected = offset2 == minutes,
                        onClick = { onOffset2(minutes) },
                        modifier = Modifier.weight(1f),
                        label = { Text("+${minutes.toInt()}") },
                    )
                }
            }
        }
        if (activity) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.today_screen_whatif_walk_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(62.dp),
                )
                listOf(30f, 60f, 90f).forEach { minutes ->
                    androidx.compose.material3.FilterChip(
                        selected = activityDuration == minutes,
                        onClick = { onActivityDuration(minutes) },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.today_screen_whatif_activity_minutes, minutes.toInt())) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val nowLabel = stringResource(R.string.today_screen_whatif_now_label)
                listOf(0f to nowLabel, 30f to "+30", 60f to "+60").forEach { (offset, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = activityOffset == offset,
                        onClick = { onActivityOffset(offset) },
                        modifier = Modifier.weight(1f),
                        label = { Text(label) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val lightLabel = stringResource(R.string.today_screen_whatif_intensity_light)
                val normalLabel = stringResource(R.string.today_screen_whatif_intensity_normal)
                val fastLabel = stringResource(R.string.today_screen_whatif_intensity_fast)
                listOf(0.7f to lightLabel, 1f to normalLabel, 1.4f to fastLabel).forEach { (level, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = activityIntensity == level,
                        onClick = { onActivityIntensity(level) },
                        modifier = Modifier.weight(1f),
                        label = { Text(label) },
                    )
                }
            }
        }
        if (active && trajectory != null) {
            Text(
                (existingIob?.takeIf { it > 0.05 }?.let {
                    stringResource(R.string.today_screen_whatif_on_top_iob, it) + " · "
                } ?: "") +
                    stringResource(
                        R.string.today_screen_whatif_summary,
                        trajectory.atHour, trajectory.minimum, trajectory.atThreeHours,
                    ) +
                    if (!trusted) " · " + stringResource(R.string.today_screen_whatif_model_limited) else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (trusted) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            trajectory.risk?.let { risk ->
                Text(
                    risk,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/**
 * The food-suggestion inbox. Each row is a trigger the engine believes was an
 * unlogged meal (a detected rise / a meal-sized dose with no note), with the
 * hour-of-day dish prediction pre-filled. One tap accepts; "edit" edits
 * the name/grams INLINE (no composer round-trip); "✗" declines for good.
 */
@Composable
private fun FoodSuggestDialog(
    suggestions: List<com.diapilot.core.analysis.FoodSuggestion>,
    onAccept: (com.diapilot.core.analysis.FoodSuggestion, String, Double?) -> Unit,
    onDecline: (com.diapilot.core.analysis.FoodSuggestion) -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    var editTs by remember { mutableStateOf<Long?>(null) }
    var editName by remember { mutableStateOf("") }
    var editGrams by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.today_screen_close)) } },
        title = { Text(stringResource(R.string.today_screen_food_suggest_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                suggestions.forEach { s ->
                    val p = s.prediction
                    val head = buildString {
                        append(fmt.format(java.util.Date(s.triggerTsMs)))
                        append(" · ")
                        append(
                            FoodText.suggestTrigger(
                                androidx.compose.ui.platform.LocalContext.current, s.trigger,
                            ),
                        )
                        s.units?.let {
                            append(" ")
                            append(stringResource(R.string.today_screen_food_suggest_units, it))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            head,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (editTs == s.triggerTsMs) {
                            OutlinedTextField(
                                value = editName, onValueChange = { editName = it },
                                label = { Text(stringResource(R.string.today_screen_food_suggest_what_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = editGrams, onValueChange = { editGrams = it },
                                label = { Text(stringResource(R.string.today_screen_food_suggest_carbs_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row {
                                TextButton(
                                    enabled = editName.isNotBlank(),
                                    onClick = {
                                        onAccept(
                                            s, editName.trim(),
                                            editGrams.trim().replace(',', '.').toDoubleOrNull(),
                                        )
                                        editTs = null
                                    },
                                ) { Text(stringResource(R.string.today_screen_food_suggest_save)) }
                                TextButton(onClick = { editTs = null }) { Text(stringResource(R.string.today_screen_food_suggest_cancel)) }
                            }
                        } else {
                            Text(
                                p?.let {
                                    it.dish +
                                        (it.grams?.let { g -> " · " + stringResource(R.string.today_screen_food_suggest_grams, g) } ?: "") +
                                        " · ×${it.nHistory}"
                                } ?: stringResource(R.string.today_screen_food_suggest_unknown_dish),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row {
                                if (p != null) {
                                    TextButton(
                                        onClick = { onAccept(s, p.dish, p.grams) },
                                    ) { Text(stringResource(R.string.today_screen_food_suggest_accept)) }
                                }
                                TextButton(onClick = {
                                    editTs = s.triggerTsMs
                                    editName = p?.dish ?: ""
                                    editGrams = p?.grams?.let {
                                        if (it == Math.floor(it)) it.toInt().toString() else it.toString()
                                    } ?: ""
                                }) { Text(stringResource(R.string.today_screen_food_suggest_edit)) }
                                TextButton(onClick = { onDecline(s) }) { Text("✗") }
                            }
                        }
                    }
                }
            }
        },
    )
}
