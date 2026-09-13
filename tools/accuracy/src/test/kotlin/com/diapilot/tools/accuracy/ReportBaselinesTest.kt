package com.diapilot.tools.accuracy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * The whole report on a HAND-BUILT database: three tables with only the
 * columns the tool reads, one `main` run, sixteen readings. Every expected
 * number below is worked out by hand in the comments, so a change in the
 * arithmetic — or in which points the baselines are scored on — fails here
 * with a number, not with "the output changed".
 */
class ReportBaselinesTest {
    private val t0 = 1_700_000_000_000L
    private fun min(m: Int) = t0 + m * 60_000L

    private fun build(): File {
        val file = File.createTempFile("accuracy-baselines", ".sqlite").apply { delete() }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE forecast_runs(id INTEGER PRIMARY KEY, anchor_ts_ms INTEGER, consumer TEXT, algo_version TEXT, applied TEXT)")
                st.executeUpdate("CREATE TABLE forecast_points(run_id INTEGER, horizon_min INTEGER, target_ts_ms INTEGER, mmol REAL)")
                st.executeUpdate("CREATE TABLE glucose_readings(ts_ms INTEGER, mmol REAL)")
                // The quarter hour before the anchor rises 0.02 mmol/min; then
                // the glucose turns and falls — a shape neither baseline gets right.
                listOf(-15 to 6.0, -10 to 6.1, -5 to 6.2, 0 to 6.3, 30 to 6.5, 60 to 6.0, 120 to 5.0, 180 to 4.5)
                    .forEach { (m, v) -> st.executeUpdate("INSERT INTO glucose_readings VALUES(${min(m)}, $v)") }
                // Run 1: anchored at t0, one point per reported horizon.
                st.executeUpdate("INSERT INTO forecast_runs VALUES(1, ${min(0)}, 'main', 'v', '20/75/300 isf=1.8')")
                listOf(30 to 6.6, 60 to 6.2, 120 to 5.2, 180 to 4.4)
                    .forEach { (h, v) -> st.executeUpdate("INSERT INTO forecast_points VALUES(1, $h, ${min(h)}, $v)") }
                // Run 2: anchored where no reading exists (minute 200; the
                // nearest is 180, twenty minutes away). Its point at 30 min
                // has a fact, so the MODEL-ONLY table counts it — the baseline
                // block must not, because nothing was in hand at that anchor.
                st.executeUpdate("INSERT INTO glucose_readings VALUES(${min(230)}, 4.0)")
                st.executeUpdate("INSERT INTO forecast_runs VALUES(2, ${min(200)}, 'main', 'v', NULL)")
                st.executeUpdate("INSERT INTO forecast_points VALUES(2, 30, ${min(230)}, 4.8)")
            }
        }
        return file
    }

    @Test fun `baselines are scored beside the model on the common set`() {
        val file = build()
        try {
            val report = openDatabase(file).use { buildReport(it) }
            // Model-only table: horizon 30 has two matched points (runs 1 and 2).
            assertEquals(2, report.errorByHorizon.first { it.first == 30 }.second!!.n)
            val b30 = report.baselinesByHorizon.first { it.first == 30 }.second!!
            // Baseline block: run 2 has no anchor reading, so n = 1.
            assertEquals(1, b30.n)
            // Run 1 at 30 min: actual 6.5. Model 6.6 -> |err| 0.1.
            // Last value 6.3 -> 0.2. Linear 6.3 + 0.02*30 = 6.9 -> 0.4.
            assertEquals(0.1, b30.model.maeMmol, 1e-9)
            assertEquals(0.2, b30.lastValue.maeMmol, 1e-9)
            assertEquals(0.4, b30.linear.maeMmol, 1e-9)
            assertEquals(0.5, b30.skillVsLastValue!!, 1e-9)
            assertEquals(0.75, b30.skillVsLinear!!, 1e-9)
            // 180 min: actual 4.5. Model 4.4 -> 0.1; last value 6.3 -> 1.8;
            // linear 6.3 + 0.02*180 = 9.9 -> 5.4. Bias sign: actual - predicted.
            val b180 = report.baselinesByHorizon.first { it.first == 180 }.second!!
            assertEquals(+0.1, b180.model.biasMmol, 1e-9)
            assertEquals(-1.8, b180.lastValue.biasMmol, 1e-9)
            assertEquals(-5.4, b180.linear.biasMmol, 1e-9)
            assertEquals(1.0 - 0.1 / 1.8, b180.skillVsLastValue!!, 1e-9)
            // And the model's own table did not move: same n and MAE as before
            // the baselines existed, on horizon 60 (one point, |6.0 - 6.2|).
            assertEquals(0.2, report.errorByHorizon.first { it.first == 60 }.second!!.maeMmol, 1e-9)
        } finally {
            file.delete()
        }
    }

    @Test fun `the printed report carries the baseline block with the skill column`() {
        val file = build()
        try {
            val text = openDatabase(file).use { formatReport(buildReport(it)) }
            assertTrue(text, text.contains("Naive baselines from glucose_readings alone"))
            assertTrue(text, text.contains("skill = 1 - MAE(model) / MAE(baseline)"))
            val lines = text.lines()
            val model30 = lines.first { it.contains("30 min") && it.contains("model") }
            val last30 = lines.first { it.contains("30 min") && it.contains("last-value") }
            val lin30 = lines.first { it.contains("30 min") && it.contains("linear-15") }
            assertTrue(model30, model30.contains("0.100"))
            assertTrue(last30, last30.contains("+0.500"))
            assertTrue(lin30, lin30.contains("+0.750"))
        } finally {
            file.delete()
        }
    }

    @Test fun `a horizon with no anchored run prints as no data, never as zero`() {
        val file = File.createTempFile("accuracy-empty", ".sqlite").apply { delete() }
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate("CREATE TABLE forecast_runs(id INTEGER PRIMARY KEY, anchor_ts_ms INTEGER, consumer TEXT, algo_version TEXT, applied TEXT)")
                    st.executeUpdate("CREATE TABLE forecast_points(run_id INTEGER, horizon_min INTEGER, target_ts_ms INTEGER, mmol REAL)")
                    st.executeUpdate("CREATE TABLE glucose_readings(ts_ms INTEGER, mmol REAL)")
                    // One run whose anchor precedes every reading: a fact for the
                    // point exists, nothing was in hand at the anchor.
                    st.executeUpdate("INSERT INTO forecast_runs VALUES(1, ${min(0)}, 'main', 'v', NULL)")
                    st.executeUpdate("INSERT INTO forecast_points VALUES(1, 30, ${min(30)}, 6.0)")
                    st.executeUpdate("INSERT INTO glucose_readings VALUES(${min(30)}, 6.4)")
                }
            }
            val report = openDatabase(file).use { buildReport(it) }
            assertEquals(1, report.errorByHorizon.first { it.first == 30 }.second!!.n)
            assertNull(report.baselinesByHorizon.first { it.first == 30 }.second)
            val text = formatReport(report)
            assertTrue(text, text.contains("no run with a reading at its anchor"))
        } finally {
            file.delete()
        }
    }
}
