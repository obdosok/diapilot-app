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
 * These tests pin the places that must agree on that — the clamp and the search
 * corridor — because the previous state had them agreeing on a floor that could
 * not be reached (audit M1).
 *
 * ⚠ THIS FLOOR IS ABOUT THE INSTRUMENT ONLY. A person stating their own end of
 * action is not reporting a truncated measurement, so the hand tier has a
 * domain of its own ([PhysioBoundsV1.insulinTailMinManualRange]) and is applied
 * as entered — see [ManualInsulinTailV1Test], which pins the other half.
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
     * THE HAND PATH DOES NOT PASS THROUGH THIS FLOOR AT ALL.
     *
     * It used to: the resolver checked the entered end of action against
     * `insulinTailMinRange`, so a person stating 200 got their whole shape
     * refused and the measured curve stayed in charge. That check now runs
     * against the hand tier's own domain, which starts at 120 — this test pins
     * that the two really are separate numbers, so a later edit to one does not
     * quietly move the other.
     */
    @Test
    fun `the hand tier's domain is not the instrument's domain`() {
        assertEquals(120.0, bounds.insulinTailMinManualRange.start, 0.0)
        assertTrue(
            "the hand floor must sit below the instrument's reach",
            bounds.insulinTailMinManualRange.start < bounds.insulinTailMinRange.start,
        )
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
        assertTrue("unexpected refusal: ${r.rejected}", r.rejected.isEmpty())
        assertEquals(InsulinParamTierV1.MANUAL, r.shapeTier)
        assertEquals(200.0, checkNotNull(r.landmarks).tailMin, 1e-9)
        assertEquals(200.0, checkNotNull(r.knots).last().minute, 1e-6)
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

    /**
     * AND THE CORRIDOR BUILT AROUND A MEASUREMENT CANNOT ESCAPE THAT RANGE.
     *
     * `boundsAround` OVERRIDES the flat table in `fitOne`, so a ±20% band
     * around a measured end of action opened below the floor — 192 around a
     * measured 240 — and every candidate down there was then discarded one at a
     * time by `tuned`'s own domain check, silently, while the corridor printed
     * on the card claimed the region was searchable. The band is now held
     * inside the range: the fit REFUSES to go under the floor instead of
     * proposing and discarding.
     */
    @Test
    fun `the auto-fit cannot propose an end of action below the floor from a measurement`() {
        val (floor, ceiling) = PhysioAutoFitV1.rangeOf("tail")
        // The value every measured corpus on this instrument actually produces,
        // once the clamp has lifted it to the floor.
        val atFloor = PhysioAutoFitV1.boundsAround(
            InsulinShapeLandmarksV1(20.0, 55.0, plateauEndMin = 90.0, tailMin = 240.0),
        ).getValue("tail")
        assertEquals(floor, atFloor.first, 1e-9)
        assertTrue("the band must still have room upward: $atFloor", atFloor.second > floor)
        assertTrue(atFloor.second <= ceiling)

        // And an uncoerced short measurement collapses ONTO the floor rather
        // than handing the search a region entirely below it.
        val short = PhysioAutoFitV1.boundsAround(
            InsulinShapeLandmarksV1(20.0, 55.0, plateauEndMin = 90.0, tailMin = 150.0),
        ).getValue("tail")
        assertEquals(floor, short.first, 1e-9)
        assertEquals(floor, short.second, 1e-9)

        // A measurement in the middle of the domain keeps its full band: the
        // clamp must bind only where it has to.
        val middle = PhysioAutoFitV1.boundsAround(
            InsulinShapeLandmarksV1(20.0, 55.0, plateauEndMin = 90.0, tailMin = 360.0),
        ).getValue("tail")
        assertEquals(288.0, middle.first, 1e-9)
        assertEquals(432.0, middle.second, 1e-9)
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
