package com.diapilot.core.analysis

import org.junit.Assert.*
import org.junit.Test

class FoodKineticsV2Test {
    @Test fun `component speed mixture is carb weighted and name independent`() {
        val analysis="""
            СОСТАВ: произвольный альфа = 30 угл
            СОСТАВ: произвольный бета = 10 угл
            МЕТА: произвольный альфа [conf 0.80 · fast]; произвольный бета [conf 0.60 · slow]
        """.trimIndent()
        val k=parseFoodKineticsV2(analysis)
        assertEquals(.75,k.fastFraction,1e-9)
        assertEquals(0.0,k.mediumFraction,1e-9)
        assertEquals(.25,k.slowFraction,1e-9)
        assertEquals(.75,k.confidence,1e-9)
        assertEquals("component-tool-v2",k.provenance)
    }

    /**
     * LEAN ON PURPOSE. This test measures that what the writer serialised is
     * what the reader returns; with 25 g of fat it would instead be measuring
     * [capFastForFat], which caps a fatty dish's fast fraction at 0.2 and would
     * make the round trip fail for a reason that has nothing to do with
     * serialisation. The guard has its own tests in FatNotFastGuardTest.
     */
    @Test fun `structured output round trips form fiber and fractions`() {
        val a=FoodAnalysisOut(
            dishName="arbitrary",physicalForm=FoodPhysicalFormV2.MIXED,totalFiberG=8.0,
            totalProteinG=20.0,totalFatG=10.0,
            components=listOf(
                FoodComponentOut("alpha",30.0,speed="FAST",confidence=.8),
                FoodComponentOut("beta",10.0,speed="SLOW",confidence=.6),
            ),
        )
        val text=serializeFoodAnalysis(a);val k=parseFoodKineticsV2(text)
        assertEquals(FoodPhysicalFormV2.MIXED,k.physicalForm)
        assertEquals(8.0,k.fiberG!!,1e-9)
        assertEquals(.75,k.fastFraction,1e-4)
        assertEquals(.25,k.slowFraction,1e-4)
        assertTrue(text.contains(FOOD_KINETICS_PREFIX_V2))
    }

    @Test fun `title words cannot change neutral fallback`() {
        val a=parseFoodKineticsV2("НАЗВАНИЕ: сок кусок мороженое\nБЕЛКИ: 10 г\nЖИРЫ: 10 г")
        val b=parseFoodKineticsV2("НАЗВАНИЕ: совершенно другое\nБЕЛКИ: 10 г\nЖИРЫ: 10 г")
        assertEquals(a,b)
    }
}
