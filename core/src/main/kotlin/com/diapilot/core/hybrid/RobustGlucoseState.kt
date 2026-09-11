package com.diapilot.core.hybrid

import kotlin.math.abs

/**
 * A causal, robust estimate of the glucose level at [atMs].
 *
 * CGM minute packets can alternate by tens of mg/dL while the underlying
 * glucose state barely changes.  A plain average lags a real rise/fall and the
 * latest packet makes the whole forecast jump.  Theil-Sen keeps a genuine
 * sustained slope while ignoring isolated level spikes; the median projected
 * level is the latent state used by the forward model.
 *
 * This is a sensor observation filter, not a physiological/person parameter.
 */
data class RobustGlucoseState(
    val levelMmol: Double,
    val slopeMmolPerMin: Double,
    val latestResidualMmol: Double,
    val pointsUsed: Int,
)

fun robustGlucoseState(
    points: List<HybridGlucosePoint>,
    atMs: Long,
    windowMin: Int = 15,
): RobustGlucoseState? {
    val windowStart = atMs - windowMin.coerceAtLeast(1) * 60_000L
    val rows = points.asSequence()
        .filter { it.tsMs in windowStart..atMs && it.mmol.isFinite() }
        .distinctBy { it.tsMs }
        .sortedBy { it.tsMs }
        .toList()
    val latest = rows.lastOrNull() ?: return null
    if (rows.size < 4) {
        return RobustGlucoseState(latest.mmol, 0.0, 0.0, rows.size)
    }

    // Ignore sub-minute pairs: repeated packets at almost the same timestamp
    // otherwise manufacture enormous slopes from harmless transport jitter.
    val slopes = ArrayList<Double>(rows.size * rows.size / 2)
    for (i in 0 until rows.lastIndex) {
        for (j in i + 1 until rows.size) {
            val dtMin = (rows[j].tsMs - rows[i].tsMs) / 60_000.0
            if (dtMin >= 1.0) slopes += (rows[j].mmol - rows[i].mmol) / dtMin
        }
    }
    val slope = median(slopes) ?: 0.0
    val projected = rows.map { row ->
        row.mmol + slope * (atMs - row.tsMs) / 60_000.0
    }
    // A trimmed centre is steadier than a plain median for an alternating
    // high/low stream with an odd number of packets: the median would flip
    // between the two rails merely because one rail currently has one extra
    // sample.
    val firstLevel = trimmedMean(projected) ?: latest.mmol

    // One refinement rejects large deviations from the robust line using a
    // data-derived MAD.  No glucose/amplitude threshold is person-specific.
    val deviations = rows.map { row ->
        abs(row.mmol - (firstLevel - slope * (atMs - row.tsMs) / 60_000.0))
    }
    val mad = median(deviations) ?: 0.0
    val retained = if (mad > 0.0) {
        projected.filterIndexed { index, _ -> deviations[index] <= 3.0 * mad }
    } else projected
    val level = trimmedMean(retained) ?: firstLevel
    return RobustGlucoseState(
        levelMmol = level,
        slopeMmolPerMin = slope,
        latestResidualMmol = latest.mmol - level,
        pointsUsed = rows.size,
    )
}

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid]
    else (sorted[mid - 1] + sorted[mid]) / 2.0
}

private fun trimmedMean(values: List<Double>, trimFraction: Double = 0.20): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val trim = (sorted.size * trimFraction).toInt()
        .coerceAtMost((sorted.size - 1) / 2)
    val kept = sorted.subList(trim, sorted.size - trim)
    return kept.average()
}

/**
 * C1 bridge from the observed slope back to an existing forecast.
 * The correction is exactly zero (with zero derivative) at [durationMin], so
 * it cannot move the model's medium/long-term target.
 */
fun slopeBridgeDelta(
    horizonMin: Double,
    durationMin: Double,
    stepMin: Double,
    anchorMmol: Double,
    firstForecastMmol: Double,
    observedSlopeMmolPerMin: Double,
): Double {
    if (horizonMin <= 0.0 || horizonMin >= durationMin ||
        stepMin <= 0.0 || stepMin >= durationMin
    ) return 0.0
    val firstShape = stepMin * (1.0 - stepMin / durationMin).let { it * it }
    val correction = (
        observedSlopeMmolPerMin * stepMin - (firstForecastMmol - anchorMmol)
    ) / firstShape.coerceAtLeast(1e-9)
    val remaining = 1.0 - horizonMin / durationMin
    return correction * horizonMin * remaining * remaining
}
