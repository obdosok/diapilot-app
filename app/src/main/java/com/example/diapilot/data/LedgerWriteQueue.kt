package com.example.diapilot.data

/**
 * THE SCREEN MUST NEVER WAIT FOR A LEDGER WRITE.
 *
 * SQLite takes one writer. The learning chain in
 * [PhysioLearningMaintenanceRuntime] runs its whole pass inside a single
 * transaction, and while that is open every other write blocks. A live
 * measurement, taken at the moment a meal was logged:
 *
 *     ForecastPerf: ledger: whatIf 7021 ms · forecast 0 ms
 *     PhysioMaintenance: episode-amplitude skipped: Failed requirement.
 *
 * — the ledger write released within 200 ms of the learning transaction
 * committing. That is the several-second delay on any new event the user
 * reported, and the forecast itself took a small fraction of it.
 *
 * NOT [UiMaintenanceQueue], and the difference is the whole reason this exists:
 * that queue discards the oldest task under load. The prospective ledger is the
 * app's own record of what it showed and when it was looking — the instrument
 * that solved the «268» mystery and the one discipline #6 says to size blind
 * holes against. A dropped row is not a slow row, it is a false hole. So this
 * queue is unbounded and serial: work waits, it is never discarded.
 */
internal object LedgerWriteQueue {
    private val executor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
    ) { r -> Thread(r, "diapilot-ledger").apply { isDaemon = true } }

    fun submit(block: () -> Unit) {
        runCatching { executor.execute { runCatching(block).onFailure { android.util.Log.w(TAG, "ledger write failed", it) } } }
            .onFailure { android.util.Log.w(TAG, "ledger enqueue failed", it) }
    }

    private const val TAG = "LedgerWriteQueue"
}
