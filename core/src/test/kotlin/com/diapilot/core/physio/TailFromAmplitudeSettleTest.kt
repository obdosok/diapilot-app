package com.diapilot.core.physio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M-61: the settle rule had a hole — the step from the candidate horizon to the
 * next one was checked by neither clause, so a curve that pauses once and then
 * climbs steeply could be called settled at the pause.
 *
 * These pin the rule itself rather than the user's numbers, because the
 * user's numbers did not move when the hole was closed and so cannot detect
 * a regression.
 */
class TailFromAmplitudeSettleTest {

    private val horizons = listOf(60, 120, 180, 240, 300, 360, 420)

    /** Three identical doses carrying one curve, so the median IS that curve. */
    private fun doses(vararg fall: Double) = List(3) { i ->
        InsulinTailFromAmplitudeV1.CleanDose(
            tsMs = i.toLong(),
            units = 1.0,
            fallByHorizon = horizons.zip(fall.toList()).toMap(),
        )
    }

    @Test
    fun `a pause, then ONE big jump, then flat — the pause is not the end`() {
        // This is the exact shape the hole let through, and it took two tries to
        // write: the skipped step is the one FROM the candidate horizon, so the
        // curve must jump there and be flat everywhere after. A curve that keeps
        // climbing is caught by either rule and proves nothing.
        //
        // 180 -> 240 gains 0.02 (looks settled); 240 -> 300 gains 1.98 (the
        // skipped step); 300 onward is flat. The old rule filtered on
        // `> 240` and so never saw the jump, reporting the end of action at 180
        // while more than a third of the total fall was still to come.
        val r = InsulinTailFromAmplitudeV1.read(
            doses(1.0, 2.0, 3.0, 3.02, 5.0, 5.05, 5.1), horizons,
        )
        assertEquals("the pause at 180 must not be called the end", 300.0, r.endMin!!, 1e-9)
    }

    @Test
    fun `a real plateau is still found`() {
        // Mutation guard for the fix: if closing the hole had made the rule
        // unsatisfiable, this would refuse too and the object would be inert.
        val r = InsulinTailFromAmplitudeV1.read(
            doses(1.0, 2.0, 2.9, 3.0, 3.01, 3.02, 3.03), horizons,
        )
        assertNotNull("a curve that genuinely flattens must still yield an end", r.endMin)
        // 180, not 240: the reported end is the LEFT edge of the first step that
        // stops growing, i.e. the last horizon at which the fall was still
        // accumulating. Written as 240 first, from the intent rather than the
        // arithmetic — the same mistake the censored-median test made.
        assertEquals(180.0, r.endMin!!, 1e-9)
    }

    @Test
    fun `the plateau level is the ISF, read across the whole plateau`() {
        val r = InsulinTailFromAmplitudeV1.read(
            doses(1.0, 2.0, 2.9, 3.0, 3.01, 3.02, 3.03), horizons,
        )
        // The median of the plateau — 2.90, 3.00, 3.01, 3.02, 3.03 from 180
        // inclusive — not the value at the settling horizon alone, which would
        // throw away four later readings of the same quantity.
        assertEquals(3.01, r.isfAtPlateau!!, 1e-9)
    }

    @Test
    fun `a curve that climbs to the end refuses instead of naming the last horizon`() {
        val r = InsulinTailFromAmplitudeV1.read(
            doses(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0), horizons,
        )
        assertEquals(InsulinTailFromAmplitudeV1.NEVER_SETTLES, r.refusal)
        assertNull(r.endMin)
    }

    @Test
    fun `two doses refuse rather than reporting a median of two`() {
        val two = doses(1.0, 2.0, 2.9, 3.0, 3.01, 3.02, 3.03).take(2)
        assertEquals(
            InsulinTailFromAmplitudeV1.NOT_ENOUGH_DOSES,
            InsulinTailFromAmplitudeV1.read(two, horizons).refusal,
        )
    }
}
