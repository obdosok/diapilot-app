package com.diapilot.core.physio

import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.analysis.ParametricKernel
import com.diapilot.core.analysis.iobFraction
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.hybrid.HybridForecastEngine
import com.diapilot.core.hybrid.HybridPersonModel
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Stage 9 causal insulin model.  Event facts, priors, retrospective diagnostics,
 * causal evidence and promoted parameters deliberately have different types.
 * Only [EpisodeContextFactV1] and [PromotedKernelEffectV1] can change a live
 * median, and both are rejected when they were not known at [asOfMs].
 */
const val EPISODE_KERNEL_VERSION_V1 = "episode-kernel-v1"

enum class KernelFactorKindV1 { TOD, DAY_STATE, ACTIVITY, PREVIOUS_DAY_ACTIVITY, SLEEP,
    PRIOR_HYPO, BASAL_BACKGROUND, HIGH_GLUCOSE_BACKGROUND, ILLNESS_STRESS, ALCOHOL,
    INSULIN_PRODUCT_AGE_HEAT, INJECTION_SITE, SENSOR_QUALITY, DOSE_DEPENDENCE }
enum class KernelAxisV1 { TIMING, POTENCY, BACKGROUND, UNCERTAINTY_ONLY }
enum class KernelEvidenceLayerV1 { EPISODE_FACT, MODEL_PRIOR, POST_HOC_DIAGNOSTIC,
    CAUSAL_EVIDENCE, PROMOTED_RUNTIME }

data class EpisodeContextFactV1(
    val kind: KernelFactorKindV1,
    val value: Double?,
    val unit: String,
    val eventAtMs: Long,
    val knownAtMs: Long,
    val revision: Int = 1,
    val provenance: String,
) { init { require(knownAtMs >= eventAtMs); require(revision > 0); require(provenance.isNotBlank()) } }

/** A post-hoc fact may be displayed but can never enter kernelForEpisode(). */
data class EpisodeDiagnosticV1(val kind: KernelFactorKindV1, val value: Double?, val knownAtMs: Long, val provenance: String)

data class IdentifiedIsfEvidenceV1(
    val episodeAtMs: Long,
    val knownAtMs: Long,
    val isfMmolPerU: Double,
    val weight: Double,
    val revision: Int,
    val provenance: String,
) { init { require(knownAtMs >= episodeAtMs && isfMmolPerU > 0 && weight in 0.0..1.0) } }

data class PromotedKernelEffectV1(
    val kind: KernelFactorKindV1,
    val axis: KernelAxisV1,
    /** Multiplicative median, 1 = no change. Timing scales peak and DIA. */
    val median: Double,
    val low: Double,
    val high: Double,
    val knownAtMs: Long,
    val revision: Int,
    val provenance: String,
    val doseMinU: Double? = null,
) {
    init { require(low > 0 && low <= median && median <= high); require(revision > 0) }
}

data class KernelFactorPosteriorV1(
    val kind: KernelFactorKindV1,
    val axis: KernelAxisV1,
    val median: Double,
    val low: Double,
    val high: Double,
    val layer: KernelEvidenceLayerV1,
    val knownAtMs: Long?,
    val revision: Int?,
    val provenance: String,
    val appliedToMedian: Boolean,
)

data class EpisodeKernelResultV1(
    val version: String,
    val episodeAtMs: Long,
    val asOfMs: Long,
    val points: List<KernelPoint>,
    val isf: PosteriorV1,
    val peakMin: PosteriorV1,
    val diaMin: PosteriorV1,
    val potency: PosteriorV1,
    val backgroundMmolPerHour: PosteriorV1,
    val factors: List<KernelFactorPosteriorV1>,
    val basePriorIdentity: String,
    val basePriorKnownAtMs: Long,
    val basePriorRevision: Int,
    val provenanceHash: String,
) {
    init { require(asOfMs >= episodeAtMs); require(points.isNotEmpty()) }
}

data class EpisodeKernelConfigV1(
    val bounds: PhysioBoundsV1 = PhysioBoundsV1(),
    val dayHalfLifeDays: Double = 3.0,
    val globalShrinkageEpisodes: Double = 8.0,
    val unknownFactorLogSigma: Double = 0.035,
    val maxTimingMultiplier: ClosedFloatingPointRange<Double> = 0.70..1.35,
    val maxPotencyMultiplier: ClosedFloatingPointRange<Double> = 0.55..1.45,
)

/** A base shape/global prior is itself causal evidence and must be selected as-of. */
data class EpisodeKernelPriorRevisionV1(
    val identity: String,
    val knownAtMs: Long,
    val revision: Int,
    val base: ParametricKernel,
    val globalIsf: PosteriorV1,
    val provenance: String,
) {
    init {
        require(identity.isNotBlank() && revision > 0 && knownAtMs >= 0 && provenance.isNotBlank())
    }
}

object EpisodeKernelPriorsV1 {
    val PHYSIOLOGICAL_V1 =
        EpisodeKernelPriorRevisionV1(
            "physiological-insulin-prior-v1",
            0L,
            1,
            ParametricKernel(2.5, 240.0, 65.0, 0, 0, 0.0, .75, false, false, null),
            PosteriorV1(2.5, 1.5, 4.0, 0, 0, 0L),
            "frozen physiological prior; no patient outcome data",
        )
}

/** Adapter from the selected forecast's exact onset/peak/tail mechanics to the
 * episode-kernel receipt consumed by joint food attribution. */
fun hybridEpisodeKernelV1(
    person: HybridPersonModel,
    bolus: BolusPoint,
    asOfMs: Long,
): EpisodeKernelResultV1 {
    val p = person.insulin
    val points = HybridForecastEngine(person).insulinKernelPoints(bolus.units)
    val identity = "selected-engine:${person.modelVersion}"
    val body =
        "$identity|${bolus.tsMs}|${bolus.units}|$asOfMs|${p.onsetMin}|${p.peakMin}|${p.tailDurationMin}|${p.isf}|${p.isfLow}|${p.isfHigh}|${points.joinToString{it.tauMin.toString()+":"+it.median}}"
    val hash =
        java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString(
            ""
        ) {
            "%02x".format(it)
        }
    return EpisodeKernelResultV1(
        EPISODE_KERNEL_VERSION_V1,
        bolus.tsMs,
        asOfMs,
        points,
        PosteriorV1(p.isf, p.isfLow, p.isfHigh, 0, 0, 0),
        PosteriorV1(p.peakMin, p.peakMin, p.peakMin, 0, 0, 0),
        PosteriorV1(p.tailDurationMin, p.tailDurationMin, p.tailDurationMin, 0, 0, 0),
        PosteriorV1(1.0, 1.0, 1.0, 0, 0, 0),
        PosteriorV1(0.0, 0.0, 0.0, 0, 0, 0),
        emptyList(),
        identity,
        0L,
        1,
        hash,
    )
}

class VersionedEpisodeKernelModelV1(
    private val priors: List<EpisodeKernelPriorRevisionV1>,
    private val identifiedIsf: List<IdentifiedIsfEvidenceV1> = emptyList(),
    private val promoted: List<PromotedKernelEffectV1> = emptyList(),
    private val config: EpisodeKernelConfigV1 = EpisodeKernelConfigV1(),
) {
    init { require(priors.isNotEmpty()) }
    fun kernelForEpisode(
        timestampMs: Long,
        context: List<EpisodeContextFactV1>,
        asOfMs: Long,
        doseU: Double? = null
    ): EpisodeKernelResultV1 {
        val prior =
            priors
                .filter { it.knownAtMs <= asOfMs }
                .maxWithOrNull(
                    compareBy<EpisodeKernelPriorRevisionV1> { it.knownAtMs }.thenBy { it.revision }
                ) ?: error("no base prior known at $asOfMs")
        return EpisodeKernelModelV1(
                prior.base,
                prior.globalIsf,
                identifiedIsf,
                promoted,
                config,
                prior.identity,
                prior.knownAtMs,
                prior.revision
            )
            .kernelForEpisode(timestampMs, context, asOfMs, doseU)
    }
}

class EpisodeKernelModelV1(
    private val base: ParametricKernel,
    private val globalIsf: PosteriorV1,
    private val identifiedIsf: List<IdentifiedIsfEvidenceV1> = emptyList(),
    private val promoted: List<PromotedKernelEffectV1> = emptyList(),
    private val config: EpisodeKernelConfigV1 = EpisodeKernelConfigV1(),
    private val basePriorIdentity: String = "legacy-unversioned-constructor",
    private val basePriorKnownAtMs: Long = 0L,
    private val basePriorRevision: Int = 1,
) {
    /**
     * Causal by construction: future event times, future-known revisions and
     * post-hoc diagnostics have no parameter through which to enter this call.
     */
    fun kernelForEpisode(
        timestampMs: Long,
        context: List<EpisodeContextFactV1>,
        asOfMs: Long,
        doseU: Double? = null,
    ): EpisodeKernelResultV1 {
        require(asOfMs >= timestampMs)
        require(basePriorKnownAtMs <= asOfMs) { "base prior is future-known" }
        val facts = context.filter { it.eventAtMs <= timestampMs && it.knownAtMs <= asOfMs }
        val evidence = identifiedIsf.filter { it.episodeAtMs < timestampMs && it.knownAtMs <= asOfMs }
        val day = dayState(timestampMs, evidence)
        var isfMedian = day.median
        var isfLogVar = day.logVariance
        var timing = 1.0
        var potency = 1.0
        var background = 0.0
        var backgroundVar = 0.04
        val factorRows = mutableListOf<KernelFactorPosteriorV1>()

        factorRows +=
            KernelFactorPosteriorV1(
                KernelFactorKindV1.DAY_STATE,
                KernelAxisV1.POTENCY,
                day.median / globalIsf.median,
                day.low / globalIsf.median,
                day.high / globalIsf.median,
                KernelEvidenceLayerV1.CAUSAL_EVIDENCE,
                day.latestKnownAt,
                day.revision,
                day.provenance,
                true
            )

        for (kind in KernelFactorKindV1.entries.filter { it != KernelFactorKindV1.DAY_STATE }) {
            val fact = facts.filter { it.kind == kind }.maxByOrNull { it.knownAtMs }
            val candidates =
                promoted
                    .filter { it.kind == kind && it.knownAtMs <= asOfMs }
                    .filter { it.doseMinU == null || (doseU != null && doseU >= it.doseMinU) }
            val effect =
                candidates.maxWithOrNull(
                    compareBy<PromotedKernelEffectV1> { it.knownAtMs }.thenBy { it.revision }
                )
            if (fact == null || effect == null) {
                // Unknown does not invent a direction. It only widens the interval.
                isfLogVar += config.unknownFactorLogSigma * config.unknownFactorLogSigma
                factorRows +=
                    KernelFactorPosteriorV1(
                        kind,
                        KernelAxisV1.UNCERTAINTY_ONLY,
                        1.0,
                        exp(-config.unknownFactorLogSigma),
                        exp(config.unknownFactorLogSigma),
                        KernelEvidenceLayerV1.MODEL_PRIOR,
                        fact?.knownAtMs,
                        fact?.revision,
                        if (fact == null) "context unknown" else "not promoted: ${fact.provenance}",
                        false
                    )
                continue
            }
            val m = effect.median
            when (effect.axis) {
                KernelAxisV1.TIMING -> timing *= m
                KernelAxisV1.POTENCY -> potency *= m
                KernelAxisV1.BACKGROUND -> {
                    background += (m - 1.0);
                    backgroundVar += ((effect.high - effect.low) / 3.29).let { it * it }
                }
                KernelAxisV1.UNCERTAINTY_ONLY -> Unit
            }
            val sigma = ln(effect.high / effect.low).coerceAtLeast(0.0) / 3.29
            if (effect.axis == KernelAxisV1.POTENCY || effect.axis == KernelAxisV1.UNCERTAINTY_ONLY)
                isfLogVar += sigma * sigma
            factorRows +=
                KernelFactorPosteriorV1(
                    kind,
                    effect.axis,
                    m,
                    effect.low,
                    effect.high,
                    KernelEvidenceLayerV1.PROMOTED_RUNTIME,
                    effect.knownAtMs,
                    effect.revision,
                    "${effect.provenance}; fact=${fact.provenance}",
                    effect.axis != KernelAxisV1.UNCERTAINTY_ONLY
                )
        }

        timing = timing.coerceIn(config.maxTimingMultiplier)
        potency = potency.coerceIn(config.maxPotencyMultiplier)
        isfMedian =
            (isfMedian * potency).coerceIn(config.bounds.isfMmolPerLUmin, config.bounds.isfMmolPerLUmax)
        val isfSigma = sqrt(isfLogVar)
        val isfLow = (isfMedian * exp(-1.2816 * isfSigma)).coerceAtLeast(config.bounds.isfMmolPerLUmin)
        val isfHigh = (isfMedian * exp(1.2816 * isfSigma)).coerceAtMost(config.bounds.isfMmolPerLUmax)
        val peak = (base.peakMin * timing).coerceIn(config.bounds.insulinPeakMinRange)
        // THE AUTOMATIC RANGE, AND THIS ONE IS NOT A HAND VALUE. `base` is the
        // FROZEN PRIOR (`EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1`, DIA 240) and
        // `timing` is a promoted multiplier from the kernel-effect ledger — a
        // number no person ever typed, so `insulinTailMinRange` is exactly the
        // right domain for it. The hand-entered end of action reaches an
        // episode kernel by a DIFFERENT route,
        // [hybridEpisodeKernelV1], which reads `person.insulin.tailDurationMin`
        // and passes it through unclamped. Audit M1 named this line as a place
        // that coerces a manual DIA; it never saw one.
        val dia = (base.diaMin * timing).coerceIn(config.bounds.insulinTailMinRange)
        val n = (dia / 5.0).toInt()
        val points =
            (0..n).map { i ->
                val tau = i * 5.0
                fun g(isf: Double, p: Double, d: Double) = -isf * (1.0 - iobFraction(tau, d, p))
                val med = g(isfMedian, peak, dia)
                KernelPoint(
                    tau,
                    med,
                    minOf(g(isfHigh, peak * .85, dia * .85), med),
                    maxOf(g(isfLow, peak * 1.15, dia * 1.15), med),
                    base.nEpisodes
                )
            }
        val latest =
            (factorRows.mapNotNull { it.knownAtMs } + evidence.map { it.knownAtMs }).maxOrNull() ?: 0L
        val hashBody = buildString {
            append(EPISODE_KERNEL_VERSION_V1)
                .append('|')
                .append(timestampMs)
                .append('|')
                .append(asOfMs)
                .append('|')
                .append(doseU)
                .append('|')
            append(basePriorIdentity)
                .append(':')
                .append(basePriorKnownAtMs)
                .append(':')
                .append(basePriorRevision)
                .append('|')
            append(base.isfMmolPerU)
                .append(':')
                .append(base.diaMin)
                .append(':')
                .append(base.peakMin)
                .append(':')
                .append(base.nEpisodes)
                .append(':')
                .append(base.rmseMmolPerU)
                .append('|')
            append(globalIsf.median)
                .append(':')
                .append(globalIsf.p10)
                .append(':')
                .append(globalIsf.p90)
                .append(':')
                .append(globalIsf.identifyingEpisodes)
                .append(':')
                .append(globalIsf.independentDays)
                .append(':')
                .append(globalIsf.lastUpdateMs)
                .append('|')
            append("%.6f|%.3f|%.3f|".format(isfMedian, peak, dia))
            evidence
                .sortedBy { it.knownAtMs }
                .forEach {
                    append("ev:")
                        .append(it.episodeAtMs)
                        .append(':')
                        .append(it.knownAtMs)
                        .append(':')
                        .append(it.isfMmolPerU)
                        .append(':')
                        .append(it.weight)
                        .append(':')
                        .append(it.revision)
                        .append(':')
                        .append(it.provenance)
                        .append(';')
                }
            facts
                .sortedWith(compareBy<EpisodeContextFactV1> { it.kind.name }.thenBy { it.knownAtMs })
                .forEach {
                    append("fact:")
                        .append(it.kind)
                        .append(':')
                        .append(it.value)
                        .append(':')
                        .append(it.unit)
                        .append(':')
                        .append(it.eventAtMs)
                        .append(':')
                        .append(it.knownAtMs)
                        .append(':')
                        .append(it.revision)
                        .append(':')
                        .append(it.provenance)
                        .append(';')
                }
            factorRows
                .sortedBy { it.kind.name }
                .forEach {
                    append(it.kind)
                        .append(':')
                        .append(it.revision)
                        .append(':')
                        .append(it.median)
                        .append(':')
                        .append(it.low)
                        .append(':')
                        .append(it.high)
                        .append(':')
                        .append(it.provenance)
                        .append(';')
                }
        }
        val hash =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(hashBody.toByteArray())
                .joinToString("") { "%02x".format(it) }
        return EpisodeKernelResultV1(
            EPISODE_KERNEL_VERSION_V1,
            timestampMs,
            asOfMs,
            points,
            PosteriorV1(
                isfMedian,
                isfLow,
                isfHigh,
                evidence.size,
                evidence.map { it.episodeAtMs / 86_400_000 }.distinct().size,
                latest
            ),
            PosteriorV1(peak, peak * .85, peak * 1.15, base.nEpisodes, 0, latest),
            PosteriorV1(dia, dia * .85, dia * 1.15, base.nEpisodes, 0, latest),
            PosteriorV1(
                potency,
                config.maxPotencyMultiplier.start,
                config.maxPotencyMultiplier.endInclusive,
                0,
                0,
                latest
            ),
            PosteriorV1(
                background,
                background - 1.2816 * sqrt(backgroundVar),
                background + 1.2816 * sqrt(backgroundVar),
                0,
                0,
                latest
            ),
            factorRows,
            basePriorIdentity,
            basePriorKnownAtMs,
            basePriorRevision,
            hash
        )
    }

    private data class DayState(
        val median: Double,
        val low: Double,
        val high: Double,
        val logVariance: Double,
        val latestKnownAt: Long?,
        val revision: Int?,
        val provenance: String
    )
    private fun dayState(atMs: Long, rows: List<IdentifiedIsfEvidenceV1>): DayState {
        if (rows.isEmpty()) {
            val s = ln(globalIsf.p90 / globalIsf.p10).coerceAtLeast(.01) / 2.5632
            return DayState(
                globalIsf.median,
                globalIsf.p10,
                globalIsf.p90,
                s * s,
                null,
                null,
                "global prior; no causal episode"
            )
        }
        val weighted = rows.map { r ->
            val ageDays = (atMs - r.episodeAtMs).toDouble() / 86_400_000.0
            r to r.weight * exp(-ln(2.0) * ageDays / config.dayHalfLifeDays)
        }
        val sw = weighted.sumOf { it.second }
        val priorW = config.globalShrinkageEpisodes
        val raw =
            (priorW * globalIsf.median + weighted.sumOf { it.first.isfMmolPerU * it.second }) /
                (priorW + sw)
        val last = rows.maxBy { it.episodeAtMs }
        val bounded =
            config.bounds.boundedIsf(
                globalIsf.median,
                raw,
                ((atMs - last.episodeAtMs) / 86_400_000L).toInt().coerceAtLeast(1)
            )
        val ess = sw * sw / weighted.sumOf { it.second * it.second }.coerceAtLeast(1e-9)
        val sigma = (.28 / sqrt(priorW + ess)).coerceAtLeast(.06)

        return DayState(
            bounded,
            bounded * exp(-1.2816 * sigma),
            bounded * exp(1.2816 * sigma),
            sigma * sigma,
            last.knownAtMs,
            last.revision,
            "${rows.size} causal ISF observations, shrinkage=$priorW"
        )
    }
}
