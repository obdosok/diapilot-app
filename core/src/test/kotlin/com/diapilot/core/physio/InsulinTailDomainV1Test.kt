package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridCdfKnot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE END-OF-ACTION DOMAIN, AND WHY ITS FLOOR IS THE INSTRUMENT'S REACH.
 *
 * The floor was 120 minutes, which no bolus analogue reaches. It never clamped
 * anything, and that was not luck: the measuring instrument cannot see past
 * four hours — a dose's window is capped at `InsulinProfileRuntime.CAP_MS` =
 * 240 min and closed earlier by the next injection, the end landmark needs
 * [SegmentLandmarkReaderV1.TAIL_HORIZON_MIN] clean minutes inside that, and
 * "end" is the rate returning to the pre-dose slope, which a rising background
 * meets early. So every measured end came out in roughly 150..230 and a
 * 120-minute floor agreed with all of them.
 *
 * Raising the floor to the instrument's own reach makes the clamp SAY
 * something: below it, the number describes the window and not the insulin.
 * These tests pin the three places that must now agree on that — the clamp, the
 * hand entry, and the search corridor — because the previous state had them
 * agreeing on a floor that could not be reached (audit M1).
 */
class InsulinTailDomainV1Test {

    private val bounds = PhysioBoundsV1()

    @Test
    fun `the floor is the instrument's own reach and not below it`() {
        assertEquals(240.0, bounds.insulinTailMinRange.start, 0.0)
        assertEquals(600.0, bounds.insulinTailMinRange.endInclusive, 0.0)
    }

    /**
     * A short measurement is MOVED and NAMED, never discarded: losing that
     * once left a curve built, displayed, and then silently refused by the code
     * that installs it.
     */
    @Test
    fun `a measured end below the floor is clamped up and reported as moved`() {
        val short = InsulinShapeLandmarksV1(20.0, 55.0, plateauEndMin = 90.0, tailMin = 180.0)
        val (coerced, moved) = InsulinShapeV1.coerceIntoDomain(short)

        assertEquals(bounds.insulinTailMinRange.start, coerced.tailMin, 1e-9)
        val note = moved.single { it.landmark == InsulinLandmark.TAIL_END }
        assertEquals(180.0, note.fromMin, 1e-9)
        assertEquals(240.0, note.toMin, 1e-9)
        // And the clamped quadruple is still a curve the synthesiser accepts —
        // a floor that produced an unbuildable shape would fail closed instead.
        assertNotNull(InsulinShapeV1.synthesize(coerced))
    }

    @Test
    fun `an end already inside the domain is left exactly where it was measured`() {
        val (coerced, moved) = InsulinShapeV1.coerceIntoDomain(
            InsulinShapeLandmarksV1(20.0, 55.0, plateauEndMin = 90.0, tailMin = 300.0),
        )
        assertEquals(300.0, coerced.tailMin, 1e-9)
        assertTrue("nothing should have moved: $moved", moved.none { it.landmark == InsulinLandmark.TAIL_END })
    }

    /**
     * The hand path REFUSES rather than clamps, and that is deliberate: a
     * measurement may be corrected by a population bound, a person's own
     * statement may not be silently rewritten into a different one.
     */
    @Test
    fun `a hand entered end below the floor is refused, not clamped`() {
        val measured = listOf(
            0.0 to 0.0, 20.0 to .02, 40.0 to .28, 60.0 to .60, 120.0 to .95, 260.0 to 1.0,
        ).map { (m, f) -> HybridCdfKnot(m, f) }
        val r = InsulinParameterResolverV1.resolve(
            ManualInsulinParamsV1(onsetMin = 25.0, peakMin = 55.0, tailMin = 200.0),
            measured,
            InsulinParamTierV1.TAGGED_CORRECTION,
            2.0,
            InsulinParamTierV1.TAGGED_CORRECTION,
            InsulinShapeLandmarksV1(15.0, 75.0, tailMin = 300.0),
        )
        assertTrue(
            "expected an out-of-domain refusal, got ${r.rejected}",
            InsulinParameterResolverV1.SHAPE_OUT_OF_DOMAIN in r.rejected,
        )
        assertEquals("the measured curve stays in charge", measured, r.knots)
        assertEquals(InsulinParamTierV1.TAGGED_CORRECTION, r.shapeTier)
    }

    /**
     * The search corridor must sit INSIDE the artifact's domain. When it did
     * not, the fit could propose an end of action the model then clamped, so
     * the proposal and the installed value disagreed with nobody told.
     */
    @Test
    fun `the auto-fit corridor for the tail sits inside the domain`() {
        val (low, high) = PhysioAutoFitV1.rangeOf("tail")
        assertEquals(240.0, low, 0.0)
        assertEquals(480.0, high, 0.0)
        assertTrue(low >= bounds.insulinTailMinRange.start)
        assertTrue(high <= bounds.insulinTailMinRange.endInclusive)
    }

    /** And a candidate under the floor produces no model at all, rather than one
     *  whose numbers were quietly moved after the loss was computed. */
    @Test
    fun `the fitter skips a candidate whose end of action is under the floor`() {
        val person = com.diapilot.core.hybrid.HybridBlindDayParityTest().model(
            org.json.JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json"))
                    .bufferedReader().readText(),
            ),
        )
        fun knobs(tail: Double) = PhysioAutoFitV1.Knobs(
            isf = 2.0,
            onsetMin = 20.0,
            fullSpeedMin = 55.0,
            phaseMin = 30.0,
            tailMin = tail,
            emptyingKcalPerHour = 200.0,
            carbSieving = 0.8,
            carbSpread = 1.0,
            trustRamp = 0.0,
        )
        assertNull(PhysioAutoFitV1.tuned(person, knobs(200.0)))
        assertNotNull(PhysioAutoFitV1.tuned(person, knobs(300.0)))
    }
}
