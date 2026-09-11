package com.diapilot.core.collector

/**
 * ONE STEP SOURCE — the same rule as `MeasurementStream.choose` for glucose.
 *
 * ## How this was found
 *
 * A comparison against a dedicated step-counting watch showed the database's
 * daily step totals running roughly double the watch's numbers, day after day.
 *
 * The cause was not counting but summing. Health Connect returns `StepsRecord`
 * from EVERY app that writes them, and the reader was taking all of it. There
 * are no exact duplicates (boundaries differ, `start_ms` is the primary key),
 * so nothing got overwritten: overlapping pairs of intervals were simply added
 * together.
 *
 * There are two families by duration, and **each one on its own matches the
 * watch's count**:
 *
 * | family | share of intervals | daily total |
 * |---|---|---|
 * | ~10-minute buckets | roughly a third | close to the watch's own daily count |
 * | short (0-4 min) | roughly two thirds | close to the watch's own daily count |
 * | **their sum** | all of them | **about double** the watch's count |
 *
 * Day by day the two families track each other closely — the same walking,
 * recorded twice.
 *
 * ## Why this is not cosmetic
 *
 * `ACTIVITY_STEPS_PER_MIN` = 80 steps/min sustained for >=20 minutes is a
 * threshold calibrated on this doubled data. At double counting it really
 * means 40 real steps per minute — an easy walk. Deduplicating roughly halved
 * both the number of activity windows detected and the total minutes they
 * covered.
 *
 * Activity windows thin out the fitting corpus: they down-weight ISF episodes
 * and flag meals as contaminated. Twice as many windows means twice as much
 * discarded evidence, and the whole time it looked like activity was twice as
 * high as it really was.
 *
 * ## The rule
 *
 * Take ONE source — whichever gave more intervals over the requested window.
 * Not "most complete by total steps": winning by sum would just reward
 * whichever app slices intervals more finely, and slicing granularity is a
 * property of the app, not the body.
 *
 * Rows with no recorded source (from before the source was tracked) are told
 * apart by DURATION: ten-minute buckets versus short ones. This is recovery
 * from an indirect signal, and it is named as such — newer rows carry an
 * explicit source and need no heuristic.
 */
object StepStream {

    /** Boundary between families of legacy rows, in minutes. */
    private const val LONG_BUCKET_MIN = 6.0

    /** Stream key: the explicit source, otherwise the duration family. */
    fun keyOf(bucket: StepBucket): String =
        bucket.source ?: legacyFamily(bucket)

    private fun legacyFamily(b: StepBucket): String {
        val minutes = (b.endMs - b.startMs) / 60_000.0
        return if (minutes >= LONG_BUCKET_MIN) "legacy-long" else "legacy-short"
    }

    /**
     * One stream out of the mix.
     *
     * @return the winning key's intervals, in time order. An empty input gives
     *         an empty output; a single key passes through unchanged.
     */
    fun choose(buckets: List<StepBucket>): List<StepBucket> {
        if (buckets.isEmpty()) return buckets
        val byKey = buckets.groupBy(::keyOf)
        if (byKey.size == 1) return buckets.sortedBy { it.startMs }
        val winner = byKey.maxByOrNull { it.value.size }?.key ?: return buckets
        return byKey.getValue(winner).sortedBy { it.startMs }
    }

    /** What got discarded — so the gap is COUNTABLE, not silent. */
    fun rejected(buckets: List<StepBucket>): Map<String, Int> {
        if (buckets.isEmpty()) return emptyMap()
        val byKey = buckets.groupBy(::keyOf)
        if (byKey.size <= 1) return emptyMap()
        val winner = byKey.maxByOrNull { it.value.size }?.key
        return byKey.filterKeys { it != winner }.mapValues { it.value.size }
    }
}
