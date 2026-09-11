package io.github.obdosok.diapilot.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart background collection after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CollectorService.start(context)
        TreatmentsPollWorker.schedule(context)
    }
}
