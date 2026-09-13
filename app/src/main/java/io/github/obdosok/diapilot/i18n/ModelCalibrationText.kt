package io.github.obdosok.diapilot.i18n

import android.content.Context
import com.diapilot.core.analysis.fmtBg
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.ModelCalibration
import io.github.obdosok.diapilot.data.ModelCalibrationStatus

/**
 * Renders [ModelCalibration.Report] in the UI language — the same two lines
 * on Today and under More, so the two cards cannot describe the model
 * differently. Works from Compose and from a worker alike.
 */
object ModelCalibrationText {

    fun title(context: Context, report: ModelCalibration.Report): String {
        val res = context.localized()
        return when (report.status) {
            ModelCalibrationStatus.EXAMPLE_PERSON -> res.getString(R.string.calibration_example_title)
            ModelCalibrationStatus.HAND_ENTERED -> res.getString(R.string.calibration_hand_title)
            ModelCalibrationStatus.MEASURED -> res.getString(R.string.calibration_measured_title)
        }
    }

    /**
     * ISF is printed with [fmtBg]: it is a glucose difference per unit, so it
     * follows the display units and the decimal separator like every other
     * glucose number on the screen.
     */
    fun body(context: Context, report: ModelCalibration.Report, mgdl: Boolean): String {
        val res = context.localized()
        val isf = report.isfMmolPerU?.let { "${fmtBg(it, mgdl)} ${unitLabel(mgdl)}" }
        return when (report.status) {
            ModelCalibrationStatus.EXAMPLE_PERSON -> res.getString(R.string.calibration_example_body)
            ModelCalibrationStatus.HAND_ENTERED -> {
                val onset = report.onsetMin
                val peak = report.peakMin
                val tail = report.tailMin
                if (onset != null && peak != null && tail != null) {
                    res.getString(
                        R.string.calibration_hand_body,
                        isf ?: "—", onset.toInt(), peak.toInt(), tail.toInt(),
                    )
                } else {
                    res.getString(R.string.calibration_hand_body_isf_only, isf ?: "—")
                }
            }
            ModelCalibrationStatus.MEASURED -> res.getString(
                R.string.calibration_measured_body,
                report.measuredDoses, report.measuredDays, isf ?: "—",
            )
        }
    }

    fun action(context: Context, report: ModelCalibration.Report): String {
        val res = context.localized()
        return when (report.status) {
            ModelCalibrationStatus.EXAMPLE_PERSON -> res.getString(R.string.calibration_example_action)
            else -> res.getString(R.string.calibration_change)
        }
    }
}
