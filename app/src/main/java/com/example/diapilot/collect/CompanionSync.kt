package com.example.diapilot.collect

import android.content.Context
import android.util.Log
import com.example.diapilot.i18n.localized
import org.json.JSONArray
import org.json.JSONObject

/**
 * Push the live status to the personal companion server (server/ in this
 * repo): current glucose + smoothed trend, the twin forecast with both
 * corridor bands, IOB, recent events and a 3h history tail — everything the
 * remote dashboard renders. Plus the daily database file as an off-device
 * backup.
 *
 * Fire-and-forget: any network failure is logged and swallowed — the
 * companion must never affect the phone app. Configured in Settings
 * (URL + token); silent no-op when unset.
 */
object CompanionSync {
    private const val TAG = "CompanionSync"
    private const val PUSH_PERIOD_MS = 60_000L
    @Volatile private var lastPushMs = 0L

    /** Called on every fresh minute of data (collector heartbeat). */
    fun pushIfDue(context: Context) {
        val url = com.example.diapilot.data.Settings.companionUrl(context) ?: return
        val token = com.example.diapilot.data.Settings.companionToken(context) ?: return
        val now = System.currentTimeMillis()
        if (now - lastPushMs < PUSH_PERIOD_MS) return
        lastPushMs = now
        Thread {
            try {
                val body = buildSnapshot(context, now) ?: return@Thread
                post("$url/api/push", token, body.toString().toByteArray(), "application/json")
            } catch (e: Exception) {
                Log.w(TAG, "push failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Upload today's DB copy; called from the daily auto-backup.
     *
     * THIS IS THE CODE THAT STOPPED THE APP FROM OPENING, and the mechanism is worth
     * keeping written down because nothing about it looked like a memory bug.
     *
     * The body used to be assembled in the HEAP as `head + buf.toByteArray() + tail`.
     * That is FOUR live copies of the whole database: the `ByteArrayOutputStream`'s
     * internal array (which doubles as it grows), `toByteArray()`, and one fresh array
     * per `+` — `ArraysKt.plus` is `Arrays.copyOf`. At a 67 MB database that is ~270 MB
     * of live byte arrays against ART's 256 MB growth limit, so it threw
     * OutOfMemoryError — on a raw thread started from `MainActivity.onCreate`, i.e. on
     * EVERY launch. `autoBackupIfDue` writes its «done for today» stamp only AFTER this
     * call returns, so the crash also guaranteed a retry on the next launch, and
     * ActivityManager eventually stopped restarting the process:
     * «has crashed too many times, killing! Reason: crashed quickly».
     *
     * It is also why the user experienced it as a slow degradation rather than a
     * regression: the database grows ~0.35 MB/day, and nothing failed until the four
     * copies crossed the limit.
     *
     * Now the export is spooled to a file and streamed. The bytes on the wire are
     * unchanged (same multipart framing, same Content-Length); the heap cost is one
     * 8 KB copy buffer.
     */
    fun uploadBackup(context: Context) {
        val url = com.example.diapilot.data.Settings.companionUrl(context) ?: return
        val token = com.example.diapilot.data.Settings.companionToken(context) ?: return
        try {
            val store = com.example.diapilot.data.Stores.get(context)
                as? com.example.diapilot.data.SqliteCollectorStore ?: return
            val spool = java.io.File.createTempFile("dpbackup", ".sqlite", context.cacheDir)
            try {
                spool.outputStream().use { store.exportSnapshot(it) }
                val boundary = "----diapilot${System.currentTimeMillis()}"
                val head = ("--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"diapilot.sqlite\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
                val tail = "\r\n--$boundary--\r\n".toByteArray()
                postStreaming(
                    "$url/api/backup", token,
                    contentLength = head.size + spool.length() + tail.size,
                    contentType = "multipart/form-data; boundary=$boundary",
                    timeoutMs = 60_000,
                ) { out ->
                    out.write(head)
                    spool.inputStream().use { it.copyTo(out) }
                    out.write(tail)
                }
                Log.i(TAG, "backup uploaded (${spool.length()} bytes)")
            } finally {
                spool.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "backup upload failed: ${e.message}")
        }
    }

    /**
     * [post] for a body too large to hold in memory. `setFixedLengthStreamingMode`
     * keeps a real Content-Length (so the server sees exactly the same request as
     * before, not a chunked one) while letting the connection write straight through.
     */
    private fun postStreaming(
        url: String,
        token: String,
        contentLength: Long,
        contentType: String,
        timeoutMs: Int = 10_000,
        writeBody: (java.io.OutputStream) -> Unit,
    ) {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(contentLength)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { java.io.BufferedOutputStream(it).use(writeBody) }
            val code = conn.responseCode
            if (code !in 200..299) Log.w(TAG, "POST $url -> $code")
        } finally {
            conn.disconnect()
        }
    }

    private fun post(
        url: String,
        token: String,
        body: ByteArray,
        contentType: String,
        timeoutMs: Int = 10_000,
    ) {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) Log.w(TAG, "POST $url -> $code")
        } finally {
            conn.disconnect()
        }
    }

    /** The same lenses as the widget/watch — the dashboard must agree. */
    private fun buildSnapshot(context: Context, now: Long): JSONObject? {
        val text = context.localized()
        val store = com.example.diapilot.data.Stores.get(context)
        val mgdl = com.example.diapilot.data.Units.isMgdl(context)
        val minuteCal = com.example.diapilot.data.MinuteCalCache.get(store, context)

        // Meter-correction lens — SAME as the widget/phone header (cached);
        // without it the dashboard shows the raw sensor scale.
        val meterCal = com.example.diapilot.data.MeterCalCache.get(store, context)
        fun lens(ts: Long, mmol: Double): Double = meterCal?.correctedAt(ts, mmol) ?: mmol

        val sharedAnchor = com.example.diapilot.data.forecastAnchor(
            store, context, now,
        ) ?: return null
        val bgTs = sharedAnchor.reading.tsMs
        val bgMmol = sharedAnchor.reading.mmol

        // The minute stream still feeds the FORECAST (fresh momentum); the
        // TREND comes off the meter-calibrated 5-min MAIN grid (same source as
        // the phone header). The grid is the FROZEN real-time record; the
        // 1-min oop2 stream retroactively revises its history and overshot
        // during a rapid drop. Minute stream is the
        // sparse-grid trend fallback only.
        val minutePts = sharedAnchor.minutePoints
        val gridPts = store.sensorReadings(now - 16L * 60_000, now)
            .map { com.diapilot.core.collector.GlucosePoint(it.tsMs, lens(it.tsMs, it.mmol)) }
        val trend = com.diapilot.core.twin.gridTrendReadout(gridPts, now)
            ?: com.diapilot.core.twin.trendReadout(minutePts, now)

        // Forecast — the ONE shared engine (not re-recorded: mirrors "main").
        val model = com.example.diapilot.data.TwinCache.getForForecast(store, context)
        val forecast = if (model != null) {
            try {
                com.example.diapilot.data.Forecaster.forecast(
                    store, model, now, anchorTsMs = bgTs, anchorMmol = bgMmol,
                    minutePoints = minutePts,
                )?.points ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()

        val iob = com.example.diapilot.data.HybridRuntimeMetrics
            .surfaceIobUnits(store, context, now) ?: 0.0
        val lastBolus = store.boluses(now - 8L * 3_600_000, now)
            .filter { it.purpose != "воздух" }.lastOrNull()
        val insulinLine = buildString {
            if (iob >= 0.2) append("IOB %.1f".format(java.util.Locale.ENGLISH, iob))
            lastBolus?.let { b ->
                if (isNotEmpty()) append(" · ")
                val min = (now - b.tsMs) / 60_000
                val durationText = if (min < 60) {
                    text.getString(com.example.diapilot.R.string.companion_sync_minutes_compact, min)
                } else {
                    text.getString(
                        com.example.diapilot.R.string.companion_sync_hours_minutes_compact,
                        min / 60, min % 60,
                    )
                }
                append(text.getString(com.example.diapilot.R.string.companion_sync_insulin_line, b.units, durationText))
            }
        }

        // Settle headline — same wording as the phone header.
        val future = forecast.filter { it.tsMs > now }
        val settleIdx = com.diapilot.core.analysis.settleIndex(future.map { it.mmol })
        val settlePt = settleIdx?.let { future[it] }
        val statusLine = settlePt?.let { p ->
            com.example.diapilot.i18n.StatusText.lines(
                context,
                com.diapilot.core.analysis.statusSummary(
                    com.diapilot.core.analysis.StatusInput(
                        iobUnits = null, lastBolusUnits = null, lastBolusAgeMin = null,
                        foodLabel = null, foodAgeMin = null, foodCarbs = null,
                        predMmolIn60 = p.mmol, predLoIn60 = p.loMid, predHiIn60 = p.hiMid,
                        predSettleMin = (p.tsMs - now) / 60_000,
                        predSettled = settleIdx < future.size - 1,
                        mgdl = mgdl,
                    ),
                ),
            ).joinToString(com.example.diapilot.i18n.StatusText.SEPARATOR)
        } ?: ""

        // Events: food/notes + boluses over the last 3h, newest first.
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
        val events = JSONArray()
        (
            store.annotations(now - 3L * 3_600_000, now)
                .filter { it.kind != "tag" || it.content.isNotBlank() }
                .map { a ->
                    val time = fmt.format(java.util.Date(a.tsMs))
                    val content = com.example.diapilot.i18n.TokenText.noteTag(context, a.content)
                    val line = a.estCarbs?.let { c ->
                        text.getString(com.example.diapilot.R.string.companion_sync_event_note_with_carbs, time, content, c)
                    } ?: text.getString(com.example.diapilot.R.string.companion_sync_event_note, time, content)
                    a.tsMs to line
                } +
                store.boluses(now - 3L * 3_600_000, now)
                    .map { b ->
                        val time = fmt.format(java.util.Date(b.tsMs))
                        val purpose = com.example.diapilot.i18n.TokenText.bolusPurpose(context, b.purpose)
                        val line = purpose?.let { p ->
                            text.getString(com.example.diapilot.R.string.companion_sync_event_bolus_with_purpose, time, b.units, p)
                        } ?: text.getString(com.example.diapilot.R.string.companion_sync_event_bolus, time, b.units)
                        b.tsMs to line
                    }
            )
            .sortedByDescending { it.first }
            .take(8)
            .forEach { events.put(it.second) }

        // 3h history downsampled to ~5-min steps — through the same lens.
        val history = JSONArray()
        val hist = com.example.diapilot.data.displayHistory(
            store, context, now - 3L * 3_600_000, now,
        ).map { it.tsMs to it.mmol }
        var lastKept = 0L
        for ((t, m) in hist) {
            if (t - lastKept >= 5 * 60_000 || t == hist.last().first) {
                history.put(JSONObject().put("t", t).put("m", m))
                lastKept = t
            }
        }

        val forecastArr = JSONArray()
        forecast.filter { it.tsMs >= now }.forEach { p ->
            forecastArr.put(
                JSONObject().put("t", p.tsMs).put("m", p.mmol)
                    .put("lo", p.lo).put("hi", p.hi)
                    .put("lm", p.loMid).put("hm", p.hiMid),
            )
        }

        val rangeLo = com.example.diapilot.data.Settings.rangeLoMmol(context)
        val rangeHi = com.example.diapilot.data.Settings.rangeHiMmol(context)
        val tir = com.example.diapilot.data.tir24h(store, context, now, rangeLo, rangeHi)
        return JSONObject()
            .put("mgdl", mgdl)
            .put("bg_mmol", bgMmol)
            .put("bg_ts_ms", bgTs)
            .put("arrow", trend?.delta5Mmol?.let {
                com.diapilot.core.trendGlyph(com.diapilot.core.trendName(it))
            } ?: "")
            .put("delta_mmol", trend?.delta5Mmol)
            .put("nuance", trend?.nuance?.let { com.example.diapilot.i18n.TwinText.nuance(context, it) } ?: "")
            .put("status_line", statusLine)
            .put("insulin_line", insulinLine)
            .put("range_lo", rangeLo)
            .put("range_hi", rangeHi)
            .put("tir", tir?.inRange)
            .put("tir_low", tir?.low)
            .put("tir_high", tir?.high)
            .put("events", events)
            .put("history", history)
            .put("forecast", forecastArr)
    }
}
