package com.diapilot.core.physio

import com.diapilot.core.hybrid.CarbAppearancePolicyV1
import com.diapilot.core.hybrid.CarbTrianglesV1
import com.diapilot.core.hybrid.HybridBasalEvent
import com.diapilot.core.hybrid.HybridBolusEvent
import com.diapilot.core.hybrid.HybridFoodEvent
import com.diapilot.core.hybrid.HybridForecastState
import com.diapilot.core.hybrid.HybridGlucosePoint
import com.diapilot.core.hybrid.HybridPersonModel
import com.diapilot.core.hybrid.physioForecastEngine
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ONE FITTER, USED BY BOTH THE LAPTOP BENCH AND THE PHONE.
 *
 * Two fitters over the same knobs would diverge without announcing it, so the
 * search, the loss and the tuning all live here and both callers are thin: each
 * supplies episodes read from its own database and neither owns any of the
 * mathematics.
 *
 * The three metrics are reported separately and never collapsed — [Metric.SHAPE]
 * is blind to a constant offset, [Metric.BIAS] to everything else, and
 * [Metric.BALANCE] is their sum offered as a third choice rather than the only
 * one. A known confound the caller must show: with food still under-delivered,
 * BIAS pulls ISF down to correct a mean the food should have carried, so an ISF
 * read off that arm is partly a food deficit wearing an insulin label (M-86,
 * audit finding M3).
 *
 * What it fits, why each axis exists, and the corridor it searches in:
 * `docs/forecast-engine.md` §4.
 */
object PhysioAutoFitV1 {

    private const val MINUTE_MS = 60_000L

    /**
     * THE SCORING WINDOW — one definition, because two callers share the search.
     *
     * The phone was fitting on 0..240 at 15-minute steps while the bench handed
     * the SAME function 0..480 at 10. Sharing the fitter and then handing it
     * different grids is the A-33 failure re-created one level up: the two
     * surfaces run identical code on different problems and neither says so.
     *
     * SIX HOURS, and it is measured rather than chosen. On a 22-episode corpus the
     * old four-hour window had **not returned to baseline in 16 of them**, and
     * several peaked at +220..+240 — that is, at or past the edge, so the fit
     * was judging a truncated rise and could pay for the missing top by making
     * the food smaller or the insulin different. Nothing was diluted: not one
     * episode finished early and left a flat tail, so the window was too SHORT
     * in one direction only.
     *
     * Six covers the return in twenty of the twenty-two. The two that run to
     * +380 and +450 stay truncated; extending for them would drag most episodes
     * deep into unrelated territory, and a window that ends is honest as long as
     * it is the same window everywhere.
     */
    val SCORING_GRID: List<Int> = (0..360 step 15).toList()

    enum class Metric { SHAPE, BIAS, BALANCE }

    /**
     * A tuning point. Nulls are not allowed here — the fitter always works with
     * concrete numbers; it is the SETTINGS layer that distinguishes «not set»
     * from «set to the shipped value». What each axis means, and the
     * measurement behind it: `docs/forecast-engine.md` §4.3.
     */
    data class Knobs(
        val isf: Double,
        val onsetMin: Double,
        val fullSpeedMin: Double,
        val phaseMin: Double,
        val tailMin: Double,
        /**
         * SHARE OF THE DOSE ARRIVING AFTER THE ACTIVE PHASE — the axis that
         * makes "insulin acts longer" testable, because [tailMin] alone only
         * spreads a fixed share wider and weakens the late action.
         *
         * It does NOT substitute for ISF cleanly: the area under the CDF is
         * invariant, yet the bias still moves with the share, so the engine is
         * sensitive to the PATH and not only to the total. The mechanism behind
         * that is named but NOT confirmed — see `docs/forecast-engine.md` §4.3.
         */
        val tailShare: Double = com.diapilot.core.physio.InsulinShapeV1.TAIL_SHARE,
        val emptyingKcalPerHour: Double,
        val carbSieving: Double,
        val carbSpread: Double,
        val trustRamp: Double,
        /**
         * Moves mass between the carb bases: `+0.2` makes every dish a fifth
         * more «fast». Distinct from [carbSpread], which moves the BASES apart
         * rather than the weights between them — §4.3.
         */
            val fastShift: Double = 0.0,
        /**
         * Scalar on `grams x CS`. THE AXIS THIS FITTER WAS MISSING: without it
         * the only lever that could lift a too-flat line was ISF, and the
         * walk-forward pinned ISF to its corridor floor.
         * See `docs/forecast-engine.md` §4.3.
         */
        val foodAmp: Double = 1.0,
        /**
         * HOW MUCH GLUCOSE ACTIVITY REMOVES — an axis that was missing despite
         * the mechanism already existing, and whose range ceiling comes from the
         * measurement rather than from a number in the artifact.
         * See `docs/forecast-engine.md` §4.3.
         */
        val activityDirect: Double = 0.0,
        /** Time axis of the carb triangles; >1 makes food arrive LATER. A model
         *  whose food peaks near its insulin cancels itself — §4.3. */
        val carbTimeScale: Double = 1.0,
        /** How long food keeps arriving, with its onset and peak held. The axis
         *  the search was missing: see `HybridForecastEngine.foodTailScale`. */
        val foodTailScale: Double = 1.0,
        /**
         * THE THREE POPULATION TRIANGLES, one axis per landmark. They make
         * [carbSpread] and [carbTimeScale] exactly redundant, so [fitOne] locks
         * those whenever any triangle axis is free, and says so. Defaults come
         * from `CarbTrianglesV1.SHIPPED`, never duplicated here.
         * See `docs/forecast-engine.md` §4.3.
         */
        val fastDelayMin: Double = CarbTrianglesV1.SHIPPED.fastDelayMin,
        val fastPeakMin: Double = CarbTrianglesV1.SHIPPED.fastPeakMin,
        val fastEndMin: Double = CarbTrianglesV1.SHIPPED.fastEndMin,
        val medDelayMin: Double = CarbTrianglesV1.SHIPPED.mediumDelayMin,
        val medPeakMin: Double = CarbTrianglesV1.SHIPPED.mediumPeakMin,
        val medEndMin: Double = CarbTrianglesV1.SHIPPED.mediumEndMin,
        val slowDelayMin: Double = CarbTrianglesV1.SHIPPED.slowDelayMin,
        val slowPeakMin: Double = CarbTrianglesV1.SHIPPED.slowPeakMin,
        val slowEndMin: Double = CarbTrianglesV1.SHIPPED.slowEndMin,
        /**
         * How strongly each carb type yields to the gastric terms — fat,
         * protein and fibre. Equal since M-120, and the per-type field stays so
         * they can diverge again without a migration. Fittable because it moves
         * the curve on real meals and no other axis reaches it — self-limiting
         * on a lean dish. See `docs/forecast-engine.md` §4.3.
         */
        val fastMacroShare: Double = CarbTrianglesV1.SHIPPED.fastMacroShare,
        val medMacroShare: Double = CarbTrianglesV1.SHIPPED.mediumMacroShare,
        val slowMacroShare: Double = CarbTrianglesV1.SHIPPED.slowMacroShare,
    )

    /** One scoreable stretch: an anchor, what was eaten and injected, and what
     *  glucose actually did on [gridMin]. `null` in [real] means «no reading». */
    data class Episode(
        val startMs: Long,
        val g0: Double,
        val foods: List<HybridFoodEvent>,
        val boluses: List<HybridBolusEvent>,
        val basals: List<HybridBasalEvent> = emptyList(),
        val real: List<Double?>,
        val label: String = "",
        /**
         * Movement behind the anchor, from
         * [com.diapilot.core.analysis.ActivityExposureV1]. Defaults to zero, so
         * a caller that leaves it is declaring «this episode had no movement»
         * — forecast-engine.md §4.6.
         */
        val activityExposure: Double = 0.0,
        /**
         * WHAT OPENED THIS EPISODE — food or an injection. [Kind.INSULIN] opens
         * on a dose with no food in the window and is judged by the DROP, which
         * is how the night got into a corpus that had only daytime meals.
         * See `docs/forecast-engine.md` §4.6.
         */
        val kind: Kind = Kind.MEAL,
    ) {
        enum class Kind { MEAL, INSULIN }
    }

    data class Score(val shape: Double, val bias: Double) {
        fun of(metric: Metric): Double = when (metric) {
            Metric.SHAPE -> shape
            Metric.BIAS -> abs(bias)
            Metric.BALANCE -> shape + abs(bias)
        }
    }

    data class Fit(val knobs: Knobs, val score: Score, val probes: Int)

    /** Per-episode fits plus the median of each knob across them. The MEDIAN is
     *  the answer, and the per-episode list is the evidence — a median with no
     *  spread beside it hides whether the episodes agreed, and they do not. */
    data class Batch(val fits: List<Pair<Episode, Fit>>, val median: Knobs)

    /**
     * The settled amplitude: seven tagged corrections, median 2.50 —
     * «timing from every dose, amplitude from corrections». Lives here so the
     * bench and the phone bound ISF by the same number.
     */
    const val MEASURED_ISF_MMOL_PER_UNIT = 2.50

    /** Names accepted by `locked`; also the search order. */
    val AXES = listOf(
        "isf", "onset", "fullSpeed", "phase", "tail", "tailShare",
        "kcal", "sieve", "spread", "ramp",
        "fastShift", "foodAmp", "carbTimeScale", "foodTail", "activityDirect",
        "fastDelay", "fastPeak", "fastEnd",
        "medDelay", "medPeak", "medEnd",
        "slowDelay", "slowPeak", "slowEnd",
        "fastMacro", "medMacro", "slowMacro",
    )

    /** The twelve triangle axes, named once so nothing re-spells them. */
    val TRIANGLE_AXES = setOf(
        "fastDelay", "fastPeak", "fastEnd",
        "medDelay", "medPeak", "medEnd",
        "slowDelay", "slowPeak", "slowEnd",
        "fastMacro", "medMacro", "slowMacro",
    )

    /**
     * THE SEARCH CORRIDOR, AND WHY IT IS NOT ONE SIZE.
     *
     * By the user's ruling: "timing is a safety question, let auto-fit work
     * within these timing bounds... the rest can be tuned, but should still
     * come out physiologically plausible. And ISF matters most."
     *
     * That is three different kinds of knob and they deserve three different
     * freedoms, which the flat [RANGES] table did not give them:
     *
     *  - **insulin timing is MEASURED**, segment by segment, from real doses
     *    (`SegmentLandmarkReaderV1`). A fit is allowed to nudge it, never to
     *    invent a different pharmacology — and it tried: on the wide table the
     *    search reached 100-120 min for full speed, which real top-up
     *    practice refutes outright (M-82).
     *  - **the queue and the carb spread are PHYSIOLOGY WITH SLACK.** They may
     *    move, but not to values no stomach has: a sieve of 0 means no
     *    carbohydrate ever appears, a spread of 0 means fast and slow sugars are
     *    the same substance.
     *  - **ISF is the VARIABLE.** It genuinely drifts with activity, site and
     *    the day, the app exists partly to catch that drift, and applying a
     *    computed value has already shown a glucose improvement. It stays wide.
     *
     * Pass `bounds` to override any axis; anything absent keeps [RANGES].
     *
     * ⚠ THE TAIL BAND IS HELD INSIDE [RANGES]' OWN `tail` ENTRY, which is the
     * one place this function does not simply trust its centre. The corridor
     * returned here OVERRIDES the flat table in [fitOne] (`bounds[axis] ?:
     * RANGES`), so a centre below the instrument's floor used to hand the
     * search a legal region entirely under it — a ±20% band around a measured
     * 240 already opens at 192. Every such candidate was then thrown away by
     * [tuned]'s own domain check, silently, one candidate at a time, while the
     * corridor the card printed claimed the fit was free down there. Clamping
     * the band instead means the fit REFUSES to go under the floor instead of
     * proposing and discarding, and the printed corridor is the region actually
     * searched. It does not lift the user's own number: a hand-entered end of
     * action is locked out of the fit entirely (`PhysioAutoFitRuntime`), so
     * this floor only ever binds an instrument feeding the model on its own.
     */
    fun boundsAround(
        measured: InsulinShapeLandmarksV1,
        fraction: Double = 0.20,
        minHalfWidthMin: Double = 5.0,
    ): Map<String, Pair<Double, Double>> {
        fun band(v: Double): Pair<Double, Double> {
            val half = maxOf(v * fraction, minHalfWidthMin)
            return (v - half) to (v + half)
        }
        val phase = (measured.plateauEndMin ?: (measured.peakMin + 30.0)) - measured.peakMin
        val (tailFloor, tailCeiling) = rangeOf("tail")
        val (tailLow, tailHigh) = band(measured.tailMin)
        return mapOf(
            "onset" to band(measured.onsetMin),
            "fullSpeed" to band(measured.peakMin),
            "phase" to band(phase.coerceAtLeast(1.0)),
            // A band entirely below the floor collapses ONTO the floor rather
            // than inverting: low == high == 240 is a corridor of one value,
            // which is the honest reading of "the ruler cannot support anything
            // shorter, and nobody has stated otherwise".
            "tail" to (
                tailLow.coerceIn(tailFloor, tailCeiling) to
                    tailHigh.coerceIn(tailFloor, tailCeiling)
                ),
        )
    }

    /** Values no stomach has. Deliberately not the widest table that still
     *  parses — a fit that lands outside these is telling us the LOSS is wrong,
     *  not that the user's physiology is unusual. */
    val PLAUSIBLE_PHYSIOLOGY: Map<String, Pair<Double, Double>> = mapOf(
        // GASTRIC EMPTYING, BOUNDED WHERE IT STOPS MATTERING — measured directly.
        //
        // The upper bound used to be 400, and above roughly 180 the shape does
        // not move at all: 180, 250, 300 and 400 all return the same score to
        // four decimals. That is not a defect. It is the queue's own invariant —
        // «a queue may bound arrival, never manufacture it», written as
        // `min(desiredCdf, delivered/carbBasis)` — so once the pipe is wide
        // enough the triangle governs and the stomach rate becomes irrelevant.
        //
        // (It is NOT the 30 g/h cap in `caloricCarbRateGPerHourV1`. That lives
        // in the legacy gram branch and never runs on this arm. The first
        // version of this note said otherwise and was wrong.)
        //
        // Leaving the bound at 400 let the search wander across a flat plateau
        // and REPORT a value from it: the median of the daily optima settled at
        // 189, which reads like a measurement and is the middle of a shelf.
        // Below the release point the knob is real and strong — 60 scores 4.837
        // against 180's 2.949 — so the lower bound stays.
        // THE TRIANGLES, bounded by what a stomach and a gut can do rather than
        // by what parses. Fast carbohydrate that peaks at two hours is not fast;
        // slow carbohydrate done in forty minutes is not slow. The bands overlap
        // on purpose — the ORDER of the three types is not something this fit is
        // entitled to assume, only something it may report.
        "fastDelay" to (0.0 to 25.0),
        "fastPeak" to (10.0 to 60.0),
        "fastEnd" to (40.0 to 150.0),
        "medDelay" to (0.0 to 35.0),
        "medPeak" to (25.0 to 110.0),
        "medEnd" to (90.0 to 300.0),
        "slowDelay" to (0.0 to 60.0),
        "slowPeak" to (45.0 to 200.0),
        "slowEnd" to (150.0 to 480.0),
        // Above ~1.5 the gastric prior would delay a fatty meal by more than the
        // triangle itself lasts, which is not a stomach, it is a fit escaping.
        "fastMacro" to (0.0 to 1.5),
        "medMacro" to (0.0 to 1.5),
        "slowMacro" to (0.0 to 1.5),
        "kcal" to (80.0 to 200.0),
        // 0.0 would mean the carbohydrate never appears at all.
        "sieve" to (0.35 to 1.0),
        // 0.0 would make fast and slow sugars the same substance. Measured
        // small (M-83: whole-meal fast moves 2-hour delivery by 1 point) but
        // not zero.
        "spread" to (0.5 to 1.5),
        "fastShift" to (-0.3 to 0.3),
        // A meal cannot deliver half or triple its carbohydrate. Wide enough
        // that a fit landing INSIDE is informative and one landing ON the wall
        // says the deficit is not amplitude — which is the whole point of
        // giving it a wall rather than a free rein.
        "foodAmp" to (0.5 to 2.5),
        // 0.5 = twice as fast as the textbook, 2.0 = twice as slow.
        "carbTimeScale" to (0.5 to 2.0),
        // A meal can plausibly finish arriving twice as late as the textbook
        // says, or half again as early; beyond that it is not a tail any more.
        "foodTail" to (0.6 to 2.5),
    )

    private val RANGES = mapOf(
        "isf" to (1.0 to 5.0),
        "onset" to (0.0 to 45.0),
        "fullSpeed" to (20.0 to 140.0),
        "phase" to (0.0 to 90.0),
        // Inside the artifact's own domain floor (`insulinTailMinRange`), not
        // below it: a corridor that started at 90 let the fit propose an end of
        // action the model then had to clamp, so the proposal and the installed
        // value disagreed with nobody told. The ceiling drops to 480 for the
        // same reason the floor rose — above eight hours the measurement window
        // has no say at all, and the fit would be reading the loss, not insulin.
        "tail" to (240.0 to 480.0),
        // This axis was added together with the field: without a range `fitOne`
        // trips on NoSuchElement, because it iterates AXES and requires
        // bounds for each. The lower bound is not zero — a curve with no tail at
        // all is not pharmacodynamics any more; the upper bound of 0.6 leaves
        // the active phase more than half the dose.
        "tailShare" to (0.05 to 0.60),
        // The measurement gives 5.63; the ceiling is twice that, the floor is
        // zero, i.e. "as shipped now". No negative values on purpose: "walking
        // raises glucose" is not the hypothesis this measurement tests, and
        // allowing it would give the fit an extra direction with no physiology
        // behind it.
        "activityDirect" to (0.0 to 12.0),
        "kcal" to (60.0 to 500.0),
        // 0.0 is degenerate (no carbohydrate ever appears) but it is what the
        // bench searched all week, and every number in the registry came out of
        // that range. Narrowing it here would silently move the incumbent while
        // claiming a pure refactor. The SETTINGS layer clamps to 0.05.
        "sieve" to (0.0 to 1.0),
        "spread" to (0.0 to 1.5),
        "ramp" to (0.0 to 1.0),
        "fastShift" to (-0.5 to 0.5),
        "foodAmp" to (0.3 to 3.0),
        "carbTimeScale" to (0.3 to 3.0),
        "foodTail" to (0.4 to 3.0),
        // The unconstrained fallback for the triangles — wider than
        // [PLAUSIBLE_PHYSIOLOGY], which is what any caller that cares should
        // pass. Present because [fitOne] reads this table by `getValue` and a
        // missing key throws rather than degrading; the axis list and this table
        // must stay the same length.
        "fastDelay" to (0.0 to 40.0),
        "fastPeak" to (5.0 to 90.0),
        "fastEnd" to (20.0 to 240.0),
        "medDelay" to (0.0 to 60.0),
        "medPeak" to (15.0 to 160.0),
        "medEnd" to (60.0 to 420.0),
        "slowDelay" to (0.0 to 90.0),
        "slowPeak" to (30.0 to 260.0),
        "slowEnd" to (100.0 to 600.0),
        "fastMacro" to (0.0 to 2.0),
        "medMacro" to (0.0 to 2.0),
        "slowMacro" to (0.0 to 2.0),
    )

    /** Public form of [with], so a stand can sweep one axis and read the
     *  LEVERAGE it has — how far the curve can move while every other knob is
     *  held. Leverage and drift are different questions and were conflated all
     *  week; a knob with no leverage cannot be the cause of anything, however
     *  interesting its fitted value looks. */
    fun withAxis(k: Knobs, axis: String, v: Double): Knobs = k.with(axis, v)

    /** Reading one axis by name, the counterpart of [withAxis]. A stand that
     *  reports DRIFT has to read the fitted value back out, and open-coding a
     *  25-branch `when` in the stand is how it silently stops matching the
     *  setter (discipline #7 in miniature). */
    fun axisValue(k: Knobs, axis: String): Double = k.at(axis)

    /** Range of one axis by name — so the axis-coverage test can move each
     *  one inside its own bounds without knowing them in advance. */
    fun rangeOf(axis: String): Pair<Double, Double> = RANGES.getValue(axis)

    private fun Knobs.with(axis: String, v: Double): Knobs = when (axis) {
        "isf" -> copy(isf = v)
        "onset" -> copy(onsetMin = v)
        "fullSpeed" -> copy(fullSpeedMin = v)
        "phase" -> copy(phaseMin = v)
        "tail" -> copy(tailMin = v)
        "kcal" -> copy(emptyingKcalPerHour = v)
        "sieve" -> copy(carbSieving = v)
        "spread" -> copy(carbSpread = v)
        "ramp" -> copy(trustRamp = v)
        "foodAmp" -> copy(foodAmp = v)
        "carbTimeScale" -> copy(carbTimeScale = v)
        "foodTail" -> copy(foodTailScale = v)
        "tailShare" -> copy(tailShare = v)
        "activityDirect" -> copy(activityDirect = v)
        "fastDelay" -> copy(fastDelayMin = v)
        "fastPeak" -> copy(fastPeakMin = v)
        "fastEnd" -> copy(fastEndMin = v)
        "medDelay" -> copy(medDelayMin = v)
        "medPeak" -> copy(medPeakMin = v)
        "medEnd" -> copy(medEndMin = v)
        "slowDelay" -> copy(slowDelayMin = v)
        "slowPeak" -> copy(slowPeakMin = v)
        "slowEnd" -> copy(slowEndMin = v)
        "fastMacro" -> copy(fastMacroShare = v)
        "medMacro" -> copy(medMacroShare = v)
        "slowMacro" -> copy(slowMacroShare = v)
        else -> copy(fastShift = v)
    }

    private fun Knobs.at(axis: String): Double = when (axis) {
        "isf" -> isf
        "onset" -> onsetMin
        "fullSpeed" -> fullSpeedMin
        "phase" -> phaseMin
        "tail" -> tailMin
        "kcal" -> emptyingKcalPerHour
        "sieve" -> carbSieving
        "spread" -> carbSpread
        "ramp" -> trustRamp
        "foodAmp" -> foodAmp
        "carbTimeScale" -> carbTimeScale
        "foodTail" -> foodTailScale
        "tailShare" -> tailShare
        "activityDirect" -> activityDirect
        "fastDelay" -> fastDelayMin
        "fastPeak" -> fastPeakMin
        "fastEnd" -> fastEndMin
        "medDelay" -> medDelayMin
        "medPeak" -> medPeakMin
        "medEnd" -> medEndMin
        "slowDelay" -> slowDelayMin
        "slowPeak" -> slowPeakMin
        "slowEnd" -> slowEndMin
        "fastMacro" -> fastMacroShare
        "medMacro" -> medMacroShare
        "slowMacro" -> slowMacroShare
        else -> fastShift
    }

    /**
     * Builds the tuned model. Shared with the settings screen so a knob set
     * found here draws the same curve when applied there — a fitter and an
     * applier that synthesize differently is the whole failure this file exists
     * to avoid.
     *
     * Returns null when the four landmarks do not form a valid shape. The
     * caller must then SKIP the candidate, never fall back to a neighbour: a
     * silent substitution once made a refused warp print the previous arm's
     * numbers under the new arm's name (M-59).
     */
    fun tuned(base: HybridPersonModel, k: Knobs): HybridPersonModel? {
        val want = InsulinShapeLandmarksV1(
            k.onsetMin,
            k.fullSpeedMin.coerceAtLeast(k.onsetMin + 5),
            k.fullSpeedMin.coerceAtLeast(k.onsetMin + 5) + k.phaseMin,
            k.tailMin.coerceAtLeast(k.fullSpeedMin + k.phaseMin + 15),
        )
        if (!want.ordered) return null
        val (lm, moved) = InsulinShapeV1.coerceIntoDomain(want)
        if (moved.isNotEmpty()) return null
        // TAIL_SHARE pinned: the tail carries a fixed fifth, so moving the end
        // of action no longer weakens the peak (M-79). Without it the tail is a
        // third twin of ISF and every fit reaches for it.
        val knots = InsulinShapeV1.synthesize(lm, k.tailShare) ?: return null
        // 1.0 keeps the model's own weight; 0.0 lifts it to 1.0 (no shrinkage).
        fun mix(w: Double) = 1.0 - (1.0 - w) * k.trustRamp
        return base.copy(
            insulin = base.insulin.copy(
                isf = k.isf,
                onsetMin = lm.onsetMin,
                peakMin = lm.peakMin,
                shortDurationMin = lm.tailMin,
                tailDurationMin = lm.tailMin,
                actionCdfKnots = knots,
            ),
            food = base.food.copy(
                emptyingKcalPerHourOverride = k.emptyingKcalPerHour,
                carbSievingOverride = k.carbSieving,
                carbSpreadOverride = k.carbSpread,
                // NEUTRAL MEANS ABSENT, not «1.0 written down».
                //
                // `PhysioTuningTest` pins that the settings applier and this
                // fitter build the SAME model — the whole reason a knob set
                // found on one screen draws the same curve on the other. Writing
                // an explicit 1.0 where the applier leaves null broke that, and
                // the test said so. It also matters beyond the test: `null` is
                // the contract for «exactly what ships», so a fit that moved
                // nothing must leave the model object untouched rather than
                // produce a look-alike that compares unequal.
                carbTimeScaleOverride = k.carbTimeScale.takeIf { it != 1.0 },
                foodAmpOverride = k.foodAmp.takeIf { it != 1.0 },
                foodTailScaleOverride = k.foodTailScale.takeIf { it != 1.0 },
            ),
            // THE ONLY PLACE THIS AXIS ENTERS THE MODEL. `personModelAt`
            // zeroes this coefficient on the device path, so a fit that does
            // not set it here will measure zero and call that "activity does
            // not help".
            joint = base.joint.copy(
                coefficients = base.joint.coefficients.copy(
                    activityDirect = k.activityDirect,
                ),
            ),
            trend = base.trend.copy(
                weight60 = mix(base.trend.weight60),
                weight120 = mix(base.trend.weight120),
                weight180 = mix(base.trend.weight180),
            ),
        )
    }

    // A FRESH ENGINE PER CANDIDATE. `clusterMassCache` is keyed on
    // «id:startMs:grams:kcal» and carries NO SHAPE, so reusing an engine across
    // two knob sets hands the second one the first one's decomposition. That is
    // M-42, and it has been walked into twice — once returning thirteen
    // episodes identical to the hundredth, which nearly shipped as a real zero.
    /**
     * Terms that have a handle but no AXIS — swept by hand, never fitted.
     *
     * All six were measured "no effect" at one point and that verdict is now
     * void: it was taken while `foodAmp` was fitted invisibly and while insulin
     * timing and the ramp were free, so any one of them could be absorbed
     * elsewhere. The same sweep also cleared the carb triangles, which turned out
     * to move median shape from 2.487 to 1.293 once the corset held the rest to
     * account. Defaults are exactly what ships.
     */
    data class SweepKnobs(
        val formStretchScale: Double = 1.0,
        val formDelayScale: Double = 1.0,
        val fiberScale: Double = 1.0,
        val peakGastricShare: Double = 0.35,
        val macroShareScale: Double = 1.0,
        val foodTailScale: Double = 1.0,
    )

    fun curve(
        base: HybridPersonModel,
        k: Knobs,
        e: Episode,
        gridMin: List<Int>,
        sweep: SweepKnobs = SweepKnobs(),
    ): List<Double>? {
        val model = tuned(base, k) ?: return null
        val pts = physioForecastEngine(
            model,
            appearance = CarbAppearancePolicyV1(k.emptyingKcalPerHour, k.carbSieving),
            carbSpread = k.carbSpread,
            carbTimeScale = k.carbTimeScale,
            foodAmpScale = k.foodAmp,
            foodTailScale = k.foodTailScale * sweep.foodTailScale,
            carbTriangles = k.triangles(),
            formStretchScale = sweep.formStretchScale,
            formDelayScale = sweep.formDelayScale,
            fiberScale = sweep.fiberScale,
            peakGastricShare = sweep.peakGastricShare,
            macroShareScale = sweep.macroShareScale,
        ).forecast(
            HybridForecastState(
                nowMs = e.startMs,
                glucoseHistory = listOf(HybridGlucosePoint(e.startMs - MINUTE_MS, e.g0)),
                foodHistory = e.foods.map { shiftFast(it, k.fastShift) },
                bolusHistory = e.boluses,
                basalHistory = e.basals,
                // WAS ALWAYS ZERO BEFORE THIS FIELD EXISTED. Production passes a real
                // exposure; this state omitted the field, so every fit and every
                // walk-forward scored a body that never moved.
                activityExposure = e.activityExposure,
            ),
        ).points.associate { it.minutes to it.baseline }
        return gridMin.map { pts[it] ?: return null }
    }

    /**
     * The knobs as the engine's triangle object, with the ONE ordering the shape
     * cannot do without: delay < peak < end. Coordinate descent moves one axis
     * at a time and would otherwise propose a peak before its own onset — a
     * shape the engine then coerces silently, so the fit would report a number
     * it never actually ran (the same class as M-59).
     */
    fun Knobs.triangles(): CarbTrianglesV1 {
        fun ord(d: Double, p: Double, e: Double): Triple<Double, Double, Double> {
            val pk = maxOf(p, d + 5.0)
            return Triple(d, pk, maxOf(e, pk + 10.0))
        }
        val f = ord(fastDelayMin, fastPeakMin, fastEndMin)
        val m = ord(medDelayMin, medPeakMin, medEndMin)
        val sl = ord(slowDelayMin, slowPeakMin, slowEndMin)
        return CarbTrianglesV1(
            fastDelayMin = f.first, fastPeakMin = f.second, fastEndMin = f.third,
            fastMacroShare = fastMacroShare,
            mediumDelayMin = m.first, mediumPeakMin = m.second, mediumEndMin = m.third,
            mediumMacroShare = medMacroShare,
            slowDelayMin = sl.first, slowPeakMin = sl.second, slowEndMin = sl.third,
            slowMacroShare = slowMacroShare,
        )
    }

    /** Re-weights the carb bases of one event; identity at shift 0. */
    fun shiftFast(f: HybridFoodEvent, shift: Double): HybridFoodEvent {
        if (shift == 0.0) return f
        val k = f.kineticFeatures ?: return f
        val fast = (k.fastFraction + shift).coerceIn(0.0, 1.0)
        val rest = 1.0 - fast
        val oldRest = (k.mediumFraction + k.slowFraction).coerceAtLeast(1e-9)
        return f.copy(
            kineticFeatures = k.copy(
                fastFraction = fast,
                mediumFraction = rest * (k.mediumFraction / oldRest),
                slowFraction = rest * (k.slowFraction / oldRest),
            ),
        )
    }

    fun score(
        base: HybridPersonModel,
        k: Knobs,
        e: Episode,
        gridMin: List<Int>,
        minPoints: Int = 8,
        sweep: SweepKnobs = SweepKnobs(),
    ): Score? {
        val c = curve(base, k, e, gridMin, sweep) ?: return null
        val d = gridMin.indices.mapNotNull { i ->
            val r = e.real.getOrNull(i) ?: return@mapNotNull null
            if (gridMin[i] <= 0) null else r - c[i]
        }
        if (d.size < minPoints) return null
        val mean = d.average()
        return Score(sqrt(d.sumOf { (it - mean) * (it - mean) } / d.size), mean)
    }

    /**
     * Coordinate descent, coarse to fine: four passes whose span narrows by 3x
     * each time, re-probing every axis around the CURRENT winner. Coarse first
     * because the loss has local structure the fine grid would never escape;
     * fine after because the coarse grid answers only to its own resolution —
     * a value that lands exactly on a coarse probe point is a value determined
     * to about half a coarse step, and the caller should say so.
     */
    fun fitOne(
        base: HybridPersonModel,
        episode: Episode,
        start: Knobs,
        metric: Metric,
        gridMin: List<Int>,
        locked: Set<String> = emptySet(),
        passes: List<Int> = listOf(7, 5, 5, 3),
        minPoints: Int = 8,
        bounds: Map<String, Pair<Double, Double>> = emptyMap(),
        /**
         * Extra sweeps at the FINEST span, repeated until one of them fails to
         * improve. Zero reproduces every result recorded before this option existed.
         *
         * WHY IT IS NEEDED, found by hand: after the search finishes,
         * turning knobs manually still improves the curve. The cause is not
         * mysterious — [passes] is a FIXED schedule of four sweeps whose span
         * shrinks by 3^pass whether or not the previous sweep found anything,
         * so an axis moved late in the last sweep is never re-tried against the
         * axes that were set before it. Coordinate descent cannot follow a
         * diagonal ridge either, and this does not fix that; it only stops the
         * search from quitting while it is still descending.
         *
         * Deterministic on purpose — no random restarts. Two runs of the same
         * fit returning different knobs would make every recorded number
         * unreproducible, which costs more than the ridge does.
         */
        polishRounds: Int = 0,
    ): Fit? {
        // IDENTIFIABILITY, ENFORCED RATHER THAN DOCUMENTED. `spread` is fast and
        // slow measured against medium; `carbTimeScale` is a common multiplier
        // on all three. Both are exactly reachable by moving the nine triangle
        // landmarks, so with any of those free the loss has two flat directions
        // and the search will report a confident value picked off a ridge — the
        // same failure mode as the kcal plateau, where a bound of 400 let the
        // median settle at 189 and read like a measurement.
        //
        // Locking is silent to the caller but not to the RESULT: the returned
        // knobs carry whatever those two were on entry, so a caller that wanted
        // them fitted sees them unchanged rather than invented.
        val trianglesFree = TRIANGLE_AXES.any { it !in locked }
        val effectiveLocked =
            if (trianglesFree) locked + setOf("spread", "carbTimeScale") else locked
        val axes = AXES.filter { it !in effectiveLocked }
        // THE START MUST BE INSIDE THE CORRIDOR, or the promise is not kept.
        //
        // The search clamps its CANDIDATES into the bounds but began from
        // whatever is applied today — and if nothing inside beat that starting
        // point, it was reported unchanged. This was caught by a mismatch: the
        // card claimed values were "fitted ONLY inside the ±20% corridor around
        // the landmarks" above a fitted phase that was outside that corridor.
        //
        // A LOCKED axis is left alone even when it sits outside: the lock is an
        // explicit instruction and outranks the corridor, which exists to stop
        // the SEARCH from inventing pharmacology, not to overrule the user.
        var cur = axes.fold(start) { k, axis ->
            val range = bounds[axis] ?: return@fold k
            k.with(axis, k.at(axis).coerceIn(range.first, range.second))
        }
        // Coordinate descent re-probes each axis CENTRE on every pass, and the
        // passes overlap near the optimum, so the same knob set recurs many
        // times in one search. Memoising on the knobs themselves — structural
        // equality, no rounding — turns those into free lookups. The bench used
        // to round to slider resolution before the lookup, which let two
        // genuinely different candidates share one answer; exact is both faster
        // to reason about and strictly more correct.
        val seen = HashMap<Knobs, Score?>()
        fun sc(k: Knobs): Score? = seen.getOrPut(k) { score(base, k, episode, gridMin, minPoints) }
        var best = sc(cur) ?: return null
        var probes = 0
        if (axes.isEmpty()) return Fit(cur, best, 0)
        passes.forEachIndexed { pass, steps ->
            axes.forEach { axis ->
                val range = bounds[axis] ?: RANGES.getValue(axis)
                val span = (range.second - range.first) / 3.0.pow(pass.toDouble())
                val centre = cur.at(axis)
                for (i in 0 until steps) {
                    val v = (centre - span / 2 + span * i / (steps - 1.0))
                        .coerceIn(range.first, range.second)
                    val cand = cur.with(axis, v)
                    val s = sc(cand) ?: continue
                    probes++
                    if (s.of(metric) < best.of(metric)) {
                        best = s
                        cur = cand
                    }
                }
            }
        }
        // Keep sweeping at the finest span while it still pays. `seen` memoises
        // on the knobs, so a sweep that proposes nothing new costs nothing.
        var round = 0
        while (round < polishRounds) {
            val before = best.of(metric)
            axes.forEach { axis ->
                val range = bounds[axis] ?: RANGES.getValue(axis)
                val span = (range.second - range.first) / 3.0.pow(passes.size.toDouble())
                val centre = cur.at(axis)
                for (i in 0 until 5) {
                    val v = (centre - span / 2 + span * i / 4.0)
                        .coerceIn(range.first, range.second)
                    val cand = cur.with(axis, v)
                    val s2 = sc(cand) ?: continue
                    probes++
                    if (s2.of(metric) < best.of(metric)) { best = s2; cur = cand }
                }
            }
            if (before - best.of(metric) < 1e-4) break
            round++
        }
        return Fit(cur, best, probes)
    }

    private fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return Double.NaN
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * The median of an already-fitted set.
     *
     * Public and separate from [fitBatch] so a caller that wants to run the
     * episodes CONCURRENTLY — the phone does, because ten sequential fits is a
     * three-minute wait — can still assemble the answer with this function
     * rather than re-spelling the median. A median assembled by a second
     * implementation is a median of something else, and nothing on screen would
     * say so.
     */
    fun medianOf(fits: List<Fit>): Knobs? {
        if (fits.isEmpty()) return null
        fun m(f: (Knobs) -> Double) = median(fits.map { f(it.knobs) })
        return Knobs(
            isf = m { it.isf },
            onsetMin = m { it.onsetMin },
            fullSpeedMin = m { it.fullSpeedMin },
            phaseMin = m { it.phaseMin },
            tailMin = m { it.tailMin },
            emptyingKcalPerHour = m { it.emptyingKcalPerHour },
            carbSieving = m { it.carbSieving },
            carbSpread = m { it.carbSpread },
            trustRamp = m { it.trustRamp },
            fastShift = m { it.fastShift },
            foodAmp = m { it.foodAmp },
            carbTimeScale = m { it.carbTimeScale },
            foodTailScale = m { it.foodTailScale },
            // EVERY AXIS OR THE MEDIAN IS OF SOMETHING ELSE. This function
            // rebuilds `Knobs` field by field, so an axis added to the data
            // class and forgotten here does not fail to compile — it silently
            // falls back to the SHIPPED default. The batch would then fit twelve
            // triangle axes per episode and hand back the population shape as
            // «the median», with nothing on any screen to say so.
            fastDelayMin = m { it.fastDelayMin }, fastPeakMin = m { it.fastPeakMin },
            fastEndMin = m { it.fastEndMin }, fastMacroShare = m { it.fastMacroShare },
            medDelayMin = m { it.medDelayMin }, medPeakMin = m { it.medPeakMin },
            medEndMin = m { it.medEndMin }, medMacroShare = m { it.medMacroShare },
            slowDelayMin = m { it.slowDelayMin }, slowPeakMin = m { it.slowPeakMin },
            slowEndMin = m { it.slowEndMin }, slowMacroShare = m { it.slowMacroShare },
            // BOTH FORGOTTEN WHEN ADDED — the warning above fired exactly as
            // written. `tailShare` and `activityDirect` were added to `Knobs`,
            // to `AXES` and to `tuned`, but not here, so fitting moved them
            // while the SCORE kept using the shipped default.
            //
            // Cost: the "tail share free" arm compared a model whose share was
            // always 0.20 against itself — the conclusion "the share alone is
            // harmful" is retracted. Both activity arms from the same period are
            // invalid for the same reason.
            // `PhysioAutoFitAxisCoverageTest` now catches this before the run.
            tailShare = m { it.tailShare },
            activityDirect = m { it.activityDirect },
        )
    }

    /**
     * Fits each episode SEPARATELY and returns the median of each knob.
     *
     * Deliberately not a joint fit. A joint fit returns one number and hides
     * whether the episodes agreed; this returns the spread, and the spread is
     * the finding — the episodes do not agree, and ISF in particular wanders
     * by a factor of 1.75 across them (M-50, M-60, M-73).
     */
    fun fitBatch(
        base: HybridPersonModel,
        episodes: List<Episode>,
        start: Knobs,
        metric: Metric,
        gridMin: List<Int>,
        locked: Set<String> = emptySet(),
        minPoints: Int = 8,
        bounds: Map<String, Pair<Double, Double>> = emptyMap(),
    ): Batch {
        val fits = episodes.mapNotNull { e ->
            fitOne(base, e, start, metric, gridMin, locked, minPoints = minPoints, bounds = bounds)
                ?.let { e to it }
        }
        return Batch(fits, medianOf(fits.map { it.second }) ?: start)
    }
}
