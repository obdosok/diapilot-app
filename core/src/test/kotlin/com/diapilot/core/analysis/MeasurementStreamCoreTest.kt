package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounds exist because a live device stored several libre_ble readings at
 * or below ZERO mmol and many more below 1.5, and nothing refused to measure on them.
 *
 * Each test below was checked by MUTATION — delete the guard it names and it
 * fails. A test that passes with and without the fix pins nothing, and this
 * repo has produced three of those in a row.
 */
class MeasurementStreamCoreTest {

    private fun p(ts: Long, mmol: Double) = GlucosePoint(ts, mmol) to "libre_ble"

    @Test
    fun `a negative reading is never measured on`() {
        val chosen = MeasurementStreamCore.chooseFrom(
            listOf(p(1, 5.5), p(2, -0.46), p(3, 6.0)),
        )
        assertTrue("negative glucose reached a measurement", chosen.readings.none { it.mmol < 0 })
        assertEquals(listOf(5.5, 6.0), chosen.readings.map { it.mmol })
        assertEquals(1, chosen.refusedImplausible)
    }

    @Test
    fun `the bounds are the fitted 1_5 and 28, inclusive`() {
        val chosen = MeasurementStreamCore.chooseFrom(
            listOf(p(1, 1.49), p(2, 1.5), p(3, 28.0), p(4, 28.01)),
        )
        assertEquals(listOf(1.5, 28.0), chosen.readings.map { it.mmol })
        assertEquals(2, chosen.refusedImplausible)
    }

    /**
     * The one that would silently break the measurement it is meant to protect:
     * if the primary feed were picked AFTER filtering, a failing sensor could
     * hand the record to a minority feed writing a different calibration — and
     * mixing calibrations is the error class that publishes a materially wrong ISF.
     */
    @Test
    fun `the primary feed is chosen on raw counts, not on cleanliness`() {
        val tagged = listOf(
            GlucosePoint(1, -0.4) to "libre_ble",
            GlucosePoint(2, -0.2) to "libre_ble",
            GlucosePoint(3, 5.0) to "libre_ble",
            GlucosePoint(4, 7.0) to "xdrip_sgv",
            GlucosePoint(5, 7.5) to "xdrip_sgv",
        )
        val chosen = MeasurementStreamCore.chooseFrom(tagged)
        // `source` names the GROUP ("libre_ble+libre_nfc"),
        // because transports reading one physical sensor are counted together.
        // The claim here is about counting on RAW values, so it asserts the
        // winner rather than the spelling.
        assertTrue(
            "a failing feed lost the record to another calibration: ${chosen.source}",
            chosen.source!!.contains("libre_ble"),
        )
        assertEquals(listOf(5.0), chosen.readings.map { it.mmol })
        assertEquals(2, chosen.refusedImplausible)
    }

    @Test
    fun `an all-implausible window is empty rather than quietly plausible`() {
        val chosen = MeasurementStreamCore.chooseFrom(listOf(p(1, -0.4), p(2, 0.0), p(3, 1.0)))
        assertTrue(chosen.isEmpty)
        assertEquals(3, chosen.refusedImplausible)
    }
}
