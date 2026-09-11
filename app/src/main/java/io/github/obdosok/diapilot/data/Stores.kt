package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.collector.CollectorStore

/** Process-wide store singleton (receiver, worker and UI share one connection). */
object Stores {
    @Volatile
    private var instance: CollectorStore? = null

    fun get(context: Context): CollectorStore =
        instance ?: synchronized(this) {
            instance ?: SqliteCollectorStore(context.applicationContext).also { instance = it }
        }

    /** Restore path only: close the shared handle before swapping the DB file. */
    fun close() = synchronized(this) {
        (instance as? SqliteCollectorStore)?.close()
        instance = null
    }
}
