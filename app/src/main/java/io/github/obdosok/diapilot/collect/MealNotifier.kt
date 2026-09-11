package io.github.obdosok.diapilot.collect

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.diapilot.core.collector.MealEvent
import io.github.obdosok.diapilot.MainActivity
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.i18n.localized
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Looks like you ate — what was it?" notifications: label a detected meal
 * straight from the shade (frequent-label buttons + free-text reply),
 * without opening the app.
 */
object MealNotifier {

    private const val CHANNEL_ID = "meal_detect"
    const val EXTRA_ONSET_MS = "onset_ms"
    const val EXTRA_LABEL = "label"
    const val KEY_REPLY = "reply_label"

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        val text = context.localized()
        val manager = context.getSystemService(NotificationManager::class.java)
        // Re-creating a channel with the same id updates its name and
        // description, so a language switch reaches them on the next post.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                text.getString(R.string.meal_notifier_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = text.getString(R.string.meal_notifier_channel_description) },
        )
    }

    fun notifyMeal(context: Context, meal: MealEvent, topLabels: List<String>) {
        if (!canNotify(context)) return
        ensureChannel(context)
        // Text from the localized context; system calls keep the original one.
        val text = context.localized()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val notifId = (meal.onsetMs / 60_000).toInt()

        fun labelIntent(label: String?, requestCode: Int, mutable: Boolean): PendingIntent {
            val intent = Intent(context, LabelActionReceiver::class.java).apply {
                action = LabelActionReceiver.ACTION_LABEL
                putExtra(EXTRA_ONSET_MS, meal.onsetMs)
                label?.let { putExtra(EXTRA_LABEL, it) }
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }

        val openApp = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val mgdl = io.github.obdosok.diapilot.data.Units.isMgdl(context)
        val from = com.diapilot.core.analysis.fmtBg(meal.preBg, mgdl)
        val to = com.diapilot.core.analysis.fmtBg(meal.peakBg, mgdl)
        val unit = io.github.obdosok.diapilot.i18n.unitLabel(mgdl)
        val body = meal.bolusUnits
            ?.let { text.getString(R.string.meal_notifier_text_bolus, from, to, unit, it) }
            ?: text.getString(R.string.meal_notifier_text_no_bolus, from, to, unit)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(text.getString(R.string.meal_notifier_title, fmt.format(Date(meal.onsetMs))))
            .setContentText(body)
            .setContentIntent(openApp)
            .setAutoCancel(true)

        // Up to two frequent-label buttons — the user's own labels, shown as stored…
        topLabels.take(2).forEachIndexed { i, name ->
            builder.addAction(0, name, labelIntent(name, notifId * 10 + i, mutable = false))
        }
        // …plus a free-text reply.
        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                text.getString(R.string.meal_notifier_action_other),
                labelIntent(null, notifId * 10 + 9, mutable = true),
            )
                .addRemoteInput(
                    RemoteInput.Builder(KEY_REPLY)
                        .setLabel(text.getString(R.string.meal_notifier_reply_hint))
                        .build(),
                )
                .build(),
        )

        // canNotify() already gated entry to this function, but the lint check
        // for NotificationManagerCompat.notify() wants the permission visibly
        // guarded right here too.
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        }
    }

    fun cancel(context: Context, onsetMs: Long) {
        NotificationManagerCompat.from(context).cancel((onsetMs / 60_000).toInt())
    }
}
