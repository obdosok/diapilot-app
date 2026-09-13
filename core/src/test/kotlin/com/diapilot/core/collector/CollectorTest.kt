/** Contract tests for the stage-1 collection core — mirror of python_core/tests/test_collector.py. */
package com.diapilot.core.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = 1_748_768_400_000L // ms

class BroadcastParserTest {

    @Test
    fun `parse bg broadcast ok`() {
        val r = parseBgBroadcast(
            mapOf(EXTRA_BG to 180.0, EXTRA_TIME to BASE, EXTRA_SLOPE_NAME to "Flat"),
            nowMs = BASE,
        )
        assertNotNull(r)
        r!!
        assertEquals(180.0, r.mgdl, 1e-9)
        assertEquals(180.0 / 18.0182, r.mmol, 1e-6)
        assertEquals("Flat", r.trend)
        assertEquals(BASE, r.tsMs)
    }

    @Test
    fun `parse bg broadcast garbled returns null`() {
        assertNull(parseBgBroadcast(emptyMap(), BASE))
        assertNull(parseBgBroadcast(null, BASE))
        assertNull(parseBgBroadcast(mapOf(EXTRA_BG to 0, EXTRA_TIME to BASE), BASE))
        assertNull(parseBgBroadcast(mapOf(EXTRA_BG to "abc", EXTRA_TIME to BASE), BASE))
        assertNull(parseBgBroadcast(mapOf(EXTRA_BG to 120, EXTRA_TIME to null), BASE))
    }

    @Test
    fun `a broadcast value outside the CGM range is refused`() {
        fun at(mgdl: Any) = parseBgBroadcast(mapOf(EXTRA_BG to mgdl, EXTRA_TIME to BASE), BASE)
        // The bounds themselves are accepted; anything past them is not.
        assertNotNull(at(20.0))
        assertNotNull(at(600.0))
        assertNull(at(19.9))
        assertNull(at(600.1))
        assertNull(at(-100.0))
        assertNull(at(Double.NaN))
        assertNull(at(Double.POSITIVE_INFINITY))
        // A forged "8.0 now" that would become the forecast anchor: the value
        // is in range, so the range alone does not catch it — the window and
        // the sender check do their own halves of the job.
        assertNotNull(at(144.0))
    }

    @Test
    fun `a broadcast timestamp outside the window around now is refused`() {
        fun at(tsMs: Long) = parseBgBroadcast(mapOf(EXTRA_BG to 120, EXTRA_TIME to tsMs), BASE)
        assertNotNull(at(BASE))
        assertNotNull(at(BASE - BG_BROADCAST_MAX_AGE_MS))
        assertNotNull(at(BASE + BG_BROADCAST_MAX_AHEAD_MS))
        assertNull(at(BASE - BG_BROADCAST_MAX_AGE_MS - 1))
        assertNull(at(BASE + BG_BROADCAST_MAX_AHEAD_MS + 1))
        // Rewriting last week, and parking a value in next year.
        assertNull(at(BASE - 7L * 24 * 3_600_000))
        assertNull(at(BASE + 365L * 24 * 3_600_000))
        assertNull(at(0L))
        assertNull(at(-BASE))
    }

    @Test
    fun `parse treatments filters and normalizes`() {
        val items = listOf(
            mapOf("insulin" to 3.0, "timestamp" to BASE, "insulinType" to "Fiasp"),
            mapOf("carbs" to 30, "timestamp" to BASE + 1000),   // no insulin -> skipped
            mapOf("insulin" to 0, "timestamp" to BASE + 2000),  // zero -> skipped
            mapOf("insulin" to 5.0, "mills" to BASE + 3000),    // alt timestamp field
        )
        val evs = parseTreatments(items)
        assertEquals(2, evs.size)
        assertEquals(3.0, evs[0].units, 1e-9)
        assertEquals("Fiasp", evs[0].insulinType)
        assertEquals(BASE + 3000, evs[1].tsMs)
    }

    @Test
    fun `parse treatments accepts real xdrip nfc pen shape`() {
        // The shape an xDrip build writes for an NFC pen scan: numeric created_at,
        // priming shots with insulin=0 must be skipped. The pen serial is a placeholder.
        val items = listOf(
            mapOf(
                "_id" to "PEN0001:Open1002:5.0", "created_at" to 1_736_285_533_782L,
                "eventType" to "<none>", "enteredBy" to "xDrip NFC scan @ 2000-01-01 00:00:00",
                "notes" to "PEN PEN0001\nNovo Nordisk A/S NovoPen", "carbs" to 0, "insulin" to 5,
            ),
            mapOf(
                "_id" to "PEN0001:Open1001:0.5", "created_at" to 1_736_285_499_782L,
                "notes" to "Priming 0.5U\nPEN PEN0001", "carbs" to 0, "insulin" to 0,
            ),
        )
        val evs = parseTreatments(items)
        assertEquals(1, evs.size)                    // priming (0 units) skipped
        assertEquals(5.0, evs[0].units, 1e-9)
        assertEquals(1_736_285_533_782L, evs[0].tsMs)
    }

    @Test
    fun `parse treatments accepts iso created_at`() {
        val items = listOf(
            mapOf("insulin" to 2.5, "created_at" to "2025-01-07T10:15:00.000Z"),
            mapOf("insulin" to 1.0, "created_at" to "2025-01-07T13:15:00+03:00"),
            mapOf("insulin" to 1.5, "created_at" to "2025-01-07T13:15:00+0300"), // compact offset
            mapOf("insulin" to 4.0, "created_at" to "not-a-date"),               // skipped
            mapOf("insulin" to 3.0, "timestamp" to BASE, "created_at" to "2000-01-01T00:00:00Z"), // numeric wins
        )
        val evs = parseTreatments(items)
        assertEquals(4, evs.size)
        assertEquals(1_736_244_900_000L, evs[0].tsMs) // 2025-01-07T10:15Z
        assertEquals(evs[1].tsMs, evs[2].tsMs)        // +03:00 == +0300
        assertEquals(BASE, evs[3].tsMs)
    }

    @Test
    fun `parse oop2 trend verbatim payload`() {
        // The shape of an OOP2 broadcast. FIRST element is the newest (matches
        // the OOP2 screen); timestamps quantized to the minute so jittering
        // broadcast times dedup into the same slot. Buffer and UID are placeholders.
        val fields = mapOf(
            "ROW_ID" to 10335,
            "DecodedBuffer" to "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "PatchUid" to "AAAAAAAAAAA=",
            "TrendBg" to listOf(215, 208, 201, 196, 189, 181, 173),
            "HistoricBg" to listOf(154, 163, 177),
            "com.eveningoutpost.dexdrip.Extras.TIMESTAMP" to 1_737_713_152_636L,
        )
        val rs = parseOop2Trend(fields, nowMs = 1_737_713_152_636L)
        assertEquals(7, rs.size)
        val quantized = Math.round(1_737_713_152_636L / 60_000.0) * 60_000L
        // First element (215) is newest — lands on the quantized broadcast minute.
        assertEquals(quantized, rs.first().tsMs)
        assertEquals(215.0, rs.first().mgdl, 1e-9)
        // Last element is 6 minutes older.
        assertEquals(quantized - 6 * 60_000, rs.last().tsMs)
        assertEquals(173.0, rs.last().mgdl, 1e-9)
        assertEquals("oop2_ble", rs.first().source)
        // Quantization: a broadcast 4 seconds later maps to the same slots.
        val again = parseOop2Trend(
            fields + ("com.eveningoutpost.dexdrip.Extras.TIMESTAMP" to 1_737_713_156_700L),
            nowMs = 1_737_713_156_700L,
        )
        assertEquals(rs.first().tsMs, again.first().tsMs)
    }

    @Test
    fun `parse oop2 trend garbled yields empty`() {
        assertEquals(0, parseOop2Trend(null, BASE).size)
        assertEquals(0, parseOop2Trend(emptyMap(), BASE).size)
        assertEquals(0, parseOop2Trend(mapOf("TrendBg" to listOf(150)), BASE).size) // no ts
        assertEquals(
            0,
            parseOop2Trend(mapOf("com.eveningoutpost.dexdrip.Extras.TIMESTAMP" to 1L), BASE).size, // no trend
        )
    }

    private fun oop2(ts: Long, vararg trend: Any) = mapOf(
        "TrendBg" to trend.toList(),
        "com.eveningoutpost.dexdrip.Extras.TIMESTAMP" to ts,
    )

    @Test
    fun `an oop2 payload timestamped outside the live window is dropped whole`() {
        // The minute stream is live data and it anchors the forecast when it
        // is fresher than the main reading: parking a payload in the future
        // would keep it the freshest value for as long as the clock lags it.
        assertEquals(3, parseOop2Trend(oop2(BASE, 150, 148, 146), BASE).size)
        assertEquals(3, parseOop2Trend(oop2(BASE - BG_BROADCAST_MAX_AGE_MS, 150, 148, 146), BASE).size)
        assertEquals(3, parseOop2Trend(oop2(BASE + BG_BROADCAST_MAX_AHEAD_MS, 150, 148, 146), BASE).size)
        assertEquals(0, parseOop2Trend(oop2(BASE - BG_BROADCAST_MAX_AGE_MS - 1, 150, 148, 146), BASE).size)
        assertEquals(0, parseOop2Trend(oop2(BASE + BG_BROADCAST_MAX_AHEAD_MS + 1, 150, 148, 146), BASE).size)
        assertEquals(0, parseOop2Trend(oop2(BASE + 365L * 24 * 3_600_000, 150), BASE).size)
        assertEquals(0, parseOop2Trend(oop2(0L, 150), BASE).size)
    }

    @Test
    fun `an oop2 trend value outside the CGM range is skipped, the rest kept`() {
        val rs = parseOop2Trend(oop2(BASE, 150, 2000, 19.9, -5, 600, 20, Double.NaN, 0), BASE)
        assertEquals(listOf(150.0, 600.0, 20.0), rs.map { it.mgdl })
        // Skipped elements keep their minute slot: the survivors do not slide.
        val slot0 = Math.round(BASE / 60_000.0) * 60_000L
        assertEquals(listOf(slot0, slot0 - 4 * 60_000, slot0 - 5 * 60_000), rs.map { it.tsMs })
    }

    @Test
    fun `parse sgv entries filters and normalizes`() {
        val items = listOf(
            mapOf("sgv" to 180, "date" to BASE, "direction" to "Flat"),
            mapOf("sgv" to 0, "date" to BASE + 1000),          // zero -> skipped
            mapOf("date" to BASE + 2000),                       // no sgv -> skipped
            mapOf("sgv" to 120.5, "date" to null),              // no ts -> skipped
            mapOf("sgv" to 99, "date" to BASE + 300_000),
        )
        val rs = parseSgvEntries(items, nowMs = BASE + 300_000)
        assertEquals(2, rs.size)
        assertEquals(180.0, rs[0].mgdl, 1e-9)
        assertEquals(180.0 / 18.0182, rs[0].mmol, 1e-6)
        assertEquals("Flat", rs[0].trend)
        assertEquals("xdrip_sgv", rs[0].source)
        assertEquals(BASE + 300_000, rs[1].tsMs)
    }

    @Test
    fun `an sgv value outside the CGM range is skipped, not the whole backfill`() {
        fun at(sgv: Any) = parseSgvEntries(listOf(mapOf("sgv" to sgv, "date" to BASE)), BASE)
        assertEquals(1, at(20).size)
        assertEquals(1, at(600).size)
        assertEquals(0, at(19.9).size)
        assertEquals(0, at(600.1).size)
        assertEquals(0, at(-100).size)
        assertEquals(0, at(Double.NaN).size)
        assertEquals(0, at(Double.POSITIVE_INFINITY).size)
        // One forged row in a real backfill costs that row only.
        val mixed = parseSgvEntries(
            listOf(
                mapOf("sgv" to 120, "date" to BASE - 600_000),
                mapOf("sgv" to 5000, "date" to BASE - 300_000),
                mapOf("sgv" to 118, "date" to BASE),
            ),
            BASE,
        )
        assertEquals(listOf(BASE - 600_000, BASE), mixed.map { it.tsMs })
    }

    @Test
    fun `sgv backfill accepts two weeks of history but nothing from the future`() {
        fun at(ts: Long) = parseSgvEntries(listOf(mapOf("sgv" to 120, "date" to ts)), BASE)
        // The whole backfill depth is legitimate — the broadcast's 20-minute
        // window must NOT apply here, or the once-per-install pull is refused.
        assertEquals(1, at(BASE - 7L * 24 * 3_600_000).size)
        assertEquals(1, at(BASE - BG_BACKFILL_MAX_AGE_MS).size)
        assertEquals(0, at(BASE - BG_BACKFILL_MAX_AGE_MS - 1).size)
        // The future is bounded exactly as tightly as for the broadcast.
        assertEquals(1, at(BASE + BG_BROADCAST_MAX_AHEAD_MS).size)
        assertEquals(0, at(BASE + BG_BROADCAST_MAX_AHEAD_MS + 1).size)
        assertEquals(0, at(BASE + 365L * 24 * 3_600_000).size)
        assertEquals(0, at(0L).size)
        assertEquals(0, at(-BASE).size)
    }
}

class MealDetectorTest {

    private fun readings(points: List<Pair<Int, Double>>): List<GlucosePoint> =
        points.map { (m, b) -> GlucosePoint(BASE + m * 60_000L, b) }

    private fun boluses(points: List<Pair<Int, Double>>): List<BolusPoint> =
        points.map { (m, u) -> BolusPoint(BASE + m * 60_000L, u) }

    @Test
    fun `meal rise with bolus is announced`() {
        val pts = (-30 until 5 step 5).map { it to 6.0 } +
            listOf(5 to 6.5, 10 to 7.5, 15 to 8.8, 20 to 9.5, 25 to 9.8, 30 to 9.9) +
            (35 until 60 step 5).map { it to 9.9 }
        val meals = detectMeals(readings(pts), boluses(listOf(0 to 6.0)))
        assertEquals(1, meals.size)
        assertEquals(MealEvent.Kind.ANNOUNCED, meals[0].kind)
        assertTrue(meals[0].rise >= 2.0)
        assertEquals(6.0, meals[0].bolusUnits!!, 1e-9)
    }

    @Test
    fun `meal rise without bolus is unannounced`() {
        val pts = (-30 until 5 step 5).map { it to 6.0 } +
            listOf(5 to 6.6, 10 to 7.6, 15 to 8.9, 20 to 9.6, 25 to 9.9) +
            (30 until 55 step 5).map { it to 9.9 }
        val meals = detectMeals(readings(pts), boluses(emptyList()))
        assertEquals(1, meals.size)
        assertEquals(MealEvent.Kind.UNANNOUNCED, meals[0].kind)
    }

    @Test
    fun `correction fall is not a meal`() {
        // BG falling after a bolus -> correction, no rise, no meal.
        val pts = listOf(0 to 12.0, 30 to 11.0, 60 to 9.5, 90 to 8.0, 120 to 7.0, 150 to 6.5)
        val meals = detectMeals(readings(pts), boluses(listOf(0 to 4.0)))
        assertEquals(0, meals.size)
    }

    @Test
    fun `micro wiggle ignored`() {
        val pts = (0 until 120 step 5).map { it to (6.0 + 0.3 * (it % 2)) } // tiny noise <2 mmol
        val meals = detectMeals(readings(pts), boluses(emptyList()))
        assertEquals(0, meals.size)
    }

    @Test
    fun `empty readings yield no meals`() {
        assertEquals(0, detectMeals(emptyList(), emptyList()).size)
    }

    @Test
    fun `unsorted readings are handled`() {
        val pts = listOf(20 to 9.5, 0 to 6.0, 10 to 7.5, 5 to 6.5, 15 to 8.8, 25 to 9.8, 30 to 9.9, -5 to 6.0, -10 to 6.0)
        val meals = detectMeals(readings(pts), boluses(emptyList()))
        assertEquals(1, meals.size)
    }
}
