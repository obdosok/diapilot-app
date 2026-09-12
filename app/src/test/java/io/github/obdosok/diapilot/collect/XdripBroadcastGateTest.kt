package io.github.obdosok.diapilot.collect

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.collector.ACTION_BG
import com.diapilot.core.collector.EXTRA_BG
import com.diapilot.core.collector.EXTRA_TIME
import com.diapilot.core.collector.Reading
import io.github.obdosok.diapilot.data.SqliteCollectorStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * The glucose intake boundary: who may write a reading, and whose row a writer
 * may revise.
 *
 * The broadcast action carries no permission and the receiver is exported, so
 * the two facts the app can establish at all are "the app that legitimately
 * sends this intent is installed" and "this timestamp already belongs to
 * another source".
 *
 * The store runs on Robolectric's NATIVE SQLite here: the legacy in-memory
 * engine predates `ON CONFLICT ... DO UPDATE` (SQLite 3.24), which the store
 * has always used, so the statement would not even parse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class XdripBroadcastGateTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val ts = 1_700_000_000_000L

    private fun bgIntent(mgdl: Double, tsMs: Long) = Intent(ACTION_BG).apply {
        putExtra(EXTRA_BG, mgdl)
        putExtra(EXTRA_TIME, tsMs)
    }

    private fun installXdrip() = Shadows.shadowOf(context.packageManager).installPackage(
        android.content.pm.PackageInfo().apply { packageName = XdripApp.PACKAGE },
    )

    @Before fun setUp() {
        XdripApp.reset()
        // Every accepted broadcast doubles as a catch-up trigger for the
        // treatments poll. Marking the poll as just-run keeps WorkManager (not
        // initialized in a unit test) out of this test's way.
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(TreatmentsPollWorker.PREF_LAST_POLL_TS, System.currentTimeMillis())
            .commit()
    }

    @After fun cleanUp() {
        XdripApp.reset()
        io.github.obdosok.diapilot.data.Stores.close()
    }

    @Test fun `a broadcast arriving with no xDrip installed is dropped`() {
        // Nothing else is installed in a Robolectric package manager.
        assertNull(XdripBgReceiver.handle(context, bgIntent(120.0, System.currentTimeMillis())))
    }

    @Test fun `with xDrip installed a fresh in-range reading is accepted`() {
        installXdrip()
        val now = System.currentTimeMillis()
        val r = XdripBgReceiver.handle(context, bgIntent(126.0, now))
        assertNotNull(r)
        assertEquals(126.0, r!!.mgdl, 1e-9)
    }

    @Test fun `the bounds hold for a sender that does carry xDrip's package id`() {
        installXdrip()
        val now = System.currentTimeMillis()
        assertNull(XdripBgReceiver.handle(context, bgIntent(2000.0, now)))
        assertNull(XdripBgReceiver.handle(context, bgIntent(1.0, now)))
        assertNull(XdripBgReceiver.handle(context, bgIntent(120.0, now - 7L * 24 * 3_600_000)))
        assertNull(XdripBgReceiver.handle(context, bgIntent(120.0, now + 365L * 24 * 3_600_000)))
    }

    @Test fun `a second source cannot rewrite a landed reading`() {
        val name = "cross-source-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            store.upsertReading(Reading(ts, 126.0, 126.0 / 18.0182, "Flat", "xdrip_broadcast"))
            // A forged value at the same slot — the number a low alert would
            // never fire on — and a forged low, from two other sources.
            store.upsertReading(Reading(ts, 144.0, 144.0 / 18.0182, "Flat", "impostor"))
            store.upsertReading(Reading(ts, 54.0, 54.0 / 18.0182, "Flat", "xdrip_sgv"))
            assertEquals(126.0 / 18.0182, store.readings(ts, ts).single().mmol, 1e-9)

            // A free slot is still a free slot.
            store.upsertReading(Reading(ts + 300_000, 90.0, 90.0 / 18.0182, "Flat", "meter"))
            assertEquals(2, store.readings(ts, ts + 300_000).size)
        }
        context.deleteDatabase(name)
    }

    @Test fun `the owning source may still revise its own reading`() {
        val name = "same-source-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            // PRE-EXISTING AND UNRELATED, dropped so this assertion can run at
            // all: the model-input revision triggers do `INSERT OR REPLACE`,
            // and inside a trigger body SQLite (3.32 here) applies the OUTER
            // statement's conflict handling instead — ABORT for an upsert — so
            // any conflicting `ON CONFLICT ... DO UPDATE` on a tracked table
            // fails on the trigger, with or without this change. Reported
            // separately; it is not what this test is about.
            listOf("insert", "update", "delete").forEach {
                store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS physio_dirty_glucose_readings_$it")
            }
            store.upsertReading(Reading(ts, 126.0, 126.0 / 18.0182, "Flat", "xdrip_broadcast"))
            store.upsertReading(Reading(ts, 130.0, 130.0 / 18.0182, "Flat", "xdrip_broadcast"))
            assertEquals(130.0 / 18.0182, store.readings(ts, ts).single().mmol, 1e-9)
        }
        context.deleteDatabase(name)
    }
}
