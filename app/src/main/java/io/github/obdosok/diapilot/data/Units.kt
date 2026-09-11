package io.github.obdosok.diapilot.data

import android.content.Context
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker

/**
 * Display units toggle. Storage, analytics and the core stay in mmol/L
 * forever — this flag only affects formatting at the presentation edge
 * (screens, notifications, watch, LLM context).
 */
object Units {
    private const val KEY = "units_mgdl"

    fun isMgdl(context: Context): Boolean =
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun setMgdl(context: Context, mgdl: Boolean) {
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, mgdl).apply()
    }
}
