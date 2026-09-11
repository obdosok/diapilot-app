/**
 * Light descriptive analytics: time-in-range and per-food-label response stats.
 * Pure description of collected data — no prediction, no dosing advice.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.LabeledMeal

data class GlycemicStats(
    val n: Int,
    val mean: Double,
    val inRangePct: Double,
    val belowPct: Double,
    val abovePct: Double,
    val hypoEpisodes: Int,
)

/**
 * Share of readings in/below/above the target range plus distinct hypo
 * episodes (a run of readings below `lo`; runs separated by >= gapMin of
 * non-low readings count separately).
 */
fun glycemicStats(
    readings: List<GlucosePoint>,
    lo: Double = 3.9,
    hi: Double = 10.0,
    gapMin: Double = 15.0,
): GlycemicStats? {
    if (readings.isEmpty()) return null
    val r = readings.sortedBy { it.tsMs }
    var below = 0
    var above = 0
    var episodes = 0
    var lastLowTs = Long.MIN_VALUE
    var inEpisode = false
    for (p in r) {
        when {
            p.mmol < lo -> {
                below++
                if (!inEpisode || p.tsMs - lastLowTs > gapMin * 60_000) episodes++
                inEpisode = true
                lastLowTs = p.tsMs
            }
            p.mmol > hi -> {
                above++
                inEpisode = false
            }
            else -> inEpisode = false
        }
    }
    val n = r.size
    return GlycemicStats(
        n = n,
        mean = r.sumOf { it.mmol } / n,
        inRangePct = 100.0 * (n - below - above) / n,
        belowPct = 100.0 * below / n,
        abovePct = 100.0 * above / n,
        hypoEpisodes = episodes,
    )
}

data class LabelStats(
    val name: String,
    val count: Int,
    val avgRise: Double,          // mmol/L trough -> peak
    val avgTimeToPeakMin: Double,
    val avgBolus: Double?,        // among announced occurrences; null if none
    val unannounced: Int,         // occurrences with no paired bolus
)

/**
 * The dish's OWN rise: what the detector measured minus what the insulin
 * active over [onset, peak] was doing to BG at the same time. A meal eaten
 * on the tail of a big bolus looks "weaker" than it is — the kernel knows
 * how much the insulin pulled, so give it back. Empty kernel/boluses = no
 * adjustment (legacy behaviour). Never negative: a rise is a rise.
 */
fun insulinAdjustedRise(
    event: com.diapilot.core.collector.MealEvent,
    boluses: List<com.diapilot.core.collector.BolusPoint>,
    kernel: List<KernelPoint>,
    // Meter-calibration slope: the DETECTOR rise is raw-scale, the kernel is
    // meter-scale (its amplitude is pinned to the user's ISF). Scale the rise
    // FIRST, then subtract insulin — scale×rise − dIns, NOT scale×(rise−dIns):
    // the latter wrongly shrinks the insulin term by the slope (external
    // review).
    riseScale: Double = 1.0,
    // Calibration EPOCH: the affine fit is built from meter pairs of the
    // CURRENT sensor — applying its slope to older sensors' episodes would
    // extrapolate a lens that never saw them. Events before this stay raw.
    riseScaleFromMs: Long = 0L,
): Double {
    val scaledRise = event.rise * (if (event.onsetMs >= riseScaleFromMs) riseScale else 1.0)
    if (kernel.isEmpty() || boluses.isEmpty()) return scaledRise
    val onset = event.onsetMs
    val peak = event.peakMs
    if (peak <= onset) return scaledRise
    val dIns = boluses
        .filter { it.tsMs > onset - 6L * 3_600_000 && it.tsMs <= peak }
        .sumOf { b ->
            val gPeak = kernelAt(kernel, (peak - b.tsMs) / 60_000.0) ?: 0.0
            val gOnset = if (b.tsMs <= onset) {
                kernelAt(kernel, (onset - b.tsMs) / 60_000.0) ?: 0.0
            } else 0.0
            b.units * (gPeak - gOnset)
        }
    // dIns is negative (insulin drops BG) → the dish's own rise is larger.
    return (scaledRise - dIns).coerceAtLeast(0.0)
}

/** Per-food-label response profile from labeled meals; most-used first.
 *  System categories (continuation, dawn) are not food and are excluded.
 *  With [boluses]+[kernel] the rise is insulin-adjusted (the dish's own
 *  effect, not "dish minus whatever IOB happened to be there"). */
fun labelStats(
    labeled: List<LabeledMeal>,
    boluses: List<com.diapilot.core.collector.BolusPoint> = emptyList(),
    kernel: List<KernelPoint> = emptyList(),
    riseScale: Double = 1.0,
    riseScaleFromMs: Long = 0L,
): List<LabelStats> {
    // Recency-weighted: the winter smoothie must not outvote yesterday's —
    // recipes, portions and the body all drift (external review).
    val nowMs = labeled.maxOfOrNull { it.event.onsetMs } ?: 0L
    val halfLifeMs = com.diapilot.core.PersonalParams.DEFAULT.foodHalfLifeDays * 86_400_000.0
    fun w(onsetMs: Long): Double =
        Math.pow(2.0, -((nowMs - onsetMs).coerceAtLeast(0L)) / halfLifeMs)
    return labeled.filter { it.labelName !in SysLabels.ALL }
        .groupBy { it.labelName }
        .map { (name, items) ->
            val ws = items.map { w(it.event.onsetMs) }
            val tw = ws.sum()
            val bolusPairs = items.mapIndexedNotNull { i, m ->
                m.event.bolusUnits?.let { it to ws[i] }
            }
            LabelStats(
                name = name,
                count = items.size,
                avgRise = items.mapIndexed { i, m ->
                    insulinAdjustedRise(m.event, boluses, kernel, riseScale, riseScaleFromMs) * ws[i]
                }.sum() / tw,
                avgTimeToPeakMin =
                    items.mapIndexed { i, m -> m.event.timeToPeakMin * ws[i] }.sum() / tw,
                avgBolus = bolusPairs.takeIf { it.isNotEmpty() }
                    ?.let { ps -> ps.sumOf { it.first * it.second } / ps.sumOf { it.second } },
                unannounced = items.count { it.event.bolusUnits == null },
            )
        }
        .sortedByDescending { it.count }
}


/** One week of the numbers the user actually lives by. */
data class WeekOutcome(
    val fromMs: Long,
    val toMs: Long,
    val tirPct: Int,
    /** GMI from the week's mean glucose (NGSP %%) — an estimate, not a lab. */
    val gmiPct: Double,
    /** Excursions above 300 mg/dl (16.65 mmol): distinct events, not points. */
    val spikes300: Int,
    /** Distinct dips below the range floor lasting >= 15 min. */
    val hypoEpisodes: Int,
)

