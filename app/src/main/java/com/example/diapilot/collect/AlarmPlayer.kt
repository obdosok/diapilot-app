package com.example.diapilot.collect

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays a hypo alert on the ALARM audio stream and vibrates directly — so it
 * is heard even when the RINGER is silenced (a phone in silent mode still
 * lets alarms through, and this is why xDrip is audible when notifications are
 * not). Notification-channel sound follows the ringer/DND and gets swallowed
 * on silent; this does not.
 *
 * Two intensities: [gentle] (a short buzz, no sound) opens a night alert;
 * [alarm] (looping alarm tone for a few seconds + strong vibration) is the
 * escalation, or the immediate daytime alert. Sound is skipped entirely when
 * the user turned it off — vibration still fires.
 */
object AlarmPlayer {
    private const val TAG = "AlarmPlayer"

    private fun vibrator(context: Context): Vibrator? {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        return v?.takeIf { it.hasVibrator() }
    }

    // Alarm usage — the ONLY way a vibration fires when the ringer is silent
    // (an unqualified vibration follows the ringer mode and gets suppressed,
    // which is why the "test alarm" action felt dead on a muted phone).
    private val alarmAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun vibrate(context: Context, pattern: LongArray) {
        val vib = vibrator(context)
        if (vib == null) {
            Log.w(TAG, "no vibrator available")
            return
        }
        try {
            // Explicit MAX amplitude on the "on" segments — some devices no-op
            // a default-amplitude waveform.
            val amps = IntArray(pattern.size) { i -> if (i % 2 == 1) 255 else 0 }
            val effect = VibrationEffect.createWaveform(pattern, amps, -1)
            @Suppress("DEPRECATION")
            vib.vibrate(effect, alarmAttrs)
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed: ${e.message}")
        }
    }

    /** True if the device has a usable vibrator — for the settings self-test. */
    fun hasVibrator(context: Context): Boolean = vibrator(context) != null

    /** Short buzz, no sound — the first, non-jarring night nudge. */
    fun gentle(context: Context) {
        vibrate(context, longArrayOf(0, 250, 150, 250))
    }

    /**
     * Full alarm: strong vibration + (unless [withSound] is false) the default
     * alarm tone on STREAM_ALARM, looped for [seconds]. Heard on a silent
     * ringer as long as the ALARM volume is up.
     */
    fun alarm(context: Context, withSound: Boolean, seconds: Int = 8) {
        vibrate(context, longArrayOf(0, 500, 250, 500, 250, 700, 250, 700))
        if (!withSound) return
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            // Stop and release after the window — a hypo alert should ring,
            // not ring forever.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    if (mp.isPlaying) mp.stop()
                    mp.release()
                } catch (_: Exception) {}
            }, seconds * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "alarm sound failed: ${e.message}")
        }
    }
}
