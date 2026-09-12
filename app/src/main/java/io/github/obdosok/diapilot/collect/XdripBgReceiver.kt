package io.github.obdosok.diapilot.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.diapilot.core.collector.ACTION_BG
import com.diapilot.core.collector.Reading
import com.diapilot.core.collector.parseBgBroadcast
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.Stores
import io.github.obdosok.diapilot.diag.DiagLog
import io.github.obdosok.diapilot.diag.Redact

/**
 * Receives xDrip BgEstimate broadcasts and persists each glucose reading.
 *
 * Registered twice on purpose:
 *  - in the manifest — works when xDrip is configured with an explicit
 *    receiver list (Identify receiver);
 *  - dynamically by [CollectorService] — receives the ordinary *implicit*
 *    broadcast in the background without touching the user's xDrip
 *    inter-app settings (other consumers like OOPAlgorithm2/WatchDrip
 *    depend on them staying as they are).
 *
 * A garbled broadcast is logged and dropped, never crashes the collector.
 */
class XdripBgReceiver(
    private val onReading: ((Reading) -> Unit)? = null,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reading = handle(context, intent) ?: return
        onReading?.invoke(reading)
    }

    companion object {
        private const val TAG = "XdripBgReceiver"

        /** Parse + store + opportunistic catch-up poll. Returns the reading, if valid. */
        fun handle(context: Context, intent: Intent): Reading? {
            if (intent.action != ACTION_BG) return null
            // NO xDRIP, NO BROADCAST. This action has no permission behind it —
            // with the app that legitimately sends it absent, every arriving
            // intent comes from something else, and glucose is the forecast
            // anchor. There is nothing to ignore selectively here.
            if (!XdripApp.installed(context)) {
                DiagLog.w(TAG, "BgEstimate received while xDrip is not installed — dropped")
                return null
            }
            val extras = intent.extras ?: return null
            val map = extras.keySet().associateWith {
                @Suppress("DEPRECATION")
                extras.get(it)
            }
            val reading = parseBgBroadcast(map, System.currentTimeMillis())
            if (reading == null) {
                // Keys only: a value out of bounds is still a value.
                DiagLog.w(TAG, "BgEstimate broadcast refused, keys=${map.keys}")
                return null
            }
            // Own-BLE mode: DiaPilot is the single glucose source. xDrip's
            // scale differs slightly from the minute calibration — mixing
            // them makes a 5-minute sawtooth and flips the delta sign.
            if (Settings.ownBleEnabled(context)) {
                DiagLog.d(TAG, "own-BLE mode: xDrip reading ignored")
                TreatmentsPollWorker.pollIfStale(context)
                return null
            }
            Stores.get(context).upsertReading(reading)
            DataPulse.pulse()   // release any watch long-poll instantly
            // THE ARRIVAL, NOT THE VALUE (docs/audit.md, S10). The value was
            // printed here at debug level, so a shared bug report carried
            // hours of a stranger's glucose history. What debugging this path
            // needs is that a reading arrived, how old it was and which way it
            // was pointing — the number itself never answered a question.
            DiagLog.i(
                TAG,
                "BG ${Redact.glucose(mgdl = false)} arrived, " +
                    "${Redact.minutesAgo(System.currentTimeMillis(), reading.tsMs)} (${reading.trend})",
            )
            // Each broadcast doubles as a background wake-up: catch up on
            // treatments/pebble if the periodic worker has been dozing.
            TreatmentsPollWorker.pollIfStale(context)
            return reading
        }
    }
}
