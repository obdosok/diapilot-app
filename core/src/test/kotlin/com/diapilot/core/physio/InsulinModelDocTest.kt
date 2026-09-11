package com.diapilot.core.physio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `docs/insulin-model.md` must keep telling the truth.
 *
 * The document exists so a neighbouring session can reproduce this database's
 * numbers from a short prompt instead of re-opening two days of work. A spec
 * that quietly drifts from the code is worse than none: it reads like
 * verification while confirming nothing, and the reader has no way to tell.
 *
 * So every constant the document states is asserted against the constant itself.
 * Change a threshold and this fails, naming the line to update.
 *
 * Only CONSTANTS are pinned here. The measured results in §2.6/§3.6/§4/§5 (the
 * fitted landmarks, ISF and lift) belong to one pulled database and must NOT be
 * frozen in a test — they move when new corrections arrive, and that is the point.
 */
class InsulinModelDocTest {
    private val doc: String by lazy {
        val here = File("docs/insulin-model.md")
        val fromRoot = File("../docs/insulin-model.md")
        (if (here.exists()) here else fromRoot).readText()
    }

    private fun states(fragment: String) = assertTrue(
        "docs/insulin-model.md must state «$fragment» — the code says so",
        doc.contains(fragment),
    )

    @Test fun `the shape section states the code's horizons`() {
        states("`ONSET_HORIZON_MIN` = ${SegmentLandmarkReaderV1.ONSET_HORIZON_MIN.toInt()}")
        states("`PEAK_HORIZON_MIN` = ${SegmentLandmarkReaderV1.PEAK_HORIZON_MIN.toInt()}")
        states("`SLOWDOWN_HORIZON_MIN` = ${SegmentLandmarkReaderV1.SLOWDOWN_HORIZON_MIN.toInt()}")
        states("`TAIL_HORIZON_MIN` = ${SegmentLandmarkReaderV1.TAIL_HORIZON_MIN.toInt()}")
        states("`MIN_RISE_SPAN_MIN` = ${SegmentLandmarkReaderV1.MIN_RISE_SPAN_MIN.toInt()}")
        states("`MIN_PHASE_SPAN_MIN` = ${SegmentLandmarkReaderV1.MIN_PHASE_SPAN_MIN.toInt()}")
        states("`EDGE_MARGIN_MIN` = ${SegmentLandmarkReaderV1.EDGE_MARGIN_MIN.toInt()}")
    }

    @Test fun `the conditioning section states the code's food window and arm rule`() {
        states("`FOOD_ACTION_DELAY_MIN` = ${SegmentLandmarkReaderV1.FOOD_ACTION_DELAY_MIN.toInt()}")
        states("`FOOD_ABSORPTION_MIN` = ${SegmentLandmarkReaderV1.FOOD_ABSORPTION_MIN.toInt()}")
        states("`RISING_BACKGROUND_MMOL_PER_H` = ${SegmentLandmarkReaderV1.RISING_BACKGROUND_MMOL_PER_H}")
        states("`RISING_BACKGROUND_WEIGHT` = ${SegmentLandmarkReaderV1.RISING_BACKGROUND_WEIGHT}")
        states("`MIN_CONDITIONED_SAMPLES` = ${ConditionedLandmarkV1.MIN_CONDITIONED_SAMPLES}")
    }

    // `the convention width matches the reader` USED TO STAND HERE.
    //
    // It required the document to print the single-dose band formula
    // (CONVENTION_ISF_SHARE_LOW/HIGH from SensorCorrectionReaderV1). The band
    // no longer exists: it was computed by `IsfEvidenceRuntime`, torn down
    // along with the review card, and §3.5 of the document disappeared along
    // with the code — correctly.
    //
    // The reader itself is ALIVE and stays in production (`SegmentLandmarksV1`
    // reads its trace and thresholds), but these two constants are now only
    // read by the bench. Pinning a constant in the document that production
    // does not apply is exactly the green test on a dead path that the audit
    // was looking for.

    @Test fun `the bounds section states the population range that coerces the tail`() {
        val b = PhysioBoundsV1()
        states("`insulinTailMinRange` = ${b.insulinTailMinRange.start.toInt()}…${b.insulinTailMinRange.endInclusive.toInt()}")
    }

    /**
     * And the document must keep the reproduction contract itself: the short
     * prompt and the checklist are the whole reason it exists.
     */
    @Test fun `the reproduction prompt survives`() {
        states("## 7. Prompt for a fresh session")
        // Tags of LIVE logs, not of every tag that ever existed. `ClosedEpisode`
        // stood here before the pipeline was torn down, and after that the
        // check became falsely green: the word still appeared in the text — in
        // the tombstone «`ClosedEpisodeRuntime` removed». The test claimed to
        // assert the presence of a TAG, but actually confirmed the presence of
        // a STRING. Different things, and the second is worth nothing.
        listOf("InsulinProfile", "PhysioTuning", "AdaptiveIsf").forEach {
            assertTrue(
                "the checklist must name the live logcat tag «$it»",
                doc.contains("`$it`"),
            )
        }
        assertTrue(
            "and must warn that a mismatch is a stand problem before it is a finding",
            doc.contains("discipline #7"),
        )
    }
}
