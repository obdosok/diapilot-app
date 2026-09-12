package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.analysis.MinuteCalibration
import com.diapilot.core.analysis.fitMinuteCalibration
import com.diapilot.core.collector.CollectorStore
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker

/**
 * Rolling minute→main calibration, refit at most every [TTL_MS] over the
 * last 24 h of paired points.
 *
 * Retirement plan: while xDrip still broadcasts calibrated readings, the
 * fit keeps learning and every good fit is PERSISTED. Once xDrip is gone
 * (no fresh pairs), the frozen coefficients keep the minute stream on the
 * calibrated scale for up to a sensor's lifetime; drift correction is the
 * meter-check lens's job from then on.
 */
object MinuteCalCache {
    private const val TTL_MS = 10L * 60_000
    private const val FROZEN_MAX_AGE_MS = 14L * 24 * 3_600_000

    @Volatile
    private var cached: MinuteCalibration? = null

    @Volatile
    private var builtAtMs = 0L

    @Synchronized
    fun get(store: CollectorStore, context: Context): MinuteCalibration? {
        val now = System.currentTimeMillis()
        if (now - builtAtMs < TTL_MS) return cached
        val fresh = fitMinuteCalibration(
            // Sensor-only main points: pairing raw OOP2 against a fingerstick
            // instead of the simultaneous CGM would teach a wrong slope/intercept.
            main = store.sensorReadings(now - 24L * 3_600_000, now),
            minute = store.minuteReadings(now - 24L * 3_600_000, now),
        )
        cached = if (fresh != null) {
            persist(context, fresh, now)
            fresh
        } else {
            loadFrozen(context, now)
        }
        builtAtMs = now
        return cached
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE,
        )

    private fun persist(context: Context, cal: MinuteCalibration, now: Long) {
        prefs(context).edit()
            .putFloat("minutecal_slope", cal.slope.toFloat())
            .putFloat("minutecal_intercept", cal.intercept.toFloat())
            .putInt("minutecal_n", cal.n)
            .putFloat("minutecal_mad", cal.madMmol.toFloat())
            .putLong("minutecal_ts", now)
            .apply()
    }

    private fun loadFrozen(context: Context, now: Long): MinuteCalibration? {
        val p = prefs(context)
        val ts = p.getLong("minutecal_ts", 0L)
        if (ts == 0L || now - ts > FROZEN_MAX_AGE_MS) return null
        val slope = p.getFloat("minutecal_slope", 0f).toDouble()
        if (slope == 0.0) return null
        return MinuteCalibration(
            slope = slope,
            intercept = p.getFloat("minutecal_intercept", 0f).toDouble(),
            n = p.getInt("minutecal_n", 0),
            madMmol = p.getFloat("minutecal_mad", 0f).toDouble(),
        )
    }
}
