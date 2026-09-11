/**
 * The wrist hint — DiaPilot's replacement for xDrip's BWP line on the watch.
 *
 * SAFETY LINE, do not cross: this module never names an insulin dose. The
 * high side reports the model's state (uncovered mmol above target) and the
 * low side suggests carbs — standard hypo first aid whose worst-case error
 * is a mild high, not a hypo. Insulin units are the user's decision alone.
 */
package com.diapilot.core.analysis

data class WatchHintInput(
    val predMmolIn60: Double?,   // model point where the forecast settles (or the horizon)
    val predLoIn60: Double?,     // corridor floor: the MINIMUM over the next hour
    val iobUnits: Double?,
    val carbSensMmolPerGram: Double?,  // calibrated personal scale, nullable
    val loMmol: Double = 3.9,
    val hiMmol: Double = 10.0,   // trigger threshold: hint fires above this
    val targetMmol: Double = 5.55,  // the excess is measured against THIS
    val mgdl: Boolean = false,   // display units for the excess figure
    /** The user's own pre-agreed hypo first step (e.g. "10 g juice") — reminded
     *  verbatim on a predicted low. Null = generic check prompt. */
    val hypoProtocol: String? = null,
)

/** What the watch face's hint slot says; the app renders it in the UI language. */
sealed interface WatchHint {
    /** A predicted low, with the user's own pre-agreed first step, verbatim. */
    data class HypoPlan(val protocol: String) : WatchHint

    /** A predicted low and no protocol set: a check, never a computed amount. */
    data object HypoCheck : WatchHint

    /** Expected to settle above range, at [settleMmol]. Glucose, never a dose. */
    data class AboveTarget(val settleMmol: Double, val mgdl: Boolean) : WatchHint
}

/**
 * The hint for the watch face's slot, or null when the model sees nothing
 * actionable. Low wins over high (a predicted low is always the more urgent
 * story).
 */
fun watchHint(
    s: WatchHintInput,
    p: com.diapilot.core.PersonalParams = com.diapilot.core.PersonalParams.DEFAULT,
): WatchHint? {
    val lo = s.predLoIn60
    val mid = s.predMmolIn60

    // Corridor floor dips under the hypo line within the hour. NO computed
    // gram figure: deriving rescue carbs from a meal-trained mmol/gram is a
    // personal therapeutic recommendation from an experimental model
    // (dextrose, pizza and salad are different physics). The user's own
    // pre-agreed hypo protocol (set once in Settings, ideally with their
    // doctor) is REMINDED; without one, the hint is a check, not a therapy.
    if (lo != null && lo < s.loMmol) {
        return s.hypoProtocol?.takeIf { it.isNotBlank() }
            ?.let { WatchHint.HypoPlan(it) }
            ?: WatchHint.HypoCheck
    }

    // Model expects to SETTLE out of range despite the insulin on board.
    // Report the settle value itself — "where this levels off" is the number
    // the wrist wants, more concrete than an excess-over-target delta. Still
    // glucose, never a dose.
    if (mid != null && mid > s.hiMmol + 0.5) {
        return WatchHint.AboveTarget(mid, s.mgdl)
    }
    return null
}
