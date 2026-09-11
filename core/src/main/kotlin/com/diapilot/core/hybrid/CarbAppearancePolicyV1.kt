package com.diapilot.core.hybrid

/**
 * HOW CARBOHYDRATE IS ALLOWED TO LEAVE THE STOMACH — one object, one answer.
 *
 * Review finding (second pass): the caloric queue and its sieving
 * reached the forecast and What-if while Stage9 — the joint deconvolution that
 * produces what the model LEARNS — still ran the flat 30 g/h, because the flag
 * that carried the new behaviour was a boolean defaulting to false. So the
 * corpus was being built under one appearance model and the forecast drawn
 * under another, which is discipline #7 turned inward: the instrument and the
 * model disagreed about the same meal.
 *
 * Two rules make that class of bug structural rather than vigilance-based:
 *
 *  1. The engine DEFAULTS to [PHYSIO_SHIPPED], so forgetting the argument
 *     yields the shipped physiology rather than an older one.
 *  2. Where an arm cannot be inferred — [jointMealAttributionV1] takes notes and
 *     readings, not a model — the parameter is REQUIRED. A new call site is a
 *     compile error, never a silent fallback to the old physiology.
 *
 * [GRAM_QUEUE_LEGACY] no longer runs anywhere: the v11 arm it belonged to was
 * deleted. It is kept as the CONTRAST arm the queue studies sweep
 * against — «what would a flat gram queue have said» is still the question that
 * sized the caloric one. It has sieving at zero because with no caloric limit
 * there is no fat in the pipe for carbohydrate to pass.
 */
data class CarbAppearancePolicyV1(
    /** Meter the queue by calories at this rate; null keeps the flat gram queue. */
    val emptyingKcalPerHour: Double?,
    /** How much carbohydrate leaves ahead of the fat — see [PERSONAL_CARB_SIEVING_V1]. */
    val carbSieving: Double,
) {
    init {
        require(emptyingKcalPerHour == null || emptyingKcalPerHour > 0.0) {
            "emptying rate must be positive or null, was $emptyingKcalPerHour"
        }
        require(carbSieving in 0.0..1.0) { "sieving must be a fraction, was $carbSieving" }
    }

    val caloric: Boolean get() = emptyingKcalPerHour != null

    /**
     * A short stable name for THIS policy, for version strings and hashes.
     *
     * Every cache that stores a decomposition made under this policy embeds
     * this, so retuning the sieving cannot ship without invalidating what was
     * computed under the old value. Bumping a hand-written version string does
     * the same thing only as long as somebody remembers — which, for the queue
     * itself, is exactly what did not happen.
     */
    fun signature(): String =
        emptyingKcalPerHour?.let { "kcal%.0f-sieve%.2f".format(java.util.Locale.ROOT, it, carbSieving) }
            ?: "gram30"

    companion object {
        /** What the Physio arm runs: 180 kcal/h with sieving 0.65 — the shipped
         *  value. See [PERSONAL_EMPTYING_KCAL_PER_HOUR_V1]. */
        val PHYSIO_SHIPPED = CarbAppearancePolicyV1(
            PERSONAL_EMPTYING_KCAL_PER_HOUR_V1, PERSONAL_CARB_SIEVING_V1,
        )

        /** The v11 arm: a flat 30 g/h and no calories anywhere. */
        val GRAM_QUEUE_LEGACY = CarbAppearancePolicyV1(null, 0.0)
    }
}

