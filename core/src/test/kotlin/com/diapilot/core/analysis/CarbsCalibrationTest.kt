package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarbsCalibrationTest {
    // Flat synthetic kernel: −1.0 mmol/L per unit absorbed by any peak time.
    private val kernel = listOf(KernelPoint(0.0, -1.0, -1.2, -0.8, 10))

    private fun ep(rise: Double, units: Double?, carbs: Double?, ttp: Double = 60.0) =
        CarbEpisode(0L, rise, ttp, units, carbs)

    @Test
    fun effectAddsBackWhatInsulinAbsorbed() {
        // Rise 3.0 with 2 U on board that already pulled 2.0 → true effect 5.0.
        assertEquals(5.0, glucoseEffect(ep(3.0, 2.0, null), kernel)!!, 1e-9)
    }

    @Test
    fun noBolusMeansRiseIsTheEffect() {
        assertEquals(4.0, glucoseEffect(ep(4.0, null, null), kernel)!!, 1e-9)
        // Bolus present but kernel empty: refuse to guess.
        assertNull(glucoseEffect(ep(4.0, 2.0, null), emptyList()))
    }

    @Test
    fun sensitivityIsMedianOfEffectOverCarbs() {
        // Effects 5.0/50g, 6.0/60g, 4.5/45g → all exactly 0.1 mmol per gram.
        val eps = listOf(
            ep(3.0, 2.0, 50.0),
            ep(4.0, 2.0, 60.0),
            ep(2.5, 2.0, 45.0),
        )
        val s = carbSensitivity(eps, kernel)!!
        assertEquals(0.1, s.mmolPerGram, 1e-9)
        assertEquals(3, s.n)
    }

    @Test
    fun tooFewOrTinyMealsGiveNull() {
        assertNull(carbSensitivity(listOf(ep(3.0, 2.0, 50.0)), kernel))
        // 10 g meals fall under the minCarbs floor.
        assertNull(carbSensitivity(List(3) { ep(1.0, 0.5, 10.0) }, kernel))
    }

    @Test
    fun carbEpisodesMergeNotesAndSkipSysLabels() {
        val min = 60_000L
        fun meal(onset: Long) = com.diapilot.core.collector.MealEvent(
            onsetMs = onset, peakMs = onset + 60 * min, preBg = 6.0, peakBg = 9.0,
            rise = 3.0, timeToPeakMin = 60.0, bolusUnits = 2.0,
            kind = com.diapilot.core.collector.MealEvent.Kind.ANNOUNCED,
        )
        val meals = listOf(meal(0L), meal(10L * 3_600_000))
        val labels = mapOf(10L * 3_600_000 to SysLabels.DAWN)
        val notes = listOf(
            com.diapilot.core.collector.Annotation(-20 * min, "food", "пицца", estCarbs = 55.0),
            com.diapilot.core.collector.Annotation(-25 * min, "text", "стресс", estCarbs = 99.0),
        )
        val eps = carbEpisodes(meals, labels, notes)
        assertEquals(1, eps.size)          // dawn rise excluded
        assertEquals(55.0, eps[0].estCarbs!!, 1e-9)  // nearest food note wins
    }

    @Test
    fun effectiveCarbsInvertsTheCalibration() {
        val s = CarbSensitivity(0.1, 0.08, 0.12, 5)
        // Rise 3.0 + 2 U × 1.0 = effect 5.0 → 50 g equivalent.
        assertEquals(50.0, effectiveCarbs(ep(3.0, 2.0, null), kernel, s)!!, 1e-9)
        // A drop below zero (mislabeled correction) yields null, not nonsense.
        assertNull(effectiveCarbs(ep(-1.0, 2.0, null), listOf(KernelPoint(0.0, 1.0, 0.0, 1.0, 5)), s))
    }

    @Test
    fun `the override WINS but never destroys what was learned`() {
        val learned = CarbSensitivity(mmolPerGram = 0.1026, q1 = 0.07, q3 = 0.19, n = 26)
        val eff = effectiveCarbSens(learned, 0.15)!!
        assertEquals("the forecast runs on the override", 0.15, eff.mmolPerGram, 1e-9)
        // n=0 marks «not measured from meals» — the same convention the carb-side
        // prior uses, so nothing downstream mistakes an assumption for evidence.
        assertEquals(0, eff.n)
        // ...and the learned object itself is untouched: the caller stores it
        // beside the effective one, and their divergence is the signal that says
        // when the override can be removed.
        assertEquals(0.1026, learned.mmolPerGram, 1e-9)
        assertEquals(26, learned.n)
    }

    @Test
    fun `no override means the learned value ships unchanged`() {
        val learned = CarbSensitivity(mmolPerGram = 0.1026, q1 = 0.07, q3 = 0.19, n = 26)
        assertEquals(learned, effectiveCarbSens(learned, null))
        assertEquals(learned, effectiveCarbSens(learned, 0.0))
        assertNull(effectiveCarbSens(null, null))
    }
}
