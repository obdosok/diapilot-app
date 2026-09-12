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

// ---------------------------------------------------------------------------
// The end-of-action comparison (WP-B10). See the header block in Metrics.kt
// for what this is and, more importantly, for what it is NOT: it does not
// replay the engine under a counterfactual tail. It splits the ledger by the
// tail the phone was actually running and scores each side.
// ---------------------------------------------------------------------------

/** One arm of the comparison: everything the ledger says about the stretches
 *  that ran one end of action. */
data class TailArmReport(
    val tailMin: Double,
    val mainRuns: Int,
    val errorByHorizon: List<Pair<Int, ErrorStats?>>,
    val hypo: HypoConfusion,
    val hypoRuns: Int,
)

data class TailComparison(
    val arms: List<TailArmReport>,
    /** Every applied end of action the ledger holds, with its run count —
     *  printed so an empty arm can be told from a mistyped one. */
    val tailsPresent: List<Pair<Double, Int>>,
    /** `main` runs whose `applied` column could not be parsed, hence assigned
     *  to no arm. Reported, never silently dropped. */
    val unattributedMainRuns: Int,
    val toleranceMin: Double,
)

fun buildTailComparison(
    conn: Connection,
    tailsMin: List<Double>,
    toleranceMin: Double = 1.0,
    thresholdMmol: Double = 3.9,
    leadMaxMin: Double = 45.0,
): TailComparison {
    val mainRuns = readRuns(conn, "main")
    val hypoRuns = readRuns(conn, "hypo_alert")
    val readings = readGlucoseReadings(conn)
    val arms = tailsMin.map { tail ->
        val mine = runsWithAppliedTail(mainRuns, tail, toleranceMin)
        val points = readPoints(conn, mine.map { it.id })
        val hypoMine = runsWithAppliedTail(hypoRuns, tail, toleranceMin)
        val hypoPoints = readPoints(conn, hypoMine.map { it.id }).groupBy { it.runId }
        TailArmReport(
            tailMin = tail,
            mainRuns = mine.size,
            errorByHorizon = REPORT_HORIZONS_MIN.map { h ->
                h to errorStats(matchHorizon(points, readings, h))
            },
            hypo = hypoConfusion(
                evaluateHypoAlert(hypoMine, hypoPoints, readings, thresholdMmol, leadMaxMin),
            ),
            hypoRuns = hypoMine.size,
        )
    }
    return TailComparison(
        arms = arms,
        tailsPresent = appliedTailsPresent(mainRuns),
        unattributedMainRuns = mainRuns.count { parseAppliedTailMin(it.applied) == null },
        toleranceMin = toleranceMin,
    )
}

fun formatTailComparison(c: TailComparison): String = buildString {
    appendLine("End of action — the ledger split by the tail the phone was RUNNING")
    appendLine("(not a replay: see docs/accuracy.md, \"Comparing two end-of-action values\")")
    appendLine()
    appendLine("Applied tails found in forecast_runs (consumer='main'):")
    if (c.tailsPresent.isEmpty()) {
        appendLine("  -- none: no run carries a parsable 'applied' column --")
    } else {
        for ((tail, n) in c.tailsPresent) appendLine("  %7.0f min  %6d runs".format(tail, n))
    }
    if (c.unattributedMainRuns > 0) {
        appendLine(
            "  %6d run(s) with no parsable 'applied' column — in no arm".format(
                c.unattributedMainRuns,
            ),
        )
    }
    appendLine()
    for (arm in c.arms) {
        appendLine(
            "tail %.0f min (±%.0f) — %d 'main' runs, %d 'hypo_alert' runs".format(
                arm.tailMin, c.toleranceMin, arm.mainRuns, arm.hypoRuns,
            ),
        )
        if (arm.mainRuns == 0 && arm.hypoRuns == 0) {
            appendLine("  -- no run in this database ran this end of action --")
            appendLine()
            continue
        }
        appendLine("  horizon   n      bias      MAE      RMSE")
        for ((horizon, stats) in arm.errorByHorizon) {
            if (stats == null) {
                appendLine("  %6d min   -- no matched readings --".format(horizon))
            } else {
                appendLine(
                    "  %6d min  %5d  %+7.3f  %7.3f  %7.3f".format(
                        horizon, stats.n, stats.biasMmol, stats.maeMmol, stats.rmseMmol,
                    ),
                )
            }
        }
        val h = arm.hypo
        appendLine("  low alert: TP=${h.truePos} FP=${h.falsePos} FN=${h.falseNeg} TN=${h.trueNeg}")
        appendLine(
            "  precision = ${h.precision?.let { "%.3f".format(it) } ?: "undefined (nothing predicted)"}" +
                " · recall = ${h.recall?.let { "%.3f".format(it) } ?: "undefined (no real low in the set)"}",
        )
        appendLine()
    }
    // The difference is printed only for exactly two arms and only where both
    // sides have a number: a delta against "no data" is not a delta, and three
    // arms have three differences, which is a table the reader should ask for
    // rather than one this tool invents.
    val (a, b) = c.arms.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return@buildString
    appendLine("Difference, tail %.0f minus tail %.0f".format(b.tailMin, a.tailMin))
    // The sign, spelled out where it is actually read. bias = actual −
    // predicted, so the arm with the MORE NEGATIVE bias is the one that
    // predicted HIGHER — and a tail too short stops subtracting insulin
    // action, which is exactly how it surfaces at 120-180 min after a bolus.
    // Under-modelled food amplitude produces the same sign (audit M3/M5), so
    // this column narrows the question rather than answering it.
    appendLine("bias = actual - predicted, so the MORE NEGATIVE arm is the one that read HIGH")
    appendLine("horizon      d-bias     d-MAE    d-RMSE")
    for ((horizon, _) in a.errorByHorizon) {
        val sa = a.errorByHorizon.first { it.first == horizon }.second
        val sb = b.errorByHorizon.first { it.first == horizon }.second
        if (sa == null || sb == null) {
            appendLine("%6d min   -- one side has no data --".format(horizon))
        } else {
            appendLine(
                "%6d min  %+8.3f  %+8.3f  %+8.3f".format(
                    horizon,
                    sb.biasMmol - sa.biasMmol,
                    sb.maeMmol - sa.maeMmol,
                    sb.rmseMmol - sa.rmseMmol,
                ),
            )
        }
    }
    appendLine()
    appendLine("The two arms are different stretches of life, not paired runs. Check n,")
    appendLine("and read the caveats in docs/accuracy.md before concluding anything.")
}
