package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridCdfKnot
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 — what the user sets by hand wins, and we may only report that we
 * disagree.
 */
class ManualInsulinParamsV1Test {
    /** The user's own reading of their sensor: onset 25-30, peak ~55, tail ~145. */
    private val reported = InsulinShapeLandmarksV1(27.0, 55.0, tailMin = 145.0)
    private val prior = InsulinShapeLandmarksV1(15.0, 75.0, tailMin = 300.0)

    /** "Most active action from 30 to 70, after that it tails off." */
    private val phase = InsulinShapeLandmarksV1(27.0, 30.0, plateauEndMin = 70.0, tailMin = 145.0)

    /**
     * A measured curve shaped like the one a real correction produced.
     *
     * It ENDS AT 260, not at 130 as it used to. `insulinTailMinRange` now floors
     * the end of action at 240 (audit M1), and every measured curve reaches this
     * resolver through `InsulinShapeV1.coerceIntoDomain`, so a corpus curve that
     * ends inside the third hour is no longer one the runtime can hand over. A
     * fixture below the floor would test a path production cannot reach — and it
     * would refuse a partial manual entry for a reason the user never caused.
     */
    private val measured = listOf(
        0.0 to 0.0, 15.0 to 0.0, 20.0 to .02, 30.0 to .12, 40.0 to .28,
        50.0 to .45, 60.0 to .60, 80.0 to .80, 100.0 to .92, 180.0 to .99, 260.0 to 1.0,
    ).map { (m, f) -> HybridCdfKnot(m, f) }

    private fun resolve(
        manual: ManualInsulinParamsV1,
        curve: List<HybridCdfKnot>? = measured,
        isf: Double? = 2.33,
        tier: InsulinParamTierV1 = InsulinParamTierV1.TAGGED_CORRECTION,
    ) = InsulinParameterResolverV1.resolve(manual, curve, tier, isf, tier, prior)

    // ---- landmarks -------------------------------------------------------

    @Test fun landmarksReadTheCurveTheRuntimeWouldHaveRead() {
        val lm = checkNotNull(InsulinShapeV1.landmarks(measured))
        assertEquals(20.0, lm.onsetMin, 1e-9)
        assertEquals(260.0, lm.tailMin, 1e-9)
        assertTrue("steepest segment is between 30 and 60: ${lm.peakMin}", lm.peakMin in 30.0..60.0)
    }

    @Test fun aCurveTooShortToHaveAShapeHasNoLandmarks() {
        assertNull(InsulinShapeV1.landmarks(listOf(HybridCdfKnot(0.0, 0.0), HybridCdfKnot(60.0, 1.0))))
    }

    // ---- warp ------------------------------------------------------------

    @Test fun warpMovesTheLandmarksAndKeepsEveryFraction() {
        val moved = checkNotNull(InsulinShapeV1.warp(measured, reported))
        val lm = checkNotNull(InsulinShapeV1.landmarks(moved))
        assertEquals(reported.onsetMin, lm.onsetMin, 1e-6)
        assertEquals(reported.tailMin, lm.tailMin, 1e-6)
        assertEquals(
            "the warp moves time, never probability",
            measured.map { it.fraction }, moved.map { it.fraction },
        )
    }

    /**
     * This is why the warp exists rather than a redraw: the measured curve's
     * interior detail — how much action has landed midway — must survive being
     * told when the curve starts and ends.
     */
    @Test fun warpKeepsTheInteriorDetailOfTheMeasuredCurve() {
        val moved = checkNotNull(InsulinShapeV1.warp(measured, reported))
        assertEquals(measured.size, moved.size)
        assertTrue(moved.zipWithNext().all { (a, b) -> b.minute > a.minute })
    }

    @Test fun warpRefusesLandmarksThatAreNotACurve() {
        assertNull(InsulinShapeV1.warp(measured, InsulinShapeLandmarksV1(60.0, 40.0, tailMin = 200.0)))
    }

    // ---- the active phase ------------------------------------------------

    /**
     * The landmark that a single peak cannot carry. Rate must be FLAT across
     * the stated phase and strictly falling after it — "action continues,
     * but is already tailing off".
     */
    @Test fun aStatedActivePhaseIsFlatAndThenDecays() {
        val knots = checkNotNull(InsulinShapeV1.synthesize(phase))
        fun rateAt(minute: Double): Double {
            val bin = knots.zipWithNext().first { (a, b) -> minute >= a.minute && minute <= b.minute }
            return (bin.second.fraction - bin.first.fraction) / (bin.second.minute - bin.first.minute)
        }
        val inside = listOf(35.0, 45.0, 55.0, 65.0).map { rateAt(it) }
        val spread = (inside.max() - inside.min()) / inside.max()
        assertTrue("the active phase must hold flat, spread was $spread", spread < .05)
        assertTrue("and decay after it", rateAt(90.0) < inside.min() * .8)
        assertTrue("still acting late in the tail", rateAt(120.0) > 0.0)
    }

    /**
     * A phase is not a peak: half the action must NOT already be gone when the
     * phase opens, or the flat stretch is decoration on a triangle.
     */
    @Test fun aStatedActivePhaseCarriesItsMassInsideThePhase() {
        val knots = checkNotNull(InsulinShapeV1.synthesize(phase))
        fun at(minute: Double) = knots.first { it.minute >= minute }.fraction
        assertTrue("too much action before the phase opens: ${at(30.0)}", at(30.0) < .10)
        val inPhase = at(70.0) - at(30.0)
        assertTrue("the phase must carry the bulk, carried $inPhase", inPhase > .55)
    }

    /** Measured curves are read the same way, so the two are comparable. */
    @Test fun aMeasuredPlateauIsRecognisedAsAPhase() {
        val knots = checkNotNull(InsulinShapeV1.synthesize(phase))
        val lm = checkNotNull(InsulinShapeV1.landmarks(knots))
        assertTrue("no phase detected in a curve built as a phase", lm.plateauEndMin != null)
        assertTrue("phase opens near 30, got ${lm.peakMin}", abs(lm.peakMin - 30.0) <= 8.0)
        assertTrue("phase closes near 70, got ${lm.plateauEndMin}", abs(lm.plateauEndMin!! - 70.0) <= 8.0)
    }

    /**
     * Any smooth curve has SOME neighbourhood within 15% of its peak rate, so
     * the detector reports a phase for both. What separates them is width, and
     * that is the number worth asserting: a stated 40-minute plateau must read
     * as far wider than the shoulder of a single-moded curve of the same span.
     */
    @Test fun aStatedPhaseReadsMuchWiderThanASingleModeShoulder() {
        fun width(l: InsulinShapeLandmarksV1) = l.activeEndMin - l.peakMin
        val stated = width(checkNotNull(InsulinShapeV1.landmarks(checkNotNull(InsulinShapeV1.synthesize(phase)))))
        val single = width(checkNotNull(InsulinShapeV1.landmarks(checkNotNull(InsulinShapeV1.synthesize(reported)))))
        // Measured: a stated 30..70 reads back as exactly 40 min
        // wide, while the single-moded curve's shoulder reads 25.
        assertEquals(phase.plateauEndMin!! - phase.peakMin, stated, 5.0)
        assertTrue("stated $stated must read wider than a shoulder $single", stated > single + 10.0)
    }

    /**
     * 27 / 30 / 190 / 300 is a properly ORDERED quadruple — only the phase's
     * own end is out of range. Without a bound of its own it would sail
     * through, and a three-hour flat-out phase is not rapid insulin.
     */
    @Test fun anActivePhaseOutsideThePhysiologicalRangeIsRefused() {
        val r = resolve(
            ManualInsulinParamsV1(onsetMin = 27.0, peakMin = 30.0, plateauEndMin = 190.0, tailMin = 300.0),
        )
        assertTrue(
            "expected an out-of-domain refusal, got ${r.rejected}",
            InsulinParameterResolverV1.SHAPE_OUT_OF_DOMAIN in r.rejected,
        )
        assertEquals(measured, r.knots)
    }

    @Test fun anActivePhaseEndingAfterTheCurveIsRefused() {
        val r = resolve(
            ManualInsulinParamsV1(onsetMin = 27.0, peakMin = 30.0, plateauEndMin = 320.0, tailMin = 300.0),
        )
        assertTrue(InsulinParameterResolverV1.SHAPE_OUT_OF_DOMAIN in r.rejected)
        assertEquals(measured, r.knots)
    }

    // ---- synthesize ------------------------------------------------------

    @Test fun synthesisPlacesItsModeAtTheStatedPeak() {
        val knots = checkNotNull(InsulinShapeV1.synthesize(reported))
        val steepest = knots.zipWithNext()
            .maxByOrNull { (a, b) -> (b.fraction - a.fraction) / (b.minute - a.minute) }!!
        val mode = (steepest.first.minute + steepest.second.minute) / 2.0
        assertTrue("mode $mode should sit at ${reported.peakMin}", abs(mode - reported.peakMin) <= 5.0)
    }

    @Test fun synthesisIsFlatBeforeOnsetAndCompleteAtTheTail() {
        val knots = checkNotNull(InsulinShapeV1.synthesize(reported))
        assertTrue(knots.filter { it.minute < reported.onsetMin }.all { it.fraction == 0.0 })
        assertEquals(1.0, knots.last().fraction, 1e-9)
        assertEquals(reported.tailMin, knots.last().minute, 1e-9)
    }

    /**
     * The user rejected the triangle by name. A triangular density decays
     * linearly, so exactly half its mass sits before the peak; the user's
     * sensor says the action holds a shoulder past the peak and then runs a
     * long tail.
     */
    @Test fun synthesisIsNotATriangle() {
        val knots = checkNotNull(InsulinShapeV1.synthesize(reported))
        val atPeak = knots.first { it.minute >= reported.peakMin }.fraction
        assertTrue("a triangle would already be near half at the peak: $atPeak", atPeak < .45)
        assertTrue("at least a 4–5 point curve, as asked", knots.size >= 5)
    }

    // ---- precedence ------------------------------------------------------

    @Test fun manualShapeWinsOverAMeasuredCurve() {
        val r = resolve(ManualInsulinParamsV1(onsetMin = 27.0, peakMin = 55.0, tailMin = 300.0))
        assertEquals(InsulinParamTierV1.MANUAL, r.shapeTier)
        assertEquals(27.0, checkNotNull(r.landmarks).onsetMin, 1e-6)
        // ISF was not overridden, so it stays measured.
        assertEquals(InsulinParamTierV1.TAGGED_CORRECTION, r.isfTier)
        assertEquals(2.33, checkNotNull(r.isfMmolPerU), 1e-9)
    }

    @Test fun manualIsfWinsWithoutTouchingTheShape() {
        val r = resolve(ManualInsulinParamsV1(isfMmolPerU = 2.5), isf = 4.2)
        assertEquals(InsulinParamTierV1.MANUAL, r.isfTier)
        assertEquals(2.5, checkNotNull(r.isfMmolPerU), 1e-9)
        assertEquals(InsulinParamTierV1.TAGGED_CORRECTION, r.shapeTier)
        assertEquals(measured, r.knots)
    }

    /**
     * Cold start, which is the whole point of P1: a new user has no
     * measured curve at all and must still get the shape they set manually.
     */
    @Test fun manualShapeWorksWithNothingMeasured() {
        val r = resolve(
            ManualInsulinParamsV1(onsetMin = 27.0, peakMin = 55.0, tailMin = 300.0, isfMmolPerU = 2.5),
            curve = null, isf = null,
        )
        assertEquals(InsulinParamTierV1.MANUAL, r.shapeTier)
        assertEquals(InsulinParamTierV1.MANUAL, r.isfTier)
        assertTrue(checkNotNull(r.knots).size >= 4)
        assertTrue("nothing measured, nothing to disagree with", r.divergences.isEmpty())
    }

    @Test fun withNoManualEntryTheMeasuredTierIsReportedAsIs() {
        val r = resolve(ManualInsulinParamsV1.EMPTY, tier = InsulinParamTierV1.GATED_EPISODE)
        assertEquals(InsulinParamTierV1.GATED_EPISODE, r.shapeTier)
        assertEquals(measured, r.knots)
    }

    @Test fun withNothingAtAllTheTierIsThePrior() {
        val r = resolve(ManualInsulinParamsV1.EMPTY, curve = null, isf = null)
        assertEquals(InsulinParamTierV1.PRIOR, r.shapeTier)
        assertEquals(InsulinParamTierV1.PRIOR, r.isfTier)
    }

    // ---- partial entry and domain ---------------------------------------

    /**
     * A single field is validated against the RESOLVED triple. Onset 90 is
     * inside its own bound but lands after a measured peak of ~45, and a curve
     * whose onset follows its peak is not a curve.
     */
    @Test fun aPartialEntryThatBreaksTheOrderIsRefusedNotClamped() {
        val r = resolve(ManualInsulinParamsV1(onsetMin = 90.0))
        assertTrue(InsulinParameterResolverV1.SHAPE_OUT_OF_DOMAIN in r.rejected)
        assertEquals("the measured curve stays in charge", measured, r.knots)
        assertEquals(InsulinParamTierV1.TAGGED_CORRECTION, r.shapeTier)
    }

    /**
     * Each of 50 / 30 / 300 sits inside its OWN bound; together they are not a
     * curve. Checking the fields one at a time would let this through — the
     * order is a separate constraint and has to be asserted separately.
     */
    @Test fun landmarksInBoundsButOutOfOrderAreRefused() {
        val r = resolve(ManualInsulinParamsV1(onsetMin = 50.0, peakMin = 30.0, tailMin = 300.0))
        assertTrue(
            "expected an out-of-domain refusal, got ${r.rejected}",
            InsulinParameterResolverV1.SHAPE_OUT_OF_DOMAIN in r.rejected,
        )
        assertEquals(measured, r.knots)
        assertEquals(InsulinParamTierV1.TAGGED_CORRECTION, r.shapeTier)
    }

    @Test fun aPartialEntryThatKeepsTheOrderIsAccepted() {
        val r = resolve(ManualInsulinParamsV1(onsetMin = 27.0))
        assertTrue(r.rejected.isEmpty())
        assertEquals(InsulinParamTierV1.MANUAL, r.shapeTier)
        val lm = checkNotNull(r.landmarks)
        assertEquals(27.0, lm.onsetMin, 1e-6)
        assertEquals("the untouched tail stays where it was measured", 260.0, lm.tailMin, 1e-6)
    }

    @Test fun anIsfOutsideThePhysiologicalDomainIsRefused() {
        val r = resolve(ManualInsulinParamsV1(isfMmolPerU = 40.0))
        assertTrue(InsulinParameterResolverV1.ISF_OUT_OF_DOMAIN in r.rejected)
        assertEquals(2.33, checkNotNull(r.isfMmolPerU), 1e-9)
        assertEquals(InsulinParamTierV1.TAGGED_CORRECTION, r.isfTier)
    }

    // ---- divergence ------------------------------------------------------

    @Test fun aRealDisagreementIsReported() {
        val r = resolve(ManualInsulinParamsV1(isfMmolPerU = 2.5), isf = 4.2)
        val d = checkNotNull(r.divergences.firstOrNull { it.field == ManualInsulinParamsV1.ISF })
        assertEquals(2.5, d.manual, 1e-9)
        assertEquals(4.2, d.measured, 1e-9)
        assertTrue(d.delta > 0)
    }

    /** A screen that flags a rounding difference teaches the user to ignore it. */
    @Test fun agreementWithinNoiseIsNotReported() {
        val r = resolve(ManualInsulinParamsV1(isfMmolPerU = 2.33), isf = 2.4)
        assertTrue("3% apart is the same number twice: ${r.divergences}", r.divergences.isEmpty())
    }

    @Test fun divergenceIsReportedEvenThoughManualStillWins() {
        val r = resolve(
            ManualInsulinParamsV1(onsetMin = 27.0, peakMin = 55.0, tailMin = 300.0, isfMmolPerU = 2.5),
            isf = 4.2,
        )
        assertEquals(InsulinParamTierV1.MANUAL, r.shapeTier)
        assertEquals(2.5, checkNotNull(r.isfMmolPerU), 1e-9)
        assertTrue(r.divergences.any { it.field == ManualInsulinParamsV1.ISF })
        // 300 against a measured 260 is 13% — below the reporting floor, and
        // deliberately so: the tail is the landmark the user is least sure of.
        assertTrue(r.divergences.none { it.field == ManualInsulinParamsV1.TAIL })
    }
}
