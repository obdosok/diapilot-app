package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.collector.*
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.diapilot.core.twin.*
import com.diapilot.core.physio.ForecastArmSelection
import com.diapilot.core.hybrid.HybridForecastState
import com.diapilot.core.hybrid.HybridGlucosePoint
import com.diapilot.core.physio.PhysioForecastEngine
import com.diapilot.core.physio.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage7SqliteIntegrationTest {
    /** Data below is laid out relative to a fixed synthetic era start. */
    @Before fun fixFoodEra() { TestFoodEra.install() }

    // A-27: production keeps the shadow-metrics switch OFF until an on-device
    // cost measurement exists; the machinery itself must stay under test.
    @Test fun `pre food era rows stay stored but are excluded from food and factor learning`() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="food-era-floor-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context,name).use{store->
            val before=TestFoodEra.ERA.startMs-86_400_000L
            val after=TestFoodEra.ERA.startMs+86_400_000L
            store.addAnnotation(Annotation(before,"food","unlogged-era synthetic",estCarbs=99.0,carbsSource="manual",carbsKnownAtMs=before))
            store.addAnnotation(Annotation(after,"food","food-era synthetic",estCarbs=20.0,carbsSource="manual",carbsKnownAtMs=after))
            store.writableDatabase.execSQL("INSERT INTO insulin_events(ts_ms,units,insulin_type,source,user_edited) VALUES(?,9.0,'bolus','old',0)",arrayOf(before))
            store.writableDatabase.execSQL("INSERT INTO insulin_events(ts_ms,units,insulin_type,source,user_edited) VALUES(?,1.0,'bolus','new',0)",arrayOf(after))
            store.writableDatabase.execSQL("INSERT INTO steps(start_ms,end_ms,count) VALUES(?,?,?)",arrayOf(before-60_000,before,9999))
            store.writableDatabase.execSQL("INSERT INTO steps(start_ms,end_ms,count) VALUES(?,?,?)",arrayOf(after-60_000,after,100))

            assertEquals(2,store.annotations(0,after+1).count{it.kind=="food"}) // history/storage is intact
            // THE ADMISSION RULE IS READ FROM THE LIVE PATH. This used to call
            // `FoodSources.causalFoods`, which was removed: it had
            // no production caller left, and the test would have been
            // guarding code nobody uses.
            val causal=HybridRuntimeMetrics.cobEvents(store,TestFoodEra.ERA.startMs,after+1)
            assertEquals(listOf(after),causal.map{it.first.tsMs})
            // Two checks through PhysioSourceInventoryRuntime used to stand
            // here — the inventory was removed as unreachable from the app.
            // What this test exists for — that the pre-food-era row is
            // STORED but does not enter training — is checked two lines
            // above, on the live code.
        }
        context.deleteDatabase(name)
    }

    @Test fun historicalStage10ReceiptUsesCarbEvidenceRevisionAtCutoff() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="stage10-asof-${System.nanoTime()}.sqlite";val event=1_700_000_000_000L;val cutoff=event+360*60_000L
        SqliteCollectorStore(context,name).use{store->
            val id=store.addAnnotation(Annotation(event,"food","unversioned title",estCarbs=30.0,carbsSource="manual",carbsKnownAtMs=event+1_000))
            val u=CarbEvidenceInputV1(CarbEvidenceSourceV1.LABEL_WEIGHT,true,labelCarbsPer100g=20.0,weighedEdibleG=150.0,
                amountUncertainty=CarbUncertaintyV1("label"),timingUncertainty=CarbUncertaintyV1("start"))
            store.appendCarbEvidence(id,event,u,event+1_000,event+1_000)
            val rr=(-1..72).map{i->GlucosePoint(event+i*5*60_000L,5.5+if(i>0)i.coerceAtMost(30)/30.0*4 else 0.0)}
            fun receipt()=Stage9EpisodeRuntime.build(store.annotations(event-1,cutoff+1),rr,emptyList(),emptyList(),event,cutoff,
                carbEvidenceAsOf={annotationId,asOf->store.carbEvidenceKnownAt(annotationId,asOf)},context=context).getValue(id)
            val before=receipt()
            store.appendCarbEvidence(id,event,u.copy(weighedEdibleG=300.0,totalCarbsG=60.0),cutoff+1_000,cutoff+1_000)
            val after=receipt()
            assertEquals(before,after)
            assertTrue(after.modelForecast.contains("30.0"))
            assertTrue(after.causalProvenance.contains("CarbEvidence"))
        }
        context.deleteDatabase(name)
    }

    @Test fun evidenceExportParseImportDbExportConflictAndIdempotency() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "stage7-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            val id = store.addAnnotation(Annotation(1_000, "food", "redacted"))
            val input = CarbEvidenceInputV1(CarbEvidenceSourceV1.LABEL_WEIGHT, true,
                labelCarbsPer100g = 20.0, weighedEdibleG = 150.0,
                amountUncertainty = CarbUncertaintyV1("label"), timingUncertainty = CarbUncertaintyV1("start"),
                kineticsV2 = "KINETICS_V2: fast=.2;medium=.6;slow=.2;form=MIXED;confidence=.8;source=label;alcohol=false")
            val first = store.appendCarbEvidence(id, 1_000, input, 2_000, 2_100)
            val second = store.appendCarbEvidence(id, 500, input.copy(weighedEdibleG = 160.0, totalCarbsG = 32.0), 5_000, 5_100)
            assertEquals(1, store.carbEvidenceKnownAt(id, 3_000)?.revision)
            assertEquals(2, store.carbEvidenceKnownAt(id, 6_000)?.revision)
            val parsed = CarbEvidenceV1.parseCanonicalJson(first.canonicalJson())
            assertEquals(input.kineticsV2, parsed.input.kineticsV2)
            assertFalse(store.importCarbEvidence(parsed))
            assertThrows(IllegalArgumentException::class.java) {
                store.importCarbEvidence(parsed.copy(input = parsed.input.copy(totalCarbsG = 32.0, weighedEdibleG = 160.0)))
            }
            assertEquals(listOf(first.canonicalJson(), second.canonicalJson()), store.carbEvidenceHistory(id).map { it.canonicalJson() })
            assertThrows(IllegalArgumentException::class.java) {
                store.appendCarbEvidence(id, 1_000, input, 1_999, 2_099)
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun v43MigrationAddsFrozenKineticsWithoutChangingEvidenceRows() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="kinetics-v43-${System.nanoTime()}.sqlite"
        context.openOrCreateDatabase(name,0,null).use{db->
            db.execSQL("CREATE TABLE carb_evidence_v1(id INTEGER PRIMARY KEY, owner_id INTEGER, payload TEXT)")
            db.execSQL("INSERT INTO carb_evidence_v1(id,owner_id,payload) VALUES(7,42,'keep')")
            db.version=43
        }
        SqliteCollectorStore(context,name).use{store->
            val columns=store.readableDatabase.rawQuery("PRAGMA table_info(carb_evidence_v1)",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
            assertTrue("kinetics_v2" in columns)
            val row=store.readableDatabase.rawQuery("SELECT owner_id,payload,kinetics_v2 FROM carb_evidence_v1 WHERE id=7",null).use{c->
                assertTrue(c.moveToFirst());Triple(c.getLong(0),c.getString(1),c.getString(2))
            }
            assertEquals(Triple(42L,"keep",null),row)
        }
        context.deleteDatabase(name)
    }

    @Test fun v32MigrationPreservesAnnotationAndCreatesStage7Ledgers() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "stage7-migration-${System.nanoTime()}.sqlite"
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE annotations(id INTEGER PRIMARY KEY AUTOINCREMENT, ts_ms INTEGER, kind TEXT, content TEXT, media_ref TEXT, analysis TEXT, est_carbs REAL, carbs_known_at_ms INTEGER, carbs_source TEXT)")
            db.execSQL("INSERT INTO annotations(ts_ms,kind,content) VALUES(1000,'food','redacted')")
            db.version = 32
        }
        SqliteCollectorStore(context, name).use { store ->
            assertEquals(1, store.annotations(0, 2_000).size)
            val tables = store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            val annotationTriggers=store.readableDatabase.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'physio_dirty_annotations_%'",null).use{it.moveToFirst();it.getInt(0)}
            assertEquals(3,annotationTriggers)
        }
        context.deleteDatabase(name)
    }

    @Test fun v37MigrationCreatesClosedEpisodeProductionSchemaWithoutLosingFacts() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="stage11-v37-migration-${System.nanoTime()}.sqlite"
        context.openOrCreateDatabase(name,0,null).use{db->
            db.execSQL("CREATE TABLE annotations(id INTEGER PRIMARY KEY AUTOINCREMENT, ts_ms INTEGER, kind TEXT, content TEXT, media_ref TEXT, analysis TEXT, est_carbs REAL, carbs_known_at_ms INTEGER, carbs_source TEXT, analysis_known_at_ms INTEGER)")
            db.execSQL("INSERT INTO annotations(ts_ms,kind,content) VALUES(1000,'food','preserve-me')")
            db.version=37
        }
        SqliteCollectorStore(context,name).use{store->
            assertEquals("preserve-me",store.annotations(0,2_000).single().content)
            val tables=store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table'",null).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
            assertTrue("physio_closed_episodes" in tables)
            assertTrue("physio_closed_isf_evidence_v1" in tables)
            assertTrue("physio_activity_coverage_v1" in tables)
            assertTrue("physio_closed_input_invalidations_v1" in tables)
            assertTrue("physio_closed_run_status_v1" in tables)
        }
        context.deleteDatabase(name)
    }

    @Test fun v42MigrationPreservesRawHistoryButResetsPreStrictEraLearnedProducts() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="strict-era-v42-migration-${System.nanoTime()}.sqlite"
        context.openOrCreateDatabase(name,0,null).use{db->
            db.execSQL("CREATE TABLE annotations(id INTEGER PRIMARY KEY AUTOINCREMENT, ts_ms INTEGER, kind TEXT, content TEXT)")
            db.execSQL("INSERT INTO annotations(ts_ms,kind,content) VALUES(1000,'food','preserve-raw-history')")
            PhysioExperimentalLedger.DDL.forEach(db::execSQL)
            db.execSQL("INSERT INTO physio_daily_checkpoints VALUES('old',NULL,'RECONCILED',1000,'{}',0)")
            db.execSQL("INSERT INTO physio_global_cs_state VALUES(1,1000,0.29,0.2,0.4,1,1,'PRELIMINARY','old-hash','old learner',0)")
            db.execSQL("INSERT INTO physio_closed_episodes VALUES('old-episode',1000,2000,2000,'CLOSED',NULL,'old-input','{}')")
            db.execSQL("CREATE TABLE physio_learning_snapshots_v1(revision INTEGER PRIMARY KEY, maintained_at_ms INTEGER)")
            db.execSQL("INSERT INTO physio_learning_snapshots_v1 VALUES(1,1000)")
            db.version=42
        }
        SqliteCollectorStore(context,name).use{store->
            assertEquals("preserve-raw-history",store.readableDatabase.rawQuery("SELECT content FROM annotations",null).use{it.moveToFirst();it.getString(0)})
            listOf("physio_daily_checkpoints","physio_global_cs_state","physio_closed_episodes").forEach{table->
                assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getInt(0)})
            }
            assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='physio_learning_snapshots_v1'",null).use{it.moveToFirst();it.getInt(0)})
        }
        context.deleteDatabase(name)
    }

    @Test fun lateLoggedFoodKeepsIntakeStartButCannotLeakBeforeKnowledge() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "stage7-latelog-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            val base=TestFoodEra.ERA.startMs
            store.addAnnotation(Annotation(base+1_000, "food", "late", estCarbs = 20.0, carbsSource = "manual", carbsKnownAtMs = base+5_000))
            store.addAnnotation(Annotation(base+2_000, "food", "legacy", estCarbs = 10.0, carbsSource = null, carbsKnownAtMs = null))
            val foods = HybridRuntimeMetrics.cobEvents(store, base, base+10_000)
            // A late-logged record keeps the MEAL TIME and carries its own knowledge time.
            assertEquals(base+1_000, foods.first { it.first.tsMs == base+1_000L }.first.tsMs)
            assertEquals(base+5_000, foods.first { it.first.tsMs == base+1_000L }.second)
            // And a note with no knowledge time is not admitted AT ALL. The
            // removed `causalFoods` used to return it with
            // `knownAtMs = Long.MAX_VALUE` ("never known"); the live path
            // refuses it, matching `HybridShadow.causalFoodNotes`. These are
            // two different things, and this checks the second one.
            assertTrue(foods.none { it.first.tsMs == base+2_000L })
        }
        context.deleteDatabase(name)
    }

    @Test fun recipeCompatibilityNeverBecomesAnchor() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "stage7-recipe-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            val id = store.addAnnotation(Annotation(1_000, "food", "recipe"))
            store.appendCarbEvidence(id, 1_000, CarbEvidenceInputV1(
                CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT, true,
                recipeVersion = "v1", recipeTotalCarbsG = 40.0, recipeTotalWeightG = 200.0, recipeConsumedWeightG = 200.0,
                amountUncertainty = CarbUncertaintyV1("recipe_density"), timingUncertainty = CarbUncertaintyV1("start"),
            ), 2_000, 2_000)
            assertEquals("manual", store.annotations(0, 3_000).single().carbsSource)
        }
        context.deleteDatabase(name)
    }

    @Test fun physioRuntimeUsesGlobalAmplitudeAndProducesFiniteForecast() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = context.assets.open("models/person_model_v11_runtime.json").use { HybridPersonModelJson.read(it) }
        HybridShadowRegistry.install(model, "0123456789abcdef0123456789abcdef")
        val artifact = requireNotNull(PhysioRuntime.artifact())
        val runtimeModel = artifact.personModelAt(12.0)
        // The dish dictionary these three lines compared is gone; what the
        // test still has to pin is that the runtime model carries the AMPLITUDE
        // through unchanged, which is the property the name claims.
        // NOT «the asset's number passes through»: with no weight recorded the
        // runtime deliberately replaces it with the weight prior, and that
        // substitution is what makes a new user's first meal sane.
        assertEquals(
            com.diapilot.core.analysis.CarbSensitivityPriorV1.fromWeight(null),
            runtimeModel.food.globalFactor, 1e-12,
        )
        assertEquals(model.food.calibration, runtimeModel.food.calibration, 1e-12)
        val result = PhysioForecastEngine(artifact).forecast(HybridForecastState(1_000, listOf(HybridGlucosePoint(1_000, 6.0))))
        assertTrue(result.points.isNotEmpty()); assertTrue(result.points.all { it.baseline.isFinite() && it.low.isFinite() && it.high.isFinite() })
    }

    @Test fun dailyCheckpointRevisionLedgerNeverOverwritesEarlyConclusion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "stage7-checkpoints-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            val state = DailyResponseStateV1(PosteriorV1(50.0, 30.0, 75.0, 1, 1, 60), PosteriorV1(0.0, -20.0, 20.0, 0, 0, null), EvidenceStatus.NOT_IDENTIFIABLE)
            val c60 = DailyCheckpointV1("meal:1:60", null, CheckpointKind.PROVISIONAL_60, 60, state, listOf("meal:1:60"))
            val c120 = DailyCheckpointV1("meal:1:120", "meal:1:60", CheckpointKind.PROVISIONAL_120, 120, state.copy(effectiveResponsePercent=state.effectiveResponsePercent.copy(median=35.0)), listOf("meal:1:120"))
            PhysioExperimentalLedger.recordCheckpoint(store.writableDatabase, c60, "{\"v\":60}")
            PhysioExperimentalLedger.recordCheckpoint(store.writableDatabase, c120, "{\"v\":120}")
            PhysioExperimentalLedger.recordCheckpoint(store.writableDatabase, c60, "{\"v\":999}")
            val payloads = store.readableDatabase.rawQuery("SELECT payload_json FROM physio_daily_checkpoints ORDER BY known_at_ms", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
            assertEquals(listOf("{\"v\":60}", "{\"v\":120}"), payloads)
        }
        context.deleteDatabase(name)
    }

    /**
     * CONTRACT CHANGED: EVEN CONFIRMED EVIDENCE DOES NOT MOVE THE APPLIED ISF.
     *
     * This test used to pin exactly the opposite — that at `SUPPORTED`, n>=6
     * and days>=3 the `DailyResponseEstimatorV1` answer becomes the applied
     * number. That was a FOURTH door to the ISF axis, and it was found by the
     * question "why do we need DailyResponseEstimatorV1 at all, when we
     * already dropped the correction-based estimator".
     *
     * It was invisible because it sleeps: in production `NOT_IDENTIFIABLE`,
     * n=1 against the n>=6 gate. So the defect would have woken up on its own
     * once six corrections accumulated, and silently swapped out the user's
     * chosen source with no line on screen.
     *
     * The axis has exactly two doors — hand and daily balance — and both
     * arrive through `mechanics.insulin.isf`. The learner remains an
     * OBSERVATION: it writes checkpoints, feeds the daily-discrepancy card,
     * and is printed alongside as a "learned alternative". A person can apply
     * it, not a `?:` branch.
     *
     * The first two gate stages are still checked as before: they never
     * should have moved ISF, and still do not. Only the third changed.
     */
    @Test fun supportedEvidenceIsOfferedAsAnAlternativeAndNeverApplied() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = context.assets.open("models/person_model_v11_runtime.json").use { HybridPersonModelJson.read(it) }
        HybridShadowRegistry.install(model, "feedfacefeedface")
        val name = "stage7-isf-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            val base=TestFoodEra.ERA.startMs
            val mixed = """{"identified_isf":{"median":50,"p10":30,"p90":75,"n":0},"isf_status":"NOT_IDENTIFIABLE"}"""
            store.writableDatabase.execSQL("INSERT INTO physio_daily_checkpoints VALUES('m',NULL,'PROVISIONAL_60',?,?,0)", arrayOf(base+1_000,mixed))
            assertEquals(model.insulin.isf, PhysioRuntime.artifact(store, base+1_000)!!.globalIsf.median, 1e-12)
            val identifying = """{"identified_isf":{"median":20,"p10":5,"p90":32,"n":3,"days":2},"isf_status":"PRELIMINARY"}"""
            store.writableDatabase.execSQL("INSERT INTO physio_daily_checkpoints VALUES('c','m','RECONCILED',?,?,0)", arrayOf(base+2_000,identifying))
            assertEquals(model.insulin.isf, PhysioRuntime.artifact(store, base+2_000)!!.globalIsf.median, 1e-9)
            // THIRD STAGE: evidence passed the gate (n=6, days=3,
            // SUPPORTED) and asks for +20%. The applied number must NOT move.
            val supported = """{"identified_isf":{"median":20,"p10":5,"p90":32,"n":6,"days":3},"isf_status":"SUPPORTED"}"""
            store.writableDatabase.execSQL("INSERT INTO physio_daily_checkpoints VALUES('s','c','RECONCILED',?,?,0)", arrayOf(base+3_000,supported))
            val a = PhysioRuntime.artifact(store, base+3_000)!!
            assertEquals(
                "confirmed evidence moved the applied ISF — the fourth door is open",
                model.insulin.isf, a.globalIsf.median, 1e-9,
            )
            assertEquals(
                "applied ISF disagreed with the mechanics",
                a.personModelAt(12.0).insulin.isf, a.globalIsf.median, 1e-9,
            )
            // ...and at the same time the learner's answer MUST be visible,
            // or it is simply lost: the user must be able to accept it themselves.
            assertEquals(
                "the learner's answer was not offered as an alternative",
                model.insulin.isf * 1.2, a.learnedIsfAlternative?.median ?: 0.0, 1e-9,
            )
        }
        context.deleteDatabase(name)
    }

    @Test fun extremeSupportedCheckpointIsBoundedAndFlaggableRatherThanAcceptedAsCoefficient() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val model=context.assets.open("models/person_model_v11_runtime.json").use{HybridPersonModelJson.read(it)}
        HybridShadowRegistry.install(model,"extreme")
        val name="stage7-extreme-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context,name).use{store->
            val payload="""{"identified_isf":{"median":500,"p10":300,"p90":700,"n":10,"days":5},"isf_status":"SUPPORTED"}"""
            store.writableDatabase.execSQL("INSERT INTO physio_daily_checkpoints VALUES('extreme',NULL,'RECONCILED',1000,?,0)",arrayOf(payload))
            val artifact=PhysioRuntime.artifact(store,1_000)!!
            assertTrue(artifact.globalIsf.p10>=artifact.bounds.isfMmolPerLUmin)
            assertTrue(artifact.globalIsf.p90<=artifact.bounds.isfMmolPerLUmax)
        }
        context.deleteDatabase(name)
    }

    @Test fun invalidLegacyPhysioPriorsAreCoercedAndFlagged() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val base=context.assets.open("models/person_model_v11_runtime.json").use{HybridPersonModelJson.read(it)}
        val invalid=base.copy(insulin=base.insulin.copy(onsetMin=70.0,peakMin=100.0,shortDurationMin=180.0,tailDurationMin=300.0),food=base.food.copy(globalFactor=2.0))
        HybridShadowRegistry.install(invalid,"invalid-bounds")
        val artifact=PhysioRuntime.artifact()!!
        // Stage 8 owns the PHYSIO food-scale anchor. Even an invalid legacy
        // value is reported as a conflict, never adopted as the live CS.
        assertEquals(PhysioRuntime.foodDynamicsGlobalPriorV1().median,artifact.globalCs.median,0.0)
        assertTrue(artifact.baseMechanics.insulin.onsetMin<=artifact.bounds.insulinOnsetMinRange.endInclusive)
        assertTrue(artifact.conflictFlags.size>=2)
        HybridShadowRegistry.install(base,"restored")
    }

    // sharedExposureBoundariesAndNightWindowAreExact used to stand here — it
    // pinned the boundaries of PhysioExposureDefinitions (sleep <7 h, protein
    // >=15 g, meal >=20 min, night window). The definitions were removed: this
    // test was their only reader, and the app never called them.

    @Test fun genericContextSourceAppearsCausallyAndIsIdempotentAcrossRestart() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="stage7-context-${System.nanoTime()}.sqlite"
        val row=ContextExposureV1("weather-1","weather_heat",1_000,2_000,3_000,3_100,35.0,"C","{\"duration_min\":30}")
        SqliteCollectorStore(context,name).use{store->
            assertTrue(store.appendContextExposure(row));assertFalse(store.appendContextExposure(row))
            assertTrue(store.contextExposures("weather_heat",0,5_000,2_999).isEmpty())
            assertEquals(listOf(row),store.contextExposures("weather_heat",0,5_000,3_000))
        }
        SqliteCollectorStore(context,name).use{store->assertEquals(listOf(row),store.contextExposures("weather_heat",0,5_000,3_000))}
        context.deleteDatabase(name)
    }

    @Test fun lateLoggedCheckpointNeverUsesForecastProducedAtOrAfterTarget() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "stage7-prospective-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context,name).use { store ->
            // Seeds `forecast_runs`/`forecast_points` directly — the series
            // `forecastAt` reads now. It used to seed
            // `physio_parallel_runs` through `recordPair`, whose only writer
            // died with the second arm; the test kept passing while the
            // production path it guards returned null for everything.
            fun run(id: Long, anchorMs: Long, targetMs: Long) {
                val db = store.writableDatabase
                db.execSQL(
                    "INSERT OR REPLACE INTO forecast_runs" +
                        "(id,created_at_ms,anchor_ts_ms,anchor_mmol,consumer,algo_version,regime,health,input_hash)" +
                        " VALUES (?,?,?,?,'main','test',NULL,NULL,?)",
                    arrayOf<Any?>(id, anchorMs, anchorMs, 6.0, "h$id"),
                )
                listOf(anchorMs to 6.0, targetMs to 7.0).forEach { (t, v) ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO forecast_points" +
                            "(run_id,horizon_min,target_ts_ms,mmol,lo,hi,lo_mid,hi_mid) VALUES (?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(id, ((t - anchorMs) / 60_000).toInt(), t, v, v - 1, v + 1, v - 0.5, v + 0.5),
                    )
                }
            }
            // Anchored AT the target: it already contains the outcome.
            run(1L, 6_000, 7_000)
            assertNull(DailyDiscrepancyRuntime.forecastAt(store.readableDatabase,2_000,5_000))
            // Anchored after the event became known and strictly before the target.
            run(2L, 3_000, 5_000)
            assertNotNull(DailyDiscrepancyRuntime.forecastAt(store.readableDatabase,2_000,5_000))
            assertNull(DailyDiscrepancyRuntime.forecastAt(store.readableDatabase,5_000,5_000))
        }
        context.deleteDatabase(name)
    }

    @Test fun artifactIdentityIsAsOfStateAndLateRevisionCannotRewriteEarlierRun() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val model=context.assets.open("models/person_model_v11_runtime.json").use{HybridPersonModelJson.read(it)}
        HybridShadowRegistry.install(model,"artifact-causal")
        val name="stage7-artifact-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context,name).use { store ->
            val base=TestFoodEra.ERA.startMs;val prior=PhysioRuntime.artifact(store,base+1_000)!!.artifactId
            val payload="""{"identified_isf":{"median":20,"p10":5,"p90":32,"n":3},"isf_status":"PRELIMINARY"}"""
            store.writableDatabase.execSQL("INSERT INTO physio_daily_checkpoints VALUES('c1',NULL,'RECONCILED',?,?,0)",arrayOf(base+2_000,payload))
            val at2=PhysioRuntime.artifact(store,base+2_000)!!.artifactId
            assertNotEquals(prior,at2)
            store.writableDatabase.execSQL("INSERT INTO physio_daily_checkpoints VALUES('late','c1','RECONCILED',?,?,0)",arrayOf(base+3_000,payload.replace("20","25")))
            assertEquals(at2,PhysioRuntime.artifact(store,base+2_000)!!.artifactId)
            assertNotEquals(at2,PhysioRuntime.artifact(store,base+3_000)!!.artifactId)
        }
        context.deleteDatabase(name)
    }

    @Test fun multipleOwnerEventsUseDifferentKernelsInOneForecast() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>();val model=context.assets.open("models/person_model_v11_runtime.json").use{HybridPersonModelJson.read(it)}
        val now=6*3_600_000L;val history=listOf(com.diapilot.core.hybrid.HybridGlucosePoint(now-60*60_000L,8.0),com.diapilot.core.hybrid.HybridGlucosePoint(now,8.0))
        val engine=com.diapilot.core.hybrid.HybridForecastEngine(model)
        val old=com.diapilot.core.hybrid.HybridBolusEvent(now-120*60_000L,2.0)
        val recent=com.diapilot.core.hybrid.HybridBolusEvent(now-60*60_000L,2.0)
        fun bolusCurve(a:com.diapilot.core.hybrid.HybridBolusEvent,b:com.diapilot.core.hybrid.HybridBolusEvent)=engine.forecast(com.diapilot.core.hybrid.HybridForecastState(now,history,bolusHistory=listOf(a,b))).points.last().scenario
        val modifyOld=bolusCurve(old.copy(tailOffsetMin=90.0,contextIds=setOf("dose_tail")),recent)
        val modifyRecent=bolusCurve(old,recent.copy(tailOffsetMin=90.0,contextIds=setOf("dose_tail")))
        assertNotEquals(modifyOld,modifyRecent,1e-9)
        // Keep the synthetic owner test below the shared 30 g/h cluster cap;
        // under a saturated queue swapping identical owners intentionally
        // preserves the total cluster curve even though allocation differs.
        val oldFood=com.diapilot.core.hybrid.HybridFoodEvent(now-120*60_000L,10.0)
        val recentFood=com.diapilot.core.hybrid.HybridFoodEvent(now-30*60_000L,10.0)
        fun foodCurve(a:com.diapilot.core.hybrid.HybridFoodEvent,b:com.diapilot.core.hybrid.HybridFoodEvent)=engine.forecast(com.diapilot.core.hybrid.HybridForecastState(now,history,foodHistory=listOf(a,b))).points.map{it.scenario}
        // At a saturated, non-identifiable food cluster the production
        // contract deliberately preserves the aggregate mass curve instead
        // of inventing which equal portion owns the tail.
        assertEquals(foodCurve(oldFood.copy(tailOffsetMin=120.0,contextIds=setOf("protein_absorption")),recentFood),foodCurve(oldFood,recentFood.copy(tailOffsetMin=120.0,contextIds=setOf("protein_absorption"))))
    }

    @Test fun promotedEffectAutomaticallyDemotesWhenEvidenceAgesOut() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>();val name="stage7-stale-${System.nanoTime()}.sqlite";val d=PhysioHypothesisRegistryV1.definitions.first()
        SqliteCollectorStore(context,name).use{store->
            val effect="""{"typed_effect":{"target":"ISF_MULTIPLIER","median":0.1,"low":0.05,"high":0.15},"evaluation_hash":"e","source_evidence_hash":"s"}"""
            store.writableDatabase.execSQL("INSERT INTO physio_factor_promotion_ledger VALUES(?,?,?,?,?,?,?,?)",arrayOf(d.id,1,100,"PROMOTED_MEDIAN",10.0,"test","SUPPORTED",effect))
            val now=100+(d.executableSpec.decayHalfLifeDays*4*86_400_000).toLong()
            val state=PhysioExperimentalLedger.demoteIfStale(store.writableDatabase,d,now)!!
            assertEquals(PromotionStageV1.DEMOTED,state.stage);assertTrue(state.reason.contains("stale"))
        }
        context.deleteDatabase(name)
    }
}
