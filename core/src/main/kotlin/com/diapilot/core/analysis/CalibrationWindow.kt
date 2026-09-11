/**
 * Clean-window detector for CAPTURING calibration data — never a prompt to
 * dose or eat.
 *
 * The model's bottleneck is clean episodes: a correction taken away from
 * food (teaches ISF) and a meal logged with exact grams away from insulin
 * (teaches the carb ratio). This flags the moments when conditions are ideal
 * — glucose flat, no active insulin, food long settled, not near a low — so
 * that IF the user acts on their own, tagging it precisely yields the
 * highest-value episode. It states an opportunity to LOG, not an action to
 * take: the app never tells anyone to inject insulin or to eat.
 */
package com.diapilot.core.analysis

enum class CalibrationNeed { ISF, CARB_RATIO, BOTH }

data class CalibrationWindow(val need: CalibrationNeed)

fun detectCalibrationWindow(
    rateMmolPerMin: Double?,        // null = unknown → not provably flat
    currentMmol: Double,
    iobUnits: Double,
    minSinceLastFood: Double?,      // null = none in memory / long ago
    minSinceLastBolus: Double?,
    isfEffectiveEpisodes: Double,   // Kish-effective clean ISF episodes
    hasCarbRatio: Boolean,          // a personal carb ratio has been learned
    rangeLo: Double,
    rangeHi: Double,
    flatRate: Double = FLAT_RATE_MMOL_PER_MIN,
    isfEnough: Double = 10.0,
    settleMin: Double = 180.0,      // food/insulin action settled
): CalibrationWindow? {
    // Provably flat only — an unknown or moving trend is not a clean window.
    if (rateMmolPerMin == null || kotlin.math.abs(rateMmolPerMin) > flatRate) return null
    // No insulin still working, and food long absorbed.
    if (iobUnits > 0.3) return null
    if (minSinceLastFood != null && minSinceLastFood < settleMin) return null
    if (minSinceLastBolus != null && minSinceLastBolus < settleMin) return null
    // Never near a low (the hypo protocol owns that), and not while high —
    // a banner at 15-19 mmol would read as pressure to correct. Cap just
    // above the user's ceiling.
    if (currentMmol <= rangeLo + 0.5 || currentMmol >= rangeHi + 3.0) return null

    val isfScarce = isfEffectiveEpisodes < isfEnough
    val carbScarce = !hasCarbRatio
    return when {
        isfScarce && carbScarce -> CalibrationWindow(CalibrationNeed.BOTH)
        isfScarce -> CalibrationWindow(CalibrationNeed.ISF)
        carbScarce -> CalibrationWindow(CalibrationNeed.CARB_RATIO)
        else -> null   // enough data — no nagging
    }
}
