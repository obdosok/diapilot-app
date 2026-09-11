package io.github.obdosok.diapilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.ForecastLedger

/**
 * WHAT THE LINES CANNOT SAY — a strip, deliberately not a dialog.
 *
 * The first version was an `AlertDialog` and it covered the chart completely,
 * which defeats the whole feature: the point is to LOOK at the two forecasts
 * drawn back at that moment, against what actually happened. A modal that hides
 * the evidence it is describing is worse than no panel at all.
 *
 * So this stays at the bottom edge and carries only what a line cannot express:
 * which ISF and insulin curve produced it, how far each horizon missed, and
 * whether a meal was logged after the fact — because that last one decides
 * whether the miss is about the model or about the typing.
 */
@Composable
fun ForecastAtPointStrip(
    run: ForecastLedger.InspectedRun?,
    requestedTsMs: Long,
    mgdl: Boolean,
    replayPoints: Int,
    onDismiss: () -> Unit,
) {
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    fun bg(v: Double) = if (mgdl) "%.0f".format(v * 18.0) else "%.1f".format(v)

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    run?.let {
                        stringResource(
                            R.string.forecast_at_point_strip_title_with_bg,
                            time.format(java.util.Date(requestedTsMs)),
                            bg(it.anchorMmol),
                        )
                    } ?: stringResource(
                        R.string.forecast_at_point_strip_title,
                        time.format(java.util.Date(requestedTsMs)),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.forecast_at_point_strip_hide)) }
            }
            if (run == null) {
                Text(
                    pluralStringResource(R.plurals.forecast_at_point_strip_no_saved, replayPoints, replayPoints),
                    style = MaterialTheme.typography.labelSmall,
                )
                return@Column
            }
            run.applied?.let {
                Text(
                    stringResource(R.string.forecast_at_point_strip_applied, it),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            } ?: Text(
                stringResource(R.string.forecast_at_point_strip_coeffs_missing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Errors only: the predicted and actual values are on the chart, the
            // miss is the number a line cannot show at a glance.
            val missEntry = stringResource(R.string.forecast_at_point_strip_miss_entry)
            val misses = run.points.mapNotNull { p ->
                p.actualMmol?.let { a ->
                    val e = a - p.predictedMmol
                    missEntry.format(p.horizonMin, if (e >= 0) "+" else "", bg(e))
                }
            }
            Text(
                if (misses.isEmpty()) stringResource(R.string.forecast_at_point_strip_no_actual)
                else stringResource(R.string.forecast_at_point_strip_miss_prefix) + misses.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (run.blindMeals > 0) {
                Text(
                    stringResource(R.string.forecast_at_point_strip_late_meal_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!run.algoVersion.startsWith(ForecastLedger.PHYSIO_FAMILY)) {
                Text(
                    stringResource(R.string.forecast_at_point_strip_fallback_twin_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
