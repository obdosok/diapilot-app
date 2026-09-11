package com.diapilot.core.analysis

import com.diapilot.core.collector.StepBucket
import kotlin.math.exp
import kotlin.math.min

/**
 * Recent movement, decayed, as the forecast state carries it.
 *
 * MOVED HERE BECAUSE THE BENCH WAS BLIND TO IT. This arithmetic lived only in
 * `HybridShadow`, an app file the harness cannot call, so every off-device
 * measurement built its state without `activityExposure` — every episode fit,
 * every walk-forward, every study run this week modelled a body that never
 * moves. Production passes it; the stand did not. That is the instrument-versus-
 * model defect the project keeps paying for (discipline #7), and it is the same
 * shape as the basal history this bench was also silently omitting.
 *
 * Porting the formula into `tools/` would have reproduced the `HarnessFoods`
 * problem — two spellings of one calculation that drift apart and produce
 * confident wrong numbers. So it lives in core as a PURE function over buckets:
 * the app hands it `store.steps(...)`, the harness hands it `db.steps(...)`, and
 * there is one implementation to be wrong.
 *
 * WHAT IT CANNOT DO, and it matters for how the number is read. This is a
 * SCALAR evaluated at one instant — «how much movement is behind me now». A
 * forecast started at 16:30 therefore knows nothing about a walk at 17:00, and
 * no value of tau changes that. So activity explains a fall the model missed
 * only when the movement PRECEDED the anchor; a fall that begins before the
 * steps do is not this term's doing, and reaching for it there is fitting a
 * story to a coincidence.
 */
object ActivityExposureV1 {

    private const val MINUTE_MS = 60_000L

    /** Six hours of lookback: beyond that the decay has taken any bucket to noise. */
    const val LOOKBACK_MIN = 360L

    /**
     * THE CEILING ON EXPOSURE.
     *
     * `HybridForecastEngine` turns this number into an insulin multiplier of
     * `1 + iobGamma * min(1.5, exposure)`, which tops out at 2.5x with the
     * shipped gamma of 1.0. The MULTIPLIER was bounded; the RESULT was not. An
     * exposure spike above 2 turned an ordinary correction dose into an
     * amount of removal that drove the forecast to a physiologically
     * impossible negative reading while measured glucose actually ROSE. A
     * second episode did the same at a lower exposure. Those two episodes are
     * the two worst in the corpus by a wide margin over a typical error, and
     * a forecast no body can reach protects nobody: live it would raise a
     * hypo alarm about a number that cannot happen.
     *
     * THE TERM IS INERT ALMOST ALWAYS. Across 41 episodes the median exposure is
     * 0.03 and the third quartile 0.17; five exceed 0.5 and two exceed 1.5. So
     * this ceiling changes nothing on nine days in ten.
     *
     * A CEILING, NOT A REMOVAL. Switching activity off scores better
     * retrospectively (MAE@180 3.14 -> 2.49, shape 1.950 -> 1.803) but the
     * day-paired gate makes it a coin flip — better on 14 days, worse on 10 — and
     * the case the term exists for is a FORECAST case: inject, then go running.
     * At this ceiling the gate reads better on 5 days and worse on 1.
     *
     * WHY HERE AND NOT IN THE ENGINE. `HybridForecastEngineContractTest` and
     * `HybridBlindDayParityTest` pin the engine against the frozen Python v11
     * artifact at 1e-9, and one of those fixtures carries an exposure of 0.35.
     * Clamping inside the engine breaks that cross-language contract for a
     * reason that has nothing to do with the contract. Clamping at the point
     * where exposure is MEASURED keeps the physics identical across languages
     * and bounds the input, which is what the gate actually scored — it capped
     * the episode's exposure field, not the engine's arithmetic. Both the app
     * (`HybridShadow.activityExposure`) and the bench (`WalkForwardStand`) read
     * this function, so the two stay in step (discipline #7).
     */
    const val MAX_EXPOSURE = 0.30

    /** Below this cadence a bucket is standing up, not moving. */
    private const val MIN_RATE_PER_MIN = 40.0

    /**
     * @param buckets step buckets overlapping `[atMs - LOOKBACK_MIN, atMs]`.
     *   Extra buckets outside the window are ignored, so a caller may pass a
     *   wider slice without changing the answer.
     * @param oldestMs the era clamp the caller applies — exposure must not reach
     *   back past the point where the record begins.
     */
    fun at(buckets: List<StepBucket>, atMs: Long, tauMin: Double, oldestMs: Long): Double {
        if (tauMin <= 0.0) return 0.0
        val oldest = maxOf(oldestMs, atMs - LOOKBACK_MIN * MINUTE_MS)
        var total = 0.0
        for (bucket in buckets.sortedBy { it.startMs }) {
            if (bucket.endMs <= oldest) continue
            if (bucket.startMs >= atMs) break
            val durationMin = (bucket.endMs - bucket.startMs).toDouble() / MINUTE_MS
            if (durationMin <= 0.0) continue
            val overlap = maxOf(0L, minOf(bucket.endMs, atMs) - maxOf(bucket.startMs, oldest))
            if (overlap <= 0L) continue
            val rate = min(180.0, bucket.count / durationMin)
            if (rate >= MIN_RATE_PER_MIN) {
                val lag = maxOf(0.0, (atMs - minOf(bucket.endMs, atMs)).toDouble() / MINUTE_MS)
                total += overlap.toDouble() / MINUTE_MS * min(2.0, rate / 60.0) * exp(-lag / tauMin)
            }
        }
        return min(MAX_EXPOSURE, total / 60.0)
    }
}
