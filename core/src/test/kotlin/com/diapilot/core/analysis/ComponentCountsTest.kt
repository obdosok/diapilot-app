package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case below is an invented note built to exercise one parsing shape,
 * because the defect this file guards against was a hand-written reader that
 * mis-read a documented format and was then "verified" against its own mistake.
 * The corpus is designed to walk every shape the format allows: a synthetic
 * string that only covers the "happy path" cannot catch that class of bug.
 */
class ComponentCountsTest {

    // ---------------------------------------------------------------- text ----

    @Test
    fun numeralBeforeInflectedNounWithUnitWordBetween() {
        // "2 small slices of bread" — numeral, inflected adjective, unit word, then the noun.
        val tc = countFromNoteText("ужин (омлет, сыр, огурцы и 2 небольших куска хлеба)", "Хлеб")
        assertEquals(2, tc!!.count)
        assertFalse(tc.aggregateMass)
    }

    @Test
    fun theBreakfastTextEveryRepeatOfTheDishUses() {
        // The same short note logged for a repeated breakfast.
        val note = "завтрак (сыр, салат, скрэмбл, 2 куска хлеба)"
        assertEquals(2, countFromNoteText(note, "хлеб")!!.count)
        // The same note says NOTHING about the other three, and the parser must
        // not invent counts for them — the ×2 that shows up on `scramble` in later
        // notes is unsupported by the text and stays unresolved on purpose.
        assertNull(countFromNoteText(note, "сыр"))
        assertNull(countFromNoteText(note, "салат"))
        assertNull(countFromNoteText(note, "скрэмбл"))
    }

    @Test
    fun timesNWrittenByThePatientInTheNoteItself() {
        // "bread x2, tuna paste" · "bread with butter and pate x2" · "a quarter of cake x3"
        assertEquals(2, countFromNoteText("хлеб ×2, паста тунца", "хлеб")!!.count)
        assertEquals(2, countFromNoteText("хлеб с маслом и паштетом ×2", "паштет")!!.count)
        assertEquals(3, countFromNoteText("четверть кекса x3", "кекс")!!.count)
    }

    @Test
    fun aWeightIsNeverReadAsACount() {
        // "110g of rice", "(305g - recompute)", "a 0.33 bottle of beer".
        assertNull(countFromNoteText("110гр риса и салат с редисом", "рис отварной"))
        assertNull(countFromNoteText("порция пюре (305гр - пересчитай)", "пюре"))
        // Separated unit — "12 grams" must not become a count of 12.
        assertNull(countFromNoteText("сухарик весит 12 грамм", "сухарик"))
        // Non-integer volume.
        assertNull(countFromNoteText("бутылка пива 0.33", "пиво"))
    }

    @Test
    fun aCombinedWeightForTheCountedUnitsIsFlaggedNotApplied() {
        // "2 pieces ... 14g" is the weight of BOTH, so the recorded 8 g is
        // already the total and multiplying by 2 would double a correct row.
        val choc = countFromNoteText("2 дольки большой тёмной шоколадки 14гр", "тёмный шоколад")
        assertEquals(2, choc!!.count)
        assertTrue(choc.aggregateMass)
        // "both weigh 12 grams" says it outright.
        assertTrue(countFromNoteText("2 сухарика с миндалём, оба весят 12 грамм, и чай", "сухарик")!!.aggregateMass)
        // "each 40g" is PER UNIT, so the count stands.
        assertFalse(countFromNoteText("2 вафли, каждая 40гр", "вафли")!!.aggregateMass)
    }

    @Test
    fun shortNamesRefuseToGuess() {
        // "rice", "tea", "onion" are below the stem floor: a 3-char prefix collides
        // with too much Russian to be safe.
        assertNull(countFromNoteText("2 порции риса", "рис"))
    }

    // ------------------------------------------------------ reconciliation ----


    // ------------------------------- guards the review found uncovered ----

    @Test
    fun aCountBelongingToANEIGHBOURINGComponentIsRefused() {
        // Found by probing the parser in review: "potato" stems to a 5-char prefix,
        // matches an inflected form, and inherits the sausages' count of 3. Both
        // shapes are plausible notes on their own (`potato ×2`, `sausage ×3`),
        // so this is one note away from silently tripling grams.
        assertEquals(3, countFromNoteText("3 сосиски с картошкой", "картофель")!!.count)
        // The primitive still reports it — the VETO lives in reconcileCounts, which is
        // the only level that knows the siblings.
        val rows = reconcileCounts(
            "3 сосиски с картошкой",
            "СОСТАВ: сосиска ×3 = 2 г\nСОСТАВ: картофель = 8 г",
        )
        val potato = rows.single { normalizeFoodName(it.component.name) == "картофель" }
        assertEquals(CountVerdict.NO_EVIDENCE, potato.verdict)
        assertNull(potato.derivedTotalGrams)
        assertEquals(8.0, potato.bestTotalGrams, 1e-9)
        // And the component the count DOES belong to is unaffected.
        assertEquals(CountVerdict.AGREE, rows.single { it.component.name.contains("сосиск") }.verdict)
    }

    @Test
    fun aSeparatedMassUnitIsNotACount() {
        // The review showed `aWeightIsNeverReadAsACount` never reached the
        // `next == MASS_UNITS` guard: every case there short-circuited earlier. This is
        // the shape that does reach it — a numeral whose NEXT token is the unit.
        assertNull(countFromNoteText("12 грамм хлеба", "хлеб"))
        // Control: the same sentence with a real count still works.
        assertEquals(3, countFromNoteText("3 куска хлеба", "хлеб")!!.count)
    }

    @Test
    fun agreeStillOutranksAmbiguousOnTheNoteThatNeedsIt() {
        // Mutation testing showed the AGREE-before-AMBIGUOUS ordering was load-bearing
        // and UNTESTED: flipping it left the suite green while moving rows that should
        // agree into `ambiguous` instead. Here the only mass in the note
        // belongs to the mashed potatoes, and the clause scope (no comma) charges it to the cutlets.
        val rows = reconcileCounts(
            "порция пюре (305гр - пересчитай) и 2 котлеты",
            "СОСТАВ: пюре = 45 г\nСОСТАВ: котлета ×2 = 4 г",
        )
        val cutlet = rows.single { normalizeFoodName(it.component.name) == "котлета" }
        assertEquals(CountVerdict.AGREE, cutlet.verdict)
        assertEquals(8.0, cutlet.bestTotalGrams, 1e-9)
    }

    @Test
    fun countMissingFromTheRecordIsDerivedNeverWrittenOver() {
        // The text says two slices, but the SOSTAV row carries no count.
        val note = "завтрак (сыр, салат, скрэмбл, 2 куска хлеба)"
        val analysis = """
            СОСТАВ: скрэмбл ×2 = 2 угл
            СОСТАВ: хумус = 10 угл
            СОСТАВ: хлеб = 17 угл · 35 порц
            СОСТАВ: салат = 4 угл
        """.trimIndent()
        val rows = reconcileCounts(note, analysis)
        val bread = rows.single { normalizeFoodName(it.component.name) == "хлеб" }
        assertEquals(CountVerdict.MISSING_IN_RECORD, bread.verdict)
        assertEquals(17.0, bread.component.totalGrams, 1e-9)   // the record is UNTOUCHED
        assertEquals(34.0, bread.derivedTotalGrams!!, 1e-9)    // derived value sits beside it
        assertEquals(34.0, bread.bestTotalGrams, 1e-9)
        assertTrue(bread.provenance!!.contains("2 куска хлеба"))

        // `scramble ×2` has no support in the text either way — reported, not touched.
        val scramble = rows.single { normalizeFoodName(it.component.name) == "скрэмбл" }
        assertEquals(CountVerdict.RECORD_ONLY, scramble.verdict)
        assertNull(scramble.derivedTotalGrams)
        assertEquals(4.0, scramble.bestTotalGrams, 1e-9)
    }

    @Test
    fun countPresentInBothIsLeftAlone() {
        // Same text, and here the writer DID record the count.
        val row = reconcileCounts(
            "завтрак (сыр, салат, скрэмбл, 2 куска хлеба)",
            "СОСТАВ: хлеб ×2 = 12 г",
        ).single()
        assertEquals(CountVerdict.AGREE, row.verdict)
        assertNull(row.derivedTotalGrams)
        assertEquals(24.0, row.bestTotalGrams, 1e-9)
    }

    @Test
    fun anAggregateWeightBlocksTheDerivation() {
        val row = reconcileCounts(
            "2 дольки большой тёмной шоколадки 14гр",
            "СОСТАВ: тёмный шоколад = 8 г",
        ).single()
        assertEquals(CountVerdict.AMBIGUOUS, row.verdict)
        assertNull(row.derivedTotalGrams)
        assertEquals(8.0, row.bestTotalGrams, 1e-9)
    }

    @Test
    fun aDisagreementIsReportedAndNeverResolvedByHeuristic() {
        val row = reconcileCounts(
            "завтрак (сыр, салат, скрэмбл, 2 куска хлеба)",
            "СОСТАВ: хлеб ×3 = 12 г",
        ).single()
        assertEquals(CountVerdict.CONFLICT, row.verdict)
        assertNull(row.derivedTotalGrams)
        assertEquals(36.0, row.bestTotalGrams, 1e-9)  // the RECORD wins by default
    }

    @Test
    fun noCountsAnywhereIsACleanNoEvidence() {
        val rows = reconcileCounts("пиво и чипсы", "СОСТАВ: пиво = 18 угл\nСОСТАВ: чипсы = 20 угл")
        assertTrue(rows.all { it.verdict == CountVerdict.NO_EVIDENCE && it.derivedTotalGrams == null })
    }
}
