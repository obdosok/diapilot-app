package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-B5 acceptance: a notification reply written in English ends up as grams
 * — the same way a Russian one already did — end to end. Splits the reply
 * into named components with the count the text itself states, prices each
 * through its concept's typical portion (exactly what
 * `AnnotationComposer.CompRow.deriveCarbs` already does per component when
 * the user edits a note by hand: [conceptFor] then [carbsForPortion] /
 * [typicalCarbs]), and sums them into the figure that becomes the
 * annotation's `est_carbs`.
 */
class EnglishOfflineFoodParserEndToEndTest {

    /** [name]'s carb grams for however many the note's own text states —
     *  offline, no LLM, no photo: only the concept table and the note text. */
    private fun offlineGrams(note: String, name: String): Double? {
        val concept = conceptFor(name) ?: return null
        val count = countFromNoteText(note, name)?.count ?: 1
        val portion = typicalPortionOf(concept.id) ?: return null
        return carbsForPortion(concept.id, portion * count)
    }

    @Test
    fun `a notification reply resolves to grams end to end`() {
        val reply = "2 slices of bread and a banana"
        val breadCarbs = offlineGrams(reply, "bread")!!
        val bananaCarbs = offlineGrams(reply, "banana")!!
        // bread: 2 x 30 g typical slice x 48 g carbs/100 g = 28.8 g
        assertEquals(28.8, breadCarbs, 1e-6)
        // banana: 1 (no count in the text) x 120 g typical x 23 g carbs/100 g = 27.6 g
        assertEquals(27.6, bananaCarbs, 1e-6)

        val estCarbs = breadCarbs + bananaCarbs
        assertEquals(56.4, estCarbs, 1e-6)
        assertTrue(
            "the reply prices to a plausible meal, not zero or a runaway number",
            estCarbs in 10.0..200.0,
        )
    }

    @Test
    fun `a generic portion word prices a food the concept's own typical portion does not cover`() {
        // Rice's own typical portion is a plateful (150 g), not a cup — "a cup
        // of rice" needs the generic portion-word table, not TYPICAL_PORTION_G.
        val id = conceptFor("rice")!!.id
        val cupGrams = typicalPortionWordGrams("cup")!!
        assertEquals(240.0, cupGrams, 1e-9)
        assertEquals(67.2, carbsForPortion(id, cupGrams)!!, 1e-6)
    }

    @Test
    fun `the same reply in Russian prices the same way`() {
        // Parity, not a coincidence: the primitives don't know which language
        // they were called with, only which concept and count they were given.
        val reply = "2 куска хлеба и банан"
        assertEquals(28.8, offlineGrams(reply, "хлеб")!!, 1e-6)
        assertEquals(27.6, offlineGrams(reply, "банан")!!, 1e-6)
    }
}
