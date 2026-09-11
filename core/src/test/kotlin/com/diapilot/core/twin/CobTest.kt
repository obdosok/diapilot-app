package com.diapilot.core.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COB is the mirror of IOB: what is LEFT of what was eaten. It must be honest
 * about the two things we know (the grams, the time since) and never invent
 * carbs for food it has no grams for.
 */
class CobTest {

    private val now = 1_700_000_000_000L
    private fun at(minAgo: Long) = now - minAgo * 60_000

    @Test
    fun `just eaten — all of it is still coming`() {
        val f = ActiveFood(now, rise = 3.0, timeToPeakMin = 60.0, carbGrams = 60.0)
        assertEquals(60.0, cobGrams(listOf(f), now), 1e-9)
    }

    @Test
    fun `long past its peak — nothing left`() {
        val f = ActiveFood(at(600), rise = 3.0, timeToPeakMin = 60.0, carbGrams = 60.0)
        assertTrue("ten hours on, COB must be ~0", cobGrams(listOf(f), now) < 1.0)
    }

    @Test
    fun `mid-absorption — between, and falling`() {
        val f = ActiveFood(at(30), rise = 3.0, timeToPeakMin = 60.0, carbGrams = 60.0)
        val cob = cobGrams(listOf(f), now)
        assertTrue("half an hour into a 60-min meal: $cob", cob in 1.0..59.0)
        // Monotone: later is always less.
        assertTrue(cobGrams(listOf(f), now + 30 * 60_000) < cob)
    }

    @Test
    fun `a faster dish empties sooner than a slow one`() {
        val fast = ActiveFood(at(40), rise = 3.0, timeToPeakMin = 40.0, carbGrams = 40.0)
        val slow = ActiveFood(at(40), rise = 3.0, timeToPeakMin = 120.0, carbGrams = 40.0)
        assertTrue(
            "same grams, same age — the slow one must still hold more",
            cobGrams(listOf(fast), now) < cobGrams(listOf(slow), now),
        )
    }

    @Test
    fun `food with no grams contributes nothing — never a guess`() {
        // A detected rise with no note has no carbs to report. Inventing them
        // from the rise would be the app estimating what the user ate.
        val detected = ActiveFood(at(20), rise = 3.0, timeToPeakMin = 60.0)
        assertEquals(0.0, cobGrams(listOf(detected), now), 1e-9)
    }

    @Test
    fun `meals add up`() {
        val a = ActiveFood(now, rise = 2.0, timeToPeakMin = 60.0, carbGrams = 30.0)
        val b = ActiveFood(now, rise = 2.0, timeToPeakMin = 60.0, carbGrams = 20.0)
        assertEquals(50.0, cobGrams(listOf(a, b), now), 1e-9)
    }

    @Test
    fun `a fat tail keeps carbs on board past the fast peak`() {
        // Pizza: the fast phase is done, the slow one is not — COB must know.
        val pizza = ActiveFood(
            at(90), rise = 3.0, timeToPeakMin = 45.0, carbGrams = 60.0,
            absorption = TwoGamma(
                fastRise = 3.0, fastPeakMin = 45.0,
                slowRise = 2.0, slowPeakMin = 240.0,
            ),
        )
        val flat = ActiveFood(at(90), rise = 3.0, timeToPeakMin = 45.0, carbGrams = 60.0)
        assertTrue(
            "90 min in, the pizza still holds more than the single-phase dish",
            cobGrams(listOf(pizza), now) > cobGrams(listOf(flat), now),
        )
    }
}
