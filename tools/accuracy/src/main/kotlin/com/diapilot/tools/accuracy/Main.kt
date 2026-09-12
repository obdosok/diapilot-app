/**
 * CLI entry point. Usage:
 *
 *   ./gradlew :tools:accuracy:run --args="/path/to/diapilot.db"
 *
 * See docs/accuracy.md for how to obtain a real database and what to do
 * with the output.
 */
package com.diapilot.tools.accuracy

import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: accuracy <path-to-diapilot-database>")
        kotlin.system.exitProcess(2)
    }
    val dbPath = File(args[0])
    try {
        openDatabase(dbPath).use { conn ->
            requireForecastData(conn)
            val report = buildReport(conn)
            print(formatReport(report))
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
