package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicDishRecallTest {
    @Test
    fun `structured kinetics survive repeat dish recall without composition`() {
        val analysis =
            "KINETICS_V2: fast=.9;medium=.1;slow=0;form=LIQUID;confidence=.9;source=repeat;alcohol=false"
        val recalls = lastCompositions(
            listOf(Annotation(1L, "food", "atomic-repeat", estCarbs = 24.0, analysis = analysis, id = 7L)),
        )

        val recalled = recallComposition("atomic-repeat", recalls)
        assertNotNull(recalled)
        assertTrue(recalled!!.analysis.contains("KINETICS_V2:"))
        assertEquals(24.0, recalled.grams!!, 1e-9)
    }
}
