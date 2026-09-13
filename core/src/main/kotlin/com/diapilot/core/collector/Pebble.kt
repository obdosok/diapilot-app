/**
 * Parser for the xDrip `/pebble` endpoint — the "live display" value, matching
 * exactly what the xDrip screen shows (sgv.json lags: it is the 5-min record
 * history). Shape: {"status":[{"now":ms}],"bgs":[{"sgv":"263","trend":5,
 * "direction":"FortyFiveDown","datetime":ms,"bgdelta":-9,"iob":"5,45"}],"cals":[...]}.
 *
 * Quirks handled: `sgv` and `iob` are strings in *display units* with a
 * locale-dependent decimal separator ("5,45"); mg/dL vs mmol/L is detected by
 * magnitude (a BG above 35 can only be mg/dL).
 */
package com.diapilot.core.collector

data class PebbleNow(
    val reading: Reading?,
    val iobUnits: Double?,
)

private fun localizedDouble(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().replace(',', '.').toDoubleOrNull()
    else -> null
}

/**
 * Parse the first entry of the pebble `bgs` array. Tolerant: bad fields -> nulls.
 *
 * The reading is bounded the way `/sgv.json` is (see `parseSgvEntries`): the
 * same unauthenticated socket answers both, and a value outside
 * [BG_BROADCAST_MGDL_RANGE] or a timestamp past `now + BG_BROADCAST_MAX_AHEAD_MS`
 * yields a null reading. The past bound is the backfill depth, not the live
 * window — xDrip's display value is whatever it last received, and a stale one
 * is stale, not forged. The insulin-on-board figure is untouched: it is a
 * display hint, never a stored dose. [nowMs] is passed in, not read here.
 */
fun parsePebbleBg(bg: Map<String, Any?>?, nowMs: Long): PebbleNow {
    if (bg.isNullOrEmpty()) return PebbleNow(null, null)
    val iob = localizedDouble(bg["iob"])?.takeIf { it >= 0 }

    val value = localizedDouble(bg["sgv"])
    val tsMs = when (val t = bg["datetime"]) {
        is Number -> t.toLong()
        is String -> t.toLongOrNull()
        else -> null
    }
    val mgdlOrNull = value?.let { if (it > 35) it else it * MGDL_PER_MMOL }
    val reading = if (
        mgdlOrNull != null && mgdlOrNull.isFinite() && mgdlOrNull in BG_BROADCAST_MGDL_RANGE &&
        tsMs != null && bgTimestampAccepted(tsMs, nowMs, BG_BACKFILL_MAX_AGE_MS)
    ) {
        val mgdl = mgdlOrNull
        Reading(
            tsMs = tsMs,
            mgdl = mgdl,
            mmol = mgdl / MGDL_PER_MMOL,
            trend = bg["direction"] as? String,
            source = "xdrip_pebble",
        )
    } else null
    return PebbleNow(reading, iob)
}
