/**
 * Calibration bridge between the per-minute OOP2 raw stream and the main
 * (xDrip-calibrated) 5-minute readings.
 *
 * The two streams measure the same blood on different scales: OOP2 outputs
 * the factory-algorithm value, the main stream carries the user's xDrip
 * calibration. A rolling linear fit (minute -> main) learned on paired
 * points lets the minute stream be PROMOTED from display-only to a
 * first-class data source — one-minute freshness for the header, the
 * prediction anchor and the watch.
 *
 * Safety: the fit must prove itself (enough pairs, sane slope, small
 * residual) or the caller falls back to the 5-minute truth.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import kotlin.math.abs

data class MinuteCalibration(
    val slope: Double,
    val intercept: Double,   // mmol/L
    val n: Int,              // pairs that survived the outlier trim
    val madMmol: Double,     // median absolute residual of the fit
) {
    fun apply(rawMmol: Double): Double = slope * rawMmol + intercept
}

/**
 * Fit minute→main over paired points (nearest minute reading within
 * [pairWindowMs] of each main reading). Ordinary least squares, then one
 * outlier trim at 3×MAD and a refit. Returns null when the data cannot
 * support a trustworthy line:
 *  - fewer than [minPairs] surviving pairs,
 *  - slope outside [0.5, 2.0] (the scales are cousins, not strangers),
 *  - residual MAD above 1.0 mmol/L (the fit explains nothing).
 */
fun fitMinuteCalibration(
    main: List<GlucosePoint>,
    minute: List<GlucosePoint>,
    pairWindowMs: Long = 90_000,
    minPairs: Int = 20,
): MinuteCalibration? {
    if (main.isEmpty() || minute.isEmpty()) return null
    val m = minute.sortedBy { it.tsMs }
    val ts = LongArray(m.size) { m[it].tsMs }
    fun lowerBound(key: Long): Int {
        var lo = 0; var hi = ts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    val pairs = main.mapNotNull { p ->
        val j = lowerBound(p.tsMs)
        listOfNotNull(m.getOrNull(j - 1), m.getOrNull(j))
            .minByOrNull { abs(it.tsMs - p.tsMs) }
            ?.takeIf { abs(it.tsMs - p.tsMs) <= pairWindowMs }
            ?.let { it.mmol to p.mmol }   // x = raw minute, y = calibrated
    }
    if (pairs.size < minPairs) return null

    fun fit(ps: List<Pair<Double, Double>>): Triple<Double, Double, List<Double>>? {
        val n = ps.size
        val mx = ps.sumOf { it.first } / n
        val my = ps.sumOf { it.second } / n
        var cov = 0.0; var varX = 0.0
        for ((x, y) in ps) {
            cov += (x - mx) * (y - my)
            varX += (x - mx) * (x - mx)
        }
        if (varX < 1e-9) return null
        val slope = cov / varX
        val intercept = my - slope * mx
        val residuals = ps.map { (x, y) -> y - (slope * x + intercept) }
        return Triple(slope, intercept, residuals)
    }

    val first = fit(pairs) ?: return null
    val mad = first.third.map { abs(it) }.sorted().let { it[it.size / 2] }
    val trimmed = pairs.filterIndexed { i, _ -> abs(first.third[i]) <= 3 * mad + 1e-9 }
    if (trimmed.size < minPairs) return null
    val final = fit(trimmed) ?: return null
    val finalMad = final.third.map { abs(it) }.sorted().let { it[it.size / 2] }

    return MinuteCalibration(final.first, final.second, trimmed.size, finalMad)
        .takeIf { it.slope in 0.5..2.0 && it.madMmol <= 1.0 }
}

/** The minute stream re-expressed on the calibrated scale. */
fun calibrateMinuteStream(
    minute: List<GlucosePoint>,
    cal: MinuteCalibration,
): List<GlucosePoint> = minute.map { GlucosePoint(it.tsMs, cal.apply(it.mmol)) }
