package com.diapilot.core.analysis

/**
 * Where a NEW user's carbohydrate sensitivity comes from on day one: their body
 * weight.
 *
 * THE THREE-TIER DESIGN THIS BELONGS TO:
 *
 *  1. **weight** — a prior that works from the first meal, for anybody;
 *  2. **episodes** — analysis refines it as data arrives;
 *  3. **meals with no bolus** — where they exist, they check it, because with no
 *     insulin in play the rise per gram is measured rather than inferred.
 *
 * This file is tier one. It exists because the incumbent prior was a single
 * population number identical for every body, and a 55 kg person and a 95 kg
 * person do not share a glucose distribution volume.
 *
 * THE FORM, AND WHY IT IS A DIVISION RATHER THAN A FIT. One gram of glucose is
 * 5.55 mmol, and it distributes through a volume that scales with body mass —
 * roughly 0.16 L/kg for glucose. That alone would give
 * `5.55 / (0.16 x weight)` = 0.50 mmol/L per gram at 70 kg, about twice what is
 * ever observed, because a good half never presents as a measurable rise:
 * first-pass hepatic uptake, ongoing clearance during absorption, and the
 * fraction still in the gut when the peak is read. Folding that into one
 * empirical factor leaves an inverse law in weight, which is the part the
 * physiology actually dictates.
 *
 * So the reference point is stated instead of derived, and everything else
 * follows by mass:
 *
 *     cs(weight) = REFERENCE_MMOL_PER_G x REFERENCE_WEIGHT_KG / weight
 *
 * WHICH REFERENCE, AND WHY NOT THE TEXTBOOK ONE. Clinical teaching puts a gram
 * at about 0.28 mmol/L for a 70 kg adult, and the first version of this file
 * used it. Checked against real outside-anchored values (insulin-free hypo
 * rescues) at the reference weight, the textbook figure ran high, so the
 * reference is a stated project value rather than the textbook one.
 *
 * In this repository that value is the bundled synthetic example person's
 * carb sensitivity (ISF 1.80 over a carb ratio of about 11 g per unit), not a
 * measurement of anyone. The FORM is what this file adds; the level is a
 * starting point that tiers two and three replace.
 *
 * WHAT THAT MEANS FOR A USER AT THE REFERENCE WEIGHT: they get the reference
 * itself. The scaling is what changes for everyone else, and it is the part
 * physiology dictates.
 *
 * A given user's measured values can imply an effective body mass different
 * from their actual weight, i.e. that user responds a little more or less per
 * gram than the reference body predicts. That is the residual a prior is
 * supposed to have, and tiers two and three are supposed to remove it.
 *
 * WHAT THIS IS NOT. It is a prior, and priors lose. The moment tier two or three
 * has support, the measurement replaces this and the difference between them is
 * worth printing rather than hiding — a prior that silently disagrees with a
 * measurement is how a model ends up believing two things at once.
 */
object CarbSensitivityPriorV1 {

    /**
     * One gram of carbohydrate in a 70 kg adult.
     *
     * The synthetic example person's value, not the textbook 0.28 — see the
     * note above.
     */
    const val REFERENCE_MMOL_PER_G = 0.165

    const val REFERENCE_WEIGHT_KG = 70.0

    /** Outside these the formula is extrapolating past anything it was stated
     *  for, so the input is clamped rather than the output — a 20 kg reading is
     *  a typo, not a toddler using an MDI companion app. */
    val PLAUSIBLE_WEIGHT_KG = 35.0..200.0

    /**
     * Carbohydrate sensitivity in mmol/L per gram for a body of [weightKg].
     *
     * Null weight returns the reference — an unknown weight is the same
     * situation the single population number was always in, so the fallback is
     * exactly the previous behaviour rather than a guess.
     */
    fun fromWeight(weightKg: Double?): Double {
        val w = (weightKg ?: REFERENCE_WEIGHT_KG).coerceIn(PLAUSIBLE_WEIGHT_KG)
        return REFERENCE_MMOL_PER_G * REFERENCE_WEIGHT_KG / w
    }

    /** The weight this sensitivity implies — used to say, in one line, whether a
     *  measured value and the body it came from are consistent. */
    fun impliedWeightKg(csMmolPerG: Double): Double =
        REFERENCE_MMOL_PER_G * REFERENCE_WEIGHT_KG / csMmolPerG
}
