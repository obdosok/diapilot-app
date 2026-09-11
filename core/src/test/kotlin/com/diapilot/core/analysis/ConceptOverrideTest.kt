package com.diapilot.core.analysis

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The user's edits to the concept dictionary must actually reach the places
 * that read it — an editor writing into a void would be worse than no editor.
 */
class ConceptOverrideTest {

    @After
    fun clean() = setUserConceptOverrides(emptyList())

    @Test
    fun `editing density changes what a portion is worth — the field that matters`() {
        // Concepts are a carb-density table first: this is what turns "pizza
        // 120 g" into grams, the one food input measured worth 21%.
        assertEquals(36.0, carbsForPortion("pizza", 120.0)!!, 1e-6)   // built-in 30/100
        setUserConceptOverrides(listOf(ConceptOverride(id = "pizza", carbPer100g = 25.0)))
        assertEquals(30.0, carbsForPortion("pizza", 120.0)!!, 1e-6)
    }

    @Test
    fun `an untouched field keeps the built-in`() {
        setUserConceptOverrides(listOf(ConceptOverride(id = "bread", carbPer100g = 40.0)))
        assertEquals(40.0, carbPer100gOf("bread")!!, 1e-6)
        // speed was never touched → still the built-in FAST
        assertEquals(CarbSpeed.FAST, conceptFor("хлеб")?.carbSpeed)
    }

    @Test
    fun `a user alias ADDS a word, it never erases one that already works`() {
        setUserConceptOverrides(listOf(ConceptOverride(id = "pizza", aliasesRu = listOf("основа"))))
        assertEquals("pizza", conceptFor("основа")?.id)   // the new word
        assertEquals("pizza", conceptFor("пицца")?.id)    // ...and the old one
    }

    @Test
    fun `an invented concept resolves — id, speed and density all reach the model`() {
        assertNull(conceptFor("вино"))
        setUserConceptOverrides(
            listOf(
                ConceptOverride(
                    id = "wine", carbPer100g = 3.0, portionG = 150.0,
                    carbSpeed = CarbSpeed.FAST, fat = MacroLevel.LOW,
                    protein = MacroLevel.LOW, fiber = MacroLevel.LOW,
                    aliasesRu = listOf("вино", "винишко"),
                ),
            ),
        )
        val c = conceptFor("винишко")
        assertNotNull(c)
        assertEquals("wine", c!!.id)
        assertEquals(CarbSpeed.FAST, c.carbSpeed)
        assertEquals(4.5, carbsForPortion("wine", 150.0)!!, 1e-6)
    }

    @Test
    fun `an invented concept with no speed is ignored rather than half-built`() {
        // Nothing can answer «how fast» for it, so it must not exist at all.
        setUserConceptOverrides(listOf(ConceptOverride(id = "mystery", aliasesRu = listOf("нечто"))))
        assertNull(conceptFor("нечто"))
    }

}
