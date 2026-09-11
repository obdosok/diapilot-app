package com.diapilot.core.twin

/**
 * "HIGH FOR N MINUTES" — a state, not a crossing.
 *
 * A gap identified by two independent external reviews and confirmed by the
 * user's own data: the user spends **37.2% of time above 10 mmol/L and 8.7%
 * above 13.9** — the largest measured problem, twice the TIR deviation.
 * Nothing currently serves it.
 *
 * Why [findPredictedHigh] does not fit here, and this is NOT a defect in it:
 * it is a CROSSING detector, and its first line refuses to run when glucose
 * is already high (`if (currentMmol > thresholdMmol - marginMmol) return
 * null`). A crossing that has already happened cannot be caught. A second,
 * separate question is needed: "how long has this been going on."
 *
 * ## The threshold was measured, not eyeballed
 *
 * Run against several weeks of the user's own data, with a 120-minute cooldown:
 *
 * | threshold | ≥45 min | ≥60 min | ≥90 min |
 * |---|---|---|---|
 * | 10.0 | 4.55/day | 4.44/day | 3.39/day |
 * | 11.0 | 3.57 | 3.13 | 2.40 |
 * | **13.9** | 0.98 | **0.87** | 0.47 |
 *
 * At the top of the target range (10.0) that is four and a half alarms a
 * day — background noise that gets muted within a week, after which there is
 * no alert at all. At 13.9 it is 0.87 per day. 13.9 and one hour were chosen
 * because 13.9 is the clinical threshold for "very high," and at that level
 * the message stays an event rather than noise.
 *
 * ## What it does NOT do
 *
 * It does not suggest a dose and does not compute a correction — like
 * everything else here. It reports a FACT ("high for this many minutes above
 * this level") and points back to the user's own plan. No forecast is needed
 * for this, and it deliberately does not read one: this is an observation
 * from readings, so it works even when the model cannot be trusted — which is
 * exactly when the model tends to be most limited.
 */
object SustainedHigh {

    /** Clinical threshold for "very high." Below it the frequency becomes noise. */
    const val THRESHOLD_MMOL = 13.9

    /** How long it must last before it stops being just an excursion. */
    const val SUSTAINED_MIN = 60L

    /** Pause between messages about ONE and the same sustained episode. */
    const val COOLDOWN_MIN = 120L

    /**
     * Gap in readings after which the count restarts.
     *
     * Without it, a gap in observation (roughly 0.6 per day, ≥25 minutes long)
     * would silently be counted as "high the whole time" — discipline #6:
     * readings are backfilled, and a missing point does not mean an event did
     * not happen, but it also does not mean it did.
     */
    const val MAX_GAP_MIN = 20L

    data class Verdict(
        /** How many minutes in a row were observed above the threshold. */
        val minutesAbove: Long,
        /** The worst value within that stretch. */
        val peakMmol: Double,
    )

    /**
     * @param readings calibrated readings in ascending time order; the same
     *        ones shown on the graph. On the raw feed the threshold means a
     *        different number (M-123).
     * @param nowMs the moment of evaluation; the tail is counted back from it.
     */
    fun evaluate(
        readings: List<Pair<Long, Double>>,
        nowMs: Long,
        thresholdMmol: Double = THRESHOLD_MMOL,
        sustainedMin: Long = SUSTAINED_MIN,
        maxGapMin: Long = MAX_GAP_MIN,
    ): Verdict? {
        if (readings.isEmpty()) return null
        val sorted = readings.sortedBy { it.first }
        val last = sorted.last()
        // Freshness: a stale tail says nothing about what is happening NOW.
        if (nowMs - last.first > maxGapMin * 60_000) return null
        if (last.second <= thresholdMmol) return null

        var startMs = last.first
        var peak = last.second
        var previousMs = last.first
        for (i in sorted.indices.reversed()) {
            val (ts, mmol) = sorted[i]
            if (previousMs - ts > maxGapMin * 60_000) break   // gap — count breaks off
            if (mmol <= thresholdMmol) break
            startMs = ts
            if (mmol > peak) peak = mmol
            previousMs = ts
        }
        val minutes = (last.first - startMs) / 60_000
        return if (minutes >= sustainedMin) Verdict(minutes, peak) else null
    }
}
