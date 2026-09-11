/**
 * Exercise as a model input.
 *
 * Aerobic effort burns glucose and potentiates insulin — a walk after
 * dinner eats a rise the food model expects. The effect is modeled as a
 * NEGATIVE ActiveFood (a smoothstep DOWN over the bout + tail), which
 * plugs into every consumer of the food superposition — live prediction,
 * what-if, hypo alert, backtest — without new plumbing.
 *
 * The magnitude is LEARNED from the user's own history: for each sustained
 * HR bout, the actual BG change minus the insulin-only model's change is
 * the exercise's excess drop; the median per elevated minute becomes the
 * coefficient. Bouts contaminated by food are excluded. Falls back to a
 * conservative population default while history is thin.
 */
package com.diapilot.core.twin

import com.diapilot.core.analysis.ActivityWindow
import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint

/** Conservative default: ~1 mmol per 30 elevated minutes. */
const val DEFAULT_ACTIVITY_DROP_PER_MIN = 0.033

/**
 * Aerobic glucose uptake does not begin the instant the feet move — muscle
 * GLUT4 recruitment and the shift to glucose oxidation ramp over the first
 * ~20 minutes of sustained effort. This matches the user's own months of
 * experience: on walks at 5+ km/h, after roughly 20 minutes glucose drops or
 * a rise stops — almost always. So the v2 exercise food waits this
 * long before the drop starts (reusing ActiveFood.onsetLagMin). For a bout that
 * has ALREADY been going >20 min the lag is spent by the anchor and costs
 * nothing; it matters for a just-started bout and for a PLANNED (What-if) walk.
 */
const val ACTIVITY_ONSET_LAG_MIN = 20.0

/**
 * Median excess drop per elevated minute across clean bouts (no food onset
 * within −60..+90 min of the bout). Null when fewer than [minWindows] clean
 * bouts exist. Clamped to a sane band — one bad estimate must not turn
 * every walk into a predicted crash.
 */
fun learnActivityDropPerMin(
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    windows: List<ActivityWindow>,
    foodOnsetsMs: List<Long>,
    tailMin: Double = 45.0,
    minWindows: Int = 4,
): Double? {
    if (kernel.isEmpty()) return null
    val r = readings.sortedBy { it.tsMs }
    fun bgAt(ts: Long): Double? = r.minByOrNull { kotlin.math.abs(it.tsMs - ts) }
        ?.takeIf { kotlin.math.abs(it.tsMs - ts) < 12 * 60_000 }?.mmol

    val perMin = windows.mapNotNull { w ->
        val horizonEnd = w.endMs + (tailMin * 60_000).toLong()
        if (foodOnsetsMs.any { it in (w.startMs - 60L * 60_000)..horizonEnd }) return@mapNotNull null
        val bg0 = bgAt(w.startMs) ?: return@mapNotNull null
        val bg1 = bgAt(horizonEnd) ?: return@mapNotNull null
        val horizonMin = (horizonEnd - w.startMs) / 60_000.0
        val modelDelta = simulateForward(
            w.startMs, bg0, boluses, kernel,
            stepMin = horizonMin, horizonMin = horizonMin,
        ).last().mmol - bg0
        val excess = (bg1 - bg0) - modelDelta
        if (w.elevatedMin <= 0) null else excess / w.elevatedMin
    }
    if (perMin.size < minWindows) return null
    val median = perMin.sorted()[perMin.size / 2]
    // Exercise should DROP glucose; a positive median means adrenaline-heavy
    // training dominates this user's bouts — stay neutral rather than wrong.
    if (median >= 0) return 0.0
    return (-median).coerceIn(0.01, 0.08)
}

/**
 * PROLONGED post-exercise insulin sensitization. Hours after a bout the same
 * insulin bites harder (glycogen repletion, lingering GLUT4) — classically an
 * evening walk surfacing as a NOCTURNAL low. Learned as the excess drop per
 * elevated minute over the DELAYED window [end+earlyH .. end+lateH], with the
 * insulin-only model change removed and food-contaminated bouts excluded.
 * Much smaller and slower than the acute drop; clamped tight.
 *
 * CONFOUNDING CAVEAT — read before wiring this into the forecast. The excess
 * subtracts only the BOLUS-insulin model change. Over the overnight window it
 * does NOT subtract basal action, circadian drift, or sleep, which typically
 * dominate the night's fall. So on a plain evening-walk night the ordinary
 * night drop gets credited to exercise, inflating this coefficient and biasing
 * any forecast/alert built on it toward false lows. Until a difference-in-
 * differences control exists (subtract the drop learned on comparable NON-
 * activity nights so the baseline cancels), this value is used only for the
 * observational activity-night card, NOT injected into the live prediction —
 * see FoodSources.activeFoods and ActivityNight/activityNightOutcomes, which
 * do the with/without comparison honestly. Never a basal-dose instruction.
 */
fun learnPostActivityDropPerMin(
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    windows: List<ActivityWindow>,
    foodOnsetsMs: List<Long>,
    earlyH: Double = 3.0,
    lateH: Double = 9.0,
    minWindows: Int = 4,
): Double? {
    if (kernel.isEmpty()) return null
    val r = readings.sortedBy { it.tsMs }
    fun bgAt(ts: Long): Double? = r.minByOrNull { kotlin.math.abs(it.tsMs - ts) }
        ?.takeIf { kotlin.math.abs(it.tsMs - ts) < 20 * 60_000 }?.mmol

    val perMin = windows.mapNotNull { w ->
        val from = w.endMs + (earlyH * 3_600_000).toLong()
        val to = w.endMs + (lateH * 3_600_000).toLong()
        // Any eating in the delayed window ruins the attribution.
        if (foodOnsetsMs.any { it in (w.startMs)..to }) return@mapNotNull null
        val bg0 = bgAt(from) ?: return@mapNotNull null
        val bg1 = bgAt(to) ?: return@mapNotNull null
        val horizonMin = (to - from) / 60_000.0
        val modelDelta = simulateForward(
            from, bg0, boluses, kernel,
            stepMin = horizonMin, horizonMin = horizonMin,
        ).last().mmol - bg0
        val excess = (bg1 - bg0) - modelDelta
        if (w.elevatedMin <= 0) null else excess / w.elevatedMin
    }
    if (perMin.size < minWindows) return null
    val median = perMin.sorted()[perMin.size / 2]
    if (median >= 0) return 0.0            // no delayed drop — stay neutral
    return (-median).coerceIn(0.003, 0.03) // slower/smaller than the acute band
}


/**
 * Bouts as negative foods: total drop = coefficient × elevated minutes
 * (capped), realized over the bout plus a [tailMin] tail.
 *
 * [v2] switches on the shape corrections settled on below, all behind the
 * caller's toggle so v2=false is byte-identical to the shipped model:
 *  - a ~20-min ONSET LAG ([ACTIVITY_ONSET_LAG_MIN]) — the drop starts after the
 *    muscle uptake ramp, not at the first step;
 *  - LEVEL-DEPENDENCE ([ActiveFood.activityGated]) — the simulator damps the
 *    drop toward the floor, so a walk cannot punch the forecast through hypo.
 * The magnitude ([dropPerMin]) is UNCHANGED: it cannot be re-learned on this
 * data (only ~1 food-free bout survives the calibrated detector — actcoef), and
 * with the gate it is now read as the HIGH-BG (gate=1) rate, so v2's drop is
 * everywhere ≤ v1's. A larger, measured coefficient waits for prospective clean
 * fasting-from-high walks; the shape is what v2 fixes.
 */
fun exerciseFoods(
    windows: List<ActivityWindow>,
    dropPerMin: Double,
    tailMin: Double = 45.0,
    maxDropMmol: Double = 4.0,
    v2: Boolean = false,
): List<ActiveFood> =
    if (dropPerMin <= 0.0) emptyList()
    else windows.map { w ->
        ActiveFood(
            onsetMs = w.startMs,
            rise = -(dropPerMin * w.elevatedMin).coerceAtMost(maxDropMmol),
            timeToPeakMin = (w.endMs - w.startMs) / 60_000.0 + tailMin,
            onsetLagMin = if (v2) ACTIVITY_ONSET_LAG_MIN else 0.0,
            activityGated = v2,
        )
    }
