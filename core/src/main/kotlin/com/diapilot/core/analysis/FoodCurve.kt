/**
 * Per-dish empirical response curve — food v2.
 *
 * One (rise, timeToPeak) pair describes a smoothie but lies about pizza:
 * fat-and-protein meals keep rising for 3-5 hours after the "peak". Here
 * each dish with enough repeats earns its own measured curve — the median
 * insulin-adjusted ΔBG at fixed checkpoints after eating, recency-weighted
 * like everything else.
 *
 * Insulin adjustment uses the current kernel, so the curve inherits its
 * error; the display must say so. Observations only — never dosing advice.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint

data class DishCurvePoint(
    val tauMin: Double,
    val deltaMmol: Double,   // median insulin-adjusted ΔBG vs the pre-meal level
    val n: Int,              // repeats contributing to this checkpoint
)

data class DishCurve(
    val points: List<DishCurvePoint>,
    val nEpisodes: Int,
    /** Effective glycemic index inferred from the measured time-to-peak
     *  (inverse of the GI→ttp prior); null when the curve is too thin. */
    val effectiveGi: Double?,
    /** A slow second phase: the late tail still holds most of the peak. */
    val hasLateTail: Boolean,
)

/** A known non-dish influence which makes a meal episode unsuitable for
 * learning: rescue carbs, exercise, illness/stress, or another unpaired food. */
data class FoodContaminationWindow(val startMs: Long, val endMs: Long)

/**
 * Sorted readings + binary search, built ONCE and shared by every
 * [dishResponseCurve] call of a deconvolution.
 *
 * WHY THIS EXISTS — it is a measurement, not tidiness. `dishResponseCurve` used
 * to `sortedBy` the whole reading list and then linearly `filter` it for the
 * episode window, on EVERY call. A twin build makes ~470 of those calls (one per
 * meal × up to 4 carryover passes, twice over — the dossier audit re-runs the
 * deconvolution), against ~148 000 readings. That is ~140 M element visits and
 * ~560 MB of throwaway arrays per build; on the phone it showed as 93.8% CPU on
 * one dispatcher thread with the GC daemon at 20.4% beside it.
 *
 * IDENTITY IS THE POINT, so both operations are reproduced exactly:
 *  · the sort is the same STABLE `sortedBy { it.tsMs }`, so equal timestamps keep
 *    their original order — `bgNear` breaks ties by taking the first minimum, so
 *    that order is observable;
 *  · [range] returns the contiguous slice a sorted list's `filter` would return,
 *    as a `subList` VIEW — same elements, same order, no copy.
 */
internal class ReadingIndex private constructor(
    val pts: List<GlucosePoint>,
    private val ts: LongArray,
) {
    fun lowerBound(key: Long): Int {
        var lo = 0; var hi = ts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Nearest reading to [t] within 10 min, ties to the earlier one. */
    fun bgNear(t: Long): Double? {
        val j = lowerBound(t)
        return listOfNotNull(pts.getOrNull(j - 1), pts.getOrNull(j))
            .minByOrNull { kotlin.math.abs(it.tsMs - t) }
            ?.takeIf { kotlin.math.abs(it.tsMs - t) <= 10 * 60_000 }?.mmol
    }

    /** The readings with `tsMs in from..to`, as a view. */
    fun range(from: Long, to: Long): List<GlucosePoint> {
        val lo = lowerBound(from)
        val hi = lowerBound(to + 1)
        return if (lo >= hi) emptyList() else pts.subList(lo, hi)
    }

    companion object {
        fun of(readings: List<GlucosePoint>): ReadingIndex {
            val r = readings.sortedBy { it.tsMs }
            return ReadingIndex(r, LongArray(r.size) { r[it].tsMs })
        }
    }
}

/** The same trick for boluses — `insulinDelta` filtered all of them per
 *  checkpoint, i.e. 7× per episode. */
internal class BolusIndex private constructor(
    private val pts: List<BolusPoint>,
    private val ts: LongArray,
) {
    private fun lowerBound(key: Long): Int {
        var lo = 0; var hi = ts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Boluses with `tsMs > after && tsMs <= to` — `insulinDelta`'s predicate,
     *  which on a time-sorted list selects exactly one contiguous run. */
    fun range(after: Long, to: Long): List<BolusPoint> {
        val lo = lowerBound(after + 1)
        val hi = lowerBound(to + 1)
        return if (lo >= hi) emptyList() else pts.subList(lo, hi)
    }

    companion object {
        fun of(boluses: List<BolusPoint>): BolusIndex {
            val b = boluses.sortedBy { it.tsMs }
            return BolusIndex(b, LongArray(b.size) { b[it].tsMs })
        }
    }
}

fun dishResponseCurve(
    onsetsMs: List<Long>,
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    nowMs: Long,
    checkpoints: List<Double> = listOf(30.0, 60.0, 90.0, 120.0, 180.0, 240.0, 300.0),
    halfLifeDays: Double = com.diapilot.core.PersonalParams.DEFAULT.foodHalfLifeDays,
    minEpisodes: Int = 3,
    // ALL meal onsets (any dish): an episode with another meal inside its
    // 5-hour window is contaminated — that other food's rise would be
    // attributed to THIS dish (a late tail from a follow-on dessert misread as
    // this dish's own tail).
    allOnsetsMs: List<Long> = emptyList(),
    contaminationWindows: List<FoodContaminationWindow> = emptyList(),
    // Reject an episode whose CGM jumps faster than this (mmol/min) — a
    // compression/artifact guard. 0.25 suits slow dish curves, but FAST carbs
    // (a smoothie, dextrose) legitimately rise that fast, so the note-anchored
    // deconvolution passes a higher limit or it would drop exactly what it wants.
    maxJumpMmolPerMin: Double = 0.25,
    // Carryover from KNOWN neighbouring meals (both the still-absorbing prior one
    // and — once its shape is known — the next one the window extends through),
    // as absolute-time mmol. Subtracted exactly like insulin: the delta over
    // [onset, t]. Default: nothing to subtract, identical to before.
    foodCarryover: ((onsetMs: Long, tMs: Long) -> Double)? = null,
    kernelForBolus:((BolusPoint)->List<KernelPoint>)?=null,
    /**
     * DIVIDE THE INSULIN BETWEEN MEALS THE WAY THE CARBOHYDRATE IS DIVIDED.
     *
     * The window returns ALL the insulin in it but shares out only the food, so
     * a meal eaten into another meal's insulin is charged with all of it. A
     * measured case: glucose flat over five hours despite a substantial injected
     * dose in the window, with a modest cake portion made to explain a large
     * apparent rise.
     *
     * With a share, `Δglucose − all insulin` is read as the whole meal's food
     * signal and split by how much carbohydrate each meal is expected to deliver
     * inside the window. A lone meal has a share of 1 and is untouched to the
     * digit — the clean episodes the corpus trusts most cannot move.
     *
     * The circularity is real but weak, and worth stating: the SPLIT uses the
     * model's own carb curve, while the TOTAL stays measured. That is far less
     * than the joint attribution, whose per-meal amplitude is clamped to 1.35x a
     * prior built from the very carb sensitivity we would be estimating.
     */
    foodShare: ((onsetMs: Long, tMs: Long) -> Double)? = null,
): DishCurve? {
    if (readings.isEmpty()) return null
    // LAZY, and that is not a style choice. Most calls die on the contamination
    // filter below without touching a single reading — 51 of 51 labelled dishes
    // do, on this history. Building the index eagerly here made that free
    // rejection cost a 148 000-point sort each, and the A/B caught it: dish
    // curves went 45 → 135 ms while everything else halved.
    return dishResponseCurve(
        onsetsMs,
        lazy(LazyThreadSafetyMode.NONE) { ReadingIndex.of(readings) },
        lazy(LazyThreadSafetyMode.NONE) { BolusIndex.of(boluses) },
        kernel, nowMs, checkpoints, halfLifeDays, minEpisodes, allOnsetsMs,
        contaminationWindows, maxJumpMmolPerMin, foodCarryover,kernelForBolus,foodShare,
    )
}

/**
 * The same curve, over PREBUILT indices — for the callers that make hundreds of
 * these against one series (the deconvolution). Building the index per call was
 * the whole cost; see [ReadingIndex]. Behaviour is identical by construction.
 */
internal fun dishResponseCurve(
    onsetsMs: List<Long>,
    readings: Lazy<ReadingIndex>,
    boluses: Lazy<BolusIndex>,
    kernel: List<KernelPoint>,
    nowMs: Long,
    checkpoints: List<Double> = listOf(30.0, 60.0, 90.0, 120.0, 180.0, 240.0, 300.0),
    halfLifeDays: Double = com.diapilot.core.PersonalParams.DEFAULT.foodHalfLifeDays,
    minEpisodes: Int = 3,
    allOnsetsMs: List<Long> = emptyList(),
    contaminationWindows: List<FoodContaminationWindow> = emptyList(),
    maxJumpMmolPerMin: Double = 0.25,
    foodCarryover: ((onsetMs: Long, tMs: Long) -> Double)? = null,
    kernelForBolus:((BolusPoint)->List<KernelPoint>)?=null,
    /** See the public overload — this meal's share of the meal's food signal. */
    foodShare: ((onsetMs: Long, tMs: Long) -> Double)? = null,
): DishCurve? {
    // The analysis stops at the last checkpoint, so contamination is only
    // disqualifying INSIDE that window. A phase-limited smoothie curve
    // (maxTau≈60) must not be thrown away by an activity event two hours later
    // that its checkpoints never touch — that hardcoded 5h reach dropped almost
    // every isolated fast-carb episode the deconvolution exists to learn.
    val analysisEndMin = checkpoints.maxOrNull() ?: 300.0
    val clean = onsetsMs.filter { onset ->
        val episodeStart = onset - 30L * 60_000
        val episodeEnd = onset + (analysisEndMin * 60_000).toLong()
        allOnsetsMs.none { other ->
            other != onset && other in (onset - 90L * 60_000)..episodeEnd
        } && contaminationWindows.none { it.startMs <= episodeEnd && it.endMs >= episodeStart }
    }
    @Suppress("NAME_SHADOWING") val onsetsMs = clean
    if (onsetsMs.size < minEpisodes) return null
    fun bgNear(t: Long): Double? = readings.value.bgNear(t)
    fun insulinDelta(onset: Long, t: Long): Double = boluses.value
        .range(onset - 6L * 3_600_000, t)
        .sumOf { b ->
            val eventKernel=kernelForBolus?.invoke(b)?:kernel
            val tauT = (t - b.tsMs) / 60_000.0
            val tauO = (onset - b.tsMs) / 60_000.0
            b.units * (g(eventKernel, tauT) - g(eventKernel, tauO))
        }

    val halfLifeMs = halfLifeDays * 86_400_000.0
    val bins = checkpoints.map { mutableListOf<Pair<Double, Double>>() }  // (delta, weight)
    var used = 0
    for (onset in onsetsMs) {
        // Sparse or implausibly jumping CGM must not teach a dish shape. A
        // checkpoint alone can look valid even when the intervening sensor
        // trace was disconnected or contained a compression/artifact jump.
        val episodeReadings = readings.value.range(
            onset - 15L * 60_000,
            onset + (checkpoints.maxOrNull() ?: 300.0).toLong() * 60_000,
        )
        val badGap = episodeReadings.zipWithNext().any { (a, b) -> b.tsMs - a.tsMs > 20L * 60_000 }
        val badJump = episodeReadings.zipWithNext().any { (a, b) ->
            val minutes = (b.tsMs - a.tsMs) / 60_000.0
            minutes > 0 && kotlin.math.abs(b.mmol - a.mmol) / minutes > maxJumpMmolPerMin
        }
        if (episodeReadings.size < 8 || badGap || badJump) continue
        val bg0 = bgNear(onset) ?: continue
        val w = Math.pow(2.0, -((nowMs - onset).coerceAtLeast(0L)) / halfLifeMs)
        var contributed = false
        val carry0 = foodCarryover?.invoke(onset, onset) ?: 0.0
        checkpoints.forEachIndexed { i, tau ->
            val t = onset + (tau * 60_000).toLong()
            bgNear(t)?.let { bg ->
                val carry = (foodCarryover?.invoke(onset, t) ?: 0.0) - carry0
                val whole = (bg - bg0) - insulinDelta(onset, t)
                val value = if (foodShare == null) whole - carry
                else whole * foodShare(onset, t).coerceIn(0.0, 1.0)
                bins[i].add(value to w)
                contributed = true
            }
        }
        if (contributed) used++
    }
    if (used < minEpisodes) return null

    val points = checkpoints.mapIndexedNotNull { i, tau ->
        if (bins[i].size < minEpisodes) null
        else DishCurvePoint(tau, weightedPercentile(bins[i], 50.0), bins[i].size)
    }
    if (points.size < 3) return null

    // Effective GI: invert the GI→time-to-peak prior from the MEASURED peak.
    val peak = points.maxByOrNull { it.deltaMmol } ?: return null
    val effGi = if (peak.deltaMmol > 0.8) {
        ((115.0 - peak.tauMin) / 0.85).coerceIn(10.0, 110.0)
    } else null

    // Late tail: at 4-5 h the response still holds >=60% of the peak while
    // the peak itself came early — the pizza signature.
    val late = points.filter { it.tauMin >= 240.0 }.maxOfOrNull { it.deltaMmol }
    val hasTail = late != null && peak.deltaMmol > 0.8 &&
        peak.tauMin <= 120.0 && late >= 0.6 * peak.deltaMmol

    return DishCurve(
        points = points,
        nEpisodes = used,
        effectiveGi = effGi,
        hasLateTail = hasTail,
    )
}

/** The measured curve split into a fast phase and a late fat/protein tail. */
data class DishTwoPhase(
    val rise: Double,        // fast phase: the early peak (≤ 2 h)
    val ttpMin: Double,
    val tailRise: Double,    // EXTRA late rise on top of the early peak
    val tailStartMin: Double,
    val tailTtpMin: Double,
)

/**
 * Two-phase extraction. The early peak is the max at τ ≤ 120 min; the tail
 * is how much the late max (τ ≥ 180 min) exceeds it — comparing the late
 * points against the GLOBAL max can never fire (a global max is ≥ any
 * late point by definition; external review round 6).
 *
 * A late tail is a strong claim: it feeds the forecast only when the
 * curve has at least [minTailEpisodes] repeats behind it.
 */
fun dishTwoPhase(curve: DishCurve, minTailEpisodes: Int = 5): DishTwoPhase? {
    val early = curve.points.filter { it.tauMin <= EARLY_PHASE_END_MIN }.maxByOrNull { it.deltaMmol }
        ?: curve.points.maxByOrNull { it.deltaMmol }?.let {
            // No early coverage at all — single-phase from whatever exists.
            return DishTwoPhase(it.deltaMmol.coerceAtLeast(0.3), it.tauMin, 0.0, 0.0, 0.0)
        }
        ?: return null
    val late = curve.points.filter { it.tauMin >= 180.0 }.maxByOrNull { it.deltaMmol }
    val tail = late
        ?.let { (it.deltaMmol - early.deltaMmol).coerceAtLeast(0.0) }
        ?.takeIf { it > 0.2 && curve.nEpisodes >= minTailEpisodes }
        ?: 0.0
    return DishTwoPhase(
        rise = early.deltaMmol.coerceAtLeast(0.3),
        ttpMin = early.tauMin,
        tailRise = tail,
        // The extra fat/protein phase starts after the early component has
        // reached its peak; starting both at the meal timestamp systematically
        // over-predicted mixed meals during their first hour.
        tailStartMin = if (tail > 0) early.tauMin else 0.0,
        tailTtpMin = if (tail > 0) late!!.tauMin else 0.0,
    )
}

// Note-anchored meal observations — the deconvolution the food model needs.
//
// The detector keys on a NET glucose rise, so a correctly-dosed meal (carbs up,
// insulin down, glucose flat) is invisible and never teaches its profile. Here
// every logged food NOTE is the anchor instead: its absorption curve is the
// observed ΔBG with the insulin effect ADDED BACK (dishResponseCurve already
// subtracts the kernel's contribution). A flat-but-dosed meal recovers its full
// rise from the insulin curve — the strongest, cleanest signal.
//
// One observation per isolated note (dishResponseCurve excludes notes with
// another meal in their window — you can't cleanly separate overlapping carbs).
// Each observation carries its own single-episode late-tail CANDIDATE (no n≥5
// gate here); robustness comes downstream — predictKinetics pools ≥3 donors by
// concept and takes the weighted MEDIAN, so a stray tail can't move the estimate
// but a real one (pizza at hour 3-4) survives across repeats.
//
// CAVEAT the caller owns: this trusts the [kernel]. A weak insulin model bleeds
// into every recovered profile — pin the kernel on clean insulin-only
// corrections first, then deconvolve meals against it.
/** Dense-early checkpoints for note-anchored curves — most meals have <2h of
 *  clean window before the next, so the early phase must be well sampled. */
// The early points (5/10/15) exist to measure the ONSET — when absorption
// actually starts. The list began at 20, so nothing before minute 20 was ever
// visible and the lag could only ever be one global constant (16 min, the
// median of every note lumped together). Breakfast waits ~30 and dextrose ~3;
// no single number is right for both, and the corpus could not say so.
// They cannot move ttp: it is an argmax, and a point at minute 5 wins it only
// if the meal never rose at all (already excluded by the rise>=0.5 gate).
/** Public: the card samples the MODEL curve on the same grid the recovered
 *  fact curve uses, so the two spark rows are comparable point for point. */
val DECONV_CHECKPOINTS =
    listOf(5.0, 10.0, 15.0, 20.0, 30.0, 45.0, 60.0, 75.0, 90.0, 120.0, 180.0, 240.0, 300.0)

/** Onset = the curve crossing this share of its own peak — scale-free, so a
 *  2-unit rise and a 0.6-unit one are judged the same way. Floored in absolute
 *  mmol so sensor noise on a small rise can't read as «absorption started». */
// Above this end-of-window slope the meal was still rising when the window was
// cut → censored. Below it the curve had levelled off (or turned down) → the
// peak was seen. 0.03 mmol/min ≈ 0.45 mmol over a 15-min gap: a real climb, not
// a plateau's residual drift. Chosen against the smoothie curves: it censors the
// two genuinely-still-rising episodes (slope 0.08) and keeps the two plateaued
// ones (0.015/−0.01) as observed.
private const val CENSOR_CLIMB_SLOPE_MMOL_PER_MIN = 0.03

// Pooling weight kept for a meal eaten during activity. Small on purpose: the
// observation is real but the activity shifted its response, so it should nudge
// the pooled shape, not steer it. ~3 such episodes ≈ one clean one.
const val SOFT_CONFOUNDER_CONFIDENCE = 0.35

// Pooling weight kept for a curve recovered by subtracting a neighbour meal. The
// subtraction uses the neighbour's AVERAGE profile; a single day deviates from
// it, so the residual is real but noisy — trusted about half.
const val CARRYOVER_CONFIDENCE = 0.5

// The same subtraction when the neighbour's shape was NOT measured — a censored
// floor, or the concept archetype and the global carb sensitivity. Two guessed
// factors (shape AND amplitude) where the pooled case guesses one, so the same
// halving applied twice: 0.5 × 0.5. Derived from the constant above rather than
// picked, so the two move together if that one is ever re-measured.
const val CARRYOVER_ROUGH_CONFIDENCE = CARRYOVER_CONFIDENCE * CARRYOVER_CONFIDENCE

// A material neighbour whose subtraction had to be DISCARDED (it drove the curve
// well below zero — our model of that neighbour is wrong). The episode's rise is
// knowingly inflated and nothing was removed, so it is worse than any subtracted
// case: kept as faint evidence, never allowed to steer a concept, and above all
// never recorded as «clean» — which is what happens today.
/**
 * How many minutes a previous meal is still considered absorbing.
 *
 * Replaces the quantitative overlap estimate that relied on subtracting a
 * neighbour's learned profile: profiles are gone, but the fact of adjacency
 * remains.
 */
const val NEIGHBOUR_ABSORBING_MIN = 240.0

/** Below this the curve's residual is genuinely negative, not noise. Lived in
 *  the removed `ComponentDeconvolution`; the only reader left is the check
 *  for "the insulin profile is wrong". */
const val NEGATIVE_RESIDUAL_MMOL = -0.5
const val MAX_NEGATIVE_RESIDUAL_POINTS = 3

const val OVERLAP_UNRESOLVED_CONFIDENCE = 0.15

// Subtracted mmol below which a neighbour is not worth marking at all — the
// pre-existing threshold, kept verbatim so the pooled path is unchanged.
const val CARRYOVER_MIN_MMOL = 0.1

// ...and as a SHARE of the rise the episode kept. A neighbour that delivered a
// tenth of what this meal shows can only move the estimate by about that much,
// which is inside the spread every pooled number already carries.
const val NEIGHBOUR_MATERIAL_SHARE = 0.10

// A meal cannot raise glucose by more than this per gram of carbs — well above
// any real carb sensitivity (~0.13), so only CONTAMINATION exceeds it: rescue
// dextrose taken for a hypo, where the recovered curve also holds the insulin
// over-shoot the kernel under-counted (a small dextrose dose reading a large
// mmol rise). Such an episode is kept but heavily down-weighted — it was also the
// SLOW one dragging dextrose's ttp 30→60.
const val AMPLITUDE_CEILING_MMOL_PER_G = 0.5
const val IMPLAUSIBLE_AMPLITUDE_CONFIDENCE = 0.2

// Was the peak actually SEEN, or did the window close while glucose was still
// rising? Only a truncated window can censor; within it, the test is the slope
// across the last checkpoint gap. A plateau or a decline (slope ≤ threshold)
// means the peak was reached — even when the flat top's argmax happens to sit on
// the final point, which the old `argmax == lastCheckpoint` test read as «still
// rising» and wrongly censored every plateaued smoothie.
/** Where the early phase ends. Named because [dishTwoPhase] and
 * [isEarlyPeakBounded] must agree on it: one decides the peak, the other decides
 * whether that peak is a measurement or a bound. */
const val EARLY_PHASE_END_MIN = 120.0

/** The extra late rise above which the curve is judged to have kept climbing.
 * Same 0.2 mmol [dishTwoPhase] uses to admit a tail, so the two cannot disagree
 * about whether a late phase exists. */
private const val BOUNDARY_STILL_RISING_MMOL = 0.2

// Is the early peak pinned to the phase BOUNDARY rather than measured?
//
// `dishTwoPhase` reads ttp as the argmax over `tau <= 120`. When a meal is still
// climbing through 120 the argmax lands exactly on the boundary, and 120 is
// recorded as if it were a peak. Measured on the corpus:
// ttp took six values and **36 of 76** usable observations sat on 120 — of which
// **31 (86%) had a late rise above 0.2 mmol**, i.e. the curve was still going up.
//
// That is a right-censored bound wearing a point estimate's clothes, and it is
// why two separate questions could not be answered: the magnitude of the macro
// effect on time-to-peak, and whether the 30 g/h throughput cap is too slow.
// Both read a variable whose upper half is one value.
//
// [isPeakObserved] does not catch it because it asks a different question — was
// the WINDOW cut by the next meal — and answers `true` immediately for any meal
// with a long clean window, however early its own phase boundary bites.
//
// Deliberately NOT folded into `peakObserved`: the two flags gate different
// things. A window-truncated episode understates the AMPLITUDE as well and must
// leave both corpora. A boundary-bounded one has a perfectly good total rise
// (peak + observed tail) — only its TIMING is a bound.
/**
 * PUBLIC, so an instrument can re-apply the phone's own gate instead of
 * re-implementing it.
 *
 * `twin_snapshot.json` stored the checkpoint curve but not this verdict, so a
 * restored corpus came back with every episode unbounded. The reader now
 * recomputes the flag from the stored curve when the field is absent — and it
 * must be THIS function, not a re-spelling of the same three lines, or the
 * stand starts measuring a gate the phone does not apply (discipline #7).
 */
fun isEarlyPeakBounded(points: List<DishCurvePoint>): Boolean {
    val early = points.filter { it.tauMin <= EARLY_PHASE_END_MIN }.maxByOrNull { it.deltaMmol }
        ?: return false
    if (early.tauMin < EARLY_PHASE_END_MIN) return false

    val late = points.filter { it.tauMin > EARLY_PHASE_END_MIN }.maxByOrNull { it.deltaMmol }
        ?: return false
    return late.deltaMmol - early.deltaMmol > BOUNDARY_STILL_RISING_MMOL
}


/** The widest pre-boundary interval over which an approach slope still means
 *  what the threshold assumes. The checkpoint grid puts the previous checkpoint
 *  at 90, so every real episode today sits exactly at this value. */
private const val MAX_JUDGEABLE_APPROACH_GAP_MIN = 30.0

/**
 * The LATE phase's own boundary — the sibling [isEarlyPeakBounded] never got.
 *
 * The user's question: what if a fatty dish's rise keeps going past 3 hours?
 * v16 censored a peak pinned to the INNER phase boundary at 120 and left the
 * OUTER one alone, and the outer one holds most of the cases: measured on the
 * corpus, the tail's maximum sits on the very last point of the window in
 * 38 of 65 episodes (58%). For those the tail is a lower
 * bound recorded as if it were the whole thing.
 *
 * The inner test could look PAST its boundary for evidence the curve kept
 * rising. Here there is nothing past it — that is the difficulty — so the
 * evidence has to be the approach: still climbing into the final point. The
 * threshold is [CENSOR_CLIMB_SLOPE_MMOL_PER_MIN], the one truncation already
 * uses, rather than a new constant chosen for this.
 *
 * INERT on purpose for now. The tail enters the AMPLITUDE (full rise = peak +
 * tail), so consuming this flag changes the amplitude corpus — and amplitude is
 * outside the food-shape suspension of «shadow-safe first». Measure first.
 */
internal fun isLateTailBounded(points: List<DishCurvePoint>): Boolean {
    val sorted = points.sortedBy { it.tauMin }
    val last = sorted.lastOrNull() ?: return false
    val previous = sorted.getOrNull(sorted.size - 2) ?: return false
    // Only about the LATE phase. No explicit early-only guard: `last` IS the
    // largest tau, so when it sits inside the early phase no point can reach
    // 180 and the lookup below already returns null. Mutation proved the guard
    // redundant — deleting it failed nothing — and redundant code in a medical
    // model is a liability, not documentation.
    val late = sorted.filter { it.tauMin >= 180.0 }.maxByOrNull { it.deltaMmol } ?: return false
    if (late.tauMin < last.tauMin) return false
    val minutes = last.tauMin - previous.tauMin
    if (minutes <= 0.0) return false
    return (last.deltaMmol - previous.deltaMmol) / minutes > CENSOR_CLIMB_SLOPE_MMOL_PER_MIN
}

internal fun isPeakObserved(points: List<DishCurvePoint>, truncated: Boolean): Boolean {
    if (!truncated) return true
    val sorted = points.sortedBy { it.tauMin }
    val a = sorted.getOrNull(sorted.size - 2) ?: return true
    val b = sorted.last()
    val slope = if (b.tauMin > a.tauMin) (b.deltaMmol - a.deltaMmol) / (b.tauMin - a.tauMin) else 0.0
    return slope <= CENSOR_CLIMB_SLOPE_MMOL_PER_MIN
}

// TTP as the time the curve first REACHES its (early) peak, not the argmax. On a
// flat top the argmax drifts to whichever late point noise made highest — a
// smoothie that plateaus by 45 read ttp 120 once its window was long. The first
// time it gets within FRAC of the peak is stable against that drift.
private const val TTP_REACH_FRAC = 0.9

internal fun effectiveTtp(curve: DishCurve, earlyPeakRise: Double, argmaxTtp: Double): Double {
    if (earlyPeakRise <= 0.0) return argmaxTtp
    val thr = TTP_REACH_FRAC * earlyPeakRise
    return curve.points.filter { it.tauMin <= argmaxTtp }.sortedBy { it.tauMin }
        .firstOrNull { it.deltaMmol >= thr }?.tauMin ?: argmaxTtp
}

const val ONSET_FRAC = 0.15
private const val ONSET_FLOOR_MMOL = 0.2

/**
 * When did this meal actually START raising glucose?
 *
 * Interpolated from the meal instant (τ=0, Δ=0 by construction) through the
 * first checkpoint that crosses the threshold — the crossing lands between two
 * checkpoints far more often than on one, and rounding it to the grid would
 * quantize every lag to 5/10/15.
 *
 * Null = never crossed before its own peak, i.e. no measurable onset.
 */
internal fun measureOnsetLag(curve: DishCurve, peakRise: Double, ttpMin: Double): Double? {
    val thr = maxOf(ONSET_FRAC * peakRise, ONSET_FLOOR_MMOL)
    val pts = listOf(0.0 to 0.0) +
        curve.points.filter { it.tauMin <= ttpMin }.sortedBy { it.tauMin }
            .map { it.tauMin to it.deltaMmol }
    val i = pts.indices.firstOrNull { it > 0 && pts[it].second >= thr } ?: return null
    val (t0, d0) = pts[i - 1]
    val (t1, d1) = pts[i]
    if (d1 <= d0) return t1
    return (t0 + (thr - d0) / (d1 - d0) * (t1 - t0)).coerceIn(0.0, ttpMin)
}

fun deconvolvedMealObservations(
    notes: List<com.diapilot.core.collector.Annotation>,
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    nowMs: Long,
    contaminationWindows: List<FoodContaminationWindow> = emptyList(),
    softContaminationWindows: List<FoodContaminationWindow> = emptyList(),
    maxPasses: Int = 3,
    extendThroughKnown: Boolean = false,
    /** Global carb sensitivity: the amplitude the engine builds the neighbour
     *  meal's contribution from (`NeighbourContributionV1`). */
    carbSensPerGram: Double? = null,
    /** Diagnostic: override the leftover-neighbour's time to peak. */
    leftoverTtpOverride: Double? = null,
    kernelForBolus:((BolusPoint)->List<KernelPoint>)?=null,
    /** Join adjacent meals while the earlier one is still EMPTYING, instead of
     *  by a flat 45-minute gap — see [MealCaloricExtentV1]. Default off: it
     *  changes what the corpus learns, so it needs its own measurement, and the
     *  user's rule was "while the previous one is still working", not a
     *  constant. */
    floatingSessionGap: Boolean = false,
    /**
     * THE FORECAST'S OWN APPEARANCE CURVE, injected rather than re-implemented.
     *
     * Given a meal's notes and an age in minutes, returns the fraction of that
     * meal's carbohydrate the FORECAST believes has arrived. Supplied by the
     * caller, which owns the person model; this file must not grow a second
     * food-shape model beside the one the forecast draws.
     *
     * Why it exists: a neighbour is subtracted only when every one of its
     * ingredients has a measured concept profile. A one-off dish has none, so
     * it was subtracted as ZERO — and a measured case showed a neighbouring dish
     * with no profile treated as finished ~113 minutes in, handing its entire
     * ongoing rise to the dish behind it (a large apparent rise attributed to a
     * modest gram count, several times the carb sensitivity, shown on the card
     * as measured fact). The prior that produced "finished"
     * peaks that dish at 65 minutes; the forecast, since the caloric queue
     * with sieving, has it half arrived at ~115 and 90% at ~225.
     *
     * Null keeps the previous behaviour exactly.
     */
    neighbourAppearance: ((List<MealObservation>) -> (MealSession, Double) -> Double)? = null,
    /** Split `Δglucose − all insulin` between the session's meals by their expected
     *  carbohydrate delivery, instead of subtracting neighbours' carbs and
     *  charging one meal with everyone's insulin — see [dishResponseCurve]. */
    divideInsulinLikeCarbs: Boolean = false,
): List<MealObservation> = curvesToCorpus(
    iteratedSessionCurves(
        notes, readings, boluses, kernel, nowMs,
        contaminationWindows, softContaminationWindows, maxPasses, extendThroughKnown,
        carbSensPerGram, leftoverTtpOverride, kernelForBolus,
        floatingSessionGap, neighbourAppearance, divideInsulinLikeCarbs,
    ),
    nowMs,
)

/** Reduce recovered session curves to the pooling corpus: one whole-meal
 *  observation each, plus the ingredient-isolated ones from decomposition. */
internal fun curvesToCorpus(curves: List<SessionCurve>, nowMs: Long): List<MealObservation> {
    val wholeMeal = curves.map { sc ->
        MealObservation(
            fingerprint = sc.fingerprint,
            ttpMin = sc.tp.ttpMin,
            tailRise = sc.tp.tailRise,
            peakRise = sc.tp.rise,
            onsetMs = sc.anchorMs,
            nEpisodes = 1,
            carbGrams = sc.totalCarbs,
            peakObserved = sc.peakObserved, earlyPeakBounded = sc.earlyPeakBounded,
            lateTailBounded = sc.lateTailBounded,
            tailObserved = sc.tailObserved,
            onsetLagMin = measureOnsetLag(sc.curve, sc.tp.rise, sc.tp.ttpMin),
            confidence = sc.confidence,
            neighbourMmol = sc.neighbourMmol,
            neighbourResolved = sc.neighbourResolved,
            neighbourQuality = null,
            curveTaus = sc.curve.points.map { it.tauMin },
            curveMmol = sc.curve.points.map { it.deltaMmol },
        )
    }
    // NO MORE COMPONENT ROWS. The user's call: a component breakdown that shows
    // garbage is not needed. And it was showing garbage: out of a corpus of
    // roughly 200 rows only two were component rows, and a third fell into the
    // funnel as `fish ×1 grams 1`. They were shown nowhere: the history card
    // filters `component == null`, and the dossier never rendered a single screen
    // for them.
    return wholeMeal
}

/**
 * The carryover bootstrap: pass 0 builds plain per-session curves; each later
 * pass learns which meals are POOLED and subtracts those known neighbours from
 * their overlappers. Converges when no new concept becomes known.
 */
internal fun iteratedSessionCurves(
    notes: List<com.diapilot.core.collector.Annotation>,
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    nowMs: Long,
    contaminationWindows: List<FoodContaminationWindow> = emptyList(),
    softContaminationWindows: List<FoodContaminationWindow> = emptyList(),
    maxPasses: Int = 3,
    extendThroughKnown: Boolean = false,
    carbSensPerGram: Double? = null,
    leftoverTtpOverride: Double? = null,
    kernelForBolus:((BolusPoint)->List<KernelPoint>)?=null,
    /** Join adjacent meals while the earlier one is still EMPTYING, instead of
     *  by a flat 45-minute gap — see [MealCaloricExtentV1]. Default off: it
     *  changes what the corpus learns, so it needs its own measurement, and the
     *  user's rule was "while the previous one is still working", not a
     *  constant. */
    floatingSessionGap: Boolean = false,
    /**
     * THE NEIGHBOUR'S OWN CONTRIBUTION IN MMOL, rebuilt from each pass's corpus.
     *
     * A factory rather than a function because the amplitude a neighbour is
     * subtracted at should be ITS OWN measured one where the corpus has it, and
     * the corpus only exists between passes. Subtracting every predecessor at
     * `grams x global CS` over-corrected exactly where it matters: on meals
     * whose neighbour had only just started, the recovered per-gram went from
     * 48% ABOVE the no-neighbour reference to 30% below it.
     *
     * Built as a PASS, deliberately, and never as a recursion: pass 0 is handed
     * an empty corpus and must fall back to a prior, so nothing reads an
     * amplitude it is in the middle of computing.
     */
    neighbourAppearance: ((List<MealObservation>) -> (MealSession, Double) -> Double)? = null,
    divideInsulinLikeCarbs: Boolean = false,
): List<SessionCurve> {
    // THE LOOP REMAINS, BUT SERVES A DIFFERENT PURPOSE. It used to re-learn
    // component profiles and subtract them from overlapping meals; the profile
    // layer is gone. Now it feeds the corpus to the NEIGHBOUR-CONTRIBUTION FACTORY
    // (`NeighbourContributionV1`), which builds the neighbour's curve with the
    // macro-driven engine: the neighbour's amplitude is refined pass to pass,
    // because the residuals it is drawn from are themselves refined.
    val readingIndex = lazyOf(ReadingIndex.of(readings))
    val bolusIndex = lazyOf(BolusIndex.of(boluses))
    fun pass(corpus: List<MealObservation>) = buildSessionCurves(
        notes, readingIndex, bolusIndex, kernel, nowMs,
        contaminationWindows, softContaminationWindows, extendThroughKnown,
        carbSensPerGram, leftoverTtpOverride, kernelForBolus,
        floatingSessionGap, neighbourAppearance?.invoke(corpus), divideInsulinLikeCarbs,
    )
    var curves = pass(emptyList())
    if (neighbourAppearance == null) return curves
    var n = 1
    while (n < maxPasses) {
        val next = pass(curvesToCorpus(curves, nowMs))
        if (next == curves) return next
        curves = next
        n++
    }
    return curves
}

/** The per-session recovered curves, before they are reduced to observations —
 *  shared by [deconvolvedMealObservations] and diagnostics so both see exactly
 *  the same censoring/onset decisions. */
internal fun buildSessionCurves(
    notes: List<com.diapilot.core.collector.Annotation>,
    readings: Lazy<ReadingIndex>,
    boluses: Lazy<BolusIndex>,
    kernel: List<KernelPoint>,
    nowMs: Long,
    contaminationWindows: List<FoodContaminationWindow> = emptyList(),
    // SOFT confounders (activity): unlike the hard ones they do NOT disqualify an
    // episode — an activity meal is still a real observation — they only cut its
    // pooling confidence. Discarding them threw away 2 of the user's 6 daily
    // breakfasts; a small down-weight keeps them as gentle noise instead.
    softContaminationWindows: List<FoodContaminationWindow> = emptyList(),
    // Per-concept profiles trusted enough to SUBTRACT from a neighbour's window
    // (the carryover pass). Empty = pass 0, identical to the old behaviour: no
    // BACKWARD step: let a known next meal NOT truncate — extend the window
    // through it (subtracting it) to recover the earlier meal's hidden late
    // phase. Off by default; under investigation (unlogged-meal exposure).
    extendThroughKnown: Boolean = false,
    // ROUGH NEIGHBOUR SUBTRACTION. [knownProfiles] only ever contains concepts the
    // model would POOL, so on this history the carryover fired 3 times in 71 and
    // every other overlapped episode was recorded as CLEAN — the later meal
    // silently credited with the earlier one's rise. These are the same profiles
    // at whatever quality exists, plus the note's own GI for grams no concept
    // claimed, so an overlap is SUBTRACTED and MARKED instead of ignored.
    carbSensPerGram: Double? = null,
    /** Diagnostic: override the leftover-neighbour's time to peak. */
    leftoverTtpOverride: Double? = null,
    kernelForBolus:((BolusPoint)->List<KernelPoint>)?=null,
    /** Join adjacent meals while the earlier one is still EMPTYING, instead of
     *  by a flat 45-minute gap — see [MealCaloricExtentV1]. Default off: it
     *  changes what the corpus learns, so it needs its own measurement, and the
     *  user's rule was "while the previous one is still working", not a
     *  constant. */
    floatingSessionGap: Boolean = false,
    /** The neighbour's contribution in MMOL at an age — see [iteratedSessionCurves]. */
    neighbourAppearance: ((MealSession, Double) -> Double)? = null,
    divideInsulinLikeCarbs: Boolean = false,
): List<SessionCurve> {
    if (kernel.isEmpty()) return emptyList()
    val foodNotes = deconvolutionFoodNotes(notes)
    // The anchor is a MEAL (session), not a note. Several notes logged close
    // together are ONE meal — meatballs + beetroot soup a minute apart, two
    // pancakes then a pancake with Nutella. Anchoring per NOTE made them truncate
    // each other's window to nothing (measured: 26% of notes dropped as
    // "window<45", down to 13% per session) and, worse, split one sizeable meal
    // into two roughly-equal halves — halving the per-gram amplitude of exactly the
    // biggest meals, and letting the second note claim the first one's rise.
    // groupMealSessions has encoded this since a prior commit (fca8077); the
    // deconvolution, written four days later, simply never used it. Rescue
    // dextrose stays its own session there — treating a hypo mid-dinner is not
    // a course of the dinner, so it must still truncate the meal.
    val sessions = groupMealSessions(
        foodNotes,
        extentMinOf = if (floatingSessionGap) MealCaloricExtentV1::sessionExtentMin else null,
    )
    val allOnsets = sessions.map { it.startMs }
    // Each session's carb-driver ingredients (conceptId → grams). A session is
    // SUBTRACTABLE when every one of them is known — then we can predict its whole
    // curve and remove it from a neighbour instead of letting it truncate.
    val sessionDrivers = sessions.associateWith { sessionCarbDrivers(it) }
    // NEIGHBOUR SUBTRACTION BY LEARNED PROFILE IS GONE — there is nothing left to
    // subtract by. What remains is the macro-driven subtraction (`neighbourAppearance`),
    // which arrives as a parameter and works.
    return sessions.mapNotNull { session ->
        // PHASE-LIMITED, not all-or-nothing: excluding every meal with a
        // neighbour within 5h loses ~92% of a normal eating day. Instead truncate
        // the analysis window to just BEFORE the next meal and recover the EARLY
        // phase (ttp + amplitude) — the part that is still clean and the part the
        // model + insulin-refit need most. The tail survives only for genuinely
        // isolated meals (a long clean window).
        val anchorMs = session.startMs
        // A confounder (activity, illness) that BEGINS after the meal only bounds
        // how far the clean early phase reaches — truncate to just before it.
        val nextContamMin = contaminationWindows
            .filter { it.startMs > anchorMs }
            .minOfOrNull { (it.startMs - anchorMs) / 60_000.0 } ?: Double.MAX_VALUE
        // Carryover: absolute mmol from every KNOWN neighbour meal at time t. One
        // formula for both directions — a prior meal's ongoing tail and a
        // subtracted next meal both appear as `contrib(t) − contrib(onset)`.
        val pooledCarry: ((Long, Long) -> Double)? = null
        val roughCarry: ((Long, Long) -> Double)? = null
        // THE FORECAST'S CURVE FOR EVERY NEIGHBOUR THAT HAS NO MEASURED PROFILE.
        // `subtractable` demands a pooled concept for every ingredient, which a
        // one-off dish never has — so it was subtracted as zero and its rise was
        // handed to whatever came next. Grams are always known, so the forecast
        // can always say how much of it has arrived; amplitude is grams x the
        // same global carb sensitivity the rest of this file uses.
        // EVERY neighbour, not only the profileless ones — and REPLACING the
        // pooled path rather than adding to it.
        //
        // The first version skipped a neighbour that already had a measured
        // concept profile, on the theory that a measured profile beats a model.
        // Measured, it does not: a pizza-type dish IS pooled, and the pooled
        // profile peaks it at 65 minutes — the old shape model, the one the
        // forecast stopped using when the caloric queue shipped. So one build
        // (where the neighbour dish had no profile) showed the following dish's
        // recovered amplitude falling sharply while the other build (where it
        // did have a profile) barely moved. Two corpora, one dish, two answers:
        // discipline #7 again.
        //
        // Composing them additively would subtract the same neighbour twice.
        val physioCarry: ((Long, Long) -> Double)? =
            if (neighbourAppearance == null) null else { _, tMs ->
                sessions.sumOf { s ->
                    if (s === session || s.startMs > tMs) 0.0
                    else neighbourAppearance(s, (tMs - s.startMs) / 60_000.0)
                }
            }
        var carryover: ((Long, Long) -> Double)? = physioCarry ?: (roughCarry ?: pooledCarry)
        // THIS MEAL'S SHARE of the meal's food signal, when the caller asked for
        // insulin to be divided like carbohydrate. Built from the SAME injected
        // curve as the subtraction, so there is one food model here and not two.
        //
        // Delivered inside the window, not total: insulin acting between `onset`
        // and `t` is opposed by what ARRIVES between `onset` and `t`. A meal
        // that finished before the anchor contributes nothing and correctly
        // takes no insulin with it.
        val shareFn: ((Long, Long) -> Double)? =
            if (!divideInsulinLikeCarbs || neighbourAppearance == null) null else { anchor, tMs ->
                fun delivered(s: MealSession): Double {
                    val a = neighbourAppearance(s, (anchor - s.startMs) / 60_000.0)
                    val b = neighbourAppearance(s, (tMs - s.startMs) / 60_000.0)
                    return (b - a).coerceAtLeast(0.0)
                }
                val mine = delivered(session)
                val total = sessions.sumOf { if (it.startMs > tMs) 0.0 else delivered(it) }
                if (total <= 1e-9) 1.0 else (mine / total).coerceIn(0.0, 1.0)
            }
        // THE CARRYOVER STAYS FOR DIAGNOSTICS when the share is active. Nulling
        // it (v24 as first shipped) silently erased the overlap honesty markers:
        // neighbourMmol read 0, the overlap was judged immaterial, and a meal
        // sharing its window with a substantial pizza printed "100% confidence" with
        // no neighbour note — the exact overconfidence the user had already
        // caught once before. The VALUE path ignores the carryover when
        // foodShare is set (dishResponseCurve uses the share), so keeping it
        // here changes no recovered number, only the confidence and the flags.
        // Build the recovered curve for a given clean-window length. Returns the
        // curve, its (robust) two-phase summary, and whether it was truncated.
        fun build(horizonMin: Double, carry: ((Long, Long) -> Double)?): Triple<DishCurve, DishTwoPhase, Boolean>? {
            val maxTau = minOf(300.0, horizonMin - 15.0)
            if (maxTau < 45.0) return null
            val cps = DECONV_CHECKPOINTS.filter { it <= maxTau }
            if (cps.size < 3) return null
            val curve = dishResponseCurve(
                onsetsMs = listOf(anchorMs),
                readings = readings, boluses = boluses, kernel = kernel, nowMs = nowMs,
                checkpoints = cps, minEpisodes = 1, allOnsetsMs = emptyList(),
                contaminationWindows = contaminationWindows,
                maxJumpMmolPerMin = 0.6, foodCarryover = carry,kernelForBolus=kernelForBolus,
                foodShare = shareFn,
            ) ?: return null
            val tpRaw = dishTwoPhase(curve, minTailEpisodes = 1) ?: return null
            if (tpRaw.rise < 0.5) return null
            val tp = tpRaw.copy(ttpMin = effectiveTtp(curve, tpRaw.rise, tpRaw.ttpMin))
            return Triple(curve, tp, horizonMin < 315.0)
        }

        // FORWARD window: truncate at the FIRST next meal (known or not).
        val nextMealFwd = sessions.filter { it.startMs > anchorMs }
            .minOfOrNull { (it.startMs - anchorMs) / 60_000.0 } ?: Double.MAX_VALUE
        val fwdHorizon = minOf(nextMealFwd, nextContamMin)
        // The re-subtraction guard is gone: there is nothing left to subtract by profile.
        val built = build(fwdHorizon, carryover)
        var (curve, tp, truncatedByNextMeal) = built ?: return@mapNotNull null

        // BACKWARD: only when the forward curve is CENSORED (still rising at the
        // cut — a long meal hidden under a snack) is there a late phase worth
        // recovering. An already-peaked meal (smoothie) gains only noise from a
        // longer window, so it is never extended. Extend through the KNOWN next
        // meals, truncating at the first UNKNOWN one, and adopt the longer curve
        // only if it now shows a peak AND has no fresh late rise — a checkpoint
        // well past the peak, far above it, is a new event (an unlogged meal or
        // an over-/under-subtracted neighbour), not this meal's tail.
        if (extendThroughKnown && !isPeakObserved(curve.points, truncatedByNextMeal)) {
            val nextUnknown = sessions
                .filter { it.startMs > anchorMs }
                .minOfOrNull { (it.startMs - anchorMs) / 60_000.0 } ?: Double.MAX_VALUE
            val extHorizon = minOf(nextUnknown, nextContamMin)
            if (extHorizon > fwdHorizon + 1.0) {
                build(extHorizon, carryover)?.let { (cE, tpE, truncE) ->
                    val earlyPeak = cE.points.filter { it.tauMin <= 120.0 }
                        .maxOfOrNull { it.deltaMmol } ?: 0.0
                    val freshRise = cE.points.any {
                        it.tauMin > tpE.ttpMin + 30.0 && it.deltaMmol > earlyPeak * 1.5
                    }
                    if (isPeakObserved(cE.points, truncE) && !freshRise) {
                        curve = cE; tp = tpE; truncatedByNextMeal = truncE
                    }
                }
            }
        }

        val maxTau = curve.points.maxOfOrNull { it.tauMin } ?: 0.0
        val episodeEnd0 = anchorMs + (maxTau * 60_000).toLong()
        val carryoverUsed = carryover != null &&
            (carryover!!(anchorMs, episodeEnd0) - carryover!!(anchorMs, anchorMs)) > CARRYOVER_MIN_MMOL
        val peakObserved = isPeakObserved(curve.points, truncatedByNextMeal)
        // Separate from the truncation test above: the early phase can be
        // bounded by its own 120-minute boundary even in a long clean window.
        val earlyPeakBounded = isEarlyPeakBounded(curve.points)
        // A late fat/protein phase only becomes visible with a long clean window;
        // a short one saw no tail, it didn't prove the tail is zero.
        val lastTau = curve.points.maxOfOrNull { it.tauMin } ?: 0.0
        val tailObserved = lastTau >= 180.0
        // ...and separately: a tail that was SEEN is not the same as a tail that
        // FINISHED. 58% of measured episodes have their tail maximum on the window's
        // last point.
        val lateTailBounded = isLateTailBounded(curve.points)
        // One meal = one fingerprint: pool the concept inputs of every note in
        // the session, so "meatballs + soup" is one mixed meal rather than two
        // unrelated halves.
        val inputs = session.notes.flatMap {
            mealConceptFingerInputs(it.analysis, it.content, it.estCarbs)
        }
        if (inputs.isEmpty()) return@mapNotNull null
        val fp = mealFingerprint(inputs)
        if (fp.carbDrivers.isEmpty()) return@mapNotNull null
        // Soft-confounded (activity touched the episode window) → kept, down-weighted.
        val softHit = softContaminationWindows.any {
            it.startMs <= episodeEnd0 && it.endMs >= anchorMs - 30L * 60_000
        }
        // A curve that leaned on a subtracted neighbour is real but noisier (it
        // rests on that neighbour's AVERAGE profile, and any one day deviates), so
        // it too is down-weighted — combining multiplicatively with activity.
        // An implausibly large rise-per-gram is a contamination tell (rescue
        // dextrose over a hypo) — down-weighted the same way.
        val totalCarbs = session.totalCarbs ?: 0.0
        val implausible = totalCarbs > 0 && tp.rise / totalCarbs > AMPLITUDE_CEILING_MMOL_PER_G
        // A food contribution cannot be materially negative. If glucose still
        // needs a negative residual after adding back the selected insulin
        // profile, the episode is evidence of a model/context mismatch (ISF or
        // timing today, activity/basal/background, sensor, or logged grams), not
        // clean evidence about this dish. Keep it for diagnostics, but give it
        // too little weight to teach dish timing or amplitude confidently.
        val insulinProfileMismatch =
            curve.points.count { it.deltaMmol < NEGATIVE_RESIDUAL_MMOL } > MAX_NEGATIVE_RESIDUAL_POINTS
        // THE NEIGHBOUR NO LONGER GETS GRADED BY PROFILE QUALITY.
        //
        // How much the neighbour poured in is now counted as the SAME contribution
        // it was subtracted by: `carryover` is `physioCarry`, the macro-driven
        // engine. That stayed.
        val neighbourMmol = if (carryover == null) 0.0
        else carryover!!(anchorMs, episodeEnd0) - carryover!!(anchorMs, anchorMs)
        // A neighbour there was NOTHING to subtract by: it is adjacent, but no
        // contribution was built for it.
        val priorNeighbourMs = sessions
            .filter { it !== session && it.startMs < anchorMs }
            .maxOfOrNull { it.startMs }
        val unresolved = carryover == null && priorNeighbourMs != null &&
            (anchorMs - priorNeighbourMs) / 60_000.0 < NEIGHBOUR_ABSORBING_MIN
        val material = unresolved ||
            (neighbourMmol > CARRYOVER_MIN_MMOL && neighbourMmol >= NEIGHBOUR_MATERIAL_SHARE * tp.rise)
        val neighbourConfidence = when {
            unresolved -> OVERLAP_UNRESOLVED_CONFIDENCE
            material -> CARRYOVER_CONFIDENCE
            else -> 1.0
        }
        // ONE PENALTY FORMULA FOR A NEIGHBOUR.
        //
        // There used to be a branch on `roughNeighbours` here, and it made sense
        // while the switch chose a MECHANISM: rough subtraction by learned profile
        // versus pooled. Both are gone; one subtraction remains — the macro-driven
        // engine — and choosing between two penalties for the same mechanism means
        // keeping a toggle whose name lies.
        val confidence = (if (softHit) SOFT_CONFOUNDER_CONFIDENCE else 1.0) *
            neighbourConfidence *
            (if (implausible) IMPLAUSIBLE_AMPLITUDE_CONFIDENCE else 1.0) *
            (if (insulinProfileMismatch) 0.15 else 1.0)
        SessionCurve(
            anchorMs = anchorMs,
            fingerprint = fp,
            curve = curve,
            tp = tp,
            peakObserved = peakObserved,
            earlyPeakBounded = earlyPeakBounded,
            lateTailBounded = lateTailBounded,
            tailObserved = tailObserved,
            totalCarbs = session.totalCarbs ?: 0.0,
            confidence = confidence,
            softHit = softHit,
            neighbourConfidence = neighbourConfidence,
            neighbourMmol = neighbourMmol,
            neighbourMaterial = material,
            neighbourResolved = !unresolved,
        )
    }
}

/** The food notes the deconvolution anchors on — one definition, so a
 *  diagnostic can never audit a different note universe than the model used. */
internal fun deconvolutionFoodNotes(
    notes: List<com.diapilot.core.collector.Annotation>,
): List<com.diapilot.core.collector.Annotation> = notes.filter {
    it.kind == "food" && (it.estCarbs ?: 0.0) > 0 && !isContextNote(it.content)
}

/** A meal's carb-driver ingredients (conceptId → grams). */
internal fun sessionCarbDrivers(s: MealSession): Map<String, Double> {
    val inputs = s.notes.flatMap { mealConceptFingerInputs(it.analysis, it.content, it.estCarbs) }
    if (inputs.isEmpty()) return emptyMap()
    return mealFingerprint(inputs).components
        .filter { it.conceptId != null && it.carbGrams > 0 }
        .associate { it.conceptId!! to it.carbGrams }
}

// THE OVERLAP AUDIT IS REMOVED, along with the layer that read it. It used to
// count HOW MUCH the neighbour poured in, by the same learned profile; profiles
// are gone, and the quantity is not computable. What remains in its place is
// the observation "a neighbour was there".

/** One recovered episode, flattened for diagnostics. */
data class DeconvEpisodeDebug(
    val anchorMs: Long,
    val drivers: List<String>,
    val points: List<Pair<Double, Double>>,   // (tauMin, deltaMmol)
    val ttpMin: Double,
    val onsetLagMin: Double?,
    val peakObserved: Boolean,
)

/** Every recovered episode with its raw checkpoint curve — for eyeballing why a
 *  meal was (not) marked censored. Not used by the model; diagnostics only. */
fun deconvolvedEpisodesDebug(
    notes: List<com.diapilot.core.collector.Annotation>,
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    nowMs: Long,
    contaminationWindows: List<FoodContaminationWindow> = emptyList(),
    softContaminationWindows: List<FoodContaminationWindow> = emptyList(),
    extendThroughKnown: Boolean = false,
    carbSensPerGram: Double? = null,
    leftoverTtpOverride: Double? = null,
): List<DeconvEpisodeDebug> =
    iteratedSessionCurves(
        notes, readings, boluses, kernel, nowMs,
        contaminationWindows, softContaminationWindows, extendThroughKnown = extendThroughKnown, carbSensPerGram = carbSensPerGram,
        leftoverTtpOverride = leftoverTtpOverride,
    ).map { sc ->
        DeconvEpisodeDebug(
            anchorMs = sc.anchorMs,
            drivers = sc.fingerprint.carbDrivers,
            points = sc.curve.points.sortedBy { it.tauMin }.map { it.tauMin to it.deltaMmol },
            ttpMin = sc.tp.ttpMin,
            onsetLagMin = measureOnsetLag(sc.curve, sc.tp.rise, sc.tp.ttpMin),
            peakObserved = sc.peakObserved,
        )
    }

/** A session's recovered food curve, before it is reduced to an observation.
 *  Kept whole so [decomposeComponents] can subtract known ingredients from the
 *  raw checkpoint curve, not just from the ttp/rise summary. */
internal data class SessionCurve(
    val anchorMs: Long,
    val fingerprint: MealFingerprint,
    val curve: DishCurve,
    val tp: DishTwoPhase,
    val peakObserved: Boolean,
    val tailObserved: Boolean,
    /** The early peak is pinned to the 120-minute phase boundary — its ttp is a
     *  lower bound. See [isEarlyPeakBounded]. */
    val earlyPeakBounded: Boolean = false,
    /** The late phase was still climbing when the window ended — its tail is a
     *  LOWER BOUND. Sibling of [earlyPeakBounded] at the other boundary. */
    val lateTailBounded: Boolean = false,
    val totalCarbs: Double,
    val confidence: Double = 1.0,
    // The confidence FACTORS, kept apart for diagnostics. `confidence` is their
    // product, and reading it alone cannot tell an episode contaminated by a
    // NEIGHBOUR from one contaminated by ACTIVITY — two different defects with
    // two different owners, and 0.18 is both at once.
    val softHit: Boolean = false,
    val neighbourConfidence: Double = 1.0,
    /** Was the neighbour's contribution BUILT (by the macro-driven engine) — or
     *  did it stay unresolved. Replaces the grading by learned-profile quality. */
    val neighbourResolved: Boolean = true,
    val neighbourMmol: Double = 0.0,
    val neighbourMaterial: Boolean = false,
)

/** Kernel value helper shared with the twin (G(τ<=0)=0, flat past the end). */
private fun g(kernel: List<KernelPoint>, tauMin: Double): Double {
    if (tauMin <= 0 || kernel.isEmpty()) return 0.0
    val last = kernel.last()
    if (tauMin >= last.tauMin) return last.median
    return kernel.lastOrNull { it.tauMin <= tauMin }?.median ?: 0.0
}

