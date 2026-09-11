/**
 * Meter-check calibration: the glucometer is the arbiter when the sensor
 * lies. Two layers, learned from the checks entered during the sensor's
 * life (14-day window):
 *
 *  - PERSISTENT: some sensors read high/low their whole life (the user's
 *    lived experience with Libre). With 2+ checks, the median gap becomes
 *    a standing offset; with 4+ checks spread over a BG range, a full
 *    slope+intercept line (xDrip-style, but median-guarded).
 *  - TRANSIENT: the freshest check's residual ON TOP of the persistent
 *    layer, decaying with a 6h half-life — temporary drift that Libre
 *    often corrects on its own.
 *
 * One check = transient only (one point must not define a life-long law).
 * Storage is never rewritten — this is a read-time lens.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import kotlin.math.abs
import kotlin.math.pow

data class MeterCorrection(
    val offsetMmol: Double,
    val checkTsMs: Long,
    val halfLifeMs: Long = 6L * 3_600_000,
) {
    /** The offset still in force at [tsMs]; zero before the check. */
    fun offsetAt(tsMs: Long): Double {
        if (tsMs < checkTsMs) return 0.0
        val halves = (tsMs - checkTsMs).toDouble() / halfLifeMs
        return offsetMmol * 2.0.pow(-halves)
    }
}

data class MeterCalibration(
    val slope: Double,          // persistent layer: corrected = slope*sensor + intercept
    val interceptMmol: Double,
    val nChecks: Int,           // checks that built the persistent layer
    val transient: MeterCorrection?,   // freshest residual, decaying
    val validFromMs: Long = Long.MIN_VALUE,
    val validUntilMs: Long = Long.MAX_VALUE,
) {
    fun correctedAt(tsMs: Long, sensorMmol: Double): Double =
        if (tsMs !in validFromMs..validUntilMs) sensorMmol
        else slope * sensorMmol + interceptMmol + (transient?.offsetAt(tsMs) ?: 0.0)

    fun apply(p: GlucosePoint): GlucosePoint =
        GlucosePoint(p.tsMs, correctedAt(p.tsMs, p.mmol))

    /** Anything to show/apply at all? */
    fun isActive(nowMs: Long): Boolean =
        nowMs in validFromMs..validUntilMs &&
            (slope != 1.0 || abs(interceptMmol) >= 0.05 ||
                abs(transient?.offsetAt(nowMs) ?: 0.0) >= 0.1)
}

private const val MAX_PAIR_GAP_MMOL = 5.0   // larger = typo/other-arm, not drift
private const val PAIR_WINDOW_MS = 10L * 60_000

/**
 * Rate cutoff for a trustworthy calibration pair, mmol/L per minute
 * (~1.1 mg/dl/min — the edge of a "flat" trend). Above it the fingerstick
 * (blood) and the sensor (interstitial fluid, ~5-15 min behind) are reading
 * different moments, so the gap is lag, not sensor error — calibrating then
 * bakes in a wrong offset.
 */
const val FLAT_RATE_MMOL_PER_MIN = 0.06

/**
 * Local rate of change of the sensor around [tsMs] (mmol/L per min), from
 * points within ±15 min. Null when too few/too short to judge — the caller
 * then keeps the pair (absence of evidence is not fast change).
 */
fun sensorRateNear(sensorAsc: List<GlucosePoint>, tsMs: Long): Double? {
    val win = sensorAsc.filter { kotlin.math.abs(it.tsMs - tsMs) <= 15L * 60_000 }
    if (win.size < 3) return null
    val dtMin = (win.last().tsMs - win.first().tsMs) / 60_000.0
    return if (dtMin < 10.0) null else (win.last().mmol - win.first().mmol) / dtMin
}

/**
 * Build the two-layer calibration from meter checks within [windowMs]
 * (default: a sensor lifetime). Returns null when no check can be paired
 * with a sensor point.
 */
fun meterCalibration(
    sensor: List<GlucosePoint>,
    meter: List<GlucosePoint>,
    nowMs: Long,
    windowMs: Long = 14L * 24 * 3_600_000,
    // Denser signal (the 1-min stream) for the trend guard — the 5-min main
    // list is often too sparse to see a fast change around a check. Defaults
    // to [sensor]. Only used to judge rate, never to pair/offset.
    rateSource: List<GlucosePoint> = sensor,
    validFromMs: Long = Long.MIN_VALUE,
    validUntilMs: Long = Long.MAX_VALUE,
): MeterCalibration? {
    if (meter.isEmpty() || sensor.isEmpty()) return null
    val rateAsc = rateSource.sortedBy { it.tsMs }
    val meterTs = meter.mapTo(HashSet()) { it.tsMs }
    val sensorOnly = sensor.filter { it.tsMs !in meterTs }.sortedBy { it.tsMs }
    if (sensorOnly.isEmpty()) return null
    val ts = LongArray(sensorOnly.size) { sensorOnly[it].tsMs }
    fun lowerBound(key: Long): Int {
        var lo = 0; var hi = ts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // (sensorMmol, meterMmol, checkTs) for every plausible check in window.
    data class P(val x: Double, val y: Double, val t: Long)
    val pairs = meter
        .filter { nowMs - it.tsMs in 0..windowMs }
        .mapNotNull { check ->
            val j = lowerBound(check.tsMs)
            listOfNotNull(sensorOnly.getOrNull(j - 1), sensorOnly.getOrNull(j))
                .minByOrNull { abs(it.tsMs - check.tsMs) }
                ?.takeIf { abs(it.tsMs - check.tsMs) <= PAIR_WINDOW_MS }
                ?.takeIf { abs(check.mmol - it.mmol) <= MAX_PAIR_GAP_MMOL }
                // Trend guard: drop a check taken while glucose was moving
                // fast (blood vs lagged ISF = a false offset). Unknown rate
                // (sparse data) is kept — we can't judge it.
                ?.takeIf {
                    val r = sensorRateNear(rateAsc, check.tsMs)
                    r == null || abs(r) <= FLAT_RATE_MMOL_PER_MIN
                }
                ?.let { P(it.mmol, check.mmol, check.tsMs) }
        }
        .sortedBy { it.t }
    if (pairs.isEmpty()) return null

    // --- persistent layer -------------------------------------------------
    var slope = 1.0
    var intercept = 0.0
    if (pairs.size >= 2) {
        val spread = pairs.maxOf { it.x } - pairs.minOf { it.x }
        if (pairs.size >= 4 && spread >= 4.0) {
            // Enough range for a line: robust Theil–Sen meter~sensor.
            // A single noisy/contaminated fingerstick cannot rotate the
            // calibration line as it could under ordinary least squares.
            val n = pairs.size
            val candidateSlopes = buildList {
                for (i in 0 until n) for (j in i + 1 until n) {
                    val dx = pairs[j].x - pairs[i].x
                    if (abs(dx) >= 1.0) add((pairs[j].y - pairs[i].y) / dx)
                }
            }.sorted()
            val a = candidateSlopes.takeIf { it.isNotEmpty() }
                ?.let { it[it.size / 2] } ?: 1.0
            if (a in 0.7..1.3) {
                slope = a
                val intercepts = pairs.map { it.y - a * it.x }.sorted()
                intercept = intercepts[intercepts.size / 2]
            } else {
                intercept = pairs.map { it.y - it.x }.sorted()[n / 2]
            }
        } else {
            // Median standing offset — robust with few checks.
            val offsets = pairs.map { it.y - it.x }.sorted()
            intercept = offsets[offsets.size / 2]
        }
    }

    // --- transient layer: freshest check's residual vs persistent ---------
    val latest = pairs.last()
    val transient = (latest.y - (slope * latest.x + intercept))
        .takeIf { abs(it) >= 0.15 && nowMs - latest.t <= 24L * 3_600_000 }
        ?.let { MeterCorrection(offsetMmol = it, checkTsMs = latest.t) }

    val nPersistent = if (pairs.size >= 2) pairs.size else 0
    if (nPersistent == 0 && transient == null) return null
    return MeterCalibration(
        slope, intercept, nPersistent, transient,
        validFromMs = validFromMs,
        validUntilMs = validUntilMs,
    )
}
