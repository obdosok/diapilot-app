package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterCalTest {
    private val h = 3_600_000L
    private val flatSensor = (0 until 24 * 12).map { GlucosePoint(it * 5L * 60_000, 8.0) }

    @Test
    fun singleCheckIsTransientOnly() {
        val checkTs = 12 * h
        val cal = meterCalibration(
            flatSensor, listOf(GlucosePoint(checkTs, 6.5)), nowMs = checkTs + 10 * 60_000,
        )
        assertNotNull(cal)
        assertEquals(1.0, cal!!.slope, 1e-9)
        assertEquals(0.0, cal.interceptMmol, 1e-9)
        assertEquals(0, cal.nChecks)
        assertEquals(6.5, cal.correctedAt(checkTs, 8.0), 1e-9)
        // Decays: one half-life later only half the correction remains.
        assertEquals(8.0 - 0.75, cal.correctedAt(checkTs + 6 * h, 8.0), 1e-9)
    }

    @Test
    fun repeatedChecksBecomeAStandingOffset() {
        // The sensor reads ~1.5 high all its life (user's Libre experience).
        val meter = listOf(
            GlucosePoint(6 * h, 6.5),
            GlucosePoint(12 * h, 6.4),
            GlucosePoint(18 * h, 6.6),
        )
        val cal = meterCalibration(flatSensor, meter, nowMs = 20 * h)!!
        assertEquals(3, cal.nChecks)
        assertEquals(-1.5, cal.interceptMmol, 0.1)
        // The standing part does NOT decay: two days later still corrected.
        assertEquals(6.5, cal.correctedAt(60 * h, 8.0), 0.2)
    }

    @Test
    fun slopeFitWhenChecksSpanARange() {
        // Sensor = meter * 1.25 (proportionally high): checks at 4 levels.
        val sensor = (0 until 24 * 12).map {
            val mmol = 5.0 + (it % 96) / 8.0   // 5..17 sweep
            GlucosePoint(it * 5L * 60_000, mmol * 1.25)
        }
        val meter = listOf(12, 30, 60, 90).map { idx ->
            val s = sensor[idx]
            GlucosePoint(s.tsMs + 60_000, s.mmol / 1.25)
        }
        val cal = meterCalibration(sensor, meter, nowMs = 24 * h)!!
        assertEquals(0.8, cal.slope, 0.03)
        assertTrue(kotlin.math.abs(cal.interceptMmol) < 0.5)
    }

    @Test
    fun checkTakenWhileMovingFastIsRejected() {
        // Sensor falls 0.1 mmol/min through i=130..140, flat 5.0 after — the
        // blood check leads the lagged ISF there, so the gap is lag not error.
        val ramp = (0 until 24 * 12).map { i ->
            val mmol = when {
                i < 130 -> 9.0
                i in 130..140 -> 9.0 - (i - 130) * 0.5
                else -> 5.0
            }
            GlucosePoint(i * 5L * 60_000, mmol)
        }
        val rampTs = 135 * 5L * 60_000
        val rampAt = ramp.first { it.tsMs == rampTs }.mmol       // 6.5
        // On the moving stretch: the only check is dropped → no calibration.
        assertNull(
            meterCalibration(ramp, listOf(GlucosePoint(rampTs, rampAt - 1.5)), rampTs + 60_000),
        )
        // The SAME offset on the flat tail IS accepted.
        val flatTs = 200 * 5L * 60_000
        val cal = meterCalibration(ramp, listOf(GlucosePoint(flatTs, 5.0 - 1.5)), flatTs + 60_000)
        assertNotNull(cal)
        assertTrue(cal!!.isActive(flatTs))
    }

    @Test
    fun denseRateSourceCatchesWhatSparseMainMisses() {
        val checkTs = 12 * h
        // Sparse main around the check (a gap) → its local rate is unknown.
        val sparseMain = listOf(
            GlucosePoint(checkTs - 18 * 60_000, 9.0),
            GlucosePoint(checkTs - 2 * 60_000, 9.0),
            GlucosePoint(checkTs + 40 * 60_000, 6.0),
        )
        // Dense 1-min stream falling 0.15 mmol/min through the check.
        val minute = (0..40).map { GlucosePoint(checkTs - 20 * 60_000 + it * 60_000, 12.0 - it * 0.15) }
        val check = listOf(GlucosePoint(checkTs, 7.0))
        // Default (sparse main): rate unknown → kept → calibrates.
        assertNotNull(meterCalibration(sparseMain, check, checkTs + 60_000))
        // Dense stream: fast fall seen → the only check dropped → no calibration.
        assertNull(meterCalibration(sparseMain, check, checkTs + 60_000, rateSource = minute))
    }

    @Test
    fun typoPairsAreDroppedAndAgreementIsQuiet() {
        val ts = 12 * h
        // 8.0 sensor vs 19.0 meter — dropped as implausible.
        assertNull(meterCalibration(flatSensor, listOf(GlucosePoint(ts, 19.0)), ts))
        // Sensor agrees within noise — nothing active.
        val cal = meterCalibration(flatSensor, listOf(GlucosePoint(ts, 8.05)), ts)
        assertTrue(cal == null || !cal.isActive(ts))
    }

    @Test
    fun calibrationNeverLeaksBeforeItsSensorEpoch() {
        val start = 10 * h
        val check = 12 * h
        val cal = meterCalibration(
            flatSensor,
            listOf(GlucosePoint(check, 6.5)),
            nowMs = check,
            validFromMs = start,
        )!!
        assertEquals(8.0, cal.correctedAt(start - 1, 8.0), 1e-9)
        assertEquals(6.5, cal.correctedAt(check, 8.0), 1e-9)
    }

    @Test
    fun robustSlopeIgnoresOneNoisyCheck() {
        val xs = listOf(5.0, 7.0, 9.0, 11.0, 13.0)
        val sensor = xs.mapIndexed { i, x -> GlucosePoint((i + 1) * h, x) }
        val meter = xs.mapIndexed { i, x ->
            // Four points follow y=.8x+1; one admissible but noisy point is off.
            val y = if (i == 2) 10.0 else 0.8 * x + 1.0
            GlucosePoint((i + 1) * h + 60_000, y)
        }
        val cal = meterCalibration(sensor, meter, nowMs = 7 * h)!!
        assertEquals(0.8, cal.slope, 0.08)
        assertEquals(1.0, cal.interceptMmol, 0.5)
    }
}
