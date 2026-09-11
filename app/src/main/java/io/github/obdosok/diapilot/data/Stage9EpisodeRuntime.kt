package io.github.obdosok.diapilot.data

import android.content.Context
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.i18n.isRussianUi
import io.github.obdosok.diapilot.i18n.localized
import com.diapilot.core.analysis.IsfEpisode
import com.diapilot.core.analysis.isFoodNote
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.CarbEvidenceSourceV1
import com.diapilot.core.collector.CarbEvidenceV1
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.physio.*
import java.util.Locale
import kotlin.math.abs

/** Bounded diagnostic sidecar. It is never a dependency of forecast publication. */
object Stage9EpisodeRuntime {
    /**
     * v12, DERIVED rather than written down.
     *
     * The appearance policy is appended, so retuning the sieving or the
     * emptying rate changes this string on its own and every decomposition
     * stored under the old physiology is rejected on restore. Writing the
     * version by hand is what let a v6 receipt built on the flat 30 g/h come
     * back as «current» after the queue shipped.
     */
    val CACHE_VERSION="time-resolved-attribution-v13-physio-only+" +
        com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED.signature()
    private const val DAY=86_400_000L

    data class BuildResult(
        val receipts: Map<Long, EpisodeAttributionExplanationV1>,
        val complete: Boolean,
        val processedClusters: Int,
        val totalClusters: Int,
        val budgetLimited: Boolean,
        val nextOffset: Int = 0,
    )

    /** Sorted once, then every cluster window is O(log n + window) instead of
     * filtering the complete month of CGM/boluses again. */
    internal class TimeRangeIndex<T>(rows: List<T>, private val timestamp: (T) -> Long) {
        private val sorted=rows.sortedBy(timestamp)
        fun between(fromInclusive: Long, toInclusive: Long): List<T> {
            fun lower(value: Long, strict: Boolean): Int {
                var lo = 0;
                var hi = sorted.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1;
                    val ts = timestamp(sorted[mid])
                    if (ts < value || strict && ts == value) lo = mid + 1 else hi = mid
                }
                return lo
            }
            return sorted.subList(lower(fromInclusive, false), lower(toInclusive, true))
        }
    }

    internal fun annotationAsOf(n: Annotation, asOf: Long, evidence: CarbEvidenceV1?): Annotation {
        if (evidence == null) return n
        require(evidence.annotationId == n.id && evidence.knownAtMs <= asOf)
        if (evidence.deleted)
            return n.copy(
                estCarbs = null,
                carbsKnownAtMs = evidence.knownAtMs,
                analysisKnownAtMs = null
            )
        val source =
            when (evidence.input.source) {
                CarbEvidenceSourceV1.LABEL_WEIGHT -> "label"
                CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT -> "anchor"
                CarbEvidenceSourceV1.PRESET_TABLE -> "preset"
                CarbEvidenceSourceV1.PHOTO_LLM -> "photo"
                CarbEvidenceSourceV1.USER_ESTIMATE,
                CarbEvidenceSourceV1.LABEL_PORTION_ESTIMATED -> "manual"
                CarbEvidenceSourceV1.LEGACY_UNKNOWN -> "unknown"
            }
        // Title/analysis have no append-only revision journal. They remain display
        // facts only; the causal core deliberately ignores unversioned title shape.
        return n.copy(
            tsMs = evidence.intakeStartMs,
            estCarbs = evidence.input.totalCarbsG,
            carbsSource = source,
            carbsKnownAtMs = evidence.knownAtMs,
            analysis = evidence.input.kineticsV2,
            analysisKnownAtMs = evidence.input.kineticsV2?.let { evidence.knownAtMs }
        )
    }

    fun build(
        notes: List<Annotation>,
        readings: List<GlucosePoint>,
        boluses: List<BolusPoint>,
        episodes: List<IsfEpisode>,
        foodEraStartMs: Long?,
        nowMs: Long,
        budgetMs: Long = 2_000L,
        carbEvidenceAsOf: (Long, Long) -> CarbEvidenceV1? = { _, _ -> null },
        context: Context,
    ): Map<Long, EpisodeAttributionExplanationV1> =
        buildDetailed(
                notes,
                readings,
                boluses,
                episodes,
                foodEraStartMs,
                nowMs,
                budgetMs,
                carbEvidenceAsOf,
                context = context
            )
            .receipts

    fun buildDetailed(
        notes: List<Annotation>,
        readings: List<GlucosePoint>,
        boluses: List<BolusPoint>,
        episodes: List<IsfEpisode>,
        foodEraStartMs: Long?,
        nowMs: Long,
        budgetMs: Long = 2_000L,
        carbEvidenceAsOf: (Long, Long) -> CarbEvidenceV1? = { _, _ -> null },
        lookbackMs: Long = 30 * DAY,
        personModelForBolus: ((BolusPoint) -> com.diapilot.core.hybrid.HybridPersonModel?)? = null,
        clusterOffset: Int = 0,
        /** Receipts are display text, built in the app language; FoodCalculationRegistry
         *  keeps that language with its cache and rebuilds after a switch. */
        context: Context,
    ): BuildResult {
        val started = android.os.SystemClock.elapsedRealtime()
        val text = context.localized()
        val ru = text.isRussianUi()
        fun s(id: Int, vararg args: Any): String = text.getString(id, *args)
        fun min(v: String) = s(R.string.stage9_episode_minutes, v)
        val causal =
            episodes
                .filter {
                    foodEraStartMs != null && it.t0Ms >= foodEraStartMs && !it.activityContaminated
                }
                .mapIndexed { i, e ->
                    IdentifiedIsfEvidenceV1(
                        e.t0Ms,
                        e.t0Ms + 4 * 3_600_000L,
                        e.isf.coerceAtLeast(.1),
                        if (e.postActivityTail) .25 else 1.0,
                        i + 1,
                        "food-era correction candidate"
                    )
                }
        // Current Twin kernel is deliberately absent. Every historical bolus
        // selects a prior revision which was already known at that bolus.
        val model =
            VersionedEpisodeKernelModelV1(listOf(EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1), causal)
        val readingIndex = TimeRangeIndex(readings) { it.tsMs }
        val bolusIndex = TimeRangeIndex(boluses) { it.tsMs }
        val personModelCache =
            mutableMapOf<Pair<Long, Long>, com.diapilot.core.hybrid.HybridPersonModel?>()
        val recent =
            notes
                .filter { it.tsMs >= nowMs - lookbackMs && it.tsMs <= nowMs && isFoodNote(it) }
                .sortedBy { it.tsMs }
        val clusters = mutableListOf<MutableList<Annotation>>()
        recent.forEach { n ->
            val c = clusters.lastOrNull();
            if (
                c != null &&
                    n.tsMs - c.last().tsMs <= 6 * 3_600_000L &&
                    n.tsMs - c.first().tsMs <= 6 * 3_600_000L
            )
                c.add(n)
            else clusters.add(mutableListOf(n))
        }
        val out = mutableMapOf<Long, EpisodeAttributionExplanationV1>()
        val selected = clusters.asReversed().drop(clusterOffset.coerceIn(0, clusters.size));
        var processed = 0;
        var budgetLimited = false
        for (cluster in selected) {
            if (android.os.SystemClock.elapsedRealtime() - started > budgetMs) {
                budgetLimited = true;
                break
            }
            // The continuation cursor counts attempted clusters, not only
            // clusters which produced a receipt. A terminal cluster with no
            // causal grams/CGM previously hit `continue` below without moving
            // the cursor, so the final few in history were retried forever.
            processed++
            val from = cluster.first().tsMs - 8 * 3_600_000L;
            val to = minOf(nowMs, cluster.first().tsMs + 6 * 3_600_000L)
            val rr = readingIndex.between(from, to);
            val bb = bolusIndex.between(from, to)
            val causalCluster = cluster.mapNotNull { n ->
                val e = carbEvidenceAsOf(n.id, to)
                val c = if (e != null) annotationAsOf(n, to, e) else n
                c.takeIf {
                    it.estCarbs != null && it.carbsKnownAtMs?.let { known -> known <= to } == true
                }
            }
            val receipt =
                Stage9SharedCoreV1.jointReceipt(
                    causalCluster,
                    rr,
                    bb,
                    PosteriorV1(.165, .133, .20, 0, 0, 0),
                    to,
                    { b ->
                        val facts = buildList {
                            val hour =
                                java.util.Calendar.getInstance()
                                    .apply { timeInMillis = b.tsMs }
                                    .get(java.util.Calendar.HOUR_OF_DAY)
                            add(
                                EpisodeContextFactV1(
                                    KernelFactorKindV1.TOD,
                                    hour.toDouble(),
                                    "hour",
                                    b.tsMs,
                                    b.tsMs,
                                    1,
                                    "event clock"
                                )
                            )
                            if (
                                rr.any {
                                    it.tsMs in (b.tsMs - 12 * 3_600_000L)..b.tsMs && it.mmol < 3.9
                                }
                            )
                                add(
                                    EpisodeContextFactV1(
                                        KernelFactorKindV1.PRIOR_HYPO,
                                        1.0,
                                        "present",
                                        b.tsMs,
                                        b.tsMs,
                                        1,
                                        "CGM known before bolus"
                                    )
                                )
                        }
                        personModelForBolus
                            ?.let { provider ->
                                val key = b.tsMs to java.lang.Double.doubleToLongBits(b.units)
                                if (personModelCache.containsKey(key)) personModelCache[key]
                                else provider(b).also { personModelCache[key] = it }
                            }
                            ?.let { hybridEpisodeKernelV1(it, b, to) }
                            ?: Stage9SharedCoreV1.episodeKernel(model, b.tsMs, facts, b.tsMs, b.units)
                    },
                    // THE SAME PHYSIOLOGY THE FORECAST DRAWS. Stage9 is what the
                    // model learns from; decomposing a meal under the flat 30 g/h
                    // while the forecast draws it under the caloric queue means the
                    // corpus and the forecast disagree about the same food.
                    appearance = com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED,
                ) ?: continue
            receipt.allocations.forEachIndexed { i, a ->
                val neighbours =
                    receipt.allocations
                        .filterIndexed { j, _ -> j != i }
                        .joinToString { n -> min(((n.startMs - a.startMs) / 60_000).toString()) }
                        .ifBlank { s(R.string.stage9_episode_none) }
                val kernelHash =
                    receipt.insulinKernelHashes.values.firstOrNull()?.take(10)
                        ?: s(R.string.stage9_episode_no_bolus)
                // TRIMMED TO COVERAGE. The solver zeroes a meal's basis beyond
                // its continuous CGM coverage, so the raw curve ended in a run
                // of zeros and "0.0 mmol/L by the end of the window" — the censoring
                // artifact printed as if the contribution had vanished. A
                // cumulative contribution cannot fall to zero; the meal does
                // not come back out. Fourth instance in two days of a bound
                // shown as a measurement (120-min insulin end, 360/360 series
                // timing, the 20-mismatch truncation, now this).
                val covered =
                    a.contributionCurve.filter {
                        (it.tsMs - a.startMs) / 60_000.0 <= a.coverageMin + 1.0
                    }
                val peak = covered.maxByOrNull { it.median }
                val modelPeakMin = peak?.let { (it.tsMs - a.startMs) / 60_000.0 }
                val censoredTail = a.contributionCurve.size > covered.size
                val curveArgs =
                    arrayOf<Any>(
                        spark(covered.map { it.median }),
                        fmt((a.causalOnsetMs - a.startMs) / 60_000.0),
                        modelPeakMin?.let { min(fmt(it)) } ?: "—",
                        peak?.let { fmt(it.median) } ?: "—",
                        covered.lastOrNull()?.let { fmt(it.median) } ?: "—",
                        pct(a.fractionBeforeNext.median),
                        pct(a.fractionAfterNext.median),
                    )
                val curveText =
                    if (censoredTail)
                        s(R.string.stage9_episode_curve_censored, *curveArgs, fmt(a.coverageMin))
                    else s(R.string.stage9_episode_curve, *curveArgs)
                val transferText =
                    receipt.tailTransferAudit
                        ?.let { audit ->
                            val share = audit.previousMealsShareOfRecognizedFoodLate
                            val transferArgs =
                                arrayOf<Any>(
                                    fmt(audit.earlyDeficitMmol),
                                    fmt(audit.lastSurplusMmol),
                                    pct(share.p10),
                                    pct(share.p90),
                                    pct(audit.unassignedLateShare.median),
                                )
                            when {
                                audit.detected &&
                                    audit.resolution == AttributionResolutionV1.RESOLVED ->
                                    s(R.string.stage9_episode_transfer_resolved, *transferArgs)
                                audit.detected ->
                                    s(R.string.stage9_episode_transfer_possible, *transferArgs)
                                audit.resolution != AttributionResolutionV1.RESOLVED ->
                                    s(R.string.stage9_episode_transfer_overlap)
                                else -> s(R.string.stage9_episode_transfer_none)
                            }
                        }
                        .orEmpty()
                val portions =
                    a.perNoteOnsetsMs
                        .mapNotNull { ts -> causalCluster.firstOrNull { abs(it.tsMs - ts) < 1 } }
                        .joinToString("; ") { p ->
                            s(
                                R.string.stage9_episode_portion,
                                fmt(p.estCarbs ?: 0.0),
                                java.text
                                    .SimpleDateFormat("HH:mm", Locale.getDefault())
                                    .format(java.util.Date(p.tsMs))
                            )
                        }
                // The CS is printed from the receipt that USED it, never as a literal:
                // a hardcoded «0,248» would keep printing after the learner moves.
                val cs =
                    String.format(Locale.US, "%.3f", receipt.globalCs.median).let {
                        if (ru) it.replace('.', ',') else it
                    }
                val explanation =
                    EpisodeAttributionExplanationV1(
                        receipt.version,
                        s(
                            R.string.stage9_episode_forecast,
                            fmt(a.modelForecastMmol),
                            fmt(a.recordedCarbsG),
                            cs
                        ),
                        // The printed "allowed L-H" range reads as a free measurement; it is not — the
                        // per-meal amplitude is clamped to the prior (x1.35, or x1.60
                        // at weak provenance) and the group mass is bounded too. The
                        // clamp is now stated where the number is shown.
                        if (a.status == AttributionResolutionV1.RESOLVED)
                            s(
                                R.string.stage9_episode_allocated_resolved,
                                fmt(a.bestAllocationMmol),
                                fmt(a.allocationLowMmol),
                                fmt(a.allocationHighMmol)
                            )
                        else
                            s(
                                R.string.stage9_episode_allocated_overlap,
                                fmt(a.allocationLowMmol),
                                fmt(a.allocationHighMmol),
                                fmt(a.bestAllocationMmol)
                            ),
                        phaseSummary(text, a, receipt.clusterTiming),
                        s(
                            R.string.stage9_episode_overlap,
                            neighbours,
                            fmt(receipt.unloggedFoodResidualMmol.median)
                        ),
                        s(
                            R.string.stage9_episode_allocation,
                            pct(a.shareMedian),
                            pct(a.shareLow),
                            pct(a.shareHigh)
                        ),
                        when (a.status) {
                            AttributionResolutionV1.RESOLVED ->
                                s(R.string.stage9_episode_resolution_resolved)
                            AttributionResolutionV1.UNRESOLVED ->
                                s(R.string.stage9_episode_resolution_unresolved)
                            AttributionResolutionV1.CENSORED ->
                                s(R.string.stage9_episode_resolution_censored, fmt(a.coverageMin))
                        },
                        if (personModelForBolus != null)
                            s(
                                R.string.stage9_episode_kernel_physio,
                                EPISODE_KERNEL_VERSION_V1,
                                kernelHash
                            )
                        else
                            "$EPISODE_KERNEL_VERSION_V1; causal prior ${EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1.identity}; hash $kernelHash",
                        if (personModelForBolus != null)
                            s(R.string.stage9_episode_kernel_difference_physio)
                        else s(R.string.stage9_episode_kernel_difference_prior),
                        confidence(text, a.confidence),
                        (a.caveats.map { caveat(text, it) } +
                                listOfNotNull(
                                    s(
                                        R.string.stage9_episode_caveat_carbs,
                                        fmt(a.gramsLow),
                                        fmt(a.gramsHigh),
                                        a.gramsProvenance
                                    ),
                                    if (portions.isNotBlank())
                                        s(R.string.stage9_episode_caveat_portions, portions)
                                    else null,
                                    s(R.string.stage9_episode_caveat_sensor),
                                    if (receipt.inputTruncated)
                                        s(R.string.stage9_episode_caveat_truncated)
                                    else null,
                                ))
                            .joinToString("; "),
                        causalProvenance = s(R.string.stage9_episode_causal_provenance),
                        diagnosticProvenance = s(R.string.stage9_episode_diagnostic_provenance),
                        timeResolvedCurve = curveText,
                        tailTransfer = transferText,
                        compactSummary =
                            s(
                                R.string.stage9_episode_compact_summary,
                                fmt(a.modelForecastMmol),
                                fmt(a.bestAllocationMmol),
                                fmt(a.allocationLowMmol),
                                fmt(a.allocationHighMmol)
                            ),
                        unloggedResidualMmol = receipt.unloggedFoodResidualMmol.median,
                        compactFinding =
                            when (a.timingScope) {
                                com.diapilot.core.hybrid.MealTimingScopeV1.COMPLETED_BEFORE_CLUSTER ->
                                    s(R.string.stage9_episode_finding_completed)
                                // A window-bound triplet on the user's card was the
                                // WINDOW BOUND printed as a measurement: mainEndMin is
                                // the last supported grid bin, and when the series is
                                // still active at the 6-hour horizon that bin IS the
                                // horizon (levelPeak lands there too). The same trap as
                                // an "end at 120 min" figure in the insulin doc — a bound must say
                                // it is a bound.
                                com.diapilot.core.hybrid.MealTimingScopeV1.CLUSTER_ONLY ->
                                    receipt.clusterTiming?.let { ct ->
                                        if (ct.mainEndMin >= 355.0)
                                            s(
                                                R.string.stage9_episode_finding_series_bounded,
                                                fmt(ct.onsetMin)
                                            )
                                        else
                                            s(
                                                R.string.stage9_episode_finding_series,
                                                fmt(ct.onsetMin),
                                                fmt(ct.levelPeakMin),
                                                fmt(ct.mainEndMin)
                                            )
                                    } ?: s(R.string.stage9_episode_finding_series_unsplit)
                                else ->
                                    when (a.status) {
                                        AttributionResolutionV1.RESOLVED ->
                                            if (receipt.tailTransferAudit?.detected == true)
                                                s(R.string.stage9_episode_finding_transfer)
                                            else s(R.string.stage9_episode_finding_resolved)
                                        AttributionResolutionV1.UNRESOLVED ->
                                            s(R.string.stage9_episode_finding_unresolved)
                                        AttributionResolutionV1.CENSORED ->
                                            s(R.string.stage9_episode_finding_censored)
                                    }
                            },
                        unresolved = a.status == AttributionResolutionV1.UNRESOLVED,
                    )
                // One session receipt belongs to its first portion. Per-portion
                // onsets/grams are listed inside it; copying the whole allocation
                // onto every bottle would falsely present session amplitude twice.
                a.perNoteOnsetsMs.minOrNull()?.let { ts ->
                    causalCluster
                        .firstOrNull { abs(it.tsMs - ts) < 1 }
                        ?.let { out[it.id] = explanation }
                }
            }
        }
        val next = (clusterOffset + processed).coerceAtMost(clusters.size)
        val complete = !budgetLimited && next >= clusters.size
        return BuildResult(out, complete, processed, clusters.size, !complete, next)
    }
    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun pct(v: Double) = String.format(Locale.US, "%.0f%%", 100 * v.coerceIn(0.0, 1.0))
    private fun confidence(text: Context, v: Double) =
        text.getString(
            when {
                v >= .7 -> R.string.stage9_episode_confidence_high
                v >= .4 -> R.string.stage9_episode_confidence_medium
                else -> R.string.stage9_episode_confidence_low
            }
        )
    private fun spark(values: List<Double>): String {
        if (values.isEmpty()) return "—"
        val blocks = "▁▂▃▄▅▆▇█";
        val max = values.maxOrNull()?.coerceAtLeast(1e-9) ?: return "—"
        val step = (values.size / 8.0).coerceAtLeast(1.0)
        return (0 until minOf(8, values.size))
            .map { i -> values[(i * step).toInt().coerceAtMost(values.lastIndex)] }
            .joinToString("") {
                blocks[((it / max) * (blocks.lastIndex)).toInt().coerceIn(0, blocks.lastIndex)]
                    .toString()
            }
    }
    private fun phaseName(text: Context, p: ObservedPhaseV1) =
        text.getString(
            when (p) {
                ObservedPhaseV1.START -> R.string.stage9_episode_phase_start
                ObservedPhaseV1.LEVEL_MAXIMUM -> R.string.stage9_episode_phase_level_max
                ObservedPhaseV1.LATE_PHASE -> R.string.stage9_episode_phase_late
                ObservedPhaseV1.PLATEAU -> R.string.stage9_episode_phase_plateau
            }
        )
    private fun phaseSummary(text: Context, a: MealAllocationV1, cluster: ClusterTimingV1?): String {
        if (a.timingScope == com.diapilot.core.hybrid.MealTimingScopeV1.CLUSTER_ONLY)
            return cluster?.let {
                // The same window-bound trap as compactFinding: when the series
                // is still active at the 6-hour horizon, mainEndMin (and often
                // the fitted peak) IS the horizon, not a measurement.
                if (it.mainEndMin >= 355.0)
                    text.getString(
                        R.string.stage9_episode_phases_cluster_bounded,
                        fmt(a.clusterCarbsG),
                        fmt(it.onsetMin)
                    )
                else
                    text.getString(
                        R.string.stage9_episode_phases_cluster,
                        fmt(a.clusterCarbsG),
                        fmt(it.onsetMin),
                        fmt(it.levelPeakMin),
                        fmt(it.mainEndMin)
                    )
            } ?: text.getString(R.string.stage9_episode_phases_cluster_none)
        if (a.timingScope == com.diapilot.core.hybrid.MealTimingScopeV1.COMPLETED_BEFORE_CLUSTER)
            return text.getString(R.string.stage9_episode_phases_completed)
        val seen = a.observedPhases.joinToString { phaseName(text, it) }
        val missing = (a.phaseWindowsAvailable - a.observedPhases).joinToString { phaseName(text, it) }
        if (seen.isBlank() && missing.isBlank())
            return text.getString(R.string.stage9_episode_phases_no_data)
        return listOfNotNull(
                seen
                    .takeIf { it.isNotBlank() }
                    ?.let { text.getString(R.string.stage9_episode_phases_seen, it) },
                missing
                    .takeIf { it.isNotBlank() }
                    ?.let { text.getString(R.string.stage9_episode_phases_missing, it) },
            )
            .joinToString(" ")
    }
    /** Core's caveats are English identifiers; unknown ones are shown as they are. */
    private val CAVEATS=mapOf(
        "overlap not identifiable" to R.string.stage9_episode_caveat_overlap,
        "individual timing replaced by aggregate meal-cluster timing" to R.string.stage9_episode_caveat_cluster_timing,
        "at least 90% completed before next intake; early contribution locked" to R.string.stage9_episode_caveat_locked,
        "joint model mismatch; episode excluded from confident food learning" to R.string.stage9_episode_caveat_mismatch,
        "meal-specific late window censored" to R.string.stage9_episode_caveat_late_censored,
        "meal-specific CGM window censored at gap or low density" to R.string.stage9_episode_caveat_cgm_censored,
        "input truncated; allocation unresolved" to R.string.stage9_episode_caveat_input_truncated,
        "insulin uncertainty propagated" to R.string.stage9_episode_caveat_insulin,
        "shared process residual retained" to R.string.stage9_episode_caveat_residual,
        "phase window available but phase not established" to R.string.stage9_episode_caveat_phase,
        "grams provenance is weak" to R.string.stage9_episode_caveat_weak_grams,
        "unknown context widens insulin interval" to R.string.stage9_episode_caveat_unknown_context,
    )
    private fun caveat(text: Context, s: String) = CAVEATS[s]?.let(text::getString) ?: s
}
