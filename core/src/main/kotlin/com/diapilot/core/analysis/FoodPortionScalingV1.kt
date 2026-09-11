package com.diapilot.core.analysis

/**
 * HALF A PORTION, OR TWO — scaling one logged meal without re-asking the LLM.
 *
 * The user's request: add a button to the food-editing screen for "half the
 * dish" or "double portion", rescaling the macros accordingly — and don't
 * forget the LLM description.
 *
 * WHAT SCALES AND WHAT MUST NOT. The distinction is the whole content of this
 * file, and getting it backwards would be silent: the numbers would still look
 * reasonable.
 *
 *  - **grams scale**: carbohydrate, protein, fat, fibre. Twice the plate is
 *    twice of each.
 *  - **fractions do NOT**: `fast`/`medium`/`slow` are shares of this dish's own
 *    carbohydrate and sum to one. Scaling them would break that sum and, worse,
 *    would be read by `parseFoodKineticsV2` as a different KIND of food rather
 *    than more of the same one.
 *  - **form, alcohol, source do NOT**: a bigger portion of ice cream is still
 *    ice cream, still liquid-ish, still the same provenance.
 *  - **confidence does NOT**: the estimate is no better or worse for being
 *    doubled. It is the same reading of the same plate.
 *
 * THE TEXT IS SCALED TOO, and that is not cosmetic. The description is what the
 * user reads back later and what the composer offers as a repeat, so
 * a note saying "pizza, 2 slices" carrying a mismatched gram figure is a record
 * that contradicts itself. It is marked rather than rewritten — inventing new
 * prose about a meal nobody re-photographed would be the app claiming to know
 * something it does not.
 */
object FoodPortionScalingV1 {

    const val KINETICS_PREFIX = FOOD_KINETICS_PREFIX_V2

    /** Grams-bearing keys in the `KINETICS_V2:` line. Everything else is held. */
    private val GRAM_KEYS = setOf("protein", "fat", "fiber")

    data class Scaled(val text: String, val estCarbsG: Double?, val analysis: String?)

    /**
     * Scale one logged meal by [factor].
     *
     * Returns the inputs unchanged for a factor of 1, or one that is not finite
     * and positive — a caller passing nonsense must not silently zero a meal.
     *
     * [portionWord] is the word written into the note, in the UI language at the
     * time of the edit ([PORTION_WORD_RU], "portion"); the note is the user's own text, so
     * it keeps whatever language it was written in. A mark in either language is
     * recognised and replaced.
     */
    fun scale(
        text: String,
        estCarbsG: Double?,
        analysis: String?,
        factor: Double,
        portionWord: String = PORTION_WORD_RU,
    ): Scaled {
        if (!factor.isFinite() || factor <= 0.0 || factor == 1.0) {
            return Scaled(text, estCarbsG, analysis)
        }
        return Scaled(
            text = markPortion(text, factor, portionWord),
            estCarbsG = estCarbsG?.takeIf { it.isFinite() }?.let { it * factor },
            analysis = analysis?.let { scaleAnalysis(it, factor) },
        )
    }

    /** Human-readable factor: «×2», «½», «×1.5». */
    fun label(factor: Double): String = when {
        factor == 0.5 -> "½"
        factor == kotlin.math.floor(factor) -> "×%.0f".format(java.util.Locale.ROOT, factor)
        else -> "×%.2f".format(java.util.Locale.ROOT, factor).trimEnd('0').trimEnd('.')
    }

    /**
     * Mark the portion in the text without inventing prose.
     *
     * An existing mark is REPLACED rather than stacked, so halving twice reads
     * «½» once and not «½ ½» — the mark describes the note's current state, not
     * its edit history.
     */
    private fun markPortion(text: String, factor: Double, portionWord: String): String {
        val stripped = Regex("\\s*\\((?:½|×[0-9.,]+)\\s*(?:порци[яи]|portions?)\\)\\s*$").replace(text, "")
        return "$stripped (${label(factor)} $portionWord)"
    }

    /** The portion mark's word in Russian — the historical default. */
    const val PORTION_WORD_RU = "порция"

    private fun scaleAnalysis(analysis: String, factor: Double): String =
        analysis.lineSequence().joinToString("\n") { line ->
            if (!line.trim().startsWith("$KINETICS_PREFIX:", ignoreCase = true)) line
            else scaleKineticsLine(line, factor)
        }

    private fun scaleKineticsLine(line: String, factor: Double): String {
        val head = line.substringBefore(':')
        val body = line.substringAfter(':')
        val parts = body.split(';').map { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@map part
            val key = part.substring(0, i).trim().lowercase()
            if (key !in GRAM_KEYS) return@map part
            val raw = part.substring(i + 1).trim()
            val v = raw.replace(',', '.').toDoubleOrNull() ?: return@map part
            part.substring(0, i + 1) + "%.1f".format(java.util.Locale.ROOT, v * factor)
        }
        return head + ":" + parts.joinToString(";")
    }
}
