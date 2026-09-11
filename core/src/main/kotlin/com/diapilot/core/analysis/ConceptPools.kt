/**
 * Concept pooling — the payoff of the concept-id model. The dish NAME is a free
 * human label; what the model learns from is the concept-id each component maps
 * to. This groups everything the user ever logged BY concept, so name variation
 * ("smoothie" vs "smoothie with orange juice and parsley" vs "fresh juice") collapses into
 * one pool. It's the visible proof that the model pools correctly without any
 * manual renaming.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation

/** One concept and everything from history that landed in it. */
data class ConceptPool(
    val conceptId: String,          // "smoothie", or "?" for the unmapped gap
    val label: String,              // human label = most-common raw alias seen
    val carbSpeed: CarbSpeed,
    val fat: MacroLevel,
    val protein: MacroLevel,
    val occurrences: Int,           // component hits pooled here
    val totalCarbs: Double,         // summed carb grams attributed to the concept
    val aliases: List<String>,      // distinct raw names, most-common first
) {
    val mapped: Boolean get() = conceptId != UNMAPPED_CONCEPT
}

/** Bucket id for names that map to no concept — the dictionary gap, kept visible. */
const val UNMAPPED_CONCEPT = "?"

// Concept-model components of ONE logged meal: its COMPONENT_LINE_PREFIX split (carbs per
// component), or — for an ATOMIC dish with no split — a single component named
// after the dish (its clean title, else the free text). Mirrors what the
// history fingerprint shows, so "beer = 20 g" without a breakdown still pools.
/** Scale factor that pulls a NON-canonical (bullet) component sum onto the
 *  confirmed [estCarbs] — the UI treats bullet-midpoint sums as unreliable, so
 *  the model shouldn't trust them as absolute either. 1.0 for canonical composition. */
private fun bulletScale(analysis: String?, estCarbs: Double?, sum: Double): Double =
    if (!hasCanonicalComposition(analysis) && estCarbs != null && estCarbs > 0 && sum > 0)
        estCarbs / sum else 1.0

fun mealConceptComponents(analysis: String?, freeName: String, estCarbs: Double?): List<Pair<String, Double>> {
    val parsed = parseComponents(analysis ?: "")
    if (parsed.isNotEmpty()) {
        val s = bulletScale(analysis, estCarbs, parsed.sumOf { it.totalGrams })
        return parsed.map { it.name to it.totalGrams * s }
    }
    val carbs = estCarbs ?: return emptyList()
    val name = parseNameSuggestion(analysis ?: "")
        ?: freeName.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: return emptyList()
    return listOf(name to carbs)
}

/** As [mealConceptComponents] but carrying each component's natural PORTION
 *  (from the canonical composition) so the fingerprint's macro loads are amount-aware.
 *  Atomic dishes get a single input with no portion (typical assumed). */
fun mealConceptFingerInputs(analysis: String?, freeName: String, estCarbs: Double?): List<FingerInput> {
    val parsed = parseComponents(analysis ?: "")
    if (parsed.isNotEmpty()) {
        val s = bulletScale(analysis, estCarbs, parsed.sumOf { it.totalGrams })
        return parsed.map { FingerInput(it.name, it.totalGrams * s, it.totalPortion) }
    }
    val carbs = estCarbs ?: return emptyList()
    val name = parseNameSuggestion(analysis ?: "")
        ?: freeName.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: return emptyList()
    return listOf(FingerInput(name, carbs, null))
}

/**
 * Pool a flat list of (rawName, carbGrams) occurrences by concept-id. Names
 * that differ but share a concept merge; unmapped names collect under a single
 * [UNMAPPED_CONCEPT] bucket so the gap is one visible row, not noise. Sorted by
 * occurrence count (the biggest pools first).
 */
fun conceptPools(components: List<Pair<String, Double>>): List<ConceptPool> {
    val byConcept = LinkedHashMap<String, MutableList<Pair<String, Double>>>()
    for ((rawName, carbs) in components) {
        val key = conceptFor(rawName)?.id ?: UNMAPPED_CONCEPT
        byConcept.getOrPut(key) { mutableListOf() }.add(rawName to carbs)
    }
    return byConcept.map { (id, occ) ->
        val concept = effectiveConcepts().firstOrNull { it.id == id }
        // Alias = the raw name the user typed, normalized only for case/space.
        val aliasCounts = occ.groupingBy { it.first.trim().lowercase() }.eachCount()
            .entries.sortedByDescending { it.value }
        ConceptPool(
            conceptId = id,
            label = concept?.aliasesRu?.firstOrNull() ?: aliasCounts.firstOrNull()?.key ?: id,
            carbSpeed = concept?.carbSpeed ?: CarbSpeed.NONE,
            fat = concept?.fat ?: MacroLevel.LOW,
            protein = concept?.protein ?: MacroLevel.LOW,
            occurrences = occ.size,
            totalCarbs = occ.sumOf { it.second },
            aliases = aliasCounts.map { it.key },
        )
    }.sortedWith(compareByDescending<ConceptPool> { it.mapped }.thenByDescending { it.occurrences })
}

/** Concept pools straight from history annotations — atomic dishes included. */
fun conceptPoolsFromAnnotations(notes: List<Annotation>): List<ConceptPool> =
    conceptPools(notes.flatMap { mealConceptComponents(it.analysis, it.content, it.estCarbs) })
