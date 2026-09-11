package io.github.obdosok.diapilot

import com.diapilot.core.analysis.COMPONENT_CARBS_TAG
import com.diapilot.core.analysis.COMPONENT_LINE_PREFIX
import com.diapilot.core.analysis.isContextNote
import com.diapilot.core.analysis.lookupFoodGrams
import com.diapilot.core.analysis.parseComponents
import com.diapilot.core.collector.Annotation

/**
 * Batch decomposition of already-logged meals: a meal whose NAME is an explicit
 * list of dishes (e.g. "hummus, scramble, bread ×2") gets its
 * component split written into the analysis as composition-marker lines — the
 * substrate a future component model reads. The human NAME is left untouched
 * (it is a free alias); only the composition metadata is added.
 *
 * Conservative on purpose: only names that `parseMealParts` splits into ≥2 real
 * dishes are proposed. Prose (e.g. "cutlets with salad and buckwheat") and
 * atomics (e.g. "beer", "dextrose") are left for the user / LLM — the preview
 * shows exactly what will change and nothing is applied without approval.
 */
data class DishDecomp(
    val annotationId: Long,
    val tsMs: Long,
    val name: String,
    val existingAnalysis: String?,
    val components: List<Triple<String, Int, Double?>>,  // name, count, gramsPerUnit
)

fun computeDecompPlan(
    notes: List<Annotation>,
    gramsByNorm: Map<String, Double>,
): List<DishDecomp> = notes
    .filter {
        it.kind == "food" && it.content.isNotBlank() && !isContextNote(it.content)
    }
    .mapNotNull { note ->
        // Already has a split → nothing to do.
        if (parseComponents(note.analysis ?: "").isNotEmpty()) return@mapNotNull null
        val parts = io.github.obdosok.diapilot.ui.parseMealParts(note.content)
        if (parts.size < 2) return@mapNotNull null
        DishDecomp(
            annotationId = note.id,
            tsMs = note.tsMs,
            name = note.content,
            existingAnalysis = note.analysis,
            components = parts.map { (n, c) -> Triple(n, c, lookupFoodGrams(gramsByNorm, n)) },
        )
    }
    .sortedByDescending { it.tsMs }

/** The composition-marker lines for one decomposition (unknown grams → 0,
 *  name still enters the vocabulary; refine later in the meal editor). */
fun decompSostav(d: DishDecomp): String = d.components.joinToString("\n") { (n, c, g) ->
    "$COMPONENT_LINE_PREFIX: $n${if (c > 1) " ×$c" else ""} = ${(g ?: 0.0).let { "%.0f".format(it) }} $COMPONENT_CARBS_TAG"
}

/** Free-text that likely bundles several dishes — the LLM decompose targets
 *  these (the lexical parser can't read a run-on sentence naming several
 *  items with a combined weight). Atomics (e.g. "beer") are skipped to save
 *  API calls. */
fun looksComposite(content: String): Boolean {
    val s = content.trim()
    return s.contains(',') || s.contains('+') ||
        Regex("""\s(с|и)\s""").containsMatchIn(" $s ") ||
        s.split(Regex("""\s+""")).size >= 3
}
