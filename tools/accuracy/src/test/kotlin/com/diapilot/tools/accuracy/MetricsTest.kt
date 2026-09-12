package com.diapilot.tools.accuracy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class MetricsTest {

    // ---- errorStats --------------------------------------------------

    @Test
    fun `errorStats is null on an empty list`() {
        assertNull(errorStats(emptyList()))
    }

    @Test
    fun `errorStats bias sign matches actual minus predicted`() {
        // Model reads low by exactly 1.0 every time: actual - predicted = +1.
        val samples = listOf(
            ErrorSample(predictedMmol = 5.0, actualMmol = 6.0),
            ErrorSample(predictedMmol = 7.0, actualMmol = 8.0),
        )
        val stats = errorStats(samples)!!
        assertEquals(2, stats.n)
        assertEquals(1.0, stats.biasMmol, 1e-9)
        assertEquals(1.0, stats.maeMmol, 1e-9)
        assertEquals(1.0, stats.rmseMmol, 1e-9)
    }

    @Test
    fun `errorStats MAE and RMSE on a hand-computed vector`() {
        // diffs (actual - predicted): +1, -3, +2 -> |.|: 1,3,2 -> MAE = 2
        // squares: 1, 9, 4 -> mean = 14/3 -> RMSE = sqrt(14/3)
        val samples = listOf(
            ErrorSample(predictedMmol = 5.0, actualMmol = 6.0),
            ErrorSample(predictedMmol = 8.0, actualMmol = 5.0),
            ErrorSample(predictedMmol = 4.0, actualMmol = 6.0),
        )
        val stats = errorStats(samples)!!
        assertEquals(3, stats.n)
        assertEquals(0.0, stats.biasMmol, 1e-9)   // (1 - 3 + 2) / 3 = 0
        assertEquals(2.0, stats.maeMmol, 1e-9)
        assertEquals(sqrt(14.0 / 3.0), stats.rmseMmol, 1e-9)
    }

    // ---- nearestReading / matchHorizon --------------------------------

    private fun reading(tsMs: Long, mmol: Double) = GlucoseReadingRow(tsMs, mmol)

    @Test
    fun `nearestReading picks the closer of two candidates within tolerance`() {
        val readings = listOf(reading(0, 5.0), reading(300_000, 6.0), reading(600_000, 7.0))
        // target at 250_000ms: 300_000 (50s away) beats 0 (250s away).
        val hit = nearestReading(readings, targetTsMs = 250_000, toleranceMs = 360_000)
        assertEquals(6.0, hit!!.mmol, 1e-9)
    }

    @Test
    fun `nearestReading returns null outside tolerance`() {
        val readings = listOf(reading(0, 5.0))
        assertNull(nearestReading(readings, targetTsMs = 10_000_000, toleranceMs = 360_000))
    }

    @Test
    fun `matchHorizon only matches points at the requested horizon`() {
        val points = listOf(
            ForecastPointRow(runId = 1, horizonMin = 30, targetTsMs = 1_800_000, mmol = 6.0),
            ForecastPointRow(runId = 1, horizonMin = 60, targetTsMs = 3_600_000, mmol = 5.0),
        )
        val readings = listOf(reading(1_800_000, 6.5), reading(3_600_000, 5.2))
        val matched = matchHorizon(points, readings, horizonMin = 30)
        assertEquals(1, matched.size)
        assertEquals(6.0, matched[0].predictedMmol, 1e-9)
        assertEquals(6.5, matched[0].actualMmol, 1e-9)
    }

    // ---- hypoConfusion --------------------------------------------------

    @Test
    fun `hypoConfusion counts all four buckets`() {
        val outcomes = listOf(
            HypoOutcome(predictedHypo = true, actualHypo = true),   // TP
            HypoOutcome(predictedHypo = true, actualHypo = true),   // TP
            HypoOutcome(predictedHypo = true, actualHypo = false),  // FP
            HypoOutcome(predictedHypo = false, actualHypo = true),  // FN
            HypoOutcome(predictedHypo = false, actualHypo = false), // TN
        )
        val c = hypoConfusion(outcomes)
        assertEquals(2, c.truePos)
        assertEquals(1, c.falsePos)
        assertEquals(1, c.falseNeg)
        assertEquals(1, c.trueNeg)
        assertEquals(2.0 / 3.0, c.precision!!, 1e-9)
        assertEquals(2.0 / 3.0, c.recall!!, 1e-9)
    }

    @Test
    fun `hypoConfusion precision and recall are null, not zero, when undefined`() {
        val noPredictions = listOf(HypoOutcome(predictedHypo = false, actualHypo = true))
        assertNull(hypoConfusion(noPredictions).precision)

        val noRealHypos = listOf(HypoOutcome(predictedHypo = true, actualHypo = false))
        assertNull(hypoConfusion(noRealHypos).recall)
    }

    // ---- evaluateHypoAlert --------------------------------------------------

    @Test
    fun `evaluateHypoAlert - predicted and confirmed is a true positive`() {
        val run = RunRow(id = 1, anchorTsMs = 0, consumer = "hypo_alert", algoVersion = "v1")
        val points = mapOf(
            1L to listOf(ForecastPointRow(runId = 1, horizonMin = 30, targetTsMs = 1_800_000, mmol = 3.5)),
        )
        val readings = listOf(reading(1_800_000, 3.6))  // really did go low, near the target
        val outcomes = evaluateHypoAlert(
            listOf(run), points, readings, thresholdMmol = 3.9, leadMaxMin = 45.0,
        )
        assertEquals(listOf(HypoOutcome(predictedHypo = true, actualHypo = true)), outcomes)
    }

    @Test
    fun `evaluateHypoAlert - predicted but nothing happened is a false positive`() {
        val run = RunRow(id = 1, anchorTsMs = 0, consumer = "hypo_alert", algoVersion = "v1")
        val points = mapOf(
            1L to listOf(ForecastPointRow(runId = 1, horizonMin = 30, targetTsMs = 1_800_000, mmol = 3.5)),
        )
        val readings = listOf(reading(1_800_000, 6.0))  // stayed comfortably in range
        val outcomes = evaluateHypoAlert(listOf(run), points, readings)
        assertEquals(listOf(HypoOutcome(predictedHypo = true, actualHypo = false)), outcomes)
    }

    @Test
    fun `evaluateHypoAlert - missed low is a false negative`() {
        val run = RunRow(id = 1, anchorTsMs = 0, consumer = "hypo_alert", algoVersion = "v1")
        val points = mapOf(
            1L to listOf(ForecastPointRow(runId = 1, horizonMin = 30, targetTsMs = 1_800_000, mmol = 5.5)),
        )
        val readings = listOf(reading(1_800_000, 3.2))  // went low, model never saw it coming
        val outcomes = evaluateHypoAlert(listOf(run), points, readings)
        assertEquals(listOf(HypoOutcome(predictedHypo = false, actualHypo = true)), outcomes)
    }

    @Test
    fun `evaluateHypoAlert - a crossing outside the lead window does not count`() {
        val run = RunRow(id = 1, anchorTsMs = 0, consumer = "hypo_alert", algoVersion = "v1")
        // Predicted low sits at horizon 60min, outside a 45-minute lead window.
        val points = mapOf(
            1L to listOf(ForecastPointRow(runId = 1, horizonMin = 60, targetTsMs = 3_600_000, mmol = 3.0)),
        )
        val readings = listOf(reading(3_600_000, 3.0))
        val outcomes = evaluateHypoAlert(listOf(run), points, readings, leadMaxMin = 45.0)
        assertEquals(listOf(HypoOutcome(predictedHypo = false, actualHypo = false)), outcomes)
    }
}
