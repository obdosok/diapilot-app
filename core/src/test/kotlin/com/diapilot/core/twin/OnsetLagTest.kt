package com.diapilot.core.twin

import com.diapilot.core.analysis.FOOD_ONSET_LAG_MIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Food is not in the blood the moment it is in the mouth.
 *
 * The user caught this in the moment: eating 37 minutes ago and already
 * seeing the forecast lower its prediction — for no reason. With no lag
 * the gamma starts at tau=0, so at
 * minute 37 a ttp=60 meal is «76% absorbed», the forecast carries only the
 * remaining 24%, and the insulin — all of which is still ahead — turns the line
 * down. Measured as a systematic undershoot: bias +0.64 mmol on the clean era.
 */
class OnsetLagTest {

    private val now = 1_700_000_000_000L
    private fun meal(lag: Double) = ActiveFood(
        onsetMs = now, rise = 3.0, timeToPeakMin = 60.0, onsetLagMin = lag,
    )

    /**
     * PINS THE DECISION, not the arithmetic — and it exists because every other
     * test in this file hard-codes the literal 16.0 in its own fixture, so
     * setting the constant to 40 left the whole suite GREEN (checked by
     * mutation). "We decided to leave it at 16" was a comment, not a fact.
     *
     * Re-measured on the v8 kernel: the
     * per-concept spread is real and survives the new kernel (12..40 min), but it
     * cannot be separated from logging habit, the pooled estimator is not robust,
     * and the segment scorer holds TWO food-opened segments at h=60 whose dish
     * would change. If this number moves, that argument moved with it.
     */
    @Test
    fun `the shipped lag is the value that was measured`() {
        assertEquals("the shipped onset lag must stay the measured value", 16.0, FOOD_ONSET_LAG_MIN, 1e-9)
    }

    /**
     * SEPARATE ON PURPOSE, and the separation is the whole point.
     *
     * The equality above pins today's value; this pins the DIRECTION it may ever
     * move in. Inside one test the inequality could never fail — the assertEquals
     * fires first in every case — so the day `max(16, measured)` ships and
     * somebody relaxes the pin, they would take the safety property with it. Here
     * it survives that edit and keeps failing on the one change that matters.
     */
    @Test
    fun `the lag may only ever get LONGER — a shorter one hides hypo risk`() {
        assertTrue(
            "a later meal draws a DEEPER dip; going below the measured median " +
                "makes the forecast understate hypo risk",
            FOOD_ONSET_LAG_MIN >= 16.0,
        )
    }

    // NOT GUARDED HERE, and it cannot be: the constant is APPLIED in
    // `FoodSources.activeFoods` (app/) and its `HarnessFoods` port (tools/),
    // neither of which `:core:test` can reach. A green core suite says the curve
    // maths honours a lag, never that the live food path still passes one. A test
    // that hand-builds an `ActiveFood` in core and calls itself a check on the
    // live path is worse than none — it was written, it passed under BOTH
    // mutants (16 → 40 and 16 → 8), and it is deleted rather than left.

    @Test
    fun `nothing is absorbed before absorption starts`() {
        val f = meal(16.0)
        assertEquals(0.0, foodDelta(f, 0.0), 1e-9)
        assertEquals(0.0, foodDelta(f, 10.0), 1e-9)
        assertEquals(0.0, foodDelta(f, 16.0), 1e-9)
        assertTrue("the rise begins after the lag", foodDelta(f, 25.0) > 0.0)
    }

    @Test
    fun `at minute 37 the model no longer thinks the meal is nearly done`() {
        // The user's exact moment, on the LIVE forecast's curve. activeFoods
        // builds legacy smoothstep foods (absorption=null) — the two-component
        // gamma is the fp shadow's. Smoothstep at 37/60 realizes 67%, so the
        // forecast carries only a third of the rise while all the insulin is
        // still ahead. With a 16-min lag only 21 min have absorbed: 28%.
        val without = foodDelta(meal(0.0), 37.0) / 3.0
        val with = foodDelta(meal(16.0), 37.0) / 3.0
        assertEquals("live curve, no lag", 0.67, without, 0.02)
        assertEquals("live curve, 16-min lag", 0.28, with, 0.02)
        assertTrue("the lag more than halves what is written off", with < without / 2)
    }

    @Test
    fun `the lag SHIFTS the curve, it does not shrink it`() {
        // Total absorption must be unchanged — a delayed meal is not a smaller
        // meal. Same fraction, 16 minutes later.
        val a = foodDelta(meal(0.0), 60.0)
        val b = foodDelta(meal(16.0), 76.0)
        assertEquals(a, b, 1e-9)
    }

    @Test
    fun `a lagged meal is still ACTING when an unlagged one would be written off`() {
        // simulateForward drops foods past their peak. A meal eaten 70 min ago
        // with a 16-min lag has absorbed for 54 — dropping it would silently
        // delete the rise that is still coming.
        val anchor = now + 70 * 60_000
        val lagged = ActiveFood(now, rise = 3.0, timeToPeakMin = 60.0, onsetLagMin = 16.0)
        val out = simulateForward(
            anchorTsMs = anchor, anchorMmol = 6.0, boluses = emptyList(),
            kernel = emptyList(), horizonMin = 60.0, foods = listOf(lagged),
        )
        assertTrue("the remaining rise must still arrive", out.last().mmol > 6.05)
    }

    @Test
    fun `no lag by default — a detector meal's onset IS the rise`() {
        // The detector sees the rise, not the eating: its lag already happened.
        // Giving it one would delay the food twice.
        val detected = ActiveFood(now, rise = 3.0, timeToPeakMin = 60.0)
        assertEquals(0.0, detected.onsetLagMin, 1e-9)
        assertTrue(foodDelta(detected, 5.0) > 0.0)
    }

    @Test
    fun `COB counts the lag too — nothing has left the stomach yet`() {
        val f = ActiveFood(
            now, rise = 3.0, timeToPeakMin = 60.0, carbGrams = 60.0, onsetLagMin = 16.0,
        )
        assertEquals("still all on board", 60.0, cobGrams(listOf(f), now + 10 * 60_000), 1e-6)
        assertTrue("and it does start", cobGrams(listOf(f), now + 40 * 60_000) < 45.0)
    }
}
