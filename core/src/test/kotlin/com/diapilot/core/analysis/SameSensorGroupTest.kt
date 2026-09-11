package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transports that read ONE physical sensor must not lose to themselves.
 *
 * `SensorPreference` used to hold this, keeping one series PER DAY. It was
 * deleted because per-day selection was already ruled
 * insufficient, and because the two rules disagreed on a meaningful share of
 * food-era days — the deconvolution corpus and every ISF measurement were reading
 * different glucose two days in five. What survives is the physical fact it
 * carried: `libre_nfc` is the same sensor scanned by phone, not a rival series.
 */
class SameSensorGroupTest {

    private fun p(t: Long, mmol: Double, src: String) = GlucosePoint(t, mmol) to src

    @Test
    fun `a split transport does not lose to a rival feed`() {
        // 6 readings of one sensor, arriving on two transports, against 5 of a
        // rival. Counted apart the rival wins; counted as one sensor it loses.
        val tagged = buildList {
            repeat(4) { add(p(it * 60_000L, 6.0, "libre_ble")) }
            repeat(2) { add(p(100_000L + it * 60_000L, 6.1, "libre_nfc")) }
            repeat(5) { add(p(200_000L + it * 60_000L, 7.4, "xdrip_sgv")) }
        }
        val chosen = MeasurementStreamCore.chooseFrom(tagged)
        assertEquals(
            "the same sensor lost to a rival because its transports were counted apart",
            6, chosen.readings.size,
        )
        assertTrue("the group is not named as one feed", chosen.source!!.contains("libre_ble"))
    }

    @Test
    fun `an unknown feed set falls back to plain per-source counting`() {
        val tagged = buildList {
            repeat(5) { add(p(it * 60_000L, 6.0, "dexcom_g7")) }
            repeat(3) { add(p(100_000L + it * 60_000L, 7.0, "some_other")) }
        }
        val chosen = MeasurementStreamCore.chooseFrom(tagged)
        assertEquals("dexcom_g7", chosen.source)
        assertEquals(5, chosen.readings.size)
    }

    @Test
    fun `the choice is made once over the span, never per day`() {
        // Day 1 belongs to the rival, days 2-3 to the primary. A per-day rule
        // would keep the rival's day; the era-wide rule drops it, because that
        // day is written in another calibration.
        val day = 86_400_000L
        val tagged = buildList {
            repeat(6) { add(p(it * 60_000L, 7.5, "xdrip_sgv")) }
            repeat(20) { add(p(day + it * 60_000L, 6.0, "libre_ble")) }
            repeat(20) { add(p(2 * day + it * 60_000L, 6.2, "libre_ble")) }
        }
        val chosen = MeasurementStreamCore.chooseFrom(tagged)
        assertEquals("the rival's day survived — this is per-day selection", 40, chosen.readings.size)
    }
}
