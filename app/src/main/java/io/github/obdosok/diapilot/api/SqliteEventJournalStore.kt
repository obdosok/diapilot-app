package io.github.obdosok.diapilot.api

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.diapilot.core.api.EventJournalStore
import com.diapilot.core.api.JournalEntry
import com.diapilot.core.api.PendingEntry

/**
 * The journal on disk, in its OWN database file.
 *
 * Deliberately not a table in `diapilot.sqlite`: the main store is ~67 MB, its
 * backup already ran the app out of heap once (b1ee5a9), and a schema migration
 * there would put the whole collector at risk for a read-only side channel.
 * A separate file means no `DB_VERSION` bump, no migration, no backup growth,
 * and the user can delete it without touching a single measurement.
 *
 * `journal` is the append-only log. `head` is the derived current state per
 * fact — kept as a real table rather than recomputed with `MAX(seq) GROUP BY`,
 * because the reconcile pass asks «what does this fact look like now?» once per
 * fact and a primary-key probe is the difference between seconds and minutes
 * over 150k rows.
 */
class SqliteEventJournalStore(context: Context, dbName: String = DB_NAME) :
    SQLiteOpenHelper(context.applicationContext, dbName, null, 1), EventJournalStore {

    companion object {
        const val DB_NAME = "diafor_events.sqlite"
        private const val KEY_SOURCE_ID = "source_id"
        private const val KEY_PRUNED_THROUGH = "pruned_through_seq"

        private const val COLS =
            "seq, fact_id, revision, type, occurred_at_ms, received_at_ms, deleted, payload"

        private val SCHEMA = listOf(
            // AUTOINCREMENT, not a bare rowid alias: after a prune SQLite would
            // happily REUSE the freed rowids, and a consumer holding after=1052
            // would then be handed different events under the same seq.
            """CREATE TABLE IF NOT EXISTS journal (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                fact_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                type TEXT NOT NULL,
                occurred_at_ms INTEGER NOT NULL,
                received_at_ms INTEGER NOT NULL,
                deleted INTEGER NOT NULL,
                payload TEXT NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_journal_fact ON journal(fact_id, seq)",
            """CREATE TABLE IF NOT EXISTS head (
                fact_id TEXT PRIMARY KEY,
                seq INTEGER NOT NULL,
                revision INTEGER NOT NULL,
                type TEXT NOT NULL,
                occurred_at_ms INTEGER NOT NULL,
                received_at_ms INTEGER NOT NULL,
                deleted INTEGER NOT NULL,
                payload TEXT NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_head_occurred ON head(occurred_at_ms)",
            "CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)",
        )
    }

    override fun onCreate(db: SQLiteDatabase) = SCHEMA.forEach(db::execSQL)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // --- meta ---------------------------------------------------------------

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun setMeta(key: String, value: String) {
        writableDatabase.execSQL(
            "INSERT INTO meta (k, v) VALUES (?,?) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
            arrayOf<Any?>(key, value),
        )
    }

    /**
     * Created once, then never again. Written inside a transaction that first
     * re-reads the row, so two threads racing on the very first request cannot
     * mint two identities.
     */
    @Synchronized
    override fun sourceId(): String {
        meta(KEY_SOURCE_ID)?.let { return it }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(KEY_SOURCE_ID))
                .use { if (it.moveToFirst()) it.getString(0) else null }
            val id = existing ?: java.util.UUID.randomUUID().toString()
            if (existing == null) {
                db.execSQL(
                    "INSERT INTO meta (k, v) VALUES (?,?)", arrayOf<Any?>(KEY_SOURCE_ID, id),
                )
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    override fun prunedThroughSeq(): Long = meta(KEY_PRUNED_THROUGH)?.toLongOrNull() ?: 0L

    // --- append -------------------------------------------------------------

    override fun append(rows: List<PendingEntry>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                val v = ContentValues().apply {
                    put("fact_id", r.id)
                    put("revision", r.revision)
                    put("type", r.type)
                    put("occurred_at_ms", r.occurredAtMs)
                    put("received_at_ms", r.receivedAtMs)
                    put("deleted", if (r.deleted) 1 else 0)
                    put("payload", r.payload)
                }
                // rowid IS seq here (INTEGER PRIMARY KEY), so the head row can
                // point at the log entry that produced it without a re-query.
                val seq = db.insertOrThrow("journal", null, v)
                db.execSQL(
                    "INSERT INTO head (fact_id, seq, revision, type, occurred_at_ms, " +
                        "received_at_ms, deleted, payload) VALUES (?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT(fact_id) DO UPDATE SET seq=excluded.seq, " +
                        "revision=excluded.revision, type=excluded.type, " +
                        "occurred_at_ms=excluded.occurred_at_ms, " +
                        "received_at_ms=excluded.received_at_ms, deleted=excluded.deleted, " +
                        "payload=excluded.payload",
                    arrayOf<Any?>(
                        r.id, seq, r.revision, r.type, r.occurredAtMs, r.receivedAtMs,
                        if (r.deleted) 1 else 0, r.payload,
                    ),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- read ---------------------------------------------------------------

    private fun Cursor.entry() = JournalEntry(
        seq = getLong(0), id = getString(1), revision = getInt(2), type = getString(3),
        occurredAtMs = getLong(4), receivedAtMs = getLong(5),
        deleted = getInt(6) != 0, payload = getString(7),
    )

    override fun page(afterSeq: Long, limit: Int): List<JournalEntry> =
        readableDatabase.rawQuery(
            "SELECT $COLS FROM journal WHERE seq > ? ORDER BY seq LIMIT ?",
            arrayOf(afterSeq.toString(), limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.entry()) } }

    override fun hasAfter(seq: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM journal WHERE seq > ? LIMIT 1", arrayOf(seq.toString()),
        ).use { it.moveToFirst() }

    override fun headsInWindow(fromMs: Long, toMs: Long): Map<String, JournalEntry> =
        readableDatabase.rawQuery(
            "SELECT $COLS FROM head WHERE occurred_at_ms BETWEEN ? AND ?",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildMap { while (c.moveToNext()) c.entry().let { put(it.id, it) } }
        }

    override fun head(id: String): JournalEntry? =
        readableDatabase.rawQuery(
            "SELECT $COLS FROM head WHERE fact_id = ?", arrayOf(id),
        ).use { if (it.moveToFirst()) it.entry() else null }

    // --- retention ----------------------------------------------------------

    /**
     * Drop journal entries at or below [throughSeq] and record that we did, so
     * a consumer resuming from inside the dropped stretch gets 410 rather than
     * a page that silently skips events.
     *
     * Nothing calls this on a schedule today — full history is retained. It
     * exists so the 410 contract is real code with a real test, not a promise.
     */
    fun prune(throughSeq: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // A fact's CURRENT state is never dropped, only its superseded
            // revisions: head must keep answering for revision numbering.
            db.execSQL(
                "DELETE FROM journal WHERE seq <= ? AND seq NOT IN (SELECT seq FROM head)",
                arrayOf<Any?>(throughSeq),
            )
            setMeta(KEY_PRUNED_THROUGH, throughSeq.toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- diagnostics --------------------------------------------------------

    data class Stats(
        val entries: Long,
        val facts: Long,
        val maxSeq: Long,
        val oldestOccurredMs: Long?,
        val newestOccurredMs: Long?,
    )

    fun stats(): Stats = readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(MAX(seq),0), MIN(occurred_at_ms), MAX(occurred_at_ms) FROM journal",
        null,
    ).use { c ->
        c.moveToFirst()
        Stats(
            entries = c.getLong(0),
            facts = readableDatabase.rawQuery("SELECT COUNT(*) FROM head", null)
                .use { it.moveToFirst(); it.getLong(0) },
            maxSeq = c.getLong(1),
            oldestOccurredMs = if (c.isNull(2)) null else c.getLong(2),
            newestOccurredMs = if (c.isNull(3)) null else c.getLong(3),
        )
    }
}
