package com.diapilot.core.twin

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentumTest {
    private val min = 60_000L

    // THE KINEMATIC BRANCH WAS REMOVED ALONG WITH THE LEGACY ENGINE, and with
    // it four tests: recoversVelocityAndAcceleration, refusesThinDataAndClamps-
    // Insanity, blendOwnsTheNearHorizonAndFadesOut and momentumVelocityReconciles-
    // ToGridSlope. The only consumer of `Momentum` was `fun forecast`.
    // The trend arrow remains — it reads readings, not the model.

    @Test
    fun trendReadoutIsTheFactualSmoothedChange() {
        val now = 10_000_000L
        fun stream(build: (backMin: Int) -> Double): List<GlucosePoint> =
            (0..15).map { back -> GlucosePoint(now - back * min, build(back)) }

        // Steady fall −0.1/min: delta = −0.5, no nuance (windows equal).
        val steady = trendReadout(stream { back -> 6.0 + 0.1 * back }, now)!!
        assertEquals(-0.5, steady.delta5Mmol, 0.05)
        assertNull(steady.nuance)

        // Accelerating fall: last 5 min −0.9, previous −0.4.
        val accel = trendReadout(
            stream { back ->
                when {
                    back <= 5 -> 6.0 + 0.18 * back
                    back <= 10 -> 6.9 + 0.08 * (back - 5)
                    else -> 7.3
                }
            },
            now,
        )!!
        assertEquals("ускоряется", accel.nuance)

        // Decelerating fall: last −0.3 after −0.9.
        val decel = trendReadout(
            stream { back ->
                when {
                    back <= 5 -> 6.0 + 0.06 * back
                    back <= 10 -> 6.3 + 0.18 * (back - 5)
                    else -> 7.2
                }
            },
            now,
        )!!
        assertEquals("замедляется", decel.nuance)

        // NOISE (±1 mg/dL teeth): delta ~0, no nuance, no phantom arrows —
        // the exact field failure (132→129 over 4 min displayed as −17).
        val noisy = trendReadout(
            stream { back -> 8.0 + if (back % 2 == 0) 0.05 else -0.05 },
            now,
        )!!
        assertTrue(kotlin.math.abs(noisy.delta5Mmol) < 0.15)
        assertNull(noisy.nuance)

        // A real turn: fell before, rising now.
        val turn = trendReadout(
            stream { back ->
                when {
                    back <= 5 -> 6.5 - 0.08 * back   // rising into now
                    else -> 6.1 + 0.09 * (back - 5)  // was falling
                }
            },
            now,
        )!!
        assertEquals("развернулся", turn.nuance)

        // Thin/stale data refuses.
        assertNull(trendReadout(emptyList(), now))
        assertNull(
            trendReadout(
                (10..15).map { back -> GlucosePoint(now - back * min, 6.0) },
                now,
            ),
        )
    }


    @Test
    fun gridTrendReadoutUsesTheFrozenFiveMinGrid() {
        // A real field incident, real numbers. Frozen 5-min grid (mmol):
        // 01:34=10.39, 01:41=9.71, 01:46=9.48; now ≈ 01:48. The 5-min change
        // is a gentle −0.23 (≈−4 mg/dL, flat arrow), decelerating — NOT the
        // phantom −20 the retroactively-revised OOP2 minute stream produced.
        val now = 10_000_000L
        val grid = listOf(
            GlucosePoint(now - 14 * min, 10.39),
            GlucosePoint(now - 7 * min, 9.71),
            GlucosePoint(now - 2 * min, 9.48),
        )
        val r = gridTrendReadout(grid, now)!!
        assertEquals(-0.23, r.delta5Mmol, 0.03)
        assertEquals("замедляется", r.nuance)

        // Stale grid (newest 8 min old) refuses → caller falls back.
        assertNull(
            gridTrendReadout(
                listOf(
                    GlucosePoint(now - 13 * min, 9.7),
                    GlucosePoint(now - 8 * min, 9.5),
                ),
                now,
            ),
        )
    }
}
