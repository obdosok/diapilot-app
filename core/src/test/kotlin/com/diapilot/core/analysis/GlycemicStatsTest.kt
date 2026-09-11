package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.LabeledMeal
import com.diapilot.core.collector.MealEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val BASE = 1_748_768_400_000L

class GlycemicStatsTest {

    private fun pts(vararg bg: Double): List<GlucosePoint> =
        bg.mapIndexed { i, b -> GlucosePoint(BASE + i * 5 * 60_000L, b) }

    @Test
    fun `empty readings yield null`() {
        assertNull(glycemicStats(emptyList()))
    }

    @Test
    fun `tir percentages sum to 100`() {
        // 2 below, 6 in range, 2 above
        val s = glycemicStats(pts(3.0, 3.5, 5.0, 6.0, 7.0, 8.0, 9.0, 9.9, 11.0, 12.0))!!
        assertEquals(10, s.n)
        assertEquals(20.0, s.belowPct, 1e-9)
        assertEquals(60.0, s.inRangePct, 1e-9)
        assertEquals(20.0, s.abovePct, 1e-9)
        assertEquals(100.0, s.belowPct + s.inRangePct + s.abovePct, 1e-9)
    }

    @Test
    fun `consecutive lows are one hypo episode`() {
        val s = glycemicStats(pts(5.0, 3.5, 3.2, 3.6, 5.0, 6.0))!!
        assertEquals(1, s.hypoEpisodes)
    }

    @Test
    fun `separated lows are distinct episodes`() {
        // Low, then 30+ min in range, then low again.
        val s = glycemicStats(pts(3.5, 5.0, 6.0, 6.0, 6.0, 6.0, 6.0, 3.4))!!
        assertEquals(2, s.hypoEpisodes)
    }
}

class LabelStatsTest {

    private fun meal(
        onset: Long,
        rise: Double,
        ttp: Double = 60.0,
        bolus: Double? = null,
    ) = MealEvent(
        onsetMs = onset, peakMs = onset + (ttp * 60_000).toLong(),
        preBg = 6.0, peakBg = 6.0 + rise, rise = rise, timeToPeakMin = ttp,
        bolusUnits = bolus,
        kind = if (bolus != null) MealEvent.Kind.ANNOUNCED else MealEvent.Kind.UNANNOUNCED,
    )

    @Test
    fun `label stats aggregate per label sorted by count`() {
        val labeled = listOf(
            LabeledMeal(meal(BASE, 3.0, 60.0, 4.0), 1, "пиво"),
            LabeledMeal(meal(BASE + 1, 5.0, 90.0, 6.0), 1, "пиво"),
            LabeledMeal(meal(BASE + 2, 4.0, 75.0, null), 1, "пиво"),
            LabeledMeal(meal(BASE + 3, 2.0, 45.0, 2.0), 2, "смузи"),
        )
        val stats = labelStats(labeled)
        assertEquals(2, stats.size)
        val beer = stats[0]
        assertEquals("пиво", beer.name)
        assertEquals(3, beer.count)
        assertEquals(4.0, beer.avgRise, 1e-9)
        assertEquals(75.0, beer.avgTimeToPeakMin, 1e-9)
        assertEquals(5.0, beer.avgBolus!!, 1e-9)   // (4+6)/2, unannounced excluded
        assertEquals(1, beer.unannounced)
    }

    @Test
    fun `label with only unannounced meals has null avg bolus`() {
        val stats = labelStats(listOf(LabeledMeal(meal(BASE, 3.0), 1, "яблоко")))
        assertNull(stats[0].avgBolus)
        assertEquals(1, stats[0].unannounced)
    }

    @Test
    fun `insulin adjusted rise credits the dish for what IOB was pulling`() {
        // Flat -1.0 mmol/u kernel plateau reached instantly: a 2u bolus 30 min
        // BEFORE the meal is fully realized by onset → contributes nothing to
        // [onset, peak]; a 2u bolus right AT onset pulls the full -2.0 during
        // the rise, so the dish's own effect is rise + 2.0.
        val kernel = listOf(
            KernelPoint(0.0, 0.0, 0.0, 0.0, 1),
            KernelPoint(1.0, -1.0, -1.0, -1.0, 1),
            KernelPoint(600.0, -1.0, -1.0, -1.0, 1),
        )
        val m = meal(BASE, rise = 3.0, ttp = 60.0)
        val bolusAtOnset = com.diapilot.core.collector.BolusPoint(BASE, 2.0)
        assertEquals(
            5.0,
            insulinAdjustedRise(m, listOf(bolusAtOnset), kernel),
            1e-6,
        )
        // No kernel → legacy raw rise.
        assertEquals(3.0, insulinAdjustedRise(m, listOf(bolusAtOnset), emptyList()), 1e-9)
        // Never negative.
        val tiny = meal(BASE, rise = 0.5)
        assertEquals(
            2.5,
            insulinAdjustedRise(tiny, listOf(bolusAtOnset), kernel),
            1e-6,
        )
        // And labelStats picks it up.
        val stats = labelStats(
            listOf(LabeledMeal(m, 1, "паста")),
            boluses = listOf(bolusAtOnset),
            kernel = kernel,
        )
        assertEquals(5.0, stats[0].avgRise, 1e-6)
    }

    @Test
    fun `rise scale applies BEFORE the insulin adjustment`() {
        // scale×rise − dIns, NOT scale×(rise − dIns): the kernel term is
        // already on the meter scale (amplitude pinned to the user's ISF).
        // rise=3 raw, slope=0.8, insulin pulled 2.0 during the rise:
        // correct = 0.8*3 + 2.0 = 4.4; the wrong order gives 0.8*5 = 4.0.
        val kernel = listOf(
            KernelPoint(0.0, 0.0, 0.0, 0.0, 1),
            KernelPoint(1.0, -1.0, -1.0, -1.0, 1),
            KernelPoint(600.0, -1.0, -1.0, -1.0, 1),
        )
        val m = meal(BASE, rise = 3.0, ttp = 60.0)
        val bolusAtOnset = com.diapilot.core.collector.BolusPoint(BASE, 2.0)
        assertEquals(
            4.4,
            insulinAdjustedRise(m, listOf(bolusAtOnset), kernel, riseScale = 0.8),
            1e-6,
        )
        val stats = labelStats(
            listOf(LabeledMeal(m, 1, "паста")),
            boluses = listOf(bolusAtOnset),
            kernel = kernel,
            riseScale = 0.8,
        )
        assertEquals(4.4, stats[0].avgRise, 1e-6)
    }
}
