package com.diapilot.core.twin

/**
 * HOW MUCH THE APP TRUSTS ITS OWN LINE — computed from named inputs, by whoever
 * drew the line.
 *
 * Extracted from `ForecastEngine` (that file was later DELETED),
 * where it was inline and therefore belonged to ONE arm while being
 * inherited by the other. The physio arm copies
 * the legacy result as a template and replaces the trajectory, so it also
 * inherited the legacy arm's verdict on its own trustworthiness.
 *
 * THAT WAS A REAL DEFECT, and the numbers say so. Two of the five reasons below
 * are properties of the LEGACY twin:
 *
 *  - `effectiveEvidence` is the recency-weighted count of the legacy kernel's
 *    correction episodes. A typical snapshot reads `kernelEpisodes 12`
 *    and `effectiveEpisodes 0.0` — twelve episodes whose weights have all
 *    decayed — so this reason fires on EVERY run;
 *  - `corridorHalfWidth60` was the legacy corridor. The physio arm computes its
 *    own corridor AFTERWARDS, from its own variance ledger, so the width being
 *    judged was never the width being drawn.
 *
 * Consequence, measured on the pulled database: 1105 of 1106 runs in twenty-four
 * hours carry `LIMITED`, and the reason is an aged-out kernel the shown model
 * does not use. An audit read that as «the app knows its forecast is limited and
 * does not say so»; the sharper statement is that it did not know anything of
 * the kind — it was repeating another model's verdict.
 *
 * The gate itself is unchanged: same five reasons, same thresholds, same
 * ordering. What changes is WHO supplies the numbers.
 */
object ForecastHealthV1 {

    /** Older than this and the anchor cannot support a trajectory at all. */
    const val STALE_ANCHOR_MS = 15L * 60_000

    /** Above this at h=60 the model is saying it does not know. */
    const val WIDE_CORRIDOR_60_MMOL = 3.5

    /**
     * Effective evidence below which the amplitude rests on too little.
     *
     * «Effective» rather than raw: seventeen episodes whose weight sits on four
     * fresh ones are four episodes for trust purposes.
     */
    const val THIN_EVIDENCE = 10.0

    data class Verdict(
        val health: ForecastHealth,
        val reasons: List<HealthReason>,
    )

    /**
     * @param evidenceAvailable whether an evidence corpus exists at all. When it
     *        does not, the verdict is INSUFFICIENT_DATA rather than a thinness
     *        complaint — the two are different states and the old code kept them
     *        apart by checking `kernel.isEmpty()` separately.
     * @param effectiveEvidence the recency-weighted count of that corpus, in the
     *        units of whatever the CALLER learns its amplitude from. The legacy
     *        arm passes its kernel's effective episodes; the physio arm must pass
     *        its own, never the other's.
     * @param corridorHalfWidth60 the half-width at one hour OF THE CORRIDOR THIS
     *        ARM WILL DRAW.
     */
    fun evaluate(
        anchorAgeMs: Long,
        evidenceAvailable: Boolean,
        effectiveEvidence: Double,
        evidenceCount: Int,
        corridorHalfWidth60: Double,
        momentumAvailable: Boolean,
        minuteStreamAvailable: Boolean,
        sensorSuspect: String? = null,
    ): Verdict {
        val reasons = mutableListOf<HealthReason>()
        if (anchorAgeMs > STALE_ANCHOR_MS) {
            reasons.add(HealthReason.StaleData(anchorAgeMs / 60_000))
        }
        if (evidenceCount > 0 && effectiveEvidence < THIN_EVIDENCE) {
            reasons.add(HealthReason.ThinEvidence(effectiveEvidence))
        }
        if (corridorHalfWidth60 > WIDE_CORRIDOR_60_MMOL) {
            reasons.add(HealthReason.WideCorridor)
        }
        if (!momentumAvailable && !minuteStreamAvailable) {
            reasons.add(HealthReason.NoMinuteStream)
        }
        if (sensorSuspect != null) {
            reasons.add(HealthReason.SensorImplausible(sensorSuspect))
        }
        val health = when {
            anchorAgeMs > STALE_ANCHOR_MS -> ForecastHealth.STALE
            !evidenceAvailable -> ForecastHealth.INSUFFICIENT_DATA
            reasons.isNotEmpty() -> ForecastHealth.LIMITED
            else -> ForecastHealth.TRUSTED
        }
        return Verdict(health, reasons)
    }
}

/** Why a forecast is not TRUSTED; the app renders the sentence (i18n.TwinText). */
sealed interface HealthReason {
    /** The anchor reading is [ageMin] minutes old. */
    data class StaleData(val ageMin: Long) : HealthReason

    /** Only [effectiveCorrections] recency-weighted corrections behind the amplitude. */
    data class ThinEvidence(val effectiveCorrections: Double) : HealthReason

    /** The band at one hour is wider than [ForecastHealthV1.WIDE_CORRIDOR_60_MMOL]. */
    data object WideCorridor : HealthReason

    /** Neither the minute stream nor momentum is available. */
    data object NoMinuteStream : HealthReason

    /** The sensor reads implausibly; [detail] is the plausibility verdict, as data. */
    data class SensorImplausible(val detail: String) : HealthReason

    /** The trajectory hit the physiological floor ([floorMmol]) at [points] points. */
    data class FloorClamped(val floorMmol: Double, val points: Int) : HealthReason
}
