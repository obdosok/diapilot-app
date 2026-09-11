package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Doubling a plate must double the GRAMS and leave the KIND of food alone.
 *
 * The failure this pins is silent: scaling the fast/medium/slow shares would
 * still produce a plausible-looking line, but `parseFoodKineticsV2` would read
 * it as a different food rather than more of the same one — and the sum that is
 * supposed to be 1.0 would be 2.0.
 */
class FoodPortionScalingV1Test {

    private val analysis = """
        Пицца, два куска.
        KINETICS_V2: fast=0.30;medium=0.50;slow=0.20;form=MIXED;fiber=3;confidence=0.6;source=photo;protein=20;fat=18
    """.trimIndent()

    private fun kinetics(s: String?) =
        requireNotNull(s).lineSequence().first { it.trim().startsWith("KINETICS_V2:") }

    @Test
    fun `doubling scales grams and holds the fractions`() {
        val out = FoodPortionScalingV1.scale("пицца", 70.0, analysis, 2.0)
        assertEquals(140.0, requireNotNull(out.estCarbsG), 1e-9)

        val parsed = parseFoodKineticsV2(out.analysis, null, null)
        assertEquals("protein did not double", 40.0, requireNotNull(parsed.proteinG), 1e-6)
        assertEquals("fat did not double", 36.0, requireNotNull(parsed.fatG), 1e-6)
        assertEquals("fibre did not double", 6.0, requireNotNull(parsed.fiberG), 1e-6)

        // The SHARES IN THE LINE are held — that is what this object promises.
        val line = kinetics(out.analysis)
        assertTrue("the fast share was scaled: $line", line.contains("fast=0.30"))
        assertTrue("the medium share was scaled: $line", line.contains("medium=0.50"))
        assertTrue("the slow share was scaled: $line", line.contains("slow=0.20"))
        assertEquals(
            "the shares must still sum to one", 1.0,
            parsed.fastFraction + parsed.mediumFraction + parsed.slowFraction, 1e-6,
        )
        assertEquals("form is a property of the dish, not of the portion",
            FoodPhysicalFormV2.MIXED, parsed.physicalForm)
        assertEquals("confidence is the same reading of the same plate", 0.6, parsed.confidence, 1e-6)
    }

    @Test
    fun `halving is the same rule in the other direction`() {
        val out = FoodPortionScalingV1.scale("пицца", 70.0, analysis, 0.5)
        assertEquals(35.0, requireNotNull(out.estCarbsG), 1e-9)
        val parsed = parseFoodKineticsV2(out.analysis, null, null)
        assertEquals(10.0, requireNotNull(parsed.proteinG), 1e-6)
        assertEquals(9.0, requireNotNull(parsed.fatG), 1e-6)
        assertTrue("the text must say which portion this is", out.text.contains("½"))
    }

    @Test
    fun `the portion mark replaces itself rather than stacking`() {
        val once = FoodPortionScalingV1.scale("пицца", 70.0, analysis, 0.5)
        val twice = FoodPortionScalingV1.scale(once.text, once.estCarbsG, once.analysis, 0.5)
        assertEquals(
            "the mark stacked — the note now describes its edit history, not its state",
            1, Regex("½").findAll(twice.text).count(),
        )
        assertEquals(17.5, requireNotNull(twice.estCarbsG), 1e-9)
    }

    @Test
    fun `a factor of one, or nonsense, returns the note untouched`() {
        val text = "пицца"
        listOf(1.0, 0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { f ->
            val out = FoodPortionScalingV1.scale(text, 70.0, analysis, f)
            assertSame("factor $f rewrote the text", text, out.text)
            assertEquals("factor $f moved the carbohydrate", 70.0, requireNotNull(out.estCarbsG), 1e-9)
            assertSame("factor $f rewrote the analysis", analysis, out.analysis)
        }
    }

    @Test
    fun `a note without a kinetics line still scales its carbohydrate`() {
        val out = FoodPortionScalingV1.scale("хлеб", 24.0, "просто текст", 2.0)
        assertEquals(48.0, requireNotNull(out.estCarbsG), 1e-9)
        assertEquals("просто текст", out.analysis)
    }

    /**
     * THE PARSED SPEED IS UNCHANGED BY SCALING, and the reason is worth
     * recording because the first version of this test asserted the opposite.
     *
     * `parseFoodKineticsV2` applies `capFastForFat`, so the fast share the LINE
     * declares (0.30) is not what comes back — 0.20 does, at BOTH sizes. The
     * guard is already saturated at this dish's 18 g of fat, so doubling to 36 g
     * moves nothing. Scaling therefore changes the AMOUNT and not the kind,
     * which is what the user asked for; the cap is a property of the dish.
     */
    @Test
    fun `scaling does not change what kind of food the parser sees`() {
        val single = parseFoodKineticsV2(analysis, null, null)
        val double = parseFoodKineticsV2(
            requireNotNull(FoodPortionScalingV1.scale("пицца", 70.0, analysis, 2.0).analysis), null, null,
        )
        assertEquals("the portion changed the food's speed class",
            single.fastFraction, double.fastFraction, 1e-9)
        assertEquals(single.mediumFraction, double.mediumFraction, 1e-9)
        assertEquals(single.slowFraction, double.slowFraction, 1e-9)
        assertEquals(single.physicalForm, double.physicalForm)

        // ...and the cap really is what holds 0.30 down to 0.20 here: the same
        // line with no fat comes back as declared.
        val lean = parseFoodKineticsV2(analysis.replace("fat=18", "fat=0"), null, null)
        assertTrue(
            "the fat guard is not what caps this dish: ${lean.fastFraction} vs ${single.fastFraction}",
            lean.fastFraction > single.fastFraction + 1e-6,
        )
    }
}
