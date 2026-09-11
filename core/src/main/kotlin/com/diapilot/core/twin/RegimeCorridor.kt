/**
 * Regime-dependent forecast corridor.
 *
 * One w(Δt) for all situations is a lie in both directions: the backtest
 * shows the model erring ~3× more after meals than in quiet stretches, so
 * a single band is too wide when life is calm and too narrow when food is
 * absorbing. Residuals are therefore bucketed by the anchor's REGIME and a
 * corridor is fitted per regime, falling back to the global fit where a
 * regime is data-thin.
 *
 * Classification looks STRICTLY backward — the live forecast cannot know
 * the future, so its corridor must be chosen from the same information.
 */
package com.diapilot.core.twin

import com.diapilot.core.analysis.effectEndMs
import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint

data class RegimeCorridors(
    val global: Corridor,
    val byRegime: Map<Regime, Corridor>,
) {
    fun forRegime(r: Regime): Corridor = byRegime[r] ?: global
}

/**
 * Calibrate per-regime corridors the same way [calibrateCorridor] fits the
 * global one — anchors along the stream, forward simulation, |residual|
 * p80 per Δt bin, least squares against √Δt — but each anchor's residuals
 * land in its regime's buckets. A regime needs [minBinsPerRegime] populated
 * bins to earn its own line; otherwise it inherits the global corridor.
 */
fun calibrateRegimeCorridors(
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    foods: List<ActiveFood>,
    mealOnsetsMs: List<Long>,
    activityWindows: List<com.diapilot.core.analysis.ActivityWindow>,
    hourOf: (Long) -> Int,
    anchorStepMin: Double = 60.0,
    horizonMin: Double = 180.0,
    binMin: Double = 15.0,
    minPerBin: Int = 12,
    minBinsPerRegime: Int = 4,
): RegimeCorridors? {
    val global = calibrateCorridor(readings, boluses, kernel, anchorStepMin, horizonMin, binMin, foods)
    if (readings.size < 50 || kernel.isEmpty()) return null

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

    val nBins = (horizonMin / binMin).toInt()
    val bins = Regime.entries.associateWith { Array(nBins) { mutableListOf<Double>() } }
    val sortedFoods = foods.sortedBy { it.onsetMs }
    val sortedBoluses = boluses.sortedBy { it.tsMs }
    val sortedMeals = mealOnsetsMs.sorted()

    var idx = 0
    while (idx < r.size) {
        val anchor = r[idx]
        val regime = classifyRegime(
            anchor.tsMs, sortedMeals, sortedBoluses, activityWindows, hourOf(anchor.tsMs),
        )
        // Conditional band — same rule as the global fit: residuals stop at
        // the first post-anchor event (a live forecast is redrawn there).
        val horizonEnd = minOf(
            anchor.tsMs + (horizonMin * 60_000).toLong(),
            firstEventAfter(anchor.tsMs, sortedBoluses, sortedFoods),
        )
        val sim = simulateForward(
            anchor.tsMs, anchor.mmol, sortedBoluses, kernel,
            foods = sortedFoods.filter { it.knownAtMs <= anchor.tsMs },
        )
        val simByBin = sim.associateBy { ((it.tsMs - anchor.tsMs) / 60_000.0 / binMin).toInt() }
        var i = lowerBound(anchor.tsMs + 1)
        while (i < r.size && r[i].tsMs <= horizonEnd) {
            val dtMin = (r[i].tsMs - anchor.tsMs) / 60_000.0
            val bin = (dtMin / binMin).toInt()
            if (bin in 0 until nBins) {
                simByBin[bin]?.let { p ->
                    // SIGNED residual: the up/dn asymmetry is the point.
                    bins.getValue(regime)[bin].add(r[i].mmol - p.mmol)
                }
            }
            i++
        }
        idx = maxOf(idx + 1, lowerBound(anchor.tsMs + (anchorStepMin * 60_000).toLong()))
    }

    return RegimeCorridors(
        global = global,
        byRegime = Regime.entries.mapNotNull { reg ->
            corridorFromSignedBins(bins.getValue(reg), binMin, minPerBin, minBinsPerRegime)
                ?.let { reg to it }
        }.toMap(),
    )
}
