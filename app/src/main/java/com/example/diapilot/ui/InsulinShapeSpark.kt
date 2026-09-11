package com.example.diapilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diapilot.core.hybrid.HybridCdfKnot
import com.example.diapilot.R

/**
 * The insulin shape actually in force, drawn as the RATE of action.
 *
 * The rate is what the user reads off the sensor — "the drop starts around
 * minute 25–30, speeds up, then fades after 90" — while the CDF the model
 * stores is its integral and says nothing legible to the eye. Drawing the
 * stored curve directly would be honest and unreadable at once.
 *
 * This must be fed the SAME knots the model runs. A picture assembled from
 * separate landmark numbers would drift from the curve behind it, and that is
 * exactly the class of error this project has paid for repeatedly.
 */
@Composable
fun InsulinShapeSpark(
    knots: List<HybridCdfKnot>,
    onsetMin: Double?,
    peakMin: Double?,
    modifier: Modifier = Modifier,
) {
    if (knots.size < 3) return
    val line = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val span = knots.last().minute - knots.first().minute
    if (span <= 0.0) return
    // Rate on the completed bin, placed at its right edge — the same
    // convention the estimator uses for its peak landmark.
    val rates = knots.zipWithNext().map { (a, b) ->
        b.minute to (b.fraction - a.fraction) / (b.minute - a.minute).coerceAtLeast(1e-9)
    }
    val top = rates.maxOf { it.second }.takeIf { it > 0.0 } ?: return

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            fun x(minute: Double) = (((minute - knots.first().minute) / span) * size.width).toFloat()
            fun y(rate: Double) = (size.height - (rate / top) * size.height * .88f).toFloat()
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            // Every hour, so the horizontal axis can be read without a label.
            var hour = 60.0
            while (hour < knots.last().minute) {
                drawLine(grid, Offset(x(hour), 0f), Offset(x(hour), size.height), 1f)
                hour += 60.0
            }
            listOfNotNull(onsetMin, peakMin).forEach {
                drawLine(marker.copy(alpha = .55f), Offset(x(it), 0f), Offset(x(it), size.height), 2f)
            }
            val path = Path().apply {
                moveTo(x(knots.first().minute), size.height)
                rates.forEach { (minute, rate) -> lineTo(x(minute), y(rate)) }
            }
            drawPath(path, line, style = Stroke(width = 3f))
            drawPath(
                Path().apply {
                    addPath(path)
                    lineTo(x(knots.last().minute), size.height)
                    close()
                },
                Color(line.red, line.green, line.blue, .12f),
            )
        }
        Text(
            stringResource(R.string.insulin_shape_spark_caption, knots.last().minute),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
