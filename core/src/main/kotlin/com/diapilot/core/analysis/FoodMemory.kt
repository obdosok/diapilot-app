/**
 * Food memory: what happened the previous times this dish was eaten —
 * effective dose (primary bolus + catch-ups), the response curve, and whether
 * the dose fell short (continuation = under-dose marker).
 *
 * Facts from the user's own history, surfaced at the moment of choice.
 * Never a dose instruction — a diary with perfect recall.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.LabeledMeal

data class FoodEpisode(
    val onsetMs: Long,
    val primaryDose: Double?,     // bolus paired by the detector
    val topUpDose: Double,        // continuations' boluses + correction-bolus-purpose shots
    val rise: Double,
    val timeToPeakMin: Double,
    val underDosed: Boolean,      // a continuation followed or a top-up was needed
    /** Minutes from the manual food entry to the BG rise onset — the dish's
     *  absorption lag. Null when no note anchors the eating moment. */
    val lagMin: Double? = null,
    /** Non-null when the measured rise is contaminated by a background force:
     *  "insulin" (IOB from a pre-meal bolus was pulling BG down while this
     *  rise was measured), "food" (another meal overlaps the window), or both.
     *  Honesty marker for the library — the rise is not the dish's pure own. */
    val background: String? = null,
    /** Portion in grams of carbs (from the eating-moment note), if known. */
    val grams: Double? = null,
    /** The dish's OWN rise (insulin-adjusted) — set when a kernel was given. */
    val ownRise: Double? = null,
) {
    val effectiveDose: Double? get() =
        if (primaryDose == null && topUpDose == 0.0) null else (primaryDose ?: 0.0) + topUpDose
}

data class FoodMemory(
    val label: String,
    val episodes: List<FoodEpisode>,   // newest first
    val avgEffectiveDose: Double?,
    val avgRise: Double,
    val underDosedCount: Int,
    val avgLagMin: Double? = null,     // how soon this dish starts raising BG
    // SHAPE vs SIZE (food-v6 groundwork): the dish's own mmol per gram of carbs,
    // from CLEAN episodes (no background insulin/food) that carry a portion.
    // Separating this from avgRise means 250 ml and 500 ml stop sharing one
    // amplitude — effect ≈ perGramRise × grams.
    val perGramRise: Double? = null,
    val perGramN: Int = 0,
    val typicalGrams: Double? = null,   // median logged portion
)

private const val EPISODE_WINDOW_MS = 5L * 3_600_000

/** Build the memory of a dish from labeled history. Null if never eaten. */
fun foodMemory(
    label: String,
    labeled: List<LabeledMeal>,
    boluses: List<BolusPoint>,
    notes: List<com.diapilot.core.collector.Annotation> = emptyList(),
    // With a kernel, each episode gets its insulin-adjusted OWN rise, and the
    // per-gram coefficient is computed from it (the dish's true amplitude).
    kernel: List<KernelPoint> = emptyList(),
): FoodMemory? {
    if (label in SysLabels.ALL) return null
    val meals = labeled.filter { it.labelName == label }.sortedByDescending { it.event.onsetMs }
    if (meals.isEmpty()) return null
    val continuations = labeled.filter { it.labelName == SysLabels.CONTINUATION }

    val episodes = meals.map { m ->
        val onset = m.event.onsetMs
        val myConts = continuations.filter {
            it.event.onsetMs > onset && it.event.onsetMs - onset <= EPISODE_WINDOW_MS
        }
        val contDose = myConts.sumOf { it.event.bolusUnits ?: 0.0 }
        // correction-bolus-purpose shots in the episode window, not already counted as
        // a continuation's paired bolus.
        val contPaired = myConts.mapNotNull { c -> c.event.bolusUnits?.let { c.event.onsetMs } }.toSet()
        val topUpShots = boluses.filter { b ->
            b.purpose == "докол" &&
                b.tsMs in (onset + 20 * 60_000)..(onset + EPISODE_WINDOW_MS) &&
                myConts.none { c -> kotlin.math.abs(b.tsMs - c.event.onsetMs) < 45 * 60_000 && c.event.onsetMs in contPaired }
        }.sumOf { it.units }
        val topUp = contDose + topUpShots
        // Absorption lag: the note (eating moment) vs the rise onset. Exact
        // content match preferred; else the nearest food-like note in the
        // stitch window (either side may have been renamed).
        val inWindow = notes.filter {
            it.content.isNotBlank() &&
                !isContextNote(it.content) &&
                it.content.lowercase() !in SysLabels.ALL &&
                it.tsMs in (onset - 90 * 60_000)..(onset + 30 * 60_000)
        }
        val note = inWindow.firstOrNull { it.content == label }
            ?: inWindow.minByOrNull { kotlin.math.abs(it.tsMs - onset) }
        val lag = note?.let { (onset - it.tsMs) / 60_000.0 }?.takeIf { it > 0 }
        val grams = note?.estCarbs?.takeIf { it > 0 }
        // Background forces contaminating the measured rise (honesty marker):
        // a PRE-meal bolus still active (≥1u within the prior 2.5h, not this
        // meal's own paired shot), or another meal overlapping the window.
        val bgInsulin = boluses.any { b ->
            b.tsMs in (onset - 150L * 60_000) until onset && b.units >= 1.0
        }
        val bgFood = labeled.any { o ->
            o.event.onsetMs != onset && o.labelName !in SysLabels.ALL &&
                o.event.onsetMs in (onset - 90L * 60_000)..(onset + 180L * 60_000)
        }
        val bg = listOfNotNull(
            "инсулин".takeIf { bgInsulin }, "еда".takeIf { bgFood },
        ).joinToString("+").ifEmpty { null }
        FoodEpisode(
            onsetMs = onset,
            primaryDose = m.event.bolusUnits,
            topUpDose = topUp,
            rise = m.event.rise,
            timeToPeakMin = m.event.timeToPeakMin,
            underDosed = myConts.isNotEmpty() || topUp > 0,
            lagMin = lag,
            background = bg,
            grams = grams,
            ownRise = if (kernel.isEmpty()) null
            else insulinAdjustedRise(m.event, boluses, kernel),
        )
    }
    // Recency-weighted: fresh repeats describe the current recipe/body.
    val nowMs = episodes.maxOf { it.onsetMs }
    val halfLifeMs = com.diapilot.core.PersonalParams.DEFAULT.foodHalfLifeDays * 86_400_000.0
    fun w(onsetMs: Long): Double =
        Math.pow(2.0, -((nowMs - onsetMs).coerceAtLeast(0L)) / halfLifeMs)
    fun weightedAvg(pairs: List<Pair<Double, Double>>): Double? =
        pairs.takeIf { it.isNotEmpty() }
            ?.let { ps -> ps.sumOf { it.first * it.second } / ps.sumOf { it.second } }
    // SHAPE vs SIZE: mmol per gram from CLEAN episodes carrying a portion.
    // Clean = no background insulin/food contamination; use the insulin-
    // adjusted own-rise when available, else the raw rise. A dish measured
    // only on contaminated episodes has no honest per-gram number.
    val clean = episodes.filter { it.background == null && (it.grams ?: 0.0) > 0 }
    val perGramPairs = clean.map { e ->
        ((e.ownRise ?: e.rise) / e.grams!!) to w(e.onsetMs)
    }
    val portions = episodes.mapNotNull { it.grams }.sorted()
    return FoodMemory(
        label = label,
        episodes = episodes,
        avgEffectiveDose = weightedAvg(
            episodes.mapNotNull { e -> e.effectiveDose?.let { it to w(e.onsetMs) } },
        ),
        avgRise = weightedAvg(episodes.map { it.rise to w(it.onsetMs) }) ?: 0.0,
        underDosedCount = episodes.count { it.underDosed },
        avgLagMin = weightedAvg(
            episodes.mapNotNull { e -> e.lagMin?.let { it to w(e.onsetMs) } },
        ),
        perGramRise = weightedAvg(perGramPairs),
        perGramN = clean.size,
        typicalGrams = portions.takeIf { it.isNotEmpty() }?.let { p ->
            if (p.size % 2 == 1) p[p.size / 2] else (p[p.size / 2 - 1] + p[p.size / 2]) / 2.0
        },
    )
}

/**
 * Dishes sharing recipe components with [label] — the curated bridge that
 * transfers experience between beer brands (both contain the "beer" component) or
 * between a breakfast and an evening sandwich (both contain the "bread"
 * component). Unlike rise/ttp
 * similarity, this works for a dish NEVER eaten: its recipe alone connects
 * it to measured history.
 *
 * [componentsByDish]: dish display name → its NORMALIZED component set
 * (a simple product maps to the singleton of its own normalized name).
 * Ranked by shared-component count, ties broken toward the simpler dish.
 */
fun similarByComponents(
    label: String,
    componentsByDish: Map<String, Set<String>>,
    limit: Int = 3,
): List<String> {
    val mine = componentsByDish[label].orEmpty()
    if (mine.isEmpty()) return emptyList()
    return componentsByDish.entries
        .filter { it.key != label }
        .map { (dish, comps) -> Triple(dish, (comps intersect mine).size, comps.size) }
        .filter { it.second > 0 }
        .sortedWith(
            compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third },
        )
        .take(limit)
        .map { it.first }
}

/**
 * Borrowed kinetics for a dish with NO measured history of its own: the
 * TIME-TO-PEAK of measured dishes sharing its recipe components, episode-
 * weighted. A new beer brand inherits the measured beer curve; a sandwich
 * inherits bread's timing from breakfasts. Magnitude is NOT borrowed — the
 * rise still comes from the meal's own grams; and a dish's own measured
 * profile always outranks this (callers try it first).
 *
 * [targetComponents] — normalized components of the new dish;
 * [donorComponents] — measured dish → its normalized components (a simple
 * product maps to its own name). Donors need [minCount]+ episodes.
 */
fun borrowedComponentTtp(
    targetComponents: Set<String>,
    donorComponents: Map<String, Set<String>>,
    stats: List<LabelStats>,
    minCount: Int = 2,
): Double? {
    if (targetComponents.isEmpty()) return null
    val statByName = stats.associateBy { it.name }
    // Per component: episode-weighted ttp across measured dishes containing
    // it; then the components pool, weighted by their evidence.
    val perComponent = targetComponents.mapNotNull { comp ->
        val donors = donorComponents.mapNotNull { (dish, comps) ->
            if (comp in comps) statByName[dish]?.takeIf { it.count >= minCount } else null
        }
        if (donors.isEmpty()) null
        else {
            val w = donors.sumOf { it.count }
            (donors.sumOf { it.avgTimeToPeakMin * it.count } / w) to w
        }
    }
    if (perComponent.isEmpty()) return null
    val totalW = perComponent.sumOf { it.second }.toDouble()
    return perComponent.sumOf { it.first * it.second } / totalW
}

/**
 * Dishes whose glucose response resembles [label]'s — similar rise and
 * time-to-peak. "Sandwiches behave like pizza" transfers experience to
 * foods eaten rarely.
 */
fun similarFoods(
    label: String,
    stats: List<LabelStats>,
    maxDistance: Double = 0.5,
    limit: Int = 3,
): List<LabelStats> {
    val target = stats.firstOrNull { it.name == label } ?: return emptyList()
    fun dist(a: LabelStats, b: LabelStats): Double {
        val dRise = (a.avgRise - b.avgRise) / 5.0        // ~5 mmol/L full scale
        val dTtp = (a.avgTimeToPeakMin - b.avgTimeToPeakMin) / 60.0
        return kotlin.math.sqrt(dRise * dRise + dTtp * dTtp)
    }
    return stats.filter { it.name != label && it.count >= 2 }
        .map { it to dist(target, it) }
        .filter { it.second <= maxDistance }
        .sortedBy { it.second }
        .take(limit)
        .map { it.first }
}
