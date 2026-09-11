package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.collector.*
import org.junit.Before
import com.diapilot.core.physio.EvidenceStatus
import com.diapilot.core.physio.EffectPayloadV1
import com.diapilot.core.physio.ParameterTargetV1
import com.diapilot.core.physio.PromotionStageV1
import com.diapilot.core.physio.PromotionStateV1
import com.diapilot.core.hybrid.HybridFoodEvent
import com.diapilot.core.twin.FORECAST_ALGO_VERSION_FP_SHADOW
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Stage8FoodDynamicsIntegrationTest {
    /** Data below is laid out relative to a fixed synthetic era start. */
    @Before fun fixFoodEra() { TestFoodEra.install() }

    @Test fun `stage10 receipts survive cold registry restart as stable baseline for a new source key`() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        java.io.File(context.filesDir,"stage10_history_receipts.json").delete()
        FoodCalculationRegistry.resetEpisodeStateForTest()
        val e=EpisodeAttributionExplanationV1("v-cache","p","a","f","o","x","r","k","d","low","c")
        FoodCalculationRegistry.updateEpisodeAttribution(mapOf(77L to e),generation=1,processedClusters=3,totalClusters=3)
        assertTrue(FoodCalculationRegistry.persist(context,"source-A"))
        FoodCalculationRegistry.resetEpisodeStateForTest()
        assertTrue(FoodCalculationRegistry.restore(context,"source-B"))
        assertEquals(e,FoodCalculationRegistry.getEpisode(77L))
        assertFalse(FoodCalculationRegistry.isCurrent("source-B"))
        assertEquals(3,FoodCalculationRegistry.episodeFlow.value.processedClusters)
        java.io.File(context.filesDir,"stage10_history_receipts.json").delete()
        FoodCalculationRegistry.resetEpisodeStateForTest()
    }

    @Test fun `pre-stage9 snapshot is rejected as stale`() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        java.io.File(context.filesDir,"twin_snapshot.json").writeText("""{"v":7}""")
        assertNull(TwinSnapshot.load(context))
    }

    @Test fun `comparison snapshot identity ignores model owned food timing`() {
        val causal=HybridFoodEvent(
            tsMs=1_000,carbsG=22.0,text="smoothie",recipeKey="smoothie-orange-parsley-v1",
            physicalForm="liquid_simple",contextIds=setOf("food:1"),
        )
        // The learned template that used to be added here went with the layer;
        // the offsets remain model-owned and must still not enter
        // the signature.
        val physio=causal.copy(onsetOffsetMin=3.0,peakOffsetMin=4.0,tailOffsetMin=5.0)
        assertEquals(
            causalFoodSnapshotSignature(listOf(causal)),
            causalFoodSnapshotSignature(listOf(physio)),
        )
    }

    @Test fun `bundled food artifact documents exact runtime contract`() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val json=JSONObject(context.assets.open("models/food_dynamics_physio_v1.json").bufferedReader().readText())
        val prior=PhysioRuntime.foodDynamicsGlobalPriorV1()
        assertEquals(prior.median,json.getJSONObject("global_cs").getDouble("median_mmol_per_l_per_g"),0.0)
        assertTrue(FORECAST_ALGO_VERSION_FP_SHADOW.contains("measured-insulin-cdf"))
        assertFalse("recipe timing is rolled back until an explicit recipe identity exists",FORECAST_ALGO_VERSION_FP_SHADOW.contains("recipe-timing"))
    }

    @Test fun `history receipt uses structured features and never dish title`() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val model=context.assets.open("models/person_model_v11_runtime.json").use{HybridPersonModelJson.read(it)}
        HybridShadowRegistry.install(model,"stage8-history-receipt")
        val artifact=requireNotNull(PhysioRuntime.artifact())
        val kinetics="KINETICS_V2: fast=1;medium=0;slow=0;form=LIQUID;confidence=1;source=test;alcohol=false"
        val smoothie=requireNotNull(HybridRuntimeMetrics.foodReadoutForModel(
            artifact.personModelAt(12.0),"arbitrary title",22.0,kinetics,macroTiming=artifact.macroTiming,physioArtifact=artifact,
        ))
        assertEquals(22.0*.165,smoothie.amplitudeMmol,1e-9)
        assertEquals("structured-feature-mixture-v2",smoothie.timingSource)
        val renamed=requireNotNull(HybridRuntimeMetrics.foodReadoutForModel(
            artifact.personModelAt(12.0),"completely different words",22.0,kinetics,macroTiming=artifact.macroTiming,physioArtifact=artifact,
        ))
        assertEquals(smoothie.onsetMin,renamed.onsetMin)
        assertEquals(smoothie.peakMin,renamed.peakMin)
        assertEquals(smoothie.durationMin,renamed.durationMin)

        val generic=requireNotNull(HybridRuntimeMetrics.foodReadoutForModel(
            artifact.personModelAt(12.0),"generic meal",20.0,null,macroTiming=artifact.macroTiming,physioArtifact=artifact,
        ))
        assertTrue(smoothie.durationMin<generic.durationMin)
    }

    @Test fun `batched history receipts are identical to per-row receipts`() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val model=context.assets.open("models/person_model_v11_runtime.json").use{HybridPersonModelJson.read(it)}
        HybridShadowRegistry.install(model,"history-batch-equivalence")
        val artifact=requireNotNull(PhysioRuntime.artifact())
        val kinetics="KINETICS_V2: fast=.3;medium=.5;slow=.2;form=SOLID;confidence=.8;source=test;alcohol=false"
        val notes=listOf(
            Annotation(1_000,"food","first",estCarbs=20.0,analysis=kinetics,id=11),
            Annotation(2_000,"food","second",estCarbs=35.0,analysis=null,id=12),
        )
        val batched=HybridRuntimeMetrics.foodReadoutsForModel(
            artifact.personModelAt(12.0),notes,macroTiming=artifact.macroTiming,physioArtifact=artifact,
        )
        notes.forEach { note ->
            val single=requireNotNull(HybridRuntimeMetrics.foodReadoutForModel(
                artifact.personModelAt(12.0),note.content,note.estCarbs,note.analysis,note.tsMs,
                macroTiming=artifact.macroTiming,physioArtifact=artifact,
            ))
            // EQUAL ON WHAT BOTH CAN KNOW. The batched path
            // also reports what the shared gastric pipe did to each dish, and
            // the single-row path structurally cannot: it is handed one note
            // with no neighbours, so it has no meal to place the dish in.
            // These two notes are a second apart, that is, one meal — batching is
            // what makes that visible, and equality here would mean the cluster
            // curve had been dropped.
            // modelCurveMmol joins the cluster-adjusted set: the
            // batched path samples the meal-applied curve, which one note with
            // no neighbours structurally cannot know.
            assertEquals(single,batched[note.id]?.copy(
                clusterMembers=1,clusterHalfArrivalMin=null,
                clusterDurationMin=null,clusterPriorRealised=null,
                modelCurveMmol=single.modelCurveMmol,
            ))
            assertEquals(2,batched[note.id]?.clusterMembers)
            assertEquals(1,single.clusterMembers)
        }
    }

    @Test fun `history receipt exposes partial live insulin deconvolution`() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val model=context.assets.open("models/person_model_v11_runtime.json").use{HybridPersonModelJson.read(it)}
        HybridShadowRegistry.install(model,"stage8-live-observation")
        val artifact=requireNotNull(PhysioRuntime.artifact())
        val ts=123_456_789L
        HybridRuntimeMetrics.installLiveFoodObservations(listOf(
            com.diapilot.core.analysis.MealObservation(
                fingerprint=com.diapilot.core.analysis.mealFingerprint(listOf("test" to 20.0)),
                ttpMin=72.0,tailRise=0.0,peakRise=4.8,onsetMs=ts,carbGrams=20.0,
                peakObserved=false,tailObserved=false,onsetLagMin=14.0,confidence=.8,
            ),
        ))
        val row=requireNotNull(HybridRuntimeMetrics.foodReadoutForModel(
            artifact.personModelAt(12.0),"test",20.0,null,tsMs=ts,macroTiming=artifact.macroTiming,physioArtifact=artifact,
        ))
        val observed=requireNotNull(row.observed)
        assertEquals(14,observed.onsetMin)
        assertEquals(72,observed.levelMaxMin)
        assertTrue(observed.levelMaxCensored)
        assertNull(observed.plateauMin)
        assertTrue(observed.insulinSubtracted)
        assertEquals(.8,observed.confidence!!,0.0)
    }

    @Test fun `pre strict era observational cs cannot replace stage8 live anchor`() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val model=context.assets.open("models/person_model_v11_runtime.json").use{HybridPersonModelJson.read(it)}
        HybridShadowRegistry.install(model,"stage8-cs-gate")
        val name="stage8-cs-gate-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context,name).use{store->
            store.writableDatabase.execSQL(
                "INSERT INTO physio_global_cs_state VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(1,TestFoodEra.ERA.startMs+1_000,.290666,.20,.34,20,10,EvidenceStatus.PRELIMINARY.name,"current-hash","observational causal readiness only; learner=curve-aware-v4-food-era-floor",0),
            )
            val artifact=requireNotNull(PhysioRuntime.artifact(store,TestFoodEra.ERA.startMs+2_000))
            assertEquals(.165,artifact.globalCs.median,0.0)
            assertEquals(0,artifact.globalCs.identifyingEpisodes)
        }
        context.deleteDatabase(name)
    }

    @Test fun `published calculation is immutable read projection`() {
        val row=FoodCalculationV1(7,1,"redacted","PHYSIO",22.0,"STANDARD_RECIPE_WEIGHT",0.0,0.0,0.0,.165,.18,.34,5.456,"personal","smoothie",10.0,25.0,55.0,10.0,5.0,5.4,-2.0,.1,2.2,1.7,2.8,"channels",2,2,"strict known-at")
        FoodCalculationRegistry.update(mapOf(7L to row))
        assertEquals(row,FoodCalculationRegistry.get(7))
        assertEquals(row,FoodCalculationRegistry.latest())
        assertEquals(2,FoodCalculationRegistry.latest()?.globalCsOnlineEpisodes)
        assertFalse(FoodCalculationRegistry.latest()!!.globalCsEvidencePolicy.contains("retrospective"))
    }

    @Test fun `stage10 attribution merges into existing history receipt without replacing live forecast`() {
        val row=FoodCalculationV1(9,2,"coldnik","PHYSIO",35.0,"manual",null,null,0.0,.165,.20,.30,8.68,"class","slow",15.0,70.0,260.0,25.0,3.0,7.0,-1.0,.2,2.5,2.0,3.0,"base uncertainty")
        FoodCalculationRegistry.update(mapOf(9L to row))
        val explanation=EpisodeAttributionExplanationV1(
            "joint-meal-attribution-v1","8.7 mmol/L","5.0–7.0 mmol/L","start, late phase",
            "ice cream +60; residual 0.8","best 5.2; possible 2.0–7.0","unresolved",
            "episode-kernel-v1 knownAt","ISF 2.4 vs global 2.5","low","grams and sensor",
        )
        FoodCalculationRegistry.updateEpisodeAttribution(mapOf(9L to explanation))
        val merged=requireNotNull(FoodCalculationRegistry.get(9))
        assertEquals("PHYSIO",merged.model) // live forecast fields are untouched
        assertEquals(8.68,merged.totalAmplitudeMmol,0.0)
        assertEquals(explanation,merged.episodeAttribution)
        // The receipt is shown beside the live summary in the dialog's uncertainty line.
        val shown=FoodCalculationRegistry.uncertaintyText(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(),merged)
        assertTrue(shown.contains("base uncertainty"))
        assertTrue(shown.contains("unresolved"))
        assertTrue(shown.contains("owner = legacy"))
        FoodCalculationRegistry.updateEpisodeAttribution(emptyMap())
    }

    @Test fun `old History note exposes stage10 receipt without live-window base row`() {
        FoodCalculationRegistry.update(emptyMap())
        val e=EpisodeAttributionExplanationV1("v","прогноз","этому приёму отнесено 2–5","окно доступно, фаза не установлена","сосед +60","20–50%","неразрешимо","causal prior","только day state","низкая","датчик")
        FoodCalculationRegistry.updateEpisodeAttribution(mapOf(99L to e))
        assertNull(FoodCalculationRegistry.get(99L))
        assertEquals(e,FoodCalculationRegistry.getEpisode(99L))
        FoodCalculationRegistry.updateEpisodeAttribution(emptyMap())
    }

    @Test fun `stage10 failure cannot prevent forecast model publication`() {
        var published:String?=null
        val model=TwinCache.publishThenScheduleStage9("forecast-model",{published=it}){_->throw OutOfMemoryError("synthetic sidecar failure")}
        assertEquals("forecast-model",model)
        assertEquals("forecast-model",published)
    }

    @Test fun `time resolved History receipt keeps per meal curve and Russian transfer text`() {
        FoodCalculationRegistry.update(emptyMap())
        val e=EpisodeAttributionExplanationV1("time-resolved-joint-attribution-v2","5,0 ммоль/л","этому приёму отнесено 4–6","старт установлен","сосед +60","40–60%","неразрешимо","causal kernel","day state","низкая","датчик",
            timeResolvedCurve="Временной вклад: старт 10 мин; максимум 4,8; после соседа 42%.",
            tailTransfer="У ранних приёмов дефицит 1,2, у последнего избыток 1,0; вероятен перенос хвоста.")
        FoodCalculationRegistry.updateEpisodeAttribution(mapOf(321L to e))
        assertEquals(e,FoodCalculationRegistry.getEpisode(321L))
        assertTrue(e.timeResolvedCurve.contains("после соседа"))
        assertTrue(e.tailTransfer.contains("перенос хвоста"))
        FoodCalculationRegistry.updateEpisodeAttribution(emptyMap())
    }

    @Test fun `expired sidecar budget returns before running a cluster`() {
        val t=1_700_000_000_000L
        val note=com.diapilot.core.collector.Annotation(t,"food","meal",id=1,estCarbs=20.0,carbsSource="label",carbsKnownAtMs=t)
        val readings=(0..20).map{com.diapilot.core.collector.GlucosePoint(t+it*5*60_000L,5.5)}
        val started=System.nanoTime()
        val out=Stage9EpisodeRuntime.build(listOf(note),readings,emptyList(),emptyList(),t,t+100*60_000L,budgetMs=-1,context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>())
        assertTrue(out.isEmpty())
        assertTrue((System.nanoTime()-started)/1_000_000<500)
    }

    @Test fun `distributed beer session is displayed once with both portions`() {
        val t=1_700_100_000_000L
        val kinetics="KINETICS_V2: fast=0.3;medium=0.6;slow=0.1;form=LIQUID;confidence=0.8;source=test;alcohol=true"
        val notes=listOf(
            com.diapilot.core.collector.Annotation(t,"food","one",id=701,estCarbs=12.0,carbsSource="label",carbsKnownAtMs=t,analysis=kinetics,analysisKnownAtMs=t),
            com.diapilot.core.collector.Annotation(t+110*60_000L,"food","two",id=702,estCarbs=12.0,carbsSource="label",carbsKnownAtMs=t+110*60_000L,analysis=kinetics,analysisKnownAtMs=t+110*60_000L),
        )
        val rr=(-1..72).map{i->com.diapilot.core.collector.GlucosePoint(t+i*5*60_000L,5.5+if(i>0)i.coerceAtMost(30)/30.0*3 else 0.0)}
        val out=Stage9EpisodeRuntime.build(notes,rr,emptyList(),emptyList(),t,t+360*60_000L,context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>())
        assertEquals(setOf(701L),out.keys)
        assertTrue(out.getValue(701).caveats.contains("12.0 g"))
        assertTrue(out.getValue(701).caveats.count{it==';'}>=2)
    }

    @Test fun `registry is observable and rejects stale generation`() {
        FoodCalculationRegistry.resetEpisodeStateForTest()
        val e=EpisodeAttributionExplanationV1("v","p","a","f","o","x","r","k","d","low","c")
        val base=FoodCalculationRegistry.episodeFlow.value.generation+10
        FoodCalculationRegistry.expectEpisodeGeneration(base)
        assertFalse(FoodCalculationRegistry.updateEpisodeAttribution(mapOf(1L to e),base-1))
        assertTrue(FoodCalculationRegistry.updateEpisodeAttribution(mapOf(2L to e),base))
        assertEquals(setOf(2L),FoodCalculationRegistry.episodeFlow.value.receipts.keys)
    }

    @Test fun `hot window replaces recent receipts and preserves stable history`() {
        FoodCalculationRegistry.resetEpisodeStateForTest()
        val e=EpisodeAttributionExplanationV1("v","p","a","f","o","x","r","k","d","low","c")
        FoodCalculationRegistry.updateEpisodeAttribution(mapOf(1L to e,2L to e),generation=1)
        assertTrue(FoodCalculationRegistry.updateEpisodeAttributionWindow(
            next=mapOf(2L to e.copy(compactSummary="fresh")),replaceIds=setOf(2L,3L),generation=2,
            runComplete=true,processedClusters=1,totalClusters=1,budgetLimited=false,
            fullRefresh=false,nowMs=10_000L,
        ))
        val snapshot=FoodCalculationRegistry.episodeFlow.value
        assertEquals(setOf(1L,2L),snapshot.receipts.keys)
        assertEquals("fresh",snapshot.receipts.getValue(2L).compactSummary)
        assertEquals(0L,snapshot.fullRefreshAtMs)
    }

    @Test fun `complete full refresh advances daily refresh clock`() {
        FoodCalculationRegistry.resetEpisodeStateForTest()
        val e=EpisodeAttributionExplanationV1("v","p","a","f","o","x","r","k","d","low","c")
        val now=100_000_000L
        FoodCalculationRegistry.updateEpisodeAttributionWindow(
            mapOf(7L to e),setOf(7L),generation=1,runComplete=true,processedClusters=1,totalClusters=1,
            budgetLimited=false,fullRefresh=true,nowMs=now,
        )
        assertFalse(FoodCalculationRegistry.needsFullRefresh(now+23*60*60_000L))
        assertTrue(FoodCalculationRegistry.needsFullRefresh(now+25*60*60_000L))
    }

    @Test fun `A B C sidecar race publishes newest generation only`() {
        FoodCalculationRegistry.resetEpisodeStateForTest()
        val started=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val done=java.util.concurrent.CountDownLatch(1)
        val e=EpisodeAttributionExplanationV1("v","p","a","f","o","x","r","k","d","low","c")
        TwinCache.scheduleStage9FailOpen{g->started.countDown();release.await();FoodCalculationRegistry.updateEpisodeAttribution(mapOf(101L to e),g)}
        assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS))
        TwinCache.scheduleStage9FailOpen{g->FoodCalculationRegistry.updateEpisodeAttribution(mapOf(102L to e),g)}
        TwinCache.scheduleStage9FailOpen{g->FoodCalculationRegistry.updateEpisodeAttribution(mapOf(103L to e),g);done.countDown()}
        release.countDown();assertTrue(done.await(3,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf(103L),FoodCalculationRegistry.episodeFlow.value.receipts.keys)
    }

    @Test fun `closed eligibility invalidation is a generation and disk barrier`() {
        FoodCalculationRegistry.resetEpisodeStateForTest()
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val disk=java.io.File(context.filesDir,"stage10_history_receipts.json");disk.delete()
        val e=EpisodeAttributionExplanationV1("v","p","a","f","o","x","r","k","d","low","c")
        assertTrue(FoodCalculationRegistry.updateEpisodeAttribution(mapOf(1L to e,2L to e),generation=10))
        // A was already running with generation 11 when the closed-episode
        // gate invalidated food 1 and reserved a strictly newer generation.
        FoodCalculationRegistry.expectEpisodeGeneration(11)
        val barrier=FoodCalculationRegistry.invalidateClosedEpisodeEligibility(setOf(1L),12,context)
        assertTrue(barrier>11)
        assertFalse(FoodCalculationRegistry.updateEpisodeAttribution(mapOf(1L to e),generation=11))
        assertEquals(setOf(2L),FoodCalculationRegistry.episodeFlow.value.receipts.keys)
        val persisted=org.json.JSONObject(disk.readText()).getJSONObject("receipts")
        assertFalse(persisted.has("1"));assertTrue(persisted.has("2"))
        val unchangedText=disk.readText();val unchangedGeneration=FoodCalculationRegistry.episodeFlow.value.generation
        val repeated=FoodCalculationRegistry.invalidateClosedEpisodeEligibility(setOf(1L),barrier+1,context)
        assertEquals("identical enforced set must not bump generation",unchangedGeneration,repeated)
        assertEquals(unchangedGeneration,FoodCalculationRegistry.episodeFlow.value.generation)
        assertEquals("identical enforced set must not rewrite disk",unchangedText,disk.readText())
        val twinFirst=TwinCache.invalidateStage10Eligibility(context,setOf(1L))
        val twinSecond=TwinCache.invalidateStage10Eligibility(context,setOf(1L))
        assertEquals("TwinCache must not pre-increment for an idempotent invalidation",twinFirst,twinSecond)
        assertEquals(unchangedText,disk.readText())

        // Relaxing the complete policy is also a real barrier. It must force
        // a rebuild once, while repeated empty -> empty remains idempotent.
        val cleared=TwinCache.invalidateStage10Eligibility(context,emptySet())
        assertTrue(cleared>twinSecond)
        assertFalse(FoodCalculationRegistry.episodeFlow.value.complete)
        val clearedAgain=TwinCache.invalidateStage10Eligibility(context,emptySet())
        assertEquals(cleared,clearedAgain)

        // A changed policy still creates a newer barrier and removes the newly
        // blocked receipt, so an already-running generation cannot restore it.
        val changed=FoodCalculationRegistry.invalidateClosedEpisodeEligibility(setOf(1L,2L),barrier+2,context)
        assertTrue(changed>barrier)
        assertTrue(FoodCalculationRegistry.episodeFlow.value.receipts.isEmpty())
        assertFalse(FoodCalculationRegistry.updateEpisodeAttribution(mapOf(2L to e),generation=barrier+1))
        disk.delete()
    }
}
