package com.diapilot.core.physio

import com.diapilot.core.collector.GlucosePoint
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sensor step is an artefact, and an artefact must not drive the reading.
 *
 * A real trace stepped +1.7 then -2.3 within twelve minutes, right where the
 * action begins. Measured with a mean-only smoother: that step
 * survived and the whole episode scored a roughness of 0.11 — the worst of the
 * eight — while the fall around it is textbook. The design framing: noise and steps
 * are not something this app forecasts or needs to, so smooth them out and
 * still answer with a few robust numbers.
 */
class SensorStepFilterTest {
    private val bolus = 1_800_000_000_000L
    private fun at(m: Double) = bolus + (m * 60_000).toLong()

    private fun action(m: Double) = ((m - 30.0) / 120.0).coerceIn(0.0, 1.0)
        .let { it * it * (3.0 - 2.0 * it) }

    private fun clean() = (-30..200 step 5).map {
        GlucosePoint(at(it.toDouble()), 17.2 - 14.2 * action(it.toDouble()))
    }

    /** A real sawtooth pattern: up 1.7, down 2.3, twelve minutes wide. */
    private fun stepped() = clean().map {
        val t = (it.tsMs - bolus) / 60_000.0
        when (t) {
            15.0 -> GlucosePoint(it.tsMs, it.mmol + 1.7)
            20.0 -> GlucosePoint(it.tsMs, it.mmol - 0.6)
            else -> it
        }
    }

    @Test fun aStepDoesNotSurviveIntoTheReading() {
        val ok = checkNotNull(SensorCorrectionReaderV1.read(clean(), bolus, 3.5, at(200.0)))
        val stepped = checkNotNull(SensorCorrectionReaderV1.read(stepped(), bolus, 3.5, at(200.0)))
        assertEquals(
            "the amplitude must not move because of an artefact",
            ok.isfMmolPerU, stepped.isfMmolPerU, .4,
        )
        assertTrue(
            "nor the break: ${ok.onsetMin} vs ${stepped.onsetMin}",
            abs(ok.onsetMin - stepped.onsetMin) <= 15.0,
        )
    }

    @Test fun aSteppedLineStillReturnsAProfile() {
        val f = checkNotNull(SensorCorrectionReaderV1.read(stepped(), bolus, 3.5, at(200.0)))
        assertTrue("landmarks must still be ordered: $f", f.ordered)
        assertTrue(f.cdf.size >= 4)
        assertTrue("and the amplitude must be usable: ${f.isfMmolPerU}", f.isfMmolPerU > 1.0)
    }
}
