package com.diapilot.core.physio

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two peak rulers must agree.
 *
 * `synthesize` WRITES `peakMin` as «the minute full rate is reached»;
 * `landmarks` READS it back off the curve. Both mean the same event, and they
 * used to disagree by up to nineteen minutes — the shipped 0.85
 * threshold caught the smoothstep's flattening top long before the plateau
 * actually opened. That made «measured peak 51» and «fitted peak 93»
 * incomparable numbers wearing the same name.
 *
 * This pins the round trip. It is the whole reason the threshold has the value
 * it has, so it is the test that must fail if anyone moves it back.
 */
class PeakRoundTripTest {

    private fun roundTrip(peak: Double): Double? {
        val want = InsulinShapeLandmarksV1(23.0, peak, peak + 18.0, 190.0)
        if (!want.ordered) return null
        val (lm, moved) = InsulinShapeV1.coerceIntoDomain(want)
        if (moved.isNotEmpty()) return null
        val k = InsulinShapeV1.synthesize(lm, InsulinShapeV1.TAIL_SHARE) ?: return null
        return InsulinShapeV1.landmarks(k)?.peakMin?.minus(lm.peakMin)
    }

    @Test
    fun `a written peak reads back as itself, within one knot`() {
        listOf(35.0, 55.0, 75.0, 93.0, 120.0).forEach { pk ->
            val err = roundTrip(pk)
            assertNotNull("round trip must produce a peak for $pk", err)
            assertTrue(
                "peak $pk read back off by ${err}м — the rulers have drifted apart again",
                Math.abs(err!!) <= 5.5,
            )
        }
    }

    @Test
    fun `the error does not grow with the ramp`() {
        // The defect's signature was a widening gap, not a constant offset:
        // -1 at peak 35 and -20 at peak 120. A constant bias would be harmless;
        // a growing one silently rescales every comparison.
        val near = Math.abs(roundTrip(35.0)!!)
        val far = Math.abs(roundTrip(120.0)!!)
        assertTrue("error grew from $near to $far across the range", far - near <= 4.0)
    }

    @Test
    fun `the active phase survives the stricter threshold`() {
        // A threshold too close to 1.0 would collapse the plateau to a single
        // bin and report no active phase at all — the failure mode that makes
        // raising it dangerous.
        val k = InsulinShapeV1.synthesize(
            InsulinShapeLandmarksV1(11.0, 51.0, 69.0, 133.0), InsulinShapeV1.TAIL_SHARE,
        )!!
        val lm = InsulinShapeV1.landmarks(k)
        assertNotNull("the era curve must still read", lm)
        assertNotNull("the active phase must not vanish", lm!!.plateauEndMin)
        assertTrue("the phase must have width", lm.plateauEndMin!! > lm.peakMin)
    }
}
