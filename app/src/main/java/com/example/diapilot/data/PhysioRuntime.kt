package com.example.diapilot.data

import com.diapilot.core.physio.*
import com.diapilot.core.hybrid.MacroTimingParamsV1
import com.diapilot.core.twin.PredictedPoint
import java.security.MessageDigest

object PhysioRuntime {
    private data class ArtifactCacheKey(
        val databaseIdentity:Int?,
        val dbPath:String?,
        val modelSha:String?,
        val maintenanceIdentity:String?,
        val closedEpisodeRevision:String,
        val checkpointRevision:Long,
        val promotionIdentity:String,
        /** Hand-entered parameters are model input like any other. A cache that
         * ignored them would keep serving the curve the user just replaced. */
        val manualIdentity:String,
        /**
         * THE SETTINGS-SCREEN TUNING, and it was missing.
         *
         * `HybridModelStore` installs `PhysioTuning.apply(model)` under the
         * ARTIFACT'S OWN sha, because the tuning is applied at install rather
         * than at read. So `modelSha256()` does not move when the user changes a knob,
         * `manualIdentity` tracks a different feature entirely
         * (`ManualInsulinRuntime`), and no ledger watermark shifts either —
         * every component of this key stayed put while the model underneath it
         * changed. The cache then served the PREVIOUS artifact, so the screen
         * said "applied", Hybrid V11 obeyed, and PHYSIO quietly did not.
         *
         * A comment in `HybridDomain` claimed the overrides «enter the artifact
         * hash». They do not — they ride on the model, which is a different
         * thing, and this field is what makes the claim true for the cache.
         */
        val tuningIdentity:String,
        /**
         * The causal WATERMARK, not the raw cutoff: the newest known-at that is
         * at or before `asOfMs` across the ledgers this artifact reads.
         *
         * Keyed on the exact millisecond the cache missed on every call, and the
         * closed-episode rebuild asks for the artifact once per FOOD and BOLUS
         * event — ~457 rebuilds per pass, each running the promotion state of 27
         * hypotheses plus checkpoints, global CS and maintenance. Measured:
         * a couple of seconds per episode window, over two minutes for a full pass.
         *
         * Bucketing to the day was tried first and is WRONG: it lets a later
         * revision answer an earlier replay, which is precisely what
         * `artifactIdentityIsAsOfStateAndLateRevisionCannotRewriteEarlierRun`
         * exists to forbid — and that test caught it. The watermark is exact
         * instead of approximate: two cutoffs with no ledger write between them
         * cannot produce different artifacts, so sharing one is not a
         * relaxation, it is the same answer computed once.
         */
        val causalWatermark:Long,
    )
    @Volatile private var artifactCache:Pair<ArtifactCacheKey,PhysioArtifactV1?>?=null
    /** Stage-8 development artifact. It is intentionally independent of the
     * v11 dish-amplitude maps. The point CS is the bundled synthetic example
     * person's value; its wide interval preserves the usual disagreement between
     * recorded grams and grams confirmed against a weighed recipe. */
    private const val STAGE8_GLOBAL_CS_PRIOR=0.165
    private const val STAGE8_GLOBAL_CS_LOW=0.12
    private const val STAGE8_GLOBAL_CS_HIGH=0.226

    /**
     * The carbohydrate-sensitivity prior, now scaled by body weight.
     *
     * `STAGE8_GLOBAL_CS_PRIOR` used to be one number for every body. One gram of glucose
     * distributes through a volume proportional to mass, so that constant was
     * silently assuming one — see [com.diapilot.core.analysis.CarbSensitivityPriorV1]
     * for the derivation and for the three-tier design this is tier one of.
     *
     * The BAND keeps its previous width relative to the median (0.73x..1.37x
     * around the point value) rather than being re-derived: nothing was measured about the
     * spread, and pretending otherwise would smuggle a second change in beside
     * the first. With no weight recorded the whole thing reproduces the old
     * constants to the digit.
     */
    internal fun foodDynamicsGlobalPriorV1(weightKg: Double? = null): PosteriorV1 {
        // NO ROUND TRIP WHEN THERE IS NO WEIGHT. The first version reproduced
        // the old constant by converting it to an implied weight and back, and
        // floating point returned a value one ulp away from it — two integration tests
        // that pin the bundled artifact's exact contract caught it. «Unchanged»
        // has to mean bit-for-bit here, because those tests exist to notice a
        // silent drift in what ships.
        val median = if (weightKg == null) STAGE8_GLOBAL_CS_PRIOR
        else com.diapilot.core.analysis.CarbSensitivityPriorV1.fromWeight(weightKg)
        val scale = median / STAGE8_GLOBAL_CS_PRIOR
        return PosteriorV1(median, STAGE8_GLOBAL_CS_LOW * scale, STAGE8_GLOBAL_CS_HIGH * scale, 0, 0, null)
    }
    private fun artifactId(stateIdentity: String): String {
        val base = HybridShadowRegistry.modelSha256()?.take(16) ?: "unhashed"
        val stateHash = MessageDigest.getInstance("SHA-256")
            .digest(stateIdentity.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        // THE FOOD SHAPE'S GENERATION IS PART OF THE IDENTITY. `base` is the
        // model JSON's hash and `stateHash` the learned state; a change to the
        // engine's own constants moves neither, so at one point editing a
        // carb triangle wrote a different model into `physio_parallel_runs`
        // under an unchanged artifact id, and the paired A/B averaged two food
        // models without saying so.
        return "physio-v1:$base:${com.diapilot.core.hybrid.CarbTrianglesV1.GENERATION}:state-$stateHash"
    }
    fun priorArtifactId(): String = artifactId("prior-only")

    /**
     * The artifact is immutable for a concrete model-input revision and
     * causal minute.  Foreground/chart code asks for it repeatedly during a
     * single render; rebuilding it used to repeat the promotion/checkpoint
     * queries dozens of times.  The key contains the maintenance identity and
     * the closed-episode revision so a newly learned correction CDF cannot be
     * hidden behind this cache.
     */
    fun artifact(store: com.diapilot.core.collector.CollectorStore? = null, asOfMs: Long = Long.MAX_VALUE): PhysioArtifactV1? {
        val sqlite=(store as? SqliteCollectorStore)?.readableDatabase
        val manualIdentity=ManualInsulinRuntime.identity((store as? SqliteCollectorStore)?.appContext)
        val tuningIdentity=(store as? SqliteCollectorStore)?.appContext
            ?.let { PhysioTuning.identity(PhysioTuning.read(it)) } ?: ""
        val key=sqlite?.let { db ->
            val maintenance=""  // maintenance was removed; nothing left in the cache key to change
            // Cheap columns only. This used to LIKE-scan `payload_json`, and by
            // at one point that meant reading megabytes of receipts on EVERY call —
            // and the foreground asks for the artifact many times per render.
            // Max known-at plus the row count moves whenever a receipt is
            // written or revoked, which is all a cache key needs.
            val closed=db.rawQuery(
                "SELECT COALESCE(MAX(known_at_ms),0),COUNT(*) FROM physio_closed_episodes",null,
            ).use { c -> if(c.moveToFirst())"${c.getLong(0)}:${c.getLong(1)}" else "0:0" }
            // These two ledgers can change independently of the maintenance
            // input vector (for example, an append-only promotion/revocation
            // in the same process).  Their cheap monotone revisions are the
            // cache invalidation signal; using only a database path made
            // separate in-memory test databases share a stale artifact.
            val checkpoint=db.rawQuery(
                "SELECT COALESCE(MAX(known_at_ms),0) FROM physio_daily_checkpoints",
                null,
            ).use { c -> if(c.moveToFirst())c.getLong(0)else 0L }
            val promotion=db.rawQuery(
                "SELECT COALESCE(group_concat(hypothesis_id || ':' || revision || ':' || stage || ':' || known_at_ms,'|'),'') FROM physio_factor_promotion_ledger ORDER BY hypothesis_id,revision",
                null,
            ).use { c -> if(c.moveToFirst())c.getString(0)else "" }
            // One query for all of them: the artifact can only change where one
            // of these ledgers gained a row.
            val watermark=db.rawQuery(
                "SELECT COALESCE(MAX(k),0) FROM (" +
                    "SELECT MAX(known_at_ms) k FROM physio_daily_checkpoints WHERE known_at_ms<=?1 " +
                    "UNION ALL SELECT MAX(known_at_ms) FROM physio_factor_promotion_ledger WHERE known_at_ms<=?1 " +
                    "UNION ALL SELECT MAX(known_at_ms) FROM physio_closed_episodes WHERE known_at_ms<=?1 " +
                    "UNION ALL SELECT MAX(known_at_ms) FROM physio_closed_isf_evidence_v1 WHERE known_at_ms<=?1)",
                arrayOf(asOfMs.toString()),
            ).use { c -> if(c.moveToFirst())c.getLong(0) else 0L }
            ArtifactCacheKey(System.identityHashCode(db),db.path,HybridShadowRegistry.modelSha256(),maintenance,closed,checkpoint,promotion,manualIdentity,tuningIdentity,watermark)
        } ?: ArtifactCacheKey(null,null,HybridShadowRegistry.modelSha256(),null,"0:0",0L,"",manualIdentity,tuningIdentity,asOfMs)
        artifactCache?.takeIf { it.first==key }?.let { return it.second }
        val tBuild = android.os.SystemClock.elapsedRealtime()
        val changed = artifactCache?.first?.let { old ->
            listOfNotNull(
                "maintenance".takeIf { old.maintenanceIdentity != key.maintenanceIdentity },
                "closed".takeIf { old.closedEpisodeRevision != key.closedEpisodeRevision },
                "checkpoint".takeIf { old.checkpointRevision != key.checkpointRevision },
                "promotion".takeIf { old.promotionIdentity != key.promotionIdentity },
                "manual".takeIf { old.manualIdentity != key.manualIdentity },
                "tuning".takeIf { old.tuningIdentity != key.tuningIdentity },
                "watermark".takeIf { old.causalWatermark != key.causalWatermark },
                "model".takeIf { old.modelSha != key.modelSha },
            ).joinToString(",").ifEmpty { "db" }
        } ?: "cold"
        return buildArtifact(store,asOfMs).also {
            artifactCache=key to it
            val ms = android.os.SystemClock.elapsedRealtime() - tBuild
            if (ms >= 200) android.util.Log.i(
                "PhysioRuntime", "artifact rebuilt in $ms ms — changed: $changed",
            )
        }
    }

    private fun buildArtifact(store: com.diapilot.core.collector.CollectorStore? = null, asOfMs: Long = Long.MAX_VALUE): PhysioArtifactV1? = HybridShadowRegistry.model()?.let { base ->
        // READ HERE TOO, not borrowed from the caller. `artifact()` computes the
        // same string for its CACHE key; this one goes into the artifact's own
        // id, and the two are different jobs — a cache that misses is slow, an
        // id that collides makes the paired A/B average two models.
        val tuningIdentity = (store as? SqliteCollectorStore)?.appContext
            ?.let { PhysioTuning.identity(PhysioTuning.read(it)) } ?: ""
        val bounds=PhysioBoundsV1()
        val safeOnset=base.insulin.onsetMin.coerceIn(bounds.insulinOnsetMinRange)
        val safePeak=base.insulin.peakMin.coerceIn(maxOf(safeOnset+1,bounds.insulinPeakMinRange.start)..bounds.insulinPeakMinRange.endInclusive)
        val safeTail=base.insulin.tailDurationMin.coerceIn(maxOf(safePeak+2,bounds.insulinTailMinRange.start)..bounds.insulinTailMinRange.endInclusive)
        val safeShort=base.insulin.shortDurationMin.coerceIn(safePeak+1..safeTail)
        val shape=base.food.defaultShape
        val safeDelay=shape.delayMin.coerceIn(bounds.absorptionOnsetMinRange)
        val safeFoodPeak=shape.peakMin.coerceIn(maxOf(safeDelay+1,bounds.absorptionPeakMinRange.start)..bounds.absorptionPeakMinRange.endInclusive)
        val safeFoodTail=shape.durationMin.coerceIn(maxOf(safeFoodPeak+1,bounds.absorptionTailMinRange.start)..bounds.absorptionTailMinRange.endInclusive)
        val safeBase=base.copy(insulin=base.insulin.copy(isf=base.insulin.isf.coerceIn(bounds.isfMmolPerLUmin,bounds.isfMmolPerLUmax),
            isfLow=base.insulin.isfLow.coerceIn(bounds.isfMmolPerLUmin,bounds.isfMmolPerLUmax),isfHigh=base.insulin.isfHigh.coerceIn(bounds.isfMmolPerLUmin,bounds.isfMmolPerLUmax),
            onsetMin=safeOnset,peakMin=safePeak,shortDurationMin=safeShort,tailDurationMin=safeTail),
            food=base.food.copy(defaultShape=shape.copy(delayMin=safeDelay,peakMin=safeFoodPeak,durationMin=safeFoodTail)))
        val sqlite=(store as? SqliteCollectorStore)?.readableDatabase
        // Cold start is the model's broad parametric prior.  As independently
        // observed correction traces accumulate, replace only its timing CDF;
        // ISF remains a separately inferred, context-sensitive state.
        // ONE path to person.insulin. The receipt-CDF aggregation that used to
        // sit here was a second implementation of the same quantity, computed by
        // a different method on a different corpus — see InsulinProfileRuntime.
        val profileState=sqlite?.let{runCatching{InsulinProfileRuntime.state(store!!,it,asOfMs)}.getOrNull()}
        // THE SEGMENT READER'S OWN DIAGNOSTICS. It computes the arm,
        // the per-landmark n and the refusal counts — the whole answer to
        // "is the shape fitted correctly" — and only the settings screen ever saw
        // them. One line at startup makes the audit repeatable from a log.
        profileState?.let { st ->
            val p = st.profile
            android.util.Log.i(
                "PhysioTuning",
                "segments: doses ${st.dosesConsidered}" +
                    (p?.let {
                        " · arm ${it.arm}" +
                            " · onset n=${it.onset.on(it.arm)?.samples ?: 0}/${it.onset.pooled?.samples ?: 0}" +
                            " · visible fall n=${it.visibleFall.on(it.arm)?.samples ?: 0}" +
                            " · peak n=${it.peakRate.on(it.arm)?.samples ?: 0}/${it.peakRate.pooled?.samples ?: 0}" +
                            " · applied slowdown %.0f (n=%d) · end %.0f (n=%d)".format(
                                it.slowdown?.minute ?: -1.0, it.slowdown?.samples ?: 0,
                                it.tailEnd?.minute ?: -1.0, it.tailEnd?.samples ?: 0,
                            ) +
                            (it.orderingConflict?.let { c -> " · CONFLICT: $c" } ?: "") +
                            // WHAT CONDITIONING THE LATE PAIR WOULD DO. Measured
                            // only; the curve still uses the pooled numbers above.
                            (it.slowdownConditioned?.let { c ->
                                " · [slowdown if conditioned] no food %.0f (n=%d) / with food %.0f (n=%d) / all %.0f (n=%d)".format(
                                    c.flat?.minute ?: -1.0, c.flat?.samples ?: 0,
                                    c.rising?.minute ?: -1.0, c.rising?.samples ?: 0,
                                    c.pooled?.minute ?: -1.0, c.pooled?.samples ?: 0,
                                )
                            } ?: "") +
                            (it.tailConditioned?.let { c ->
                                " · [end if conditioned] no food %.0f (n=%d) / with food %.0f (n=%d) / all %.0f (n=%d)".format(
                                    c.flat?.minute ?: -1.0, c.flat?.samples ?: 0,
                                    c.rising?.minute ?: -1.0, c.rising?.samples ?: 0,
                                    c.pooled?.minute ?: -1.0, c.pooled?.samples ?: 0,
                                )
                            } ?: "")
                    } ?: " · profile not assembled") +
                    (if (st.refusals.isEmpty()) "" else
                        " · refusals: " + st.refusals.entries.sortedByDescending { it.value }
                            .joinToString(", ") { "${it.key} x${it.value}" }),
            )
        }
        val personalCurve=profileState?.curve
        val measured=InsulinCurveRuntime.applyWithReason(safeBase,personalCurve)
        val measuredMechanics=measured.model
        // P1 last, so what the user entered by hand overrides what we
        // measured — and reaches deconvolution, IOB and What-if by the same
        // single path, because they all read `person.insulin`.
        val manualContext=(store as? SqliteCollectorStore)?.appContext
        // Until this person's own segments replace it, the shipped insulin block
        // is a PRIOR and must carry a prior's uncertainty — see InsulinPriorV1.
        val widened=InsulinPriorV1.widen(measuredMechanics)
        // WHICH ISF IS IN FORCE — asked once, here, and never inferred.
        //
        // When the user has chosen the adaptive fit, the hand ISF is withheld from the
        // resolver rather than overridden after it: two layers both writing
        // `insulin.isf` is exactly the shape of the bug that made "configured"
        // and "applied" disagree for weeks. The hand TIMINGS still go through
        // untouched — this switch is about one axis.
        val adaptiveIsf=IsfSource.adaptiveInForce(manualContext)
            ?.coerceIn(bounds.isfMmolPerLUmin,bounds.isfMmolPerLUmax)
        val manualParams=ManualInsulinRuntime.params(manualContext)
            .let{if(adaptiveIsf!=null)it.copy(isfMmolPerU=null)else it}
        val resolution=ManualInsulinRuntime.resolve(widened,manualParams,personalCurve)
        // WHY A HAND-SET VALUE DID NOT ARRIVE.
        //
        // The resolver already computes `rejected` and `divergences` — the exact
        // answer to "I set one thing, but a different set of landmarks applied" — and every
        // caller threw them away. The startup log said what was CONFIGURED and
        // what was APPLIED and left the gap between them unexplained, which is
        // the same shape of defect M-132 recorded and did not close.
        // UNCONDITIONAL. The tier IS the answer to «where did the applied
        // number come from», and printing it only on a rejection meant the
        // ordinary case — the one that is actually in force — said nothing.
        android.util.Log.i(
            "PhysioTuning",
            "manual params: shape=${resolution.shapeTier} ISF=${resolution.isfTier}" +
                " · resolver ISF ${resolution.isfMmolPerU?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "none"}" +
                " · before manual ${"%.3f".format(java.util.Locale.ROOT, widened.insulin.isf)}" +
                (if(resolution.rejected.isEmpty()) "" else " · REJECTED: ${resolution.rejected.joinToString(", ")}") +
                (if(resolution.divergences.isEmpty()) "" else " · divergences: ${resolution.divergences.size}"),
        )
        val mechanics=ManualInsulinRuntime.apply(widened,resolution).let{m->
            // The learned value lands the same way a hand-set one does: as a
            // better CENTRE, with the band rescaled around it rather than
            // collapsed. A zero-width ISF band narrows the forecast corridor and
            // with it the hypo alert's own margin, whoever supplied the number.
            if(adaptiveIsf==null)m else{
                val scale=if(m.insulin.isf>0.0)adaptiveIsf/m.insulin.isf else 1.0
                m.copy(insulin=m.insulin.copy(
                    isf=adaptiveIsf,
                    isfLow=(m.insulin.isfLow*scale).coerceAtLeast(1e-3),
                    isfHigh=(m.insulin.isfHigh*scale).coerceAtLeast(m.insulin.isfLow*scale),
                ))
            }
        }
        val mechanicsCoerced=safeBase.insulin!=base.insulin||safeBase.food.defaultShape!=base.food.defaultShape
        // HYPOTHESIS MODIFIERS REMOVED, AND THIS IS PROVABLY NEUTRAL.
        //
        // Here thirteen registry hypotheses were checked for the PROMOTED_MEDIAN
        // or MONITORED stage. Across the whole database's history, EXACTLY ONE
        // contract ever reached those stages — closed_episode_global_isf, several
        // hundred revisions — and it has just been removed. No registry hypothesis
        // has or ever had a single such row, so the list was always empty.
        //
        // An empty list makes both `personModelAt` (context filter) and
        // `varianceAt` (conditional variance) a no-op. So along with it, the
        // contextIds that PhysioExposureEvaluator computed on every event are
        // also inert.
        val promoted = emptyList<AppliedModifierV1>()
        // `global_cs` REMOVED FROM THE MODEL PATH. Carb sensitivity
        // is a CONSTANT in the current concept, and it already was one in fact:
        // this contract never left SHADOW_CANDIDATE in the whole database (one
        // row), so `globalCsScale` was always 0 and `baseCs` was always the
        // prior. The learner beside it said so itself — «its median must not
        // enter the live arm until the promotion gate explicitly promotes».
        // Removed rather than left inert, so nothing can quietly promote it.
        // `closed_episode_global_isf` REMOVED FROM THE MODEL PATH.
        //
        // The second amplitude learner. It read the corpus of TAGGED corrections,
        // aggregated a handful of doses into a single percent lift and applied it — the
        // previous concept, where ISF was learned from labelled corrections. The
        // current one learns it from the day's balance (`DailyBalanceIsfV1`),
        // and the walk-forward that day rejected 2.50 outright: zero days better
        // out of twelve on shape, and twice the false hypo alarms.
        //
        // It was the ONLY contract that ever reached PROMOTED_MEDIAN — every
        // other one in the ledger is DESCRIPTIVE_ONLY. So this deletion also
        // empties the promotion path of live users; the framework stays for now
        // and is removed separately, on its own evidence.
        val selected = (store as? SqliteCollectorStore)?.readableDatabase?.rawQuery(
            "SELECT checkpoint_id,known_at_ms,payload_json FROM physio_daily_checkpoints WHERE known_at_ms>=? AND known_at_ms<=? ORDER BY known_at_ms DESC,checkpoint_id DESC LIMIT 1", arrayOf(FoodEraSettings.current().startMs.toString(),asOfMs.toString()),
        )?.use { c -> if(c.moveToFirst()) Triple(c.getString(0), c.getLong(1), c.getString(2)) else null }
        val identified = selected?.third?.let { org.json.JSONObject(it) }
        fun parsedPosterior(name:String,defaultHalf:Double):PosteriorV1 {
            val p=identified?.optJSONObject(name)?:return PosteriorV1(0.0,-defaultHalf,defaultHalf,0,0,null)
            return PosteriorV1(p.getDouble("median"),p.getDouble("p10"),p.getDouble("p90"),p.optInt("n"),p.optInt("days",0),selected?.second)
        }
        val storedState=DailyResponseStateV1(parsedPosterior("effective",30.0),parsedPosterior("identified_isf",20.0),
            identified?.optString("isf_status")?.takeIf{it.isNotBlank()}?.let{EvidenceStatus.valueOf(it)}?:EvidenceStatus.INSUFFICIENT)
        val transitioned=if(selected!=null&&asOfMs!=Long.MAX_VALUE)DailyResponseEstimatorV1.carryForward(storedState,asOfMs) else storedState
        val isfStatus = transitioned.identifiedIsfStatus.name
        val isfPct = transitioned.identifiedIsfPercent
        val nIsf = isfPct.identifyingEpisodes
        val canUpdateIsf = nIsf >= 6 && isfPct.independentDays >= 3 && isfStatus == EvidenceStatus.SUPPORTED.name
        val baseCsRaw=base.food.globalFactor
        val baseCsBounded=baseCsRaw.coerceIn(bounds.globalCsMmolPerLGMin,bounds.globalCsMmolPerLGMax)
        val stage8Prior=STAGE8_GLOBAL_CS_PRIOR.coerceIn(bounds.globalCsMmolPerLGMin,bounds.globalCsMmolPerLGMax)
        val baseCs=stage8Prior
        val conflicts=buildList{
            add("v11 global CS $baseCsRaw is not used as an independent PHYSIO anchor")
            if(baseCsRaw!=baseCsBounded)add("legacy global CS prior $baseCsRaw coerced to physiological bound $baseCsBounded")
            if(mechanicsCoerced)add("legacy mechanics coerced to PHYSIO bounds; model/data conflict")
            // A measured curve that exists but did not reach the model is the
            // one state nobody could see. It has to be a conflict, not a
            // silently unchanged person model.
            measured.refusal?.let{add("measured insulin curve NOT applied: $it")}
            if(InsulinPriorV1.isPrior(mechanics))add(
                "insulin profile is a population prior, not this person's data; " +
                    "ISF band widened to ${InsulinPriorV1.ISF_LOW_MMOL_PER_U}..${InsulinPriorV1.ISF_HIGH_MMOL_PER_U} mmol/L per U",
            )
            // The moved landmarks in their data form (no Context here): "TAIL_END 110→120".
            personalCurve?.coerced?.takeIf{it.isNotEmpty()}?.let{add("measured curve coerced to bounds: ${it.joinToString(", "){c->"%s %.0f→%.0f".format(java.util.Locale.ROOT,c.landmark.name,c.fromMin,c.toMin)}}")}
        }
        fun scaled(pct: Double): Double = (mechanics.insulin.isf * (1.0 + pct / 100.0)).coerceIn(bounds.isfMmolPerLUmin,bounds.isfMmolPerLUmax)
        // A HAND-SET ISF IS FINAL — see PhysioArtifactV1.isfPinnedByHand.
        //
        // The pin is read from the tuning knob rather than from P1 because that
        // is where the user sets it and where the startup log reads it; P1's own ISF,
        // when it exists, is already resolved into `mechanics.insulin.isf`
        // above and is equally a hand-set number.
        // The VALUE, not just the fact of a pin. Suppressing the learner is not
        // enough: the pinned number reaches `mechanics.insulin.isf` only via
        // `PhysioTuning.apply` at install time, so an artifact built through any
        // other path would suppress the learner and still run the bundled ISF.
        // A test caught exactly that. Read here, the pin is authoritative
        // wherever the artifact is built.
        // ONE DOOR. Read from `ManualInsulinRuntime`, the same
        // place the hand timings live — and by this point `apply` above has
        // ALREADY put that value into `mechanics.insulin.isf`, rescaling the
        // band around it. So this read decides only whether the learner is
        // suppressed and its answer offered; it no longer supplies the number.
        // Under the adaptive choice nothing is pinned: the learner owns the
        // axis, so `isfPinnedByHand` must read false and the card must stop
        // saying the user's number is protected when it is not being used.
        val pinnedIsf = runCatching {
            if (adaptiveIsf != null) null else manualParams.isfMmolPerU
        }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(bounds.isfMmolPerLUmin, bounds.isfMmolPerLUmax)
        val isfPinnedByHand = pinnedIsf != null
        val learnedIsfPosterior = if (canUpdateIsf) PosteriorV1(
            scaled(isfPct.median), minOf(scaled(isfPct.p10),scaled(isfPct.median)), maxOf(scaled(isfPct.p90),scaled(isfPct.median)), nIsf, isfPct.independentDays, asOfMs,
        ) else null
        // THE PIN WINS. `heldPosterior` is the hand-set number, untouched; the
        // learner's answer is carried beside it so the card can offer the swap.
        val heldPosterior = PosteriorV1(mechanics.insulin.isf, minOf(mechanics.insulin.isfLow,mechanics.insulin.isf), maxOf(mechanics.insulin.isfHigh,mechanics.insulin.isf), 0, 0, null)
        // THE BAND IS NOT COLLAPSED. This used to publish
        // `PosteriorV1(pinned, pinned, pinned)` — zero width — which is exactly
        // what `ManualInsulinRuntime.apply` refuses to do one screen away, and
        // for the reason stated there: a zero-width ISF band narrows every
        // forecast corridor and with it the hypo alert's own margin. A hand-set
        // point value is a better centre than ours; it is not a claim of
        // certainty. `heldPosterior` is that centre with the band P1 rescaled.
        // A FOURTH DOOR TO ISF, CLOSED. Found by the user's question:
        // "why do we need DailyResponseEstimatorV1, we dropped the corrections
        // tool — did we forget to clip it?"
        //
        // Not forgotten: it is a DIFFERENT subsystem, not the correction corpus
        // that was removed. But it is a door to the same axis — and it silently
        // predated the one that was chosen.
        //
        // It used to read: `if (pinnedIsf != null) heldPosterior else (learnedIsfPosterior ?: heldPosterior)`.
        // With adaptive ISF enabled, `pinnedIsf` is null (adaptive owns the
        // axis), so the branch fell through to `learnedIsfPosterior` — the
        // answer from `DailyResponseEstimatorV1`, which compares the DISPLAYED
        // forecast against fact. So the user chooses "learn from the day's balance",
        // and a different, unrequested teacher would be applied instead.
        //
        // Today this is dormant: on-device it reads `NOT_IDENTIFIABLE`, with too
        // few samples and days against the gate of n>=6 and days>=3. That is exactly
        // why it was worth fixing now — a defect that would wake up on its own
        // once enough corrections accumulated, and silently swap the applied
        // number with no line on screen to show it.
        //
        // THE ISF AXIS HAS EXACTLY TWO DOORS: the hand value and the daily
        // balance. Both arrive here through `mechanics.insulin.isf`, i.e.
        // through `heldPosterior`. `DailyResponseEstimatorV1` remains an
        // OBSERVATION: it still writes checkpoints and feeds the daily-discrepancy
        // card, and its answer is printed alongside as the "learned alternative" —
        // but only a person can apply it, not the `?:` branch.
        val isfPosterior = heldPosterior
        // The alternative is shown ALWAYS when the learner has computed
        // something — not only on a manual pin. It used to hide under the
        // adaptive choice because it was applied then; now it is never
        // applied, so there is nothing to hide, and showing it is useful.
        val isfAlternative = learnedIsfPosterior
        val promotionIdentity=promoted.sortedBy{it.hypothesisId}.joinToString("|"){"${it.hypothesisId}:${it.promotionRevision}:${it.effect.target}:${it.effect.median}:${it.evaluationHash}"}
        val macro=macroTiming(promoted)
        val globalEffect: EffectPayloadV1? = null
        // Context modifier uncertainty is applied by PhysioArtifactV1.varianceAt
        // only while the shared typed exposure predicate is active.
        // THE DEGRADED-CORRIDOR BRANCH WAS REMOVED, AFTER MEASUREMENT.
        //
        // `maintenanceUnhealthy` widened the forecast variance when the
        // learning chain broke or went stale. The logic was correct while
        // the chain was teaching something. There is nothing left to teach,
        // and the signal would have kept widening the corridor because of a
        // subsystem's staleness that no longer affects anything.
        //
        // Removing it narrows the corridor, which is the dangerous direction,
        // so it was measured rather than just decided: on-device, dozens of
        // maintenance snapshots, ZERO errors, with the next lifecycle deadline
        // still well out — meaning the degraded branch was unreachable and had
        // never actually fired.
        val variance=varianceWithPromotions(com.diapilot.core.physio.PHYSIO_BASE_VARIANCE_HEALTHY_V1,promoted)
        PhysioArtifactV1(
            artifactId((selected?.let { "${it.first}|${it.second}|${it.third}|asof=$asOfMs|transitioned=$isfPct" } ?: "prior-only")+"|promotions=$promotionIdentity|tuning=$tuningIdentity|safe=${mechanics.insulin}:${mechanics.food.defaultShape}|personal-cdf=${personalCurve?.observations}/${personalCurve?.independentDays}|food=macro-mixture-v3-no-dish-curves|cs=$baseCs"), mechanics,
            PosteriorV1(baseCs,minOf(baseCs,STAGE8_GLOBAL_CS_LOW),maxOf(baseCs,STAGE8_GLOBAL_CS_HIGH),0,0,null),
            isfPosterior,
            variance = variance,
            macroTiming=macro,
            bounds=bounds,
            promotedModifiers=promoted,
            conflictFlags=conflicts,
            isfPinnedByHand=isfPinnedByHand,
            learnedIsfAlternative=isfAlternative,
        )
    }

    // FAT -> TIME TO PEAK, MEASURED ON THE USER'S CORPUS.
    //
    // The other three stay at zero and that is not caution, it is what the data
    // said:
    // - `proteinDelayMinPer10g` — onset gave fat +3.4 and protein +3.2 min per
    // 10 g with intervals of -1.2..+9.0 and -6.9..+15.9. Undetermined;
    // - both TAIL coefficients — the corpus carries no tail landmark at all,
    // so «when does the tail end» has no observable to fit against;
    // - protein's own peak coefficient came out NEGATIVE at -8.6, and its
    // correlation with fat is 0.81. That is one effect split between two
    // collinear predictors, not a second effect.
    //
    // The one that survived: OLS over 48 episodes with CARBS held as a control
    // so «more fat» cannot smuggle in «more meal», giving +17.0 min per 10 g,
    // bootstrap 10-90% +8.4..+25.5, positive in 600 of 600 draws. Inside the
    // type's own 0..30 bound rather than clipped by it.
    //
    // Shipped as a measured constant with its provenance, the same way the carbSens
    // default was — the promotion ledger's `fat_absorption` contract has never
    // left DESCRIPTIVE_ONLY, and waiting for it would keep a measured number out
    // of the model indefinitely. It is a TIMING coefficient, so the food-shape
    // suspension of «shadow-safe first» covers it; amplitude is untouched.
    /** Kept as the named provenance of the value that now lives in
     *  [com.diapilot.core.hybrid.PHYSIO_SHIPPED_MACRO_TIMING_V1]. */
    const val FITTED_FAT_PEAK_MIN_PER_10G = 17.0

    /**
     * RETIRED, then REINSTATED the same evening — and the round
     * trip is the finding, not an embarrassment.
     *
     * It was retired because the caloric queue appeared to explain everything
     * the fitted +17 min per 10 g explained:
     *
     *     gram queue, no slope      Spearman +0.43
     *     gram queue + slope 17            +0.48   <- what originally shipped
     *     CALORIC queue, no slope          +0.52
     *     caloric queue + slope 17         +0.53   <- +0.01, so the slope went
     *
     * That was measured on the PEAK alone. The same queue's TAIL was refuted by
     * the user's own meals hours later: across many multi-dish meals it still held
     * 20-30% of the carbohydrate unarrived at +7..+9 h while the glucose had
     * fallen low with no insulin left. The repair is gastric sieving
     * ([PERSONAL_CARB_SIEVING_V1] = 0.65) — and sieving is exactly what takes
     * the PEAK delay back out of the queue, because carbohydrate that passes
     * the fat is no longer waiting behind it:
     *
     *     sieve 0.65, no slope             +0.47
     *     sieve 0.65 + slope 17            +0.52   <- +0.05, and it is back
     *
     * So the two were never substitutes. Duodenal feedback slows the pylorus
     * (a PEAK effect, the slope); sieving lets the sugar past the fat (a TAIL
     * effect, the queue). The queue was carrying both under one name, which is
     * why it looked like a replacement and why its tail was wrong.
     */
    private fun macroTiming(modifiers:List<AppliedModifierV1>):MacroTimingParamsV1 =
        com.diapilot.core.hybrid.PHYSIO_SHIPPED_MACRO_TIMING_V1

    private fun varianceWithPromotions(base:VarianceLedgerV1,mods:List<AppliedModifierV1>,globalCsEffect:EffectPayloadV1?=null):VarianceLedgerV1 {
        fun width(e:EffectPayloadV1)=kotlin.math.abs(e.high-e.low)/2.0
        fun normalized(e:EffectPayloadV1)=when(e.target.unit){"fraction"->width(e);"minute"->width(e)/180.0;"mmol/L/hour"->width(e);"mmol/L"->width(e);else->0.0}
        fun extra(vararg targets:ParameterTargetV1)=kotlin.math.sqrt(mods.filter{it.effect.target in targets}.sumOf{normalized(it.effect)*normalized(it.effect)})
        fun combine(a:Double,b:Double)=kotlin.math.sqrt(a*a+b*b).coerceAtMost(.8)
        val cs=globalCsEffect?.let(::normalized)?:0.0
        return base.copy(
            carbAmount=combine(base.carbAmount,cs),
            foodTiming=combine(base.foodTiming,extra(ParameterTargetV1.FOOD_ONSET,ParameterTargetV1.FOOD_PEAK,ParameterTargetV1.FOOD_TAIL)),
            insulinTiming=combine(base.insulinTiming,extra(ParameterTargetV1.ISF_MULTIPLIER,ParameterTargetV1.INSULIN_ONSET,ParameterTargetV1.INSULIN_PEAK,ParameterTargetV1.INSULIN_TAIL,ParameterTargetV1.INSULIN_POTENCY)),
            backgroundHepatic=combine(base.backgroundHepatic,extra(ParameterTargetV1.BACKGROUND_RATE)),
            basalMismatch=combine(base.basalMismatch,extra(ParameterTargetV1.BASAL_MISMATCH)),
            sensorProcess=combine(base.sensorProcess,extra(ParameterTargetV1.SENSOR_BIAS,ParameterTargetV1.SENSOR_PROCESS_VARIANCE)),
        )
    }

}

object ForecastComparisonRegistry {
    data class PairResult(val anchorMs: Long, val legacy: List<PredictedPoint>, val physio: List<PredictedPoint>, val inputHash: String)
    @Volatile private var latest: PairResult? = null
    fun update(value: PairResult) { latest = value }
    fun at(anchorMs: Long): PairResult? = latest?.takeIf { it.anchorMs == anchorMs }
    fun current(): PairResult? = latest
}
