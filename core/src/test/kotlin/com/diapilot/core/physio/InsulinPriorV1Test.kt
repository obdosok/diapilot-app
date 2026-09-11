package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridBlindDayParityTest
import com.diapilot.core.hybrid.HybridCdfKnot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped insulin block must not impersonate a measurement.
 *
 * It is one person's fitted values (ISF and insulin timing), and every fresh
 * install runs on them. A replay of a real history showed a new user lives on
 * them for three days; on the ISF, whose learning path has yet to produce a
 * single row of evidence, potentially forever.
 */
class InsulinPriorV1Test {
    private fun shipped() = HybridBlindDayParityTest().model(
        JSONObject(
            checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json"))
                .bufferedReader().readText(),
        ),
    )

    @Test fun `the shipped block is recognised as a prior`() {
        assertTrue("no measured curve means no measurement", InsulinPriorV1.isPrior(shipped()))
    }

    /**
     * The band, not the median, is what changes. A population centre is still
     * the best available guess; claiming to know it to ±13% is not.
     */
    @Test fun `widening moves the band and leaves the median alone`() {
        val base = shipped()
        val wide = InsulinPriorV1.widen(base)
        assertEquals("the median is still the best guess", base.insulin.isf, wide.insulin.isf, 0.0)
        assertTrue("the band must widen downward", wide.insulin.isfLow <= InsulinPriorV1.ISF_LOW_MMOL_PER_U)
        assertTrue("and upward", wide.insulin.isfHigh >= InsulinPriorV1.ISF_HIGH_MMOL_PER_U)
        assertTrue(
            "and it must actually be wider than shipped",
            (wide.insulin.isfHigh - wide.insulin.isfLow) > (base.insulin.isfHigh - base.insulin.isfLow),
        )
    }

    /**
     * Direction is the safety argument. The high-ISF arm predicts the deeper
     * fall, so it drives the corridor's lower bound and therefore how early a
     * hypo can be seen coming — widening can only move that EARLIER.
     */
    @Test fun `widening never narrows the hypo margin`() {
        val base = shipped()
        val wide = InsulinPriorV1.widen(base)
        assertTrue(wide.insulin.isfHigh >= base.insulin.isfHigh)
        assertTrue(wide.insulin.isfLow <= base.insulin.isfLow)
    }

    /**
     * And a measured curve ends it: once this person's own segments are
     * installed, the prior's uncertainty must not be re-applied on top of them.
     *
     * Mutation check: drop the `isPrior` guard in `widen` and this fails.
     */
    @Test fun `a measured curve is left untouched`() {
        val base = shipped()
        val measured = base.copy(
            insulin = base.insulin.copy(
                actionCdfKnots = listOf(
                    HybridCdfKnot(0.0, 0.0), HybridCdfKnot(20.0, .1),
                    HybridCdfKnot(60.0, .6), HybridCdfKnot(120.0, 1.0),
                ),
            ),
        )
        assertFalse(InsulinPriorV1.isPrior(measured))
        assertEquals(measured.insulin.isfLow, InsulinPriorV1.widen(measured).insulin.isfLow, 0.0)
        assertEquals(measured.insulin.isfHigh, InsulinPriorV1.widen(measured).insulin.isfHigh, 0.0)
    }
}
