package io.github.obdosok.diapilot.i18n

import android.content.Context
import com.diapilot.core.analysis.Confidence
import com.diapilot.core.analysis.Reject
import com.diapilot.core.analysis.TodBucket
import io.github.obdosok.diapilot.R

/** Renders :core's ISF analysis values (IsfAnalysis.kt). */
object IsfText {
    /** "night 00–06", in the UI language. */
    fun todBucket(context: Context, b: TodBucket): String = context.localized().getString(
        when (b) {
            TodBucket.NIGHT -> R.string.isf_tod_night
            TodBucket.MORNING -> R.string.isf_tod_morning
            TodBucket.DAY -> R.string.isf_tod_day
            TodBucket.EVENING -> R.string.isf_tod_evening
        },
    )

    /** The slot for an English LLM prompt: "night 00–06", whatever the UI language. */
    fun todPromptLabel(b: TodBucket): String =
        "%s %02d–%02d".format(java.util.Locale.ROOT, b.name.lowercase(), b.range.first, b.range.last + 1)

    fun confidence(context: Context, c: Confidence): String = context.localized().getString(
        when (c) {
            Confidence.INSUFFICIENT -> R.string.isf_confidence_insufficient
            Confidence.PRELIMINARY -> R.string.isf_confidence_preliminary
            Confidence.STABLE -> R.string.isf_confidence_stable
        },
    )

    /** A [Reject] code as text; an unknown code comes back as itself. */
    fun reject(context: Context, code: String): String {
        val id = when (code) {
            Reject.DOSE_TOO_SMALL -> R.string.isf_reject_dose_too_small
            Reject.ACTIVITY_IN_WINDOW -> R.string.isf_reject_activity_in_window
            Reject.DOSE_TOO_LARGE -> R.string.isf_reject_dose_too_large
            Reject.OTHER_BOLUS_IN_WINDOW -> R.string.isf_reject_other_bolus_in_window
            Reject.FOOD_IN_WINDOW -> R.string.isf_reject_food_in_window
            Reject.NO_PRE_BOLUS_READINGS -> R.string.isf_reject_no_pre_bolus_readings
            Reject.START_BG_TOO_LOW -> R.string.isf_reject_start_bg_too_low
            Reject.INSUFFICIENT_CGM_COVERAGE -> R.string.isf_reject_insufficient_cgm_coverage
            Reject.HYPO_IN_WINDOW -> R.string.isf_reject_hypo_in_window
            Reject.NO_END_WINDOW_READINGS -> R.string.isf_reject_no_end_window_readings
            Reject.LIKELY_UNLOGGED_FOOD -> R.string.isf_reject_likely_unlogged_food
            else -> return code
        }
        return context.localized().getString(id)
    }
}
