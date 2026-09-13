package io.github.obdosok.diapilot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * [BackupRestore.restore] treats the file as untrusted input: a damaged
 * file, a file from a newer build, a file with a table missing and a file
 * carrying an impossible dose are each refused before the live database is
 * touched — and when a file is accepted, the previous database survives as
 * a rollback copy that [BackupRestore.undoRestore] puts back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val liveDb get() = context.getDatabasePath("diapilot.sqlite")
    private val rollback get() = File(context.filesDir, "restore_rollback.sqlite")
    private val backupPrefs get() = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    @Before fun clean() {
        Stores.close()
        context.deleteDatabase("diapilot.sqlite")
        rollback.delete()
        backupPrefs.edit().clear().apply()
    }

    @After fun tearDown() {
        Stores.close()
        context.deleteDatabase("diapilot.sqlite")
        rollback.delete()
    }

    /**
     * A DiaPilot database in a file of its own, built through the real
     * helper so it carries the current schema and version. [marker] tells
     * copies apart; [doses] land in `insulin_events`.
     */
    private fun buildDatabase(marker: String, doses: List<Double> = listOf(2.0), readings: Int = 3): File {
        val name = "candidate-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            val db = store.writableDatabase
            repeat(readings) { i ->
                db.execSQL(
                    "INSERT INTO glucose_readings(ts_ms,mgdl,mmol,trend,source) VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(60_000L * (i + 1), 100.0, 5.55, "Flat", marker),
                )
            }
            doses.forEachIndexed { i, units ->
                db.execSQL(
                    "INSERT INTO insulin_events(ts_ms,units,insulin_type,source) VALUES(?,?,?,?)",
                    arrayOf<Any?>(1_000L * (i + 1), units, "bolus", marker),
                )
            }
        }
        val file = File(context.cacheDir, name)
        context.getDatabasePath(name).copyTo(file, overwrite = true)
        context.deleteDatabase(name)
        return file
    }

    private fun installLive(marker: String): ByteArray {
        val built = buildDatabase(marker)
        liveDb.parentFile?.mkdirs()
        built.copyTo(liveDb, overwrite = true)
        built.delete()
        return liveDb.readBytes()
    }

    private fun sourceOfFirstReading(file: File): String =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT source FROM glucose_readings ORDER BY ts_ms LIMIT 1", null).use { c ->
                c.moveToFirst(); c.getString(0)
            }
        }

    private fun edit(file: File, block: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun refused(file: File): String {
        val before = liveDb.takeIf { it.exists() }?.readBytes()
        val e = assertThrows(IllegalArgumentException::class.java) {
            BackupRestore.restore(context, Uri.fromFile(file))
        }
        assertArrayEquals("a refused file must not touch the live database", before, liveDb.takeIf { it.exists() }?.readBytes())
        assertFalse("a refused file leaves no rollback copy", rollback.exists())
        return e.message!!
    }

    @Test fun `a file that fails the integrity check is refused`() {
        installLive("live")
        val file = buildDatabase("bad")
        // Point the header's freelist at a page that does not exist. The file
        // still opens and every table still counts, so only the integrity
        // check itself can catch it — which is what this proves runs.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(32)
            raf.writeInt(999_999)   // first freelist trunk page
            raf.writeInt(1)         // freelist page count
        }
        val message = refused(file)
        assertTrue(message, message.contains("integrity check"))
        assertTrue(message, message.contains("freelist", ignoreCase = true))
    }

    @Test fun `a file that is not SQLite at all is refused`() {
        installLive("live")
        val file = File(context.cacheDir, "not-a-db.sqlite").apply { writeText("hello, this is not a database at all, not even close") }
        val message = refused(file)
        assertTrue(message, message.contains("SQLite"))
    }

    @Test fun `a file written by a newer build is refused and names both versions`() {
        installLive("live")
        val file = buildDatabase("future")
        edit(file) { it.version = SqliteCollectorStore.DB_VERSION + 1 }
        val message = refused(file)
        assertTrue(message, message.contains("${SqliteCollectorStore.DB_VERSION + 1}"))
        assertTrue(message, message.contains("${SqliteCollectorStore.DB_VERSION}"))
        assertTrue(message, message.contains("newer"))
    }

    @Test fun `a file with no schema version is refused`() {
        installLive("live")
        val file = buildDatabase("unversioned")
        edit(file) { it.version = 0 }
        assertTrue(refused(file).contains("no schema version"))
    }

    @Test fun `a file missing a core table is refused and names the table`() {
        installLive("live")
        val file = buildDatabase("tableless")
        edit(file) { it.execSQL("DROP TABLE annotations") }
        assertTrue(refused(file).contains("annotations"))
    }

    @Test fun `a file carrying an impossible dose is refused and counts the rows`() {
        installLive("live")
        val fuse = com.diapilot.core.PersonalParams.DEFAULT.commandMaxBolusUnits
        val file = buildDatabase("dosed", doses = listOf(fuse, fuse + 0.5, 50.0, 1.0))
        val message = refused(file)
        assertTrue(message, message.startsWith("refused: 2 insulin rows"))
        assertTrue(message, message.contains("${fuse.toInt()} U"))
    }

    @Test fun `an impossible basal in the basal ledger is refused too`() {
        installLive("live")
        val file = buildDatabase("basal")
        edit(file) {
            it.execSQL("INSERT INTO basal_events(ts_ms,units) VALUES(1, ?)", arrayOf<Any?>(com.diapilot.core.PersonalParams.DEFAULT.commandMaxBasalUnits + 1))
        }
        assertTrue(refused(file).startsWith("refused: 1 insulin row"))
    }

    @Test fun `a dose exactly at the fuse is a plausible dose and passes`() {
        installLive("live")
        val file = buildDatabase("edge", doses = listOf(com.diapilot.core.PersonalParams.DEFAULT.commandMaxBolusUnits))
        val status = BackupRestore.restore(context, Uri.fromFile(file))
        assertTrue(status, status.startsWith("Restored"))
        assertEquals("edge", sourceOfFirstReading(liveDb))
    }

    @Test fun `restoring keeps the previous database as a rollback copy and undo puts it back`() {
        val before = installLive("previous")
        val file = buildDatabase("incoming")

        BackupRestore.restore(context, Uri.fromFile(file))

        assertEquals("incoming", sourceOfFirstReading(liveDb))
        assertTrue(rollback.exists())
        assertTrue(BackupRestore.rollbackAvailable(context))
        assertArrayEquals(before, rollback.readBytes())

        val status = BackupRestore.undoRestore(context)
        assertTrue(status, status.startsWith("Previous database restored"))
        assertArrayEquals(before, liveDb.readBytes())
        assertEquals("previous", sourceOfFirstReading(liveDb))
        assertFalse(rollback.exists())
        assertFalse(BackupRestore.rollbackAvailable(context))
        assertThrows(IllegalArgumentException::class.java) { BackupRestore.undoRestore(context) }
    }

    @Test fun `a second restore replaces the rollback copy with the database it displaced`() {
        installLive("first")
        BackupRestore.restore(context, Uri.fromFile(buildDatabase("second")))
        val secondBytes = liveDb.readBytes()
        BackupRestore.restore(context, Uri.fromFile(buildDatabase("third")))
        assertEquals("third", sourceOfFirstReading(liveDb))
        assertArrayEquals(secondBytes, rollback.readBytes())
    }

    @Test fun `the rollback copy expires after seven days`() {
        installLive("old")
        BackupRestore.restore(context, Uri.fromFile(buildDatabase("new")))
        val savedAt = backupPrefs.getLong("rollback_saved_ms", 0)
        assertTrue(savedAt > 0)
        assertTrue(BackupRestore.rollbackAvailable(context, nowMs = savedAt + BackupRestore.ROLLBACK_TTL_MS))
        assertFalse(BackupRestore.rollbackAvailable(context, nowMs = savedAt + BackupRestore.ROLLBACK_TTL_MS + 1))
        assertFalse(rollback.exists())
        assertFalse(backupPrefs.contains("rollback_saved_ms"))
    }

    @Test fun `a first restore onto an empty phone has nothing to roll back`() {
        BackupRestore.restore(context, Uri.fromFile(buildDatabase("fresh")))
        assertFalse(rollback.exists())
        assertFalse(BackupRestore.rollbackAvailable(context))
    }

    @Test fun `every sidecar of the old file is removed on swap`() {
        installLive("live")
        val sidecars = listOf("-wal", "-shm", "-journal").map { File(liveDb.path + it).apply { writeText("stale") } }
        BackupRestore.restore(context, Uri.fromFile(buildDatabase("new")))
        sidecars.forEach { assertFalse(it.name, it.exists()) }
        assertFalse(File(liveDb.path + ".staging").exists())
    }

    @Test fun `no store is handed out while the file is being swapped`() {
        Stores.replaceDatabase {
            val e = assertThrows(IllegalStateException::class.java) { Stores.get(context) }
            assertTrue(e.message!!.contains("being replaced"))
        }
        // Once the swap is over the next caller gets a store over the new file.
        Stores.get(context)
    }
}
