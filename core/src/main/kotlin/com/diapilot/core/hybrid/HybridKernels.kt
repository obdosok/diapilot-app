package com.diapilot.core.hybrid

internal fun clamp(value: Double, low: Double, high: Double): Double =
    maxOf(low, minOf(high, value))

fun triangularCdf(
    t: Double,
    onset: Double,
    peak: Double,
    duration: Double,
): Double {
    if (t <= onset) return 0.0
    if (t >= duration) return 1.0
    return if (t <= peak) {
        (t - onset) * (t - onset) /
            maxOf(1e-9, (duration - onset) * (peak - onset))
    } else {
        1.0 - (duration - t) * (duration - t) /
            maxOf(1e-9, (duration - onset) * (duration - peak))
    }
}


internal fun linearKnots(x: Double, knots: List<Pair<Double, Double>>): Double {
    for (index in 0 until knots.lastIndex) {
        val (x0, y0) = knots[index]
        val (x1, y1) = knots[index + 1]
        if (x <= x1) {
            val ratio = (x - x0) / (x1 - x0)
            return y0 + ratio * (y1 - y0)
        }
    }
    return knots.last().second
}
