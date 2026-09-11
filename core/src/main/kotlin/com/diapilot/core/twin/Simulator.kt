/**
 * Forward simulation from an anchor point — the second half of the digital
 * twin (stage-0 twin spec §3, drift = 0: the model honestly knows insulin only).
 *
 *   BG(T) = BG(anchor) + Σ_i D_i · [G(T − t_i) − G(T_anchor − t_i)]
 *
 * where G is the personal insulin action kernel and the sum runs over boluses
 * in the recent window. The uncertainty corridor w(Δt) = w0 + k·√Δt is
 * calibrated on the user's own history: the model never outputs a bare number.
 *
 * Research observations only — never dosing advice, never hypo predictions
 * presented as certainty.
 */
package com.diapilot.core.twin

import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.analysis.percentile
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import kotlin.math.sqrt

/** Boluses older than this contribute nothing new (kernel is flat by then). */
const val BOLUS_LOOKBACK_MS = 6L * 3_600_000

/**
 * A meal (or a food note) currently acting on BG. The response is modeled as
 * a smoothstep rise to [rise] over [timeToPeakMin] — learned from the user's
 * own labeled repeats of that food. The subsequent fall is insulin's job and
 * is already modeled by the kernel.
 */
data class ActiveFood(
    val onsetMs: Long,
    val rise: Double,          // mmol/L trough -> peak
    val timeToPeakMin: Double,
    /** When this food became KNOWABLE. Live entry: the moment it was
     *  logged (~onset). Detector meals: after the peak - the detector
     *  cannot see a rise before it happens. Evaluation must filter on
     *  this, not on onset, or the backtest quietly reads the future. */
    val knownAtMs: Long = onsetMs,
    /** Slow second phase (fat/protein tail): extra rise realized over
     *  [tailTtpMin] on top of the fast component. 0 = single-phase. */
    val tailRise: Double = 0.0,
    val tailTtpMin: Double = 0.0,
    val tailStartMin: Double = 0.0,
    /** How the SHAPE (ttp/tail) was sourced — lets the fingerprint model avoid
     *  overriding a dish's own measured curve with a pooled average. */
    val shapeSource: ShapeSource = ShapeSource.WEAK,
    /** Two-component continuous absorption (Phase-3). When present it REPLACES
     *  the smoothstep rise/tail: a fast + a slow gamma component, so smoothie
     *  (fast only), bread+egg (fast + small slow) and pizza (fast + strong late)
     *  fall out of one form. Null = legacy smoothstep. */
    val absorption: TwoGamma? = null,
    /** Carbs (g) this meal carried, when known — the note's own number. Purely
     *  for COB: the simulator works in mmol and never reads this. */
    val carbGrams: Double = 0.0,
    /**
     * Minutes between EATING and the rise actually starting. Food is not in the
     * blood the moment it is in the mouth; the gamma below starts at tau=0 and
     * food does not.
     *
     * Untreated, the model believes a ttp=60 meal is 76% absorbed by minute 37
     * and forecasts only the remaining 24% while the insulin is all still
     * ahead — so the line turns DOWN right after a meal. That is a systematic
     * undershoot, and it was measurable: bias +0.64 mmol (actual − predicted)
     * on the clean era, sitting in the Analysis card unexplained.
     *
     * ONLY for a food whose onset is an EATING time (a logged note). A detector
     * meal's onset IS the rise — its lag has already happened, and adding one
     * would delay the food twice.
     */
    val onsetLagMin: Double = 0.0,
    /**
     * ACTIVITY (v2, negative food): this contribution is LEVEL-DEPENDENT — its
     * per-step drop is attenuated by [activityLevelGate] of the running
     * simulated BG, so a walk eats a big rise up high and is damped near the
     * floor (counter-regulation protects the low — the user's own observation
     * was "held at 4.0, then crashed AFTER" the activity). Only the acute exercise ActiveFood sets this;
     * every real food leaves it false, so the simulator is byte-identical
     * without activity. Never gate a POSITIVE rise — the gate is a safety floor
     * on a DROP only.
     */
    val activityGated: Boolean = false,
)

/**
 * Carbs still to come (g) — COB, the mirror of IOB. Observational: what is left
 * of what was EATEN, never a dose.
 *
 * Deliberately built from what is already there: each food's absorbed FRACTION
 * is foodDelta/total, so the mmol scale cancels and only the curve's shape is
 * used — the same shape the forecast draws, or the two would contradict each
 * other on screen.
 *
 * Honest about its own weakness: the shape rests on time-to-peak, the part of
 * the food model measured at ~0 value against a constant. COB
 * survives that because it is dominated by the two things we actually know —
 * the grams you typed and the time since you ate. A wrong ttp shifts WHEN the
 * number decays, not whether 60 g became roughly 25.
 */
fun cobGrams(foods: List<ActiveFood>, nowMs: Long): Double = foods.sumOf { f ->
    if (f.carbGrams <= 0.0) return@sumOf 0.0
    val tauMin = (nowMs - f.onsetMs) / 60_000.0
    if (tauMin <= 0.0) return@sumOf f.carbGrams        // eaten, nothing absorbed yet
    val total = f.absorption?.let { it.fastRise + it.slowRise } ?: (f.rise + f.tailRise)
    if (total <= 0.0) return@sumOf 0.0                 // no modelled rise → nothing to track
    val absorbed = (foodDelta(f, tauMin) / total).coerceIn(0.0, 1.0)
    f.carbGrams * (1.0 - absorbed)
}

/** Provenance of a food's shape, strongest first: an EXACT measured dish curve
 *  must never be replaced by a weaker source (dish profile, GI/speed prior). */
enum class ShapeSource { EXACT_CURVE, PROFILE, WEAK }

// ── Activity level-dependence (v2) ───────────────────────────────────────────
// Aerobic effort's glucose drain is NOT constant in the starting glucose: it is
// large up high and squeezed to almost nothing near hypo, where counter-
// regulation (glucagon, adrenaline, hepatic output) defends the floor. The
// user's own lived record is the calibration we have: one post-pancake
// walk crashed 11 → 3.0 + rescue; another low-IOB walk held at 4.0,
// then crashed AFTER the activity — held at the floor DURING the bout. So the ACUTE drop must
// vanish as the (simulated) line approaches the floor.
//
// Shape: a linear ramp, 0 at [ACTIVITY_BG_FLOOR] → 1 at [ACTIVITY_BG_FULL]. The
// numbers are a PHYSIOLOGY PRIOR, not a fit — n is far too small to fit a curve,
// and a linear ramp
// with no free parameters cannot overfit two episodes. Because the gate is ≤ 1
// everywhere, the v2 activity drop is EVERYWHERE ≤ the flat v1 drop at the same
// coefficient — it can only REMOVE a predicted crash near the floor (fewer false
// exercise-hypo alarms), never add one. That one-sided safety is the reason it
// is defensible on this little data.
const val ACTIVITY_BG_FLOOR = 4.0
const val ACTIVITY_BG_FULL = 7.0

/** 0 at/below [ACTIVITY_BG_FLOOR], 1 at/above [ACTIVITY_BG_FULL], linear between.
 *  Multiplies the acute activity DROP by how much room there is above the floor. */
fun activityLevelGate(bg: Double): Double =
    ((bg - ACTIVITY_BG_FLOOR) / (ACTIVITY_BG_FULL - ACTIVITY_BG_FLOOR)).coerceIn(0.0, 1.0)

/** A meal's absorption as the sum of two shape-2 gamma components — the amount
 *  of BG each realizes ([fastRise]/[slowRise], mmol) and WHEN each is mostly in
 *  ([fastPeakMin]/[slowPeakMin]). */
data class TwoGamma(
    val fastRise: Double,
    val slowRise: Double,
    val fastPeakMin: Double,
    val slowPeakMin: Double,
)

// [fastRise]/[slowRise] are the MEASURED plateau (peakRise), reached at ~the
// nominal peak time — so the CDF must be ~fully in by peakMin, not 71%. Shape
// factor 4.5 puts ~0.94 in at peakMin (asymptote = the plateau, no overshoot),
// while the SLOW component still carries a long tail via its own late peakMin.
private const val GAMMA_SHAPE = 4.5

/** Cumulative fraction (0→1) of a shape-2 gamma absorbed by [tauMin], scaled so
 *  ~0.94 is in by [peakMin] (asymptote 1). */
private fun gammaCdf(tauMin: Double, peakMin: Double): Double {
    if (peakMin <= 0 || tauMin <= 0) return 0.0
    val x = GAMMA_SHAPE * tauMin / peakMin
    return 1.0 - Math.exp(-x) * (1.0 + x)
}

/** Food response at τ minutes after onset. */
internal fun foodDelta(food: ActiveFood, tauMin: Double): Double {
    // Nothing is absorbed before absorption starts.
    val tau = tauMin - food.onsetLagMin
    if (tau <= 0) return 0.0
    @Suppress("NAME_SHADOWING") val tauMin = tau
    food.absorption?.let { g ->
        // Two-component continuous absorption (Phase 3).
        return g.fastRise * gammaCdf(tauMin, g.fastPeakMin) +
            g.slowRise * gammaCdf(tauMin, g.slowPeakMin)
    }
    // Legacy: fast smoothstep + optional slow (fat/protein) tail — the pizza shape.
    fun phase(rise: Double, start: Double, ttp: Double): Double {
        if (rise == 0.0 || ttp <= start || tauMin <= start) return 0.0
        if (tauMin >= ttp) return rise
        val x = (tauMin - start) / (ttp - start)
        return rise * (3 * x * x - 2 * x * x * x)
    }
    return phase(food.rise, 0.0, food.timeToPeakMin) +
        phase(food.tailRise, food.tailStartMin, food.tailTtpMin)
}

data class PredictedPoint(
    val tsMs: Long,
    val mmol: Double,
    val lo: Double,
    val hi: Double,
    /** Inner (≈50%) band for display — where the value lands half the time.
     *  Defaults collapse onto the outer band for legacy constructors. */
    val loMid: Double = lo,
    val hiMid: Double = hi,
    /**
     * WHAT THIS POINT IS MADE OF — the engine's four terms, or null.
     *
     * Added after a case where the user's own line went to 376 mg/dL where
     * the same mass-balance gave 282, and the question "food, insulin, or
     * something else" cost an evening of reconstruction: the bench collected 276
     * and named no cause. The engine COMPUTES these numbers on every step
     * (`HybridForecastPoint.foodDelta` and neighbours), but only the total ever
     * reached the database, so a stored run could not answer what it was made of.
     *
     * ON THE MODEL'S RAW SCALE, while [mmol] is on the meter-calibrated one. They
     * cannot be added, and that is deliberate: a "calibrated breakdown" would
     * require splitting the calibration lens across the terms, i.e. inventing
     * numbers the model never computed. What is stored here is exactly what it did
     * compute.
     *
     * null means "this point is not from the physio engine" (anchor bridge,
     * legacy path) — a refusal must look like a refusal, not like zero.
     */
    val foodDelta: Double? = null,
    val insulinDelta: Double? = null,
    val driftDelta: Double? = null,
    val backgroundDelta: Double? = null,
)

/**
 * Forecast uncertainty, w(Δt) = w0 + k·√Δt per side. Two refinements over
 * the original single symmetric band:
 *
 *  - ASYMMETRY: up/dn are fitted from SIGNED residual quantiles (p90/p10),
 *    so a regime where reality mostly undershoots the model (post-bolus:
 *    insulin on board pulls down) earns a thin upper band and a real lower
 *    one, instead of a fat symmetric lie in both directions.
 *  - INNER BAND: midUp/midDn (p75/p25) — the "half the time you're here"
 *    band for display; the outer band stays the honest p90/p10.
 *
 * Defaults make a plain Corridor(w0, k) behave exactly like the legacy
 * symmetric band (tests, fallbacks, thin regimes).
 */
data class Corridor(
    val w0: Double, val k: Double,
    val upW0: Double = w0, val upK: Double = k,
    val dnW0: Double = w0, val dnK: Double = k,
    val midUpW0: Double = w0 * 0.5, val midUpK: Double = k * 0.5,
    val midDnW0: Double = w0 * 0.5, val midDnK: Double = k * 0.5,
) {
    private fun w(a: Double, b: Double, dtMin: Double): Double =
        a + b * sqrt(dtMin.coerceAtLeast(0.0))
    /** Legacy symmetric |residual|-p80 width — health checks, backtests. */
    fun halfWidth(dtMin: Double): Double = w(w0, k, dtMin)
    fun upAt(dtMin: Double): Double = w(upW0, upK, dtMin)
    fun dnAt(dtMin: Double): Double = w(dnW0, dnK, dtMin)
    // Inner bands are clamped inside the outer at EVERY horizon: the four
    // quantile lines are fitted independently, so nothing else guarantees
    // lo ≤ loMid ≤ median ≤ hiMid ≤ hi across all Δt.
    fun midUpAt(dtMin: Double): Double = minOf(w(midUpW0, midUpK, dtMin), upAt(dtMin))
    fun midDnAt(dtMin: Double): Double = minOf(w(midDnW0, midDnK, dtMin), dnAt(dtMin))
}

/** Kernel lookup with the spec boundary conditions: G(τ≤0)=0, G(τ>T)=G(T). */
internal fun g(kernel: List<KernelPoint>, tauMin: Double): Double {
    if (tauMin <= 0 || kernel.isEmpty()) return 0.0
    val last = kernel.last()
    if (tauMin >= last.tauMin) return last.median
    return kernel.lastOrNull { it.tauMin <= tauMin }?.median ?: 0.0
}

/**
 * Simulate BG forward from the anchor. Only boluses at or before each
 * predicted moment contribute (future boluses are unknown by definition).
 */
fun simulateForward(
    anchorTsMs: Long,
    anchorMmol: Double,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    stepMin: Double = 5.0,
    horizonMin: Double = 180.0,
    foods: List<ActiveFood> = emptyList(),
): List<GlucosePoint> {
    val active = boluses.filter {
        it.tsMs > anchorTsMs - BOLUS_LOOKBACK_MS && it.tsMs <= anchorTsMs
    }
    // Same increment logic as insulin: only the not-yet-realized part of the
    // food response counts; a meal that peaked before the anchor adds nothing.
    val activeFoods = foods.filter {
        // The lag pushes the whole curve later — a meal eaten 60 min ago with a
        // 16-min lag has only been absorbing for 44 and is still acting.
        it.tauMin(anchorTsMs) < it.onsetLagMin + maxOf(it.timeToPeakMin, it.tailTtpMin)
    }
    // Activity foods are integrated INCREMENTALLY (each step's drop gated by the
    // level entering that step); everything else stays an exact from-anchor
    // superposition. With the gate ≡ 1 (BG always above ACTIVITY_BG_FULL) the two
    // paths telescope to the identical value — see activityGated's invariant test.
    val plainFoods = activeFoods.filter { !it.activityGated }
    val gatedFoods = activeFoods.filter { it.activityGated }
    val out = mutableListOf<GlucosePoint>()
    var activityDrop = 0.0
    var prevTs = anchorTsMs
    var m = 0.0
    while (m <= horizonMin) {
        val ts = anchorTsMs + (m * 60_000).toLong()
        var delta = 0.0
        for (b in active) {
            val tauT = (ts - b.tsMs) / 60_000.0
            val tauA = (anchorTsMs - b.tsMs) / 60_000.0
            delta += b.units * (g(kernel, tauT) - g(kernel, tauA))
        }
        for (f in plainFoods) {
            delta += foodDelta(f, f.tauMin(ts)) - foodDelta(f, f.tauMin(anchorTsMs))
        }
        if (gatedFoods.isNotEmpty()) {
            val gate = activityLevelGate(out.lastOrNull()?.mmol ?: anchorMmol)
            for (f in gatedFoods) {
                activityDrop += gate * (foodDelta(f, f.tauMin(ts)) - foodDelta(f, f.tauMin(prevTs)))
            }
        }
        out.add(GlucosePoint(ts, anchorMmol + delta + activityDrop))
        prevTs = ts
        m += stepMin
    }
    return out
}

private fun ActiveFood.tauMin(atMs: Long): Double = (atMs - onsetMs) / 60_000.0

/** Simulation plus the calibrated corridor. */
fun predictWithCorridor(
    anchorTsMs: Long,
    anchorMmol: Double,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    corridor: Corridor,
    stepMin: Double = 5.0,
    horizonMin: Double = 180.0,
    foods: List<ActiveFood> = emptyList(),
): List<PredictedPoint> =
    simulateForward(anchorTsMs, anchorMmol, boluses, kernel, stepMin, horizonMin, foods).map { p ->
        val dtMin = (p.tsMs - anchorTsMs) / 60_000.0
        PredictedPoint(
            p.tsMs, p.mmol,
            lo = p.mmol - corridor.dnAt(dtMin),
            hi = p.mmol + corridor.upAt(dtMin),
            loMid = p.mmol - corridor.midDnAt(dtMin),
            hiMid = p.mmol + corridor.midUpAt(dtMin),
        )
    }

/**
 * The corridor's semantics are CONDITIONAL: "if nothing new happens". A live
 * forecast is redrawn the moment a meal or bolus is logged, so residuals
 * measured ACROSS a later event describe an error the user never sees — they
 * only inflate the band with what-if-you-eat scenarios ("could jump to 300"
 * on a quiet evening). Each anchor's residual collection therefore stops at
 * the first event (bolus or food onset) after the anchor. The flip side is
 * honest too: unloggable surprises (stress, illness, compression) still
 * break the conditional band sometimes — that break is the deviation card's
 * "explain this" signal, not a corridor failure.
 */
internal fun firstEventAfter(
    anchorTsMs: Long,
    boluses: List<BolusPoint>,
    foods: List<ActiveFood>,
): Long {
    val b = boluses.firstOrNull { it.tsMs > anchorTsMs }?.tsMs ?: Long.MAX_VALUE
    // Foods truncate at knownAtMs, NOT onsetMs: the live forecast is redrawn
    // when the food becomes KNOWN (logged / detector-confirmed), so errors
    // between an unnoticed onset and its detection WERE shown to the user
    // and belong in the corridor. Manual food has knownAt == onset.
    val f = foods.filter { it.knownAtMs > anchorTsMs }
        .minOfOrNull { it.knownAtMs } ?: Long.MAX_VALUE
    return minOf(b, f)
}

/**
 * Fit one band line w(Δt) = w0 + k·√Δt from per-bin SIGNED residuals via
 * [pick] (a quantile of the sorted signed array, returned as a positive
 * distance from the median line). Null when too few populated bins.
 */
private fun fitBandLine(
    sortedBins: List<DoubleArray?>,
    binMin: Double,
    minBins: Int,
    floorW0: Double,
    floorK: Double,
    pick: (DoubleArray) -> Double,
): Pair<Double, Double>? {
    val xs = mutableListOf<Double>()
    val ys = mutableListOf<Double>()
    sortedBins.forEachIndexed { i, arr ->
        if (arr != null) {
            xs.add(sqrt((i + 0.5) * binMin))
            ys.add(pick(arr).coerceAtLeast(0.0))
        }
    }
    if (xs.size < minBins) return null
    val n = xs.size
    val mx = xs.average(); val my = ys.average()
    var cov = 0.0; var varX = 0.0
    for (i in 0 until n) {
        cov += (xs[i] - mx) * (ys[i] - my)
        varX += (xs[i] - mx) * (xs[i] - mx)
    }
    if (varX == 0.0) return null
    val k = (cov / varX).coerceAtLeast(floorK)
    val w0 = (my - k * mx).coerceAtLeast(floorW0)
    return w0 to k
}

/**
 * Build the full asymmetric corridor from per-bin signed residuals:
 * legacy |·|-p80 symmetric line + signed p90/p10 outer sides + p75/p25
 * inner sides. Null when even the legacy fit lacks data.
 */
internal fun corridorFromSignedBins(
    signedBins: Array<MutableList<Double>>,
    binMin: Double,
    minPerBin: Int,
    minBins: Int,
): Corridor? {
    val sorted = signedBins.map { v ->
        if (v.size >= minPerBin) v.toDoubleArray().sortedArray() else null
    }
    val legacy = fitBandLine(sorted, binMin, minBins, floorW0 = 0.2, floorK = 0.05) { arr ->
        percentile(DoubleArray(arr.size) { kotlin.math.abs(arr[it]) }.sortedArray(), 80.0)
    } ?: return null
    val up = fitBandLine(sorted, binMin, minBins, 0.2, 0.05) { percentile(it, 90.0) }
    val dn = fitBandLine(sorted, binMin, minBins, 0.2, 0.05) { -percentile(it, 10.0) }
    val midUp = fitBandLine(sorted, binMin, minBins, 0.1, 0.02) { percentile(it, 75.0) }
    val midDn = fitBandLine(sorted, binMin, minBins, 0.1, 0.02) { -percentile(it, 25.0) }
    return Corridor(
        w0 = legacy.first, k = legacy.second,
        upW0 = up?.first ?: legacy.first, upK = up?.second ?: legacy.second,
        dnW0 = dn?.first ?: legacy.first, dnK = dn?.second ?: legacy.second,
        midUpW0 = midUp?.first ?: legacy.first * 0.5,
        midUpK = midUp?.second ?: legacy.second * 0.5,
        midDnW0 = midDn?.first ?: legacy.first * 0.5,
        midDnK = midDn?.second ?: legacy.second * 0.5,
    )
}

/**
 * Calibrate the corridor on history: anchors along the stream, forward
 * simulation, SIGNED residuals per Δt bin (truncated at the first post-anchor
 * event — see [firstEventAfter]), quantile lines fitted against √Δt.
 *
 * Fallback when history is too thin: a deliberately wide symmetric default.
 */
fun calibrateCorridor(
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    anchorStepMin: Double = 60.0,
    horizonMin: Double = 180.0,
    binMin: Double = 15.0,
    // Known food, filtered per anchor: without it every meal's rise lands
    // in the residuals and the corridor stays inflated.
    foods: List<ActiveFood> = emptyList(),
): Corridor {
    val fallback = Corridor(w0 = 0.5, k = 0.25)
    if (readings.size < 50 || kernel.isEmpty()) return fallback
    val sortedFoods = foods.sortedBy { it.onsetMs }
    val sortedBoluses = boluses.sortedBy { it.tsMs }

    val r = readings.sortedBy { it.tsMs }
    val ts = LongArray(r.size) { r[it].tsMs }
    fun lowerBound(key: Long): Int {
        var lo = 0; var hi = ts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    val nBins = (horizonMin / binMin).toInt()
    val residualBins = Array(nBins) { mutableListOf<Double>() }

    var anchorIdx = 0
    while (anchorIdx < r.size) {
        val anchor = r[anchorIdx]
        // Conditional band: stop collecting at the first new event — a live
        // forecast is redrawn there, so errors across it are never displayed.
        val horizonEnd = minOf(
            anchor.tsMs + (horizonMin * 60_000).toLong(),
            firstEventAfter(anchor.tsMs, sortedBoluses, sortedFoods),
        )
        val sim = simulateForward(
            anchor.tsMs, anchor.mmol, boluses, kernel,
            foods = sortedFoods.filter { it.knownAtMs <= anchor.tsMs },
        )
        val simByBin = sim.associateBy { ((it.tsMs - anchor.tsMs) / 60_000.0 / binMin).toInt() }
        var i = lowerBound(anchor.tsMs + 1)
        while (i < r.size && r[i].tsMs <= horizonEnd) {
            val dtMin = (r[i].tsMs - anchor.tsMs) / 60_000.0
            val bin = (dtMin / binMin).toInt()
            if (bin in 0 until nBins) {
                simByBin[bin]?.let { p -> residualBins[bin].add(r[i].mmol - p.mmol) }
            }
            i++
        }
        // Next anchor at least anchorStepMin later.
        val nextTs = anchor.tsMs + (anchorStepMin * 60_000).toLong()
        anchorIdx = maxOf(anchorIdx + 1, lowerBound(nextTs))
    }

    return corridorFromSignedBins(residualBins, binMin, minPerBin = 20, minBins = 3)
        ?: fallback
}
