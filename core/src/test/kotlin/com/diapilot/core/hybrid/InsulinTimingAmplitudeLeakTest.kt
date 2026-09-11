package com.diapilot.core.hybrid

import com.diapilot.core.physio.InsulinShapeLandmarksV1
import com.diapilot.core.physio.InsulinShapeV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVING THE PEAK MUST NOT CHANGE HOW MUCH GLUCOSE THE DOSE REMOVES.
 *
 * The intuition behind this test, put into words while dragging the bench
 * slider: "the amount of insulin doesn't change, and the ISF doesn't change,
 * only the peak — it seemed like that should affect the shape of the curve,
 * not the amount of carbs that would get burned." That intuition is
 * right, and it is not a preference: the action CDF integrates to 1, so a dose
 * eventually removes `units x ISF` whatever route it takes there. Timing is a
 * SHAPE property; total effect is an AMPLITUDE property; the two must not mix,
 * or every shape knob becomes a second amplitude knob and the fit stops being
 * identifiable (this is the «third twin» of M-79/M-80, found in a new place).
 *
 * Measured on a set of episodes: with the peak moved 40 -> 100 min
 * and everything else held, the END of the window — long after all insulin
 * action is over — moved by up to **1.05 mmol** in six of seven episodes. It
 * moved DOWN, that is, delaying the peak bought extra insulin effect for free.
 *
 * The cause is `backboneWeight`: `forecast` multiplies each increment by the
 * trust weight AT ITS HORIZON, and the model carries 1.00 -> 0.50 at 60 ->
 * 0.75 at 120/180. Action landing at h=60 is charged at half; the same action
 * delayed to h=120 is charged at three quarters. So the ramp converts timing
 * into amplitude (M-83, M-84).
 *
 * Both halves are pinned here deliberately:
 *
 *  - with a flat ramp the invariant HOLDS exactly, which is what makes it an
 *    invariant rather than an aspiration;
 *  - with the shipped ramp it is BROKEN by a wide margin, so the defect cannot
 *    be quietly lost. When the ramp is fixed, this second test must be changed
 *    ON PURPOSE, with the numbers re-taken — that is the point of writing it.
 */
class InsulinTimingAmplitudeLeakTest {

    private val now = 1_800_000_000_000L

    private fun knots(peakMin: Double): List<HybridCdfKnot> {
        val want = InsulinShapeLandmarksV1(28.0, peakMin, peakMin + 30.0, 165.0)
        val lm = InsulinShapeV1.coerceIntoDomain(want).first
        return InsulinShapeV1.synthesize(lm, InsulinShapeV1.TAIL_SHARE)
            ?: error("shape $peakMin did not synthesize")
    }

    /** Horizon runs far past the tail so every dose is fully spent at the end. */
    private fun model(peakMin: Double, flatRamp: Boolean): HybridPersonModel {
        val k = knots(peakMin)
        return HybridPersonModel(
            schemaVersion = 1,
            modelVersion = "test",
            personModelId = "timing-amplitude",
            runtime = HybridRuntimeParams(horizonMin = 360, stepMin = 5),
            insulin = HybridInsulinParams(
                isf = 2.41,
                isfLow = 2.0,
                isfHigh = 3.0,
                onsetMin = 28.0,
                peakMin = peakMin,
                shortDurationMin = 165.0,
                tailDurationMin = 165.0,
                tailWeight = 0.8,
                tailWeightPerUnit = 0.3,
                tailReferenceUnits = 2.5,
                actionCdfKnots = k,
            ),
            food = HybridFoodParams(
                globalFactor = 0.24,
                defaultShape = HybridShape(10.0, 55.0, 180.0),
            ),
            activity = HybridActivityParams(
                iobGamma = 0.0, tauMin = 180.0, foodGamma = 0.0, foodTauMin = 60.0,
            ),
            basal = HybridBasalParams(
                onsetMin = 60.0, peakMin = 1080.0, durationMin = 1800.0,
                referenceUnits24h = 20.0, sensitivityMmolPerActionUnit = 0.0, scale = 0.0,
            ),
            joint = HybridJointParams(
                coefficients = HybridJointCoefficients(intercept = 0.0, stateReversion = 0.0),
                targetGlucose = 7.6,
                backgroundScale = 0.0,
            ),
            trend = HybridTrendParams(
                windowMin = 60,
                tauMin = 30.0,
                weight60 = if (flatRamp) 1.0 else 0.5,
                weight120 = if (flatRamp) 1.0 else 0.75,
                weight180 = if (flatRamp) 1.0 else 0.75,
            ),
            uncertainty = HybridUncertaintyParams(
                sigmaPerSqrtHour = 1.2,
                foodFraction = 0.7,
                unknownFoodExtraFraction = 0.35,
            ),
        )
    }

    private val state = HybridForecastState(
        nowMs = now,
        glucoseHistory = listOf(
            HybridGlucosePoint(now - 60L * MINUTE_MS, 9.0),
            HybridGlucosePoint(now, 9.0),
        ),
        bolusHistory = listOf(HybridBolusEvent(now, 4.0)),
    )

    private fun settledAt(peakMin: Double, flatRamp: Boolean): Double =
        HybridForecastEngine(model(peakMin, flatRamp)).forecast(state)
            .points.maxByOrNull { it.minutes }!!.baseline

    @Test
    fun `with a flat ramp the settled level does not depend on the peak`() {
        val early = settledAt(40.0, flatRamp = true)
        val late = settledAt(100.0, flatRamp = true)
        // The dose is fully spent well before the horizon in both arms, so the
        // only thing separating them is the route — and the route must not bill.
        assertEquals(early, late, 1e-9)
        // Guard against passing vacuously: the dose must actually have done
        // something. A test that would still pass with ISF = 0 pins nothing —
        // that failure mode has already been seen here once.
        assertTrue("bolus had no effect at all: $early", early < 9.0 - 1.0)
    }

    @Test
    fun `the shipped trust ramp converts timing into amplitude`() {
        val early = settledAt(40.0, flatRamp = false)
        val late = settledAt(100.0, flatRamp = false)
        // Delaying the peak moves action into a better-trusted horizon, so MORE
        // of the same four units survives and the settled level drops. This
        // asserts the DEFECT, and the sign matters: `late` must be the lower.
        assertTrue(
            "expected the later peak to remove more glucose, got early=$early late=$late",
            late < early - 0.2,
        )
    }
}
