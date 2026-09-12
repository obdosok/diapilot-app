/**
 * CLI entry point. Usage:
 *
 *   ./gradlew :tools:accuracy:run --args="/path/to/diapilot.db"
 *   ./gradlew :tools:accuracy:run --args="/path/to/diapilot.db --compare-tail=130,300"
 *
 * The second form adds the end-of-action comparison: the same metrics, with
 * the ledger split by the insulin tail the phone was actually RUNNING when it
 * drew each forecast. It is NOT a replay — see docs/accuracy.md for what a
 * replay would need and why this module cannot do it.
 *
 * See docs/accuracy.md for how to obtain a real database and what to do
 * with the output.
 */
package com.diapilot.tools.accuracy

import java.io.File

private const val COMPARE_TAIL = "--compare-tail="
private const val TAIL_TOLERANCE = "--tail-tolerance="

private const val USAGE =
    "usage: accuracy <path-to-diapilot-database> [--compare-tail=A,B] [--tail-tolerance=MIN]"

/** Parsed apart from the report so a bad flag fails before any database is
 *  opened, and so the parsing itself is testable without one. */
internal data class Options(
    val dbPath: String,
    val compareTailsMin: List<Double> = emptyList(),
    val toleranceMin: Double = 1.0,
)

/** @throws IllegalArgumentException with a message written for a human. */
internal fun parseArgs(args: Array<String>): Options {
    val path = args.firstOrNull { !it.startsWith("--") }
        ?: throw IllegalArgumentException(USAGE)
    var tails = emptyList<Double>()
    var tolerance = 1.0
    for (arg in args.filter { it.startsWith("--") }) when {
        arg.startsWith(COMPARE_TAIL) -> {
            tails = arg.removePrefix(COMPARE_TAIL).split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { value ->
                    value.toDoubleOrNull()
                        ?: throw IllegalArgumentException(
                            "$COMPARE_TAIL\"$value\" is not a number of minutes",
                        )
                }
            // One value is not a comparison, and printing a single arm under a
            // flag named "compare" is how a reader ends up quoting half a
            // table as the result.
            if (tails.size < 2) {
                throw IllegalArgumentException(
                    "$COMPARE_TAIL needs at least two end-of-action values, " +
                        "for example ${COMPARE_TAIL}130,300",
                )
            }
        }
        arg.startsWith(TAIL_TOLERANCE) ->
            tolerance = arg.removePrefix(TAIL_TOLERANCE).toDoubleOrNull()
                ?.takeIf { it >= 0.0 }
                ?: throw IllegalArgumentException(
                    "$TAIL_TOLERANCE needs a non-negative number of minutes",
                )
        else -> throw IllegalArgumentException("unknown option \"$arg\"\n$USAGE")
    }
    return Options(path, tails, tolerance)
}

fun main(args: Array<String>) {
    val options = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        kotlin.system.exitProcess(2)
    }
    val dbPath = File(options.dbPath)
    try {
        openDatabase(dbPath).use { conn ->
            requireForecastData(conn)
            print(formatReport(buildReport(conn)))
            if (options.compareTailsMin.isNotEmpty()) {
                println()
                print(
                    formatTailComparison(
                        buildTailComparison(conn, options.compareTailsMin, options.toleranceMin),
                    ),
                )
            }
        }
    } catch (e: MissingDataException) {
        System.err.println(e.message)
        kotlin.system.exitProcess(1)
    } catch (e: java.sql.SQLException) {
        System.err.println("Could not read $dbPath as a DiaPilot database: ${e.message}")
        System.err.println()
        System.err.println(databaseRequirementsMessage())
        kotlin.system.exitProcess(1)
    }
}
