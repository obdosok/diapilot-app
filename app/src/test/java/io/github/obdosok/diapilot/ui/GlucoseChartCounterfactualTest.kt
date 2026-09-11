package io.github.obdosok.diapilot.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.diapilot.core.hybrid.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlucoseChartCounterfactualEnglishTest {
    @Test fun `counterfactual copy is conditional and never claims what would have happened`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val s = conditionalBolusScenarioLabel(2.5).resolve(context)
        assertTrue(s.contains("Conditional scenario"));assertTrue(s.contains("hidden processes"))
        assertFalse(s.contains("what would have happened"));assertFalse(s.contains("without the injection"))
    }
}

/** The exact Russian text this scenario label had while it was hardcoded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class GlucoseChartCounterfactualRussianTest {
    @Test fun `counterfactual copy keeps its Russian wording`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val s = conditionalBolusScenarioLabel(2.5).resolve(context)
        assertTrue(s.contains("Условный сценарий"));assertTrue(s.contains("скрытые процессы"))
        assertFalse(s.contains("как было бы"));assertFalse(s.contains("без укола"))
    }
}

class GlucoseChartCounterfactualTest {
    private val minute=60_000L
    private val kernel=listOf(
        KernelPoint(0.0,0.0,0.0,0.0,10),
        KernelPoint(60.0,-1.0,-1.2,-0.8,10),
        KernelPoint(120.0,-2.0,-2.2,-1.8,10),
    )

    @Test fun `removing bolus adds its negative learned effect back to observed glucose`() {
        val readings=listOf(
            GlucosePoint(-5*minute,6.0),GlucosePoint(0,6.0),
            GlucosePoint(60*minute,5.0),GlucosePoint(120*minute,4.0),
            GlucosePoint(180*minute,4.0),
        )
        val line=withoutBolusSeries(readings,BolusPoint(0,2.0),kernel)
        assertEquals(listOf(0L,60*minute,120*minute),line.map{it.tsMs})
        assertEquals(6.0,line[0].mmol,0.0)
        assertEquals(7.0,line[1].mmol,1e-9)
        assertEquals(8.0,line[2].mmol,1e-9)
    }

    @Test fun `air shot and missing kernel never invent a counterfactual`() {
        val readings=listOf(GlucosePoint(0,6.0),GlucosePoint(60*minute,5.0))
        assertTrue(withoutBolusSeries(readings,BolusPoint(0,2.0,"воздух"),kernel).isEmpty())
        assertTrue(withoutBolusSeries(readings,BolusPoint(0,2.0),emptyList()).isEmpty())
    }

    @Test fun `audit exposes lowering that even strong ISF boundary cannot explain`() {
        val readings=(0..120 step 5).map{m->GlucosePoint(m*minute,10.0-m/20.0)}
        val audit=counterfactualInsulinAudit(readings,BolusPoint(0,1.0),kernel,Triple(30.0,60.0,120.0))!!
        assertTrue(audit.observedFall>audit.explainedHigh)
        assertTrue(audit.materiallyUnderExplained)
        assertTrue(audit.unexplainedLowering>=0.8)
    }

    @Test fun `audit accepts fall covered by episode ISF interval`() {
        val readings=(0..120 step 5).map{m->GlucosePoint(m*minute,10.0-m/60.0)}
        val audit=counterfactualInsulinAudit(readings,BolusPoint(0,1.0),kernel,Triple(30.0,60.0,120.0))!!
        assertTrue(!audit.materiallyUnderExplained)
    }

    @Test fun `stated thirty minute onset is also the subtraction onset`() {
        val person=HybridPersonModel(1,"x","x",HybridRuntimeParams(180,5),
            HybridInsulinParams(2.0,1.8,2.2,10.0,55.0,120.0,180.0,.8,.2,2.5),
            HybridFoodParams(.165,defaultShape=HybridShape(10.0,55.0,180.0)),
            HybridActivityParams(0.0,120.0,0.0,120.0),HybridBasalParams(0.0,60.0,180.0,20.0,.1,1.0),
            HybridJointParams(HybridJointCoefficients(),6.0),HybridTrendParams(30,30.0,0.0,0.0,0.0),HybridUncertaintyParams(.2,.2,.2))
        val measured=person.copy(insulin=person.insulin.copy(
            onsetMin=30.0,peakMin=55.0,shortDurationMin=180.0,tailDurationMin=180.0,
            actionCdfKnots=listOf(HybridCdfKnot(0.0,0.0),HybridCdfKnot(30.0,0.0),HybridCdfKnot(35.0,.1),HybridCdfKnot(180.0,1.0)),
        ))
        val kernel=HybridForecastEngine(measured).insulinKernelPoints(2.5)
        val readings=(0..60 step 5).map{m->GlucosePoint(m*minute,10.0)}
        val line=withoutBolusSeries(readings,BolusPoint(0,2.5),kernel).associateBy{it.tsMs}
        assertEquals(10.0,line.getValue(25*minute).mmol,0.0)
        assertEquals(10.0,line.getValue(30*minute).mmol,0.0)
        assertTrue(line.getValue(35*minute).mmol>10.0)
    }
}
