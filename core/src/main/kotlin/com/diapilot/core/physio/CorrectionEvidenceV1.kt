package com.diapilot.core.physio

import com.diapilot.core.collector.GlucosePoint

/**
 * Which doses BEHAVED like corrections — without anybody labelling them.
 *
 * The user's question: «what if corrections in the database are not labelled?
 * we need a way to work it out ourselves». It is the right question:
 * on real devices only a minority of era boluses carry a label, and a new user
 * would arrive with none at all, so a pipeline that needs labels to estimate ISF
 * has no cold start.
 *
 * The answer uses an order already established: timing first, from every dose and
 * needing no labels; then this, which asks the line a question the timing makes
 * answerable.
 *
 * **A correction is a dose with no carbohydrate under it.** That is not a
 * statement about intent, which is what a label records — it is a statement
 * about the trace, and the trace shows it directly: carbohydrate PUSHES GLUCOSE
 * UP. Over a dose's own clean window, a meal bolus leaves the line above the
 * pre-dose inertia at some point; a correction never does. So the primary
 * evidence is the largest EXCURSION ABOVE the held baseline, in mmol/L, and it
 * is close to definitional rather than fitted.
 *
 * Deliberately NOT part of the decision, though both are reported:
 *
 *  - **How well the fall matches the action curve.** It is circular for this
 *    purpose — the curve is what we are trying to calibrate — and it is also
 *    the wrong test: a correction given during an unlogged walk falls faster
 *    than the curve and would be rejected for behaving unusually rather than
 *    for having carbs under it.
 *  - **Whether food was logged nearby.** Absence of a log is not absence of
 *    food; food logging on real devices starts later than dose logging and
 *    remains partial. It
 *    is reported so a caller can prefer a dose that is clean on both counts,
 *    never to admit one that is not clean on the trace.
 */
data class CorrectionEvidenceV1(
    val bolusTsMs: Long,
    /**
     * Largest excursion of the line ABOVE its pre-dose inertia, mmol/L.
     *
     * The carbohydrate signature. Near zero on a correction, clearly positive
     * whenever a meal is being covered.
     */
    val riseAboveBaselineMmol: Double,
    /** Where the pre-dose glucose sits in this person's OWN distribution. */
    val preGlucosePercentile: Double,
    /** Diagnostic only — see the class note on why it does not decide. */
    val fallVsBaselineMmol: Double,
    val loggedFoodNearby: Boolean,
) {
    /**
     * The trace shows no carbohydrate under this dose.
     *
     * One threshold, in the units of the measurement, at the scale of CGM noise
     * over a smoothed line. It is not tuned against the user's labels — those
     * are held back to VALIDATE this, and a threshold fitted to them would make
     * the validation meaningless.
     */
    val carbFree: Boolean get() = riseAboveBaselineMmol <= CARB_RISE_TOLERANCE_MMOL

    /** And it actually did something worth measuring an ISF from. */
    val usableForIsf: Boolean get() = carbFree && fallVsBaselineMmol >= MIN_FALL_MMOL

    companion object {
        /**
         * A rise this small is sensor wobble on a smoothed line, not food.
         *
         * Chosen at the scale of the instrument rather than from the user's
         * labelled set — see [carbFree]. Its effect is measured, not assumed:
         * `study corrfind` reports the separation against the user's own labels.
         */
        const val CARB_RISE_TOLERANCE_MMOL = 1.0

        /** Below this the dose moved nothing and divides into noise. */
        const val MIN_FALL_MMOL = 1.0
    }
}

object CorrectionFinderV1 {
    /**
     * @param cleanUntilMs when the next injection or new meal lands.
     * @param glucoseDistribution this person's own readings, for the percentile.
     *   Person-relative on purpose: «high» is not a number that transfers.
     */
    fun evaluate(
        readings: List<GlucosePoint>,
        bolusTsMs: Long,
        cleanUntilMs: Long,
        glucoseDistribution: List<Double>,
        loggedFoodNearby: Boolean,
        noiseMultiple: Double = SensorCorrectionReaderV1.DEPARTURE_NOISE_MULTIPLE,
    ): CorrectionEvidenceV1? {
        val trace = SensorCorrectionReaderV1.trace(
            readings, bolusTsMs, cleanUntilMs, noiseMultiple,
            SegmentLandmarkReaderV1.MIN_AFTER_ONSET,
        ) ?: return null
        // Held, not extrapolated — the same inertia the landmark reader uses, so
        // «above the baseline» means one thing in this app.
        fun baseline(t: Double) =
            trace.g0 + trace.baseSlopeMmolPerH * minOf(t / 60.0, BACKGROUND_TREND_HOLD_H)
        val rise = trace.after.maxOfOrNull { (t, v) -> v - baseline(t) } ?: 0.0
        val fall = trace.after.maxOfOrNull { (t, v) -> baseline(t) - v } ?: 0.0
        val sorted = glucoseDistribution.sorted()
        val percentile = if (sorted.isEmpty()) .5 else
            sorted.count { it <= trace.g0 }.toDouble() / sorted.size
        return CorrectionEvidenceV1(
            bolusTsMs = bolusTsMs,
            riseAboveBaselineMmol = rise.coerceAtLeast(0.0),
            preGlucosePercentile = percentile,
            fallVsBaselineMmol = fall.coerceAtLeast(0.0),
            loggedFoodNearby = loggedFoodNearby,
        )
    }
}
