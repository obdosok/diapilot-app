package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * English duplicate of [FoodConceptsTest] (WP-B5) — same shapes (exact alias,
 * plural/inflection via the stem, brand/synonym to concept, exact alias
 * beating a substring), English input. The Russian aliases are untouched;
 * this only exercises the English ones added alongside them.
 */
class EnglishFoodConceptsTest {

    @Test
    fun `aliases and plural forms map to the concept id`() {
        assertEquals("buckwheat", conceptFor("buckwheat")?.id)
        assertEquals("banana", conceptFor("bananas")?.id)           // plural via the 5-char stem
        assertEquals("bread", conceptFor("bread")?.id)
        assertEquals("bread", conceptFor("2 bread slices")?.id)     // xN / prose
        assertEquals("beer", conceptFor("bottle of lager")?.id)     // synonym → beer
        assertEquals("meat_cutlet", conceptFor("cutlet")?.id)
        assertEquals("egg", conceptFor("scrambled eggs")?.id)
        assertEquals("salad", conceptFor("garden salad")?.id)
    }

    @Test
    fun `unknown component is unmapped (generic bucket)`() {
        assertNull(conceptFor("an exotic mangosteen fruit"))
    }

    @Test
    fun `exact alias beats a substring match`() {
        // "tuna paste" is fish paste, not pasta — exact alias must win.
        assertEquals("fish", conceptFor("tuna paste")?.id)
        assertEquals("pasta", conceptFor("pasta")?.id)
        assertEquals("salad", conceptFor("parsley")?.id)
    }
}
