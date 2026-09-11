package com.diapilot.core.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A SUSTAINED HIGH MUST COUNT OBSERVED TIME, NOT WALL-CLOCK TIME.
 *
 * The rule was added after two external reviews: 37.2% of time above 10
 * and 8.7% above 13.9 were not served by anything, because [findPredictedHigh]
 * is a CROSSING detector and refuses to work once glucose is already high.
 *
 * What is pinned here is exactly what is easy to break in the next edit: the
 * threshold, the duration, and — most importantly — that a gap in observation
 * breaks the count. Readings are back-filled (discipline #6), so "there were
 * no points" does not mean "it was high".
 */
class SustainedHighTest {

    private val t0 = 1_700_000_000_000L
    private fun min(m: Long) = t0 + m * 60_000

    /** A series every 5 minutes at one level. */
    private fun flat(fromMin: Long, toMin: Long, mmol: Double) =
        (fromMin..toMin step 5).map { min(it) to mmol }

    @Test
    fun `an hour above the threshold fires and reports what it saw`() {
        val v = SustainedHigh.evaluate(flat(0, 70, 15.0), nowMs = min(70))
        assertNotNull("an hour above 13.9 must fire", v)
        assertEquals(70L, v!!.minutesAbove)
        assertEquals(15.0, v.peakMmol, 1e-9)
    }

    @Test
    fun `a shorter excursion is not an episode`() {
        assertNull(
            "45 minutes is an excursion, not a sustained high",
            SustainedHigh.evaluate(flat(0, 45, 15.0), nowMs = min(45)),
        )
    }

    @Test
    fun `just under the threshold is silent`() {
        assertNull(
            "13.8 is below the threshold — there should be no alarm",
            SustainedHigh.evaluate(flat(0, 120, 13.8), nowMs = min(120)),
        )
    }

    /**
     * THE MOST IMPORTANT CASE HERE.
     *
     * A gap in observation is not proof that the whole time was high.
     * Gaps in the feed of >=25 minutes occur roughly 0.6 times per day, and
     * without this gate every such gap would hand the rule an hour of
     * "high" that nobody actually saw.
     */
    @Test
    fun `a gap in observation restarts the count instead of filling it in`() {
        val series = flat(0, 20, 15.0) + flat(100, 120, 15.0)
        val v = SustainedHigh.evaluate(series, nowMs = min(120))
        assertNull("an eighty-minute gap was counted as observation", v)
    }

    @Test
    fun `a dip below the threshold ends the episode`() {
        val series = flat(0, 60, 15.0) + listOf(min(65) to 12.0) + flat(70, 100, 15.0)
        val v = SustainedHigh.evaluate(series, nowMs = min(100))
        assertNull("the episode must be counted from the dip, not from the start of the series", v)
    }

    @Test
    fun `a stale tail says nothing about now`() {
        assertNull(
            "a reading an hour old does not describe the current state",
            SustainedHigh.evaluate(flat(0, 120, 15.0), nowMs = min(180)),
        )
    }

    /** Threshold and duration are part of the contract: chosen by measurement (0.87/day). */
    @Test
    fun `the measured thresholds are the ones that ship`() {
        assertEquals(13.9, SustainedHigh.THRESHOLD_MMOL, 1e-9)
        assertEquals(60L, SustainedHigh.SUSTAINED_MIN)
        assertEquals(120L, SustainedHigh.COOLDOWN_MIN)
    }
}
