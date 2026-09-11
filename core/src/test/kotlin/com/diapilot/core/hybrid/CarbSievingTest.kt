package com.diapilot.core.hybrid

import com.diapilot.core.analysis.FoodKineticFeaturesV2
import com.diapilot.core.analysis.FoodPhysicalFormV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE STOMACH SIEVES — and the parameter's two ends are the two queues.
 *
 * This is the test that keeps the fit honest. Sieving was introduced because
 * the user's own real meals refuted the perfectly-mixed caloric queue: it held
 * a large share of the carbohydrate unarrived many hours later on evenings
 * where the glucose had fallen low with no insulin left. But the repair must
 * not quietly become "go back to grams", so the endpoints are pinned as identities:
 *
 *   sigma = 1  ≡  carbohydrate first, unaffected by the fat beside it
 *   sigma = 0  ≡  carbohydrate pro rata with calories (what was refuted)
 *
 * The first line used to read "sigma = 1 ≡ the flat 30 g/h gram queue,
 * to the digit", and that was true only because the rate was 120 kcal/h and
 * 30 g/h x 4 kcal/g IS 120. At 180 the identity survives for a SINGLE dish —
 * `caloricCarbRateGPerHourV1` caps the carb rate at 30 g/h — and ends for a
 * CLUSTER, which meters kcal directly with no such cap. So the endpoints are
 * now pinned against each other at one rate, which is the fat claim itself.
 *
 * If a later change makes the shipped value indistinguishable from either end,
 * these tests say so instead of leaving a parameter that does nothing.
 */
class CarbSievingTest {

    private val person by lazy {
        HybridBlindDayParityTest().model(
            org.json.JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json"))
                    .bufferedReader().readText(),
            ),
        ).let { it.copy(food = it.food.copy(globalFactor = .165)) }
    }

    private fun dish(tsMin: Long, carbs: Double, protein: Double, fat: Double, text: String) =
        HybridFoodEvent(
            tsMs = tsMin * 60_000, carbsG = carbs, text = text, proteinG = protein, fatG = fat,
            kineticFeatures = FoodKineticFeaturesV2(
                .05, .90, .05, FoodPhysicalFormV2.SOFT_SOLID, confidence = .6, provenance = "accepted-v1",
            ),
        )

    private val pizza = dish(0, 69.0, 30.0, 30.0, "пицца")
    private val beer = dish(40, 18.0, 2.0, 0.0, "пиво")

    /** The shipped policy with only the sieving moved — the sweep axis. */
    private fun sieved(sigma: Double) =
        CarbAppearancePolicyV1.PHYSIO_SHIPPED.copy(carbSieving = sigma)

    /** ASKED FOR BY NAME. Since the appearance policy is derived from the arm,
     *  a physio engine built with three arguments is a CALORIC one — which is
     *  the point of the change, and which silently turned this helper into its
     *  own opposite until the endpoint identity failed. */
    private fun gramQueue() = HybridForecastEngine(
        person,
        // THE SHIPPED TIMING ON BOTH SIDES. The axis under test is the QUEUE;
        // holding the fat slope at the type's default here while the factory
        // ships +17 would compare two things at once and the identity below
        // would fail for the wrong reason.
        PHYSIO_SHIPPED_MACRO_TIMING_V1, CarbAppearancePolicyV1.GRAM_QUEUE_LEGACY,
    )

    /** CARBOHYDRATE FIRST IS THE CEILING, and the ceiling is derived.
     *
     *  With sieving 1.0 only the carbohydrate calories count, so the rate is
     *  `kcalPerHour / 4` — which is also what the cap is set to. The two must
     *  coincide exactly: if the cap ever sat BELOW this, it would clip sieving
     *  values that are supposed to be distinguishable, which is precisely what
     *  happened while the cap was a hardcoded 30 g/h and the rate moved to 180.
     */
    @Test
    fun `carbohydrate first runs at the pure-carbohydrate ceiling`() {
        val rate = PERSONAL_EMPTYING_KCAL_PER_HOUR_V1
        assertEquals(
            "sieving 1.0 is not the carbs-only rate",
            rate / KCAL_PER_CARB_GRAM_V1,
            caloricCarbRateGPerHourV1(69.0, 30.0, 30.0, rate, carbSieving = 1.0),
            1e-9,
        )
        // ...and every sieving BELOW it must be strictly slower, i.e. unclipped.
        listOf(0.0, .35, PERSONAL_CARB_SIEVING_V1, .9).forEach { sigma ->
            val r = caloricCarbRateGPerHourV1(69.0, 30.0, 30.0, rate, carbSieving = sigma)
            assertTrue(
                "sieving $sigma was clipped to the ceiling: $r",
                r < rate / KCAL_PER_CARB_GRAM_V1 - 1e-9,
            )
        }
    }

    /** IN A CLUSTER there is no such cap, so the identity ends — and what
     *  replaces it is the claim that actually matters: at the SAME rate, letting
     *  carbohydrate through first is strictly faster than making it queue behind
     *  the fat. Same base speed on both sides, so the only difference is fat. */
    @Test
    fun `carbohydrate first is faster than carbohydrate behind the fat`() {
        fun mark(sigma: Double): Double {
            val e = physioForecastEngine(person, appearance = sieved(sigma))
            var t = 0.0
            while (t < 900.0) {
                if (e.clusteredFoodCdf(pizza, listOf(pizza, beer), t) >= .90) return t
                t += 1.0
            }
            return 900.0
        }
        val first = mark(1.0)
        val shipped = mark(PERSONAL_CARB_SIEVING_V1)
        val mixed = mark(0.0)
        assertTrue("carbs-first is not the fastest end: $first vs $shipped", shipped - first > 5.0)
        assertTrue("perfectly mixed is not the slowest end: $mixed vs $shipped", mixed - shipped > 5.0)
    }

    /** And zero is the perfectly-mixed queue the user's data refuted — kept live so
     *  the refutation stays reproducible rather than becoming folklore.
     *
     *  Compared against carbs-first AT THE SAME RATE. It used to be compared
     *  against the 30 g/h gram queue, which at 180 kcal/h is simply a slower
     *  base speed and would have made this pass for the wrong reason. */
    @Test
    fun `sieving of zero is materially slower than carbohydrate first`() {
        val mixed = physioForecastEngine(person, appearance = sieved(0.0))
        val first = physioForecastEngine(person, appearance = sieved(1.0))
        assertTrue(
            "the mixed queue no longer holds carbohydrate back",
            first.clusteredFoodCdf(pizza, listOf(pizza, beer), 180.0) -
                mixed.clusteredFoodCdf(pizza, listOf(pizza, beer), 180.0) > 0.15,
        )
    }

    /** The shipped value must sit strictly BETWEEN the two ends: at either end
     *  the parameter is doing nothing and one of the two measurements that
     *  fitted it is being ignored. */
    @Test
    fun `the shipped value is neither end`() {
        // Scored at the 90% MARK, not at a fixed age: at 300 min the pizza is
        // 99.4% arrived under both 0.8 and 1.0, so a level probe there compares
        // two saturated curves and reports "identical" about a real difference.
        fun mark(sigma: Double): Double {
            val e = physioForecastEngine(person, appearance = sieved(sigma))
            var t = 0.0
            while (t < 900.0) {
                if (e.clusteredFoodCdf(pizza, listOf(pizza, beer), t) >= .90) return t
                t += 1.0
            }
            return 900.0
        }
        val now = mark(PERSONAL_CARB_SIEVING_V1)
        val one = mark(1.0)
        val zero = mark(0.0)
        assertTrue("shipped == carbs-first: $now vs $one", now - one > 5.0)
        assertTrue("shipped == perfectly mixed: $now vs $zero", zero - now > 5.0)
    }

    /** A LEAN dish is untouched at every sieving value — there is no fat for
     *  the carbohydrate to pass, so the whole axis must collapse.
     *
     *  Compared ACROSS sieving values rather than against the 30 g/h gram queue:
     *  a lean dish under the caloric queue now runs at `kcalPerHour/4`, so the
     *  old comparison tested the rate, not the sieve. */
    @Test
    fun `a lean dish is untouched at any sieving`() {
        val juice = dish(0, 30.0, 0.0, 0.0, "сок")
        val reference = physioForecastEngine(person, appearance = sieved(1.0))
        listOf(0.0, .35, .8).forEach { sigma ->
            listOf(45.0, 120.0, 240.0).forEach { age ->
                assertEquals(
                    "lean dish moved at sigma=$sigma, $age min",
                    reference.foodCdf(juice, age),
                    physioForecastEngine(person, appearance = sieved(sigma)).foodCdf(juice, age),
                    1e-9,
                )
            }
        }
    }
}
