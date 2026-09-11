package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IobTest {
    @Test
    fun freshBolusIsFullyOnBoard() {
        assertEquals(1.0, iobFraction(0.0), 1e-9)
        assertTrue(iobFraction(1.0) > 0.99)
    }

    @Test
    fun expiredBolusIsGone() {
        assertEquals(0.0, iobFraction(DIA_MIN), 1e-9)
        assertEquals(0.0, iobFraction(DIA_MIN + 60), 1e-9)
    }

    @Test
    fun fractionDecreasesMonotonically() {
        var prev = 1.0
        var t = 0.0
        while (t <= DIA_MIN) {
            val f = iobFraction(t)
            assertTrue("IOB fraction rose at t=$t", f <= prev + 1e-9)
            prev = f
            t += 5.0
        }
    }

    @Test
    fun roughlyHalfRemainsNearMidCurve() {
        // The exponential curve with peak 75/DIA 300 crosses 50% around ~2h.
        val f = iobFraction(120.0)
        assertTrue("expected ~half at 2h, got $f", f in 0.35..0.65)
    }

    @Test
    fun sumsDosesAndIgnoresFutureOnes() {
        val now = 10_000_000_000L
        val boluses = listOf(
            BolusPoint(now - 30 * 60_000, 4.0),          // recent — mostly on board
            BolusPoint(now - 6 * 3_600_000, 10.0),       // expired
            BolusPoint(now + 3_600_000, 99.0),           // future (clock skew) — ignored
        )
        val iob = iobUnits(boluses, now)
        assertTrue("expected ~3.5u, got $iob", iob in 3.0..4.0)
    }
}
