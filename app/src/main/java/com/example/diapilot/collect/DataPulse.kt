package com.example.diapilot.collect

/**
 * Process-wide "new glucose data landed" signal. The watch long-poll used to
 * poll SQLite once a second for up to ~80 s per request — thousands of
 * queries an hour while a watch face is connected. Writers pulse once per
 * landed reading; waiters block on the monitor instead of polling.
 */
object DataPulse {
    private val lock = Object()

    /** Wake every waiter — call right after a reading is stored. */
    fun pulse() {
        synchronized(lock) { lock.notifyAll() }
    }

    /** Block until the next pulse or [timeoutMs], whichever comes first. */
    fun await(timeoutMs: Long) {
        if (timeoutMs <= 0) return
        synchronized(lock) {
            try {
                lock.wait(timeoutMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
