package com.diapilot.core.physio

import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.analysis.RESCUE_NOTE_PREFIX
import com.diapilot.core.analysis.isFoodNote
import com.diapilot.core.analysis.parseFoodNutrition
import com.diapilot.core.analysis.parseFoodKineticsV2
import com.diapilot.core.analysis.FoodPhysicalFormV2
import com.diapilot.core.hybrid.MealClusterMemberV1
import com.diapilot.core.hybrid.MealTimingScopeV1
import com.diapilot.core.hybrid.causalMealClusterClockV1
import com.diapilot.core.hybrid.CarbAppearanceMemberV1
import com.diapilot.core.hybrid.throughputLimitedFractionsV1
import com.diapilot.core.hybrid.caloricLimitedFractionsV1
import com.diapilot.core.hybrid.caloricLimitedTimelineV1
import com.diapilot.core.hybrid.throughputLimitedFractionTimelineV1
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * v7: the decomposition now runs the caloric queue with sieving
 * instead of the flat 30 g/h. A receipt computed under v6 describes a different
 * physiology, so it must not be restored as current after an upgrade — this
 * string is half of what stops that, and [CarbAppearancePolicyV1.signature] in
 * the hash below is the other half, the half that keeps working when somebody
 * retunes the policy without touching this line.
 */
const val JOINT_ATTRIBUTION_VERSION_V1 = "time-resolved-joint-attribution-v7-caloric-sieving"
const val DISTRIBUTED_INTAKE_GAP_MIN_V1 = 120L
const val DISTRIBUTED_INTAKE_MAX_SPAN_MIN_V1 = 180L

enum class AttributionResolutionV1 { RESOLVED, UNRESOLVED, CENSORED }
enum class ObservedPhaseV1 { START, LEVEL_MAXIMUM, LATE_PHASE, PLATEAU }

data class DistributedIntakeV1(val sessionId:String,val notes:List<Annotation>) {
    val startMs get()=notes.first().tsMs
    val endMs get()=notes.last().tsMs
    val carbsG get()=notes.mapNotNull{it.estCarbs}.sum()
    val durationMin get()=(endMs-startMs)/60_000.0
}

fun groupDistributedIntakesV1(notes:List<Annotation>,gapMin:Long=DISTRIBUTED_INTAKE_GAP_MIN_V1,maxSpanMin:Long=DISTRIBUTED_INTAKE_MAX_SPAN_MIN_V1):List<DistributedIntakeV1> {
    // A long intake is a structured property, never a title substring.
    fun isLongIntake(a:Annotation)=parseFoodKineticsV2(a.analysis).alcoholPresent
    val groups=mutableListOf<MutableList<Annotation>>()
    notes.filter(::isFoodNote).sortedBy{it.tsMs}.forEach{n->
        val cur=groups.lastOrNull();val rescue=n.content.startsWith(RESCUE_NOTE_PREFIX,true)
        val sameRescue=cur?.lastOrNull()?.content?.startsWith(RESCUE_NOTE_PREFIX,true)==rescue
        val sameLong=cur?.lastOrNull()?.let(::isLongIntake)==true&&isLongIntake(n)
        val allowed=if(sameLong)gapMin else 45L
        if(cur!=null&&sameRescue&&n.tsMs-cur.last().tsMs<=allowed*60_000&&n.tsMs-cur.first().tsMs<=maxSpanMin*60_000)cur.add(n)
        else groups.add(mutableListOf(n))
    }
    return groups.map{DistributedIntakeV1("meal:${it.first().tsMs}:${it.joinToString(","){n->n.id.toString()}}",it)}
}

data class TimeContributionPointV2(val tsMs:Long,val median:Double,val low:Double,val high:Double)
data class TimeFitBinV2(
    val tsMs:Long,val observedAfterInsulin:Double,val fittedMeals:Double,val fittedBackground:Double,
    val sharedResidual:Double,val fittedTotal:Double,val sigma:Double,
)
data class TailTransferAuditV2(
    val detected:Boolean,
    val earlyDeficitMmol:Double,
    val lastSurplusMmol:Double,
    val lastMealNaiveMmol:Double,
    val lastMealJointMmol:Double,
    val previousMealsShareOfRecognizedFoodLate:PosteriorV1,
    val unassignedLateShare:PosteriorV1,
    val resolution:AttributionResolutionV1,
    val provenance:String,
)
data class ClusterTimingV1(
    val onsetMin:Double,val levelPeakMin:Double,val mainEndMin:Double,
    val provenance:String="joint fitted aggregate meal curve",
)

data class MealAllocationV1(
    val sessionId:String,val startMs:Long,val endMs:Long,val recordedCarbsG:Double,val gramsProvenance:String,
    val gramsLow:Double,val gramsHigh:Double,val modelForecastMmol:Double,val bestAllocationMmol:Double,
    val allocationLowMmol:Double,val allocationHighMmol:Double,val shareMedian:Double,val shareLow:Double,
    val shareHigh:Double,val status:AttributionResolutionV1,val observedPhases:Set<ObservedPhaseV1>,
    val phaseWindowsAvailable:Set<ObservedPhaseV1>,val coverageMin:Double,val perNoteOnsetsMs:List<Long>,
    val confidence:Double,val caveats:List<String>,
    val contributionCurve:List<TimeContributionPointV2>,val fractionBeforeNext:PosteriorV1,
    val fractionAfterNext:PosteriorV1,val priorStrength:Double,val causalOnsetMs:Long,
    val causalLearningEligible:Boolean=false,
    val timingScope:MealTimingScopeV1=MealTimingScopeV1.INDIVIDUAL,
    val clusterCarbsG:Double=recordedCarbsG,
)

data class JointAttributionReceiptV1(
    val version:String,val clusterStartMs:Long,val clusterEndMs:Long,val asOfMs:Long,val globalCs:PosteriorV1,
    val allocations:List<MealAllocationV1>,val unloggedFoodResidualMmol:PosteriorV1,
    val backgroundMmolPerHour:PosteriorV1,val insulinKernelHashes:Map<Long,String>,
    val clusterObservedAfterInsulinBackground:PosteriorV1,val status:AttributionResolutionV1,
    val cacheIdentity:String,val timeBins:List<TimeFitBinV2>,val sharedResidualCurve:List<TimeContributionPointV2>,
    val tailTransferAudit:TailTransferAuditV2?,val inputTruncated:Boolean,
    val latentSignedProcessMmol:PosteriorV1=PosteriorV1(0.0,0.0,0.0,0,0,null),
    val clusterTiming:ClusterTimingV1?=null,
)

data class JointAttributionConfigV1(
    val horizonMin:Double=360.0,val gridMin:Double=15.0,val mealAmplitudeMaxPrior:Double=1.60,
    val backgroundAbsMaxMmolPerHour:Double=1.5,val anchorPenalty:Double=5.0,val residualPenalty:Double=12.0,
    val residualSmoothness:Double=5.0,val sensorLagMedianMin:Double=10.0,val sensorLagLowMin:Double=5.0,
    val sensorLagHighMin:Double=20.0,val sensorSigmaMmol:Double=.18,val maxIterations:Int=140,
    val maxNotes:Int=24,val maxCgmGapMin:Double=30.0,val minCoverageDensity:Double=.60,
    /** Current annotation titles have no append-only revisions. Android and evaluator keep this false. */
    val allowUnversionedTitleShape:Boolean=false,
)

private data class ShapePartV2(val weight:Double,val rise:Double,val tail:Double)
private data class ShapeSpec(
    val parts:List<ShapePartV2>,val lag:Double,
    val proteinG:Double,val fatG:Double,val fiberG:Double,
    val rescue:Boolean=false,
)
private fun shapeSpec(n:Annotation,config:JointAttributionConfigV1,asOfMs:Long):ShapeSpec {
    if(n.content.trim().startsWith(RESCUE_NOTE_PREFIX,ignoreCase=true))
        return ShapeSpec(listOf(ShapePartV2(1.0,3.0,30.0)),10.0,0.0,0.0,0.0,true)
    val causalAnalysis=n.analysis.takeIf{n.analysisKnownAtMs?.let{known->known<=asOfMs}==true}
    val nutrition=parseFoodNutrition(causalAnalysis)
    val fat=(nutrition.fatG?:0.0);val protein=(nutrition.proteinG?:0.0)
    val k=parseFoodKineticsV2(causalAnalysis,nutrition.proteinG,nutrition.fatG).normalized()
    val fiber=k.fiberG?:0.0
    val formStretch=when(k.physicalForm){FoodPhysicalFormV2.LIQUID->.82;FoodPhysicalFormV2.PUREE->.92
        FoodPhysicalFormV2.SOFT_SOLID->1.0;FoodPhysicalFormV2.SOLID->1.10;FoodPhysicalFormV2.MIXED->1.15;FoodPhysicalFormV2.UNKNOWN->1.0}
    val macroTail=(fat*2.0+protein*1.2+fiber*2.0).coerceIn(0.0,180.0)
    return ShapeSpec(listOf(
        ShapePartV2(k.fastFraction,25.0*formStretch,100.0*formStretch+macroTail*.45),
        ShapePartV2(k.mediumFraction,55.0*formStretch,220.0*formStretch+macroTail*.75),
        ShapePartV2(k.slowFraction,90.0*formStretch,360.0*formStretch+macroTail),
    ).filter{it.weight>1e-6},config.sensorLagMedianMin,protein,fat,fiber,false)
}
private fun shapeValueAtAge(ageMin:Double,spec:ShapeSpec,lag:Double=spec.lag):Double {
    val tau=ageMin-lag
    if(tau<=0)return 0.0
    return spec.parts.sumOf{part->
        val rise=1-exp(-tau/part.rise.coerceAtLeast(1.0))
        val decay=exp(-max(0.0,tau-part.tail)/(part.tail*.75).coerceAtLeast(1.0))
        part.weight*rise*decay
    }.coerceIn(0.0,1.0)
}
private fun shapeProgressAtAge(ageMin:Double,spec:ShapeSpec,lag:Double=spec.lag):Double {
    val tau=ageMin-lag;if(tau<=0)return 0.0
    return spec.parts.sumOf{it.weight*(1-exp(-tau/it.rise.coerceAtLeast(1.0)))}.coerceIn(0.0,1.0)
}
private fun clusterMember(n:Annotation,spec:ShapeSpec):MealClusterMemberV1 {
    var main=spec.lag
    while(main<720&&shapeProgressAtAge(main,spec)<.90)main+=2.0
    val tail=(spec.parts.maxOfOrNull{it.tail*2.0}?:360.0).coerceIn(main+1,720.0)+spec.lag
    return MealClusterMemberV1("food:${n.id}",n.tsMs,spec.proteinG,spec.fatG,spec.fiberG,main,tail.coerceAtMost(720.0))
}
private fun provenancePrior(source:String?):Pair<Double,Double> = when(source?.lowercase()) {
    "weighed","label","anchor"->18.0 to .08
    "manual"->13.0 to .10
    "preset"->7.0 to .18
    "llm","photo","vision"->3.0 to .32
    else->2.0 to .40
}

/**
 * Bounded time-resolved joint posterior. All CGM bins enter one fit. Meal bases
 * retain their own causal onsets and tails; a smooth signed latent-process
 * curve keeps both unexplained rises and unexplained falls unassigned instead
 * of donating them to the last meal or to insulin.
 */
fun jointMealAttributionV1(
    notes:List<Annotation>,readings:List<GlucosePoint>,boluses:List<BolusPoint>,globalCs:PosteriorV1,
    asOfMs:Long,kernelForBolus:(BolusPoint)->EpisodeKernelResultV1,
    config:JointAttributionConfigV1=JointAttributionConfigV1(),
    /**
     * HOW CARBOHYDRATE APPEARS — required, with no default, on purpose.
     *
     * It WAS a boolean defaulting to false, and that default is precisely how
     * the corpus ended up being built under the flat 30 g/h while the forecast
     * drew the same meal under the caloric queue with sieving. A corpus learned
     * under one appearance model and a forecast drawn under another disagree
     * about the same food, and the corpus is what the forecast learns from.
     *
     * There is no arm to infer it from here — this function is handed notes and
     * readings, not a person model — so the caller must state it and a new call
     * site is a compile error rather than a silent revert to the old queue.
     */
    appearance:com.diapilot.core.hybrid.CarbAppearancePolicyV1,
):JointAttributionReceiptV1? {
    val eligibleAll=notes.filter{it.carbsKnownAtMs?.let{known->known<=asOfMs}==true}.sortedBy{it.tsMs}
    val inputTruncated=eligibleAll.size>config.maxNotes
    // Preserve the newest onset in a diagnostic even when the hard input cap
    // is hit. The whole receipt is UNRESOLVED below; silently dropping only the
    // late meal would recreate the exact tail-transfer blind spot.
    val eligible=if(inputTruncated&&config.maxNotes>1)eligibleAll.take(config.maxNotes-1)+eligibleAll.last() else eligibleAll.take(config.maxNotes)
    val sessions=groupDistributedIntakesV1(eligible).filter{it.carbsG>0&&it.startMs<=asOfMs}
    if(sessions.isEmpty()||readings.isEmpty())return null
    val start=sessions.minOf{it.startMs};val intendedEnd=minOf(asOfMs,start+(config.horizonMin*60_000).toLong())
    val r=readings.filter{it.tsMs<=asOfMs}.sortedBy{it.tsMs}
    fun near(t:Long)=r.minByOrNull{abs(it.tsMs-t)}?.takeIf{abs(it.tsMs-t)<=10*60_000}
    // Never anchor on post-meal glucose: even +10 minutes can already contain food.
    val basePoint=r.filter{it.tsMs<=start}.maxByOrNull{it.tsMs}?.takeIf{start-it.tsMs<=15*60_000}?:return null
    val base=basePoint.mmol
    val times=generateSequence(start+(config.gridMin*60_000).toLong()){it+(config.gridMin*60_000).toLong()}
        .takeWhile{it<=intendedEnd}.mapNotNull{t->near(t)?.let{p->Triple(t,p.tsMs,p.mmol)}}.distinctBy{it.second}.toList()
    if(times.size<3)return null
    fun continuousEnd(onset:Long):Long {
        val points=(listOfNotNull(r.filter{it.tsMs<=onset}.maxByOrNull{it.tsMs})+r.filter{it.tsMs>onset&&it.tsMs<=intendedEnd}).sortedBy{it.tsMs}
        if(points.isEmpty())return onset
        var end=points.first().tsMs
        for(p in points.drop(1)){if((p.tsMs-end)/60_000.0>config.maxCgmGapMin)break;end=p.tsMs}
        return end
    }
    val continuousEnds=sessions.map{continuousEnd(it.startMs)}

    val activeBoluses=boluses.filter{it.tsMs<=asOfMs&&it.tsMs>=start-8*3_600_000L}
    val kernels=activeBoluses.associateWith(kernelForBolus)
    fun gv(k:List<KernelPoint>,tau:Double,which:Int):Double {
        if(tau<=0)return 0.0
        val p=k.lastOrNull{it.tauMin<=tau}?:return 0.0
        return when(which){-1->p.q1;1->p.q3;else->p.median}
    }
    fun insulin(t:Long,which:Int)=activeBoluses.sumOf{b->val k=kernels.getValue(b).points
        b.units*(gv(k,(t-b.tsMs)/60_000.0,which)-gv(k,(start-b.tsMs)/60_000.0,which))}
    val y=DoubleArray(times.size){i->times[i].third-base-insulin(times[i].first,0)}
    val sigma=DoubleArray(times.size){i->
        val lo=insulin(times[i].first,-1);val hi=insulin(times[i].first,1)
        sqrt(config.sensorSigmaMmol*config.sensorSigmaMmol+((hi-lo)/2.5632).let{it*it}).coerceAtLeast(.12)
    }

    val specs=sessions.map{s->s.notes.associateWith{shapeSpec(it,config,asOfMs)}}
    val specByNote=specs.flatMap{it.entries}.associate{it.key to it.value}
    val clusterMembers=specs.flatMap{it.entries}.associate{(n,spec)->n to clusterMember(n,spec)}
    val normalNotes=clusterMembers.keys.filterNot{specByNote.getValue(it).rescue}
    // Calories per member, so the deconvolution meters the same pipe the
    // forecast does. Without this the corpus learned a meal's shape under a
    // 30 g/h assumption while the forecast drew it under a caloric one — the
    // two would disagree about the SAME meal, and the corpus is what the
    // forecast then learns from.
    val appearanceMembers=normalNotes.map{n->
        // CAUSAL MACROS ONLY. The caloric queue reads protein and fat, so a
        // receipt computed as of a past moment would otherwise be metered by
        // grams the user typed hours later — the corpus would quietly know the
        // future. Caught by `future macro analysis cannot change historical
        // shape or receipt hash`, which had guarded exactly this before the
        // queue gave macros a second way in.
        val nutrition=com.diapilot.core.analysis.parseFoodNutrition(
            n.analysis.takeIf{n.analysisKnownAtMs?.let{known->known<=asOfMs}==true},
        )
        CarbAppearanceMemberV1(
            "food:${n.id}",n.tsMs,n.estCarbs?:0.0,
            kcal=if(!appearance.caloric)null else com.diapilot.core.analysis.MealCaloricExtentV1.kcal(
                n.estCarbs?:0.0,nutrition.proteinG,nutrition.fatG,
            ),
        )
    }
    val noteByAppearanceId=normalNotes.associateBy{it.id.toString()}
    val allClusterMembers=clusterMembers.values.toList()
    val normalClusterMembers=normalNotes.mapNotNull(clusterMembers::get)
    val limitedProgressCache=mutableMapOf<Long,Map<String,Double>>()
    fun rawClusterProgress(n:Annotation,t:Long,spec:ShapeSpec):Double {
        val clock=causalMealClusterClockV1(clusterMembers.getValue(n),t,normalClusterMembers,
            ownerStandaloneCdf={age->shapeProgressAtAge(age,spec,spec.lag)})
        return shapeProgressAtAge(clock.effectiveAgeMin,spec,spec.lag)
    }
    // Most calls below are for the same 15-minute grid. Simulate the 30 g/h
    // queue once up to the final bin instead of replaying it from the meal for
    // every bin. Non-grid boundary probes retain the exact single-time path.
    run {
        val supply={m:CarbAppearanceMemberV1,at:Long->
            val note=noteByAppearanceId.getValue(m.id.removePrefix("food:"))
            rawClusterProgress(note,at,specByNote.getValue(note))
        }
        // PASS THE POLICY, do not lean on the queue's own defaults: the rate and
        // the sieving are the parameters being fitted, so a study that sweeps
        // them must actually move this call. The same omission once made a rate
        // sweep print nine identical numbers.
        limitedProgressCache.putAll(
            appearance.emptyingKcalPerHour?.let{rate->
                caloricLimitedTimelineV1(appearanceMembers,times.map{it.first},supply,
                    kcalPerHour=rate,carbSieving=appearance.carbSieving)
            }?:throughputLimitedFractionTimelineV1(appearanceMembers,times.map{it.first},supply),
        )
    }
    fun limitedProgress(n:Annotation,t:Long,spec:ShapeSpec):Double {
        if(spec.rescue)return shapeProgressAtAge((t-n.tsMs)/60_000.0,spec,spec.lag)
        // OFF-GRID PROBES MUST USE THE SAME QUEUE. This branch was hard-wired
        // to the gram queue while the grid above ran the caloric one, so a
        // single receipt decomposed its 15-minute bins under one physiology and
        // its boundary probes under another — the same class of split as the
        // corpus-versus-forecast one this policy object exists to close.
        val map=limitedProgressCache.getOrPut(t){
            val supply={m:CarbAppearanceMemberV1,at:Long->
                val note=noteByAppearanceId.getValue(m.id.removePrefix("food:"))
                rawClusterProgress(note,at,specByNote.getValue(note))
            }
            appearance.emptyingKcalPerHour?.let{rate->
                caloricLimitedFractionsV1(appearanceMembers,t,supply,
                    kcalPerHour=rate,carbSieving=appearance.carbSieving)
            }?:throughputLimitedFractionsV1(appearanceMembers,t,supply)
        }
        return map["food:${n.id}"]?:0.0
    }
    fun clusteredShapeValue(n:Annotation,t:Long,spec:ShapeSpec,lag:Double):Double {
        if(spec.rescue)return shapeValueAtAge((t-n.tsMs)/60_000.0,spec,lag)
        val owner=clusterMembers.getValue(n)
        val clock=causalMealClusterClockV1(
            owner,t,allClusterMembers,
            ownerStandaloneCdf={age->shapeProgressAtAge(age,spec,lag)},
        )
        val rawProgress=shapeProgressAtAge(clock.effectiveAgeMin,spec,lag)
        val ratio=if(rawProgress<=1e-9)0.0 else limitedProgress(n,t,spec)/rawProgress
        return (shapeValueAtAge(clock.effectiveAgeMin,spec,lag)*ratio).coerceIn(0.0,1.0)
    }
    fun sessionBasis(j:Int,t:Long,lagMode:Int=0):Double {
        val known=sessions[j].notes.filter{(it.estCarbs?:0.0)>0};val total=known.sumOf{it.estCarbs!!}.coerceAtLeast(1e-9)
        return known.sumOf{n->val sp=specs[j].getValue(n);val lag=when(lagMode){-1->config.sensorLagLowMin;1->config.sensorLagHighMin;else->sp.lag}
            itWeight(n,total)*clusteredShapeValue(n,t,sp,lag)}
    }
    fun sessionProgress(j:Int,t:Long):Double {
        val known=sessions[j].notes.filter{(it.estCarbs?:0.0)>0};val total=known.sumOf{it.estCarbs!!}.coerceAtLeast(1e-9)
        return known.sumOf{n->
            val sp=specs[j].getValue(n);val clock=causalMealClusterClockV1(
                clusterMembers.getValue(n),t,allClusterMembers,
                ownerStandaloneCdf={age->shapeProgressAtAge(age,sp,sp.lag)},
            )
            itWeight(n,total)*(if(sp.rescue)shapeProgressAtAge(clock.effectiveAgeMin,sp,sp.lag) else limitedProgress(n,t,sp))
        }.coerceIn(0.0,1.0)
    }
    val x=sessions.indices.map{j->DoubleArray(times.size){i->if(times[i].second<=continuousEnds[j])sessionBasis(j,times[i].first)else 0.0}}
    val xEarly=sessions.indices.map{j->DoubleArray(times.size){i->if(times[i].second<=continuousEnds[j])sessionBasis(j,times[i].first,-1)else 0.0}}
    val xLate=sessions.indices.map{j->DoubleArray(times.size){i->if(times[i].second<=continuousEnds[j])sessionBasis(j,times[i].first,1)else 0.0}}
    val bgBasis=DoubleArray(times.size){i->(times[i].first-start)/3_600_000.0}
    val prior=DoubleArray(sessions.size){globalCs.median*sessions[it].carbsG}
    val priorStrength=DoubleArray(sessions.size){j->val total=sessions[j].carbsG.coerceAtLeast(1e-9);sessions[j].notes.sumOf{(it.estCarbs?:0.0)/total*provenancePrior(it.carbsSource).first}}
    val gramUncertainty=DoubleArray(sessions.size){j->val total=sessions[j].carbsG.coerceAtLeast(1e-9);sqrt(sessions[j].notes.sumOf{val w=(it.estCarbs?:0.0)/total;val u=provenancePrior(it.carbsSource).second;w*w*u*u})}
    val beta=prior.copyOf();val process=DoubleArray(times.size);var bg=0.0
    fun pred(i:Int)=x.indices.sumOf{j->x[j][i]*beta[j]}+bgBasis[i]*bg+process[i]
    repeat(config.maxIterations){
        // Jacobi update: every meal sees the same previous iterate. Sequential
        // in-place updates made allocation depend on note order in collinear
        // chains and could favour the first or last meal.
        val previousBeta=beta.copyOf();val previousProcess=process.copyOf();val previousBg=bg
        fun previousPred(i:Int)=x.indices.sumOf{j->x[j][i]*previousBeta[j]}+bgBasis[i]*previousBg+previousProcess[i]
        val nextBeta=beta.copyOf()
        for(j in x.indices){
            var num=priorStrength[j]*prior[j];var den=priorStrength[j]
            for(i in y.indices){val w=1/(sigma[i]*sigma[i]);val without=previousPred(i)-x[j][i]*previousBeta[j];num+=w*x[j][i]*(y[i]-without);den+=w*x[j][i]*x[j][i]}
            val maxFactor=if(priorStrength[j]>=12)1.35 else config.mealAmplitudeMaxPrior
            nextBeta[j]=(num/den.coerceAtLeast(1e-9)).coerceIn(0.0,prior[j]*maxFactor)
        }
        nextBeta.copyInto(beta)
        // Preserve plausible group mass. Ambiguous timing may redistribute a
        // group but cannot manufacture a many-fold first/last-meal amplitude.
        val priorTotal=prior.sum().coerceAtLeast(1e-9);val fittedTotal=beta.sum()
        val strong=priorStrength.average()>=10.0;val lo=priorTotal*(if(strong).70 else .50);val hi=priorTotal*(if(strong)1.30 else 1.60)
        if(fittedTotal>1e-9&&fittedTotal !in lo..hi){val scale=(fittedTotal.coerceIn(lo,hi)/fittedTotal);for(j in beta.indices)beta[j]*=scale}
        run{var num=0.0;var den=config.anchorPenalty*2.0;for(i in y.indices){val w=1/(sigma[i]*sigma[i]);val without=pred(i)-bgBasis[i]*bg;num+=w*bgBasis[i]*(y[i]-without);den+=w*bgBasis[i]*bgBasis[i]};bg=(num/den).coerceIn(-config.backgroundAbsMaxMmolPerHour,config.backgroundAbsMaxMmolPerHour)}
        for(i in process.indices){
            val meals=x.indices.sumOf{j->x[j][i]*beta[j]};val raw=(y[i]-meals-bgBasis[i]*bg)/(1+config.residualPenalty*sigma[i]*sigma[i])
            val neighbour=((process.getOrNull(i-1)?:0.0)+(process.getOrNull(i+1)?:0.0))/2
            process[i]=((raw+config.residualSmoothness*neighbour)/(1+config.residualSmoothness)).coerceIn(-12.0,12.0)
        }
    }
    val errors=DoubleArray(y.size){y[it]-pred(it)};val rmse=sqrt(errors.sumOf{it*it}/errors.size)
    // Preserve negative unexplained movement as its own signed latent process
    // without allowing it to purchase a lower food amplitude during the meal
    // fit. This is deliberately an unallocated observational residual, not a
    // post-hoc insulin or activity claim.
    val signedProcess=DoubleArray(process.size){i->(process[i]+errors[i]).coerceIn(-12.0,12.0)}
    fun jointCosine(a:DoubleArray,b:DoubleArray):Double {
        val support=a.indices.filter{a[it]>.03&&b[it]>.03};if(support.size<3)return 0.0
        val ab=support.sumOf{a[it]*b[it]/(sigma[it]*sigma[it])};val aa=sqrt(support.sumOf{a[it]*a[it]/(sigma[it]*sigma[it])});val bb=sqrt(support.sumOf{b[it]*b[it]/(sigma[it]*sigma[it])})
        return if(aa*bb<1e-9)0.0 else ab/(aa*bb)
    }
    val total=(beta.sum()+process.maxOrNull().orZero()).coerceAtLeast(1e-9)
    val intervalLow=DoubleArray(beta.size);val intervalHigh=DoubleArray(beta.size)
    val allocations=sessions.indices.map{j->
        val previousClose=j>0&&(sessions[j].startMs-sessions[j-1].endMs)/60_000.0<=120.0
        val nextClose=j<sessions.lastIndex&&(sessions[j+1].startMs-sessions[j].endMs)/60_000.0<=120.0
        val previousStillActive=previousClose&&sessionProgress(j-1,sessions[j].startMs)<.90
        val completedBeforeNext=nextClose&&sessionProgress(j,sessions[j+1].startMs)>=.90
        val timingScope=when{completedBeforeNext->MealTimingScopeV1.COMPLETED_BEFORE_CLUSTER
            previousStillActive||nextClose->MealTimingScopeV1.CLUSTER_ONLY
            else->MealTimingScopeV1.INDIVIDUAL}
        val localClusterCarbs=sessions.indices.filter{k->
            (k==j)||(k<j&&(sessions[j].startMs-sessions[k].endMs)/60_000.0<=120.0)||(k>j&&(sessions[k].startMs-sessions[j].endMs)/60_000.0<=120.0)
        }.sumOf{sessions[it].carbsG}
        val corr=x.indices.filter{it!=j}.maxOfOrNull{jointCosine(x[j],x[it])}?:0.0
        val close=sessions.indices.any{k->k!=j&&abs(sessions[k].startMs-sessions[j].startMs)<=90*60_000L}
        // On the shared post-onset support these saturating/tail bases become
        // poorly conditioned well before a whole-grid cosine reaches 0.97.
        // Be deliberately conservative: ambiguity remains an interval/residual.
        val unidentified=inputTruncated||corr>.90||close||times.size<2*(sessions.size+2)||timingScope==MealTimingScopeV1.CLUSTER_ONLY
        val info=x[j].indices.sumOf{x[j][it]*x[j][it]/(sigma[it]*sigma[it])}
        val baseWidth=(1.2816*sqrt(1/(priorStrength[j]+info).coerceAtLeast(1e-9))*(1+4*corr)+rmse*.30+prior[j]*gramUncertainty[j]*.35)
        val width=baseWidth.coerceAtLeast(prior[j]*(if(unidentified).30 else .08))
        val maxFactor=if(priorStrength[j]>=12)1.35 else config.mealAmplitudeMaxPrior
        val lo=(beta[j]-width).coerceAtLeast(0.0);val hi=(beta[j]+width).coerceAtMost(prior[j]*maxFactor)
        intervalLow[j]=lo;intervalHigh[j]=hi
        val mealIdx=times.indices.filter{times[it].first>=sessions[j].startMs&&times[it].second<=continuousEnds[j]};val coverage=mealIdx.lastOrNull()?.let{(times[it].second-sessions[j].startMs)/60_000.0}?:0.0
        val expected=(coverage/config.gridMin).toInt().coerceAtLeast(1);val density=mealIdx.size.toDouble()/expected
        val available=buildSet{if(coverage>=30)add(ObservedPhaseV1.START);if(coverage>=90)add(ObservedPhaseV1.LEVEL_MAXIMUM);if(coverage>=180)add(ObservedPhaseV1.LATE_PHASE);if(coverage>=240)add(ObservedPhaseV1.PLATEAU)}
        // Phase claims require signal in the observed insulin-adjusted CGM, not
        // merely a non-zero prior curve (otherwise flat CGM would invent phases).
        // Phase evidence uses a conservative partial residual. If an anchored
        // neighbour can explain the movement, that movement must not prove a
        // phase for this meal merely because the joint optimum shrank the
        // neighbour to satisfy this meal's own amplitude prior.
        val evidenceCurve=mealIdx.map{i->y[i]-bgBasis[i]*max(bg,0.0)-process[i]-x.indices.filter{it!=j}.sumOf{k->x[k][i]*max(beta[k],prior[k])}}
        val excursion=(evidenceCurve.maxOrNull().orZero()-evidenceCurve.minOrNull().orZero())
        val rawExcursion=mealIdx.map{y[it]}.let{it.maxOrNull().orZero()-it.minOrNull().orZero()}
        val phases=if(unidentified||evidenceCurve.size<3||excursion<.20||rawExcursion<.20||evidenceCurve.maxOrNull().orZero()<.20)emptySet() else buildSet{
            val diffs=evidenceCurve.zipWithNext{a,b->b-a};if(ObservedPhaseV1.START in available&&diffs.any{it>.08})add(ObservedPhaseV1.START)
            val peak=evidenceCurve.indices.maxByOrNull{evidenceCurve[it]}?:0;if(ObservedPhaseV1.LEVEL_MAXIMUM in available&&peak<evidenceCurve.lastIndex)add(ObservedPhaseV1.LEVEL_MAXIMUM)
            val late=mealIdx.indexOfFirst{(times[it].first-sessions[j].startMs)/60_000.0>=120};if(ObservedPhaseV1.LATE_PHASE in available&&late>=0&&evidenceCurve.drop(late).let{it.isNotEmpty()&&it.max()-it.min()>.15})add(ObservedPhaseV1.LATE_PHASE)
            val tail=evidenceCurve.takeLast(3);if(ObservedPhaseV1.PLATEAU in available&&tail.size==3&&tail.max()-tail.min()<=.20)add(ObservedPhaseV1.PLATEAU)
        }
        // A high joint residual means the selected insulin/food/background
        // combination did not reproduce this episode. Never turn that mismatch
        // into a confident dish-specific observation: its owner may be today's
        // ISF/timing, carbs, activity, basal/hepatic background, or the sensor.
        val fitMismatch=rmse>max(1.0,sigma.sorted()[sigma.size/2]*2.5)
        val censored=coverage<180||mealIdx.size<6||density<config.minCoverageDensity;val status=when{inputTruncated->AttributionResolutionV1.UNRESOLVED;censored->AttributionResolutionV1.CENSORED;unidentified||fitMismatch->AttributionResolutionV1.UNRESOLVED;else->AttributionResolutionV1.RESOLVED}
        val next=sessions.getOrNull(j+1)?.startMs
        fun area(filter:(Long)->Boolean,basis:DoubleArray=x[j])=times.indices.filter{filter(times[it].first)}.sumOf{basis[it]}.coerceAtLeast(0.0)
        val allArea=area({true}).coerceAtLeast(1e-9);val before=if(next==null)1.0 else area({it<next})/allArea;val after=if(next==null)0.0 else (1-before).coerceIn(0.0,1.0)
        val curve=times.indices.map{i->TimeContributionPointV2(times[i].first,beta[j]*x[j][i],lo*xLate[j][i],hi*xEarly[j][i])}
        MealAllocationV1(sessions[j].sessionId,sessions[j].startMs,sessions[j].endMs,sessions[j].carbsG,
            sessions[j].notes.mapNotNull{it.carbsSource}.distinct().joinToString("+").ifBlank{"unknown"},sessions[j].carbsG*(1-gramUncertainty[j]),sessions[j].carbsG*(1+gramUncertainty[j]),prior[j],beta[j],lo,hi,
            beta[j]/total,lo/(hi+(beta.sum()-beta[j])+process.maxOrNull().orZero()).coerceAtLeast(1e-9),hi/(lo+(beta.sum()-beta[j])+process.minOrNull().orZero()).coerceAtLeast(1e-9),status,phases,available,coverage,sessions[j].notes.map{it.tsMs},
            ((1-rmse/3).coerceIn(.05,1.0)*(1-corr).coerceAtLeast(.08)*(if(fitMismatch).15 else 1.0)),buildList{if(unidentified)add("overlap not identifiable");if(timingScope==MealTimingScopeV1.CLUSTER_ONLY)add("individual timing replaced by aggregate meal-cluster timing");if(timingScope==MealTimingScopeV1.COMPLETED_BEFORE_CLUSTER)add("at least 90% completed before next intake; early contribution locked");if(fitMismatch)add("joint model mismatch; episode excluded from confident food learning");if(inputTruncated)add("input truncated; allocation unresolved");if(censored)add("meal-specific CGM window censored at gap or low density");if(phases.isEmpty()&&available.isNotEmpty())add("phase window available but phase not established");if(gramUncertainty[j]>.2)add("grams provenance is weak");if(kernels.values.any{it.factors.any{f->!f.appliedToMedian}})add("insulin uncertainty propagated");if(process.maxOrNull().orZero()>.5)add("shared process residual retained")},curve,
            PosteriorV1(before,(before-.12).coerceAtLeast(0.0),(before+.12).coerceAtMost(1.0),0,0,asOfMs),PosteriorV1(after,(after-.12).coerceAtLeast(0.0),(after+.12).coerceAtMost(1.0),0,0,asOfMs),priorStrength[j],sessions[j].startMs+(config.sensorLagMedianMin*60_000).toLong(),false,
            timingScope=timingScope,clusterCarbsG=localClusterCarbs)
    }

    val naive=DoubleArray(sessions.size){j->
        val from=sessions[j].startMs+(config.sensorLagMedianMin*60_000).toLong();val until=sessions.getOrNull(j+1)?.startMs?:intendedEnd
        val ids=times.indices.filter{times[it].first in from..until};var num=priorStrength[j]*prior[j];var den=priorStrength[j]
        ids.forEach{i->val w=1/(sigma[i]*sigma[i]);num+=w*x[j][i]*(y[i]-bgBasis[i]*bg);den+=w*x[j][i]*x[j][i]}
        (num/den.coerceAtLeast(1e-9)).coerceIn(0.0,prior[j]*config.mealAmplitudeMaxPrior)
    }
    val transfer=if(sessions.size<2)null else run{
        val last=sessions.lastIndex;val deficit=(0 until last).sumOf{(prior[it]-naive[it]).coerceAtLeast(0.0)};val surplus=(naive[last]-prior[last]).coerceAtLeast(0.0)
        val lateIds=times.indices.filter{times[it].first>=sessions[last].startMs+(config.sensorLagMedianMin*60_000).toLong()}
        val previous=lateIds.sumOf{i->(0 until last).sumOf{j->x[j][i]*beta[j]}};val mealAll=lateIds.sumOf{i->sessions.indices.sumOf{j->x[j][i]*beta[j]}}.coerceAtLeast(1e-9)
        val residualLate=lateIds.sumOf{process[it]};val backgroundLate=lateIds.sumOf{(bgBasis[it]*bg).coerceAtLeast(0.0)};val observedParts=(mealAll+residualLate+backgroundLate).coerceAtLeast(1e-9)
        val frac=(previous/mealAll).coerceIn(0.0,1.0);val unassigned=(residualLate/observedParts).coerceIn(0.0,1.0);val unresolved=allocations.any{it.status!=AttributionResolutionV1.RESOLVED}
        TailTransferAuditV2(deficit>.3&&surplus>.3,deficit,surplus,naive[last],beta[last],PosteriorV1(frac,(frac-.18).coerceAtLeast(0.0),(frac+.18).coerceAtMost(1.0),0,0,asOfMs),PosteriorV1(unassigned,(unassigned-.12).coerceAtLeast(0.0),(unassigned+.12).coerceAtMost(1.0),0,0,asOfMs),if(unresolved)AttributionResolutionV1.UNRESOLVED else AttributionResolutionV1.RESOLVED,"retrospective constrained joint fit; naive windows are diagnostic only")
    }
    val receiptStatus=when{allocations.any{it.status==AttributionResolutionV1.UNRESOLVED}->AttributionResolutionV1.UNRESOLVED;allocations.any{it.status==AttributionResolutionV1.CENSORED}->AttributionResolutionV1.CENSORED;else->AttributionResolutionV1.RESOLVED}
    val bins=times.indices.map{i->val meals=x.indices.sumOf{j->x[j][i]*beta[j]};TimeFitBinV2(times[i].first,y[i],meals,bgBasis[i]*bg,signedProcess[i],pred(i)+errors[i].coerceAtMost(0.0),sigma[i])}
    val clusterTiming=bins.maxOfOrNull{it.fittedMeals}?.takeIf{it>.20}?.let{maximum->
        val supported=bins.filter{it.fittedMeals>=maximum*.10}
        val peak=bins.maxBy{it.fittedMeals}
        ClusterTimingV1(
            onsetMin=(supported.first().tsMs-start)/60_000.0,
            levelPeakMin=(peak.tsMs-start)/60_000.0,
            mainEndMin=(supported.last().tsMs-start)/60_000.0,
        )
    }
    val processCurve=times.indices.map{i->TimeContributionPointV2(times[i].first,signedProcess[i],signedProcess[i]-rmse,signedProcess[i]+rmse)}
    val residualPeak=process.maxOrNull().orZero().coerceAtLeast(0.0);val observed=y.last()-bgBasis.last()*bg
    val hashBody=buildString{append(JOINT_ATTRIBUTION_VERSION_V1).append('|').append(appearance.signature()).append('|').append(asOfMs).append('|').append(globalCs).append('|').append(config);sessions.forEach{append('|').append(it.sessionId).append(':').append(it.notes.joinToString(","){n->val causalAnalysis=n.analysis.takeIf{n.analysisKnownAtMs?.let{known->known<=asOfMs}==true};"${n.tsMs}/${n.estCarbs}/${n.carbsKnownAtMs}/${n.carbsSource}/$causalAnalysis/${n.analysisKnownAtMs?.takeIf{it<=asOfMs}}"})};r.forEach{append("|g:${it.tsMs}:${it.mmol}")};activeBoluses.forEach{append("|b:${it.tsMs}:${it.units}:${it.purpose}:${kernels.getValue(it).provenanceHash}")};append("|fit:").append(beta.joinToString()).append(':').append(bg).append(':').append(process.joinToString()).append(':').append(rmse)}
    val hash=MessageDigest.getInstance("SHA-256").digest(hashBody.toByteArray()).joinToString(""){"%02x".format(it)}
    val signedSummary=signedProcess.average();val signedLow=(signedProcess.minOrNull().orZero()-rmse).coerceAtMost(signedSummary);val signedHigh=(signedProcess.maxOrNull().orZero()+rmse).coerceAtLeast(signedSummary)
    return JointAttributionReceiptV1(JOINT_ATTRIBUTION_VERSION_V1,start,intendedEnd,asOfMs,globalCs,allocations,
        PosteriorV1(residualPeak,(residualPeak-rmse).coerceAtLeast(0.0),residualPeak+rmse,0,0,asOfMs),PosteriorV1(bg,(bg-rmse/3).coerceAtLeast(-config.backgroundAbsMaxMmolPerHour),(bg+rmse/3).coerceAtMost(config.backgroundAbsMaxMmolPerHour),0,0,asOfMs),
        kernels.mapKeys{it.key.tsMs}.mapValues{it.value.provenanceHash},PosteriorV1(observed,observed-rmse,observed+rmse,0,0,asOfMs),receiptStatus,hash,bins,processCurve,transfer,inputTruncated,
        PosteriorV1(signedSummary,signedLow,signedHigh,0,0,asOfMs),clusterTiming)
}

private fun itWeight(n:Annotation,total:Double)=(n.estCarbs?:0.0)/total
private fun Double?.orZero()=this?:0.0
