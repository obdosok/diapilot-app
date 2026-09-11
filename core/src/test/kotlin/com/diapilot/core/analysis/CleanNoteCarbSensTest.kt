package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [carbSensFromCleanNotes] — the note-anchored, insulin-free carb-sensitivity
 * learner that replaces the detector-fed [carbSensitivity].
 *
 * Each test pins ONE gate, because every gate here has a known direction of
 * damage if it silently stops working, and the previous coefficient (0.10 against
 * an external anchor of 0.23–0.25) was wrong precisely by accumulating such
 * biases quietly.
 */
class CleanNoteCarbSensTest {

    private val h = 3_600_000L

    /** JUnit's assertNotNull returns Unit, so this returns the value instead. */
    private fun <T : Any> needs(v: T?, what: String = "expected a value"): T {
        assertTrue(what, v != null)
        return v!!
    }

    private fun obs(
        onsetMs: Long,
        peakRise: Double,
        carbGrams: Double,
        ttpMin: Double = 60.0,
        peakObserved: Boolean = true,
        component: String? = null,
    ) = MealObservation(
        fingerprint = mealFingerprint(listOf("смузи" to carbGrams)),
        ttpMin = ttpMin,
        tailRise = 0.0,
        peakRise = peakRise,
        onsetMs = onsetMs,
        carbGrams = carbGrams,
        peakObserved = peakObserved,
        onsetLagMin = 0.0,
        component = component,
    )

    /** No drift correction, so the arithmetic under test is only the ratio. */
    private fun learn(
        corpus: List<MealObservation>,
        boluses: List<BolusPoint> = emptyList(),
        minMeals: Int = 3,
        lookbackMin: Long = 240L,
        drift: Double = 0.0,
    ) = carbSensFromCleanNotes(
        corpus, boluses, minMeals = minMeals,
        insulinLookbackMin = lookbackMin, basalDriftMmolPerH = drift,
    )

    @Test
    fun `learns the median per-gram from clean episodes`() {
        // 6.0/30, 9.0/30, 12.0/30 = 0.20, 0.30, 0.40 -> median 0.30
        val corpus = listOf(
            obs(10 * h, 6.0, 30.0), obs(30 * h, 9.0, 30.0), obs(50 * h, 12.0, 30.0),
        )
        val s = needs(learn(corpus))
        assertEquals(0.30, s.mmolPerGram, 1e-9)
        assertEquals(3, s.n)
        assertTrue(s.q1 < s.mmolPerGram && s.q3 > s.mmolPerGram)
    }

    @Test
    fun `an episode with insulin acting is excluded`() {
        val corpus = listOf(
            obs(10 * h, 6.0, 30.0), obs(30 * h, 9.0, 30.0), obs(50 * h, 12.0, 30.0),
        )
        // One unit two hours before the middle meal — inside the 240-min gate.
        val dirty = listOf(BolusPoint(30 * h - 2 * h, 1.0))
        assertNull("3 episodes minus 1 dirty is below minMeals", learn(corpus, dirty))

        // ...and with a fourth clean episode it survives, without the dirty one:
        // remaining 0.20, 0.40, 0.40 -> median 0.40, NOT the 0.30 of the full set.
        val corpus4 = corpus + obs(70 * h, 12.0, 30.0)
        val s = needs(learn(corpus4, dirty))
        assertEquals(3, s.n)
        assertEquals(0.40, s.mmolPerGram, 1e-9)
    }

    @Test
    fun `the insulin lookback window is honoured at its edges`() {
        val corpus = listOf(
            obs(10 * h, 6.0, 30.0), obs(30 * h, 9.0, 30.0), obs(50 * h, 12.0, 30.0),
        )
        // A bolus 300 min before the middle meal: outside 240, inside no gate wider.
        val old = listOf(BolusPoint(30 * h - 300 * 60_000, 1.0))
        assertEquals(3, needs(learn(corpus, old)).n)
        // The same bolus IS inside a 360-min gate.
        assertNull(learn(corpus, old, lookbackMin = 360L))
    }

    @Test
    fun `a bolus lands INSIDE the measurement window, after the meal`() {
        // The gate must look forward to the meal's own peak, not only backward:
        // a bolus 30 min into a 60-min window is acting during the measurement.
        val corpus = listOf(
            obs(10 * h, 6.0, 30.0), obs(30 * h, 9.0, 30.0), obs(50 * h, 12.0, 30.0),
        )
        val during = listOf(BolusPoint(30 * h + 30 * 60_000, 1.0))
        assertNull(learn(corpus, during))
    }

    @Test
    fun `censored episodes are excluded because their peak is a lower bound`() {
        // The censored one carries a HIGH ratio; if it leaked in it would move the
        // median. Its exclusion must not depend on the value being low.
        val corpus = listOf(
            obs(10 * h, 6.0, 30.0), obs(30 * h, 9.0, 30.0), obs(50 * h, 12.0, 30.0),
            obs(70 * h, 30.0, 30.0, peakObserved = false),
        )
        val s = needs(learn(corpus))
        assertEquals(3, s.n)
        assertEquals(0.30, s.mmolPerGram, 1e-9)
    }

    @Test
    fun `component rows are excluded so one meal is not counted twice`() {
        val corpus = listOf(
            obs(10 * h, 6.0, 30.0), obs(30 * h, 9.0, 30.0), obs(50 * h, 12.0, 30.0),
            obs(50 * h, 12.0, 30.0, component = "juice"),
        )
        assertEquals(3, needs(learn(corpus)).n)
    }

    @Test
    fun `tiny meals are excluded — their relative gram error is huge`() {
        val corpus = listOf(
            obs(10 * h, 6.0, 30.0), obs(30 * h, 9.0, 30.0), obs(50 * h, 12.0, 30.0),
            obs(70 * h, 4.0, 5.0),   // 0.80/g — would drag the median up hard
        )
        assertEquals(3, needs(learn(corpus)).n)
    }

    @Test
    fun `basal drift correction raises the estimate, and by the measured amount`() {
        // One hour of window, drift -0.51 mmol/h: 6.0 observed -> 6.51 attributed.
        val corpus = listOf(
            obs(10 * h, 6.0, 30.0), obs(30 * h, 6.0, 30.0), obs(50 * h, 6.0, 30.0),
        )
        val raw = needs(learn(corpus))
        val corrected = needs(carbSensFromCleanNotes(corpus, emptyList(), insulinLookbackMin = 240L),)
        assertEquals(0.20, raw.mmolPerGram, 1e-9)
        assertEquals(6.51 / 30.0, corrected.mmolPerGram, 1e-9)
        assertTrue("basal correction must raise it", corrected.mmolPerGram > raw.mmolPerGram)
    }

    @Test
    fun `too few clean episodes returns null rather than a confident number`() {
        val corpus = listOf(obs(10 * h, 6.0, 30.0), obs(30 * h, 9.0, 30.0))
        assertNull(learn(corpus))
    }
}
