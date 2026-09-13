package io.github.obdosok.diapilot.data

import android.content.Context
import android.net.Uri
import io.github.obdosok.diapilot.AppIdentity
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.collect.CompanionSync
import io.github.obdosok.diapilot.i18n.localized
import android.database.sqlite.SQLiteDatabase
import com.diapilot.core.backup.BackupAuthException
import com.diapilot.core.backup.BackupCrypto
import com.diapilot.core.backup.BackupFormatException
import java.io.File
import java.io.OutputStream

/**
 * Disaster insurance. The entire history lives in one on-device SQLite file;
 * a dead phone must not mean a dead model. Two halves:
 *
 *  - [restore]: replace the live database with an exported snapshot (the
 *    Settings export / auto-backup file) — the "new phone" path. Validates
 *    the file is a DiaPilot database before touching anything; the process
 *    must be restarted afterwards so every open handle re-reads the new file.
 *  - [autoBackupIfDue]: once a day, silently export the database into the
 *    public Downloads folder (7 rotating weekday files) once the user has
 *    switched it on in Settings. Downloads survives an uninstall and is
 *    reachable from a computer/cloud even if the app itself will never open
 *    again.
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

    /** The live database file name; the one `SqliteCollectorStore` opens by default. */
    private const val DB_NAME = "diapilot.sqlite"

    /**
     * Tables a DiaPilot database has carried since version 1. Anything else is
     * added by `onUpgrade` when the restored file is first opened.
     */
    internal val REQUIRED_TABLES = listOf("glucose_readings", "insulin_events", "annotations")

    /** The database as it was before the last restore; see [undoRestore]. */
    private const val ROLLBACK_NAME = "restore_rollback.sqlite"
    private const val KEY_ROLLBACK_SAVED = "rollback_saved_ms"

    /**
     * How long the rollback copy is kept. A restore that turned out to be the
     * wrong file is noticed within a day or two of using the app; a copy the
     * size of the database (hundreds of MB) is not something to keep in the
     * sandbox for ever.
     */
    internal const val ROLLBACK_TTL_MS = 7L * 24 * 3_600_000

    /** True when a backup password is set, i.e. every file written now is a `.sqlite.enc`. */
    fun encryptsBackups(context: Context): Boolean = Settings.backupPassword(context) != null

    /**
     * THE one writer every backup goes through — the Downloads copy, the
     * cloud copy, the Settings export and the companion upload. With a backup
     * password set the snapshot is wrapped in [BackupCrypto] on the way out,
     * chunk by chunk, so a 600 MB database costs one chunk of heap; without
     * one it is the plain SQLite file it always was. One writer, so no target
     * can be forgotten when the password is set.
     */
    fun writeSnapshot(context: Context, store: SqliteCollectorStore, out: OutputStream) {
        val password = Settings.backupPassword(context)
        if (password == null) {
            store.exportSnapshot(out)
        } else {
            BackupCrypto.encrypting(out, password.toCharArray()).use { store.exportSnapshot(it) }
        }
    }

    /** Whether the file behind [uri] starts with the encrypted-backup magic. */
    fun isEncrypted(context: Context, uri: Uri): Boolean =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(BackupCrypto.MAGIC.size)
            var got = 0
            while (got < head.size) {
                val n = input.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
            got == head.size && BackupCrypto.isEncrypted(head)
        } ?: false

    /**
     * Validate [uri] as a DiaPilot snapshot and swap it in as the live
     * database. Returns a human status; on success the CALLER must restart
     * the process (all SQLiteOpenHelper handles point at the old inode).
     *
     * An encrypted file (`DPBK1` header) is decrypted with [password] into a
     * second cache file first; a plaintext file restores as before, whatever
     * [password] says — old exports keep working. The two crypto failures
     * are reported apart: a wrong password (or a modified file — the format
     * cannot tell) and a file cut short.
     *
     * THE FILE IS UNTRUSTED INPUT. It is the one way a value reaches the
     * store without passing the bounds every live input passes, so it is
     * checked before it touches anything, in this order: SQLite's own
     * integrity check; the schema version (a file from a NEWER build is
     * refused — this build's `onUpgrade` cannot read forward; an older one is
     * accepted, because `onUpgrade` migrates it on first open); the tables a
     * DiaPilot database has always had; then every insulin row against the
     * same dose fuses the command path uses — one impossible dose and the
     * whole file is refused, because a restored dose becomes the model's
     * history and there is no later gate to catch it.
     *
     * The previous database is kept as a rollback copy ([undoRestore]) and
     * the swap runs under the store lock ([Stores.replaceDatabase]), so no
     * thread can open the file while it is being replaced.
     */
    fun restore(context: Context, uri: Uri, password: CharArray? = null): String {
        val text = context.localized()
        val tmp = File(context.cacheDir, "restore_candidate.sqlite")
        val plain = File(context.cacheDir, "restore_candidate.plain.sqlite")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { text.getString(R.string.backup_restore_file_unavailable) }
            tmp.outputStream().use { input.copyTo(it) }
        }
        try {
            val candidate = if (isEncryptedFile(tmp)) {
                require(password != null && password.isNotEmpty()) {
                    text.getString(R.string.backup_restore_encrypted_needs_password)
                }
                try {
                    BackupCrypto.decrypting(tmp.inputStream().buffered(), password).use { input ->
                        plain.outputStream().use { input.copyTo(it) }
                    }
                } catch (e: BackupAuthException) {
                    throw IllegalArgumentException(text.getString(R.string.backup_restore_wrong_password), e)
                } catch (e: BackupFormatException) {
                    throw IllegalArgumentException(text.getString(R.string.backup_restore_corrupt_encrypted, e.message), e)
                }
                plain
            } else {
                tmp
            }
            val counts = validateCandidate(text, candidate)
            swapIn(context, candidate, keepRollback = true)
            return text.resources.getQuantityString(
                R.plurals.backup_restore_success, counts.readings.toInt(), counts.readings, counts.insulin,
            )
        } finally {
            tmp.delete()
            plain.delete()
        }
    }

    private fun isEncryptedFile(file: File): Boolean =
        file.inputStream().use { input ->
            val head = ByteArray(BackupCrypto.MAGIC.size)
            val got = input.read(head)
            got == head.size && BackupCrypto.isEncrypted(head)
        }

    internal class Counts(val readings: Long, val insulin: Long)

    /** Every check a candidate must pass; throws with a localized message. */
    private fun validateCandidate(text: Context, candidate: File): Counts {
        val db = try {
            SQLiteDatabase.openDatabase(candidate.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: android.database.sqlite.SQLiteException) {
            throw IllegalArgumentException(text.getString(R.string.backup_restore_not_sqlite), e)
        }
        db.use {
            // (a) SQLite's own verdict first: every later query assumes sane pages.
            val verdict = try {
                db.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
            } catch (e: android.database.sqlite.SQLiteException) {
                e.message ?: e.javaClass.simpleName
            }
            require(verdict == "ok") {
                // The first finding, not the "*** in database main ***" banner above it.
                val finding = verdict.lineSequence().map(String::trim)
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("***") } ?: verdict
                text.getString(R.string.backup_restore_corrupt, finding.take(120))
            }

            // (b) Schema version: forward is unreadable, backward is migrated.
            val version = db.version
            require(version >= 1) { text.getString(R.string.backup_restore_unversioned) }
            require(version <= SqliteCollectorStore.DB_VERSION) {
                text.getString(R.string.backup_restore_newer_version, version, SqliteCollectorStore.DB_VERSION)
            }

            // (c) The tables every DiaPilot database has.
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            REQUIRED_TABLES.firstOrNull { it !in tables }?.let {
                throw IllegalArgumentException(text.getString(R.string.backup_restore_missing_table, it))
            }

            // (d) The dose fuse: the same bounds as a typed or spoken command.
            val params = com.diapilot.core.PersonalParams.DEFAULT
            fun over(table: String, fuse: Double): Long =
                db.rawQuery("SELECT COUNT(*) FROM $table WHERE units > ?", arrayOf(fuse.toString())).use { c ->
                    c.moveToFirst(); c.getLong(0)
                }
            val overFuse = over("insulin_events", params.commandMaxBolusUnits) +
                if ("basal_events" in tables) over("basal_events", params.commandMaxBasalUnits) else 0L
            require(overFuse == 0L) {
                text.resources.getQuantityString(
                    R.plurals.backup_restore_dose_fuse, overFuse.toInt(),
                    overFuse, params.commandMaxBolusUnits, params.commandMaxBasalUnits,
                )
            }

            fun count(table: String): Long =
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) }
            val readings = count("glucose_readings")
            require(readings > 0) { text.getString(R.string.backup_restore_no_readings) }
            return Counts(readings, count("insulin_events"))
        }
    }

    /**
     * Replace the live database with [source] under the store lock. With
     * [keepRollback] the current file becomes the rollback copy first.
     *
     * The new file is staged next to the live one and renamed over it: a
     * rename is atomic on the filesystem the sandbox lives on, so a handle
     * opened at any instant sees either the old file or the whole new one,
     * never a copy in progress. The `-wal`, `-shm` and `-journal` sidecars
     * belong to the old file and would be replayed into the new one.
     */
    private fun swapIn(context: Context, source: File, keepRollback: Boolean) {
        Stores.replaceDatabase {
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            if (keepRollback && dbFile.exists()) saveRollback(context, dbFile)
            deleteSidecars(dbFile)
            moveOver(source, dbFile)
        }
    }

    private fun deleteSidecars(dbFile: File) {
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
    }

    /** Copy [source] to a staging file beside [target], then rename it over [target]. */
    private fun moveOver(source: File, target: File) {
        val staging = File(target.path + ".staging")
        source.copyTo(staging, overwrite = true)
        if (!staging.renameTo(target)) {
            // Rename refused (different mount, odd filesystem): fall back to a
            // plain overwrite, which is what the restore always did before.
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
    }

    /**
     * Keep the live database as the rollback copy.
     *
     * SQLite folds the WAL into the main file when the last connection closes,
     * so after `Stores.close()` the file alone is normally the whole database.
     * A `-wal` still lying there means a crash left committed rows in it, and
     * a copy of the main file alone would lose them: fold it in first. Only
     * then, and with WAL kept on — a plain `openDatabase` switches the journal
     * mode back and rewrites the header, and the copy is meant to be the
     * file as it was, byte for byte.
     */
    private fun saveRollback(context: Context, dbFile: File) {
        if (File(dbFile.path + "-wal").length() > 0) {
            runCatching {
                SQLiteDatabase.openDatabase(
                    dbFile.path, null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                ).use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                }
            }
        }
        moveOver(dbFile, rollbackFile(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ROLLBACK_SAVED, System.currentTimeMillis()).apply()
    }

    private fun rollbackFile(context: Context) = File(context.filesDir, ROLLBACK_NAME)

    /**
     * True when a rollback copy exists and is younger than [ROLLBACK_TTL_MS].
     * An expired copy is deleted here — this is called on every launch and
     * from the Settings screen, so the seven-day bound needs no scheduler.
     */
    fun rollbackAvailable(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val file = rollbackFile(context)
        if (!file.exists()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong(KEY_ROLLBACK_SAVED, file.lastModified())
        if (nowMs - savedAt > ROLLBACK_TTL_MS) {
            file.delete()
            prefs.edit().remove(KEY_ROLLBACK_SAVED).apply()
            return false
        }
        return true
    }

    /**
     * Put the database from before the last [restore] back. Returns a human
     * status; on success the CALLER must restart the process, as after a
     * restore. The rollback copy is consumed: undo is one level deep.
     */
    fun undoRestore(context: Context): String {
        val text = context.localized()
        require(rollbackAvailable(context)) { text.getString(R.string.backup_restore_undo_unavailable) }
        val rollback = rollbackFile(context)
        Stores.replaceDatabase {
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            deleteSidecars(dbFile)
            moveOver(rollback, dbFile)
            rollback.delete()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ROLLBACK_SAVED).apply()
        }
        return text.getString(R.string.backup_restore_undo_done)
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
     * DELIBERATELY NARROW. It matches only `<applicationId>-auto-backup-%`, so
     * the user's own dated exports (`<applicationId>-backup-<stamp>.sqlite`,
     * written by the Settings button) and another installed copy's
     * auto-backups are never touched. `resolver.delete` can only remove rows
     * this app owns, which is the second bound: a file someone else put in
     * Downloads is not ours to delete even if it were named like one of ours.
     * Files written under the older, id-less name are left alone as well.
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
                arrayOf(AppIdentity.autoBackupPrefix() + "%"),
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

    /**
     * Which daily copies are configured. Each target is an explicit opt-in:
     * the Downloads copy by its Settings switch (off by default — a second
     * installed copy of the app must not write into shared storage on its
     * own), the cloud copy by picking a folder, the companion copy by a URL
     * and a token.
     */
    data class AutoBackupTargets(val downloads: Boolean, val cloud: Boolean, val companion: Boolean) {
        val any: Boolean get() = downloads || cloud || companion
    }

    fun autoBackupTargets(context: Context): AutoBackupTargets = AutoBackupTargets(
        downloads = Settings.autoBackupEnabled(context),
        cloud = cloudFolder(context) != null,
        companion = Settings.companionUrl(context) != null && Settings.companionToken(context) != null,
    )

    /** Daily silent export to the configured targets; call from any background thread. */
    fun autoBackupIfDue(context: Context) {
        rollbackAvailable(context)   // expires a copy older than ROLLBACK_TTL_MS
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val targets = autoBackupTargets(context)
        if (!targets.any) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0) < PERIOD_MS) return
        try {
            val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            // The applicationId is part of the name: the MediaStore lookup
            // below, the SAF lookup in the cloud folder and the prune all
            // match by name, and a name shared with another installed copy of
            // the app would reuse — through SAF, overwrite — that copy's file.
            val name = AppIdentity.autoBackupName(day, encrypted = encryptsBackups(context))
            val resolver = context.contentResolver
            if (targets.downloads) {
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
                    val store = Stores.get(context) as? SqliteCollectorStore ?: return
                    writeSnapshot(context, store, out)
                }
            }
            // Second copy into the user's cloud folder when configured.
            cloudFolder(context)?.let { tree ->
                try {
                    val target = findOrCreateChild(context, tree, name)
                    resolver.openOutputStream(target, "wt")?.use { out ->
                        (Stores.get(context) as? SqliteCollectorStore)?.let { writeSnapshot(context, it, out) }
                    }
                    android.util.Log.i("BackupRestore", "cloud copy written: $name")
                } catch (e: Exception) {
                    android.util.Log.w("BackupRestore", "cloud copy failed: ${e.message}")
                }
            }
            // Third copy: off-device, to the companion server when configured.
            CompanionSync.uploadBackup(context)
            if (targets.downloads) pruneAutoBackups(context)
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
        ) ?: error(context.localized().getString(R.string.backup_restore_cloud_create_failed))
    }
}
