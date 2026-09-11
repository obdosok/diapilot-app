package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.twin.Corridor
import com.diapilot.core.twin.ForecastHealth
import com.diapilot.core.twin.ForecastResult
import com.diapilot.core.twin.PredictedPoint
import com.diapilot.core.twin.Regime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * EVERY PASS LEAVES A HEARTBEAT; ONLY THE VISIBLE LINE LEAVES DETAIL.
 *
 * The two halves of the ledger answer different questions and cost different
 * amounts. One row per pass is what says the app was LOOKING — discipline #6,
 * and readings cannot substitute for it because they back-fill. The six points
 * behind that row are ten times the volume and are read back only for a line a
 * person can actually see: the acceptance test («does a replay reproduce what
 * the device ran») and «what did the model believe at that moment».
 *
 * `watch` and `widget` are shorter-horizon slices of the same forecast, so
 * their points answer nothing the screen's do not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerScopeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = 1_787_000_000_000L

    private fun result() = ForecastResult(
        points = (0..180 step 15).map {
            PredictedPoint(now + it * 60_000L, 7.0, 6.0, 8.0)
        },
        regime = Regime.QUIET,
        corridor = Corridor(1.0, 0.1),
        momentumUsed = false,
        health = ForecastHealth.TRUSTED,
        healthReasons = emptyList(),
        modelVersion = "test",
    )

    private fun count(store: SqliteCollectorStore, sql: String): Int =
        store.readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    @Test
    fun `the screen and the alert keep detail, the watch and widget do not`() {
        val name = "ledger-scope.sqlite"
        context.deleteDatabase(name)
        SqliteCollectorStore(context, name).use { store ->
            val db = store.writableDatabase
            listOf("main", "hypo_alert", "watch", "widget").forEachIndexed { i, consumer ->
                ForecastLedger.record(
                    db, result(), now + i * 1_000L, 7.0, now, consumer, "hash-$i",
                    applied = "20/75/145 isf=1.800",
                )
            }
            // TWO, not four: `watch` and `widget` are no
            // longer recorded at all. Their rows had one consumer — the
            // back-fill proof in the ISF reader — and it was removed at the
            // same time, because for a retrospective measurement the current
            // calibrated readings are the evidence and a missing stretch shows
            // in their timestamps.
            assertEquals(
                "a consumer nobody reads is being recorded again",
                2, count(store, "SELECT COUNT(*) FROM forecast_runs"),
            )
            assertEquals(
                "the alert path lost its heartbeat — discipline #6 reads that row",
                1,
                count(store, "SELECT COUNT(*) FROM forecast_runs WHERE consumer='hypo_alert'"),
            )
            assertEquals(
                "detail was stored for a consumer nobody looks at",
                2,
                count(
                    store,
                    "SELECT COUNT(DISTINCT run_id) FROM forecast_points",
                ),
            )
            assertTrue(
                "the screen's own detail was dropped",
                count(
                    store,
                    "SELECT COUNT(*) FROM forecast_points p JOIN forecast_runs r ON r.id=p.run_id " +
                        "WHERE r.consumer='main'",
                ) > 0,
            )
        }
        context.deleteDatabase(name)
    }

    /**
     * ONE STORED RUN PER READING — the screen forecasts far more often than its
     * input changes. A live measurement over two weeks showed roughly one
     * stored forecast every 3.5 minutes.
     *
     * The alert path is deliberately exempt: its rows are the only record of
     * whether the app was watching when it failed to warn, and thinning them
     * makes a hole ambiguous at the resolution the question is asked.
     */
    @Test
    fun `the screen is sampled, the alert path is not`() {
        val name = "ledger-sample.sqlite"
        context.deleteDatabase(name)
        SqliteCollectorStore(context, name).use { store ->
            val db = store.writableDatabase
            listOf(0L, 90_000L, 180_000L).forEachIndexed { i, offset ->
                ForecastLedger.record(
                    db, result(), now + offset, 7.0, now, "main", "m$i", applied = null,
                )
                ForecastLedger.record(
                    db, result(), now + offset, 7.0, now, "hypo_alert", "a$i", applied = null,
                )
            }
            assertEquals(
                "three screen passes 90 s apart stored more than one forecast",
                1, count(store, "SELECT COUNT(*) FROM forecast_runs WHERE consumer='main'"),
            )
            assertEquals(
                "the alert path was thinned — a blind hole becomes ambiguous",
                3, count(store, "SELECT COUNT(*) FROM forecast_runs WHERE consumer='hypo_alert'"),
            )
            // And past the spacing the screen records again.
            ForecastLedger.record(
                db, result(), now + ForecastLedger.SCREEN_SAMPLE_MS + 60_000, 7.0, now,
                "main", "later", applied = null,
            )
            assertEquals(
                "the screen stopped recording altogether",
                2, count(store, "SELECT COUNT(*) FROM forecast_runs WHERE consumer='main'"),
            )
        }
        context.deleteDatabase(name)
    }

    /**
     * The applied curve travels with the run. Without it the ledger says WHICH
     * ALGORITHM ran and not what it believed — and on a real device those have
     * come apart: M-132 found it configured for one hand-set shape and ISF
     * while applying a different, measured pair.
     */
    @Test
    fun `the run records what the model was applying`() {
        val name = "ledger-applied.sqlite"
        context.deleteDatabase(name)
        SqliteCollectorStore(context, name).use { store ->
            ForecastLedger.record(
                store.writableDatabase, result(), now, 7.0, now, "main", "h",
                applied = "20/75/145 isf=1.800",
            )
            val applied = store.readableDatabase
                .rawQuery("SELECT applied FROM forecast_runs LIMIT 1", null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
            assertEquals(
                "the coefficients behind this line are not recoverable from the ledger",
                "20/75/145 isf=1.800", applied,
            )
        }
        context.deleteDatabase(name)
    }
}
