/**
 * Dosing-regime changepoint — "a different body" detector.
 *
 * The user's own field observation: sensitivity shifts arrive unmarked
 * (vitamin D, a new injection site, training), but the RESPONSE to them is
 * visible in the dosing history — at some point the doses visibly dropped
 * and started splitting. Episodes from before that break describe a
 * different organism and must not outvote the current one.
 *
 * Detection: weekly median bolus dose; the latest split where the after-
 * median is at least [dropRatio] below the before-median, with at least
 * [minWeeks] qualifying weeks on each side.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint

/**
 * A break in DOSING BEHAVIOR — not proof of a changed body. Doses can drop
 * for many reasons (eating less, splitting the same total, more small
 * corrections, post-hypo caution). Whether it also reflects PHYSIOLOGY is
 * answered separately by [responseConfirmed]: the measured per-unit drop
 * (ISF) must have shifted in the same direction. Only a confirmed change
 * earns the hard pre-change discount; an unconfirmed one just informs.
 */
data class DosingBehaviorChange(
    val changeMs: Long,
    val beforeMedianU: Double,
    val afterMedianU: Double,
    /** True when clean-correction ISF also shifted (>15%) across the break —
     *  behavior AND measured response moved together. */
    val responseConfirmed: Boolean = false,
    val isfBefore: Double? = null,
    val isfAfter: Double? = null,
)

/** A persistent change measured directly from clean correction responses.
 * Unlike [DosingBehaviorChange], this does not require the user to have
 * already reacted by changing doses. */
data class SensitivityRegimeChange(
    val changeMs: Long,
    val beforeIsf: Double,
    val afterIsf: Double,
    val relativeChange: Double,
)

/** Separate the well-observed insulin ACTION SHAPE from its current
 * amplitude. Before systematic food logging, covered meals can make insulin
 * look weaker without producing a detectable rise. Fresh clean corrections
 * therefore take over the ISF amplitude gradually as they accumulate. */
data class IsfAmplitudeEstimate(
    val mmolPerUnit: Double,
    /** Trusted (food-era) corrections behind it — the only ones that count. */
    val freshEpisodes: Int,
    /** Their share of the answer; the rest is [prior], never the corpus. */
    val blend: Double,
    val prior: IsfPrior? = null,
    /** Where the trusted corrections alone centre — report it next to the
     *  result so a number carried by the prior cannot be read as measured. */
    val trustedCentre: Double = 0.0,
    /** Sum of contamination weights: three in-bout episodes are not three. */
    val effectiveTrusted: Double = 0.0,
)

/**
 * Total daily dose over the trailing [windowDays] — basal PLUS bolus, because
 * the population ISF rules are stated over the whole daily requirement and
 * bolus alone would halve it. Null only when the window holds no insulin.
 */
fun totalDailyDose(
    boluses: List<BolusPoint>,
    basals: List<BolusPoint>,
    nowMs: Long,
    windowDays: Int = 14,
): TddWindow? {
    val from = nowMs - windowDays * 86_400_000L
    val bolusDoses = boluses.filter { it.tsMs >= from }
    val basalDoses = basals.filter { it.tsMs >= from }
    if (bolusDoses.isEmpty() && basalDoses.isEmpty()) return null
    // DIVIDE BY WHAT WAS OBSERVED, NOT BY THE NOMINAL WINDOW. An account three
    // days old divided by 14 reports a third of its real TDD — and TDD is in the
    // DENOMINATOR of the prior, so understating it OVERSTATES sensitivity.
    val firstSeen = minOf(
        bolusDoses.minOfOrNull { it.tsMs } ?: Long.MAX_VALUE,
        basalDoses.minOfOrNull { it.tsMs } ?: Long.MAX_VALUE,
    )
    // +1: the first day is covered by the first dose, not by the gap after it.
    // Dividing by the bare span turns 14 daily doses over a 13-day span into a
    // 7% overstated rate — and TDD is in the denominator of the prior.
    val covered = ((nowMs - firstSeen) / 86_400_000.0 + 1.0)
        .coerceIn(1.0, windowDays.toDouble())
    return TddWindow(
        unitsPerDay = (bolusDoses.sumOf { it.units } + basalDoses.sumOf { it.units }) / covered,
        bolusUnits = bolusDoses.sumOf { it.units },
        basalUnits = basalDoses.sumOf { it.units },
        basalRecords = basalDoses.size,
        windowDays = windowDays,
        daysCovered = covered,
    )
}

data class TddWindow(
    val unitsPerDay: Double,
    val bolusUnits: Double,
    val basalUnits: Double,
    val basalRecords: Int,
    val windowDays: Int,
    /** Days of insulin history actually inside the window. */
    val daysCovered: Double = windowDays.toDouble(),
) {
    /** Fewer than one logged basal per two days of COVERAGE. */
    val basalSparse: Boolean get() = basalRecords * 2 < daysCovered
}

data class IsfPrior(
    val mmolPerUnit: Double,
    val tddUnitsPerDay: Double,
    val clamped: Boolean,
    val tddIncomplete: Boolean,
)

/**
 * Population ISF prior — the "1800 rule": ISF(mg/dl per U) = 1800 / TDD, i.e.
 * `1800 / TDD / 18` in mmol/L. 1800 rather than 1500 because the two bracket the
 * same body and the safety asymmetry decides: overstating sensitivity means
 * fewer units in What-if and an earlier alert; understating it means overdose
 * and a late alert. Clamped so a mis-logged TDD cannot produce nonsense.
 */
fun isfPriorFromTdd(tdd: TddWindow): IsfPrior? {
    // A TDD WITHOUT BASAL IS NOT A TDD — it is roughly half of one, and TDD sits
    // in the DENOMINATOR, so half a TDD doubles the prior straight into the
    // clamp. This body's `basal_events` history starts well after the bolus
    // history does: before basal logging began, the absence of basal is a
    // missing RECORD, not a missing dose, and reading it as zero made every
    // historical walk-forward fold run on an inflated prior. Refuse instead of
    // guessing — the caller then keeps the learned shape at its own scale and
    // says so.
    if (tdd.basalSparse) return null
    if (tdd.unitsPerDay < 2.0) return null
    val raw = 1800.0 / tdd.unitsPerDay / 18.0
    return IsfPrior(raw.coerceIn(0.8, 4.5), tdd.unitsPerDay, raw !in 0.8..4.5, tdd.basalSparse)
}

/**
 * CONTAMINATION WEIGHTS — a rule stated in advance, applied uniformly to every
 * axis, NOT a patch aimed at whichever episodes are currently inconvenient.
 *
 * Each axis is an a-priori statement about how a measurement is degraded:
 *
 *  - **Dose.** ISF = drop / dose, so a sensor error σ on the drop becomes σ/dose
 *    on the ISF: precision scales with the dose. Inverse-variance weighting is
 *    therefore (dose/ref)², capped at 1 so one large shot cannot dominate.
 *    A 1.0 U correction carries 16% of the vote of a 2.5 U one. This is
 *    arithmetic, not tuning.
 *  - **Activity.** Inside a bout (or <3h after) muscle drain adds to the drop,
 *    so the ISF reads too strong; the post-bout sensitisation tail does the same
 *    more weakly. Both keep a fraction of a vote, never zero — rejecting them
 *    outright cost 3 of 8 confirmed corrections.
 *
 * The rule is deliberately blind to the VALUE of the episode. Nothing here may
 * depend on how far an episode sits from the current mean: that rule is circular
 * (the mean is the number under correction) and would bury exactly the
 * corrections that exposed the problem. Only physical impossibility (isf <= 0)
 * excludes.
 */
const val ISF_REFERENCE_DOSE_U = 2.5
const val ACTIVITY_IN_BOUT_WEIGHT = 0.3

fun isfEpisodeWeight(ep: IsfEpisode): Double {
    val dose = ((ep.dose / ISF_REFERENCE_DOSE_U).coerceAtMost(1.0)).let { it * it }
    val activity = when {
        ep.activityContaminated -> ACTIVITY_IN_BOUT_WEIGHT
        ep.postActivityTail -> com.diapilot.core.PersonalParams.DEFAULT.postActivityTailWeight
        else -> 1.0
    }
    return dose * activity
}

/**
 * Weighted TRIMMED mean — not a weighted median.
 *
 * A weighted median fails badly when the weights are very unequal: with six
 * episodes at 0.3 and two at 1.0 the six never reach half the weight, so the
 * cumulative walk crosses only at the seventh value and the estimate lands on
 * the 87th percentile. Measured on the real set that was 3.37 against an
 * unweighted median of 2.21. Trimming [trim] of the weight from each tail keeps
 * the robustness to outliers without handing the answer to whichever episodes
 * happen to carry full weight.
 */
internal fun weightedTrimmedMean(pairs: List<Pair<Double, Double>>, trim: Double = 0.25): Double {
    val sorted = pairs.sortedBy { it.first }
    val total = sorted.sumOf { it.second }
    if (total <= 0.0) return sorted.map { it.first }.average()
    val lo = trim * total
    val hi = (1.0 - trim) * total
    var acc = 0.0
    var num = 0.0
    var den = 0.0
    for ((v, w) in sorted) {
        val a = acc
        val b = acc + w
        acc = b
        val keep = maxOf(0.0, minOf(b, hi) - maxOf(a, lo))
        num += v * keep
        den += keep
    }
    return if (den > 0) num / den else sorted.map { it.first }.average()
}

/**
 * THE AMPLITUDE. Trusted personal corrections against a POPULATION PRIOR —
 * never against the unconfirmed corpus.
 *
 * The old form blended against `learnedPlateau`, and removing that parameter is
 * the point of this signature: 97.5% of the corpus predates food logging, where
 * "no logged carbs in the window" passes VACUOUSLY because nothing was logged,
 * so meal boluses entered as corrections and the measured drop came out too
 * small. Four sources, and only that one says insulin is weak: rule-1500 2.15,
 * rule-1800 2.58, trusted-correction centre ~2.2-2.7, corpus 1.48.
 *
 * The corpus keeps its real job — SHAPE, where its n is what matters (49 bins).
 *
 * NOT tunable by forecast error: anchors followed by UNLOGGED food punish a
 * stronger kernel, so pooled sweeps run off toward zero insulin and their argmin
 * sits on the edge of whatever grid you try. Acceptance is the agreement of the
 * trusted sources plus the alert-path grid.
 *
 * @param foodEraStartMs when food logging became reliable. NO DEFAULT: a
 *   twenty-month sensitivity computation must not be what forgetting an argument
 *   gives you. The cut is HARD, not a recency decay — muting the blind era still
 *   left 234 episodes counting as 41.8 "effective", which is why the old
 *   estimate stalled at 1.48.
 */
fun estimateCurrentIsfAmplitude(
    episodes: List<IsfEpisode>,
    foodEraStartMs: Long,
    prior: IsfPrior,
    fullWeightEpisodes: Double = 10.0,
): IsfAmplitudeEstimate {
    val trusted = episodes
        .filter { !it.anomaly && it.isf > 0 && it.t0Ms >= foodEraStartMs }
        .map { it.isf to isfEpisodeWeight(it) }
    if (trusted.isEmpty()) return IsfAmplitudeEstimate(prior.mmolPerUnit, 0, 0.0, prior)
    val effectiveN = trusted.sumOf { it.second }
    val blend = (effectiveN / fullWeightEpisodes).coerceIn(0.0, 1.0)
    val centre = weightedTrimmedMean(trusted)
    return IsfAmplitudeEstimate(
        mmolPerUnit = prior.mmolPerUnit * (1.0 - blend) + centre * blend,
        freshEpisodes = trusted.size,
        blend = blend,
        prior = prior,
        trustedCentre = centre,
        effectiveTrusted = effectiveN,
    )
}

fun detectSensitivityRegimeChange(
    episodes: List<IsfEpisode>,
    minEpisodesPerSide: Int = 5,
    minRelativeChange: Double = 0.20,
    minCostImprovement: Double = 0.20,
): SensitivityRegimeChange? {
    val clean = episodes.filter { !it.anomaly && it.isf > 0 }.sortedBy { it.t0Ms }
    if (clean.size < 2 * minEpisodesPerSide) return null
    fun median(xs: List<IsfEpisode>): Double = xs.map { it.isf }.sorted()[xs.size / 2]
    fun cost(xs: List<IsfEpisode>, m: Double): Double = xs.sumOf { kotlin.math.abs(it.isf - m) }
    val globalMedian = median(clean)
    val globalCost = cost(clean, globalMedian)
    if (globalCost <= 1e-9) return null

    var best: SensitivityRegimeChange? = null
    var bestCost = Double.MAX_VALUE
    for (i in minEpisodesPerSide..clean.size - minEpisodesPerSide) {
        val before = clean.subList(0, i)
        val after = clean.subList(i, clean.size)
        val mb = median(before)
        val ma = median(after)
        val relative = kotlin.math.abs(ma / mb - 1.0)
        if (relative < minRelativeChange) continue
        val splitCost = cost(before, mb) + cost(after, ma)
        val improvement = (globalCost - splitCost) / globalCost
        if (improvement >= minCostImprovement && splitCost < bestCost) {
            bestCost = splitCost
            best = SensitivityRegimeChange(clean[i].t0Ms, mb, ma, ma / mb - 1.0)
        }
    }
    return best
}

fun detectDoseRegimeChange(
    boluses: List<BolusPoint>,
    minWeeks: Int = 3,
    minDosesPerWeek: Int = 5,
    dropRatio: Double = 0.7,
    // Optional confirmation: clean-correction episodes to measure the
    // per-unit ISF on each side of the break.
    episodes: List<IsfEpisode>? = null,
): DosingBehaviorChange? {
    if (boluses.isEmpty()) return null
    val weekMs = 7L * 86_400_000
    val start = boluses.minOf { it.tsMs }
    // Weekly median dose, weeks with too few doses skipped.
    val weeks = boluses.groupBy { (it.tsMs - start) / weekMs }
        .filterValues { it.size >= minDosesPerWeek }
        .mapValues { (_, ds) -> ds.map { it.units }.sorted()[ds.size / 2] }
        .toSortedMap()
    if (weeks.size < 2 * minWeeks) return null

    val keys = weeks.keys.toList()
    // Classic L1 changepoint: the split minimizing total absolute deviation
    // from each side's median — lands ON the break, not merely where a drop
    // is still detectable. Only splits with a real drop qualify.
    var best: Triple<Int, Double, Double>? = null   // split index, before, after
    var bestCost = Double.MAX_VALUE
    for (i in minWeeks..keys.size - minWeeks) {
        val before = keys.subList(0, i).map { weeks.getValue(it) }.sorted()
        val after = keys.subList(i, keys.size).map { weeks.getValue(it) }.sorted()
        val mb = before[before.size / 2]
        val ma = after[after.size / 2]
        if (mb > 0 && ma <= mb * dropRatio) {
            val cost = before.sumOf { kotlin.math.abs(it - mb) } +
                after.sumOf { kotlin.math.abs(it - ma) }
            if (cost < bestCost) {
                bestCost = cost
                best = Triple(i, mb, ma)
            }
        }
    }
    val (i, mb, ma) = best ?: return null
    val changeMs = start + keys[i] * weekMs

    // Confirmation: did the MEASURED response (ISF from clean corrections)
    // move too? Behavior alone is not physiology.
    var isfBefore: Double? = null
    var isfAfter: Double? = null
    var confirmed = false
    if (episodes != null) {
        fun medIsf(eps: List<IsfEpisode>): Double? {
            val v = eps.filter { !it.anomaly && it.isf > 0 }.map { it.isf }.sorted()
            return if (v.size >= 3) v[v.size / 2] else null
        }
        isfBefore = medIsf(episodes.filter { it.t0Ms < changeMs })
        isfAfter = medIsf(episodes.filter { it.t0Ms >= changeMs })
        if (isfBefore != null && isfAfter != null) {
            // Direction matters: the detector only finds dose DROPS, and
            // dropping doses because the body got more sensitive means the
            // per-unit response must be LARGER after. An ISF that moved the
            // other way is a conflicting signal, not a confirmation.
            confirmed = isfAfter > isfBefore * 1.15
        }
    }
    return DosingBehaviorChange(
        changeMs = changeMs,
        beforeMedianU = mb,
        afterMedianU = ma,
        responseConfirmed = confirmed,
        isfBefore = isfBefore,
        isfAfter = isfAfter,
    )
}

/** Kish effective sample size: (Σw)² / Σw². Far below the raw count when
 *  a few fresh episodes carry most of the weight. */
fun effectiveSampleSize(weights: DoubleArray): Double {
    val sum = weights.sum()
    val sumSq = weights.sumOf { it * it }
    return if (sumSq > 0) sum * sum / sumSq else 0.0
}

/**
 * Per-episode training weights: exponential recency decay (half-life
 * [halfLifeDays]) times a hard discount for pre-changepoint episodes.
 * The body of a year ago gets a vote, not a veto.
 */
fun episodeWeights(
    episodes: List<IsfEpisode>,
    nowMs: Long,
    halfLifeDays: Double = com.diapilot.core.PersonalParams.DEFAULT.kernelHalfLifeDays,
    changepointMs: Long? = null,
    // The hard discount applies ONLY to a CONFIRMED physiological change;
    // a mere behavior shift (doses split/reduced) must not veto old data.
    preChangeFactor: Double = com.diapilot.core.PersonalParams.DEFAULT.preChangepointWeight,
): DoubleArray = DoubleArray(episodes.size) { i ->
    val ageDays = (nowMs - episodes[i].t0Ms) / 86_400_000.0
    val recency = Math.pow(2.0, -(ageDays.coerceAtLeast(0.0)) / halfLifeDays)
    val era = if (changepointMs != null && episodes[i].t0Ms < changepointMs) preChangeFactor else 1.0
    // Sensitization-tail episodes (correction 3-12h after a long bout) carry
    // an amplified drop — kept so an active lifestyle still learns ISF, but
    // at a fraction of the vote (never full-weight, never zero).
    // Same contamination rule the amplitude uses — one definition, not two.
    val activity = when {
        episodes[i].activityContaminated -> ACTIVITY_IN_BOUT_WEIGHT
        episodes[i].postActivityTail ->
            com.diapilot.core.PersonalParams.DEFAULT.postActivityTailWeight
        else -> 1.0
    }
    recency * era * activity
}
