package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridCdfKnot

/**
 * The measured insulin timing curve the whole app runs on.
 *
 * Produced by `InsulinProfileRuntime` from per-segment landmarks — see
 * [SegmentLandmarkReaderV1] and the method settled with the user there.
 *
 * What used to live in this file — a receipt-CDF aggregator with its own
 * admission rules, support counters and isotonic regression — was deleted.
 * It had become a SECOND implementation of this quantity, reachable
 * only through a settings preview that drew a different curve beside the real
 * one. The audit that found it started from the user's question «which
 * constants are actually in use right now, or is this from an old model».
 */
data class PersonalInsulinCurveV1(
    /** The smooth, model-facing curve: what forecast, IOB, deconvolution and
     * What-if all integrate. */
    val knots:List<HybridCdfKnot>,
    val observations:Int,
    val independentDays:Int,
    val supportWeight:Double,
    /**
     * The landmarks this curve was built on — measured, then coerced into the
     * artifact's domain.  Carried on the curve so every consumer reads ONE
     * onset/peak/tail instead of re-deriving its own from the knots.
     *
     * This is the APPLIED set: what the model runs, and what the Auto-fit
     * corridor is centred on. For what the doses actually showed, read
     * [measuredLandmarks] — the two differ by exactly [coerced].
     */
    val landmarks:InsulinShapeLandmarksV1,
    /**
     * The same landmarks BEFORE the domain was applied — as measured.
     *
     * Added because the card read [landmarks] and so printed the artifact's own
     * floor under the words "measured from N doses": a user whose doses said
     * the action ends at 180 min was shown 240, and had to infer the
     * substitution from a separate clause further along the line. A measurement
     * may be corrected on its way into the model; the number attributed to the
     * measurement must stay the measurement (audit M1).
     *
     * Defaults to [landmarks] so a caller with nothing to distinguish reads the
     * same value twice rather than a null.
     */
    val measuredLandmarks:InsulinShapeLandmarksV1 = landmarks,
    /** The raw aggregate before smoothing, for display and audit. */
    val measuredKnots:List<HybridCdfKnot> = knots,
    /**
     * Landmarks that had to be moved to satisfy [PhysioBoundsV1], with from/to.
     *
     * Named rather than hidden: a coerced tail is still a measurement, and the
     * user is entitled to know the app moved their number and by how much.
     */
    val coerced:List<CoercedLandmark> = emptyList(),
    /** Were untagged (P3) doses needed to reach support? */
    val usesUntagged:Boolean = false,
) {
    val ready:Boolean get() =
        observations>=MIN_SHAPE_OBSERVATIONS&&independentDays>=MIN_SHAPE_DAYS&&knots.size>=4

    companion object {
        /**
         * Fewest doses and fewest DISTINCT days before a measured curve replaces
         * the prior.
         *
         * Days matter separately from doses: four segments from one afternoon
         * describe one afternoon. Replayed over the user's own history these
         * put the first usable curve on day 3.
         */
        const val MIN_SHAPE_OBSERVATIONS=4
        const val MIN_SHAPE_DAYS=3
    }
}
