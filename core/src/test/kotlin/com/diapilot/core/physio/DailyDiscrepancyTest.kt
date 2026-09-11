package com.diapilot.core.physio

import org.junit.Assert.*
import org.junit.Test

class DailyDiscrepancyTest {
    private val prior = DailyResponseStateV1(
        PosteriorV1(0.0, -20.0, 20.0, 0, 0, null),
        PosteriorV1(0.0, -15.0, 15.0, 0, 0, null), EvidenceStatus.INSUFFICIENT,
    )

    @Test fun mixedMealUpdatesEffectiveResponseButNotIdentifiedIsf() {
        val o = ResponseObservationV1("meal", 0, 60_000, 60, 2.0, 3.0, true, true, false)
        val state = DailyResponseEstimatorV1.update(prior, listOf(o), 60_000)
        assertEquals(50.0, state.effectiveResponsePercent.median, 1e-9)
        assertEquals(0.0, state.identifiedIsfPercent.median, 1e-9)
        assertEquals(EvidenceStatus.NOT_IDENTIFIABLE, state.identifiedIsfStatus)
    }

    @Test fun separableCorrectionsRecoverIsfDirection() {
        val rows = (1..4).map { i -> ResponseObservationV1("c$i", i * 86_400_000L, i * 86_400_000L + 180_000, 180, -2.0, -2.4, false, true, true) }
        val state = DailyResponseEstimatorV1.update(prior, rows, Long.MAX_VALUE)
        assertEquals(20.0, state.identifiedIsfPercent.median, 1e-9)
        assertEquals(EvidenceStatus.PRELIMINARY, state.identifiedIsfStatus)
    }

    @Test fun checkpointsAppendWithoutOverwritingAndConfoundingIsRetained() {
        val state = prior
        val a = DailyCheckpointV1("e:60", null, CheckpointKind.PROVISIONAL_60, 60, state, listOf("e"))
        val b = DailyCheckpointV1("e:120", "e:60", CheckpointKind.PROVISIONAL_120, 120, state, listOf("e"))
        assertEquals(2, DailyResponseEstimatorV1.appendCheckpoint(listOf(a), b).size)
        val d = decomposeDiscrepancyV1(50.0, listOf(
            CandidateContributionV1("activity", 8.0, 3.0, 12.0, true, "context"),
            CandidateContributionV1("sleep", 12.0, 4.0, 18.0, true, "context"),
            CandidateContributionV1("unsupported", 99.0, 0.0, 100.0, false),
        ))
        assertEquals(20.0, d.jointConfoundedPoints, 1e-9)
        assertEquals(30.0, d.unresolvedPoints, 1e-9)
        assertEquals(.4, d.explainedShare, 1e-9)
        val text = explanationCoverageTextV1(d)
        assertTrue(text.contains("+20 percentage points")); assertTrue(text.contains("40%")); assertTrue(text.contains("+30 points"))
    }

    @Test fun changeDetectorRetainsIntervalAndPracticalThreshold() {
        val current = PosteriorV1(50.0, 30.0, 75.0, 2, 1, 120)
        val baseline = PosteriorV1(0.0, -5.0, 5.0, 8, 7, 0)
        val change = detectChangeV1("effective response", current, baseline, 10.0, "yesterday")
        assertTrue(change.practicalThresholdCrossed); assertTrue(change.probabilityPositive > .9)
        assertEquals(25.0, change.changePercent.p10, 1e-9); assertEquals(80.0, change.changePercent.p90, 1e-9)
    }

    @Test fun gapWithoutObservationsShrinksStateAndWidensUncertaintyDeterministically() {
        val last=DailyResponseStateV1(PosteriorV1(40.0,30.0,50.0,5,3,0),PosteriorV1(20.0,15.0,25.0,6,3,0),EvidenceStatus.SUPPORTED)
        val a=DailyResponseEstimatorV1.carryForward(last,2*86_400_000L)
        val b=DailyResponseEstimatorV1.carryForward(last,2*86_400_000L)
        assertEquals(a,b)
        assertTrue(a.identifiedIsfPercent.median<20.0)
        assertTrue(a.identifiedIsfPercent.p90-a.identifiedIsfPercent.p10>10.0)
    }

    @Test fun singleIdentifyingDayCannotCreateAnIsfJump() {
        val rows=listOf(ResponseObservationV1("c",86_400_000,86_500_000,180,-1.0,-3.0,false,true,true))
        val state=DailyResponseEstimatorV1.update(prior,rows,86_500_000)
        assertTrue(kotlin.math.abs(state.identifiedIsfPercent.median)<=15.0+1e-9)
        assertNotEquals(EvidenceStatus.SUPPORTED,state.identifiedIsfStatus)
    }
}
