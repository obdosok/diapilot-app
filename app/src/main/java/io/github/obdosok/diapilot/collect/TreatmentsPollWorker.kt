package io.github.obdosok.diapilot.collect

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.diapilot.core.collector.parsePebbleBg
import com.diapilot.core.collector.parseSgvEntries
import com.diapilot.core.collector.parseTreatments
import com.diapilot.core.collector.scanMeals
import io.github.obdosok.diapilot.data.FoodEraSettings
import io.github.obdosok.diapilot.data.HealthConnectSync
import io.github.obdosok.diapilot.data.LedgerRetention
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.SqliteCollectorStore
import io.github.obdosok.diapilot.data.Stores
import io.github.obdosok.diapilot.diag.DiagLog
import io.github.obdosok.diapilot.diag.Redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Polls the xDrip local Nightscout-compatible web service (127.0.0.1:17580).
 * Loopback only — no cloud.
 *
 * Two feeds per run, both idempotent by timestamp:
 *  - `/sgv.json` — glucose backfill for readings missed by the broadcast channel;
 *  - treatments — insulin events. The exact path varies by xDrip build, so the
 *    first candidate that answers with a JSON array is remembered and reused.
 */
class TreatmentsPollWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Stores.get(applicationContext)
        var anySuccess = false

        // Ledger retention, at most once a day and failing open. It lives here
        // rather than on a screen refresh because it is maintenance, not state:
        // the UI must never wait on a DELETE that touches a million rows.
        (store as? SqliteCollectorStore)?.let {
            LedgerRetention.runIfDue(applicationContext, it)
        }

        // Deep backfill: once per install (xDrip keeps weeks of history — grab
        // ~14 days for analytics), and again after long downtime. Steady state
        // stays light.
        val prefsAll = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastTs = store.lastSensorReading()?.tsMs ?: 0
        val deep = !prefsAll.getBoolean(PREF_DEEP_DONE, false) ||
            System.currentTimeMillis() - lastTs > 24 * 3_600_000L
        val sgvCount = if (deep) SGV_COUNT_DEEP else SGV_COUNT
        val treatmentsCount = if (deep) TREATMENTS_COUNT_DEEP else TREATMENTS_COUNT

        // Own-BLE mode: glucose comes from OUR sensor link only; xDrip's
        // scale must not leak into the store (5-minute sawtooth otherwise).
        val ownBle = Settings.ownBleEnabled(applicationContext)

        // The equipment watchdog used to be raised HERE, and only in own-BLE
        // mode. It has moved to [AlertTick] at the end of this run, together
        // with every other alert: a stall is a stall on whatever the phone's
        // source is, and hanging it off one sensor-direct switch left every
        // other phone to go blind in silence (docs/audit.md, P7).

        // NO xDRIP, NO POLL. Port 17580 is a socket, not a trusted peer: with
        // xDrip absent, whatever answers there is something else, and this
        // run would store its glucose and — through the fuse, but still —
        // its insulin. The same precondition the broadcast receiver applies
        // (see [XdripBgReceiver.handle]); logged once, not every 15 minutes,
        // because the ring in [DiagLog] holds 800 lines and this line would
        // say the same thing 96 times a day.
        val pollXdrip = xdripPollAllowed(applicationContext)
        if (pollXdrip) {
            try {
                pollXdripWebService(store, ownBle, deep, sgvCount, treatmentsCount, prefsAll)
                anySuccess = true
            } catch (e: Exception) {
                DiagLog.w(TAG, "xDrip web-service poll failed: ${e.message}")
            }
        }

        // 4. Physiology from Health Connect (no-op until permissions granted).
        try {
            HealthConnectSync.sync(applicationContext, store)
        } catch (e: Exception) {
            DiagLog.w(TAG, "HC sync failed: ${e.message}")
        }

        // THE PERIODIC BACKSTOP, and the only line of it.
        //
        // OUTSIDE the `anySuccess` branch on purpose: a phone whose web
        // service never answers is exactly the phone whose alerts nothing else
        // evaluates, and the stall notification matters most when the poll
        // itself is failing. This worker is the app's existing 15-minute
        // periodic work (see [schedule]) and runs in both editions, so it
        // covers a phone whose only source is this poll, a phone whose stream
        // has gone quiet, and a confirmed low that must keep re-firing while
        // no new reading arrives. The tick de-duplicates, so the one-shot
        // [pollNow] each accepted broadcast triggers costs nothing. It fires
        // whether or not xDrip is installed: the poll was skipped, the
        // alerts were not.
        AlertTick.fire(applicationContext, AlertTick.Source.WEB_POLL)

        if (anySuccess) {
            val now = System.currentTimeMillis()
            prefsAll.edit().putLong(PREF_LAST_POLL_TS, now).apply()
            // Only the tail can have changed. The previous implementation
            // rescanned 14 days every 15 minutes (~1,300 overlapping scans a
            // fortnight) even when xDrip returned the same points.
            val watermark = prefsAll.getLong(PREF_MEAL_SCAN_WATERMARK, 0L)
            val scanFrom = if (watermark > 0L) {
                watermark - MEAL_SCAN_OVERLAP_MS
            } else {
                now - FIRST_MEAL_SCAN_WINDOW_MS
            }
            val found = scanMeals(store, scanFrom, now)
            prefsAll.edit().putLong(PREF_MEAL_SCAN_WATERMARK, now).apply()
            val quiet = com.diapilot.core.collector.promoteQuietMeals(store, now)
            DiagLog.d(TAG, "meal scan: $found events, $quiet quiet successes promoted")
            notifyFreshMeals(now)
            // Second, independent driver for closed-episode receipts.
            //
            // The collector heartbeat is the primary one, but it only fires on
            // an OOP2 broadcast — so a sensor gap, a killed service or a warm-up
            // period would stall learning exactly the way the screen-only path
            // did. This worker is periodic and survives all three. The runtime's
            // own throttle makes the overlap free.
            Result.success()
        } else if (!pollXdrip) {
            // Nothing to retry: the peer is not there, and WorkManager's
            // back-off would only reschedule the same skip.
            Result.success()
        } else {
            Result.retry()
        }
    }

    /**
     * The three xDrip feeds of one run. Throws only if the caller's own
     * bookkeeping fails; each feed catches its own I/O so one endpoint that is
     * down does not cost the other two. Returns normally when at least one feed
     * answered — the caller counts that as a successful run.
     */
    private fun pollXdripWebService(
        store: com.diapilot.core.collector.CollectorStore,
        ownBle: Boolean,
        deep: Boolean,
        sgvCount: Int,
        treatmentsCount: Int,
        prefsAll: android.content.SharedPreferences,
    ) {
        var anySuccess = false
        // 1. Glucose backfill from sgv.json (confirmed reachable in the phone browser).
        try {
            if (!ownBle) {
                fetch("$BASE_URL/sgv.json?count=$sgvCount")?.let { json ->
                    val readings = parseSgvEntries(jsonToMaps(json), System.currentTimeMillis())
                    readings.forEach(store::upsertReading)
                    DiagLog.d(TAG, "sgv.json: ${readings.size} readings backfilled (deep=$deep)")
                    if (deep) prefsAll.edit().putBoolean(PREF_DEEP_DONE, true).apply()
                    anySuccess = true
                }
            } else {
                anySuccess = true
            }
        } catch (e: Exception) {
            DiagLog.w(TAG, "sgv.json poll failed: ${e.message}")
        }

        // 2. Pebble: the live display value (exactly what the xDrip screen shows) + IOB.
        try {
            fetch("$BASE_URL/pebble")?.let { json ->
                val bgs = JSONObject(json).optJSONArray("bgs")
                if (bgs != null && bgs.length() > 0) {
                    val now = parsePebbleBg(bgs.getJSONObject(0).toMap(), System.currentTimeMillis())
                    if (!ownBle) now.reading?.let(store::upsertReading)
                    now.iobUnits?.let { iob ->
                        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putFloat(PREF_IOB, iob.toFloat())
                            .putLong(PREF_IOB_TS, System.currentTimeMillis())
                            .apply()
                    }
                    // Presence, not the pair of values (docs/audit.md, S10):
                    // this line existed to prove the endpoint answered, and a
                    // glucose reading plus insulin on board is the most
                    // sensitive line the app ever wrote.
                    DiagLog.d(
                        TAG,
                        "pebble: reading ${if (now.reading == null) "absent" else "present"}, " +
                            "iob ${Redact.insulin()}",
                    )
                    anySuccess = true
                }
            }
        } catch (e: Exception) {
            DiagLog.w(TAG, "pebble poll failed: ${e.message}")
        }

        // 3. Insulin from treatments (path differs across xDrip builds — probe candidates).
        try {
            val json = fetchFirst(TREATMENT_PATHS.map { "$BASE_URL$it?count=$treatmentsCount" })
            if (json != null) {
                val events = parseTreatments(jsonToMaps(json))
                // ONE FUSE FOR EVERY INSULIN INPUT. Port 17580 is a socket, not
                // a trusted peer: with xDrip stopped any app holding INTERNET
                // can bind it and answer this poll. Every dose therefore passes
                // the same guard the LLM path passes before it reaches IOB, the
                // forecast and the hypo alert.
                val accepted = com.diapilot.core.analysis.acceptedInsulinEvents(events)
                accepted.forEach(store::upsertInsulin)
                if (accepted.size != events.size) {
                    // Count only — the refused dose is medical payload.
                    DiagLog.w(TAG, "dose guard refused ${events.size - accepted.size} treatments")
                }
                // Deletion sync: within the span the response covers, anything we
                // have locally that xDrip no longer returns was deleted there.
                // It reads the FULL parsed list on purpose: a refused dose was
                // still reported by the source, and treating its timestamp as
                // "no longer there" would delete the local row it collides with.
                if (events.isNotEmpty()) {
                    val minTs = events.minOf { it.tsMs }
                    val fetched = events.mapTo(HashSet()) { it.tsMs }
                    // Manual/watch-entered shots are unknown to xDrip — the
                    // deletion sync must leave them alone.
                    val protected = store.nonSyncInsulinTs(minTs)
                    val stale = store.boluses(minTs, Long.MAX_VALUE)
                        .filter { it.tsMs !in fetched && it.tsMs !in protected }
                    stale.forEach { store.deleteInsulin(it.tsMs) }
                    if (stale.isNotEmpty()) DiagLog.i(TAG, "removed ${stale.size} deleted-in-xDrip boluses")
                }
                // The same shot may exist both ways (pen NFC here + the pen
                // history scanned into xDrip) with clocks seconds apart —
                // collapse to the non-sync copy.
                val duped = store.dedupeSyncedBoluses()
                if (duped > 0) DiagLog.i(TAG, "dropped $duped synced boluses duplicating pen/manual doses")
                DiagLog.d(TAG, "treatments: ${events.size} insulin events")
                anySuccess = true
            } else {
                DiagLog.w(TAG, "No treatments endpoint answered")
            }
        } catch (e: Exception) {
            DiagLog.w(TAG, "treatments poll failed: ${e.message}")
        }
        if (!anySuccess) throw java.io.IOException("no xDrip endpoint answered")
    }

    /**
     * Notify about newly detected, still-unlabeled meals. Only recent onsets
     * (last 2h) qualify — the first backfill after install must not spam with
     * old rises. High-water mark in prefs keeps each meal to one notification.
     */
    private fun notifyFreshMeals(now: Long) {
        val store = Stores.get(applicationContext)
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastNotified = prefs.getLong(PREF_LAST_NOTIFIED, 0)
        val fresh = store.unlabeledMeals()
            .filter { it.onsetMs > lastNotified && now - it.onsetMs <= 2 * 3_600_000 }
        if (fresh.isEmpty()) return
        // Buttons are the TIME-OF-DAY prediction first (the diet is a
        // timetable: the daily smoothie lands at 12:48±12 min, so the right
        // button is knowable from the onset hour), backed by a global
        // frequent label — not two globals, which mostly offered dinner
        // dishes at breakfast.
        val era=FoodEraSettings.current().startMs
        val topLabels = store.labeledMeals(500).filter { it.event.onsetMs>=era }
            .groupingBy { it.labelName }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(2)
        val notes = store.annotations(era, now)
        fresh.take(3).forEach { m ->
            val predicted = com.diapilot.core.analysis.predictDishAt(notes, m.onsetMs)?.dish
            val labels = (listOfNotNull(predicted) + topLabels).distinct().take(2)
            MealNotifier.notifyMeal(applicationContext, m, labels)
        }
        prefs.edit().putLong(PREF_LAST_NOTIFIED, fresh.maxOf { it.onsetMs }).apply()
        DiagLog.d(TAG, "notified about ${fresh.size} fresh meals")
    }


    /** Returns the body of the first URL answering 200 with a JSON array. */
    private fun fetchFirst(urls: List<String>): String? {
        for (url in urls) {
            val body = try {
                fetch(url)
            } catch (e: Exception) {
                DiagLog.d(TAG, "$url: ${e.message}")
                null
            }
            if (body != null && body.trimStart().startsWith("[")) {
                DiagLog.d(TAG, "treatments endpoint: $url")
                return body
            }
        }
        return null
    }

    /**
     * The `api-secret` header value, or null when the user configured none.
     * Read once per run: the secret is decrypted through the keystore, and this
     * worker makes several requests.
     */
    private val apiSecret: String? by lazy {
        com.diapilot.core.collector.xdripApiSecretHeader(
            Settings.xdripApiSecret(applicationContext),
        )
    }

    /**
     * One request to the xDrip web service. Every attempt and every 200 is
     * stamped into [DiagState], because the Data sources screen reports
     * "xDrip web service reachable" from those two stamps and a probe that was
     * never sent must not read as a service that never answered. Observation
     * only: nothing in this worker reads the stamps back.
     */
    private fun fetch(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        DiagState.lastXdripWebProbeMs = System.currentTimeMillis()
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            // Hashed, never the plaintext secret — the format xDrip expects.
            apiSecret?.let {
                conn.setRequestProperty(com.diapilot.core.collector.XDRIP_API_SECRET_HEADER, it)
            }
            if (conn.responseCode != 200) {
                DiagLog.d(TAG, "$url -> HTTP ${conn.responseCode}")
                null
            } else {
                DiagState.lastXdripWebOkMs = System.currentTimeMillis()
                conn.inputStream.bufferedReader().readText()
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "TreatmentsPollWorker"
        private const val BASE_URL = "http://127.0.0.1:17580"

        /** Whether the last run said "skipped" — so the next skip is silent
         *  and the first poll after an install is announced once too. */
        @Volatile private var skipLogged = false

        /**
         * May this run read 127.0.0.1:17580 at all? True when xDrip is
         * installed. Kept apart from the worker body so the decision — and its
         * once-only logging — is testable without WorkManager.
         */
        internal fun xdripPollAllowed(context: Context): Boolean {
            val installed = XdripApp.installed(context)
            if (!installed && !skipLogged) {
                DiagLog.w(TAG, "xDrip is not installed — web-service poll skipped until it is")
                skipLogged = true
            } else if (installed && skipLogged) {
                DiagLog.i(TAG, "xDrip installed — web-service poll resumed")
                skipLogged = false
            }
            return installed
        }

        /** Tests only: forget whether the skip was announced. */
        internal fun resetSkipLog() {
            skipLogged = false
        }
        private const val SGV_COUNT = 288          // ~24h of 5-min readings
        private const val SGV_COUNT_DEEP = 4032    // ~14 days, for first run / catch-up
        private const val TREATMENTS_COUNT = 100
        private const val TREATMENTS_COUNT_DEEP = 1000
        // /treatments.json confirmed working on the user's xDrip build; probe it first.
        private val TREATMENT_PATHS = listOf(
            "/treatments.json",
            "/api/v1/treatments",
            "/treatments",
        )
        private const val WORK_NAME = "xdrip_treatments_poll"
        const val PREFS = "diapilot"
        const val PREF_IOB = "iob_units"
        const val PREF_IOB_TS = "iob_ts_ms"
        const val PREF_LAST_NOTIFIED = "last_notified_onset_ms"
        const val PREF_DEEP_DONE = "deep_backfill_done"
        const val PREF_LAST_POLL_TS = "last_poll_ts_ms"
        const val PREF_MEAL_SCAN_WATERMARK = "meal_scan_watermark"

        /**
         * Poll if the last successful poll is older than maxAgeMin. Called from
         * the BG broadcast receiver: every xDrip reading doubles as a wake-up,
         * which keeps treatments/pebble flowing overnight when Doze defers the
         * periodic worker.
         */
        fun pollIfStale(context: Context, maxAgeMin: Long = 10) {
            val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(PREF_LAST_POLL_TS, 0)
            if (System.currentTimeMillis() - last > maxAgeMin * 60_000) pollNow(context)
        }
        private const val FIRST_MEAL_SCAN_WINDOW_MS = 14L * 24 * 60 * 60_000
        private const val MEAL_SCAN_OVERLAP_MS = 6L * 60 * 60_000

        /** Convert a Nightscout-compatible JSON array into the core parser's shape. */
        fun jsonToMaps(json: String): List<Map<String, Any?>> {
            val arr = JSONArray(json)
            return buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(obj.toMap())
                }
            }
        }

        private fun JSONObject.toMap(): Map<String, Any?> =
            keys().asSequence().associateWith { k -> if (isNull(k)) null else get(k) }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TreatmentsPollWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        /** Immediate one-shot poll (app opened / manual refresh) — periodic work may lag. */
        fun pollNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<TreatmentsPollWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_now", ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}
