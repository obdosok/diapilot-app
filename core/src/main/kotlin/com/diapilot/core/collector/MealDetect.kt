/**
 * Meal detection from the glucose curve.
 *
 * A meal shows up as a sustained BG rise from a local trough to a peak. Each rise
 * is paired with a nearby bolus (announced meal) or flagged as unannounced. A
 * bolus during a fall is a correction, not a meal.
 *
 * Direct port of the validated Python detector (python_core/collector/meal_detect.py);
 * same curve math as the twin, complementary to the stage-0 clean-correction detector.
 */
package com.diapilot.core.collector

data class MealConfig(
    val riseThreshold: Double = 2.0,      // mmol/L, min net rise to count as a meal
    val riseWindowMin: Double = 90.0,     // max minutes trough->peak
    val minTroughGapMin: Double = 20.0,   // ignore micro-wiggles shorter than this
    val pairBeforeMin: Double = 45.0,     // bolus may precede onset (pre-bolus)
    val pairAfterMin: Double = 20.0,      // or follow onset shortly
)

data class MealEvent(
    val onsetMs: Long,
    val peakMs: Long,
    val preBg: Double,
    val peakBg: Double,
    val rise: Double,
    val timeToPeakMin: Double,
    val bolusUnits: Double?,
    val kind: Kind,
) {
    enum class Kind { ANNOUNCED, UNANNOUNCED }
}

data class GlucosePoint(val tsMs: Long, val mmol: Double)

/** An insulin shot; [purpose] is the user-assigned intent (the Russian tags for "correction" / "for a meal"). */
data class BolusPoint(val tsMs: Long, val units: Double, val purpose: String? = null)

/**
 * Detect meals on a glucose stream paired with boluses.
 * Readings may arrive unsorted; boluses are matched in a window around meal onset.
 */
fun detectMeals(
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    cfg: MealConfig = MealConfig(),
): List<MealEvent> {
    if (readings.isEmpty()) return emptyList()
    val r = readings.sortedBy { it.tsMs }
    val tsMs = LongArray(r.size) { r[it].tsMs }
    val bg = DoubleArray(r.size) { r[it].mmol }

    val events = mutableListOf<MealEvent>()
    val n = bg.size
    var i = 0
    while (i < n - 1) {
        // Find a local trough: bg[i] is a rise onset if the following points climb.
        if (i > 0 && bg[i] > bg[i - 1]) {
            i++
            continue
        }
        // Walk up while BG keeps rising (allow tiny dips), within the rise window.
        var j = i
        var peak = i
        while (j + 1 < n && (tsMs[j + 1] - tsMs[i]) <= cfg.riseWindowMin * 60_000) {
            if (bg[j + 1] >= bg[peak] - 0.2) {  // tolerate small noise
                if (bg[j + 1] > bg[peak]) peak = j + 1
                j++
            } else {
                break
            }
        }
        var rise = bg[peak] - bg[i]
        var durMin = (tsMs[peak] - tsMs[i]) / 60_000.0
        if (rise >= cfg.riseThreshold && durMin >= cfg.minTroughGapMin) {
            // Move onset to where the climb actually starts: the last point still
            // near the trough minimum before BG lifts off (handles flat plateaus).
            var troughMin = Double.MAX_VALUE
            for (k in i..peak) troughMin = minOf(troughMin, bg[k])
            var onsetIdx = i
            for (k in i until peak) {
                if (bg[k] <= troughMin + 0.3) onsetIdx = k else break
            }
            val onsetMs = tsMs[onsetIdx]
            rise = bg[peak] - bg[onsetIdx]
            durMin = (tsMs[peak] - tsMs[onsetIdx]) / 60_000.0
            events.add(
                MealEvent(
                    onsetMs = onsetMs, peakMs = tsMs[peak],
                    preBg = bg[i], peakBg = bg[peak], rise = rise,
                    timeToPeakMin = durMin,
                    bolusUnits = null,   // assigned exclusively below
                    kind = MealEvent.Kind.UNANNOUNCED,
                )
            )
            i = peak + 1  // continue after the peak (dedup overlapping rises)
        } else {
            i++
        }
    }
    // Bolus pairing, EXCLUSIVE: each shot belongs to the NEAREST onset whose
    // window covers it. Independent per-event windows double-counted a shot
    // that fell between two close meals (dinner 19:34 + snack 20:23 both
    // claimed the 19:44 bolus) — inflating both dishes' effective doses.
    if (boluses.isNotEmpty() && events.isNotEmpty()) {
        val beforeMs = (cfg.pairBeforeMin * 60_000).toLong()
        val afterMs = (cfg.pairAfterMin * 60_000).toLong()
        val sums = HashMap<Long, Double>()
        for (b in boluses) {
            events
                .filter { b.tsMs in (it.onsetMs - beforeMs)..(it.onsetMs + afterMs) }
                .minByOrNull { kotlin.math.abs(b.tsMs - it.onsetMs) }
                ?.let { sums.merge(it.onsetMs, b.units, Double::plus) }
        }
        for (idx in events.indices) {
            val u = sums[events[idx].onsetMs] ?: continue
            events[idx] = events[idx].copy(
                bolusUnits = u, kind = MealEvent.Kind.ANNOUNCED,
            )
        }
    }
    return events
}
