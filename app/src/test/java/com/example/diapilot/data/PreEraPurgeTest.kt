package com.example.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.api.FoodEra
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreEraPurgeTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val day = 86_400_000L

    /** The user's explicit choice; the purge boundary exists only after it. */
    private fun chooseEra(): Long {
        TestFoodEra.install()
        return requireNotNull(PreEraPurge.boundary(context))
    }

    private fun seed(store: SqliteCollectorStore, era: Long) {
        val db = store.writableDatabase
        // Two of each, one side of the era boundary each.
        db.execSQL("INSERT INTO glucose_readings(ts_ms,mgdl,mmol,source) VALUES(?,100.0,5.5,'test')", arrayOf(era - day))
        db.execSQL("INSERT INTO glucose_readings(ts_ms,mgdl,mmol,source) VALUES(?,100.0,5.5,'test')", arrayOf(era + day))
        db.execSQL("INSERT INTO insulin_events(ts_ms,units,insulin_type,source,purpose,user_edited) VALUES(?,2.0,'bolus','test','коррекция',0)", arrayOf(era - day))
        db.execSQL("INSERT INTO insulin_events(ts_ms,units,insulin_type,source,purpose,user_edited) VALUES(?,2.0,'bolus','test','коррекция',0)", arrayOf(era + day))
        db.execSQL("INSERT INTO heart_rate(ts_ms,bpm) VALUES(?,70.0)", arrayOf(era - day))
        db.execSQL("INSERT INTO steps(start_ms,end_ms,count) VALUES(?,?,600)", arrayOf(era - day, era - day + 600_000))
        db.execSQL("INSERT INTO sleep_sessions(start_ms,end_ms) VALUES(?,?)", arrayOf(era - day, era - day + 8 * 3_600_000))
    }

    @Test fun `a first-run default era offers nothing to purge`() {
        // Fresh install: the era is the first-run date, chosen by nobody.
        // Older history (an import, a restored backup) must not become deletable.
        assertEquals(false, FoodEraSettings.isUserSet(context))
        assertNull(PreEraPurge.boundary(context))
    }

    @Test fun `the purge boundary is exactly the era start the user chose`() {
        val chosen = LocalDate.of(2025, 2, 1)
        FoodEraSettings.setByUser(context, chosen, ZoneOffset.UTC)
        assertEquals(FoodEra(chosen, ZoneOffset.UTC).startMs, PreEraPurge.boundary(context))
    }

    @Test fun `purge removes only rows before the era and keeps the rest`() {
        val era = chooseEra()
        SqliteCollectorStore(context, "purge-basic-${System.nanoTime()}.sqlite").use { store ->
            seed(store, era)
            val preview = PreEraPurge.preview(store, era)
            assertEquals(5, preview.total)

            val result = PreEraPurge.purge(store, era)
            assertEquals(5, result.total)

            val db = store.readableDatabase
            fun count(sql: String) = db.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
            assertEquals(1, count("SELECT COUNT(*) FROM glucose_readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM insulin_events"))
            assertEquals(0, count("SELECT COUNT(*) FROM heart_rate"))
            assertEquals(0, count("SELECT COUNT(*) FROM steps"))
            assertEquals(0, count("SELECT COUNT(*) FROM sleep_sessions"))
            // Everything that survives must be on the era side of the line.
            assertEquals(0, count("SELECT COUNT(*) FROM glucose_readings WHERE ts_ms < $era"))
            assertEquals(0, count("SELECT COUNT(*) FROM insulin_events WHERE ts_ms < $era"))
        }
    }

    @Test fun `purge is idempotent and leaves nothing to remove`() {
        val era = chooseEra()
        SqliteCollectorStore(context, "purge-idem-${System.nanoTime()}.sqlite").use { store ->
            seed(store, era)
            PreEraPurge.purge(store, era)
            assertEquals(0, PreEraPurge.preview(store, era).total)
            assertEquals(0, PreEraPurge.purge(store, era).total)
        }
    }

    @Test fun `pen doses survive because they de-duplicate NFC reads, not analysis`() {
        val era = chooseEra()
        SqliteCollectorStore(context, "purge-pen-${System.nanoTime()}.sqlite").use { store ->
            store.writableDatabase.execSQL(
                "INSERT INTO pen_doses(uuid,ts_ms) VALUES('old-dose',?)", arrayOf(era - day),
            )
            PreEraPurge.purge(store, era)
            val left = store.readableDatabase
                .rawQuery("SELECT COUNT(*) FROM pen_doses", null)
                .use { it.moveToFirst(); it.getInt(0) }
            assertEquals(1, left)
        }
    }

    @Test fun `carb evidence of a purged annotation goes with its owner`() {
        val era = chooseEra()
        SqliteCollectorStore(context, "purge-evidence-${System.nanoTime()}.sqlite").use { store ->
            val db = store.writableDatabase
            db.execSQL(
                "INSERT INTO annotations(ts_ms,kind,content,est_carbs) VALUES(?,'food','старое',30.0)",
                arrayOf(era - day),
            )
            val id = db.rawQuery("SELECT id FROM annotations", null)
                .use { it.moveToFirst(); it.getLong(0) }
            // Fill exactly the columns this schema version REQUIRES, so the test
            // pins the orphan rule rather than a hand-copied column list that
            // rots on the next migration.
            data class Col(val name: String, val type: String, val required: Boolean)
            val columns = db.rawQuery("PRAGMA table_info(carb_evidence_v1)", null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Col(c.getString(1), c.getString(2).uppercase(), c.getInt(3) == 1 && c.isNull(4)))
                    }
                }
            }
            val needed = columns.filter { it.required || it.name == "annotation_id" }
            db.execSQL(
                "INSERT INTO carb_evidence_v1(${needed.joinToString(",") { it.name }}) " +
                    "VALUES(${needed.joinToString(",") { "?" }})",
                needed.map { c ->
                    when {
                        c.name == "annotation_id" -> id
                        c.name.endsWith("_ms") -> era - day
                        c.type.startsWith("TEXT") -> "test-${c.name}"
                        c.type.startsWith("REAL") -> 0.0
                        else -> 0L
                    }
                }.toTypedArray(),
            )
            PreEraPurge.purge(store, era)
            val left = store.readableDatabase
                .rawQuery("SELECT COUNT(*) FROM carb_evidence_v1", null)
                .use { it.moveToFirst(); it.getInt(0) }
            assertEquals(0, left)
            assertTrue(PreEraPurge.preview(store, era).total == 0)
        }
    }
}
