package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridBlindDayParityTest
import com.diapilot.core.hybrid.HybridCdfKnot
import com.diapilot.core.hybrid.HybridForecastEngine
import com.diapilot.core.hybrid.HybridPersonModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A HAND-ENTERED END OF ACTION OF 130 MINUTES, FROM THE SETTING TO THE KERNEL.
 *
 * The app's own owner measures roughly 130 minutes and sees no insulin effect
 * after two hours. Entering that produced 240 everywhere the model was read:
 * the resolver checked the hand entry against `insulinTailMinRange`, which is
 * the MEASURING WINDOW'S reach and not a statement about a body, so the shape
 * was refused as out of domain and the measured curve silently stayed in
 * charge — while the card went on showing the entered numbers.
 *
 * Silently overriding an explicit human value is wrong independently of who is
 * right about the physiology, and the direction it errs in is the unsafe one: a
 * tail longer than the real one over-reads insulin on board, and an over-read
 * IOB suppresses a low alert. So the hand tier has its own domain
 * ([PhysioBoundsV1.insulinTailMinManualRange]) and is applied as entered.
 *
 * Every assertion below reads the ENGINE, not the parameters. "Applied" means
 * the curve the forecast integrates, which is the same object IOB, What-if, the
 * alert and the meal deconvolution read — there is no second path (see
 * `docs/insulin-model.md` §6).
 */
class ManualInsulinTailV1Test {

    private val bounds = PhysioBoundsV1()

    /** What the owner states: onset 25, full speed 55, nothing left at 130. */
    private val entered = ManualInsulinParamsV1(onsetMin = 25.0, peakMin = 55.0, tailMin = 130.0)

    /** A measured corpus curve that disagrees, ending in the fifth hour. */
    private val measured = listOf(
        0.0 to 0.0, 15.0 to 0.0, 20.0 to .02, 30.0 to .12, 40.0 to .28,
        50.0 to .45, 60.0 to .60, 80.0 to .80, 100.0 to .92, 180.0 to .99, 280.0 to 1.0,
    ).map { (m, f) -> HybridCdfKnot(m, f) }

    private fun resolve(manual: ManualInsulinParamsV1 = entered) =
        InsulinParameterResolverV1.resolve(
            manual = manual,
            measured = measured,
            measuredTier = InsulinParamTierV1.TAGGED_CORRECTION,
            measuredIsf = 2.0,
            measuredIsfTier = InsulinParamTierV1.TAGGED_CORRECTION,
            priorLandmarks = InsulinShapeLandmarksV1(15.0, 75.0, tailMin = 300.0),
        )

    // ---- step 1: the resolver keeps the number ---------------------------

    @Test
    fun `the hand tier accepts an end of action of 130 and reports it as its own`() {
        val r = resolve()
        assertTrue("unexpected refusal: ${r.rejected}", r.rejected.isEmpty())
        assertEquals(InsulinParamTierV1.MANUAL, r.shapeTier)
        assertEquals(130.0, checkNotNull(r.landmarks).tailMin, 1e-9)
    }

    /** Not merely reported: the curve handed on actually ENDS there. */
    @Test
    fun `the resolved curve ends at the entered minute, not at the measured one`() {
        val knots = checkNotNull(resolve().knots)
        assertEquals(130.0, knots.last().minute, 1e-6)
        assertEquals(1.0, knots.last().fraction, 1e-9)
    }

    /**
     * And the disagreement is REPORTED rather than resolved in silence — the
     * only channel left open once P1 is in force.
     */
    @Test
    fun `the measured end of action is carried alongside as a divergence`() {
        val d = resolve().divergences.single { it.field == ManualInsulinParamsV1.TAIL }
        assertEquals(130.0, d.manual, 1e-9)
        assertEquals(280.0, d.measured, 1e-9)
    }

    // ---- step 2: the artifact accepts it ---------------------------------

    private fun personWithHandShape(): HybridPersonModel {
        val r = resolve()
        val lm = checkNotNull(r.landmarks)
        val base = HybridBlindDayParityTest().model(
            JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json"))
                    .bufferedReader(Charsets.UTF_8).readText(),
            ),
        )
        // Exactly what `ManualInsulinRuntime.apply` writes: one number for the
        // single-peak consumer, the tail on both duration fields, the resolved
        // knots as the curve.
        return base.copy(
            insulin = base.insulin.copy(
                onsetMin = lm.onsetMin,
                peakMin = lm.singlePeakMin,
                shortDurationMin = lm.tailMin,
                tailDurationMin = lm.tailMin,
                actionCdfKnots = checkNotNull(r.knots),
            ),
        )
    }

    /**
     * The artifact's own invariant used to be the narrow range, so a hand entry
     * of 130 threw inside the constructor and took the whole PHYSIO arm down
     * rather than one number with it.
     */
    @Test
    fun `the artifact accepts a hand-entered end of action and serves it unchanged`() {
        val artifact = PhysioArtifactV1(
            artifactId = "manual-tail-130",
            baseMechanics = personWithHandShape(),
            globalCs = PosteriorV1(.165, .097, .257, 0, 0, null),
            globalIsf = PosteriorV1(1.85, 1.5, 2.4, 0, 0, null),
            variance = PHYSIO_BASE_VARIANCE_HEALTHY_V1,
        )
        assertEquals(130.0, artifact.personModelAt(12.0).insulin.tailDurationMin, 1e-9)
        assertEquals(130.0, artifact.personModelAt(3.0).insulin.tailDurationMin, 1e-9)
    }

    // ---- step 3: the kernel the forecast integrates ----------------------

    /**
     * THE ONE THAT MATTERS. `insulinKernelPoints` is the per-dose kernel the
     * forecast subtracts and the deconvolution uses; its length IS the applied
     * end of action. A kernel that ran to 240 while the card said 130 is the
     * defect, stated as a number.
     */
    @Test
    fun `the kernel the forecast integrates stops at the entered end of action`() {
        val points = HybridForecastEngine(personWithHandShape()).insulinKernelPoints(1.0)
        assertEquals(130.0, points.last().tauMin, 1e-9)
        assertTrue("the kernel must carry more than a couple of steps", points.size >= 20)
    }

    /** Insulin on board reaches zero at the entered end, and not before it. */
    @Test
    fun `nothing is left on board after the entered end of action`() {
        val engine = HybridForecastEngine(personWithHandShape())
        assertTrue("still acting at 100 min: ${engine.insulinCdf(100.0)}", engine.insulinCdf(100.0) < .999)
        assertEquals(1.0, engine.insulinCdf(130.0), 1e-6)
    }

    /** The landmark line the card draws agrees with the kernel above. */
    @Test
    fun `the engine reports the entered end of action as its own effect end`() {
        val landmarks = HybridForecastEngine(personWithHandShape()).insulinLandmarks()
        assertTrue(
            "effect end ${landmarks.effectEndMin} should not outlast the entered 130",
            landmarks.effectEndMin <= 130.0 + 1e-6,
        )
    }

    // ---- the floor that remains ------------------------------------------

    /**
     * The hand tier has a floor of its own, and it is NUMERICAL rather than
     * physiological: under it there is no ordered quadruple left to synthesize.
     * Below it the entry is refused and SAID so — still never clamped, because
     * a clamp would put a curve nobody entered behind the card's own numbers.
     */
    @Test
    fun `an end of action under the hand tier's own floor is refused, not clamped`() {
        val r = resolve(ManualInsulinParamsV1(onsetMin = 10.0, peakMin = 30.0, tailMin = 90.0))
        assertTrue(
            "expected an out-of-domain refusal, got ${r.rejected}",
            InsulinParameterResolverV1.SHAPE_OUT_OF_DOMAIN in r.rejected,
        )
        assertEquals("the measured curve stays in charge", measured, r.knots)
        assertEquals(InsulinParamTierV1.TAGGED_CORRECTION, r.shapeTier)
    }

    @Test
    fun `the hand tier's floor is the documented 120 minutes`() {
        assertEquals(120.0, bounds.insulinTailMinManualRange.start, 0.0)
        assertEquals(600.0, bounds.insulinTailMinManualRange.endInclusive, 0.0)
        // And it is genuinely buildable at the floor itself, or the floor would
        // be advertising a value the synthesiser refuses.
        assertNotNull(
            InsulinShapeV1.synthesize(
                InsulinShapeLandmarksV1(10.0, 40.0, tailMin = bounds.insulinTailMinManualRange.start),
            ),
        )
    }
}
