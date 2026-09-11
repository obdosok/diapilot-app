package com.example.diapilot.data

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SENSOR IS TRUSTED BY SIGNAL, NOT BY CALENDAR.
 *
 * Trust used to cut off exactly at `start + 14 days` in three places, and
 * that killed everything at once: the line, the widget, the watch — they all
 * go through the same `forecastAnchor` / `displayHistory` / `trustedHistory`.
 * The rule this replaces: a sensor rated for 14 days can keep working past
 * that mark, and the app should keep going while the sensor keeps sending a
 * signal.
 *
 * The cutoff was NOT pointless: Libre keeps sending plausible packets past
 * its rated life — on real hardware the main stream once read far below the
 * minute stream. But that is about STREAM DISAGREEMENT, not age, and the
 * threshold was measured on the same kind of sensor: thousands of paired
 * readings over many days of normal operation, after calibration a median
 * disagreement around 0.2 mmol, p95 under 1 mmol, p99 under 1.5 mmol.
 *
 * The one real disagreement that triggered this design was in the tail of
 * that distribution: a single point cannot be a threshold. A run can: the
 * longest run of consecutive readings above 2.0 mmol apart over that whole
 * stretch was TWO; a run of three never happened.
 */
class SensorLifeBySignalTest {

    private val rated = 1_787_000_000_000L
    private val until = rated + 12L * 3_600_000L

    /** The minute stream on its own scale; calibration maps it back to the main one. */
    private fun minute(i: Int, mmol: Double) =
        GlucosePoint(rated + i * 60_000L, (mmol - 0.5) / 0.75)

    private val calibrate: (Double) -> Double = { 0.75 * it + 0.5 }

    private fun main(vararg v: Pair<Int, Double>) = v.map { (i, m) -> GlucosePoint(rated + i * 60_000L, m) }

    @Test
    fun `agreeing streams keep the sensor alive past the rated life`() {
        val end = sensorTrustFromStreams(
            rated, until,
            main = main(0 to 7.0, 5 to 7.2, 10 to 7.1, 15 to 6.9),
            minutes = (0..20).map { minute(it, 7.0) },
            calibrate = calibrate,
        )
        assertEquals("streams agree — trust must survive to the end of the window", until, end)
    }

    @Test
    fun `three consecutive divergent readings end the trust at the first of them`() {
        // Two consecutive disagreements are normal (this happened during
        // normal operation), the third makes a run, and then the first one
        // was already lying.
        val end = sensorTrustFromStreams(
            rated, until,
            main = main(0 to 7.0, 5 to 7.1, 10 to 2.7, 15 to 2.6, 20 to 2.8, 25 to 2.7),
            minutes = (0..30).map { minute(it, 7.0) },
            calibrate = calibrate,
        )
        assertEquals(
            "trust must end at the FIRST reading of the run, not the third",
            rated + 10 * 60_000L - 1, end,
        )
    }

    @Test
    fun `two divergent readings are noise and do not end anything`() {
        val end = sensorTrustFromStreams(
            rated, until,
            main = main(0 to 7.0, 5 to 2.7, 10 to 2.6, 15 to 7.1, 20 to 7.0),
            minutes = (0..25).map { minute(it, 7.0) },
            calibrate = calibrate,
        )
        assertEquals(
            "two in a row is noise: this happened during normal operation, " +
                "while a run of three never happened",
            until, end,
        )
    }

    @Test
    fun `with no minute stream the refusal looks like a refusal`() {
        val end = sensorTrustFromStreams(
            rated, until,
            main = main(0 to 7.0, 5 to 7.1),
            minutes = emptyList(),
            calibrate = calibrate,
        )
        assertEquals(
            "nothing to confirm with — falls back to the calendar cutoff; \"not checked\" " +
                "is not the same as \"checked and fine\"",
            rated, end,
        )
    }

    @Test
    fun `a gap in the minute stream breaks the run rather than counting as divergence`() {
        // The minute stream ended at minute 6: nothing to confirm the rest
        // with. This is not evidence either way — a run must break, not
        // accumulate.
        val end = sensorTrustFromStreams(
            rated, until,
            main = main(0 to 7.0, 10 to 2.7, 20 to 2.6, 30 to 2.8),
            minutes = (0..6).map { minute(it, 7.0) },
            calibrate = calibrate,
        )
        assertTrue("without confirmation the run does not accumulate", end == until)
    }
}
