package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridCdfKnot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-79: the user's two invariants for the insulin curve.
 *
 *   "timings must not affect the ISF (it equals the area under the action curve)"
 *   "the tail is roughly 20 percent, after the main action"
 *
 * The first was true at infinity and false inside any observation window,
 * because the curve was normalised by its own total: a longer end of action
 * grew the denominator and shrank every earlier fraction, so lengthening the
 * tail silently weakened the peak. These pin the repair.
 */
class TailShareInvariantTest {

    private fun cdfAt(knots: List<HybridCdfKnot>, minute: Double): Double {
        val below = knots.lastOrNull { it.minute <= minute } ?: return 0.0
        val above = knots.firstOrNull { it.minute >= minute } ?: return 1.0
        if (above.minute == below.minute) return below.fraction
        val w = (minute - below.minute) / (above.minute - below.minute)
        return below.fraction + w * (above.fraction - below.fraction)
    }

    private fun curve(tail: Double, share: Double?) = InsulinShapeV1.synthesize(
        InsulinShapeLandmarksV1(onsetMin = 11.0, peakMin = 55.0, plateauEndMin = 73.0, tailMin = tail),
        tailShare = share,
    )!!

    @Test
    fun `with the share pinned the active phase always delivers the same`() {
        // THE point of the change: the end of action may move by four hours and
        // the dose delivered by the end of the active phase must not budge.
        val tails = listOf(120.0, 165.0, 240.0, 300.0, 420.0)
        val atPlateau = tails.map { cdfAt(curve(it, InsulinShapeV1.TAIL_SHARE), 73.0) }
        atPlateau.forEach {
            assertEquals("active phase must carry 1 - TAIL_SHARE at every tail", 0.80, it, 0.02)
        }
        assertTrue(
            "the spread across tails must be negligible, was ${atPlateau.max() - atPlateau.min()}",
            atPlateau.max() - atPlateau.min() < 0.02,
        )
    }

    @Test
    fun `the shipped construction does NOT hold it — this is the defect`() {
        // Mutation guard in reverse: if this ever starts passing, the two paths
        // have converged and the parameter is pointless. Measured on the bench:
        // 0.77 -> 0.49 at ninety minutes between tail 165 and 300.
        val a = cdfAt(curve(165.0, null), 73.0)
        val b = cdfAt(curve(300.0, null), 73.0)
        assertTrue(
            "shipped path is expected to sag with a longer tail: $a vs $b",
            a - b > 0.10,
        )
    }

    @Test
    fun `a longer tail still stretches the last fifth`() {
        // The share is pinned, not the shape: the remaining 20% must arrive
        // LATER when the tail is longer, or nothing was gained.
        val short = curve(165.0, InsulinShapeV1.TAIL_SHARE)
        val long = curve(300.0, InsulinShapeV1.TAIL_SHARE)
        val m = 150.0
        assertTrue(
            "at $m min the long tail must have delivered less",
            cdfAt(long, m) < cdfAt(short, m) - 0.02,
        )
    }

    @Test
    fun `the curve still reaches one and never goes backwards`() {
        listOf(120.0, 240.0, 420.0).forEach { tail ->
            val k = curve(tail, InsulinShapeV1.TAIL_SHARE)
            assertEquals(1.0, k.last().fraction, 1e-9)
            assertTrue(k.zipWithNext().all { (a, b) -> b.fraction >= a.fraction })
        }
    }
}
