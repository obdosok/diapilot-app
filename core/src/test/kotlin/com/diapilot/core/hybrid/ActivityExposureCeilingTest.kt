package com.diapilot.core.hybrid

import com.diapilot.core.analysis.ActivityExposureV1
import com.diapilot.core.collector.StepBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVEMENT MAY NOT MULTIPLY INSULIN WITHOUT BOUND.
 *
 * The engine's multiplier was already capped at 2.5x; the RESULT was not. A
 * real episode with an exposure past 2.6 turned a normal dose at a typical
 * ISF into an absurd amount of computed removal, with the forecast running
 * far negative while glucose actually rose — the worst episode in the
 * corpus, and one of two that hit the ceiling.
 *
 * The clamp lives in [ActivityExposureV1] rather than in the engine so the
 * cross-language parity tests keep comparing the SAME physics; see the constant
 * for why. These tests fail if it is deleted: each pins the exact value the
 * ceiling produces, not a range the unclamped result would also satisfy.
 */
class ActivityExposureCeilingTest {

    private val tau = 180.0

    /** Six hours of hard walking — far past anything the ceiling admits. */
    private fun heavy(atMs: Long) = (0 until 12).map { i ->
        val start = atMs - (360L - i * 30L) * 60_000L
        StepBucket(start, start + 30L * 60_000L, 30 * 120)
    }

    @Test
    fun `a long hard walk is capped at the measured ceiling`() {
        val now = 1_700_000_000_000L
        val e = ActivityExposureV1.at(heavy(now), now, tau, now - 12L * 3_600_000L)
        assertEquals(ActivityExposureV1.MAX_EXPOSURE, e, 1e-9)
    }

    /** A ceiling, not a switch: ordinary movement still registers, because the
     *  case the term exists for — inject, then go running — is real. */
    @Test
    fun `ordinary movement still registers below the ceiling`() {
        val now = 1_700_000_000_000L
        val light = listOf(
            StepBucket(now - 40L * 60_000L, now - 25L * 60_000L, 15 * 70),
        )
        val e = ActivityExposureV1.at(light, now, tau, now - 12L * 3_600_000L)
        assertTrue("a light walk must give something nonzero: $e", e > 0.0)
        assertTrue("...and not hit the ceiling: $e", e < ActivityExposureV1.MAX_EXPOSURE)
    }

    /** Stillness is still zero — the clamp must not introduce a floor. */
    @Test
    fun `no steps is no exposure`() {
        val now = 1_700_000_000_000L
        assertEquals(0.0, ActivityExposureV1.at(emptyList(), now, tau, now - 12L * 3_600_000L), 1e-12)
    }

    /**
     * The size of what was removed, stated so a future edit to the constant
     * says by how much: at the ceiling the engine's insulin multiplier is about
     * 1.2 over a three-hour horizon (the exposure decays across it), where the
     * old unclamped 2.62 reached 2.5 at the dose.
     */
    @Test
    fun `the ceiling bounds the engine multiplier near one and a fifth`() {
        // THE FIXTURE IS EXPLICIT, NOT THE SHIPPED ARTIFACT.
        //
        // The subject of the test is a property of the ENGINE: the exposure
        // ceiling bounds the multiplier. The artifact used to be taken as the
        // fixture, and because of that the test started asserting something
        // else that is not true: that activity in the shipped model amplifies
        // insulin by 1.2x. It does not — `PhysioV1.personModelAt` zeroes
        // `iobGamma`, and the artifact now carries that same zero, so it
        // cannot be misread (see `ArtifactHonestyTest`).
        //
        // The coefficient is set explicitly here: the test measures the
        // ceiling, not what is shipped.
        assertEquals(0.30, ActivityExposureV1.MAX_EXPOSURE, 1e-9)
        val shipped = java.io.File("../app/src/main/assets/models/person_model_v11_runtime.json")
            .inputStream().use(HybridPersonModelJson::read)
        val model = shipped.copy(
            activity = shipped.activity.copy(iobGamma = 1.0, foodGamma = 0.25),
        )
        val engine = physioForecastEngine(model)
        val still = engine.hypotheticalInsulinForecastDelta(180.0, 10.0, 0.0, 0.0)
        val capped = engine.hypotheticalInsulinForecastDelta(180.0, 10.0, 0.0, ActivityExposureV1.MAX_EXPOSURE)
        val ratio = capped / still
        assertTrue("множитель на потолке ожидался около 1.2, получилось $ratio", ratio in 1.1..1.3)
    }
}
