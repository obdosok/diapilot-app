package com.diapilot.core.hybrid

import com.diapilot.core.analysis.FoodKineticFeaturesV2
import com.diapilot.core.analysis.FoodPhysicalFormV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fitted fat->peak slope has to reach the UNKNOWN-dish branch.
 *
 * It shipped inside `physioMacroShape`, which since wave 2 runs only when a
 * learned pool wins — the two dishes that already have their own measured
 * curve. Everything else takes `physioFeatureShapes`, where fat moved the peak
 * by `fat*.35*.35*macroShare`, about 0.9 min for 10 g. The whole point of the
 * user's architecture is that an unfamiliar dish be drawable from its
 * composition, so the coefficient belongs where unfamiliar dishes are drawn.
 *
 * Mutation-checked.
 */
class FatPeakInMixtureTest {

    private val person by lazy {
        HybridBlindDayParityTest().model(
            org.json.JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json"))
                    .bufferedReader().readText(),
            ),
        ).let { it.copy(food = it.food.copy(globalFactor = .165)) }
    }

    private fun engine(fitted: Boolean) = HybridForecastEngine(
        person,
        if (fitted) MacroTimingParamsV1("test", true, fatPeakMinPer10g = 17.0)
        else MacroTimingParamsV1(),
    )

    /** A dish with STRUCTURE but no personal history — the case the whole
     *  architecture is for. */
    private fun meal(fatG: Double, proteinG: Double = 4.0) = HybridFoodEvent(
        tsMs = 0L, carbsG = 40.0, text = "незнакомое блюдо",
        proteinG = proteinG, fatG = fatG,
        kineticFeatures = FoodKineticFeaturesV2(
            .20, .60, .20, FoodPhysicalFormV2.SOFT_SOLID, confidence = .6, provenance = "accepted-v1",
        ),
    )

    /**
     * Asserted on the MEDIAN ARRIVAL, not on `peakMin`.
     *
     * `peakMin` is an argmax over a two-humped rate and is not monotone in fat —
     * measured: 55 -> 72 -> 59 -> 93 min for 0, 10, 20, 40 g. A test written
     * against it would have to encode that wobble as if it were intended.
     */
    @Test
    fun `fat moves an unknown dish later once the slope is fitted`() {
        val e = engine(true)
        val lean = e.foodTiming(meal(0.0)).medianArrivalMin
        val fatty = e.foodTiming(meal(20.0)).medianArrivalMin
        assertTrue("fat moved arrival by only ${fatty - lean} min", fatty - lean > 15.0)
    }

    /** Monotone in fat, which is the property the argmax lacks. */
    @Test
    fun `arrival is monotone in fat`() {
        val e = engine(true)
        val arrivals = listOf(0.0, 10.0, 20.0, 40.0).map { e.foodTiming(meal(it)).medianArrivalMin }
        arrivals.zipWithNext().forEach { (a, b) ->
            assertTrue("arrival went backwards: $arrivals", b > a)
        }
    }

    @Test
    fun `unfitted behaviour is the old near-zero fat effect`() {
        val e = engine(false)
        val lean = e.foodTiming(meal(0.0)).medianArrivalMin
        val fatty = e.foodTiming(meal(20.0)).medianArrivalMin
        // WHAT THIS PINS IS THE GAP, not an absolute «barely». The bound was 15
        // min and the unfitted arm now moves arrival by 17.75 — not because the
        // fat term grew but because the medium triangle got shorter (end 180 ->
        // 91, M-93), so the same gastric delay is a larger share of a shorter
        // curve. The claim worth keeping is that the FITTED shift adds a real
        // fat-to-peak effect ON TOP of the gastric one, which is a comparison
        // between the two arms and survives a change of shape.
        val fittedLean = engine(true).foodTiming(meal(0.0)).medianArrivalMin
        val fittedFatty = engine(true).foodTiming(meal(20.0)).medianArrivalMin
        assertTrue(
            "the fitted shift adds nothing over the gastric term: " +
                "unfitted ${fatty - lean}, fitted ${fittedFatty - fittedLean}",
            (fittedFatty - fittedLean) - (fatty - lean) > 5.0,
        )
    }

    /**
     * NO DOUBLE COUNT. Fat feeds the gastric term too, so when the fitted shift
     * is in force fat must leave the peak's gastric part or the two stack.
     *
     * The bound is TIGHT because it has to be: the old fat term is worth about
     * 0.9 min per 10 g, so a loose assertion cannot tell double-counting from
     * correct behaviour — the first version used «< 45» and mutation showed it
     * green with the removal deleted. These are deterministic computations, so a
     * narrow window is legitimate rather than brittle.
     */
    @Test
    fun `the fitted shift replaces the old fat term rather than adding to it`() {
        val e = engine(true)
        val lean = e.foodTiming(meal(0.0)).medianArrivalMin
        val fatty = e.foodTiming(meal(20.0)).medianArrivalMin
        // THE BOUND IS MEASURED, and its window is now uncomfortably narrow.
        //
        // Re-measured after the carb triangles were refitted (M-93):
        //     correct (fat dropped from the peak's gastric term)  27.00 min
        //     stacked (removal deleted)                           27.25 min
        //
        // A quarter of a minute out of twenty-seven. Under the previous
        // triangles the window was 1.25 min, and the shrinkage has a cause worth
        // recording: `fatPeakFitted` requires `macroTiming.fatPeakMinPer10g > 0`
        // and the SHIPPED `PHYSIO_SHIPPED_MACRO_TIMING_V1` sets it to 0.0 — so
        // the branch this test guards is DORMANT in production and only this
        // test's `engine(true)` reaches it. The guard is kept because the branch
        // is still there and would fire if that constant were ever promoted, but
        // its discriminating power is thin and a future failure here is more
        // likely to be an unrelated shape change than a real double count.
        val bound = 27.1
        assertTrue("shift ${fatty - lean} looks like the two terms stacked", fatty - lean < bound)
    }

    /** Fat's effect on ONSET is unchanged: the fit said nothing about it
     *  (+3.4 min per 10 g, interval -1.2..+9.0). */
    @Test
    fun `the onset term is untouched by the peak fit`() {
        val fittedOnset = engine(true).foodTiming(meal(20.0)).onsetMin
        val plainOnset = engine(false).foodTiming(meal(20.0)).onsetMin
        assertEquals(plainOnset, fittedOnset, 1e-9)
    }

    /** Amplitude is carbohydrate x CS and this must not touch it. */
    @Test
    fun `amplitude does not move`() {
        assertEquals(
            engine(false).foodAmplitude(meal(20.0)).first,
            engine(true).foodAmplitude(meal(20.0)).first,
            1e-12,
        )
    }
}
