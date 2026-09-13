package io.github.obdosok.diapilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.collector.InsulinEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * The insulin intake boundary: whose row a writer may revise.
 *
 * A dose is keyed by its timestamp, and until now any writer that knew the
 * timestamp could replace the units — the loopback poll, the watch socket, an
 * import. The rule under test is the one glucose already had: the source that
 * wrote a slot owns it, a repeat from the same source revises it, a write from
 * another source is dropped, and units the user edited by hand outlive both.
 *
 * Native SQLite, for the same reason as [io.github.obdosok.diapilot.collect.XdripBroadcastGateTest]:
 * the legacy engine predates `ON CONFLICT ... DO UPDATE`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class InsulinSourceOwnershipTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val ts = 1_700_000_000_000L

    private fun withStore(label: String, block: (SqliteCollectorStore) -> Unit) {
        // Short names: Robolectric's sandbox path must fit Windows' 260
        // characters (see Stage7SqliteIntegrationTest).
        val name = "ins-$label-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            // PRE-EXISTING AND UNRELATED, dropped so the upsert can conflict at
            // all: the model-input revision triggers do `INSERT OR REPLACE`,
            // and inside a trigger body SQLite 3.32 applies the OUTER
            // statement's conflict handling — ABORT for an upsert. Reported
            // separately; not what this test is about.
            listOf("insert", "update", "delete").forEach {
                store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS physio_dirty_insulin_events_$it")
            }
            block(store)
        }
        context.deleteDatabase(name)
    }

    private fun row(store: SqliteCollectorStore, at: Long = ts): Pair<Double, String?> =
        store.readableDatabase.rawQuery(
            "SELECT units, source FROM insulin_events WHERE ts_ms = ?", arrayOf(at.toString()),
        ).use { c ->
            assertTrue("a row at $at", c.moveToFirst())
            c.getDouble(0) to c.getString(1)
        }

    private fun count(store: SqliteCollectorStore): Int =
        android.database.DatabaseUtils.queryNumEntries(store.readableDatabase, "insulin_events").toInt()

    @Test fun `a re-sync from the same source updates the dose`() = withStore("same") { store ->
        store.upsertInsulin(InsulinEvent(ts, 4.0, "bolus", "xdrip_treatments"))
        // xDrip corrected the treatment; the poll fetches it again.
        store.upsertInsulin(InsulinEvent(ts, 4.5, "bolus", "xdrip_treatments"))
        assertEquals(4.5 to "xdrip_treatments", row(store))
        assertEquals(1, count(store))
    }

    @Test fun `a different source finds the slot taken and is dropped`() = withStore("cross") { store ->
        store.upsertInsulin(InsulinEvent(ts, 4.0, "bolus", "pen_nfc"))
        // The poll, the watch socket and something that is neither, all at
        // the same slot with a dose the fuse would let through.
        store.upsertInsulin(InsulinEvent(ts, 9.0, "bolus", "xdrip_treatments"))
        store.upsertInsulin(InsulinEvent(ts, 9.0, "bolus", "watch"))
        store.upsertInsulin(InsulinEvent(ts, 9.0, "bolus", "impostor"))
        assertEquals(4.0 to "pen_nfc", row(store))
        // A free slot is still a free slot.
        store.upsertInsulin(InsulinEvent(ts + 60_000, 2.0, "bolus", "watch"))
        assertEquals(2.0 to "watch", row(store, ts + 60_000))
        assertEquals(2, count(store))
    }

    @Test fun `a hand edit survives a same-source re-sync and a cross-source write`() = withStore("edit") { store ->
        store.upsertInsulin(InsulinEvent(ts, 4.0, "bolus", "xdrip_treatments"))
        store.setBolusUnits(ts, 3.0)
        store.upsertInsulin(InsulinEvent(ts, 4.0, "bolus", "xdrip_treatments"))
        assertEquals(3.0 to "xdrip_treatments", row(store))
        store.upsertInsulin(InsulinEvent(ts, 8.0, "bolus", "manual"))
        assertEquals(3.0 to "xdrip_treatments", row(store))
    }

    @Test fun `the owner's re-sync leaves a user-set purpose alone`() = withStore("purpose") { store ->
        store.addBolusWithPurpose(ts, 4.0, "correction", "manual")
        store.upsertInsulin(InsulinEvent(ts, 4.0, "bolus", "manual"))
        assertEquals("correction", store.bolusesAll(ts, ts).single().purpose)
    }

    @Test fun `a tombstoned shot stays dead for every source`() = withStore("tomb") { store ->
        store.upsertInsulin(InsulinEvent(ts, 4.0, "bolus", "xdrip_treatments"))
        store.deleteInsulinForever(ts)
        store.upsertInsulin(InsulinEvent(ts, 4.0, "bolus", "xdrip_treatments"))
        store.upsertInsulin(InsulinEvent(ts, 4.0, "bolus", "manual"))
        assertEquals(0, count(store))
    }
}
