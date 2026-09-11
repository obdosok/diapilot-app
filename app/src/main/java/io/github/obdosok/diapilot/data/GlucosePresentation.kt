package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.analysis.Plausibility
import com.diapilot.core.analysis.SuspectReason
import com.diapilot.core.analysis.plausibilityGate
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.Reading

/** One glucose fact all live surfaces agree on. */
data class ForecastAnchor(
    val reading: Reading,
    val minutePoints: List<GlucosePoint>,
    val openLoop: Boolean,
)

/** The Libre's rated life. The point where unconditional trust ENDS. */
private const val SENSOR_RATED_LIFE_MS = 14L * 24L * 3_600_000L

/**
 * HOW MUCH LONGER TO TRUST THE SENSOR AFTER ITS RATED LIFE, at most.
 *
 * Confirmation comes from the MINUTE stream of the same sensor, not from an
 * independent device, so both can drift together. The ceiling bounds that
 * shared blindness; it comfortably covers the extra time a sensor typically
 * keeps working past its rated life in practice.
 */
private const val SENSOR_GRACE_MS = 24L * 3_600_000L

/** Stream divergence above which a reading is considered suspect. */
private const val STREAM_DIVERGENCE_MMOL = 2.0

/** How many consecutive suspect readings mean the sensor is lying, not noisy. */
private const val STREAM_DIVERGENCE_RUN = 3

/**
 * UP TO WHAT MOMENT THE SENSOR IS TRUSTED — by SIGNAL, not by calendar.
 *
 * This used to be a hard cutoff at `start + 14 days` in three places, and it
 * killed everything: the line, the widget, the watch. Reported by the user:
 * the sensor is rated for 14 days but in practice keeps working for another
 * 12 hours — let the app keep working while the sensor is still signaling.
 *
 * The cutoff was not there for nothing, and the reason is recorded beside it:
 * Libre keeps sending numerically plausible packets after its rated life
 * expires — on one device the main stream read a value well below the
 * independent minute stream's reading, tens of mg/dL apart.
 * But that describes not AGE but STREAM DIVERGENCE, and the gate belongs
 * there instead.
 *
 * THE THRESHOLD WAS MEASURED against a real sensor's own data, thousands of
 * paired readings over roughly two weeks of normal operation. After
 * calibrating the minute stream (`fitMinuteCalibration`), the divergence was
 * small at the median and stayed modest even at high percentiles; the one
 * documented failure case sat in the TAIL of that distribution: a single
 * point cannot be a threshold.
 *
 * A RUN can, though. The longest consecutive run above 2.0 mmol across the
 * whole normal-operation stretch was TWO; a run of three or more never
 * happened. Hence the rule: three in a row above 2.0, and trust ended at the
 * first of them.
 *
 * A failure looks like a failure: with no minute stream there is nothing to
 * confirm against, and the old calendar cutoff returns. "Not checked" is not
 * the same as "checked and fine".
 */
internal fun sensorTrustEndMs(
    store: CollectorStore,
    context: Context,
    nowMs: Long,
): Long? {
    val start = Settings.libreSensorStartMs(context).takeIf { it > 0L } ?: return null
    val rated = start + SENSOR_RATED_LIFE_MS
    if (nowMs <= rated) return rated
    val cal = MinuteCalCache.get(store, context) ?: return rated
    val until = minOf(nowMs, rated + SENSOR_GRACE_MS)
    return sensorTrustFromStreams(
        rated = rated,
        until = until,
        main = store.sensorReadings(rated, until),
        minutes = store.minuteReadings(rated - 10L * 60_000, until),
        calibrate = cal::apply,
    )
}

/**
 * A PURE RULE, separated from data fetching — so it can be tested.
 *
 * [sensorTrustEndMs] reaches into `MinuteCalCache`, which reaches into the
 * system clock, a cache and SharedPreferences; it does not spin up on a
 * fixture, and the rule would otherwise be untested. This holds only the
 * decision, and it all comes down to the four measured numbers above.
 */
internal fun sensorTrustFromStreams(
    rated: Long,
    until: Long,
    main: List<GlucosePoint>,
    minutes: List<GlucosePoint>,
    calibrate: (Double) -> Double,
): Long {
    if (minutes.isEmpty()) return rated
    var run = 0
    var runStart = 0L
    for (row in main.sortedBy { it.tsMs }) {
        val near = minutes.minByOrNull { kotlin.math.abs(it.tsMs - row.tsMs) }
            ?.takeIf { kotlin.math.abs(it.tsMs - row.tsMs) <= 90_000 }
        if (near == null) {
            // Nothing to confirm against — not evidence either way, the run breaks.
            run = 0
            continue
        }
        if (kotlin.math.abs(row.mmol - calibrate(near.mmol)) > STREAM_DIVERGENCE_MMOL) {
            if (run == 0) runStart = row.tsMs
            run++
            // Trust ends at the FIRST reading of the run, not the third:
            // if the stream is lying, it was already lying then — we just didn't know yet.
            if (run >= STREAM_DIVERGENCE_RUN) return runStart - 1
        } else {
            run = 0
        }
    }
    return until
}

/**
 * Phone, watch, widget and companion must not each invent freshness and
 * calibration rules. A meter check is ground truth and wins when newer than
 * the sensor; otherwise the same minute promotion and current sensor lens are
 * used everywhere.
 */
fun forecastAnchor(
    store: CollectorStore,
    context: Context,
    nowMs: Long,
    maxAgeMs: Long = 36L * 3_600_000,
): ForecastAnchor? {
    val sensorEndMs = sensorTrustEndMs(store, context, nowMs)
    // Libre may keep emitting numerically plausible packets after the sensor
    // has expired. They are not observations: on the current phone the main
    // stream said 49.6 mg/dL at 22:28 while the independent minute stream said
    // 89, immediately after the registered 14-day lifetime ended. Never let
    // such a packet replace the last trustworthy fact or a meter anchor.
    val main = store.lastSensorReading()
        ?.takeIf { sensorEndMs == null || it.tsMs <= sensorEndMs }
    val minuteCal = MinuteCalCache.get(store, context)
    val promoted = run {
        val minute = store.lastMinuteReading()
        if (minuteCal != null && minute != null && main != null &&
            minute.tsMs > main.tsMs + 90_000 && nowMs - minute.tsMs < 10L * 60_000
        ) {
            val mmol = minuteCal.apply(minute.mmol)
            main.copy(
                tsMs = minute.tsMs,
                mmol = mmol,
                mgdl = mmol * com.diapilot.core.analysis.MGDL_PER_MMOL_F,
                source = "minute_cal",
            )
        } else main
    }
    val meterCal = MeterCalCache.get(store, context)
    val sensor = promoted?.let { reading ->
        val mmol = meterCal?.correctedAt(reading.tsMs, reading.mmol) ?: reading.mmol
        reading.copy(
            mmol = mmol,
            mgdl = mmol * com.diapilot.core.analysis.MGDL_PER_MMOL_F,
        )
    }
    val meter = store.meterReadings(nowMs - maxAgeMs, nowMs).lastOrNull()
    val chosen = listOfNotNull(sensor, meter).maxByOrNull { it.tsMs } ?: return null
    if (nowMs - chosen.tsMs > maxAgeMs) return null
    if (chosen.source != "meter") {
        (store as? SqliteCollectorStore)?.rememberPresentedGlucose(
            chosen.tsMs, chosen.mmol, chosen.source, nowMs,
        )
    }
    val momentum = if (chosen.source == "meter" || minuteCal == null) emptyList() else {
        store.minuteReadings(chosen.tsMs - 15L * 60_000, chosen.tsMs)
            .map { GlucosePoint(it.tsMs, minuteCal.apply(it.mmol)) }
    }
    return ForecastAnchor(
        reading = chosen,
        minutePoints = momentum,
        openLoop = nowMs - chosen.tsMs > 15L * 60_000,
    )
}

/**
 * Stable historical view. Calibration versions are applied only from the
 * moment they became known; later checks cannot redraw an earlier night.
 * Meter rows are already blood values and are never calibrated again.
 */
fun causalHistory(
    store: CollectorStore,
    fromMs: Long,
    toMs: Long,
): List<GlucosePoint> {
    val timeline = MeterCalCache.timeline(store)
    val frozen = (store as? SqliteCollectorStore)
        ?.presentedGlucose(fromMs, toMs).orEmpty()
    val frozenByTs = frozen.associateBy { it.tsMs }
    val sensor = store.sensorReadings(fromMs, toMs).map { point ->
        frozenByTs[point.tsMs] ?: (
            timeline.lastOrNull { point.tsMs in it.validFromMs..it.validUntilMs }
                ?.apply(point) ?: point
            )
    }
    val meter = store.meterReadings(fromMs, toMs)
        .map { GlucosePoint(it.tsMs, it.mmol) }
    val sensorTs = sensor.mapTo(HashSet()) { it.tsMs }
    return (sensor + frozen.filter { it.tsMs !in sensorTs } + meter)
        .distinctBy { it.tsMs }.sortedBy { it.tsMs }
}

/**
 * What the user can inspect on a graph. Suspect physiology is evidence with a
 * warning, not permission to erase hours of history. Only non-physical values
 * and packets emitted after the registered sensor lifetime are omitted: both
 * are outside the device's measurement contract. Analytics use
 * [trustedHistory].
 */
fun displayHistory(
    store: CollectorStore,
    context: Context,
    fromMs: Long,
    toMs: Long,
): List<GlucosePoint> {
    val sensorEndMs = sensorTrustEndMs(store, context, toMs)
    val meters = store.meterReadings(fromMs, toMs)
        .map { GlucosePoint(it.tsMs, it.mmol) }
    val meterTs = meters.mapTo(HashSet()) { it.tsMs }
    val sensor = plausibilityGate(
        causalHistory(store, fromMs, toMs).filter { it.tsMs !in meterTs },
    ).filter { row ->
        row.reason != SuspectReason.NON_PHYSICAL &&
            (sensorEndMs == null || row.point.tsMs <= sensorEndMs)
    }.map { it.point }
    return (sensor + meters).distinctBy { it.tsMs }.sortedBy { it.tsMs }
}

/**
 * Remove only unmistakable sensor-failure excursions from the presented line
 * and TIR. Borderline/ordinary lows remain. Raw rows stay untouched in SQLite.
 * The solid line bridges the rejected segment instead of pretending its
 * impossible magnitude was physiology.
 */
fun trustedHistory(
    store: CollectorStore,
    context: Context,
    fromMs: Long,
    toMs: Long,
): List<GlucosePoint> {
    val meters = store.meterReadings(fromMs, toMs)
        .map { GlucosePoint(it.tsMs, it.mmol) }
    val meterTs = meters.mapTo(HashSet()) { it.tsMs }
    val sensorEndMs = sensorTrustEndMs(store, context, toMs)
    val causal = causalHistory(store, fromMs, toMs)
    val gated = plausibilityGate(causal.filter { it.tsMs !in meterTs })
        .filter { row ->
            (sensorEndMs == null || row.point.tsMs <= sensorEndMs) &&
                (row.plausibility == Plausibility.OK ||
                    row.reason !in setOf(
                        SuspectReason.NON_PHYSICAL,
                        SuspectReason.ARTIFACT_NEIGHBOR,
                    ) && row.point.mmol >= 1.5)
        }
        .map { it.point }
    return (gated + meters).distinctBy { it.tsMs }.sortedBy { it.tsMs }
}

data class Tir24h(val inRange: Int, val low: Int, val high: Int)

fun tir24h(
    store: CollectorStore,
    context: Context,
    nowMs: Long,
    lo: Double,
    hi: Double,
): Tir24h? {
    val day = trustedHistory(store, context, nowMs - 24L * 3_600_000, nowMs)
    if (day.isEmpty()) return null
    val low = (100.0 * day.count { it.mmol < lo } / day.size).toInt()
    val high = (100.0 * day.count { it.mmol > hi } / day.size).toInt()
    return Tir24h(100 - low - high, low, high)
}
