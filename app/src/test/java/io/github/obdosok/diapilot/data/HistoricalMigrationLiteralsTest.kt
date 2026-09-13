package io.github.obdosok.diapilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ARE THE RUSSIAN LITERALS IN THE OLD UPGRADE STEPS STALE? No — and this is the
 * evidence, rather than a comment.
 *
 * Schema v8 adopts purpose NOTES ("коррекция" / "на еду") into
 * `insulin_events.purpose`; v19 excludes 'воздух' boluses from a meal's dose;
 * v50 (`LabelMigrationV1`) converts every stored token to a language-neutral
 * key. The literals in v8 and v19 match nothing on a v50 database — but they
 * never run on one: `onUpgrade` runs its steps in ascending order, so v8 sees
 * a v7 database, which holds exactly those tokens, and v50 runs after it. The
 * first test shows the chain producing the key; the second shows what
 * "fixing" the literal, or moving v50 up, would do — the step would adopt
 * nothing and the notes would stay notes. The third pins the order in the
 * source, because that order is the whole argument.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoricalMigrationLiteralsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun purposes(db: android.database.sqlite.SQLiteDatabase): List<String> =
        db.rawQuery("SELECT ts_ms, purpose FROM insulin_events ORDER BY ts_ms", null).use { c ->
            buildList { while (c.moveToNext()) add("${c.getLong(0)}|${if (c.isNull(1)) "∅" else c.getString(1)}") }
        }

    private fun notes(db: android.database.sqlite.SQLiteDatabase): List<String> =
        db.rawQuery("SELECT ts_ms, content FROM annotations ORDER BY ts_ms", null).use { c ->
            buildList { while (c.moveToNext()) add("${c.getLong(0)}|${c.getString(1)}") }
        }

    /** What a v7 database holds: purpose-less boluses and a Russian purpose NOTE at each one's timestamp. */
    private fun seedV7Shape(db: android.database.sqlite.SQLiteDatabase) {
        listOf(1_000L to "коррекция", 2_000L to "на еду", 3_000L to null).forEach { (ts, note) ->
            db.execSQL(
                "INSERT INTO insulin_events(ts_ms,units,insulin_type,source,purpose,user_edited) VALUES(?,2.0,'bolus','test',NULL,0)",
                arrayOf<Any?>(ts),
            )
            if (note != null) db.execSQL("INSERT INTO annotations(ts_ms,kind,content) VALUES(?,'text',?)", arrayOf<Any?>(ts, note))
        }
        // A note with the same words that belongs to no bolus stays a note.
        db.execSQL("INSERT INTO annotations(ts_ms,kind,content) VALUES(9000,'text','коррекция')")
    }

    @Test fun `v8 adopts v7 tokens and v50 turns them into keys`() {
        val name = "h1-${System.nanoTime() % 100000}.sqlite"
        context.deleteDatabase(name)
        SqliteCollectorStore(context, name).use { store ->
            val db = store.writableDatabase
            seedV7Shape(db)
            SqliteCollectorStore.adoptLegacyPurposeNotesV8(db)
            assertEquals(listOf("1000|коррекция", "2000|на еду", "3000|∅"), purposes(db))
            assertEquals("the adopted notes are gone, the orphan stays", listOf("9000|коррекция"), notes(db))
            LabelMigrationV1.migrate(db)
            assertEquals(listOf("1000|correction", "2000|meal_bolus", "3000|∅"), purposes(db))
            assertEquals(listOf("9000|correction"), notes(db))
        }
        context.deleteDatabase(name)
    }

    @Test fun `v8 after v50 matches nothing - so v50 stays last`() {
        val name = "h2-${System.nanoTime() % 100000}.sqlite"
        context.deleteDatabase(name)
        SqliteCollectorStore(context, name).use { store ->
            val db = store.writableDatabase
            seedV7Shape(db)
            LabelMigrationV1.migrate(db)
            SqliteCollectorStore.adoptLegacyPurposeNotesV8(db)
            assertEquals("nothing adopted", listOf("1000|∅", "2000|∅", "3000|∅"), purposes(db))
            assertEquals("the notes were converted, not adopted", listOf("1000|correction", "2000|meal_bolus", "9000|correction"), notes(db))
        }
        context.deleteDatabase(name)
    }

    @Test fun `the token-to-key step is the last upgrade step in the source`() {
        val rel = "src/main/java/io/github/obdosok/diapilot/data/SqliteCollectorStore.kt"
        val source = File(rel).let { if (it.exists()) it else File("app/$rel") }.readText()
        val steps = Regex("""if \(oldVersion < (\d+)\)""").findAll(source).map { it.groupValues[1].toInt() }.toList()
        assertTrue("no upgrade steps found", steps.isNotEmpty())
        assertEquals("the last step in onUpgrade must be the token-to-key conversion", SqliteCollectorStore.DB_VERSION, steps.last())
        assertEquals("v50 appears exactly once", 1, steps.count { it == SqliteCollectorStore.DB_VERSION })
    }
}
