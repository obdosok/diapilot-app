package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentSubstrateTest {

    private fun food(ts: Long, content: String, analysis: String? = null) =
        Annotation(ts, "food", content, id = ts).let {
            if (analysis == null) it else it.copy(analysis = analysis)
        }

    @Test
    fun `coverage counts meals with a split, from analysis or recipe`() {
        val notes = listOf(
            food(1, "завтрак", "СОСТАВ: хумус = 10 г\nСОСТАВ: хлеб ×2 = 10 г"),
            food(2, "пиво"),                       // named a recipe (see below)
            food(3, "непонятная еда"),             // no split anywhere
        )
        val recipes = mapOf(
            "пиво" to listOf(ComponentEstimate("пиво", 16.0, 1)),
        )
        val s = componentSubstrate(notes, recipes)
        assertEquals(3, s.totalMeals)
        assertEquals(2, s.withComposition)         // breakfast (analysis) + beer (recipe)
    }

    @Test
    fun `vocabulary aggregates meals and grams, meal-count once per meal`() {
        val notes = listOf(
            food(1, "завтрак", "СОСТАВ: хлеб ×2 = 10 г\nСОСТАВ: хумус = 8 г"),
            food(2, "перекус", "СОСТАВ: хлеб = 10 г"),
        )
        val s = componentSubstrate(notes, emptyMap())
        val bread = s.vocabulary.first { it.name == "хлеб" }
        assertEquals(2, bread.meals)               // in two meals
        assertEquals(30.0, bread.totalGrams, 1e-9) // 2×10 + 1×10
        val hummus = s.vocabulary.first { it.name == "хумус" }
        assertEquals(1, hummus.meals)
        // Most-used first: bread (2) before hummus (1).
        assertEquals("хлеб", s.vocabulary.first().name)
    }

    @Test
    fun `context and system notes are not meals`() {
        val notes = listOf(
            food(1, "тренировка"),   // context, not food substrate
            food(2, "смузи", "СОСТАВ: смузи = 20 г"),
        )
        val s = componentSubstrate(notes, emptyMap())
        assertEquals(1, s.totalMeals)
        assertEquals(1, s.withComposition)
    }
}
