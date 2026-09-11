package com.diapilot.core.twin

import com.diapilot.core.analysis.ActivityWindow
import com.diapilot.core.analysis.effectEndMs
import com.diapilot.core.collector.BolusPoint

/**
 * WHAT SITUATION THE ANCHOR IS IN — a property of the day, not of a model.
 *
 * Moved out of `RegimeCorridor.kt`, ahead of the legacy twin's
 * removal. It lived beside the regime-bucketed CORRIDOR, which is legacy
 * machinery, and would have been deleted with it — but the classifier itself
 * knows nothing about any forecast: it reads the clock, the meals, the boluses
 * and the activity windows.
 *
 * That distinction is why this is a MOVE and not a copy. `ForecastHealthV1` had
 * to be split per arm because two of its inputs were properties of the legacy
 * MODEL (its kernel's episode count, its corridor). Regime is not: both arms
 * are looking at the same afternoon, so a second implementation would be a
 * second answer to a question that has one.
 */
// The names are stored (ledger, snapshot JSON); the app renders the label.
enum class Regime {
    NIGHT,
    ACTIVITY,
    POST_MEAL,
    POST_BOLUS,
    QUIET,
}

/**
 * The regime at [tsMs], backward-looking only. Mirrors the backtest's
 * segment priority (night > activity > meal > bolus > quiet) except that
 * future meals are invisible here by design.
 */
fun classifyRegime(
    tsMs: Long,
    mealOnsetsMs: List<Long>,
    boluses: List<BolusPoint>,
    activityWindows: List<com.diapilot.core.analysis.ActivityWindow>,
    hour: Int,
): Regime = when {
    hour in 0..6 -> Regime.NIGHT
    activityWindows.any { tsMs in it.startMs..it.effectEndMs() } -> Regime.ACTIVITY
    mealOnsetsMs.any { tsMs - it in 0..(150L * 60_000) } -> Regime.POST_MEAL
    boluses.any { tsMs - it.tsMs in 0..(120L * 60_000) } -> Regime.POST_BOLUS
    else -> Regime.QUIET
}
