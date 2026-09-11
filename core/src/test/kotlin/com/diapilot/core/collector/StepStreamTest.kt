package com.diapilot.core.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STEPS ARE READ FROM ONE SOURCE, OR THEY GET SUMMED.
 *
 * The defect was caught by the user, comparing against a fitness watch: a much
 * higher daily step count in the database than on the watch. Health Connect
 * hands back records from every app, their bucket boundaries differ,
 * `start_ms` is the primary key, so there are no duplicates and nothing is
 * overwritten: overlapping pairs of intervals were simply summed together.
 *
 * This pins exactly the thing that is easy to break again.
 */
class StepStreamTest {

    private val t0 = 1_700_000_000_000L
    private fun min(m: Long) = t0 + m * 60_000

    private fun b(fromMin: Long, lenMin: Long, count: Long, src: String? = null) =
        StepBucket(min(fromMin), min(fromMin + lenMin), count, src)

    @Test
    fun `two sources over the same walk do not add up`() {
        val zepp = (0..5).map { b(it * 10L, 10, 500, "com.huami.watch") }
        val phone = (0..59).map { b(it.toLong(), 1, 50, "com.miui.health") }
        val chosen = StepStream.choose(zepp + phone)
        val total = chosen.sumOf { it.count }
        assertTrue(
            "a mix was chosen: $total steps against 3000 and 3000 from the two sources",
            total == 3000L || total == 3000L,
        )
        assertEquals("exactly ONE source must remain", 1, chosen.map { it.source }.distinct().size)
    }

    /**
     * WINS BY NUMBER OF INTERVALS, NOT BY THE SUM OF STEPS.
     *
     * Choosing by sum would just reward a finer slicing, and slicing is a
     * property of the app, not the body. Here the "phone" has twice the
     * steps and still loses, because it has fewer intervals.
     */
    @Test
    fun `the stream with more intervals wins, not the one with more steps`() {
        val many = (0..19).map { b(it.toLong(), 1, 10, "A") }
        val few = listOf(b(0, 20, 10_000, "B"))
        assertEquals("A", StepStream.choose(many + few).first().source)
    }

    /**
     * Legacy rows with no source split by DURATION — this is recovery by an
     * indirect signal, and it has to work because a large part of the history
     * was recorded with no source at all.
     */
    @Test
    fun `legacy rows without a source split by bucket length`() {
        val long = (0..5).map { b(it * 10L, 10, 500) }
        val short = (0..59).map { b(it.toLong(), 1, 50) }
        val chosen = StepStream.choose(long + short)
        assertEquals("short buckets are more numerous — they win", 60, chosen.size)
        assertTrue(chosen.all { (it.endMs - it.startMs) / 60_000 < 6 })
    }

    @Test
    fun `a single source passes through untouched`() {
        val only = (0..5).map { b(it * 10L, 10, 500, "A") }
        assertEquals(only.size, StepStream.choose(only).size)
        assertEquals(3000L, StepStream.choose(only).sumOf { it.count })
        assertTrue("nothing to reject", StepStream.rejected(only).isEmpty())
    }

    /** What was dropped is countable: a silent hole is indistinguishable from no walking at all. */
    @Test
    fun `what was dropped is countable`() {
        val a = (0..9).map { b(it.toLong(), 1, 10, "A") }
        val bb = listOf(b(0, 20, 100, "B"))
        assertEquals(mapOf("B" to 1), StepStream.rejected(a + bb))
    }

    @Test
    fun `an empty input is empty output`() {
        assertTrue(StepStream.choose(emptyList()).isEmpty())
        assertTrue(StepStream.rejected(emptyList()).isEmpty())
    }
}
