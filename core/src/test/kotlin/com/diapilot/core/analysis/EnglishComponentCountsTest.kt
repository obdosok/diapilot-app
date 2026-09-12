package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * English duplicate of [ComponentCountsTest] (WP-B5): the same invented
 * shapes, English input, so an English-speaking user's own notes are covered
 * by name and not just inferred from the Russian corpus. The general
 * back-scan (numeral up to three tokens before the name) needed no new data
 * to read "2 slices of bread" — it already tolerates an arbitrary word in
 * between, same as it tolerates the Russian unit noun for "piece" today,
 * which is not in any special list either. What DID need new data: English number
 * words, the English mass/volume units that must never be misread as a
 * count, and the English per-unit / combined-weight markers.
 */
class EnglishComponentCountsTest {

    // ---------------------------------------------------------------- text ----

    @Test
    fun numeralBeforeInflectedNounWithUnitWordBetween() {
        // "2 thin bread slices" — numeral, adjective, then the noun, one word
        // before its own unit noun (the back-scan tolerates the word between,
        // same as it tolerates Russian's inflected adjective in the same slot).
        val tc = countFromNoteText("omelet, cheese, cucumbers and 2 thin bread slices", "bread")
        assertEquals(2, tc!!.count)
        assertFalse(tc.aggregateMass)
    }

    @Test
    fun theBreakfastTextEveryRepeatOfTheDishUses() {
        val note = "breakfast (cheese, salad, scramble, 2 slices of bread)"
        assertEquals(2, countFromNoteText(note, "bread")!!.count)
        // The same note says NOTHING about the other three — no invented counts.
        assertNull(countFromNoteText(note, "cheese"))
        assertNull(countFromNoteText(note, "salad"))
        assertNull(countFromNoteText(note, "scramble"))
    }

    @Test
    fun timesNWrittenByThePatientInTheNoteItself() {
        assertEquals(2, countFromNoteText("bread x2, tuna paste", "bread")!!.count)
        assertEquals(2, countFromNoteText("bread with butter and pate x2", "pate")!!.count)
        assertEquals(3, countFromNoteText("a quarter of cake x3", "cake")!!.count)
    }

    @Test
    fun aWeightIsNeverReadAsACount() {
        assertNull(countFromNoteText("110g of rice and radish salad", "rice"))
        assertNull(countFromNoteText("a portion of mash (305g - recompute)", "mash"))
        // Separated unit — "12 grams" must not become a count of 12.
        assertNull(countFromNoteText("a cracker weighs 12 grams", "cracker"))
        // Non-integer volume.
        assertNull(countFromNoteText("a 0.33 bottle of beer", "beer"))
    }

    @Test
    fun aCombinedWeightForTheCountedUnitsIsFlaggedNotApplied() {
        // "2 pieces ... 14g" is the weight of BOTH, so the recorded 8 g is
        // already the total and multiplying by 2 would double a correct row.
        val choc = countFromNoteText("2 pieces dark chocolate, 14g", "chocolate")
        assertEquals(2, choc!!.count)
        assertTrue(choc.aggregateMass)
        // "both weigh 12 grams" says it outright.
        assertTrue(
            countFromNoteText("2 crackers with almonds, both weigh 12 grams, and tea", "cracker")!!.aggregateMass,
        )
        // "each 40g" is PER UNIT, so the count stands.
        assertFalse(countFromNoteText("2 waffles, each 40g", "waffle")!!.aggregateMass)
    }

    @Test
    fun shortNamesRefuseToGuess() {
        // "tea" is below the stem floor: a 3-char prefix collides with too much
        // English to be safe (same reason "rice"/"tea"/"onion" refuse in Russian).
        assertNull(countFromNoteText("2 cups of tea", "tea"))
    }

    // ------------------------------- guards the review found uncovered ----

    @Test
    fun aCountBelongingToANEIGHBOURINGComponentIsRefused() {
        // "potato" stems to a 5-char prefix, matches the inflected "potatoes",
        // and inherits the sausages' count of 3 — the primitive still reports
        // it, and the VETO lives in reconcileCounts, the only level that knows
        // the siblings.
        assertEquals(3, countFromNoteText("3 sausages with potatoes", "potato")!!.count)
        val rows = reconcileCounts(
            "3 sausages with potatoes",
            "COMPOSITION: sausage x3 = 2 g\nCOMPOSITION: potato = 8 g",
        )
        val potato = rows.single { normalizeFoodName(it.component.name) == "potato" }
        assertEquals(CountVerdict.NO_EVIDENCE, potato.verdict)
        assertNull(potato.derivedTotalGrams)
        assertEquals(8.0, potato.bestTotalGrams, 1e-9)
        // And the component the count DOES belong to is unaffected.
        assertEquals(CountVerdict.AGREE, rows.single { it.component.name.contains("sausage") }.verdict)
    }

    @Test
    fun aSeparatedMassUnitIsNotACount() {
        assertNull(countFromNoteText("12 grams of bread", "bread"))
        // Control: the same sentence with a real count still works.
        assertEquals(3, countFromNoteText("3 slices of bread", "bread")!!.count)
    }

    @Test
    fun agreeStillOutranksAmbiguousOnTheNoteThatNeedsIt() {
        // Here the only mass in the note belongs to the mash, and the clause
        // scope (no comma) charges it to the cutlets — AGREE must still win.
        val rows = reconcileCounts(
            "a portion of mash (305g - recompute) and 2 cutlets",
            "COMPOSITION: mash = 45 g\nCOMPOSITION: cutlet x2 = 4 g",
        )
        val cutlet = rows.single { normalizeFoodName(it.component.name) == "cutlet" }
        assertEquals(CountVerdict.AGREE, cutlet.verdict)
        assertEquals(8.0, cutlet.bestTotalGrams, 1e-9)
    }

    @Test
    fun countMissingFromTheRecordIsDerivedNeverWrittenOver() {
        val note = "breakfast (cheese, salad, scramble, 2 slices of bread)"
        val analysis = """
            COMPOSITION: scramble x2 = 2 g
            COMPOSITION: hummus = 10 g
            COMPOSITION: bread = 17 carbs · 35 portion
            COMPOSITION: salad = 4 g
        """.trimIndent()
        val rows = reconcileCounts(note, analysis)
        val bread = rows.single { normalizeFoodName(it.component.name) == "bread" }
        assertEquals(CountVerdict.MISSING_IN_RECORD, bread.verdict)
        assertEquals(17.0, bread.component.totalGrams, 1e-9)   // the record is UNTOUCHED
        assertEquals(34.0, bread.derivedTotalGrams!!, 1e-9)    // derived value sits beside it
        assertEquals(34.0, bread.bestTotalGrams, 1e-9)
        assertTrue(bread.provenance!!.contains("2 slices of bread"))

        // `scramble x2` has no support in the text either way — reported, not touched.
        val scramble = rows.single { normalizeFoodName(it.component.name) == "scramble" }
        assertEquals(CountVerdict.RECORD_ONLY, scramble.verdict)
        assertNull(scramble.derivedTotalGrams)
        assertEquals(4.0, scramble.bestTotalGrams, 1e-9)
    }

    @Test
    fun countPresentInBothIsLeftAlone() {
        val row = reconcileCounts(
            "breakfast (cheese, salad, scramble, 2 slices of bread)",
            "COMPOSITION: bread x2 = 12 g",
        ).single()
        assertEquals(CountVerdict.AGREE, row.verdict)
        assertNull(row.derivedTotalGrams)
        assertEquals(24.0, row.bestTotalGrams, 1e-9)
    }

    @Test
    fun anAggregateWeightBlocksTheDerivation() {
        val row = reconcileCounts(
            "2 pieces dark chocolate, 14g",
            "COMPOSITION: chocolate = 8 g",
        ).single()
        assertEquals(CountVerdict.AMBIGUOUS, row.verdict)
        assertNull(row.derivedTotalGrams)
        assertEquals(8.0, row.bestTotalGrams, 1e-9)
    }

    @Test
    fun aDisagreementIsReportedAndNeverResolvedByHeuristic() {
        val row = reconcileCounts(
            "breakfast (cheese, salad, scramble, 2 slices of bread)",
            "COMPOSITION: bread x3 = 12 g",
        ).single()
        assertEquals(CountVerdict.CONFLICT, row.verdict)
        assertNull(row.derivedTotalGrams)
        assertEquals(36.0, row.bestTotalGrams, 1e-9)  // the RECORD wins by default
    }

    @Test
    fun noCountsAnywhereIsACleanNoEvidence() {
        val rows = reconcileCounts("beer and chips", "COMPOSITION: beer = 18 g\nCOMPOSITION: chips = 20 g")
        assertTrue(rows.all { it.verdict == CountVerdict.NO_EVIDENCE && it.derivedTotalGrams == null })
    }
}
