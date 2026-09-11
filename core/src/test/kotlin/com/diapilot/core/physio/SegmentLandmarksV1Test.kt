package com.diapilot.core.physio

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * Each landmark admitted on its own horizon.
 *
 * The user's proposal: stop hunting for a wholly clean dose and take each
 * landmark from wherever it is visible. The user's data supports this —
 * requiring 90 clean minutes to establish a quantity that lives in the first 30
 * threw away five doses whose onset was perfectly readable, while the
 * tail, the one landmark that genuinely needs the long window, was being
 * manufactured by a fallback on two of the doses that did qualify.
 */
class SegmentLandmarksV1Test {
    private val t0 = 1_700_000_000_000L

    /**
     * A physiological fall: flat, accelerating, steepest at [inflection], then
     * decaying. An exponential decay would put its steepest rate at t=0, which
     * is not an insulin action and gives the walk-back no room — the landmarks
     * would be artefacts of the fixture rather than of the reader.
     */
    private fun fall(
        spanMin: Int,
        inflection: Double = 50.0,
        width: Double = 12.0,
        drop: Double = 4.0,
        preSlopePerH: Double = 0.0,
    ): List<GlucosePoint> {
        fun l(t: Double) = 1.0 / (1.0 + exp(-(t - inflection) / width))
        val at0 = l(0.0)
        return (-6..(spanMin / 5)).map { i ->
            val m = i * 5.0
            val mmol = 12.0 + preSlopePerH * m / 60.0 -
                (if (m <= 0.0) 0.0 else drop * (l(m) - at0))
            GlucosePoint(t0 + (m * 60_000).toLong(), mmol)
        }
    }

    private fun read(readings: List<GlucosePoint>, cleanMin: Int) =
        SegmentLandmarkReaderV1.read(readings, t0, t0 + cleanMin * 60_000L)

    /**
     * THE point of the change: a dose with an hour of clean line still tells us
     * when its action began. Under whole-episode admission it told us nothing.
     */
    @Test fun `a short clean stretch still yields an onset`() {
        val seg = read(fall(75), 75)
        assertNotNull("an hour is enough to see the break", seg.onset)
        assertNull("but not enough to see the tail", seg.tailEnd)
        assertNull("nor the slowdown", seg.slowdown)
    }

    @Test fun `a long clean stretch yields every landmark`() {
        val seg = read(fall(240), 240)
        assertNotNull(seg.onset); assertNotNull(seg.peakRate)
        assertNotNull(seg.slowdown); assertNotNull(seg.tailEnd)
        assertTrue(seg.onset!!.minute < seg.peakRate!!.minute)
        assertTrue(seg.peakRate!!.minute < seg.slowdown!!.minute)
        assertTrue(seg.slowdown!!.minute < seg.tailEnd!!.minute)
    }

    /**
     * A gap in OBSERVATION must never be read as a fact about insulin.
     *
     * The whole-episode reader reports the last reading in the window when the
     * rate never returns above the break. When the sensor simply stops early
     * inside a long clean window, that fallback hands back «the sensor went
     * quiet at 150 min» dressed as «insulin finished at 150 min» — discipline
     * #6, arriving through the estimator instead of through the readings.
     *
     * Mutation check: restore `?: trace.after.last().first` for the tail and
     * this fails. (The edge margin catches the case where the readings run to
     * the window's end; only this one isolates the fallback itself.)
     */
    @Test fun `a sensor that stops early does not become an end of action`() {
        // A complete early phase — break, steepest fall, slowdown — followed by
        // a steady decline that never returns to baseline, with the recording
        // stopping at 150 min inside a four-hour clean window.
        fun l(t: Double) = 1.0 / (1.0 + exp(-(t - 35.0) / 9.0))
        val truncated = (-6..30).map { i ->
            val m = i * 5.0
            val mmol = when {
                m <= 0.0 -> 12.0
                m <= 60.0 -> 12.0 - 3.0 * (l(m) - l(0.0))
                else -> 12.0 - 3.0 * (l(60.0) - l(0.0)) - 0.02 * (m - 60.0)
            }
            GlucosePoint(t0 + (m * 60_000).toLong(), mmol)
        }
        val seg = SegmentLandmarkReaderV1.read(truncated, t0, t0 + 240 * 60_000L)
        assertNotNull("the early phase IS observed and must be kept", seg.peakRate)
        assertNotNull(seg.slowdown)
        assertNull("where the sensor stopped is not where insulin stopped", seg.tailEnd)
    }

    /**
     * A landmark outside its own physiological domain is dropped ALONE.
     *
     * This is the rule that per-landmark admission makes possible, and it is
     * not cosmetic: on a real device three doses reported onsets past 75
     * minutes — the reader had locked onto a much later fall — and those
     * values were holding the onset median up by about ten minutes. Dropping them
     * (and only them) corrected it, while their slowdown and tail, which were
     * fine, stayed in the corpus. Under whole-episode admission the choice was
     * to keep the nonsense or lose the dose entirely.
     */
    @Test fun `an out of domain landmark is dropped without taking the dose with it`() {
        // Steepest fall at 120 min: a legal peak, but the walk-back puts the
        // break past the 60-minute onset bound.
        val seg = read(fall(240, inflection = 120.0, width = 15.0), 240)
        assertNull("an onset beyond the domain is not evidence about onset", seg.onset)
        assertNotNull("but the peak of the same trace still is", seg.peakRate)
        assertNotNull(seg.slowdown)
        assertNotNull(seg.tailEnd)
    }

    /**
     * The sharpest part of the proposal: the onset is legible through food,
     * because the baseline is the pre-dose inertia rather than a flat line.
     * It is marked and down-weighted, because what it measures then is «when
     * insulin overcame the rise», which is later than when insulin began — the
     * two quantities M-02 separates.
     */
    @Test fun `an onset is readable against a climbing background at reduced weight`() {
        val seg = read(fall(100, preSlopePerH = 2.4), 100)
        val onset = seg.onset
        assertNotNull("food on board must not hide the break", onset)
        assertTrue("and it must say food was confounding it", onset!!.confoundedByFood)
        assertEquals(SegmentLandmarkReaderV1.RISING_BACKGROUND_WEIGHT, onset.weight, 1e-9)
    }

    /**
     * Assembly mixes doses, so nothing guarantees the medians increase. When
     * they do not, that is a finding about the corpus — reported, never quietly
     * repaired into a valid-looking curve.
     */
    @Test fun `assembly reports an ordering conflict instead of hiding it`() {
        fun s(minute: Double, which: Int) = SegmentLandmarksV1(
            onset = if (which == 0) LandmarkSampleV1(1L, minute, 1.0, false) else null,
            peakRate = if (which == 1) LandmarkSampleV1(2L, minute, 1.0, false) else null,
        )
        val conflicted = SegmentLandmarkReaderV1.assemble(listOf(s(40.0, 0), s(20.0, 1)))
        assertNotNull("a peak before the onset must be named", conflicted.orderingConflict)
        assertNull("and must not produce landmarks", conflicted.landmarks)

        val clean = SegmentLandmarkReaderV1.assemble(listOf(s(15.0, 0), s(50.0, 1)))
        assertNull(clean.orderingConflict)
    }

    /** Per-landmark counts are the honest output: the early profile is well
     * determined and the tail stays thin, which is what CGM can and cannot see. */
    @Test fun `each landmark reports its own sample count`() {
        val samples = listOf(read(fall(75), 75), read(fall(240), 240), read(fall(240), 240))
        val profile = SegmentLandmarkReaderV1.assemble(samples)
        assertEquals(3, profile.onset.pooled!!.samples)
        assertEquals(2, profile.tailEnd!!.samples)
    }
}
