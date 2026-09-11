package com.example.diapilot.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.diapilot.core.collector.ACTION_BG
import com.diapilot.core.collector.Reading
import com.diapilot.core.collector.parseBgBroadcast
import com.example.diapilot.data.Stores

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
            val extras = intent.extras ?: return null
            val map = extras.keySet().associateWith {
                @Suppress("DEPRECATION")
                extras.get(it)
            }
            val reading = parseBgBroadcast(map)
            if (reading == null) {
                Log.w(TAG, "Unparsed BgEstimate broadcast, keys=${map.keys}")
                return null
            }
            // Own-BLE mode: DiaPilot is the single glucose source. xDrip's
            // scale differs slightly from the minute calibration — mixing
            // them makes a 5-minute sawtooth and flips the delta sign.
            if (com.example.diapilot.data.Settings.ownBleEnabled(context)) {
                Log.d(TAG, "own-BLE mode: xDrip reading ignored")
                TreatmentsPollWorker.pollIfStale(context)
                return null
            }
            Stores.get(context).upsertReading(reading)
            DataPulse.pulse()   // release any watch long-poll instantly
            Log.d(TAG, "BG ${"%.1f".format(reading.mmol)} mmol/L @ ${reading.tsMs} (${reading.trend})")
            // Each broadcast doubles as a background wake-up: catch up on
            // treatments/pebble if the periodic worker has been dozing.
            TreatmentsPollWorker.pollIfStale(context)
            return reading
        }
    }
}
