package io.github.obdosok.diapilot.data

import com.diapilot.core.analysis.isContextNote
import com.diapilot.core.analysis.labelStats
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.twin.ActiveFood

/**
 * Assemble the foods currently acting on BG, for the prediction and the
 * deviation detector:
 *  - detected meals from the last 4h — labeled ones use the label's learned
 *    profile, unlabeled fall back to the meal's own observed rise (or the
 *    personal average while still absorbing);
 *  - proactive food notes ("smoothie" written at drinking time) from the last
 *    2h that the detector hasn't caught yet — the prediction can warn about
 *    the rise before it even starts.
 */
object FoodSources {



    /** Mirrors `fingerprintShapedFoods`'s own `matchToleranceMs` default. */
    private const val FINGERPRINT_MATCH_TOLERANCE_MS = 90L * 60_000
    private const val FINGERPRINT_SESSION_MARGIN_MS = 24L * 3_600_000
    fun historicalFoods(
        store: CollectorStore,
        carbSens: com.diapilot.core.analysis.CarbSensitivity? = null,
        // Same scale contract as activeFoods: detector rises are raw-scale,
        // multiply by the persistent meter-cal slope when the caller works on
        // the calibrated scale (corridor calibration does now).
        riseScale: Double = 1.0,
        riseScaleFromMs: Long = 0L,
    ): List<ActiveFood> {
        val labeled = store.labeledMeals(limit = 1000).filter { it.event.onsetMs >= FoodEraSettings.current().startMs }
        val profiles = labelStats(labeled, riseScale = riseScale, riseScaleFromMs = riseScaleFromMs)
            .associateBy { it.name }
        val labelByOnset = labeled.associate { it.event.onsetMs to it.labelName }
        val meals = store.meals(FoodEraSettings.current().startMs, Long.MAX_VALUE)
        val out = meals.mapNotNull { m ->
            val label = labelByOnset[m.onsetMs]
            if (label in com.diapilot.core.analysis.SysLabels.ALL) return@mapNotNull null
            val profile = label?.let { profiles[it] }
            when {
                profile != null && profile.count >= 2 ->
                    ActiveFood(m.onsetMs, profile.avgRise, profile.avgTimeToPeakMin)
                m.rise > 0 -> ActiveFood(
                    m.onsetMs,
                    m.rise * (if (m.onsetMs >= riseScaleFromMs) riseScale else 1.0),
                    m.timeToPeakMin,
                )
                else -> null
            }
        }.toMutableList()
        // Population default (~3.6 mg/dl per gram) until the personal scale
        // exists — a rough rise beats an invisible dextrose rebound.
        val sens = carbSens?.mmolPerGram ?: 0.2
        val mealTs = meals.map { it.onsetMs }.sorted()
        store.annotations(FoodEraSettings.current().startMs, Long.MAX_VALUE)
            .filter {
                it.estCarbs != null && it.content.isNotBlank() &&
                    !isContextNote(it.content)
            }
            .filter { n -> mealTs.none { kotlin.math.abs(it - n.tsMs) < 45L * 60_000 } }
            .forEach { n ->
                val ttp = n.analysis
                    ?.let { com.diapilot.core.analysis.parseGiEstimate(it) }
                    ?.let { com.diapilot.core.analysis.giTimeToPeakMin(it) }
                    // Dextrose-fast default for small rescues, normal for meals.
                    ?: if (n.estCarbs!! <= 12.0) 25.0 else 60.0
                out.add(
                    ActiveFood(
                        n.tsMs, n.estCarbs!! * sens, ttp,
                        knownAtMs = n.carbsKnownAtMs ?: Long.MAX_VALUE,
                        onsetLagMin = com.diapilot.core.analysis.FOOD_ONSET_LAG_MIN,
                    ),
                )
            }
        return out
    }
}
