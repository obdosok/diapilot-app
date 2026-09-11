package io.github.obdosok.diapilot.collect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.obdosok.diapilot.MainActivity
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.i18n.localized

/**
 * "The stream stopped — bring the phone to the sensor." Today's incident: a
 * BLE nonce desync silently stalled glucose for 40+ minutes and the user
 * learned it from an empty chart. The app must say it out loud instead.
 *
 * Two triggers share this notifier:
 *  - LibreBleClient: the nonce probe walked the full circle without a single
 *    accepted login — reconnecting harder won't help, only an NFC rescan
 *    re-keys the sensor;
 *  - TreatmentsPollWorker: no fresh reading for [STALL_MIN] minutes in
 *    own-BLE mode (catches every other silent failure: BT off, sensor out
 *    of range, dead sensor).
 *
 * Not a glucose alert — an equipment alert. Default-importance channel,
 * 45-min cooldown, auto-noop while data is actually fresh.
 */
object StreamStallNotifier {
    private const val TAG = "StreamStall"
    /**
     * `_v2` — PRECISELY BECAUSE THE CHANNEL'S IMPORTANCE WAS RAISED.
     *
     * Android remembers a channel's importance from the first time it was
     * created and silently ignores it on later `createNotificationChannel`
     * calls with the same id. Raising `IMPORTANCE_DEFAULT` to `HIGH` on the
     * old `stream_stall` id would be an edit that does nothing on a device
     * where that channel already exists — exactly where it matters most. A
     * new id is the only way to make the change take effect.
     *
     * WHY IT WAS RAISED (found by two external reviews). The hypo path stops
     * judging once the anchor is older than 10 minutes, and this notification
     * used to fire at 25 minutes — on a default-importance channel, which is
     * silenced by night mode. So at the most dangerous moment — the app has
     * gone blind — the user got neither sound nor forecast.
     *
     * WHY THE THRESHOLD ITSELF WASN'T LOWERED, even though the 10..25 minute
     * gap remains: measured over a multi-week stretch of real use, stream
     * gaps of 15-25 minutes occur about **3.2 times a day**, and gaps
     * ≥25 minutes about 0.6 times a day. A 15-minute threshold would mean
     * three extra alarms a night — exactly the kind of edit that improves one
     * column while moving the cost somewhere the metric can't see it.
     */
    private const val CHANNEL = "stream_stall_v2"
    /**
     * 3004, not 3002 — the other half of the same id collision (found by
     * external review).
     *
     * `HypoAlertNotifier.NOTIF_ID_HYPER` is also 3002, so "No data from the
     * sensor" used to overwrite the high-glucose warning.
     */
    private const val NOTIF_ID = 3004
    private const val COOLDOWN_MS = 45L * 60_000
    private const val PREF_LAST = "stream_stall_last_ms"
    const val STALL_MIN = 25L

    /** Fire (cooldown-guarded) unless data is actually fresh. [reason] is one
     *  of the `stream_stall_notifier_reason_*` strings. */
    fun maybeNotify(context: Context, @androidx.annotation.StringRes reason: Int) {
        try {
            val store = io.github.obdosok.diapilot.data.Stores.get(context)
            val now = System.currentTimeMillis()
            val freshest = maxOf(
                store.lastSensorReading()?.tsMs ?: 0L,
                store.lastMinuteReading()?.tsMs ?: 0L,
            )
            if (now - freshest < STALL_MIN * 60_000) return   // stream is fine
            val prefs = context.getSharedPreferences(
                TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE,
            )
            if (now - prefs.getLong(PREF_LAST, 0) < COOLDOWN_MS) return
            prefs.edit().putLong(PREF_LAST, now).apply()

            val ageMin = if (freshest > 0) (now - freshest) / 60_000 else -1
            val text = context.localized()
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, text.getString(R.string.stream_stall_notifier_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = text.getString(R.string.stream_stall_notifier_channel_description)
                    // Blindness here isn't cosmetic: while the stream is down, the
                    // hypo alert cannot fire at all. This is the only signal
                    // that can still reach the user at that moment.
                    enableVibration(true)
                },
            )
            nm.notify(
                NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle(
                        if (ageMin > 0) {
                            text.getString(R.string.stream_stall_notifier_title_with_age, ageMin)
                        } else {
                            text.getString(R.string.stream_stall_notifier_title_no_age)
                        },
                    )
                    .setContentText(text.getString(R.string.stream_stall_notifier_body, text.getString(reason)))
                    .setStyle(NotificationCompat.BigTextStyle())
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context, 0, Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .setAutoCancel(true)
                    .build(),
            )
            Log.i(TAG, "stall notified: ${context.resources.getResourceEntryName(reason)} (age=$ageMin min)")
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }
}
