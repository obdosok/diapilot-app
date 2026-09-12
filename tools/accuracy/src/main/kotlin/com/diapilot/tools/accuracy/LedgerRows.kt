/**
 * The three facts this report is built from — plain data, no SQL and no
 * Android inside them, so [errorStats] and [evaluateHypoAlert] can be tested
 * on hand-written vectors instead of a database (see MetricsTest).
 *
 * The shapes mirror `forecast_runs` / `forecast_points` / `glucose_readings`
 * in `app/.../data/ForecastLedger.kt` and `SqliteCollectorStore.kt` — read
 * those first if a column here looks surprising.
 */
package com.diapilot.tools.accuracy

/** One row of `forecast_runs`, only the columns this report needs. */
data class RunRow(
    val id: Long,
    val anchorTsMs: Long,
    val consumer: String,
    /** `ForecastLedger.REFUSAL_ALGO_VERSION` ("refusal") when the model did
     *  not run at all — such a run has no points and is excluded from the
     *  accuracy numbers; see [MissingDataException] and docs/accuracy.md. */
    val algoVersion: String,
    /**
     * `forecast_runs.applied` — the insulin block and food knobs the phone was
     * RUNNING when it drew this forecast, as `ForecastLedger.appliedSummary`
     * formats them: `onset/peak/tail isf=... ramp=... kcal=... sieve=...`.
     *
     * Null on a row written before that column existed, which is a real case
     * on an old database: such a run cannot be assigned to a tail arm, and it
     * is left OUT of the comparison rather than folded into whichever arm
     * looks likelier. See [parseAppliedTailMin].
     */
    val applied: String? = null,
)

/** One row of `forecast_points`: what the app actually drew for one horizon
 *  of one run. `mmol` is on the meter-calibrated scale (see the SCALE note
 *  on `ForecastLedger`), same as the chart the user saw. */
data class ForecastPointRow(
    val runId: Long,
    val horizonMin: Int,
    val targetTsMs: Long,
    val mmol: Double,
)

/** One row of `glucose_readings` — the sensor's own scale, not calibrated to
 *  the meter. See the CAVEAT in docs/accuracy.md: comparing a calibrated
 *  prediction against a raw fact carries the same bias `ForecastLedger`
 *  documents under "SCALE", and this tool does not correct for it. */
data class GlucoseReadingRow(
    val tsMs: Long,
    val mmol: Double,
)
