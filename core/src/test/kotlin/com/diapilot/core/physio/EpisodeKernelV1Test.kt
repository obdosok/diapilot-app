package com.diapilot.core.physio

import com.diapilot.core.analysis.ParametricKernel
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.*
import org.junit.Test

class EpisodeKernelV1Test {
    private val day=86_400_000L
    private fun base()=ParametricKernel(2.5,240.0,65.0,200,12,10.0,.2,false,false,90.0)
    private fun posterior()=PosteriorV1(2.5,2.0,3.0,12,8,100L)

    @Test fun `future checkpoint and future-known context cannot change historical kernel`() {
        val model=EpisodeKernelModelV1(base(),posterior(),identifiedIsf=listOf(
            IdentifiedIsfEvidenceV1(day,day+1,3.0,1.0,1,"past correction"),
            IdentifiedIsfEvidenceV1(3*day,3*day+1,6.0,1.0,2,"future correction"),
        ),promoted=listOf(PromotedKernelEffectV1(KernelFactorKindV1.TOD,KernelAxisV1.TIMING,1.2,1.1,1.3,day,1,"chronological fold")))
        val known=EpisodeContextFactV1(KernelFactorKindV1.TOD,20.0,"hour",2*day,2*day,1,"clock")
        val futureKnown=known.copy(knownAtMs=2*day+1000,revision=2)
        val a=model.kernelForEpisode(2*day,listOf(known),2*day)
        val b=model.kernelForEpisode(2*day,listOf(futureKnown),2*day)
        assertTrue(a.peakMin.median>b.peakMin.median)
        assertTrue(a.isf.median<3.2) // future 6.0 observation is excluded and past is shrunk.
        assertEquals(a.provenanceHash,model.kernelForEpisode(2*day,listOf(known),2*day).provenanceHash)
    }

    @Test fun `unknown context widens interval without moving median`() {
        val model=EpisodeKernelModelV1(base(),posterior())
        val a=model.kernelForEpisode(day,emptyList(),day)
        val unsupported=EpisodeContextFactV1(KernelFactorKindV1.ACTIVITY,45.0,"minutes",day-1,day,1,"steps")
        val b=model.kernelForEpisode(day,listOf(unsupported),day)
        assertEquals(a.isf.median,b.isf.median,1e-12)
        assertEquals(a.isf.p10,b.isf.p10,1e-12)
        assertTrue(b.factors.first{it.kind==KernelFactorKindV1.ACTIVITY}.appliedToMedian.not())
    }

    @Test fun `day state and promoted potency obey shrinkage and physiological bounds`() {
        val rows=(1..12).map{d->IdentifiedIsfEvidenceV1(d*day,d*day+1,20.0,1.0,d,"trusted correction")}
        val effect=PromotedKernelEffectV1(KernelFactorKindV1.ACTIVITY,KernelAxisV1.POTENCY,1.4,1.2,1.45,day,3,"prospective stable")
        val fact=EpisodeContextFactV1(KernelFactorKindV1.ACTIVITY,60.0,"minutes",13*day-1,13*day,1,"steps")
        val out=EpisodeKernelModelV1(base(),posterior(),rows,listOf(effect)).kernelForEpisode(13*day,listOf(fact),13*day)
        assertTrue(out.isf.median in .5..8.0)
        assertTrue(out.isf.median<8.0) // global shrinkage + inertial bound prevents the raw 20.
        assertTrue(out.points.zipWithNext().all{it.second.median<=it.first.median+1e-9})
    }

    @Test fun `dose dependence is inert unless explicitly promoted and threshold supported`() {
        val eff=PromotedKernelEffectV1(KernelFactorKindV1.DOSE_DEPENDENCE,KernelAxisV1.TIMING,1.25,1.1,1.3,0,1,"supported large-dose slice",doseMinU=10.0)
        val fact=EpisodeContextFactV1(KernelFactorKindV1.DOSE_DEPENDENCE,12.0,"U",0,0,1,"bolus")
        val model=EpisodeKernelModelV1(base(),posterior(),promoted=listOf(eff))
        val small=model.kernelForEpisode(day,listOf(fact),day,5.0)
        val large=model.kernelForEpisode(day,listOf(fact),day,12.0)
        assertTrue(large.peakMin.median>small.peakMin.median)
    }

    @Test fun `future base and global revision cannot change old receipt`() {
        val old=EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1
        val future=EpisodeKernelPriorRevisionV1("future-patient-fit",3*day,2,
            base().copy(isfMmolPerU=6.0,peakMin=110.0),PosteriorV1(6.0,5.0,7.0,20,10,3*day),"future outcomes")
        val a=VersionedEpisodeKernelModelV1(listOf(old)).kernelForEpisode(2*day,emptyList(),2*day,4.0)
        val b=VersionedEpisodeKernelModelV1(listOf(old,future)).kernelForEpisode(2*day,emptyList(),2*day,4.0)
        assertEquals(a.isf.median,b.isf.median,0.0)
        assertEquals(a.peakMin.median,b.peakMin.median,0.0)
        assertEquals(a.provenanceHash,b.provenanceHash)
        assertEquals(old.identity,b.basePriorIdentity)
    }

    @Test fun `episode hash covers base global evidence context and dose identities`() {
        val old=EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1
        val at=2*day
        fun result(prior:EpisodeKernelPriorRevisionV1=old,evidence:List<IdentifiedIsfEvidenceV1> = emptyList(),value:Double=10.0,dose:Double=4.0)=
            VersionedEpisodeKernelModelV1(listOf(prior),evidence).kernelForEpisode(at,listOf(
                EpisodeContextFactV1(KernelFactorKindV1.TOD,value,"hour",at,at,1,"clock")),at,dose)
        val base=result()
        assertNotEquals(base.provenanceHash,result(old.copy(identity="other-base")).provenanceHash)
        assertNotEquals(base.provenanceHash,result(old.copy(globalIsf=PosteriorV1(2.6,1.5,4.0,0,0,0))).provenanceHash)
        assertNotEquals(base.provenanceHash,result(evidence=listOf(IdentifiedIsfEvidenceV1(day,day+1,3.0,.8,1,"correction"))).provenanceHash)
        assertNotEquals(base.provenanceHash,result(value=11.0).provenanceHash)
        assertNotEquals(base.provenanceHash,result(dose=5.0).provenanceHash)
    }

    @Test fun `future base revision cannot change historical joint receipt`() {
        val minute=60_000L
        val old=EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1
        val future=EpisodeKernelPriorRevisionV1("future-fit",3*day,2,
            base().copy(isfMmolPerU=6.0,peakMin=120.0),PosteriorV1(6.0,5.0,7.0,10,5,3*day),"future outcomes")
        val note=Annotation(day,"food","meal",id=1,estCarbs=20.0,carbsSource="manual",carbsKnownAtMs=day)
        val readings=(0..240 step 5).map{t->GlucosePoint(day+t*minute,5.5+t/100.0)}
        val boluses=listOf(BolusPoint(day,2.0))
        fun receipt(priors:List<EpisodeKernelPriorRevisionV1>)=jointMealAttributionV1(
            listOf(note),readings,boluses,PosteriorV1(.165,.117,.217,0,0,0),day+240*minute,
            {b->VersionedEpisodeKernelModelV1(priors).kernelForEpisode(b.tsMs,emptyList(),b.tsMs,b.units)},
        appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        val a=receipt(listOf(old))
        val b=receipt(listOf(old,future))
        assertEquals(a.cacheIdentity,b.cacheIdentity)
        assertEquals(a.allocations.single().bestAllocationMmol,b.allocations.single().bestAllocationMmol,0.0)
        assertEquals(a.insulinKernelHashes,b.insulinKernelHashes)
    }
}
