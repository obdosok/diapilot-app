package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlausibilityTest {
    private val min = 60_000L
    private fun series(vararg pts: Pair<Long, Double>) = pts.map { GlucosePoint(it.first * min, it.second) }
    private fun gate(pts: List<GlucosePoint>) = plausibilityGate(pts)

    @Test
    fun nonphysicalAndBelowFloorAreSuspect() {
        val g = gate(series(
            0L to 5.0, 5L to 3.0, 10L to 1.8, 15L to -0.2,
            20L to 4.0, 25L to (1_250.0 / MGDL_PER_MMOL_F),
        ))
        assertEquals(SuspectReason.NONE, g[0].reason)          // 5.0 OK
        assertEquals(SuspectReason.BELOW_FLOOR, g[2].reason)   // 1.8 < floor
        assertEquals(SuspectReason.NON_PHYSICAL, g[3].reason)  // -0.2
        assertTrue(g[4].ok)                                    // 4.0 recovered OK
        assertEquals(SuspectReason.NON_PHYSICAL, g[5].reason)  // activation artifact
    }

    @Test
    fun aRealGradualLowIsKept() {
        // 5.0 -> 2.3 over 30 min (0.09 mmol/min), no impossible value: a real low.
        val g = gate(series(0L to 5.0, 5L to 4.5, 10L to 4.0, 15L to 3.4, 20L to 2.9, 25L to 2.5, 30L to 2.3, 35L to 3.0))
        assertTrue("no reading should be de-trusted: ${g.filter { !it.ok }.map { it.point.mmol }}",
            g.all { it.ok })
    }

    @Test
    fun anExcursionContainingAnImpossibleValueIsDeTrustedWhole() {
        // The 2.5 and 3.0 shoulders sit in the same below-range run as the −0.2,
        // so the whole compression event is de-trusted, not just the core.
        val g = gate(series(0L to 5.0, 5L to 3.0, 10L to 2.5, 15L to 1.0, 20L to (-0.2), 25L to 1.5, 30L to 3.0, 35L to 4.5))
        // shoulders (>= floor) flagged as neighbours of the artifact
        val shoulder = g.first { it.point.mmol == 2.5 }
        assertFalse("2.5 shoulder must be de-trusted", shoulder.ok)
        assertEquals(SuspectReason.ARTIFACT_NEIGHBOR, shoulder.reason)
        assertTrue("3.0 in the excursion de-trusted", g.first { it.point.mmol == 3.0 && it.point.tsMs == 30L * min }.let { !it.ok })
        assertTrue("4.5 above ceiling stays OK", g.last().ok)
    }

    @Test
    fun aFastFallIntoLowIsSuspectButAFastFallThatStaysHighIsNot() {
        // 8.0 -> 2.5 in 5 min: >0.15 mmol/min AND arrives below 3.0 -> suspect.
        val low = gate(series(0L to 8.0, 5L to 8.0, 10L to 2.5, 15L to 3.5))
        assertEquals(SuspectReason.FAST_FALL_TO_LOW, low[2].reason)
        // 12.0 -> 8.0 in 5 min: fast, but lands at 8.0 -> a real fast fall, kept.
        val high = gate(series(0L to 12.0, 5L to 12.0, 10L to 8.0, 15L to 6.0))
        assertTrue(high.all { it.ok })
    }

    @Test
    fun aSeparateRealLowSurvivesAnArtifactEarlierTheSameNight() {
        // artifact excursion, a clean return above ceiling, then a real low with
        // NO impossible value in it — the real low must stay trusted.
        val g = gate(
            series(
                0L to 5.0, 5L to 1.0, 10L to (-0.3), 15L to 2.0, 20L to 5.5,   // artifact + recovery
                60L to 5.0, 65L to 4.0, 70L to 3.2, 75L to 2.6, 80L to 2.4, 85L to 3.0, 90L to 4.5,  // real low
            ),
        )
        val realLow = g.first { it.point.tsMs == 80L * min }  // 2.4
        assertTrue("a real low far from any impossible value must stay OK", realLow.ok)
    }

    @Test
    fun trustedHelperDropsOnlySuspect() {
        val g = gate(series(0L to 5.0, 5L to (-0.1), 10L to 4.0))
        assertEquals(listOf(5.0, 4.0), g.trusted().map { it.mmol })
    }
}
