package com.example.diapilot.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Retention must free the ledger WITHOUT touching the observation heartbeat.
 *
 * Two of these assertions guard a mistake that would be invisible until the day
 * it mattered:
 *
 *  - `forecast_runs` survives regardless of age or generation. It is the only
 *    series that records whether the app was LOOKING (discipline #6 — readings
 *    back-fill, so a blind stretch reads as a clean quiet one afterwards).
 *    A sweep that took old runs would make every historical anomaly claim
 *    unfalsifiable, and nothing in the app would report the loss.
 *  - the live SHADOW arm survives. Its `algo_version` is not the shipped
 *    engine's and is not the FP shadow constant either, so any rule written
 *    against the code constants deletes it. Here the shadow arm is deliberately
 *    given a version string that matches neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerRetentionTest {

    private val day = 24 * 3_600_000L

    private fun store() = SqliteCollectorStore(
        ApplicationProvider.getApplicationContext(),
        "retention-test-${System.nanoTime()}.sqlite",
    )

    /** One run plus one point and one score for it. */
    private fun seed(
        db: android.database.sqlite.SQLiteDatabase,
        id: Long,
        version: String,
        createdMs: Long,
    ) {
        db.execSQL(
            "INSERT INTO forecast_runs(id,created_at_ms,anchor_ts_ms,anchor_mmol,consumer,algo_version,regime,health,input_hash) " +
                "VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(id, createdMs, createdMs, 7.0, "main", version, "", "", "h$id"),
        )
        db.execSQL(
            "INSERT INTO forecast_points(run_id,horizon_min,target_ts_ms,mmol,lo,hi,lo_mid,hi_mid) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any>(id, 60, createdMs + 3_600_000L, 7.5, 6.0, 9.0, 6.5, 8.5),
        )
    }

    private fun count(db: android.database.sqlite.SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    @Test
    fun `dead generations lose detail, live arms and every run survive`() {
        val s = store()
        val db = s.writableDatabase
        val now = 1_700_000_000_000L
        // Two arms written today: the shipped engine and a shadow whose version
        // string is neither the engine constant nor the FP shadow tag.
        seed(db, 1, "forecast-v15-live", now - 1 * 3_600_000L)
        seed(db, 2, "forecast-v11-shadow-12-live", now - 2 * 3_600_000L)
        // The same two arms a fortnight ago: older than the floor, but their
        // generations are still in use, so their detail stays.
        // Inside [LedgerRetention.DETAIL_DAYS]: the live arm's RECENT detail is
        // what this test is about. Older live detail now goes on the time floor
        // — asserted separately below, so the two mechanisms
        // stay distinguishable instead of one masking the other.
        seed(db, 3, "forecast-v15-live", now - 3 * day)
        seed(db, 4, "forecast-v11-shadow-12-live", now - 3 * day)
        // A generation nobody runs any more, old: the only deletable rows.
        seed(db, 5, "forecast-v9-retired", now - 20 * day)
        seed(db, 6, "forecast-v9-retired", now - 30 * day)
        // Retired generation, outside the ACTIVE window (2 d) but inside the
        // FLOOR (7 d). This is the case the two windows exist to separate: the
        // generation is dead, the row is recent, and the row wins.
        seed(db, 7, "forecast-v9-retired", now - 4 * day)

        val before = count(db, "SELECT COUNT(*) FROM forecast_runs")
        assertEquals(7, before)

        val r = LedgerRetention.run(db, now)

        assertEquals("points deleted only from the two dead runs", 2, r.points)
        // Scores no longer exist as a table: the fact is computed from readings
        // at the target moment. The field stays zero in Result so as not to break callers.
        assertEquals("the scores field must be zero", 0, r.scores)
        assertTrue(
            "a live arm older than the time floor kept its detail — the ledger grows without bound",
            LedgerRetention.DETAIL_DAYS < LedgerRetention.HEARTBEAT_DAYS,
        )
        assertEquals("two live generations", 2, r.generationsKept)

        assertEquals(
            "forecast_runs НЕ ТРОГАЕМ — это пульс наблюдения",
            7, count(db, "SELECT COUNT(*) FROM forecast_runs"),
        )
        assertEquals(
            "живая тень цела",
            2, count(db, "SELECT COUNT(*) FROM forecast_points WHERE run_id IN (2,4)"),
        )
        assertEquals(
            "старое живое поколение цело",
            1, count(db, "SELECT COUNT(*) FROM forecast_points WHERE run_id = 3"),
        )
        assertEquals(
            "мёртвое поколение, но строка свежее пола — под защитой пола",
            1, count(db, "SELECT COUNT(*) FROM forecast_points WHERE run_id = 7"),
        )
        assertEquals(
            "мёртвое и старое — удалено",
            0, count(db, "SELECT COUNT(*) FROM forecast_points WHERE run_id IN (5,6)"),
        )
        s.close()
    }

    @Test
    fun `no forecasts inside the active window deletes nothing rather than everything`() {
        val s = store()
        val db = s.writableDatabase
        val now = 1_700_000_000_000L
        // Nothing written inside the floor: the active set is empty. A naive
        // rule would conclude that every generation is dead and wipe the lot.
        seed(db, 1, "forecast-v15-live", now - 30 * day)
        seed(db, 2, "forecast-v11-shadow-12-live", now - 40 * day)

        val r = LedgerRetention.run(db, now)

        assertEquals("простой не повод для чистки", 0, r.total)
        assertEquals(2, count(db, "SELECT COUNT(*) FROM forecast_points"))
        s.close()
    }

    @Test
    fun `preview removes nothing`() {
        val s = store()
        val db = s.writableDatabase
        val now = 1_700_000_000_000L
        seed(db, 1, "forecast-v15-live", now - 3_600_000L)
        seed(db, 2, "forecast-v9-retired", now - 30 * day)

        val p = LedgerRetention.preview(db, now)
        // ONE row, counted once. The 30-day-old run is past both the dead
        // generation and the detail floor, and the two sweeps are deliberately
        // disjoint so `Result.total` says what a real sweep would remove
        // rather than double-counting it.
        assertEquals("предпросмотр считает", 1, p.points)
        assertEquals("но не удаляет", 2, count(db, "SELECT COUNT(*) FROM forecast_points"))
        s.close()
    }
}
