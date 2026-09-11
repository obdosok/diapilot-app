package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE FIRST FRAME MAY DRAW THE LAST FORECAST, BUT ONLY WHILE IT IS STILL ABOUT
 * THIS GLUCOSE.
 *
 * `loadLivePreview` runs before the model does, and on a cold process it had no
 * line to draw — a live check showed a few seconds with no forecast
 * line, with the chart window still ending at "now".
 *
 * The freshness bound is the safety argument, not an optimisation: a forecast
 * is a statement about one anchor, and drawn against a glucose that has since
 * moved it would start off the current point and read as the line lying when
 * nothing lied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LastShownPredictionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = 1_787_000_000_000L

    private fun run(store: SqliteCollectorStore, id: Long, anchorTsMs: Long, mmol: Double) {
        val db = store.writableDatabase
        db.execSQL(
            "INSERT OR REPLACE INTO forecast_runs" +
                "(id,created_at_ms,anchor_ts_ms,anchor_mmol,consumer,algo_version,regime,health,input_hash)" +
                " VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(id, anchorTsMs, anchorTsMs, 6.0, "main", "test", null, null, "h$id"),
        )
        db.execSQL(
            "INSERT OR REPLACE INTO forecast_points" +
                "(run_id,horizon_min,target_ts_ms,mmol,lo,hi,lo_mid,hi_mid) VALUES (?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(id, 60, anchorTsMs + 3_600_000, mmol, mmol - 1, mmol + 1, mmol - 0.5, mmol + 0.5),
        )
    }

    private fun <T> withStore(name: String, body: (SqliteCollectorStore) -> T): T {
        context.deleteDatabase(name)
        return SqliteCollectorStore(context, name).use(body).also { context.deleteDatabase(name) }
    }

    @Test
    fun `a forecast for the reading on screen is drawn`() = withStore("lsp-fresh.sqlite") { store ->
        run(store, 1L, now, 7.5)
        val got = ForecastLedger.lastShownPrediction(store.readableDatabase, "main", now)
        assertEquals("the stored line was not found — the first frame stays empty", 1, got.size)
        assertEquals(7.5, got.first().mmol, 1e-9)
        assertEquals(6.5, got.first().lo, 1e-9)
        assertEquals(7.0, got.first().loMid, 1e-9)
    }

    /** The guard itself. Deleting the anchor-age bound must fail this. */
    @Test
    fun `a forecast older than the bound is refused`() = withStore("lsp-stale.sqlite") { store ->
        val stale = now - ForecastLedger.PREVIEW_MAX_ANCHOR_AGE_MS - 60_000
        run(store, 1L, stale, 7.5)
        assertTrue(
            "a stale forecast was drawn against a newer glucose",
            ForecastLedger.lastShownPrediction(store.readableDatabase, "main", now).isEmpty(),
        )
    }

    @Test
    fun `the newest run wins`() = withStore("lsp-newest.sqlite") { store ->
        run(store, 1L, now - 8L * 60_000, 4.0)
        run(store, 2L, now - 60_000, 9.0)
        assertEquals(
            "an older run was preferred over the newest one",
            9.0, ForecastLedger.lastShownPrediction(store.readableDatabase, "main", now).first().mmol, 1e-9,
        )
    }

    /**
     * A run anchored in the FUTURE of the displayed reading is refused too.
     * `hypo_alert` and the widget anchor on their own reading, and a run from a
     * pass that already saw a newer point would draw a line the visible chart
     * does not reach.
     */
    @Test
    fun `another consumer's run is not borrowed`() = withStore("lsp-consumer.sqlite") { store ->
        store.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO forecast_runs" +
                "(id,created_at_ms,anchor_ts_ms,anchor_mmol,consumer,algo_version,regime,health,input_hash)" +
                " VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(7L, now, now, 6.0, "hypo_alert", "test", null, null, "h7"),
        )
        store.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO forecast_points" +
                "(run_id,horizon_min,target_ts_ms,mmol,lo,hi,lo_mid,hi_mid) VALUES (?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(7L, 60, now + 3_600_000, 3.2, 2.2, 4.2, 2.7, 3.7),
        )
        assertTrue(
            "the screen borrowed the alert path's run",
            ForecastLedger.lastShownPrediction(store.readableDatabase, "main", now).isEmpty(),
        )
    }
}
