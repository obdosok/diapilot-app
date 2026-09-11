package com.diapilot.core.api

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodEraTest {
    @Test fun `era start is local midnight of the start date in its own zone`() {
        val utc = FoodEra(LocalDate.of(2025, 1, 6), ZoneOffset.UTC)
        assertEquals(1_736_121_600_000L, utc.startMs) // 2025-01-06T00:00Z

        // Same calendar day, a zone east of UTC: the boundary is earlier in UTC.
        val tokyo = FoodEra(LocalDate.of(2025, 1, 6), ZoneId.of("Asia/Tokyo"))
        assertEquals(utc.startMs - 9 * 3_600_000L, tokyo.startMs)
    }

    @Test fun `learning ranges cannot begin before the era`() {
        val era = FoodEra(LocalDate.of(2025, 1, 6), ZoneOffset.UTC)
        assertEquals(era.startMs, era.clampFrom(0))
        assertEquals(era.startMs + 1, era.clampFrom(era.startMs + 1))
        assertTrue(era.contains(era.startMs))
        assertFalse(era.contains(era.startMs - 1))
    }

    @Test fun `the era follows its input, there is no built-in date`() {
        val a = FoodEra(LocalDate.of(2025, 1, 6), ZoneOffset.UTC)
        val b = FoodEra(LocalDate.of(2025, 3, 1), ZoneOffset.UTC)
        assertTrue(b.startMs > a.startMs)
        assertEquals(b.startMs, b.clampFrom(a.startMs))
    }

    @Test fun `an era can start on the day of any instant`() {
        val zone = ZoneId.of("America/New_York")
        // Three hours past UTC midnight is still the previous local day in New York.
        val era = FoodEra.startingOnDayOf(1_736_218_800_000L, zone)
        assertEquals(LocalDate.of(2025, 1, 6), era.startDate)
        assertEquals(zone, era.zone)
    }

    @Test fun `a stored era round-trips and malformed input is rejected`() {
        val era = FoodEra.parse("2025-01-06", "UTC")
        assertEquals(FoodEra(LocalDate.of(2025, 1, 6), ZoneId.of("UTC")), era)
        assertThrows(Exception::class.java) { FoodEra.parse("06.01.2025", "UTC") }
        assertThrows(Exception::class.java) { FoodEra.parse("2025-01-06", "Not/AZone") }
    }
}
