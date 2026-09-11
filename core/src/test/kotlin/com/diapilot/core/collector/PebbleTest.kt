package com.diapilot.core.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PebbleTest {

    @Test
    fun `parse a real-shaped pebble bg from xdrip`() {
        // Real shape: sgv is a string in mg/dL, iob uses a comma decimal separator.
        val bg = mapOf(
            "sgv" to "257", "trend" to 5, "direction" to "FortyFiveDown",
            "datetime" to 1_737_670_429_681L, "filtered" to 266470, "unfiltered" to 266470,
            "noise" to 1, "bgdelta" to -9, "battery" to "", "iob" to "5,20",
        )
        val now = parsePebbleBg(bg)
        assertNotNull(now.reading)
        assertEquals(257.0, now.reading!!.mgdl, 1e-9)
        assertEquals(257.0 / 18.0182, now.reading!!.mmol, 1e-6)
        assertEquals(1_737_670_429_681L, now.reading!!.tsMs)
        assertEquals("FortyFiveDown", now.reading!!.trend)
        assertEquals("xdrip_pebble", now.reading!!.source)
        assertEquals(5.20, now.iobUnits!!, 1e-9)
    }

    @Test
    fun `parse pebble in mmol display units`() {
        val bg = mapOf("sgv" to "14,3", "datetime" to 1_737_670_429_681L, "iob" to "0")
        val now = parsePebbleBg(bg)
        assertEquals(14.3, now.reading!!.mmol, 1e-9)
        assertEquals(14.3 * 18.0182, now.reading!!.mgdl, 1e-6)
        assertEquals(0.0, now.iobUnits!!, 1e-9)
    }

    @Test
    fun `garbled pebble yields nulls`() {
        assertNull(parsePebbleBg(null).reading)
        assertNull(parsePebbleBg(emptyMap()).reading)
        assertNull(parsePebbleBg(mapOf("sgv" to "abc", "datetime" to 1L)).reading)
        assertNull(parsePebbleBg(mapOf("sgv" to "120")).reading) // no timestamp
        assertNull(parsePebbleBg(mapOf("sgv" to "120", "datetime" to 1L, "iob" to "x")).iobUnits)
    }
}
