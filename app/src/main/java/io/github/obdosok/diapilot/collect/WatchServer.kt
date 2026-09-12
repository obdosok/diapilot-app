package io.github.obdosok.diapilot.collect

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.api.DiaForApi
import io.github.obdosok.diapilot.data.Forecaster
import io.github.obdosok.diapilot.data.HybridRuntimeMetrics
import io.github.obdosok.diapilot.data.MeterCalCache
import io.github.obdosok.diapilot.data.MinuteCalCache
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.Stores
import io.github.obdosok.diapilot.data.TwinCache
import io.github.obdosok.diapilot.data.Units
import io.github.obdosok.diapilot.data.WatchApiToken
import io.github.obdosok.diapilot.data.displayHistory
import io.github.obdosok.diapilot.data.forecastAnchor
import io.github.obdosok.diapilot.data.tir24h
import io.github.obdosok.diapilot.i18n.StatusText
import io.github.obdosok.diapilot.i18n.localized
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

/**
 * WatchDrip-compatible local web service (Zepp OS watch faces).
 *
 * The WatchDrip mini-app on a Zepp OS watch polls
 * http://127.0.0.1:29863/info.json via the Zepp side-service; serving the
 * same JSON shape from DiaPilot lets an existing watch face keep working
 * with WatchDrip uninstalled. Schema reimplemented from observed behavior —
 * no WatchDrip code is copied.
 *
 * Loopback-only: the socket binds 127.0.0.1 and ::1 (the Zepp side-service
 * resolves "localhost", which on Android may mean either) — nothing is exposed
 * to the LAN or to 0.0.0.0.
 *
 * It also carries a second, unrelated endpoint: `GET /api/v1/events`, the
 * append-only change journal a companion app on this phone reads glucose and
 * insulin history from. It shares the socket and nothing else — `/info.json`
 * behaviour is unchanged, including its long poll, its text/plain body and its
 * field-for-field payload.
 *
 * Loopback is not a permission (any app holding `INTERNET` reaches 127.0.0.1),
 * so the two endpoints that are not the frozen watch feed are gated — see
 * [com.diapilot.core.api.localAccess]: `POST /add_treatments` and
 * `/api/v1/events` both require the per-installation token
 * ([WatchApiToken]), presented as
 * `X-DiaPilot-Token`, as `Authorization: Bearer …` or as a `token` parameter.
 * The user reads the token off the Settings screen and configures the client
 * with it.
 */
class WatchServer(private val context: Context) {

    companion object {
        private const val TAG = "WatchServer"
        const val PORT = 29863
        private const val MGDL_PER_MMOL = 18.0182

        /** Ceiling on a request body. The parameters fit in a few dozen
         *  characters; anything longer is not a client of ours. */
        private const val MAX_BODY_CHARS = 4096
    }

    // Both loopback stacks: the Zepp side-service resolves "localhost", which
    // on Android may mean ::1 — an IPv4-only bind reads as "server off".
    // Still loopback-only: nothing is exposed to the LAN.
    private val servers = java.util.concurrent.ConcurrentHashMap<String, ServerSocket>()

    @Volatile
    private var stopped = false

    fun start() {
        if (servers.isNotEmpty()) return
        stopped = false
        listOf("127.0.0.1", "::1").forEach { addr -> acceptLoop(addr) }
    }

    private fun acceptLoop(addr: String) {
        Thread({
            // WatchDrip may still own the port (its autostart receiver revives
            // it on every xDrip broadcast) — keep retrying until it's gone.
            while (!stopped) {
                try {
                    val ss = ServerSocket(PORT, 4, InetAddress.getByName(addr))
                    servers[addr] = ss
                    DiagState.watchServerUp = true
                    Log.i(TAG, "listening on $addr:$PORT")
                    while (!ss.isClosed) {
                        val client = ss.accept()
                        // Thread per connection: long-poll requests hold their
                        // socket for up to ~80s and must not block the others.
                        Thread({
                            try {
                                handle(client)
                            } catch (e: Exception) {
                                Log.w(TAG, "request failed: ${e.message}")
                            } finally {
                                runCatching { client.close() }
                            }
                        }, "diapilot-watch-req").apply { isDaemon = true }.start()
                    }
                } catch (e: Exception) {
                    if (servers.containsKey(addr)) {
                        servers.remove(addr)
                        if (servers.isEmpty()) DiagState.watchServerUp = false
                        if (stopped) break
                    } else {
                        Log.w(TAG, "$addr port busy, retry in 60s: ${e.message}")
                    }
                }
                if (!stopped) Thread.sleep(60_000)
            }
        }, "diapilot-watch-$addr").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        servers.values.forEach { runCatching { it.close() } }
        servers.clear()
        DiagState.watchServerUp = false
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        val reader = client.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break  // headers done
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.ENGLISH)] =
                    line.substring(colon + 1).trim()
            }
        }
        val words = requestLine.split(" ")
        val method = words.getOrNull(0)?.uppercase(Locale.ENGLISH) ?: "GET"
        val target = words.getOrNull(1) ?: "/"
        val r = respond(method, target, headers, readBody(reader, headers), client.inetAddress?.toString())
        val bytes = r.body.toByteArray()
        client.getOutputStream().apply {
            write(
                ("HTTP/1.1 ${r.status}\r\n" +
                    "Content-Type: ${r.contentType}\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    r.extraHeaders +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            write(bytes)
            flush()
        }
    }

    /**
     * The announced body, bounded and read as text.
     *
     * Bounded because an unbounded read on a socket any app can connect to is a
     * memory hole, and read at all because a POST whose body is never drained
     * can have the connection closed under a client that is still writing.
     */
    private fun readBody(reader: java.io.Reader, headers: Map<String, String>): String? {
        val announced = headers["content-length"]?.toIntOrNull() ?: return null
        if (announced <= 0) return null
        val buf = CharArray(minOf(announced, MAX_BODY_CHARS))
        var read = 0
        while (read < buf.size) {
            val n = reader.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    /** One response, ready to be written to the socket. */
    internal class Response(
        val status: String,
        val body: String,
        // text/plain mirrors WatchDrip's NanoHTTPD: the Zepp fetch must NOT
        // auto-parse the body — the watch app expects a string and does its
        // own str2json. The journal endpoint has no such constraint and says
        // what it actually serves.
        val contentType: String = "text/plain",
        // Extra headers go ONLY to the new route: the watch response stays
        // byte-for-byte what it has always been, headers included.
        val extraHeaders: String = "",
    )

    /**
     * The whole request → response decision, with no socket in it: the tests
     * drive this directly, so routing, the fuse and the response codes are
     * checked without binding a port or racing an accept loop.
     */
    internal fun respond(
        method: String,
        target: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        peer: String? = null,
    ): Response {
        val parsed = com.diapilot.core.api.parseRequestTarget(target)
        val path = parsed.path
        // A form-encoded body carries the same parameters as the query string,
        // so a client may POST either way; the query wins on a collision.
        val params = if (body.isNullOrEmpty() ||
            headers["content-type"]?.startsWith("application/x-www-form-urlencoded") != true
        ) {
            parsed.params
        } else {
            com.diapilot.core.api.parseFormEncoded(body) + parsed.params
        }
        val route = com.diapilot.core.api.routeFor(path)
        // Path only. The query string is where the medical payload lives —
        // /add_treatments?insulin=4.5&carbs=30 was going into the production
        // log verbatim.
        Log.i(TAG, "request from $peer: $method $path")

        // The gate. `/info.json` passes untouched (see localAccess); the two
        // endpoints that write a dose or publish history need the
        // per-installation token, and `/add_treatments` needs a POST first.
        val access = com.diapilot.core.api.localAccess(
            route,
            method,
            com.diapilot.core.api.presentedToken(headers, params),
            // Minted here rather than at start(): the token then exists as soon
            // as anything asks for it, without the server having to run first.
            WatchApiToken.getOrCreate(context),
        )
        when (access) {
            com.diapilot.core.api.LocalAccess.METHOD_NOT_ALLOWED -> {
                Log.w(TAG, "$path: $method not allowed")
                return Response(
                    "405 Method Not Allowed",
                    "Method Not Allowed",
                    extraHeaders = "Allow: POST\r\n",
                )
            }
            com.diapilot.core.api.LocalAccess.UNAUTHORIZED -> {
                Log.w(TAG, "$path: refused, no valid token")
                return Response("401 Unauthorized", "Unauthorized")
            }
            com.diapilot.core.api.LocalAccess.OK -> Unit
        }

        // Long-poll: hold the response until a reading NEWER than newer_than
        // arrives (or the wait budget runs out). The xDrip broadcast lands in
        // the store within a second of the sensor grid, so the watch gets its
        // fresh point seconds after it exists — no polling races.
        // Watch routes only: the journal endpoint pages, it does not wait.
        if (route == com.diapilot.core.api.LocalRoute.INFO_JSON) params["newer_than"]?.toLongOrNull()?.let { since ->
            val waitS = (params["wait"]?.toIntOrNull() ?: 75).coerceIn(1, 110)
            val deadline = System.currentTimeMillis() + waitS * 1000L
            val store = Stores.get(context)
            // Event-driven: block on DataPulse (writers pulse per landed
            // reading) instead of polling SQLite every second. The 10 s cap
            // per wait is a safety net against a missed pulse.
            while (System.currentTimeMillis() < deadline) {
                val ts = store.lastSensorReading()?.tsMs ?: 0
                // The calibrated minute stream releases the poll too — the
                // wrist updates every minute, not on the 5-minute grid.
                val mts = if (MinuteCalCache.get(store, context) != null) {
                    store.lastMinuteReading()?.tsMs ?: 0
                } else 0
                if (maxOf(ts, mts) > since) break
                DataPulse.await(
                    (deadline - System.currentTimeMillis()).coerceAtMost(10_000),
                )
            }
        }

        return when (route) {
            com.diapilot.core.api.LocalRoute.INFO_JSON ->
                Response("200 OK", infoJson(params["graph"] == "1"))
            com.diapilot.core.api.LocalRoute.ADD_TREATMENTS ->
                addTreatments(params).let { (status, body) -> Response(status, body) }
            com.diapilot.core.api.LocalRoute.EVENTS -> {
                val r = DiaForApi.handle(context, params)
                // Status only. The body is medical payload and never logged.
                Log.i(TAG, "events -> ${r.status}")
                Response(
                    "${r.status} ${r.reason}",
                    r.body,
                    contentType = "application/json; charset=utf-8",
                    // The journal is a moving target — an intermediary caching
                    // a page would strand a consumer on a stale next_after.
                    extraHeaders = "Cache-Control: no-store\r\n",
                )
            }
            com.diapilot.core.api.LocalRoute.NOT_FOUND -> Response("404 Not Found", "Not Found")
        }
    }

    // --- /info.json ---------------------------------------------------------

    private fun infoJson(includeGraph: Boolean = false): String {
        val store = Stores.get(context)
        val now = System.currentTimeMillis()
        val sharedAnchor = forecastAnchor(
            store, context, now,
        ) ?: return "{}"
        val last = sharedAnchor.reading
        val minuteCal = MinuteCalCache.get(store, context)
        val meterCal = MeterCalCache.get(store, context)

        // Trend off the meter-calibrated 5-min MAIN grid — the same source the
        // phone header now uses, so all surfaces agree. The grid is the FROZEN
        // real-time record; the 1-min oop2 stream retroactively revises its
        // history and has been observed to overshoot during a rapid drop.
        // Minute stream only as a sparse-grid fallback.
        val delta = run {
            val mainPts = store.sensorReadings(now - 16L * 60_000, now).map {
                com.diapilot.core.collector.GlucosePoint(
                    it.tsMs, meterCal?.correctedAt(it.tsMs, it.mmol) ?: it.mmol,
                )
            }
            (com.diapilot.core.twin.gridTrendReadout(mainPts, now)?.delta5Mmol
                ?: run {
                    val pts = if (minuteCal != null) {
                        store.minuteReadings(now - 15L * 60_000, now)
                            .map { com.diapilot.core.collector.GlucosePoint(it.tsMs, minuteCal.apply(it.mmol)) }
                    } else emptyList()
                    com.diapilot.core.twin.trendReadout(pts, now)?.delta5Mmol
                })
                ?: store.readings(now - 25 * 60_000, now).takeLast(2)
                    .takeIf { it.size == 2 }?.let { it[1].mmol - it[0].mmol }
        }

        // 24h time-in-range, integer percents that sum to 100.
        val rangeLo = Settings.rangeLoMmol(context)
        val rangeHi = Settings.rangeHiMmol(context)
        val tir = tir24h(store, context, now, rangeLo, rangeHi)
        val lowPct = tir?.low ?: 0
        val highPct = tir?.high ?: 0
        val inPct = tir?.inRange ?: 0

        // Local IOB — the store sees pen/watch/manual doses xDrip never will.
        val iob = HybridRuntimeMetrics
            .surfaceIobUnits(store, context, now) ?: 0.0
        // Only a dose that still matters reaches the wrist (150 min);
        // older shots are history, findable on the chart.
        val doseCutoffMs = com.diapilot.core.PersonalParams.DEFAULT.lastDoseShowMin * 60_000
        val lastBolus = store.boluses(now - doseCutoffMs, now)
            .lastOrNull { !com.diapilot.core.analysis.isPrimePurpose(it.purpose) }

        val bat = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0

        // The face interprets values via status.isMgdl — follow the app's
        // display-unit setting so phone and wrist always agree.
        val mgdlPref = Units.isMgdl(context)
        val bg = JSONObject()
            .put("tir", inPct.toString())
            .put("tirLow", lowPct.toString())
            .put("tirHigh", highPct.toString())
            .put(
                "val",
                if (mgdlPref) String.format(Locale.ENGLISH, "%.0f", last.mmol * MGDL_PER_MMOL)
                else String.format(Locale.ENGLISH, "%.1f", last.mmol),
            )
            .put(
                "delta",
                delta?.let {
                    if (mgdlPref) String.format(Locale.ENGLISH, "%+.1f", it * MGDL_PER_MMOL)
                    else String.format(Locale.ENGLISH, "%+.1f", it)
                } ?: "",
            )
            .put("trend", last.trend?.takeIf { it.isNotBlank() } ?: trendName(delta))
            .put("isHigh", last.mmol > rangeHi)
            // OUR smart hypo alert (predictive + state machine) drives isLow —
            // the watch face vibrates on it, earlier than a raw threshold and
            // silenced during a dextrose snooze. Plus an explicit hypoAlert
            // field for faces that read it.
            .put("isLow", last.mmol < rangeLo || HypoAlertNotifier.watchSignalActive())
            .put("hypoAlert", HypoAlertNotifier.watchSignalActive())
            // Night-dim flag for the face (manual toggle or schedule on phone).
            .put("dim", Settings.watchNightDimActive(context))
            .put("time", last.tsMs)
            .put("isStale", now - last.tsMs > 13 * 60_000)

        val treatment = JSONObject()
        lastBolus?.let {
            treatment.put("insulin", it.units)
            treatment.put("time", it.tsMs)
        }
        treatment.put("predictIOB", String.format(Locale.ENGLISH, "%.1fu", iob))
        // Active food on the wrist: the freshest carb-carrying note within 3h.
        store.annotations(now - 3L * 3_600_000, now)
            .filter { it.estCarbs != null && it.kind != "tag" }
            .maxByOrNull { it.tsMs }
            ?.let {
                treatment.put("food", it.content.take(18))
                treatment.put("foodTime", it.tsMs)
            }
        // Our BWP replacement: the twin's hint (carbs for a predicted low /
        // uncovered mmol above target). Never an insulin dose — see WatchHint.
        val prediction = predictionPoints(now)
        // The hint anchors where the forecast SETTLES (same point the phone
        // header names), not an arbitrary +60-min sample; the hypo check
        // scans the corridor floor's minimum over the next hour so a dip
        // before a rebound isn't missed.
        val future = prediction.filter { it.tsMs > now }
        val settlePt = com.diapilot.core.analysis.settleIndex(future.map { it.mmol })
            ?.let { future[it] } ?: future.lastOrNull()
        val hourLo = future.filter { it.tsMs <= now + 3_600_000 }
            .minOfOrNull { it.lo }
        settlePt
            ?.let { pSettle ->
                com.diapilot.core.analysis.watchHint(
                    com.diapilot.core.analysis.WatchHintInput(
                        predMmolIn60 = pSettle.mmol,
                        predLoIn60 = hourLo,
                        iobUnits = iob,
                        carbSensMmolPerGram =
                            HybridRuntimeMetrics
                                .carbSensitivityMmolPerGram()
                                .takeIf {
                                    true
                                }
                                ?: TwinCache
                                    .getForForecast(store, context)?.carbSens?.mmolPerGram,
                        loMmol = rangeLo,
                        hiMmol = rangeHi,
                        targetMmol = Settings.targetMmol(context),
                        mgdl = mgdlPref,
                        hypoProtocol = Settings.hypoProtocol(context),
                    ),
                )?.let { treatment.put("predictBWP", StatusText.watchHint(context, it)) }
            }

        val root = JSONObject()
            .put(
                "status",
                JSONObject().put("now", now).put("isMgdl", mgdlPref).put("bat", bat),
            )
            .put("bg", bg)
            .put("treatment", treatment)
            .put(
                "pump",
                JSONObject().put("reservoir", -1.0).put("iob", -1.0).put("bat", -1.0),
            )
            .put("external", JSONObject().put("time", 0))
        if (includeGraph) root.put("graph", graphJson(now, prediction))
        return root.toString()
    }

    /** Twin prediction from the freshest reading; empty when data is stale. */
    private fun predictionPoints(now: Long): List<com.diapilot.core.twin.PredictedPoint> {
        val store = Stores.get(context)
        return try {
            val model = TwinCache.getForForecast(store, context)
            val anchor = forecastAnchor(store, context, now)
            if (model != null && anchor != null) {
                Forecaster.forecast(
                    store, model, now,
                    anchorTsMs = anchor.reading.tsMs,
                    anchorMmol = anchor.reading.mmol,
                    minutePoints = anchor.minutePoints,
                    recordAs = "watch",
                    // ONE SWITCH FOR THE WATCH AND THE SCREEN.
                    //
                    // The watch read `hybridV11Main`, a different setting, and
                    // its mapping had no PHYSIO_V1 branch at all — so the arm
                    // selected for the phone could never reach the wrist, and
                    // the two surfaces could disagree about the same minute with
                    // nothing on either saying why. It now mirrors `MainState`
                    // exactly; diverge here and validation lies.
                )?.points ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "prediction failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Watch-face mini graph: 3h of history split into high/inRange/low point
     * lines, target guides, and — the part WatchDrip can't do — the twin's
     * personal prediction as the predict line. Viewport math on the face:
     * x = raw ts between start..end, y in mg/dl against GRAPH_LIMIT.
     */
    private fun graphJson(
        now: Long,
        prediction: List<com.diapilot.core.twin.PredictedPoint>,
    ): JSONObject {
        val store = Stores.get(context)
        val from = now - 3L * 3_600_000
        val readings = displayHistory(store, context, from, now)
        // Red/green/yellow by the USER's range, not a hardcoded 3.9/10.
        val rangeLo = Settings.rangeLoMmol(context)
        val rangeHi = Settings.rangeHiMmol(context)

        val mgdlPref = Units.isMgdl(context)

        fun line(name: String, color: String, pts: List<Pair<Long, Number>>): JSONObject =
            JSONObject().put("name", name).put("color", color).put(
                "points",
                org.json.JSONArray().apply {
                    pts.forEach { (x, y) -> put(org.json.JSONArray().put(x).put(y)) }
                },
            )

        // Graph y values must match status.isMgdl (the face scales its
        // viewport by that flag).
        fun mgdl(mmol: Double): Number =
            if (mgdlPref) Math.round(mmol * MGDL_PER_MMOL)
            else Math.round(mmol * 10.0) / 10.0

        val high = mutableListOf<Pair<Long, Number>>()
        val inRange = mutableListOf<Pair<Long, Number>>()
        val low = mutableListOf<Pair<Long, Number>>()
        for (r in readings) {
            val p = r.tsMs to mgdl(r.mmol)
            when {
                r.mmol > rangeHi -> high.add(p)
                r.mmol < rangeLo -> low.add(p)
                else -> inRange.add(p)
            }
        }

        // Twin prediction for the next hour (computed once per request,
        // shared with the hint).
        val predWindow = prediction.filter { it.tsMs > now && it.tsMs <= now + 3_600_000 }
        val predict = predWindow.map { it.tsMs to mgdl(it.mmol) }
        val end = predict.lastOrNull()?.first ?: now

        val lines = org.json.JSONArray()
        if (high.isNotEmpty()) lines.put(line("high", "0xFFFF00", high))
        if (inRange.isNotEmpty()) lines.put(line("inRange", "0x00FF00", inRange))
        if (low.isNotEmpty()) lines.put(line("low", "0xFF0000", low))
        if (predict.isNotEmpty()) {
            lines.put(line("predict", "0xBB86FC", predict))
            // Corridor bounds, same x grid as predict — the face fills the fan.
            lines.put(line("predLo", "0x4A3670", predWindow.map { it.tsMs to mgdl(it.lo) }))
            lines.put(line("predHi", "0x4A3670", predWindow.map { it.tsMs to mgdl(it.hi) }))
        }
        lines.put(line("lineLow", "0x424242", listOf(from to mgdl(rangeLo))))
        lines.put(line("lineHigh", "0x424242", listOf(from to mgdl(rangeHi))))

        return JSONObject()
            .put("lines", lines)
            .put("start", from)
            .put("end", end)
            .put("fuzzer", 1)
    }

    /**
     * Fallback trend from the ~5-min delta when the reading carries none —
     * the shared core convention (PersonalParams thresholds).
     */
    private fun trendName(deltaMmol: Double?): String =
        com.diapilot.core.trendName(deltaMmol)

    // --- /add_treatments ----------------------------------------------------

    /**
     * Entry FROM the watch: insulin units and/or carb grams. Insulin goes in
     * as a manual event; carbs become a food note (feeds the twin like any
     * other grams). Source-tagged so a future sync never clobbers them.
     *
     * POST with the token, checked before this is reached. The parameters ride
     * in the query string or in a form-encoded body, whichever the client finds
     * easier.
     *
     * ONE FUSE FOR EVERY INSULIN INPUT. The values arrive over a socket and are
     * written into IOB, the forecast and the hypo alert, so they pass the same
     * [com.diapilot.core.analysis.validateCommandValues] the LLM path passes —
     * `insulin > 0` was not a fuse, it merely excluded one of the two absurd
     * directions. Nothing is written unless every value clears the guard: a
     * request carrying a valid dose and implausible carbs stores neither, so a
     * client is never left guessing which half landed.
     */
    private fun addTreatments(params: Map<String, String>): Pair<String, String> {
        // Deliberately NOT filtered by `> 0` first: a non-positive or
        // non-finite number must be REFUSED by the guard, not silently read as
        // "parameter absent".
        val insulin = params["insulin"]?.toDoubleOrNull()
        val carbs = params["carbs"]?.toDoubleOrNull()
        if (insulin == null && carbs == null) {
            return "400 Bad Request" to "Parameters not specified"
        }
        val foodNote = context.localized().getString(R.string.watch_server_food_from_watch)
        val block = listOfNotNull(
            insulin?.let { com.diapilot.core.analysis.validateCommandValues("bolus", units = it) },
            carbs?.let {
                com.diapilot.core.analysis.validateCommandValues("food", food = foodNote, grams = it)
            },
        ).firstOrNull()
        if (block != null) {
            // The reason's CLASS only: the rejected value is medical payload
            // and never reaches the log (same rule as the request target).
            Log.w(TAG, "add_treatments refused: ${block.javaClass.simpleName}")
            return "400 Bad Request" to "Refused by the dose guard"
        }
        val store = Stores.get(context)
        val now = System.currentTimeMillis()
        insulin?.let {
            store.upsertInsulin(
                com.diapilot.core.collector.InsulinEvent(now, it, "bolus", "watch"),
            )
        }
        carbs?.let {
            store.addAnnotation(
                com.diapilot.core.collector.Annotation(
                    now, "food", foodNote, estCarbs = it,
                ),
            )
        }
        return "200 OK" to "Treatment added"
    }
}
