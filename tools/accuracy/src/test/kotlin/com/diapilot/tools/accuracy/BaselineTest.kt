package com.diapilot.tools.accuracy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two naive baselines and the skill score, on hand-written vectors —
 * the same terms as MetricsTest: no database, pure functions only.
 */
class BaselineTest {
    private fun r(minute: Int, mmol: Double) = GlucoseReadingRow(minute * 60_000L, mmol)

    // Fifteen minutes of a steady rise, then the anchor at minute 60.
    private val rising = listOf(r(45, 6.0), r(50, 6.1), r(55, 6.2), r(60, 6.3), r(90, 7.0))
    private val anchor = 60 * 60_000L

    @Test fun `the anchor reading is the last one at or before the anchor, never a later one`() {
        assertEquals(6.3, anchorReading(rising, anchor)!!.mmol, 1e-9)
        // Two minutes after the reading: still that reading, not the one at 90.
        assertEquals(6.3, anchorReading(rising, anchor + 2 * 60_000)!!.mmol, 1e-9)
        // Seven minutes late is outside the ledger's six-minute tolerance.
        assertNull(anchorReading(rising, anchor + 7 * 60_000))
        // Before the first reading there is nothing in hand.
        assertNull(anchorReading(rising, 10 * 60_000L))
    }

    @Test fun `last-value baseline is the anchor reading at every horizon`() {
        assertEquals(6.3, lastValueBaseline(rising, anchor)!!, 1e-9)
    }

    @Test fun `linear baseline continues the fitted slope of the last fifteen minutes`() {
        // Slope 0.02 mmol/min over 45..60, from the anchor value 6.3.
        assertEquals(6.3 + 0.02 * 30, linearBaseline(rising, anchor, 30)!!, 1e-9)
        assertEquals(6.3 + 0.02 * 180, linearBaseline(rising, anchor, 180)!!, 1e-9)
    }

    @Test fun `linear baseline fits, it does not join the endpoints`() {
        // Noisy window: OLS over four points, not (last - first) / span.
        val noisy = listOf(r(45, 6.0), r(50, 6.4), r(55, 6.1), r(60, 6.5))
        // xs relative to the anchor: -15,-10,-5,0 (mean -7.5); ys mean 6.25.
        // sxy = (-7.5)(-0.25)+(-2.5)(0.15)+(2.5)(-0.15)+(7.5)(0.25) = 3.0; sxx = 125.
        val slope = 3.0 / 125.0
        assertEquals(6.5 + slope * 60, linearBaseline(noisy, anchor, 60)!!, 1e-9)
    }

    @Test fun `linear baseline is clamped to the store's own range`() {
        val falling = listOf(r(45, 5.0), r(50, 4.4), r(55, 3.8), r(60, 3.2))   // -0.12 mmol/min
        assertEquals(BASELINE_MIN_MMOL, linearBaseline(falling, anchor, 180)!!, 1e-9)
        val soaring = listOf(r(45, 20.0), r(50, 22.0), r(55, 24.0), r(60, 26.0)) // +0.4 mmol/min
        assertEquals(BASELINE_MAX_MMOL, linearBaseline(soaring, anchor, 60)!!, 1e-9)
    }

    @Test fun `one reading is not a slope`() {
        assertNull(linearBaseline(listOf(r(60, 6.3)), anchor, 30))
        // And readings older than the window do not count toward one either.
        assertNull(linearBaseline(listOf(r(40, 5.0), r(60, 6.3)), anchor, 30))
    }

    @Test fun `skill is one minus the MAE ratio and undefined against a perfect baseline`() {
        assertEquals(0.5, skillScore(0.5, 1.0)!!, 1e-9)
        assertEquals(0.0, skillScore(1.0, 1.0)!!, 1e-9)
        assertEquals(-1.0, skillScore(2.0, 1.0)!!, 1e-9)
        assertNull(skillScore(0.1, 0.0))
    }

    @Test fun `a run without a reading at its anchor contributes no baseline sample`() {
        val runs = mapOf(
            1L to RunRow(1, anchorTsMs = anchor, consumer = "main", algoVersion = "v"),
            2L to RunRow(2, anchorTsMs = anchor + 30 * 60_000, consumer = "main", algoVersion = "v"),
        )
        val points = listOf(
            ForecastPointRow(1, 30, anchor + 30 * 60_000, 6.8),
            ForecastPointRow(2, 30, anchor + 60 * 60_000, 6.8),
        )
        val readings = rising + listOf(r(120, 7.5))
        val samples = matchHorizonWithBaselines(points, runs, readings, 30)
        // Run 2's anchor (minute 90) has a reading, but the window 75..90 holds
        // only that one reading, so no slope — it is left out, and the model is
        // scored against the baselines on run 1 alone.
        assertEquals(1, samples.size)
        val s = samples.single()
        assertEquals(6.8, s.model.predictedMmol, 1e-9)
        assertEquals(7.0, s.model.actualMmol, 1e-9)
        assertEquals(6.3, s.lastValue.predictedMmol, 1e-9)
        assertEquals(6.9, s.linear.predictedMmol, 1e-9)
        val stats = baselineStats(samples)!!
        assertEquals(1, stats.n)
        assertEquals(0.2, stats.model.maeMmol, 1e-9)
        assertEquals(0.7, stats.lastValue.maeMmol, 1e-9)
        assertEquals(0.1, stats.linear.maeMmol, 1e-9)
        assertEquals(1.0 - 0.2 / 0.7, stats.skillVsLastValue!!, 1e-9)
        assertEquals(1.0 - 0.2 / 0.1, stats.skillVsLinear!!, 1e-9)
    }
}
