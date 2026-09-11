package com.diapilot.core.physio

import com.diapilot.core.analysis.ParametricKernel
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.*
import org.junit.Test

class JointMealAttributionV1Test {
    private val min=60_000L
    private fun note(t:Long,name:String,g:Double,alcohol:Boolean=false)=Annotation(t,"food",name,id=t,estCarbs=g,carbsSource="manual",carbsKnownAtMs=t,
        analysis=if(alcohol)"KINETICS_V2: fast=0.3;medium=0.6;slow=0.1;form=LIQUID;confidence=0.8;source=test;alcohol=true" else null,
        analysisKnownAtMs=if(alcohol)t else null)
    private fun model()=EpisodeKernelModelV1(ParametricKernel(2.5,240.0,65.0,200,12,10.0,.2,false,false,90.0),PosteriorV1(2.5,2.0,3.0,12,8,0))
    private fun readings(end:Int, f:(Int)->Double)= (0..end step 5).map{GlucosePoint(it*min,f(it))}

    @Test fun `beer grouping is 120 inclusive 121 split and pairwise chaining is bounded`() {
        assertEquals(1,groupDistributedIntakesV1(listOf(note(0,"arbitrary a",10.0,true),note(120*min,"arbitrary b",10.0,true))).size)
        assertEquals(2,groupDistributedIntakesV1(listOf(note(0,"arbitrary a",10.0,true),note(121*min,"arbitrary b",10.0,true))).size)
        val chained=groupDistributedIntakesV1(listOf(note(0,"a",10.0,true),note(110*min,"b",10.0,true),note(220*min,"c",10.0,true)))
        assertEquals(2,chained.size)
        assertTrue(chained.all{it.durationMin<=DISTRIBUTED_INTAKE_MAX_SPAN_MIN_V1})
    }

    @Test fun `two beers preserve per-note onsets and delayed amplitude`() {
        val ns=listOf(note(0,"anything",10.0,true),note(110*min,"another",10.0,true))
        val out=jointMealAttributionV1(ns,readings(300){t->5.5+when{t<30->0.0;t<110->1.2;t<150->1.4;else->2.4}},emptyList(),PosteriorV1(.165,.137,.197,20,10,0),300*min,{b->model().kernelForEpisode(b.tsMs,emptyList(),300*min,b.units)},appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        assertEquals(1,out.allocations.size)
        assertEquals(listOf(0L,110*min),out.allocations.single().perNoteOnsetsMs)
        assertTrue(out.allocations.single().bestAllocationMmol>0)
    }

    @Test fun `coldnik plus 60 ice cream remains jointly allocated not merged`() {
        val ns=listOf(note(0,"coldnik with potatoes",35.0),note(60*min,"ice cream",20.0))
        val out=jointMealAttributionV1(ns,readings(300){t->5.5+when{t<45->t/45.0;t<100->1.0+(t-45)/35.0;else->2.6}},emptyList(),PosteriorV1(.165,.137,.197,20,10,0),300*min,{b->model().kernelForEpisode(b.tsMs,emptyList(),300*min,b.units)},appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        assertEquals(2,out.allocations.size)
        assertTrue(out.allocations.all{it.bestAllocationMmol>=0&&it.allocationLowMmol<=it.bestAllocationMmol&&it.bestAllocationMmol<=it.allocationHighMmol})
        assertTrue(out.allocations.any{it.status==AttributionResolutionV1.UNRESOLVED})
    }

    @Test fun `oversubtraction cannot create negative food and unlogged food has residual`() {
        val ns=listOf(note(0,"meal",10.0))
        val bolus=listOf(BolusPoint(0,8.0))
        val out=jointMealAttributionV1(ns,readings(240){t->5.5+t/90.0},bolus,PosteriorV1(.165,.137,.197,20,10,0),240*min,{b->model().kernelForEpisode(b.tsMs,emptyList(),240*min,b.units)},appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        assertTrue(out.allocations.single().bestAllocationMmol>=0)
        assertTrue(out.unloggedFoodResidualMmol.median>=0)
    }

    @Test fun `censored window reports interval instead of point fantasy`() {
        val out=jointMealAttributionV1(listOf(note(0,"slow meal",40.0)),readings(90){5.5+it/60.0},emptyList(),PosteriorV1(.165,.137,.197,20,10,0),90*min,{b->model().kernelForEpisode(b.tsMs,emptyList(),90*min,b.units)},appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        assertEquals(AttributionResolutionV1.CENSORED,out.allocations.single().status)
        assertTrue(out.allocations.single().allocationHighMmol>out.allocations.single().allocationLowMmol)
    }

    @Test fun `null grams knownAt is excluded rather than made retroactively known`() {
        val unknown=Annotation(0,"food","meal",id=1,estCarbs=20.0,carbsSource="manual",carbsKnownAtMs=null)
        assertNull(jointMealAttributionV1(listOf(unknown),readings(240){5.5},emptyList(),PosteriorV1(.165,.117,.217,0,0,0),240*min,{b->model().kernelForEpisode(b.tsMs,emptyList(),240*min,b.units)},appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED))
    }

    @Test fun `flat CGM exposes windows but establishes no physiological phase`() {
        val out=jointMealAttributionV1(listOf(note(0,"meal",20.0)),readings(300){5.5},emptyList(),PosteriorV1(.165,.117,.217,0,0,0),300*min,{b->model().kernelForEpisode(b.tsMs,emptyList(),300*min,b.units)},appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        assertTrue(out.allocations.single().phaseWindowsAvailable.isNotEmpty())
        assertTrue(out.allocations.single().observedPhases.isEmpty())
        assertTrue(out.allocations.single().caveats.any{it.contains("phase not established")})
    }

    @Test fun `late second meal uses its own coverage and stays censored`() {
        val out=jointMealAttributionV1(listOf(note(0,"first",20.0),note(350*min,"late",20.0)),readings(360){5.5+it/100.0},emptyList(),PosteriorV1(.165,.117,.217,0,0,0),360*min,{b->model().kernelForEpisode(b.tsMs,emptyList(),360*min,b.units)},appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        val late=out.allocations.maxBy{it.startMs}
        assertTrue(late.coverageMin<=10.0)
        assertEquals(AttributionResolutionV1.CENSORED,late.status)
    }

    @Test fun `joint hash covers glucose bolus and config revisions`() {
        val ns=listOf(note(0,"meal",20.0));val cs=PosteriorV1(.165,.117,.217,0,0,0)
        fun run(rr:List<GlucosePoint>,bb:List<BolusPoint> = emptyList(),cfg:JointAttributionConfigV1=JointAttributionConfigV1())=
            jointMealAttributionV1(ns,rr,bb,cs,240*min,{b->model().kernelForEpisode(b.tsMs,emptyList(),b.tsMs,b.units)},cfg,appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!.cacheIdentity
        val base=run(readings(240){5.5+it/100.0})
        assertNotEquals(base,run(readings(240){5.5+it/90.0}))
        assertNotEquals(base,run(readings(240){5.5+it/100.0},listOf(BolusPoint(0,1.0))))
        assertNotEquals(base,run(readings(240){5.5+it/100.0},cfg=JointAttributionConfigV1(anchorPenalty=6.0)))
    }

    @Test fun `latent negative process remains signed and is not forced into food or ISF`() {
        val ns=listOf(note(0,"meal",30.0));val bolus=listOf(BolusPoint(0,2.5))
        val out=jointMealAttributionV1(ns,readings(240){t->5.5-if(t<30)0.0 else (t-30).coerceAtMost(120)/30.0},bolus,PosteriorV1(.165,.117,.217,0,0,0),240*min,{b->model().kernelForEpisode(b.tsMs,emptyList(),240*min,b.units)},appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        assertTrue(out.sharedResidualCurve.any{it.median<0})
        assertTrue(out.latentSignedProcessMmol.median<0)
        assertFalse(out.allocations.single().causalLearningEligible)
    }
}
