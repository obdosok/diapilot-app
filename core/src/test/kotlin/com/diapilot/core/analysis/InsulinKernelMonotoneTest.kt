package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kernel's tail rebound is the model claiming insulin hands glucose back in
 * hour three. These pin that it cannot, and — via [shippedKernel] — that the
 * reshape holds the AMPLITUDE fixed. v7 IS a reshaping; what it must not do is
 * change the total per-unit drop, and that is what the ordering guarantees.
 */
class InsulinKernelMonotoneTest {

    private fun k(vararg medians: Double) = medians.mapIndexed { i, m ->
        KernelPoint(tauMin = i * 5.0, median = m, q1 = m - 0.1, q3 = m + 0.1, n = 10)
    }

    @Test
    fun theTailReboundIsFlattenedAway() {
        // The real shape: deepens to -1.70, then gives 0.36 back.
        val out = monotoneKernel(k(0.0, -0.8, -1.70, -1.52, -1.34))
        assertEquals(listOf(0.0, -0.8, -1.70, -1.70, -1.70), out.map { it.median })
    }

    @Test
    fun flatteningAloneWouldRaiseTheAmplitude() {
        // Flattening on its own makes the deepest bin the new plateau — this is
        // the REJECTED variant, kept here because the ordering test below is
        // only meaningful next to it.
        val out = monotoneKernel(k(0.0, -1.70, -1.34))
        assertEquals(-1.70, out.last().median, 1e-9)
    }

    @Test
    fun theShippedKernelHoldsTheAmplitudeAtTheTarget() {
        // THE ORDERING INVARIANT, and the one thing TwinCache's call site had no
        // coverage for: whatever the rebound did, the plateau the forecast
        // integrates equals the ISF target exactly. Move `monotoneKernel` after
        // the rescale and this fails — before, that regression was one line and
        // a green suite away from shipping +18% insulin to the hypo alert.
        val out = shippedKernel(k(0.07, -0.8, -1.70, -1.52, -1.34), 1.4829)
        assertEquals(-1.4829, out.last().median, 1e-9)
        out.zipWithNext { a, b -> assertTrue(b.median <= a.median + 1e-12) }
    }

    @Test
    fun theShippedKernelOnlyRedistributesTheDropOverTime() {
        // Same total, different distribution: relative to a plain rescale the
        // first hour gets SHALLOWER and the tail DEEPER. If this ever flips,
        // "amplitude unchanged, mass moves to the tail" has stopped being true.
        val learned = k(0.07, -0.8, -1.70, -1.52, -1.34)
        val target = 1.34
        val plain = learned.map { it.copy(median = it.median * (target / 1.34)) }
        val shipped = shippedKernel(learned, target)
        assertEquals(-target, shipped.last().median, 1e-9)
        assertTrue("head must be shallower", shipped[1].median > plain[1].median)
        assertTrue("tail must be deeper", shipped.last().median < plain[4].median + 1e-12)
    }

    @Test
    fun theBandAlwaysContainsItsOwnLine() {
        // Lowering the median under an untouched q1 would draw the kernel chart
        // with the curve outside its own band.
        monotoneKernel(k(0.0, -1.70, -1.34)).forEach {
            assertTrue("q1 ${it.q1} <= median ${it.median}", it.q1 <= it.median + 1e-12)
            assertTrue("q3 ${it.q3} >= median ${it.median}", it.q3 >= it.median - 1e-12)
        }
    }

    @Test
    fun anAlreadyMonotoneCurveIsUntouched() {
        val input = k(0.0, -0.5, -1.2, -1.6, -1.8)
        assertEquals(input.map { it.median }, monotoneKernel(input).map { it.median })
    }

    @Test
    fun spentNeverDecreasesAtAnyPoint() {
        val out = monotoneKernel(k(0.0, -0.4, -1.9, -0.2, -2.4, -1.0, -2.4))
        out.zipWithNext { a, b -> assertTrue("$a -> $b", b.median <= a.median + 1e-12) }
    }

    @Test
    fun theQuartilesAreNotMonotonisedOnlyWidenedToHoldTheMedian() {
        // Only the median carries the action; q1/q3 feed the spread, and
        // MONOTONISING them would reshape it with nothing measuring it. The one
        // thing that may happen is widening, and only where the lowered median
        // would otherwise escape its own band (see theBandAlwaysContainsItsOwnLine).
        val input = k(0.0, -1.70, -1.34)
        val out = monotoneKernel(input)
        out.zip(input).forEach { (o, i) ->
            assertTrue("q1 may only widen", o.q1 <= i.q1 + 1e-12)
            assertTrue("q3 may only widen", o.q3 >= i.q3 - 1e-12)
        }
        // Where the median did not move, nothing moves at all.
        assertEquals(input[1].q1, out[1].q1, 1e-12)
        assertEquals(input[1].q3, out[1].q3, 1e-12)
        assertEquals(input.map { it.tauMin }, out.map { it.tauMin })
        assertEquals(input.map { it.n }, out.map { it.n })
    }

    @Test
    fun aPositiveHeadIsClampedToZero() {
        // The REAL kernel opens positive — six bins up to +0.076 mmol/U at
        // τ=0..25 — and a fresh bolus cannot raise glucose. The seed of the
        // running minimum is what handles this, and every other fixture here
        // starts at 0.0, so without this case seeding it with MAX_VALUE (i.e.
        // no clamp at all) passes the whole suite. Found by mutation.
        val out = monotoneKernel(k(0.076, 0.055, 0.023, -0.4, -1.2))
        assertEquals(listOf(0.0, 0.0, 0.0, -0.4, -1.2), out.map { it.median })
    }

    @Test
    fun aCurveThatNeverGoesNegativeIsFlattenedToZeroThroughout() {
        val out = monotoneKernel(k(0.05, 0.09, 0.02))
        assertTrue(out.all { it.median == 0.0 })
    }

    @Test
    fun scalingAndFlatteningCommute() {
        // The invariant the ORDERING argument rests on: monotone(c·x) == c·monotone(x).
        // If this ever stopped holding, "before vs after the ISF rescale" would
        // mean something different from what TwinCache's comment claims.
        val input = k(0.07, -0.8, -1.70, -1.52, -1.34)
        val c = 0.844
        val scaledThenFlat = monotoneKernel(input.map { it.copy(median = it.median * c) })
        val flatThenScaled = monotoneKernel(input).map { it.copy(median = it.median * c) }
        scaledThenFlat.zip(flatThenScaled).forEach { (a, b) ->
            assertEquals(b.median, a.median, 1e-12)
        }
    }

    @Test
    fun anEmptyKernelStaysEmpty() {
        assertTrue(monotoneKernel(emptyList()).isEmpty())
    }
}
