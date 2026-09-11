package io.github.obdosok.diapilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diapilot.core.analysis.GlycemicStats
import com.diapilot.core.analysis.glycemicStats
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.HybridModelStore
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.SqliteCollectorStore
import io.github.obdosok.diapilot.data.Stores
import io.github.obdosok.diapilot.i18n.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Product-facing analysis.
 *
 * This deliberately reads only 30 days of glucose plus the already-scored
 * prospective ledgers. Opening a tab must not retrain physiology, run
 * walk-forward folds, or replay the old forecasting engine.
 */
@Composable
fun AnalysisScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var data by remember { mutableStateOf<AnalysisOverview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // `ClosedEpisodeRuntime.updates` was retired along with the pipeline it
    // fed; the screen no longer recomposes from its generation.

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                val store = Stores.get(context)
                val now = System.currentTimeMillis()
                val from30 = now - 30L * 24 * 60 * 60_000
                val from7 = now - 7L * 24 * 60 * 60_000
                val readings = store.sensorReadings(from30, now)
                val lo = Settings.rangeLoMmol(context)
                val hi = Settings.rangeHiMmol(context)
                val db = (store as? SqliteCollectorStore)?.readableDatabase
                // Analysis is a read-only projection. Learning/promotion is
                // maintained by the normal causal runtime, never by rendering.
                val artifact=io.github.obdosok.diapilot.data.PhysioRuntime.artifact(store,now)
                val rescueEvents=com.diapilot.core.analysis.rescueEvents(store.annotations(from30,now),from30,now)
                val rescueSafety=com.diapilot.core.analysis.rescueSafetyEpisodesV1(rescueEvents,readings,store.boluses(from30,now))
                // Same computation the model applies: the card calls
                // AdaptiveIsfRuntime.balance instead of computing its own balance.
                val balance=io.github.obdosok.diapilot.data.AdaptiveIsfRuntime.balance(store,now,windowDays=14)
                val balanceCarbSens=io.github.obdosok.diapilot.data.AdaptiveIsfRuntime.carbSens(store,now)
                AnalysisOverview(
                    week = glycemicStats(readings.filter { it.tsMs >= from7 }, lo, hi),
                    month = glycemicStats(readings, lo, hi),
                    model = HybridModelStore.status(context),
                    hybridEnabled = true,
                    today = db?.let { io.github.obdosok.diapilot.data.DailyDiscrepancyRuntime.view(it, now, store = store, context = context) },
                    rescueSafety=rescueSafety,
                    balance=balance,
                    balanceCarbSens=balanceCarbSens,
                )
            }
        }.onSuccess { data = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.analysis_overview_screen_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.analysis_overview_screen_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (error != null) {
            item {
                OverviewCard(stringResource(R.string.analysis_overview_screen_metrics_failed)) { Text(error!!) }
            }
        } else if (data == null) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.analysis_overview_screen_loading),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val snapshot = data!!
            item {
                OverviewCard(stringResource(R.string.analysis_overview_screen_glucose_card_title)) {
                    StatsRow(stringResource(R.string.analysis_overview_screen_days_7), snapshot.week)
                    StatsRow(stringResource(R.string.analysis_overview_screen_days_30), snapshot.month)
                }
            }
            item {
                ModelCard(snapshot)
            }
            item { TodayChangeCard(snapshot.today) }
            item { RescueSafetyCard(snapshot.rescueSafety) }
            item { DailyBalanceCard(snapshot.balance, snapshot.balanceCarbSens) }
            item {
            }
            item {
            }
            item {
                Text(
                    stringResource(R.string.analysis_overview_screen_backtests_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class AnalysisOverview(
    val week: GlycemicStats?,
    val month: GlycemicStats?,
    val model: HybridModelStore.Status?,
    val hybridEnabled: Boolean,
    val today: io.github.obdosok.diapilot.data.TodayDiscrepancyView?,
    val rescueSafety:List<com.diapilot.core.analysis.RescueSafetyEpisodeV1>,
    val balance: com.diapilot.core.physio.DailyBalanceIsfV1.Result?,
    val balanceCarbSens: Double?,
)

/**
 * DAILY BALANCE — the card that replaced "Closed causal segments".
 *
 * That one asked the same question — "what ISF would close the arithmetic" —
 * but PER SEGMENT, and its own comment admitted that at that scale meals,
 * activity, hepatic output and sensor error are observationally
 * interchangeable with insulin amplitude. It also subtracted the MODEL's
 * carb contribution, and the model delivers roughly a quarter less glucose
 * than a real meal does, so its "reconciling ISF" was systematically biased
 * high by that same amount.
 *
 * Here the same balance is computed per DAY with a 04:00 boundary, on raw
 * grams and units: over a full day the timing integrates out and most of
 * that interchangeability goes away.
 */
@Composable private fun DailyBalanceCard(
    result: com.diapilot.core.physio.DailyBalanceIsfV1.Result?,
    carbSens: Double?,
) {
    OverviewCard(stringResource(R.string.analysis_overview_screen_daily_balance_title)) {
        Text(
            stringResource(R.string.analysis_overview_screen_daily_balance_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result == null) {
            Text(
                pluralStringResource(
                    R.plurals.analysis_overview_screen_daily_balance_empty,
                    com.diapilot.core.physio.DailyBalanceIsfV1.MIN_DAYS,
                    com.diapilot.core.physio.DailyBalanceIsfV1.MIN_DAYS,
                    com.diapilot.core.physio.DailyBalanceIsfV1.MIN_CARBS_G.toInt(),
                    com.diapilot.core.physio.DailyBalanceIsfV1.MIN_UNITS.toInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            return@OverviewCard
        }
        val stamp = java.text.SimpleDateFormat("dd.MM", java.util.Locale.getDefault())
        Text(
            "%-7s %6s %6s %7s %6s %6s".format(
                stringResource(R.string.analysis_overview_screen_daily_balance_col_day),
                stringResource(R.string.analysis_overview_screen_daily_balance_col_carbs),
                stringResource(R.string.analysis_overview_screen_daily_balance_col_units),
                stringResource(R.string.analysis_overview_screen_daily_balance_col_delta),
                stringResource(R.string.analysis_overview_screen_daily_balance_col_g_per_u),
                stringResource(R.string.analysis_overview_screen_daily_balance_col_isf),
            ),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        result.rows.forEach { d ->
            Text(
                "%-7s %6.0f %6.1f %+7.2f %6.1f %6.2f".format(
                    java.util.Locale.ROOT,
                    stamp.format(java.util.Date(d.startMs)), d.carbs, d.units, d.deltaMmol,
                    d.gramsPerUnit, d.isf(carbSens ?: 0.0),
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        // Numbers are pre-formatted with Locale.ROOT so the decimal point stays
        // consistent regardless of UI language, matching the table above.
        Text(
            pluralStringResource(
                R.plurals.analysis_overview_screen_daily_balance_median,
                result.days,
                result.days,
                "%.2f".format(java.util.Locale.ROOT, result.isf),
                "%.2f".format(java.util.Locale.ROOT, result.spread?.first ?: Double.NaN),
                "%.2f".format(java.util.Locale.ROOT, result.spread?.second ?: Double.NaN),
                "%.3f".format(java.util.Locale.ROOT, carbSens ?: Double.NaN),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.analysis_overview_screen_daily_balance_footnote),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable private fun RescueSafetyCard(rows:List<com.diapilot.core.analysis.RescueSafetyEpisodeV1>) {
    val prevented=rows.filter{it.outcome==com.diapilot.core.analysis.RescueOutcomeV1.AVERTED_LOW_COMPATIBLE}
    val context = LocalContext.current
    OverviewCard(stringResource(R.string.analysis_overview_screen_rescue_safety_title)) {
        Text(stringResource(R.string.analysis_overview_screen_rescue_safety_intro),style=MaterialTheme.typography.bodySmall)
        Text(
            pluralStringResource(R.plurals.analysis_overview_screen_rescue_safety_summary, rows.size, rows.size, prevented.size),
            fontWeight=FontWeight.SemiBold,
        )
        prevented.takeLast(5).asReversed().forEach{r->
            val whenText=java.text.SimpleDateFormat(
                "d MMM, HH:mm",
                context.localized().resources.configuration.locales[0],
            ).format(java.util.Date(r.rescue.tsMs))
            Text(
                stringResource(
                    R.string.analysis_overview_screen_rescue_safety_row,
                    whenText,
                    "%.0f".format(r.rescue.grams),
                    r.glucoseAtRescue?.let{"%.1f".format(it)}?:"?",
                    r.preSlopeMmolPerMin?.let{"%+.2f".format(it)}?:"?",
                    "%.1f".format(r.activeRecentBolusUnits),
                ),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.analysis_overview_screen_rescue_safety_footnote),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary,
        )
    }
}



@Composable
private fun humanEpisodeStatus(s:String)=when(s){
    "IDENTIFIED_ISF"->stringResource(R.string.analysis_overview_screen_episode_status_identified_isf)
    "EFFECTIVE_RESPONSE_ONLY"->stringResource(R.string.analysis_overview_screen_episode_status_effective_response_only)
    "CARB_ERROR_CANDIDATE"->stringResource(R.string.analysis_overview_screen_episode_status_carb_error_candidate)
    "TIMING_MISMATCH"->stringResource(R.string.analysis_overview_screen_episode_status_timing_mismatch)
    "SENSOR_OR_PROCESS"->stringResource(R.string.analysis_overview_screen_episode_status_sensor_or_process)
    "RETROSPECTIVE_CORRECTION"->stringResource(R.string.analysis_overview_screen_episode_status_retrospective_correction)
    "CENSORED"->stringResource(R.string.analysis_overview_screen_episode_status_censored)
    else->stringResource(R.string.analysis_overview_screen_episode_status_unexplained)
}



@Composable
private fun TodayChangeCard(today: io.github.obdosok.diapilot.data.TodayDiscrepancyView?) {
    OverviewCard(stringResource(R.string.analysis_overview_screen_today_card_title)) {
        if (today == null) {
            Text(stringResource(R.string.analysis_overview_screen_today_no_checkpoints))
        } else {
            Text(stringResource(R.string.analysis_overview_screen_today_what_changed), fontWeight = FontWeight.SemiBold)
            Text(today.effectiveLine); Text(today.identifiedIsfLine)
            Text(today.kineticsLine)
            Text(stringResource(R.string.analysis_overview_screen_today_what_explains), fontWeight = FontWeight.SemiBold)
            Text(today.explainedLine)
            today.contributionRows.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.analysis_overview_screen_today_what_unexplained), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.analysis_overview_screen_today_unallocated))
            Text(stringResource(R.string.analysis_overview_screen_today_mixed_data), fontWeight = FontWeight.SemiBold)
            Text(today.mixedDataLine)
            Text(stringResource(R.string.analysis_overview_screen_today_timeline), fontWeight = FontWeight.SemiBold)
            today.timeline.forEach { Text(it) }
        }
    }
}


@Composable
private fun humanConclusion(r:com.diapilot.core.physio.FactorEvidenceV1)=when(r.status){
    com.diapilot.core.physio.EvidenceStatus.SOURCE_MISSING->stringResource(R.string.analysis_overview_screen_evidence_source_missing)
    com.diapilot.core.physio.EvidenceStatus.NO_EXPOSURES->stringResource(R.string.analysis_overview_screen_evidence_no_exposures)
    com.diapilot.core.physio.EvidenceStatus.NOT_IDENTIFIABLE->stringResource(R.string.analysis_overview_screen_evidence_not_identifiable)
    com.diapilot.core.physio.EvidenceStatus.INSUFFICIENT->stringResource(R.string.analysis_overview_screen_evidence_insufficient)
    com.diapilot.core.physio.EvidenceStatus.NULL_COMPATIBLE->stringResource(R.string.analysis_overview_screen_evidence_null_compatible)
    com.diapilot.core.physio.EvidenceStatus.HYPOTHESIS->stringResource(R.string.analysis_overview_screen_evidence_hypothesis)
    com.diapilot.core.physio.EvidenceStatus.PRELIMINARY->stringResource(R.string.analysis_overview_screen_evidence_preliminary)
    com.diapilot.core.physio.EvidenceStatus.SUPPORTED->stringResource(R.string.analysis_overview_screen_evidence_supported)
    com.diapilot.core.physio.EvidenceStatus.CONFLICTING->stringResource(R.string.analysis_overview_screen_evidence_conflicting)
}

@Composable
private fun collectionHint(id:String)=when(id){
    "protein_absorption","fat_absorption"->stringResource(R.string.analysis_overview_screen_hint_absorption)
    "duration_absorption"->stringResource(R.string.analysis_overview_screen_hint_duration_absorption)
    "alcohol_background"->stringResource(R.string.analysis_overview_screen_hint_alcohol_background)
    "cartridge_kinetics"->stringResource(R.string.analysis_overview_screen_hint_cartridge_kinetics)
    "injection_site"->stringResource(R.string.analysis_overview_screen_hint_injection_site)
    "cartridge_heat"->stringResource(R.string.analysis_overview_screen_hint_cartridge_heat)
    "stress_illness_background"->stringResource(R.string.analysis_overview_screen_hint_stress_illness_background)
    else->stringResource(R.string.analysis_overview_screen_hint_default)
}

@Composable
private fun measurementDescription(id:String)=when(id){
    "activity_same_isf"->stringResource(R.string.analysis_overview_screen_measurement_activity_same_isf)
    "activity_same_effective"->stringResource(R.string.analysis_overview_screen_measurement_activity_same_effective)
    else->null
}



@Composable
private fun OverviewCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun StatsRow(label: String, stats: GlycemicStats?) {
    if (stats == null) {
        Text(
            stringResource(R.string.analysis_overview_screen_stats_no_data, label),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.analysis_overview_screen_stats_mean, stats.mean),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            Text(
                stringResource(R.string.analysis_overview_screen_stats_in_range, stats.inRangePct),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.analysis_overview_screen_stats_below_above, stats.belowPct, stats.abovePct),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelCard(snapshot: AnalysisOverview) {
    OverviewCard(stringResource(R.string.analysis_overview_screen_model_card_title)) {
        val model = snapshot.model
        if (model == null) {
            Text(stringResource(R.string.analysis_overview_screen_model_not_installed))
        } else {
            Text(stringResource(R.string.analysis_overview_screen_model_in_use))
            Text(
                pluralStringResource(
                    R.plurals.analysis_overview_screen_model_trained,
                    model.trainingDays,
                    model.trainingDays,
                    model.trainedThrough ?: "—",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${model.modelVersion} · ${model.activeSha256.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


