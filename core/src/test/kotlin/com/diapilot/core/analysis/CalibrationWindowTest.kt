package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationWindowTest {
    private fun window(
        rate: Double? = 0.01, mmol: Double = 9.0, iob: Double = 0.0,
        food: Double? = 300.0, bolus: Double? = 300.0,
        isf: Double = 3.0, carb: Boolean = true,
    ) = detectCalibrationWindow(rate, mmol, iob, food, bolus, isf, carb, 3.9, 10.0)

    @Test fun cleanFlatScarceIsfSurfacesIsf() {
        assertEquals(CalibrationNeed.ISF, window(isf = 3.0, carb = true)!!.need)
    }

    @Test fun bothScarceReturnsBoth() {
        assertEquals(CalibrationNeed.BOTH, window(isf = 3.0, carb = false)!!.need)
    }

    @Test fun onlyCarbScarce() {
        assertEquals(CalibrationNeed.CARB_RATIO, window(isf = 20.0, carb = false)!!.need)
    }

    @Test fun enoughDataNoBanner() {
        assertNull(window(isf = 20.0, carb = true))
    }

    @Test fun movingGlucoseIsNotAWindow() {
        assertNull(window(rate = 0.2, carb = false))
    }

    @Test fun unknownRateIsNotFlat() {
        assertNull(window(rate = null, carb = false))
    }

    @Test fun activeInsulinBlocksIt() {
        assertNull(window(iob = 1.5, carb = false))
    }

    @Test fun recentFoodBlocksIt() {
        assertNull(window(food = 60.0, carb = false))
    }

    @Test fun recentBolusBlocksIt() {
        assertNull(window(bolus = 45.0, carb = false))
    }

    @Test fun nearLowNeverPrompts() {
        assertNull(window(mmol = 4.2, carb = false))
    }

    @Test fun aboveCeilingPlusMarginNeverPrompts() {
        // rangeHi 10 → cap 13; a flat 15 must NOT surface (would read as
        // pressure to correct).
        assertNull(window(mmol = 15.0, carb = false))
    }

    @Test fun mildlyHighWithinCapStillPrompts() {
        assertEquals(CalibrationNeed.BOTH, window(mmol = 12.0, carb = false)!!.need)
    }

    @Test fun noFoodOrBolusInMemoryIsFine() {
        assertEquals(CalibrationNeed.BOTH, window(food = null, bolus = null, carb = false)!!.need)
    }
}
