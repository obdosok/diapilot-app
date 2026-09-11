package com.example.diapilot.data

import android.content.Context
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.Reading

/**
 * ONE SCALE FOR EVERY CONSUMER — the meter lens, outside the main screen.
 *
 * Reported by the user: "the phone alerts that say glucose is dropping seem to
 * be reading an uncalibrated value, and it's lower than the real one".
 *
 * The user was right, and it was worse than a cosmetic mismatch. `MeterCalCache`
 * — the lens that turns the sensor's scale into the meter's blood scale — was
 * applied in `MainState.kt` and NOWHERE ELSE. The rapid-fall notification, the
 * predictive hypo alert and the watch all read the RAW sensor.
 *
 * MEASURED against real fingersticks, several pairs within ten minutes of a
 * sensor reading: the meter reads **noticeably higher** than the sensor at the
 * median, and higher in most of the pairs. In the range that matters it is
 * worse, because that is where checks tend to cluster: near hypo range, the
 * meter-to-sensor gap runs a mmol or more.
 *
 * So the alarms were firing on a number well below the truth, and printing
 * that number to the user. Both halves are harmful and in the same direction:
 * alarms too eager, and a figure that disagrees with the app's own screen —
 * which teaches the user to distrust whichever one they are looking at.
 *
 * This is deliberately a thin shared helper rather than a fix pasted into three
 * files: the failure was one code path owning a correction that four consumers
 * needed, and pasting it would rebuild the same trap with more copies.
 *
 * NOT applied to a fingerstick. A meter value is already the blood value;
 * running it through the sensor lens would correct it twice.
 */
object CalibratedGlucose {

    /** The lens in force, or null when there is nothing to correct with. */
    fun lens(store: CollectorStore, context: Context) =
        runCatching { MeterCalCache.get(store, context) }.getOrNull()

    fun correct(store: CollectorStore, context: Context, tsMs: Long, mmol: Double): Double =
        lens(store, context)?.correctedAt(tsMs, mmol) ?: mmol

    /** The newest sensor reading on the calibrated (meter) scale. Meter rows pass through. */
    fun lastReading(store: CollectorStore, context: Context): Reading? {
        val r = store.lastSensorReading() ?: return null
        if (r.source == "meter") return r
        val cal = lens(store, context) ?: return r
        val mmol = cal.correctedAt(r.tsMs, r.mmol)
        return r.copy(mmol = mmol, mgdl = mmol * com.diapilot.core.analysis.MGDL_PER_MMOL_F)
    }

    /**
     * Minute-stream points on the calibrated scale.
     *
     * Two corrections in sequence, and the order is not free: the minute feed
     * lives on its OWN scale relative to the five-minute stream, so it is
     * brought onto the sensor scale first ([MinuteCalCache]) and only then onto
     * the blood scale. Applying the meter lens to a raw minute point would
     * correct the wrong distance.
     */
    fun minutePoints(
        store: CollectorStore,
        context: Context,
        fromMs: Long,
        toMs: Long,
    ): List<GlucosePoint> {
        val raw = store.minuteReadings(fromMs, toMs)
        val minuteCal = runCatching { MinuteCalCache.get(store, context) }.getOrNull()
        val meter = lens(store, context)
        return raw.map { p ->
            val onSensorScale = minuteCal?.apply(p.mmol) ?: p.mmol
            GlucosePoint(p.tsMs, meter?.correctedAt(p.tsMs, onSensorScale) ?: onSensorScale)
        }
    }
}
