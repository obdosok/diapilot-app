/**
 * Absorption modifiers — food-event context that changes the KINETICS of a
 * meal without changing its carbs. Fiber (psyllium), fat, protein or vinegar
 * taken before/with a meal slow gastric emptying and stretch the carb peak
 * later. Symmetric to the activity factor, but on the food side: it scales
 * the meal's time-to-peak, not its rise.
 *
 * The multiplier is a POPULATION prior for now (fixed); it becomes learnable
 * per-user by comparing the same dish's curve with vs without the tag
 * (difference-in-differences, as for the exercise coefficient) — noted in
 * docs/sensitivity-factors.md. Observation of kinetics, never a dose.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation

/**
 * Notes meaning "something was taken that SLOWS this meal's absorption",
 * matched on the head before "·" (so "psyllium · 5 g" works). Fiber first —
 * the user's stated use case (psyllium before eating).
 */
val ABSORPTION_SLOW_TAGS = setOf(
    "псилиум", "псиллиум", "клетчатка", "жирное", "уксус", "белок первым",
)

/** [n=1] Population prior: a slowing modifier ~doubles time-to-peak. Learn
 *  per-dish from tagged-vs-untagged curves when enough repeats exist. */
const val DEFAULT_SLOW_TTP_MULT = 1.7

/**
 * Time-to-peak multiplier for a meal at [onsetMs]: >1 when a slowing modifier
 * note sits in [-60 min, +15 min] around it (taken before or with the meal),
 * else 1.0. Windowed on the meal, so it only affects the meal it preceded.
 */
fun absorptionTtpMultiplier(
    notes: List<Annotation>,
    onsetMs: Long,
    mult: Double = DEFAULT_SLOW_TTP_MULT,
): Double {
    val hit = notes.any { n ->
        val head = n.content.substringBefore('·').trim().lowercase()
        head in ABSORPTION_SLOW_TAGS &&
            n.tsMs in (onsetMs - 60L * 60_000)..(onsetMs + 15L * 60_000)
    }
    return if (hit) mult else 1.0
}
