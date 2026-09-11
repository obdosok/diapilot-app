package io.github.obdosok.diapilot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.physio.*
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.i18n.localized
import java.util.Calendar
import kotlin.math.abs

data class TodayDiscrepancyView(
    val timeline: List<String>,
    val effectiveLine: String,
    val identifiedIsfLine: String,
    val explainedLine: String,
    val mixedDataLine: String,
    val kineticsLine: String,
    val contributionRows: List<String> = emptyList(),
)

object DailyDiscrepancyRuntime {
    private fun dayStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun update(store: CollectorStore, db: SQLiteDatabase, now: Long) {
        // ClosedEpisodeRuntime is deliberately scheduled by MainState only
        // AFTER the renderable state has been assembled. Starting it here let
        // its long retrospective SQLite pass contend with foreground History
        // reads; changing tabs then cancelled the still-unpublished state.
        // Legacy fixed checkpoints remain below as the cheap comparison path.
        val start = dayStart(now)
        val foods = store.annotations(start, now).filter { it.kind == "food" && it.carbsKnownAtMs != null }
        val boluses = store.boluses(start, now)
        val observations = mutableListOf<ResponseObservationV1>()
        for (food in foods) for (h in listOf(60, 120, 180)) {
            val target = food.tsMs + h * 60_000L
            // A meal learned after the checkpoint target has no prospective
            // forecast for that checkpoint. It may be studied elsewhere as a
            // labelled retrospective diagnostic, but cannot enter this state.
            if (target > now || food.carbsKnownAtMs!! >= target) continue
            val run = forecastAt(db, food.carbsKnownAtMs!!, target) ?: continue
            val actual = store.sensorReadings(target - 6 * 60_000, target + 6 * 60_000).minByOrNull { abs(it.tsMs - target) } ?: continue
            observations += ResponseObservationV1(
                "meal:${food.id}:$h", food.tsMs, actual.tsMs, h,
                run.second - run.first, actual.mmol - run.first,
                hasFood = true, hasBolus = boluses.any { abs(it.tsMs - food.tsMs) <= 45 * 60_000 },
                insulinIdentifying = false,
                contamination = buildSet { if (boluses.any { abs(it.tsMs - food.tsMs) <= 45 * 60_000 }) add("meal_bolus_overlap") },
            )
        }
        for (bolus in boluses.filter { it.purpose?.lowercase()?.contains("корр") == true }) for (h in listOf(60, 120, 180)) {
            val target = bolus.tsMs + h * 60_000L
            val activityContaminated = store.steps(bolus.tsMs, target).sumOf { it.count } > 500
            val anotherBolus = boluses.any { it.tsMs > bolus.tsMs && it.tsMs <= target }
            val sensorSupport = store.sensorReadings(bolus.tsMs, target)
            val sensorGap = sensorSupport.zipWithNext().any { (a,b) -> b.tsMs-a.tsMs > 15*60_000 } || sensorSupport.size < h/10
            if (target > now || foods.any { abs(it.tsMs - bolus.tsMs) < 3 * 3_600_000L } || activityContaminated || anotherBolus || sensorGap) continue
            val run = forecastAt(db, bolus.tsMs, target) ?: continue
            val actual = store.sensorReadings(target - 6 * 60_000, target + 6 * 60_000).minByOrNull { abs(it.tsMs - target) } ?: continue
            observations += ResponseObservationV1("correction:${bolus.tsMs}:$h", bolus.tsMs, actual.tsMs, h, run.second - run.first, actual.mmol - run.first, false, true, true)
        }
        if (observations.isEmpty()) return
        val fallbackPrior = DailyResponseStateV1(
            PosteriorV1(0.0, -30.0, 30.0, 0, 0, null),
            PosteriorV1(0.0, -20.0, 20.0, 0, 0, null), EvidenceStatus.INSUFFICIENT,
        )
        val prior = priorFromDb(db, start) ?: fallbackPrior
        val rolling = rollingPriorFromDb(db, start) ?: prior
        invalidateLateContamination(db, foods, prior, now)
        observations.sortedBy { it.knownAtMs }.forEach { o ->
            val state = DailyResponseEstimatorV1.update(prior, observations.filter { it.knownAtMs <= o.knownAtMs }, o.knownAtMs)
            val kind = when (o.horizonMin) { 60 -> CheckpointKind.PROVISIONAL_60; 120 -> CheckpointKind.PROVISIONAL_120; else -> CheckpointKind.RECONCILED }
            val prefix = o.episodeId.substringBeforeLast(':')
            val supersedes = when (o.horizonMin) { 120 -> "$prefix:60"; 180 -> "$prefix:120"; else -> null }
            val cp = DailyCheckpointV1(o.episodeId, supersedes, kind, o.knownAtMs, state, listOf(o.episodeId))
            PhysioExperimentalLedger.recordCheckpoint(db, cp, payload(state, prior, rolling, o))
        }
    }

    private fun invalidateLateContamination(db: SQLiteDatabase, foods: List<com.diapilot.core.collector.Annotation>, prior: DailyResponseStateV1, now: Long) {
        db.rawQuery("SELECT checkpoint_id FROM physio_daily_checkpoints WHERE checkpoint_id LIKE 'correction:%' AND kind!='INVALIDATED' AND checkpoint_id NOT IN (SELECT supersedes_id FROM physio_daily_checkpoints WHERE kind='INVALIDATED')", null).use { c ->
            while(c.moveToNext()) {
                val id=c.getString(0); val bolusTs=id.split(':').getOrNull(1)?.toLongOrNull() ?: continue
                if(foods.none { abs(it.tsMs-bolusTs)<3*3_600_000L }) continue
                val invalidId="$id:invalidated:${now}"
                val cp=DailyCheckpointV1(invalidId,id,CheckpointKind.INVALIDATED,now,prior,listOf(id),closed=true)
                val payload=org.json.JSONObject().put("episode",id).put("effective",posterior(prior.effectiveResponsePercent)).put("identified_isf",posterior(prior.identifiedIsfPercent)).put("isf_status",EvidenceStatus.NOT_IDENTIFIABLE.name).put("invalidated_reason","late-known food overlap").put("predicted_delta",0).put("observed_delta",0).toString()
                PhysioExperimentalLedger.recordCheckpoint(db,cp,payload)
            }
        }
    }

    /**
     * What the app forecast for [target], using only knowledge it had by
     * [eventKnown].
     *
     * READS THE SHOWN LINE. It used to read
     * `physio_parallel_runs WHERE engine='PHYSIO_V1'`, and the only writer of
     * those rows was `PhysioExperimentalLedger.recordPair` — deleted along with
     * the second arm. So this returned null for every call since
     * then and the daily-discrepancy card went quietly blank, the same silent
     * casualty as the ledger regression fixed in [Forecaster].
     *
     * `forecast_runs`/`forecast_points` is the right source and now the only
     * one: it carries the line that was actually displayed, tagged with the
     * model that produced it.
     *
     * THE CAUSALITY CONDITION IS THE POINT and is unchanged: the run must have
     * been ANCHORED at or after the event became known and STRICTLY BEFORE the
     * target, or the «forecast» already contains the outcome it is compared
     * against.
     */
    internal fun forecastAt(db: SQLiteDatabase, eventKnown: Long, target: Long): Pair<Double, Double>? {
        if (eventKnown >= target) return null
        val run = db.rawQuery(
            "SELECT id,anchor_ts_ms FROM forecast_runs WHERE consumer='main' " +
                "AND anchor_ts_ms>=? AND anchor_ts_ms<? ORDER BY anchor_ts_ms LIMIT 1",
            arrayOf(eventKnown.toString(), target.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null } ?: return null
        val (runId, producedAnchor) = run
        val points = db.rawQuery(
            "SELECT target_ts_ms,mmol FROM forecast_points WHERE run_id=?",
            arrayOf(runId.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getDouble(1)) }
        }
        if (points.isEmpty()) return null
        val at = points.filter { it.first > producedAnchor }
            .minByOrNull { abs(it.first - target) }
            ?.takeIf { abs(it.first - target) <= 6 * 60_000 } ?: return null
        val anchor = points.minByOrNull { abs(it.first - producedAnchor) } ?: return null
        return anchor.second to at.second
    }

    private fun payload(s: DailyResponseStateV1, baseline: DailyResponseStateV1, rolling: DailyResponseStateV1, o: ResponseObservationV1): String {
        val change = detectChangeV1("effective_response", s.effectiveResponsePercent, baseline.effectiveResponsePercent, 10.0, "previous/inertial day")
        val rollingChange = detectChangeV1("effective_response", s.effectiveResponsePercent, rolling.effectiveResponsePercent, 10.0, "rolling recent baseline")
        return org.json.JSONObject()
        .put("episode", o.episodeId).put("horizon_min", o.horizonMin)
        .put("effective", posterior(s.effectiveResponsePercent))
        .put("identified_isf", posterior(s.identifiedIsfPercent))
        .put("isf_status", s.identifiedIsfStatus.name)
        .put("change_probability_positive", change.probabilityPositive)
        .put("rolling_probability_positive", rollingChange.probabilityPositive)
        .put("practical_threshold_crossed", change.practicalThresholdCrossed)
        .put("mixed", o.hasFood && o.hasBolus)
        .put("contamination", org.json.JSONArray(o.contamination.toList()))
        .put("predicted_delta", o.predictedDelta).put("observed_delta", o.observedDelta).toString()
    }

    private fun priorFromDb(db: SQLiteDatabase, beforeMs: Long): DailyResponseStateV1? = db.rawQuery(
        "SELECT known_at_ms,payload_json FROM physio_daily_checkpoints WHERE known_at_ms>=? AND known_at_ms<? ORDER BY known_at_ms DESC LIMIT 1", arrayOf(FoodEraSettings.current().startMs.toString(),beforeMs.toString()),
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        val knownAt = c.getLong(0)
        val root = org.json.JSONObject(c.getString(1)); fun p(name: String): PosteriorV1 { val o=root.getJSONObject(name); return PosteriorV1(o.getDouble("median"),o.getDouble("p10"),o.getDouble("p90"),o.getInt("n"),o.optInt("days",0),knownAt) }
        DailyResponseStateV1(p("effective"), p("identified_isf"), EvidenceStatus.valueOf(root.getString("isf_status")))
    }

    private fun rollingPriorFromDb(db: SQLiteDatabase, beforeMs: Long): DailyResponseStateV1? = db.rawQuery(
        "SELECT known_at_ms,payload_json FROM physio_daily_checkpoints WHERE known_at_ms>=? AND known_at_ms<? ORDER BY known_at_ms DESC LIMIT 40", arrayOf(FoodEraSettings.current().startMs.toString(),beforeMs.toString()),
    ).use { c ->
        val byDay = linkedMapOf<Long, org.json.JSONObject>()
        while(c.moveToNext()) byDay.putIfAbsent(c.getLong(0)/86_400_000, org.json.JSONObject(c.getString(1)))
        val rows=byDay.values.take(7); if(rows.isEmpty()) return@use null
        fun avg(name:String,key:String)=rows.map { it.getJSONObject(name).getDouble(key) }.average()
        fun p(name:String)=PosteriorV1(avg(name,"median"),avg(name,"p10"),avg(name,"p90"),rows.sumOf { it.getJSONObject(name).getInt("n") },rows.size,null)
        DailyResponseStateV1(p("effective"),p("identified_isf"),EvidenceStatus.INSUFFICIENT)
    }

    private fun posterior(p: PosteriorV1) = org.json.JSONObject().put("median", p.median).put("p10", p.p10).put("p90", p.p90).put("n", p.identifyingEpisodes).put("days",p.independentDays)

    fun view(
        db: SQLiteDatabase,
        now: Long,
        matrix: List<FactorEvidenceV1> = emptyList(),
        store: CollectorStore? = null,
        context: Context? = null,
    ): TodayDiscrepancyView? = db.rawQuery(
        "SELECT known_at_ms,kind,payload_json FROM physio_daily_checkpoints WHERE known_at_ms>=? ORDER BY known_at_ms",
        arrayOf(maxOf(dayStart(now),FoodEraSettings.current().startMs).toString()),
    ).use { c ->
        val rows = buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), org.json.JSONObject(c.getString(2)))) }
        val last = rows.lastOrNull()?.third ?: return@use null
        val e = last.getJSONObject("effective"); val i = last.getJSONObject("identified_isf")
        val observationStart = last.optString("episode").split(':').getOrNull(1)?.toLongOrNull() ?: now
        val currentFactors = store?.let { currentFactorIds(it, observationStart) }.orEmpty()
        val candidates = buildList {
            if (last.getString("isf_status") == EvidenceStatus.SUPPORTED.name) add(
                CandidateContributionV1("identified ISF", i.getDouble("median"), i.getDouble("p10"), i.getDouble("p90"), true, hypotheses=listOf("identified_isf"), promotedToForecastMedian=true),
            )
            matrix.filter { it.rawExposures > 0 && factorIsPresent(it.dataSource, currentFactors) }.forEach { factor ->
                val group = when {
                    factor.factor.contains("activity") || factor.factor.contains("sleep") -> "context"
                    factor.targetParameter.contains("absorption") -> "food_timing"
                    factor.targetParameter.contains("background") -> "hepatic_background"
                    else -> null
                }
                add(CandidateContributionV1(
                    factor.factor, factor.effectPercent.median, factor.effectPercent.p10,
                    factor.effectPercent.p90,
                    factor.status == EvidenceStatus.SUPPORTED,
                    correlationGroup = group, hypotheses = listOf(factor.dataSource),
                    promotedToForecastMedian = factor.forecastUse == ForecastUse.MEDIAN,
                ))
            }
        }
        val decomposition = decomposeDiscrepancyV1(e.getDouble("median"), candidates)
        val coverage = explanationCoverageTextV1(decomposition)
        val v=PhysioRuntime.artifact(store,now)?.variance
        val varianceRows=if(v==null)listOf("variance ledger unavailable") else listOf(
            "carbs/provenance amount: median=0 unless identified; interval coefficient=${v.carbAmount}",
            "protein/fat, absorption tail and intake duration: median=0 unless promoted; interval coefficient=${v.foodTiming}",
            "bolus timing, product, injection site, cartridge/heat: median=0 unless promoted; interval coefficient=${v.insulinTiming}",
            "activity today/yesterday, sleep, hypo/counterregulation, glucose/hepatic, stress/illness, alcohol: median=0 unless supported/promoted; background interval coefficient=${v.backgroundHepatic}",
            "basal mismatch: median follows parity contract only; residual interval coefficient=${v.basalMismatch}",
            "sensor age/epoch/change, lag/compression/calibration/process and gaps: never relabelled ISF; interval coefficient=${v.sensorProcess}",
        )
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        // Localized when a Context is available (the caller does not always
        // have one yet); otherwise falls back to the English default text.
        val res = context?.localized()
        val thresholdCrossed = last.optBoolean("practical_threshold_crossed")
        val thresholdWord = res?.getString(
            if (thresholdCrossed) R.string.daily_discrepancy_runtime_threshold_crossed
            else R.string.daily_discrepancy_runtime_threshold_not_crossed,
        ) ?: if (thresholdCrossed) "crossed" else "not crossed"
        val effectiveLine = res?.getString(
            R.string.daily_discrepancy_runtime_effective_line,
            e.getDouble("median"), e.getDouble("p10"), e.getDouble("p90"),
            100 * last.optDouble("change_probability_positive", .5),
            100 * last.optDouble("rolling_probability_positive", .5),
            thresholdWord,
        ) ?: ("Observed combined food and insulin response: %+.0f%% [%.0f…%.0f] vs. the previous/inertial " +
            "state; P(increase)=%.0f%%, vs. rolling baseline %.0f%%; practical threshold: %s").format(
            e.getDouble("median"), e.getDouble("p10"), e.getDouble("p90"),
            100 * last.optDouble("change_probability_positive", .5),
            100 * last.optDouble("rolling_probability_positive", .5),
            thresholdWord,
        )
        val identifiedIsfLine = if (last.getString("isf_status") == EvidenceStatus.NOT_IDENTIFIABLE.name) {
            res?.getString(R.string.daily_discrepancy_runtime_isf_unclear) ?: "Pure ISF can't be separated out yet"
        } else {
            "ISF posterior: %+.0f%% [%.0f…%.0f], n=%d".format(i.getDouble("median"), i.getDouble("p10"), i.getDouble("p90"), i.getInt("n"))
        }
        val mixedDataLine = res?.getString(
            R.string.daily_discrepancy_runtime_residual_line,
            last.getDouble("observed_delta"), last.getDouble("predicted_delta"), last.getString("episode"),
        ) ?: "Residual: observed %+.2f, expected %+.2f mmol/L; provenance: %s".format(
            last.getDouble("observed_delta"), last.getDouble("predicted_delta"), last.getString("episode"),
        )
        TodayDiscrepancyView(
            rows.map { "${fmt.format(java.util.Date(it.first))} · ${it.second.lowercase()}" },
            effectiveLine,
            identifiedIsfLine,
            "$coverage Unsupported hypotheses contribute 0 to median and remain visible in the registry.",
            mixedDataLine,
            PhysioRuntime.artifact()?.baseMechanics?.let { person ->
                com.diapilot.core.hybrid.HybridForecastEngine(person).insulinLandmarks().let{
                    "Insulin kinetics prior: onset %.0f, rate peak %.0f, tail %.0f min; exact selected-engine CDF; identifying n=0, current state carried from prior.".format(it.onsetMin,it.ratePeakMin,it.effectEndMin)
                }
            } ?: "Insulin kinetics source missing",
            candidates.map { row ->
                val use = when {
                    row.supported && row.promotedToForecastMedian -> "supported attribution; used in forecast median"
                    row.supported -> "supported attribution; not used in forecast median"
                    else -> "not attribution-supported; not used in forecast median"
                }
                "%s: %+.1f [%.1f…%.1f] percentage points · %s · hypotheses=%s".format(
                    row.factor,row.medianPoints,row.lowPoints,row.highPoints,use,row.hypotheses.joinToString(),
                )
            } + varianceRows + listOf("joint/confounded: %+.1f points; unresolved: %+.1f points; allocation is not forced to 100%%".format(decomposition.jointConfoundedPoints,decomposition.unresolvedPoints)),
        )
    }

    private fun factorIsPresent(id: String, present: Set<String>): Boolean = id in present

    /**
     * Always empty now.
     *
     * Used to return the active contexts for hypothesis modifiers. There are no
     * modifiers — no registry hypothesis ever reached the applied stage — so
     * the exposure predicate was removed too. The function is kept so its
     * callers say "no contexts" explicitly, rather than through a missing call.
     */
    internal fun currentFactorIds(store: CollectorStore, now: Long): Set<String> = emptySet()
}
