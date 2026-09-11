package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = 1_748_768_400_000L
private const val MIN = 60_000L

/** Minute series ending at BASE+10min with the given per-minute slope. */
private fun series(startMmol: Double, slope: Double): List<GlucosePoint> =
    (0..10).map { i -> GlucosePoint(BASE + i * MIN, startMmol + slope * i) }

class RapidFallTest {

    private val now = BASE + 10 * MIN

    @Test
    fun `steep fall toward hypo fires urgent`() {
        // 6.5 falling 0.2/min -> current 4.5, projected 20min = 0.5.
        val fall = detectRapidFall(series(6.5, -0.2), calibratedMmol = null, nowMs = now)
        assertNotNull(fall)
        assertTrue(fall!!.urgent)
        assertEquals(-0.2, fall.slopePerMin, 0.01)
        assertTrue(fall.projected20Mmol < 3.9)
    }

    @Test
    fun `fast fall from high ground is silent while projection stays safe`() {
        // 14 -> 12 over 10 min: steep, but 20-min projection ~9.2 — no alert.
        assertNull(detectRapidFall(series(14.0, -0.2), null, now))
    }

    @Test
    fun `slow drift is silent even when low`() {
        assertNull(detectRapidFall(series(5.0, -0.05), null, now))
    }

    @Test
    fun `calibrated level overrides raw for the absolute value`() {
        val fall = detectRapidFall(series(6.5, -0.2), calibratedMmol = 5.5, nowMs = now)
        assertNotNull(fall)
        assertEquals(5.5, fall!!.currentMmol, 1e-9)
        assertEquals(5.5 - 4.0, fall.projected20Mmol, 0.01)
    }

    @Test
    fun `stale or thin data is silent`() {
        assertNull(detectRapidFall(series(6.5, -0.2), null, nowMs = now + 10 * MIN))
        assertNull(detectRapidFall(series(6.5, -0.2).take(3), null, now))
    }
}
