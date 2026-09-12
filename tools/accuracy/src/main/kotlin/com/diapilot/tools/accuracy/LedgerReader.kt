/**
 * The only file in this module that touches SQL or a `Connection`. Metrics
 * (Metrics.kt) do not know this class exists — see its header.
 */
package com.diapilot.tools.accuracy

import java.sql.Connection
import java.sql.DriverManager
import org.sqlite.SQLiteConfig

/** Thrown instead of printing a report built from nothing. The message is
 *  written to be read by a human, not caught by other code — it doubles as
 *  the "what a database needs" text. See docs/accuracy.md. */
class MissingDataException(message: String) : Exception(message)

/** `forecast_runs` / `forecast_points` are written by `ForecastLedger`;
 *  `glucose_readings` by `SqliteCollectorStore`. Both were read before
 *  writing this module — see their DDL for the columns this file assumes. */
private val REQUIRED_TABLES = listOf("forecast_runs", "forecast_points", "glucose_readings")

/** `ForecastLedger.REFUSAL_ALGO_VERSION` — a run recorded because the model
 *  refused to compute has no points and must not be treated as a prediction
 *  of "no hypo" or as a zero-error forecast. Duplicated here rather than
 *  imported: this module deliberately does not depend on `:app` (an Android
 *  module) or `:core`, see build.gradle.kts. */
private const val REFUSAL_ALGO_VERSION = "refusal"

/** Opens [path] read-only — this may be pointed at a copy of someone's real
 *  medical database, and a report tool has no business writing to it. */
fun openDatabase(path: java.io.File): Connection {
    if (!path.exists()) {
        throw MissingDataException("No database at $path.\n\n${databaseRequirementsMessage()}")
    }
    val config = SQLiteConfig()
    config.setReadOnly(true)
    val conn = DriverManager.getConnection("jdbc:sqlite:${path.absolutePath}", config.toProperties())
    try {
        // SQLite opens almost any file lazily; the first real read is what
        // notices "this isn't a database" (e.g. the golden JSON fixtures in
        // core/src/test/resources). Fail here, with our own message, rather
        // than surfacing a raw SQLiteException from deeper in the report.
        conn.createStatement().use { it.executeQuery("PRAGMA schema_version").use { rs -> rs.next() } }
    } catch (e: java.sql.SQLException) {
        conn.close()
        throw MissingDataException("$path is not a readable SQLite database (${e.message}).\n\n${databaseRequirementsMessage()}")
    }
    return conn
}

/** Fails loudly and specifically rather than printing a report of zeros or
 *  empty tables — the WP-A5 requirement this whole file exists to satisfy. */
fun requireForecastData(conn: Connection) {
    val existing = tableNames(conn)
    val missingTables = REQUIRED_TABLES.filter { it !in existing }
    if (missingTables.isNotEmpty()) {
        throw MissingDataException(
            "This database has no ${missingTables.joinToString(", ")} table(s).\n\n" +
                databaseRequirementsMessage(),
        )
    }
    val runCount = conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT COUNT(*) FROM forecast_runs " +
                "WHERE consumer IN ('main','hypo_alert') AND algo_version != '$REFUSAL_ALGO_VERSION'",
        ).use { rs -> rs.next(); rs.getInt(1) }
    }
    if (runCount == 0) {
        throw MissingDataException(
            "forecast_runs has no 'main' or 'hypo_alert' rows with an actual forecast " +
                "(every row is either missing or a refusal).\n\n${databaseRequirementsMessage()}",
        )
    }
}

private fun tableNames(conn: Connection): Set<String> {
    val names = mutableSetOf<String>()
    conn.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
        while (rs.next()) names += rs.getString("TABLE_NAME")
    }
    return names
}

fun databaseRequirementsMessage(): String = """
    A database this report can read needs:
      - forecast_runs / forecast_points, written by ForecastLedger.record()
        while the app runs — every time the main screen or the hypo alert
        produces a forecast, for consumers 'main' and 'hypo_alert'. A brand
        new install, or a database copied before the app has run for a
        while, has none of these.
      - glucose_readings, so predictions can be checked against what the
        sensor actually saw afterwards.

    In short: this is a report on a PHONE'S OWN HISTORY, not something that
    can be produced from a config file or a schema alone. See the USER
    ACTION section of docs/accuracy.md for how to pull a real database off a
    device and run this tool against it.
""".trimIndent()

fun readRuns(conn: Connection, consumer: String): List<RunRow> {
    val out = mutableListOf<RunRow>()
    conn.prepareStatement(
        "SELECT id, anchor_ts_ms, consumer, algo_version FROM forecast_runs " +
            "WHERE consumer = ? AND algo_version != ? ORDER BY anchor_ts_ms",
    ).use { ps ->
        ps.setString(1, consumer)
        ps.setString(2, REFUSAL_ALGO_VERSION)
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                out += RunRow(
                    id = rs.getLong(1), anchorTsMs = rs.getLong(2),
                    consumer = rs.getString(3), algoVersion = rs.getString(4),
                )
            }
        }
    }
    return out
}

fun readPoints(conn: Connection, runIds: List<Long>): List<ForecastPointRow> {
    if (runIds.isEmpty()) return emptyList()
    val out = mutableListOf<ForecastPointRow>()
    // forecast_points has no index on run_id beyond the PRIMARY KEY prefix,
    // but that prefix IS (run_id, horizon_min) — an IN-list here is a set of
    // small indexed lookups, not a scan of the whole (typically much larger)
    // table.
    runIds.chunked(500).forEach { chunk ->
        val placeholders = chunk.joinToString(",") { "?" }
        conn.prepareStatement(
            "SELECT run_id, horizon_min, target_ts_ms, mmol FROM forecast_points " +
                "WHERE run_id IN ($placeholders)",
        ).use { ps ->
            chunk.forEachIndexed { i, id -> ps.setLong(i + 1, id) }
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += ForecastPointRow(
                        runId = rs.getLong(1), horizonMin = rs.getInt(2),
                        targetTsMs = rs.getLong(3), mmol = rs.getDouble(4),
                    )
                }
            }
        }
    }
    return out
}

/** Ascending by `ts_ms`, as [nearestReading] and [evaluateHypoAlert] require. */
fun readGlucoseReadings(conn: Connection): List<GlucoseReadingRow> {
    val out = mutableListOf<GlucoseReadingRow>()
    conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT ts_ms, mmol FROM glucose_readings WHERE mmol IS NOT NULL ORDER BY ts_ms",
        ).use { rs ->
            while (rs.next()) out += GlucoseReadingRow(rs.getLong(1), rs.getDouble(2))
        }
    }
    return out
}
