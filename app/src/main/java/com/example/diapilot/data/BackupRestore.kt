package com.example.diapilot.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Disaster insurance. The entire history lives in one on-device SQLite file;
 * a dead phone must not mean a dead model. Two halves:
 *
 *  - [restore]: replace the live database with an exported snapshot (the
 *    Settings export / auto-backup file) — the "new phone" path. Validates
 *    the file is a DiaPilot database before touching anything; the process
 *    must be restarted afterwards so every open handle re-reads the new file.
 *  - [autoBackupIfDue]: once a day, silently export the database into the
 *    public Downloads folder (7 rotating weekday files). Downloads survives
 *    an uninstall and is reachable from a computer/cloud even if the app
 *    itself will never open again.
 */
object BackupRestore {

    private const val PREFS = "backup"
    private const val KEY_LAST = "last_auto_ms"
    private const val KEY_TREE = "cloud_tree_uri"
    private const val PERIOD_MS = 24L * 3_600_000

    /**
     * How many auto-backups survive a prune. Seven is the weekday rotation the
     * design intends; the files run 600+ MB each, so this is ~4.5 GB of
     * insurance and the number is a deliberate ceiling, not a round guess.
     */
    private const val AUTO_BACKUP_KEEP = 7

    /** User-picked SAF folder (e.g. a Google Drive directory): the daily
     *  copy lands there too, and the OS/cloud app does the syncing. The
     *  data never touches any server of ours. */
    fun cloudFolder(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE, null)?.let(Uri::parse)

    fun setCloudFolder(context: Context, tree: Uri?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (tree == null) {
            prefs.edit().remove(KEY_TREE).apply()
        } else {
            context.contentResolver.takePersistableUriPermission(
                tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            prefs.edit().putString(KEY_TREE, tree.toString())
                .putLong(KEY_LAST, 0)   // force a copy on next launch
                .apply()
        }
    }

    /**
     * Validate [uri] as a DiaPilot snapshot and swap it in as the live
     * database. Returns a human status; on success the CALLER must restart
     * the process (all SQLiteOpenHelper handles point at the old inode).
     */
    fun restore(context: Context, uri: Uri): String {
        val tmp = File(context.cacheDir, "restore_candidate.sqlite")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "файл недоступен" }
            tmp.outputStream().use { input.copyTo(it) }
        }
        try {
            // Sanity: it must open as SQLite and carry our core table.
            val readings: Long
            val insulin: Long
            android.database.sqlite.SQLiteDatabase.openDatabase(
                tmp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                fun count(table: String): Long =
                    db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                        c.moveToFirst(); c.getLong(0)
                    }
                readings = count("glucose_readings")
                insulin = count("insulin_events")
            }
            require(readings > 0) { "в файле нет показаний глюкозы — это не копия DiaPilot?" }

            // Swap: close every handle, drop WAL sidecars, move the file in.
            Stores.close()
            val dbFile = context.getDatabasePath("diapilot.sqlite")
            dbFile.parentFile?.mkdirs()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            tmp.copyTo(dbFile, overwrite = true)
            return "Восстановлено: $readings показаний, $insulin уколов. Перезапускаю…"
        } finally {
            tmp.delete()
        }
    }

    /**
     * Keep only the newest [AUTO_BACKUP_KEEP] of our own auto-backups.
     *
     * WHY IT IS A PRUNE AND NOT A FIX TO THE WRITE. The design above is seven
     * rotating weekday files, and it already queries MediaStore by DISPLAY_NAME
     * and writes into the row it finds. It still produced thirty-four files
     * totalling several gigabytes — MediaStore had been appending "(1)",
     * "(2)", "(3)", "(4)" to the names. Exactly why the reuse query missed is
     * not something the source can settle, and three guesses read off the source
     * were already wrong today; a prune is self-healing whatever the cause, and
     * it caps the damage at a known number of files rather than at none.
     *
     * DELIBERATELY NARROW. It matches only `diapilot-auto-backup-%`, so the
     * user's own dated exports (`diapilot-backup-<stamp>.sqlite`, written by
     * the Settings button) are never touched. `resolver.delete` can only remove
     * rows this app owns, which is the second bound: a file someone else put in
     * Downloads is not ours to delete even if it were named like one of ours.
     */
    private fun pruneAutoBackups(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        runCatching {
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val ids = context.contentResolver.query(
                collection,
                arrayOf(
                    android.provider.MediaStore.Downloads._ID,
                    android.provider.MediaStore.Downloads.DISPLAY_NAME,
                ),
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("diapilot-auto-backup-%"),
                "${android.provider.MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
            }.orEmpty()
            var removed = 0
            ids.drop(AUTO_BACKUP_KEEP).forEach { (id, displayName) ->
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                if (runCatching { context.contentResolver.delete(uri, null, null) }
                        .getOrDefault(0) > 0
                ) {
                    removed++
                    android.util.Log.i("BackupRestore", "pruned old auto-backup: $displayName")
                }
            }
            if (removed > 0) {
                android.util.Log.i(
                    "BackupRestore",
                    "auto-backups: kept ${minOf(ids.size, AUTO_BACKUP_KEEP)}, removed $removed",
                )
            }
        }.onFailure { android.util.Log.w("BackupRestore", "prune failed open: ${it.message}") }
    }

    /** Daily silent export into Downloads; call from any background thread. */
    fun autoBackupIfDue(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0) < PERIOD_MS) return
        try {
            val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            val name = "diapilot-auto-backup-$day.sqlite"
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // Reuse our own weekday file when it exists; otherwise create it.
            val existing = resolver.query(
                collection, arrayOf(android.provider.MediaStore.Downloads._ID),
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                } else null
            }
            val uri = existing ?: resolver.insert(
                collection,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                },
            ) ?: return
            resolver.openOutputStream(uri, "wt")?.use { out ->
                (Stores.get(context) as? SqliteCollectorStore)?.exportSnapshot(out) ?: return
            }
            // Second copy into the user's cloud folder when configured.
            cloudFolder(context)?.let { tree ->
                try {
                    val target = findOrCreateChild(context, tree, name)
                    resolver.openOutputStream(target, "wt")?.use { out ->
                        (Stores.get(context) as? SqliteCollectorStore)?.exportSnapshot(out)
                    }
                    android.util.Log.i("BackupRestore", "cloud copy written: $name")
                } catch (e: Exception) {
                    android.util.Log.w("BackupRestore", "cloud copy failed: ${e.message}")
                }
            }
            // Third copy: off-device, to the companion server when configured.
            com.example.diapilot.collect.CompanionSync.uploadBackup(context)
            pruneAutoBackups(context)
            prefs.edit().putLong(KEY_LAST, now).apply()
            android.util.Log.i("BackupRestore", "auto-backup written: $name")
        } catch (e: Exception) {
            // Never let insurance break the app; retry next launch.
            android.util.Log.w("BackupRestore", "auto-backup failed: ${e.message}")
        }
    }

    /** SAF tree helper: reuse the child named [name] or create it. */
    private fun findOrCreateChild(context: Context, tree: Uri, name: String): Uri {
        val resolver = context.contentResolver
        val treeDoc = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDoc)
        resolver.query(
            children,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return android.provider.DocumentsContract.createDocument(
            resolver,
            android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, treeDoc),
            "application/octet-stream", name,
        ) ?: error("не удалось создать файл в облачной папке")
    }
}
