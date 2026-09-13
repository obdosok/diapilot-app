package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.collector.CollectorStore

/** Process-wide store singleton (receiver, worker and UI share one connection). */
object Stores {
    @Volatile
    private var instance: CollectorStore? = null

    /**
     * True while [replaceDatabase] is swapping the file. Guarded by this
     * object's monitor, like [instance].
     */
    private var swapping = false

    /**
     * The shared store. Throws while the database file is being replaced: a
     * `SQLiteOpenHelper` opened over a half-copied file would run `onCreate`
     * or `onUpgrade` against it and write, and whatever it wrote would be
     * garbage in the restored copy. A thread that arrives during the swap
     * waits on the monitor and gets a store over the finished file.
     */
    fun get(context: Context): CollectorStore =
        instance ?: synchronized(this) {
            check(!swapping) { "the database is being replaced; try again after the restart" }
            instance ?: SqliteCollectorStore(context.applicationContext).also { instance = it }
        }

    /** Tests and shutdown: close the shared handle. */
    fun close() = synchronized(this) {
        (instance as? SqliteCollectorStore)?.close()
        instance = null
    }

    /**
     * Restore path: close the shared handle, then run [swap] while still
     * holding the lock every [get] takes.
     *
     * WHY NOT `close()` THEN COPY. Between the two the collector thread, the
     * widget or a screen could call [get], open a fresh helper over the OLD
     * file, and hold it while the copy replaced the file underneath — a
     * handle on the old inode that writes into nothing, or worse, a helper
     * that opened the new file mid-copy. Doing the copy under the same lock
     * closes that window; a caller that already held a store reference from
     * before the swap keeps a closed helper, which is why the caller of a
     * restore still restarts the process afterwards.
     */
    fun <T> replaceDatabase(swap: () -> T): T = synchronized(this) {
        (instance as? SqliteCollectorStore)?.close()
        instance = null
        swapping = true
        try {
            swap()
        } finally {
            swapping = false
        }
    }
}
