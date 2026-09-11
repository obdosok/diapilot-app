package io.github.obdosok.diapilot.data

import android.database.sqlite.SQLiteDatabase
import com.diapilot.core.analysis.BolusPurpose
import com.diapilot.core.analysis.canonicalMealLabel
import com.diapilot.core.analysis.canonicalNoteContent

/**
 * Stored Russian tokens -> language-neutral keys (schema v50).
 *
 * WHAT IS CONVERTED — structured values only, each by the one rule in :core
 * that the live write paths also apply:
 *  - `insulin_events.purpose`: a known bolus purpose in any language becomes its
 *    key ([BolusPurpose.canonical]: the Russian correction token -> "correction").
 *  - `annotations.content`, ONLY when the whole note is tag-shaped
 *    ([canonicalNoteContent]): a note tag or purpose (the Russian walk tag -> "walk"), a tag
 *    with its minute suffix (-> "walk · 40 min"), the rescue
 *    note (-> "dextrose ×2").
 *  - `meal_labels.name` ([canonicalMealLabel]): the four system labels
 *    (the Russian dawn label -> "dawn") and tag-shaped labels.
 *
 * WHAT IS NOT TOUCHED: free-text note content, `annotations.analysis` (the LLM
 * protocol markers are read in both forms by the :core parsers), every other
 * table, and every value the rules do not recognise — unknown text is left
 * exactly as it is.
 *
 * SAFETY PROPERTIES, each pinned by `LabelMigrationV1Test`:
 *  - NO ROW IS DELETED. Only UPDATEs run. When a system label's key already
 *    exists as its own row (UNIQUE name), the meals are repointed to it and the
 *    old row is kept.
 *  - IDEMPOTENT. Keys map to themselves, so a second run finds nothing to do and
 *    opens no transaction at all.
 *  - TRANSACTIONAL. All changes commit together or not at all.
 *  - NO FALSE MODEL-INPUT CHANGE. The physio triggers on these tables count every
 *    UPDATE as a changed model input and invalidate the closed learning products
 *    around it; a spelling change of a value whose meaning is identical is not
 *    such a change. The triggers on the updated tables are recorded verbatim from
 *    `sqlite_master`, dropped for the duration of the UPDATEs and re-created from
 *    the recorded SQL inside the same transaction, so a failure anywhere rolls the
 *    triggers back too.
 *
 * Runs from `onUpgrade` (below v50) and again, as a cheap no-op check, from
 * `onOpen` — so a restored backup is converted whatever schema version it
 * carries, including one written by a build that never had this step.
 */
object LabelMigrationV1 {

    data class Result(
        val purposes: Int = 0,
        val notes: Int = 0,
        val labelsRenamed: Int = 0,
        val mealsRepointed: Int = 0,
    ) {
        val total: Int get() = purposes + notes + labelsRenamed + mealsRepointed
    }

    private val TABLES = listOf("insulin_events", "annotations", "meal_labels", "meal_events")

    private fun tables(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }

    private fun distinct(db: SQLiteDatabase, sql: String): List<String> =
        db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private data class Plan(
        val purposes: Map<String, String>,
        val notes: Map<String, String>,
        /** old name -> new name, where no row is named `new` yet. */
        val renames: Map<String, String>,
        /** old label id -> existing label id of the key, where meals still point at the old one. */
        val repoints: Map<Long, Long>,
    ) {
        val empty: Boolean get() = purposes.isEmpty() && notes.isEmpty() && renames.isEmpty() && repoints.isEmpty()
    }

    private fun plan(db: SQLiteDatabase): Plan {
        val present = tables(db)
        val purposes = if ("insulin_events" in present && "purpose" in columns(db, "insulin_events")) {
            distinct(db, "SELECT DISTINCT purpose FROM insulin_events WHERE purpose IS NOT NULL")
                .associateWith { BolusPurpose.canonical(it)!! }.filter { (old, new) -> old != new }
        } else emptyMap()
        val notes = if ("annotations" in present) {
            distinct(db, "SELECT DISTINCT content FROM annotations WHERE content IS NOT NULL")
                .associateWith(::canonicalNoteContent).filter { (old, new) -> old != new }
        } else emptyMap()
        val renames = mutableMapOf<String, String>()
        val repoints = mutableMapOf<Long, Long>()
        if ("meal_labels" in present) {
            val ids = db.rawQuery("SELECT id, name FROM meal_labels WHERE name IS NOT NULL", null)
                .use { c -> buildMap { while (c.moveToNext()) put(c.getString(1), c.getLong(0)) } }
            val claimed = mutableSetOf<String>()
            for ((name, id) in ids) {
                val key = canonicalMealLabel(name)
                if (key == name) continue
                val existing = ids[key]
                if (existing == null && key !in claimed) {
                    renames[name] = key
                    claimed += key
                } else if (existing != null && "meal_events" in present) {
                    val pointing = db.rawQuery(
                        "SELECT COUNT(*) FROM meal_events WHERE label_id = ?", arrayOf(id.toString()),
                    ).use { c -> c.moveToFirst(); c.getLong(0) }
                    if (pointing > 0) repoints[id] = existing
                }
                // A second spelling of a key already claimed by a rename in this run is
                // left for the next open, when the key row exists and it is repointed.
            }
        }
        return Plan(purposes, notes, renames, repoints)
    }

    /** Convert every stored token; returns what changed (all zero when nothing did). */
    fun migrate(db: SQLiteDatabase): Result {
        val plan = plan(db)
        if (plan.empty) return Result()
        db.beginTransaction()
        try {
            val triggers = db.rawQuery(
                "SELECT name, sql FROM sqlite_master WHERE type='trigger' AND tbl_name IN (" +
                    TABLES.joinToString(",") { "'$it'" } + ")",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            triggers.forEach { (name, _) -> db.execSQL("DROP TRIGGER IF EXISTS \"$name\"") }

            var purposes = 0
            plan.purposes.forEach { (old, new) ->
                purposes += db.compileStatement("UPDATE insulin_events SET purpose = ? WHERE purpose = ?")
                    .use { s -> s.bindString(1, new); s.bindString(2, old); s.executeUpdateDelete() }
            }
            var notes = 0
            plan.notes.forEach { (old, new) ->
                notes += db.compileStatement("UPDATE annotations SET content = ? WHERE content = ?")
                    .use { s -> s.bindString(1, new); s.bindString(2, old); s.executeUpdateDelete() }
            }
            var renamed = 0
            plan.renames.forEach { (old, new) ->
                renamed += db.compileStatement("UPDATE meal_labels SET name = ? WHERE name = ?")
                    .use { s -> s.bindString(1, new); s.bindString(2, old); s.executeUpdateDelete() }
            }
            var repointed = 0
            plan.repoints.forEach { (old, new) ->
                repointed += db.compileStatement("UPDATE meal_events SET label_id = ? WHERE label_id = ?")
                    .use { s -> s.bindLong(1, new); s.bindLong(2, old); s.executeUpdateDelete() }
            }

            triggers.forEach { (_, sql) -> db.execSQL(sql) }
            db.setTransactionSuccessful()
            return Result(purposes, notes, renamed, repointed).also {
                android.util.Log.i(
                    "LabelMigrationV1",
                    "stored tokens -> keys: purposes ${it.purposes}, notes ${it.notes}, " +
                        "labels ${it.labelsRenamed}, meals repointed ${it.mealsRepointed}",
                )
            }
        } finally {
            db.endTransaction()
        }
    }
}
