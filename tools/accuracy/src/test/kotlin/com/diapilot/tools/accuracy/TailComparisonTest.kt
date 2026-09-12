package com.diapilot.tools.accuracy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE END-OF-ACTION COMPARISON, ON HAND-WRITTEN ROWS.
 *
 * The question it exists to settle: is the app's own owner right that their
 * insulin is done at about 130 minutes, or is the 240-minute domain floor
 * right? Argument cannot settle it. The ledger can, because
 * `forecast_runs.applied` records the insulin block the phone was RUNNING when
 * it drew each forecast — so a database that carried one tail for a stretch and
 * another afterwards already holds both arms, measured by the shipped code on
 * the real person.
 *
 * These tests pin the two parts that can silently be wrong: reading the tail
 * out of that column, and assigning a run to an arm. The metrics themselves are
 * the ones MetricsTest already covers — the comparison reuses them rather than
 * growing a second implementation, which is the whole reason it can be trusted
 * at all.
 *
 * No database here. Nothing in this file opens a connection.
 */
class TailComparisonTest {

    private fun run(id: Long, applied: String?) = RunRow(
        id = id, anchorTsMs = id * 1_000L, consumer = "main",
        algoVersion = "v1", applied = applied,
    )

    // ---- parsing the applied column --------------------------------------

    @Test
    fun `the applied tail is the third field of the head`() {
        // Exactly what ForecastLedger.appliedSummary writes.
        assertEquals(
            300.0,
            parseAppliedTailMin("20/75/300 isf=1.850 ramp=1.00/0.50/0.75 kcal=180 sieve=0.65"),
        )
        assertEquals(130.0, parseAppliedTailMin("27/55/130 isf=2.500 ramp=0.00/0.00/0.00 kcal=180 sieve=0.65"))
    }

    /** Rows written before `ramp=`/`kcal=`/`sieve=` were appended must keep
     *  parsing — the column is older than those terms. */
    @Test
    fun `an old row with only the head and the ISF still parses`() {
        assertEquals(175.0, parseAppliedTailMin("20/75/175 isf=1.850"))
    }

    /**
     * Null, never a guess. A run whose applied model is unknown belongs to no
     * arm; folding it into the likelier one would put the answer into the
     * question.
     */
    @Test
    fun `an unusable applied column reads as no tail at all`() {
        assertNull(parseAppliedTailMin(null))
        assertNull(parseAppliedTailMin(""))
        assertNull(parseAppliedTailMin("   "))
        assertNull(parseAppliedTailMin("20/75 isf=1.850"))
        assertNull(parseAppliedTailMin("20/75/unknown isf=1.850"))
        assertNull(parseAppliedTailMin("20/75/0 isf=1.850"))
        assertNull(parseAppliedTailMin("refused"))
    }

    // ---- splitting the ledger into arms ----------------------------------

    @Test
    fun `runs are assigned to the arm whose tail they actually ran`() {
        val runs = listOf(
            run(1, "27/55/130 isf=2.500"),
            run(2, "27/55/130 isf=2.500"),
            run(3, "20/75/300 isf=1.850"),
            run(4, null),
        )
        assertEquals(listOf(1L, 2L), runsWithAppliedTail(runs, 130.0).map { it.id })
        assertEquals(listOf(3L), runsWithAppliedTail(runs, 300.0).map { it.id })
        assertTrue(runsWithAppliedTail(runs, 240.0).isEmpty())
    }

    /**
     * The column stores whole minutes and a curve can be re-derived a minute
     * off, so the match is a tolerance — but a tolerance wide enough to merge
     * two arms would be worse than none.
     */
    @Test
    fun `the tolerance absorbs rounding without merging two arms`() {
        val runs = listOf(run(1, "27/55/129 isf=2.500"), run(2, "20/75/300 isf=1.850"))
        assertEquals(listOf(1L), runsWithAppliedTail(runs, 130.0, toleranceMin = 1.0).map { it.id })
        assertTrue(runsWithAppliedTail(runs, 300.0, toleranceMin = 1.0).none { it.id == 1L })
        // And an explicit wide tolerance does merge them, which is the caller's
        // choice to make and the reason the default is tight.
        assertEquals(2, runsWithAppliedTail(runs, 215.0, toleranceMin = 90.0).size)
    }

    /**
     * What the database actually holds, printed before the comparison so an
     * empty arm can be told from a mistyped one.
     */
    @Test
    fun `the arms present in a ledger are listed longest tail first`() {
        val runs = listOf(
            run(1, "27/55/130 isf=2.500"),
            run(2, "20/75/300 isf=1.850"),
            run(3, "20/75/300 isf=1.850"),
            run(4, "20/75/300 isf=1.850"),
            run(5, null),
        )
        assertEquals(listOf(300.0 to 3, 130.0 to 1), appliedTailsPresent(runs))
    }

    @Test
    fun `a ledger with no applied column at all lists no arms`() {
        assertEquals(emptyList<Pair<Double, Int>>(), appliedTailsPresent(listOf(run(1, null))))
    }

    // ---- the printed shape -----------------------------------------------

    /**
     * An arm with nothing in it says so. The earlier single-arm report made the
     * same promise for a horizon with no matched readings, and for the same
     * reason: a silent zero reads as a perfect score.
     */
    @Test
    fun `an empty arm is reported as empty rather than as a zero`() {
        val text = formatTailComparison(
            TailComparison(
                arms = listOf(
                    TailArmReport(
                        tailMin = 130.0,
                        mainRuns = 0,
                        errorByHorizon = REPORT_HORIZONS_MIN.map { it to null },
                        hypo = HypoConfusion(0, 0, 0, 0),
                        hypoRuns = 0,
                    ),
                ),
                tailsPresent = emptyList(),
                unattributedMainRuns = 4,
                toleranceMin = 1.0,
            ),
        )
        assertTrue(text, text.contains("no run in this database ran this end of action"))
        assertTrue(text, text.contains("no parsable 'applied' column"))
        // And it must never read as a replay, because it is not one.
        assertTrue(text, text.contains("not a replay"))
    }

    /**
     * The difference row is the number the disagreement turns on: a too-short
     * tail stops subtracting insulin action and so predicts glucose too HIGH at
     * 120-180 min after a bolus, which shows up as the bias moving negative
     * (bias = actual − predicted). The sign is stated in the output itself so
     * nobody has to re-derive it from the metric's definition.
     */
    @Test
    fun `two arms print a difference row with the sign explained`() {
        fun arm(tail: Double, bias: Double) = TailArmReport(
            tailMin = tail,
            mainRuns = 10,
            errorByHorizon = REPORT_HORIZONS_MIN.map { h ->
                h to ErrorStats(n = 10, biasMmol = bias, maeMmol = 1.0, rmseMmol = 1.2)
            },
            hypo = HypoConfusion(truePos = 2, falsePos = 1, falseNeg = 1, trueNeg = 6),
            hypoRuns = 10,
        )
        val text = formatTailComparison(
            TailComparison(
                arms = listOf(arm(130.0, -1.0), arm(300.0, 0.5)),
                tailsPresent = listOf(300.0 to 10, 130.0 to 10),
                unattributedMainRuns = 0,
                toleranceMin = 1.0,
            ),
        )
        assertTrue(text, text.contains("Difference, tail 300 minus tail 130"))
        assertTrue(text, text.contains("+1.500"))
        assertTrue(text, text.contains("precision = 0.667"))
        assertTrue(text, text.contains("recall = 0.667"))
        // The honesty clause about pairing is part of the output, not just the
        // documentation: the table is read far more often than the doc.
        assertTrue(text, text.contains("not paired runs"))
    }

    /** A single arm prints no difference row — there is nothing to subtract. */
    @Test
    fun `one arm prints no difference row`() {
        val text = formatTailComparison(
            TailComparison(
                arms = listOf(
                    TailArmReport(
                        tailMin = 300.0,
                        mainRuns = 5,
                        errorByHorizon = REPORT_HORIZONS_MIN.map { h ->
                            h to ErrorStats(5, 0.2, 0.4, 0.5)
                        },
                        hypo = HypoConfusion(1, 0, 0, 4),
                        hypoRuns = 5,
                    ),
                ),
                tailsPresent = listOf(300.0 to 5),
                unattributedMainRuns = 0,
                toleranceMin = 1.0,
            ),
        )
        assertTrue(text, !text.contains("Difference, tail"))
    }

    // ---- the CLI flag ----------------------------------------------------

    @Test
    fun `the compare flag parses two tails and the default tolerance`() {
        val o = parseArgs(arrayOf("/tmp/copy.db", "--compare-tail=130,300"))
        assertEquals("/tmp/copy.db", o.dbPath)
        assertEquals(listOf(130.0, 300.0), o.compareTailsMin)
        assertEquals(1.0, o.toleranceMin, 0.0)
    }

    @Test
    fun `without the flag the report is the single-arm one`() {
        assertEquals(emptyList<Double>(), parseArgs(arrayOf("/tmp/copy.db")).compareTailsMin)
    }

    @Test
    fun `a comparison of one value is refused rather than printed as a comparison`() {
        val e = runCatching { parseArgs(arrayOf("/tmp/copy.db", "--compare-tail=130")) }
            .exceptionOrNull()
        assertTrue("$e", e is IllegalArgumentException)
        assertTrue("$e", e!!.message!!.contains("at least two"))
    }

    @Test
    fun `a non-numeric tail and an unknown flag are both refused by name`() {
        assertTrue(
            runCatching { parseArgs(arrayOf("/tmp/copy.db", "--compare-tail=130,soon")) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { parseArgs(arrayOf("/tmp/copy.db", "--whatever")) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { parseArgs(arrayOf("--compare-tail=130,300")) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `the tolerance can be widened explicitly`() {
        assertEquals(
            15.0,
            parseArgs(arrayOf("/tmp/copy.db", "--compare-tail=130,300", "--tail-tolerance=15"))
                .toleranceMin,
            0.0,
        )
        assertTrue(
            runCatching { parseArgs(arrayOf("/tmp/copy.db", "--tail-tolerance=-1")) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }
}
