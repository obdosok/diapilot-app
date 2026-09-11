package io.github.obdosok.diapilot.data

/**
 * Removal of every fact recorded before the food era the user has CHOSEN.
 *
 * WHEN IT MAY RUN. Only after the user has explicitly moved the era start in
 * Settings, and only below that chosen start: the boundary comes from
 * [FoodEraSettings.explicitStartMs], which is null for a first-run default, and
 * [boundary] passes that through unchanged. There is no built-in date and no
 * background caller — the screen that runs it asks for confirmation first.
 *
 * WHY THIS EXISTS, and it is not a size optimisation. Pre-era rows can
 * describe a DIFFERENT regime: no food was logged, and whatever changed around
 * the boundary the user chose may change how insulin and glucose relate. That
 * contaminates absorption SHAPE exactly as it contaminates amplitude, so those
 * rows may teach neither.
 *
 * The production readers already clamp to the era ([FoodEraSettings]) — measured on a
 * device pull, the kernel corpus held a handful of episodes rather than the
 * hundreds an ungated reader would have produced. But a clamp lives at each
 * CALL SITE and is therefore forgettable: the laptop harness reads without one
 * to this day. Absent rows cannot be read by a reader that forgets.
 *
 * DELIBERATELY NOT TOUCHED:
 *  - `pen_doses` — an NFC de-duplication ledger keyed by dose uuid, not
 *    analysis. Dropping it lets an old pen read re-import doses already known.
 *  - `deleted_boluses` — tombstones are cheap and keep a deleted dose dead.
 *  - `forecast_*`, `physio_*`, `hybrid_whatif_*` — derived products, not
 *    facts; they carry their own generation tags and retention question
 *    ([LedgerRetention]).
 *  - `meter_calibrations` — a fitted lens, not a fact; its readers already
 *    clamp to the era, and it is refitted from the surviving fingersticks.
 */
object PreEraPurge {

    /** Rows removed (or removable) from one table. */
    data class TableRows(val table: String, val rows: Int)

    data class Result(val tables: List<TableRows>, val freedBytes: Long) {
        val total: Int get() = tables.sumOf { it.rows }
    }

    /**
     * Table → its event-time column. A table is listed even when this user has
     * no pre-era rows in it: a zero-row DELETE is free, and omitting it would
     * silently leave a hole the day the collector starts filling it.
     */
    private val TIME_COLUMNS = listOf(
        "glucose_readings" to "ts_ms",
        "minute_readings" to "ts_ms",
        "presented_glucose" to "ts_ms",
        "insulin_events" to "ts_ms",
        "basal_events" to "ts_ms",
        "annotations" to "ts_ms",
        "meal_events" to "onset_ms",
        "meal_marks" to "onset_ms",
        "heart_rate" to "ts_ms",
        "sleep_sessions" to "start_ms",
        "steps" to "start_ms",
    )

    /**
     * The purge boundary, or null when there is nothing the user has asked to
     * delete: the era start is still its first-run default.
     */
    fun boundary(context: android.content.Context): Long? = FoodEraSettings.explicitStartMs(context)

    /** What a purge below [boundaryMs] WOULD remove. Read-only; safe to call from a dialog. */
    fun preview(store: SqliteCollectorStore, boundaryMs: Long): Result {
        val db = store.readableDatabase
        val rows = TIME_COLUMNS.map { (table, column) ->
            val n = db.rawQuery(
                "SELECT COUNT(*) FROM $table WHERE $column < ?",
                arrayOf(boundaryMs.toString()),
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            TableRows(table, n)
        }.filter { it.rows > 0 }
        return Result(rows, 0L)
    }

    /**
     * Delete, then reclaim. The DELETEs run in ONE transaction so a process
     * death cannot leave half a history; VACUUM runs after the commit because
     * SQLite refuses it inside one.
     *
     * VACUUM takes an exclusive lock on a ~350 MB file, so the collector cannot
     * write while it runs. That is why this is an explicit user action and
     * never a background job.
     */
    fun purge(store: SqliteCollectorStore, boundaryMs: Long): Result {
        val db = store.writableDatabase
        val before = java.io.File(db.path).length()
        val removed = mutableListOf<TableRows>()
        db.beginTransaction()
        try {
            // Evidence hangs off an annotation id, so it must go before its
            // owner disappears — otherwise it is orphaned rather than deleted.
            db.execSQL(
                "DELETE FROM carb_evidence_v1 WHERE annotation_id IN " +
                    "(SELECT id FROM annotations WHERE ts_ms < ?)",
                arrayOf(boundaryMs),
            )
            for ((table, column) in TIME_COLUMNS) {
                val n = db.rawQuery(
                    "SELECT COUNT(*) FROM $table WHERE $column < ?",
                    arrayOf(boundaryMs.toString()),
                ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                if (n > 0) {
                    db.execSQL("DELETE FROM $table WHERE $column < ?", arrayOf(boundaryMs))
                    removed += TableRows(table, n)
                }
            }
            // A label's use_count is a cache of how many meals carry it.
            // Deleting their meals without this leaves the label picker
            // ordered by a history that no longer exists.
            db.execSQL(
                "UPDATE meal_labels SET use_count = " +
                    "(SELECT COUNT(*) FROM meal_events WHERE meal_events.label_id = meal_labels.id)",
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.execSQL("VACUUM")
        val freed = (before - java.io.File(db.path).length()).coerceAtLeast(0L)
        // Everything learned was derived from rows that just changed. Rebuild
        // rather than serve a twin whose inputs no longer exist.
        TwinCache.invalidate()
        return Result(removed, freed)
    }
}
