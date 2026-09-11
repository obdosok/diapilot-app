package com.diapilot.core.analysis

/**
 * HOW LONG A MEAL IS STILL DELIVERING — as calories, not grams of carbohydrate.
 *
 * The user's request: change the limit and timings from 30 g/hour to roughly
 * 100-120 kcal/hour, and separately: if we know a smoothie finished absorbing in
 * 60 minutes, it should not still be interfering with breakfast 65 minutes later.
 * Those are the same
 * quantity asked for twice — a meal confounds its neighbour for exactly as long
 * as it is still emptying — so there is one function for both.
 *
 * THE RATE IS NOT A NEW NUMBER. The shipped throughput prior is 30 g of
 * carbohydrate per hour, and carbohydrate is 4 kcal/g:
 *
 *     30 g/h x 4 kcal/g = 120 kcal/h
 *
 * So a pure-carbohydrate meal — dextrose, juice, a smoothie — comes out
 * IDENTICAL to today, to the digit. What changes is that fat's 9 kcal/g and
 * protein's 4 now occupy the same pipe, which is what gastric emptying actually
 * does: the stomach delivers at a roughly constant caloric rate, so fat does not
 * add a separate delay term, it consumes the budget.
 *
 * That makes the change one-directional and cheap to reason about: lean meals do
 * not move at all, fatty ones get longer.
 *
 * Consistency check on the user's own dishes, against the 90%-completion the
 * accepted structure gives independently:
 *
 *     smoothie   105 kcal -> 52 min      structure says 73
 *     breakfast  534 kcal -> 267 min     structure says 262
 *
 * Two unrelated routes landing within a few minutes on the long dish is worth
 * more than either alone.
 */
object MealCaloricExtentV1 {

    /** 30 g/h of carbohydrate, restated. Not retuned — see the class comment. */
    const val EMPTYING_KCAL_PER_HOUR = 120.0

    const val KCAL_PER_G_CARB = 4.0
    const val KCAL_PER_G_PROTEIN = 4.0
    const val KCAL_PER_G_FAT = 9.0

    /**
     * Never shorter than the fixed 45-minute session gap this replaces.
     *
     * Deliberate: it makes the change join MORE and never less, so a corpus
     * measurement before and after has one direction to explain instead of two.
     * A 20-kcal nibble would otherwise stop joining with the meal beside it, and
     * that is a second change nobody asked for.
     */
    const val MIN_EXTENT_MIN = 45.0

    /**
     * And never longer than the analysis window.
     *
     * A 1000-kcal meal would otherwise claim eight hours and swallow a whole
     * day into one virtual meal. The cap is a LIMITATION, not a physiological
     * claim: a genuinely long meal does keep contaminating past 300, and this
     * says we stop tracking it, not that it stopped.
     */
    const val MAX_EXTENT_MIN = 300.0

    fun kcal(carbsG: Double, proteinG: Double?, fatG: Double?): Double =
        carbsG.coerceAtLeast(0.0) * KCAL_PER_G_CARB +
            (proteinG ?: 0.0).coerceAtLeast(0.0) * KCAL_PER_G_PROTEIN +
            (fatG ?: 0.0).coerceAtLeast(0.0) * KCAL_PER_G_FAT

    /** Minutes this meal keeps delivering, from its calories alone. */
    fun extentMin(
        carbsG: Double,
        proteinG: Double?,
        fatG: Double?,
        rateKcalPerHour: Double = EMPTYING_KCAL_PER_HOUR,
    ): Double {
        if (rateKcalPerHour <= 0.0) return MIN_EXTENT_MIN
        val minutes = kcal(carbsG, proteinG, fatG) / rateKcalPerHour * 60.0
        return minutes.coerceIn(MIN_EXTENT_MIN, MAX_EXTENT_MIN)
    }

    /**
     * The reach of a SESSION — its notes pooled, because a meal that has been
     * accumulating courses keeps delivering all of them, not just the last.
     *
     * This is the function the corpus passes to [groupMealSessions]; keeping it
     * here rather than at each call site means every consumer asks the same
     * question of the same numbers.
     */
    fun sessionExtentMin(notes: List<com.diapilot.core.collector.Annotation>): Double {
        var carbs = 0.0; var protein = 0.0; var fat = 0.0
        notes.forEach { n ->
            carbs += n.estCarbs ?: 0.0
            val nutrition = parseFoodNutrition(n.analysis)
            protein += nutrition.proteinG ?: 0.0
            fat += nutrition.fatG ?: 0.0
        }
        return extentMin(carbs, protein, fat)
    }

    /** The same, read straight off a note. Null carbs means no meal to time. */
    fun extentMin(note: com.diapilot.core.collector.Annotation): Double? {
        val carbs = note.estCarbs?.takeIf { it > 0.0 } ?: return null
        val nutrition = parseFoodNutrition(note.analysis)
        return extentMin(carbs, nutrition.proteinG, nutrition.fatG)
    }
}
