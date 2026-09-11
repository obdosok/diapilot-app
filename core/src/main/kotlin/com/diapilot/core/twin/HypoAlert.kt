/**
 * Predictive hypo alert — the north-star feature: "we warn 30-40
 * minutes ahead and are almost never wrong".
 *
 * The RULE (findPredictedHypo) is deliberately simple: the prediction
 * median crosses below the threshold within the lead window while the
 * current value is still comfortably above it (acute lows belong to the
 * rapid-fall alarm, not this one).
 *
 * ⚠ THIS RULE CURRENTLY HAS NO MEASUREMENT. This comment used to say "the rule
 * ships only through its own BACKTEST (hypoAlertBacktest): replayed over
 * history, scored on precision/recall and false alarms per day — numbers the
 * user sees before trusting a single push". That stopped being
 * true once `hypoAlertBacktest` was removed together with the legacy engine
 * it called, and no replacement over the physio core has been written.
 *
 * The wording is kept in full rather than deleted, because it names a
 * GATE, not a detail: until it exists, no change to this rule can be
 * accompanied by a "false alarms per day" number. A comment that kept
 * promising the gate would be worse than its absence — a reviewer would read
 * it as an active safeguard (exactly the wrong conclusion `CommentContractTest`
 * exists to catch, and exactly the class of mistake it cannot catch here).
 *
 * Restoring the measurement means building it over the physio run, not
 * reviving the old one.
 */
package com.diapilot.core.twin

import com.diapilot.core.analysis.IsfAggregate
import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.analysis.TodBucket
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint

data class PredictedHypo(
    val crossTsMs: Long,   // when the median dips below the threshold
    val leadMin: Double,   // minutes from "now" to the crossing
    val minMmol: Double,   // the predicted trough within the window
    val minTsMs: Long,
)

/**
 * Fire when the median forecast crosses [thresholdMmol] within
 * [leadMaxMin], and the current BG is still above threshold + margin.
 */
fun findPredictedHypo(
    prediction: List<PredictedPoint>,
    nowMs: Long,
    currentMmol: Double,
    thresholdMmol: Double,
    leadMaxMin: Double = com.diapilot.core.PersonalParams.DEFAULT.hypoLeadMaxMin,
    marginMmol: Double = com.diapilot.core.PersonalParams.DEFAULT.hypoMarginMmol,
    // Night mode: while asleep, RECALL outranks precision — trigger on the
    // corridor's LOWER edge, not the median (the band's floor dipping under
    // the line is enough reason to wake a check).
    useCorridorLow: Boolean = false,
): PredictedHypo? {
    if (currentMmol < thresholdMmol + marginMmol) return null
    val horizonMs = nowMs + (leadMaxMin * 60_000).toLong()
    val window = prediction.filter { it.tsMs in nowMs..horizonMs }
    val cross = window.firstOrNull {
        (if (useCorridorLow) it.lo else it.mmol) < thresholdMmol
    } ?: return null
    val trough = window.minByOrNull { it.mmol } ?: return null
    return PredictedHypo(
        crossTsMs = cross.tsMs,
        leadMin = (cross.tsMs - nowMs) / 60_000.0,
        minMmol = trough.mmol,
        minTsMs = trough.tsMs,
    )
}

data class PredictedHigh(
    val crossTsMs: Long,   // when the median rises above the ceiling
    val leadMin: Double,
    val maxMmol: Double,   // predicted crest within the window
    val maxTsMs: Long,
)

/**
 * The hyper mirror of [findPredictedHypo]: the median forecast crosses
 * ABOVE [thresholdMmol] (the range ceiling) within [leadMaxMin] while the
 * current value is still comfortably below it. Fewer 300s start with
 * knowing 40 minutes earlier. Observations only — never doses.
 */
fun findPredictedHigh(
    prediction: List<PredictedPoint>,
    nowMs: Long,
    currentMmol: Double,
    thresholdMmol: Double,
    leadMaxMin: Double = com.diapilot.core.PersonalParams.DEFAULT.hyperLeadMaxMin,
    marginMmol: Double = com.diapilot.core.PersonalParams.DEFAULT.hypoMarginMmol,
): PredictedHigh? {
    if (currentMmol > thresholdMmol - marginMmol) return null
    val horizonMs = nowMs + (leadMaxMin * 60_000).toLong()
    val window = prediction.filter { it.tsMs in nowMs..horizonMs }
    val cross = window.firstOrNull { it.mmol > thresholdMmol } ?: return null
    val crest = window.maxByOrNull { it.mmol } ?: return null
    return PredictedHigh(
        crossTsMs = cross.tsMs,
        leadMin = (cross.tsMs - nowMs) / 60_000.0,
        maxMmol = crest.mmol,
        maxTsMs = crest.tsMs,
    )
}

data class FallVelocity(
    val slopeMmolPerMin: Double,  // least-squares slope over the window, negative = falling
    val projectedMmol: Double,    // current + slope · projMin
    val minToCross: Double,       // minutes from now to reach the threshold at this slope
)

/**
 * FALL-VELOCITY projection trigger (SHADOW — not wired to production yet).
 *
 * The median rule ([findPredictedHypo]) keys off a low VALUE the forecast reaches;
 * the recall-ceiling finding showed it is blind to DRIVERLESS FALLS — glucose
 * sliding toward low with nothing the model tracks pushing it, and the overcorrection
 * class that falls fast from a HIGH level and never crosses the line at all. Both
 * are visible in one thing the forecast ignores: the recent SLOPE.
 *
 * Fires when the slope over [windowMin], projected forward, reaches
 * [thresholdMmol] within [projMin] — from ANY level. Self-limiting: a shallow
 * slope from a high level never projects to the threshold inside the horizon, so
 * it does not fire; only a fall steep enough to arrive in time does.
 *
 * Slope is fit on the caller's series, which MUST be the frozen/calibrated grid,
 * never the raw minute stream — the minute stream retroactively over-steepens
 * drops (OOP2 back-fill), which would manufacture false falls. Values at or below
 * the threshold return null: an already-low reading is the value rule's job, not
 * this one's (this rule only buys LEAD before the crossing).
 */
fun findFallVelocity(
    readings: List<GlucosePoint>,   // ascending; the anchor grid series
    nowMs: Long,
    currentMmol: Double,
    thresholdMmol: Double,
    windowMin: Double = 15.0,
    minSlopeMmolPerMin: Double = 0.03,
    projMin: Double = 45.0,
): FallVelocity? {
    if (currentMmol <= thresholdMmol) return null
    val from = nowMs - (windowMin * 60_000).toLong()
    val win = readings.filter { it.tsMs in from..nowMs }
    if (win.size < 3) return null
    val slope = leastSquaresSlopePerMin(win) ?: return null
    if (slope >= -minSlopeMmolPerMin) return null            // not falling fast enough
    val projected = currentMmol + slope * projMin
    if (projected > thresholdMmol) return null               // won't reach the line in time
    return FallVelocity(
        slopeMmolPerMin = slope,
        projectedMmol = projected,
        minToCross = (currentMmol - thresholdMmol) / (-slope),
    )
}

/** Least-squares slope in mmol per MINUTE over the points; null if degenerate. */
internal fun leastSquaresSlopePerMin(points: List<GlucosePoint>): Double? {
    if (points.size < 2) return null
    val t0 = points.first().tsMs
    var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
    val n = points.size.toDouble()
    for (p in points) {
        val x = (p.tsMs - t0) / 60_000.0   // minutes
        val y = p.mmol
        sx += x; sy += y; sxx += x * x; sxy += x * y
    }
    val denom = n * sxx - sx * sx
    if (denom == 0.0) return null
    return (n * sxy - sx * sy) / denom
}

// THE ALERT'S HISTORICAL BACKTEST WAS REMOVED TOGETHER WITH THE LEGACY ENGINE.
//
// `hypoAlertBacktest` + `HypoAlertReport` replayed the rule over history through
// `fun forecast` — through a model the app no longer ships. That is
// exactly discipline #7: a bench scoring a DIFFERENT model prints a confident
// wrong number, and six of those turned up in three days.
//
// The alert rule itself — `findPredictedHypo`, `findPredictedHigh`,
// `findFallVelocity`, `decideHypoAlert` — is untouched: it reads points and
// readings, not the engine. When the measurement is needed again, it is built
// over the physio run, not resurrected from git.
