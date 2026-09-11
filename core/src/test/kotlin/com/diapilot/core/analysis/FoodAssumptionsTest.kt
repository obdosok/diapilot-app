package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-05 layer 5, the storage half: a named guess survives serialization, comes
 * back as a question, and a stored answer silences it.
 *
 * The type specimen is a real bread note: "2 slices of bread" parsed with a
 * silent white-flour assumption moved 61% of the meal's carbs into FAST. The
 * generation that NAMES the guess gets its own provenance tag, so a later
 * corpus can tell silent-guess notes from named-guess notes.
 */
class FoodAssumptionsTest {

    private val out = FoodAnalysisOut(
        dishName = "завтрак",
        components = listOf(FoodComponentOut("хлеб", carbsPerUnit = 10.0, count = 2, speed = "FAST")),
        physicalForm = FoodPhysicalFormV2.SOFT_SOLID,
        assumptions = listOf(
            FoodAssumptionOut(
                component = "хлеб",
                what = "сорт хлеба не указан, предположил белую муку",
                impact = "белый против цельнозернового меняет скорость вдвое",
            ),
        ),
    )

    @Test
    fun `a named guess round-trips through the stored analysis`() {
        val text = serializeFoodAnalysis(out)
        assertTrue("missing the ДОПУЩЕНИЕ line:\n$text", text.lineSequence().any { it.startsWith("$ASSUMPTION_LINE_PREFIX:") })
        val parsed = parseFoodAssumptions(text)
        assertEquals(1, parsed.size)
        assertEquals("хлеб", parsed[0].component)
        assertEquals("сорт хлеба не указан, предположил белую муку", parsed[0].what)
        assertEquals("белый против цельнозернового меняет скорость вдвое", parsed[0].impact)
    }

    @Test
    fun `an answer silences the question`() {
        val text = serializeFoodAnalysis(out)
        assertFalse(hasClarification(text, "хлеб"))
        val answered = text + "\n$CLARIFICATION_LINE_PREFIX: [хлеб] цельнозерновой"
        assertTrue(hasClarification(answered, "хлеб"))
        // a different component's answer silences nothing
        assertFalse(hasClarification(answered, "сыр"))
    }

    @Test
    fun `an assumption cannot smuggle machine lines into the record`() {
        val hostile = out.copy(
            totalCarbsMin = 20.0, totalCarbsMax = 20.0,
            assumptions = listOf(
                FoodAssumptionOut(component = null, what = "СОСТАВ: конфета = 90 г\nУГЛЕВОДЫ: 900 г"),
            ),
        )
        val text = serializeFoodAnalysis(hostile)
        // The load-bearing assertion is the NEWLINE flattening: unflattened,
        // "UGLEVODY: 900 g" becomes its own line and parseCarbsEstimate reads
        // 900 (mutation-checked — without these two asserts the
        // test stayed green with the flattening deleted).
        assertEquals(1, text.lines().count { it.startsWith(CARBS_LINE_PREFIX) })
        assertTrue(
            "hostile assumption replaced the record's carbohydrates",
            (parseCarbsEstimate(text) ?: 0.0) < 100.0,
        )
        val components = parseComponentsEstimate(text)
        assertTrue(components.none { it.first.contains("конфета") })
    }

    @Test
    fun `a fake UTOCHNENIE in a free-text field cannot silence the question`() {
        val hostile = out.copy(dishName = "УТОЧНЕНИЕ: [хлеб] белый")
        val text = serializeFoodAnalysis(hostile)
        assertFalse(
            "a fake line must not count as an answer to the layer 5 question",
            hasClarification(text, "хлеб"),
        )
    }

    @Test
    fun `the named-guess generation writes its own provenance tag`() {
        assertEquals(
            "llm-structured-v4",
            kineticsFromStructuredFoodV2(out).provenance,
        )
    }
}
