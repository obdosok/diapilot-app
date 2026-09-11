package com.diapilot.core.physio

/**
 * ISF FROM THE DAY'S BALANCE — the estimator that replaced the replay.
 *
 * The user's own framing:
 *
 * > the crudest ISF estimate — the day's total carb grams divided by the day's
 * > total insulin units, plus a correction for the start and end glucose.
 * > The amplitude is the sum; every other knob is timing.
 *
 * The split is exactly right, and it is why this belongs on the amplitude axis
 * and nowhere near the timings. Over a whole day
 *
 *     carbs_g x CS  -  units x ISF  +  (basal - hepatic output)  =  dG
 *
 * and every timing parameter — gastric emptying, the sieve, the carb spread, the
 * insulin landmarks — describes only HOW the two sides are distributed inside
 * the day. Integrate over the day and they cancel out of the equation entirely.
 * That is what makes this estimator immune to a wrong insulin shape, which the
 * replay-based one very much is not.
 *
 * WHY IT REPLACED THE FORECAST-ERROR FIT (the user's decision, and measured)
 *
 * The replay fit swept fifteen ISF candidates over every episode in a trailing
 * window — thousands of full forecast replays — and returned a number that was
 * systematically 0.3–0.7 mmol BELOW this one on nearly every scored day, hugging
 * the floor of its own candidate grid. Two independent facts say the low answer
 * is the wrong one: this balance's corpus median comes out close to the
 * hand-set value, and a sweep along the ISF axis is a known-biased meter
 * (weaker insulin predicts a smaller fall, so it always scores better on the
 * columns that count dips — discipline #8).
 *
 * ⚠ THE HONEST COST, measured the same day in the same walk-forward. On held-out
 * days this estimator leaves a residual bias of about +2.4 mmol at the horizon
 * and 7 false hypo alarms, where the replay fit left +0.43 and none. Fixing the
 * amplitude does NOT fix the forecast: whatever is causing that bias is not ISF,
 * and it stays unfixed by this change. What the balance does buy, besides being
 * ~1000x cheaper, is that on a 21-day window it was the ONLY arm that caught
 * both real hypos and missed neither.
 */
object DailyBalanceIsfV1 {

    /**
     * The day boundary is 04:00, NOT midnight.
     *
     * At four in the morning the user is asleep and has not eaten, so both endpoint
     * glucose readings are taken in a settled state rather than in the middle of
     * dinner's tail. A midnight boundary charges the previous evening's
     * unfinished excursion to the next day.
     */
    const val BOUNDARY_HOUR = 4

    /**
     * A day with almost no food or almost no insulin does not carry a ratio —
     * it carries a division by something near zero. Such days are dropped
     * rather than clamped, because a clamped ratio still looks like a
     * measurement.
     */
    const val MIN_CARBS_G = 40.0
    const val MIN_UNITS = 4.0

    /** Below this the median is one atypical day. */
    const val MIN_DAYS = 3

    /**
     * Ten days, matching the window the adaptive path already used.
     *
     * Measured on the corpus: the 21-day window settles on one lagging figure,
     * while the 10-day one moves within a real range depending on where it
     * sits, so the length is NOT cosmetic here —
     * it is the difference between tracking the drift and averaging over it. Ten
     * tracks; twenty-one lags.
     */
    const val WINDOW_DAYS = 10

    data class Day(
        val startMs: Long,
        val carbs: Double,
        val units: Double,
        /** Glucose at the day's end minus glucose at its start. */
        val deltaMmol: Double,
    ) {
        /** Grams per unit — the crudest form, with no glucose correction at all. */
        val gramsPerUnit: Double get() = carbs / units

        /** The same balance with the endpoints paid for. */
        fun isf(carbSens: Double): Double = (carbs * carbSens - deltaMmol) / units
    }

    data class Result(
        val isf: Double,
        val days: Int,
        val fromMs: Long,
        val toMs: Long,
        val perDay: List<Double>,
        /**
         * The days themselves, so a screen can show the arithmetic rather than
         * its median. Asked for, to replace the card the retired
         * episode pipeline used to draw: that one asked the same question PER
         * EPISODE, where food, activity, hepatic output and sensor error are
         * observationally interchangeable with insulin amplitude — its own
         * comment said so. Over a whole day they are not.
         */
        val rows: List<Day> = emptyList(),
    ) {
        /** Spread across the window — a wide one means the median is not a level. */
        val spread: Pair<Double, Double>? get() =
            perDay.minOrNull()?.let { lo -> perDay.maxOrNull()?.let { hi -> lo to hi } }
    }

    /**
     * @param dayStarts each day's 04:00 boundary, ascending, oldest first. The
     *        caller owns the calendar so this file stays free of a timezone.
     * @param carbSens mmol per gram — the LEARNED global value, not a constant.
     *        ISF scales linearly with it, so a hardcoded one here would quietly
     *        put a population number inside a personal measurement.
     */
    fun estimate(
        dayStarts: List<Long>,
        dayMs: Long,
        carbSens: Double,
        carbsIn: (Long, Long) -> Double,
        unitsIn: (Long, Long) -> Double,
        glucoseAt: (Long) -> Double?,
    ): Result? {
        if (carbSens <= 0.0) return null
        val rows = dayStarts.mapNotNull { d ->
            val end = d + dayMs
            val g0 = glucoseAt(d) ?: return@mapNotNull null
            val g1 = glucoseAt(end) ?: return@mapNotNull null
            val carbs = carbsIn(d, end)
            val units = unitsIn(d, end)
            if (carbs < MIN_CARBS_G || units < MIN_UNITS) return@mapNotNull null
            Day(d, carbs, units, g1 - g0)
        }
        if (rows.size < MIN_DAYS) return null
        val values = rows.map { it.isf(carbSens) }
        return Result(
            isf = median(values),
            days = rows.size,
            fromMs = rows.first().startMs,
            toMs = rows.last().startMs + dayMs,
            perDay = values,
            rows = rows,
        )
    }

    private fun median(v: List<Double>): Double {
        val s = v.filter { it.isFinite() }.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }
}
