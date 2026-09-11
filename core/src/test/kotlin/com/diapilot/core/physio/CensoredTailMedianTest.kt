package com.diapilot.core.physio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-22: the tail corpus is right-censored, and dropping the censored doses
 * selects short tails. These pin the estimator that fixes it.
 *
 * The property that matters is not «Kaplan-Meier is implemented» but «a dose
 * still falling when its window closed RAISES the estimate instead of vanishing
 * from it», because that is the defect the whole finding rests on.
 */
class CensoredTailMedianTest {

    private fun completed(minute: Double) = SegmentLandmarksV1(
        tailEnd = LandmarkSampleV1(bolusTsMs = minute.toLong(), minute = minute, weight = 1.0, confoundedByFood = false),
    )

    private fun censored(minute: Double) = SegmentLandmarksV1(tailCensoredAtMin = minute)

    @Test
    fun `censored doses push the median LATER than the completed ones alone`() {
        // Five tails that finished early, five still falling when the window
        // closed. The naive median over completed cases is 110; the truth is
        // beyond it, and the estimator must say so.
        val completedOnly = listOf(90.0, 100.0, 110.0, 120.0, 130.0).map(::completed)
        val withCensored = completedOnly + listOf(200.0, 210.0, 220.0, 230.0, 240.0).map(::censored)
        val naive = 110.0
        val km = SegmentLandmarkReaderV1.censoredTailMedian(withCensored)
        assertTrue("censored sample must not be ignored", km != null)
        assertTrue(
            "median must move later than the completed-only median ($naive), was ${km!!.minute}",
            km.minute > naive,
        )
    }

    @Test
    fun `with nothing censored the estimator stands aside`() {
        // Degenerate case: no censoring means the plain weighted median is
        // correct, and routing it through here would change a shipped number
        // for no reason.
        assertNull(SegmentLandmarkReaderV1.censoredTailMedian(listOf(90.0, 110.0, 130.0).map(::completed)))
    }

    @Test
    fun `a majority censored gives NO median rather than the largest completed`() {
        // Survival never reaches one half, so the median lies beyond the data.
        // Reporting the last completed value there is exactly the short bias
        // this estimator exists to remove.
        val rows = listOf(completed(90.0)) + (1..9).map { censored(100.0 + it) }
        assertNull(SegmentLandmarkReaderV1.censoredTailMedian(rows))
    }

    @Test
    fun `the count reported includes the censored doses`() {
        val rows = listOf(90.0, 100.0, 110.0).map(::completed) + listOf(150.0, 160.0).map(::censored)
        val km = SegmentLandmarkReaderV1.censoredTailMedian(rows)
        assertEquals("n must count every dose that carried evidence", 5, km?.samples)
    }

    @Test
    fun `the survival curve crosses one half where it should`() {
        // Worked by hand so the test pins arithmetic rather than the current
        // output: two tails end at 100 (survival 1 - 2/5 = 0.60, still above
        // half), one at 180 with three still at risk (0.60 x 2/3 = 0.40), so
        // the median is 180. My first version of this test asserted 100 —
        // written from the intent «ties are consumed together» without doing
        // the arithmetic, and the estimator was right where the test was wrong.
        val rows = listOf(completed(100.0), completed(100.0), completed(180.0)) +
            listOf(censored(200.0), censored(210.0))
        val km = SegmentLandmarkReaderV1.censoredTailMedian(rows)
        assertEquals(180.0, km?.minute ?: -1.0, 1e-9)
    }
}
