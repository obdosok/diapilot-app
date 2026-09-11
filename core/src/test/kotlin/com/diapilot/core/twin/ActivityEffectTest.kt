package com.diapilot.core.twin

import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.analysis.sustainedHrWindows
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.HrPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityEffectTest {
    private val min = 60_000L
    private val flatKernel = listOf(KernelPoint(0.0, 0.0, 0.0, 0.0, 9))

    @Test
    fun windowsGroupAndFilter() {
        val hr = buildList {
            // 30 elevated minutes starting at t=60min.
            for (m in 60 until 90) add(HrPoint(m * min, 110.0))
            // Noise spike (5 min) far later — dropped.
            for (m in 300 until 305) add(HrPoint(m * min, 115.0))
            // Rest samples.
            for (m in 0 until 60 step 5) add(HrPoint(m * min, 62.0))
        }
        val windows = sustainedHrWindows(hr, baseline = 62.0)
        assertEquals(1, windows.size)
        assertEquals(60 * min, windows[0].startMs)
        assertEquals(30, windows[0].elevatedMin)
    }

    @Test
    fun learnsTheDropFromCleanBouts() {
        // Five identical bouts: BG falls 1.5 mmol over 30 elevated minutes
        // (+45 tail) with no insulin and no food → 0.05 mmol per minute.
        val readings = mutableListOf<GlucosePoint>()
        val windows = mutableListOf<com.diapilot.core.analysis.ActivityWindow>()
        for (day in 0 until 5) {
            val base = day * 24L * 60 * min
            for (m in 0 until 300 step 5) {
                val t = base + m * min
                val startDrop = 60L
                val v = when {
                    m < startDrop -> 8.0
                    m < startDrop + 75 -> 8.0 - 1.5 * (m - startDrop) / 75.0
                    else -> 6.5
                }
                readings.add(GlucosePoint(t, v))
            }
            windows.add(
                com.diapilot.core.analysis.ActivityWindow(
                    base + 60 * min, base + 90 * min, 30,
                ),
            )
        }
        val k = learnActivityDropPerMin(
            readings, emptyList(), flatKernel, windows, foodOnsetsMs = emptyList(),
        )
        assertNotNull(k)
        assertEquals(0.05, k!!, 0.01)
    }

    @Test
    fun contaminatedAndThinDataRefused() {
        val readings = (0 until 200).map { GlucosePoint(it * 5 * min, 7.0) }
        val w = com.diapilot.core.analysis.ActivityWindow(60 * min, 90 * min, 30)
        // Food near the bout → excluded → too few windows → null.
        assertEquals(
            null,
            learnActivityDropPerMin(
                readings, emptyList(), flatKernel, listOf(w),
                foodOnsetsMs = listOf(70 * min),
            ),
        )
        // Exercise foods from zero coefficient — empty.
        assertTrue(exerciseFoods(listOf(w), 0.0).isEmpty())
        // Negative food shape: drop capped and realized over bout+tail.
        val foods = exerciseFoods(listOf(w), 0.05)
        assertEquals(-1.5, foods[0].rise, 1e-9)
        assertEquals(75.0, foods[0].timeToPeakMin, 1e-9)
    }

    // ── Activity model v2 ────────────────────────────────────────────────────

    @Test
    fun v2SetsOnsetLagAndGatedFlagV1DoesNot() {
        val w = com.diapilot.core.analysis.ActivityWindow(60 * min, 90 * min, 30)
        val v1 = exerciseFoods(listOf(w), 0.05)[0]
        assertEquals(0.0, v1.onsetLagMin, 1e-9)
        assertTrue(!v1.activityGated)
        val v2 = exerciseFoods(listOf(w), 0.05, v2 = true)[0]
        assertEquals(ACTIVITY_ONSET_LAG_MIN, v2.onsetLagMin, 1e-9)
        assertTrue(v2.activityGated)
        // The MAGNITUDE is unchanged — v2 only reshapes WHEN and HOW MUCH by level.
        assertEquals(v1.rise, v2.rise, 1e-9)
    }

    @Test
    fun levelGateShape() {
        assertEquals(0.0, activityLevelGate(3.0), 1e-9)          // below floor
        assertEquals(0.0, activityLevelGate(ACTIVITY_BG_FLOOR), 1e-9)
        assertEquals(1.0, activityLevelGate(ACTIVITY_BG_FULL), 1e-9)
        assertEquals(1.0, activityLevelGate(12.0), 1e-9)         // above full
        assertEquals(0.5, activityLevelGate((ACTIVITY_BG_FLOOR + ACTIVITY_BG_FULL) / 2), 1e-9)
        // Monotone non-decreasing.
        var prev = -1.0
        var bg = 2.0
        while (bg <= 10.0) {
            val g = activityLevelGate(bg)
            assertTrue(g >= prev - 1e-12); prev = g; bg += 0.25
        }
    }

    @Test
    fun gatedFoodEqualsPlainWhenAlwaysAboveFull() {
        // BG stays above ACTIVITY_BG_FULL the whole horizon ⇒ gate ≡ 1 ⇒ the
        // incremental gated path telescopes to the exact from-anchor path.
        val anchor = 1_000_000L
        val gated = ActiveFood(anchor, rise = -1.5, timeToPeakMin = 75.0, activityGated = true)
        val plain = gated.copy(activityGated = false)
        val g = simulateForward(anchor, 10.0, emptyList(), flatKernel, horizonMin = 100.0, foods = listOf(gated))
        val p = simulateForward(anchor, 10.0, emptyList(), flatKernel, horizonMin = 100.0, foods = listOf(plain))
        for (i in g.indices) assertEquals(p[i].mmol, g[i].mmol, 1e-9)
        assertTrue(g.last().mmol > ACTIVITY_BG_FULL)   // guard: really stayed above full
    }

    @Test
    fun gatedFoodIsDampedNearTheFloor() {
        // Start at 5.0 (gate ≈ 0.32): the gated drop must be SMALLER than the
        // ungated one, and must not punch the line through the floor.
        val anchor = 1_000_000L
        val gated = ActiveFood(anchor, rise = -2.5, timeToPeakMin = 75.0, activityGated = true)
        val plain = gated.copy(activityGated = false)
        val g = simulateForward(anchor, 5.0, emptyList(), flatKernel, horizonMin = 100.0, foods = listOf(gated))
        val p = simulateForward(anchor, 5.0, emptyList(), flatKernel, horizonMin = 100.0, foods = listOf(plain))
        assertTrue("gated ends higher than plain", g.last().mmol > p.last().mmol + 0.5)
        assertTrue("gated stays above floor", g.minOf { it.mmol } > ACTIVITY_BG_FLOOR - 0.2)
        assertTrue("plain crashes through floor", p.minOf { it.mmol } < ACTIVITY_BG_FLOOR)
    }
}
