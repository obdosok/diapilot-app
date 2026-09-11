package io.github.obdosok.diapilot.data

import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.twin.Corridor
import com.diapilot.core.twin.classifyRegime

/**
 * The corridor for RIGHT NOW: classify the current regime (backward-looking
 * — night / activity / post-meal / post-bolus / quiet) and take its
 * calibrated width; regimes without enough history inherit the global one.
 * One helper so the header, watch, widget and hypo alert never disagree
 * about how sure the model claims to be.
 */
object CorridorPick {
    fun now(store: CollectorStore, model: TwinCache.Model, nowMs: Long): Corridor {
        val rc = model.corridors ?: return model.corridor
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val regime = classifyRegime(
            tsMs = nowMs,
            mealOnsetsMs = store.meals(nowMs - 3L * 3_600_000, nowMs).map { it.onsetMs },
            boluses = store.boluses(nowMs - 3L * 3_600_000, nowMs),
            activityWindows = model.activityWindows,
            hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
        )
        return rc.forRegime(regime)
    }
}
