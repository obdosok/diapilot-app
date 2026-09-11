/**
 * Early rapid-fall warning on the per-minute stream. The minute cadence sees
 * a steep drop 3-4 minutes before the 5-minute calibrated stream does — at
 * hypo speeds that head start matters.
 *
 * Slope comes from the minute stream (uncalibrated, but slope is scale-
 * robust); the absolute level prefers the calibrated reading when fresh.
 * Observation + projection only, no instructions.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint

data class RapidFall(
    val currentMmol: Double,
    val slopePerMin: Double,       // negative
    val projected20Mmol: Double,   // current + slope * 20
    val urgent: Boolean,           // projected below hypo threshold
)

/**
 * Fires when BG falls at [slopeThreshold] mmol/min or faster (least-squares
 * over the last [windowMin]) AND the 20-minute projection lands below
 * [warnBelow]. Urgent when it lands below [hypoAt].
 */
fun detectRapidFall(
    minuteReadings: List<GlucosePoint>,
    calibratedMmol: Double?,
    nowMs: Long,
    windowMin: Double = 10.0,
    slopeThreshold: Double = -0.12,
    warnBelow: Double = 4.5,
    hypoAt: Double = 3.9,
    staleMin: Double = 5.0,
    minPoints: Int = 5,
): RapidFall? {
    val from = nowMs - (windowMin * 60_000).toLong()
    val pts = minuteReadings.filter { it.tsMs >= from }.sortedBy { it.tsMs }
    if (pts.size < minPoints) return null
    if (nowMs - pts.last().tsMs > staleMin * 60_000) return null

    // Least-squares slope, mmol per minute.
    val t0 = pts.first().tsMs
    val xs = pts.map { (it.tsMs - t0) / 60_000.0 }
    val ys = pts.map { it.mmol }
    val mx = xs.average()
    val my = ys.average()
    var cov = 0.0
    var varX = 0.0
    for (i in pts.indices) {
        cov += (xs[i] - mx) * (ys[i] - my)
        varX += (xs[i] - mx) * (xs[i] - mx)
    }
    if (varX == 0.0) return null
    val slope = cov / varX
    if (slope > slopeThreshold) return null

    val current = calibratedMmol ?: pts.last().mmol
    val projected = current + slope * 20
    if (projected >= warnBelow) return null
    return RapidFall(
        currentMmol = current,
        slopePerMin = slope,
        projected20Mmol = projected,
        urgent = projected < hypoAt,
    )
}
