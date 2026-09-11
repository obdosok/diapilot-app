package com.diapilot.core.physio

import com.diapilot.core.analysis.ParametricKernel
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.max
import com.diapilot.core.hybrid.MealTimingScopeV1

class TimeResolvedAttributionV2Test {
    private val m=60_000L
    private val cs=PosteriorV1(.165,.117,.217,10,5,0)
    private val model=VersionedEpisodeKernelModelV1(listOf(EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1))
    private fun note(t:Int,name:String,g:Double,source:String="label",id:Long=t.toLong()+1,analysis:String?=null)=
        Annotation(t*m,"food",name,id=id,analysis=analysis,estCarbs=g,carbsSource=source,carbsKnownAtMs=t*m,analysisKnownAtMs=t*m)
    private fun kernel(b:BolusPoint)=model.kernelForEpisode(b.tsMs,emptyList(),b.tsMs,b.units)
    private fun shape(t:Int,onset:Int,rise:Double,tail:Double,lag:Double=10.0):Double {
        val tau=t-onset-lag;if(tau<=0)return 0.0
        return ((1-exp(-tau/rise))*exp(-max(0.0,tau-tail)/(tail*.75))).coerceIn(0.0,1.0)
    }
    private fun readings(end:Int,delta:(Int)->Double)=(0..end step 5).map{GlucosePoint(it*m,5.5+delta(it))}
    private fun solve(ns:List<Annotation>,rr:List<GlucosePoint>,bb:List<BolusPoint> = emptyList(),k:(BolusPoint)->EpisodeKernelResultV1=::kernel)=
        jointMealAttributionV1(ns,rr,bb,cs,rr.last().tsMs,k,JointAttributionConfigV1(allowUnversionedTitleShape=true),appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!

    @Test fun `coldnik tail is retained after plus60 ice cream and true amplitudes are covered`() {
        val slow="KINETICS_V2: fast=0;medium=0;slow=1;form=MIXED;confidence=1;source=test;alcohol=false"
        val medium="KINETICS_V2: fast=0;medium=1;slow=0;form=SOFT_SOLID;confidence=1;source=test;alcohol=false"
        val cold=note(0,"arbitrary-a",35.0,analysis=slow);val ice=note(60,"arbitrary-b",20.0,analysis=medium)
        val a=35*.165;val b=20*.165
        val out=solve(listOf(cold,ice),readings(360){t->a*shape(t,0,103.5,414.0)+b*shape(t,60,55.0,220.0)})
        assertEquals(2,out.allocations.size)
        assertTrue(a in out.allocations[0].allocationLowMmol..out.allocations[0].allocationHighMmol)
        assertTrue(b in out.allocations[1].allocationLowMmol..out.allocations[1].allocationHighMmol)
        val afterIce=out.allocations[0].contributionCurve.first{it.tsMs>=120*m}.median
        assertTrue(afterIce>1.0)
        assertTrue(out.allocations[1].contributionCurve.filter{it.tsMs<70*m}.all{it.median==0.0})
    }

    @Test fun `fast meal completed before complex meal is locked and does not merge timings`() {
        val fast="KINETICS_V2: fast=1;medium=0;slow=0;form=LIQUID;confidence=1;source=test;alcohol=false"
        val complex="БЕЛКИ: 30 г\nЖИРЫ: 35 г\nKINETICS_V2: fast=.1;medium=.55;slow=.35;form=MIXED;confidence=1;source=test;alcohol=false;protein=30;fat=35"
        val smoothie=note(0,"ignored",22.0,analysis=fast);val breakfast=note(75,"ignored",45.0,analysis=complex)
        val out=solve(listOf(smoothie,breakfast),readings(420){t->
            22*.165*shape(t,0,20.5,82.0)+45*.165*shape(t,75,75.0,430.0)
        })
        assertEquals(MealTimingScopeV1.COMPLETED_BEFORE_CLUSTER,out.allocations.first().timingScope)
        assertEquals(MealTimingScopeV1.INDIVIDUAL,out.allocations.last().timingScope)
        assertEquals(67.0,out.allocations.first().clusterCarbsG,1e-9)
        assertNotNull(out.clusterTiming)
    }

    @Test fun `overlapping complex meals expose aggregate timing but no individual phases`() {
        val complex="БЕЛКИ: 25 г\nЖИРЫ: 30 г\nKINETICS_V2: fast=.1;medium=.55;slow=.35;form=MIXED;confidence=1;source=test;alcohol=false;protein=25;fat=30"
        val ns=listOf(note(0,"ignored-a",35.0,analysis=complex),note(60,"ignored-b",25.0,analysis=complex))
        val out=solve(ns,readings(420){t->35*.165*shape(t,0,75.0,400.0)+25*.165*shape(t,60,75.0,400.0)})
        assertTrue(out.allocations.all{it.timingScope==MealTimingScopeV1.CLUSTER_ONLY})
        assertTrue(out.allocations.all{it.observedPhases.isEmpty()})
        assertNotNull(out.clusterTiming)
        assertEquals(60.0,out.allocations.first().clusterCarbsG,1e-9)
    }

    @Test fun `three meal apparent deficit surplus is audited without inflating last meal`() {
        val ns=listOf(note(0,"slow fatty meal",30.0,"photo"),note(60,"slow protein meal",25.0,"photo"),note(120,"dessert",15.0,"label"))
        val amps=ns.map{it.estCarbs!!*.165}
        val out=solve(ns,readings(360){t->amps[0]*shape(t,0,105.0,390.0)+amps[1]*shape(t,60,105.0,370.0)+amps[2]*shape(t,120,65.0,230.0)})
        assertNotNull(out.tailTransferAudit)
        assertEquals(AttributionResolutionV1.UNRESOLVED,out.status)
        assertTrue(out.allocations.last().bestAllocationMmol<=out.allocations.last().modelForecastMmol*1.35+1e-9)
        assertTrue(out.tailTransferAudit!!.previousMealsShareOfRecognizedFoodLate.median>0.25)
    }

    @Test fun `late meal has exactly zero contribution before causal sensor onset`() {
        val out=solve(listOf(note(0,"first",20.0),note(60,"late",20.0)),readings(240){t->2*shape(t,0,65.0,230.0)+2*shape(t,60,65.0,230.0)})
        val late=out.allocations.last()
        assertEquals(70*m,late.causalOnsetMs)
        assertTrue(late.contributionCurve.filter{it.tsMs<late.causalOnsetMs}.all{it.median==0.0&&it.high==0.0})
    }

    @Test fun `high fat protein meal keeps a tail after the next meal`() {
        val macro="БЕЛКИ: 35 г\nЖИРЫ: 30 г"
        val out=solve(listOf(note(0,"fat protein meal",30.0,analysis=macro),note(120,"snack",10.0)),readings(420){t->4.95*shape(t,0,105.0,472.5)+1.65*shape(t,120,65.0,230.0)})
        val first=out.allocations.first()
        assertTrue(first.contributionCurve.first{it.tsMs>=240*m}.median>1.0)
        assertTrue(first.fractionBeforeNext.median<.8)
    }

    @Test fun `two identical strongly overlapping meals stay unresolved with broad intervals`() {
        val out=solve(listOf(note(0,"same meal",20.0),note(50,"same meal",20.0)),readings(300){t->3.3*shape(t,0,65.0,230.0)+3.3*shape(t,50,65.0,230.0)})
        assertTrue(out.allocations.all{it.status==AttributionResolutionV1.UNRESOLVED})
        assertTrue(out.allocations.all{it.allocationHighMmol-it.allocationLowMmol>=it.modelForecastMmol*.5})
    }

    @Test fun `early tail cannot establish phases for a flat late meal`() {
        val early=note(0,"early meal",35.0);val late=note(150,"late meal",20.0)
        val out=solve(listOf(early,late),readings(420){t->5.775*shape(t,0,65.0,230.0)})
        assertTrue(out.allocations.last().phaseWindowsAvailable.isNotEmpty())
        assertTrue(out.allocations.last().toString(),out.allocations.last().observedPhases.isEmpty())
    }

    @Test fun `joint-support collinearity beyond ninety minutes stays unresolved`() {
        val out=solve(listOf(note(0,"same slow meal",25.0),note(105,"same slow meal",25.0)),
            readings(420){t->4.125*shape(t,0,105.0,390.0)+4.125*shape(t,105,105.0,390.0)})
        assertTrue(out.allocations.all{it.status==AttributionResolutionV1.UNRESOLVED})
    }

    @Test fun `truncated twenty five note input can never resolve`() {
        val ns=(0..24).map{i->note(i*20,"meal $i",5.0,id=(i+1).toLong())}
        val out=solve(ns,readings(600){t->ns.sumOf{0.825*shape(t,(it.tsMs/m).toInt(),65.0,230.0)}})
        assertTrue(out.inputTruncated)
        assertEquals(AttributionResolutionV1.UNRESOLVED,out.status)
        assertTrue(out.allocations.all{it.status==AttributionResolutionV1.UNRESOLVED})
        assertTrue(out.allocations.any{24*20*m in it.perNoteOnsetsMs})
    }

    @Test fun `six sparse points over six hours cannot resolve phases`() {
        val rr=(0..5).map{i->GlucosePoint(i*72*m,5.5+i*.2)}
        val out=jointMealAttributionV1(listOf(note(0,"meal",20.0)),rr,emptyList(),cs,rr.last().tsMs,::kernel,
            JointAttributionConfigV1(gridMin=72.0),appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        assertNotEquals(AttributionResolutionV1.RESOLVED,out.status)
        assertTrue(out.allocations.single().observedPhases.isEmpty())
    }

    @Test fun `multi portion prior precision is carb weighted`() {
        val long="KINETICS_V2: fast=0.3;medium=0.6;slow=0.1;form=LIQUID;confidence=1;source=test;alcohol=true"
        val ns=listOf(note(0,"first",99.0,"label",1,long),note(60,"second",1.0,"photo",2,long))
        val out=solve(ns,readings(360){t->16.5*shape(t,0,50.0,220.0)})
        assertEquals(1,out.allocations.size)
        assertTrue(out.allocations.single().priorStrength>17.0)
    }

    @Test fun `baseline is last glucose at or before intake`() {
        val rr=listOf(GlucosePoint(-14*m,5.0),GlucosePoint(1*m,10.0))+
            (15..300 step 15).map{GlucosePoint(it*m,10.0)}
        val out=jointMealAttributionV1(listOf(note(0,"meal",20.0)),rr,emptyList(),cs,300*m,::kernel,appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        // A bounded fitted background may explain a small part of the sustained
        // five-point step; anchoring on the post-meal 10.0 sample would leave
        // approximately zero, so a clear positive margin still pins the causal
        // baseline contract.
        assertTrue(out.clusterObservedAfterInsulinBackground.toString(),out.clusterObservedAfterInsulinBackground.median>2.0)
    }

    @Test fun `exact label prior is stronger and narrower than photo prior`() {
        val rr=readings(300){t->3.3*shape(t,0,65.0,230.0)}
        val exact=solve(listOf(note(0,"meal",20.0,"label")),rr).allocations.single()
        val photo=solve(listOf(note(0,"meal",20.0,"photo")),rr).allocations.single()
        assertTrue(exact.priorStrength>photo.priorStrength)
        assertTrue(exact.allocationHighMmol-exact.allocationLowMmol<photo.allocationHighMmol-photo.allocationLowMmol)
    }

    @Test fun `unlogged late rise remains process residual rather than last meal`() {
        val meal=note(0,"meal",20.0,"label")
        val out=solve(listOf(meal),readings(360){t->3.3*shape(t,0,65.0,230.0)+if(t>210)(t-210)/25.0 else 0.0})
        assertTrue(out.unloggedFoodResidualMmol.median>.3)
        assertTrue(out.allocations.single().bestAllocationMmol<=out.allocations.single().modelForecastMmol*1.35+1e-9)
    }

    @Test fun `wider insulin posterior widens meal allocation`() {
        val meal=note(0,"meal",25.0,"label");val bolus=listOf(BolusPoint(0,2.0))
        val rr=readings(300){t->4.125*shape(t,0,65.0,230.0)-2.0*(1-exp(-t/80.0))}
        fun k(spread:Double):(BolusPoint)->EpisodeKernelResultV1={b->
            val base=kernel(b);base.copy(points=base.points.map{it.copy(q1=it.median-spread,q3=it.median+spread)},provenanceHash=base.provenanceHash+spread)
        }
        val narrow=solve(listOf(meal),rr,bolus,k(.02)).allocations.single()
        val wide=solve(listOf(meal),rr,bolus,k(.8)).allocations.single()
        assertTrue(wide.allocationHighMmol-wide.allocationLowMmol>=narrow.allocationHighMmol-narrow.allocationLowMmol)
    }

    @Test fun `revised glucose and causal configuration change time resolved hash`() {
        val n=listOf(note(0,"meal",20.0));val r=readings(240){t->3.3*shape(t,0,65.0,230.0)}
        val a=solve(n,r).cacheIdentity
        assertNotEquals(a,solve(n,r.mapIndexed{i,p->if(i==20)p.copy(mmol=p.mmol+.1)else p}).cacheIdentity)
        val changed=jointMealAttributionV1(n,r,emptyList(),cs,r.last().tsMs,::kernel,JointAttributionConfigV1(sensorLagMedianMin=15.0),appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!.cacheIdentity
        assertNotEquals(a,changed)
    }

    @Test fun `future macro analysis cannot change historical shape or receipt hash`() {
        val base=note(0,"meal",20.0).copy(analysis=null,analysisKnownAtMs=300*m)
        val future=base.copy(analysis="БЕЛКИ: 50 г\nЖИРЫ: 50 г")
        val rr=readings(240){t->3.3*shape(t,0,65.0,230.0)}
        assertEquals(solve(listOf(base),rr).cacheIdentity,solve(listOf(future),rr).cacheIdentity)
    }

    @Test fun `solver work and memory dimensions are bounded`() {
        val notes=(0 until 30).map{note(it,"distributed portion",1.0,"photo",it.toLong()+1)}
        val rr=readings(360){t->4.95*shape(t,0,65.0,230.0)}
        val started=System.nanoTime();val out=solve(notes,rr)
        val elapsedMs=(System.nanoTime()-started)/1_000_000
        assertTrue(out.inputTruncated)
        assertTrue(out.timeBins.size<=24)
        assertTrue(elapsedMs<2_000)
    }

    @Test fun `allocation is invariant to input note order`() {
        val slow="KINETICS_V2: fast=0.1;medium=0.2;slow=0.7;form=MIXED;confidence=1;source=test;alcohol=false"
        val ns=listOf(note(0,"alpha",30.0,analysis=slow),note(105,"omega",18.0,analysis=slow))
        val rr=readings(420){t->4.95*shape(t,0,100.0,380.0)+2.97*shape(t,105,100.0,380.0)}
        val forward=solve(ns,rr).allocations.associateBy{it.startMs}
        val reversed=solve(ns.reversed(),rr).allocations.associateBy{it.startMs}
        assertEquals(forward.keys,reversed.keys)
        forward.forEach{(ts,a)->assertEquals(a.bestAllocationMmol,reversed.getValue(ts).bestAllocationMmol,1e-9)}
    }

    @Test fun `signed falling process is not purchased by first or last food`() {
        val ns=listOf(note(0,"one",24.0),note(100,"two",24.0),note(200,"three",24.0))
        val rr=readings(500){t->
            ns.sumOf{3.96*shape(t,(it.tsMs/m).toInt(),65.0,230.0)}-
                if(t in 230..330)(t-230)/25.0 else if(t>330)4.0 else 0.0
        }
        val out=solve(ns,rr)
        assertTrue(out.latentSignedProcessMmol.p10<-.25)
        assertTrue(out.allocations.all{it.bestAllocationMmol<=it.modelForecastMmol*1.35+1e-9})
        assertEquals(AttributionResolutionV1.UNRESOLVED,out.status)
    }
}
