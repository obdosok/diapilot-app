package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE LEDGER MUST STOP GROWING.
 *
 * The generation sweep prunes only runs whose `algo_version` is no longer
 * live, so a stable tag prunes nothing — and the tag was stable for weeks.
 * At the app's write rate that is a few MB a day, forever.
 *
 * Two floors, and the split is the point: the heartbeat (one row per pass, the
 * only series that can say whether the app was LOOKING — discipline #6) is
 * cheap and keeps for months; the points and scores it indexes are ten times
 * the volume and answer only recent questions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerDetailFloorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = 1_787_000_000_000L
    private val day = 86_400_000L

    private fun seed(
        store: SqliteCollectorStore,
        id: Long,
        createdAtMs: Long,
        tag: String,
        consumer: String = "main",
    ) {
        val db = store.writableDatabase
        db.execSQL(
            "INSERT OR REPLACE INTO forecast_runs" +
                "(id,created_at_ms,anchor_ts_ms,anchor_mmol,consumer,algo_version,regime,health,input_hash)" +
                " VALUES (?,?,?,?,?,?,NULL,NULL,?)",
            arrayOf<Any?>(id, createdAtMs, createdAtMs, 6.0, consumer, tag, "h$id"),
        )
        db.execSQL(
            "INSERT OR REPLACE INTO forecast_points" +
                "(run_id,horizon_min,target_ts_ms,mmol,lo,hi,lo_mid,hi_mid) VALUES (?,60,?,7.0,6.0,8.0,6.5,7.5)",
            arrayOf<Any?>(id, createdAtMs + 3_600_000),
        )
    }

    private fun count(store: SqliteCollectorStore, table: String): Int =
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null)
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }

    @Test
    fun `detail older than the floor goes and the heartbeat stays`() {
        val name = "ledger-floor.sqlite"
        context.deleteDatabase(name)
        SqliteCollectorStore(context, name).use { store ->
            val live = "live-tag"
            seed(store, 1L, now - 1 * day, live)                                  // fresh
            // The alert path, not the screen: its detail is kept for
            // [LedgerRetention.DETAIL_DAYS] only, while its heartbeat row lives
            // to [LedgerRetention.HEARTBEAT_DAYS]. The screen's two floors are
            // now equal, so it cannot show this split.
            seed(store, 2L, now - (LedgerRetention.DETAIL_DAYS + 5) * day, live, consumer = "hypo_alert")
            LedgerRetention.run(store.writableDatabase, now)

            assertEquals(
                "the stale point survived — the ledger still grows without bound",
                1, count(store, "forecast_points"),
            )
            assertEquals(
                "the heartbeat was pruned with its detail; discipline #6 reads that row",
                2, count(store, "forecast_runs"),
            )
        }
        context.deleteDatabase(name)
    }

    /** And the heartbeat is not immortal either — just an order of magnitude longer. */
    @Test
    fun `the heartbeat has its own, longer floor`() {
        val name = "ledger-heartbeat.sqlite"
        context.deleteDatabase(name)
        SqliteCollectorStore(context, name).use { store ->
            seed(store, 1L, now - 1 * day, "live-tag")
            seed(store, 2L, now - (LedgerRetention.HEARTBEAT_DAYS + 5) * day, "live-tag")
            LedgerRetention.run(store.writableDatabase, now)
            assertEquals("an ancient pass row survived its floor", 1, count(store, "forecast_runs"))
        }
        context.deleteDatabase(name)
        // THE INVARIANT, and it is not decorative: `forecast_points` reaches
        // the reader THROUGH its run, so a run pruned while its points are
        // still kept orphans them — the long-press card goes blank while the
        // data is still on disk. A first attempt at 30 days broke exactly this
        // and the test caught it.
        assertTrue(
            "the heartbeat must outlive every detail it indexes, or points are orphaned",
            LedgerRetention.HEARTBEAT_DAYS >= LedgerRetention.SCREEN_DETAIL_DAYS &&
                LedgerRetention.HEARTBEAT_DAYS >= LedgerRetention.DETAIL_DAYS,
        )
    }
}
