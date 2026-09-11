package com.diapilot.core.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class HybridForecastEngineGoldenTest {
    private val tolerance = 1e-9

    @Test
    fun `active bolus ISF uncertainty widens only the interval`() {
        val now = 1_800_000_000_000L
        val state = HybridForecastState(
            nowMs = now,
            glucoseHistory = listOf(
                HybridGlucosePoint(now - 60L * MINUTE_MS, 8.0),
                HybridGlucosePoint(now, 8.0),
            ),
            bolusHistory = listOf(HybridBolusEvent(now - 20L * MINUTE_MS, 3.0)),
        )
        val oldModel = v11GoldenModel()
        val newModel = oldModel.copy(
            uncertainty = oldModel.uncertainty.copy(includeActiveBolusIsf = true),
        )
        val old = HybridForecastEngine(oldModel).forecast(state)
        val widened = HybridForecastEngine(newModel).forecast(state)
        val old60 = old.points.single { it.minutes == 60 }
        val widened60 = widened.points.single { it.minutes == 60 }
        assertEquals(old60.baseline, widened60.baseline, tolerance)
        assertEquals(old60.scenario, widened60.scenario, tolerance)
        assertEquals(old60.foodDelta, widened60.foodDelta, tolerance)
        assertEquals(old60.insulinActualDelta, widened60.insulinActualDelta, tolerance)
        if (widened60.high - widened60.low <= old60.high - old60.low) {
            fail("active bolus must widen the interval")
        }
    }

    @Test
    fun `triangular kernel matches v11 golden contract`() {
        val cases = listOf(
            doubleArrayOf(-5.0, 0.0, 60.0, 180.0, 0.0),
            doubleArrayOf(0.0, 0.0, 60.0, 180.0, 0.0),
            doubleArrayOf(60.0, 0.0, 60.0, 180.0, 1.0 / 3.0),
            doubleArrayOf(180.0, 0.0, 60.0, 180.0, 1.0),
            doubleArrayOf(185.0, 0.0, 60.0, 180.0, 1.0),
            doubleArrayOf(-5.0, 25.0, 120.0, 175.0, 0.0),
            doubleArrayOf(25.0, 25.0, 120.0, 175.0, 0.0),
            doubleArrayOf(120.0, 25.0, 120.0, 175.0, 0.6333333333333333),
            doubleArrayOf(175.0, 25.0, 120.0, 175.0, 1.0),
        )
        cases.forEach { (t, onset, peak, duration, expected) ->
            assertEquals(expected, triangularCdf(t, onset, peak, duration), tolerance)
        }
    }

    @Test
    fun `model rejects an invalid insulin timeline`() {
        try {
            v11GoldenModel().copy(
                insulin = v11GoldenModel().insulin.copy(peakMin = 10.0),
            )
            fail("Invalid timeline must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun v11GoldenModel(): HybridPersonModel = HybridPersonModel(
        schemaVersion = 1,
        modelVersion = "v11",
        personModelId = "person-test-fixture",
        runtime = HybridRuntimeParams(horizonMin = 180, stepMin = 5),
        insulin = HybridInsulinParams(
            isf = 1.85,
            isfLow = 1.5,
            isfHigh = 2.4,
            onsetMin = 30.0,
            peakMin = 60.0,
            shortDurationMin = 115.0,
            tailDurationMin = 165.0,
            tailWeight = 0.8,
            tailWeightPerUnit = 0.3,
            tailReferenceUnits = 2.5,
        ),
        food = HybridFoodParams(
            globalFactor = 0.17,
            defaultShape = HybridShape(
                delayMin = 25.0,
                peakMin = 95.0,
                durationMin = 150.0,
            ),
        ),
        activity = HybridActivityParams(
            iobGamma = 0.5,
            tauMin = 180.0,
            foodGamma = 0.0,
            foodTauMin = 60.0,
        ),
        basal = HybridBasalParams(
            onsetMin = 60.0,
            peakMin = 1080.0,
            durationMin = 1800.0,
            referenceUnits24h = 20.0,
            sensitivityMmolPerActionUnit = 0.0,
            scale = 0.0,
        ),
        joint = HybridJointParams(
            coefficients = HybridJointCoefficients(
                intercept = 0.0015,
                stateReversion = 0.0065,
            ),
            targetGlucose = 7.6,
        ),
        trend = HybridTrendParams(
            windowMin = 60,
            tauMin = 30.0,
            weight60 = 0.5,
            weight120 = 0.5,
            weight180 = 0.75,
        ),
        uncertainty = HybridUncertaintyParams(
            sigmaPerSqrtHour = 1.27,
            foodFraction = 0.7,
            unknownFoodExtraFraction = 0.35,
        ),
    )
}
