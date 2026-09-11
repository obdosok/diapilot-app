/**
 * Parametric insulin kernel: ONE physiology for IOB and the forecast.
 *
 * The empirical kernel G(τ) (median of binned episode traces) and the IOB
 * curve (hand-set exponential) were two disconnected models of the same
 * insulin — they could disagree. Here the SAME exponential activity family
 * that powers iobFraction() is fitted directly to the clean correction
 * episodes:
 *
 *   G(τ) = −ISF · (1 − iobFraction(τ; DIA, peak))
 *
 * so ISF, DIA and time-to-peak become LEARNED personal parameters, the
 * curve is smooth by construction (no binning steps), and the empirical
 * kernel stays alive as the validator: a fit is trustworthy when it runs
 * inside the empirical IQR.
 *
 * Fit: grid over (DIA, peak) — the model is linear in ISF for a fixed
 * shape, so the amplitude has a closed form per grid node. One 3×MAD
 * outlier trim and a refit (an unlogged meal inside an episode must not
 * bend the curve). Research observations only — never dosing advice.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint

data class ParametricKernel(
    val isfMmolPerU: Double,    // full drop at DIA, positive
    val diaMin: Double,
    val peakMin: Double,
    val nPoints: Int,           // trace points that survived the trim
    val nEpisodes: Int,
    /** Effective episode count after recency+era weighting:
     *  (Σw)² / Σw². Far below nEpisodes when a few fresh cases dominate. */
    val effectiveEpisodes: Double = nEpisodes.toDouble(),
    val rmseMmolPerU: Double,   // residual RMSE of dose-normalized traces
    /** True when the best DIA sits on the UPPER grid edge — the observation
     *  window is too short to see the tail flatten; DIA is a lower bound. */
    val diaAtBound: Boolean,
    /** True when the best DIA sits on the LOWER grid edge — the fit wanted
     *  an even faster insulin than the grid allows; treat DIA as "≤". */
    val diaAtFloor: Boolean = false,
    /** Share of the curve inside the empirical kernel's IQR (bins with
     *  n≥5); null when no empirical kernel was supplied. */
    val agreementPct: Double?,
) {
    /** The fitted curve: mmol/L per U at τ minutes after the shot, ≤ 0. */
    fun g(tauMin: Double): Double =
        -isfMmolPerU * (1 - iobFraction(tauMin, diaMin, peakMin))

}

/**
 * Fit the parametric kernel to clean correction episodes.
 *
 * @param horizonMin how far past t0 the traces are trusted — MUST match the
 *   isolation window of the config the episodes were detected with; points
 *   beyond it may contain other boluses.
 */
fun fitParametricKernel(
    readings: List<GlucosePoint>,
    episodes: List<IsfEpisode>,
    horizonMin: Double,
    empirical: List<KernelPoint>? = null,
    minEpisodes: Int = 8,
    minPoints: Int = 120,
    // Per-episode weights (recency x era) aligned to episodes; null = equal.
    weights: DoubleArray? = null,
): ParametricKernel? {
    val cleanIdx = episodes.indices.filter { !episodes[it].anomaly }
    val clean = cleanIdx.map { episodes[it] }
    if (clean.size < minEpisodes) return null

    val r = readings.sortedBy { it.tsMs }
    val ts = LongArray(r.size) { r[it].tsMs }
    fun lowerBound(key: Long): Int {
        var lo = 0; var hi = ts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // Dose-normalized traces: (τ, ΔBG/dose, weight) across all episodes.
    val tau = ArrayList<Double>()
    val y = ArrayList<Double>()
    val pw = ArrayList<Double>()
    clean.forEachIndexed { ci, e ->
        val epW = weights?.get(cleanIdx[ci]) ?: 1.0
        val lo = lowerBound(e.t0Ms)
        val hi = lowerBound(e.t0Ms + (horizonMin * 60_000).toLong() + 1)
        // Same-total-weight-per-episode normalization as the empirical kernel.
        val nPts = (hi - lo).coerceAtLeast(1)
        val ptW = epW / nPts
        for (i in lo until hi) {
            tau.add((ts[i] - e.t0Ms) / 60_000.0)
            y.add((r[i].mmol - e.bgStart) / e.dose)
            pw.add(ptW)
        }
    }
    if (tau.size < minPoints) return null

    // Floor 90 min: even ultra-rapid analogs don't finish faster.
    val diaGrid = generateSequence(90.0) { it + 10.0 }.takeWhile { it <= 360.0 }.toList()

    data class Fit(val isf: Double, val dia: Double, val peak: Double, val sse: Double)

    fun bestFit(taus: List<Double>, ys: List<Double>, ws: List<Double>): Fit? {
        var best: Fit? = null
        for (dia in diaGrid) {
            var peak = 30.0
            while (peak <= minOf(dia * 0.6, 120.0)) {
                // iobFraction's τ-constant is singular at peak = dia/2.
                if (kotlin.math.abs(1 - 2 * peak / dia) > 0.05) {
                    var sfy = 0.0
                    var sff = 0.0
                    for (i in taus.indices) {
                        val f = 1 - iobFraction(taus[i], dia, peak)
                        sfy += ws[i] * f * ys[i]
                        sff += ws[i] * f * f
                    }
                    if (sff > 1e-9) {
                        val isf = -sfy / sff
                        if (isf > 0.1) {
                            var sse = 0.0
                            for (i in taus.indices) {
                                val res = ys[i] + isf * (1 - iobFraction(taus[i], dia, peak))
                                sse += ws[i] * res * res
                            }
                            if (best == null || sse < best!!.sse) best = Fit(isf, dia, peak, sse)
                        }
                    }
                }
                peak += 5.0
            }
        }
        return best
    }

    val first = bestFit(tau, y, pw) ?: return null

    // One robust trim: an unlogged meal in one episode is a cluster of
    // positive residuals — drop everything past 3×MAD, refit.
    val res = tau.indices.map { i ->
        y[i] + first.isf * (1 - iobFraction(tau[i], first.dia, first.peak))
    }
    val mad = res.map { kotlin.math.abs(it) }.sorted()[res.size / 2]
    val keep = tau.indices.filter { kotlin.math.abs(res[it]) <= 3 * mad + 1e-9 }
    if (keep.size < minPoints) return null
    val tau2 = keep.map { tau[it] }
    val y2 = keep.map { y[it] }
    val pw2 = keep.map { pw[it] }
    val fit = bestFit(tau2, y2, pw2) ?: return null

    // Weighted RMSE — the fit minimizes weighted SSE, so its quality must
    // be reported on the same objective (external review P2).
    val rmse = kotlin.math.sqrt(
        tau2.indices.sumOf { i ->
            val d = y2[i] + fit.isf * (1 - iobFraction(tau2[i], fit.dia, fit.peak))
            pw2[i] * d * d
        } / pw2.sum(),
    )

    // Validation against the empirical kernel: inside-IQR share.
    val agreement = empirical
        ?.filter { it.n >= 5 && it.tauMin <= horizonMin }
        ?.takeIf { it.isNotEmpty() }
        ?.let { pts ->
            val inside = pts.count { p ->
                val v = -fit.isf * (1 - iobFraction(p.tauMin, fit.dia, fit.peak))
                v in p.q1..p.q3
            }
            100.0 * inside / pts.size
        }

    val effN = weights?.let {
        val w = cleanIdx.map { i -> it[i] }
        val sum = w.sum(); val sumSq = w.sumOf { x -> x * x }
        if (sumSq > 0) sum * sum / sumSq else clean.size.toDouble()
    } ?: clean.size.toDouble()
    return ParametricKernel(
        isfMmolPerU = fit.isf,
        diaMin = fit.dia,
        peakMin = fit.peak,
        nPoints = tau2.size,
        nEpisodes = clean.size,
        effectiveEpisodes = effN,
        rmseMmolPerU = rmse,
        diaAtBound = fit.dia >= diaGrid.last() - 1e-9,
        diaAtFloor = fit.dia <= diaGrid.first() + 1e-9,
        agreementPct = agreement,
    )
}
