package io.github.obdosok.diapilot

/**
 * The labeling tab: detected meals, history feed, meal cards.
 */

import android.content.Context
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
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
import io.github.obdosok.diapilot.data.FoodBasis
import io.github.obdosok.diapilot.data.FoodCalculationRegistry
import io.github.obdosok.diapilot.data.HybridFoodReadout
import io.github.obdosok.diapilot.data.Stores
import io.github.obdosok.diapilot.data.Units
import io.github.obdosok.diapilot.i18n.FoodText
import io.github.obdosok.diapilot.i18n.TokenText
import io.github.obdosok.diapilot.i18n.localized
import io.github.obdosok.diapilot.i18n.unitLabel
import io.github.obdosok.diapilot.ui.AnalysisScreen
import io.github.obdosok.diapilot.ui.AnnotationEditor
import io.github.obdosok.diapilot.ui.AnnotationRow
import io.github.obdosok.diapilot.ui.FoodLibraryDialog
import io.github.obdosok.diapilot.ui.GlucoseChart
import io.github.obdosok.diapilot.ui.HistoryDatePicker
import io.github.obdosok.diapilot.ui.PhotoThumbPublic
import io.github.obdosok.diapilot.ui.pickDateTime
import io.github.obdosok.diapilot.ui.theme.DiaPilotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class HistoryItem(val tsMs: Long) {
    class Meal(
        val event: MealEvent,
        val label: String?,
        val note: com.diapilot.core.collector.Annotation? = null,   // merged food note
        val continuations: List<MealEvent> = emptyList(),           // ↩ children
        // The note's session-mates (starter photographed a minute before the
        // main course) — same meal, folded into this row.
        val extraNotes: List<com.diapilot.core.collector.Annotation> = emptyList(),
        // Sort by the EATING time (note ts) when a food note is merged — the
        // row DISPLAYS that time, so the detection onset must not be the sort
        // key, or a meal jumps out of chronological order past later events.
    ) : HistoryItem(note?.tsMs ?: event.onsetMs) {
        val allNotes: List<com.diapilot.core.collector.Annotation>
            get() = listOfNotNull(note) + extraNotes
        // Header carbs are AUTHORITATIVE from the component split when a dish
        // has a composition breakdown (that's the model's number and what the
        // editor shows), falling back to the stored estCarbs for atomic dishes.
        // Prevents the old split-brain where the header showed stale estCarbs
        // while the editor/fingerprint showed the recomputed component sum.
        val totalCarbs: Double?
            get() = allNotes.mapNotNull { n ->
                // Trust the component sum only for the CANONICAL composition-marker format
                // (editor output). Bullet-only legacy parses keep their estCarbs —
                // their bullet midpoints are raw LLM ranges, often inflated.
                if (com.diapilot.core.analysis.hasCanonicalComposition(n.analysis))
                    com.diapilot.core.analysis.parseComponents(n.analysis ?: "").sumOf { it.totalGrams }
                else n.estCarbs
            }.takeIf { it.isNotEmpty() }?.sum()
    }
    class Bolus(val point: BolusPoint) : HistoryItem(point.tsMs)
    class Basal(val point: BolusPoint) : HistoryItem(point.tsMs)
    class Note(
        val annotation: com.diapilot.core.collector.Annotation,
        // Session-mates grouped under this row (undetected multi-dish meal).
        val mates: List<com.diapilot.core.collector.Annotation> = emptyList(),
    ) : HistoryItem(annotation.tsMs)
    class Meter(val reading: com.diapilot.core.collector.Reading) : HistoryItem(reading.tsMs)
}

private enum class HistoryFilter { ALL, FOOD, INSULIN, NOTES }

@Composable
private fun historyFilterLabel(f: HistoryFilter): String = stringResource(
    when (f) {
        HistoryFilter.ALL -> R.string.label_screen_filter_all
        HistoryFilter.FOOD -> R.string.label_screen_filter_food
        HistoryFilter.INSULIN -> R.string.label_screen_filter_insulin
        HistoryFilter.NOTES -> R.string.label_screen_filter_notes
    },
)

// Components a meal contributes to the concept model. A decomposed dish gives
// its parsed composition; an ATOMIC one (beer, ice cream, smoothie — no
// composition breakdown) is a SINGLE component named after the dish, so it
// still maps to a concept-id and earns a fingerprint. That's why "beer = 20 g"
// without a composition breakdown is fine: the name alone pools it with every
// other beer regardless of how it was typed.
/** The same intrinsic food response v11 feeds into the forward forecast. */
private fun hybridFoodLine(
    rows: List<HybridFoodReadout>,
    mgdl: Boolean,
    context: Context,
): String? {
    if (rows.isEmpty()) return null
    val text = context.localized()
    // Decimal separator follows the UI language, like core's fmtBg/fmtOneDecimal.
    fun fmtCs(x: Double, digits: Int): String {
        val s = String.format(java.util.Locale.ROOT, "%.${digits}f", x)
        return if (com.diapilot.core.analysis.BgFormat.decimalComma) s.replace('.', ',') else s
    }
    val total = rows.sumOf { it.amplitudeMmol }
    val weight = rows.sumOf { it.amplitudeMmol.coerceAtLeast(0.01) }
    // HALF-ARRIVAL, not the rate peak. The peak is the argmax of a two-humped
    // arrival curve and jumps between humps: measured on the shipped engine,
    // 40 g of carbohydrate reports 55 -> 50 -> 154 -> 149 min as fat rises
    // 0 -> 10 -> 20 -> 40 g. Half-arrival is a level crossing, monotone, and
    // answers the same question the card was trying to answer.
    val half = (rows.sumOf { it.halfArrivalMin * it.amplitudeMmol.coerceAtLeast(0.01) } / weight)
        .toInt()
    val onset = rows.minOf { it.onsetMin }
    val duration = rows.maxOf { it.durationMin }
    val carbs=rows.mapNotNull{it.carbsG}.sum().takeIf{it>0}

    // UNITS. `total` is mmol and `fmtBg` renders in the DISPLAY unit, so quoting
    // CS as mmol/g beside an mg/dL result made the line arithmetically false:
    // a live check showed the two units disagreeing on the same card. Both
    // sides now speak the unit the reader is in, and the unit is named rather
    // than implied.
    val csDisplay = carbs?.let {
        val perGram = total / it
        if (mgdl) perGram * com.diapilot.core.analysis.MGDL_PER_MMOL_F else perGram
    }
    val arithmetic = if (carbs != null && csDisplay != null)
        text.getString(
            R.string.label_screen_food_arithmetic,
            carbs,
            fmtCs(csDisplay, if (mgdl) 2 else 3),
            com.diapilot.core.analysis.fmtBg(total, mgdl),
            unitLabel(mgdl),
        )
    else text.getString(
        R.string.label_screen_food_forecast_only,
        com.diapilot.core.analysis.fmtBg(total, mgdl),
        unitLabel(mgdl),
    )
    fun timingEvidence(row:HybridFoodReadout):String=when(row.timingSource){
        // SAY WHAT IS BEHIND THE TIMING. The card described the structural
        // mixture without ever saying how much of the user's own history
        // stands behind it — and for this branch the answer is none: the
        // mixture is derived from the note's macro fractions, and the user's
        // corpus may hold measured episodes of the same dish that no part of
        // this path consults. Reading the old "structured mixture" text as
        // "the model has learned this smoothie" was a fair inference from
        // the old text.
        // The percentages CAME BACK after being cut: the user reads
        // them to judge whether the LLM classified the dish correctly — they
        // are input control, not diagnostics. Still cut: the source tag and
        // the "not classified by name" note. The episode count stays — it is
        // the lesson (how much of the user's history backs the timing).
        // ONLY TWO ANSWERS ARE POSSIBLE NOW, and that is the whole point of the
        // cleanup: either the note's macros drive the mixture, or
        // there are no macros and the prior does. The branches that were here
        // for "personal recipe", "macro/shape class" and "observed class"
        // described per-dish templates, deleted in M-129.
        //
        // The "personal episodes: N" line went with them, and it was the
        // misleading one: the counter has been a hardcoded 0 since the pools
        // were removed, so every meal was being told "shape not measured" as
        // if that were a fact about the dish rather than about a field
        // nobody fills.
        "structured-feature-mixture-v2"->
            row.kinetics?.let { k ->
                if (k.tailEndMin != null) text.getString(
                    R.string.hybrid_readout_kinetics_tail, k.fastPct, k.mediumPct, k.slowPct, k.form, k.tailEndMin,
                ) else text.getString(R.string.hybrid_readout_kinetics, k.fastPct, k.mediumPct, k.slowPct, k.form)
            } ?: text.getString(R.string.label_screen_food_structured_mixture)
        "physiological-prior","physiological_prior"->
            text.getString(R.string.label_screen_food_physio_template)
        else->when(row.timingBasis){
            FoodBasis.DISH_PROFILE->text.getString(R.string.label_screen_basis_dish_profile)
            FoodBasis.GLOBAL_CS->text.getString(R.string.label_screen_basis_global_cs)
            FoodBasis.MACROS_DURATION->text.getString(R.string.label_screen_basis_macros_duration)
            else->row.timingBasis
        }
    }
    val evidence=rows.map(::timingEvidence).distinct().joinToString(" + ")
    // THE CURVE THAT WAS APPLIED, when it differs from the dish's own.
    // Review finding: the card always described the dish ALONE while the
    // forecast drew it inside a shared gastric pipe, so a dessert eaten right
    // after a heavy meal was shown a curve nobody used. Both are printed,
    // labelled, and the reason — how much of the earlier food had already
    // left the stomach — is printed beside them rather than left to be
    // inferred.
    val clusterRows = rows.filter { it.clusterMembers > 1 && it.clusterHalfArrivalMin != null }
    val clusterLine = if (clusterRows.isEmpty()) "" else {
        val cHalf = (clusterRows.sumOf {
            (it.clusterHalfArrivalMin ?: 0) * it.amplitudeMmol.coerceAtLeast(0.01)
        } / clusterRows.sumOf { it.amplitudeMmol.coerceAtLeast(0.01) }).toInt()
        val cDuration = clusterRows.mapNotNull { it.clusterDurationMin }.maxOrNull()
        val members = clusterRows.maxOf { it.clusterMembers }
        val prior = clusterRows.mapNotNull { it.clusterPriorRealised }.minOrNull()
        "\n" + text.resources.getQuantityString(R.plurals.label_screen_food_cluster_summary, members, members, cHalf) +
            (cDuration?.let { " · " + text.getString(R.string.label_screen_food_cluster_p90, it) } ?: "") +
            (prior?.let {
                "\n" + text.getString(R.string.label_screen_food_cluster_prior_absorbed, (it * 100).toInt())
            } ?: "")
    }
    // Macros and kcal on the main card: the macros are what the user
    // checks the LLM's parse against, and the kcal is what the gastric queue
    // actually meters.
    val protein = rows.mapNotNull { it.proteinG }.sum().takeIf { rows.any { r -> r.proteinG != null } }
    val fat = rows.mapNotNull { it.fatG }.sum().takeIf { rows.any { r -> r.fatG != null } }
    val macroLine = if (protein == null && fat == null) "" else {
        val kcal = com.diapilot.core.analysis.MealCaloricExtentV1.kcal(carbs ?: 0.0, protein, fat)
        "\n" + text.getString(R.string.label_screen_food_macro_line, protein ?: 0.0, fat ?: 0.0, carbs ?: 0.0, kcal)
    }
    val prediction = text.getString(R.string.label_screen_food_prediction, arithmetic, onset, half, duration) +
        macroLine + clusterLine +
        "\n" + text.getString(R.string.label_screen_food_timing, evidence)
    val observedRow = rows.firstOrNull { it.observed != null } ?: return prediction+
        "\n" + text.getString(R.string.label_screen_food_fact_not_calculated)
    val observed = observedRow.observed ?: return prediction
    if(observed.amplitudeMmol==null&&observed.onsetMin==null&&observed.levelMaxMin==null&&observed.plateauMin==null){
        val reason=when(observed.status){
            "overlapped_by_food"->text.getString(R.string.label_screen_food_reason_overlapped)
            "carbs_missing"->text.getString(R.string.label_screen_food_reason_carbs_missing)
            "not_extractable"->text.getString(R.string.label_screen_food_reason_not_extractable)
            else->text.getString(R.string.label_screen_food_reason_insufficient_data)
        }
        return prediction+"\n"+text.getString(R.string.label_screen_food_fact_reason, reason)
    }
    fun minuteDelta(actual:Int,predicted:Int)=String.format(java.util.Locale.ROOT,"%+d",actual-predicted)
    val facts=buildList{
        observed.onsetMin?.let{
            add(text.getString(R.string.label_screen_food_onset_fact, it, minuteDelta(it,observedRow.onsetMin)))
        }
        observed.levelMaxMin?.let{
            add(
                if(observed.levelMaxCensored) text.getString(R.string.label_screen_food_level_max_censored, it)
                else text.getString(R.string.label_screen_food_level_max, it),
            )
        }
        observed.plateauMin?.let{
            add(text.getString(R.string.label_screen_food_plateau_fact, it, minuteDelta(it,observedRow.durationMin)))
        }
        if(observed.plateauMin==null&&observed.latePhaseObserved)add(text.getString(R.string.label_screen_food_late_phase))
    }
    // Only whether something was seen matters below (seen.isEmpty()) — the
    // labels themselves are never displayed, so they need no translation.
    val seen=buildList{
        if(observed.onsetMin!=null)add("onset")
        if(observed.levelMaxMin!=null&&!observed.levelMaxCensored)add("level_max")
        if(observed.plateauMin!=null)add("plateau")
        else if(observed.latePhaseObserved)add("late_phase")
    }
    val amplitude=observed.amplitudeMmol?.let{
        val delta=com.diapilot.core.analysis.fmtBgDelta(it-observedRow.amplitudeMmol,mgdl)
        val bound=if(observed.levelMaxCensored)"≥" else ""
        "$bound${com.diapilot.core.analysis.fmtBg(it,mgdl)} " +
            text.getString(R.string.label_screen_food_amplitude_delta, delta)
    }
    val missing=buildList{
        if(observed.onsetMin==null)add(text.getString(R.string.label_screen_food_missing_onset))
        if(observed.levelMaxMin==null||observed.levelMaxCensored)add(text.getString(R.string.label_screen_food_missing_level_max))
        if(observed.plateauMin==null)add(text.getString(R.string.label_screen_food_missing_plateau))
    }
    val quality=buildList{
        // "Insulin action accounted for" was true of every row this path can emit —
        // a sentence that is always true says nothing, so it went.
        if(observed.neighbourMmol>0.0){
            val neighbour=com.diapilot.core.analysis.fmtBg(observed.neighbourMmol,mgdl)
            add(if(observed.neighbourResolved)
                text.getString(R.string.label_screen_food_overlap_resolved, neighbour)
            else text.getString(R.string.label_screen_food_overlap_unresolved, neighbour))
        }
        observed.confidence?.let{add(text.getString(R.string.label_screen_food_confidence_pct, it*100))}
        if(observed.sensorUnstable)add(text.getString(R.string.label_screen_food_sensor_unstable))
    }
    // MODEL VS FACT AS ONE PICTURE — this replaces comparing separate number
    // rows by eye. Both rows share ONE maximum: normalising each to its own
    // peak would make every pair look identical, which is precisely the
    // deception a comparison chart exists to avoid. The fact row is a PREFIX of
    // the model's grid (its window is censored at the next meal), so a shorter
    // fact row means "the window ended", not "the contribution ended".
    val modelCurve = observedRow.modelCurveMmol
    val factCurve = observed.curveMmol
    // THREE ROUTES, ONE PICTURE. The dish's own measured curve, the same meal
    // read from carbs/fat/calories with that memory withheld, and what was
    // recovered from the glucose.
    //
    // The middle row is drawn ONLY when the dish actually has a pool: otherwise
    // it is the same line as the first, and printing it twice would manufacture
    // an agreement between two routes that never disagreed.
    // `mixtureCurveMmol` was «the same meal WITHOUT its learned curve», and there
    // are no learned curves since M-129 — the metrics object returns an empty
    // list unconditionally. Kept as a local so the rows below read unchanged;
    // `paired` is simply never true now.
    val mixtureCurve = observedRow.mixtureCurveMmol
    val curveBlock = if (modelCurve.size < 3 || factCurve.size < 3) "" else {
        val shared = listOf(modelCurve.max(), factCurve.max(), mixtureCurve.maxOrNull() ?: 0.0)
            .max().coerceAtLeast(1e-9)
        val blocks = "▁▂▃▄▅▆▇█"
        fun sparkRow(vs: List<Double>) = vs.joinToString("") {
            blocks[((it / shared) * (blocks.length - 1)).toInt().coerceIn(0, blocks.length - 1)].toString()
        }
        val lastTau = observed.curveTaus.lastOrNull()?.toInt()
        val paired = mixtureCurve.size >= 3
        val dishLabel = text.getString(R.string.label_screen_food_curve_dish)
        val modelLabel = text.getString(R.string.label_screen_food_curve_model)
        val mixLabel = text.getString(R.string.label_screen_food_curve_mix)
        val factLabel = text.getString(R.string.label_screen_food_curve_fact)
        val labelWidth = maxOf(dishLabel.length, modelLabel.length, mixLabel.length, factLabel.length)
        fun pad(s: String) = s.padEnd(labelWidth)
        buildString {
            append("\n").append(pad(if (paired) dishLabel else modelLabel))
            append(" ").append(sparkRow(modelCurve))
            if (paired) append("\n").append(pad(mixLabel)).append(" ").append(sparkRow(mixtureCurve))
            append("\n").append(pad(factLabel)).append(" ").append(sparkRow(factCurve))
            // The fact row is a PREFIX of the model grid - its window is cut at
            // the next meal - so a shorter row means the window ended, never
            // that the contribution ended.
            lastTau?.takeIf { factCurve.size < modelCurve.size }
                ?.let { append(" ").append(text.getString(R.string.label_screen_food_curve_window, it)) }
            // And it is a RECONSTRUCTION, not a measurement: glucose with the
            // insulin model subtracted, sometimes a neighbour too. Drawn beside
            // two predictions it would otherwise read as ground truth, which is
            // exactly what we do not have.
            append("\n").append(
                if (observed.neighbourMmol > 0.0)
                    text.getString(R.string.label_screen_food_curve_reconstructed_neighbor)
                else text.getString(R.string.label_screen_food_curve_reconstructed),
            )
        }
    }
    return buildString{
        append(prediction)
        append(curveBlock)
        append("\n").append(text.getString(R.string.label_screen_food_episode_header))
        amplitude?.let{append(": +$it")}
        if(facts.isNotEmpty())append(" · ").append(facts.joinToString(" · "))
        // The positive "seen" list restated the fact numbers printed
        // one line above it; only what is MISSING carries information.
        if(missing.isNotEmpty())append("\n").append(text.getString(R.string.label_screen_food_missing, missing.joinToString(", ")))
        else if(seen.isEmpty())append("\n").append(text.getString(R.string.label_screen_food_fragment_only))
        if(quality.isNotEmpty())append("\n").append(text.getString(R.string.label_screen_food_quality, quality.joinToString(" · ")))
    }
}

/** Observable primary History projection; also serves the recomposition test. */
@Composable
internal fun Stage10MealReceipt(
    annotationIds:List<Long>,
    modifier:Modifier=Modifier,
) {
    val snapshot by FoodCalculationRegistry.episodeFlow.collectAsState()
    val boundary=annotationIds.mapNotNull(FoodCalculationRegistry::get)
        .firstOrNull{it.nextMealAtMs!=null&&it.realisedFractionAtNext!=null}
    boundary?.let{b->
        val fraction=b.realisedFractionAtNext!!.coerceIn(0.0,1.0)
        val nextTime=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(b.nextMealAtMs!!))
        Text(
            stringResource(
                R.string.label_screen_receipt_next_meal,
                nextTime, fraction * 100, b.totalAmplitudeMmol * fraction, b.totalAmplitudeMmol, (1 - fraction) * 100,
            ),
            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=modifier,
        )
    }
    val cluster=FoodCalculationRegistry.let{registry->annotationIds.mapNotNull(registry::get).firstOrNull{it.clusterMembers>1}}
    cluster?.let{c->
        val clusterGrams = c.clusterCarbsG?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: "?"
        val clusterText=when(c.timingScope){
            "COMPLETED_BEFORE_CLUSTER"->stringResource(R.string.label_screen_receipt_cluster_completed)
            "CLUSTER_ONLY"->pluralStringResource(R.plurals.label_screen_receipt_cluster_only, c.clusterMembers, c.clusterMembers, clusterGrams)
            else->null
        }
        clusterText?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary,modifier=modifier)}
    }
    val receipt=snapshot.receipts.filterKeys{it in annotationIds}.values
        .distinctBy{it.receiptVersion+it.allocation+it.resolution}.firstOrNull()
    var expanded by rememberSaveable(annotationIds.joinToString(",")){mutableStateOf(false)}
    receipt?.let{e->
        Column(modifier=modifier) {
            // THE PROMISE'S OWN SIGNAL, surfaced only when it fires: «if the
            // line lies, something we do NOT track is acting». At 0.2 mmol it
            // is noise and stays in the depth; above 1 mmol it is the most
            // important line on the card.
            if(e.unloggedResidualMmol>1.0)Text(
                stringResource(R.string.label_screen_receipt_unlogged_residual, e.unloggedResidualMmol),
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error,
            )
            // ONE FACT PER CARD — the deconvolution's, not the user's decision.
            // This receipt's numbers live behind the "how was this
            // calculated?" disclosure only; even the LABEL that first
            // replaced them proved to be a line of noise ("where did it go
            // down?") and was cut.
            Text(
                e.compactFinding.ifBlank{e.resolution},
                style=MaterialTheme.typography.labelSmall,
                color=if(e.unresolved) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val forecastPrefix = stringResource(R.string.label_screen_receipt_forecast_prefix, e.modelForecast)
            val confidencePrefix = stringResource(R.string.label_screen_receipt_confidence_prefix, e.confidence)
            TextButton(onClick={expanded=!expanded}){
                Text(if(expanded)stringResource(R.string.label_screen_receipt_hide_calc) else stringResource(R.string.label_screen_receipt_how_calculated))
            }
            if(expanded)Text(
                "$forecastPrefix\n${e.allocatedMealContribution}\n${e.phases}\n${e.overlap}\n"+
                    "${e.allocation}; ${e.resolution}\n${e.timeResolvedCurve}\n${e.tailTransfer}\n"+
                    "$confidencePrefix. ${e.caveats}\n${e.episodeKernel}\n${e.kernelDifference}\n"+
                    "${e.causalProvenance}\n${e.diagnosticProvenance}",
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } ?: run {
        // NO BUTTON. The backlog drains itself now (TwinCache slices until it is
        // done or its wall-clock cap expires), so asking the user to tap the
        // old "continue calculation" button — which invalidated the whole
        // Twin to earn ONE more slice — was making them hand-crank a queue
        // the app can turn itself.
        //
        // What is left is a progress line, and it says WHERE the work happens
        // rather than implying it is advancing before the user's eyes: the sidecar runs
        // inside a Twin build, and Twin builds are deliberately not triggered
        // from History (History must never wait for the model). Pretending
        // otherwise with a live-looking bar would be the same class of lie as
        // the auto-fit spinner over a cancelled job (A-34).
        if(!snapshot.complete&&snapshot.totalClusters>0)Column(
            modifier=modifier,
            verticalArrangement=Arrangement.spacedBy(2.dp),
        ) {
            val completed=snapshot.nextOffset.coerceIn(0,snapshot.totalClusters)
            Text(
                stringResource(R.string.label_screen_receipt_progress, completed, snapshot.totalClusters),
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary,
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress={ completed.toFloat()/snapshot.totalClusters.coerceAtLeast(1) },
                modifier=Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.label_screen_receipt_progress_note),
                style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LabelScreen(
    state: UiState,
    onLabel: (MealEvent, String) -> Unit,
    onRelabel: (LabeledMeal, String) -> Unit,
    onDismissMeal: (Long) -> Unit,
    onUpdateNote: (com.diapilot.core.collector.Annotation, Long, String, String?) -> Unit,
    onDeleteNote: (com.diapilot.core.collector.Annotation) -> Unit,
    /** "Repeat": the same dish, logged for now. */
    onRepeatFood: (com.diapilot.core.collector.Annotation) -> Unit = {},
    /** Half portion / double: the same meal, rescaled as a whole. */
    onScaleFoodPortion: (com.diapilot.core.collector.Annotation, Double) -> Unit = { _, _ -> },
    onDeleteBasal: (Long) -> Unit = {},
    onUpdateBasal: (oldTs: Long, newTs: Long, units: Double) -> Unit = { _, _, _ -> },
    onTagBolus: (Long, String) -> Unit = { _, _ -> },
    onAddNote: (Long, String, String?, String, Double?) -> Unit = { _, _, _, _, _ -> },
    onShowOnChart: (Long) -> Unit = {},
    onEditBolusUnits: (Long, Double) -> Unit = { _, _ -> },
    onDeleteBolus: (Long) -> Unit = {},
    onUpdateMeter: (Long, Long, Double) -> Unit = { _, _, _ -> },
    onDeleteMeter: (Long) -> Unit = {},
    onRefreshRequest: () -> Unit = {},
    onReconcilePlan: suspend () -> List<com.diapilot.core.analysis.LabelFix> = { emptyList() },
    onApplyReconcile: (List<com.diapilot.core.analysis.LabelFix>) -> Unit = {},
    onDecomposePlan: suspend () -> List<DishDecomp> = { emptyList() },
    onLlmDecomposePlan: suspend () -> List<DishDecomp> = { emptyList() },
    onApplyDecompose: (List<DishDecomp>) -> Unit = {},
    onNutritionPlan: suspend () -> List<NutritionFill> = { emptyList() },
    onApplyNutrition: (List<NutritionFill>) -> Unit = {},
    onSearchStart: () -> Unit = {},
    onLoadMoreHistory: () -> Unit = {},
    onHistoryJump: (Long) -> Unit = {},
    onMarkMeal: (Long, com.diapilot.core.analysis.MarkKind, String?) -> Unit = { _, _, _ -> },
    onUnmarkMeal: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val text = context.localized()
    var reconcilePlan by remember {
        mutableStateOf<List<com.diapilot.core.analysis.LabelFix>?>(null)
    }
    var decompPlan by remember {
        mutableStateOf<List<DishDecomp>?>(null)
    }
    var decompBusy by remember { mutableStateOf(false) }
    var nutritionBusy by remember { mutableStateOf(false) }
    var nutritionPlan by remember {
        mutableStateOf<List<NutritionFill>?>(null)
    }
    val scope = rememberCoroutineScope()
    var showLibrary by remember { mutableStateOf(false) }
    var showHistoryTools by remember { mutableStateOf(false) }
    var showHistoryMaintenance by remember { mutableStateOf(false) }
    // Deep-link into a dish's library editor (its LLM breakdown → recipe),
    // opened from a history meal's ⋯ menu. null = list mode.
    // Every eating moment in the window. A shot belongs to the NEAREST of them
    // and to that one only: a ±45 min window around each meal overlaps its
    // neighbour, so a shot could be shown under BOTH of two nearby meals —
    // the same insulin counted twice on screen. Mutual-nearest
    // is the rule fingerprintShapedFoods already uses to bind a note to a food.
    val foodAnchors = remember(state.historyNotes, state.historyMeals) {
        val noteTs = state.historyNotes
            .filter { com.diapilot.core.analysis.isFoodNote(it) }
            .map { it.tsMs }
        // A rise with no note of its own is an eating moment too.
        noteTs + state.historyMeals.map { it.onsetMs }
            .filter { o -> noteTs.none { kotlin.math.abs(it - o) <= 45L * 60_000 } }
    }
    var editingMeal by remember { mutableStateOf<Long?>(null) }
    // A CONTINUATION is attached to its parent and dropped from the list, so it
    // has no row of its own — and was therefore impossible to re-label. Its ↩
    // line inside the parent opens the picker through this.
    var editingCont by remember { mutableStateOf<Long?>(null) }
    var editingNote by remember { mutableStateOf<Long?>(null) }
    var editingBolus by remember { mutableStateOf<Long?>(null) }
    var confirmDismiss by remember { mutableStateOf<Long?>(null) }
    var confirmBasalDelete by remember { mutableStateOf<Long?>(null) }
    var editBasalFor by remember { mutableStateOf<Long?>(null) }
    var editBasalUnitsText by remember { mutableStateOf("") }
    var editBasalTs by remember { mutableStateOf(0L) }
    var confirmBolusDelete by remember { mutableStateOf<Long?>(null) }
    var editUnitsFor by remember { mutableStateOf<Long?>(null) }
    var editUnitsText by remember { mutableStateOf("") }
    var editingMeter by remember { mutableStateOf<Long?>(null) }
    var meterText by remember { mutableStateOf("") }
    var meterTs by remember { mutableStateOf(0L) }
    var confirmMeterDelete by remember { mutableStateOf<Long?>(null) }
    var filter by rememberSaveable { mutableStateOf(0) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // labelByOnset MUST be a key: relabeling changes no event list, only the
    // label map — without it the cached items keep showing the old label.
    val items = remember(
        state.historyMeals, state.historyBoluses, state.historyNotes,
        state.historyBasals, state.historyMeter, state.labelByOnset, filter,
    ) {
        val f = HistoryFilter.entries[filter]
        val labelOf = state.labelByOnset
        val cont = com.diapilot.core.analysis.SysLabels.CONTINUATION

        // Continuations attach to their parent meal (nearest preceding labeled
        // food within 5h) — a continuation is a child, not a standalone event.
        val childrenByParent = mutableMapOf<Long, MutableList<MealEvent>>()
        val attachedConts = mutableSetOf<Long>()
        state.historyMeals.filter { labelOf[it.onsetMs] == cont }.forEach { c ->
            val parent = state.historyMeals.filter {
                val l = labelOf[it.onsetMs]
                l != null && l !in com.diapilot.core.analysis.SysLabels.ALL &&
                    it.onsetMs < c.onsetMs && c.onsetMs - it.onsetMs <= 5L * 3_600_000
            }.maxByOrNull { it.onsetMs }
            if (parent != null) {
                childrenByParent.getOrPut(parent.onsetMs) { mutableListOf() }.add(c)
                attachedConts.add(c.onsetMs)
            }
        }

        // A food note near a detected meal is the same event — merge into one
        // row. Exact text match wins; otherwise the nearest food-like note in
        // the stitch window attaches (either side may have been renamed, e.g.
        // by the Vision "-> rename" flow).
        val mergedNoteIds = mutableSetOf<Long>()
        // v11 composite food is one annotation with explicit composition-marker lines.
        // Do not infer that two independently logged dishes are one meal just
        // because their clocks are within 45 minutes: this previously folded a
        // separately logged smoothie and breakfast together more than once.
        // Separate rows stay separate.
        val mateIds = emptySet<Long>()
        fun matesOf(head: com.diapilot.core.collector.Annotation) =
            emptyList<com.diapilot.core.collector.Annotation>()
        fun foodLikeNote(n: com.diapilot.core.collector.Annotation): Boolean =
            n.content.isNotBlank() &&
                // Head-based: "walk · 150 min" must not fold into a meal row.
                !com.diapilot.core.analysis.isContextNote(n.content) &&
                n.content.lowercase() !in com.diapilot.core.analysis.SysLabels.ALL
        val mealItems = state.historyMeals
            .filter { it.onsetMs !in attachedConts }
            // CHRONOLOGICAL, though the store hands them back newest-first:
            // notes are claimed greedily (mergedNoteIds), so the iteration
            // order decides who gets which note. Newest-first let a LATER rise
            // reach back and steal an earlier meal's note — a rise stole a
            // note that had actually belonged to an earlier rise it followed
            // shortly after. Earliest-first, every rise sees its own
            // note first.
            .sortedBy { it.onsetMs }
            .map { meal ->
                val label = labelOf[meal.onsetMs]
                // A system-labeled rise (sport/dawn/unknown) is declared
                // "not food" — no food note gets folded into it; the note
                // stays its own row (e.g. dextrose before a workout rise).
                val note = if (label in com.diapilot.core.analysis.SysLabels.ALL) null else {
                    val inWindow = state.historyNotes.filter {
                        it.id !in mergedNoteIds && foodLikeNote(it) &&
                            it.tsMs in (meal.onsetMs - 90L * 60_000)..(meal.onsetMs + 30L * 60_000)
                    }
                    (inWindow.firstOrNull { it.content == label }
                        ?: inWindow.minByOrNull { kotlin.math.abs(it.tsMs - meal.onsetMs) })
                        ?.also { mergedNoteIds.add(it.id) }
                }
                val extras = emptyList<com.diapilot.core.collector.Annotation>()
                HistoryItem.Meal(
                    meal, label, note, childrenByParent[meal.onsetMs].orEmpty(),
                    extraNotes = extras,
                )
            }

        // ONE meal — ONE row. The detector can emit two events for a single
        // sitting (a second course, a drifted re-detect grabbing a different
        // note of the same session): display-merge food meals whose times
        // chain within the session gap. The later event folds in as a
        // continuation (its rise shows as ↩, its bolus as a correction bolus); notes pool.
        // Display-only — the DB keeps both events, so training is unaffected.
        val sessionMealItems = run {
            fun mergeable(x: HistoryItem.Meal): Boolean =
                x.label !in com.diapilot.core.analysis.SysLabels.ALL &&
                    !com.diapilot.core.analysis.isRescueNote(x.label) &&
                    !com.diapilot.core.analysis.isRescueNote(x.note?.content)
            val sorted = mealItems.sortedBy { it.tsMs }
            val out = mutableListOf<HistoryItem.Meal>()
            for (m in sorted) {
                val prev = out.lastOrNull()
                // A later event with its OWN distinct dish note is likely a
                // separate snack (ice cream, then beer) — merge only when
                // really close (25 min). A note-less re-detect of the same
                // rise merges within the full session gap (45 min).
                val ownDish = m.note != null &&
                    prev?.allNotes?.none { it.id == m.note.id } != false
                val gapMs = 45L * 60_000
                // A note-less RE-DETECT wearing the row above's label. The
                // detector fires twice on one meal for a minority of meals, and
                // the auto-labeller stamps BOTH copies with the same name — so
                // the copy that lost the note-claiming race renders as a bare
                // label, e.g. "07:58 · dextrose ×2" with no dextrose behind it. Same
                // name, no dish of its own, within 2h ⇒ it is the same thing
                // continuing, not a second one. Rescue is allowed here (unlike
                // the gap rule, which keeps a hypo out of a MEAL) — an identical
                // label means it IS that rescue.
                // Display-only: the DB keeps both events, training is untouched.
                val sameLabelRedetect = prev != null && m.note == null &&
                    m.label != null && m.label == prev.label &&
                    m.label !in com.diapilot.core.analysis.SysLabels.ALL &&
                    m.tsMs - maxOf(prev.tsMs, prev.event.onsetMs) in 0..(2L * 3_600_000)
                // The gap must be a real forward interval. Rows sort by NOTE
                // time while the chain measures from the previous row's EVENT,
                // so the delta can come out negative — and a bare `<= gapMs`
                // reads a negative delta as "well within 45" and merges meals
                // that are nowhere near each other (a smoothie could swallow
                // two unrelated later dishes into one row).
                val chained = sameLabelRedetect || (
                    prev != null && !ownDish && mergeable(prev) && mergeable(m) &&
                        m.tsMs - maxOf(prev.tsMs, prev.event.onsetMs) in 0..gapMs
                    )
                if (chained) {
                    out[out.size - 1] = HistoryItem.Meal(
                        prev!!.event,
                        prev.label ?: m.label,
                        prev.note,
                        prev.continuations + listOf(m.event) + m.continuations,
                        extraNotes = (prev.extraNotes + m.allNotes)
                            .distinctBy { it.id }
                            .filter { it.id != prev.note?.id },
                    )
                } else {
                    out.add(m)
                }
            }
            // The row's headline must be the FIRST dish of the meal: the
            // stitcher may have grabbed the note nearest to the detector's
            // onset (i.e. the SECOND course), leaving the first as an extra —
            // re-anchor on the earliest note so the time reads as "when the
            // meal started".
            out.map { m ->
                val notes = m.allNotes.sortedBy { it.tsMs }
                if (notes.size > 1 && m.note?.id != notes.first().id) {
                    HistoryItem.Meal(
                        m.event, m.label, notes.first(), m.continuations,
                        extraNotes = notes.drop(1),
                    )
                } else m
            }
        }

        // A bolus inside a meal's pairing window (-45..+20 min around the meal
        // or its continuations) is part of that episode — the meal row already
        // shows it. Raw shot list lives under the "Insulin" filter.
        val absorbedBolusTs: Set<Long> = if (f == HistoryFilter.ALL) {
            buildSet {
                sessionMealItems.forEach { m ->
                    (listOf(m.event) + m.continuations).forEach { ev ->
                        state.historyBoluses.forEach { b ->
                            if (b.tsMs in (ev.onsetMs - 45 * 60_000)..(ev.onsetMs + 20 * 60_000)) add(b.tsMs)
                        }
                    }
                    // Eating-time window too — the displayed dose is anchored
                    // there, its shot must not linger as a separate row.
                    val eatTs = m.note?.tsMs ?: m.event.onsetMs
                    state.historyBoluses.forEach { b ->
                        if (b.tsMs in (eatTs - 45L * 60_000)..(eatTs + 45L * 60_000)) add(b.tsMs)
                    }
                }
            }
        } else emptySet()

        buildList<HistoryItem> {
            if (f == HistoryFilter.ALL || f == HistoryFilter.FOOD) {
                sessionMealItems.forEach { m ->
                    // Dawn is not food — visible under "All" only.
                    val sys = m.label in com.diapilot.core.analysis.SysLabels.ALL
                    if (f == HistoryFilter.ALL || !sys) add(m)
                }
            }
            if (f == HistoryFilter.ALL || f == HistoryFilter.INSULIN) {
                state.historyBoluses.forEach {
                    if (it.tsMs !in absorbedBolusTs) add(HistoryItem.Bolus(it))
                }
                state.historyBasals.forEach { add(HistoryItem.Basal(it)) }
            }
            if (f == HistoryFilter.ALL) {
                state.historyMeter.forEach { add(HistoryItem.Meter(it)) }
            }
            if (f == HistoryFilter.FOOD) {
                // Manually logged food that the detector hasn't caught (yet):
                // it IS food and belongs under this filter from the moment of
                // entry; once a rise is detected and labeled, it merges into
                // the meal row instead.
                state.historyNotes.forEach { n ->
                    // Head-based check: "walk · 150 min" is context, not food.
                    val foodLike = n.content.isNotBlank() &&
                        !com.diapilot.core.analysis.isContextNote(n.content) &&
                        n.content.lowercase() !in com.diapilot.core.analysis.SysLabels.ALL
                    if (n.id !in mergedNoteIds && n.id !in mateIds && foodLike) {
                        add(HistoryItem.Note(n, mates = matesOf(n)))
                    }
                }
            }
            if (f == HistoryFilter.ALL || f == HistoryFilter.NOTES) {
                state.historyNotes.forEach {
                    if (it.id !in mergedNoteIds && it.id !in mateIds) {
                        add(HistoryItem.Note(it, mates = matesOf(it)))
                    }
                }
            }
        }.sortedByDescending { it.tsMs }
    }
    val visibleItems = remember(items, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) items else items.filter { item ->
            when (item) {
                is HistoryItem.Meal -> buildList {
                    item.label?.let(::add)
                    item.allNotes.forEach { note ->
                        add(note.content)
                        note.analysis?.let(::add)
                    }
                }.any { it.lowercase().contains(q) }
                is HistoryItem.Note -> (listOf(item.annotation) + item.mates).any { note ->
                    note.content.lowercase().contains(q) ||
                        note.analysis?.lowercase()?.contains(q) == true
                }
                else -> false
            }
        }
    }
    val dayFmt = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Pinned above the list: the filter row never scrolls away (#3).
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            var showHistoryDate by remember { mutableStateOf(false) }
            val anchorFmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                // Keep the title readable. The old header put seven emoji
                // actions in this row, leaving only a few pixels for the
                // screen title and forcing one letter per line on a phone.
                val historyDaysText = pluralStringResource(
                    R.plurals.label_screen_history_days, state.historyDays, state.historyDays,
                )
                Text(
                    if (state.historyAnchorMs != null)
                        "${anchorFmt.format(Date(state.historyAnchorMs!!))} · $historyDaysText"
                    else "${stringResource(R.string.label_screen_history_title)} · $historyDaysText",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showHistoryDate = true },
                    maxLines = 1,
                )
                if (state.historyAnchorMs != null) {
                    TextButton(onClick = { onHistoryJump(-1L) }) { Text(stringResource(R.string.label_screen_now)) }
                }
                TextButton(onClick = { showHistoryDate = true }) { Text(stringResource(R.string.label_screen_date)) }
                TextButton(onClick = {
                    searchOpen = !searchOpen
                    if (searchOpen) onSearchStart() else searchQuery = ""
                }) { Text(if (searchOpen) "×" else "⌕") }
                TextButton(onClick = { showHistoryTools = true }) { Text("⋮") }
                androidx.compose.material3.DropdownMenu(
                    expanded = showHistoryTools,
                    onDismissRequest = { showHistoryTools = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.label_screen_menu_dishes_recipes)) },
                        onClick = {
                            showHistoryTools = false
                            showLibrary = true
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                if (nutritionBusy) stringResource(R.string.label_screen_menu_estimating_macros)
                                else stringResource(R.string.label_screen_menu_fill_macros),
                            )
                        },
                        enabled = !nutritionBusy,
                        onClick = {
                            showHistoryTools = false
                            scope.launch {
                                nutritionBusy = true
                                nutritionPlan = onNutritionPlan()
                                nutritionBusy = false
                            }
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.label_screen_menu_history_maintenance)) },
                        onClick = {
                            showHistoryTools = false
                            showHistoryMaintenance = true
                        },
                    )
                }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.label_screen_search_label)) },
                    placeholder = { Text(stringResource(R.string.label_screen_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searchQuery.isNotBlank()) {
                    Text(
                        stringResource(R.string.label_screen_search_found, visibleItems.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showHistoryMaintenance) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showHistoryMaintenance = false },
                    title = { Text(stringResource(R.string.label_screen_maintenance_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.label_screen_maintenance_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                showHistoryMaintenance = false
                                scope.launch { reconcilePlan = onReconcilePlan() }
                            }) { Text(stringResource(R.string.label_screen_maintenance_check_links)) }
                            TextButton(onClick = {
                                showHistoryMaintenance = false
                                scope.launch { decompPlan = onDecomposePlan() }
                            }) { Text(stringResource(R.string.label_screen_maintenance_decompose)) }
                            TextButton(
                                enabled = !decompBusy,
                                onClick = {
                                    showHistoryMaintenance = false
                                    scope.launch {
                                        decompBusy = true
                                        decompPlan = onLlmDecomposePlan()
                                        decompBusy = false
                                    }
                                },
                            ) { Text(stringResource(R.string.label_screen_maintenance_llm_decompose)) }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showHistoryMaintenance = false }) {
                            Text(stringResource(R.string.label_screen_close))
                        }
                    },
                )
            }
            nutritionPlan?.let { plan ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { nutritionPlan = null },
                    title = { Text(stringResource(R.string.label_screen_nutrition_title)) },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (plan.isEmpty()) {
                                Text(
                                    stringResource(R.string.label_screen_nutrition_empty),
                                )
                            } else {
                                Text(
                                    pluralStringResource(
                                        R.plurals.label_screen_nutrition_summary,
                                        plan.size,
                                        plan.size, plan.sumOf { it.annotationIds.size },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                plan.forEach { row ->
                                    Text(
                                        stringResource(
                                            R.string.label_screen_nutrition_row,
                                            row.dish, row.proteinG, row.fatG, row.kcal,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { nutritionPlan = null }) { Text(stringResource(R.string.label_screen_cancel)) }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = plan.isNotEmpty(),
                            onClick = {
                                onApplyNutrition(plan)
                                nutritionPlan = null
                            },
                        ) { Text(stringResource(R.string.label_screen_save)) }
                    },
                )
            }
            reconcilePlan?.let { plan ->
                ReconcileDialog(
                    plan = plan,
                    onApply = { onApplyReconcile(it); reconcilePlan = null },
                    onDismiss = { reconcilePlan = null },
                )
            }
            decompPlan?.let { plan ->
                DecomposeDialog(
                    plan = plan,
                    onApply = { onApplyDecompose(it); decompPlan = null },
                    onDismiss = { decompPlan = null },
                )
            }
            if (showHistoryDate) {
                HistoryDatePicker(
                    onPick = { showHistoryDate = false; it?.let(onHistoryJump) },
                    onDismiss = { showHistoryDate = false },
                )
            }
            if (showLibrary) {
                FoodLibraryDialog(
                    foodMemory = state.foodMemory,
                    carbsByFood = state.carbsByFood,
                    recentFoodTexts = state.recentFoodTexts,
                    recentFoodCarbs = state.recentFoodCarbs,
                    onDismiss = { showLibrary = false },
                    onChanged = onRefreshRequest,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HistoryFilter.entries.forEachIndexed { i, hf ->
                    androidx.compose.material3.FilterChip(
                        selected = filter == i,
                        onClick = { filter = i },
                        label = { Text(historyFilterLabel(hf)) },
                    )
                }
            }
        }
    // Infinite scroll: when the list is within a few items of the bottom, page
    // in the previous week. Fires once per transition to "near end", so a load
    // that fills the screen simply arms the next page.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val nearEnd by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    androidx.compose.runtime.LaunchedEffect(nearEnd) {
        if (nearEnd) onLoadMoreHistory()
    }
    // Date jump landed a new window — bring the user to its top (the picked
    // date sits near there, newest-first).
    androidx.compose.runtime.LaunchedEffect(state.historyAnchorMs) {
        if (state.historyAnchorMs != null) listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            // The activity already uses adjustResize. A second full IME inset
            // here reserved the keyboard height *inside* the resized viewport,
            // producing the large black band and hiding results. Give the list
            // the remaining height instead; LazyColumn can then scroll every
            // match naturally above the keyboard.
            .weight(1f)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var lastDay = ""
        visibleItems.forEachIndexed { idx, item ->
            val day = dayFmt.format(Date(item.tsMs))
            if (day != lastDay) {
                lastDay = day
                stickyHeader(key = "day_${item.tsMs / 86_400_000}_$idx") {
                    Text(
                        day,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(top = 6.dp, bottom = 2.dp),
                    )
                }
            }
            when (item) {
                is HistoryItem.Meal -> item(key = "m${item.tsMs}") {
                    val meal = item.event
                    // The meal's dose, anchored on EATING time (note ts), not
                    // the detector's onset: BG can react late and the onset
                    // window then grabs a LATER unrelated shot from a
                    // different meal entirely.
                    // Window is +-45 min of eating time; fallback is the
                    // detector's own untimed pairing.
                    // NEVER a sum. Summing every shot in the window and stamping
                    // the nearest one's timing on it asserted a dose that was
                    // never given: two separate shots rendered as one combined
                    // bolus that never existed.
                    // Each real shot is listed as itself. Air is not insulin
                    // delivered (history is the audit view and keeps air shots).
                    val pairedBolus: String? = run {
                        val eatTs = item.note?.tsMs ?: meal.onsetMs
                        fun timing(dMin: Long) = " " + when {
                            dMin <= -2 -> text.getString(R.string.label_screen_timing_before, -dMin)
                            dMin >= 2 -> text.getString(R.string.label_screen_timing_after, dMin)
                            else -> text.getString(R.string.label_screen_timing_with_meal)
                        }
                        val inWindow = state.historyBoluses
                            .filter {
                                // Stored bolus-purpose token, not UI text.
                                !com.diapilot.core.analysis.isPrimePurpose(it.purpose) &&
                                    it.tsMs in (eatTs - 45L * 60_000)..(eatTs + 45L * 60_000)
                            }
                        // ...of which only the ones no OTHER meal sits closer to.
                        val mine = inWindow
                            .filter { b ->
                                foodAnchors.minByOrNull { kotlin.math.abs(it - b.tsMs) } == eatTs
                            }
                            .sortedBy { it.tsMs }
                        when {
                            mine.isNotEmpty() -> mine.joinToString(" · ") {
                                text.getString(R.string.label_screen_bolus_units, it.units) + timing((it.tsMs - eatTs) / 60_000)
                            }
                            // Shots were near, but they belong to a neighbour —
                            // show NOTHING. The detector's own bolusUnits must not
                            // sneak them back in: it pairs by a window too, and can
                            // pair the SAME shot to two nearby meals, which is
                            // exactly the double-count we just removed.
                            inWindow.isNotEmpty() -> null
                            // Nothing near at all: the detector may still have
                            // paired one from its own wider window.
                            else -> meal.bolusUnits?.let { text.getString(R.string.label_screen_bolus_units, it) }
                        }
                    }
                    // Dextrose by label OR by the merged note — a freshly
                    // detected rescue rise isn't labeled yet, but its note
                    // already says "dextrose".
                    val isDextrose = com.diapilot.core.analysis.isRescueNote(item.label ?: item.note?.content)
                    val sysKey = com.diapilot.core.analysis.SysLabels.keyOf(item.label)
                    val emoji = when {
                        isDextrose -> "🍬"
                        sysKey == com.diapilot.core.analysis.SysLabels.DAWN -> "🌅"
                        sysKey == com.diapilot.core.analysis.SysLabels.SPORT -> "💪"
                        sysKey == com.diapilot.core.analysis.SysLabels.CONTINUATION -> "↩"
                        sysKey == com.diapilot.core.analysis.SysLabels.UNKNOWN -> "❓"
                        else -> "🍽"
                    }
                    // ONE tap, ONE modal. The row used to open an
                    // intermediate card that only repeated what the row
                    // already says (rise, bolus, 🧩, ⚡) and hung four buttons
                    // off it; editing the dish then took a SECOND modal. A
                    // note-less rise has nothing to edit, so it opens the
                    // label picker instead — never two steps either way.
                    if (editingMeal == meal.onsetMs) {
                        val note = item.note
                        if (note != null) {
                            androidx.compose.ui.window.Dialog(onDismissRequest = { editingMeal = null }) {
                                AnnotationEditor(
                                    annotation = note,
                                    onSave = { ts, text, media ->
                                        editingMeal = null
                                        onUpdateNote(note, ts, text, media)
                                    },
                                    onDelete = { editingMeal = null; onDeleteNote(note) },
                                    onCancel = { editingMeal = null },
                                    onRepeat = if (note.kind == "food") {
                                        { editingMeal = null; onRepeatFood(note) }
                                    } else null,
                                    onScalePortion = if (note.kind == "food") {
                                        { f -> editingMeal = null; onScaleFoodPortion(note, f) }
                                    } else null,
                                    foodLabels = state.foodLabels,
                                    carbsByFood = state.carbsByFood,
                                    recentFoodTexts = state.recentFoodTexts,
                                    foodMemories = state.foodMemory,
                                    recentFoodCarbs = state.recentFoodCarbs,
                                )
                            }
                        } else {
                            androidx.compose.ui.window.Dialog(onDismissRequest = { editingMeal = null }) {
                                MealCard(
                                    meal, state.topLabels,
                                    onLabel = { name ->
                                        editingMeal = null
                                        val labeled = state.labeled.firstOrNull { it.event.onsetMs == meal.onsetMs }
                                        if (labeled != null) onRelabel(labeled, name) else onLabel(meal, name)
                                    },
                                    currentLabel = item.label,
                                    onCancel = { editingMeal = null },
                                    onDismissMeal = {
                                        editingMeal = null; confirmDismiss = meal.onsetMs
                                    },
                                )
                            }
                        }
                    }
                    // A session-mate is edited from its own modal.
                    item.extraNotes.forEach { n ->
                        if (editingNote == n.id) {
                            androidx.compose.ui.window.Dialog(onDismissRequest = { editingNote = null }) {
                                AnnotationEditor(
                                    annotation = n,
                                    onSave = { ts, text, media ->
                                        editingNote = null
                                        onUpdateNote(n, ts, text, media)
                                    },
                                    onDelete = { editingNote = null; onDeleteNote(n) },
                                    onCancel = { editingNote = null },
                                    onRepeat = if (n.kind == "food") {
                                        { editingNote = null; onRepeatFood(n) }
                                    } else null,
                                    onScalePortion = if (n.kind == "food") {
                                        { f -> editingNote = null; onScaleFoodPortion(n, f) }
                                    } else null,
                                    foodLabels = state.foodLabels,
                                    carbsByFood = state.carbsByFood,
                                    recentFoodTexts = state.recentFoodTexts,
                                    foodMemories = state.foodMemory,
                                    recentFoodCarbs = state.recentFoodCarbs,
                                )
                            }
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { editingMeal = meal.onsetMs }) {
                      Column {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            item.note?.mediaRef?.let {
                                PhotoThumbPublic(it, item.label ?: "")
                                Text("  ")
                            }
                            // Two-tier: bright primary (what & how many carbs),
                            // dim secondary (rise/time/bolus). Dextrose is a
                            // rescue, not a dish — no rise/bolus clutter.
                            val time = timeFmt.format(Date(item.note?.tsMs ?: item.tsMs))
                            Column(Modifier.weight(1f)) {
                                if (isDextrose) {
                                    Text(
                                        "$emoji $time · " + text.getString(R.string.label_screen_hypo_treatment) +
                                            (item.totalCarbs?.let { " · " + text.getString(R.string.label_screen_carbs_approx, it) } ?: ""),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                } else {
                                    Text(
                                        // Headline = the FIRST dish as written (the
                                        // label may be the second course's name from
                                        // auto-labeling); label stays in the editor.
                                        // The other dishes of the meal are listed
                                        // BELOW as their own tappable rows, so the
                                        // headline no longer hides them behind a
                                        // "+1 dish" counter that nothing opened.
                                        "$emoji $time · ${item.note?.content?.let { TokenText.noteTag(context, it) } ?: item.label?.let { FoodText.mealLabel(context, it) } ?: text.getString(R.string.label_screen_not_labeled)}" +
                                            (item.totalCarbs?.let { " · " + text.getString(R.string.label_screen_carbs_approx, it) } ?: ""),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val modelLine = hybridFoodLine(
                                        item.allNotes.mapNotNull { state.hybridFoodReadouts[it.id] },
                                        state.mgdl,
                                        context,
                                    )
                                    Text(
                                        buildString {
                                            append(
                                                modelLine ?: text.getString(R.string.label_screen_food_no_model),
                                            )
                                            pairedBolus?.let { append(" · " + text.getString(R.string.label_screen_bolus_prefix, it)) }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Stage10MealReceipt(item.allNotes.map{it.id})
                                }
                            }
                            // Same right-hand affordances as every other row.
                            TextButton(onClick = {
                                onShowOnChart(item.note?.tsMs ?: meal.onsetMs)
                            }) { Text("📈") }
                            // ⋯ marks a rise still waiting for an answer, and
                            // answers it in one tap. It shows ONLY on unlabeled
                            // ones — that is the point: they must catch the eye
                            // in a list where everything else is settled.
                            //
                            // An already-answered rise ("not sure", "continuation")
                            // gets none: tapping the row opens the same picker,
                            // which is the way to CHANGE an answer (a
                            // "continuation of meal" that was really "not sure").
                            // On a named dish "this is not food: dawn" would be
                            // nonsense anyway, and a
                            // foot-gun: FoodSources skips a DAWN/SPORT/UNKNOWN
                            // meal and the note can't rescue it (the notes loop
                            // skips anything within 45 min of a meal), so the dish
                            // would vanish from the forecast while still sitting
                            // in the history.
                            var moreMenu by remember { mutableStateOf(false) }
                            if (item.note == null && item.label == null) androidx.compose.foundation.layout.Box {
                                TextButton(onClick = { moreMenu = true }) { Text("⋯") }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = moreMenu,
                                    onDismissRequest = { moreMenu = false },
                                ) {
                                    Text(
                                        stringResource(R.string.label_screen_not_food_header),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                    listOf(
                                        "🌅" to com.diapilot.core.analysis.SysLabels.DAWN,
                                        "💪" to com.diapilot.core.analysis.SysLabels.SPORT,
                                        "❓" to com.diapilot.core.analysis.SysLabels.UNKNOWN,
                                        "↩" to com.diapilot.core.analysis.SysLabels.CONTINUATION,
                                    ).forEach { (ic, name) ->
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("$ic " + FoodText.mealLabel(context, name)) },
                                            onClick = {
                                                moreMenu = false
                                                val labeled = state.labeled
                                                    .firstOrNull { it.event.onsetMs == meal.onsetMs }
                                                if (labeled != null) onRelabel(labeled, name)
                                                else onLabel(meal, name)
                                            },
                                        )
                                    }
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.label_screen_false_detection),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = { moreMenu = false; confirmDismiss = meal.onsetMs },
                                    )
                                }
                            }
                        }
                        // The rest of the meal. They used to hide behind a
                        // "+1 dish" counter, and after the modal went away
                        // NOTHING could open them — the second dish became
                        // uneditable. Now each is its own row, one tap to edit,
                        // and the meal still reads as one card (which is also
                        // what the model now learns from — one session).
                        item.extraNotes.forEach { n ->
                            AnnotationRow(
                                n,
                                onClick = { editingNote = n.id },
                                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                            )
                        }
                        // A "continuation of meal" is attached to this parent and
                        // dropped from the list, so it had no row and could NEVER
                        // be re-labelled — a one-way door. A rise could be tagged
                        // "continuation" when "not sure" was the honest answer,
                        // and there was no way back. Now the ↩ line opens the same
                        // picker every other rise gets.
                        item.continuations.forEach { c ->
                            Text(
                                "↩ ${timeFmt.format(Date(c.onsetMs))} · " +
                                    text.getString(
                                        R.string.label_screen_continuation_line,
                                        com.diapilot.core.analysis.fmtBg(c.rise, state.mgdl),
                                        c.timeToPeakMin,
                                    ) +
                                    (c.bolusUnits?.let {
                                        " · " + TokenText.bolusPurpose(context, com.diapilot.core.analysis.BolusPurpose.TOP_UP) + " " +
                                            com.diapilot.core.analysis.fmtOneDecimal(it)
                                    } ?: "") + " ▾",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editingCont = c.onsetMs }
                                    .padding(start = 20.dp, top = 2.dp, bottom = 6.dp),
                            )
                            if (editingCont == c.onsetMs) {
                                androidx.compose.ui.window.Dialog(
                                    onDismissRequest = { editingCont = null },
                                ) {
                                    MealCard(
                                        c, state.topLabels,
                                        onLabel = { name ->
                                            editingCont = null
                                            val lm = state.labeled.firstOrNull { it.event.onsetMs == c.onsetMs }
                                            if (lm != null) onRelabel(lm, name) else onLabel(c, name)
                                        },
                                        currentLabel = com.diapilot.core.analysis.SysLabels.CONTINUATION,
                                        onCancel = { editingCont = null },
                                        onDismissMeal = {
                                            editingCont = null; confirmDismiss = c.onsetMs
                                        },
                                    )
                                }
                            }
                        }
                      }
                    }
                }
                is HistoryItem.Bolus -> item(key = "b${item.tsMs}") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { editingBolus = item.tsMs },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "💉 ${timeFmt.format(Date(item.tsMs))} · " + text.getString(R.string.label_screen_bolus_units, item.point.units) +
                                    (state.bgAtShot[item.tsMs]?.let { " · " + text.getString(R.string.label_screen_at_bg, com.diapilot.core.analysis.fmtBg(it, state.mgdl)) } ?: "") +
                                    (item.point.purpose?.let { " · " + (TokenText.bolusPurpose(context, it) ?: it) } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            TextButton(onClick = { onShowOnChart(item.tsMs) }) { Text("📈") }
                        }
                        if (editingBolus == item.tsMs) {
                            androidx.compose.ui.window.Dialog(onDismissRequest = { editingBolus = null }) {
                                Card {
                                    Column(
                                        Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(
                                            "💉 ${timeFmt.format(Date(item.tsMs))} · " + text.getString(R.string.label_screen_bolus_units, item.point.units),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            stringResource(R.string.label_screen_bolus_purpose_header),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        @OptIn(ExperimentalLayoutApi::class)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            com.diapilot.core.analysis.BolusPurpose.entries.map { it.key }.forEach { pr ->
                                                androidx.compose.material3.FilterChip(
                                                    selected = com.diapilot.core.analysis.BolusPurpose.canonical(item.point.purpose) == pr,
                                                    onClick = { editingBolus = null; onTagBolus(item.tsMs, pr) },
                                                    label = { Text(TokenText.bolusPurpose(context, pr) ?: pr) },
                                                )
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = {
                                                editingBolus = null
                                                editUnitsText = "%.1f".format(item.point.units)
                                                editUnitsFor = item.tsMs
                                            }) { Text(stringResource(R.string.label_screen_edit_dose)) }
                                            TextButton(onClick = {
                                                editingBolus = null; onShowOnChart(item.tsMs)
                                            }) { Text(stringResource(R.string.label_screen_chart)) }
                                            TextButton(onClick = {
                                                editingBolus = null; confirmBolusDelete = item.tsMs
                                            }) { Text("🗑", color = MaterialTheme.colorScheme.error) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is HistoryItem.Basal -> item(key = "bs${item.tsMs}") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            editBasalUnitsText = "%.0f".format(item.point.units)
                            editBasalTs = item.tsMs
                            editBasalFor = item.tsMs
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                // 🌙 — long-acting night shot, visually distinct
                                // from prandial 💉 and meter 🩸.
                                "🌙 ${timeFmt.format(Date(item.tsMs))} · " + text.getString(R.string.label_screen_basal_units, item.point.units) +
                                    (state.bgAtShot[item.tsMs]?.let { " · " + text.getString(R.string.label_screen_at_bg, com.diapilot.core.analysis.fmtBg(it, state.mgdl)) } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            TextButton(onClick = { onShowOnChart(item.tsMs) }) { Text("📈") }
                        }
                    }
                    if (editBasalFor == item.tsMs) {
                        androidx.compose.ui.window.Dialog(onDismissRequest = { editBasalFor = null }) {
                            Card {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(stringResource(R.string.label_screen_basal_label), style = MaterialTheme.typography.titleSmall)
                                    OutlinedTextField(
                                        value = editBasalUnitsText,
                                        onValueChange = { editBasalUnitsText = it },
                                        label = { Text(stringResource(R.string.label_screen_dose_units_label)) },
                                        singleLine = true,
                                    )
                                    val basalFmt = remember { SimpleDateFormat("HH:mm, d MMM", Locale.getDefault()) }
                                    val basalCtx = androidx.compose.ui.platform.LocalContext.current
                                    Text(
                                        "🕐 ${basalFmt.format(Date(editBasalTs))} ✎",
                                        modifier = Modifier.clickable {
                                            pickDateTime(basalCtx, editBasalTs) {
                                                editBasalTs = it
                                            }
                                        },
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        // Delete lives HERE, not on the list row:
                                        // an × sitting under the thumb in a scrolling
                                        // list deletes a shot by accident.
                                        TextButton(onClick = {
                                            editBasalFor = null; confirmBasalDelete = item.tsMs
                                        }) { Text("🗑", color = MaterialTheme.colorScheme.error) }
                                        TextButton(onClick = { editBasalFor = null }) { Text(stringResource(R.string.label_screen_cancel)) }
                                        TextButton(onClick = {
                                            val u = editBasalUnitsText.replace(',', '.').toDoubleOrNull()
                                            if (u != null && u > 0) {
                                                onUpdateBasal(item.tsMs, editBasalTs, u)
                                                editBasalFor = null
                                            }
                                        }) { Text(stringResource(R.string.label_screen_save)) }
                                    }
                                }
                            }
                        }
                    }
                }
                is HistoryItem.Note -> item(key = "n${item.annotation.id}") {
                    (listOf(item.annotation) + item.mates).forEach { n ->
                        if (editingNote == n.id) {
                            androidx.compose.ui.window.Dialog(onDismissRequest = { editingNote = null }) {
                                AnnotationEditor(
                                    annotation = n,
                                    onSave = { ts, text, media ->
                                        editingNote = null
                                        onUpdateNote(n, ts, text, media)
                                    },
                                    onDelete = { editingNote = null; onDeleteNote(n) },
                                    onCancel = { editingNote = null },
                                    onRepeat = if (n.kind == "food") {
                                        { editingNote = null; onRepeatFood(n) }
                                    } else null,
                                    onScalePortion = if (n.kind == "food") {
                                        { f -> editingNote = null; onScaleFoodPortion(n, f) }
                                    } else null,
                                    foodLabels = state.foodLabels,
                                    carbsByFood = state.carbsByFood,
                                    recentFoodTexts = state.recentFoodTexts,
                                    foodMemories = state.foodMemory,
                                    recentFoodCarbs = state.recentFoodCarbs,
                                )
                            }
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                      Column(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AnnotationRow(
                                item.annotation,
                                onClick = { editingNote = item.annotation.id },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onShowOnChart(item.tsMs) }) { Text("📈") }
                        }
                        // Session-mates: the rest of this meal, one card.
                        item.mates.forEach { n ->
                            AnnotationRow(
                                n,
                                onClick = { editingNote = n.id },
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                        // A logged dish exists even when net glucose stayed flat.
                        // Show the food contribution from the same v11 engine as
                        // the forward forecast; do not fall back to the legacy
                        // rise detector or confuse net sensor movement with food.
                        if (com.diapilot.core.analysis.isFoodNote(item.annotation)) {
                            val all = listOf(item.annotation) + item.mates
                            Text(
                                hybridFoodLine(
                                    all.mapNotNull { state.hybridFoodReadouts[it.id] },
                                    state.mgdl,
                                    context,
                                ) ?: text.getString(R.string.label_screen_food_no_model),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                            Stage10MealReceipt(
                                all.map { it.id },
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        (listOf(item.annotation) + item.mates)
                            .mapNotNull { it.estCarbs }
                            .takeIf { item.mates.isNotEmpty() && it.size > 1 }
                            ?.sum()?.let {
                                Text(
                                    text.getString(R.string.label_screen_note_total_carbs, it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                      }
                    }
                }
                is HistoryItem.Meter -> item(key = "mt${item.tsMs}") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            meterText = com.diapilot.core.analysis.fmtBg(item.reading.mmol, state.mgdl)
                            meterTs = item.tsMs
                            editingMeter = item.tsMs
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "🩸 ${timeFmt.format(Date(item.tsMs))} · " + text.getString(R.string.label_screen_meter_label) + " " +
                                    "${com.diapilot.core.analysis.fmtBg(item.reading.mmol, state.mgdl)} " +
                                    unitLabel(state.mgdl),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            TextButton(onClick = { onShowOnChart(item.tsMs) }) { Text("📈") }
                        }
                    }
                }
            }
        }
    }

    // --- Bolus dose editor / delete confirm / meter editor -----------------
    editUnitsFor?.let { ts ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editUnitsFor = null },
            title = { Text(stringResource(R.string.label_screen_dose_units_label)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = editUnitsText,
                    onValueChange = { editUnitsText = it },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editUnitsText.trim().replace(',', '.').toDoubleOrNull()
                        ?.takeIf { it > 0 && it < 100 }?.let { u ->
                            onEditBolusUnits(ts, u)
                            editUnitsFor = null
                        }
                }) { Text(stringResource(R.string.label_screen_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editUnitsFor = null }) { Text(stringResource(R.string.label_screen_cancel)) }
            },
        )
    }
    confirmBolusDelete?.let { ts ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmBolusDelete = null },
            title = { Text(stringResource(R.string.label_screen_delete_bolus_title)) },
            text = { Text(stringResource(R.string.label_screen_delete_bolus_text)) },
            confirmButton = {
                TextButton(onClick = { confirmBolusDelete = null; onDeleteBolus(ts) }) {
                    Text(stringResource(R.string.label_screen_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBolusDelete = null }) { Text(stringResource(R.string.label_screen_cancel)) }
            },
        )
    }
    editingMeter?.let { origTs ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingMeter = null },
            title = { Text(stringResource(R.string.label_screen_meter_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = meterText,
                        onValueChange = { meterText = it },
                        label = { Text(unitLabel(state.mgdl)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(meterTs)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { meterTs -= 30 * 60_000 }) { Text(stringResource(R.string.label_screen_minus_30m)) }
                        TextButton(onClick = {
                            meterTs = (meterTs + 30 * 60_000).coerceAtMost(System.currentTimeMillis())
                        }) { Text(stringResource(R.string.label_screen_plus_30m)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = meterText.trim().replace(',', '.').toDoubleOrNull()
                    val mmol = v?.let {
                        if (state.mgdl) it / com.diapilot.core.analysis.MGDL_PER_MMOL_F else it
                    }
                    if (mmol != null && mmol in 1.0..35.0) {
                        onUpdateMeter(origTs, meterTs, mmol)
                        editingMeter = null
                    }
                }) { Text(stringResource(R.string.label_screen_save)) }
            },
            dismissButton = {
                // Delete moved off the list row: an × under the thumb in a
                // scrolling list deletes a calibration point by accident.
                TextButton(onClick = {
                    editingMeter = null; confirmMeterDelete = origTs
                }) { Text("🗑", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { editingMeter = null }) { Text(stringResource(R.string.label_screen_cancel)) }
            },
        )
    }
    confirmMeterDelete?.let { ts ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmMeterDelete = null },
            title = { Text(stringResource(R.string.label_screen_delete_meter_title)) },
            confirmButton = {
                TextButton(onClick = { confirmMeterDelete = null; onDeleteMeter(ts) }) {
                    Text(stringResource(R.string.label_screen_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMeterDelete = null }) { Text(stringResource(R.string.label_screen_cancel)) }
            },
        )
    }

    confirmBasalDelete?.let { ts ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmBasalDelete = null },
            title = { Text(stringResource(R.string.label_screen_delete_basal_title)) },
            confirmButton = {
                TextButton(onClick = { confirmBasalDelete = null; onDeleteBasal(ts) }) {
                    Text(stringResource(R.string.label_screen_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBasalDelete = null }) { Text(stringResource(R.string.label_screen_cancel)) }
            },
        )
    }

    confirmDismiss?.let { onset ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDismiss = null },
            title = { Text(stringResource(R.string.label_screen_dismiss_title)) },
            text = {
                Text(
                    stringResource(R.string.label_screen_dismiss_text),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDismiss = null
                    editingMeal = null
                    onDismissMeal(onset)
                }) { Text(stringResource(R.string.label_screen_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDismiss = null }) { Text(stringResource(R.string.label_screen_cancel)) }
            },
        )
    }
    }
}


@Composable
private fun MealCard(
    meal: MealEvent,
    topLabels: List<Pair<Long, String>>,
    onLabel: (String) -> Unit,
    currentLabel: String? = null,
    onCancel: (() -> Unit)? = null,
    suggested: String? = null,
    // "Log food…": opens the real food flow (note + editor) instead of
    // typing a naked label; the label then follows the note automatically.
    onLogFood: (() -> Unit)? = null,
    // "This wasn't actually a rise" belongs with the other answers to "what
    // was this?". Without it here a LABELED rise could never be dismissed —
    // the ⋯ that used to carry it now shows only on unlabeled rows.
    onDismissMeal: (() -> Unit)? = null,
) {
    val fmt = remember { SimpleDateFormat("HH:mm, d MMM", Locale.getDefault()) }
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    fmt.format(Date(meal.onsetMs)) +
                        (if (meal.kind == MealEvent.Kind.UNANNOUNCED) " · " + stringResource(R.string.label_screen_no_bolus) else "") +
                        (currentLabel?.let { " · " + stringResource(R.string.label_screen_current_label, FoodText.mealLabel(context, it)) } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.label_screen_cancel)) }
                }
            }
            val mgdl = Units.isMgdl(context)
            Text(
                "${com.diapilot.core.analysis.fmtBg(meal.preBg, mgdl)} → " +
                    "${com.diapilot.core.analysis.fmtBg(meal.peakBg, mgdl)} " +
                    stringResource(R.string.label_screen_bg_rise_duration, unitLabel(mgdl), meal.timeToPeakMin) +
                    (meal.bolusUnits?.let { " · " + stringResource(R.string.label_screen_bolus_dose, it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            // A nearby context note likely names this meal — offer it first.
            suggested?.let { s ->
                androidx.compose.material3.FilledTonalButton(onClick = { onLabel(s) }) {
                    Text(stringResource(R.string.label_screen_suggested_from_note, s))
                }
            }
            // FOOD goes in through the food form, not a bare label: the note
            // carries the dish name, grams, photo — and the label follows it.
            if (onLogFood != null && suggested == null) {
                androidx.compose.material3.FilledTonalButton(onClick = onLogFood) {
                    Text(stringResource(R.string.label_screen_log_food))
                }
            }
            // System categories: the rise is real but it's not a new meal.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SuggestionChip(
                    onClick = { onLabel(com.diapilot.core.analysis.SysLabels.CONTINUATION) },
                    label = { Text("↩ " + FoodText.mealLabel(context, com.diapilot.core.analysis.SysLabels.CONTINUATION)) },
                )
                SuggestionChip(
                    onClick = { onLabel(com.diapilot.core.analysis.SysLabels.DAWN) },
                    label = { Text("🌅 " + FoodText.mealLabel(context, com.diapilot.core.analysis.SysLabels.DAWN)) },
                )
                SuggestionChip(
                    onClick = { onLabel(com.diapilot.core.analysis.SysLabels.SPORT) },
                    label = { Text("💪 " + FoodText.mealLabel(context, com.diapilot.core.analysis.SysLabels.SPORT)) },
                )
                SuggestionChip(
                    onClick = { onLabel(com.diapilot.core.analysis.SysLabels.UNKNOWN) },
                    label = { Text("❓ " + FoodText.mealLabel(context, com.diapilot.core.analysis.SysLabels.UNKNOWN)) },
                )
            }
            onDismissMeal?.let {
                TextButton(onClick = it) {
                    Text(stringResource(R.string.label_screen_false_detection), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Dry-run preview of the label reconcile: each row is "was → will become" with
 * a checkbox. Nothing changes until the user taps Apply — the review is the
 * safety net against a wrong note<->episode link.
 */
@Composable
private fun ReconcileDialog(
    plan: List<com.diapilot.core.analysis.LabelFix>,
    onApply: (List<com.diapilot.core.analysis.LabelFix>) -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    val checked = remember(plan) { mutableStateListOf<Boolean>().apply { repeat(plan.size) { add(true) } } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (plan.isEmpty()) stringResource(R.string.label_screen_reconcile_all_matched) else stringResource(R.string.label_screen_reconcile_title, plan.size)) },
        text = {
            if (plan.isEmpty()) {
                Text(stringResource(R.string.label_screen_reconcile_empty_text))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.label_screen_reconcile_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    plan.forEachIndexed { i, f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = checked[i],
                                onCheckedChange = { checked[i] = it },
                            )
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(
                                    stringResource(R.string.label_screen_reconcile_change, f.from, f.to),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    fmt.format(Date(f.onsetMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (plan.isEmpty()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.label_screen_ok)) }
            } else {
                TextButton(onClick = {
                    onApply(plan.filterIndexed { i, _ -> checked[i] })
                }) { Text(stringResource(R.string.label_screen_apply, checked.count { it })) }
            }
        },
        dismissButton = {
            if (plan.isNotEmpty()) TextButton(onClick = onDismiss) { Text(stringResource(R.string.label_screen_cancel)) }
        },
    )
}

/** One editable component in the decompose preview (Compose-observable). */
private class EditComp(name: String, count: Int, grams: Double?) {
    var name by mutableStateOf(name)
    var count by mutableStateOf(count.coerceAtLeast(1))
    var gramsText by mutableStateOf(grams?.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "")
}

/**
 * Preview of the batch decomposition — each component is EDITABLE (LLM guesses
 * can confuse similarly named dishes): fix the name, count, grams or use ✕ to
 * drop it before applying. The human dish name is NOT changed; only the
 * composition metadata is written. Applies only the approved (checked) meals.
 */
@Composable
private fun DecomposeDialog(
    plan: List<DishDecomp>,
    onApply: (List<DishDecomp>) -> Unit,
    onDismiss: () -> Unit,
) {
    val checked = remember(plan) { mutableStateListOf<Boolean>().apply { repeat(plan.size) { add(true) } } }
    val meals = remember(plan) {
        plan.map { d -> d.components.map { EditComp(it.first, it.second, it.third) }.toMutableStateList() }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (plan.isEmpty()) stringResource(R.string.label_screen_decompose_empty_title) else stringResource(R.string.label_screen_decompose_title, plan.size)) },
        text = {
            if (plan.isEmpty()) {
                Text(
                    stringResource(R.string.label_screen_decompose_empty_text),
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.label_screen_decompose_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    plan.forEachIndexed { i, d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = checked[i],
                                onCheckedChange = { checked[i] = it },
                            )
                            Text(d.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                        if (checked[i]) {
                            meals[i].forEach { comp ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                                ) {
                                    Text("−", modifier = Modifier
                                        .clickable { if (comp.count > 1) comp.count-- }
                                        .padding(horizontal = 4.dp))
                                    Text("${comp.count}", style = MaterialTheme.typography.bodySmall)
                                    Text("＋", modifier = Modifier
                                        .clickable { comp.count++ }
                                        .padding(horizontal = 4.dp))
                                    OutlinedTextField(
                                        value = comp.name,
                                        onValueChange = { comp.name = it },
                                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall,
                                    )
                                    OutlinedTextField(
                                        value = comp.gramsText,
                                        onValueChange = { comp.gramsText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                        modifier = Modifier.width(56.dp),
                                        singleLine = true,
                                        placeholder = { Text(stringResource(R.string.label_screen_grams_placeholder)) },
                                        textStyle = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        " ✕",
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.clickable { meals[i].remove(comp) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (plan.isEmpty()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.label_screen_ok)) }
            } else {
                TextButton(onClick = {
                    val result = plan.mapIndexedNotNull { i, d ->
                        if (!checked[i]) return@mapIndexedNotNull null
                        val comps = meals[i]
                            .map { Triple(it.name.trim(), it.count, it.gramsText.trim().toDoubleOrNull()) }
                            .filter { it.first.isNotEmpty() }
                        if (comps.isEmpty()) null else d.copy(components = comps)
                    }
                    onApply(result)
                }) { Text(stringResource(R.string.label_screen_apply, checked.count { it })) }
            }
        },
        dismissButton = {
            if (plan.isNotEmpty()) TextButton(onClick = onDismiss) { Text(stringResource(R.string.label_screen_cancel)) }
        },
    )
}
