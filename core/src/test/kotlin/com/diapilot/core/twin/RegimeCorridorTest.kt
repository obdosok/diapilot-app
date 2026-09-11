package com.diapilot.core.twin

import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegimeCorridorTest {

    private val kernel = listOf(KernelPoint(0.0, 0.0, 0.0, 0.0, 10))

    @Test
    fun classifierIsBackwardLookingWithPriority() {
        val meal = 100L * 60_000
        val bolus = com.diapilot.core.collector.BolusPoint(90L * 60_000, 2.0)
        // Night wins over everything.
        assertEquals(
            Regime.NIGHT,
            classifyRegime(meal + 60_000, listOf(meal), listOf(bolus), emptyList(), hour = 3),
        )
        // Meal within 150 min back → POST_MEAL even with a bolus around.
        assertEquals(
            Regime.POST_MEAL,
            classifyRegime(meal + 60_000, listOf(meal), listOf(bolus), emptyList(), hour = 12),
        )
        // FUTURE meal must be invisible (backward-looking only).
        assertEquals(
            Regime.QUIET,
            classifyRegime(meal - 60_000, listOf(meal), emptyList(), emptyList(), hour = 12),
        )
        // Bolus 2h back, no meal → POST_BOLUS.
        assertEquals(
            Regime.POST_BOLUS,
            classifyRegime(bolus.tsMs + 60L * 60_000, emptyList(), listOf(bolus), emptyList(), hour = 12),
        )
    }

    @Test
    fun noisyDaytimeQuietVsWildPostMealGetDifferentWidths() {
        // 20 days: quiet flat readings with tiny noise; daily "meal" at 12:00
        // followed by a wild ±3 mmol swing for 2.5h. The POST_MEAL corridor
        // must come out wider than the QUIET one.
        val readings = mutableListOf<GlucosePoint>()
        val meals = mutableListOf<Long>()
        val dayMs = 24L * 3_600_000
        for (d in 0 until 20) {
            val mealTs = d * dayMs + 12L * 3_600_000
            meals.add(mealTs)
            var t = d * dayMs + 8L * 3_600_000       // 08:00..20:00 daytime
            while (t < d * dayMs + 20L * 3_600_000) {
                val sinceMeal = (t - mealTs) / 60_000.0
                val wild = if (sinceMeal in 0.0..150.0) {
                    3.0 * kotlin.math.sin(sinceMeal / 11.0 + d)
                } else 0.0
                val noise = 0.15 * kotlin.math.sin(t / 600_000.0)
                readings.add(GlucosePoint(t, 7.0 + wild + noise))
                t += 5 * 60_000
            }
        }
        val rc = calibrateRegimeCorridors(
            readings, emptyList(), kernel,
            foods = emptyList(), mealOnsetsMs = meals,
            activityWindows = emptyList(),
            hourOf = { ts -> ((ts / 3_600_000) % 24).toInt() },
            anchorStepMin = 30.0,
        )
        assertNotNull(rc)
        rc!!
        val quiet = rc.forRegime(Regime.QUIET)
        val postMeal = rc.forRegime(Regime.POST_MEAL)
        val wQuiet = quiet.halfWidth(60.0)
        val wMeal = postMeal.halfWidth(60.0)
        assertTrue(
            "post-meal ($wMeal) should be wider than quiet ($wQuiet)",
            wMeal > wQuiet * 1.5,
        )
    }

    @Test
    fun thinRegimeFallsBackToGlobal() {
        // Flat data, no meals/boluses/activity → only QUIET (day) and NIGHT
        // have data; ACTIVITY must inherit the global corridor.
        val readings = (0 until 2000).map {
            GlucosePoint(it * 5L * 60_000, 7.0 + 0.1 * kotlin.math.sin(it * 0.7))
        }
        val rc = calibrateRegimeCorridors(
            readings, emptyList(), kernel,
            foods = emptyList(), mealOnsetsMs = emptyList(),
            activityWindows = emptyList(),
            hourOf = { ts -> ((ts / 3_600_000) % 24).toInt() },
        )
        assertNotNull(rc)
        assertEquals(rc!!.global, rc.forRegime(Regime.ACTIVITY))
    }
}
