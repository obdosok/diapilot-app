package io.github.obdosok.diapilot.collect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.diapilot.core.analysis.RapidFall
import io.github.obdosok.diapilot.MainActivity
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.i18n.localized
import io.github.obdosok.diapilot.i18n.unitLabel

/**
 * Early rapid-fall alert from the per-minute stream — fires minutes before
 * 5-minute alarms can. High-importance channel (sound); observation and
 * projection only, no instructions.
 */
object RapidFallNotifier {

    private const val CHANNEL_ID = "rapid_fall"
    /**
     * 3005 — THE THIRD ID TRIED, BECAUSE THE FIRST TWO WERE ALREADY TAKEN.
     *
     * 3001 collided with `HypoAlertNotifier.NOTIF_ID`, and `notify()` with the
     * same id does not add a notification, it REPLACES it: "Glucose is
     * dropping fast" overwrote the text of a live hypo alert, the sound still
     * fired, but the text read was the last one published, and `autoCancel`
     * dismissed both with one tap.
     *
     * Moving to 3003 landed on `NOTIF_ID_WATCH_TEST`, which is worse: the
     * watch-signal test starts with a `cancel` of that id, so it SILENCED a
     * live "Glucose is dropping fast" alert. Found by an external review, the
     * same day this id was assigned.
     *
     * Ids taken by this family of notifications: 3001 hypo, 3002 hyper, 3003
     * watch test, 3004 stream stall, 3005 rapid fall. Before picking the next
     * one, recount all of them: two collisions in a row happened precisely
     * because the list was kept in someone's head instead of written down here.
     */
    private const val NOTIF_ID = 3005
    const val PREF_LAST_FALL_NOTIF = "last_fall_notif_ms"
    private const val DEBOUNCE_MS = 30L * 60_000

    fun maybeNotify(context: Context, fall: RapidFall) {
        if (!MealNotifier.canNotify(context)) return
        val prefs = context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(PREF_LAST_FALL_NOTIF, 0)
        if (System.currentTimeMillis() - last < DEBOUNCE_MS) return

        ensureChannel(context)
        val localizedText = context.localized()
        val title = if (fall.urgent) {
            localizedText.getString(R.string.rapid_fall_notifier_title_urgent)
        } else {
            localizedText.getString(R.string.rapid_fall_notifier_title)
        }
        val mgdl = io.github.obdosok.diapilot.data.Units.isMgdl(context)
        val slope = if (mgdl) {
            "%.0f %s".format(fall.slopePerMin * com.diapilot.core.analysis.MGDL_PER_MMOL_F, unitLabel(true))
        } else {
            "%.2f %s".format(fall.slopePerMin, unitLabel(false))
        }
        val text = localizedText.getString(
            R.string.rapid_fall_notifier_text,
            com.diapilot.core.analysis.fmtBg(fall.currentMmol, mgdl),
            slope,
            com.diapilot.core.analysis.fmtBg(fall.projected20Mmol.coerceAtLeast(2.0), mgdl),
        )
        val openApp = PendingIntent.getActivity(
            context, NOTIF_ID, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // MealNotifier.canNotify() already gated entry to this function, but
        // the lint check for NotificationManagerCompat.notify() wants the
        // permission visibly guarded right here too.
        if (android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(
                NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        prefs.edit().putLong(PREF_LAST_FALL_NOTIF, System.currentTimeMillis()).apply()
    }

    private fun ensureChannel(context: Context) {
        val text = context.localized()
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, text.getString(R.string.rapid_fall_notifier_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = text.getString(R.string.rapid_fall_notifier_channel_desc)
            },
        )
    }
}
