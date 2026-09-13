package io.github.obdosok.diapilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.obdosok.diapilot.data.ModelCalibration
import io.github.obdosok.diapilot.data.ModelCalibrationStatus
import io.github.obdosok.diapilot.i18n.ModelCalibrationText

/**
 * WHOSE NUMBERS THE MODEL RUNS ON, as a card. Composed on Today above the hero
 * number and under More in the Personal model card, from the same
 * [ModelCalibration.Report], so the two never disagree.
 *
 * The example-person state is drawn on the tertiary container — a warning,
 * not an error: nothing is broken, the person has simply not been asked yet,
 * and the reading-driven alerts are working. The other two states are plain.
 */
@Composable
fun ModelCalibrationCard(
    report: ModelCalibration.Report,
    mgdl: Boolean,
    onSetUpModel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null: "Set up" / "Change" by state. Settings passes its own wording. */
    actionLabel: String? = null,
) {
    val context = LocalContext.current
    val warning = report.status == ModelCalibrationStatus.EXAMPLE_PERSON
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (warning) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ModelCalibrationText.title(context, report), style = MaterialTheme.typography.titleSmall)
            Text(ModelCalibrationText.body(context, report, mgdl), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onSetUpModel) {
                Text(actionLabel ?: ModelCalibrationText.action(context, report))
            }
        }
    }
}
