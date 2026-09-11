package com.diapilot.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire contract of `GET /api/v1/events`, exercised end to end against an
 * in-memory journal store.
 *
 * The fake is not a stand-in for SQLite semantics — it implements the same
 * three guarantees the port demands (monotonic never-reused seq, ascending
 * pages, a persisted source id) and the tests below fail if the JOURNAL logic
 * breaks any of them. Restart is modelled the way it actually happens: the
 * process-side objects are thrown away and rebuilt over the same storage.
 */
class EventsApiTest {

    // --- a store that survives "restart" ------------------------------------

    /** The bytes on disk. Outlives the store object, exactly like a file. */
    class Disk {
        val rows = mutableListOf<JournalEntry>()
        var nextSeq = 1L
        var sourceId: String? = null
        var prunedThrough = 0L
    }

    class FakeStore(private val disk: Disk) : EventJournalStore {
        override fun sourceId(): String =
            disk.sourceId ?: java.util.UUID.randomUUID().toString().also { disk.sourceId = it }

        override fun append(rows: List<PendingEntry>) {
            for (r in rows) {
                disk.rows += JournalEntry(
                    seq = disk.nextSeq++, id = r.id, revision = r.revision, type = r.type,
                    occurredAtMs = r.occurredAtMs, receivedAtMs = r.receivedAtMs,
                    deleted = r.deleted, payload = r.payload,
                )
            }
        }

        override fun page(afterSeq: Long, limit: Int): List<JournalEntry> =
            disk.rows.filter { it.seq > afterSeq }.sortedBy { it.seq }.take(limit)

        override fun hasAfter(seq: Long): Boolean = disk.rows.any { it.seq > seq }

        override fun headsInWindow(fromMs: Long, toMs: Long): Map<String, JournalEntry> =
            disk.rows.groupBy { it.id }
                .mapValues { (_, v) -> v.maxBy { it.seq } }
                .filterValues { it.occurredAtMs in fromMs..toMs }

        override fun head(id: String): JournalEntry? =
            disk.rows.filter { it.id == id }.maxByOrNull { it.seq }

        override fun prunedThroughSeq(): Long = disk.prunedThrough
    }

    // --- fixtures -----------------------------------------------------------

    private val t0 = 1_785_000_000_000L // 2026-07-24T06:40:00Z

    /** An era that starts well before [t0]: the floor is not what these tests are about. */
    private val era = FoodEra(java.time.LocalDate.of(2025, 1, 6), java.time.ZoneOffset.UTC)

    private fun glucoseFact(tsMs: Long, mmol: Double, source: String? = "libre_ble") = Fact(
        id = "glucose:$tsMs",
        type = EventPayloads.TYPE_GLUCOSE,
        occurredAtMs = tsMs,
        payload = EventPayloads.glucose(mmol, GlucoseUnit.MMOL_L, source),
    )

    private fun insulinFact(
        tsMs: Long,
        units: Double,
        kind: InsulinKind = InsulinKind.BOLUS,
        product: String = "Fiasp",
    ) = Fact(
        id = "insulin:event:$tsMs",
        type = EventPayloads.TYPE_INSULIN,
        occurredAtMs = tsMs,
        payload = EventPayloads.insulin(units, kind, product, DeliveryStatus.DELIVERED),
    )

    private fun journal(disk: Disk = Disk()) = EventJournal(FakeStore(disk))

    /** Ten readings on the 5-minute grid, all landing in one observation. */
    private fun tenReadings(j: EventJournal, receivedAtMs: Long = t0 + 5_000) {
        val facts = (0 until 10).map { glucoseFact(t0 + it * 300_000L, 8.0 + it * 0.1) }
        j.reconcile(t0, t0 + 3_600_000, facts, receivedAtMs)
    }

    private fun seqs(body: String): List<Long> =
        Regex("\"seq\":(\\d+)").findAll(body).map { it.groupValues[1].toLong() }.toList()

    private fun field(body: String, name: String): String =
        Regex("\"$name\":(\"[^\"]*\"|[^,}]+)").find(body)!!.groupValues[1].trim('"')

    private fun get(j: EventJournal, after: Long, limit: Int? = null): ApiResponse =
        EventsApi.handle(
            buildMap {
                put("after", after.toString())
                if (limit != null) put("limit", limit.toString())
            },
            j,
        )

    // --- 1. after is exclusive ---------------------------------------------

    @Test
    fun `after is exclusive`() {
        val j = journal()
        tenReadings(j)

        val all = seqs(get(j, 0).body)
        assertEquals((1L..10L).toList(), all)

        // Asking after=3 must NOT return 3 itself.
        val tail = seqs(get(j, 3).body)
        assertEquals((4L..10L).toList(), tail)
        assertFalse("seq 3 was already delivered", tail.contains(3L))

        // And the last one delivered yields an empty page, not a repeat.
        assertEquals(emptyList<Long>(), seqs(get(j, 10).body))
    }

    // --- 2. events are ordered by seq ---------------------------------------

    @Test
    fun `events come back in ascending seq order`() {
        val j = journal()
        // Facts handed over in DESCENDING time, plus a late back-fill of an
        // OLDER reading afterwards — the real shape of this data, and the
        // reason seq exists at all.
        val descending = (9 downTo 0).map { glucoseFact(t0 + it * 300_000L, 8.0 + it * 0.1) }
        j.reconcile(t0, t0 + 3_600_000, descending, t0 + 5_000)
        j.reconcile(
            t0 - 3_600_000, t0 - 1,
            listOf(glucoseFact(t0 - 600_000, 7.4)),
            t0 + 600_000,
        )

        val body = get(j, 0).body
        val order = seqs(body)
        assertEquals(order.sorted(), order)
        assertEquals(11, order.size)

        // The back-filled point is LAST by seq while being FIRST by time —
        // exactly the case a ts-ordered feed would lose.
        val occurred = Regex("\"occurred_at\":\"([^\"]+)\"").findAll(body)
            .map { it.groupValues[1] }.toList()
        assertEquals(rfc3339Utc(t0 - 600_000), occurred.last())
        assertTrue(occurred.last() < occurred.first())
    }

    // --- 3. pagination loses and duplicates nothing -------------------------

    @Test
    fun `paging through in small pages yields every event exactly once`() {
        val j = journal()
        val facts = (0 until 47).map { glucoseFact(t0 + it * 300_000L, 5.0 + it * 0.01) }
        j.reconcile(t0, t0 + 30L * 3_600_000, facts, t0 + 5_000)

        val collected = mutableListOf<Long>()
        var after = 0L
        var guard = 0
        while (true) {
            val r = get(j, after, limit = 7)
            assertEquals(200, r.status)
            val page = seqs(r.body)
            collected += page
            val next = field(r.body, "next_after").toLong()
            if (field(r.body, "has_more") != "true") break
            assertNotEquals("a has_more page that does not advance would spin", after, next)
            after = next
            check(guard++ < 100) { "paging did not terminate" }
        }

        assertEquals(47, collected.size)
        assertEquals(47, collected.toSet().size)          // no duplicates
        assertEquals((1L..47L).toList(), collected)       // no gaps
    }

    // --- 4. next_after, empty and non-empty ---------------------------------

    @Test
    fun `next_after is the last delivered seq, and stands still on an empty page`() {
        val j = journal()
        tenReadings(j)

        val first = get(j, 0, limit = 4)
        assertEquals("4", field(first.body, "next_after"))

        val last = get(j, 4, limit = 100)
        assertEquals("10", field(last.body, "next_after"))

        // Nothing new: next_after must stay where the consumer already is,
        // never reset to 0 and never jump ahead.
        val empty = get(j, 10)
        assertEquals("10", field(empty.body, "next_after"))
        assertEquals("[]", Regex("\"events\":(\\[.*])").find(empty.body)!!.groupValues[1])

        // An empty journal answers the same way about after=0.
        assertEquals("0", field(get(journal(), 0).body, "next_after"))
    }

    // --- 5. has_more --------------------------------------------------------

    @Test
    fun `has_more says whether anything is left behind the page`() {
        val j = journal()
        tenReadings(j)

        assertEquals("true", field(get(j, 0, limit = 4).body, "has_more"))
        // Exactly drained by this page.
        assertEquals("false", field(get(j, 0, limit = 10).body, "has_more"))
        assertEquals("false", field(get(j, 0, limit = 50).body, "has_more"))
        // Caught up.
        assertEquals("false", field(get(j, 10).body, "has_more"))
        assertEquals("false", field(get(journal(), 0).body, "has_more"))
    }

    // --- 6. source_id and seq survive a restart -----------------------------

    @Test
    fun `source_id and seq survive a restart`() {
        val disk = Disk()
        val before = journal(disk)
        tenReadings(before)
        val idBefore = field(get(before, 0).body, "source_id")
        assertEquals(10L, seqs(get(before, 0).body).last())

        // Restart: every in-process object is gone, the storage is not.
        val after = journal(disk)
        val idAfter = field(get(after, 0).body, "source_id")
        assertEquals(idBefore, idAfter)
        assertTrue(idBefore.isNotBlank())

        // seq continues rather than restarting at 1 — otherwise a consumer
        // holding after=10 would never see the next eleven events.
        after.reconcile(
            t0 + 3_600_000, t0 + 7_200_000,
            listOf(glucoseFact(t0 + 3_900_000, 6.2)),
            t0 + 3_905_000,
        )
        assertEquals(listOf(11L), seqs(get(after, 10).body))

        // And what was already handed out is still there, byte for byte.
        assertEquals(get(before, 0).body.substringBefore(",\"next_after\""),
            get(after, 0).body.substringBefore(",\"next_after\""))
        assertEquals((1L..11L).toList(), seqs(get(after, 0).body))
    }

    // --- 7. a correction keeps the id and bumps the revision ----------------

    @Test
    fun `a corrected dose keeps its id and increments revision`() {
        val j = journal()
        val ts = t0 + 100_000
        j.reconcile(t0, t0 + 3_600_000, listOf(insulinFact(ts, 4.5)), t0 + 200_000)

        val v1 = get(j, 0).body
        assertEquals("insulin:event:$ts", field(v1, "id"))
        assertEquals("1", field(v1, "revision"))
        assertEquals("4.5", field(v1, "units"))

        // The user edits 4.5 U to 5.0 U.
        j.reconcile(t0, t0 + 3_600_000, listOf(insulinFact(ts, 5.0)), t0 + 900_000)

        val v2 = get(j, 1).body
        assertEquals(listOf(2L), seqs(v2))
        assertEquals("insulin:event:$ts", field(v2, "id"))   // same fact
        assertEquals("2", field(v2, "revision"))             // new version
        assertEquals("5", field(v2, "units"))
        assertEquals("false", field(v2, "deleted"))

        // Reconciling the SAME truth again must not invent a revision 3 —
        // otherwise every poll would republish the whole history.
        j.reconcile(t0, t0 + 3_600_000, listOf(insulinFact(ts, 5.0)), t0 + 1_800_000)
        assertEquals(emptyList<Long>(), seqs(get(j, 2).body))
    }

    // --- 8. deletion --------------------------------------------------------

    @Test
    fun `a deleted dose is published as deleted, not silently dropped`() {
        val j = journal()
        val ts = t0 + 100_000
        j.reconcile(t0, t0 + 3_600_000, listOf(insulinFact(ts, 4.5)), t0 + 200_000)

        // Gone from storage; the window it lived in is reconciled again.
        j.reconcile(t0, t0 + 3_600_000, emptyList(), t0 + 900_000)

        val body = get(j, 1).body
        assertEquals(listOf(2L), seqs(body))
        assertEquals("insulin:event:$ts", field(body, "id"))
        assertEquals("2", field(body, "revision"))
        assertEquals("true", field(body, "deleted"))
        assertEquals("insulin", field(body, "type"))
        // Medical fields are dropped from a tombstone; the timestamps are not,
        // because the consumer needs to know WHICH point in its own history to
        // remove.
        assertFalse(body.contains("\"units\""))
        assertTrue(body.contains("\"occurred_at\""))
        assertTrue(body.contains("\"received_at\""))

        // Already dead: a second sweep must not keep emitting tombstones.
        j.reconcile(t0, t0 + 3_600_000, emptyList(), t0 + 1_800_000)
        assertEquals(emptyList<Long>(), seqs(get(j, 2).body))

        // Re-added later — same identity, revision keeps climbing.
        j.reconcile(t0, t0 + 3_600_000, listOf(insulinFact(ts, 4.5)), t0 + 2_700_000)
        val back = get(j, 2).body
        assertEquals("3", field(back, "revision"))
        assertEquals("false", field(back, "deleted"))
    }

    // --- 9. occurred_at and received_at stay apart --------------------------

    @Test
    fun `occurred_at and received_at are recorded separately`() {
        val j = journal()
        val measuredAt = t0
        // A back-filled reading: the app learned of it 14 minutes late. That
        // gap is the whole point — substituting occurred_at would erase it.
        val learnedAt = t0 + 14L * 60_000
        j.reconcile(
            t0 - 3_600_000, t0 + 3_600_000,
            listOf(glucoseFact(measuredAt, 8.61)),
            learnedAt,
        )

        val body = get(j, 0).body
        assertEquals(rfc3339Utc(measuredAt), field(body, "occurred_at"))
        assertEquals(rfc3339Utc(learnedAt), field(body, "received_at"))
        assertNotEquals(field(body, "occurred_at"), field(body, "received_at"))

        // RFC 3339, UTC, milliseconds always present.
        val stamp = field(body, "received_at")
        assertTrue(stamp, Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(stamp))

        // A correction re-stamps received_at and leaves occurred_at alone.
        j.reconcile(
            t0 - 3_600_000, t0 + 3_600_000,
            listOf(glucoseFact(measuredAt, 8.7)),
            learnedAt + 60_000,
        )
        val v2 = get(j, 1).body
        assertEquals(rfc3339Utc(measuredAt), field(v2, "occurred_at"))
        assertEquals(rfc3339Utc(learnedAt + 60_000), field(v2, "received_at"))
    }

    // --- 10. glucose in both units ------------------------------------------

    @Test
    fun `glucose carries its unit and is not rounded`() {
        val j = journal()
        // 155 mg/dL through the app's own conversion — the long decimal is the
        // point: the value goes out as computed, without a display rounding.
        val computed = 155.0 / 18.0182
        j.reconcile(
            t0, t0 + 600_000,
            listOf(
                glucoseFact(t0, computed),
                Fact(
                    id = "glucose:${t0 + 300_000}",
                    type = EventPayloads.TYPE_GLUCOSE,
                    occurredAtMs = t0 + 300_000,
                    payload = EventPayloads.glucose(155.0, GlucoseUnit.MGDL, "xdrip_sgv"),
                ),
            ),
            t0 + 5_000,
        )

        val body = get(j, 0).body
        assertTrue(body, body.contains("\"value\":$computed,\"unit\":\"mmol/L\""))
        assertTrue(body, body.contains("\"value\":155,\"unit\":\"mg/dL\""))
        assertEquals(computed, field(body, "value").toDouble(), 0.0)
        assertTrue(body.contains("\"type\":\"glucose\""))
    }

    // --- 11. insulin kinds --------------------------------------------------

    @Test
    fun `insulin distinguishes bolus, basal and prime`() {
        val j = journal()
        j.reconcile(
            t0, t0 + 3_600_000,
            listOf(
                insulinFact(t0 + 60_000, 4.5, InsulinKind.BOLUS),
                Fact(
                    id = "insulin:basal:${t0 + 120_000}",
                    type = EventPayloads.TYPE_INSULIN,
                    occurredAtMs = t0 + 120_000,
                    payload = EventPayloads.insulin(
                        20.0, InsulinKind.BASAL, "Toujeo", DeliveryStatus.DELIVERED,
                    ),
                ),
                insulinFact(t0 + 180_000, 1.0, InsulinKind.PRIME),
            ),
            t0 + 200_000,
        )

        val body = get(j, 0).body
        assertTrue(body, body.contains("\"units\":4.5,\"kind\":\"bolus\",\"product\":\"Fiasp\""))
        assertTrue(body, body.contains("\"units\":20,\"kind\":\"basal\",\"product\":\"Toujeo\""))
        assertTrue(body, body.contains("\"units\":1,\"kind\":\"prime\""))
        // Only what was actually put in counts as delivered — and DiaPilot has
        // no other kind of record, so nothing here can be a programmed dose.
        assertEquals(3, Regex("\"delivery_status\":\"delivered\"").findAll(body).count())
        assertEquals(0, Regex("\"delivery_status\":\"programmed\"").findAll(body).count())
    }

    // --- 12. the old endpoint still routes ----------------------------------

    @Test
    fun `the watch endpoint is untouched by the new route`() {
        // The exact target a Zepp OS face polls.
        val watch = parseRequestTarget("/info.json?graph=1")
        assertEquals("/info.json", watch.path)
        assertEquals("1", watch.params["graph"])
        assertEquals(LocalRoute.INFO_JSON, routeFor(watch.path))

        // Its long-poll form, which must not be swallowed either.
        val poll = parseRequestTarget("/info.json?newer_than=1785000000000&wait=75&graph=1")
        assertEquals(LocalRoute.INFO_JSON, routeFor(poll.path))
        assertEquals("1785000000000", poll.params["newer_than"])
        assertEquals("75", poll.params["wait"])
        assertEquals("1", poll.params["graph"])

        assertEquals(LocalRoute.ADD_TREATMENTS, routeFor(parseRequestTarget("/add_treatments?insulin=4.5").path))

        // The new one is a separate path, and nothing else moved.
        val api = parseRequestTarget("/api/v1/events?after=1052&limit=500")
        assertEquals(LocalRoute.EVENTS, routeFor(api.path))
        assertEquals("1052", api.params["after"])
        assertEquals("500", api.params["limit"])
        assertEquals(LocalRoute.NOT_FOUND, routeFor("/api/v1/other"))
        assertEquals(LocalRoute.NOT_FOUND, routeFor("/"))
    }

    // --- errors -------------------------------------------------------------

    @Test
    fun `bad after and limit are rejected with 400`() {
        val j = journal()
        tenReadings(j)
        for (bad in listOf("-1", "abc", "", "1.5")) {
            assertEquals("after=$bad", 400, EventsApi.handle(mapOf("after" to bad), j).status)
        }
        for (bad in listOf("0", "-5", "x", "1001")) {
            assertEquals(
                "limit=$bad", 400,
                EventsApi.handle(mapOf("after" to "0", "limit" to bad), j).status,
            )
        }
        assertEquals(200, EventsApi.handle(mapOf("after" to "0", "limit" to "1000").toMap(), j).status)
        // Defaults apply when the caller omits them.
        assertEquals(200, EventsApi.handle(emptyMap(), j).status)

        // An error body never carries a medical value — least of all a zero,
        // which a consumer would read as a measured hypo.
        val err = EventsApi.handle(mapOf("after" to "-1"), j)
        assertFalse(err.body.contains("\"value\""))
        assertFalse(err.body.contains("glucose"))
    }

    @Test
    fun `resuming from a pruned stretch is 410, not a page with a hole`() {
        val disk = Disk()
        val j = journal(disk)
        tenReadings(j)

        // Retention dropped everything through seq 5.
        disk.rows.removeAll { it.seq <= 5 }
        disk.prunedThrough = 5

        assertEquals(410, get(j, 3).status)
        assertEquals(410, get(j, 4).status)
        // At the boundary the consumer has everything that was dropped.
        assertEquals(200, get(j, 5).status)
        assertEquals(200, get(j, 7).status)
        // A fresh consumer is always servable: it asked for "whatever you have".
        assertEquals(200, get(j, 0).status)
        assertEquals(listOf(6L, 7L, 8L, 9L, 10L), seqs(get(j, 0).body))
    }

    // --- meals --------------------------------------------------------------

    private fun note(
        id: Long,
        tsMs: Long,
        content: String,
        kind: String = "food",
        carbs: Double? = null,
        source: String? = null,
        analysis: String? = null,
    ) = com.diapilot.core.collector.Annotation(
        tsMs = tsMs, kind = kind, content = content, id = id,
        analysis = analysis, estCarbs = carbs, carbsSource = source,
    )

    @Test
    fun `meals publish the logged text, grams, their provenance and the LLM analysis`() {
        val j = journal()
        val ts = t0 + 60_000
        j.reconcile(
            t0, t0 + 3_600_000,
            DiaForFacts.meals(
                listOf(
                    note(
                        207, ts, "холодник", carbs = 41.0, source = "manual",
                        // The real shape: free text with prefixed lines, and a
                        // newline that MUST survive JSON escaping.
                        analysis = "Холодник свекольный\nСОСТАВ: Свёкла = 7 угл · 80 пор",
                    ),
                ),
                era,
            ),
            t0 + 120_000,
        )

        val body = get(j, 0).body
        assertEquals("meal", field(body, "type"))
        assertEquals("meal:207", field(body, "id"))
        assertEquals("холодник", field(body, "text"))
        assertEquals("41", field(body, "carbs_g"))
        assertEquals("manual", field(body, "carbs_source"))
        assertTrue(body, body.contains("\"analysis\":\"Холодник свекольный\\nСОСТАВ:"))
        // The note's own time, not the moment it was written down.
        assertEquals(rfc3339Utc(ts), field(body, "occurred_at"))
        assertEquals(rfc3339Utc(t0 + 120_000), field(body, "received_at"))
    }

    @Test
    fun `only food notes become meals`() {
        // Context notes and tags are not food, and none of them carries carbs.
        val facts = DiaForFacts.meals(
            listOf(
                note(1, t0, "прогулка · 150 мин", kind = "text"),
                note(2, t0 + 1, "спорт", kind = "tag"),
                note(3, t0 + 2, "чипсы", kind = "food", carbs = 35.0),
            ),
            era,
        )
        assertEquals(listOf("meal:3"), facts.map { it.id })
    }

    @Test
    fun `a meal keeps its identity when its time is corrected`() {
        val j = journal()
        val logged = t0 + 3_600_000
        j.reconcile(t0, t0 + 7_200_000, DiaForFacts.meals(listOf(note(207, logged, "блины", carbs = 50.0)), era), logged)

        // A correction: the meal happened an hour earlier than first logged. The row id does not move, so
        // this is one fact at revision 2 — not a delete plus a new meal.
        val actual = logged - 3_600_000
        j.reconcile(
            t0, t0 + 7_200_000,
            DiaForFacts.meals(listOf(note(207, actual, "блины", carbs = 50.0)), era),
            logged + 300_000,
        )

        val v2 = get(j, 1).body
        assertEquals(listOf(2L), seqs(v2))
        assertEquals("meal:207", field(v2, "id"))
        assertEquals("2", field(v2, "revision"))
        assertEquals("false", field(v2, "deleted"))
        assertEquals(rfc3339Utc(actual), field(v2, "occurred_at"))
    }

    @Test
    fun `absent grams and analysis are omitted, not sent as null`() {
        val j = journal()
        j.reconcile(
            t0, t0 + 3_600_000,
            DiaForFacts.meals(listOf(note(9, t0 + 60_000, "мороженое магнум с")), era),
            t0 + 120_000,
        )
        val body = get(j, 0).body
        assertTrue(body, body.contains("\"text\":\"мороженое магнум с\""))
        assertFalse(body, body.contains("null"))
        assertFalse(body, body.contains("carbs_g"))
        assertFalse(body, body.contains("analysis"))
        assertFalse(body, body.contains("carbs_source"))
    }

    @Test
    fun `a deleted note is published as a deleted meal`() {
        val j = journal()
        j.reconcile(
            t0, t0 + 3_600_000,
            DiaForFacts.meals(listOf(note(207, t0 + 60_000, "холодник", carbs = 41.0)), era),
            t0 + 120_000,
        )
        j.reconcile(t0, t0 + 3_600_000, emptyList(), t0 + 900_000)

        val body = get(j, 1).body
        assertEquals("meal:207", field(body, "id"))
        assertEquals("true", field(body, "deleted"))
        assertEquals("meal", field(body, "type"))
        assertFalse(body, body.contains("\"text\""))
        assertFalse(body, body.contains("\"carbs_g\""))
    }

    @Test
    fun `the era floor is an input and moves with it`() {
        // The same reading is published under one era and withheld under a
        // later one: the builders hold no date of their own.
        val reading = listOf(com.diapilot.core.collector.GlucosePoint(era.startMs + 1, 6.0) to "libre_ble")
        val later = FoodEra(era.startDate.plusDays(1), era.zone)
        assertEquals(1, DiaForFacts.glucose(reading, era).size)
        assertEquals(0, DiaForFacts.glucose(reading, later).size)
    }

    @Test
    fun `nothing before the era floor is publishable`() {
        val floor = era.startMs
        val day = 86_400_000L

        // One reading a day either side of the floor, plus the floor itself.
        val glucose = DiaForFacts.glucose(
            listOf(
                com.diapilot.core.collector.GlucosePoint(floor - day, 6.0) to "xdrip_import",
                com.diapilot.core.collector.GlucosePoint(floor - 1, 6.1) to "xdrip_import",
                com.diapilot.core.collector.GlucosePoint(floor, 6.2) to "libre_ble",
                com.diapilot.core.collector.GlucosePoint(floor + day, 6.3) to "libre_ble",
            ),
            era,
        )
        assertEquals(listOf(floor, floor + day), glucose.map { it.occurredAtMs })

        // Both dose tables obey the same floor.
        val insulin = DiaForFacts.insulin(
            boluses = listOf(
                com.diapilot.core.collector.BolusPoint(floor - day, 4.0, null),
                com.diapilot.core.collector.BolusPoint(floor + day, 5.0, null),
            ),
            basal = listOf(
                com.diapilot.core.collector.BolusPoint(floor - 1, 20.0, null),
                com.diapilot.core.collector.BolusPoint(floor, 20.0, null),
            ),
            bolusProduct = "Fiasp", basalProduct = "Toujeo",
            era = era,
        )
        assertEquals(
            listOf(DiaForFacts.bolusId(floor + day), DiaForFacts.basalId(floor)),
            insulin.map { it.id },
        )

        val meals = DiaForFacts.meals(
            listOf(note(1, floor - 1, "старая еда"), note(2, floor, "первая еда")),
            era,
        )
        assertEquals(listOf(DiaForFacts.mealId(2)), meals.map { it.id })

        // And the floor holds on the WIRE, not merely in the builder: a caller
        // declaring a window that reaches back further gets nothing older.
        val j = journal()
        j.reconcile(floor - 30 * day, floor + 2 * day, glucose + insulin, floor + 2 * day)
        val served = Regex("\"occurred_at\":\"([^\"]+)\"").findAll(get(j, 0).body)
            .map { it.groupValues[1] }.toList()
        assertEquals(4, served.size)
        assertTrue(served.toString(), served.all { it >= rfc3339Utc(floor) })
    }

    @Test
    fun `the response envelope carries exactly the declared fields`() {
        val j = journal()
        tenReadings(j)
        val body = get(j, 0, limit = 1).body
        assertTrue(body, body.startsWith("{\"version\":1,\"source_id\":\""))
        assertTrue(body, body.endsWith("]}"))
        assertEquals("1", field(body, "version"))
        val event = Regex("\\{\"seq\".*?}").find(body)!!.value
        for (f in listOf(
            "seq", "id", "revision", "type", "occurred_at", "received_at", "deleted",
        )) {
            assertTrue("$f missing from $event", event.contains("\"$f\":"))
        }
    }
}
