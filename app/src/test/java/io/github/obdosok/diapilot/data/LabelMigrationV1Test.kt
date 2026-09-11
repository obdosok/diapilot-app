package io.github.obdosok.diapilot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Schema v50: stored Russian tokens -> language-neutral keys.
 *
 * The fixture is built in the test: a database in the OLD format (v49, Russian
 * tokens written by raw SQL, past every write path that would canonicalize
 * them), then opened by the current store exactly as the phone opens it after
 * an app update or a backup restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelMigrationV1Test {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun rawOpen(name: String): SQLiteDatabase =
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun rows(db: SQLiteDatabase, sql: String): List<String> =
        db.rawQuery(sql, null).use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "∅" else c.getString(it) })
            }
        }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

    private val OLD_ANALYSIS = "Гречка с курицей\nСОСТАВ: гречка = 30 угл · 150 порц\nГИ: 50\nУГЛЕВОДЫ: 30–35 г"

    /** A v49 database holding the Russian tokens older builds wrote. */
    private fun buildOldFormat(name: String) {
        context.deleteDatabase(name)
        SqliteCollectorStore(context, name).use { store ->
            val db = store.writableDatabase
            db.execSQL("INSERT INTO glucose_readings(ts_ms,mgdl,mmol,trend,source) VALUES(1000,108,6.0,'Flat','test')")
            listOf(
                1L to "коррекция", 2L to "на еду", 3L to "докол", 4L to "воздух",
                5L to " Коррекция ", 6L to "что-то своё", 7L to null, 8L to "correction",
            ).forEach { (ts, p) ->
                db.execSQL(
                    "INSERT INTO insulin_events(ts_ms,units,insulin_type,source,purpose,user_edited) VALUES(?,2.0,'bolus','test',?,0)",
                    arrayOf<Any?>(ts, p),
                )
            }
            listOf(
                Triple(10L, "text", "прогулка · 40 мин"),
                Triple(11L, "text", "недосып"),
                Triple(12L, "text", "укол в живот"),
                Triple(13L, "text", "фото"),
                Triple(14L, "text", "утренняя заря"),
                Triple(15L, "food", "декстроза ×2"),
                Triple(16L, "food", "гречка с курицей"),
                Triple(17L, "text", "Прогулка по парку"),
                Triple(18L, "text", "псилиум · 5 г"),
                Triple(19L, "text", "коррекция"),
            ).forEach { (ts, kind, content) ->
                db.execSQL(
                    "INSERT INTO annotations(ts_ms,kind,content,analysis,est_carbs) VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(ts, kind, content, if (ts == 16L) OLD_ANALYSIS else null, if (kind == "food") 10.0 else null),
                )
            }
            listOf("утренняя заря", "продолжение еды", "спорт/адреналин", "гречка с курицей", "декстроза ×2", "не знаю", "unknown")
                .forEach { db.execSQL("INSERT INTO meal_labels(name,use_count) VALUES(?,1)", arrayOf(it)) }
            fun labelId(n: String) = db.rawQuery("SELECT id FROM meal_labels WHERE name=?", arrayOf(n)).use { it.moveToFirst(); it.getLong(0) }
            listOf(100L to "утренняя заря", 200L to "продолжение еды", 300L to "гречка с курицей", 400L to "декстроза ×2", 500L to "не знаю")
                .forEach { (onset, label) ->
                    db.execSQL(
                        "INSERT INTO meal_events(onset_ms,peak_ms,pre_bg,peak_bg,rise,time_to_peak_min,bolus_units,kind,label_id) VALUES(?,?,5.0,8.0,3.0,40.0,NULL,'unannounced',?)",
                        arrayOf<Any?>(onset, onset + 1, labelId(label)),
                    )
                }
            db.version = 49
        }
    }

    private data class Snapshot(
        val triggers: List<String>,
        val revisions: List<String>,
        val invalidations: Long,
        val counts: Map<String, Long>,
    )

    private fun snapshot(db: SQLiteDatabase) = Snapshot(
        rows(db, "SELECT name, sql FROM sqlite_master WHERE type='trigger' ORDER BY name"),
        rows(db, "SELECT source_table, revision FROM physio_model_input_revisions_v1 ORDER BY source_table"),
        count(db, "physio_closed_input_invalidations_v1"),
        listOf("insulin_events", "annotations", "meal_labels", "meal_events", "glucose_readings").associateWith { count(db, it) },
    )

    @Test fun `upgrade converts every stored token and nothing else`() {
        val name = "labels-upgrade-${System.nanoTime()}.sqlite"
        buildOldFormat(name)
        val before = rawOpen(name).use { snapshot(it) }
        assertTrue("the fixture must carry the physio triggers", before.triggers.size > 10)

        SqliteCollectorStore(context, name).use { store ->
            val db = store.readableDatabase
            assertEquals(50, db.version)
            assertEquals(
                listOf(
                    "1|correction", "2|meal_bolus", "3|top_up", "4|prime", "5|correction",
                    "6|что-то своё", "7|∅", "8|correction",
                ),
                rows(db, "SELECT ts_ms, purpose FROM insulin_events ORDER BY ts_ms"),
            )
            assertEquals(
                listOf(
                    "10|walk · 40 min", "11|sleep_debt", "12|injection_belly", "13|photo", "14|dawn",
                    "15|dextrose ×2", "16|гречка с курицей", "17|Прогулка по парку", "18|псилиум · 5 г", "19|correction",
                ),
                rows(db, "SELECT ts_ms, content FROM annotations ORDER BY ts_ms"),
            )
            // Stored analyses are never rewritten: the parsers read the old markers.
            assertEquals(listOf(OLD_ANALYSIS), rows(db, "SELECT analysis FROM annotations WHERE ts_ms=16"))
            assertEquals(30.0, com.diapilot.core.analysis.parseComponents(OLD_ANALYSIS).single().unitGrams, 1e-9)
            assertEquals(
                listOf("dawn", "continuation", "sport_adrenaline", "гречка с курицей", "dextrose ×2", "не знаю", "unknown"),
                rows(db, "SELECT name FROM meal_labels ORDER BY id"),
            )
            // Every meal keeps its label; the one whose key row already existed is repointed.
            assertEquals(
                listOf("100|dawn", "200|continuation", "300|гречка с курицей", "400|dextrose ×2", "500|unknown"),
                rows(db, "SELECT e.onset_ms, l.name FROM meal_events e JOIN meal_labels l ON l.id = e.label_id ORDER BY e.onset_ms"),
            )
            val after = snapshot(db)
            assertEquals("no row is ever deleted", before.counts, after.counts)
            assertEquals("the triggers come back byte for byte", before.triggers, after.triggers)
            assertEquals("a spelling change is not a model-input change", before.revisions, after.revisions)
            assertEquals(before.invalidations, after.invalidations)
        }
        context.deleteDatabase(name)
    }

    @Test fun `the migration is idempotent`() {
        val name = "labels-idem-${System.nanoTime()}.sqlite"
        buildOldFormat(name)
        SqliteCollectorStore(context, name).use { store ->
            val db = store.writableDatabase
            val dump = { rows(db, "SELECT ts_ms, content, analysis FROM annotations ORDER BY ts_ms") +
                rows(db, "SELECT ts_ms, purpose FROM insulin_events ORDER BY ts_ms") +
                rows(db, "SELECT id, name, use_count FROM meal_labels ORDER BY id") +
                rows(db, "SELECT onset_ms, label_id FROM meal_events ORDER BY onset_ms") }
            val first = dump()
            val snap = snapshot(db)
            assertEquals(0, LabelMigrationV1.migrate(db).total)
            assertEquals(0, LabelMigrationV1.migrate(db).total)
            assertEquals(first, dump())
            assertEquals(snap, snapshot(db))
        }
        // And a second open (onOpen runs the check again) changes nothing either.
        SqliteCollectorStore(context, name).use { store ->
            assertEquals(0, LabelMigrationV1.migrate(store.writableDatabase).total)
        }
        context.deleteDatabase(name)
    }

    @Test fun `a direct run reports what it changed and a failed run leaves nothing behind`() {
        val name = "labels-direct-${System.nanoTime()}.sqlite"
        buildOldFormat(name)
        rawOpen(name).use { db ->
            val r = LabelMigrationV1.migrate(db)
            assertEquals(4 + 1, r.purposes)            // four tokens + the spaced variant
            assertEquals(7, r.notes)                     // the tag-shaped notes only
            assertEquals(4, r.labelsRenamed)             // three system labels + the rescue label
            assertEquals(1, r.mealsRepointed)            // the Russian unknown label -> the existing "unknown" row
        }
        context.deleteDatabase(name)
    }

    @Test fun `the write paths store keys whatever language they are given`() {
        val name = "labels-writes-${System.nanoTime()}.sqlite"
        context.deleteDatabase(name)
        SqliteCollectorStore(context, name).use { store ->
            val a = store.addAnnotation(com.diapilot.core.collector.Annotation(1_000, "text", "прогулка · 30 мин"))
            val b = store.addAnnotation(com.diapilot.core.collector.Annotation(2_000, "text", "Sleep debt"))
            val c = store.addAnnotation(com.diapilot.core.collector.Annotation(3_000, "food", "гречка"))
            store.updateAnnotation(c, 3_000, "декстроза ×3", null)
            val db = store.writableDatabase
            listOf(4_000L, 5_000L).forEach { ts ->
                db.execSQL("INSERT INTO insulin_events(ts_ms,units,insulin_type,source) VALUES(?,2.0,'bolus','manual')", arrayOf<Any?>(ts))
            }
            store.setBolusPurpose(4_000, "на еду")
            store.setBolusPurpose(5_000, "for food")
            store.getOrCreateLabel("утренняя заря")
            store.getOrCreateLabel("dawn")
            assertEquals(
                listOf("$a|walk · 30 min", "$b|sleep_debt", "$c|dextrose ×3"),
                rows(db, "SELECT id, content FROM annotations ORDER BY id"),
            )
            assertEquals(listOf("meal_bolus", "meal_bolus"), rows(db, "SELECT purpose FROM insulin_events ORDER BY ts_ms"))
            assertEquals(listOf("dawn|2"), rows(db, "SELECT name, use_count FROM meal_labels"))
            // A context tag in any language still becomes a structured exposure.
            store.addAnnotation(com.diapilot.core.collector.Annotation(6_000, "text", "укол в живот"))
            assertTrue(rows(db, "SELECT source_type FROM physio_context_exposures_v1").contains("injection_site"))
        }
        context.deleteDatabase(name)
    }

    @Test fun `restoring an old backup runs the same migration`() {
        val name = "labels-backup-${System.nanoTime()}.sqlite"
        buildOldFormat(name)
        val backup = File(context.cacheDir, "old-backup.sqlite")
        context.getDatabasePath(name).copyTo(backup, overwrite = true)
        context.deleteDatabase(name)
        context.deleteDatabase("diapilot.sqlite")

        BackupRestore.restore(context, Uri.fromFile(backup))
        SqliteCollectorStore(context).use { store ->
            val db = store.readableDatabase
            assertEquals(50, db.version)
            assertEquals(listOf("correction"), rows(db, "SELECT purpose FROM insulin_events WHERE ts_ms=1"))
            assertEquals(listOf("walk · 40 min"), rows(db, "SELECT content FROM annotations WHERE ts_ms=10"))
            assertEquals(listOf("dawn"), rows(db, "SELECT l.name FROM meal_events e JOIN meal_labels l ON l.id=e.label_id WHERE e.onset_ms=100"))
        }
        // The user's backup file itself is never modified.
        SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(49, db.version)
            assertEquals(listOf("коррекция"), rows(db, "SELECT purpose FROM insulin_events WHERE ts_ms=1"))
        }
        backup.delete()
        context.deleteDatabase("diapilot.sqlite")
    }

    @Test fun `a backup that already claims v50 but holds russian tokens is converted on open`() {
        val name = "labels-v50-${System.nanoTime()}.sqlite"
        buildOldFormat(name)
        rawOpen(name).use { it.version = 50 }        // e.g. written by a build that never had this step
        SqliteCollectorStore(context, name).use { store ->
            val db = store.readableDatabase
            assertEquals(listOf("prime"), rows(db, "SELECT purpose FROM insulin_events WHERE ts_ms=4"))
            assertNull(rows(db, "SELECT content FROM annotations WHERE content='недосып'").firstOrNull())
        }
        context.deleteDatabase(name)
    }
}
