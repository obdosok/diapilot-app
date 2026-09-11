package com.diapilot.core.physio

import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.hybrid.HybridCdfKnot
import kotlin.math.abs

/**
 * Read a correction the way its owner reads it — off the sensor line.
 *
 * The user's description of a correction: a plateau or a light slope before the
 * dose, the injection, the trajectory breaking, the steepest fall, the
 * slowing, the tail. Other factors are known to exist. The estimator assumes the
 * background is settled and inertial, and builds its coefficients from what the sensor
 * shows. That is not wrong to do: the line already CONTAINS the background, so
 * subtracting a simulated one adds a second model's error to a measurement that
 * did not need it.
 *
 * That is exactly what the counterfactual fit was doing. Measured directly:
 * with a simulated food background one correction went from RMSE 0.15 to
 * 5.12 and another — a textbook 17.2 -> 3.05 — scored a NEGATIVE correlation.
 * The old good numbers came from a FLAT fallback, so they measured «how far did
 * glucose fall» rather than «what did insulin explain»; the new bad numbers came
 * from a food model that overshoots. Neither matches what the user sees.
 *
 * So this estimator makes the pre-dose inertia the baseline and reads the
 * DEPARTURE from it. Landmarks are reported in SENSOR TIME — the fall shows up
 * on screen later than plasma insulin acts, and quietly shifting for that lag
 * would print numbers the user cannot check against their own screen.
 */
data class SensorReadingFitV1(
    /** Where the trajectory departs from the pre-dose line. */
    val onsetMin: Double,
    /** Steepest fall. */
    val peakRateMin: Double,
    /** Where the fall has slowed to half its steepest. */
    val slowdownMin: Double,
    /** Where the line returns to its pre-dose inertia. */
    val tailEndMin: Double,
    /** How far below the held baseline the line reached. */
    val dropMmol: Double,
    val isfMmolPerU: Double,
    val baselineSlopeMmolPerH: Double,
    /** Normalized cumulative departure — the observed action curve. */
    val cdf: List<HybridCdfKnot>,
    /**
     * How smoothly the line actually behaves, in mmol/L: residual of the
     * smoothed curve against the raw points. The user's own criterion —
     * "the line has to be smooth enough" — and a far better description of
     * a usable trace than a correlation against a modelled action rate.
     */
    val roughnessMmol: Double,
) {
    val ordered: Boolean get() = onsetMin < peakRateMin && peakRateMin <= slowdownMin && slowdownMin < tailEndMin
}

/**
 * HOW LONG A PRE-DOSE TREND MAY BE EXTRAPOLATED, in hours.
 *
 * Moved here from `ClosedCausalEpisodeV1`, which was deleted with
 * the episode pipeline. The constant itself is measured and still in use by the
 * reader below, so it moved rather than went with its former file.
 *
 * It used to be a free 1.0 hour, picked after one episode: for one particular
 * correction — a textbook 17.2 -> 3.05 — an unbounded projection put the counterfactual at
 * -6.6 mmol/L and the fit answered ISF 0.20 with a NEGATIVE correlation for an
 * excellent correction. Tying it to the observation window keeps the fix and
 * removes the arbitrariness: thirty minutes of evidence buys thirty minutes of
 * extrapolation. Measured effect on the corrections corpus: median ISF 2.31 -> 2.26.
 */
const val BACKGROUND_TREND_HOLD_H = 0.5

/** And no background drifts faster than this for an hour. */
const val BACKGROUND_TREND_MAX_MMOL_H = 3.0

// Dose labels the user uses to say "I trusted this injection".
//
// Moved here with the deletion of the episode pipeline. It is still
// the admission rule for the ISF corpus and for the shape corpus, so it belongs
// beside the reader that uses it rather than in a retired file.
//
// A correction bolus (top-up) label is a top-up given when glucose ran higher than the
// meal bolus covered. The user asked for it to count, and it is the same
// statement of intent as a plain correction — the difference is that a top-up
// lands while food is still absorbing, which is a CONCERN to name, not a
// reason to discard the label.
/**
 * The receipt version the retired episode pipeline last wrote.
 *
 * Kept after the pipeline was deleted for exactly one reason: the
 * rows it wrote are still in the database, and `TypedPhysioEvidenceRuntime`
 * still filters on this string. Nothing writes it any more, so the research
 * matrix now reports no physical estimates — which is the truth, not a bug.
 */
const val CLOSED_CAUSAL_EPISODE_VERSION_V1 = "closed-causal-episode-v42-activity-coverage-reachable"

fun isTrustedDosePurposeV1(purpose: String?): Boolean {
    val p = purpose?.trim().orEmpty()
    return p.contains("корр", ignoreCase = true) || p.contains("докол", ignoreCase = true)
}

object SensorCorrectionReaderV1 {
    /** Half-width of the centred smoother. Two CGM samples either side. */
    const val SMOOTH_HALF_WIDTH_MIN = 7.5

    /** Floor under «the trajectory broke», in mmol/L/h below the inertia. */
    const val DEPARTURE_MMOL_PER_H = 0.8

    /**
     * REMOVED as a free parameter: kept at 0 so the break threshold
     * is the fixed [DEPARTURE_MMOL_PER_H] alone.
     *
     * It scaled the threshold by the line's own rate noise, and it was the one
     * constant in this estimator chosen against the user's own words — its
     * previous comment said so outright: a fixed threshold «put the break at 3,
     * 7, 7, 11 and 15 minutes on lines whose owner reads the fall from 25–30».
     * Tuning an estimator until it reproduces what the person already believes
     * makes its agreement with them worthless as evidence.
     *
     * Measured cost of removing it, on a 221-dose corpus:
     *
     *     with x2.0   onset 11 · peak 40 · active to 69 · end 120   n=131
     *     without     onset 14 · peak 43 · active to 69 · end 120   n=156
     *
     * Three minutes of shift, twenty-five MORE usable segments, and the
     * circularity gone. Kept as a parameter rather than deleted so the harness
     * can still sweep it and show what it would have done.
     */
    const val DEPARTURE_NOISE_MULTIPLE = 0.0

    /**
     * How much of this reader's ISF is our CHOICE rather than the body.
     *
     * RECOMPUTED, after the two constants that produced most of this
     * width stopped being free: [DEPARTURE_NOISE_MULTIPLE] is fixed at 0 and
     * [BACKGROUND_TREND_HOLD_H] is derived from [BASELINE_WINDOW_MIN]. The width
     * has to describe what is free NOW, not what was free before.
     *
     * What remains sweepable is the baseline window itself — how many minutes of
     * pre-dose line we call inertia. Measured over a 14-dose trusted corpus:
     *
     *     window 15 min (hold .25)   ISF 2.38
     *     window 30 min (hold .50)   ISF 2.49   <- production
     *     window 60 min (hold 1.0)   ISF 2.54
     *     spread 0.16 mmol/U = 7%   (was 33% with the old free constants)
     *
     * Set ABOVE the measured 7%, because 7% is a lower bound: it is the spread
     * from the one knob the harness can vary. [SMOOTH_HALF_WIDTH_MIN], the 0.5
     * that defines «slowdown», and the reading-count floors are all still chosen
     * and still unswept, and pretending otherwise would be the same overclaim in
     * a smaller size.
     *
     * NOTE THE DIRECTION. This NARROWS the corridor — from ±12/18% to ±8/10% —
     * and a narrower corridor makes a predicted low appear LATER. That is
     * normally the unsafe direction, and it is accepted here only because the
     * old width was measuring constants that no longer vary: claiming
     * uncertainty we do not have is not caution, it is a different wrong number.
     *
     * Still applied as a SYSTEMATIC — added to the trace's own noise term, not
     * combined in quadrature. The shift has the same sign for every episode, so
     * more episodes do not shrink it.
     */
    const val CONVENTION_ISF_SHARE_LOW = .08
    const val CONVENTION_ISF_SHARE_HIGH = .10

    /** Minutes of line before the dose that define the inertia. */
    const val BASELINE_WINDOW_MIN = 30.0

    /** Fewest readings before and after the dose to read anything at all. */
    const val MIN_BEFORE = 3
    const val MIN_AFTER = 12

    // @param windowEndMs end of this dose's own clean interval.
    // @return null only when the line cannot be read at all — never because the
    // reading is inconvenient. Judging the trace is the caller's business.
    /**
     * The line as this reader sees it: smoothed, with its pre-dose inertia and
     * its own restlessness measured.
     *
     * Extracted so the segment reader cannot drift from the whole-episode one.
     * Two readers of the same line that smooth differently would produce two
     * incompatible profiles and no way to tell which is the body's — the exact
     * failure this project has already paid for with four representations of
     * insulin.
     */
    internal data class Trace(
        /** minute-from-dose to smoothed mmol, dose at t=0. */
        val smooth: List<Pair<Double, Double>>,
        val after: List<Pair<Double, Double>>,
        /** minute (bin centre) to mmol/L/h. */
        val rate: List<Pair<Double, Double>>,
        val baseSlopeMmolPerH: Double,
        val g0: Double,
        val roughnessMmol: Double,
        /** Below this the line is no longer behaving like its old self. */
        val breakRate: Double,
    )

    internal fun trace(
        readings: List<GlucosePoint>,
        bolusTsMs: Long,
        windowEndMs: Long,
        noiseMultiple: Double,
        minAfter: Int,
        departureFloor: Double = DEPARTURE_MMOL_PER_H,
    ): Trace? {
        val span = readings
            .filter { it.tsMs in bolusTsMs - (BASELINE_WINDOW_MIN * 60_000).toLong()..windowEndMs }
            .sortedBy { it.tsMs }
        val before = span.count { it.tsMs <= bolusTsMs }
        if (before < MIN_BEFORE || span.size - before < minAfter) return null
        fun minute(p: GlucosePoint) = (p.tsMs - bolusTsMs) / 60_000.0
        // MEDIAN first, then mean — see the comment in [read].
        val medianed = span.map { p ->
            val t = minute(p)
            val near = span.filter { abs(minute(it) - t) <= SMOOTH_HALF_WIDTH_MIN }
                .map { it.mmol }.sorted()
            t to near[near.size / 2]
        }
        val smooth = medianed.map { (t, _) ->
            val near = medianed.filter { abs(it.first - t) <= SMOOTH_HALF_WIDTH_MIN }
            t to near.sumOf { it.second } / near.size
        }
        val roughness = kotlin.math.sqrt(
            span.indices.sumOf { i -> (span[i].mmol - smooth[i].second).let { it * it } } / span.size,
        )
        val pre = smooth.filter { it.first <= 0.0 }
        if (pre.size < MIN_BEFORE) return null
        val preHours = ((pre.last().first - pre.first().first) / 60.0).coerceAtLeast(1e-3)
        val baseSlope = ((pre.last().second - pre.first().second) / preHours)
            .let { if (it.isFinite()) it else 0.0 }
            .coerceIn(-BACKGROUND_TREND_MAX_MMOL_H, BACKGROUND_TREND_MAX_MMOL_H)
        val after = smooth.filter { it.first > 0.0 }
        if (after.size < minAfter) return null
        fun rates(points: List<Pair<Double, Double>>) = points.zipWithNext().map { (a, b) ->
            ((a.first + b.first) / 2.0) to (b.second - a.second) / ((b.first - a.first) / 60.0)
        }
        val rate = rates(after)
        if (rate.isEmpty()) return null
        val preRates = rates(pre).map { it.second }
        val noise = if (preRates.size < 2) 0.0 else {
            val mean = preRates.average()
            kotlin.math.sqrt(preRates.sumOf { (it - mean) * (it - mean) } / preRates.size)
        }
        return Trace(
            smooth = smooth, after = after, rate = rate,
            baseSlopeMmolPerH = baseSlope, g0 = pre.last().second, roughnessMmol = roughness,
            breakRate = baseSlope - maxOf(departureFloor, noiseMultiple * noise),
        )
    }

    fun read(
        readings: List<GlucosePoint>,
        bolusTsMs: Long,
        units: Double,
        windowEndMs: Long,
        /**
         * The two free numbers of this estimator, as PARAMETERS.
         *
         * Both were chosen against one or two episodes, and both scale the ISF
         * this reader reports: [holdHours] decides how long the pre-dose trend
         * is carried into the baseline the departure is measured from, and
         * [noiseMultiple] decides where the fall is judged to begin. Left as
         * constants they are unmeasurable — the harness would have to
         * reimplement the reader to vary them, and then it would be measuring a
         * model production never runs (discipline #7). Defaults are exactly the
         * production values, so every existing call site is unchanged.
         */
        holdHours: Double = BACKGROUND_TREND_HOLD_H,
        noiseMultiple: Double = DEPARTURE_NOISE_MULTIPLE,
        departureFloor: Double = DEPARTURE_MMOL_PER_H,
    ): SensorReadingFitV1? {
        if (units <= 0.0) return null
        val span = readings
            .filter { it.tsMs in bolusTsMs - (BASELINE_WINDOW_MIN * 60_000).toLong()..windowEndMs }
            .sortedBy { it.tsMs }
        val before = span.count { it.tsMs <= bolusTsMs }
        if (before < MIN_BEFORE || span.size - before < MIN_AFTER) return null

        fun minute(p: GlucosePoint) = (p.tsMs - bolusTsMs) / 60_000.0
        // Centred moving average. The line the user reads is already smooth
        // to the eye; the estimator has to see the same line, not the jitter.
        // MEDIAN first, then mean. A mean alone drags every sensor step into
        // the curve — a "staircase" artifact on one trace survived it and drove
        // the whole reading. A median filter is the standard answer to steps
        // and spikes: it removes them outright, and the mean afterwards leaves
        // the smooth line the user actually reads off the screen.
        //
        // The user's own framing: noise and steps are not something
        // this app forecasts or needs to; what it needs is a few robust numbers
        // for the profile. So smooth harder and answer, rather than refuse.
        val medianed = span.map { p ->
            val t = minute(p)
            val near = span.filter { abs(minute(it) - t) <= SMOOTH_HALF_WIDTH_MIN }
                .map { it.mmol }.sorted()
            t to near[near.size / 2]
        }
        val smooth = medianed.map { (t, _) ->
            val near = medianed.filter { abs(it.first - t) <= SMOOTH_HALF_WIDTH_MIN }
            t to near.sumOf { it.second } / near.size
        }
        val roughness = kotlin.math.sqrt(
            span.indices.sumOf { i -> (span[i].mmol - smooth[i].second).let { it * it } } / span.size,
        )

        val pre = smooth.filter { it.first <= 0.0 }
        if (pre.size < MIN_BEFORE) return null
        val preHours = ((pre.last().first - pre.first().first) / 60.0).coerceAtLeast(1e-3)
        val baseSlope = ((pre.last().second - pre.first().second) / preHours)
            .let { if (it.isFinite()) it else 0.0 }
            .coerceIn(-BACKGROUND_TREND_MAX_MMOL_H, BACKGROUND_TREND_MAX_MMOL_H)
        val g0 = pre.last().second

        // Held, not extrapolated. Inertia is what the estimator assumes, and
        // inertia decays — a slope projected for hours is not a background,
        // it is a fantasy. See BACKGROUND_TREND_HOLD_H.
        fun baseline(t: Double) = g0 + baseSlope * minOf(t / 60.0, holdHours)

        val after = smooth.filter { it.first > 0.0 }
        if (after.size < MIN_AFTER) return null
        fun rates(points: List<Pair<Double, Double>>) = points.zipWithNext().map { (a, b) ->
            ((a.first + b.first) / 2.0) to (b.second - a.second) / ((b.first - a.first) / 60.0)
        }
        val rate = rates(after)
        if (rate.isEmpty()) return null

        // The threshold answers «louder than this line's own restlessness».
        val preRates = rates(pre).map { it.second }
        val noise = if (preRates.size < 2) 0.0 else {
            val mean = preRates.average()
            kotlin.math.sqrt(preRates.sumOf { (it - mean) * (it - mean) } / preRates.size)
        }
        val breakRate = baseSlope - maxOf(DEPARTURE_MMOL_PER_H, noiseMultiple * noise)

        // Find the FALL first, then walk back to where it started.
        //
        // Scanning forward for the first crossing makes the first ripple after
        // the injection the onset, and a ripple is not a trajectory change. The
        // steepest point is unambiguous — it is the minimum of a smoothed rate —
        // and the break is the last moment before it at which the line was
        // still behaving like its old self. An earlier flicker that recovered
        // is skipped by construction, because the walk stops at the crossing
        // NEAREST the fall.
        val steepest = rate.minByOrNull { it.second } ?: return null
        if (steepest.second > breakRate) return null
        val peak = steepest.first
        val onset = rate.lastOrNull { it.first < peak && it.second > breakRate }?.first
            ?: rate.first().first
        val slowdown = rate.firstOrNull { it.first > peak && it.second > steepest.second * .5 }?.first ?: peak
        val tailEnd = rate.firstOrNull { it.first > slowdown && it.second >= breakRate }?.first
            ?: after.last().first

        // Departure from the held baseline — what insulin moved, in the units
        // the user reads. Everything the line already carried (food, basal,
        // whatever else) stays where it was: inside the line.
        val departure = after.map { (t, v) -> t to (baseline(t) - v).coerceAtLeast(0.0) }
        val drop = departure.filter { it.first <= tailEnd }.maxOfOrNull { it.second } ?: return null
        if (drop <= 0.0) return null

        var running = 0.0
        val knots = buildList {
            add(HybridCdfKnot(0.0, 0.0))
            departure.filter { it.first <= tailEnd }.forEach { (t, d) ->
                running = maxOf(running, d)
                add(HybridCdfKnot(t, (running / drop).coerceIn(0.0, 1.0)))
            }
        }.distinctBy { it.minute }.sortedBy { it.minute }
        val complete = knots.dropLast(1) + HybridCdfKnot(knots.last().minute, 1.0)
        if (complete.size < 4) return null

        return SensorReadingFitV1(
            onsetMin = onset,
            peakRateMin = peak,
            slowdownMin = slowdown,
            tailEndMin = tailEnd,
            dropMmol = drop,
            isfMmolPerU = drop / units,
            baselineSlopeMmolPerH = baseSlope,
            cdf = complete,
            roughnessMmol = roughness,
        )
    }
}
