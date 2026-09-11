package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = 1_748_768_400_000L
private const val HOUR = 3_600_000L

class InsulinKernelTest {

    /** Linear fall from startBg to endBg over 3h, then flat; 5-min cadence, 4h span. */
    private fun fallCurve(t0: Long, startBg: Double, endBg: Double): List<GlucosePoint> =
        (0..48).map { i ->
            val m = i * 5
            val bg = when {
                m >= 180 -> endBg
                else -> startBg + (endBg - startBg) * m / 180.0
            }
            GlucosePoint(t0 + m * 60_000L, bg)
        }

    private fun episode(t0: Long, dose: Double, startBg: Double, endBg: Double) =
        IsfEpisode(t0, dose, startBg, endBg, (startBg - endBg) / dose, false, TodBucket.DAY)

    @Test
    fun `kernel plateau equals minus isf on synthetic data`() {
        // 6 identical episodes, ISF = 3/1.5 = 2.0 -> plateau at -2.0 per unit.
        val episodes = (0 until 6).map { i ->
            episode(BASE + i * 24 * HOUR, 1.5, 10.0, 7.0)
        }
        val readings = episodes.flatMap { fallCurve(it.t0Ms, 10.0, 7.0) }
        val kernel = insulinKernel(readings, episodes)

        assertTrue(kernel.isNotEmpty())
        assertEquals(0.0, kernel.first().median, 1e-9)          // τ=0: no effect yet
        assertEquals(-2.0, kernelAt(kernel, 240.0)!!, 0.05)     // plateau = -ISF
        assertEquals(-1.0, kernelAt(kernel, 90.0)!!, 0.05)      // halfway of linear fall
        // Monotone non-increasing until the plateau.
        kernel.zipWithNext().forEach { (a, b) -> assertTrue(b.median <= a.median + 1e-9) }
        assertEquals(6, kernel.first().n)
    }

    @Test
    fun `bins with too few episodes are dropped`() {
        val episodes = listOf(episode(BASE, 1.0, 10.0, 8.0))
        val readings = fallCurve(BASE, 10.0, 8.0)
        assertTrue(insulinKernel(readings, episodes, minEpisodes = 5).isEmpty())
        assertTrue(insulinKernel(readings, episodes, minEpisodes = 1).isNotEmpty())
    }

    @Test
    fun `empty episodes yield empty kernel`() {
        assertTrue(insulinKernel(fallCurve(BASE, 10.0, 7.0), emptyList()).isEmpty())
    }
}
