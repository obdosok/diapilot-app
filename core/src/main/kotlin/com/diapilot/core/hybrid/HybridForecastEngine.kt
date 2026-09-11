package com.diapilot.core.hybrid

import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.analysis.FoodPhysicalFormV2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

data class HybridInsulinLandmarks(
    val onsetMin: Double,
    val ratePeakMin: Double,
    val effectEndMin: Double
)

/**
 * Pure Kotlin port of the v11 causal runtime.
 *
 * It does not read a database, clock, preferences, or live glucose after the
 * supplied anchor. That makes blind replay and Python/Kotlin parity testable.
 */
class HybridForecastEngine(
    private val model: HybridPersonModel,
    private val macroTiming: MacroTimingParamsV1 = MacroTimingParamsV1(),
    /**
     * HOW CARBOHYDRATE LEAVES THE STOMACH — defaulted from the ARM above, not
     * from a constant, so the two cannot disagree.
     *
     * This used to be two loose parameters (`emptyingKcalPerHour`, then
     * `carbSieving`), each defaulting to a value rather than to the arm. That
     * shape is what let the caloric queue reach the forecast while six other
     * physio consumers kept the gram queue, and it needed a factory plus a
     * parity test to hold the line. Now forgetting the argument yields the
     * arm's own physiology, and the only way to get the other one is to ask for
     * it by name — which the studies do, deliberately, when sweeping it.
     */
    private val appearance: CarbAppearancePolicyV1 = CarbAppearancePolicyV1.PHYSIO_SHIPPED,
    /**
     * Keep the pre-queue fat/protein priors in the shape's DELAY and TAIL.
     *
     * KEPT ON, and that is the measured decision, not inertia.
     *
     * Dropping these priors does improve the trajectory (60-minute bias +1.52
     * -> +1.34 mmol over 157 meals), but `FoodDynamicsPhysioV1Test` caught what
     * the aggregate cannot see: without the fat tail the queue's throttle turns
     * delivery into a long flat ramp, and the argmax of the rate falls at the
     * START of it. A fatty meal then peaks at 27 min against a lean one at 58 —
     * fatty food peaking EARLIER than lean, which is worse than the double
     * count it was meant to fix, and it is the landmark the card shows the user.
     *
     * So the knob stays, off, until the peak landmark is derived from something
     * that a throttle cannot invert (the median-arrival crossing already exists
     * for exactly this reason — see food-model.md §4).
     *
     * The historical note on keying, kept because it cost a test run: keying it on
     * `appearance.caloric` was the first attempt and `CarbSievingTest` refused
     * it within the hour: sieving 1.0 is still a caloric queue, so the priors
     * survived there while the gram queue kept them too, and the pinned
     * identity «sieving 1.0 == gram queue» broke. The physiology does not
     * depend on which arithmetic meters the stomach, so neither does this.
     *
     * Only `physioFeatureShapes` reads it, so the v11 arm behind the alert,
     * widget and watch is untouched — it never enters this branch.
     *
     * Measured over 157 logged meals (stand `fatsweep`): dropping
     * these priors alone moves the 60-minute bias +1.52 -> +1.34 mmol; together
     * with retiring the fat->peak slope, +1.52 -> +1.05.
     */
    private val gastricMacroPrior: Boolean = true,
    /**
     * SWEEP KNOBS for the fat/protein gastric prior. 1.0 is the shipped value,
     * so a caller that omits them gets exactly the behaviour that ships.
     *
     * Why they are separate: the diagnosis they exist to test says
     * fat delays the START too much, not that it lasts too long. On a McDonald's
     * burger — 50 g carbohydrate, 35 g fat, split 92% fast — the shipped prior
     * delivers 2.79 mmol by 60 min where the same meal with fat zeroed delivers
     * 4.38, and a comparison trace rose 5.9 mmol in 69 minutes. Scaling the delay and
     * the tail together could not tell «too slow to start» from «too long to
     * finish», and those want opposite corrections.
     *
     * The knob is a SCALE rather than a replacement coefficient on purpose: at
     * 0.0 the term is exactly the `queueMetersMacros` case that already exists,
     * so the sweep's endpoints are both already-shipped behaviours and only the
     * interior is new.
     */
    private val macroGastricScale: Double = 1.0,
    private val macroTailScale: Double = 1.0,
    /**
     * SWEEP KNOB: how far apart the three carbohydrate bases stand. 1.0 ships.
     *
     * The user's claim, and it is NOT «carb type does not matter»:
     * «the difference between them is smaller than the model thinks — the
     * priors are population averages, and they are wrong». The three triangles — fast 5/25/75, medium 10/55/180, slow
     * 15/95/330 — are population constants never fitted on the user (a known open
     * gap). If reality separates the user's fast and slow carbohydrate
     * less than those numbers do, every mixture weight is amplified by a spread
     * that is too wide.
     *
     * That also explains why the WEIGHTS measured as the strongest lever
     * (M-74): a lever is powerful in proportion to how far apart its ends sit.
     * Moving weights could never distinguish «type matters» from «the priors
     * exaggerate type»; this knob separates them by collapsing the bases toward
     * the MEDIUM one without touching a single weight.
     *
     * At 1.0 nothing changes; at 0.0 all three share the medium curve and
     * carbohydrate type stops existing.
     */
    private val carbSpread: Double = 1.0,
    /**
     * HOW FAST THE USER'S CARBOHYDRATE APPEARS, relative to the population triangles.
     *
     * The three bases — fast 5/25/75, medium 10/55/180, slow 15/95/330 — are a
     * POPULATION prior and have never been measured on the user, unlike the insulin
     * landmarks, which are read from the user's own doses segment by segment. The
     * shortfall hunt (A-45) ran out of other suspects: the amplitude is whole,
     * the caloric queue is flat across a factor of ten, carb TYPE moves the
     * two-hour delivery by one point, and the fat/protein prior turned out to
     * trade fatty episodes against lean ones rather than fix either (A-46).
     * What is left is the base triangles themselves being slower than the user's gut.
     *
     * This scales their TIME AXIS — delay, peak and end together, all three
     * bases equally — so 0.8 means «the user's carbohydrate arrives a fifth sooner
     * than the textbook». It deliberately does NOT touch amplitude: how much
     * arrives is `grams x CS` and that part measures correct.
     *
     * Scaling all three by one number rather than fitting nine is the point:
     * one parameter, measurable on a corpus this small, and it cannot quietly
     * become a per-dish fudge.
     */
    private val carbTimeScale: Double = 1.0,
    /**
     * SCALES HOW MUCH GLUCOSE A MEAL DELIVERS — `grams x CS x this`.
     *
     * Added because the walk-forward stand could not ask the
     * question it was measuring. Its fit had ten axes and NONE of them touched
     * food amplitude, so the only lever that could lift a too-flat line was
     * ISF — and with ISF held in its measured corridor the search pinned to the
     * corridor FLOOR on every window. That reads as «the loss wants weak
     * insulin»; it actually means «the loss was denied the knob it needed».
     *
     * Deliberately a SCALAR on the existing amplitude, not a replacement for
     * `carbSens`: it has been measured three times on real data (F-03 opened, closed on
     * the phone corpus, re-measured; A-18 killed the size gradient), and the
     * only surviving deviation is +25% on large meals via the tail. A scalar
     * that lands near 1.0 confirms that record; one that lands at the ceiling
     * says the deficit is not amplitude at all.
     *
     * RESCUE IS EXEMPT. The corpus rule forbids learning amplitude on
     * dextrose — at hypo the liver contributes and the grams are not the whole
     * story — so a rescue inside a scored window must not be scaled by a knob
     * fitted on meals.
     */
    private val foodAmpScale: Double = 1.0,
    /**
     * A/B SWITCH FOR THE BACKGROUND FIX, so the change can be measured against
     * itself rather than asserted.
     *
     * `true` (default) steps the reversion with the trajectory. `false`
     * reproduces the behaviour shipped before this fix — the anchor level held
     * constant for the whole horizon — and exists ONLY so a stand can run both
     * arms on the same episodes. It is not a setting and must not become one:
     * the old branch is a defect, kept alive exactly as long as it takes to
     * publish the difference it makes.
     */
    private val steppedBackgroundFeedback: Boolean = true,
    /**
     * SWEEP-ONLY HANDLES ON TERMS THAT NEVER HAD ONE.
     *
     * An inventory of the physio food path found six mechanisms
     * with no knob at all, so none of them had ever been swept and none could
     * appear in any hypothesis verdict. Three of the four `MacroTimingParamsV1`
     * fields turned out to be unreachable for parsed food in the same audit —
     * measured, not argued: they returned the base score to three decimals at
     * every value including +30 min per 10 g. A term that cannot be moved
     * cannot be evidence, and a term nobody moved is not evidence either.
     *
     * These four exist to find out which of the six can move the SHAPE at all.
     * Every default is the neutral value, so nothing ships changed; a knob that
     * earns a place becomes a real parameter afterwards, and the rest stay
     * constants with a measurement behind them instead of a guess.
     *
     * `formStretchScale` scales the DEVIATION from 1.0, so 0 collapses the
     * physical-form stretch to «all foods stretch alike» and 2 doubles the
     * spread between a liquid and a solid. The others are plain multipliers.
     */
    private val formStretchScale: Double = 1.0,
    private val formDelayScale: Double = 1.0,
    private val fiberScale: Double = 1.0,
    private val peakGastricShare: Double = 0.35,
    /**
     * THE THREE BASE TRIANGLES, SEPARATELY — the last unexplored surface.
     *
     * `carbTimeScale` moves all three together, which is the right knob when
     * the question is «does the user's carbohydrate arrive sooner than the textbook».
     * It cannot answer «is the FAST basis right while the SLOW one is not», and
     * that question has never been asked: the nine numbers behind
     * fast(5/25/75), medium(10/55/180) and slow(15/95/330) have never moved
     * except uniformly.
     *
     * One scale per basis rather than nine free numbers: three parameters are
     * estimable on this corpus, nine are not, and a per-basis time dilation is
     * the same physical statement as `carbTimeScale` made three times.
     *
     * `macroShareScale` touches the other untouched term — how much of the
     * macro delay each basis receives (.45 fast, .75 medium, 1.0 slow).
     */
    private val carbTriangles: CarbTrianglesV1 = CarbTrianglesV1(),
    private val fastTimeScale: Double = 1.0,
    private val mediumTimeScale: Double = 1.0,
    private val slowTimeScale: Double = 1.0,
    private val macroShareScale: Double = 1.0,
    /**
     * HOW LONG FOOD KEEPS ARRIVING, INDEPENDENT OF WHEN IT STARTS AND PEAKS.
     *
     * Added because the fit was paying for its absence with the
     * INSULIN tail. Measured on the corpus: the model under-calls the excursion
     * by a flat 1.86-1.94 mmol at every phase/tail pair tried, and the deficit
     * sits in the 120+ band. The two knobs that could have fixed it did not:
     * `foodAmp` has the largest leverage of any axis (4.68) and the fit leaves
     * it at 1.04; `carbTimeScale` sits at 0.99. Neither is used because both
     * have the WRONG SHAPE — one lifts the whole curve, the other slides the
     * whole curve, and the early band is already the best of the three (0.539).
     *
     * So the search took the nearest thing it did have: a longer insulin tail,
     * which removes less glucose late and props the line up. That is why the
     * fitted tail lands at 156 against an observed end of action of 85-100, and
     * why the shape domain (`tail > peak + phase + 15`) then makes the user's own
     * reading literally unreachable.
     *
     * This scales the END of each base triangle only — delay and peak untouched
     * — so it can lengthen the arrival without moving its start. If the fit
     * takes it and releases the insulin tail toward the observed 90-100, the
     * compensation is confirmed and the debt is paid in the right place.
     */
    private val foodTailScale: Double = 1.0,
) {
    private val emptyingKcalPerHour: Double? get() = appearance.emptyingKcalPerHour
    private val carbSieving: Double get() = appearance.carbSieving

    // Food classification is pure for an immutable person model, but the
    // forecast/C0B code asks it again for every time-grid point.  Memoizing by
    // the original text removes thousands of regex + set-similarity passes
    // without changing a single learned coefficient or curve value.
    private data class FoodTimingKey(
        val text: String,
        val components: Map<String, Double>,
        val carbsG: Double,
        val durationMin: Double,
        val proteinG: Double?,
        val fatG: Double?,
        val kineticsIdentity: String?,
        val rescueTreatment: Boolean,
        val recipeKey: String?,
    )

    private val foodTimingCache = HashMap<FoodTimingKey, HybridFoodTiming>()
    private val clusterMemberCache = HashMap<HybridFoodEvent, MealClusterMemberV1>()

    /**
     * Memo for [physioFeatureShapes], whose result depends only on the event and
     * on this engine's own immutable scales — never on the age it is asked about.
     *
     * PROFILED, NOT GUESSED, and the profiling took three wrong turns first
     * (M-114, M-115). The twin build costs 39 s on a representative phone and 36 of
     * those are the deconvolution corpus. A stack sampler over the corpus build
     * in the phone's own configuration put `physioFeatureShapes` at 20% of
     * self-samples: `NeighbourContributionV1` tabulates each neighbour's
     * appearance over eight hours in five-minute cells — 97 calls to `foodCdf`
     * per event — and every one rebuilt the SAME mixture from scratch.
     *
     * IDENTITY, NOT EQUALITY. `HybridFoodEvent` is a wide data class carrying a
     * component map and a nested feature record, so hashing one costs more than
     * recomputing its shapes; an early version keyed on equality and measured
     * SLOWER. The callers that matter reuse the same event objects across every
     * cell they tabulate, so identity hits every time, and a distinct object
     * with equal content merely misses — a performance detail, never a
     * correctness one.
     *
     * A null result is cached as null, which is why this is not `getOrPut`:
     * `getOrPut` treats a stored null as a miss and would re-compute exactly the
     * events that take the early `return null` paths.
     *
     * MEASURED on the stand that reproduces the phone, cold factory per run:
     * 3976 ms -> 2492 ms, corpus identical row for row.
     */
    private val featureShapesCache =
        java.util.IdentityHashMap<HybridFoodEvent, List<Pair<Double, HybridShape>>?>()

    private data class ClusterMassKey(val members: List<String>, val atMs: Long)

    private val clusterMassCache = HashMap<ClusterMassKey, Map<String, Double>>()
    /*
     * Segmentation is an invariant of the immutable food list, not of a time
     * sample.  The old code rebuilt it for every food × forecast tick.  Keep
     * the cache local to one engine so it cannot survive a model change or
     * leak a later meal into another causal snapshot.
     */
    private val mealSegmentsCache = HashMap<List<HybridFoodEvent>, List<List<HybridFoodEvent>>>()

    private fun glucoseAtOrBefore(
        history: List<HybridGlucosePoint>,
        tsMs: Long,
    ): Double? = history.lastOrNull { it.tsMs <= tsMs }?.mmol

    fun insulinCdf(ageMin: Double, units: Double = 1.0): Double {
        val p = model.insulin
        if (p.actionCdfKnots.isNotEmpty()) {
            val knots = p.actionCdfKnots
            if (ageMin <= knots.first().minute) return knots.first().fraction
            if (ageMin >= knots.last().minute) return knots.last().fraction
            val rightIndex = knots.indexOfFirst { it.minute >= ageMin }
            val left = knots[rightIndex - 1]
            val right = knots[rightIndex]
            val ratio = (ageMin - left.minute) / (right.minute - left.minute)
            return (left.fraction + ratio * (right.fraction - left.fraction))
                .coerceIn(0.0, 1.0)
        }
        val doseTail = clamp(
            p.tailWeight + p.tailWeightPerUnit * (units - p.tailReferenceUnits),
            0.0,
            1.0,
        )
        val short = triangularCdf(ageMin, p.onsetMin, p.peakMin, p.shortDurationMin)
        val tail = triangularCdf(ageMin, p.onsetMin, p.peakMin, p.tailDurationMin)
        return (1.0 - doseTail) * short + doseTail * tail
    }

    private fun insulinCdf(event: HybridBolusEvent, ageMin: Double): Double {
        if (event.onsetOffsetMin == 0.0 && event.peakOffsetMin == 0.0 && event.tailOffsetMin == 0.0)
            return insulinCdf(ageMin, event.units)
        val p = model.insulin
        val a0 = (p.onsetMin + event.onsetOffsetMin).coerceIn(0.0, 60.0)
        val a1 = (p.peakMin + event.peakOffsetMin).coerceIn(a0 + 1.0, 180.0)
        val a2 = (p.tailDurationMin + event.tailOffsetMin).coerceIn(a1 + 1.0, 600.0)
        val baseAge =
            when {
                ageMin <= a0 -> if (a0 <= 0.0) ageMin else ageMin * p.onsetMin / a0
                ageMin <= a1 -> p.onsetMin + (ageMin - a0) * (p.peakMin - p.onsetMin) / (a1 - a0)
                else -> p.peakMin + (ageMin - a1) * (p.tailDurationMin - p.peakMin) / (a2 - a1)
            }
        return insulinCdf(baseAge, event.units)
    }

    /**
     * Fraction of a bolus that has not acted yet according to the same
     * personalized, dose-dependent kernel used by the v11 forecast.
     */
    fun insulinRemainingFraction(ageMin: Double, units: Double = 1.0): Double =
        when {
            ageMin <= 0.0 -> 1.0
            else -> (1.0 - insulinCdf(ageMin, units)).coerceIn(0.0, 1.0)
        }

    /**
     * The exact forecast insulin profile expressed in the legacy cumulative
     * kernel contract (mmol/L per unit). This is the bridge used by History
     * deconvolution and the chart: onset/peak/tail can no longer be described
     * by [HybridInsulinParams] while another empirical curve is subtracted.
     */
    fun insulinKernelPoints(units: Double = 1.0, stepMin: Double = 5.0): List<KernelPoint> {
        require(units > 0.0 && stepMin > 0.0)
        val p = model.insulin
        val count = kotlin.math.ceil(p.tailDurationMin / stepMin).toInt()
        return (0..count)
            .map { i ->
                val minute = minOf(i * stepMin, p.tailDurationMin)
                val cdf = insulinCdf(minute, units)
                KernelPoint(
                    minute,
                    -p.isf * cdf,
                    -p.isfHigh * cdf,
                    -p.isfLow * cdf,
                    0,
                )
            }
            .distinctBy { it.tauMin }
    }

    /** Observable landmarks of the exact CDF, including empirical knots when
     * present. UI text must use these rather than stale parametric fields. */
    fun insulinLandmarks(units: Double = model.insulin.tailReferenceUnits): HybridInsulinLandmarks {
        val p = model.insulin
        if (p.actionCdfKnots.isEmpty())
            return HybridInsulinLandmarks(p.onsetMin, p.peakMin, p.tailDurationMin)
        val samples =
            (0..kotlin.math.ceil(p.tailDurationMin).toInt()).map {
                it.toDouble() to insulinCdf(it.toDouble(), units)
            }
        val onset = samples.firstOrNull { it.second >= .01 }?.first ?: p.onsetMin
        val peak =
            samples
                .zipWithNext()
                .maxByOrNull { (a, b) -> b.second - a.second }
                ?.let { (a, b) -> (a.first + b.first) / 2 } ?: p.peakMin
        val end = samples.firstOrNull { it.second >= .99 }?.first ?: p.tailDurationMin
        return HybridInsulinLandmarks(onset, peak, end)
    }

    fun hypotheticalInsulinDelta(
        horizonMin: Double,
        units: Double,
        activityExposure: Double = 0.0,
    ): Double {
        if (horizonMin <= 0.0 || units <= 0.0) return 0.0
        return insulinDelta(
            HybridBolusEvent(0L, units),
            0L,
            (horizonMin * MINUTE_MS).toLong(),
            activityExposure,
        )
    }

    /**
     * Full causal contribution still to come from boluses already recorded in
     * [state]. Unlike the statistical forecast backbone this does not shrink a
     * known intervention with horizon trust. It is used by the interactive
     * counterfactual/display experiment, never by the promoted alert forecast.
     */
    fun fullKnownInsulinDelta(
        state: HybridForecastState,
        horizonMin: Double,
    ): Double {
        if (horizonMin <= 0.0) return 0.0
        val future = state.nowMs + (horizonMin * MINUTE_MS).toLong()
        return state.bolusHistory.sumOf { event ->
            insulinDelta(event, state.nowMs, future, state.activityExposure)
        }
    }

    /** Symmetric half-width contributed by uncertainty in the learned ISF. */
    fun insulinIsfUncertainty(insulinDelta: Double): Double {
        val isfHalfRange = maxOf(
            model.insulin.isf - model.insulin.isfLow,
            model.insulin.isfHigh - model.insulin.isf,
        )
        return abs(insulinDelta) * isfHalfRange / model.insulin.isf
    }

    /** The contribution drawn by What-if, after the same causal horizon
     * weighting used when this dose is persisted as a real bolus. */
    fun hypotheticalInsulinForecastDelta(
        horizonMin: Double,
        units: Double,
        offsetMin: Double = 0.0,
        activityExposureAtDose: Double = 0.0,
    ): Double {
        if (horizonMin <= offsetMin || units <= 0.0) return 0.0
        val step = model.runtime.stepMin.toDouble().coerceAtLeast(1.0)
        var clock = 0.0
        var previousRaw = 0.0
        var weighted = 0.0
        while (clock < horizonMin) {
            clock = minOf(horizonMin, clock + step)
            val age = clock - offsetMin
            val raw = if (age > 0.0) {
                hypotheticalInsulinDelta(age, units, activityExposureAtDose)
            } else 0.0
            weighted += backboneWeight(clock) * (raw - previousRaw)
            previousRaw = raw
        }
        return weighted
    }

    fun hypotheticalFoodDelta(
        horizonMin: Double,
        carbsG: Double,
        activityExposure: Double = 0.0,
    ): Double {
        if (horizonMin <= 0.0 || carbsG <= 0.0) return 0.0
        return foodDelta(
            HybridFoodEvent(0L, carbsG),
            0L,
            (horizonMin * MINUTE_MS).toLong(),
            activityExposure,
        )
    }

    private fun foodTokens(text: String): Set<String> {
        val main = text.split(
            // Protocol markers in both forms: the English ones new analyses use
            // and the older Russian ones stored analyses still contain.
            Regex("""\b(?:ГИ|УГЛЕВОДЫ|СОСТАВ|GI|CARBS|COMPOSITION)\s*:""", RegexOption.IGNORE_CASE),
            limit = 2,
        ).first()
        val stop = setOf(
            "грамм", "порция", "порц", "обычный", "примерно",
            "каждый", "каждая", "каждое", "половина",
            "carbs", "portion",
        )
        return Regex("""[\p{L}]{3,}""")
            .findAll(main.lowercase())
            .map { it.value }
            .filterNot { it in stop }
            .toSet()
    }

    fun foodAmplitude(event: HybridFoodEvent): Pair<Double, String> {
        val (raw, source) = foodAmplitudeUnscaled(event)
        // One multiply at ONE place. The unscaled body has four return sites and
        // scaling them individually is how one gets missed (discipline #7).
        if (foodAmpScale == 1.0 || event.rescueTreatment) return raw to source
        return raw * foodAmpScale to source
    }

    private fun foodAmplitudeUnscaled(event: HybridFoodEvent): Pair<Double, String> {
        // ONE RULE: grams x carbohydrate sensitivity. The dish dictionary that
        // used to sit in front of this — per-group factors, per-component
        // factors, whole-dish «progressive profiles» — went with the legacy
        // arm. Composition is expressed in the TIMING (macro shares, the
        // caloric queue), never as a hidden amplitude multiplier.
        val food = model.food
        return event.carbsG * food.globalFactor * food.calibration to "physio_global_cs"
    }

    private fun foodCdfInstant(event: HybridFoodEvent, ageMin: Double): Double {
        if (event.rescueTreatment) return triangularCdf(ageMin, 10.0, 15.0, 30.0)
        val food = model.food
        val mixture = physioFeatureShapes(event)
        if (mixture != null)
            return mixture
                .sumOf { (weight, shape) ->
                    weight *
                        triangularCdf(
                            ageMin,
                            shape.delayMin,
                            shape.delayMin + shape.peakMin,
                            shape.delayMin + shape.durationMin,
                        )
                }
                .coerceIn(0.0, 1.0)
        val shape = physioMacroShape(event)
        return triangularCdf(
            ageMin,
            shape.delayMin,
            shape.delayMin + shape.peakMin,
            shape.delayMin + shape.durationMin
        )
    }

    /** The queue rate this event is entitled to. Identical to the shipped
     *  30 g/h for a pure-carbohydrate meal, narrower once fat is counted. */
    private fun queueRate(event: HybridFoodEvent): Double {
        val rate = emptyingKcalPerHour ?: return PERSONAL_CARB_APPEARANCE_PRIOR_G_PER_HOUR_V1
        return caloricCarbRateGPerHourV1(event.carbsG, event.proteinG, event.fatG, rate, carbSieving)
    }

    private fun rawFoodCdf(event: HybridFoodEvent, ageMin: Double): Double {
        val duration = event.durationMin.coerceIn(0.0, 240.0)
        if (duration <= 0.0) return foodCdfInstant(event, ageMin)
        val slices = maxOf(2, minOf(24, kotlin.math.ceil(duration / 5.0).toInt()))
        return (0 until slices).sumOf { i ->
            val startOffset = duration * (i + 0.5) / slices
            foodCdfInstant(event, ageMin - startOffset)
        } / slices
    }

    /** Standalone curve with the same mass-throughput prior used by clusters. */
    fun foodCdf(event: HybridFoodEvent, ageMin: Double): Double {
        if (event.rescueTreatment) return rawFoodCdf(event, ageMin)
        val at = event.tsMs + (ageMin.coerceAtLeast(0.0) * MINUTE_MS).toLong()
        val member = CarbAppearanceMemberV1("${event.tsMs}:${event.carbsG}", event.tsMs, event.carbsG)
        return throughputLimitedFractionsV1(
            listOf(member),
            at,
            { _, t -> rawFoodCdf(event, (t - event.tsMs) / MINUTE_MS.toDouble()) },
            gramsPerHour = queueRate(event)
        )[member.id] ?: 0.0
    }

    /**
     * [foodCdf] at many ages in one pass, for callers that need a trajectory.
     *
     * The queue behind `foodCdf` integrates minute by minute from the meal's own
     * start, so asking it for a curve re-walks the same prefix once per point.
     * `NeighbourContributionV1` asks for 97 points per neighbour, turning 480
     * minute-steps into ~23 000 — a third of the corpus build's self-time on the
     * stand that reproduces the phone.
     *
     * [ageMins] must be ascending. The result is identical to calling [foodCdf]
     * once per age, not an approximation of it: the queue is a forward
     * accumulation whose state at an instant does not depend on whether the walk
     * stops there.
     */
    fun foodCdfSeries(event: HybridFoodEvent, ageMins: List<Double>): List<Double> {
        if (event.rescueTreatment)
            return ageMins.map { rawFoodCdf(event, it) }
        val member = CarbAppearanceMemberV1("${event.tsMs}:${event.carbsG}", event.tsMs, event.carbsG)
        val ats = ageMins.map { event.tsMs + (it.coerceAtLeast(0.0) * MINUTE_MS).toLong() }
        return throughputLimitedFractionsSeriesV1(
            listOf(member), ats,
            { _, t -> rawFoodCdf(event, (t - event.tsMs) / MINUTE_MS.toDouble()) },
            gramsPerHour = queueRate(event),
        ).map { it[member.id] ?: 0.0 }
    }

    /** Grams-on-board uses the exact same absorption CDF as the forecast. */
    fun foodRemainingFraction(event: HybridFoodEvent, ageMin: Double): Double =
        when {
            ageMin <= 0.0 -> 1.0
            else -> (1.0 - foodCdf(event, ageMin)).coerceIn(0.0, 1.0)
        }

    private fun clusterMember(event: HybridFoodEvent): MealClusterMemberV1 =
        clusterMemberCache.getOrPut(event) {
            val timing = rawFoodTiming(event);
            val k = event.kineticFeatures
            MealClusterMemberV1(
                id = "${event.tsMs}:${event.carbsG}",
                startMs = event.tsMs,
                proteinG = event.proteinG ?: k?.proteinG ?: 0.0,
                fatG = event.fatG ?: k?.fatG ?: 0.0,
                fiberG = k?.fiberG ?: 0.0,
                mainEndMin = timing.plateauMin,
                tailEndMin = timing.tailEndMin,
            )
        }

    private fun clusteredFoodCdfWithinSegment(
        event: HybridFoodEvent,
        normal: List<HybridFoodEvent>,
        ageMin: Double
    ): Double {
        val at = event.tsMs + (ageMin.coerceAtLeast(0.0) * MINUTE_MS).toLong()
        val owner = clusterMember(event)
        fun desired(food: HybridFoodEvent, t: Long): Double {
            val member = clusterMember(food)
            val clock =
                causalMealClusterClockV1(
                    member,
                    t,
                    normal.map(::clusterMember),
                    ownerStandaloneCdf = { rawFoodCdf(food, it) }
                )
            return rawFoodCdf(food, clock.effectiveAgeMin)
        }
        // THE CLUSTER SHARES ONE PIPE. Each member carries its own calories so
        // the queue can meter what the stomach meters; without this a cluster
        // fell back to the flat 30 g/h and a pizza+beer combo lost the caloric limit
        // that the pizza alone had just gained — the case the measurement said
        // mattered most (+266 min on a joined meal against +82 on the pizza).
        val massMembers = normal.map {
            CarbAppearanceMemberV1(
                "${it.tsMs}:${it.carbsG}",
                it.tsMs,
                it.carbsG,
                kcal =
                    if (emptyingKcalPerHour == null) null
                    else
                        com.diapilot.core.analysis.MealCaloricExtentV1.kcal(
                            it.carbsG,
                            it.proteinG,
                            it.fatG
                        ),
            )
        }
        // THE KEY MUST NAME EVERY INPUT THE SUPPLY CURVE READS.
        //
        // Found while deleting the legacy arm: the key carried
        // timestamp, grams and calories but NOT `durationMin`, and the supply
        // curve reads it (`rawFoodCdf` slices a spread meal). So two meals
        // identical but for how long they were eaten collided, and the first
        // one computed answered for both — on a fresh engine the same 30 g at
        // 90 min reads 0.287 instant against 0.103 spread over an hour, and the
        // memo returned 0.287 for both. Meal duration is a tracked input; a
        // cache is not allowed to discard one.
        val key =
            ClusterMassKey(
                normal.map { "${it.tsMs}:${it.carbsG}:${it.durationMin}" } +
                    massMembers.map { "${it.id}:${it.startMs}:${it.grams}:${it.kcal}" },
                at,
            )
        val limited =
            clusterMassCache.getOrPut(key) {
                val supply = { m: CarbAppearanceMemberV1, t: Long ->
                    val food = normal.first { it.tsMs == m.startMs && it.carbsG == m.grams };
                    desired(food, t)
                }
                val rate = emptyingKcalPerHour
                if (rate == null) throughputLimitedFractionsV1(massMembers, at, supply)
                else
                    caloricLimitedFractionsV1(
                        massMembers,
                        at,
                        supply,
                        kcalPerHour = rate,
                        carbSieving = carbSieving
                    )
            }
        return limited["${event.tsMs}:${event.carbsG}"] ?: 0.0
    }

    private fun mealSegments(foods: List<HybridFoodEvent>): List<List<HybridFoodEvent>> {
        val key = foods.distinct().sortedBy { it.tsMs }
        return mealSegmentsCache.getOrPut(key) { mealSegmentsUncached(key) }
    }

    private fun mealSegmentsUncached(foods: List<HybridFoodEvent>): List<List<HybridFoodEvent>> {
        val segments = mutableListOf<MutableList<HybridFoodEvent>>()
        for (food in foods.distinct().sortedBy { it.tsMs }) {
            val current = segments.lastOrNull()
            val split =
                current == null ||
                    food.rescueTreatment ||
                    current.any { it.rescueTreatment } ||
                    (food.tsMs - current.last().tsMs) / MINUTE_MS > 120 ||
                    current.all { previous ->
                        val age = (food.tsMs - previous.tsMs) / MINUTE_MS.toDouble()
                        clusteredFoodCdfWithinSegment(previous, current, age) >= .90
                    }
            if (split) segments.add(mutableListOf(food)) else current!!.add(food)
        }
        return segments
    }

    /** Causal cluster-adjusted CDF. Later meals can slow only the owner's
     * unrealised remainder; an already >=90% completed segment is a boundary. */
    fun clusteredFoodCdf(event: HybridFoodEvent, foods: List<HybridFoodEvent>, ageMin: Double): Double {
        if (event.rescueTreatment) return foodCdf(event, ageMin)
        val normal = foods.filterNot { it.rescueTreatment }
        val segment = mealSegments(normal).firstOrNull { event in it } ?: listOf(event)
        return clusteredFoodCdfWithinSegment(event, segment, ageMin)
    }

    /**
     * The same cluster-adjusted curve at MANY ages, in one pass of the queue.
     *
     * [clusteredFoodCdf] re-simulates the shared pipe from the cluster's start
     * for every age asked for, so reading a curve point by point is quadratic —
     * the History card wanted 70 points per row over 60 rows. The queue already
     * knows how to snapshot a list of times as it runs; this exposes that.
     */
    fun clusteredFoodCdfTimeline(
        event: HybridFoodEvent,
        foods: List<HybridFoodEvent>,
        agesMin: List<Double>,
    ): List<Double> {
        if (event.rescueTreatment) return agesMin.map { foodCdf(event, it) }
        val normal = foods.filterNot { it.rescueTreatment }
        val segment = mealSegments(normal).firstOrNull { event in it } ?: listOf(event)
        val rate = emptyingKcalPerHour
        val members = segment.map {
            CarbAppearanceMemberV1(
                "${it.tsMs}:${it.carbsG}",
                it.tsMs,
                it.carbsG,
                kcal =
                    if (rate == null) null
                    else
                        com.diapilot.core.analysis.MealCaloricExtentV1.kcal(
                            it.carbsG,
                            it.proteinG,
                            it.fatG
                        ),
            )
        }
        val supply = { m: CarbAppearanceMemberV1, t: Long ->
            val food = segment.first { it.tsMs == m.startMs && it.carbsG == m.grams }
            val member = clusterMember(food)
            val clock =
                causalMealClusterClockV1(
                    member,
                    t,
                    segment.map(::clusterMember),
                    ownerStandaloneCdf = { rawFoodCdf(food, it) }
                )
            rawFoodCdf(food, clock.effectiveAgeMin)
        }
        val times = agesMin.map { event.tsMs + (it.coerceAtLeast(0.0) * MINUTE_MS).toLong() }
        val timeline =
            if (rate == null)
                throughputLimitedFractionTimelineV1(
                    members,
                    times,
                    supply,
                    gramsPerHour = queueRate(event)
                )
            else
                caloricLimitedTimelineV1(
                    members,
                    times,
                    supply,
                    kcalPerHour = rate,
                    carbSieving = carbSieving
                )
        val id = "${event.tsMs}:${event.carbsG}"
        return times.map { timeline[it]?.get(id) ?: 0.0 }
    }

    fun mealClusterAssessment(
        event: HybridFoodEvent,
        foods: List<HybridFoodEvent>
    ): MealClusterAssessmentV1 {
        val sorted = foods.distinct().sortedBy { it.tsMs };
        val index = sorted.indexOf(event)
        if (index < 0)
            return MealClusterAssessmentV1(
                MealTimingScopeV1.INDIVIDUAL,
                listOf(clusterMember(event).id),
                event.carbsG,
                "event outside cluster"
            )
        // A clock gap is only a candidate boundary. If every normal member of
        // the current segment has already realised >=90% by the new intake,
        // the new meal starts a fresh chain even when the gap is under 120 m.
        val segments = mealSegments(sorted)
        val chain = segments.first { event in it }
        val next = sorted.firstOrNull { it.tsMs > event.tsMs }
        val realisedAtNext = next?.let {
            clusteredFoodCdf(event, chain, (it.tsMs - event.tsMs) / MINUTE_MS.toDouble())
        }
        if (chain.size == 1)
            return MealClusterAssessmentV1(
                MealTimingScopeV1.INDIVIDUAL,
                listOf(clusterMember(event).id),
                event.carbsG,
                if (next != null)
                    "${"%.0f".format((realisedAtNext?:0.0)*100)}% of predicted contribution realised by next intake; next intake starts a new chain"
                else "isolated intake",
                next?.tsMs,
                realisedAtNext,
            )
        if (next != null) {
            val age = (next.tsMs - event.tsMs).toDouble() / MINUTE_MS
            val clock =
                causalMealClusterClockV1(
                    clusterMember(event),
                    next.tsMs + 1,
                    chain.map(::clusterMember),
                    ownerStandaloneCdf = { rawFoodCdf(event, it) }
                )
            if (clock.lockedBeforeLaterMeals || clusteredFoodCdf(event, chain, age) >= .90)
                return MealClusterAssessmentV1(
                    MealTimingScopeV1.COMPLETED_BEFORE_CLUSTER,
                    chain.map { clusterMember(it).id },
                    chain.sumOf { it.carbsG },
                    "at least 90% completed before the next intake; contribution locked",
                    next.tsMs,
                    realisedAtNext,
                )
        }
        return MealClusterAssessmentV1(
            MealTimingScopeV1.CLUSTER_ONLY,
            chain.map { clusterMember(it).id },
            chain.sumOf { it.carbsG },
            "overlapping residual food and combined macros make individual timing non-identifiable",
            next?.tsMs,
            realisedAtNext,
        )
    }

    /**
     * Start, maximum absorption rate, 90% main-response completion and the
     * last mathematical trace of the same CDF used by [forecast]. A tiny slow
     * component must not make the UI call a 2--5% BJU trace a six-hour
     * "plateau"; [HybridFoodTiming.tailEndMin] remains available to episode
     * closure and censoring code.
     *
     * Sampling is only used to combine component shapes and a spread-out
     * intake. A quarter-minute grid keeps the rounded UI values stable without
     * introducing a second food model.
     */
    fun foodTiming(event: HybridFoodEvent): HybridFoodTiming {
        val key =
            FoodTimingKey(
                event.text,
                event.components,
                event.carbsG,
                event.durationMin,
                event.proteinG,
                event.fatG,
                event.kineticFeatures?.identity(),
                event.rescueTreatment,
                event.recipeKey
            )
        return foodTimingCache.getOrPut(key) { foodTimingUncached(event) }
    }

    private fun rawFoodTiming(event: HybridFoodEvent): HybridFoodTiming =
        foodTimingFrom(event, ::rawFoodCdf)

    private fun foodTimingUncached(event: HybridFoodEvent): HybridFoodTiming {
        // foodCdf() applies the 30 g/h queue by replaying it from intake to the
        // requested time. Calling it separately at every 15-second timing bin
        // made this scan quadratic (about one second per History row). Build
        // the exact same queue once and read all bins from its timeline.
        if (!event.rescueTreatment) {
            val horizon = foodTimingHorizon(event)
            val step = 0.25
            val ages = buildList {
                var minute = 0.0
                add(minute)
                while (minute < horizon) {
                    minute = minOf(horizon, minute + step)
                    add(minute)
                }
            }
            val member =
                CarbAppearanceMemberV1("${event.tsMs}:${event.carbsG}", event.tsMs, event.carbsG)
            val ageByTs = ages.associateBy { event.tsMs + (it * MINUTE_MS).toLong() }
            val timeline =
                throughputLimitedFractionTimelineV1(
                    listOf(member),
                    ageByTs.keys,
                    desiredCdf = { _, at ->
                        rawFoodCdf(event, (at - event.tsMs) / MINUTE_MS.toDouble())
                    },
                    gramsPerHour = queueRate(event),
                    stepMin = 0.25,
                )
            return foodTimingFromSamples(horizon, step) { age ->
                timeline[event.tsMs + (age * MINUTE_MS).toLong()]?.get(member.id) ?: 0.0
            }
        }
        return foodTimingFrom(event, ::foodCdf)
    }

    private fun foodTimingHorizon(event: HybridFoodEvent): Double {
        val allShapes = physioFeatureShapes(event)?.map { it.second } ?: listOf(physioMacroShape(event))
        val throughputEnd =
            if (event.rescueTreatment) 30.0 else event.carbsG / queueRate(event) * 60.0 + 120.0
        return (maxOf(allShapes.maxOf { it.delayMin + it.durationMin }, throughputEnd) +
                event.durationMin.coerceIn(0.0, 240.0) +
                5.0)
            .coerceIn(5.0, 1_440.0)
    }

    private fun foodTimingFrom(
        event: HybridFoodEvent,
        cdf: (HybridFoodEvent, Double) -> Double
    ): HybridFoodTiming {
        val horizon = foodTimingHorizon(event)
        val step = 0.25
        return foodTimingFromSamples(horizon, step) { cdf(event, it) }
    }

    /**
     * Landmarks read off the CDF.
     *
     * `peakMin` is the argmax of the arrival RATE and it is not monotone in the
     * inputs — measured while fitting the fat slope, a 40 g dish reported
     * 55 -> 72 -> 59 -> 93 minutes for 0, 10, 20 and 40 g of fat. The cause is
     * structural rather than numerical: the mixture is genuinely two-humped
     * (three triangular bases lengthening by different macro shares), so which
     * hump is tallest flips, and smoothing the rate over ten minutes did not fix
     * it. An argmax is simply a poor summary of a two-humped arrival.
     *
     * So [HybridFoodTiming.medianArrivalMin] is reported beside it: the moment
     * half the rise has arrived. It is a LEVEL crossing like onset and the 90%
     * mark, therefore monotone in every input by construction, and on the same
     * probe it reads 74 -> 86 -> 98 -> 122. `peakMin` keeps its old meaning and
     * its old numbers — nothing that consumed it has been silently redefined.
     */
    private fun foodTimingFromSamples(
        horizon: Double,
        step: Double,
        cdf: (Double) -> Double
    ): HybridFoodTiming {
        val epsilon = 1e-10
        var minute = 0.0
        var previous = cdf(minute).coerceIn(0.0, 1.0)
        var onset: Double? = null
        var peak = 0.0
        var peakGain = Double.NEGATIVE_INFINITY
        var mainEnd: Double? = if (previous >= .90) 0.0 else null
        var halfMark: Double? = if (previous >= .50) 0.0 else null
        var tailEnd = 0.0
        while (minute < horizon) {
            val nextMinute = minOf(horizon, minute + step)
            val next = cdf(nextMinute).coerceIn(0.0, 1.0)
            val gain = next - previous
            if (gain > epsilon) {
                if (onset == null) onset = minute
                if (gain > peakGain) {
                    peakGain = gain
                    peak = (minute + nextMinute) / 2.0
                }
                tailEnd = nextMinute
            }
            if (halfMark == null && next >= .50) halfMark = nextMinute
            if (mainEnd == null && next >= .90) mainEnd = nextMinute
            minute = nextMinute
            previous = next
        }
        return HybridFoodTiming(
            onsetMin = onset ?: 0.0,
            peakMin = peak,
            plateauMin = mainEnd ?: tailEnd,
            tailEndMin = tailEnd,
            medianArrivalMin = halfMark ?: (mainEnd ?: tailEnd),
        )
    }

    private fun physioMacroShape(event: HybridFoodEvent): HybridShape {
        // THE TIME SCALE TOUCHES THE POPULATION PRIOR, NEVER A LEARNED CURVE.
        //
        // `carbTimeScale` says «the user's carbohydrate arrives sooner/later than the
        // textbook», so it belongs to the textbook shape. A dish with its own
        // pooled curve has been MEASURED on the user's own repeats of that dish; a
        // global knob rescaling it would let one number fitted across all meals
        // overrule a per-dish measurement, which is the wrong way round.
        //
        // The feature path (`physioFeatureShapes`) scales its three population
        // triangles for the same reason. Without this branch the axis was inert
        // for every event without kinetic features — plumbed, reported by the
        // fit, and quietly doing nothing.
        val prior = model.food.defaultShape
        val base = if (carbTimeScale == 1.0) prior else HybridShape(
            delayMin = prior.delayMin * carbTimeScale,
            peakMin = prior.peakMin * carbTimeScale,
            durationMin = prior.durationMin * carbTimeScale,
        )
        val protein10 = (event.proteinG ?: 0.0).coerceAtLeast(0.0) / 10.0
        val fat10 = (event.fatG ?: 0.0).coerceAtLeast(0.0) / 10.0
        // The double-count guard that used to live here protected a dish's own
        // pooled curve from having the population fat slope added on top. There
        // are no pooled curves any more, so every meal takes the shared slope
        // and there is nothing left to except.
        val shared = macroTiming.promoted
        val proteinDelay=if(shared)protein10 * macroTiming.proteinDelayMinPer10g else 0.0
        val proteinTail=if(shared)protein10 * macroTiming.proteinTailMinPer10g else 0.0
        val fatPeak=if(shared)fat10 * macroTiming.fatPeakMinPer10g else 0.0
        val fatTail=if(shared)fat10 * macroTiming.fatTailMinPer10g else 0.0
        return HybridShape(
            delayMin = (base.delayMin + proteinDelay + event.onsetOffsetMin).coerceIn(0.0, 180.0),
            peakMin = (base.peakMin + fatPeak + event.peakOffsetMin).coerceIn(1.0, 360.0),
            durationMin = (base.durationMin + proteinTail + fatTail + event.tailOffsetMin).coerceIn(5.0, 720.0),
        )
    }

    private fun physioFeatureShapes(event: HybridFoodEvent): List<Pair<Double, HybridShape>>? {
        if (featureShapesCache.containsKey(event)) return featureShapesCache[event]
        return computePhysioFeatureShapes(event).also { featureShapesCache[event] = it }
    }

    private fun computePhysioFeatureShapes(event: HybridFoodEvent): List<Pair<Double, HybridShape>>? {
        // Returning null hands the shape to physioMacroShape, which is now the
        // no-macros path and nothing else: the learned-dish curve that used to
        // pre-empt this mixture was removed.
        val f = event.kineticFeatures?.normalized() ?: return null
        val protein = (event.proteinG ?: f.proteinG ?: 0.0).coerceIn(0.0, 120.0)
        val fat = (event.fatG ?: f.fatG ?: 0.0).coerceIn(0.0, 120.0)
        val fiber = (f.fiberG ?: 0.0).coerceIn(0.0, 60.0)
        // MIXED sits BETWEEN soft and solid. It used to be the
        // slowest of all — +6/x1.15 against SOLID's +5/x1.10 — which cannot be
        // right: a mixed plate is not physically slower to leave the stomach
        // than a purely solid one. The enum was conflating AGGREGATE STATE with
        // COMPOSITIONAL COMPLEXITY, and the complexity half is now carried by
        // the caloric queue, which is where it belongs.
        val formDelay =
            (when (f.physicalForm) {
                FoodPhysicalFormV2.LIQUID -> -5.0;
                FoodPhysicalFormV2.PUREE -> -2.0
                FoodPhysicalFormV2.SOFT_SOLID -> 1.0;
                FoodPhysicalFormV2.MIXED -> 3.0;
                FoodPhysicalFormV2.SOLID -> 5.0;
                FoodPhysicalFormV2.UNKNOWN -> 0.0
            }) * formDelayScale
        val formStretchRaw =
            when (f.physicalForm) {
                FoodPhysicalFormV2.LIQUID -> .82;
                FoodPhysicalFormV2.PUREE -> .92
                FoodPhysicalFormV2.SOFT_SOLID -> 1.0;
                FoodPhysicalFormV2.MIXED -> 1.05;
                FoodPhysicalFormV2.SOLID -> 1.10;
                FoodPhysicalFormV2.UNKNOWN -> 1.0
            }
        val formStretch = 1.0 + (formStretchRaw - 1.0) * formStretchScale
        // Conservative bounded priors. Personal evidence may later modify the
        // event offsets, but one LLM estimate cannot create an extreme curve.
        // THE THIRD COUNT OF THE SAME FAT — M-11, measured.
        //
        // These two priors date from the gram queue, when nothing else in the
        // model knew that fat slows a stomach. The caloric queue meters exactly
        // those calories now (`carbs*4 + protein*4 + fat*9`), so on the caloric
        // arm the fat and protein terms here bill the same physiology a second
        // time — in the DELAY and in the TAIL. The peak was already protected
        // (see `gastricForPeak` below); these two were not, and food-model.md §6
        // named the leftover before it was measured.
        //
        // FIBRE STAYS in both, on purpose: it is not in the kcal formula, so
        // the queue cannot be counting it. Whatever fibre does to emptying is
        // still this prior's job.
        //
        // Measured on six pancake Sundays (stand `fatsweep`): shipped delivers
        // half the carbohydrate at 130 min against an observed peak of 74-96,
        // and every variant that removes fat delay lands closer.
        val queueMetersMacros = !gastricMacroPrior
        // FIBRE IS NOT SCALED. The knobs above test the FAT/PROTEIN prior, and
        // fibre is not in the kcal formula, so the queue cannot be standing in
        // for it — scaling it here would quietly fold a second, unrelated
        // mechanism into the same number.
        val gastric =
            ((if (queueMetersMacros) 0.0
                else (fat * FAT_ONSET_MIN_PER_G + protein * .08) * macroGastricScale) +
                    fiber * .45 * fiberScale)
                .coerceIn(0.0, 40.0)
        val tail =
            ((if (queueMetersMacros) 0.0 else (fat * 2.0 + protein * 1.2) * macroTailScale) +
                    fiber * 2.0 * fiberScale)
                .coerceIn(0.0, 180.0)
        // FAT -> PEAK, FITTED.
        //
        // The coefficient lived only in physioMacroShape, which since wave 2
        // runs when a learned pool wins — i.e. for the two dishes that already
        // have their own measured curve. An unknown dish takes THIS branch,
        // whose fat term was hardcoded at fat*.35*.35*macroShare: about 0.9 min
        // for 10 g of fat, which is nothing. Measured on the corpus by OLS with
        // carbs held as a control, the shift is +17.0 min per 10 g, bootstrap
        // 10-90% +8.4..+25.5, positive in 600 of 600 draws.
        //
        // Applied WITHOUT macroShare on purpose: the fit is a meal-level shift
        // of the observed time-to-peak, so shifting all three bases equally
        // moves the mixture's peak by exactly the fitted amount. Weighting it
        // per-basis would deliver ~0.7 of the number that was measured.
        //
        // Fat is removed from the PEAK's gastric term when the fitted shift is
        // in force, or the two would stack. It stays in the DELAY term, which
        // this fit says nothing about — onset came out +3.4 min per 10 g with
        // an interval of -1.2..+9.0, i.e. undetermined.
        val fatPeakFitted = macroTiming.promoted && macroTiming.fatPeakMinPer10g > 0.0
        val gastricForPeak =
            if (fatPeakFitted) (fiber * .45 * fiberScale + protein * .08).coerceIn(0.0, 40.0)
            else gastric
        val fatPeakShift = if (fatPeakFitted) (fat / 10.0) * macroTiming.fatPeakMinPer10g else 0.0
        fun shape(
            delayRaw: Double,
            peakRaw: Double,
            endRaw: Double,
            macroShareRaw: Double,
            basis: Double = 1.0
        ): HybridShape {
            val macroShare = macroShareRaw * macroShareScale
            // The scale multiplies the BASE triangle only. Form, macro and the
            // learned offsets are separate mechanisms measured on their own
            // evidence; folding them in would make one number stand for four.
            val delay = delayRaw * carbTimeScale * basis
            val peakAbs = peakRaw * carbTimeScale * basis
            val endAbs = endRaw * carbTimeScale * basis * foodTailScale
            // A FLOOR, BECAUSE NOTHING APPEARS IN THE BLOOD INSTANTLY. The
            // bound used to be zero, and `formDelay` for LIQUID is -5, so a
            // smoothie came out at onset 0 — against a measured median of 8 for
            // that very dish (M-92, n=14). Zero is not a fast food, it is a
            // missing constraint.
            val d =
                (delay + formDelay + gastric * macroShare + event.onsetOffsetMin).coerceIn(
                    MIN_FOOD_ONSET_MIN,
                    180.0
                )
            val peak =
                (peakAbs * formStretch +
                        gastricForPeak * peakGastricShare * macroShare +
                        fatPeakShift +
                        event.peakOffsetMin - d)
                    .coerceIn(1.0, 360.0)
            val duration =
                (endAbs * formStretch + tail * macroShare + event.tailOffsetMin - d).coerceIn(
                    5.0,
                    720.0
                )
            return HybridShape(d, peak.coerceAtMost(duration), duration)
        }
        // Collapse toward MEDIUM, not toward the mixture's own centroid: the
        // centroid moves with the weights, so a dish would change shape when
        // its split changed even at a fixed spread, and the knob would stop
        // meaning one thing.
        fun sp(v: Double, mid: Double) = mid + (v - mid) * carbSpread
        // THE TWELVE NUMBERS THAT WERE LITERALS. They used to be inline: the three base
        // triangles were written inline here, so the population shape every
        // dish inherits could not be examined, let alone moved, without editing
        // this file — while `carbSpread` and `carbTimeScale`, which only scale
        // them, had knobs. `CarbTrianglesV1()` reproduces the previous literals
        // exactly, so this is a change of ACCESS, not of behaviour.
        val t = carbTriangles
        return listOf(
                f.fastFraction to
                    shape(
                        sp(t.fastDelayMin, t.mediumDelayMin),
                        sp(t.fastPeakMin, t.mediumPeakMin),
                        sp(t.fastEndMin, t.mediumEndMin),
                        sp(t.fastMacroShare, t.mediumMacroShare),
                        fastTimeScale,
                    ),
                f.mediumFraction to
                    shape(
                        t.mediumDelayMin,
                        t.mediumPeakMin,
                        t.mediumEndMin,
                        t.mediumMacroShare,
                        mediumTimeScale,
                    ),
                f.slowFraction to
                    shape(
                        sp(t.slowDelayMin, t.mediumDelayMin),
                        sp(t.slowPeakMin, t.mediumPeakMin),
                        sp(t.slowEndMin, t.mediumEndMin),
                        sp(t.slowMacroShare, t.mediumMacroShare),
                        slowTimeScale,
                    ),
            )
            .filter { it.first > 1e-6 }
    }

    private fun foodDelta(
        event: HybridFoodEvent,
        t0: Long,
        t1: Long,
        exposure: Double,
        clusterFoods: List<HybridFoodEvent> = listOf(event),
    ): Double {
        val age0 = (t0 - event.tsMs).toDouble() / MINUTE_MS
        val age1 = (t1 - event.tsMs).toDouble() / MINUTE_MS
        if (age1 <= 0.0) return 0.0
        val amplitude = foodAmplitude(event).first
        val horizon = maxOf(0.0, (t1 - t0).toDouble() / MINUTE_MS)
        val futureExposure = exposure * exp(-horizon / model.activity.foodTauMin)
        val activityMultiplier =
            maxOf(
                0.25,
                1.0 - model.activity.foodGamma * minOf(1.5, (exposure + futureExposure) / 2.0),
            )
        val fastFraction =
            clusteredFoodCdf(event, clusterFoods, age1) -
                clusteredFoodCdf(event, clusterFoods, maxOf(0.0, age0))
        // The legacy late tail returned a flat zero on this arm and is gone
        // with it; a fatty dish gets its extra tail from the macro shape and
        // the caloric queue, not from a second additive term.
        return amplitude * activityMultiplier * fastFraction
    }

    /** Exact contribution used by [forecast], exposed for a calculation card.
     * Keeping this as a wrapper prevents the UI from rebuilding a parallel
     * pseudo-formula. */
    fun foodContribution(
        event: HybridFoodEvent,
        t0: Long,
        t1: Long,
        activityExposure: Double = 0.0,
        clusterFoods: List<HybridFoodEvent> = listOf(event)
    ): Double = foodDelta(event, t0, t1, activityExposure, clusterFoods)

    private fun insulinDelta(
        event: HybridBolusEvent,
        t0: Long,
        t1: Long,
        exposure: Double,
    ): Double {
        val age0 = (t0 - event.tsMs).toDouble() / MINUTE_MS
        val age1 = (t1 - event.tsMs).toDouble() / MINUTE_MS
        if (age1 <= 0.0) return 0.0
        val fraction = insulinCdf(event, age1) - insulinCdf(event, maxOf(0.0, age0))
        val horizon = maxOf(0.0, (t1 - t0).toDouble() / MINUTE_MS)
        val futureExposure = exposure * exp(-horizon / model.activity.tauMin)
        val multiplier = 1.0 + model.activity.iobGamma * minOf(1.5, (exposure + futureExposure) / 2.0)
        return -event.units *
            model.insulin.isf *
            fraction *
            multiplier *
            event.potencyMultiplier.coerceIn(.5, 1.5)
    }


    private fun basalCdf(ageMin: Double): Double = triangularCdf(
        ageMin,
        model.basal.onsetMin,
        model.basal.peakMin,
        model.basal.durationMin,
    )

    companion object {
        /**
         * How many minutes each gram of fat delays the start of carb arrival.
         *
         * MEASURED at 0.55 and DELIBERATELY NOT APPLIED. Food onset was read on
         * 21 meals with no insulin within four hours before and thirty minutes
         * after, on a flat pre-meal background — `onset = 10.5 + 0.388 * fat_g`,
         * R2 0.30 (M-92). The corpus averages a macroShare near 0.7, so the
         * constant that reproduces it is 0.55, and the shipped 0.35 makes the
         * effective coefficient two to four times weaker than the fitted value.
         *
         * Raising it to 0.55 was tried and MEASURABLY HURT: median
         * shape 1.293 -> 1.334, bias 1.794 -> 1.974, MAE 2.109 -> 2.162 across
         * 24 episodes with insulin frozen. Isolated against the onset floor, the
         * whole loss is this coefficient's.
         *
         * WHY THE MEASUREMENT AND THE FIT DISAGREE, and it is not that one is
         * wrong: this coefficient does THREE jobs. `gastric` enters the delay,
         * the peak (via `gastricForPeak * peakGastricShare * macroShare`) and the
         * duration (`tail * macroShare`). Onset wants it strong; peak and tail
         * want it weak — and the independent sweep agrees, `macroShareScale` at
         * 0.5 being the single largest improvement found all day (-0.109 shape,
         * bias 1.974 -> 1.626). One coefficient serving three landmarks is
         * over-constrained, so the measured onset must arrive through a term of
         * its OWN, not by strengthening this one. Until that term exists, the
         * shipped value stands and the measurement is recorded here.
         */
        const val FAT_ONSET_MIN_PER_G = 0.35

        /**
         * The earliest carbohydrate may start arriving, whatever the dish.
         *
         * Measured onsets on a flat background run 0..38 with a median of 12.5;
         * the single zero is one meal out of twenty-one and the tenth percentile
         * is 5. So five minutes is the floor the fitted data supports — deliberately
         * below the fastest food class's median (8 for the smoothie) rather than
         * at it, because this is a bound, not an estimate.
         */
        const val MIN_FOOD_ONSET_MIN = 5.0
    }

    private fun basalDelta(
        state: HybridForecastState,
        t0: Long,
        t1: Long,
    ): Double {
        val p = model.basal
        if (p.scale <= 0.0 || t1 <= t0) return 0.0
        var action = 0.0
        for (event in state.basalHistory) {
            val age0 = (t0 - event.tsMs).toDouble() / MINUTE_MS
            val age1 = (t1 - event.tsMs).toDouble() / MINUTE_MS
            if (age1 <= 0.0 || age0 >= p.durationMin) continue
            action +=
                event.units *
                    event.actionMultiplier.coerceIn(.5, 1.5) *
                    (basalCdf(age1) - basalCdf(maxOf(0.0, age0)))
        }
        val expected =
            p.referenceUnits24h *
                state.basalReferenceScaleMultiplier.coerceIn(.5, 1.5) *
                (t1 - t0).toDouble() / (24.0 * 60.0 * MINUTE_MS)
        return -p.sensitivityMmolPerActionUnit * (action - expected) * p.scale
    }

    /**
     * Background over the interval [[fromMin], [toMin]] at state [current].
     *
     * THE INTERVAL IS THE FIX. Previously `forecast()` asked for the whole
     * horizon at once and passed the ANCHOR glucose, so
     * `- stateReversion * (current - target)` was computed from the starting
     * level and held CONSTANT for the entire forecast. A reversion that never
     * notices the line has moved is not a reversion — it is a fixed slope chosen
     * by the anchor, and the line then rises for ever.
     *
     * Measured before the fix: with no food and no insulin the model went from
     * 7.0 to 13.38 in 24 hours, and from 3.0 to 11.23 — no equilibrium anywhere
     * in the physiological range. The arithmetic matched a constant slope to two
     * decimals (start 18.0 predicted +0.060 mmol/h, probe read +0.06).
     *
     * `openLoop()` a few lines below had always stepped its state correctly.
     * Two paths in one engine computing one quantity differently, and the live
     * one took the wrong branch — the same shape as the four insulin curves and
     * the four timezone conventions in the registry.
     *
     * The `openLoop` caller passes `0, step` and so keeps its previous behaviour
     * exactly, including its own quirk of taking the circadian angle from the
     * anchor rather than from the stepped time. That is left alone deliberately:
     * one defect per change, or the measurement cannot attribute what moved.
     */
    private fun backgroundDelta(
        state: HybridForecastState,
        current: Double,
        fromMin: Int,
        toMin: Int,
    ): Pair<Double, Double> {
        val c = model.joint.coefficients
        var total = 0.0
        var activityDirect = 0.0
        var minute = fromMin
        while (minute < toMin) {
            val futureTs = state.nowMs + ((minute + 2.5) * MINUTE_MS).toLong()
            val hour = (futureTs.toDouble() / 1000.0 / 3600.0) % 24.0
            val angle = 2.0 * PI * hour / 24.0
            val exposure = state.activityExposure * exp(-minute / model.activity.tauMin)
            val eventBackgroundRate =
                state.foodHistory.sumOf { event ->
                    val ageMin = (futureTs - event.tsMs).toDouble() / MINUTE_MS
                    if (ageMin < 0 || ageMin > 12 * 60) 0.0
                    else event.backgroundRateOffsetMmolPerHour * exp(-ageMin / 360.0)
                }
            val rate30 =
                c.intercept +
                    (state.backgroundRateOffsetMmolPerHour + eventBackgroundRate) / 2.0 +
                    c.sin24 * sin(angle) +
                    c.cos24 * cos(angle) +
                    c.sin12 * sin(2.0 * angle) +
                    c.cos12 * cos(2.0 * angle) -
                    c.stateReversion * (current - model.joint.targetGlucose) +
                    c.sleep * state.asleep +
                    c.sleepDebt * state.sleepDebtHours +
                    c.hoursSinceWake * state.hoursSinceWake
            val directStep = -c.activityDirect * exposure * 5.0 / 30.0
            activityDirect += directStep
            total += rate30 * 5.0 / 30.0 + directStep
            minute += 5
        }
        // Basal follows the same interval, so accumulating the steps gives the
        // same total the single 0..horizon call used to return.
        val basal =
            basalDelta(
                state,
                state.nowMs + fromMin * MINUTE_MS,
                state.nowMs + toMin * MINUTE_MS,
            )
        return total * model.joint.backgroundScale + basal to
            activityDirect * model.joint.backgroundScale
    }

    /** Public so an off-device stand computes the trust-weighted increment
     * with THE function, not a re-spelling of it (discipline #7 — the
     * symmetry study A-16 subtracts/adds full-vs-weighted corrections and a
     * privately re-derived weight is exactly how a stand drifts). */
    fun backboneWeight(horizonMin: Double): Double = linearKnots(
        horizonMin,
        listOf(
            0.0 to 1.0,
            60.0 to model.trend.weight60,
            120.0 to model.trend.weight120,
            180.0 to model.trend.weight180,
        ),
    )

    fun forecast(
        state: HybridForecastState,
        hypotheticalInsulinUnits: Double = 0.0,
    ): HybridForecastResult {
        require(state.glucoseHistory.isNotEmpty()) {
            "At least one glucose point is required"
        }
        require(hypotheticalInsulinUnits >= 0.0)
        val history = state.glucoseHistory.sortedBy { it.tsMs }
        val current =
            glucoseAtOrBefore(history, state.nowMs)?.plus(state.sensorBiasMmol)
                ?: error("No causal glucose anchor")
        val window = model.trend.windowMin
        val previous =
            glucoseAtOrBefore(
                    history,
                    state.nowMs - window * MINUTE_MS,
                )
                ?.plus(state.sensorBiasMmol)
        val observed = if (previous != null) current - previous else 0.0
        var recentEvents = 0.0
        if (previous != null) {
            val start = state.nowMs - window * MINUTE_MS
            recentEvents +=
                state.foodHistory.sumOf {
                    foodDelta(it, start, state.nowMs, state.activityExposure, state.foodHistory)
                }
            recentEvents +=
                state.bolusHistory.sumOf {
                    insulinDelta(it, start, state.nowMs, state.activityExposure)
                }
        }
        val residualRate = (observed - recentEvents) / maxOf(1, window)
        val scenarioEvent = HybridBolusEvent(state.nowMs, hypotheticalInsulinUnits)
        // Apply horizon trust to each NEW increment. Re-weighting the entire
        // cumulative physiology at every horizon (0.50@60, then 0.75@120)
        // manufactured a flat shoulder followed by a second insulin fall.
        var weightedRaw = 0.0
        var previousRaw = 0.0
        var weightedFood = 0.0
        var previousFood = 0.0
        var weightedInsulin = 0.0
        var previousInsulin = 0.0
        var weightedScenarioInsulin = 0.0
        var previousScenarioInsulin = 0.0
        // THE BACKGROUND NOW WALKS WITH THE LINE.
        //
        // Accumulated one step at a time, each step evaluated at the level the
        // forecast has actually reached, so `stateReversion` sees where the
        // trajectory went. `bgLevel` is the previous point's baseline — the same
        // quantity `openLoop` feeds itself.
        //
        // Also O(n) instead of O(n^2): the old call re-integrated 0..horizon at
        // every horizon.
        var bgTotal = 0.0
        var bgActivityTotal = 0.0
        var bgLevel = current
        var bgFrom = 0
        // THE SCENARIO NEEDS ITS OWN BACKGROUND, and a test caught why.
        //
        // `what if bolus is identical to persisting the same bolus` is a real
        // invariant: the What-if preview must equal what actually happens if the
        // user injects that dose. It held while the background was frozen at the
        // anchor, because then both arms shared one constant.
        //
        // The moment the background reacts to the trajectory it stops being
        // shareable: persisting the bolus lowers the line, and a lower line gets
        // a different reversion. Adding a scenario delta on top of the
        // BASELINE's background would show the user a preview that no injection can
        // reproduce — the preview would be optimistic by exactly the background
        // it borrowed. So the scenario carries a parallel accumulation at its
        // OWN level.
        var scBgTotal = 0.0
        var scBgLevel = current
        var scWeightedRaw = 0.0
        var scPreviousRaw = 0.0
        var scBgFrom = 0
        val previousFoodEvents = DoubleArray(state.foodHistory.size)
        val weightedFoodEvents = DoubleArray(state.foodHistory.size)
        val points =
            (0..model.runtime.horizonMin step model.runtime.stepMin).map { horizon ->
                val future = state.nowMs + horizon * MINUTE_MS
                val foodContributions =
                    state.foodHistory.map { event ->
                        event to
                            foodDelta(
                                event,
                                state.nowMs,
                                future,
                                state.activityExposure,
                                state.foodHistory
                            )
                    }
                val food = foodContributions.sumOf { it.second }
                val incrementWeight = backboneWeight(horizon.toDouble())
                weightedFood += incrementWeight * (food - previousFood)
                previousFood = food
                foodContributions.forEachIndexed { index, (_, value) ->
                    weightedFoodEvents[index] += incrementWeight * (value - previousFoodEvents[index])
                    previousFoodEvents[index] = value
                }
                val unknownFoodUncertainty =
                    foodContributions.withIndex().sumOf { indexed ->
                        val event = indexed.value.first
                        val structurallyUnknown =
                            event.kineticFeatures == null || event.kineticFeatures.confidence < 0.45
                        if (structurallyUnknown) {
                            abs(weightedFoodEvents[indexed.index]) *
                                model.uncertainty.unknownFoodExtraFraction
                        } else {
                            0.0
                        }
                    }
                val insulin =
                    state.bolusHistory.sumOf {
                        insulinDelta(it, state.nowMs, future, state.activityExposure)
                    }
                weightedInsulin += incrementWeight * (insulin - previousInsulin)
                previousInsulin = insulin
                val drift =
                    residualRate * model.trend.tauMin * (1.0 - exp(-horizon / model.trend.tauMin))
                if (horizon > bgFrom) {
                    val (stepBg, stepAct) =
                        backgroundDelta(
                            state,
                            if (steppedBackgroundFeedback) bgLevel else current,
                            bgFrom,
                            horizon,
                        )
                    bgTotal += stepBg
                    bgActivityTotal += stepAct
                    bgFrom = horizon
                }
                val background = bgTotal
                val activityDirect = bgActivityTotal
                val raw = food + insulin + drift + background
                weightedRaw += incrementWeight * (raw - previousRaw)
                previousRaw = raw
                val baseline = current + weightedRaw
                if (hypotheticalInsulinUnits > 0.0 && horizon > scBgFrom) {
                    scBgTotal +=
                        backgroundDelta(
                                state,
                                if (steppedBackgroundFeedback) scBgLevel else current,
                                scBgFrom,
                                horizon,
                            )
                            .first
                    scBgFrom = horizon
                }
                // Feed the level forward for the NEXT step. The forecast is what the
                // reversion must react to, not the background alone — a term that
                // only saw its own contribution would still miss the meal.
                bgLevel = baseline
                val scenarioRaw =
                    if (hypotheticalInsulinUnits > 0.0) {
                        insulinDelta(
                            scenarioEvent,
                            state.nowMs,
                            future,
                            state.activityExposure,
                        )
                    } else {
                        0.0
                    }
                weightedScenarioInsulin += incrementWeight * (scenarioRaw - previousScenarioInsulin)
                previousScenarioInsulin = scenarioRaw
                val scenario =
                    if (hypotheticalInsulinUnits > 0.0) {
                        val scRaw = food + insulin + scenarioRaw + drift + scBgTotal
                        scWeightedRaw += incrementWeight * (scRaw - scPreviousRaw)
                        scPreviousRaw = scRaw
                        val v = current + scWeightedRaw
                        scBgLevel = v
                        v
                    } else {
                        baseline
                    }
                // NAMED insulin, so it stays insulin. Reporting `scenario - baseline`
                // here would fold the scenario's own background feedback into a
                // field every consumer reads as «what the dose does», and the
                // persisted-bolus invariant is stated about exactly that quantity.
                val scenarioDelta = weightedScenarioInsulin
                val isfHalfRange =
                    maxOf(
                        model.insulin.isf - model.insulin.isfLow,
                        model.insulin.isfHigh - model.insulin.isf,
                    )
                val activeBolusIsfUncertainty =
                    if (model.uncertainty.includeActiveBolusIsf) {
                        abs(weightedInsulin) * isfHalfRange / model.insulin.isf
                    } else {
                        0.0
                    }
                val band =
                    model.uncertainty.sigmaPerSqrtHour *
                        sqrt((horizon + state.observationAgeMin.coerceAtLeast(0.0)) / 60.0) +
                        abs(weightedFood) * model.uncertainty.foodFraction +
                        unknownFoodUncertainty +
                        activeBolusIsfUncertainty +
                        abs(weightedScenarioInsulin) * isfHalfRange / model.insulin.isf
                // GLUCOSE FLOOR. The integrator has no lower bound of its own — food
                // is finite, but insulin action is not bounded by however much
                // glucose is left to remove. See HYBRID_FLOOR_MMOL: forecasts have
                // stored hundreds of points below zero, some inside hypo-alert runs.
                //
                // EVERYTHING is clamped, including the bottom of the uncertainty band:
                // a band going negative is the same statement, only whispered.
                HybridForecastPoint(
                    minutes = horizon,
                    tsMs = future,
                    baseline = baseline.coerceAtLeast(HYBRID_FLOOR_MMOL),
                    scenario = scenario.coerceAtLeast(HYBRID_FLOOR_MMOL),
                    low = (scenario - band).coerceAtLeast(HYBRID_FLOOR_MMOL),
                    high = (scenario + band).coerceAtLeast(HYBRID_FLOOR_MMOL),
                    foodDelta = weightedFood,
                    insulinActualDelta = weightedInsulin,
                    insulinScenarioDelta = scenarioDelta,
                    residualDrift = drift,
                    backgroundDelta = background,
                    activityDirectDelta = activityDirect,
                )
            }
        return HybridForecastResult(
            modelVersion = model.modelVersion,
            personModelId = model.personModelId,
            anchorMmol = current,
            hypotheticalInsulinUnits = hypotheticalInsulinUnits,
            points = points,
            // Counted against the RAW scenario, before clamping: after clamping
            // every value is legal and no trace of the breach remains.
            floorClampedPoints = points.count { it.scenario <= HYBRID_FLOOR_MMOL + 1e-9 },
        )
    }

    /**
     * Blind propagation with no CGM re-anchoring.
     *
     * [initialState] supplies all logged events, including events that occur
     * later in the day; causality is preserved because every kernel is zero
     * before its event timestamp. [contexts] may contain only exogenous facts
     * knowable at each step (steps/sleep), never later glucose.
     */
    fun openLoop(
        initialState: HybridForecastState,
        contexts: List<HybridOpenLoopContext>,
    ): List<HybridOpenLoopPoint> {
        require(initialState.glucoseHistory.isNotEmpty())
        val anchor =
            initialState.glucoseHistory.filter { it.tsMs <= initialState.nowMs }.maxByOrNull { it.tsMs }
                ?: error("No causal glucose anchor")
        var simulated = anchor.mmol
        var previousTs = initialState.nowMs
        return contexts
            .sortedBy { it.tsMs }
            .map { context ->
                require(context.tsMs >= previousTs) { "Open-loop contexts must be monotone" }
                val step = model.runtime.stepMin
                val state =
                    initialState.copy(
                        nowMs = context.tsMs,
                        glucoseHistory = listOf(HybridGlucosePoint(context.tsMs, simulated),),
                        activityExposure = context.activityExposure,
                        asleep = context.asleep,
                        sleepDebtHours = context.sleepDebtHours,
                        hoursSinceWake = context.hoursSinceWake,
                    )
                val future = context.tsMs + step * MINUTE_MS
                val food =
                    state.foodHistory.sumOf {
                        foodDelta(it, context.tsMs, future, context.activityExposure, state.foodHistory)
                    }
                val insulin =
                    state.bolusHistory.sumOf {
                        insulinDelta(it, context.tsMs, future, context.activityExposure)
                    }
                val background = backgroundDelta(state, simulated, 0, step).first
                simulated += food + insulin + background
                previousTs = context.tsMs
                HybridOpenLoopPoint(
                    tsMs = future,
                    mmol = simulated,
                    foodDelta = food,
                    insulinDelta = insulin,
                    backgroundDelta = background,
                )
            }
    }

    /** Contiguous blind propagation on the engine's own step grid. The
     * context timestamp is the start of each step and every emitted point is
     * exactly one [HybridRuntimeParams.stepMin] later. A partial trailing step
     * is deliberately not invented. */
    fun openLoopUntil(
        initialState: HybridForecastState,
        endExclusiveOrAtMs: Long,
        contextAt: (Long) -> HybridOpenLoopContext,
    ): List<HybridOpenLoopPoint> {
        require(endExclusiveOrAtMs >= initialState.nowMs)
        val stepMs = model.runtime.stepMin * MINUTE_MS
        val contexts =
            generateSequence(initialState.nowMs) { it + stepMs }
                .takeWhile { it + stepMs <= endExclusiveOrAtMs }
                .map(contextAt)
                .toList()
        val out = openLoop(initialState, contexts)
        check(out.withIndex().all { (i, p) -> p.tsMs == initialState.nowMs + (i + 1) * stepMs })
        return out
    }

    fun scoreOpenLoop(
        predicted: List<HybridOpenLoopPoint>,
        actual: List<HybridGlucosePoint>,
        toleranceMs: Long = 7L * MINUTE_MS,
    ): HybridOpenLoopScore {
        val facts = actual.sortedBy { it.tsMs }
        val used = BooleanArray(facts.size)
        val matched = arrayOfNulls<HybridGlucosePoint>(predicted.size)
        val exactIndices = facts.indices.groupBy { facts[it].tsMs }
        predicted.forEachIndexed { predictionIndex, point ->
            exactIndices[point.tsMs]?.firstOrNull { !used[it] }?.let { factIndex ->
                used[factIndex] = true
                matched[predictionIndex] = facts[factIndex]
            }
        }
        predicted.forEachIndexed { predictionIndex, point ->
            if (matched[predictionIndex] != null) return@forEachIndexed
            val factIndex = facts.indices
                .asSequence()
                .filterNot { used[it] }
                .minByOrNull { kotlin.math.abs(facts[it].tsMs - point.tsMs) }
                ?: return@forEachIndexed
            if (kotlin.math.abs(facts[factIndex].tsMs - point.tsMs) <= toleranceMs) {
                used[factIndex] = true
                matched[predictionIndex] = facts[factIndex]
            }
        }
        val errors = predicted.indices.mapNotNull { index ->
            matched[index]?.let { it.mmol - predicted[index].mmol }
        }
        if (errors.isEmpty()) {
            return HybridOpenLoopScore(0, null, null, null, null)
        }
        return HybridOpenLoopScore(
            n = errors.size,
            mae = errors.sumOf(::abs) / errors.size,
            rmse = sqrt(errors.sumOf { it * it } / errors.size),
            bias = errors.average(),
            endError = errors.last(),
        )
    }
}
