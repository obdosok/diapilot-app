package com.diapilot.tools.accuracy

import java.sql.Connection

/** Horizons this report scores. A subset of `ForecastLedger.HORIZONS`
 *  (which also carries 15 and 90) — WP-A5 asks for these four. */
val REPORT_HORIZONS_MIN = listOf(30, 60, 120, 180)

data class AccuracyReport(
    val errorByHorizon: List<Pair<Int, ErrorStats?>>,
    val hypo: HypoConfusion,
    val hypoRunCount: Int,
)

fun buildReport(
    conn: Connection,
    thresholdMmol: Double = 3.9,
    leadMaxMin: Double = 45.0,
): AccuracyReport {
    val mainRuns = readRuns(conn, "main")
    val mainPoints = readPoints(conn, mainRuns.map { it.id })
    val readings = readGlucoseReadings(conn)

    val errorByHorizon = REPORT_HORIZONS_MIN.map { h ->
        h to errorStats(matchHorizon(mainPoints, readings, h))
    }

    val hypoRuns = readRuns(conn, "hypo_alert")
    val hypoPoints = readPoints(conn, hypoRuns.map { it.id }).groupBy { it.runId }
    val outcomes = evaluateHypoAlert(hypoRuns, hypoPoints, readings, thresholdMmol, leadMaxMin)
    val hypo = hypoConfusion(outcomes)

    return AccuracyReport(errorByHorizon, hypo, hypoRuns.size)
}

fun formatReport(report: AccuracyReport): String = buildString {
    appendLine("Forecast accuracy — bias / MAE / RMSE (mmol/L), consumer='main'")
    appendLine("horizon   n      bias      MAE      RMSE")
    for ((horizon, stats) in report.errorByHorizon) {
        if (stats == null) {
            appendLine("%6d min   -- no matched readings --".format(horizon))
        } else {
            appendLine(
                "%6d min  %5d  %+7.3f  %7.3f  %7.3f"
                    .format(horizon, stats.n, stats.biasMmol, stats.maeMmol, stats.rmseMmol),
            )
        }
    }
    appendLine()
    appendLine("Hypo alert vs what happened, consumer='hypo_alert' (${report.hypoRunCount} runs scored)")
    val h = report.hypo
    appendLine("TP=${h.truePos}  FP=${h.falsePos}  FN=${h.falseNeg}  TN=${h.trueNeg}")
    appendLine("precision = ${h.precision?.let { "%.3f".format(it) } ?: "undefined (nothing predicted)"}")
    appendLine("recall    = ${h.recall?.let { "%.3f".format(it) } ?: "undefined (no real hypo in the set)"}")
}
