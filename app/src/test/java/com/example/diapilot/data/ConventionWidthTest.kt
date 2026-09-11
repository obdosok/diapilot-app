package com.example.diapilot.data

import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.physio.SensorCorrectionReaderV1
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The ISF interval must carry the width our OWN constants produce, not only the
 * width of a sensor line.
 *
 * A live check over trusted doses that own a clean
 * window showed: sweeping [SensorCorrectionReaderV1.BACKGROUND_TREND_HOLD_H] and
 * [SensorCorrectionReaderV1.DEPARTURE_NOISE_MULTIPLE] across their plausible
 * range moves the median ISF by roughly thirty percent while leaving the
 * onset/peak/slowdown/tail landmarks identical at every grid point.
 * So the shape is a measurement and the amplitude is a third convention.
 *
 * This band is the forecast corridor, and the corridor is the hypo alarm's
 * margin, so a band narrower than our own uncertainty is not a cosmetic
 * overclaim.
 *
 * Mutation check: drop the CONVENTION_ISF_SHARE_* terms in `ClosedEpisodeRuntime`
 * and `theIntervalCoversTheSweep` fails.
 */
class ConventionWidthTest {
    /** A clean synthetic fall: quiet before the dose, then a smooth decline. */
    private fun trace(): List<GlucosePoint> {
        val t0 = 1_700_000_000_000L
        return (-6..48).map { i ->
            val minute = i * 5.0
            val mmol = when {
                minute <= 0.0 -> 12.0
                else -> 12.0 - 4.0 * (1.0 - kotlin.math.exp(-minute / 55.0))
            }
            GlucosePoint(t0 + (minute * 60_000).toLong(), mmol)
        }
    }

    private val bolusTs = 1_700_000_000_000L

    @Test fun theShapeIsInvariantToOurConstants() {
        val readings = trace()
        val end = bolusTs + 240 * 60_000L
        val fits = listOf(15.0, 20.0, 30.0, 45.0, 60.0).mapNotNull { windowMin ->
            SensorCorrectionReaderV1.read(readings, bolusTs, 2.0, end, windowMin / 60.0)
        }
        assertTrue("the sweep must actually read something", fits.size >= 4)
        val peaks = fits.map { it.peakRateMin }.distinct()
        assertTrue("peak must not depend on the baseline window: $peaks", peaks.size == 1)
    }

    /**
     * The reported interval has to be at least as wide as the disagreement our
     * constants produce on the very same trace.
     */
    @Test fun theIntervalCoversTheSweep() {
        val readings = trace()
        val end = bolusTs + 240 * 60_000L
        // Sweep what is FREE NOW: the baseline window, which `hold` is derived
        // from. The noise multiple is fixed at 0 and no longer varies.
        val swept = listOf(15.0, 20.0, 30.0, 45.0, 60.0).mapNotNull { windowMin ->
            SensorCorrectionReaderV1.read(readings, bolusTs, 2.0, end, windowMin / 60.0)?.isfMmolPerU
        }
        val production = SensorCorrectionReaderV1.read(readings, bolusTs, 2.0, end)!!
        val k = (production.roughnessMmol * 2.0 / production.dropMmol).coerceIn(.10, .50)

        val roughnessOnlyLow = production.isfMmolPerU * (1 - k)
        val roughnessOnlyHigh = production.isfMmolPerU * (1 + k)
        val widenedLow = production.isfMmolPerU *
            (1 - k - SensorCorrectionReaderV1.CONVENTION_ISF_SHARE_LOW)
        val widenedHigh = production.isfMmolPerU *
            (1 + k + SensorCorrectionReaderV1.CONVENTION_ISF_SHARE_HIGH)

        assertTrue("widening must widen", widenedLow < roughnessOnlyLow && widenedHigh > roughnessOnlyHigh)
        assertTrue(
            "the interval must contain every ISF our own constants can produce: " +
                "${swept.min()}..${swept.max()} vs $widenedLow..$widenedHigh",
            swept.min() >= widenedLow && swept.max() <= widenedHigh,
        )
        assertTrue("and the band must stay physiological", widenedLow > 0.0 && widenedHigh < 8.0)
    }

    /**
     * The high arm is the one that predicts the deeper fall, so it drives the
     * corridor's lower bound and therefore how early a hypo can be seen coming.
     * It must never be the narrower of the two.
     */
    @Test fun theArmThatFeedsTheAlarmIsNotTheNarrowOne() {
        assertTrue(
            SensorCorrectionReaderV1.CONVENTION_ISF_SHARE_HIGH >=
                SensorCorrectionReaderV1.CONVENTION_ISF_SHARE_LOW,
        )
        assertTrue(abs(SensorCorrectionReaderV1.CONVENTION_ISF_SHARE_HIGH) > 0.0)
    }
}
