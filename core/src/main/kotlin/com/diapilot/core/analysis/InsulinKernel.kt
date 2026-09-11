/**
 * Personal insulin action curve G(τ): the median, dose-normalized BG response
 * to an isolated correction bolus, per stage-0 twin spec §3.
 *
 * G(τ) = median over clean episodes of (BG(t0+τ) − BG_start) / dose,
 * for τ in [0, horizon]. Expected shape: ≤ 0, decreasing, flattening to a
 * plateau (the plateau level ≈ −ISF). ISF and action shape live in one curve.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint

data class KernelPoint(
    val tauMin: Double,
    val median: Double,   // mmol/L per U, ≤ 0 for a working insulin
    val q1: Double,
    val q3: Double,
    val n: Int,           // episodes contributing to this bin
)

/**
 * SPENT IS MONOTONE — insulin cannot un-act.
 *
 * On one measured snapshot the learned curve deepens to −1.757 at τ=165 and
 * REBOUNDS to −1.409 by τ=200: read literally, the forecast has insulin RAISING
 * glucose by 0.348 mmol/U in hour three. (Older notes quote −1.70/−1.34/0.36
 * from an earlier snapshot; re-measure before quoting, the curve moves.)
 *
 * It is not pharmacology — it is people eating three hours after a correction,
 * so the rebound is somebody else's glucose. `Iob.kt` already refuses to believe it (`kernelIobFraction` reads the
 * deepest drop SO FAR, and reading the current point instead once left 13% of
 * every bolus on board forever). The forecast believed it until now.
 *
 * TWO EFFECTS RIDE IN THIS ONE FUNCTION, AND THEY ARE NOT EQUALLY WELL FOUNDED.
 * They currently ship together; separating them is filed work.
 *
 *  1. **The HEAD clamp — physically justified on its own.** The measured curve
 *     opens with six POSITIVE bins (τ=0..25, up to +0.076 mmol/U). A fresh bolus
 *     cannot RAISE glucose; that is absorption lag plus noise, not pharmacology.
 *     `deepest` starting at 0.0 flattens them. Σ|Δ| over τ≤60 on the SHIPPED
 *     scale is 0.80 mmol/U — so "the first hour is untouched" is false, and was
 *     claimed once (at 0.278, which was the head-only sum on the raw scale).
 *
 *  2. **The PLATEAU monotonisation — a weaker claim, and the noise argument
 *     applies to it in full.** "Cumulative drop cannot reverse" is a sound prior,
 *     but enforcing it with a RUNNING MINIMUM over the 25 plateau bins (τ≥120)
 *     carrying σ≈0.083 bin-to-bin noise manufactures a downward ramp that is not
 *     in the body — the same selection effect that would hand back ~11% of a
 *     flat curve's amplitude. Renormalising afterwards removes the amplitude half of
 *     that error; it does not remove the shape half. The principled form is an
 *     isotonic fit (or monotonising a smoothed curve), not a running minimum,
 *     and it is not what this does.
 *
 * WHERE IT IS APPLIED MATTERS AND IS NOT COSMETIC: scaling and a running minimum
 * commute, so applying this BEFORE the ISF rescale re-shapes the curve against a
 * fixed plateau (mass moves from the first hour into the tail, total drop
 * unchanged), while applying it AFTER makes the deepest bin the new plateau and
 * raises the amplitude ~18%. `TwinCache` does the former via [shippedKernel],
 * deliberately: the amplitude half measured worse on its own, and "the deepest
 * measured drop" is the minimum of 25 noisy plateau bins — a flat curve with
 * this bin-to-bin noise would manufacture ~11 of those 18 points by selection.
 */
fun monotoneKernel(kernel: List<KernelPoint>): List<KernelPoint> {
    var deepest = 0.0
    return kernel.map { p ->
        deepest = minOf(deepest, p.median)
        // The quartiles are NOT monotonised — they carry the spread, and
        // reshaping them would move the corridor with nothing measuring it. But
        // the band must still contain its own line: lowering the median can push
        // it under an untouched q1 at tail bins, which the kernel chart would
        // draw as the curve escaping its own band.
        p.copy(median = deepest, q1 = minOf(p.q1, deepest), q3 = maxOf(p.q3, deepest))
    }
}

/**
 * The kernel the app actually forecasts with: [learned] made monotone and THEN
 * rescaled so its plateau equals [targetMmolPerU].
 *
 * This exists to make the ORDER testable. `monotoneKernel` and the rescale do
 * not commute in effect — `monotone(rescale(x))` keeps the raw plateau as the
 * anchor and raises the amplitude ~18%, `rescale(monotone(x))` holds the
 * amplitude and only redistributes it over time — and only the second is what
 * v7 ships. That distinction lived in one line inside `TwinCache`, which has no
 * test coverage at all, so moving it back was a one-line regression that would
 * have shipped +18% insulin to the hypo alert against a green suite.
 */
fun shippedKernel(learned: List<KernelPoint>, targetMmolPerU: Double): List<KernelPoint> {
    val monotone = monotoneKernel(learned)
    val plateau = -(monotone.lastOrNull()?.median ?: 0.0)
    if (targetMmolPerU <= 0.05 || plateau <= 0.05) return monotone
    val f = targetMmolPerU / plateau
    return monotone.map { it.copy(median = it.median * f, q1 = it.q1 * f, q3 = it.q3 * f) }
}

/**
 * Build G(τ) from clean episodes over the same reading stream they were
 * detected on. Bins are stepMin wide; bins backed by fewer than minEpisodes
 * are dropped (ragged coverage at the horizon tail).
 */
fun insulinKernel(
    readings: List<GlucosePoint>,
    episodes: List<IsfEpisode>,
    stepMin: Double = 5.0,
    horizonMin: Double = 240.0,
    minEpisodes: Int = 5,
    // Per-episode training weights (recency x era, see episodeWeights);
    // null = classic unweighted median.
    weights: DoubleArray? = null,
): List<KernelPoint> {
    if (episodes.isEmpty()) return emptyList()
    val r = readings.sortedBy { it.tsMs }
    val ts = LongArray(r.size) { r[it].tsMs }
    val bg = DoubleArray(r.size) { r[it].mmol }

    fun lowerBound(key: Long): Int {
        var lo = 0; var hi = ts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    val nBins = (horizonMin / stepMin).toInt() + 1
    val bins = Array(nBins) { mutableListOf<Pair<Double, Double>>() }  // value, weight

    episodes.forEachIndexed { ei, e ->
        val epW = weights?.get(ei) ?: 1.0
        val lo = lowerBound(e.t0Ms)
        val hi = lowerBound(e.t0Ms + (horizonMin * 60_000).toLong() + 1)
        // ONE value per episode per bin (median of its points there), full
        // episode weight in each bin it covers. Dividing the episode weight
        // by its TOTAL point count let sparse episodes outweigh dense ones
        // inside shared bins (external review round 6); with one value per
        // bin, each bin's "n" is a real count of distinct episodes.
        val perBin = HashMap<Int, MutableList<Double>>()
        for (i in lo until hi) {
            val tauMin = (ts[i] - e.t0Ms) / 60_000.0
            val bin = Math.round(tauMin / stepMin).toInt()
            if (bin in 0 until nBins) {
                perBin.getOrPut(bin) { mutableListOf() }.add((bg[i] - e.bgStart) / e.dose)
            }
        }
        perBin.forEach { (bin, vs) ->
            bins[bin].add(vs.sorted()[vs.size / 2] to epW)
        }
    }

    return bins.mapIndexedNotNull { i, values ->
        if (values.size < minEpisodes) return@mapIndexedNotNull null
        KernelPoint(
            tauMin = i * stepMin,
            median = weightedPercentile(values, 50.0),
            q1 = weightedPercentile(values, 25.0),
            q3 = weightedPercentile(values, 75.0),
            n = values.size,
        )
    }
}

/** Percentile over (value, weight) pairs: cumulative-weight walk. */
internal fun weightedPercentile(pairs: List<Pair<Double, Double>>, q: Double): Double {
    val sorted = pairs.sortedBy { it.first }
    val total = sorted.sumOf { it.second }
    if (total <= 0) return sorted[sorted.size / 2].first
    val target = q / 100.0 * total
    var cum = 0.0
    for ((v, w) in sorted) {
        cum += w
        if (cum >= target) return v
    }
    return sorted.last().first
}

/** Kernel value at a given τ (nearest bin at or before it); null if uncovered. */
fun kernelAt(kernel: List<KernelPoint>, tauMin: Double): Double? =
    kernel.lastOrNull { it.tauMin <= tauMin }?.median
