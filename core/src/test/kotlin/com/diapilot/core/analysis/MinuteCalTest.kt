package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinuteCalTest {

    /** Minute stream every minute; main every 5 min on y = 1.1x + 0.5. */
    private fun streams(hours: Int, noiseAt: Int = -1): Pair<List<GlucosePoint>, List<GlucosePoint>> {
        val minute = (0 until hours * 60).map { m ->
            GlucosePoint(m * 60_000L, 5.0 + 3.0 * kotlin.math.sin(m / 40.0))
        }
        val main = minute.filterIndexed { i, _ -> i % 5 == 0 }.mapIndexed { i, p ->
            val y = 1.1 * p.mmol + 0.5 + if (i == noiseAt) 8.0 else 0.0
            GlucosePoint(p.tsMs, y)
        }
        return main to minute
    }

    @Test
    fun recoversTheLinearMap() {
        val (main, minute) = streams(6)
        val cal = fitMinuteCalibration(main, minute)
        assertNotNull(cal)
        assertEquals(1.1, cal!!.slope, 0.01)
        assertEquals(0.5, cal.intercept, 0.05)
        assertTrue(cal.madMmol < 0.05)
        // Applying the fit puts a raw point onto the calibrated scale.
        assertEquals(1.1 * 6.0 + 0.5, cal.apply(6.0), 0.05)
    }

    @Test
    fun outlierIsTrimmedNotAbsorbed() {
        val (main, minute) = streams(6, noiseAt = 10)
        val cal = fitMinuteCalibration(main, minute)
        assertNotNull(cal)
        assertEquals(1.1, cal!!.slope, 0.02)
    }

    @Test
    fun refusesThinOrBrokenData() {
        val (main, minute) = streams(1)  // only ~12 pairs — below minPairs
        assertNull(fitMinuteCalibration(main, minute))
        // Unrelated streams: fit exists but explains nothing — rejected.
        val junkMain = (0 until 360).step(5).map {
            GlucosePoint(it * 60_000L, if (it % 2 == 0) 4.0 else 14.0)
        }
        val (_, minute6) = streams(6)
        assertNull(fitMinuteCalibration(junkMain, minute6))
    }

    @Test
    fun calibrateStreamMapsEveryPoint() {
        val (main, minute) = streams(6)
        val cal = fitMinuteCalibration(main, minute)!!
        val out = calibrateMinuteStream(minute, cal)
        assertEquals(minute.size, out.size)
        assertEquals(1.1 * minute[7].mmol + 0.5, out[7].mmol, 0.05)
    }
}
