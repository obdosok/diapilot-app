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
        val now = parsePebbleBg(bg, nowMs = NOW)
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
        val now = parsePebbleBg(bg, nowMs = NOW)
        assertEquals(14.3, now.reading!!.mmol, 1e-9)
        assertEquals(14.3 * 18.0182, now.reading!!.mgdl, 1e-6)
        assertEquals(0.0, now.iobUnits!!, 1e-9)
    }

    @Test
    fun `garbled pebble yields nulls`() {
        assertNull(parsePebbleBg(null, NOW).reading)
        assertNull(parsePebbleBg(emptyMap(), NOW).reading)
        assertNull(parsePebbleBg(mapOf("sgv" to "abc", "datetime" to NOW), NOW).reading)
        assertNull(parsePebbleBg(mapOf("sgv" to "120"), NOW).reading) // no timestamp
        assertNull(parsePebbleBg(mapOf("sgv" to "120", "datetime" to NOW, "iob" to "x"), NOW).iobUnits)
    }

    @Test
    fun `a pebble value outside the CGM range yields no reading but keeps the iob`() {
        fun at(sgv: String) = parsePebbleBg(mapOf("sgv" to sgv, "datetime" to NOW, "iob" to "1,5"), NOW)
        assertNotNull(at("36").reading)
        assertNotNull(at("600").reading)
        assertNull(at("601").reading)
        assertNull(at("-40").reading)
        // A string of 35 or less is display mmol/L by the parser's own rule,
        // so the low bound is only reachable that way: 1,0 mmol/L is 18 mg/dL
        // and 1,1 is 19.8, both below what a CGM reports; 1,2 (21.6) is not.
        assertNull(at("1,0").reading)
        assertNull(at("1,1").reading)
        assertNotNull(at("1,2").reading)
        assertNotNull(at("14,3").reading)
        // Insulin on board is a display hint, not a stored dose — it is not
        // the reading's hostage.
        assertEquals(1.5, at("601").iobUnits!!, 1e-9)
    }

    @Test
    fun `a pebble timestamp is bounded like the backfill, not like the broadcast`() {
        fun at(ts: Long) = parsePebbleBg(mapOf("sgv" to "120", "datetime" to ts), NOW).reading
        // xDrip's display value is whatever it last received: a stale one is
        // stale, not forged, so the past bound is the backfill depth.
        assertNotNull(at(NOW - 3L * 3_600_000))
        assertNotNull(at(NOW - BG_BACKFILL_MAX_AGE_MS))
        assertNull(at(NOW - BG_BACKFILL_MAX_AGE_MS - 1))
        assertNotNull(at(NOW + BG_BROADCAST_MAX_AHEAD_MS))
        assertNull(at(NOW + BG_BROADCAST_MAX_AHEAD_MS + 1))
        assertNull(at(0L))
        assertNull(at(1L))
    }

    private companion object {
        const val NOW = 1_737_670_429_681L
    }
}
