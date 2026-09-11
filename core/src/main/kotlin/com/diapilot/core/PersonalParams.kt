/**
 * Every tunable alert and display constant in one place.
 *
 * The app was built and tuned against a single user (n=1), so the numbers
 * marked [n=1] below were chosen by watching one person's data. They are
 * defaults, not clinical norms, and unproven for anyone else — that is the
 * point of naming them: user #2 gets a different instance, not a fork of
 * scattered literals.
 *
 * Provenance legend per field:
 *  - [n=1]      chosen/tuned by watching the user's data
 *  - [clinical] standard first-aid / published convention
 *  - [xdrip]    inherited from xDrip's field-proven convention
 *
 * Not yet lifted here (inventory, wire when touched): meal↔note merge
 * windows (-90..+30 min), kernel episode windows (TwinCache KERNEL_CFG),
 * corridor fallback (w0=0.5 k=0.25), ISF sweep ranges, chart y-limits,
 * clean-era cutoffs, activity-drop clamps (0.01..0.08).
 */
package com.diapilot.core

data class PersonalParams(
    // ---- predictive hypo alert (shipping gate: its own backtest) ----
    /** [n=1] How far ahead the alert looks for a predicted crossing. */
    val hypoLeadMaxMin: Double = 45.0,
    /** [n=1] Current BG must be this far above threshold — acute lows
     *  belong to the rapid-fall alarm, not the predictive one. */
    val hypoMarginMmol: Double = 0.4,
    /** [n=1] Silence after each alert; also simulated in the backtest. */
    val hypoCooldownMin: Double = 45.0,
    /** [n=1] Hyper mirror: how far ahead the high-side alert looks. */
    val hyperLeadMaxMin: Double = 40.0,
    /** [n=1] Highs are less urgent than lows — longer silence. */
    val hyperCooldownMin: Double = 90.0,

    // ---- forecast narration ----
    /** [n=1] The whole (median-smoothed) forecast tail must fit in this band
     *  to call it "settled" — matches what the eye calls flat on the
     *  chart. 0.5 mmol ≈ ±4.5 mg/dl of wiggle, which the eye ignores; the
     *  original 0.3 tripped on kernel-step teeth and pushed "settle" out by
     *  an hour+ on a visually level tail. */
    val settleBandMmol: Double = 0.5,
    /** [n=1] Minimum flat tail (5-min steps) before the horizon; a
     *  10-minute flat stretch right at the edge proves nothing. */
    val settleMinFlatSteps: Int = 3,

    /** [n=1] Weight of an ISF episode in the post-exercise sensitization
     *  tail (3–12h after a ≥60-min bout): the drop is amplified, so the
     *  episode votes at a fraction — but an active lifestyle must still be
     *  able to learn ISF at all, so never zero. */
    val postActivityTailWeight: Double = 0.3,

    // ---- LLM command guard (technical fuses, NOT medical norms) ----
    /** [n=1] Hard ceiling for a voice/LLM-entered bolus — well above the
     *  user's habitual dose range, far below absurd. Blocks a misheard
     *  "100 units". */
    val commandMaxBolusUnits: Double = 12.0,
    /** [n=1] Same fuse for the long-acting basal dose. */
    val commandMaxBasalUnits: Double = 40.0,

    // ---- trend arrows, mg/dl per 5 min ----
    /** [xdrip] ↗ / ↘ from here (anchored to a live WatchDrip sample). */
    val trendSlantMgdl: Double = 5.0,
    /** [xdrip] ↑ / ↓ from here. */
    val trendSingleMgdl: Double = 9.0,
    /** [xdrip] ⇈ / ⇊ from here. */
    val trendDoubleMgdl: Double = 18.0,

    // ---- hypo first aid (never insulin — the safety line) ----
    /** [clinical] Rescue carbs suggestion is clamped to this range, grams. */
    val rescueCarbsMinG: Int = 5,
    val rescueCarbsMaxG: Int = 25,
    /** [n=1] Aim this far ABOVE the hypo line, not exactly onto it. */
    val rescueLandingMmol: Double = 0.6,

    // ---- basal habit reminder ----
    /** [n=1] A basal is "due" only after this many hours since the last. */
    val basalDueAfterH: Int = 20,
    /** [n=1] Two logged basals count as a habit if their times-of-day
     *  agree within this many minutes (circularly). */
    val basalPairAgreeMin: Int = 90,
    /** [n=1] Reminder window around the usual hour: -back..+forward min. */
    val basalWindowBackMin: Int = 60,
    val basalWindowFwdMin: Int = 180,

    /** [n=1] The last dose stops being shown (header/watch/widget) after
     *  this many minutes — by then it's history, findable on the chart. */
    val lastDoseShowMin: Long = 150,

    // ---- autosens (day-to-day sensitivity dial) ----
    /** [xdrip]/[clinical] AAPS-convention clamp: a wrong ratio must nudge,
     *  never steer. Applied on top of the raw learned median. */
    val autosensMinRatio: Double = 0.8,
    val autosensMaxRatio: Double = 1.2,
    /** [n=1] Trailing evidence window for the sensitivity estimate. */
    val autosensWindowH: Double = 12.0,

    // ---- kernel recency (the body changes unmarked) ----
    /** [n=1] Episode half-life for kernel/fit training: a month-old
     *  correction carries half the vote of today's. */
    val kernelHalfLifeDays: Double = 35.0,
    /** [n=1] Episodes from before a detected dosing-regime change describe
     *  a different body — a vote, not a veto. */
    val preChangepointWeight: Double = 0.3,
    /** [n=1] Dish-profile half-life: last month's smoothie describes the
     *  current recipe/body better than winter's. */
    val foodHalfLifeDays: Double = 45.0,

    // ---- freshness display ----
    /** [n=1] With a minute stream, data age under this is noise; from
     *  here on it reads as a sensor disconnect and earns pixels. */
    val ageNoiseMin: Int = 3,
) {
    companion object {
        /** The shipped defaults. */
        val DEFAULT = PersonalParams()
    }
}

private val TREND_GLYPHS = mapOf(
    "DoubleUp" to "⇈", "SingleUp" to "↑", "FortyFiveUp" to "↗",
    "Flat" to "→",
    "FortyFiveDown" to "↘", "SingleDown" to "↓", "DoubleDown" to "⇊",
)

/**
 * xDrip-convention trend name from a ~5-min delta. THE single source:
 * the watch server, the widget and the app header must never disagree
 * about the arrow (they used to: the widget fired ⇈ from +10 mg/dl).
 */
fun trendName(
    deltaMmol: Double?,
    p: PersonalParams = PersonalParams.DEFAULT,
): String {
    val d = (deltaMmol ?: return "Flat") * 18.016
    return when {
        d >= p.trendDoubleMgdl -> "DoubleUp"
        d >= p.trendSingleMgdl -> "SingleUp"
        d >= p.trendSlantMgdl -> "FortyFiveUp"
        d > -p.trendSlantMgdl -> "Flat"
        d > -p.trendSingleMgdl -> "FortyFiveDown"
        d > -p.trendDoubleMgdl -> "SingleDown"
        else -> "DoubleDown"
    }
}

/** Arrow glyph for an xDrip trend name; empty for unknown/blank. */
fun trendGlyph(name: String?): String = TREND_GLYPHS[name] ?: ""
