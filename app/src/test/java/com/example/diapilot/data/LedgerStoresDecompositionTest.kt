package com.example.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.twin.ForecastHealth
import com.diapilot.core.twin.Corridor
import com.diapilot.core.twin.ForecastResult
import com.diapilot.core.twin.Regime
import com.diapilot.core.twin.PredictedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A STORED RUN MUST SAY WHAT IT WAS MADE OF.
 *
 * A live check once showed a forecast line well above what a mass-balance
 * estimate gave. There was nothing to answer with from the stored data:
 * `forecast_points` held only one `mmol`, while the engine computed four
 * terms and discarded them. A long reconstruction produced a number but
 * never named a reason — the question stayed open not because it was hard,
 * but because there was no instrument to answer it.
 *
 * The test holds two things, and both would break silently:
 *  - the columns are WRITTEN, not just declared by the schema;
 *  - `INSERT` names columns explicitly. An unnamed `VALUES (?,?,...)`
 *    lines up by position only until the next `ALTER TABLE`, after which
 *    values silently slide onto neighbouring columns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerStoresDecompositionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun <T> withStore(name: String, body: (SqliteCollectorStore) -> T): T {
        context.deleteDatabase(name)
        return SqliteCollectorStore(context, name).use(body).also { context.deleteDatabase(name) }
    }

    @Test
    fun `the four terms survive the round trip, and a non-physio point stays null`() =
        withStore("ledger-decomp.sqlite") { store ->
            val anchor = 1_787_000_000_000L
            val pt = { min: Int, food: Double?, ins: Double? ->
                PredictedPoint(
                    tsMs = anchor + min * 60_000L, mmol = 8.0 + min / 60.0,
                    lo = 7.0, hi = 9.0, loMid = 7.5, hiMid = 8.5,
                    foodDelta = food, insulinDelta = ins,
                    driftDelta = food?.let { 0.25 }, backgroundDelta = food?.let { 0.1 },
                )
            }
            ForecastLedger.record(
                db = store.writableDatabase,
                result = ForecastResult(
                    points = listOf(pt(30, 3.13, -1.87), pt(60, 5.21, -2.30), pt(90, null, null)),
                    regime = Regime.POST_MEAL,
                    corridor = Corridor(1.0, 0.1),
                    momentumUsed = false,
                    health = ForecastHealth.TRUSTED,
                    healthReasons = emptyList(),
                    modelVersion = "decomp-test",
                ),
                anchorTsMs = anchor, anchorMmol = 8.0, nowMs = anchor,
                consumer = "main", inputHash = "decomp-test",
            )
            val got = HashMap<Int, List<Double?>>()
            store.writableDatabase.rawQuery(
                "SELECT horizon_min,mmol,food_delta,insulin_delta,drift_delta,background_delta " +
                    "FROM forecast_points ORDER BY horizon_min", null,
            ).use { cur ->
                while (cur.moveToNext()) {
                    got[cur.getInt(0)] = (1..5).map { i ->
                        if (cur.isNull(i)) null else cur.getDouble(i)
                    }
                }
            }
            assertTrue("the run was not recorded at all: $got", got.size >= 2)

            val h30 = requireNotNull(got[30]) { "horizon 30 was not recorded" }
            // VALUES, NOT JUST NON-NULL: an INSERT that slid onto the wrong
            // column would still give non-null in all four and pass an
            // existence check.
            assertEquals("mmol", 8.5, h30[0]!!, 1e-9)
            assertEquals("food", 3.13, h30[1]!!, 1e-9)
            assertEquals("insulin", -1.87, h30[2]!!, 1e-9)
            assertEquals("drift", 0.25, h30[3]!!, 1e-9)
            assertEquals("background", 0.1, h30[4]!!, 1e-9)

            val h60 = requireNotNull(got[60])
            assertEquals("food at 60", 5.21, h60[1]!!, 1e-9)
            assertEquals("insulin at 60", -2.30, h60[2]!!, 1e-9)

            // A point not from the physio engine must stay empty, not zero:
            // zero reads as "the term was computed and equals zero".
            val h90 = requireNotNull(got[90])
            assertNotNull("mmol is present for it", h90[0])
            assertNull("food must be NULL, not 0.0", h90[1])
            assertNull("insulin must be NULL", h90[2])
        }
}
