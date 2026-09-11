package com.diapilot.core.hybrid

import kotlin.math.max

const val MEAL_CLUSTER_KINETICS_VERSION_V1 = "causal-meal-cluster-throughput-v2"
const val PERSONAL_CARB_APPEARANCE_PRIOR_G_PER_HOUR_V1 = 30.0

/**
 * How fast the stomach hands its contents on, in kcal/h.
 *
 * Gastric emptying is regulated by caloric delivery, not by grams of one macro:
 * the stomach hands over its contents at a roughly constant kcal/min, so fat at
 * 9 kcal/g occupies the pipe the carbohydrate has to pass through. Modelling it
 * per gram of carb makes fat a separate additive delay, which is not what the
 * organ does.
 *
 * THE VALUE WAS 120 AND NOTHING MEASURED AT 120 — found by the wiring audit.
 * 120 was chosen so the caloric rate would equal the old flat gram
 * queue exactly (30 g of carbohydrate is 120 kcal), which made the switch a
 * no-op and was the right first step. It then stopped being the value in play:
 * the shipped app has run 180 through `PhysioTuning` for weeks, forty stand call
 * sites hardcode 180, and every conclusion from M-100 onward — the triangles,
 * the sieve, the ISF axis — was measured on 180. Only a fresh install and nine
 * stragglers were still getting 120.
 *
 * 180 is also where the queue stops binding: 180, 250, 300 and 400 give the
 * same curve to four decimals, while 60 gives 4.837 against 2.949. So this is
 * the low end of the flat region, and accelerating past it was measured and
 * REJECTED (250 lost day-paired, -0.007 with 10 days better against 7, and it
 * hurt the alert horizon 4/8).
 *
 * RAISED 120 -> 180 BY THE USER, after the audit surfaced the
 * gap. What the change costs is stated rather than hidden: eight tests pinned
 * «sieving 1.0 reproduces the gram queue», and that identity was an ARTEFACT of
 * 120 — 30 g/h x 4 kcal/g IS 120 kcal/h, so the two queues coincided by
 * construction. At 180 the caloric queue passes 45 g/h of pure carbohydrate and
 * the coincidence ends. The surviving invariant is «sieving 1.0 lets
 * carbohydrate through at rate/4 g/h», which is a property of the arithmetic
 * rather than of one chosen number, and that is what the tests now assert.
 *
 * Nothing changes on the shipped app — the pref already overrode this to 180. What
 * changes is a fresh install, the nine stands still defaulting to 120, and the
 * ability to read the running model out of the source.
 */
const val PERSONAL_EMPTYING_KCAL_PER_HOUR_V1 = 180.0

/** 4 kcal per gram of carbohydrate — the same constant [MealCaloricExtentV1] uses. */
const val KCAL_PER_CARB_GRAM_V1 = 4.0

/**
 * HOW FAR THE CARBOHYDRATE JUMPS THE QUEUE — fitted on the user's data.
 *
 * 0 = perfect mixing (a member's carbs arrive pro rata with its calories);
 * 1 = carbohydrate first, which reproduces the flat 30 g/h gram queue exactly —
 * pinned as an identity in `CarbSievingTest`. So the parameter's two ENDS are
 * the two queues that were argued over all day, and fitting it settles that
 * argument with the data instead of re-opening it.
 *
 * WHAT WAS SHIPPED BEFORE THIS FIT WAS 0 — assumed by construction, never
 * measured, and the user's own meals refuted it: on 55 multi-dish meals the model
 * still held 20-30% of the carbohydrate unarrived at +7..+9 h while the glucose
 * had fallen to 2.8-5.0 with no insulin left. Carbohydrate arriving with
 * nothing opposing it cannot coincide with a fall.
 *
 * FITTED AGAINST TWO INDEPENDENT OBSERVABLES, with the fat peak slope armed —
 * «late» is the share of meals still delivering carbohydrate at the moment
 * the excursion is demonstrably over (glucose back to baseline, no insulin
 * active, no new food); rho is Spearman against the corpus's own time to peak,
 * n=48, which knows nothing about clusters:
 *
 * | sieving | late | rho  |
 * |------|--------|------|
 * | 0.00 |   67%  | +0.53|
 * | 0.35 |   44%  | +0.52|
 * | 0.50 |   37%  | +0.52|
 * | 0.65 | **22%**| **+0.52** |
 * | 0.80 |   22%  | +0.50|
 * | 1.00 |   19%  | +0.48|
 *
 * 0.65 is where the tail evidence has essentially saturated (67% -> 22%; the
 * whole remaining gain to the far end is 3 points) while the peak evidence is
 * still at its maximum. Note the peak column only holds up because the fitted
 * fat slope came back on beside this — without it 0.65 scores +0.47. The queue
 * had been carrying duodenal feedback and sieving under one name; separated,
 * each keeps its own knob and the tail stops being wrong.
 */
const val PERSONAL_CARB_SIEVING_V1 = 0.65

// The carb rate this MEAL is entitled to, once its own calories are counted.
//
// effective g/h = carbs / (kcal / rate)  =  carbs * rate / kcal
//
// A pure-carbohydrate meal returns exactly [PERSONAL_CARB_APPEARANCE_PRIOR_G_PER_HOUR_V1]
// — 4 kcal/g cancels — so dextrose, juice and a smoothie are untouched to the
// digit. A meal carrying fat gets a narrower pipe for the same carbohydrate.
//
// Measured on real dishes: the gram limiter never binds at all (its
// floor sits below the mixture's own 90% for every dish tried), while the
// caloric one binds on breakfast +34 min, pizza +82, carbonara +68 and a joined
// meal +266.
/**
 * THE 30 g/h CAP, AND WHERE IT DOES — AND DOES NOT — APPLY. Audited.
 *
 * `PERSONAL_CARB_APPEARANCE_PRIOR_G_PER_HOUR_V1` doubles as the PRIOR for an
 * unknown meal and as a hard CAP on the rate this function returns. The number
 * is a convention rather than a limit: it matches the AAPS
 * default of 30-35 g/h and «conservative against the physiological ceiling of a
 * single transporter (~60 g/h)». Hence the parameter — the cap is arguable and
 * should be sweepable rather than welded in.
 *
 * THE PARAGRAPH THAT STOOD HERE WAS WRONG, AND IT IS WHY THIS WAS NEVER RAISED.
 * It said the cap «is NOT on the physio path» because `queueRate` «runs solely
 * in the `rate == null` branches», and concluded «raising this cap would change
 * nothing on the physio arm — bound the search, do not raise the cap». Read the code:
 * `queueRate` returns the gram prior ONLY when `emptyingKcalPerHour` is null and
 * otherwise calls this function, and five of its six call sites are unguarded.
 * The standalone curve, the batched timing scan and the horizon all go through
 * it. Only the CLUSTER path (`caloricLimitedFractionsV1`) genuinely bypasses it.
 *
 * The consequence was measured when the rate moved to 180 kcal/h:
 * a pizza's raw carb rate became 30.1 g/h against a cap of 30, so the cap
 * clipped it and sieving 0.65 became indistinguishable from 1.0 on a single
 * dish — while remaining alive in a cluster. One model, two queue treatments.
 *
 * SO THE CAP IS NOW DERIVED RATHER THAN CHOSEN. Carbohydrate cannot leave the
 * stomach faster than the stomach hands over calories, even if the meal were
 * pure carbohydrate: that ceiling is `kcalPerHour / 4`. At sieving 1.0 the
 * formula returns exactly that, so the cap touches the carbs-first end and
 * never clips anything below it. It stays a safety rail against a pathological
 * input and stops being a hidden parameter.
 *
 * The plateau above ~170 kcal/h is still real and still structural — it is the
 * queue's own «bound arrival, never manufacture it» invariant a few dozen lines
 * below. That part of the old note survives; only its claim about this cap did
 * not.
 */
fun caloricCarbRateGPerHourV1(
    carbsG: Double,
    proteinG: Double?,
    fatG: Double?,
    kcalPerHour: Double = PERSONAL_EMPTYING_KCAL_PER_HOUR_V1,
    carbSieving: Double = PERSONAL_CARB_SIEVING_V1,
    /** Derived, not chosen: the pure-carbohydrate ceiling at this rate. */
    maxRateGPerHour: Double = kcalPerHour / KCAL_PER_CARB_GRAM_V1,
): Double {
    if (carbsG <= 0.0) return PERSONAL_CARB_APPEARANCE_PRIOR_G_PER_HOUR_V1
    val kcal = carbsG * KCAL_PER_CARB_GRAM_V1 + (proteinG ?: 0.0).coerceAtLeast(0.0) * 4.0 +
        (fatG ?: 0.0).coerceAtLeast(0.0) * 9.0
    if (kcal <= 0.0) return PERSONAL_CARB_APPEARANCE_PRIOR_G_PER_HOUR_V1
    // Sieving, on a single dish: the calories the stomach must hand over before
    // THIS dish's carbohydrate is finished. sigma=1 counts only the carb
    // calories and therefore returns kcalPerHour/4 — the carbs-first ceiling,
    // which is also the cap; sigma=0 counts all of them.
    val sigma = carbSieving.coerceIn(0.0, 1.0)
    if (sigma > 0.0) {
        val basis = sigma * carbsG * KCAL_PER_CARB_GRAM_V1 + (1 - sigma) * kcal
        return (carbsG * kcalPerHour / basis).coerceIn(4.0, maxRateGPerHour)
    }
    // Floored well below the carb-only rate but not at zero: a 2000-kcal meal
    // must still finish inside the forecast horizon rather than trickle forever.
    return (carbsG * kcalPerHour / kcal).coerceIn(4.0, maxRateGPerHour)
}

/**
 * One meal in the appearance queue.
 *
 * [kcal] is what the STOMACH meters — it empties by caloric delivery, not by
 * grams of one macro. Null means «carbohydrate only», i.e. `grams * 4`, which
 * makes the caloric queue identical to the gram queue for a pure-carb meal and
 * is why the switch is safe for dextrose, juice and a smoothie.
 */
data class CarbAppearanceMemberV1(
    val id: String,
    val startMs: Long,
    val grams: Double,
    val kcal: Double? = null,
) {
    /** Metered calories. Carbohydrate alone unless the meal says otherwise. */
    val meteredKcal: Double
        get() = kcal ?: (grams * 4.0)
}

/** Fluid-queue cap for apparent carbohydrate appearance. Capacity is not
 * banked before food reaches the queue: a late portion cannot appear at once
 * merely because the stomach was idle earlier. The value is an experimental
 * personal prior, not a universal intestinal-absorption constant. */
fun throughputLimitedFractionsV1(
    members: List<CarbAppearanceMemberV1>,
    atMs: Long,
    desiredCdf: (CarbAppearanceMemberV1, Long) -> Double,
    gramsPerHour: Double = PERSONAL_CARB_APPEARANCE_PRIOR_G_PER_HOUR_V1,
    stepMin: Double = 1.0,
): Map<String, Double> {
    val usable = members.filter { it.grams > 0 && it.startMs < atMs }.sortedBy { it.startMs }
    if (usable.isEmpty()) return members.associate { it.id to 0.0 }
    val delivered = usable.associate { it.id to 0.0 }.toMutableMap()
    var cursor = usable.first().startMs
    while (cursor < atMs) {
        val next = minOf(atMs, cursor + (stepMin * MINUTE_MS).toLong().coerceAtLeast(1L))
        val wanted = usable.associate { m ->
            m.id to (m.grams * desiredCdf(m, next)).coerceIn(0.0, m.grams)
        }
        val queue = usable.associate { m ->
            m.id to (wanted.getValue(m.id) - delivered.getValue(m.id)).coerceAtLeast(0.0)
        }
        val queued = queue.values.sum()
        val capacity = gramsPerHour / 60.0 * ((next - cursor) / MINUTE_MS.toDouble())
        val served = minOf(queued, capacity)
        if (queued > 1e-9 && served > 0)
            usable.forEach { m ->
                delivered[m.id] = delivered.getValue(m.id) + served * queue.getValue(m.id) / queued
            }
        cursor = next
    }
    return members.associate { m ->
        m.id to ((delivered[m.id] ?: 0.0) / m.grams.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
    }
}

/**
 * The same queue, integrated ONCE and sampled at many instants.
 *
 * WHY IT EXISTS. [throughputLimitedFractionsV1] walks the queue minute by minute
 * from the first member's start to `atMs`, so asking it for a whole trajectory
 * re-walks the same prefix over and over. `NeighbourContributionV1` tabulates a
 * neighbour's appearance over eight hours in five-minute cells — 97 questions in
 * ascending order — which turns 480 minute-steps into ~23 000. Profiled on the
 * stand that reproduces the phone, that integration is a third of the corpus
 * build's self-time.
 *
 * IDENTICAL ARITHMETIC, NOT AN APPROXIMATION, and that is the property worth
 * protecting. The loop is a pure forward accumulation: the state at
 * `cursor == t` does not depend on whether the walk stops there or carries on.
 * Sampling as it passes each requested instant therefore returns exactly what a
 * separate call would have returned — provided the instants are reachable by the
 * step, which they are whenever they are whole multiples of `stepMin` from the
 * start. An instant that is NOT reachable is snapped by the same `minOf(atMs, …)`
 * the single-shot version applies, so the two still agree.
 *
 * [atMsList] must be ascending; the caller has it in that order already and
 * sorting here would hide a caller that does not.
 */
fun throughputLimitedFractionsSeriesV1(
    members: List<CarbAppearanceMemberV1>,
    atMsList: List<Long>,
    desiredCdf: (CarbAppearanceMemberV1, Long) -> Double,
    gramsPerHour: Double = PERSONAL_CARB_APPEARANCE_PRIOR_G_PER_HOUR_V1,
    stepMin: Double = 1.0,
): List<Map<String, Double>> {
    if (atMsList.isEmpty()) return emptyList()
    val out = ArrayList<Map<String, Double>>(atMsList.size)
    // Members that have not started by the LAST instant can never contribute to
    // any of them, so the usable set is decided once — but membership is still
    // re-checked per instant, because a member starting midway must read zero
    // before its own start exactly as the single-shot version reports.
    val ordered = members.filter { it.grams > 0 }.sortedBy { it.startMs }
    if (ordered.isEmpty()) return atMsList.map { members.associate { m -> m.id to 0.0 } }
    val delivered = ordered.associate { it.id to 0.0 }.toMutableMap()
    fun snapshot(atMs: Long): Map<String, Double> = members.associate { m ->
        m.id to
            if (m.grams <= 0 || m.startMs >= atMs) 0.0
            else ((delivered[m.id] ?: 0.0) / m.grams.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
    }
    var cursor = Long.MIN_VALUE
    atMsList.forEach { atMs ->
        val usable = ordered.filter { it.startMs < atMs }
        if (usable.isEmpty()) {
            out += snapshot(atMs);
            return@forEach
        }
        if (cursor == Long.MIN_VALUE) cursor = usable.first().startMs
        while (cursor < atMs) {
            val next = minOf(atMs, cursor + (stepMin * MINUTE_MS).toLong().coerceAtLeast(1L))
            val wanted = usable.associate { m ->
                m.id to (m.grams * desiredCdf(m, next)).coerceIn(0.0, m.grams)
            }
            val queue = usable.associate { m ->
                m.id to (wanted.getValue(m.id) - delivered.getValue(m.id)).coerceAtLeast(0.0)
            }
            val queued = queue.values.sum()
            val capacity = gramsPerHour / 60.0 * ((next - cursor) / MINUTE_MS.toDouble())
            val served = minOf(queued, capacity)
            if (queued > 1e-9 && served > 0)
                usable.forEach { m ->
                    delivered[m.id] =
                        delivered.getValue(m.id) + served * queue.getValue(m.id) / queued
                }
            cursor = next
        }
        out += snapshot(atMs)
    }
    return out
}

/**
 * The same fluid queue as [throughputLimitedFractionsV1], evaluated for many
 * chronological chart bins in one pass. Joint attribution used to replay the
 * whole minute-by-minute queue from meal start for every 15-minute CGM bin
 * (1+2+...+N work). A six-hour cluster therefore simulated its stomach dozens
 * of times. This preserves the state and snapshots it at the requested bins.
 */
fun throughputLimitedFractionTimelineV1(
    members: List<CarbAppearanceMemberV1>,
    atTimes: Collection<Long>,
    desiredCdf: (CarbAppearanceMemberV1, Long) -> Double,
    gramsPerHour: Double = PERSONAL_CARB_APPEARANCE_PRIOR_G_PER_HOUR_V1,
    stepMin: Double = 1.0,
): Map<Long, Map<String, Double>> {
    val targets = atTimes.distinct().sorted()
    if (targets.isEmpty()) return emptyMap()
    val usable =
        members.filter { it.grams > 0 && it.startMs < targets.last() }.sortedBy { it.startMs }
    if (usable.isEmpty()) return targets.associateWith { members.associate { it.id to 0.0 } }
    val delivered = usable.associate { it.id to 0.0 }.toMutableMap()
    val result = linkedMapOf<Long, Map<String, Double>>()
    var cursor = usable.first().startMs
    fun snapshot() = members.associate { m ->
        m.id to ((delivered[m.id] ?: 0.0) / m.grams.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
    }
    for (target in targets) {
        if (target <= cursor) {
            result[target] = snapshot();
            continue
        }
        while (cursor < target) {
            val next = minOf(target, cursor + (stepMin * MINUTE_MS).toLong().coerceAtLeast(1L))
            val wanted = usable.associate { m ->
                m.id to (m.grams * desiredCdf(m, next)).coerceIn(0.0, m.grams)
            }
            val queue = usable.associate { m ->
                m.id to (wanted.getValue(m.id) - delivered.getValue(m.id)).coerceAtLeast(0.0)
            }
            val queued = queue.values.sum()
            val capacity = gramsPerHour / 60.0 * ((next - cursor) / MINUTE_MS.toDouble())
            val served = minOf(queued, capacity)
            if (queued > 1e-9 && served > 0)
                usable.forEach { m ->
                    delivered[m.id] =
                        delivered.getValue(m.id) + served * queue.getValue(m.id) / queued
                }
            cursor = next
        }
        result[target] = snapshot()
    }
    return result
}

/** Name-independent facts needed to decide whether closely spaced intakes
 * still share a physiologically plausible gastric/absorption context. */
data class MealClusterMemberV1(
    val id: String,
    val startMs: Long,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double = 0.0,
    /** 90% completion and last trace of this member's standalone prior. */
    val mainEndMin: Double = 180.0,
    val tailEndMin: Double = 360.0,
)

data class MealClusterClockV1(
    val effectiveAgeMin: Double,
    val lockedBeforeLaterMeals: Boolean,
    val interactions: Int,
    val stretch: Double,
)

enum class MealTimingScopeV1 { INDIVIDUAL, COMPLETED_BEFORE_CLUSTER, CLUSTER_ONLY }
data class MealClusterAssessmentV1(
    val scope: MealTimingScopeV1,
    val memberIds: List<String>,
    val totalCarbsG: Double,
    val reason: String,
    val nextMealAtMs: Long? = null,
    val realisedFractionAtNext: Double? = null,
)

/**
 * Piecewise causal clock for one intake inside a sequence of meals.
 *
 * A later meal cannot rewrite glucose which has already appeared. At every
 * later intake boundary we first advance the owner's standalone clock, then:
 * - if <=10% remains, the owner is locked and later BJU cannot stretch it;
 * - otherwise only the still-unrealised remainder is slowed;
 * - prior active meals may slow a new owner from its start.
 *
 * This is deliberately a bounded timing interaction. It never changes grams,
 * CS or total food amplitude.
 */
fun causalMealClusterClockV1(
    owner: MealClusterMemberV1,
    atMs: Long,
    members: List<MealClusterMemberV1>,
    ownerStandaloneCdf: (Double) -> Double,
    interactionGapMin: Double = 120.0,
): MealClusterClockV1 {
    if (atMs <= owner.startMs) return MealClusterClockV1(0.0, false, 0, 1.0)
    val sorted = members.sortedBy { it.startMs }
    fun macroLoad(m: MealClusterMemberV1) =
        (m.fatG * .012 + m.proteinG * .004 + m.fiberG * .008).coerceIn(0.0, .65)
    fun approximateRemaining(m: MealClusterMemberV1, at: Long): Double {
        val age = (at - m.startMs) / 60_000.0
        if (age <= 0) return 1.0
        if (age >= m.tailEndMin) return 0.0
        if (age <= m.mainEndMin)
            return (1.0 - .90 * age / m.mainEndMin.coerceAtLeast(1.0)).coerceIn(.10, 1.0)
        val tailSpan = (m.tailEndMin - m.mainEndMin).coerceAtLeast(1.0)
        return (.10 * (1 - (age - m.mainEndMin) / tailSpan)).coerceIn(0.0, .10)
    }

    // Food still present from earlier intakes can affect the new owner's
    // remaining appearance. Its influence is weighted by its residual mass.
    var stretch =
        (1.0 +
                sorted
                    .asSequence()
                    .filter { it.startMs < owner.startMs }
                    .filter { (owner.startMs - it.startMs) / 60_000.0 <= interactionGapMin }
                    .sumOf { macroLoad(it) * approximateRemaining(it, owner.startMs) })
            .coerceIn(1.0, 2.2)
    var effective = 0.0
    var cursor = owner.startMs
    var interactions = 0
    var locked = false
    var lastClusterStart = owner.startMs
    for (next in sorted.filter { it.startMs > owner.startMs && it.startMs < atMs }) {
        if ((next.startMs - lastClusterStart) / 60_000.0 > interactionGapMin) break
        effective += (next.startMs - cursor) / 60_000.0 / stretch
        cursor = next.startMs
        val remaining = (1 - ownerStandaloneCdf(effective)).coerceIn(0.0, 1.0)
        if (remaining <= .10) {
            locked = true;
            stretch = 1.0;
            break
        }
        val added = macroLoad(next) * remaining
        if (added > 1e-6) {
            stretch = (stretch + added).coerceIn(1.0, 2.2);
            interactions++
        }
        lastClusterStart = next.startMs
    }
    effective += (atMs - cursor) / 60_000.0 / stretch
    return MealClusterClockV1(max(0.0, effective), locked, interactions, stretch)
}


/**
 * THE CLUSTER'S SHARED PIPE — one queue metering CALORIES for every meal in it.
 *
 * Review finding: the caloric limit reached a single dish and not a
 * cluster, so a pizza alone slowed down while pizza + beer fell back to the gram
 * queue — and the cluster is exactly where the measurement said it matters most
 * (a joined meal binds by +266 min against +82 for the pizza alone).
 *
 * Passing one member's rate would not have fixed it: the stomach has ONE pipe,
 * and two meals in it share the same caloric outflow rather than each getting
 * its own. So the queue meters kcal and splits the served calories across
 * whatever is currently queued.
 *
 * THE RETURNED FRACTION IS OF CARBOHYDRATE, and it is NOT the delivered share
 * of the calories. Assuming it was — perfect mixing in the stomach — is what
 * the first version did, and the user's own data refuted it: on ten 1000-1900 kcal
 * meals the model still held 20-30% of the carbohydrate unarrived at +7..+9 h
 * while the glucose fell to 2.8-5.0 with no insulin left. Carbohydrate that
 * arrives with nothing opposing it cannot coincide with a fall.
 *
 * The stomach SIEVES: the aqueous phase — sugars and liquids — passes the
 * pylorus first, fat is retained and layers back. [carbSieving] is how far
 * along that axis the user's stomach sits, and the two ends are exactly the
 * two queues that were fought over. 0 = perfect mixing (carbs pro rata by
 * calories); 1 = carbohydrate first, which reproduces the flat 30 g/h gram
 * queue to the digit. So it is one physiological parameter to fit, not a fudge
 * factor bolted onto a lost argument.
 *
 * Capacity is not banked, exactly as in the gram queue: a late portion cannot
 * appear at once because the stomach was idle earlier.
 */
fun caloricLimitedFractionsV1(
    members: List<CarbAppearanceMemberV1>,
    atMs: Long,
    desiredCdf: (CarbAppearanceMemberV1, Long) -> Double,
    kcalPerHour: Double = PERSONAL_EMPTYING_KCAL_PER_HOUR_V1,
    stepMin: Double = 1.0,
    carbSieving: Double = PERSONAL_CARB_SIEVING_V1,
): Map<String, Double> =
    caloricLimitedTimelineV1(
        members,
        listOf(atMs),
        desiredCdf,
        kcalPerHour,
        stepMin,
        carbSieving,
    )[atMs] ?: members.associate { it.id to 0.0 }

/** The same shared pipe, snapshotted at many chronological bins in one pass. */
fun caloricLimitedTimelineV1(
    members: List<CarbAppearanceMemberV1>,
    atTimes: Collection<Long>,
    desiredCdf: (CarbAppearanceMemberV1, Long) -> Double,
    kcalPerHour: Double = PERSONAL_EMPTYING_KCAL_PER_HOUR_V1,
    stepMin: Double = 1.0,
    carbSieving: Double = PERSONAL_CARB_SIEVING_V1,
): Map<Long, Map<String, Double>> {
    val targets = atTimes.distinct().sorted()
    if (targets.isEmpty()) return emptyMap()
    val usable =
        members
            .filter { it.grams > 0 && it.meteredKcal > 0 && it.startMs < targets.last() }
            .sortedBy { it.startMs }
    if (usable.isEmpty()) return targets.associateWith { members.associate { m -> m.id to 0.0 } }
    val delivered = usable.associate { it.id to 0.0 }.toMutableMap() // kcal, on the sieved basis
    val result = linkedMapOf<Long, Map<String, Double>>()
    var cursor = usable.first().startMs
    // Sieving lives HERE and nowhere else: the pipe still meters calories, but
    // the carbohydrate inside a member is read off the delivered calories on a
    // scale that runs ahead of them. sigma=0 divides by the member's whole
    // energy (perfect mixing); sigma=1 divides by its CARBOHYDRATE energy, so
    // the carbohydrate is finished once that many calories have left. Nothing
    // is created: the fat stays in the queue and keeps delaying what is behind.
    val sigma = carbSieving.coerceIn(0.0, 1.0)
    fun carbBasis(m: CarbAppearanceMemberV1): Double =
        (sigma * (m.grams * KCAL_PER_CARB_GRAM_V1) + (1 - sigma) * m.meteredKcal).coerceAtLeast(
            1e-9
        )
    // A QUEUE MAY BOUND ARRIVAL, NEVER MANUFACTURE IT. Without the min() the
    // sieved basis divides a demand expressed in whole calories by the carb
    // calories alone, so on a pizza whose pipe is not yet binding the model
    // reported 4.6% of the carbohydrate arrived where the dish's own curve says
    // 1.9% — 2.4x, exactly kcal/carbKcal, invented by the units. Caught by the
    // endpoint identity below, which is why it is written as an identity.
    //
    // REDUNDANT SINCE THE SPLIT WAS SIEVED TOO, and kept deliberately: the
    // queue now demands `carbBasis * desired`, so `delivered` can no longer
    // exceed it and removing this min() changes nothing — a mutation check
    // confirmed no test catches its removal. It stays as the invariant's
    // statement in code, not as a live guard, and this note is here so nobody
    // reads that surviving mutant as missing coverage.
    fun snapshot(t: Long) = members.associate { m ->
        m.id to
            minOf(
                    desiredCdf(m, t),
                    (delivered[m.id] ?: 0.0) / carbBasis(m),
                )
                .coerceIn(0.0, 1.0)
    }
    for (target in targets) {
        if (target <= cursor) {
            result[target] = snapshot(target);
            continue
        }
        while (cursor < target) {
            val next = minOf(target, cursor + (stepMin * MINUTE_MS).toLong().coerceAtLeast(1L))
            // Only meals that have STARTED are in the stomach.
            val present = usable.filter { it.startMs < next }
            // SIEVING GOVERNS THE SPLIT TOO, not only what each meal does with
            // its share. It is a claim about what passes the pylorus, so a
            // fat-heavy dish must not out-bid a beer for the pipe in proportion
            // to calories the sieve is holding back. Sharing by whole calories
            // let pizza pull its carbohydrate through 10% faster than the gram
            // queue at sigma=1, where the two are supposed to be the same model.
            val queue = present.associate { m ->
                m.id to
                    (carbBasis(m) * desiredCdf(m, next) - delivered.getValue(m.id)).coerceAtLeast(
                        0.0
                    )
            }
            val queued = queue.values.sum()
            val capacity = kcalPerHour / 60.0 * ((next - cursor) / MINUTE_MS.toDouble())
            val served = minOf(queued, capacity)
            if (queued > 1e-9 && served > 0)
                present.forEach { m ->
                    delivered[m.id] =
                        delivered.getValue(m.id) + served * queue.getValue(m.id) / queued
                }
            cursor = next
        }
        result[target] = snapshot(target)
    }
    return result
}