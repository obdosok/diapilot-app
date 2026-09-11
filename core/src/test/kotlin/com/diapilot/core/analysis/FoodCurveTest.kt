package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodCurveTest {

    private fun curve(vararg pts: Pair<Double, Double>, n: Int = 5) = DishCurve(
        points = pts.map { DishCurvePoint(it.first, it.second, n) },
        nEpisodes = n,
        effectiveGi = null,
        hasLateTail = true,
    )

    @Test
    fun tailIsTheLateExcessOverTheEarlyPeak() {
        // External review's example: 30m +1, 60m +3, 120m +4, 240m +5, 300m +6
        // → fast rise 4 (early peak at 120), tail rise 2 (late max 6 − 4).
        val tp = dishTwoPhase(
            curve(30.0 to 1.0, 60.0 to 3.0, 120.0 to 4.0, 240.0 to 5.0, 300.0 to 6.0),
        )!!
        assertEquals(4.0, tp.rise, 1e-9)
        assertEquals(120.0, tp.ttpMin, 1e-9)
        assertEquals(2.0, tp.tailRise, 1e-9)
        assertEquals(120.0, tp.tailStartMin, 1e-9)
        assertEquals(300.0, tp.tailTtpMin, 1e-9)
    }

    @Test
    fun fastDishHasNoTail() {
        // Smoothie: peaks early, declines late — tail must be zero.
        val tp = dishTwoPhase(
            curve(30.0 to 2.0, 60.0 to 3.5, 120.0 to 2.0, 240.0 to 0.5, 300.0 to 0.2),
        )!!
        assertEquals(3.5, tp.rise, 1e-9)
        assertEquals(0.0, tp.tailRise, 1e-9)
    }

    @Test
    fun thinCurveNeverClaimsATail() {
        // Same pizza shape but only 3 repeats: a tail is a strong claim —
        // it must not feed the forecast below 5 episodes.
        val tp = dishTwoPhase(
            curve(60.0 to 3.0, 120.0 to 4.0, 300.0 to 6.0, n = 3),
        )!!
        assertEquals(4.0, tp.rise, 1e-9)
        assertEquals(0.0, tp.tailRise, 1e-9)
    }

    @Test
    fun lateOnlyCurveIsSinglePhase() {
        val tp = dishTwoPhase(curve(240.0 to 3.0, 300.0 to 4.0))!!
        assertEquals(4.0, tp.rise, 1e-9)
        assertEquals(0.0, tp.tailRise, 1e-9)
    }

    @Test
    fun contaminatedEpisodesAreDropped() {
        // Three onsets of the dish, but two have ANOTHER meal inside their
        // 5-hour window → only one clean episode → below minEpisodes → null.
        val h = 3_600_000L
        val onsets = listOf(0L, 24 * h, 48 * h)
        val others = listOf(2 * h, 24 * h + 2 * h)   // desserts after two of them
        val readings = (0..600).map {
            com.diapilot.core.collector.GlucosePoint(it * 5L * 60_000, 6.0)
        }
        val flat = (0..48).map { KernelPoint(it * 5.0, 0.0, 0.0, 0.0, 10) }
        assertNull(
            dishResponseCurve(
                onsets, readings, emptyList(), flat, nowMs = 50 * h,
                allOnsetsMs = onsets + others,
            ),
        )
        // Without the contamination info the same call builds a curve.
        assertTrue(
            dishResponseCurve(onsets, readings, emptyList(), flat, nowMs = 50 * h) != null,
        )
    }

    @Test
    fun knownRescueOrActivityWindowDropsEpisode() {
        val h = 3_600_000L
        val onsets = listOf(0L, 24 * h, 48 * h)
        val readings = (0..600).map { com.diapilot.core.collector.GlucosePoint(it * 5L * 60_000, 6.0) }
        val flat = (0..60).map { KernelPoint(it * 5.0, 0.0, 0.0, 0.0, 10) }
        val dirty = onsets.take(2).map { FoodContaminationWindow(it + h, it + 2 * h) }
        assertNull(dishResponseCurve(onsets, readings, emptyList(), flat, 50 * h, contaminationWindows = dirty))
    }
}
