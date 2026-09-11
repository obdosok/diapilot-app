package io.github.obdosok.diapilot.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE LEDGER MUST RECORD THE LINE THAT WAS SHOWN.
 *
 * This regressed once and nothing caught it. One commit ("legacy v11 hand-off
 * removed from the forecast") removed the `record` call that stored the
 * physio run, and the surviving call passed `result` — the BASE TWIN — under
 * `FORECAST_ALGO_VERSION`. For a day the app stored a forecast nobody saw,
 * while the line it drew went unrecorded.
 *
 * WHY A SOURCE TEST. The failure is invisible to every behavioural test: rows
 * keep appearing, at the right cadence, with plausible numbers. Only their
 * CONTENT is the wrong arm. Two things depend on it and both fail silently —
 * the discipline-#7 acceptance test («arm A must reproduce the STORED
 * forecast_runs on the same anchors») has nothing to reproduce, and
 * `recentHybridAnomalies`, which selects on the physio tag, returns an empty
 * card rather than an error.
 */
class LedgerRecordsShownArmTest {

    private fun source(rel: String): String =
        File(rel).let { if (it.exists()) it else File("app/$rel") }.readText()

    private val forecaster = source("src/main/java/io/github/obdosok/diapilot/data/Forecaster.kt")

    private fun recordCall(): String {
        val at = forecaster.indexOf("ForecastLedger.record(")
        assertTrue("the ledger call is gone — the app records nothing", at >= 0)
        return forecaster.substring(at, forecaster.indexOf(")", forecaster.indexOf("algoVersion", at)) + 1)
    }

    @Test
    fun `the recorded result is the one that is displayed`() {
        assertTrue(
            "`shown` is not derived from the physio run",
            forecaster.contains("val shown = physioShadow.result"),
        )
        val call = recordCall()
        assertTrue("the ledger is not passed the shown line: $call", call.contains("db, shown,"))
        assertFalse(
            "the ledger is passed the base twin instead of the shown line: $call",
            call.contains("db, result,"),
        )
    }

    /**
     * And the label must come off the run, not from a constant chosen here —
     * that is exactly how the row and the model parted company.
     */
    @Test
    fun `the algo tag travels with the run`() {
        assertTrue(
            "the tag is not read from the run",
            forecaster.contains("physioShadow.algorithmVersion"),
        )
        assertTrue(
            "the ledger call does not carry the run's own tag: ${recordCall()}",
            recordCall().contains("algoVersion = shownVersion"),
        )
    }

    /**
     * A MISSING RUN MUST LEAVE A NAMED REFUSAL, NOT A HOLE AND NOT A FAKE ROW.
     *
     * The first cut of this change stayed silent when the physio arm produced
     * nothing, reasoning that a row without points reads as a forecast that did
     * not happen. The user overruled it, and the argument is decisive:
     * silence is INDISTINGUISHABLE from "the app was not running". Readings
     * back-fill (discipline #6), so `forecast_runs` is the only series that
     * remembers whether the app was LOOKING — and a multi-hour physio outage
     * was found precisely because the ledger held `forecast-v15`
     * rows: there was a substitution, so there was a trace. Removing the
     * substitution obliges us to keep the trace.
     *
     * So three things are pinned here, and the third is what keeps the row
     * honest: the refusal is recorded, it does NOT travel through `record()`
     * (which would attach an algo tag and points to a run that never ran), and
     * the ledger for a REAL run is still unreachable without one.
     */
    @Test
    fun `a pass with no run records a named refusal`() {
        val bail = forecaster.indexOf("if (physioShadow == null) {")
        assertTrue("the absence branch is gone — a missing run is silent", bail >= 0)
        val ret = forecaster.indexOf("return null", bail)
        val record = forecaster.indexOf("ForecastLedger.record(")
        assertTrue("the absence branch does not return", ret in bail until record)
        assertTrue(
            "the real-run ledger is reachable without a physio run",
            bail < record,
        )
        val branch = forecaster.substring(bail, ret)
        assertTrue(
            "the refusal is not recorded — an outage would be indistinguishable " +
                "from the app not running: $branch",
            branch.contains("ForecastLedger.recordRefusal("),
        )
        assertFalse(
            "the refusal goes through record(), which would tag it with an algo " +
                "version and attach points to a run that never happened",
            branch.contains("ForecastLedger.record("),
        )
        assertFalse(
            "a constant algo tag is back on the real path",
            forecaster.contains("?: com.diapilot.core.twin.FORECAST_ALGO_VERSION"),
        )
    }

    /**
     * AND THE REFUSAL ROW MUST NOT BE MISTAKEN FOR A MODEL GENERATION.
     *
     * `LedgerRetention` calls a generation DEAD when it stopped being written.
     * If the refusal tag counted as a generation, then during an outage — the
     * one time this data matters — refusals would be the only «live» tag and
     * every real forecast would be swept. The sweep would delete the history
     * exactly when it is most needed.
     */
    @Test
    fun `refusal rows never mark real generations dead`() {
        val retention = source("src/main/java/io/github/obdosok/diapilot/data/LedgerRetention.kt")
        assertTrue(
            "the refusal tag is counted as an active generation — an outage would " +
                "make it the only live one and sweep every real forecast",
            retention.contains("filter { it != ForecastLedger.REFUSAL_ALGO_VERSION }"),
        )
        assertTrue(
            "refusal rows are treated as a dead generation instead of ageing by time",
            retention.contains("val keep = active + ForecastLedger.REFUSAL_ALGO_VERSION"),
        )
    }
}
