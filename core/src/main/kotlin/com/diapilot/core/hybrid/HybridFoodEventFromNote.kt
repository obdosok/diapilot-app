package com.diapilot.core.hybrid

import com.diapilot.core.analysis.RESCUE_NOTE_PREFIX
import com.diapilot.core.analysis.hasCanonicalComposition
import com.diapilot.core.analysis.mealConceptComponents
import com.diapilot.core.analysis.parseFoodKineticsV2
import com.diapilot.core.analysis.parseFoodNutrition

/**
 * The note -> model input boundary: everything the forecast is ever allowed to
 * know about a meal, derived from the annotation alone.
 *
 * MOVED FROM `HybridRuntimeMetrics.foodEvent` (app), unchanged.
 * Every call it makes was already pure core; it sat behind an Android class,
 * so any off-device stand had to rebuild it — and a rebuilt input is how a
 * measurement ends up describing a model the phone never ran (discipline #7).
 */
fun hybridFoodEventFromNote(
    tsMs: Long,
    text: String,
    carbsG: Double?,
    analysis: String?,
): HybridFoodEvent? {
    val grams = carbsG?.takeIf { it > 0.0 } ?: return null
    val nutrition = parseFoodNutrition(analysis)
    val isComposite = hasCanonicalComposition(analysis)
    val components = if (isComposite) {
        mealConceptComponents(analysis, text, grams)
            .groupingBy { it.first }
            .fold(0.0) { total, item -> total + item.second }
    } else emptyMap()
    val intakeDuration = analysis?.lineSequence()
        ?.firstOrNull { it.startsWith("META_DURATION_MIN:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.replace(',', '.')?.toDoubleOrNull()
        ?.coerceIn(0.0, 240.0) ?: 0.0
    return HybridFoodEvent(
        tsMs = tsMs,
        carbsG = grams,
        text = text,
        components = components,
        durationMin = intakeDuration,
        proteinG = nutrition.proteinG,
        fatG = nutrition.fatG,
        macroProvenance = analysis?.let { "annotation_analysis_v1" },
        kineticFeatures = parseFoodKineticsV2(analysis, nutrition.proteinG, nutrition.fatG),
        rescueTreatment = text.trim().startsWith(RESCUE_NOTE_PREFIX, ignoreCase = true),
    )
}
