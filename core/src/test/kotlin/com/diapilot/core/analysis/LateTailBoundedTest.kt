package com.diapilot.core.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sibling flag for the OUTER phase boundary.
 *
 * v16 censored a peak pinned to the inner boundary at 120 and left the outer end
 * alone, where a large share of episodes with a late phase have their tail
 * maximum sitting on the window's very last point.
 *
 * Mutation-checked: each guard named below, removed, fails the test that names
 * it.
 */
class LateTailBoundedTest {

    private fun pts(vararg p: Pair<Double, Double>) = p.map { DishCurvePoint(it.first, it.second, 3) }

    // The threshold is 0.03 mmol/min, i.e. 1.8 mmol/hour — a climb that is not
    // in doubt. The first fixtures here sat exactly ON it and failed, which is
    // the right behaviour: a borderline slope should not be called «still
    // rising». Borrowed from truncation rather than chosen for this, and it
    // errs toward NOT flagging, which is the safe direction while the flag is
    // about to gate an amplitude corpus.

    /** Carbonara: still climbing when the grid runs out at 300. */
    @Test
    fun `a tail still climbing into the last point is bounded`() {
        assertTrue(
            isLateTailBounded(
                pts(30.0 to 1.0, 60.0 to 2.0, 120.0 to 3.0, 180.0 to 4.0, 240.0 to 5.2, 300.0 to 8.5),
            ),
        )
    }

    /** A tail that has levelled off is finished, whatever the window's end. */
    @Test
    fun `a tail that flattens is not bounded`() {
        assertFalse(
            isLateTailBounded(
                pts(30.0 to 1.0, 60.0 to 2.0, 120.0 to 3.0, 180.0 to 4.0, 240.0 to 4.2, 300.0 to 4.25),
            ),
        )
    }

    @Test
    fun `a tail already falling is not bounded`() {
        assertFalse(
            isLateTailBounded(
                pts(30.0 to 1.0, 60.0 to 3.0, 120.0 to 4.0, 180.0 to 4.5, 240.0 to 4.0, 300.0 to 3.2),
            ),
        )
    }

    /**
     * The maximum must be ON the last point. A curve that peaked at 180, dipped,
     * and ticked up at the end has SEEN its tail — the final rise is not evidence
     * that the tail never finished.
     */
    @Test
    fun `a late maximum before the end is not bounded by a final uptick`() {
        // The final climb here is STEEP — 0.067 mmol/min, well over the
        // threshold — so only the «maximum must be on the last point» check can
        // reject it. The first fixture used a gentle uptick and passed on the
        // slope test instead, which mutation exposed: deleting the position
        // check left it green.
        assertFalse(
            isLateTailBounded(
                pts(30.0 to 1.0, 120.0 to 3.0, 180.0 to 10.0, 240.0 to 4.0, 300.0 to 8.0),
            ),
        )
    }

    /** An early-only window has no tail to bound — that case is [tailObserved]. */
    @Test
    fun `a window that never reached the late phase is not bounded`() {
        assertFalse(isLateTailBounded(pts(30.0 to 1.0, 60.0 to 2.0, 90.0 to 3.0, 120.0 to 4.0)))
    }

    @Test
    fun `too few points to judge a slope is not bounded`() {
        assertFalse(isLateTailBounded(pts(300.0 to 5.0)))
    }

    /** The pair is INDEPENDENT: a dish can be bounded at one end, both, or
     *  neither, and the two must not be collapsed into one verdict. */
    @Test
    fun `the two boundaries are independent`() {
        val bothBounded = pts(60.0 to 2.0, 120.0 to 4.0, 180.0 to 5.0, 240.0 to 6.0, 300.0 to 9.0)
        assertTrue(isEarlyPeakBounded(bothBounded))
        assertTrue(isLateTailBounded(bothBounded))

        val onlyEarly = pts(60.0 to 2.0, 120.0 to 4.0, 180.0 to 6.0, 240.0 to 6.1, 300.0 to 6.12)
        assertTrue(isEarlyPeakBounded(onlyEarly))
        assertFalse(isLateTailBounded(onlyEarly))

        val onlyLate = pts(60.0 to 5.0, 120.0 to 3.0, 180.0 to 2.0, 240.0 to 3.0, 300.0 to 6.5)
        assertFalse(isEarlyPeakBounded(onlyLate))
        assertTrue(isLateTailBounded(onlyLate))
    }
}
