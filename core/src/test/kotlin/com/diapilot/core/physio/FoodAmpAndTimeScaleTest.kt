package com.diapilot.core.physio

import com.diapilot.core.hybrid.CarbAppearancePolicyV1
import com.diapilot.core.hybrid.HybridActivityParams
import com.diapilot.core.hybrid.HybridBasalParams
import com.diapilot.core.hybrid.HybridBolusEvent
import com.diapilot.core.hybrid.HybridFoodEvent
import com.diapilot.core.hybrid.HybridFoodParams
import com.diapilot.core.hybrid.HybridForecastState
import com.diapilot.core.hybrid.HybridGlucosePoint
import com.diapilot.core.hybrid.HybridInsulinParams
import com.diapilot.core.hybrid.HybridJointCoefficients
import com.diapilot.core.hybrid.HybridJointParams
import com.diapilot.core.hybrid.HybridPersonModel
import com.diapilot.core.hybrid.HybridRuntimeParams
import com.diapilot.core.hybrid.HybridShape
import com.diapilot.core.hybrid.HybridTrendParams
import com.diapilot.core.hybrid.HybridUncertaintyParams
import com.diapilot.core.hybrid.physioForecastEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TWO AXES THE FITTER WAS MISSING, PINNED SO THEY CANNOT SILENTLY DO NOTHING.
 *
 * Both were added because the walk-forward could not ask its own
 * question. Its fit had ten axes and NONE of them touched food amplitude — so
 * on an episode where the drawn line was flat and reality arced up, the only
 * reachable lever was ISF, and the measured ISF corridor then forbade it. The
 * search duly pinned ISF to the corridor FLOOR on every window, and that got
 * read as «the loss wants weak insulin». It was the loss reaching for the
 * nearest knob to the one it needed.
 *
 * These tests exist because an override that is plumbed but INERT would
 * reproduce exactly that failure while looking fixed — the study would report a
 * value for `foodAmp`, the fit would still pin ISF, and nothing on screen would
 * say the axis never moved anything.
 *
 * Mutation-checked: making `foodAmpScale` a no-op fails [ampScales] and
 * [ampMovesTheForecastCurveItself]; making `carbTimeScale` a no-op fails
 * [timeScaleDelaysArrival]; dropping the rescue exemption fails [rescueIsExempt];
 * dropping either override from `tuned()` fails [fitterCarriesBothAxesIntoTheModel].
 */
class FoodAmpAndTimeScaleTest {

    private val t0 = 1_800_000_000_000L

    private val base: HybridPersonModel = HybridPersonModel(
        schemaVersion = 1,
        modelVersion = "test",
        personModelId = "foodamp-test",
        runtime = HybridRuntimeParams(horizonMin = 240, stepMin = 5),
        insulin = HybridInsulinParams(
            isf = 2.5, isfLow = 2.0, isfHigh = 3.0,
            onsetMin = 11.0, peakMin = 51.0,
            shortDurationMin = 130.0, tailDurationMin = 130.0,
            tailWeight = 0.8, tailWeightPerUnit = 0.3, tailReferenceUnits = 2.5,
        ),
        food = HybridFoodParams(globalFactor = 0.165, defaultShape = HybridShape(10.0, 55.0, 180.0)),
        activity = HybridActivityParams(0.0, 180.0, 0.0, 60.0),
        basal = HybridBasalParams(60.0, 1080.0, 1800.0, 20.0, 0.0, 0.0),
        joint = HybridJointParams(HybridJointCoefficients(0.0, 0.0), 7.6, backgroundScale = 0.0),
        // Ramp neutralised: this file measures the food axes, and the trust ramp
        // multiplies every physiological increment (M-83). Leaving it in would
        // make a real effect look smaller for a reason unrelated to the knob.
        trend = HybridTrendParams(60, 30.0, 1.0, 1.0, 1.0),
        uncertainty = HybridUncertaintyParams(1.2, 0.7, 0.35),
    )

    private fun meal(rescue: Boolean = false) = HybridFoodEvent(
        tsMs = t0, carbsG = 50.0, text = "тест", rescueTreatment = rescue,
    )

    private fun state(food: HybridFoodEvent) = HybridForecastState(
        nowMs = t0,
        glucoseHistory = listOf(HybridGlucosePoint(t0 - 60_000L, 6.0)),
        foodHistory = listOf(food),
        bolusHistory = emptyList<HybridBolusEvent>(),
    )

    @Test
    fun ampScales() {
        val a1 = physioForecastEngine(base, foodAmpScale = 1.0).foodAmplitude(meal()).first
        val a2 = physioForecastEngine(base, foodAmpScale = 2.0).foodAmplitude(meal()).first
        assertTrue("базовая амплитуда должна быть положительной", a1 > 0.0)
        assertEquals("foodAmp обязан масштабировать амплитуду ровно", 2.0, a2 / a1, 1e-9)
    }

    @Test
    fun rescueIsExempt() {
        // The corpus rule forbids learning amplitude on dextrose: at hypo the
        // liver contributes and the grams are not the whole story. A knob fitted
        // on MEALS must not reach a rescue that happens to sit inside the window.
        val r1 = physioForecastEngine(base, foodAmpScale = 1.0).foodAmplitude(meal(rescue = true)).first
        val r2 = physioForecastEngine(base, foodAmpScale = 2.0).foodAmplitude(meal(rescue = true)).first
        assertTrue("спасательная амплитуда должна быть положительной", r1 > 0.0)
        assertEquals("спасательная доза не масштабируется пищевой ручкой", r1, r2, 1e-9)
    }

    @Test
    fun timeScaleDelaysArrival() {
        // A LEVEL crossing, not an argmax. The arrival RATE of a meal is
        // genuinely two-humped and not monotone in the inputs
        // (HybridFoodTiming.medianArrivalMin), so reading a peak here would test
        // the wrong quantity.
        val m = meal()
        val remainingFast = physioForecastEngine(base, carbTimeScale = 0.6).foodRemainingFraction(m, 60.0)
        val remainingSlow = physioForecastEngine(base, carbTimeScale = 1.6).foodRemainingFraction(m, 60.0)
        assertTrue(
            "на 60-й минуте у растянутой шкалы должно ОСТАВАТЬСЯ больше: " +
                "$remainingSlow против $remainingFast",
            remainingSlow > remainingFast + 1e-6,
        )
    }

    @Test
    fun overridesRideOnTheModelAndTheArgumentWins() {
        // Precedence is why these live on the model at all: fourteen call sites
        // in the app, and a missed one computes the old model under the new
        // label (discipline #7). A sweep argument still outranks the model.
        val tunedModel = base.copy(food = base.food.copy(foodAmpOverride = 2.0))
        val plain = physioForecastEngine(base).foodAmplitude(meal()).first
        val viaModel = physioForecastEngine(tunedModel).foodAmplitude(meal()).first
        val viaArg = physioForecastEngine(tunedModel, foodAmpScale = 1.0).foodAmplitude(meal()).first
        assertEquals("override с модели должен применяться", 2.0, viaModel / plain, 1e-9)
        assertEquals("явный аргумент свипа должен побеждать override", 1.0, viaArg / plain, 1e-9)
    }

    @Test
    fun fitterCarriesBothAxesIntoTheModel() {
        // The fitter and the applier must synthesize the SAME model, or a knob
        // found on the bench draws a different curve when applied (M-59).
        val k = PhysioAutoFitV1.Knobs(
            isf = 2.5, onsetMin = 11.0, fullSpeedMin = 51.0, phaseMin = 18.0, tailMin = 130.0,
            emptyingKcalPerHour = 150.0, carbSieving = 0.65, carbSpread = 1.0,
            trustRamp = 0.0, foodAmp = 1.7, carbTimeScale = 1.3,
        )
        val tuned = PhysioAutoFitV1.tuned(base, k)
        assertNotNull("подобранные ориентиры должны давать валидную форму", tuned)
        assertEquals(1.7, tuned!!.food.foodAmpOverride!!, 1e-9)
        assertEquals(1.3, tuned.food.carbTimeScaleOverride!!, 1e-9)
        assertTrue(
            "обе оси должны быть в поиске",
            PhysioAutoFitV1.AXES.containsAll(listOf("foodAmp", "carbTimeScale")),
        )
    }

    @Test
    fun ampMovesTheForecastCurveItself() {
        // foodAmplitude() alone could be scaled while the forecast path reads a
        // different route — that is the failure this whole file is about.
        fun peak(amp: Double) = physioForecastEngine(
            base,
            appearance = CarbAppearancePolicyV1.PHYSIO_SHIPPED,
            foodAmpScale = amp,
        ).forecast(state(meal())).points.maxOf { it.baseline }
        val p1 = peak(1.0)
        val p2 = peak(2.0)
        assertTrue("удвоение амплитуды еды должно поднять кривую: $p1 -> $p2", p2 - p1 > 0.5)
    }
}
