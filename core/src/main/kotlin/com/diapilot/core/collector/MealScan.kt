/**
 * Orchestration: run the meal detector over stored data and persist new events.
 * Idempotent — meal events are keyed by onset_ms and existing labels are preserved,
 * so rescanning an overlapping window is safe.
 */
package com.diapilot.core.collector

/**
 * Detect meals on the stored stream in [fromMs, toMs] and persist them.
 * Returns the number of detected events (including already-known ones).
 */
fun scanMeals(
    store: CollectorStore,
    fromMs: Long,
    toMs: Long,
    cfg: MealConfig = MealConfig(),
    /** Cross-pass dedupe: a re-detected rise whose onset estimate DRIFTED
     *  (live scans run while the rise is still growing — every new point
     *  can move the estimate a few minutes) must update nothing rather
     *  than insert a sibling. Exact-onset matches still upsert. */
    dedupeWindowMs: Long = 30L * 60_000,
): Int {
    val readings = store.readings(fromMs, toMs)
    if (readings.isEmpty()) return 0
    val boluses = store.boluses(fromMs - (cfg.pairBeforeMin * 60_000).toLong(), toMs)
    val events = detectMeals(readings, boluses, cfg)
    val existing = store.meals(fromMs - dedupeWindowMs, toMs + dedupeWindowMs)
        .map { it.onsetMs }
    events
        .filter { e ->
            existing.none { it != e.onsetMs && kotlin.math.abs(it - e.onsetMs) < dedupeWindowMs }
        }
        .forEach { store.addMealEvent(it) }
    return events.size
}
