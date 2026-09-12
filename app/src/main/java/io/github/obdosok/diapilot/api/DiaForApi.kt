package io.github.obdosok.diapilot.api

import android.content.Context
import com.diapilot.core.api.ApiResponse
import com.diapilot.core.api.DiaForFacts
import com.diapilot.core.api.EventJournal
import com.diapilot.core.api.EventsApi
import com.diapilot.core.api.Fact
import com.diapilot.core.collector.CollectorStore
import io.github.obdosok.diapilot.data.FoodEraSettings
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.SqliteCollectorStore
import io.github.obdosok.diapilot.data.Stores
import io.github.obdosok.diapilot.diag.DiagLog

/**
 * The local read-only feed for a second app on the same phone (DiaFor).
 *
 * Three passes fold storage into the journal, and the whole design is about
 * which window each one may honestly claim to know completely:
 *
 *  - **live** — the last [LIVE_WINDOW_MS], on every request (throttled). This
 *    is what makes `received_at` meaningful: a fact is stamped when we OBSERVE
 *    it, so for a polling consumer the stamp is accurate to its poll interval,
 *    and never — under any path here — a copy of `occurred_at`.
 *  - **catch-up** — the last [CATCHUP_WINDOW_MS], every [CATCHUP_MIN_INTERVAL_MS].
 *    It exists because this app BACK-FILLS: a deep `sgv.json` poll imports ~14
 *    days of readings at once (`TreatmentsPollWorker.SGV_COUNT_DEEP`), and a
 *    6-hour window would never see them. The catch-up window is sized off that
 *    number, not off intuition — if the poll's depth changes, this must too.
 *  - **backfill** — the whole history, once, oldest first, on a background
 *    thread in [BACKFILL_CHUNK_MS] slices. It cannot run inside a request: 148k
 *    readings would time the consumer out. While it runs the endpoint serves
 *    normally, `has_more` stays true, and the consumer's ordinary paging loop
 *    drains it.
 *
 * **The rule the passes must not break:** a reconcile may only be handed the
 * COMPLETE truth for the window it declares, or it records mass deletions. That
 * is why reading the facts and reconciling them happen together under [lock] —
 * without it the background pass could read a window, the live pass could
 * append a new reading inside that window, and the background pass would then
 * declare the reading deleted because its list predates it.
 *
 * **Known bound:** once the backfill has passed a stretch, only the catch-up
 * window is re-examined. Deleting a bolus older than [CATCHUP_WINDOW_MS] would
 * therefore not be published as a deletion. Re-sweeping every fact on a poll to
 * catch that is not a trade worth making; the bound is documented in
 * `docs/local-api.md` instead of being pretended away.
 *
 * **Floor:** nothing before the configured food era ([FoodEraSettings]) is
 * published, and the older history is simply not mentioned to the consumer —
 * see `docs/local-api-for-diafor.md`.
 */
object DiaForApi {

    private const val TAG = "DiaForApi"

    /** Per-request window: new readings and edits of the recent past. */
    private const val LIVE_WINDOW_MS = 6L * 3_600_000
    private const val LIVE_MIN_INTERVAL_MS = 5_000L

    /** Sized off `SGV_COUNT_DEEP` (~14 days) plus a day of margin. */
    private const val CATCHUP_WINDOW_MS = 15L * 24 * 3_600_000
    private const val CATCHUP_MIN_INTERVAL_MS = 10L * 60_000

    private const val BACKFILL_CHUNK_MS = 7L * 24 * 3_600_000
    private const val KEY_BACKFILL_CURSOR = "backfill_cursor_ms"

    /**
     * A dose stamped slightly in the future by a fast-running pen clock must
     * not be published and then, on the next pass, reported deleted.
     */
    private const val FUTURE_SLACK_MS = 24L * 3_600_000

    /** Serializes «read the facts, then reconcile them» across all passes. */
    private val lock = Any()

    @Volatile
    private var store: SqliteEventJournalStore? = null

    @Volatile
    private var journalRef: EventJournal? = null

    @Volatile
    private var lastLiveMs = 0L

    @Volatile
    private var lastCatchupMs = 0L

    @Volatile
    private var backfillRunning = false

    @Synchronized
    private fun open(context: Context): Pair<SqliteEventJournalStore, EventJournal> {
        store?.let { s -> journalRef?.let { j -> return s to j } }
        val s = SqliteEventJournalStore(context.applicationContext)
        val j = EventJournal(s)
        store = s
        journalRef = j
        return s to j
    }

    /**
     * Serve one request. Never throws: a failure here must not take down the
     * watch server that shares this socket.
     */
    fun handle(context: Context, params: Map<String, String>): ApiResponse {
        val (js, journal) = try {
            open(context)
        } catch (e: Exception) {
            DiagLog.w(TAG, "journal unavailable: ${e.javaClass.simpleName}")
            return ApiResponse.unavailable("event journal is not available")
        }
        return try {
            sync(context, journal)
            startBackfill(context, js, journal)
            EventsApi.handle(params, journal)
        } catch (e: android.database.sqlite.SQLiteException) {
            // The source is there but momentarily unreadable (locked, disk
            // pressure) — a retry is the right advice, not a hard failure.
            DiagLog.w(TAG, "source unavailable: ${e.javaClass.simpleName}")
            ApiResponse.unavailable("data source temporarily unavailable")
        } catch (e: Exception) {
            DiagLog.w(TAG, "request failed: ${e.javaClass.simpleName}")
            ApiResponse.serverError("internal error")
        }
    }

    /**
     * Everything publishable in `[fromMs, toMs]` — the COMPLETE truth for that
     * window, which is exactly what a reconcile pass may be handed.
     */
    private fun factsIn(
        context: Context,
        store: CollectorStore,
        fromMs: Long,
        toMs: Long,
        asOfMs: Long,
    ): List<Fact> {
        val era = FoodEraSettings.era(context)
        return DiaForFacts.glucose(store.sensorReadingsWithSource(fromMs, toMs), era) +
            DiaForFacts.insulin(
                boluses = store.bolusesAll(fromMs, toMs),
                basal = store.basalEvents(fromMs, toMs),
                bolusProduct = Settings.bolusProduct(context),
                basalProduct = Settings.basalProduct(context),
                era = era,
            ) +
            store.annotations(fromMs, toMs).let { annotations ->
                DiaForFacts.meals(
                    annotations,
                    era,
                    annotations.mapNotNull { a ->
                        store.carbEvidenceKnownAt(a.id, asOfMs)?.let { a.id to it }
                    }.toMap(),
                )
            }
    }

    /**
     * Read and fold one window, atomically against every other pass.
     *
     * The window is clamped to the food-era start as well as
     * filtered by it. Filtering alone would be enough to keep older facts out,
     * but not to keep older facts SAFE: a window reaching below the floor would
     * sweep anything already journaled down there and publish it as deleted.
     */
    private fun foldWindow(context: Context, journal: EventJournal, from: Long, toMs: Long): Int =
        synchronized(lock) {
            val fromMs = FoodEraSettings.era(context).clampFrom(from)
            if (fromMs > toMs) return 0
            val observedAtMs = System.currentTimeMillis()
            val facts = factsIn(context, Stores.get(context), fromMs, toMs, observedAtMs)
            // `now` is read INSIDE the lock, after the facts: it is the moment
            // this truth was observed. For history imported long before the
            // journal existed, received_at can say nothing better than the pass
            // that first saw it — and saying occurred_at instead would be a lie.
            journal.reconcile(fromMs, toMs, facts, observedAtMs)
        }

    private fun sync(context: Context, journal: EventJournal) {
        val now = System.currentTimeMillis()
        if (now - lastCatchupMs >= CATCHUP_MIN_INTERVAL_MS) {
            lastCatchupMs = now
            lastLiveMs = now                       // the wide pass subsumes the narrow one
            foldWindow(context, journal, now - CATCHUP_WINDOW_MS, now + FUTURE_SLACK_MS)
            return
        }
        if (now - lastLiveMs < LIVE_MIN_INTERVAL_MS) return
        lastLiveMs = now
        foldWindow(context, journal, now - LIVE_WINDOW_MS, now + FUTURE_SLACK_MS)
    }

    /**
     * Walk the history once, oldest chunk first, on a background thread.
     *
     * Resumable: the cursor is persisted after every chunk, so a kill mid-walk
     * costs one chunk, not the whole pass. It stops where the catch-up window
     * begins, so every stretch of time is owned by exactly one pass.
     */
    private fun startBackfill(
        context: Context,
        js: SqliteEventJournalStore,
        journal: EventJournal,
    ) {
        if (backfillRunning) return
        val app = context.applicationContext
        val handOff = System.currentTimeMillis() - CATCHUP_WINDOW_MS
        val oldest = js.meta(KEY_BACKFILL_CURSOR)?.toLongOrNull()
            ?: (Stores.get(app) as? SqliteCollectorStore)
                ?.oldestFactMs()
            ?: return                       // nothing stored yet — the live pass covers it
        // Start AT the floor, not at the oldest row: walking from an old import
        // could mean dozens of empty weekly chunks before the first publishable fact.
        val cursor = FoodEraSettings.era(app).clampFrom(oldest)
        if (cursor >= handOff) return       // history already walked
        backfillRunning = true
        Thread({
            try {
                var at = cursor
                while (at < System.currentTimeMillis() - CATCHUP_WINDOW_MS) {
                    foldWindow(app, journal, at, at + BACKFILL_CHUNK_MS)
                    at += BACKFILL_CHUNK_MS
                    js.setMeta(KEY_BACKFILL_CURSOR, at.toString())
                }
                // The build ledger for this endpoint — counts and dates only,
                // never a value. `adb logcat -s DiaForApi` answers «how deep
                // does the history go» without opening the database.
                val s = js.stats()
                DiagLog.i(
                    TAG,
                    "backfill complete: ${s.entries} entries / ${s.facts} facts, " +
                        "seq<=${s.maxSeq}, oldest=${s.oldestOccurredMs} newest=${s.newestOccurredMs}",
                )
            } catch (e: Exception) {
                DiagLog.w(TAG, "backfill stopped: ${e.javaClass.simpleName}")
            } finally {
                backfillRunning = false
            }
        }, "diafor-backfill").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
