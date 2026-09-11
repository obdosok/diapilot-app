package com.example.diapilot.data

import android.content.Context
import com.example.diapilot.R
import com.example.diapilot.i18n.UiText
import com.example.diapilot.i18n.localized
import com.example.diapilot.i18n.uiLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

data class FoodCalculationV1(
    val annotationId:Long,val eventTsMs:Long,val dish:String,val model:String,
    val carbsG:Double,val carbsProvenance:String,val proteinG:Double?,val fatG:Double?,val durationMin:Double,
    val globalCsMedian:Double,val globalCsLow:Double,val globalCsHigh:Double,val totalAmplitudeMmol:Double,
    val timingSource:String,val timingTemplateId:String,val onsetMin:Double,val ratePeakMin:Double,
    val effectEndMin:Double,val timingUncertaintyMin:Double,val foodContribution60:Double,val foodContribution180:Double,
    val insulinContribution60:Double,val backgroundContribution60:Double,val isfMedian:Double,val isfLow:Double,
    val isfHigh:Double,val uncertaintySummary:String,val globalCsOnlineEpisodes:Int=0,val globalCsOnlineDays:Int=0,
    val globalCsEvidencePolicy:String="structured evidence known at forecast time only",
    val kineticsSummary:UiText=UiText.res(R.string.hybrid_shadow_kinetics_none),
    val timingScope:String="INDIVIDUAL",
    val clusterCarbsG:Double?=null,
    val clusterMembers:Int=1,
    val clusterReason:String?=null,
    val nextMealAtMs:Long?=null,
    val realisedFractionAtNext:Double?=null,
    val episodeAttribution:EpisodeAttributionExplanationV1?=null,
    /**
     * When half the modelled rise has arrived — what the dialog now shows in
     * place of [ratePeakMin].
     *
     * [ratePeakMin] is the argmax of a two-humped arrival curve and swaps humps:
     * on a 40 g dish it reads 55 -> 50 -> 154 -> 149 min as fat rises 0 -> 40 g.
     * This is a level crossing and is monotone, 73 -> 81 -> 103 -> 149.
     *
     * APPENDED LAST and defaulted, so positional callers keep compiling — the
     * first attempt put it beside `ratePeakMin` and broke an integration test's
     * argument list. The same convention `MealObservation` states for its own
     * late additions.
     */
    val halfArrivalMin:Double=ratePeakMin,
)

/** Retrospective explanation; never a live-forecast input. */
data class EpisodeAttributionExplanationV1(
    val receiptVersion:String,val modelForecast:String,val allocatedMealContribution:String,
    val phases:String,val overlap:String,val allocation:String,val resolution:String,val episodeKernel:String,
    val kernelDifference:String,val confidence:String,val caveats:String,
    val causalProvenance:String="",val diagnosticProvenance:String="",
    val timeResolvedCurve:String="",val tailTransfer:String="",
    val compactSummary:String="",val compactFinding:String="",
    /** Unclosed meal residual in mmol — a signal that "something unlogged
     *  was acting". Appended LAST: positional constructors live in tests. */
    val unloggedResidualMmol:Double=0.0,
    /** The neighbouring meals could not be split — the card tints the line. */
    val unresolved:Boolean=false,
)

object FoodCalculationRegistry {
    private const val DISK_SCHEMA = 2
    private const val DISK_FILE = "stage10_history_receipts.json"
    data class EpisodeSnapshot(
        val generation:Long=0,
        val receipts:Map<Long,EpisodeAttributionExplanationV1> = emptyMap(),
        val complete:Boolean=true,
        val processedClusters:Int=0,
        val totalClusters:Int=0,
        val budgetLimited:Boolean=false,
        val fullRefreshAtMs:Long=0,
        val nextOffset:Int=0,
        val lastAttemptAtMs:Long=0,
    )
    @Volatile private var baseValues:Map<Long,FoodCalculationV1> = emptyMap()
    @Volatile private var values:Map<Long,FoodCalculationV1> = emptyMap()
    private val mutableEpisode=MutableStateFlow(EpisodeSnapshot())
    @Volatile private var expectedGeneration:Long=0
    @Volatile private var sourceKey:String?=null
    @Volatile private var restoreAttemptedKey:String?=null
    @Volatile private var closedEligibilityBlocked:Set<Long> = emptySet()
    @Volatile private var requestedContinuationBudgetMs:Long=0
    val episodeFlow=mutableEpisode.asStateFlow()
    private val episode get()=mutableEpisode.value.receipts
    private fun decorated(id:Long,v:FoodCalculationV1):FoodCalculationV1 {
        val e=episode[id]?:return v
        return v.copy(episodeAttribution=e)
    }

    /** The uncertainty line of the "How it was calculated" dialog: the live
     *  summary, plus the Stage 10 receipt side by side when there is one. */
    fun uncertaintyText(context:Context,v:FoodCalculationV1):String {
        val e=v.episodeAttribution?:return v.uncertaintySummary
        return context.localized().getString(
            R.string.food_calculation_uncertainty_stage10,
            v.uncertaintySummary,e.modelForecast,e.allocatedMealContribution,e.phases,e.overlap,e.allocation,
            e.resolution,e.episodeKernel,e.kernelDifference,e.timeResolvedCurve,e.tailTransfer,e.confidence,e.caveats,
        )
    }

    /**
     * The cache key of the Stage 10 receipts. The receipts are display text
     * built in the app language, so the language is part of the key: after a
     * switch the key no longer matches and TwinCache rebuilds them.
     */
    fun sourceKey(store:SqliteCollectorStore,context:Context,nowMs:Long):String =
        store.stage10ReceiptCacheKey(Stage9EpisodeRuntime.CACHE_VERSION,nowMs)+"|ui="+context.uiLanguage()

    /** The language the receipts in memory were written in (null: none yet). */
    @Volatile private var receiptsLanguage:String?=null

    /** True when the receipts in memory are in another language than the UI's
     *  — the next pass must rebuild the whole history, not only the hot tail. */
    fun needsLanguageRefresh(context:Context):Boolean =
        mutableEpisode.value.receipts.isNotEmpty()&&receiptsLanguage!=context.uiLanguage()
    @Synchronized fun update(next:Map<Long,FoodCalculationV1>){baseValues=next.toMap();values=baseValues.mapValues{(id,v)->decorated(id,v)}}
    @Synchronized fun updateEpisodeAttribution(
        next:Map<Long,EpisodeAttributionExplanationV1>,generation:Long=maxOf(expectedGeneration,mutableEpisode.value.generation)+1,
        complete:Boolean=true,processedClusters:Int=0,totalClusters:Int=0,budgetLimited:Boolean=false,
    ):Boolean {
        if(generation<expectedGeneration||generation<mutableEpisode.value.generation)return false
        val receipts=if(complete)next.toMap() else mutableEpisode.value.receipts+next
        mutableEpisode.value=EpisodeSnapshot(generation,receipts,complete,processedClusters,totalClusters,budgetLimited)
        values=baseValues.mapValues{(id,v)->decorated(id,v)}
        return true
    }

    /** Merge a bounded recalculation window without throwing away stable history. */
    @Synchronized fun updateEpisodeAttributionWindow(
        next:Map<Long,EpisodeAttributionExplanationV1>,replaceIds:Set<Long>,generation:Long,
        runComplete:Boolean,processedClusters:Int,totalClusters:Int,budgetLimited:Boolean,
        fullRefresh:Boolean,nowMs:Long,
        nextOffset:Int=0,
        /** The UI language the [next] receipts were built in. */
        language:String?=null,
    ):Boolean {
        if(generation<expectedGeneration||generation<mutableEpisode.value.generation)return false
        val previous=mutableEpisode.value
        // A full rebuild replaces every receipt, so from then on they share one language.
        if(fullRefresh&&runComplete||previous.receipts.isEmpty())receiptsLanguage=language
        val receipts=when {
            fullRefresh&&runComplete -> next.toMap()
            runComplete -> previous.receipts.filterKeys{it !in replaceIds}+next
            else -> previous.receipts+next
        }
        mutableEpisode.value=EpisodeSnapshot(
            generation,receipts,runComplete,processedClusters,totalClusters,budgetLimited,
            if(fullRefresh&&runComplete)nowMs else previous.fullRefreshAtMs,
            if(runComplete)0 else nextOffset,nowMs,
        )
        values=baseValues.mapValues{(id,v)->decorated(id,v)}
        return true
    }

    /**
     * Restore the retrospective sidecar without re-running deconvolution on a
     * cold app start. A source-key mismatch is intentionally restored as a
     * stable baseline: TwinCache then refreshes only the hot tail and merges it.
     */
    @Synchronized fun restore(context:Context,key:String):Boolean {
        if(mutableEpisode.value.receipts.isNotEmpty())return sourceKey==key
        if(restoreAttemptedKey==key)return false
        restoreAttemptedKey=key
        val root=runCatching{JSONObject(File(context.filesDir,DISK_FILE).readText())}.getOrNull()?:return false
        if(root.optInt("schema")!=DISK_SCHEMA||root.optString("algorithmVersion")!=Stage9EpisodeRuntime.CACHE_VERSION)return false
        // Receipts are display text: never restore them into another language.
        if(root.optString("language")!=context.uiLanguage())return false
        val rows=root.optJSONObject("receipts")?:return false
        val receipts=buildMap<Long,EpisodeAttributionExplanationV1>{
            rows.keys().forEach{rawId->
                val id=rawId.toLongOrNull()?:return@forEach
                val o=rows.optJSONObject(rawId)?:return@forEach
                put(id,explanationFromJson(o))
            }
        }
        if(receipts.isEmpty())return false
        mutableEpisode.value=EpisodeSnapshot(
            generation=0,receipts=receipts,complete=root.optBoolean("complete",true),
            processedClusters=root.optInt("processedClusters"),totalClusters=root.optInt("totalClusters"),
            budgetLimited=root.optBoolean("budgetLimited"),
            fullRefreshAtMs=root.optLong("fullRefreshAtMs"),
            nextOffset=root.optInt("nextOffset"),lastAttemptAtMs=root.optLong("lastAttemptAtMs"),
        )
        sourceKey=root.optString("sourceKey")
        receiptsLanguage=root.optString("language")
        values=baseValues.mapValues{(id,v)->decorated(id,v)}
        return true
    }

    @Synchronized fun persist(context:Context,key:String):Boolean {
        val snapshot=mutableEpisode.value
        if(snapshot.receipts.isEmpty())return false
        return persistSnapshot(context,key,snapshot)
    }

    private fun persistSnapshot(context:Context,key:String,snapshot:EpisodeSnapshot):Boolean {
        val root=JSONObject().put("schema",DISK_SCHEMA).put("algorithmVersion",Stage9EpisodeRuntime.CACHE_VERSION).put("sourceKey",key)
            .put("complete",snapshot.complete).put("processedClusters",snapshot.processedClusters)
            .put("totalClusters",snapshot.totalClusters).put("budgetLimited",snapshot.budgetLimited)
            .put("fullRefreshAtMs",snapshot.fullRefreshAtMs)
            .put("nextOffset",snapshot.nextOffset).put("lastAttemptAtMs",snapshot.lastAttemptAtMs)
            .put("language",receiptsLanguage?:context.uiLanguage())
            .put("receipts",JSONObject().apply{snapshot.receipts.forEach{(id,e)->put(id.toString(),explanationToJson(e))}})
        val target=File(context.filesDir,DISK_FILE);val tmp=File(context.filesDir,"$DISK_FILE.tmp")
        return runCatching{
            tmp.writeText(root.toString());if(target.exists()&&!target.delete())error("cannot replace Stage10 cache")
            if(!tmp.renameTo(target))error("cannot publish Stage10 cache")
            sourceKey=key;restoreAttemptedKey=key;true
        }.getOrElse{tmp.delete();false}
    }

    fun isCurrent(key:String)=sourceKey==key&&mutableEpisode.value.complete&&mutableEpisode.value.receipts.isNotEmpty()
    fun needsFullRefresh(nowMs:Long,maxAgeMs:Long=24*60*60_000L):Boolean =
        mutableEpisode.value.fullRefreshAtMs<=0||nowMs-mutableEpisode.value.fullRefreshAtMs>=maxAgeMs
    fun continuationOffset():Int=mutableEpisode.value.nextOffset

    /**
     * One-shot larger budget for a single pass.
     *
     * NO LONGER USER-TRIGGERED. This existed for the "Continue calculation" button,
     * which was removed once `TwinCache` learned to slice the backlog until it
     * is done (A-37). Kept because `Stage9PerformanceContractTest` pins the
     * budget's effect, and because a caller that legitimately needs one long
     * pass should raise the budget rather than invent a second loop — but if
     * you are looking for the button, there isn't one any more.
     */
    @Synchronized fun requestContinuationBudget(budgetMs:Long=8_000L) {
        requestedContinuationBudgetMs=maxOf(requestedContinuationBudgetMs,budgetMs.coerceIn(2_000L,15_000L))
    }
    @Synchronized fun takeContinuationBudget(defaultMs:Long=2_000L):Long {
        val result=maxOf(defaultMs,requestedContinuationBudgetMs)
        requestedContinuationBudgetMs=0
        return result
    }

    /** Closed-episode learning gates were published after the Stage10 cache.
     * Remove newly unsafe receipts immediately and make the next background
     * Twin pass rebuild them. Live forecast state is deliberately untouched. */
    @Synchronized fun invalidateClosedEpisodeEligibility(ids:Set<Long>,proposedGeneration:Long,context:Context?=null):Long {
        val previous=mutableEpisode.value
        // ClosedEpisodeRuntime republishes the complete blocked set on every
        // maintenance pass. Once the same set has already been enforced and
        // none of those receipts reappeared, another generation/disk write is
        // pure churn. A changed set still reserves a barrier so an older
        // in-flight Stage10 run cannot publish against the new policy.
        if(ids==closedEligibilityBlocked&&previous.receipts.keys.none{it in ids})
            return maxOf(expectedGeneration,previous.generation)
        val barrier=maxOf(proposedGeneration,expectedGeneration+1,mutableEpisode.value.generation+1)
        expectedGeneration=barrier
        closedEligibilityBlocked=ids.toSet()
        mutableEpisode.value=previous.copy(generation=barrier,receipts=previous.receipts.filterKeys{it !in ids},complete=false)
        sourceKey=null
        values=baseValues.mapValues{(id,v)->decorated(id,v)}
        // Even an empty/tombstoned cache is atomically written, otherwise a
        // cold start could restore the pre-invalidation disk receipt.
        context?.let{persistSnapshot(it,"closed-eligibility-invalidated:$barrier",mutableEpisode.value)}
        return barrier
    }

    private fun explanationToJson(e:EpisodeAttributionExplanationV1)=JSONObject()
        .put("receiptVersion",e.receiptVersion).put("modelForecast",e.modelForecast)
        .put("allocatedMealContribution",e.allocatedMealContribution).put("phases",e.phases)
        .put("overlap",e.overlap).put("allocation",e.allocation).put("resolution",e.resolution)
        .put("episodeKernel",e.episodeKernel).put("kernelDifference",e.kernelDifference)
        .put("confidence",e.confidence).put("caveats",e.caveats)
        .put("causalProvenance",e.causalProvenance).put("diagnosticProvenance",e.diagnosticProvenance)
        .put("unloggedResidualMmol",e.unloggedResidualMmol)
        .put("timeResolvedCurve",e.timeResolvedCurve).put("tailTransfer",e.tailTransfer)
        .put("compactSummary",e.compactSummary).put("compactFinding",e.compactFinding)
        .put("unresolved",e.unresolved)

    private fun explanationFromJson(o:JSONObject)=EpisodeAttributionExplanationV1(
        receiptVersion=o.optString("receiptVersion"),modelForecast=o.optString("modelForecast"),
        allocatedMealContribution=o.optString("allocatedMealContribution"),phases=o.optString("phases"),
        overlap=o.optString("overlap"),allocation=o.optString("allocation"),resolution=o.optString("resolution"),
        episodeKernel=o.optString("episodeKernel"),kernelDifference=o.optString("kernelDifference"),
        confidence=o.optString("confidence"),caveats=o.optString("caveats"),
        causalProvenance=o.optString("causalProvenance"),diagnosticProvenance=o.optString("diagnosticProvenance"),
        unloggedResidualMmol=o.optDouble("unloggedResidualMmol",0.0),
        timeResolvedCurve=o.optString("timeResolvedCurve"),tailTransfer=o.optString("tailTransfer"),
        compactSummary=o.optString("compactSummary"),compactFinding=o.optString("compactFinding"),
        unresolved=o.optBoolean("unresolved"),
    )
    @Synchronized fun expectEpisodeGeneration(generation:Long){if(generation>expectedGeneration)expectedGeneration=generation}
    @Synchronized internal fun resetEpisodeStateForTest(){expectedGeneration=0;sourceKey=null;restoreAttemptedKey=null;closedEligibilityBlocked=emptySet();requestedContinuationBudgetMs=0;mutableEpisode.value=EpisodeSnapshot();values=baseValues}
    fun get(annotationId:Long)=values[annotationId]
    /** History can read old notes even when HybridShadow has no current-window base row. */
    fun getEpisode(annotationId:Long)=episode[annotationId]
    fun latest()=values.values.maxByOrNull{it.eventTsMs}
}
