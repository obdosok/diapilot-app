package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alcohol must reach the model from a note that carries NO carbohydrate, and
 * from every branch that establishes carb speed — not only the explicit one.
 *
 * The defect these pin, found by a live check: `alcohol_present = 1`
 * in ZERO of the evidence rows against notes mentioning beer, because the
 * composer's chip only reached storage through the label and recipe evidence
 * modes. Dry wine was worse than under-recorded — it had no route at all, since
 * the evidence row is only written when carbs exceed zero.
 *
 * Mutation-checked: drop `withAlcohol` from a branch and that branch's test fails.
 */
class AlcoholMarkerV2Test {

    @Test
    fun `alcohol survives a note with no carbohydrate and no structure`() {
        // Dry wine as the user would log it: a name, a marker, nothing else.
        val features = parseFoodKineticsV2("META_ALCOHOL: true")
        assertTrue("alcohol lost on the neutral branch", features.alcoholPresent)
        assertEquals("unknown-neutral-v2", features.provenance)
    }

    @Test
    fun `the neutral fallback is still neutral — the marker invents no structure`() {
        val features = parseFoodKineticsV2("META_ALCOHOL: true")
        assertEquals(0.20, features.normalized().fastFraction, 1e-9)
        assertEquals(0.60, features.normalized().mediumFraction, 1e-9)
        // A fabricated KINETICS_V2 would have claimed «stored-v2» provenance and
        // a confidence this record has not earned.
        assertTrue("the marker inflated confidence", features.confidence <= 0.25)
    }

    @Test
    fun `alcohol rides the macro branch too`() {
        val features = parseFoodKineticsV2("БЕЛКИ: 2 г\nЖИРЫ: 0 г\nMETA_ALCOHOL: true", 2.0, 0.0)
        assertTrue("alcohol lost on the macro branch", features.alcoholPresent)
        assertEquals("macro-neutral-v2", features.provenance)
    }

    @Test
    fun `alcohol rides the GI branch too`() {
        val features = parseFoodKineticsV2("ГИ: 70\nMETA_ALCOHOL: true")
        assertTrue("alcohol lost on the GI branch", features.alcoholPresent)
        assertEquals("numeric-gi-v2", features.provenance)
    }

    @Test
    fun `absence stays absence`() {
        assertFalse(parseFoodKineticsV2("БЕЛКИ: 2 г").alcoholPresent)
        assertFalse(parseAlcoholPresentV2("META_ALCOHOL: false"))
        assertFalse(parseAlcoholPresentV2(null))
        assertFalse(parseAlcoholPresentV2("пиво"))
    }

    @Test
    fun `an explicit KINETICS_V2 line still wins on structure and keeps the marker`() {
        val line = foodKineticsLineV2(
            FoodKineticFeaturesV2(1.0, 0.0, 0.0, FoodPhysicalFormV2.LIQUID),
        )
        val features = parseFoodKineticsV2("$line\nMETA_ALCOHOL: true")
        assertTrue(features.alcoholPresent)
        assertEquals(FoodPhysicalFormV2.LIQUID, features.physicalForm)
        assertEquals(1.0, features.normalized().fastFraction, 1e-9)
    }
}
