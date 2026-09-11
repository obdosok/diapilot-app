package io.github.obdosok.diapilot

/** One reviewed nutrition estimate may update several identical historical
 * portions; [annotationIds] keeps the batch economical without losing rows. */
data class NutritionFill(
    val annotationIds: List<Long>,
    val dish: String,
    val proteinG: Double,
    val fatG: Double,
    val kcal: Double,
)
