package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.hybrid.HybridBlindDayParityTest
import com.diapilot.core.hybrid.physioForecastEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A PROFILELESS NEIGHBOUR MUST STILL BE SUBTRACTED.
 *
 * Reported by the user from real cards: a pizza with no concept
 * profile was subtracted as ZERO, so a cake eaten shortly after was
 * credited with its ongoing rise — several times the user's carb
 * sensitivity, presented as measured fact.
 *
 * The fixture reproduces that arrangement: a large slow first meal, a small one
 * behind it, and a glucose trace that keeps climbing through both. Without the
 * subtraction the second meal must look enormous; with it, far smaller.
 */
class NeighbourContributionV1Test {

    private val m = 60_000L
    private val person by lazy {
        HybridBlindDayParityTest().model(
            org.json.JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json"))
                    .bufferedReader().readText(),
            ),
        ).let { it.copy(food = it.food.copy(globalFactor = .165)) }
    }

    private fun note(id: Long, atMin: Long, text: String, carbs: Double, p: Double, f: Double) =
        Annotation(
            atMin * m, "food", text, estCarbs = carbs, id = id,
            analysis = "БЕЛКИ: $p г\nЖИРЫ: $f г",
        )

    /** Climbs steadily for six hours: both meals are "still arriving". */
    private val readings = (0..420 step 5).map { t -> GlucosePoint(t * m, 5.0 + t * 0.02) }
    private val kernel = (0..300 step 5).map { KernelPoint(it.toDouble(), 0.0, 0.0, 0.0, 1) }

    private fun corpus(withNeighbourCurve: Boolean): List<MealObservation> =
        deconvolvedMealObservations(
            notes = listOf(
                note(1, 0, "пицца", 69.0, 30.0, 30.0),
                note(2, 113, "небольшой кусок торта", 35.0, 5.0, 12.0),
            ),
            readings = readings, boluses = emptyList<BolusPoint>(), kernel = kernel,
            nowMs = 600 * m, carbSensPerGram = .165,
            neighbourAppearance = if (withNeighbourCurve) {
                NeighbourContributionV1.factory(physioForecastEngine(person), .165)
            } else null,
        )

    private fun cake(rows: List<MealObservation>) =
        rows.firstOrNull { it.component == null && kotlin.math.abs(it.onsetMs - 113 * m) < 20 * m }

    @Test
    fun `the second meal loses the rise that belonged to the first`() {
        val without = checkNotNull(cake(corpus(false))) { "fixture produced no cake row" }
        val with = checkNotNull(cake(corpus(true))) { "cake vanished from the corpus — this is not subtraction" }
        // Scored as a SHARE, not in mmol: the fixture's ramp is synthetic, so an
        // absolute threshold measures the fixture rather than the subtraction.
        // Measured against a real cake episode on the live data as well.
        //
        // THRESHOLD retuned WHEN THE QUEUE MOVED TO A FASTER RATE,
        // and the direction is the model working rather than the test being
        // loosened: a faster queue means MORE of the pizza has already
        // arrived by the cake's onset, so there is less of it left
        // to subtract. The share fell measurably. The claim this
        // test makes — "the neighbour is not subtracted as ZERO" — is untouched;
        // only the fixture-calibrated size of the effect moved.
        assertTrue(
            "the pizza is still being subtracted as zero: ${without.peakRise} vs ${with.peakRise}",
            (without.peakRise - with.peakRise) / without.peakRise > 0.15,
        )
        // And the amount removed must resemble the pizza, not swallow the cake.
        assertTrue("the cake lost everything: ${with.peakRise}", with.peakRise > 0.0)
    }

    /**
     * A LONE MEAL IS UNTOUCHED. This is the control that separates "subtracting
     * a neighbour" from "subtracting signal": on real data, meals with nothing
     * before them did not move at all, and if that ever
     * stops holding the change has become something else.
     */
    @Test
    fun `a meal with no neighbour is identical either way`() {
        fun lone(withCurve: Boolean) = deconvolvedMealObservations(
            notes = listOf(note(1, 0, "пицца", 69.0, 30.0, 30.0)),
            readings = readings, boluses = emptyList<BolusPoint>(), kernel = kernel,
            nowMs = 600 * m, carbSensPerGram = .165,
            neighbourAppearance = if (withCurve) {
                NeighbourContributionV1.factory(physioForecastEngine(person), .165)
            } else null,
        ).first { it.component == null }
        assertEquals(lone(false).peakRise, lone(true).peakRise, 1e-9)
        assertEquals(lone(false).ttpMin, lone(true).ttpMin, 1e-9)
    }

    /**
     * SHARING MUST NOT ERASE THE OVERLAP MARKERS — the v24 regression.
     *
     * When the insulin share shipped it nulled the carryover, so an overlapped
     * meal read neighbourMmol=0 and confidence 100% with no neighbour note:
     * the exact overconfidence the user had already caught on a real cake episode,
     * reintroduced by the fix for it. The card can only be honest about a
     * shared window if the corpus row still says the window was shared.
     */
    @Test
    fun `sharing keeps the overlap visible and the confidence discounted`() {
        val shared = deconvolvedMealObservations(
            notes = listOf(
                note(1, 0, "пицца", 69.0, 30.0, 30.0),
                note(2, 113, "небольшой кусок торта", 35.0, 5.0, 12.0),
            ),
            readings = readings, boluses = emptyList<BolusPoint>(), kernel = kernel,
            nowMs = 600 * m, carbSensPerGram = .165,
            neighbourAppearance = NeighbourContributionV1.factory(
                physioForecastEngine(person), .165, useOwnAmplitude = false,
            ),
            divideInsulinLikeCarbs = true,
        )
        val cakeRow = checkNotNull(cake(shared))
        assertTrue(
            "the shared window claims no overlap: neighbourMmol=${cakeRow.neighbourMmol}",
            cakeRow.neighbourMmol > 0.0,
        )
        assertTrue(
            "a shared window reads full confidence: ${cakeRow.confidence}",
            cakeRow.confidence < 1.0,
        )
        // And a lone meal keeps full confidence — the discount is about the
        // overlap, not a blanket tax on the mode.
        val lone = deconvolvedMealObservations(
            notes = listOf(note(1, 0, "пицца", 69.0, 30.0, 30.0)),
            readings = readings, boluses = emptyList<BolusPoint>(), kernel = kernel,
            nowMs = 600 * m, carbSensPerGram = .165,
            neighbourAppearance = NeighbourContributionV1.factory(
                physioForecastEngine(person), .165, useOwnAmplitude = false,
            ),
            divideInsulinLikeCarbs = true,
        ).first { it.component == null }
        assertEquals(1.0, lone.confidence, 1e-9)
    }

    /** The factory must never hand back an amplitude that is itself absurd —
     *  a contaminated recovery must not become the next pass's subtractor. */
    @Test
    fun `a contaminated amplitude is bounded before it is reused`() {
        val session = MealSession(listOf(note(1, 0, "пицца", 35.0, 5.0, 12.0)))
        val absurd = listOf(
            MealObservation(
                fingerprint = mealFingerprint(listOf("пицца" to 35.0)),
                ttpMin = 90.0, tailRise = 0.0, peakRise = 33.5, onsetMs = 0L, carbGrams = 35.0,
                peakObserved = true, tailObserved = false, onsetLagMin = 15.0, confidence = 1.0,
            ),
        )
        val f = NeighbourContributionV1.factory(physioForecastEngine(person), .165)(absurd)
        val ceiling = 35.0 * 2 * .165
        assertTrue(
            "an amplitude of 0.96 mmol/g became a subtractor: ${f(session, 600.0)}",
            f(session, 600.0) <= ceiling + 1e-9,
        )
    }
}
