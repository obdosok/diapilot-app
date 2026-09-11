package com.diapilot.core.api

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The first local day from which the user's history is admissible for
 * learning: the day meals started being logged with enough regularity to make
 * glucose/insulin attribution meaningful.
 *
 * Earlier glucose and insulin stay in storage and in History, but must not
 * enter learning, retrospective factor estimates, deconvolution corpora, model
 * calibration or the published events API.
 *
 * A VALUE, not a constant. The era is a per-installation setting that the app
 * stores and passes in; nothing in `:core` knows a date of its own. A function
 * that needs the boundary takes a [FoodEra] (or its [startMs]) as an argument,
 * which keeps the core testable with any era and keeps one user's start date
 * out of every other user's build.
 *
 * [startMs] is midnight of [startDate] in [zone], as UTC epoch millis. It is
 * computed once from the stored date and zone, so a device that later changes
 * time zone does not silently move the boundary.
 */
data class FoodEra(val startDate: LocalDate, val zone: ZoneId) {

    val startMs: Long = startDate.atStartOfDay(zone).toInstant().toEpochMilli()

    /** `true` when an event at [tsMs] falls inside the era. */
    fun contains(tsMs: Long): Boolean = tsMs >= startMs

    /** A range start, raised to the era start when it reaches below it. */
    fun clampFrom(requestedMs: Long): Long = maxOf(requestedMs, startMs)

    companion object {
        /** The era that begins on the local day containing [tsMs] in [zone]. */
        fun startingOnDayOf(tsMs: Long, zone: ZoneId): FoodEra =
            FoodEra(Instant.ofEpochMilli(tsMs).atZone(zone).toLocalDate(), zone)

        /**
         * Parses a stored era: an ISO local date (`yyyy-MM-dd`) and a zone id.
         * Throws on malformed input rather than guessing a boundary.
         */
        fun parse(isoDate: String, zoneId: String): FoodEra =
            FoodEra(LocalDate.parse(isoDate), ZoneId.of(zoneId))
    }
}
