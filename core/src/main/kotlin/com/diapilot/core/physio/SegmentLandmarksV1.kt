package com.diapilot.core.physio

import com.diapilot.core.collector.GlucosePoint

/**
 * Read the insulin profile from SEGMENTS, not from whole clean episodes.
 *
 * The user's proposal: "we can be smarter than searching for a whole clean
 * injection, and assemble the profile from segments instead. Here the onset is
 * visible — take it. Here the peak is visible — take it. Seeing the onset does
 * not even need an isolated episode: it is readable even with food on board."
 *
 * The measurement bears this out. Requiring ninety clean minutes to
 * establish a quantity that lives in the first thirty throws away most of the
 * evidence: on this device only 14 of 21 trusted doses own such a window, and
 * of those 14 the TAIL — the one landmark that genuinely needs the long
 * window — was censored on 2 anyway, while the onset was readable on all of
 * them. One admission rule for four quantities with different information
 * horizons is one rule too few.
 *
 * So each landmark is admitted on ITS OWN horizon, and the profile is assembled
 * from per-landmark medians. Two consequences worth stating plainly:
 *
 *  - **n now differs per landmark, and that is the honest result.** The early
 *    profile becomes well-determined while the tail stays openly thin — which
 *    is exactly what CGM can and cannot see (M-01: the residual 5–15% of action
 *    stretched over hours is below the instrument's resolution).
 *  - **The assembled profile is no single dose's profile.** Ordering is
 *    therefore checked after assembly and reported, never silently repaired.
 *
 * On reading the onset THROUGH food, which is the sharpest part of this idea:
 * the baseline here is the pre-dose inertia, so the break is measured against
 * the rise that was already happening. That is what makes it legible with food
 * on board. But it also means the onset found this way is «where insulin
 * overcame the ongoing rise», which is LATER than where insulin began to act —
 * the same two quantities M-02 separates. Doses with a fast pre-dose rise
 * therefore carry a reduced weight rather than being excluded, and the two
 * populations stay distinguishable in [LandmarkSampleV1.confoundedByFood].
 */
data class LandmarkSampleV1(
    val bolusTsMs: Long,
    val minute: Double,
    /** How loudly this sample votes in the median. */
    val weight: Double,
    /**
     * Is food acting anywhere in this dose's window?
     *
     * CORRECTED. This used to be "was the line already climbing when
     * the dose landed", read off the pre-dose slope alone — and that misses the
     * commonest case entirely. A bolus given BEFORE the meal sits on a flat
     * line, so the backward-looking test called it quiet, while the food arrived
     * immediately after and pushed every landmark later.
     *
     * Measured directly: split by pre-dose slope, the onset moved
     * +0 min; split by whether food was logged within an hour, the SAME onset
     * moved +10, the visible fall +12 and the peak +8. The confound was there
     * all along; the flag was looking the wrong way down the time axis.
     */
    val confoundedByFood: Boolean,
)

data class SegmentLandmarksV1(
    /**
     * ACTION begins: the trajectory departs from its pre-dose inertia.
     *
     * With a climbing background this happens while glucose is still rising —
     * the rise merely stops being a rise. It is the quantity the model's action
     * curve starts from, because it is a property of the insulin.
     */
    val onset: LandmarkSampleV1? = null,
    /**
     * The VISIBLE fall begins: the line actually turns down.
     *
     * Decided with the user — carry both, do not choose. Measured
     * directly the two differ by about ten minutes (14 vs ~25), and the gap
     * is not error: it is the interval in which insulin is cancelling a rise
     * that has not yet reversed. It is what the user reads off their own screen, so
     * the app must be able to state it in those terms — but it is a property of
     * insulin AND background together, so it must never be what the action
     * curve is built on. See M-02.
     */
    val visibleFall: LandmarkSampleV1? = null,
    val peakRate: LandmarkSampleV1? = null,
    val slowdown: LandmarkSampleV1? = null,
    val tailEnd: LandmarkSampleV1? = null,
    /**
     * The dose whose insulin OUTLASTED its window — a right-censored tail.
     *
     * M-22, measured directly: only 54 of 249 doses yield a
     * tail at all, and 43 more reach the slowdown and are then dropped for
     * never returning to the pre-dose rate inside the window. Dropping them is
     * not neutral — those are precisely the LONG tails, so the surviving median
     * is taken over the doses that happened to finish in time and reads short
     * by construction. Widening the window moved the pooled median 113 -> 141,
     * and the direct horizon reading (M-53) puts the true end at 240-300.
     *
     * The old comment here was right that reporting the window end AS the tail
     * fabricates a landmark, so [tailEnd] still stays null. What is added is
     * the LOWER BOUND: this dose's tail is at least this long. The aggregate
     * then estimates the median with censoring rather than ignoring it.
     */
    val tailCensoredAtMin: Double? = null,
    /**
     * The end of action read WITHOUT a background reference — «the rate stopped
     * recovering» rather than «the rate came back to the pre-dose slope».
     *
     * The second defect of the estimator, M-54. `tailEnd` declares the end where
     * the observed rate returns to `breakRate = baseSlope − floor`, i.e. to the
     * slope the line had BEFORE the dose. That quantity belongs to insulin AND
     * background together: if the background climbs while insulin still works —
     * dawn, a late meal, the liver — the observed rate meets the old slope early
     * and the dose is declared finished while it is still removing glucose. The
     * user's own observation named it first: the end is the
     * PLATEAU.
     *
     * A plateau is background-free. A rising background lifts the whole rate
     * curve but does not make it FLAT, so «the rate stopped changing» survives
     * exactly the confound that «the rate crossed a threshold» does not.
     *
     * Computed alongside and reported, never substituted: the shipped landmark
     * keeps its old meaning until this one is measured against it.
     */
    val tailPlateauMin: Double? = null,
    /**
     * Why this dose contributed nothing — never a silent empty result.
     *
     * The user's question: "what if this is an ideal diabetic who perfectly
     * compensates for food? The line barely moves — then we would never compute
     * any timing at all." That is right, and the reader does
     * refuse such a dose (a line that never breaks has no landmarks to read).
     * The danger is that it refused in SILENCE, so a corpus could be dominated
     * by the doses where compensation FAILED without anyone being able to see
     * it. A refusal that names itself is a refusal that can be counted.
     */
    val refusal: LandmarkRefusal? = null,
) {
    val any: Boolean get() = onset != null || visibleFall != null ||
        peakRate != null || slowdown != null || tailEnd != null

    companion object {
        val WINDOW_TOO_SHORT: LandmarkRefusal = LandmarkRefusal.WindowTooShort
        val LINE_UNREADABLE: LandmarkRefusal = LandmarkRefusal.LineUnreadable
        /** THE compensated-meal case: nothing ever departed from the inertia. */
        val NO_BREAK: LandmarkRefusal = LandmarkRefusal.NoBreak
    }
}

/**
 * Which population the whole profile is read from.
 *
 * One arm for every landmark, always. Mixing them compares quantities measured
 * on different bodies of evidence, and the app cannot tell whether a change
 * came from the body or from a threshold being crossed.
 */
enum class ProfileArmV1 { FOOD_FREE, POOLED }

/** One landmark's aggregate, with the count that produced it. */
data class LandmarkMedianV1(val minute: Double, val samples: Int, val foodShare: Double)

/**
 * A landmark that turned out to depend on whether the line was already climbing.
 *
 * Measured directly over 210 doses: the break is
 * identical either way (21 vs 21 min) and so is the slowdown (68 vs 68), but the
 * PEAK moves +9 min and the visible fall +4 when food is on board. Insulin has
 * to cancel the rise before it can produce its steepest fall, so a pooled median
 * for those two is not a property of the insulin — it is a property of how many
 * meal boluses happen to be in the sample.
 *
 * [flat] is therefore what the action curve is built on, and [rising] is what
 * the user will actually see when correcting on top of a meal. Same
 * principle applied to the onset, arriving in two more places.
 */
data class ConditionedLandmarkV1(
    val flat: LandmarkMedianV1?,
    val rising: LandmarkMedianV1?,
    val pooled: LandmarkMedianV1?,
) {
    /**
     * This landmark on a CHOSEN arm. The arm is decided once for the whole
     * profile — see [SegmentLandmarkReaderV1.assemble].
     *
     * It used to be decided per landmark, and that produced two failures that
     * only a replay over growing history exposed: at one point in the corpus's
     * growth the onset fell back to the pooled arm (7 samples, one short) while the
     * visible fall had already switched to the food-free one, so the profile
     * compared 21 min against 14 and refused itself for being out of order. And
     * later the onset crossed the threshold and JUMPED from 21 to 11 —
     * which reads as the model changing its mind about the body rather than as
     * one arm replacing another.
     */
    fun on(arm: ProfileArmV1): LandmarkMedianV1? =
        if (arm == ProfileArmV1.FOOD_FREE) flat ?: pooled else pooled
    /** Enough food-free samples for this landmark to stand on its own arm. */
    val flatArmReady: Boolean get() = (flat?.samples ?: 0) >= MIN_CONDITIONED_SAMPLES

    val shiftMin: Double? get() =
        if (flat != null && rising != null) rising.minute - flat.minute else null

    companion object {
        /** Below this the conditioned arm is thinner than the pooled one it
         * would replace, and conditioning costs more than the confound. */
        const val MIN_CONDITIONED_SAMPLES = 8
    }
}

data class AssembledProfileV1(
    /**
     * CONDITIONED, since the corrected flag showed it is not immune after all:
     * split by pre-dose slope the onset moved +0, split by food actually acting
     * it moves +10. The first split simply could not see a pre-meal bolus.
     */
    val onset: ConditionedLandmarkV1,
    /** Background-dependent BY DEFINITION: it is the interaction. */
    val visibleFall: ConditionedLandmarkV1,
    /** Background-dependent by measurement (+9 min), not by definition. */
    val peakRate: ConditionedLandmarkV1,
    val slowdown: LandmarkMedianV1?,
    val tailEnd: LandmarkMedianV1?,
    /** Set when the per-landmark medians do not form an increasing sequence. */
    val orderingConflict: OrderingConflict? = null,
    /** The single arm every landmark above was read from. */
    val arm: ProfileArmV1 = ProfileArmV1.POOLED,
    /** Measured for the record only — see the note in `assemble`. */
    val slowdownConditioned: ConditionedLandmarkV1? = null,
    val tailConditioned: ConditionedLandmarkV1? = null,
) {
    /**
     * The curve contract — and it starts from ACTION, not from the visible fall.
     *
     * Deliberately excludes [visibleFall]. Building the action CDF on «when the
     * line turned down» would bake the user's typical background into the
     * insulin: on a quiet night the same insulin turns the line down sooner, and
     * the curve would then claim the insulin itself was faster. The visible fall
     * is reported beside the curve, never inside it.
     */
    val landmarks: InsulinShapeLandmarksV1?
        get() {
            if (orderingConflict != null) return null
            val o = onset.on(arm)?.minute ?: return null
            val p = peakRate.on(arm)?.minute ?: return null
            val t = tailEnd?.minute ?: return null
            return InsulinShapeLandmarksV1(o, p, slowdown?.minute?.takeIf { it > p }, t)
                .takeIf { it.ordered }
        }

    /**
     * How long insulin spends cancelling a rise before the line turns down —
     * PAIRED: the median of per-dose differences.
     *
     * A difference of two medians is not this quantity. The two landmarks are
     * pooled over different (and differently sized) sets of doses, so their
     * medians can differ by five minutes while every dose that reports both
     * shows zero. Measured directly, that is exactly
     * what happens: medians 14 and 18, paired median 0 — the gap is not a
     * property of the insulin, it is a property of the doses given while food
     * was still climbing.
     */
    var cancellingRiseMin: Double? = null
        internal set
}

object SegmentLandmarkReaderV1 {
    // Clean minutes each landmark needs AFTER the dose.
    //
    // Not round numbers for tidiness: each is «the horizon past which this
    // quantity can no longer move», plus a margin so the landmark is interior to
    // its window rather than sitting on its edge. A landmark found at the very
    // end of its window is a truncation, not an observation.
    /** How still the rate must be to count as a plateau, as a share of this
     *  dose's own steepest fall. Not an absolute mmol/h, because a four-unit
     *  dose and a twelve-unit one do not flatten at the same speed. */
    const val PLATEAU_RATE_SHARE = 0.08

    const val ONSET_HORIZON_MIN = 45.0
    const val PEAK_HORIZON_MIN = 100.0
    const val SLOWDOWN_HORIZON_MIN = 150.0
    const val TAIL_HORIZON_MIN = 200.0

    /** How far inside its window a landmark must sit to count as observed. */
    const val EDGE_MARGIN_MIN = 10.0

    /** Fewest post-dose readings for the shortest segment to be readable. */
    const val MIN_AFTER_ONSET = 6

    /** A pre-dose climb steeper than this makes the onset a «when did insulin
     * overcome the rise» measurement rather than «when did it start». */
    const val RISING_BACKGROUND_MMOL_PER_H = 0.8

    /** Weight a sample read against a climbing background still carries. */
    const val RISING_BACKGROUND_WEIGHT = 0.5

    /** How long a meal keeps acting after its onset. */
    const val FOOD_ABSORPTION_MIN = 180.0

    /**
     * How long a meal is NOT yet acting after it starts.
     *
     * The user's own observation: "food also does not start working right away,
     * so for some time, at least 15 minutes, its effect will not be visible".
     *
     * This replaces a constant that had the sign of the reasoning backwards. It
     * used to widen the confounded interval 20 minutes BEFORE a meal's onset,
     * justified by sensor lag — but glucose cannot rise from food that has not
     * been absorbed yet, and the effect of getting it wrong falls on exactly the
     * case that matters most: a pre-meal bolus whose own onset lands in the
     * quiet gap between the injection and the food.
     *
     * Deliberately a floor rather than a per-dish number: the dish-specific
     * appearance curve is stage 2, and stage 2 must not feed back into the
     * anchor that identifies it.
     */
    const val FOOD_ACTION_DELAY_MIN = 15.0

    /**
     * Shortest credible run-up from the break to the steepest fall.
     *
     * Measured directly: reading segments without this
     * admitted one dose with onset, peak, slowdown and tail packed into eight
     * minutes — four landmarks inside eight minutes. That is a ripple the reader walked
     * across, not an insulin action, and it entered every one of the four
     * medians at full weight.
     *
     * Per-landmark admission is what makes this checkable at all: under
     * whole-episode admission a degenerate trace could only be dropped whole,
     * which also discarded whatever it HAD measured properly.
     */
    const val MIN_RISE_SPAN_MIN = 10.0

    /** And the same for the phases after the peak. */
    const val MIN_PHASE_SPAN_MIN = 5.0

    /**
     * @param cleanUntilMs when the next intervention lands — the next injection
     *   or NEW meal. Food already absorbing before the dose does not shorten it:
     *   it is carried by the pre-dose inertia, which is the whole point.
     */
    fun read(
        readings: List<GlucosePoint>,
        bolusTsMs: Long,
        cleanUntilMs: Long,
        /**
         * Meal onsets, in minutes relative to this dose (negative = before it).
         *
         * PER-LANDMARK, not per-window: food arriving at minute 200 cannot
         * confound an onset at minute 20, and treating the whole window as
         * contaminated collapsed the clean arm to n=7 on this corpus.
         * The same logic as the per-landmark horizons — a quantity is confounded
         * by what overlaps IT.
         *
         * The caller knows this and the trace does not: a pre-meal bolus lands
         * on a line that is still flat, so no backward-looking test can see it.
         * See [LandmarkSampleV1.confoundedByFood].
         */
        foodMinutes: List<Double> = emptyList(),
        noiseMultiple: Double = SensorCorrectionReaderV1.DEPARTURE_NOISE_MULTIPLE,
        /**
         * The absolute departure floor, in mmol/h, exposed ONLY so an instrument
         * can ask the scale-free version of the question.
         *
         * `breakRate = baseSlope - max(floor, noise x multiple)` is an ABSOLUTE
         * rate, so a larger dose produces a steeper fall and crosses it sooner
         * by construction. Any «bigger dose starts working earlier» measured
         * against the shipped floor is therefore partly the instrument. Scaling
         * the floor with the dose makes detection equally hard at every size,
         * and an effect that survives that is the body's.
         *
         * The default is unchanged: nothing in production passes this.
         */
        departureFloor: Double = SensorCorrectionReaderV1.DEPARTURE_MMOL_PER_H,
    ): SegmentLandmarksV1 {
        val cleanMin = (cleanUntilMs - bolusTsMs) / 60_000.0
        if (cleanMin < ONSET_HORIZON_MIN)
            return SegmentLandmarksV1(refusal = SegmentLandmarksV1.WINDOW_TOO_SHORT)
        val trace = SensorCorrectionReaderV1.trace(
            readings, bolusTsMs, cleanUntilMs, noiseMultiple, MIN_AFTER_ONSET, departureFloor,
        ) ?: return SegmentLandmarksV1(refusal = SegmentLandmarksV1.LINE_UNREADABLE)

        val rising = trace.baseSlopeMmolPerH >= RISING_BACKGROUND_MMOL_PER_H
        /** Is a meal acting at [minute]? Not for its first
         * [FOOD_ACTION_DELAY_MIN] minutes, and for about three hours after. */
        fun foodAt(minute: Double) = rising || foodMinutes.any { f ->
            minute >= f + FOOD_ACTION_DELAY_MIN && minute <= f + FOOD_ABSORPTION_MIN
        }
        fun sample(minute: Double, weight: Double? = null): LandmarkSampleV1 {
            val confounded = foodAt(minute)
            return LandmarkSampleV1(
                bolusTsMs, minute,
                weight ?: if (confounded) RISING_BACKGROUND_WEIGHT else 1.0,
                confounded,
            )
        }

        /**
         * Observed only when the window reaches past this landmark's horizon,
         * the landmark sits clear of the window's edge, AND it sits clear of
         * the END OF THE RECORDING.
         *
         * The last of the three is not belt-and-braces. The smoother is centred,
         * so near the final reading it has points on one side only and flattens
         * the line — which the rate test then reads as «returned to baseline».
         * A test built to prove the tail is not invented caught this producing a
         * tail at 142 min from a trace that was still falling when the sensor
         * went quiet. The window can be clean for four hours and the sensor
         * still stop after two; those are different facts, and only this
         * condition distinguishes them.
         *
         * The margin is TWICE the half-width, because the smoother is applied
         * twice — a median over ±7.5 and then a mean over ±7.5 of those — so its
         * composite support is ±15 min. Using the single half-width left the
         * artefact alive by exactly one bin, which is how it was found.
         */
        val recordedToMin = trace.after.last().first
        val trustedToMin = recordedToMin - 2 * SensorCorrectionReaderV1.SMOOTH_HALF_WIDTH_MIN
        fun observed(minute: Double, horizon: Double): Boolean =
            cleanMin >= horizon && minute <= cleanMin - EDGE_MARGIN_MIN && minute <= trustedToMin

        // The steepest fall anchors everything, exactly as in the whole-episode
        // reader: it is the one landmark with no threshold in its definition.
        val steepest = trace.rate.minByOrNull { it.second }
            ?: return SegmentLandmarksV1(refusal = SegmentLandmarksV1.LINE_UNREADABLE)
        // A perfectly compensated meal lands here: the line held its inertia, so
        // there is no departure to time. Refusing is correct — reading a
        // landmark off a flat line would be reading sensor noise — but it must
        // be COUNTED, because these doses are not missing at random.
        if (steepest.second > trace.breakRate)
            return SegmentLandmarksV1(refusal = SegmentLandmarksV1.NO_BREAK)
        val peak = steepest.first

        val onsetMin = trace.rate.lastOrNull { it.first < peak && it.second > trace.breakRate }?.first
        // Where the LINE turns down, in absolute terms — no baseline, no
        // threshold, the reading the user takes off their own screen. Searched
        // forward from the break so it can never be reported before it, and
        // required to hold for two consecutive bins so a single wobble does not
        // claim the fall has started.
        val visibleFallMin = onsetMin?.let { from ->
            trace.rate.zipWithNext().firstOrNull { (a, b) ->
                a.first >= from && a.second < 0.0 && b.second < 0.0
            }?.first?.first
        }
        val slowdownMin = trace.rate.firstOrNull { it.first > peak && it.second > steepest.second * .5 }?.first
        // NO CENSORING FALLBACK. The whole-episode reader reports the last
        // reading in the window when the rate never returns above the break —
        // measured on this patient, that silently turned 2 of 14 window ends
        // into «end of insulin action». An unobserved tail is null.
        val tailMin = slowdownMin?.let { sd ->
            trace.rate.firstOrNull { it.first > sd && it.second >= trace.breakRate }?.first
        }
        // Still falling when the window closed: the tail is unobserved but
        // KNOWN TO EXCEED the last minute we watched. Only counted once the
        // slowdown was reached, because before that there is no evidence the
        // dose was ever acting.
        // THE PLATEAU: the first minute after the slowdown where the rate stops
        // recovering — its own change across two consecutive samples falls under
        // a small share of the steepest fall — and does not resume. Scaled to
        // the dose's own steepness rather than an absolute number, or a big dose
        // and a small one would be judged by different standards.
        val tailPlateau = slowdownMin?.let { sd ->
            val after = trace.rate.filter { it.first > sd }
            val flat = Math.abs(steepest.second) * PLATEAU_RATE_SHARE
            after.zipWithNext().firstOrNull { (a, b) ->
                val settled = Math.abs(b.second - a.second) <= flat
                val staysSettled = after.filter { it.first > b.first }.take(2)
                    .all { Math.abs(it.second - b.second) <= flat * 2 }
                settled && staysSettled
            }?.second?.first
        }
        val tailCensoredAt = if (tailMin == null && slowdownMin != null) {
            trace.rate.lastOrNull()?.first?.takeIf { it > slowdownMin + MIN_PHASE_SPAN_MIN }
        } else {
            null
        }

        // Plausibility, PER LANDMARK. A quantity outside its own physiological
        // domain is not evidence about that quantity — but it says nothing about
        // the others, so it is dropped alone rather than taking the dose with it.
        val bounds = PhysioBoundsV1()
        val onsetOk = onsetMin != null && onsetMin in bounds.insulinOnsetMinRange &&
            peak - onsetMin >= MIN_RISE_SPAN_MIN
        val peakOk = peak in bounds.insulinPeakMinRange &&
            (onsetMin == null || peak - onsetMin >= MIN_RISE_SPAN_MIN)
        val slowdownOk = slowdownMin != null && slowdownMin - peak >= MIN_PHASE_SPAN_MIN
        val tailOk = tailMin != null && slowdownMin != null &&
            tailMin - slowdownMin >= MIN_PHASE_SPAN_MIN

        val result = SegmentLandmarksV1(
            // The onset is admitted with food on board — that is the point — but
            // never when it sits on the window edge, and at half weight when the
            // background was climbing.
            onset = onsetMin?.takeIf { onsetOk && observed(peak, ONSET_HORIZON_MIN) }?.let { sample(it) },
            // Full weight even against a climbing background: unlike the onset,
            // this landmark is ABOUT the interaction with that background, so a
            // rising line is its subject rather than a defect in it.
            visibleFall = visibleFallMin
                ?.takeIf { onsetOk && observed(it, ONSET_HORIZON_MIN) }
                ?.let { sample(it, weight = 1.0) },
            peakRate = peak.takeIf { peakOk && observed(it, PEAK_HORIZON_MIN) }?.let { sample(it) },
            slowdown = slowdownMin?.takeIf { slowdownOk && observed(it, SLOWDOWN_HORIZON_MIN) }?.let { sample(it) },
            tailEnd = tailMin?.takeIf { tailOk && observed(it, TAIL_HORIZON_MIN) }?.let { sample(it) },
            tailCensoredAtMin = tailCensoredAt,
            tailPlateauMin = tailPlateau?.takeIf { slowdownMin != null && it - slowdownMin >= MIN_PHASE_SPAN_MIN },
        )
        if (result.any) return result
        // WHICH CHECK KILLED IT. «Landmarks read, none passed» was the largest
        // refusal bucket in the corpus (36 of 303 doses) and it named no cause,
        // so 12% of the corpus could not be vouched for either way. Each
        // landmark fails for one of exactly two reasons — its own plausibility
        // ordering, or falling outside the observed horizon — and the two mean
        // very different things: the first is a trace that does not look like
        // insulin, the second is a window that ended too early.
        val why = buildList {
            fun note(name: InsulinLandmark, minute: Double?, ok: Boolean, horizon: Double) {
                if (minute == null) return
                if (!ok) add(LandmarkRejection(name, LandmarkRejection.Kind.OUT_OF_ORDER))
                else if (!observed(minute, horizon)) add(LandmarkRejection(name, LandmarkRejection.Kind.BEYOND_HORIZON))
            }
            // The onset is split because its `ok` merges TWO conditions — its
            // own range, and a span against the peak. Only the first is a
            // statement about the onset; the second imports the peak's verdict.
            if (onsetMin != null && !onsetOk) {
                if (onsetMin !in bounds.insulinOnsetMinRange) {
                    add(LandmarkRejection(InsulinLandmark.ONSET, LandmarkRejection.Kind.ONSET_OUT_OF_RANGE))
                } else {
                    add(LandmarkRejection(InsulinLandmark.ONSET, LandmarkRejection.Kind.ONSET_DROPPED_FOR_PEAK))
                }
            } else note(InsulinLandmark.ONSET, onsetMin, onsetOk, ONSET_HORIZON_MIN)
            note(InsulinLandmark.PEAK, peak, peakOk, PEAK_HORIZON_MIN)
            note(InsulinLandmark.SLOWDOWN, slowdownMin, slowdownOk, SLOWDOWN_HORIZON_MIN)
            note(InsulinLandmark.TAIL_END, tailMin, tailOk, TAIL_HORIZON_MIN)
        }
        return result.copy(refusal = LandmarkRefusal.AllRejected(why))
    }

    // Per-landmark weighted medians, then one ordering check on the result.
    //
    // The check is not a formality: onset, peak and tail may now come from
    // different doses, so nothing guarantees the assembled sequence increases.
    // When it does not, that is a finding about the corpus — report it, do not
    // quietly move a number to make the curve valid.
    /**
     * Median tail WITH the censored doses, by Kaplan-Meier.
     *
     * A naive median over completed tails answers «how long do the tails that
     * finished inside their window last», which is not the question. Every dose
     * that reached the slowdown is evidence, and one still falling at minute 200
     * says the tail exceeds 200 — information the old aggregate threw away
     * along with the dose.
     *
     * Kaplan-Meier is the standard estimator for exactly this: each completed
     * tail drops the survival curve by its share of those still at risk, and a
     * censored dose leaves the curve alone but stops counting. The median is
     * where survival crosses one half. Returns null when the curve never gets
     * there — with more than half the doses censored the median is beyond the
     * data, and saying so is the honest answer rather than reporting the
     * largest completed value.
     */
    fun censoredTailMedian(samples: List<SegmentLandmarksV1>): LandmarkMedianV1? {
        data class Obs(val minute: Double, val complete: Boolean, val confounded: Boolean)
        val obs = samples.mapNotNull { s ->
            s.tailEnd?.let { Obs(it.minute, true, it.confoundedByFood) }
                ?: s.tailCensoredAtMin?.let { Obs(it, false, false) }
        }.sortedBy { it.minute }
        val completed = obs.count { it.complete }
        val censored = obs.size - completed
        // With nothing censored the estimator degenerates to the plain median,
        // so leave that path alone rather than routing it through here.
        if (completed < 3 || censored == 0) return null
        var survival = 1.0
        var atRisk = obs.size
        var median: Double? = null
        var i = 0
        while (i < obs.size && median == null) {
            val t = obs[i].minute
            var j = i
            while (j < obs.size && obs[j].minute == t) j++
            val events = (i until j).count { obs[it].complete }
            if (events > 0 && atRisk > 0) {
                survival *= 1.0 - events.toDouble() / atRisk
                if (survival <= 0.5) median = t
            }
            atRisk -= (j - i)
            i = j
        }
        val m = median ?: return null
        return LandmarkMedianV1(m, obs.size, obs.count { it.confounded }.toDouble() / obs.size)
    }

    fun assemble(samples: List<SegmentLandmarksV1>): AssembledProfileV1 {
        fun agg(pick: (SegmentLandmarksV1) -> LandmarkSampleV1?): LandmarkMedianV1? {
            val rows = samples.mapNotNull(pick)
            if (rows.isEmpty()) return null
            return LandmarkMedianV1(
                weightedMedian(rows.map { it.minute to it.weight }),
                rows.size,
                rows.count { it.confoundedByFood }.toDouble() / rows.size,
            )
        }
        fun aggWhere(
            rising: Boolean?,
            pick: (SegmentLandmarksV1) -> LandmarkSampleV1?,
        ): LandmarkMedianV1? {
            val rows = samples.mapNotNull(pick)
                .filter { rising == null || it.confoundedByFood == rising }
            if (rows.isEmpty()) return null
            return LandmarkMedianV1(
                weightedMedian(rows.map { it.minute to it.weight }),
                rows.size,
                rows.count { it.confoundedByFood }.toDouble() / rows.size,
            )
        }
        fun conditioned(pick: (SegmentLandmarksV1) -> LandmarkSampleV1?) = ConditionedLandmarkV1(
            flat = aggWhere(false, pick), rising = aggWhere(true, pick), pooled = aggWhere(null, pick),
        )
        val onset = conditioned { it.onset }
        val visible = conditioned { it.visibleFall }
        val peak = conditioned { it.peakRate }
        val slowdown = agg { it.slowdown }
        val tail = censoredTailMedian(samples) ?: agg { it.tailEnd }
        // MEASURED, NOT APPLIED. The early landmarks are conditioned on food
        // acting AT them and the late pair is not, and nothing had ever
        // measured what that condition would do here. Computed so the question
        // is answerable from a log; what the curve uses is unchanged above.
        val slowdownCond = conditioned { it.slowdown }
        val tailCond = conditioned { it.tailEnd }
        // The visible fall joins the ordering check: it comes after the break by
        // construction WITHIN a dose, but these medians are pooled across
        // different doses, so across the corpus nothing guarantees it.
        // The visible fall may COINCIDE with the break — on a flat background
        // there is nothing to cancel, so the trajectory bends and turns down in
        // the same bin. Measured directly, the paired median gap is
        // exactly 0. Requiring a strict increase there rejected the profile
        // built on the largest corpus for being physiologically normal.
        // Everything else must genuinely advance.
        // ONE arm for the whole profile. Every landmark that needs conditioning
        // must clear its own threshold before ANY of them moves to the food-free
        // arm, so the profile never mixes two populations.
        val arm = if (onset.flatArmReady && peak.flatArmReady && visible.flatArmReady)
            ProfileArmV1.FOOD_FREE else ProfileArmV1.POOLED
        val ordered = listOfNotNull(
            onset.on(arm)?.minute?.let { Triple(InsulinLandmark.ONSET, it, false) },
            visible.on(arm)?.minute?.let { Triple(InsulinLandmark.VISIBLE_FALL, it, true) },
            peak.on(arm)?.minute?.let { Triple(InsulinLandmark.PEAK, it, false) },
            slowdown?.minute?.let { Triple(InsulinLandmark.SLOWDOWN, it, false) },
            tail?.minute?.let { Triple(InsulinLandmark.TAIL_END, it, false) },
        )
        val conflict = ordered.zipWithNext()
            .firstOrNull { (a, b) -> if (b.third) b.second < a.second else b.second <= a.second }
            ?.let { (a, b) -> OrderingConflict(b.first, b.second, a.first, a.second) }
        // PAIRED, on the doses that reported both — see [cancellingRiseMin].
        val gaps = samples.mapNotNull { s ->
            val a = s.onset ?: return@mapNotNull null
            val b = s.visibleFall ?: return@mapNotNull null
            b.minute - a.minute
        }
        return AssembledProfileV1(
            onset, visible, peak, slowdown, tail, conflict, arm,
            slowdownConditioned = slowdownCond, tailConditioned = tailCond,
        ).also {
            it.cancellingRiseMin = gaps.takeIf { g -> g.isNotEmpty() }
                ?.sorted()?.let { g -> if (g.size % 2 == 1) g[g.size / 2] else (g[g.size / 2 - 1] + g[g.size / 2]) / 2.0 }
        }
    }

    private fun weightedMedian(rows: List<Pair<Double, Double>>): Double {
        val sorted = rows.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2.0
        var seen = 0.0
        return sorted.first { seen += it.second; seen >= half }.first
    }
}
