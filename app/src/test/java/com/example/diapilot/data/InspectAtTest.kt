package com.example.diapilot.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LONG-PRESS ON THE CHART: what the app drew there, against what happened.
 *
 * The properties that make the answer trustworthy rather than merely present:
 * the run is chosen by its ANCHOR (the moment the line was drawn FROM, which
 * is what a finger points at), a point with no scored fact says so instead of
 * inventing one, and a moment with no stored run returns null rather than the
 * nearest run from an hour away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InspectAtTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = 1_787_000_000_000L

    private fun seed(
        store: SqliteCollectorStore,
        id: Long,
        anchorMs: Long,
        applied: String?,
        tag: String = "forecast-v11-kotlin-shadow-13",
    ) {
        val db = store.writableDatabase
        db.execSQL(
            "INSERT OR REPLACE INTO forecast_runs" +
                "(id,created_at_ms,anchor_ts_ms,anchor_mmol,consumer,algo_version,regime,health,input_hash,applied)" +
                " VALUES (?,?,?,?,'main',?,'QUIET','TRUSTED',?,?)",
            arrayOf<Any?>(id, anchorMs, anchorMs, 6.4, tag, "h$id", applied),
        )
        listOf(15 to 6.8, 60 to 8.1).forEach { (h, v) ->
            db.execSQL(
                "INSERT OR REPLACE INTO forecast_points" +
                    "(run_id,horizon_min,target_ts_ms,mmol,lo,hi,lo_mid,hi_mid) VALUES (?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id, h, anchorMs + h * 60_000L, v, v - 1, v + 1, v - 0.5, v + 0.5),
            )
        }
        // THE FACT IS A READING NOW, not a stored score. Only the
        // 15-minute target gets one, so the 60-minute horizon must report «no
        // fact» rather than reaching for the nearest reading at any distance.
        store.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO glucose_readings(ts_ms,mgdl,mmol,trend,source) VALUES (?,?,?,?,?)",
            arrayOf<Any?>(anchorMs + 900_000L, 7.3 * 18.0, 7.3, null, "libre_ble"),
        )
    }

    private fun <T> withStore(name: String, body: (SqliteCollectorStore) -> T): T {
        context.deleteDatabase(name)
        return SqliteCollectorStore(context, name).use(body).also { context.deleteDatabase(name) }
    }

    @Test
    fun `it answers with the run nearest the pressed moment`() = withStore("insp-near.sqlite") { store ->
        seed(store, 1L, now - 40 * 60_000, "20/75/145 isf=1.800")
        seed(store, 2L, now - 3 * 60_000, "20/75/145 isf=1.800")
        val got = ForecastLedger.inspectAt(store.readableDatabase, now)
        assertNotNull("nothing was found for a moment that has a run", got)
        assertEquals(
            "a run 40 minutes away was preferred over one 3 minutes away",
            now - 3 * 60_000, got!!.anchorTsMs,
        )
        assertEquals("20/75/145 isf=1.800", got.applied)
        assertEquals(2, got.points.size)
    }

    @Test
    fun `an unscored horizon reports no fact rather than inventing one`() =
        withStore("insp-unscored.sqlite") { store ->
            seed(store, 1L, now, null)
            val got = ForecastLedger.inspectAt(store.readableDatabase, now)!!
            assertEquals(7.3, got.points.first { it.horizonMin == 15 }.actualMmol!!, 1e-9)
            assertNull(
                "an unscored horizon claimed a fact",
                got.points.first { it.horizonMin == 60 }.actualMmol,
            )
            assertNull("a run with no recorded coefficients pretended to have them", got.applied)
        }

    /**
     * THE ARM THAT WAS SHOWN WINS, even when the base twin sits closer.
     *
     * For a stretch the ledger holds both at the same anchors, and
     * only one of them was ever on screen. Answering "what did the app draw
     * here" with the other would repeat, in the reading path, exactly the
     * defect the recording path had for a while.
     */
    @Test
    fun `the displayed arm is preferred over the base twin`() = withStore("insp-arm.sqlite") { store ->
        seed(store, 1L, now - 60_000, null, tag = "forecast-v15-rescue-throughput-aggregate")
        seed(store, 2L, now - 8 * 60_000, "20/75/145 isf=1.800")
        assertEquals(
            "the base twin was offered as the line the user saw",
            "forecast-v11-kotlin-shadow-13",
            ForecastLedger.inspectAt(store.readableDatabase, now)!!.algoVersion,
        )
    }

    /**
     * A MEAL LOGGED AFTER THE FACT IS NOT A MODEL ERROR, and the card must not
     * let the number be read as one. A large share of meals have their grams
     * known more than five minutes after the mark; p75 is 46 minutes.
     */
    @Test
    fun `a meal whose carbs arrived after the anchor is flagged`() = withStore("insp-blind.sqlite") { store ->
        seed(store, 1L, now, "20/75/145 isf=1.800")
        val id = store.addAnnotation(
            com.diapilot.core.collector.Annotation(now + 10 * 60_000, "food", "смузи", estCarbs = 40.0),
        )
        // Grams became known 45 minutes after the forecast was drawn.
        store.writableDatabase.execSQL(
            "UPDATE annotations SET carbs_known_at_ms=? WHERE id=?",
            arrayOf<Any?>(now + 55 * 60_000, id),
        )
        val got = ForecastLedger.inspectAt(store.readableDatabase, now)!!
        assertEquals("the blind meal was not noticed", 1, got.blindMeals)

        // And one the forecast COULD see is not flagged.
        store.writableDatabase.execSQL(
            "UPDATE annotations SET carbs_known_at_ms=? WHERE id=?",
            arrayOf<Any?>(now - 60_000, id),
        )
        assertEquals(
            "a meal the run could see was reported as blind",
            0, ForecastLedger.inspectAt(store.readableDatabase, now)!!.blindMeals,
        )
    }

    /** Outside the tolerance there is no answer — not a stale one. */
    @Test
    fun `a moment with no run nearby returns nothing`() = withStore("insp-far.sqlite") { store ->
        seed(store, 1L, now - 3 * 3_600_000, "x")
        assertNull(
            "a run three hours away was offered as «the forecast here»",
            ForecastLedger.inspectAt(store.readableDatabase, now),
        )
    }
}
