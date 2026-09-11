package com.diapilot.core.analysis

import com.diapilot.core.collector.HrPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTest {
    private val MIN = 60_000L

    // A day at rest (62 bpm) with a 40-minute walk (95 bpm) at hour 12.
    private fun day(): List<HrPoint> = buildList {
        for (m in 0 until 24 * 60 step 5) {
            val walking = m in (12 * 60) until (12 * 60 + 40)
            add(HrPoint(m * MIN, if (walking) 95.0 else 62.0))
        }
    }

    @Test
    fun baselineIsRestingMedian() {
        assertEquals(62.0, hrBaseline(day())!!, 1e-9)
        assertNull(hrBaseline(day().take(10)))  // too sparse to trust
    }

    @Test
    fun walkIsDetectedRestIsNot() {
        val hr = day()
        val base = hrBaseline(hr)!!
        // 40-min walk sampled every 5 min = 8 elevated points; lower the
        // sample floor accordingly for the sparse stream.
        assertTrue(hasActivity(hr, 12 * 60 * MIN, (12 * 60 + 60) * MIN, base, minMinutes = 6))
        assertFalse(hasActivity(hr, 6 * 60 * MIN, 8 * 60 * MIN, base, minMinutes = 6))
    }

    @Test
    fun activeEpisodesAreExcludedFromCalibration() {
        val kernel = listOf(KernelPoint(0.0, -1.0, -1.2, -0.8, 10))
        fun ep(active: Boolean) = CarbEpisode(
            onsetMs = 0L, rise = 3.0, timeToPeakMin = 60.0,
            bolusUnits = 2.0, estCarbs = 50.0, active = active,
        )
        // Three quiet meals calibrate; three active ones don't.
        assertEquals(3, carbSensitivity(List(3) { ep(false) }, kernel)!!.n)
        assertNull(carbSensitivity(List(3) { ep(true) }, kernel))
    }

    @Test
    fun hypoRescueEpisodesAreExcludedFromCalibration() {
        val kernel = listOf(KernelPoint(0.0, -1.0, -1.2, -0.8, 10))
        // Dextrose at BG 3.2: counter-regulation makes the rise unusable.
        val rescue = CarbEpisode(
            onsetMs = 0L, rise = 3.0, timeToPeakMin = 40.0,
            bolusUnits = null, estCarbs = 16.0, rescue = true,
        )
        assertNull(carbSensitivity(List(3) { rescue }, kernel))
    }

    @Test
    fun rescueFlagComesFromLowPreBg() {
        val min = 60_000L
        fun meal(preBg: Double) = com.diapilot.core.collector.MealEvent(
            onsetMs = 0L, peakMs = 60 * min, preBg = preBg, peakBg = preBg + 3.0,
            rise = 3.0, timeToPeakMin = 60.0, bolusUnits = null,
            kind = com.diapilot.core.collector.MealEvent.Kind.UNANNOUNCED,
        )
        val eps = carbEpisodes(listOf(meal(3.4)), emptyMap(), emptyList())
        assertTrue(eps[0].rescue)
        assertFalse(carbEpisodes(listOf(meal(6.0)), emptyMap(), emptyList())[0].rescue)
    }
}
