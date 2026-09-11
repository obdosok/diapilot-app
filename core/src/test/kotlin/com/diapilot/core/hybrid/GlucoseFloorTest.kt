package com.diapilot.core.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FORECAST MAY NOT PREDICT GLUCOSE A BODY CANNOT HOLD.
 *
 * There was no floor and the integrator has no term that supplies one: food is
 * finite, insulin action is not bounded below by the glucose it has left to
 * remove. A live check found many stored points below zero, with an
 * implausibly low minimum, some of them in `hypo_alert` runs, the pass that
 * decides whether to wake the user.
 *
 * Both halves are asserted, because the clamp alone would be worse than the bug:
 * a trajectory reaching the floor is not a severe hypo, it is the model leaving
 * the region where it means anything, and a silently prettier number hides it.
 */
class GlucoseFloorTest {

    private val now = 1_700_000_000_000L

    private fun model() = HybridPersonModel(
        schemaVersion = 1,
        modelVersion = "bg-test",
        personModelId = "bg",
        runtime = HybridRuntimeParams(horizonMin = 1440, stepMin = 5),
        insulin = HybridInsulinParams(
            isf = 2.5, isfLow = 2.0, isfHigh = 3.0,
            onsetMin = 11.0, peakMin = 51.0,
            shortDurationMin = 130.0, tailDurationMin = 130.0,
            tailWeight = 0.8, tailWeightPerUnit = 0.3, tailReferenceUnits = 2.5,
        ),
        food = HybridFoodParams(globalFactor = 0.165, defaultShape = HybridShape(10.0, 55.0, 180.0)),
        activity = HybridActivityParams(0.0, 180.0, 0.0, 60.0),
        basal = HybridBasalParams(0.0, 1080.0, 1800.0, 20.0, 0.0, 0.0),
        joint = HybridJointParams(
            HybridJointCoefficients(intercept = 0.0, stateReversion = 0.0),
            7.6,
            backgroundScale = 1.0,
        ),
        // Ramp neutral: this file is about the background, and the trust ramp
        // multiplies every increment (M-83).
        trend = HybridTrendParams(60, 30.0, 1.0, 1.0, 1.0),
        uncertainty = HybridUncertaintyParams(1.2, 0.7, 0.35),
    )

    private fun forecast(units: Double): HybridForecastResult =
        HybridForecastEngine(model()).forecast(
            HybridForecastState(
                nowMs = now,
                glucoseHistory = listOf(HybridGlucosePoint(now, 4.0)),
                bolusHistory = listOf(HybridBolusEvent(now - 5L * 60_000, units)),
            ),
        )

    @Test
    fun `no predicted point falls below the physiological floor`() {
        val worst = forecast(30.0).points.minOf { minOf(it.scenario, it.low, it.baseline) }
        assertTrue("прогноз ушёл до $worst при поле $HYBRID_FLOOR_MMOL",
            worst >= HYBRID_FLOOR_MMOL - 1e-9)
    }

    @Test
    fun `hitting the floor is counted, not hidden`() {
        assertTrue("пол сработал, но не посчитан — отказ модели остался невидимым",
            forecast(30.0).floorClampedPoints > 0)
    }

    /**
     * The floor sits at 2.0, well below the 3.9 alert threshold, so it can never
     * change whether an alarm fires. This pins that it also changes nothing on an
     * ordinary day.
     */
    @Test
    fun `an ordinary forecast is untouched by the floor`() {
        val r = forecast(0.5)
        assertEquals(0, r.floorClampedPoints)
        assertTrue(r.points.all { it.scenario > HYBRID_FLOOR_MMOL })
    }
}
