/**
 * Parsers for xDrip local integration.
 *
 * Two channels (both loopback/local, no cloud):
 *  - glucose  -> Intent action com.eveningoutpost.dexdrip.BgEstimate (glucose only)
 *  - insulin  -> xDrip Nightscout-compatible local web service (127.0.0.1:17580)
 *
 * Parsers are tolerant: missing/garbled fields yield null rather than throwing, so a
 * single bad broadcast never crashes the collector.
 */
package com.diapilot.core.collector

/**
 * THE conversion factor, mg/dL per mmol/L. One constant for the whole tree:
 * three literals used to coexist (18.0182 here and in `WatchServer`, 18.016 in
 * the trend arrow, 18.0 in the 1800 rule), which is three places for a displayed
 * value to disagree with a stored one. `analysis.MGDL_PER_MMOL_F` is this same
 * value under the name the presentation code imports.
 */
const val MGDL_PER_MMOL = 18.0182

// Verified intent extras (BgEstimateBroadcaster).
const val ACTION_BG = "com.eveningoutpost.dexdrip.BgEstimate"
const val EXTRA_BG = "com.eveningoutpost.dexdrip.Extras.BgEstimate"          // double, mg/dL
const val EXTRA_TIME = "com.eveningoutpost.dexdrip.Extras.Time"              // long, ms epoch
const val EXTRA_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName" // str trend
const val EXTRA_RAW = "com.eveningoutpost.dexdrip.Extras.Raw"                // double, optional

data class Reading(
    val tsMs: Long,
    val mgdl: Double,
    val mmol: Double,
    val trend: String?,
    val source: String = "xdrip_broadcast",
)

data class InsulinEvent(
    val tsMs: Long,
    val units: Double,
    val insulinType: String?,
    val source: String = "xdrip_treatments",
)

private fun Any?.asDoubleOrNull(): Double? = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull()
    else -> null
}

private fun Any?.asLongOrNull(): Long? = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull() ?: toDoubleOrNull()?.toLong()
    else -> null
}

/**
 * The range a broadcast glucose value may carry, mg/dL — what a CGM can report
 * at all. Wider than any alert threshold on purpose: this is a sanity bound on
 * an unauthenticated sender, not a clinical judgement.
 */
val BG_BROADCAST_MGDL_RANGE = 20.0..600.0

/**
 * How stale a broadcast timestamp may be. This channel carries the LIVE value —
 * a reading lands within a second of the sensor grid — and history is
 * back-filled over the web service instead, so anything older than a few grid
 * steps is not a late broadcast.
 */
const val BG_BROADCAST_MAX_AGE_MS = 20L * 60_000

/** How far ahead of the receiver's clock a timestamp may sit: enough for two
 *  clocks to disagree, not enough to park a value in the future where it would
 *  stay the freshest reading for hours. */
const val BG_BROADCAST_MAX_AHEAD_MS = 5L * 60_000

/**
 * How far back a BACKFILLED reading may reach. The web service is the history
 * channel — the once-per-install pull asks for ~14 days, and the meal scan and
 * the analytics windows are 14 days deep — so this bound is the depth of that
 * history, not a freshness window: applying [BG_BROADCAST_MAX_AGE_MS] here
 * would refuse the very backfill the poll exists for. Anything older than the
 * deepest pull is not a late entry, and rewriting last month is what this
 * refuses.
 */
const val BG_BACKFILL_MAX_AGE_MS = 14L * 24 * 3_600_000

/**
 * Is [tsMs] a timestamp a glucose channel may carry, given the receiver's
 * clock? Shared by every parser so the future bound is one number: a value
 * parked ahead of now stays the freshest reading — and the forecast anchor —
 * until the clock catches up with it.
 */
fun bgTimestampAccepted(tsMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
    tsMs > 0 && tsMs >= nowMs - maxAgeMs && tsMs <= nowMs + BG_BROADCAST_MAX_AHEAD_MS

/**
 * Normalize xDrip BgEstimate intent extras into a [Reading].
 *
 * Bounded on BOTH axes, because the sender is not authenticated: the receiver
 * is exported (xDrip is configured with an explicit consumer list) and any app
 * on the phone can send this intent. The worst case is not a false alarm but a
 * suppressed real one — a forged value becomes the forecast anchor — so a value
 * outside [BG_BROADCAST_MGDL_RANGE], or a timestamp outside
 * `now - BG_BROADCAST_MAX_AGE_MS .. now + BG_BROADCAST_MAX_AHEAD_MS`, yields
 * null and the broadcast is dropped whole.
 *
 * [nowMs] is a parameter, not a clock read here: this module reads no clock, so
 * the window is reproducible in a replay and in a test.
 */
fun parseBgBroadcast(extras: Map<String, Any?>?, nowMs: Long): Reading? {
    if (extras.isNullOrEmpty()) return null
    val mgdl = extras[EXTRA_BG].asDoubleOrNull() ?: return null
    if (!mgdl.isFinite() || mgdl !in BG_BROADCAST_MGDL_RANGE) return null
    val tsMs = extras[EXTRA_TIME].asLongOrNull() ?: return null
    if (!bgTimestampAccepted(tsMs, nowMs, BG_BROADCAST_MAX_AGE_MS)) return null
    val trend = (extras[EXTRA_SLOPE_NAME] as? String)?.takeIf { it.isNotEmpty() }
    return Reading(tsMs = tsMs, mgdl = mgdl, mmol = mgdl / MGDL_PER_MMOL, trend = trend)
}

/**
 * Parse a Nightscout-compatible treatments array into insulin events.
 *
 * Accepts either `timestamp` (ms) or `mills`/`date` fields; tolerates entries
 * without insulin (skipped). Carbs are handled elsewhere (meal labeling).
 */
fun parseTreatments(items: List<Map<String, Any?>>?): List<InsulinEvent> {
    val out = mutableListOf<InsulinEvent>()
    for (it in items.orEmpty()) {
        val units = it["insulin"].asDoubleOrNull() ?: continue
        if (units <= 0) continue
        val tsMs = extractTsMs(it) ?: continue
        val itype = (it["insulinType"] as? String)?.takeIf { s -> s.isNotEmpty() }
            ?: (it["enteredBy"] as? String)?.takeIf { s -> s.isNotEmpty() }
        out.add(InsulinEvent(tsMs = tsMs, units = units, insulinType = itype))
    }
    return out
}

private fun extractTsMs(it: Map<String, Any?>): Long? {
    // Each candidate field may be ms-epoch (number) or ISO-8601 (string),
    // depending on the xDrip build: NFC-pen builds emit numeric `created_at`,
    // Nightscout-proper emits ISO strings.
    for (key in listOf("timestamp", "mills", "date", "created_at", "dateString")) {
        val v = it[key] ?: continue
        if (v is Number) return v.toLong()
        if (v is String) {
            v.toLongOrNull()?.let { ms -> return ms }
            parseIsoMs(v)?.let { ms -> return ms }
        }
    }
    return null
}

private fun parseIsoMs(s: String): Long? {
    // Tolerate "Z", "+03:00" and compact "+0300" offsets.
    val normalized = Regex("([+-]\\d{2})(\\d{2})$").replace(s.trim(), "$1:$2")
    return try {
        java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
    } catch (_: java.time.format.DateTimeParseException) {
        try {
            java.time.Instant.parse(normalized).toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }
}

/**
 * Parse the OOP2 per-minute decode result (action OOP2_DECODE_BLE_RESULT,
 * string extra "json"). Shape (verbatim from the device):
 * {"ROW_ID":..,"DecodedBuffer":"..","PatchUid":"..","TrendBg":[218,...,176],
 *  "HistoricBg":[..],"com.eveningoutpost.dexdrip.Extras.TIMESTAMP":ms}
 *
 * TrendBg holds per-minute values in mg/dL, FIRST element newest (Libre trend
 * buffers are newest-first; verified against the live device — the value at
 * index 0 matches what the OOP2 screen shows "now"). Timestamps are quantized
 * to the minute: broadcast times jitter by seconds, and without quantization
 * the same minute slot spawns near-duplicate rows (sawtooth on the chart).
 *
 * Raw algorithm output — a different scale from xDrip's calibrated stream, so
 * it is stored separately and never fed into analytics. It is NOT inert,
 * though: `forecastAnchor` in the app prefers the newest minute reading over
 * the 5-minute main reading whenever the minute one is fresher (through the
 * minute→main calibration), so a forged minute value becomes the forecast
 * anchor exactly the way a forged broadcast would.
 *
 * Bounded like the broadcast, therefore. The receiver is exported — the sender
 * is another app, and a package check is all the receiver can do — so the
 * payload's timestamp must sit inside `now - BG_BROADCAST_MAX_AGE_MS .. now +
 * BG_BROADCAST_MAX_AHEAD_MS` (the minute stream is LIVE data; a trend buffer
 * is 16 minutes deep and the window covers that) or the payload is dropped
 * whole, and a value outside [BG_BROADCAST_MGDL_RANGE] is skipped per element,
 * as a non-positive one always was. [nowMs] is a parameter: no clock here.
 */
fun parseOop2Trend(fields: Map<String, Any?>?, nowMs: Long): List<Reading> {
    if (fields.isNullOrEmpty()) return emptyList()
    val ts = fields.entries.firstOrNull { it.key.endsWith("TIMESTAMP") }?.value.asLongOrNull()
        ?: return emptyList()
    if (!bgTimestampAccepted(ts, nowMs, BG_BROADCAST_MAX_AGE_MS)) return emptyList()
    val trend = (fields["TrendBg"] as? List<*>)?.mapNotNull { it.asDoubleOrNull() } ?: return emptyList()
    return trend.mapIndexedNotNull { i, mgdl ->
        if (!mgdl.isFinite() || mgdl !in BG_BROADCAST_MGDL_RANGE) return@mapIndexedNotNull null
        val tsMs = (ts - i * 60_000L).let { Math.round(it / 60_000.0) * 60_000L }
        Reading(tsMs = tsMs, mgdl = mgdl, mmol = mgdl / MGDL_PER_MMOL, trend = null, source = "oop2_ble")
    }
}

/**
 * Parse xDrip web-service `/sgv.json` entries (Nightscout `entries` shape) into readings.
 * Second glucose channel: backfills gaps when broadcasts were missed (phone asleep,
 * app killed). Fields: `date` ms epoch, `sgv` mg/dL, `direction` trend.
 *
 * Bounded like the broadcast, with one difference. The port this is read from
 * is a socket, not a trusted peer — with xDrip stopped any app holding
 * `INTERNET` can bind it and answer — so a value outside
 * [BG_BROADCAST_MGDL_RANGE] or a timestamp ahead of `now +
 * BG_BROADCAST_MAX_AHEAD_MS` is skipped. The past bound is
 * [BG_BACKFILL_MAX_AGE_MS], not the broadcast's twenty minutes, because this
 * channel legitimately returns two weeks of history. Per entry, not whole:
 * one bad row in a 4032-row backfill must not cost the other 4031.
 *
 * [nowMs] is a parameter, not a clock read: this module reads no clock, so the
 * window is reproducible in a replay and in a test.
 */
fun parseSgvEntries(items: List<Map<String, Any?>>?, nowMs: Long): List<Reading> {
    val out = mutableListOf<Reading>()
    for (it in items.orEmpty()) {
        val mgdl = it["sgv"].asDoubleOrNull() ?: continue
        if (!mgdl.isFinite() || mgdl !in BG_BROADCAST_MGDL_RANGE) continue
        val tsMs = it["date"].asLongOrNull() ?: continue
        if (!bgTimestampAccepted(tsMs, nowMs, BG_BACKFILL_MAX_AGE_MS)) continue
        val trend = (it["direction"] as? String)?.takeIf { s -> s.isNotEmpty() }
        out.add(Reading(tsMs = tsMs, mgdl = mgdl, mmol = mgdl / MGDL_PER_MMOL, trend = trend, source = "xdrip_sgv"))
    }
    return out
}
