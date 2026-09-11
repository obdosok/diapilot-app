package com.diapilot.core.twin

import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HypoAlertTest {
    private val min = 60_000L

    private fun pred(vararg pts: Pair<Long, Double>): List<PredictedPoint> =
        pts.map { PredictedPoint(it.first, it.second, it.second, it.second) }

    @Test
    fun firesOnMedianCrossingWithinLead() {
        val p = pred(
            0L to 6.0, 10 * min to 5.0, 20 * min to 4.2, 30 * min to 3.6, 40 * min to 3.2,
        )
        val hit = findPredictedHypo(p, nowMs = 0, currentMmol = 6.0, thresholdMmol = 3.9)
        assertNotNull(hit)
        assertEquals(30.0, hit!!.leadMin, 1e-9)
        assertEquals(3.2, hit.minMmol, 1e-9)
    }

    @Test
    fun silentWhenAlreadyLowOrNoCrossing() {
        val falling = pred(0L to 4.0, 20 * min to 3.5)
        // Current already near threshold — the acute alarm's territory.
        assertNull(findPredictedHypo(falling, 0, currentMmol = 4.0, thresholdMmol = 3.9))
        // Comfortable and staying up.
        val flat = pred(0L to 7.0, 20 * min to 6.8, 40 * min to 6.9)
        assertNull(findPredictedHypo(flat, 0, currentMmol = 7.0, thresholdMmol = 3.9))
        // Crossing beyond the lead window doesn't fire.
        val late = pred(0L to 7.0, 40 * min to 5.0, 90 * min to 3.0)
        assertNull(findPredictedHypo(late, 0, currentMmol = 7.0, thresholdMmol = 3.9, leadMaxMin = 45.0))
    }

    @Test
    fun leastSquaresSlopeIsMmolPerMinute() {
        val pts = listOf(
            GlucosePoint(0L, 10.0), GlucosePoint(5 * min, 9.0),
            GlucosePoint(10 * min, 8.0), GlucosePoint(15 * min, 7.0),
        )
        assertEquals(-0.2, leastSquaresSlopePerMin(pts)!!, 1e-9)
    }

    @Test
    fun fallVelocityFiresOnAFastProjectedCrossingFromAnyLevel() {
        // Falling 0.1 mmol/min from 8.0 — projects to 3.5 in 45 min, crosses 3.9.
        val win = (0..3).map { GlucosePoint(it * 5 * min, 8.0 - 0.1 * it * 5) }
        val v = findFallVelocity(win, nowMs = 15 * min, currentMmol = 7.5, thresholdMmol = 3.9)
        assertNotNull(v)
        assertTrue("slope negative: ${v!!.slopeMmolPerMin}", v.slopeMmolPerMin < 0)
        assertTrue("crosses within horizon: ${v.projectedMmol}", v.projectedMmol <= 3.9)
    }

    @Test
    fun fallVelocitySilentWhenFlatRisingAlreadyLowOrTooShallow() {
        val flat = (0..3).map { GlucosePoint(it * 5 * min, 7.0) }
        assertNull(findFallVelocity(flat, 15 * min, 7.0, 3.9))

        val rising = (0..3).map { GlucosePoint(it * 5 * min, 5.0 + 0.1 * it * 5) }
        assertNull(findFallVelocity(rising, 15 * min, 6.5, 3.9))

        // Already at/under the threshold — the value rule's job, not this one's.
        val low = (0..3).map { GlucosePoint(it * 5 * min, 4.0 - 0.05 * it * 5) }
        assertNull(findFallVelocity(low, 15 * min, 3.6, 3.9))

        // Steady but shallow from a high level: won't reach the line in the horizon.
        val shallowHigh = (0..3).map { GlucosePoint(it * 5 * min, 12.0 - 0.02 * it * 5) }
        assertNull(findFallVelocity(shallowHigh, 15 * min, 11.7, 3.9, projMin = 45.0))
    }

    // THREE TESTS OVER `hypoAlertBacktest` WERE REMOVED ALONG WITH IT:
    // velocityArmCatchesADriverlessFallTheBaseArmMisses,
    // baseArmIsIndependentOfVelocityParams, backtestCountsEventsAndAlerts.
    // They replayed the rule through a legacy engine that no longer exists.
    // WHAT THEY GUARDED AND WHAT IS NOW UNGUARDED: "the velocity arm does not
    // leak into the base arm" — a property of the BACKTEST, not of the
    // shipped rule (in the app both signals are computed by HypoAlertNotifier
    // from its own inputs). The rule itself remains covered by the tests
    // above and by HypoAlertLogicTest. When backtesting returns over the
    // physio run, these three should be restored first.
}
