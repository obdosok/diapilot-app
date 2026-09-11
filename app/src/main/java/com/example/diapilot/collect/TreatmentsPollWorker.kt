package com.example.diapilot.collect

import android.content.Context
import android.util.Log
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
import com.example.diapilot.data.Stores
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
        (store as? com.example.diapilot.data.SqliteCollectorStore)?.let {
            com.example.diapilot.data.LedgerRetention.runIfDue(applicationContext, it)
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
        val ownBle = com.example.diapilot.data.Settings.ownBleEnabled(applicationContext)

        // Equipment watchdog: in own-BLE mode a silent stall (nonce desync,
        // BT off, sensor gone) must become a notification, not an empty
        // chart discovered an hour later. The notifier no-ops on fresh data.
        if (ownBle) {
            StreamStallNotifier.maybeNotify(applicationContext, com.example.diapilot.R.string.stream_stall_notifier_reason_stopped)
        }

        // 1. Glucose backfill from sgv.json (confirmed reachable in the phone browser).
        try {
            if (!ownBle) {
                fetch("$BASE_URL/sgv.json?count=$sgvCount")?.let { json ->
                    val readings = parseSgvEntries(jsonToMaps(json))
                    readings.forEach(store::upsertReading)
                    Log.d(TAG, "sgv.json: ${readings.size} readings backfilled (deep=$deep)")
                    if (deep) prefsAll.edit().putBoolean(PREF_DEEP_DONE, true).apply()
                    anySuccess = true
                }
            } else {
                anySuccess = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "sgv.json poll failed: ${e.message}")
        }

        // 2. Pebble: the live display value (exactly what the xDrip screen shows) + IOB.
        try {
            fetch("$BASE_URL/pebble")?.let { json ->
                val bgs = JSONObject(json).optJSONArray("bgs")
                if (bgs != null && bgs.length() > 0) {
                    val now = parsePebbleBg(bgs.getJSONObject(0).toMap())
                    if (!ownBle) now.reading?.let(store::upsertReading)
                    now.iobUnits?.let { iob ->
                        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putFloat(PREF_IOB, iob.toFloat())
                            .putLong(PREF_IOB_TS, System.currentTimeMillis())
                            .apply()
                    }
                    Log.d(TAG, "pebble: now=${now.reading?.mmol} iob=${now.iobUnits}")
                    anySuccess = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pebble poll failed: ${e.message}")
        }

        // 3. Insulin from treatments (path differs across xDrip builds — probe candidates).
        try {
            val json = fetchFirst(TREATMENT_PATHS.map { "$BASE_URL$it?count=$treatmentsCount" })
            if (json != null) {
                val events = parseTreatments(jsonToMaps(json))
                events.forEach(store::upsertInsulin)
                // Deletion sync: within the span the response covers, anything we
                // have locally that xDrip no longer returns was deleted there.
                if (events.isNotEmpty()) {
                    val minTs = events.minOf { it.tsMs }
                    val fetched = events.mapTo(HashSet()) { it.tsMs }
                    // Manual/watch-entered shots are unknown to xDrip — the
                    // deletion sync must leave them alone.
                    val protected = store.nonSyncInsulinTs(minTs)
                    val stale = store.boluses(minTs, Long.MAX_VALUE)
                        .filter { it.tsMs !in fetched && it.tsMs !in protected }
                    stale.forEach { store.deleteInsulin(it.tsMs) }
                    if (stale.isNotEmpty()) Log.i(TAG, "removed ${stale.size} deleted-in-xDrip boluses")
                }
                // The same shot may exist both ways (pen NFC here + the pen
                // history scanned into xDrip) with clocks seconds apart —
                // collapse to the non-sync copy.
                val duped = store.dedupeSyncedBoluses()
                if (duped > 0) Log.i(TAG, "dropped $duped synced boluses duplicating pen/manual doses")
                Log.d(TAG, "treatments: ${events.size} insulin events")
                anySuccess = true
            } else {
                Log.w(TAG, "No treatments endpoint answered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "treatments poll failed: ${e.message}")
        }

        // 4. Physiology from Health Connect (no-op until permissions granted).
        try {
            com.example.diapilot.data.HealthConnectSync.sync(applicationContext, store)
        } catch (e: Exception) {
            Log.w(TAG, "HC sync failed: ${e.message}")
        }

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
            Log.d(TAG, "meal scan: $found events, $quiet quiet successes promoted")
            notifyFreshMeals(now)
            // Second, independent driver for closed-episode receipts.
            //
            // The collector heartbeat is the primary one, but it only fires on
            // an OOP2 broadcast — so a sensor gap, a killed service or a warm-up
            // period would stall learning exactly the way the screen-only path
            // did. This worker is periodic and survives all three. The runtime's
            // own throttle makes the overlap free.
            Result.success()
        } else {
            Result.retry()
        }
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
        val era=com.example.diapilot.data.FoodEraSettings.current().startMs
        val topLabels = store.labeledMeals(500).filter { it.event.onsetMs>=era }
            .groupingBy { it.labelName }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(2)
        val notes = store.annotations(era, now)
        fresh.take(3).forEach { m ->
            val predicted = com.diapilot.core.analysis.predictDishAt(notes, m.onsetMs)?.dish
            val labels = (listOfNotNull(predicted) + topLabels).distinct().take(2)
            MealNotifier.notifyMeal(applicationContext, m, labels)
        }
        prefs.edit().putLong(PREF_LAST_NOTIFIED, fresh.maxOf { it.onsetMs }).apply()
        Log.d(TAG, "notified about ${fresh.size} fresh meals")
    }


    /** Returns the body of the first URL answering 200 with a JSON array. */
    private fun fetchFirst(urls: List<String>): String? {
        for (url in urls) {
            val body = try {
                fetch(url)
            } catch (e: Exception) {
                Log.d(TAG, "$url: ${e.message}")
                null
            }
            if (body != null && body.trimStart().startsWith("[")) {
                Log.d(TAG, "treatments endpoint: $url")
                return body
            }
        }
        return null
    }

    private fun fetch(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) {
                Log.d(TAG, "$url -> HTTP ${conn.responseCode}")
                null
            } else {
                conn.inputStream.bufferedReader().readText()
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "TreatmentsPollWorker"
        private const val BASE_URL = "http://127.0.0.1:17580"
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
