package io.github.obdosok.diapilot.data

import com.diapilot.core.hybrid.HybridActivityParams
import com.diapilot.core.hybrid.HybridBasalParams
import com.diapilot.core.hybrid.HybridFoodParams
import com.diapilot.core.hybrid.HybridInsulinParams
import com.diapilot.core.hybrid.HybridJointCoefficients
import com.diapilot.core.hybrid.HybridJointParams
import com.diapilot.core.hybrid.HybridPersonModel
import com.diapilot.core.hybrid.HybridRuntimeParams
import com.diapilot.core.hybrid.HybridShape
import com.diapilot.core.hybrid.HybridTrendParams
import com.diapilot.core.hybrid.HybridUncertaintyParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SAFETY INVARIANT OF THE TUNING SCREEN: installing changes nothing.
 *
 * These knobs reach the hypo-alert path. An install that silently re-timed the
 * user's alarms would not be a test they opted into, it would be a surprise at
 * 3am — so "untouched tuning leaves the model alone" is not a nicety, it is the property
 * that makes shipping the screen safe before the model change itself is
 * reviewed.
 *
 * It is asserted as IDENTITY, not equality. A rebuilt-but-equal model would
 * still change the artifact hash, which splits the A/B ledger and makes every
 * paired comparison restart for no reason.
 */
class PhysioTuningTest {

    private fun model(
        weight60: Double = 0.5,
        weight120: Double = 0.75,
        weight180: Double = 0.75,
    ) = HybridPersonModel(
        schemaVersion = 1,
        modelVersion = "test",
        personModelId = "tuning-test",
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
            globalFactor = 0.165,
            defaultShape = HybridShape(10.0, 55.0, 180.0),
        ),
        activity = HybridActivityParams(0.5, 180.0, 0.0, 60.0),
        basal = HybridBasalParams(60.0, 1080.0, 1800.0, 20.0, 0.0, 0.0),
        joint = HybridJointParams(HybridJointCoefficients(0.0, 0.0), 7.6),
        trend = HybridTrendParams(60, 30.0, weight60, weight120, weight180),
        uncertainty = HybridUncertaintyParams(1.2, 0.7, 0.35),
    )

    @Test
    fun `untouched tuning returns the very same model instance`() {
        val m = model()
        assertSame(m, PhysioTuning.apply(m, PhysioTuning.Values()))
        assertTrue(!PhysioTuning.Values().touched)
    }

    @Test
    fun `ramp at zero lifts every horizon weight to one`() {
        val tuned = PhysioTuning.apply(model(), PhysioTuning.Values(trustRamp = 0.0))
        assertEquals(1.0, tuned.trend.weight60, 1e-12)
        assertEquals(1.0, tuned.trend.weight120, 1e-12)
        assertEquals(1.0, tuned.trend.weight180, 1e-12)
        // And it must not have touched anything else on the way past.
        assertEquals(model().insulin, tuned.insulin)
    }

    @Test
    fun `ramp at one is a no-op on the weights`() {
        val m = model()
        val tuned = PhysioTuning.apply(m, PhysioTuning.Values(trustRamp = 1.0, isfMmol = 2.41))
        assertEquals(m.trend.weight60, tuned.trend.weight60, 1e-12)
        assertEquals(m.trend.weight120, tuned.trend.weight120, 1e-12)
        // ONE DOOR: `apply` no longer touches the base ISF — the
        // hand-set value travels through `ManualInsulinRuntime` instead. If this
        // starts failing, the base-overwrite has come back.
        assertEquals(m.insulin.isf, tuned.insulin.isf, 1e-12)
    }

    @Test
    fun `the shape does not travel through PhysioTuning at all`() {
        // CONTRACT CHANGED: the manual shape has ONE door — P1
        // (`ManualInsulinRuntime` -> `ManualInsulinParamsV1.resolve`).
        //
        // There used to be two, and they disagreed on units: the tuning card
        // writes "phase" as a DURATION, while P1 stores the ABSOLUTE end of the
        // active phase. That mismatch could produce onset 19 < peak 53 >
        // plateauEnd 31 — order violated, the shape rejected as
        // SHAPE_OUT_OF_DOMAIN and silently replaced by the measured one, while
        // the screen claimed the entered shape was applied.
        //
        // Mutation: put the synthesis back into `PhysioTuning.apply`, and this test fails.
        val m = model()
        val out = PhysioTuning.apply(
            m,
            PhysioTuning.Values(onsetMin = 28.0, fullSpeedMin = 40.0, phaseMin = 30.0, tailMin = 165.0),
        )
        assertEquals("onset is untouched", m.insulin.onsetMin, out.insulin.onsetMin, 1e-9)
        assertEquals("peak is untouched", m.insulin.peakMin, out.insulin.peakMin, 1e-9)
        assertEquals("tail is untouched", m.insulin.tailDurationMin, out.insulin.tailDurationMin, 1e-9)
        assertEquals("the curve is not substituted", m.insulin.actionCdfKnots, out.insulin.actionCdfKnots)
    }


    /**
     * THE FITTER AND THE APPLIER MUST BUILD THE SAME MODEL.
     *
     * `PhysioAutoFitV1.tuned` builds candidates during the search; this builds
     * the model that is installed when the user taps "Apply". If they synthesize
     * differently, the phone would suggest a knob set and then behave unlike the
     * fit that suggested it — the exact failure the shared fitter exists to
     * prevent, quietly reintroduced one layer up.
     */
    @Test
    fun `settings and the auto-fitter agree on everything they still share`() {
        // PARITY NARROWED ALONG WITH THE CONTRACT. The test bench and the
        // screen no longer need to agree on insulin shape: the screen does
        // not apply it — it goes through P1. They must agree on what both
        // still set: food, trend, queue, sieve, spread.
        val m = model()
        val k = com.diapilot.core.physio.PhysioAutoFitV1.Knobs(
            isf = 2.41,
            onsetMin = 28.0,
            fullSpeedMin = 40.0,
            phaseMin = 30.0,
            // Inside `insulinTailMinRange`, whose floor is now 240 (audit M1):
            // `tuned` returns null for a candidate the domain would have to move.
            tailMin = 300.0,
            emptyingKcalPerHour = 200.0,
            carbSieving = 0.80,
            carbSpread = 1.1,
            trustRamp = 0.0,
        )
        val viaFitter = com.diapilot.core.physio.PhysioAutoFitV1.tuned(m, k)!!
        val viaSettings = PhysioTuning.apply(
            m,
            PhysioTuning.Values(
                trustRamp = k.trustRamp,
                emptyingKcalPerHour = k.emptyingKcalPerHour,
                carbSieving = k.carbSieving,
                carbSpread = k.carbSpread,
            ),
        )
        assertEquals(viaFitter.food, viaSettings.food)
        assertEquals(viaFitter.trend, viaSettings.trend)
        assertEquals("экран не трогает форму", m.insulin.actionCdfKnots, viaSettings.insulin.actionCdfKnots)
        assertEquals("а стенд обязан нести запрошенный ISF", k.isf, viaFitter.insulin.isf, 1e-12)
    }


    @Test
    fun `queue overrides ride on the food params`() {
        val tuned = PhysioTuning.apply(
            model(),
            PhysioTuning.Values(emptyingKcalPerHour = 200.0, carbSieving = 0.80, carbSpread = 1.2),
        )
        assertEquals(200.0, tuned.food.emptyingKcalPerHourOverride)
        assertEquals(0.80, tuned.food.carbSievingOverride)
        assertEquals(1.2, tuned.food.carbSpreadOverride)
    }
}
