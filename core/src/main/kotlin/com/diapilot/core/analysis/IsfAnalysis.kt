/**
 * Personal ISF (Insulin Sensitivity Factor) from clean correction episodes.
 *
 * Direct port of the validated Python stage-0 core (episodes.py + analysis.py).
 * A candidate is any bolus; filters apply in a fixed order and a candidate is
 * counted under the FIRST reason that rejects it, so the rejection table plus
 * found episodes sums to the number of candidates.
 *
 * Stage-1 adaptation: the carbs filter uses detected meal onsets (this user's
 * carbs are never logged — meal detection is the food signal).
 *
 * Research observations only — never dosing advice.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import java.time.Instant
import java.time.ZoneId

data class IsfConfig(
    val minDose: Double = 0.5,             // U
    val maxDose: Double? = null,           // U; above -> likely meal bolus
    val startBg: Double = 8.0,             // mmol/L
    val hypoThreshold: Double = 3.9,       // mmol/L
    val carbWindowMin: ClosedRange<Double> = -120.0..240.0,   // food around t0
    val bolusWindowMin: ClosedRange<Double> = -180.0..240.0,  // other boluses
    val preWindowMin: Double = 15.0,       // [t0-15min, t0]
    val endWindowMin: ClosedRange<Double> = 180.0..240.0,     // [t0+3h, t0+4h]
    val obsWindowMin: Double = 240.0,      // [t0, t0+4h]
    val expectedStepMin: Double = 5.0,     // CGM cadence
    val minCoverage: Double = 0.8,
    /**
     * Boluses this close together (minutes) are one correction dosed in parts —
     * a pen habit (e.g. 2.0U then 1.5U twenty minutes later). They merge into a
     * single candidate (summed dose, t0 of the first shot) before the isolation
     * filter. 0 disables merging (strict stage-0 behavior).
     */
    val clusterGapMin: Double = 30.0,
    /**
     * Trust the user's correction intent tag: its semantics IS "high BG
     * away from food", so a tagged bolus bypasses the detected-food filter
     * (the detector may see a false rise nearby). All other filters —
     * isolation, coverage, hypo — still apply.
     */
    val trustCorrectionTag: Boolean = false,
    /**
     * Unlogged-food guard. A real correction only LOWERS glucose; if BG
     * climbs this far ABOVE the pre-bolus level within two hours, unlogged
     * food is acting and the measured ISF would look falsely weak. Objective
     * evidence — it overrides the correction tag (a hard rise is harder proof
     * than a tag). mmol/L.
     */
    val maxPostBolusRiseMmol: Double = 1.8,
)

/** Time-of-day slot; the app renders its label ("night 00–06"). */
enum class TodBucket(val range: IntRange) {
    NIGHT(0..5),
    MORNING(6..11),
    DAY(12..17),
    EVENING(18..23);

    companion object {
        fun of(hour: Int): TodBucket = entries.firstOrNull { hour in it.range } ?: NIGHT
    }
}

/** How much the ISF estimate rests on; the app renders its label. */
enum class Confidence {
    INSUFFICIENT,   // < 5 valid episodes
    PRELIMINARY,    // 5..14
    STABLE;         // >= 15

    companion object {
        fun of(n: Int): Confidence = when {
            n < 5 -> INSUFFICIENT
            n < 15 -> PRELIMINARY
            else -> STABLE
        }
    }
}

data class IsfEpisode(
    val t0Ms: Long,
    val dose: Double,
    val bgStart: Double,    // mmol/L, median of [t0-15min, t0]
    val bgEnd: Double,      // mmol/L, median of [t0+3h, t0+4h]
    val isf: Double,        // mmol/L per U
    val anomaly: Boolean,   // isf <= 0
    val tod: TodBucket,
    /** Bolus fell in the sensitization TAIL after a long bout (3–12h): the
     *  measured drop is amplified by post-exercise sensitivity. Kept (an
     *  active lifestyle must still be able to learn ISF) but DOWNWEIGHTED
     *  by the caller — never rejected, never full-weight. */
    val postActivityTail: Boolean = false,
    /** Bolus landed INSIDE a bout or within 3h of one, where muscle drain adds
     *  to the insulin drop so the measured ISF reads too STRONG. Was an outright
     *  rejection until this was relaxed, which cost 3 of 8 confirmed corrections. */
    val activityContaminated: Boolean = false,
)

data class IsfDetection(
    val episodes: List<IsfEpisode>,
    val rejections: Map<String, Int>,
    val nCandidates: Int,
    /** Per-candidate verdict: (bolus t0, first reason that rejected it). The
     *  aggregate [rejections] map says how many died of what, never WHICH shot —
     *  so anyone asking "why was MY correction thrown out?" had to guess from a
     *  context line, and that guess was wrong at least once. Appended last with a
     *  default: positional callers must not break. */
    val rejectedAt: List<Pair<Long, String>> = emptyList(),
)

// Rejection reasons, in the order filters are applied (mirrors REASONS).
// The codes are language-neutral; the app renders them (i18n.IsfText).
object Reject {
    const val DOSE_TOO_SMALL = "dose_too_small"
    const val DOSE_TOO_LARGE = "dose_too_large"
    const val OTHER_BOLUS_IN_WINDOW = "other_bolus_in_window"
    const val FOOD_IN_WINDOW = "food_in_window"
    const val NO_PRE_BOLUS_READINGS = "no_pre_bolus_readings"
    const val START_BG_TOO_LOW = "start_bg_too_low"
    const val INSUFFICIENT_CGM_COVERAGE = "insufficient_cgm_coverage"
    const val HYPO_IN_WINDOW = "hypo_in_window"
    const val NO_END_WINDOW_READINGS = "no_end_window_readings"
    const val LIKELY_UNLOGGED_FOOD = "likely_unlogged_food"
    const val ACTIVITY_IN_WINDOW = "activity_in_window"

    /** Every code, for renderers that must cover them all. */
    val ALL = listOf(
        DOSE_TOO_SMALL, ACTIVITY_IN_WINDOW, DOSE_TOO_LARGE, OTHER_BOLUS_IN_WINDOW,
        FOOD_IN_WINDOW, NO_PRE_BOLUS_READINGS, START_BG_TOO_LOW, INSUFFICIENT_CGM_COVERAGE,
        HYPO_IN_WINDOW, NO_END_WINDOW_READINGS, LIKELY_UNLOGGED_FOOD,
    )
}

data class IsfAggregate(
    val nValid: Int,
    val nAnomalies: Int,
    val confidence: Confidence,
    val median: Double?,
    val q1: Double?,
    val q3: Double?,
) {
    val iqr: Double? get() = if (q1 != null && q3 != null) q3 - q1 else null
}

/** numpy-compatible percentile (linear interpolation). */
internal fun percentile(sorted: DoubleArray, q: Double): Double {
    require(sorted.isNotEmpty())
    if (sorted.size == 1) return sorted[0]
    val pos = (sorted.size - 1) * q / 100.0
    val lo = pos.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    val frac = pos - lo
    return sorted[lo] * (1 - frac) + sorted[hi] * frac
}

private fun median(values: DoubleArray): Double = percentile(values.sortedArray(), 50.0)

/**
 * Detect clean correction episodes.
 *
 * @param readings glucose stream, mmol/L (any order)
 * @param boluses insulin events
 * @param foodOnsetsMs onsets of detected meals — the food filter
 * @param zone local timezone for time-of-day buckets
 */
fun detectIsfEpisodes(
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    /** LOGGED food evidence (grams notes / food annotations): ground truth,
     *  always disqualifying — a real meal near a bolus mixes carbs into the
     *  measured drop no matter how the shot was tagged. */
    foodOnsetsMs: List<Long>,
    cfg: IsfConfig = IsfConfig(),
    zone: ZoneId = ZoneId.systemDefault(),
    /** Exercise contaminates an ISF episode in BOTH directions: during a
     *  bout the muscle drain adds to the insulin drop, and after a LONG bout
     *  the sensitization tail (glycogen refill) keeps amplifying it into the
     *  night — a nocturnal correction after an evening 2.5h walk measures
     *  insulin+walk, not insulin, and inflates the learned ISF. Episodes
     *  whose bolus lands inside a window or its after-tail are rejected. */
    activityWindows: List<ActivityWindow> = emptyList(),
    /** DETECTED "meals" (the deviation detector's inferred onsets): a heuristic
     *  rise that a correction on a high can mimic. A user correction tag on the
     *  bolus overrides these (see [IsfConfig.trustCorrectionTag]); the objective
     *  post-bolus-rise guard still rejects any real unlogged food. Kept last +
     *  defaulted so positional callers (walk-forward) are unaffected. */
    detectedFoodOnsetsMs: List<Long> = emptyList(),
): IsfDetection {
    val r = readings.sortedBy { it.tsMs }
    val bgTs = LongArray(r.size) { r[it].tsMs }
    val bgVal = DoubleArray(r.size) { r[it].mmol }
    val bList = clusterBoluses(boluses.sortedBy { it.tsMs }, cfg.clusterGapMin)
    val food = foodOnsetsMs.sorted()
    val detFood = detectedFoodOnsetsMs.sorted()

    val episodes = mutableListOf<IsfEpisode>()
    val rejections = linkedMapOf<String, Int>()
    val rejectedAt = mutableListOf<Pair<Long, String>>()
    fun reject(reason: String, atMs: Long) {
        rejections[reason] = (rejections[reason] ?: 0) + 1
        rejectedAt += atMs to reason
    }

    // Index of the first element >= key (numpy searchsorted side="left").
    fun lowerBound(arr: LongArray, key: Long): Int {
        var lo = 0; var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun bgSlice(loMs: Long, hiMs: Long): DoubleArray {
        val i = lowerBound(bgTs, loMs)
        var j = lowerBound(bgTs, hiMs + 1)
        return bgVal.copyOfRange(i, j)
    }

    fun Long.plusMin(min: Double): Long = this + (min * 60_000).toLong()

    for (b in bList) {
        val t0 = b.tsMs
        val dose = b.units

        if (dose < cfg.minDose) {
            reject(Reject.DOSE_TOO_SMALL, t0); continue
        }
        if (cfg.maxDose != null && dose > cfg.maxDose) {
            reject(Reject.DOSE_TOO_LARGE, t0); continue
        }

        // Other boluses in [t0-3h, t0+4h] (excluding this one).
        val bLo = t0.plusMin(cfg.bolusWindowMin.start)
        val bHi = t0.plusMin(cfg.bolusWindowMin.endInclusive)
        if (bList.any { it.tsMs in bLo..bHi && it.tsMs != t0 }) {
            reject(Reject.OTHER_BOLUS_IN_WINDOW, t0); continue
        }

        // Logged food (a grams note) is ground truth — always disqualifying.
        val cLo = t0.plusMin(cfg.carbWindowMin.start)
        val cHi = t0.plusMin(cfg.carbWindowMin.endInclusive)
        if (food.any { it in cLo..cHi }) {
            reject(Reject.FOOD_IN_WINDOW, t0); continue
        }
        // A DETECTED meal is only a heuristic rise, which a correction on a high
        // mimics. A correction-tagged shot overrides those false onsets so the
        // clean correction can teach the kernel; the objective post-bolus-rise
        // guard below still rejects it if real unlogged food actually pushed BG up.
        val trustedCorrection = cfg.trustCorrectionTag && b.purpose == "коррекция"
        if (!trustedCorrection && detFood.any { it in cLo..cHi }) {
            reject(Reject.FOOD_IN_WINDOW, t0); continue
        }

        // Activity contamination, tiered so an ACTIVE lifestyle can still
        // learn ISF: inside a bout or <3h after any bout the physics is
        // hopelessly mixed -> reject; the long-bout sensitization tail
        // (3-12h) only AMPLIFIES the drop -> keep but flag for downweight.
        // DOWN-WEIGHT, DO NOT REJECT. Rejecting outright killed 3 of the 8
        // corrections the user themselves confirmed, while a post-bout tail was
        // merely down-weighted. For someone who walks daily that asymmetry eats
        // the trusted corpus — and the trusted corpus is the ONLY thing the
        // amplitude may learn from. The physics really is mixed inside a bout,
        // so these carry a fraction of a vote (see CONTAMINATION WEIGHTS in
        // DoseRegime), never a full one and never zero.
        val actHard = activityWindows.any { w ->
            t0 in (w.startMs - 30L * 60_000)..(w.endMs + 3L * 3_600_000)
        }
        val inTail = activityWindows.any { w ->
            w.elevatedMin >= 60 &&
                t0 in (w.endMs + 3L * 3_600_000)..(w.endMs + 12L * 3_600_000)
        }

        // Pre-bolus readings: at least one point in [t0-15min, t0].
        val preVals = bgSlice(t0.plusMin(-cfg.preWindowMin), t0)
        if (preVals.isEmpty()) {
            reject(Reject.NO_PRE_BOLUS_READINGS, t0); continue
        }
        val bgStart = median(preVals)
        if (bgStart < cfg.startBg) {
            reject(Reject.START_BG_TOO_LOW, t0); continue
        }

        // CGM coverage in [t0, t0+4h].
        val obsVals = bgSlice(t0, t0.plusMin(cfg.obsWindowMin))
        val expectedPoints = cfg.obsWindowMin / cfg.expectedStepMin
        if (obsVals.size < cfg.minCoverage * expectedPoints) {
            reject(Reject.INSUFFICIENT_CGM_COVERAGE, t0); continue
        }

        // Hypo in [t0, t0+4h].
        if (obsVals.min() < cfg.hypoThreshold) {
            reject(Reject.HYPO_IN_WINDOW, t0); continue
        }

        // Unlogged-food guard: a correction only LOWERS glucose. If BG climbs
        // well above the pre-bolus level in the first two hours, unlogged food
        // is pushing up against the insulin — the ISF would look falsely weak.
        // Objective, so it applies even to a correction-tagged shot.
        val riseVals = bgSlice(t0.plusMin(20.0), t0.plusMin(120.0))
        if (riseVals.isNotEmpty() && riseVals.max() > bgStart + cfg.maxPostBolusRiseMmol) {
            reject(Reject.LIKELY_UNLOGGED_FOOD, t0); continue
        }

        // End window [t0+3h, t0+4h].
        val endVals = bgSlice(t0.plusMin(cfg.endWindowMin.start), t0.plusMin(cfg.endWindowMin.endInclusive))
        if (endVals.isEmpty()) {
            reject(Reject.NO_END_WINDOW_READINGS, t0); continue
        }
        val bgEnd = median(endVals)

        val isf = (bgStart - bgEnd) / dose
        val localHour = Instant.ofEpochMilli(t0).atZone(zone).hour
        episodes.add(
            IsfEpisode(
                t0Ms = t0, dose = dose, bgStart = bgStart, bgEnd = bgEnd,
                isf = isf, anomaly = isf <= 0, tod = TodBucket.of(localHour),
                postActivityTail = inTail,
                activityContaminated = actHard,
            )
        )
    }

    return IsfDetection(episodes, rejections, bList.size, rejectedAt)
}

/** A completed, previously unclassified bolus that looks suitable for the
 * user's one-tap "yes, this was a clean correction" confirmation. This does
 * not auto-label anything: ambiguous physiology remains a human decision. */
fun correctionConfirmationCandidate(
    nowMs: Long,
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    foodOnsetsMs: List<Long>,
    activityWindows: List<ActivityWindow> = emptyList(),
    minAgeMin: Long = 150,
    maxAgeMin: Long = 300,
): BolusPoint? {
    val r = readings.sortedBy { it.tsMs }
    fun near(t: Long, windowMin: Long = 15): Double? = r
        .filter { kotlin.math.abs(it.tsMs - t) <= windowMin * 60_000 }
        .minByOrNull { kotlin.math.abs(it.tsMs - t) }?.mmol
    return boluses.asSequence()
        .filter { it.purpose == null && it.units >= 0.5 }
        .filter { (nowMs - it.tsMs) / 60_000 in minAgeMin..maxAgeMin }
        .sortedByDescending { it.tsMs }
        .firstOrNull { b ->
            val t0 = b.tsMs
            val start = near(t0)
            val end = near(t0 + 180L * 60_000, 25)
            start != null && end != null && start >= 7.0 && start - end >= 0.5 &&
                boluses.none { it.tsMs != t0 && it.tsMs in (t0 - 90L * 60_000)..(t0 + 180L * 60_000) } &&
                foodOnsetsMs.none { it in (t0 - 90L * 60_000)..(t0 + 180L * 60_000) } &&
                activityWindows.none { it.startMs <= t0 + 180L * 60_000 && it.endMs >= t0 - 30L * 60_000 } &&
                r.filter { it.tsMs in t0..(t0 + 180L * 60_000) }.none { it.mmol < 3.9 }
        }
}

/** Merge boluses separated by <= gapMin into one dose (t0 = first shot). */
internal fun clusterBoluses(sorted: List<BolusPoint>, gapMin: Double): List<BolusPoint> {
    if (gapMin <= 0 || sorted.size < 2) return sorted
    val gapMs = (gapMin * 60_000).toLong()
    val out = mutableListOf<BolusPoint>()
    var t0 = 0L
    var lastTs = 0L
    var sum = 0.0
    // The user's intent tag survives clustering (first tagged part wins).
    var purpose: String? = null
    for (b in sorted) {
        if (sum == 0.0) {
            t0 = b.tsMs; lastTs = b.tsMs; sum = b.units; purpose = b.purpose
        } else if (b.tsMs - lastTs <= gapMs) {
            lastTs = b.tsMs; sum += b.units; purpose = purpose ?: b.purpose
        } else {
            out.add(BolusPoint(t0, sum, purpose))
            t0 = b.tsMs; lastTs = b.tsMs; sum = b.units; purpose = b.purpose
        }
    }
    if (sum > 0.0) out.add(BolusPoint(t0, sum, purpose))
    return out
}

/**
 * Anomalies (ISF <= 0) are reported but excluded from median/IQR and the
 * confidence count: a single unlogged meal would drag the aggregate down.
 */
fun aggregateIsf(episodes: List<IsfEpisode>): IsfAggregate {
    val valid = episodes.filter { !it.anomaly }.map { it.isf }.toDoubleArray().sortedArray()
    val nAnom = episodes.count { it.anomaly }
    val conf = Confidence.of(valid.size)
    if (conf == Confidence.INSUFFICIENT) {
        return IsfAggregate(valid.size, nAnom, conf, null, null, null)
    }
    return IsfAggregate(
        nValid = valid.size,
        nAnomalies = nAnom,
        confidence = conf,
        median = percentile(valid, 50.0),
        q1 = percentile(valid, 25.0),
        q3 = percentile(valid, 75.0),
    )
}

fun aggregateByTod(episodes: List<IsfEpisode>): Map<TodBucket, IsfAggregate> =
    TodBucket.entries.associateWith { bucket ->
        aggregateIsf(episodes.filter { it.tod == bucket })
    }
