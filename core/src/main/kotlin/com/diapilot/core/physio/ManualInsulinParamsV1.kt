package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridCdfKnot
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Where the insulin shape and ISF the app is USING actually came from.
 *
 * The order is the user's, and it is a PRECEDENCE, not a
 * work queue: what the user sets by hand wins over what we measure from their own
 * tagged corrections, which in turn wins over what we infer from untagged
 * injections behind the full gates.
 */
enum class InsulinParamTierV1 {
    /** P1 — entered by the user. We may only report that we disagree. */
    MANUAL,

    /** P2 — the user's own doses labelled "correction". Admitted by default; problems
     * are surfaced for the user to adjudicate rather than silently vetoed. */
    TAGGED_CORRECTION,

    /** P3 — everything else, and only if it clears the full gates. */
    GATED_EPISODE,

    /** Cold start: the model's parametric prior, measured on nobody. */
    PRIOR,
}

/**
 * Landmarks that name a curve without pinning its detail.
 *
 * The most active phase is an INTERVAL, not a point — the user's own reading
 * of their sensor: «the most active action is from 30 to 70 minutes, and
 * after that action continues, but as decay». A single peak cannot
 * express that: it forces the rate to turn over the instant it arrives, which
 * is the triangle the user rejected wearing a smoother coat.
 *
 * [plateauEndMin] is nullable so a curve with one genuine mode stays expressible
 * and a user who has no opinion about the shoulder is not made to invent one.
 */
data class InsulinShapeLandmarksV1(
    val onsetMin: Double,
    /** Where the action reaches its maximum RATE and the active phase opens. */
    val peakMin: Double,
    /** Where that maximum stops holding and decay begins; null = single mode. */
    val plateauEndMin: Double? = null,
    val tailMin: Double,
) {
    val activeEndMin: Double get() = plateauEndMin ?: peakMin

    val ordered: Boolean get() =
        onsetMin < peakMin && peakMin <= activeEndMin && activeEndMin < tailMin

    /** Best single-point summary, for consumers that can hold only one peak. */
    val singlePeakMin: Double get() = (peakMin + activeEndMin) / 2.0
}

/**
 * What the user set by hand. Every field is independent — the user may know their
 * ISF cold and have no opinion on the tail, and forcing an all-or-nothing entry
 * would push them to invent the numbers they do not have.
 */
data class ManualInsulinParamsV1(
    val onsetMin: Double? = null,
    val peakMin: Double? = null,
    /** End of the most active phase. Blank means «I have no opinion». */
    val plateauEndMin: Double? = null,
    val tailMin: Double? = null,
    val isfMmolPerU: Double? = null,
    val setAtMs: Long? = null,
) {
    val anyShape: Boolean get() =
        onsetMin != null || peakMin != null || plateauEndMin != null || tailMin != null
    val any: Boolean get() = anyShape || isfMmolPerU != null

    val setFields: Set<String> get() = buildSet {
        if (onsetMin != null) add(ONSET)
        if (peakMin != null) add(PEAK)
        if (plateauEndMin != null) add(PLATEAU_END)
        if (tailMin != null) add(TAIL)
        if (isfMmolPerU != null) add(ISF)
    }

    companion object {
        const val ONSET = "onset"
        const val PEAK = "peak"
        const val PLATEAU_END = "plateau_end"
        const val TAIL = "tail"
        const val ISF = "isf"
        val EMPTY = ManualInsulinParamsV1()
    }
}

/** A field the user set that their own data disagrees with. */
data class ParamDivergenceV1(
    val field: String,
    val manual: Double,
    val measured: Double,
    val tier: InsulinParamTierV1,
) {
    val delta: Double get() = measured - manual
    val relative: Double get() = if (manual == 0.0) Double.MAX_VALUE else delta / manual
}

/**
 * Shape arithmetic shared by the runtime, the resolver and the screen.
 *
 * One implementation on purpose: the curve drawn for the user, the curve fed
 * to the forecast and the curve deconvolution subtracts from a meal must be the
 * same object, or the picture explains a model that never ran.
 */
object InsulinShapeV1 {
    private const val STEP_MIN = 5.0

    // Landmarks of a measured CDF.
    //
    // Peak is the midpoint of the steepest segment rather than its right edge.
    // That is what [PersonalInsulinCurveV1] has always been reduced to before
    // entering the person model, and the two must not drift apart — the
    // estimator's own right-edge rule exists only to check its bounds contract.
    /**
     * @param plateauShare how close to the top rate still counts as the active
     *   phase. Exposed so the round trip can be SWEPT rather than argued about:
     *   this reader and [synthesize] must agree on where the plateau opens, and
     *   whether they do is a measurable property of this number.
     */
    fun landmarks(
        knots: List<HybridCdfKnot>,
        plateauShare: Double = PLATEAU_SHARE,
    ): InsulinShapeLandmarksV1? {
        if (knots.size < 4) return null
        val onset = knots.firstOrNull { it.fraction >= .01 }?.minute ?: return null
        val bins = knots.zipWithNext().filter { (a, b) -> b.minute > a.minute }
        val rates = bins.map { (a, b) ->
            ((a.minute + b.minute) / 2.0) to (b.fraction - a.fraction) / (b.minute - a.minute)
        }
        val top = rates.maxByOrNull { it.second } ?: return null
        // The active phase is every bin still within PLATEAU_SHARE of the peak
        // rate. Reading only the single steepest bin cannot tell a curve that
        // spikes and turns over from one that holds — and that difference is
        // precisely what the user is describing when they say the action
        // stays flat out from 30 to 70 minutes.
        val active = rates.filter { it.second >= top.second * plateauShare }
        val start = active.first().first
        val end = active.last().first
        return InsulinShapeLandmarksV1(
            onsetMin = onset,
            peakMin = start,
            plateauEndMin = end.takeIf { it > start },
            tailMin = knots.last().minute,
        )
    }

    /**
     * Move landmarks INTO the artifact's domain instead of refusing them.
     *
     * ONE implementation, because losing it in a rewrite is not hypothetical:
     * the segment profile shipped without it and a measured end of action just
     * below the 120-minute floor was built, displayed, and then silently
     * refused by the code that installs it. The screen said "curve built, but
     * NOT applied".
     */
    fun coerceIntoDomain(read:InsulinShapeLandmarksV1):Pair<InsulinShapeLandmarksV1,List<String>> {
        val bounds=PhysioBoundsV1()
        val moved=mutableListOf<String>()
        fun note(name:String,from:Double,to:Double){ if(kotlin.math.abs(to-from)>1e-9)moved+="$name %.0f→%.0f".format(from,to) }
        val tail=read.tailMin.coerceIn(bounds.insulinTailMinRange).also{note("хвост",read.tailMin,it)}
        // Order is enforced against the ALREADY coerced neighbour, so the
        // result is a valid curve rather than three independently legal numbers
        // that do not form one — the same failure the manual P1 path checks for.
        val onset=read.onsetMin.coerceIn(bounds.insulinOnsetMinRange)
            .coerceAtMost(tail-3*STEP_MIN).coerceAtLeast(0.0).also{note("старт",read.onsetMin,it)}
        val peak=read.peakMin.coerceIn(bounds.insulinPeakMinRange)
            .coerceIn(onset+STEP_MIN,tail-2*STEP_MIN).also{note("пик",read.peakMin,it)}
        // The active phase is bounded by what a SINGLE-peak consumer will make
        // of it. `HybridPersonModel.insulin.peakMin` takes one number, and
        // [InsulinShapeLandmarksV1.singlePeakMin] is the midpoint of the phase —
        // so a legal peak with a long legal plateau can still summarise to an
        // illegal peak, and the curve would be built here and refused where it
        // is installed. Bound the midpoint, not just its ends.
        val plateauCeiling=minOf(tail-STEP_MIN,2*bounds.insulinPeakMinRange.endInclusive-peak)
        val plateau=read.plateauEndMin?.takeIf{plateauCeiling>peak}?.coerceIn(peak,plateauCeiling)
            ?.takeIf{it>peak}?.also{note("конец активной фазы",read.plateauEndMin,it)}
        return InsulinShapeLandmarksV1(onset,peak,plateau,tail) to moved.toList()
    }


    /**
     * How close to the maximum rate still counts as «the active phase».
     *
     * RAISED 0.85 -> 0.99, to put this reader on the same ruler
     * as [synthesize]. They intend the same event — «the minute full rate is
     * reached and the active phase opens» — but disagreed by a margin that grew
     * with the ramp, because a smoothstep flattens near its top and bins reach
     * 85% of the maximum well before the plateau begins:
     *
     * | recorded | 35 | 55 | 75 | 93 | 120 |
     * |---|---|---|---|---|---|
     * | read at 0.85 | 34 | 51 | 66 | 81 | 101 |
     * | read at 0.99 | 37 | 54 | 74 | 90 | 115 |
     *
     * Nineteen minutes at the far end, and it made every comparison between «the
     * measured peak» and a fitted one quietly wrong — the gap that had been
     * quoted was overstated by about twelve minutes.
     *
     * Safe to raise BECAUSE the only production reader ([resolve]) is handed a
     * curve that [synthesize] built, so there is no sensor noise for a strict
     * threshold to latch onto. On the era curve the active phase narrows from
     * 24 to 14 minutes and never collapses.
     */
    const val PLATEAU_SHARE = 0.99

    /**
     * Move a measured curve onto new landmarks, keeping everything between.
     *
     * The alternative — throwing the measured curve away and drawing a fresh
     * one from three numbers — discards exactly the detail the user asked
     * for when they rejected the triangle. They know WHEN their insulin starts; the
     * data knows what it does in between. A monotone piecewise-linear warp of
     * the time axis keeps both, and cannot break the CDF: it is monotone in
     * time and leaves every fraction untouched.
     */
    fun warp(knots: List<HybridCdfKnot>, target: InsulinShapeLandmarksV1): List<HybridCdfKnot>? {
        val from = landmarks(knots) ?: return null
        if (!from.ordered || !target.ordered) return null
        // The phase anchor is used only when BOTH sides have one. A target
        // that states no phase must not have the source's collapsed onto its
        // peak — that would ask the warp to map two distinct source minutes
        // onto one, which is not a time warp but a fold.
        val anchors = buildList {
            add(0.0 to 0.0)
            add(from.onsetMin to target.onsetMin)
            add(from.peakMin to target.peakMin)
            if (from.plateauEndMin != null && target.plateauEndMin != null) {
                add(from.plateauEndMin to target.plateauEndMin)
            }
            add(from.tailMin to target.tailMin)
        }.distinctBy { it.first }.sortedBy { it.first }
        if (anchors.zipWithNext().any { (a, b) -> b.second <= a.second }) return null
        fun map(minute: Double): Double {
            if (minute <= anchors.first().first) return anchors.first().second
            if (minute >= anchors.last().first) return anchors.last().second
            val right = anchors.first { it.first >= minute }
            val left = anchors[anchors.indexOf(right) - 1]
            val span = right.first - left.first
            return left.second + (right.second - left.second) * (minute - left.first) / span
        }
        val moved = knots.map { HybridCdfKnot(map(it.minute), it.fraction) }
        return moved.takeIf { it.zipWithNext().all { (a, b) -> b.minute > a.minute } }
    }

    // A curve for landmarks with no measurement behind them.
    //
    // Deliberately NOT a triangle. The user rejected it by name, and their own
    // sensor says why: the fall does not decay linearly from the peak, it holds
    // a shoulder and then runs a long tail. A gamma density with its mode at
    // [InsulinShapeLandmarksV1.peakMin] gives that shape from the same three
    // numbers, and the shape parameter is solved so the tail carries [TAIL_MASS]
    // of the action by the stated end.
    /**
     * @param tailShare pins what fraction of the dose arrives AFTER the active
     *   phase; see [TAIL_SHARE]. Null keeps the shipped construction, where the
     *   tail's weight is whatever its length makes it — which lets a longer end
     *   of action quietly weaken the peak (M-79).
     */
    fun synthesize(
        target: InsulinShapeLandmarksV1,
        tailShare: Double? = null,
    ): List<HybridCdfKnot>? {
        if (!target.ordered) return null
        // A stated active PHASE is a different curve from a stated peak, so it
        // gets its own construction rather than a gamma nudged sideways.
        if (target.plateauEndMin != null) return synthesizePlateau(target, tailShare)
        val mode = target.peakMin - target.onsetMin
        val span = target.tailMin - target.onsetMin
        if (mode <= 0.0 || span <= mode) return null
        // Density t^(a-1)e^(-t/tau) has its mode at (a-1)*tau, so pinning the
        // mode leaves tau = mode/(a-1). Larger a concentrates the density, so
        // the mass by `span` rises monotonically with a — bisection is safe.
        fun tauFor(a: Double) = mode / (a - 1.0)
        var lo = 1.0001
        var hi = 400.0
        repeat(80) {
            val mid = (lo + hi) / 2.0
            if (gammaCdf(span, mid, tauFor(mid)) < TAIL_MASS) lo = mid else hi = mid
        }
        val a = (lo + hi) / 2.0
        val tau = tauFor(a)
        val total = gammaCdf(span, a, tau)
        if (!total.isFinite() || total <= 0.0) return null
        // The grid is anchored ON the onset, not on zero. Otherwise the last
        // zero knot lands on the nearest five-minute mark below it and linear
        // interpolation leaks action into the minutes the user said were
        // quiet — the stated onset would be off by up to five minutes, which
        // is exactly the number they were setting by hand.
        val minutes = buildList {
            add(0.0)
            var minute = target.onsetMin
            while (minute < target.tailMin) { add(minute); minute += STEP_MIN }
            add(target.tailMin)
        }.distinct().sorted()
        val knots = minutes.map { minute ->
            val t = minute - target.onsetMin
            HybridCdfKnot(
                minute,
                if (t <= 0.0) 0.0 else (gammaCdf(t, a, tau) / total).coerceIn(0.0, 1.0),
            )
        }.dropLast(1) + HybridCdfKnot(target.tailMin, 1.0)
        return knots.takeIf { it.size >= 4 && it.zipWithNext().all { (x, y) -> y.minute > x.minute && y.fraction >= x.fraction } }
    }

    /** Mass the synthesized curve places before the stated end of action. */
    const val TAIL_MASS = 0.97

    /**
     * How much of a dose the TAIL carries, once the active phase is over.
     *
     * The user's own model: «the tail is, well, about 20 percent after the main
     * action», and the invariant that goes with it: «timings should not
     * affect ISF (it equals the area under the action curve)».
     *
     * The second half was already true at infinity — the CDF integrates to one
     * whatever the landmarks say — but NOT inside an observation window, and
     * that is what the fit showed at the bench. [synthesizePlateau] normalises by the
     * total area, so a longer tail grows the denominator and shrinks every
     * earlier fraction: measured, moving the tail 165 -> 300 dropped the
     * delivered share at ninety minutes from 0.77 to 0.49 (M-79). Lengthening
     * the tail was silently weakening the peak, which is why every fit reached
     * for it as a cheaper substitute for lowering ISF.
     *
     * Pinning the split fixes that: the main action always carries
     * `1 - TAIL_SHARE` and the tail always carries `TAIL_SHARE`, so moving the
     * end of action redistributes the LAST FIFTH and leaves the first four
     * untouched. Timings then stop trading against amplitude.
     */
    const val TAIL_SHARE = 0.20

    // The four-landmark curve, built as a RATE and then integrated.
    //
    // rate(t) = 0 before onset · smooth rise to its maximum at [peakMin] ·
    // flat across the active phase · exponential decay after it, reaching
    // about 5% of the maximum at the stated end.
    //
    // Building the rate and integrating — rather than writing a CDF directly —
    // is what makes the landmarks mean what they say: the flat stretch IS the
    // interval where the user says the fall runs hardest, and the decay
    // afterwards IS «action continues, but as decay». The rise uses a
    // smoothstep so the rate has no corner at the moment action begins, which
    // a corner would render as an impossible instantaneous jerk in glucose.
    /**
     * @param tailShare when non-null, the fraction of the dose the decay after
     *   the active phase must carry — see [TAIL_SHARE]. Null keeps the original
     *   construction, where the tail's own length decides its weight.
     */
    private fun synthesizePlateau(
        target: InsulinShapeLandmarksV1,
        tailShare: Double? = null,
    ): List<HybridCdfKnot>? {
        val plateauEnd = target.plateauEndMin ?: return null
        val lambda = (target.tailMin - plateauEnd) / 3.0
        if (lambda <= 0.0) return null
        fun rawRate(t: Double): Double = when {
            t <= target.onsetMin -> 0.0
            t < target.peakMin -> {
                val u = (t - target.onsetMin) / (target.peakMin - target.onsetMin)
                u * u * (3.0 - 2.0 * u)
            }
            t <= plateauEnd -> 1.0
            t <= target.tailMin -> exp(-(t - plateauEnd) / lambda)
            else -> 0.0
        }
        val fine = 0.25
        val steps = ((target.tailMin) / fine).toInt()
        // The two areas are integrated SEPARATELY so the split between them can
        // be set rather than inherited from how long the tail happens to be.
        var mainArea = 0.0
        var tailAreaRaw = 0.0
        for (i in 1..steps) {
            val a = (i - 1) * fine
            val b = i * fine
            val piece = (rawRate(a) + rawRate(b)) / 2.0 * fine
            if (b <= plateauEnd) mainArea += piece else tailAreaRaw += piece
        }
        if (!mainArea.isFinite() || mainArea <= 0.0) return null
        // Scale the decay so it carries exactly its share. With share s the tail
        // must hold s/(1-s) of the main area; a tail that is naturally too big
        // is scaled down and one that is too small is scaled up, and either way
        // the curve reaching the end of the active phase is the same.
        val tailScale = when {
            tailShare == null -> 1.0
            tailAreaRaw <= 0.0 -> return null
            else -> (tailShare / (1.0 - tailShare)) * mainArea / tailAreaRaw
        }
        fun rate(t: Double) = if (t > plateauEnd) rawRate(t) * tailScale else rawRate(t)
        val cumulative = DoubleArray(steps + 1)
        for (i in 1..steps) {
            val a = (i - 1) * fine
            val b = i * fine
            cumulative[i] = cumulative[i - 1] + (rate(a) + rate(b)) / 2.0 * fine
        }
        val total = cumulative[steps]
        if (!total.isFinite() || total <= 0.0) return null
        fun cdf(minute: Double): Double =
            (cumulative[(minute / fine).toInt().coerceIn(0, steps)] / total).coerceIn(0.0, 1.0)
        val minutes = buildList {
            add(0.0); add(target.onsetMin); add(target.peakMin); add(plateauEnd)
            var minute = target.onsetMin
            while (minute < target.tailMin) { add(minute); minute += STEP_MIN }
            add(target.tailMin)
        }.filter { it <= target.tailMin }.distinct().sorted()
        val knots = minutes.map { HybridCdfKnot(it, if (it >= target.tailMin) 1.0 else cdf(it)) }
        return knots.takeIf {
            it.size >= 4 && it.first().fraction == 0.0 && it.last().fraction == 1.0 &&
                it.zipWithNext().all { (a, b) -> b.minute > a.minute && b.fraction >= a.fraction }
        }
    }

    /** Regularized lower incomplete gamma P(a, t/tau), by series or continued
     * fraction — the standard split at t < a+1. */
    private fun gammaCdf(t: Double, a: Double, tau: Double): Double {
        if (t <= 0.0) return 0.0
        val x = t / tau
        if (x < a + 1.0) {
            var term = 1.0 / a
            var sum = term
            var n = 1
            while (n < 500) {
                term *= x / (a + n)
                sum += term
                if (term < sum * 1e-12) break
                n++
            }
            return (sum * exp(-x + a * ln(x) - lnGamma(a))).coerceIn(0.0, 1.0)
        }
        var b = x + 1.0 - a
        var c = 1e300
        var d = 1.0 / b
        var h = d
        var i = 1
        while (i < 500) {
            val an = -i * (i - a)
            b += 2.0
            d = an * d + b
            if (kotlin.math.abs(d) < 1e-300) d = 1e-300
            c = b + an / c
            if (kotlin.math.abs(c) < 1e-300) c = 1e-300
            d = 1.0 / d
            val delta = d * c
            h *= delta
            if (kotlin.math.abs(delta - 1.0) < 1e-12) break
            i++
        }
        return (1.0 - exp(-x + a * ln(x) - lnGamma(a)) * h).coerceIn(0.0, 1.0)
    }

    private fun lnGamma(a: Double): Double {
        val cof = doubleArrayOf(
            76.18009172947146, -86.50532032941677, 24.01409824083091,
            -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5,
        )
        var y = a
        val tmp = a + 5.5 - (a + 0.5) * ln(a + 5.5)
        var ser = 1.000000000190015
        for (j in cof.indices) { y += 1.0; ser += cof[j] / y }
        return -tmp + ln(2.5066282746310005 * ser / a)
    }
}

/**
 * Applies the precedence. Nothing here decides what is TRUE — it decides whose
 * answer the app uses, and records what the other tiers would have said.
 */
object InsulinParameterResolverV1 {
    /** Below this the two numbers are the same number said twice. */
    const val DIVERGENCE_RELATIVE = 0.15

    data class Resolution(
        val knots: List<HybridCdfKnot>?,
        val landmarks: InsulinShapeLandmarksV1?,
        val shapeTier: InsulinParamTierV1,
        val isfMmolPerU: Double?,
        val isfTier: InsulinParamTierV1,
        val manualFields: Set<String>,
        val divergences: List<ParamDivergenceV1>,
        val rejected: List<String>,
    )

    /**
     * @param measured the best curve the data produced, or null while learning.
     * @param measuredTier which tier produced [measured].
     * @param priorLandmarks the model's cold-start curve, used only to fill
     *        landmarks the user left blank when there is nothing measured.
     */
    fun resolve(
        manual: ManualInsulinParamsV1,
        measured: List<HybridCdfKnot>?,
        measuredTier: InsulinParamTierV1,
        measuredIsf: Double?,
        measuredIsfTier: InsulinParamTierV1,
        priorLandmarks: InsulinShapeLandmarksV1,
        bounds: PhysioBoundsV1 = PhysioBoundsV1(),
    ): Resolution {
        val rejected = mutableListOf<String>()
        val measuredLandmarks = measured?.let { InsulinShapeV1.landmarks(it) }
        val base = measuredLandmarks ?: priorLandmarks

        val wanted = InsulinShapeLandmarksV1(
            onsetMin = manual.onsetMin ?: base.onsetMin,
            peakMin = manual.peakMin ?: base.peakMin,
            plateauEndMin = manual.plateauEndMin ?: base.plateauEndMin,
            tailMin = manual.tailMin ?: base.tailMin,
        )
        // Validate the RESOLVED triple, not the entered fields. Setting only
        // the onset can still order it after a measured peak, and a curve whose
        // onset follows its peak is not a curve.
        val shapeUsable = manual.anyShape && wanted.ordered &&
            wanted.onsetMin in bounds.insulinOnsetMinRange &&
            wanted.peakMin in bounds.insulinPeakMinRange &&
            wanted.activeEndMin in bounds.insulinPeakMinRange &&
            wanted.tailMin in bounds.insulinTailMinRange
        if (manual.anyShape && !shapeUsable) rejected += SHAPE_OUT_OF_DOMAIN

        // Warp when a measured curve can carry the request, synthesize when it
        // cannot — asking for an active PHASE from a curve that has no plateau
        // to stretch is exactly such a case.
        val manualKnots = if (!shapeUsable) null else
            measured?.let { InsulinShapeV1.warp(it, wanted) } ?: InsulinShapeV1.synthesize(wanted)
                ?: InsulinShapeV1.synthesize(wanted)
        if (manual.anyShape && shapeUsable && manualKnots == null) rejected += SHAPE_NOT_REPRESENTABLE

        val isfUsable = manual.isfMmolPerU?.takeIf {
            it >= bounds.isfMmolPerLUmin && it <= bounds.isfMmolPerLUmax
        }
        if (manual.isfMmolPerU != null && isfUsable == null) rejected += ISF_OUT_OF_DOMAIN

        val knots = manualKnots ?: measured
        val landmarks = manualKnots?.let { wanted } ?: measuredLandmarks
        return Resolution(
            knots = knots,
            landmarks = landmarks,
            shapeTier = if (manualKnots != null) InsulinParamTierV1.MANUAL
                else if (measured != null) measuredTier else InsulinParamTierV1.PRIOR,
            isfMmolPerU = isfUsable ?: measuredIsf,
            isfTier = if (isfUsable != null) InsulinParamTierV1.MANUAL
                else if (measuredIsf != null) measuredIsfTier else InsulinParamTierV1.PRIOR,
            manualFields = manual.setFields,
            divergences = divergences(manual, measuredLandmarks, measuredTier, measuredIsf, measuredIsfTier),
            rejected = rejected,
        )
    }

    /**
     * What the data would have said, for the fields the user overrode.
     *
     * This is the only channel left open when P1 is in force, so it must report
     * every disagreement that matters and stay silent about the rest — a screen
     * that flags a two-minute difference in the onset teaches the user to
     * ignore it.
     */
    fun divergences(
        manual: ManualInsulinParamsV1,
        measuredLandmarks: InsulinShapeLandmarksV1?,
        measuredTier: InsulinParamTierV1,
        measuredIsf: Double?,
        measuredIsfTier: InsulinParamTierV1,
    ): List<ParamDivergenceV1> = buildList {
        measuredLandmarks?.let { m ->
            manual.onsetMin?.let { add(ParamDivergenceV1(ManualInsulinParamsV1.ONSET, it, m.onsetMin, measuredTier)) }
            manual.peakMin?.let { add(ParamDivergenceV1(ManualInsulinParamsV1.PEAK, it, m.peakMin, measuredTier)) }
            manual.plateauEndMin?.let { entered ->
                m.plateauEndMin?.let { add(ParamDivergenceV1(ManualInsulinParamsV1.PLATEAU_END, entered, it, measuredTier)) }
            }
            manual.tailMin?.let { add(ParamDivergenceV1(ManualInsulinParamsV1.TAIL, it, m.tailMin, measuredTier)) }
        }
        measuredIsf?.let { m ->
            manual.isfMmolPerU?.let { add(ParamDivergenceV1(ManualInsulinParamsV1.ISF, it, m, measuredIsfTier)) }
        }
    }.filter { kotlin.math.abs(it.relative) >= DIVERGENCE_RELATIVE }

    const val SHAPE_OUT_OF_DOMAIN = "shape-out-of-domain"
    const val SHAPE_NOT_REPRESENTABLE = "shape-not-representable"
    const val ISF_OUT_OF_DOMAIN = "isf-out-of-domain"
}
