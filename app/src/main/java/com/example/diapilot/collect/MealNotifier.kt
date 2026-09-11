package com.example.diapilot.collect

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
import com.example.diapilot.MainActivity
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
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Разметка еды",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Вопросы «что вы ели?» по детекту приёмов пищи" },
        )
    }

    fun notifyMeal(context: Context, meal: MealEvent, topLabels: List<String>) {
        if (!canNotify(context)) return
        ensureChannel(context)
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Похоже, вы поели в ${fmt.format(Date(meal.onsetMs))}")
            .setContentText(
                run {
                    val mgdl = com.example.diapilot.data.Units.isMgdl(context)
                    "${com.diapilot.core.analysis.fmtBg(meal.preBg, mgdl)} → " +
                        "${com.diapilot.core.analysis.fmtBg(meal.peakBg, mgdl)} " +
                        com.diapilot.core.analysis.unitLabel(mgdl)
                } +
                    (meal.bolusUnits?.let { " · болюс %.1f ед".format(it) } ?: " · без болюса") +
                    " — что это было?",
            )
            .setContentIntent(openApp)
            .setAutoCancel(true)

        // Up to two frequent-label buttons…
        topLabels.take(2).forEachIndexed { i, name ->
            builder.addAction(0, name, labelIntent(name, notifId * 10 + i, mutable = false))
        }
        // …plus a free-text reply.
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Другое…", labelIntent(null, notifId * 10 + 9, mutable = true))
                .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Два слова о еде…").build())
                .build(),
        )

        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }

    fun cancel(context: Context, onsetMs: Long) {
        NotificationManagerCompat.from(context).cancel((onsetMs / 60_000).toInt())
    }
}
