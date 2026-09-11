package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A FATTY DISH MAY NOT BE READ AS PURE FAST CARBOHYDRATE.
 *
 * The guard exists because the labeller files a large share of food-era
 * carbohydrate as fast, including pancakes with cheese and ham (30 g fat),
 * ice cream (15 g) and crisps with beer (21 g). Believing that literally
 * cost accuracy at the alert horizon on held-out episodes, and it also
 * forced the fitted fast triangle out further — a labelling error written
 * into the physiology.
 *
 * THE TESTS ARE WRITTEN TO FAIL IF THE GUARD IS DELETED, which is the only
 * property that makes them worth having: a test that passes with the rule
 * removed is covering nothing (this repo has shipped one such test before).
 * Each assertion below names the exact number the guard must produce, not a
 * range that the un-guarded value would also satisfy.
 */
class FatNotFastGuardTest {

    private fun line(fast: Double, med: Double, slow: Double, fat: Double) =
        "KINETICS_V2: fast=$fast;medium=$med;slow=$slow;form=SOFT_SOLID;" +
            "confidence=0.6;source=llm-structured-v4;alcohol=false;protein=5.0;fat=$fat"

    /** A jam pancake, stored as an accepted dish at fast=1.0 with 10 g fat —
     *  BELOW the threshold, so it must be left alone. The guard is narrow on
     *  purpose; a rule that also rewrote lean dishes would be trading one
     *  confident wrong answer for another. */
    @Test
    fun `a lean dish keeps its label even at fast one`() {
        val k = parseFoodKineticsV2(line(1.0, 0.0, 0.0, 10.0))
        assertEquals(1.0, k.fastFraction, 1e-9)
    }

    /** The pancakes with cheese and ham: 30 g of fat, filed at fast 0.96. */
    @Test
    fun `a fatty dish is capped and the excess goes to medium`() {
        val k = parseFoodKineticsV2(line(0.96, 0.04, 0.0, 30.0))
        assertEquals(FAT_NOT_FAST_MAX_FAST, k.fastFraction, 1e-9)
        assertEquals(0.04 + (0.96 - FAT_NOT_FAST_MAX_FAST), k.mediumFraction, 1e-9)
        assertEquals(0.0, k.slowFraction, 1e-9)
        assertEquals(1.0, k.fastFraction + k.mediumFraction + k.slowFraction, 1e-9)
    }

    /** Exactly at the threshold nothing happens — the comparison is strict, and
     *  a future edit that makes it inclusive would move real dishes. */
    @Test
    fun `the threshold is exclusive`() {
        val at = parseFoodKineticsV2(line(0.9, 0.1, 0.0, FAT_NOT_FAST_THRESHOLD_G))
        assertEquals(0.9, at.fastFraction, 1e-9)
        val above = parseFoodKineticsV2(line(0.9, 0.1, 0.0, FAT_NOT_FAST_THRESHOLD_G + 0.1))
        assertEquals(FAT_NOT_FAST_MAX_FAST, above.fastFraction, 1e-9)
    }

    /** A fatty dish already labelled slow-ish must not be PUSHED UP to the cap.
     *  The guard is a ceiling, never an assignment. */
    @Test
    fun `a fatty dish below the cap is untouched`() {
        val k = parseFoodKineticsV2(line(0.05, 0.35, 0.60, 40.0))
        assertEquals(0.05, k.fastFraction, 1e-9)
        assertEquals(0.60, k.slowFraction, 1e-9)
    }

    /** Fat unknown means the guard has nothing to act on and must not guess. */
    @Test
    fun `no fat recorded means no change`() {
        val k = parseFoodKineticsV2(
            "KINETICS_V2: fast=1.0;medium=0.0;slow=0.0;form=SOLID;confidence=0.5;source=x;alcohol=false",
        )
        assertEquals(1.0, k.fastFraction, 1e-9)
    }

    /** Alcohol still rides through — the two post-processing steps share one
     *  call site and it would be easy to drop one while editing the other. */
    @Test
    fun `the alcohol flag survives the guard`() {
        val k = parseFoodKineticsV2(
            line(0.9, 0.1, 0.0, 30.0).replace("alcohol=false", "alcohol=true"),
        )
        assertTrue(k.alcoholPresent)
        assertEquals(FAT_NOT_FAST_MAX_FAST, k.fastFraction, 1e-9)
    }
}
