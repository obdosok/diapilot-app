package com.diapilot.core.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A meal that rose and then STOPPED has shown its peak — even if the window was
 * cut short and the flat top's argmax lands on the last checkpoint.
 *
 * The user caught this from the graph: a smoothie with no insulin whose rise
 * clearly levelled off was being filed as "censored, still rising", which then
 * dragged the pooled ttp up (45 → 60). The fixtures below have the shapes of
 * recovered smoothie curves.
 */
class PeakCensoringTest {

    private fun pts(vararg pairs: Pair<Double, Double>) =
        pairs.map { DishCurvePoint(it.first, it.second, n = 4) }

    @Test
    fun `an untruncated window is never censored`() {
        val stillRising = pts(30.0 to 1.0, 45.0 to 3.0, 60.0 to 5.0)
        assertTrue(isPeakObserved(stillRising, truncated = false))
    }

    @Test
    fun `a plateau at the end is observed, not censored`() {
        // Levelled off: slope 0.015 over the last 15 min.
        val plateau = pts(20.0 to 0.08, 30.0 to 1.92, 45.0 to 3.10, 60.0 to 3.32)
        assertTrue("a plateau shows its peak", isPeakObserved(plateau, truncated = true))
    }

    @Test
    fun `a decline at the end is observed`() {
        // Turned down over the last 15 min.
        val decline = pts(15.0 to 2.98, 30.0 to 4.04, 45.0 to 4.82, 60.0 to 4.65)
        assertTrue(isPeakObserved(decline, truncated = true))
    }

    @Test
    fun `a curve still climbing steeply at the cut IS censored`() {
        // Cut mid-rise: slope 0.077 over the last 15 min.
        val climbing = pts(30.0 to 1.17, 45.0 to 3.36, 60.0 to 4.52)
        assertFalse("still rising is a lower bound only", isPeakObserved(climbing, truncated = true))
    }
}
