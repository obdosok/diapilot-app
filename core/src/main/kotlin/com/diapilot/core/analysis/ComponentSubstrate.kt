/**
 * How much of the eaten history already carries a component breakdown — the
 * SUBSTRATE a future component-level model will train on. Every meal logged
 * with a `COMPONENT_LINE_PREFIX:` split (LLM/photo or a picked composite) or matching a
 * library recipe adds to it. Pure description: shows coverage + the component
 * vocabulary being built, so progress toward component modelling is visible.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation

data class ComponentStat(
    val name: String,
    val meals: Int,        // meals where this component appears
    val totalGrams: Double,
)

data class ComponentSubstrate(
    val totalMeals: Int,      // food notes that are real meals
    val withComposition: Int, // …of which carry a component split
    val vocabulary: List<ComponentStat>,  // components, most-used first
)

/**
 * [foodNotes] — all annotations (food ones with a real dish name are counted);
 * [recipeComponents] — normalized dish name → its recipe components, so a meal
 * that named a known composite counts even without its own `COMPONENT_LINE_PREFIX:` analysis.
 */
fun componentSubstrate(
    foodNotes: List<Annotation>,
    recipeComponents: Map<String, List<ComponentEstimate>>,
): ComponentSubstrate {
    val meals = foodNotes.filter {
        it.kind == "food" && it.content.isNotBlank() && !isContextNote(it.content) &&
            normalizeFoodName(it.content).let { n -> n.isNotEmpty() && n !in SysLabels.ALL }
    }
    var withComp = 0
    val mealsPer = HashMap<String, Int>()
    val gramsPer = HashMap<String, Double>()
    for (note in meals) {
        val comps = note.analysis?.let { parseComponents(it) }?.takeIf { it.isNotEmpty() }
            ?: recipeComponents[normalizeFoodName(note.content)]
            ?: emptyList()
        if (comps.isEmpty()) continue
        withComp++
        // One meal contributes at most once to each component's meal count.
        comps.map { normalizeFoodName(it.name).ifEmpty { it.name.trim() } }.toSet()
            .forEach { mealsPer[it] = (mealsPer[it] ?: 0) + 1 }
        comps.forEach { c ->
            val n = normalizeFoodName(c.name).ifEmpty { c.name.trim() }
            gramsPer[n] = (gramsPer[n] ?: 0.0) + c.unitGrams * c.count
        }
    }
    val vocab = mealsPer.map { (n, m) -> ComponentStat(n, m, gramsPer[n] ?: 0.0) }
        .sortedWith(compareByDescending<ComponentStat> { it.meals }.thenBy { it.name })
    return ComponentSubstrate(meals.size, withComp, vocab)
}
