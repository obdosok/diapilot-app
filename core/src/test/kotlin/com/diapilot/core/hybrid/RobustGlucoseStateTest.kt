package com.diapilot.core.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RobustGlucoseStateTest {
    private val minute = 60_000L

    @Test
    fun `isolated latest spike does not move latent forecast level`() {
        val points = (0..10).map { i ->
            HybridGlucosePoint(i * minute, if (i == 10) 10.0 else 7.0)
        }

        val state = robustGlucoseState(points, 10 * minute)!!

        assertEquals(7.0, state.levelMmol, 1e-9)
        assertEquals(0.0, state.slopeMmolPerMin, 1e-9)
        assertEquals(3.0, state.latestResidualMmol, 1e-9)
    }

    @Test
    fun `sustained trend is projected to now without averaging lag`() {
        val points = (0..10).map { i ->
            HybridGlucosePoint(i * minute, 6.0 + i * 0.1)
        }

        val state = robustGlucoseState(points, 10 * minute)!!

        assertEquals(7.0, state.levelMmol, 1e-9)
        assertEquals(0.1, state.slopeMmolPerMin, 1e-9)
        assertEquals(0.0, state.latestResidualMmol, 1e-9)
    }

    @Test
    fun `alternating minute noise stays centered`() {
        val points = (0..14).map { i ->
            HybridGlucosePoint(i * minute, 8.0 + if (i % 2 == 0) 1.8 else -1.8)
        }

        val state = robustGlucoseState(points, 14 * minute)!!

        assertTrue(state.levelMmol in 7.5..8.5)
    }

    @Test
    fun `short history honestly falls back to latest fact`() {
        val points = listOf(
            HybridGlucosePoint(0, 6.0),
            HybridGlucosePoint(minute, 6.4),
            HybridGlucosePoint(2 * minute, 6.8),
        )

        val state = robustGlucoseState(points, 2 * minute)!!

        assertEquals(6.8, state.levelMmol, 1e-9)
        assertEquals(0.0, state.latestResidualMmol, 1e-9)
    }

    @Test
    fun `slope bridge matches first segment and fully releases long target`() {
        val first = 7.1 + slopeBridgeDelta(5.0, 30.0, 5.0, 7.0, 7.1, 0.10)
        assertEquals(7.5, first, 1e-9)
        assertEquals(0.0, slopeBridgeDelta(30.0, 30.0, 5.0, 7.0, 7.1, 0.10), 1e-9)
        assertEquals(0.0, slopeBridgeDelta(60.0, 30.0, 5.0, 7.0, 7.1, 0.10), 1e-9)
    }
}
