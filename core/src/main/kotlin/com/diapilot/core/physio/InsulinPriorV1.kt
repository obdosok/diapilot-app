package com.diapilot.core.physio

/**
 * The cold start, stated as a PRIOR rather than served as a fact.
 *
 * The APK ships one person's trained insulin block (ISF, onset, peak, DIA)
 * fitted on somebody else's body. Previously, a fresh install received those
 * as point values, indistinguishable on screen from a measurement. A replay of
 * a real history showed a new user lives on them for the first three days;
 * ISF, whose learning path has never yet produced evidence, they may live on
 * indefinitely.
 *
 * The fix is not to delete the artifact — a population-plausible starting shape
 * is better than nothing — but to stop it impersonating a measurement:
 *
 *  - the ISF band is widened to the population range, so the forecast corridor
 *    and therefore the hypo margin reflect that we do not know this person's
 *    sensitivity. The direction is one-way safe: a wider band can only make a
 *    predicted low appear EARLIER.
 *  - the shape is flagged as a prior, and the screen says "this is NOT your data".
 *  - both are replaced landmark-by-landmark as the person's own segments
 *    accumulate.
 *
 * The bounds themselves are deliberately NOT the estimator's admission bounds
 * ([PhysioBoundsV1]): those bracket what a measurement may be coerced into,
 * which is a different question from how uncertain an unmeasured value is.
 */
object InsulinPriorV1 {
    /**
     * Population ISF band for an adult on rapid analogues, mmol/L per unit.
     *
     * Wide on purpose. The shipped point value is a single number, but a fitted
     * estimate for one real person has been seen to span a factor of three
     * depending on which method produced it. For someone we have never
     * measured, a band narrower than that is a claim we cannot support.
     */
    const val ISF_LOW_MMOL_PER_U = 1.0
    const val ISF_HIGH_MMOL_PER_U = 5.0

    /** Is this insulin block still the shipped prior rather than this person's
     * measurement? True until a measured curve has been installed. */
    fun isPrior(model: com.diapilot.core.hybrid.HybridPersonModel): Boolean =
        model.insulin.actionCdfKnots.isEmpty()

    /**
     * Widen an unmeasured insulin block to honest uncertainty.
     *
     * Only the BAND moves — the median stays where the artifact put it, because
     * a population centre is still the best guess available. What changes is
     * that the app stops claiming to know it to ±13%.
     */
    fun widen(model: com.diapilot.core.hybrid.HybridPersonModel): com.diapilot.core.hybrid.HybridPersonModel {
        if (!isPrior(model)) return model
        val low = minOf(model.insulin.isfLow, ISF_LOW_MMOL_PER_U)
        val high = maxOf(model.insulin.isfHigh, ISF_HIGH_MMOL_PER_U)
        return model.copy(insulin = model.insulin.copy(isfLow = low, isfHigh = high))
    }
}
