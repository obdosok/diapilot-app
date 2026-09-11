package io.github.obdosok.diapilot.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import io.github.obdosok.diapilot.data.Stores
import java.util.concurrent.Executors

/** Handles label taps and free-text replies from meal notifications. */
class LabelActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LABEL) return
        val onsetMs = intent.getLongExtra(MealNotifier.EXTRA_ONSET_MS, 0)
        if (onsetMs == 0L) return

        val label = intent.getStringExtra(MealNotifier.EXTRA_LABEL)
            ?: RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(MealNotifier.KEY_REPLY)?.toString()
        if (label.isNullOrBlank()) return

        val pending = goAsync()
        EXECUTOR.execute {
            try {
                val store = Stores.get(context)
                store.setMealLabel(onsetMs, store.getOrCreateLabel(label.trim()))
                MealNotifier.cancel(context, onsetMs)
                Log.d(TAG, "Labeled meal @$onsetMs as '$label' from notification")
            } catch (e: Exception) {
                Log.w(TAG, "Labeling from notification failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LABEL = io.github.obdosok.diapilot.AppIdentity.ACTION_LABEL
        private const val TAG = "LabelActionReceiver"
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
