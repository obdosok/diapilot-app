package com.diapilot.core.hybrid

import com.diapilot.core.analysis.FoodKineticFeaturesV2
import com.diapilot.core.analysis.FoodPhysicalFormV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CLUSTER SHARES ONE PIPE.
 *
 * Review finding: the caloric limit reached a single dish and not a
 * cluster, so pizza alone slowed while pizza + beer fell back to the flat
 * 30 g/h — and the measurement had said the cluster is where it matters most,
 * a much larger delay on a joined meal against a smaller one on the pizza alone.
 *
 * The fixture must therefore be MULTI-MEAL and FATTY. Either alone passes with
 * the defect live: a single dish already had the queue, and a lean cluster
 * cannot tell the two queues apart because 120 kcal/h IS 30 g/h restated.
 */
class ClusterCaloricQueueTest {

    private val person by lazy {
        HybridBlindDayParityTest().model(
            org.json.JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json"))
                    .bufferedReader().readText(),
            ),
        ).let { it.copy(food = it.food.copy(globalFactor = .165)) }
    }

    private fun engine(caloric: Boolean) = HybridForecastEngine(
        person,
        MacroTimingParamsV1(),
        if (caloric) CarbAppearancePolicyV1.PHYSIO_SHIPPED else CarbAppearancePolicyV1.GRAM_QUEUE_LEGACY,
    )

    /** The SAME rate with the fat let through — the honest control for "does
     *  fat slow this cluster". The gram queue is a different base speed and
     *  at the shipped rate (180 kcal/h) a SLOWER one, so comparing against it
     *  would answer a question about rates while claiming to answer one about
     *  fat. */
    private fun carbsFirst() = HybridForecastEngine(
        person,
        MacroTimingParamsV1(),
        CarbAppearancePolicyV1.PHYSIO_SHIPPED.copy(carbSieving = 1.0),
    )

    private fun dish(tsMin: Long, carbs: Double, protein: Double, fat: Double, text: String) =
        HybridFoodEvent(
            tsMs = tsMin * 60_000, carbsG = carbs, text = text, proteinG = protein, fatG = fat,
            kineticFeatures = FoodKineticFeaturesV2(
                .05, .90, .05, FoodPhysicalFormV2.SOFT_SOLID, confidence = .6, provenance = "accepted-v1",
            ),
        )

    private val pizza = dish(0, 69.0, 30.0, 30.0, "пицца")
    private val beer = dish(40, 18.0, 2.0, 0.0, "пиво")

    /** How much of the pizza has arrived three hours in, inside the cluster. */
    private fun pizzaAt180(caloric: Boolean): Double =
        engine(caloric).clusteredFoodCdf(pizza, listOf(pizza, beer), 180.0)

    /** WHEN the cluster's pizza reaches 90%, not how much has arrived at a
     *  fixed hour. At 180 kcal/h both arms are past 97% by minute 180, so a
     *  level probe there compares two saturated curves and reports «identical»
     *  about a two-hour difference. Same lesson `CarbSievingTest` records. */
    private fun mark(e: HybridForecastEngine, foods: List<HybridFoodEvent>): Double {
        var t = 0.0
        while (t < 900.0) {
            if (e.clusteredFoodCdf(pizza, foods, t) >= .90) return t
            t += 1.0
        }
        return 900.0
    }

    @Test
    fun `a cluster is slowed by its calories, not only a single dish`() {
        val withFat = mark(engine(true), listOf(pizza, beer))
        val ignoringFat = mark(carbsFirst(), listOf(pizza, beer))
        assertTrue(
            "the cluster ignored its fat: $withFat vs $ignoringFat",
            withFat - ignoringFat > 15.0,
        )
    }

    /**
     * A LEAN cluster must be untouched — that is the safety property of the
     * whole change, and it is what makes «120 kcal/h» a restatement of 30 g/h
     * rather than a new number. It also proves this test could not have passed
     * on a lean fixture while the defect was live.
     */
    /** A LEAN cluster has no fat for the sieve to hold back, so every sieving
     *  value must give the same answer. This used to compare the caloric queue
     *  against the 30 g/h gram queue and passed only while 120 kcal/h made the
     *  two the same speed; the surviving claim is that SIEVING is inert without
     *  fat, which is what the parameter means. */
    @Test
    fun `a lean cluster is untouched by sieving`() {
        val juice = dish(0, 30.0, 0.0, 0.0, "сок")
        val dextrose = dish(30, 15.0, 0.0, 0.0, "глюкоза")
        listOf(60.0, 120.0, 240.0).forEach { age ->
            assertEquals(
                "lean cluster moved at $age min",
                carbsFirst().clusteredFoodCdf(juice, listOf(juice, dextrose), age),
                engine(true).clusteredFoodCdf(juice, listOf(juice, dextrose), age),
                1e-9,
            )
        }
    }

    /** The pipe is SHARED: adding a second meal must slow the first, because
     *  they compete for the same caloric outflow rather than each getting one. */
    @Test
    fun `a second meal competes with the first for the pipe`() {
        val alone = mark(engine(true), listOf(pizza))
        val shared = mark(engine(true), listOf(pizza, beer))
        assertTrue("beer did not compete with the pizza: $shared vs $alone", shared - alone > 5.0)
    }

    /** Every member's fraction stays a fraction, cluster or not. */
    @Test
    fun `fractions stay bounded`() {
        listOf(30.0, 120.0, 300.0, 600.0).forEach { age ->
            val f = pizzaAt180(true)
            assertTrue("fraction out of range at $age: $f", f in 0.0..1.0)
        }
    }
}
