/**
 * Quiet successes: a correctly dosed meal produces NO spike, so the rise
 * detector never fires — and the best outcomes would stay invisible
 * (survivorship bias: food profiles would learn only from failures).
 *
 * When a food note exists and no detected meal matched it within the waiting
 * period, promote the note into a labeled meal event with the actually
 * measured (small) response and the paired bolus. A documented success.
 */
package com.diapilot.core.collector


/**
 * Promote food notes older than [waitMin] with no matching detection into
 * labeled meal events. Idempotent: an existing meal near the note (including
 * a previously promoted one) blocks re-promotion.
 *
 * @return number of newly promoted events
 */
fun promoteQuietMeals(
    store: CollectorStore,
    nowMs: Long,
    waitMin: Double = 120.0,
    lookbackMs: Long = 48L * 3_600_000,
): Int {
    val waitMs = (waitMin * 60_000).toLong()
    // isFoodNote already gates on kind=="food"/estCarbs, !isContextNote and
    // !SysLabels — a free-text observation (e.g. "slept badly") is not a quiet meal.
    val notes = store.annotations(nowMs - lookbackMs, nowMs - waitMs)
        .filter { com.diapilot.core.analysis.isFoodNote(it) }
    if (notes.isEmpty()) return 0

    val meals = store.meals(nowMs - lookbackMs - 2 * 3_600_000, nowMs)
    var promoted = 0

    for (n in notes) {
        // Matched by a detection (same window the stitcher uses)? Then the
        // meal event already exists — including previously promoted ones.
        val matched = meals.any { m ->
            m.onsetMs in (n.tsMs - 30 * 60_000)..(n.tsMs + 90 * 60_000)
        }
        if (matched) continue

        val readings = store.readings(n.tsMs - 20 * 60_000, n.tsMs + waitMs)
        val pre = readings.filter { it.tsMs <= n.tsMs }
        val post = readings.filter { it.tsMs > n.tsMs }
        // Enough CGM coverage to claim "nothing happened"?
        if (pre.isEmpty() || post.size < (waitMin / 5.0 * 0.6).toInt()) continue

        val baseline = pre.map { it.mmol }.sorted().let { it[it.size / 2] }
        val peak = post.maxByOrNull { it.mmol } ?: continue
        val bolus = store.boluses(n.tsMs - 45 * 60_000, n.tsMs + 20 * 60_000)
            .sumOf { it.units }.takeIf { it > 0 }

        val event = MealEvent(
            onsetMs = n.tsMs,
            peakMs = peak.tsMs,
            preBg = baseline,
            peakBg = peak.mmol,
            rise = peak.mmol - baseline,
            timeToPeakMin = (peak.tsMs - n.tsMs) / 60_000.0,
            bolusUnits = bolus,
            kind = if (bolus != null) MealEvent.Kind.ANNOUNCED else MealEvent.Kind.UNANNOUNCED,
        )
        store.addMealEvent(event, labelId = store.getOrCreateLabel(n.content))
        promoted++
    }
    return promoted
}
