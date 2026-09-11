/**
 * Observational: does evening activity precede a worse night?
 *
 * Delayed post-exercise nocturnal hypoglycemia is a well-known T1D pattern —
 * a walk repletes glycogen and the same basal bites harder overnight. This
 * measures it from the user's own data: nights that FOLLOW an evening bout vs
 * nights that don't. Pure observation — it explains a residual ("the night
 * ran lower than expected because you walked"), never a dose to change.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint

data class ActivityNightOutcome(
    val nightsAfterActivity: Int,
    val lowsAfterActivity: Int,       // nights with any reading < rangeLo
    val meanAfterActivity: Double,    // mean night glucose after activity
    val baselineNights: Int,
    val baselineLows: Int,
    val meanBaseline: Double,
) {
    val lowRateAfter: Double get() = if (nightsAfterActivity > 0) 100.0 * lowsAfterActivity / nightsAfterActivity else 0.0
    val lowRateBaseline: Double get() = if (baselineNights > 0) 100.0 * baselineLows / baselineNights else 0.0
}

/**
 * Compare nights preceded by evening activity to nights that weren't. A night
 * (calendar day N, hours [nightFrom, nightTo]) is "after activity" when day
 * N−1 had a bout ending in the evening [eveningFrom, eveningTo]. Null until
 * both groups have enough nights to mean anything.
 */
fun activityNightOutcomes(
    windows: List<ActivityWindow>,
    readings: List<GlucosePoint>,
    hourOf: (Long) -> Int,
    dayOf: (Long) -> Long,
    rangeLo: Double,
    eveningFrom: Int = 15,
    eveningTo: Int = 23,
    nightFrom: Int = 0,
    nightTo: Int = 7,
    minEach: Int = 3,
): ActivityNightOutcome? {
    val eveningActiveDays = windows
        .filter { hourOf(it.endMs) in eveningFrom..eveningTo }
        .map { dayOf(it.endMs) }
        .toSet()

    // Group night-window readings by their calendar day.
    val byNight = HashMap<Long, MutableList<Double>>()
    for (p in readings) {
        if (hourOf(p.tsMs) in nightFrom..nightTo) {
            byNight.getOrPut(dayOf(p.tsMs)) { mutableListOf() }.add(p.mmol)
        }
    }

    var nAfter = 0; var lowAfter = 0; val meansAfter = mutableListOf<Double>()
    var nBase = 0; var lowBase = 0; val meansBase = mutableListOf<Double>()
    for ((night, vals) in byNight) {
        if (vals.size < 6) continue                 // too few points to judge a night
        val low = vals.any { it < rangeLo }
        val mean = vals.average()
        if ((night - 1) in eveningActiveDays) {
            nAfter++; if (low) lowAfter++; meansAfter.add(mean)
        } else {
            nBase++; if (low) lowBase++; meansBase.add(mean)
        }
    }
    if (nAfter < minEach || nBase < minEach) return null
    return ActivityNightOutcome(
        nightsAfterActivity = nAfter,
        lowsAfterActivity = lowAfter,
        meanAfterActivity = meansAfter.average(),
        baselineNights = nBase,
        baselineLows = lowBase,
        meanBaseline = meansBase.average(),
    )
}
