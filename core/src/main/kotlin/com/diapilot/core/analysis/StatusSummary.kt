/**
 * The always-current one-liner: what is happening to glycemia RIGHT NOW,
 * in words. Deterministic and rule-based — assembled from the same numbers
 * the screen already shows, so it is instant, free and never stale (unlike
 * an LLM take). Descriptive only: no dosing advice, ever.
 */
package com.diapilot.core.analysis

/** Everything the summary may mention; nulls simply drop the fragment. */
data class StatusInput(
    val iobUnits: Double?,
    val lastBolusUnits: Double?,
    val lastBolusAgeMin: Long?,
    val foodLabel: String?,        // latest food within the absorption window
    val foodAgeMin: Long?,
    val foodCarbs: Double?,        // grams EATEN, if known
    val predMmolIn60: Double?,     // model's point + corridor at the settle point
    val predLoIn60: Double? = null,
    val predHiIn60: Double? = null,
    // When the forecast line flattens out (equilibrium), in minutes from now;
    // null keeps the legacy fixed "in an hour" wording.
    val predSettleMin: Long? = null,
    // False = the line is still moving at the horizon (settle not reached).
    val predSettled: Boolean = true,
    val mgdl: Boolean = false,     // display units only; inputs stay mmol/L
    /** Carbs still to come (g) — the mirror of IOB. Null when the model can't
     *  say; the line then falls back to the total eaten.
     *
     *  LAST on purpose, with a default: callers pass this struct positionally,
     *  so a new field in the MIDDLE silently re-binds every argument after it.
     *  (Which is what happened when it first went in next to foodCarbs — the
     *  same trap that broke WalkForward's positional call a week ago.) */
    val cobGrams: Double? = null,
)

/** Deterministic "8,2" regardless of the JVM locale. */
private fun fmt1(x: Double): String =
    String.format(java.util.Locale.ROOT, "%.1f", x).replace('.', ',')

private fun fmt0(x: Double): String =
    String.format(java.util.Locale.ROOT, "%.0f", x)

private fun fmtAge(min: Long): String = when {
    min < 60 -> "${min}м"
    else -> "%dч%02dм".format(min / 60, min % 60)
}

/**
 * Compose 1–2 short lines. First line: what is acting now (food absorbing +
 * insulin at work — the BG number and trend already live in the hero).
 * Second line: where the model thinks it goes / how reality sits against
 * the corridor. Empty list when there is nothing to say.
 */
fun statusSummary(s: StatusInput): List<String> {
    val lines = mutableListOf<String>()

    // Compact by design: the header competes with the chart for pixels.
    val parts = mutableListOf<String>()
    if (s.foodLabel != null && s.foodAgeMin != null) {
        // COB answers the question this line is FOR — "what is acting now" —
        // where the eaten total answers "what happened". Once the model can say
        // what is LEFT, the total is history and belongs in the diary, not here.
        // Below 3 g there is nothing left to mention (IOB hides under 0.1 U the
        // same way); we then say the dish and its age, and stop.
        val carbs = when {
            s.cobGrams != null -> s.cobGrams.takeIf { it >= 3.0 }?.let { " · COB ~${fmt0(it)} г" } ?: ""
            else -> s.foodCarbs?.let { " ~${fmt0(it)}г" } ?: ""
        }
        parts += "«${s.foodLabel}» ${fmtAge(s.foodAgeMin)}$carbs"
    }
    val doseCutoff = com.diapilot.core.PersonalParams.DEFAULT.lastDoseShowMin
    if (s.iobUnits != null && s.iobUnits > 0.1) {
        parts += "IOB ${fmt1(s.iobUnits)}" +
            (s.lastBolusAgeMin?.takeIf { it <= doseCutoff }
                ?.let { " · укол ${fmtAge(it)}" } ?: "")
    } else if (s.lastBolusUnits != null && s.lastBolusAgeMin != null &&
        s.lastBolusAgeMin <= doseCutoff
    ) {
        parts += "${fmt1(s.lastBolusUnits)} ед · ${fmtAge(s.lastBolusAgeMin)}"
    }
    if (parts.isNotEmpty()) lines += parts.joinToString(" · ")

    // Deviations are NOT mentioned here — the red deviation card owns that
    // story with actual numbers; duplicating it in words reads as two alerts.
    if (s.predMmolIn60 != null) {
        val corridor = if (s.predLoIn60 != null && s.predHiIn60 != null) {
            " (${fmtBg(s.predLoIn60, s.mgdl)}–${fmtBg(s.predHiIn60, s.mgdl)})"
        } else ""
        val v = "~${fmtBg(s.predMmolIn60, s.mgdl)}$corridor"
        lines += when {
            s.predSettleMin == null -> "Через час $v"          // legacy callers
            !s.predSettled -> "Через ${fmtAge(s.predSettleMin)} $v, ещё в движении"
            s.predSettleMin <= 10 -> "Дальше ровно: $v"
            else -> "Выровняется на $v через ${fmtAge(s.predSettleMin)}"
        }
    }
    return lines
}

/**
 * Where the forecast flattens: the earliest point from which the ENTIRE
 * remaining tail stays inside a [bandMmol] band — i.e. what a human calls
 * "the line went flat". A per-step slope test breaks here: the kernel is
 * step-wise, and one 0.1-mmol artifact step deep in a visually flat tail
 * would push "settle" all the way to the horizon.
 *
 * Returns an index into [mmol]; the LAST index means "still moving at the
 * horizon" (also when the flat tail is shorter than [minFlatSteps] — a
 * 10-minute flat stretch right before the horizon proves nothing). Null
 * when the series is too short.
 */
fun settleIndex(
    mmol: List<Double>,
    bandMmol: Double = com.diapilot.core.PersonalParams.DEFAULT.settleBandMmol,
    minFlatSteps: Int = com.diapilot.core.PersonalParams.DEFAULT.settleMinFlatSteps,
): Int? {
    if (mmol.size < 3) return null
    // Median-of-3 first: the kernel's step artifacts put ±0.2-mmol teeth on a
    // tail every human reads as flat, and a raw min/max band trips on every
    // tooth — "settles in 1.5h" while the plotted line is already level.
    val s = List(mmol.size) { i ->
        if (i == 0 || i == mmol.size - 1) mmol[i]
        else listOf(mmol[i - 1], mmol[i], mmol[i + 1]).sorted()[1]
    }
    var mn = s.last()
    var mx = s.last()
    var flatFrom = s.size - 1
    for (i in s.size - 2 downTo 0) {
        mn = minOf(mn, s[i])
        mx = maxOf(mx, s[i])
        if (mx - mn <= bandMmol) flatFrom = i else break
    }
    return if (s.size - 1 - flatFrom >= minFlatSteps) flatFrom else s.size - 1
}
