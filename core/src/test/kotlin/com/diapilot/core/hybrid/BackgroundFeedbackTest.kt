package com.diapilot.core.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BACKGROUND MUST SEE WHERE THE LINE WENT.
 *
 * `forecast()` used to ask for the background over the whole horizon at once,
 * passing the ANCHOR glucose, so `- stateReversion * (glucose - target)` was a
 * constant chosen by the starting point. A reversion that cannot notice the
 * line has moved is not a reversion; it is a fixed slope, and the forecast then
 * rises for ever. Measured on the shipped artifact before the fix: with no food
 * and no insulin the line went 7.0 -> 13.38 mmol over 24 hours, and 3.0 ->
 * 11.23, with no equilibrium anywhere in the physiological range.
 *
 * These tests pin the property rather than any number, so a refit of the
 * coefficients (which is the separate, second half of this work) cannot make
 * them vacuous.
 */
class BackgroundFeedbackTest {

    private val now = 1_800_000_000_000L

    /** A background with a real reversion and a real intercept, and nothing else
     *  switched on — no food, no insulin, no basal, no circadian. */
    private fun model(reversion: Double, intercept: Double) = HybridPersonModel(
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
            HybridJointCoefficients(intercept = intercept, stateReversion = reversion),
            7.6,
            backgroundScale = 1.0,
        ),
        // Ramp neutral: this file is about the background, and the trust ramp
        // multiplies every increment (M-83).
        trend = HybridTrendParams(60, 30.0, 1.0, 1.0, 1.0),
        uncertainty = HybridUncertaintyParams(1.2, 0.7, 0.35),
    )

    private fun endOfDay(m: HybridPersonModel, g0: Double, stepped: Boolean): Double =
        HybridForecastEngine(m, steppedBackgroundFeedback = stepped)
            .forecast(
                HybridForecastState(
                    nowMs = now,
                    glucoseHistory = listOf(HybridGlucosePoint(now, g0)),
                ),
            ).points.last().baseline

    @Test
    fun `a higher start never ends below a lower one`() {
        // THE INVARIANT, and the old branch violated it outright.
        //
        // Two identical days differing only in starting glucose: the one that
        // starts higher must still be higher a day later. Nothing in the body
        // makes a 15 overtake a 5 downwards with no insulin and no food.
        //
        // With feedback the gap SHRINKS — the higher line is pushed down harder
        // as long as it stays high, and eases off as it approaches the fixed
        // point. That is convergence, and it keeps the order.
        //
        // With the reversion frozen at the anchor, the push never eases: the
        // high line falls at a constant steep rate for twenty-four hours and
        // sails straight past the low one. Measured here at -4.4 mmol, i.e. the
        // day that began at 15 ends four and a half BELOW the day that began at
        // 5. That is not a small calibration error, it is the term having no
        // state at all — and it is why this test asserts the ORDER rather than
        // any particular gap.
        val m = model(reversion = 0.03, intercept = 0.0)
        val low = endOfDay(m, 5.0, stepped = true)
        val high = endOfDay(m, 15.0, stepped = true)
        assertTrue("порядок обязан сохраниться: $high против $low", high > low)
        assertTrue("и разрыв обязан сократиться с 10.0: ${high - low}", high - low < 9.0)

        val crossed = endOfDay(m, 15.0, stepped = false) - endOfDay(m, 5.0, stepped = false)
        assertTrue(
            "старая ветка обязана перехлёстывать — если нет, A/B сравнивает не то: $crossed",
            crossed < 0.0,
        )
    }

    @Test
    fun `a positive intercept does not rise without bound`() {
        // The fixed point is `intercept / reversion + target`. The line must
        // approach it and stop, not walk past it. Before the fix it walked.
        val m = model(reversion = 0.03, intercept = 0.06)
        val equilibrium = 0.06 / 0.03 * 2.0 + 7.6
        val end = endOfDay(m, 5.0, stepped = true)
        assertTrue("линия $end обязана остаться ниже равновесия $equilibrium", end < equilibrium + 0.5)
        assertTrue("и всё же подняться от 5.0: $end", end > 5.0)
    }

    @Test
    fun `starting at the fixed point the line does not move`() {
        // The sharpest form: at equilibrium the background is zero by
        // construction, so a stepped background must leave the line alone. A
        // frozen one does too here — this is the case where they agree, and it
        // is included so the suite states what the term IS, not only where the
        // two branches differ.
        val m = model(reversion = 0.03, intercept = 0.0)
        assertEquals(7.6, endOfDay(m, 7.6, stepped = true), 1e-6)
    }

    @Test
    fun `the legacy branch still reproduces a constant slope`() {
        // The old behaviour is kept ONLY so measurements can run both arms, and
        // it has to stay exactly what it was or the A/B compares nothing. A
        // frozen reversion means the 24-hour move is exactly 24x the one-hour
        // move.
        val m = model(reversion = 0.03, intercept = 0.0)
        val g0 = 4.0
        val engine = HybridForecastEngine(m, steppedBackgroundFeedback = false)
        val pts = engine.forecast(
            HybridForecastState(nowMs = now, glucoseHistory = listOf(HybridGlucosePoint(now, g0))),
        ).points
        val oneHour = pts.first { it.minutes == 60 }.baseline - g0
        val day = pts.last().baseline - g0
        assertEquals("старая ветка обязана быть линейной", 24.0, day / oneHour, 0.05)
    }
}
