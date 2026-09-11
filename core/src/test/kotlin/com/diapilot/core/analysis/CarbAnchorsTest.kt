package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbAnchorsTest {

    private val smoothie = CARB_ANCHORS.single { it.conceptId == "smoothie" }

    @Test
    fun theFactorIsTrueOverRecorded() {
        assertEquals(22.0 / 33.0, smoothie.factor, 1e-12)
    }

    @Test
    fun scalingIsPROPORTIONALNotFlat() {
        // The three values actually in the corpus. Overwriting every row with 22 would
        // erase the user's own distinction between a full glass and a smaller one.
        assertEquals(22.0, rewrite("смузи", est = 33.0)!!.newEstCarbs, 1e-9)
        assertEquals(21.7, rewrite("стакан смузи", est = 32.5)!!.newEstCarbs, 1e-9)
        assertEquals(16.7, rewrite("смузи", est = 25.0)!!.newEstCarbs, 1e-9)
    }

    @Test
    fun itIsIdempotent() {
        // The dextrose migration shipped with this bug once — the marker was set after
        // the loop rather than per row, so a re-run multiplied again.
        val first = rewrite("смузи", est = 33.0)
        assertEquals(22.0, first!!.newEstCarbs, 1e-9)
        assertNull(
            "a row already carrying the anchor marker must be refused",
            rewrite("смузи", est = first.newEstCarbs, src = ANCHOR_SOURCE),
        )
    }

    @Test
    fun otherDishesAndGramlessNotesAreUntouched() {
        assertNull(rewrite("кусок домашней пиццы", est = 35.0))
        assertNull(rewrite("Пицца пепперони кусок", est = 62.0))
        assertNull(rewrite("смузи", est = null))
        assertNull(rewrite("смузи", est = 0.0))
    }

    @Test
    fun everyRealSmoothieNoteTextIsMatchedAndNothingElseIs() {
        // The verbatim texts from the food era. A migration that silently missed one of
        // these would leave two gram scales side by side in the same corpus.
        val smoothieTexts = listOf(
            "смузи с апельсиновым соком и петрушкой", "смузи", "стакан смузи",
        )
        for (t in smoothieTexts) assertNotNull(t, rewrite(t, est = 33.0))
        // And the two rows that matched a naive `LIKE '%sok%'` ("juice") substring inside
        // "kusok" ("piece") must not.
        for (t in listOf("кусок домашней пиццы", "Пицца пепперони кусок")) {
            assertNull(t, rewrite(t, est = 33.0))
        }
    }

    @Test
    fun aNoteWithNoCompositionLeavesTheTextByteIdentical() {
        // All real smoothie notes are like this — no `SOSTAV` at all — so the common
        // path must not invent an analysis or reformat one.
        assertNull(rewrite("смузи", est = 33.0, analysis = null)!!.newAnalysis)
        assertNull(rewrite("смузи", est = 33.0, analysis = "Просто текст\nГИ: 40")!!.newAnalysis)
    }

    @Test
    fun aMatchingCompositionLineIsScaledAndTheOthersAreNot() {
        // Forgotten on dextrose, where `parseComponents` still reads 8 × 4.0 = 32
        // against an est_carbs of 40.8 — two numbers for one meal, indefinitely.
        val a = """
            НАЗВАНИЕ: смузи с печеньем
            СОСТАВ: смузи = 33 угл
            СОСТАВ: печенье ×2 = 9 угл
            ГИ: 50
        """.trimIndent()
        // The FULL rewrite refuses this note — it is only partly anchored, and
        // `carbs_source` stamps the whole row. The line-level rescaler is still exercised
        // directly, because it is what a note whose composition is ALL smoothie uses, and
        // because keeping it tested is what makes the refusal above a decision.
        assertNull(rewrite("смузи", est = 42.0, analysis = a))
        val out = rescaleComposition(a, smoothie)!!
        assertTrue("smoothie scaled 33 -> 22:\n$out", out.contains("СОСТАВ: смузи = 22 угл"))
        assertTrue("the OTHER component is untouched:\n$out", out.contains("СОСТАВ: печенье ×2 = 9 угл"))
        assertTrue("non-composition lines survive:\n$out", out.contains("НАЗВАНИЕ: смузи с печеньем"))
        assertTrue(out.contains("ГИ: 50"))
        // And the scaled line must round-trip through the production parser.
        val comps = parseComponents(out)
        assertEquals(22.0, comps.single { conceptFor(it.name)?.id == "smoothie" }.totalGrams, 1e-9)
        assertEquals(18.0, comps.single { it.name.contains("печенье") }.totalGrams, 1e-9)
    }

    @Test
    fun aPortionOnTheLineSurvivesTheRewrite() {
        val a = "СОСТАВ: смузи = 33 угл · 300 порц"
        val out = rescaleComposition(a, smoothie)!!
        assertEquals("СОСТАВ: смузи = 22 угл · 300 порц", out)
    }

    @Test
    fun theProvenanceCarriesTheEXTERNALBasisNotJustAFactor() {
        // An anchor whose basis cannot be written down is not an anchor. The string has
        // to say WHY, so a future reader can re-check it without this conversation.
        val p = rewrite("смузи", est = 33.0)!!.provenance
        assertTrue(p, p.contains("200 г"))
        assertTrue(p, p.contains("апельсин"))
        assertTrue(p, p.startsWith("anchor:"))
    }


    // ---------------------------------------- the prior's gram space ----

    private fun fp(vararg comps: Pair<String, Double>) =
        mealFingerprint(comps.map { (n, g) -> FingerInput(n, g) })

    @Test
    fun theConversionIsOFFUntilTheDataIsActuallyAnchored() {
        // THE ORDERING BUG THIS PINS: keying the conversion on «an anchor exists for this
        // concept» would convert the prior the moment the code ships, while the notes
        // still hold RECORDED grams — the model would multiply 33 g by a true-gram prior
        // and over-call by 1.5×, the mirror image of the sag it exists to fix.
        val target = fp("смузи" to 33.0)
        assertEquals(0.177, priorInDishGramSpace(target, 0.177)!!, 1e-9)
        assertEquals(0.177, priorInDishGramSpace(target, 0.177, gramsAnchored = false)!!, 1e-9)
        // Only with the row's own provenance does it move.
        assertEquals(0.177 / (22.0 / 33.0), priorInDishGramSpace(target, 0.177, true)!!, 1e-9)
    }

    @Test
    fun anUnanchoredDishIsNeverConvertedEvenWhenFlagged() {
        // The flag says «this row's grams are anchored»; the FACTOR still comes from the
        // components. A dish with no anchor has factor 1.0, so the prior is untouched.
        val target = fp("гречка" to 40.0)
        assertEquals(0.177, priorInDishGramSpace(target, 0.177, gramsAnchored = true)!!, 1e-9)
    }

    @Test
    fun aMixedPlateMovesOnlyAsFarAsItsAnchoredShare() {
        // Half the carbs anchored ⇒ the weighted factor is halfway, so the prior moves
        // halfway. A plate cannot be wholly in one gram space when its components are not.
        val target = fp("смузи" to 22.0, "гречка" to 22.0)
        val f = (22.0 * (22.0 / 33.0) + 22.0 * 1.0) / 44.0
        assertEquals(0.177 / f, priorInDishGramSpace(target, 0.177, true)!!, 1e-9)
        assertTrue(priorInDishGramSpace(target, 0.177, true)!! < 0.177 / (22.0 / 33.0))
        assertTrue(priorInDishGramSpace(target, 0.177, true)!! > 0.177)
    }

    @Test
    fun nullPriorStaysNull() {
        assertNull(priorInDishGramSpace(fp("смузи" to 22.0), null, true))
    }

    @Test
    fun aProvenanceOnlyAnchorNEVERRewritesTheStoredText() {
        // Caught on a real write: `SOSTAV: beer = 18 g` came back as `= 18 carb`. Same
        // parsed value, but an unannounced edit to the user's text — and the dry run
        // had promised zero text changes. Re-rendering through `sostavLine` normalises the
        // legacy unit label, so a factor-1.0 anchor must not re-render at all.
        val beer = CARB_ANCHORS.single { it.conceptId == "beer" }
        assertTrue(beer.provenanceOnly)
        val a = """
            Пиво Corona, бутылка 0,5 л.
            СОСТАВ: пиво = 18 г
            ГИ: 66
        """.trimIndent()

        assertNull(rescaleComposition(a, beer))
        assertNull(anchorRewrite("бутылка короны", a, 18.0, null, beer)!!.newAnalysis)
    }

    @Test
    fun theBeerRangeExcludesByRULENotByAHandList() {
        val beer = CARB_ANCHORS.single { it.conceptId == "beer" }
        fun r(c: String, g: Double) = anchorRewrite(c, null, g, null, beer)
        // Passes: one bottle, and two bottles where the note's own text says «×2».
        assertNotNull(r("пиво", 18.0)); assertNotNull(r("пиво", 20.0))
        assertNotNull("«пиво ×2» is 36 g for TWO bottles = 18 each", r("пиво ×2", 36.0))
        // Refused, and each for its own reason — no exclusion list anywhere in the code.
        assertNull("mixed dish", r("пиво, фисташки", 35.0))
        assertNull("mixed dish", r("пиво и чипсы", 38.0))
        assertNull("unexplained", r("пиво", 25.0))
        assertNull("unexplained", r("пиво", 24.0))
        assertNull("non-alcoholic carries 2-3x — a different pool", r("пиво безалкогольное", 40.0))
        assertNull("correct for 0.33 L, but not this 0.5 L anchor", r("бутылка пива корона 0.33", 10.5))
    }


    @Test
    fun aMixedNoteScalesONLYTheAnchoredComponent() {
        // `est_carbs` is the whole meal; the factor belongs to one dish. Scaling the total
        // would move the bread by the smoothie's factor. Beer is the next anchor and it
        // has three mixed rows, so this is one step from firing, not hypothetical.
        // ... and the decision is to REFUSE it, because `carbs_source` stamps the WHOLE
        // row: marking "smoothie and bread" as `anchor` would claim the bread's grams are
        // externally measured too, and `gramsAnchored` is read per NOTE. Splitting
        // provenance per component is a schema change, not something to fake with a flag.
        val a = "СОСТАВ: смузи = 33 угл\nСОСТАВ: хлеб ×2 = 14 угл"
        assertNull(anchorRewrite("смузи и хлеб", a, 61.0, "manual", smoothie))
        // A note that is ALL smoothie by composition still passes and still scales, so the
        // refusal above is a decision rather than a missing capability.
        assertEquals(
            22.0,
            anchorRewrite("смузи", "СОСТАВ: смузи = 33 угл", 33.0, "manual", smoothie)!!.newEstCarbs,
            1e-9,
        )
    }

    @Test
    fun aCompositionThatNamesTheDishNowhereIsRefused() {
        // Whatever the title says. A note titled "smoothie" whose composition is all bread is
        // not ours to touch.
        assertNull(anchorRewrite("смузи", "СОСТАВ: хлеб ×2 = 14 угл", 28.0, "manual", smoothie))
    }

    @Test
    fun theBeerRangeChecksTheANCHOREDPartNotTheWholePlate() {
        val beer = CARB_ANCHORS.single { it.conceptId == "beer" }
        // A mixed plate whose BEER part is one plausible bottle passes, and only the beer
        // part would be scaled (factor 1.0 here, so nothing moves but the provenance).
        // "beer and chips" is refused BOTH ways: with a composition because the row is only
        // PARTLY anchored, and without one because 38 g is not one bottle. The user
        // named this row as excluded and the code reaches the same answer by rule, twice.
        val a = "СОСТАВ: пиво = 18 угл\nСОСТАВ: чипсы = 20 угл"
        assertNull(anchorRewrite("пиво и чипсы", a, 38.0, "manual", beer))
        assertNull(anchorRewrite("пиво и чипсы", null, 38.0, "manual", beer))
        // A pure beer note whose composition names it still passes — and this is the case
        // that made the match move from the TITLE to the COMPONENT: `conceptFor` on the
        // title "bottle of corona" does find beer, but on "beer and chips" it returns
        // CHIPS, so a title-only test would have refused mixed plates for the wrong reason.
        assertNotNull(anchorRewrite("бутылка короны", "СОСТАВ: пиво = 18 угл", 18.0, null, beer))
    }

    private fun rewrite(
        content: String,
        est: Double?,
        src: String? = "manual",
        analysis: String? = null,
    ) = anchorRewrite(content, analysis, est, src, smoothie)
}
