/**
 * Physical activity detection from the heart-rate stream (Health Connect /
 * Zepp). Exercise — even a 40-minute walk after a meal — accelerates insulin
 * action and glucose uptake, so an episode overlapping activity must not
 * calibrate carb sensitivity or be trusted as "the dish was overestimated":
 * the numbers were fine, the walk ate the rise.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.HrPoint
import com.diapilot.core.collector.StepBucket

/** Notes the user logs to mean "I exercised" — a manual activity bout. Every
 *  accepted spelling: the stored keys and each language's words. */
val ACTIVITY_TAGS: Set<String> = NoteTag.formsOf(NoteTag.entries.filter { it.group == NoteTagGroup.ACTIVITY })

/**
 * Manual activity from tagged notes — a walk/workout the user logged by hand,
 * so it feeds the exercise model even when HR/steps missed it (or aren't
 * granted).
 *
 * Time semantics: the note's timestamp is the bout's START, always. The
 * composer handles direction by placing the timestamp — "starting now"
 * stamps now, "just finished" stamps now − duration — so a finished
 * walk can never create a FUTURE window feeding phantom glucose drain into
 * the forecast.
 *
 * Duration rides in the text ("walk · 40 min"); a bare tag keeps
 * [defaultMin]. Old plain-tag notes parse exactly as before.
 */
fun activityWindowsFromNotes(
    notes: List<Annotation>,
    defaultMin: Int = 40,
): List<ActivityWindow> = notes.mapNotNull { n ->
    val txt = n.content.trim().lowercase()
    val head = txt.substringBefore('·').trim()
    if (head !in ACTIVITY_TAGS) return@mapNotNull null
    val durMin = ACTIVITY_MINUTES_REGEX.find(txt)
        ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(5, 360)
        ?: defaultMin
    ActivityWindow(n.tsMs, n.tsMs + durMin * 60_000L, durMin)
}

/**
 * Resting baseline: the median of the HR stream. Sleep and sitting dominate
 * a day's readings, so the median sits near resting HR without needing an
 * explicit rest detector.
 */
fun hrBaseline(hr: List<HrPoint>): Double? {
    if (hr.size < 30) return null
    val sorted = hr.map { it.bpm }.toDoubleArray().sortedArray()
    return percentile(sorted, 50.0)
}

/**
 * Was there sustained activity inside [fromMs, toMs]? True when at least
 * [minMinutes] worth of readings sit above baseline × [factor]. Readings are
 * sparse (Zepp: ~1/min at best, less at rest), so the criterion counts
 * elevated samples, not wall-clock coverage.
 */
fun hasActivity(
    hr: List<HrPoint>,
    fromMs: Long,
    toMs: Long,
    baseline: Double,
    factor: Double = 1.25,
    minMinutes: Int = 15,
): Boolean {
    val threshold = baseline * factor
    val elevated = hr.count { it.tsMs in fromMs..toMs && it.bpm >= threshold }
    return elevated >= minMinutes
}

/**
 * Sorted HR samples + binary search, built ONCE for callers that ask about many
 * windows over one series.
 *
 * `carbEpisodes` asked [hasActivity] per meal — 2597 meals × a full scan of 60 days
 * of samples. On the phone that was **2161 ms of a 7451 ms twin build**, the largest
 * single stage after the deconvolution, and it never appeared in any profile because
 * it hides inside a `count {}`.
 *
 * Identity is free here in a way it was not for the reading index: the answer is a
 * COUNT over a predicate, and a count does not depend on order at all. Sorting is
 * done anyway rather than assumed, so a caller passing an unordered list gets the
 * same answer as before.
 */
class HrIndex private constructor(
    private val pts: List<HrPoint>,
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

    /** Samples in `fromMs..toMs` (inclusive) at or above [threshold] bpm. */
    fun countElevated(fromMs: Long, toMs: Long, threshold: Double): Int {
        if (toMs < fromMs) return 0
        val lo = lowerBound(fromMs)
        val hi = lowerBound(toMs + 1)
        var n = 0
        for (i in lo until hi) if (pts[i].bpm >= threshold) n++
        return n
    }

    companion object {
        fun of(hr: List<HrPoint>): HrIndex {
            val s = hr.sortedBy { it.tsMs }
            return HrIndex(s, LongArray(s.size) { s[it].tsMs })
        }
    }
}

/** [hasActivity] over a prebuilt [HrIndex]. Same predicate, same threshold. */
fun hasActivity(
    hr: HrIndex,
    fromMs: Long,
    toMs: Long,
    baseline: Double,
    factor: Double = 1.25,
    minMinutes: Int = 15,
): Boolean = hr.countElevated(fromMs, toMs, baseline * factor) >= minMinutes

/** A sustained-effort bout assembled from elevated HR samples. */
data class ActivityWindow(
    val startMs: Long,
    val endMs: Long,
    val elevatedMin: Int,   // samples above threshold (≈ minutes, Zepp cadence)
)

/**
 * Group elevated samples into bouts: a gap over [gapMin] closes the bout;
 * bouts shorter than [minMinutes] of elevation are noise (stairs, stress
 * spikes) and are dropped.
 */
fun sustainedHrWindows(
    hr: List<HrPoint>,
    baseline: Double,
    factor: Double = 1.25,
    minMinutes: Int = 15,
    gapMin: Int = 20,
): List<ActivityWindow> {
    val threshold = baseline * factor
    val elevated = hr.filter { it.bpm >= threshold }.sortedBy { it.tsMs }
    if (elevated.isEmpty()) return emptyList()
    val out = mutableListOf<ActivityWindow>()
    var start = elevated.first().tsMs
    var last = start
    var count = 1
    for (p in elevated.drop(1)) {
        if (p.tsMs - last > gapMin * 60_000L) {
            if (count >= minMinutes) out.add(ActivityWindow(start, last, count))
            start = p.tsMs
            count = 0
        }
        last = p.tsMs
        count++
    }
    if (count >= minMinutes) out.add(ActivityWindow(start, last, count))
    return out
}

/**
 * Activity bouts from the STEP stream — a calm walk barely lifts HR but
 * clearly moves the feet, so steps catch bouts heart rate misses. A bucket
 * counts as active when its cadence clears [minStepsPerMin] (brisk walk);
 * adjacent active buckets merge, a gap over [gapMin] closes the bout, and
 * bouts under [minMinutes] of movement are dropped as noise.
 */
fun sustainedStepWindows(
    steps: List<StepBucket>,
    /**
     * LEGACY THRESHOLD, halved from 40 to 20 together with the calibrated one.
     *
     * It was set on the same doubled step feed. Leaving it at 40 once the
     * calibrated threshold became 40 would COLLAPSE the two paths into one
     * and silently destroy the distinction the test
     * `calibratedDropsTheStrollAndKeepsTheWalk` exists to check: that the
     * legacy path fires on a shuffle around the house while the calibrated
     * one does not.
     *
     * Both thresholds are halved together, so their RATIO is preserved, and
     * with it the behavior of both paths on the corrected data.
     */
    minStepsPerMin: Double = 20.0,
    minMinutes: Int = 12,
    gapMin: Int = 20,
): List<ActivityWindow> {
    fun bucketMin(b: StepBucket) = ((b.endMs - b.startMs) / 60_000.0)
    val active = steps.filter { bucketMin(it) > 0 && it.count / bucketMin(it) >= minStepsPerMin }
        .sortedBy { it.startMs }
    if (active.isEmpty()) return emptyList()
    val out = mutableListOf<ActivityWindow>()
    var start = active.first().startMs
    var end = active.first().endMs
    var elevatedMin = bucketMin(active.first()).toInt().coerceAtLeast(1)
    for (b in active.drop(1)) {
        if (b.startMs - end > gapMin * 60_000L) {
            if (elevatedMin >= minMinutes) out.add(ActivityWindow(start, end, elevatedMin))
            start = b.startMs
            elevatedMin = 0
        }
        end = b.endMs
        elevatedMin += bucketMin(b).toInt().coerceAtLeast(1)
    }
    if (elevatedMin >= minMinutes) out.add(ActivityWindow(start, end, elevatedMin))
    return out
}

// ── The CALIBRATED activity definition — one place, four consumers ──────────
// Agreed with the user (rule 9) after the loose defaults were measured to
// over-call: a store stroll clears 40 steps/min, and the HR path at
// 1.25×median fires on plain SLEEP. On the food era, most of the detected
// windows were PHANTOM under the defaults.
//
// steps ≥40/min sustained ≥20 min — the separator is BOUT DURATION, not peak
// cadence. ⚠ READ TOGETHER WITH THE NOTE ON `ACTIVITY_STEPS_PER_MIN`: the
// previous revision said "≥80/min" and cited a measured walking pace — both
// numbers were taken on DOUBLED steps (two Health Connect sources were being
// summed, see `StepStream`). On the corrected feed the same walk runs at
// roughly half that cadence.
/**
 * How long activity keeps affecting glucose AFTER the feet stop — muscle glucose
 * uptake and refilled-glycogen sensitivity outlast the bout. ONE constant,
 * because the reach was being answered three different ways: the regime corridor
 * and the food-corpus soft contamination each hard-coded 90, while the chart lane
 * drew the RAW bout and so told a different story from the model.
 *
 * The VALUE is load-bearing, not cosmetic: on a sample pull, a meaningful
 * share of corpus episodes fall inside window+90 but not inside the raw
 * window, so their activity down-weight owes entirely to this number. It is
 * a physiology prior (the acute exercise food's own realisation runs
 * bout+45 min), not a fit — do not tune it against a forecast error without
 * saying so.
 */
const val ACTIVITY_EFFECT_TAIL_MIN = 90L

/** End of the stretch this bout still influences — the ONE answer to "how far
 *  does a walk reach". Use everywhere a consumer asks that question. */
fun ActivityWindow.effectEndMs(): Long = endMs + ACTIVITY_EFFECT_TAIL_MIN * 60_000

/**
 * Brisk-walk cadence: the floor for a real bout.
 *
 * **80 → 40, and this is a RESTORATION of previous behavior, not a new
 * calibration.** The threshold of 80 was picked on a feed where steps from
 * two Health Connect apps were being summed and so were doubled (caught by
 * comparison against a second step source, which showed roughly double the
 * daily step count; see [com.diapilot.core.collector.StepStream]). After
 * the switch to a single stream, 80 means walking twice as fast as
 * intended.
 *
 * Checked against recorded walks on the single stream, at 20-minute
 * continuity: a threshold of 80 catches essentially none of that recorded
 * walking, while 40 recovers coverage comparable to what 80 gave on the
 * doubled feed. The detector therefore behaves as it did before, and no new
 * judgment call is being made here.
 *
 * ⚠ SEPARATE FINDING, NOT FIXED: even at 40 the detector sees only a
 * fraction of recorded walking. What limits it is not the threshold but the
 * requirement [ACTIVITY_MIN_MINUTES] of twenty minutes IN A ROW: within a
 * walk, roughly half the minutes clear the threshold, but not twenty
 * consecutive ones (traffic lights, stops). Loosening that requirement is a
 * separate decision with its own measurement; a shorter continuity window
 * raises coverage but also increases the number of windows.
 */
const val ACTIVITY_STEPS_PER_MIN = 40.0
/** Sustained minutes before a bout counts — duration is the real separator. */
const val ACTIVITY_MIN_MINUTES = 20
/** ABSOLUTE HR floor for step-less cardio (bike/row/swim). A PHYSIOLOGY PRIOR,
 *  not a fit — no cardio episode exists in this record to calibrate it, so it is
 *  deliberately high enough never to fire on rest. Re-calibrate on the first
 *  logged bike/swim. Intermittent endurance (kayaking) is caught by neither the
 *  step nor this continuous-HR path — only by NOTES. */
const val ACTIVITY_HR_BPM = 120.0

/**
 * The activity detector, in one place — so the chart and the model cannot
 * disagree about when the user was moving. That desync is what was measured:
 * the forecast ran calibrated thresholds behind a toggle while the
 * WINDOWS were still legacy, and those windows feed the ISF corpus, the food
 * corpus's soft contamination and the regime corridor.
 *
 * [calibrated] = false reproduces the legacy defaults byte-for-byte (pinned by
 * `legacyModeMatchesTheOldHandRolledComposition`), so the toggle-off path is
 * unchanged.
 *
 * CALLERS TODAY: TwinCache (model + chart), FoodSources/HarnessFoods.activeFoods
 * (the v2 branch only — v1 keeps its own 24 h-baseline/12 h-slice composition on
 * purpose) and RebuiltModel. **STILL ON LEGACY THRESHOLDS, audited and
 * deliberately not changed here** — each moves a different measurement and wants
 * its own before/after: `CarbsCalibration.carbEpisodes` (builds its own baseline
 * and REJECTS outright, harsher than the down-weight fixed here) and
 * `AnalysisScreen`'s ISF card. Until those move, "one definition everywhere" is
 * the direction, not the state. (`SegmentScore` was the third name here; it was
 * deleted with the legacy twin, so that one closed by removal rather than by
 * converging.)
 *
 * NOT AN INVARIANT: calibrated ⊆ legacy holds on the current corpus (measured:
 * 0 calibrated minutes outside legacy) but is NOT a property of the code — with
 * <30 HR samples `hrBaseline` returns null so the legacy HR path is empty while
 * the absolute-120 path can still fire, and it also breaks once median HR > 96
 * (1.25× median > 120). Do not lean on "we only ever remove windows".
 */
fun detectActivityWindows(
    hr: List<HrPoint>,
    steps: List<StepBucket>,
    notes: List<Annotation>,
    calibrated: Boolean,
): List<ActivityWindow> {
    val hrW = if (calibrated) {
        sustainedHrWindows(hr, baseline = ACTIVITY_HR_BPM, factor = 1.0, minMinutes = ACTIVITY_MIN_MINUTES)
    } else {
        hrBaseline(hr)?.let { sustainedHrWindows(hr, it) } ?: emptyList()
    }
    val stepW = if (calibrated) {
        sustainedStepWindows(steps, minStepsPerMin = ACTIVITY_STEPS_PER_MIN, minMinutes = ACTIVITY_MIN_MINUTES)
    } else {
        sustainedStepWindows(steps)
    }
    return mergeActivityWindows(mergeActivityWindows(hrW, stepW), activityWindowsFromNotes(notes))
}

/**
 * Union overlapping/adjacent bouts from different sources (HR + steps) into
 * one set — a walk seen by both must not count twice. Overlapping windows
 * merge into their span with the larger elevated-minute estimate.
 */
fun mergeActivityWindows(
    a: List<ActivityWindow>,
    b: List<ActivityWindow>,
    joinGapMin: Int = 15,
): List<ActivityWindow> {
    val all = (a + b).sortedBy { it.startMs }
    if (all.isEmpty()) return emptyList()
    val out = mutableListOf<ActivityWindow>()
    var cur = all.first()
    for (w in all.drop(1)) {
        if (w.startMs <= cur.endMs + joinGapMin * 60_000L) {
            cur = ActivityWindow(
                minOf(cur.startMs, w.startMs),
                maxOf(cur.endMs, w.endMs),
                maxOf(cur.elevatedMin, w.elevatedMin),
            )
        } else {
            out.add(cur); cur = w
        }
    }
    out.add(cur)
    return out
}
