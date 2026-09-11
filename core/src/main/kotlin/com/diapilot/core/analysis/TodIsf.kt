/**
 * Diurnal insulin sensitivity in the prediction: the user's evening unit is
 * measurably weaker than the morning one (aggregateByTod already knows it) —
 * the kernel amplitude follows the hour instead of pretending the day is
 * flat. The RATIO between buckets survives even when the absolute scale is
 * diluted (episodes recorded before food logging), so the factor composes
 * cleanly with the user's ISF prior.
 */
package com.diapilot.core.analysis

/**
 * Kernel scale factor for [hour]: bucket median vs the episode-weighted
 * overall median. 1.0 when the bucket is thin (INSUFFICIENT) or unknown.
 * Clamped — a bucket ratio must temper the curve, not replace it.
 */
fun todIsfFactor(byTod: Map<TodBucket, IsfAggregate>, hour: Int): Double {
    val agg = byTod[TodBucket.of(hour)] ?: return 1.0
    val median = agg.median ?: return 1.0
    if (agg.confidence == Confidence.INSUFFICIENT) return 1.0
    val known = byTod.values.filter { it.median != null && it.nValid > 0 }
    val totalN = known.sumOf { it.nValid }
    if (totalN == 0) return 1.0
    val overall = known.sumOf { it.median!! * it.nValid } / totalN
    if (overall <= 1e-9) return 1.0
    return (median / overall).coerceIn(0.7, 1.4)
}

/** The kernel with its amplitude scaled by [factor] (shape untouched). */
fun scaleKernel(kernel: List<KernelPoint>, factor: Double): List<KernelPoint> =
    if (factor == 1.0) kernel
    else kernel.map { it.copy(median = it.median * factor, q1 = it.q1 * factor, q3 = it.q3 * factor) }
