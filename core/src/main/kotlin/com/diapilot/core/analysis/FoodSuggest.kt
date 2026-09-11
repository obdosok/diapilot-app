/**
 * Food-logging suggestions: turn "enter what you ate" into "confirm what the
 * app already knows".
 *
 * The user's diet is a timetable — measured on real data, the daily smoothie
 * lands at 12:48 ± 12 min, breakfast at ~14:06 ± 30 min. So when something
 * signals an unlogged meal (a detected rise, or a meal-sized pen dose with no
 * food note near it), the dish is usually PREDICTABLE from the hour alone.
 * Every removed tap feeds the data flywheel: the deconvolution corpus and the
 * per-dish profiles only learn from logged meals.
 *
 * This engine only SUGGESTS. Nothing is auto-logged: a suggestion becomes a
 * food note when the user accepts it (inbox on the Today screen / notification
 * button) — the no-auto-teach rule stands.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.MealEvent
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp

/** A dish predicted for a moment in time, from the user's own note history. */
data class DishPrediction(
    val dish: String,       // most recent wording of the cluster
    val grams: Double?,     // median estCarbs over the cluster (null = unknown)
    val nHistory: Int,      // notes backing the prediction
)

data class FoodSuggestion(
    /** When the food likely happened — the trigger's own time, NOT "now". */
    val triggerTsMs: Long,
    /** What made the app ask: a detected rise or a meal-sized dose, with no note. */
    val trigger: SuggestTrigger,
    /** Dose units when the trigger is (or includes) a bolus. */
    val units: Double?,
    /** Time-of-day prediction; null = no confident guess (still worth asking). */
    val prediction: DishPrediction?,
)

/** Why a food suggestion was raised; the app renders the word. */
enum class SuggestTrigger {
    /** The meal detector saw a rise (possibly with a dose next to it). */
    DETECTED_RISE,
    /** A meal-sized dose with no rise detected. */
    DOSE,
}

private const val NOTE_COVER_MS = 45L * 60_000     // a note this close = already logged
private const val MERGE_MS = 45L * 60_000          // bolus+detect this close = one meal
private const val MIN_MEAL_DOSE_U = 2.0            // below = correction-sized, not a meal
private const val HOUR_SIGMA = 1.5                 // score falls off over ~1.5h
private const val MAX_HOUR_DIST = 2.5              // beyond = no prediction at all
private const val MIN_CLUSTER_N = 2                // one occurrence is not a habit

/** Circular hour distance (23.5 vs 0.5 = 1.0, not 23.0). */
private fun hourDist(a: Double, b: Double): Double {
    val d = abs(a - b) % 24.0
    return if (d > 12.0) 24.0 - d else d
}

private fun hourOf(tsMs: Long, zone: ZoneId): Double {
    val t = Instant.ofEpochMilli(tsMs).atZone(zone)
    return t.hour + t.minute / 60.0
}

/**
 * Predict the dish for [atMs] from the food-note history: cluster notes by
 * normalized name, score clusters by frequency × closeness of their median
 * hour to the target hour. Rescue carbs are excluded — dextrose has its own
 * one-tap button and must never be "predicted".
 */
fun predictDishAt(
    notes: List<Annotation>,
    atMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): DishPrediction? {
    val food = notes.filter {
        isFoodNote(it) && !isRescueNote(it.content)
    }
    if (food.isEmpty()) return null
    val targetHour = hourOf(atMs, zone)
    val clusters = food.groupBy { normalizeFoodName(it.content) }.values
    var best: Triple<Double, List<Annotation>, Double>? = null   // score, cluster, medHour
    for (c in clusters) {
        if (c.size < MIN_CLUSTER_N) continue
        val hours = c.map { hourOf(it.tsMs, zone) }.sorted()
        val medHour = hours[hours.size / 2]
        val dist = hourDist(targetHour, medHour)
        if (dist > MAX_HOUR_DIST) continue
        val score = c.size * exp(-(dist / HOUR_SIGMA) * (dist / HOUR_SIGMA))
        if (best == null || score > best!!.first) best = Triple(score, c, medHour)
    }
    val cluster = best?.second ?: return null
    val grams = cluster.mapNotNull { it.estCarbs }.sorted()
        .takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
    val dish = cluster.maxByOrNull { it.tsMs }!!.content
    return DishPrediction(dish = dish, grams = grams, nHistory = cluster.size)
}

/**
 * Unlogged-meal suggestions over the last [lookbackMs]. Triggers:
 *  - a meal-sized bolus (>= [MIN_MEAL_DOSE_U], intent null or [BolusPurpose.MEAL]) with no
 *    food note within ±45 min — the user dosed for food and skipped the note;
 *  - a DETECTED meal (deviation detector) with no food note within ±45 min.
 * A bolus and a detection within 45 min of each other are ONE meal (the
 * detection's onset wins as the food time; the dose tags along for display).
 */
fun suggestFood(
    nowMs: Long,
    notes: List<Annotation>,
    detectedMeals: List<MealEvent>,
    boluses: List<BolusPoint>,
    lookbackMs: Long = 3L * 3_600_000,
    zone: ZoneId = ZoneId.systemDefault(),
): List<FoodSuggestion> {
    val from = nowMs - lookbackMs
    val foodTs = notes.filter { isFoodNote(it) }.map { it.tsMs }
    fun covered(ts: Long) = foodTs.any { abs(it - ts) <= NOTE_COVER_MS }

    val mealTriggers = detectedMeals
        .filter { it.onsetMs in from..nowMs && !covered(it.onsetMs) }
    val doseTriggers = boluses
        .filter {
            it.tsMs in from..nowMs && it.units >= MIN_MEAL_DOSE_U &&
                (it.purpose == null || BolusPurpose.of(it.purpose) == BolusPurpose.MEAL) && !covered(it.tsMs)
        }

    val out = mutableListOf<FoodSuggestion>()
    val usedDoses = mutableSetOf<Long>()
    for (m in mealTriggers) {
        val dose = doseTriggers.firstOrNull { abs(it.tsMs - m.onsetMs) <= MERGE_MS }
        dose?.let { usedDoses.add(it.tsMs) }
        out.add(
            FoodSuggestion(
                triggerTsMs = m.onsetMs, trigger = SuggestTrigger.DETECTED_RISE, units = dose?.units,
                prediction = predictDishAt(notes, m.onsetMs, zone),
            ),
        )
    }
    for (d in doseTriggers) {
        if (d.tsMs in usedDoses) continue
        out.add(
            FoodSuggestion(
                triggerTsMs = d.tsMs, trigger = SuggestTrigger.DOSE, units = d.units,
                prediction = predictDishAt(notes, d.tsMs, zone),
            ),
        )
    }
    return out.sortedByDescending { it.triggerTsMs }
}

/**
 * The newest CANONICAL composition logged for each dish, keyed by normalized
 * name.
 *
 * A repeat dish is repeat COMPOSITION: breakfast is the same hummus + scramble
 * + two slices of bread every day. Quick-add paths (frequent chips, the
 * suggestion inbox, notification buttons) used to log the NAME only, so the
 * user had to retype the whole split in the editor afterwards — which is
 * exactly the friction that stops the diary from being complete, and the diary
 * is what the food model learns from.
 *
 * Carrying the previous note's analysis forward keeps the meal's COMPOSITION (and
 * therefore its concept fingerprint) intact for one tap. Callers MUST take the
 * carbs from the SAME note (see [DishRecall]) — pairing a recalled COMPOSITION with
 * a differently-sourced total would make the two contradict each other.
 */
data class DishRecall(
    val dish: String,       // the note's own wording (canonical for this dish)
    val analysis: String,   // the composition block (see [COMPONENT_LINE_PREFIX]), verbatim
    val grams: Double?,     // that note's total — must match the analysis
)

private fun hasReusableDishAnalysis(analysis: String?): Boolean =
    hasCanonicalComposition(analysis) || analysis?.lineSequence()?.any {
        it.trim().startsWith("$FOOD_KINETICS_PREFIX_V2:", ignoreCase = true)
    } == true

fun lastCompositions(notes: List<Annotation>): Map<String, DishRecall> =
    notes.asSequence()
        // Atomic repeats (juice, smoothie, beer) can own structured kinetics
        // without having a component list. Reuse structured facts only, never
        // arbitrary old LLM prose.
        .filter { isFoodNote(it) && hasReusableDishAnalysis(it.analysis) }
        .sortedBy { it.tsMs }                       // later wins over earlier
        .associate { n ->
            normalizeFoodName(n.content) to DishRecall(n.content, n.analysis!!, n.estCarbs)
        }

/** Composition remembered for [name], if this dish was ever logged with one. */
fun recallComposition(name: String, byName: Map<String, DishRecall>): DishRecall? =
    byName[normalizeFoodName(name)]
