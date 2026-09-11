/**
 * Making context annotations pay off:
 *  - stitch a proactive food note ("smoothie" written at drinking time) to the
 *    meal the detector finds 20-40 minutes later — one-tap labeling;
 *  - descriptive stats: how glycemia behaves in the hours after a recurring
 *    context tag (alcohol, sleep debt, exercise) versus the overall baseline.
 *
 * Descriptive only — associations on the user's own data, not medical claims.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.MealEvent

/**
 * Notes that describe circumstances rather than food — never suggested as a
 * meal label. Mirrors the composer's starter tags + bolus intent tags.
 * Every accepted spelling: the stored keys and each language's words
 * (see [NoteTag], [BolusPurpose]).
 */
val CONTEXT_TAGS: Set<String> =
    NoteTag.formsOf(
        NoteTag.entries.filter { it.group == NoteTagGroup.CONTEXT } + NoteTag.WORKOUT + NoteTag.WALK,
    ) + BolusPurpose.ALL_FORMS

/**
 * Is this note CONTEXT (incl. an activity bout), not food/free-form?
 * Matches on the head before "·" — activity notes carry a duration suffix
 * ("walk · 150 min") and must never read as a dish. Covers
 * [ACTIVITY_TAGS] too (run/gym/bike aren't in CONTEXT_TAGS).
 * Every "is it food?" exclusion filter must use THIS, not an exact-match
 * lookup — the exact match let suffixed notes leak into food paths.
 */
fun isContextNote(content: String): Boolean {
    val head = content.substringBefore('·').trim().lowercase()
    return head in CONTEXT_TAGS || head in ACTIVITY_TAGS || head in ABSORPTION_SLOW_TAGS
}

/**
 * System meal categories — semantic labels for a detected rise that is not a
 * new meal. Stored as ordinary labels but treated specially by analytics:
 * neither feeds food profiles; DAWN is excluded from the prediction too.
 */
object SysLabels {
    /** Previous meal outlived its insulin — under-dose or late bolus signal. */
    const val CONTINUATION = "continuation"

    /** Dawn phenomenon — a real rise, but not food. Same key as [NoteTag.DAWN]. */
    const val DAWN = "dawn"

    /** Adrenaline rise: intense exercise makes the liver dump glycogen.
     *  Real, explained, not food — never absorption to project forward. */
    const val SPORT = "sport_adrenaline"

    /** An honest mystery: the rise is real, the cause unknown (fat tail from
     *  yesterday, basal shortfall, stress…). Kept, never fed to food models. */
    const val UNKNOWN = "unknown"

    /** The stored keys. */
    val KEYS: Set<String> = setOf(CONTINUATION, DAWN, SPORT, UNKNOWN)

    /** Words people type for each key. RU are also the labels older builds stored. */
    val WORDS: Map<LabelLanguage, Map<String, List<String>>> = mapOf(
        LabelLanguage.EN to mapOf(
            CONTINUATION to listOf("meal continued"),
            DAWN to listOf("dawn phenomenon"),
            SPORT to listOf("exercise/adrenaline"),
            UNKNOWN to listOf("don't know"),
        ),
        LabelLanguage.RU to mapOf(
            CONTINUATION to listOf("продолжение еды"),
            DAWN to listOf("утренняя заря"),
            SPORT to listOf("спорт/адреналин"),
            UNKNOWN to listOf("не знаю"),
        ),
    )

    private val BY_FORM: Map<String, String> = buildMap {
        KEYS.forEach { put(it, it) }
        WORDS.values.forEach { byKey -> byKey.forEach { (k, ws) -> ws.forEach { put(it.lowercase(), k) } } }
    }

    /**
     * Every accepted spelling of a system label: the keys and each language's
     * words, so `label in ALL` holds for a label stored by any build.
     */
    val ALL: Set<String> = BY_FORM.keys

    /** The key [label] names, or null for an ordinary (dish) label. */
    fun keyOf(label: String?): String? = label?.trim()?.lowercase()?.let(BY_FORM::get)

    /** The stored form: a system label becomes its key, a dish name stays as it is. */
    fun canonical(label: String): String = keyOf(label) ?: label
}

/**
 * How often each food's absorption outlives its insulin: for every meal
 * labeled CONTINUATION, credit the nearest preceding non-system labeled meal
 * within [windowMs]. Repeats per label are the "dose was short for this dish"
 * signal.
 */
fun continuationStats(
    labeled: List<com.diapilot.core.collector.LabeledMeal>,
    windowMs: Long = 5L * 3_600_000,
): List<Pair<String, Int>> {
    val continuations = labeled.filter { SysLabels.keyOf(it.labelName) == SysLabels.CONTINUATION }
    val foods = labeled.filter { it.labelName !in SysLabels.ALL }
    return continuations.mapNotNull { c ->
        foods.filter { it.event.onsetMs < c.event.onsetMs && c.event.onsetMs - it.event.onsetMs <= windowMs }
            .maxByOrNull { it.event.onsetMs }?.labelName
    }.groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }
}

/**
 * Suggest a label for a detected meal from a nearby annotation: the food was
 * typically noted at eating time, the rise starts within the following hour.
 * Window: [onset − 90 min, onset + 30 min], nearest note wins. When the
 * nearest note belongs to a multi-note session (starter + main course), the
 * COMPOSED session name is suggested — the rise came from the whole meal,
 * so the episode should learn the combo, not one course of it.
 */
fun suggestMealLabel(
    meal: MealEvent,
    annotations: List<Annotation>,
    beforeMs: Long = 90L * 60_000,
    afterMs: Long = 30L * 60_000,
): String? {
    val nearest = annotations
        .filter {
            it.tsMs in (meal.onsetMs - beforeMs)..(meal.onsetMs + afterMs) &&
                isFoodNote(it)   // food only — a free-text note is not a dish name
        }
        .minByOrNull { kotlin.math.abs(it.tsMs - meal.onsetMs) } ?: return null
    val session = sessionOf(groupMealSessions(annotations), nearest.id)
    return session?.takeIf { it.isComposite }?.composedName ?: nearest.content
}

data class ContextStats(
    val tag: String,
    val count: Int,           // occurrences of the tag
    val nAfter: Int,          // readings inside the after-windows
    val meanAfter: Double,
    val tirAfter: Double,     // % in [lo, hi]
    val meanBaseline: Double,
    val tirBaseline: Double,
)

/**
 * For each recurring annotation text: glycemia over [ts, ts + windowMs] after
 * every occurrence vs the overall baseline. Honest small-n reporting is the
 * caller's job (count is included).
 */
fun contextStats(
    readings: List<GlucosePoint>,
    annotations: List<Annotation>,
    windowMs: Long = 12L * 3_600_000,
    minCount: Int = 2,
    lo: Double = 3.9,
    hi: Double = 10.0,
): List<ContextStats> {
    if (readings.isEmpty()) return emptyList()
    val r = readings.sortedBy { it.tsMs }
    val baseMean = r.sumOf { it.mmol } / r.size
    val baseTir = 100.0 * r.count { it.mmol in lo..hi } / r.size

    return annotations
        .filter { it.content.isNotBlank() && NoteTag.of(it.content) != NoteTag.PHOTO }
        .groupBy { it.content }
        .filter { it.value.size >= minCount }
        .mapNotNull { (tag, notes) ->
            val windows = notes.map { it.tsMs..(it.tsMs + windowMs) }
            val after = r.filter { p -> windows.any { w -> p.tsMs in w } }
            if (after.size < 12) return@mapNotNull null  // < 1h of data — noise
            ContextStats(
                tag = tag,
                count = notes.size,
                nAfter = after.size,
                meanAfter = after.sumOf { it.mmol } / after.size,
                tirAfter = 100.0 * after.count { it.mmol in lo..hi } / after.size,
                meanBaseline = baseMean,
                tirBaseline = baseTir,
            )
        }
        .sortedByDescending { it.count }
}
