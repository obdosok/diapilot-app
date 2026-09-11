package com.example.diapilot.i18n

import android.content.Context
import com.diapilot.core.physio.CoercedLandmark
import com.diapilot.core.physio.InsulinLandmark
import com.diapilot.core.physio.InsulinTailFromAmplitudeV1
import com.diapilot.core.physio.LandmarkRefusal
import com.diapilot.core.physio.LandmarkRejection
import com.diapilot.core.physio.OrderingConflict
import com.example.diapilot.R

/** Renders :core's insulin-curve messages (InsulinLandmarkMessages.kt and friends). */
object PhysioText {
    fun landmark(context: Context, l: InsulinLandmark): String = context.localized().getString(
        when (l) {
            InsulinLandmark.ONSET -> R.string.insulin_landmark_onset
            InsulinLandmark.VISIBLE_FALL -> R.string.insulin_landmark_visible_fall
            InsulinLandmark.PEAK -> R.string.insulin_landmark_peak
            InsulinLandmark.ACTIVE_END -> R.string.insulin_landmark_active_end
            InsulinLandmark.SLOWDOWN -> R.string.insulin_landmark_slowdown
            InsulinLandmark.TAIL_END -> R.string.insulin_landmark_tail_end
        },
    )

    /** "end 110→120, peak 30→35" — what the domain bounds moved, comma-separated. */
    fun coerced(context: Context, moved: List<CoercedLandmark>): String {
        val res = context.localized()
        return moved.joinToString(", ") {
            res.getString(R.string.insulin_landmark_coerced, landmark(res, it.landmark), it.fromMin, it.toMin)
        }
    }

    fun landmarkRefusal(context: Context, r: LandmarkRefusal): String {
        val res = context.localized()
        return when (r) {
            LandmarkRefusal.WindowTooShort -> res.getString(R.string.landmark_refusal_window_too_short)
            LandmarkRefusal.LineUnreadable -> res.getString(R.string.landmark_refusal_line_unreadable)
            LandmarkRefusal.NoBreak -> res.getString(R.string.landmark_refusal_no_break)
            is LandmarkRefusal.AllRejected ->
                if (r.causes.isEmpty()) res.getString(R.string.landmark_refusal_all_rejected)
                else res.getString(
                    R.string.landmark_refusal_all_rejected_because,
                    r.causes.joinToString(", ") { rejection(res, it) },
                )
        }
    }

    fun rejection(context: Context, r: LandmarkRejection): String {
        val res = context.localized()
        return when (r.kind) {
            LandmarkRejection.Kind.OUT_OF_ORDER ->
                res.getString(R.string.landmark_rejection_out_of_order, landmark(res, r.landmark))
            LandmarkRejection.Kind.BEYOND_HORIZON ->
                res.getString(R.string.landmark_rejection_beyond_horizon, landmark(res, r.landmark))
            LandmarkRejection.Kind.ONSET_OUT_OF_RANGE -> res.getString(R.string.landmark_rejection_onset_out_of_range)
            LandmarkRejection.Kind.ONSET_DROPPED_FOR_PEAK -> res.getString(R.string.landmark_rejection_onset_dropped_for_peak)
        }
    }

    fun orderingConflict(context: Context, c: OrderingConflict): String {
        val res = context.localized()
        return res.getString(
            R.string.insulin_ordering_conflict,
            landmark(res, c.later), c.laterMin, landmark(res, c.earlier), c.earlierMin,
        )
    }

    fun tailRefusal(context: Context, r: InsulinTailFromAmplitudeV1.TailRefusal): String =
        context.localized().getString(
            when (r) {
                InsulinTailFromAmplitudeV1.TailRefusal.NOT_ENOUGH_DOSES -> R.string.tail_refusal_not_enough_doses
                InsulinTailFromAmplitudeV1.TailRefusal.NEVER_SETTLES -> R.string.tail_refusal_never_settles
            },
        )
}
