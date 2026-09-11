package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint

/**
 * One recovered episode with its POINTS — read-only, for measurement.
 *
 * [deconvolvedMealObservations] reduces every episode to landmarks, and the
 * landmarks cannot answer «does the food contribution come back DOWN». They
 * cannot by construction: `MealObservation.tailRise` is an EXTRA rise beyond
 * the peak and is floored at zero, so a decline is not representable.
 *
 * That question is the open one about glucose clearance — the model's food term
 * is a CDF, meaning monotone, and nothing but insulin removes it. Answering it off
 * a re-derived curve would be a second model (discipline #7), so the curves come
 * from the same call the corpus is built from.
 */
data class RecoveredCurveV1(
    val anchorMs: Long,
    val points: List<DishCurvePoint>,
    val carbGrams: Double,
    val peakRiseMmol: Double,
    val ttpMin: Double,
    val peakObserved: Boolean,
    val earlyPeakBounded: Boolean,
    val lateTailBounded: Boolean,
    val confidence: Double,
)

/** The curves behind [deconvolvedMealObservations], same arguments, same call. */
fun deconvolvedSessionCurvesV1(
    notes: List<com.diapilot.core.collector.Annotation>,
    readings: List<GlucosePoint>,
    boluses: List<BolusPoint>,
    kernel: List<KernelPoint>,
    nowMs: Long,
    contaminationWindows: List<FoodContaminationWindow> = emptyList(),
    softContaminationWindows: List<FoodContaminationWindow> = emptyList(),
    maxPasses: Int = 3,
    extendThroughKnown: Boolean = false,
    carbSensPerGram: Double? = null,
    kernelForBolus: ((BolusPoint) -> List<KernelPoint>)? = null,
    /** Mirrors [deconvolvedMealObservations] so a measurement of the join can
     *  read the curves it actually produced. */
    floatingSessionGap: Boolean = false,
): List<RecoveredCurveV1> = iteratedSessionCurves(
    notes, readings, boluses, kernel, nowMs,
    contaminationWindows, softContaminationWindows, maxPasses, extendThroughKnown,
    carbSensPerGram, null, kernelForBolus, floatingSessionGap,
).map {
    RecoveredCurveV1(
        anchorMs = it.anchorMs,
        points = it.curve.points,
        carbGrams = it.totalCarbs,
        peakRiseMmol = it.tp.rise,
        ttpMin = it.tp.ttpMin,
        peakObserved = it.peakObserved,
        earlyPeakBounded = it.earlyPeakBounded,
        lateTailBounded = it.lateTailBounded,
        confidence = it.confidence,
    )
}
