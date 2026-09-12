/**
 * The metric functions themselves — pure, and deliberately kept apart from
 * [LedgerReader] so they can be tested on hand-written vectors (MetricsTest)
 * instead of a fake database. Nothing here opens a connection or knows what
 * SQL is.
 */
package com.diapilot.tools.accuracy

import kotlin.math.abs
import kotlin.math.sqrt

/** One matched (predicted, actual) pair at a single forecast horizon. */
data class ErrorSample(val predictedMmol: Double, val actualMmol: Double)

/**
 * Bias, MAE and RMSE over a list of matched pairs.
 *
 * Sign convention matches `ForecastLedger.Quality`: bias = mean(actual −
 * predicted), so a POSITIVE bias means the model read low (M5 in the audit).
 */
data class ErrorStats(
    val n: Int,
    val biasMmol: Double,
    val maeMmol: Double,
    val rmseMmol: Double,
)

/** Null (not zero) when there is nothing to score — an empty horizon must
 *  read as "no data", never as a perfect 0.0. */
fun errorStats(samples: List<ErrorSample>): ErrorStats? {
    if (samples.isEmpty()) return null
    val diffs = samples.map { it.actualMmol - it.predictedMmol }
    val bias = diffs.average()
    val mae = diffs.map(::abs).average()
    val rmse = sqrt(diffs.map { it * it }.average())
    return ErrorStats(samples.size, bias, mae, rmse)
}

/**
 * Nearest reading to [targetTsMs] within [toleranceMs], or null.
 * [readingsAsc] must be sorted ascending by `tsMs` — callers read it once
 * from the database and reuse it, so the sort is their job, not this
 * function's, to keep this a single binary search rather than an implicit
 * one per call.
 *
 * The default tolerance mirrors `ForecastLedger`'s own
 * `FACT_TOLERANCE_MS` (6 minutes) — this is the ledger's own definition of
 * "what counts as the fact for a target", not a value invented for this
 * report.
 */
fun nearestReading(
    readingsAsc: List<GlucoseReadingRow>,
    targetTsMs: Long,
    toleranceMs: Long = 6L * 60_000,
): GlucoseReadingRow? {
    if (readingsAsc.isEmpty()) return null
    var lo = 0
    var hi = readingsAsc.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (readingsAsc[mid].tsMs < targetTsMs) lo = mid + 1 else hi = mid
    }
    return listOfNotNull(readingsAsc.getOrNull(lo - 1), readingsAsc.getOrNull(lo))
        .filter { abs(it.tsMs - targetTsMs) <= toleranceMs }
        .minByOrNull { abs(it.tsMs - targetTsMs) }
}

/**
 * Match every stored point at [horizonMin] to the nearest reading within
 * [toleranceMs] of its target, dropping points with no matching fact
 * (still in the future, or a sensor hole — both silently absent from
 * `forecast_scores`' replacement too, see `ForecastLedger`'s "THE FACT IS
 * COMPUTED, NOT STORED").
 */
fun matchHorizon(
    points: List<ForecastPointRow>,
    readingsAsc: List<GlucoseReadingRow>,
    horizonMin: Int,
    toleranceMs: Long = 6L * 60_000,
): List<ErrorSample> =
    points.filter { it.horizonMin == horizonMin }
        .mapNotNull { p ->
            nearestReading(readingsAsc, p.targetTsMs, toleranceMs)
                ?.let { ErrorSample(predictedMmol = p.mmol, actualMmol = it.mmol) }
        }

/** One run's hypo-alert outcome: did the stored forecast cross the
 *  threshold, and did a real reading later confirm it. */
data class HypoOutcome(val predictedHypo: Boolean, val actualHypo: Boolean)

data class HypoConfusion(val truePos: Int, val falsePos: Int, val falseNeg: Int, val trueNeg: Int) {
    val n: Int get() = truePos + falsePos + falseNeg + trueNeg

    /** Null (not zero) when nothing was ever predicted — an undefined ratio,
     *  not a bad one. */
    val precision: Double? get() = (truePos + falsePos).takeIf { it > 0 }?.let { truePos.toDouble() / it }
    val recall: Double? get() = (truePos + falseNeg).takeIf { it > 0 }?.let { truePos.toDouble() / it }
}

fun hypoConfusion(outcomes: List<HypoOutcome>): HypoConfusion {
    var tp = 0
    var fp = 0
    var fn = 0
    var tn = 0
    for (o in outcomes) when {
        o.predictedHypo && o.actualHypo -> tp++
        o.predictedHypo && !o.actualHypo -> fp++
        !o.predictedHypo && o.actualHypo -> fn++
        else -> tn++
    }
    return HypoConfusion(tp, fp, fn, tn)
}

/**
 * Evaluate the hypo alert one `hypo_alert` run at a time, against what the
 * ledger actually shows — NOT a recomputation of `findPredictedHypo` (core's
 * `HypoAlert.kt`). That function walks the dense 5-minute forecast the twin
 * held in memory; the ledger only ever stored the six fixed horizons
 * (`ForecastLedger.HORIZONS`), so this is the coarser question "did any
 * stored point inside the lead window read below threshold" — a deliberately
 * simpler rule than the live one, documented as such in docs/accuracy.md
 * rather than silently passed off as identical.
 *
 * - predicted = true iff some point of that run, at a horizon within
 *   [leadMaxMin] of the anchor, has `mmol < thresholdMmol`.
 * - actual = true iff some glucose reading within [leadMaxMin] of the
 *   anchor (plus [factToleranceMs] of slack, so a reading landing just
 *   outside the window by sensor jitter still counts) has `mmol <
 *   thresholdMmol`.
 *
 * Runs with no points (refusals — `algoVersion ==
 * ForecastLedger.REFUSAL_ALGO_VERSION`) are excluded by the caller before
 * this function ever sees them: a refusal is "the app did not look", not
 * "the app looked and predicted no hypo", and folding it into precision or
 * recall would silently reward or punish the wrong thing. Count refusals
 * separately.
 */
fun evaluateHypoAlert(
    runs: List<RunRow>,
    pointsByRun: Map<Long, List<ForecastPointRow>>,
    readingsAsc: List<GlucoseReadingRow>,
    thresholdMmol: Double = 3.9,
    leadMaxMin: Double = 45.0,
    factToleranceMs: Long = 6L * 60_000,
): List<HypoOutcome> {
    val leadMs = (leadMaxMin * 60_000).toLong()
    // `readingsAsc` runs to hundreds of thousands of rows on a real phone and
    // `hypo_alert` runs are NOT thinned (see ForecastLedger.record) — a scan
    // per run here would be quadratic on exactly the table that is largest.
    // Binary search once per run instead.
    return runs.map { run ->
        val points = pointsByRun[run.id].orEmpty()
        val predicted = points.any { p ->
            val offset = p.targetTsMs - run.anchorTsMs
            offset in 0..leadMs && p.mmol < thresholdMmol
        }
        val actual = readingsInWindow(
            readingsAsc, run.anchorTsMs, run.anchorTsMs + leadMs + factToleranceMs,
        ).any { it.mmol < thresholdMmol }
        HypoOutcome(predictedHypo = predicted, actualHypo = actual)
    }
}

/** Readings with `tsMs` in `[fromTsMs, toTsMs]`, located by binary search on
 *  the ascending list rather than a linear scan. */
private fun readingsInWindow(
    readingsAsc: List<GlucoseReadingRow>,
    fromTsMs: Long,
    toTsMs: Long,
): List<GlucoseReadingRow> {
    var lo = 0
    var hi = readingsAsc.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (readingsAsc[mid].tsMs < fromTsMs) lo = mid + 1 else hi = mid
    }
    val out = mutableListOf<GlucoseReadingRow>()
    var i = lo
    while (i < readingsAsc.size && readingsAsc[i].tsMs <= toTsMs) {
        out += readingsAsc[i]
        i++
    }
    return out
}
