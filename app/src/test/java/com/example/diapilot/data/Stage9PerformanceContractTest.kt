package com.example.diapilot.data

import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Stage9PerformanceContractTest {
    @Test fun `time index keeps inclusive boundaries and chronological order`() {
        val rows=listOf(GlucosePoint(30,3.0),GlucosePoint(10,1.0),GlucosePoint(20,2.0),GlucosePoint(40,4.0))
        val index=Stage9EpisodeRuntime.TimeRangeIndex(rows){it.tsMs}
        assertEquals(listOf(10L,20L,30L),index.between(10,30).map{it.tsMs})
        assertEquals(emptyList<GlucosePoint>(),index.between(31,39))
    }

    @Test fun `manual continuation budget is one shot`() {
        FoodCalculationRegistry.resetEpisodeStateForTest()
        assertEquals(2_000L,FoodCalculationRegistry.takeContinuationBudget())
        FoodCalculationRegistry.requestContinuationBudget(8_000L)
        assertEquals(8_000L,FoodCalculationRegistry.takeContinuationBudget())
        assertEquals(2_000L,FoodCalculationRegistry.takeContinuationBudget())
    }

    @Test fun `clusters without enough facts still advance continuation cursor`() {
        val now=1_800_000_000_000L
        val notes=(0 until 5).map{i->
            Annotation(id=(i+1).toLong(),tsMs=now-i*7*3_600_000L,kind="food",content="unknown $i")
        }
        val result=Stage9EpisodeRuntime.buildDetailed(
            notes=notes,readings=emptyList(),boluses=emptyList(),episodes=emptyList(),
            foodEraStartMs=now-60L*24*3_600_000L,nowMs=now,budgetMs=10_000L,
        )
        assertEquals(5,result.processedClusters)
        assertEquals(5,result.nextOffset)
        assertTrue(result.complete)
        assertTrue(result.receipts.isEmpty())
    }
}
