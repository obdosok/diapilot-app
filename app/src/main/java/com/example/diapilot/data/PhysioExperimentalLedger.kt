package com.example.diapilot.data

import android.database.sqlite.SQLiteDatabase
import com.diapilot.core.physio.DailyCheckpointV1
import com.diapilot.core.physio.ForecastArmSelection
import com.diapilot.core.twin.ForecastResult
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.physio.*
import java.security.MessageDigest
import kotlin.math.abs

object PhysioExperimentalLedger {
    /**
     * Tags every promotion row with the food era its evidence was computed
     * under. A prior row whose tag names a different era (another start date,
     * or an older binary's tag format) is demoted once by [foodEraPrior]
     * instead of being reused: its evidence was drawn from a different
     * admissible history. Unknown tags are simply "not current" — reading an
     * old row never throws.
     */
    fun foodEraSourcePrefix(era:com.diapilot.core.api.FoodEra=FoodEraSettings.current()):String="strict-food-era-v3-${era.startMs}:"
    fun isFoodEraState(state:PromotionStateV1?,era:com.diapilot.core.api.FoodEra=FoodEraSettings.current()):Boolean=state?.sourceEvidenceHash?.startsWith(foodEraSourcePrefix(era))==true
    data class ShadowCandidate(val generationId:String,val hypothesisId:String,val promotionRevision:Int,val evaluationStartKnownAt:Long,val baselineArtifactId:String,val candidateArtifactId:String,val effect:EffectPayloadV1)
    /**
     * `physio_parallel_runs` and `physio_parallel_scores` LEFT THIS LIST,
     * with the prospective candidate A/B they existed for. They are
     * dropped from existing databases by [LedgerRetention.dropRetiredLedgers];
     * on a real device they could hold millions of rows and be most of a
     * large database file, while gating nothing — see [Forecaster] for why.
     */
    val DDL = listOf(
        /*
         * ⚠ ORPHANED — NO WRITER AND NO READER.
         *
         * This held every measured dose and what was wrong with it: the review
         * corpus. `IsfEvidenceRuntime` wrote it (ISF straight off the line, one
         * row per dose, with a refusal string when the dose was not admitted)
         * and `CorrectionReviewRuntime` read it — the screen that asked the
         * user to rule on a dose. Both were deleted with the review card at
         * the user's request, and the amplitude corpus lost the only door through which
         * it could grow (see docs/insulin-model.md §3).
         *
         * The DDL is KEPT deliberately, and not out of sentiment: the rows
         * already written are in the user's database, they are the only per-dose ISF
         * observations that exist, and dropping the table would destroy them.
         * If a way of asking the user is ever rebuilt, this is the shape it reads.
         *
         * Do not treat a row here as current. Nothing has written one in a
         * long time.
         */
        """CREATE TABLE IF NOT EXISTS physio_isf_dose_review_v1 (
            bolus_ts_ms INTEGER PRIMARY KEY,
            known_at_ms INTEGER NOT NULL,
            units REAL NOT NULL,
            admitted INTEGER NOT NULL,
            refusal TEXT,
            isf REAL, isf_low REAL, isf_high REAL,
            max_gap_min REAL,
            onset_min REAL,
            /* The action curve this dose left behind, as CDF knots. Ruling on a
             * correction without seeing its curve is ruling blind — the numbers
             * say "quality 0.27" and nothing about why. */
            cdf_json TEXT,
            producer TEXT NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS physio_daily_checkpoints (
            checkpoint_id TEXT PRIMARY KEY, supersedes_id TEXT, kind TEXT NOT NULL,
            known_at_ms INTEGER NOT NULL, payload_json TEXT NOT NULL, closed INTEGER NOT NULL DEFAULT 0)""",
        """CREATE TABLE IF NOT EXISTS physio_factor_promotion_ledger (
            hypothesis_id TEXT NOT NULL, revision INTEGER NOT NULL, known_at_ms INTEGER NOT NULL,
            stage TEXT NOT NULL, bounded_effect_percent REAL NOT NULL, reason TEXT NOT NULL,
            evidence_status TEXT NOT NULL, evidence_json TEXT NOT NULL,
            PRIMARY KEY(hypothesis_id,revision))""",
        """CREATE TABLE IF NOT EXISTS physio_global_cs_state (
            revision INTEGER PRIMARY KEY, known_at_ms INTEGER NOT NULL, median_mmol_per_l_g REAL NOT NULL,
            low_mmol_per_l_g REAL NOT NULL, high_mmol_per_l_g REAL NOT NULL,
            usable_episodes INTEGER NOT NULL, independent_days INTEGER NOT NULL,
            status TEXT NOT NULL, evidence_hash TEXT NOT NULL UNIQUE, reason TEXT NOT NULL,
            bound_hit INTEGER NOT NULL DEFAULT 0)""",
        """CREATE TABLE IF NOT EXISTS physio_closed_episodes (
            episode_id TEXT PRIMARY KEY, start_ms INTEGER NOT NULL, end_ms INTEGER,
            known_at_ms INTEGER NOT NULL, status TEXT NOT NULL, supersedes_id TEXT,
            input_hash TEXT NOT NULL, payload_json TEXT NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS idx_physio_closed_start ON physio_closed_episodes(start_ms,known_at_ms)",
        "CREATE INDEX IF NOT EXISTS idx_physio_closed_supersedes ON physio_closed_episodes(supersedes_id)",
        """CREATE TABLE IF NOT EXISTS physio_cgm_arrivals_v1 (
            ts_ms INTEGER PRIMARY KEY, observed_at_ms INTEGER NOT NULL,
            source TEXT, live INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS idx_physio_cgm_arrival_observed ON physio_cgm_arrivals_v1(observed_at_ms,live)",
        """CREATE TABLE IF NOT EXISTS physio_cgm_arrivals_v2 (
            ts_ms INTEGER NOT NULL, fact_hash TEXT NOT NULL, observed_at_ms INTEGER NOT NULL,
            source TEXT, live INTEGER NOT NULL, PRIMARY KEY(ts_ms,fact_hash))""",
        """CREATE TABLE IF NOT EXISTS physio_bolus_arrivals_v1 (
            ts_ms INTEGER PRIMARY KEY, observed_at_ms INTEGER NOT NULL,
            source TEXT, live INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS physio_bolus_arrivals_v2 (
            ts_ms INTEGER NOT NULL, fact_hash TEXT NOT NULL, observed_at_ms INTEGER NOT NULL,
            source TEXT, live INTEGER NOT NULL, PRIMARY KEY(ts_ms,fact_hash))""",
        """CREATE TABLE IF NOT EXISTS physio_closed_maintenance_v1 (
            version TEXT PRIMARY KEY, cursor_ms INTEGER NOT NULL,
            complete INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS physio_closed_isf_evidence_v1 (
            evidence_id TEXT PRIMARY KEY, episode_id TEXT NOT NULL, supersedes_id TEXT,
            bolus_ts_ms INTEGER NOT NULL, local_day INTEGER NOT NULL, known_at_ms INTEGER NOT NULL,
            effective_isf REAL NOT NULL, low_isf REAL NOT NULL, high_isf REAL NOT NULL,
            weight REAL NOT NULL, artifact_id TEXT NOT NULL, input_hash TEXT NOT NULL,
            identifying INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS idx_closed_isf_day ON physio_closed_isf_evidence_v1(local_day,known_at_ms)",
        // The user's verdict on one of their own tagged corrections. One
        // ruling per dose; `fact_hash` records WHICH version of that dose the user
        // ruled on, so an edited injection can be shown as unruled again
        // instead of silently inheriting a decision about a different dose.
        """CREATE TABLE IF NOT EXISTS physio_correction_verdicts_v1 (
            bolus_ts_ms INTEGER PRIMARY KEY, fact_hash TEXT,
            verdict TEXT NOT NULL, decided_at_ms INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS physio_closed_input_invalidations_v1 (
            source_table TEXT NOT NULL, event_ms INTEGER NOT NULL,
            changed_at_ms INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS physio_closed_invalidation_state_v1 (
            singleton INTEGER PRIMARY KEY CHECK(singleton=1),
            last_rowid INTEGER NOT NULL)""",
        "INSERT OR IGNORE INTO physio_closed_invalidation_state_v1(singleton,last_rowid) VALUES(1,0)",
        """CREATE TABLE IF NOT EXISTS physio_activity_coverage_v1 (
            from_ms INTEGER NOT NULL, to_ms INTEGER NOT NULL,
            observed_at_ms INTEGER NOT NULL, source TEXT NOT NULL,
            PRIMARY KEY(from_ms,to_ms,observed_at_ms,source))""",
        "CREATE INDEX IF NOT EXISTS idx_activity_coverage_span ON physio_activity_coverage_v1(from_ms,to_ms,observed_at_ms)",
        """CREATE TABLE IF NOT EXISTS physio_closed_run_status_v1 (
            singleton INTEGER PRIMARY KEY CHECK(singleton=1), updated_at_ms INTEGER NOT NULL,
            version TEXT NOT NULL, processed_from_ms INTEGER, processed_to_ms INTEGER,
            history_cursor_ms INTEGER, history_total_to_ms INTEGER,
            candidate_initiators INTEGER NOT NULL, closed_count INTEGER NOT NULL,
            open_count INTEGER NOT NULL, censored_count INTEGER NOT NULL,
            rejection_json TEXT NOT NULL, error_summary TEXT)""",
    )

    private fun closedInvalidationTriggers():List<String> {
        val specs=listOf(
            "annotations" to "ts_ms", "glucose_readings" to "ts_ms",
            "insulin_events" to "ts_ms", "basal_events" to "ts_ms",
            "steps" to "start_ms", "sleep_sessions" to "start_ms",
            "carb_evidence_v1" to "intake_start_ms",
            "physio_context_exposures_v1" to "event_start_ms",
        )
        return specs.flatMap{(table,column)->listOf(
            """CREATE TRIGGER IF NOT EXISTS closed_dirty_${table}_insert AFTER INSERT ON $table BEGIN
                 INSERT INTO physio_closed_input_invalidations_v1 VALUES('$table',NEW.$column,CAST(strftime('%s','now') AS INTEGER)*1000); END""",
            """CREATE TRIGGER IF NOT EXISTS closed_dirty_${table}_update AFTER UPDATE ON $table BEGIN
                 INSERT INTO physio_closed_input_invalidations_v1 VALUES('$table',OLD.$column,CAST(strftime('%s','now') AS INTEGER)*1000);
                 INSERT INTO physio_closed_input_invalidations_v1 VALUES('$table',NEW.$column,CAST(strftime('%s','now') AS INTEGER)*1000); END""",
            """CREATE TRIGGER IF NOT EXISTS closed_dirty_${table}_delete AFTER DELETE ON $table BEGIN
                 INSERT INTO physio_closed_input_invalidations_v1 VALUES('$table',OLD.$column,CAST(strftime('%s','now') AS INTEGER)*1000); END""",
        )}
    }

    /**
     * A-27: give the candidate pair a COLUMN instead of a concatenated key.
     *
     * `candidateMetrics` used to join `physio_parallel_runs` on
     * `'PHYSIO_BASELINE:'||g.generation_id`. No index can serve a concatenation,
     * so SQLite drove the whole query off a full SCAN of
     * `physio_parallel_scores` — and once the pooling fix multiplied that by
     * every generation of a hypothesis instead of one, the pass held the single
     * SQLite connection for minutes on a real device and the main
     * screen would not open. Off-device the same query did not return in ten
     * minutes.
     *
     * Measured on a large pulled database after this migration: worst single
     * hypothesis low hundreds of ms, a full maintenance pass over every
     * (hypothesis, revision) pair a fraction of a second, migration itself
     * about a second. Plan is all SEARCH, no SCAN.
     *
     * The lesson is recorded because it is general: the pooling fix was verified
     * by MEANING («300 clusters instead of one») and never by COST. «Measure
     * before claiming» covers SQL.
     */
    fun ensureClosedInvalidationTracking(db:SQLiteDatabase) {
        DDL.filter{it.contains("physio_closed_input_invalidations_v1")||it.contains("physio_closed_invalidation_state_v1")}.forEach(db::execSQL)
        val existing=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'",null).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        closedInvalidationTriggers().filter{sql->existing.any{sql.contains(" ON $it ")}}.forEach(db::execSQL)
    }

    fun recordCheckpoint(db: SQLiteDatabase, checkpoint: DailyCheckpointV1, payloadJson: String) {
        db.execSQL(
            "INSERT OR IGNORE INTO physio_daily_checkpoints(checkpoint_id,supersedes_id,kind,known_at_ms,payload_json,closed) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(checkpoint.checkpointId, checkpoint.supersedesId, checkpoint.kind.name, checkpoint.knownAtMs, payloadJson, if (checkpoint.closed) 1 else 0),
        )
    }

    fun promotionState(db:SQLiteDatabase,id:String,asOfMs:Long):PromotionStateV1?=db.rawQuery(
        """SELECT revision,stage,bounded_effect_percent,reason,known_at_ms,evidence_json
           FROM physio_factor_promotion_ledger WHERE hypothesis_id=? AND known_at_ms<=?
           ORDER BY revision DESC LIMIT 1""",arrayOf(id,asOfMs.toString()),
    ).use{c->if(!c.moveToFirst())null else {
        val evidence=org.json.JSONObject(c.getString(5))
        PromotionStateV1(id,c.getInt(0),PromotionStageV1.valueOf(c.getString(1)),c.getDouble(2),c.getString(3),c.getLong(4),c.getInt(0).takeIf{it>1}?.minus(1),evidence.optJSONObject("typed_effect")?.let(::effectFromJson),evidence.optString("evaluation_hash").takeIf{it.isNotBlank()},evidence.optString("source_evidence_hash").takeIf{it.isNotBlank()})
    }}

    private fun foodEraPrior(db:SQLiteDatabase,id:String,nowMs:Long):PromotionStateV1? {
        val prior=promotionState(db,id,nowMs)?:return null
        val prefix=foodEraSourcePrefix()
        if(prior.sourceEvidenceHash?.startsWith(prefix)==true)return prior
        val next=prior.copy(revision=prior.revision+1,previousRevision=prior.revision,stage=PromotionStageV1.DEMOTED,
            boundedEffectPercent=0.0,effect=null,reason="evidence from outside the current food-era boundary excluded",knownAtMs=nowMs,
            evaluationHash="food-era-reset:$nowMs",sourceEvidenceHash="${prefix}legacy-reset:$nowMs")
        val json=org.json.JSONObject().put("source_evidence_hash",next.sourceEvidenceHash).put("food_era_reset",true).toString()
        db.execSQL("INSERT OR IGNORE INTO physio_factor_promotion_ledger(hypothesis_id,revision,known_at_ms,stage,bounded_effect_percent,reason,evidence_status,evidence_json) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(id,next.revision,nowMs,next.stage.name,0.0,next.reason,EvidenceStatus.CONFLICTING.name,json))
        return next
    }

    fun recordPromotionEvaluation(db:SQLiteDatabase,definition:HypothesisDefinitionV1,evidence:FactorEvidenceV1,nowMs:Long,prospectiveLossImprovement:Double?=null):PromotionStateV1 {
        val prior=foodEraPrior(db,definition.id,nowMs)
        val sourceHash="${foodEraSourcePrefix()}${evidence.status}:${evidence.rawExposures}:${evidence.usableEpisodes}:${evidence.effectPercent.independentDays}:${evidence.effectPercent.median}:${evidence.effectPercent.p10}:${evidence.effectPercent.p90}:${evidence.typedEffect}"
        if(prior?.sourceEvidenceHash==sourceHash)return prior
        if(prior!=null&&(evidence.effectPercent.lastUpdateMs?:nowMs)<=prior.knownAtMs)return prior
        if(prior?.stage in setOf(PromotionStageV1.SHADOW_CANDIDATE,PromotionStageV1.PROSPECTIVE_STABLE)&&evidence.status!=EvidenceStatus.CONFLICTING)return prior!!
        if(prior?.stage in setOf(PromotionStageV1.PROMOTED_MEDIAN,PromotionStageV1.MONITORED)&&evidence.status==EvidenceStatus.SUPPORTED)return prior!!
        val nextBase=PromotionLifecycleV1.next(definition.executableSpec,evidence,prior,prospectiveLossImprovement,nowMs)
        val next=nextBase.copy(effect=evidence.typedEffect?.let{boundedCandidateEffect(it,definition.executableSpec,evidence.effectPercent.independentDays)},sourceEvidenceHash=sourceHash)
        val json=org.json.JSONObject().put("raw",evidence.rawExposures).put("usable",evidence.usableEpisodes)
            .put("days",evidence.effectPercent.independentDays).put("provenance",evidence.evidenceProvenance.name)
            .put("typed_effect",next.effect?.let(::effectToJson)).put("source_evidence_hash",sourceHash).toString()
        db.execSQL("INSERT OR IGNORE INTO physio_factor_promotion_ledger(hypothesis_id,revision,known_at_ms,stage,bounded_effect_percent,reason,evidence_status,evidence_json) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(definition.id,next.revision,nowMs,next.stage.name,next.boundedEffectPercent,next.reason,evidence.status.name,json))
        return next
    }

    private fun effectToJson(e:EffectPayloadV1)=org.json.JSONObject().put("target",e.target.name).put("median",e.median).put("low",e.low).put("high",e.high)
    private fun effectFromJson(o:org.json.JSONObject):EffectPayloadV1 {
        val target=ParameterTargetV1.valueOf(o.getString("target"));val m=o.getDouble("median");val l=o.getDouble("low");val h=o.getDouble("high")
        return when(target.unit){"fraction"->EffectPayloadV1.Fraction(target,m,l,h);"minute"->EffectPayloadV1.Minutes(target,m,l,h);"mmol/L/hour"->EffectPayloadV1.Rate(target,m,l,h);"mmol/L"->EffectPayloadV1.Bias(target,m,l,h);else->error("bad unit")}
    }
    private fun effectPercent(e:EffectPayloadV1)=e.median/e.target.displayReference*100.0
    private fun boundedCandidateEffect(e:EffectPayloadV1,spec:ExecutableHypothesisSpecV1,days:Int):EffectPayloadV1 {
        val raw=effectPercent(e);val allowed=raw.coerceIn(-spec.maxMedianEffectPercent,spec.maxMedianEffectPercent)*(days.toDouble()/spec.rampInDays).coerceIn(0.0,1.0)
        val scale=if(kotlin.math.abs(raw)<1e-12)0.0 else allowed/raw
        return e.scaled(scale)
    }

    /** Observational readiness may freeze a candidate, but cannot claim SUPPORT or promotion. */
    fun recordObservationalCandidate(db:SQLiteDatabase,definition:HypothesisDefinitionV1,evidence:FactorEvidenceV1,nowMs:Long):PromotionStateV1 {
        require(definition.executableSpec.implementationStatus==ImplementationStatusV1.EXECUTABLE&&definition.executableSpec.applicationPolicy!=ApplicationPolicyV1.ATTRIBUTION_ONLY)
        require(evidence.evidenceProvenance==EvidenceProvenanceV1.RETROSPECTIVE_DESCRIPTIVE)
        require(evidence.status==EvidenceStatus.PRELIMINARY)
        require(evidence.usableEpisodes>=definition.executableSpec.minimumIndependentEpisodes&&evidence.effectPercent.independentDays>=definition.executableSpec.minimumIndependentDays)
        val effect=evidence.typedEffect?:error("observational candidate needs typed bounded payload")
        val sourceHash="${foodEraSourcePrefix()}observational:${evidence.rawExposures}:${evidence.usableEpisodes}:${evidence.effectPercent.independentDays}:${evidence.effectPercent.median}:${effect}"
        foodEraPrior(db,definition.id,nowMs)?.takeIf{it.sourceEvidenceHash==sourceHash}?.let{return it}
        val prior=foodEraPrior(db,definition.id,nowMs)
        // A direct measurement of a base parameter is not a candidate awaiting a
        // prospective verdict — see [ApplicationPolicyV1.MEASURED_BASE]. Two
        // consequences, and both are the point:
        //
        //  - it goes live once the SUPPORT gates pass, rather than waiting to
        //    beat an incumbent whose own provenance is worse;
        //  - and it is NOT frozen. Freezing protects a shadow candidate from
        //    being refitted mid-evaluation; here there is no evaluation to
        //    protect, and freezing would nail ISF to whatever the first six
        //    corrections said and ignore every one after.
        val measuredBase=definition.executableSpec.applicationPolicy==ApplicationPolicyV1.MEASURED_BASE
        if(!measuredBase&&prior?.stage in setOf(PromotionStageV1.SHADOW_CANDIDATE,PromotionStageV1.PROSPECTIVE_STABLE,PromotionStageV1.PROMOTED_MEDIAN,PromotionStageV1.MONITORED))return prior!!
        val bounded=boundedCandidateEffect(effect,definition.executableSpec,evidence.effectPercent.independentDays)
        val stage=if(measuredBase)PromotionStageV1.PROMOTED_MEDIAN else PromotionStageV1.SHADOW_CANDIDATE
        val next=PromotionStateV1(definition.id,(prior?.revision?:0)+1,stage,
            if(measuredBase)effectPercent(bounded) else 0.0,
            if(measuredBase)"direct measurement of the base parameter; capped, ramped by independent days, decayed"
            else "observational candidate readiness only; frozen future shadow required",
            nowMs,prior?.revision,bounded,null,sourceHash)
        val json=org.json.JSONObject().put("raw",evidence.rawExposures).put("usable",evidence.usableEpisodes).put("days",evidence.effectPercent.independentDays)
            .put("provenance",evidence.evidenceProvenance.name).put("typed_effect",effectToJson(bounded)).put("source_evidence_hash",sourceHash).toString()
        db.execSQL("INSERT OR IGNORE INTO physio_factor_promotion_ledger(hypothesis_id,revision,known_at_ms,stage,bounded_effect_percent,reason,evidence_status,evidence_json) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(definition.id,next.revision,nowMs,next.stage.name,next.boundedEffectPercent,next.reason,evidence.status.name,json))
        return next
    }

    /** Append-only immediate revocation when the evidence set that created an
     * observational candidate is no longer causally sufficient. */
    fun revokeObservationalCandidate(db:SQLiteDatabase,id:String,nowMs:Long,reason:String):PromotionStateV1? {
        val prior=promotionState(db,id,nowMs)?:return null
        val provisional=prior.stage in setOf(PromotionStageV1.SHADOW_CANDIDATE,PromotionStageV1.PROSPECTIVE_STABLE)
        // Closed-episode ISF is the one observational contract whose support
        // can be revoked by a later event revision/deletion.  If that support
        // disappears, an already-live effect must stop applying immediately;
        // keeping PROMOTED/MONITORED here would leave PhysioRuntime using a
        // coefficient that no longer has causal evidence.  Other hypotheses
        // retain their normal prospective promotion/demotion policy.
        // The `closed_episode_global_isf` exception died with its contract:
        // it existed so a LIVE measured base parameter stopped
        // applying the moment its evidence was withdrawn. Nothing is live now,
        // so only a provisional stage can be demoted.
        if(!provisional)return prior
        val next=prior.copy(revision=prior.revision+1,previousRevision=prior.revision,stage=PromotionStageV1.DEMOTED,
            boundedEffectPercent=0.0,effect=null,reason=reason,knownAtMs=nowMs,
            evaluationHash="revoked:$nowMs",sourceEvidenceHash="revoked:$nowMs")
        val json=org.json.JSONObject().put("source_evidence_hash",next.sourceEvidenceHash).put("revoked",true).toString()
        db.execSQL("INSERT OR IGNORE INTO physio_factor_promotion_ledger(hypothesis_id,revision,known_at_ms,stage,bounded_effect_percent,reason,evidence_status,evidence_json) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(id,next.revision,nowMs,next.stage.name,0.0,reason,EvidenceStatus.CONFLICTING.name,json))
        return next
    }

    fun demoteIfStale(db:SQLiteDatabase,definition:HypothesisDefinitionV1,nowMs:Long):PromotionStateV1? {
        val prior=promotionState(db,definition.id,nowMs)?:return null
        if(prior.stage !in setOf(PromotionStageV1.PROMOTED_MEDIAN,PromotionStageV1.MONITORED))return prior
        val staleAfter=(definition.executableSpec.decayHalfLifeDays*3.0*86_400_000.0).toLong()
        if(nowMs-prior.knownAtMs<=staleAfter)return prior
        val next=prior.copy(revision=prior.revision+1,previousRevision=prior.revision,stage=PromotionStageV1.DEMOTED,boundedEffectPercent=0.0,reason="automatic demotion: evidence stale beyond three half-lives",knownAtMs=nowMs,evaluationHash="stale:$nowMs")
        val json=org.json.JSONObject().put("typed_effect",prior.effect?.let(::effectToJson)).put("evaluation_hash",next.evaluationHash).put("source_evidence_hash",prior.sourceEvidenceHash).toString()
        db.execSQL("INSERT OR IGNORE INTO physio_factor_promotion_ledger(hypothesis_id,revision,known_at_ms,stage,bounded_effect_percent,reason,evidence_status,evidence_json) VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(definition.id,next.revision,nowMs,next.stage.name,0.0,next.reason,EvidenceStatus.INSUFFICIENT.name,json))
        return next
    }

}
