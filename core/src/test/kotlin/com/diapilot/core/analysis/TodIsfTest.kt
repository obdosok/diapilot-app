package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class TodIsfTest {

    private fun agg(median: Double?, n: Int) = IsfAggregate(
        nValid = n, nAnomalies = 0,
        confidence = Confidence.of(n), median = median,
        q1 = median?.times(0.9), q3 = median?.times(1.1),
    )

    @Test
    fun eveningWeakerMorningStronger() {
        val byTod = mapOf(
            TodBucket.NIGHT to agg(1.2, 8),
            TodBucket.MORNING to agg(1.2, 8),
            TodBucket.DAY to agg(1.0, 8),
            TodBucket.EVENING to agg(0.75, 8),
        )
        // Weighted overall = (1.2+1.2+1.0+0.75)/4 = 1.0375
        assertEquals(0.75 / 1.0375, todIsfFactor(byTod, 20), 1e-6)   // evening < 1
        assertEquals(1.2 / 1.0375, todIsfFactor(byTod, 8), 1e-6)     // morning > 1
    }

    @Test
    fun thinBucketsAndUnknownsAreNeutral() {
        val byTod = mapOf(
            TodBucket.MORNING to agg(1.2, 8),
            TodBucket.EVENING to agg(0.5, 2),   // n=2 → INSUFFICIENT
            TodBucket.NIGHT to agg(null, 0),
        )
        assertEquals(1.0, todIsfFactor(byTod, 20), 1e-9)   // insufficient
        assertEquals(1.0, todIsfFactor(byTod, 2), 1e-9)    // unknown median
        assertEquals(1.0, todIsfFactor(emptyMap(), 12), 1e-9)
    }

    @Test
    fun clampAndKernelScaling() {
        val byTod = mapOf(
            TodBucket.DAY to agg(10.0, 8),     // absurd ratio → clamped
            TodBucket.NIGHT to agg(1.0, 8),
        )
        assertEquals(1.4, todIsfFactor(byTod, 12), 1e-9)
        val kernel = listOf(KernelPoint(0.0, -1.0, -1.2, -0.8, 5))
        val scaled = scaleKernel(kernel, 1.4)
        assertEquals(-1.4, scaled[0].median, 1e-9)
    }
}
