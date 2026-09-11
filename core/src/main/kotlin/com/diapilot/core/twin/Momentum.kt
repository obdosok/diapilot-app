/**
 * Human-facing trend from the minute stream — the arrow the screen, the watch
 * and the widget draw.
 *
 * WHAT LEFT AND WHY. This file also held the KINEMATIC branch —
 * `Momentum`, `estimateMomentum`, `reconcileMomentumToGrid`, `blendMomentum` —
 * which blended v·t + ½a·t² into the first ~15 minutes of a forecast. Its only
 * consumer was the legacy `fun forecast`, removed with the legacy twin; the
 * physio arm does not blend a momentum tail.
 *
 * The two are genuinely different jobs, which is why one survived: the trend
 * readout answers "what did glucose just DO" from the readings alone, and
 * touches no model at all. Momentum answered "where is it going", which is the
 * forecast's question.
 */
package com.diapilot.core.twin

import com.diapilot.core.collector.GlucosePoint
/**
 * Human-facing trend: the FACTUAL median-smoothed change over the last
 * 5 minutes — no model, no extrapolation. Field lesson (two rounds): any
 * fitted derivative (quadratic endpoint, then 5v−12.5a) inflates on kinks
 * and noise — raw stream ticking 132→129 over 4 min once displayed as −17.
 * What the arrow must answer is only "what did the sugar DO just now",
 * and the honest answer is s(now) − s(now−5min) on a median-3 stream.
 *
 * The nuance compares the last two 5-min windows (d1 vs d0) — plain
 * differences with a dead band, so the "accelerating" label requires the move to
 * actually be growing by ≥4 mg/dL per window, not a noisy fit curvature.
 */
data class TrendReadout(
    val ratePerMin: Double,   // smoothed mmol/L per min over the last 5 min
    val delta5Mmol: Double,   // s(now) − s(now−5min), median-smoothed
    val nuance: TrendNuance?, // null = nothing the arrow does not already say
)

/** What the arrow alone cannot tell; the app renders the word (i18n.TwinText). */
enum class TrendNuance {
    /** Same direction, meaningfully harder than the previous window. */
    ACCELERATING,
    /** Same direction, meaningfully easing off. */
    DECELERATING,
    /** A real move flipped sign — the turn already happened. */
    REVERSED,
}

fun trendReadout(
    minutePts: List<GlucosePoint>,
    nowMs: Long,
): TrendReadout? {
    val pts = minutePts
        .filter { nowMs - it.tsMs <= 16L * 60_000 && it.tsMs <= nowMs }
        .sortedBy { it.tsMs }
    if (pts.size < 5) return null
    // The newest point must be fresh, or the "change over 5 min" is stale.
    if (nowMs - pts.last().tsMs > 3L * 60_000) return null

    // Median-of-3: kills single-sample sensor blips without lagging much.
    val sm = DoubleArray(pts.size) { i ->
        if (i == 0 || i == pts.size - 1) pts[i].mmol
        else listOf(pts[i - 1].mmol, pts[i].mmol, pts[i + 1].mmol).sorted()[1]
    }

    fun valueAgo(minAgo: Long): Double? {
        val target = pts.last().tsMs - minAgo * 60_000
        var best = -1
        var bestD = Long.MAX_VALUE
        for (i in pts.indices) {
            val d = kotlin.math.abs(pts[i].tsMs - target)
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && bestD <= 90_000) sm[best] else null
    }

    val v0 = sm.last()
    val v5 = valueAgo(5) ?: return null
    val v10 = valueAgo(10)
    val d1 = v0 - v5

    // Nuance from two adjacent windows; DEAD_BAND keeps noise silent.
    val nuance: TrendNuance? = v10?.let { p10 ->
        val d0 = v5 - p10
        when {
            // Same direction, meaningfully harder than the previous window.
            d1 * d0 > 0 && kotlin.math.abs(d1) >= kotlin.math.abs(d0) + NUANCE_BAND_MMOL &&
                kotlin.math.abs(d1) >= MIN_MOVE_MMOL -> TrendNuance.ACCELERATING
            // Same direction, meaningfully easing off.
            d1 * d0 > 0 && kotlin.math.abs(d1) <= kotlin.math.abs(d0) - NUANCE_BAND_MMOL &&
                kotlin.math.abs(d0) >= MIN_MOVE_MMOL -> TrendNuance.DECELERATING
            // A real move flipped sign — the turn already HAPPENED (no
            // predictions here; the forecast owns the future).
            d1 * d0 < 0 && kotlin.math.abs(d0) >= MIN_MOVE_MMOL &&
                kotlin.math.abs(d1) >= MIN_MOVE_MMOL -> TrendNuance.REVERSED
            else -> null
        }
    }
    return TrendReadout(ratePerMin = d1 / 5.0, delta5Mmol = d1, nuance = nuance)
}

/**
 * Trend from the 5-min MAIN grid instead of the 1-min stream. The grid is the
 * FROZEN real-time record: in own-BLE mode each grid point is the newest OOP2
 * minute value promoted+calibrated once and never rewritten (in xDrip mode it
 * is the broadcast value). The 1-min `oop2_ble` stream, by contrast, gets its
 * recent history RETROACTIVELY REVISED — every packet re-decodes ~15 min and
 * overwrites it.
 *
 * Field lesson: during a rapid drop that revision overshot,
 * rewriting 01:41-45 upward (~199 mg/dl) though the live-captured grid held
 * ~175; the 5-min diff on the revised stream then showed a phantom −20 on a
 * truly-flat sensor. The frozen grid kept the truth — and, being 5-min
 * spaced, is the natural basis for a "change over 5 min".
 *
 * Same contract as [trendReadout]: delta over ~5 min, nuance from the two
 * adjacent windows with the same dead bands. Points are matched to the −5/−10
 * min targets within [tolMs] (half a grid step), so a jittered cadence still
 * lands. Newest point must be within [freshMs].
 */
fun gridTrendReadout(
    readings: List<GlucosePoint>,
    nowMs: Long,
    tolMs: Long = 150_000,      // ±2.5 min around each 5-min target
    freshMs: Long = 6L * 60_000,
): TrendReadout? {
    val pts = readings
        .filter { nowMs - it.tsMs <= 16L * 60_000 && it.tsMs <= nowMs }
        .sortedBy { it.tsMs }
    if (pts.size < 2) return null
    val last = pts.last()
    if (nowMs - last.tsMs > freshMs) return null

    fun valueAgo(minAgo: Long): Double? {
        val target = last.tsMs - minAgo * 60_000
        val best = pts.minByOrNull { kotlin.math.abs(it.tsMs - target) } ?: return null
        return if (kotlin.math.abs(best.tsMs - target) <= tolMs) best.mmol else null
    }

    val v0 = last.mmol
    val v5 = valueAgo(5) ?: return null
    val v10 = valueAgo(10)
    val d1 = v0 - v5
    val nuance: TrendNuance? = v10?.let { p10 ->
        val d0 = v5 - p10
        when {
            d1 * d0 > 0 && kotlin.math.abs(d1) >= kotlin.math.abs(d0) + NUANCE_BAND_MMOL &&
                kotlin.math.abs(d1) >= MIN_MOVE_MMOL -> TrendNuance.ACCELERATING
            d1 * d0 > 0 && kotlin.math.abs(d1) <= kotlin.math.abs(d0) - NUANCE_BAND_MMOL &&
                kotlin.math.abs(d0) >= MIN_MOVE_MMOL -> TrendNuance.DECELERATING
            d1 * d0 < 0 && kotlin.math.abs(d0) >= MIN_MOVE_MMOL &&
                kotlin.math.abs(d1) >= MIN_MOVE_MMOL -> TrendNuance.REVERSED
            else -> null
        }
    }
    return TrendReadout(ratePerMin = d1 / 5.0, delta5Mmol = d1, nuance = nuance)
}

/** [n=1] ≈4 mg/dL: two windows must differ by this to call accel/decel. */
private const val NUANCE_BAND_MMOL = 0.22

/** [n=1] ≈5 mg/dL per 5 min: below this a "move" is noise, no nuance. */
private const val MIN_MOVE_MMOL = 0.28
