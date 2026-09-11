package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weight prior has to be a LAW, not a lookup: heavier means less rise per
 * gram, and the relation has to be the inverse one physiology dictates rather
 * than something that merely trends the right way.
 */
class CarbSensitivityPriorV1Test {

    @Test
    fun `the reference weight returns the reference sensitivity`() {
        assertEquals(
            CarbSensitivityPriorV1.REFERENCE_MMOL_PER_G,
            CarbSensitivityPriorV1.fromWeight(CarbSensitivityPriorV1.REFERENCE_WEIGHT_KG),
            1e-9,
        )
    }

    @Test
    fun `doubling the weight halves the sensitivity`() {
        // The inverse law is the whole physiological claim — one gram spread
        // through twice the volume raises glucose half as much. A prior that
        // only decreased monotonically would pass a weaker test and be wrong.
        val light = CarbSensitivityPriorV1.fromWeight(50.0)
        val heavy = CarbSensitivityPriorV1.fromWeight(100.0)
        assertEquals(light / 2.0, heavy, 1e-9)
    }

    @Test
    fun `an unknown weight behaves exactly as the old single population number`() {
        assertEquals(
            CarbSensitivityPriorV1.REFERENCE_MMOL_PER_G,
            CarbSensitivityPriorV1.fromWeight(null),
            1e-9,
        )
    }

    @Test
    fun `an implausible weight is clamped rather than believed`() {
        // A 3 kg entry is a typo. Clamping the INPUT keeps the output on the
        // same law instead of inventing a separate rule for bad data.
        assertEquals(
            CarbSensitivityPriorV1.fromWeight(CarbSensitivityPriorV1.PLAUSIBLE_WEIGHT_KG.start),
            CarbSensitivityPriorV1.fromWeight(3.0),
            1e-9,
        )
        assertEquals(
            CarbSensitivityPriorV1.fromWeight(CarbSensitivityPriorV1.PLAUSIBLE_WEIGHT_KG.endInclusive),
            CarbSensitivityPriorV1.fromWeight(400.0),
            1e-9,
        )
    }

    @Test
    fun `a sensitivity near the reference implies a plausible adult weight`() {
        // The implied weight is how a measured value is compared with the body
        // it came from, so measured values within 15% of the reference must
        // land on an ordinary adult weight, on the correct side of the
        // reference: less rise per gram means a heavier body.
        val lower = CarbSensitivityPriorV1.impliedWeightKg(CarbSensitivityPriorV1.REFERENCE_MMOL_PER_G * 0.85)
        val higher = CarbSensitivityPriorV1.impliedWeightKg(CarbSensitivityPriorV1.REFERENCE_MMOL_PER_G * 1.15)
        assertTrue(
            "0.85 x reference implies $lower kg",
            lower in 75.0..90.0,
        )
        assertTrue(
            "1.15 x reference implies $higher kg",
            higher in 55.0..65.0,
        )
    }

    @Test
    fun `the round trip is exact`() {
        val w = 82.5
        assertEquals(
            w,
            CarbSensitivityPriorV1.impliedWeightKg(CarbSensitivityPriorV1.fromWeight(w)),
            1e-9,
        )
    }
}
