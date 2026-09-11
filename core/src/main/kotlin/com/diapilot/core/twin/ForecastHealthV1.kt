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
        val reasons: List<String>,
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
        val reasons = mutableListOf<String>()
        if (anchorAgeMs > STALE_ANCHOR_MS) {
            reasons.add("данные устарели (${anchorAgeMs / 60_000} мин)")
        }
        if (evidenceCount > 0 && effectiveEvidence < THIN_EVIDENCE) {
            reasons.add(
                "эффективно свежих коррекций всего %.0f — модель опирается на мало данных"
                    .format(effectiveEvidence),
            )
        }
        if (corridorHalfWidth60 > WIDE_CORRIDOR_60_MMOL) {
            reasons.add("коридор очень широк — модель не уверена в этом режиме")
        }
        if (!momentumAvailable && !minuteStreamAvailable) {
            reasons.add("минутный поток недоступен — без momentum")
        }
        if (sensorSuspect != null) {
            reasons.add("датчик читает неправдоподобно ($sensorSuspect) — якорю не доверяем")
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
