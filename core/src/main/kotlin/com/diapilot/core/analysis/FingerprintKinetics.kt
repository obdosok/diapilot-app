/**
 * Fingerprint kinetics predictor — Phase 1 of the concept model.
 *
 * A dish eaten twice can't earn its own measured curve; the generic GI prior is
 * all the old model had for it. Here a rarely-eaten meal borrows its SHAPE
 * (time-to-peak, late fat/protein tail) from every past meal that shares its
 * FINGERPRINT — the same carb-driver concept-ids and a similar macro tail —
 * pooled and recency-weighted. Pooling by concept-id, not by dish name, is the
 * whole point: "smoothie with orange juice" and "fresh juice" feed one estimate.
 *
 * Magnitude (how HIGH the rise goes) is deliberately NOT predicted here — that
 * stays carbs × per-gram, the amplitude the roadmap keeps size-driven. This
 * module answers only WHEN and WHETHER-a-tail: the shape.
 */
package com.diapilot.core.analysis

/** One past meal reduced to (fingerprint, measured shape) for pooling. */
data class MealObservation(
    val fingerprint: MealFingerprint,
    val ttpMin: Double,
    val tailRise: Double,     // EXTRA late rise (mmol) beyond the early peak, 0 = none
    val peakRise: Double,     // the early-peak rise (mmol), for the tail fraction
    val onsetMs: Long,        // recency weighting
    val nEpisodes: Int = 1,   // evidence behind this observation
    /** Carbs (g) this meal carried — with [peakRise] gives the per-gram
     *  amplitude (mmol/g). 0/null when unknown (excluded from amplitude pooling). */
    val carbGrams: Double = 0.0,
    /** Was a REAL peak seen (plateau/decline after it), or was the window cut off
     *  by the next meal while glucose was still rising? A CENSORED episode
     *  (false) gives only lower bounds on ttp and amplitude — it must not set the
     *  ttp point estimate, though it still counts as late-phase evidence. */
    val peakObserved: Boolean = true,
    /** Was the clean window long enough to SEE a late phase? A meal truncated at
     *  75–120 min physically can't show a 3–5h tail — its tailRise=0 means
     *  "unknown", not "no tail". Only tailObserved donors vote on the tail. */
    val tailObserved: Boolean = true,
    /** Measured minutes from EATING to the first real rise. Null = not measured
     *  (older corpus entries, or a curve with no readable onset). Appended last
     *  and defaulted on purpose: positional callers must keep compiling. */
    val onsetLagMin: Double? = null,
    /** Concept id this observation was ISOLATED to by subtracting the other
     *  components' known curves (see ComponentDeconvolution). null = a whole-meal
     *  curve filed under all its concepts at once — the fallback, honest only
     *  for single-ingredient meals. When set, ttp/onset/rise/carbGrams describe
     *  THIS ingredient alone, not the meal. */
    val component: String? = null,
    /** Pooling trust in [0,1]. 1 = clean. <1 = a soft confounder overlapped the
     *  episode (eaten during activity): kept but down-weighted, so it adds a
     *  little noise instead of being discarded. Multiplies the donor weight. */
    val confidence: Double = 1.0,
    /** Estimated contribution from an earlier still-absorbing meal inside this
     * episode's window. Non-zero means overlap was explicitly considered. */
    val neighbourMmol: Double = 0.0,
    /** False means the overlap was material but the subtraction failed its
     * plausibility guard; the episode is retained only as confounded evidence. */
    val neighbourResolved: Boolean = true,
    val neighbourQuality: String? = null,
    /** The early peak sat on the phase boundary while the curve kept rising —
     *  see [isEarlyPeakBounded]. Its ttp is a LOWER BOUND. Separate from
     *  [peakObserved] on purpose: this one leaves the timing estimate but keeps
     *  its amplitude, because peak + observed tail is still the true total. */
    val earlyPeakBounded: Boolean = false,
    /** The LATE phase was still climbing when the window ended, so [tailRise] is
     *  a LOWER BOUND rather than the finished tail.
     *
     *  Sibling of [earlyPeakBounded], added after the user asked
     *  what happens to a dish still rising past three hours. Separate from
     *  [tailObserved] for the same reason the early pair is separate: that one
     *  says the window was long enough to SEE a tail, this one says the tail had
     *  not FINISHED. Appended last and defaulted, so positional callers keep
     *  compiling. */
    val lateTailBounded: Boolean = false,
    /**
     * The recovered checkpoint curve itself (tau min -> mmol), so the card can
     * DRAW model-vs-fact instead of asking the reader to diff two number rows.
     * Empty for component rows and for observations restored from older
     * snapshots. Appended last: positional constructors exist.
     */
    val curveTaus: List<Double> = emptyList(),
    val curveMmol: List<Double> = emptyList(),
)


// Re-shape foods with the fingerprint prediction — the SHADOW forecast's food
// set. Each food whose onset carries a fingerprint AND earns a pooled (not
// prior) estimate gets its time-to-peak and late tail replaced; magnitude
// (rise) is untouched. Foods without a fingerprint or with only a speed-prior
// keep their baseline shape, so the shadow differs from base ONLY where the
// concept model actually has borrowed evidence.
/** A logged food note reduced to what the experimental model needs to reshape
 *  the food it belongs to: when, its fingerprint, and its carbs. */
data class NoteFingerprint(
    val tsMs: Long,
    val fingerprint: MealFingerprint,
    val carbGrams: Double,
    /**
     * Are these grams EXTERNALLY anchored (`carbs_source = 'anchor'`), i.e. TRUE grams
     * rather than the user's recorded estimate?
     *
     * WITHOUT THIS THE PRIOR CONVERSION IS UNREACHABLE FROM PRODUCTION. Review
     * caught it: `gramsAnchored` was passed only from the harness, so the flag was not
     * "off until the data carries the provenance" as its own comment claimed — it was off
     * permanently, and applying the gram migration would have produced exactly the large
     * under-call `priorInDishGramSpace` exists to remove. (The shadow tag is NOT bumped for
     * it: with zero anchored rows on the phone the whole mechanism is still a no-op, and a
     * bump would reset the paired A/B for nothing — bump it with the migration.) Appended last with a default so positional
     * callers keep compiling.
     */
    val gramsAnchored: Boolean = false,
)

/** Shrinkage strength for the per-gram amplitude: pooled evidence must reach
 *  ~[FP_REG_K0] episodes to outweigh the global carb-sens prior.
 *
 *  Made non-private (visibility only, no behaviour change) so
 *  [solveComposition]'s ridge penalty can DERIVE its shrinkage from this same
 *  convention instead of re-choosing a number that happens to agree today. Two
 *  copies of a shrinkage constant is exactly how the harness once drifted from
 *  production. */
const val FP_REG_K0 = 4.0






/** Jaccard overlap of two concept-id sets (carb drivers). 0 when either empty. */
private fun jaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val inter = a.intersect(b).size.toDouble()
    val union = a.union(b).size.toDouble()
    return if (union == 0.0) 0.0 else inter / union
}

/** Macro similarity: fat + protein drive the late tail (full weight); fiber
 *  shifts the early phase (half weight). 1 = identical, 0 = opposite. */
private fun macroSim(a: MealFingerprint, b: MealFingerprint): Double {
    val df = kotlin.math.abs(a.fatLevel.ordinal - b.fatLevel.ordinal)
    val dp = kotlin.math.abs(a.proteinLevel.ordinal - b.proteinLevel.ordinal)
    val dfi = kotlin.math.abs(a.fiberLevel.ordinal - b.fiberLevel.ordinal)
    return 1.0 - (df + dp + 0.5 * dfi) / 5.0   // max distance 2+2+0.5*2 = 5
}

// Predict a target meal's shape by pooling fingerprint-similar history. A donor
// must share at least one carb-driver concept (jaccard>0) — the carbs decide
// the curve; the macro tail refines it. Falls back to the pure speed prior when
// no donor qualifies or the pooled evidence is too thin ([minSupport]).
/** Robustness knobs — a medical shape must not swing on one stray episode. */
const val FP_MIN_ONSET_DONORS = 2       // episodes required to call an onset a profile
const val FP_MIN_DONORS = 3            // distinct pooled episodes required
const val FP_MIN_EFFECTIVE = 2.0       // Kish effective count required

/**
 * The onset TIGHTNESS gate: a concept's onset may DRIVE the forecast only if its
 * own pool agrees. Threshold read on the FORECAST pool — the jaccard-similar
 * donors predictKinetics actually pools, which is what the forecast inherits, not
 * the isolated-appearance pool an intuition table would show (onset IQR÷median,
 * right column):
 *
 *   opens:  chips 0.10 · hummus 0.33 · bread 0.34
 *   closes: smoothie 0.48 · buckwheat 0.53 · pancake 0.60 · beer 0.73 ·
 *           ice_cream 0.76 · dextrose 0.86 · cereal_puff 1.06
 *
 * A clean gap between 0.34 and 0.48, and 0.35 sits in it. It also means something
 * independent of the gap: IQR ≤ 0.35·median means the middle half of a concept's
 * episodes span about a third of its onset — for a ~30 min onset ~10 min, two CGM
 * ticks — measured to within the sensor's own resolution. The wide concepts
 * are exactly the ones consumed over a variable stretch (beer, a rescue), where
 * the spread is a MISSING INPUT (duration) that more episodes cannot average out.
 * (potato reads 0.04 on isolated appearances but is excluded by the donor floor:
 * three episodes cannot carry an IQR, and its appearances are composite meals.)
 *
 * READ THE OPEN SET HONESTLY: it is ~2 independent food groups, not 3. bread and
 * hummus are the same hummus+bread breakfasts (the audit double-counts every
 * appearance), so the gate opens for the breakfast and for chips. And bread's
 * 0.34 is one hundredth under the line — expect it to flip out on a small corpus
 * shift; nothing downstream should depend on bread specifically being in.
 */
const val FP_ONSET_MAX_SPREAD = 0.35
// NB distinct from FP_MIN_ONSET_DONORS (2) above: that is the floor to REPORT a
// measured onset (for display/observability), this is the higher floor to let it
// DRIVE the forecast — an IQR on two or three points is not a profile.
const val FP_ONSET_MIN_DONORS = 4
const val FP_MAX_TTP_SPREAD_MIN = 45.0 // donor ttp IQR above this = contradictory
private const val FP_TTP_LO = 20.0
private const val FP_TTP_HI = 130.0



