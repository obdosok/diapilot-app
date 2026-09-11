package com.diapilot.core.hybrid

import com.diapilot.core.analysis.FoodKineticFeaturesV2
import com.diapilot.core.analysis.FoodPhysicalFormV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One physio engine, or none — the parity the review asked for.
 *
 * The caloric queue landed at two call sites and missed six: What-if,
 * closed-episode closure, the global-CS learner and the typed-evidence runtime
 * each built their own engine with the shipped defaults. A FATTY meal is where
 * they diverge, because that is the only place the queue binds, so a lean
 * fixture would pass while the defect was live.
 *
 * The consequence is not cosmetic for the CURVE: What-if drew a fatty meal by
 * different rules than the forecast beside it. It turned out NOT to reach
 * closure, though that was claimed before measuring — see the last test.
 */
class PhysioEngineParityTest {

    private val person by lazy {
        HybridBlindDayParityTest().model(
            org.json.JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json"))
                    .bufferedReader().readText(),
            ),
        ).let { it.copy(food = it.food.copy(globalFactor = .165)) }
    }

    /** Pizza: the queue binds hardest here and nowhere on a lean dish. */
    private val fatty = HybridFoodEvent(
        tsMs = 0L, carbsG = 69.0, text = "пицца", proteinG = 30.0, fatG = 30.0,
        kineticFeatures = FoodKineticFeaturesV2(
            .05, .90, .05, FoodPhysicalFormV2.SOFT_SOLID, confidence = .6, provenance = "accepted-v1",
        ),
    )

    private val lean = fatty.copy(text = "сок", carbsG = 22.0, proteinG = 0.0, fatG = 0.0)
    /**
     * Every physio consumer must agree, and the fixture has to be FATTY: on a
     * lean dish the caloric and gram queues are identical by construction, so a
     * lean fixture cannot detect a missed call site.
     */
    @Test
    fun `all physio consumers see one shape`() {
        // Same call twice, and once through the explicit shipped timing: the
        // factory's DEFAULT must be the shipped physiology, not the type's
        // zeroed one. Passing MacroTimingParamsV1() here used to be «the same
        // engine» and is now a different model — which is the fix that made
        // GlobalCsRuntime and TypedPhysioEvidenceRuntime stop losing the slope.
        val a = physioForecastEngine(person).foodTiming(fatty)
        val b = physioForecastEngine(person, PHYSIO_SHIPPED_MACRO_TIMING_V1).foodTiming(fatty)
        assertEquals(a.onsetMin, b.onsetMin, 1e-9)
        assertEquals(a.medianArrivalMin, b.medianArrivalMin, 1e-9)
        assertEquals(a.plateauMin, b.plateauMin, 1e-9)
        assertEquals(a.tailEndMin, b.tailEndMin, 1e-9)
    }

    /** A lean fixture would have passed with the defect live — asserted so the
     *  next person cannot "simplify" the fixture and silently lose the test. */
    @Test
    fun `a lean dish cannot detect the defect`() {
        val withQueue = physioForecastEngine(person).foodTiming(lean)
        val without = HybridForecastEngine(
            person,
            PHYSIO_SHIPPED_MACRO_TIMING_V1, CarbAppearancePolicyV1.GRAM_QUEUE_LEGACY,
        ).foodTiming(lean)
        assertEquals(
            "a lean dish now differs — the queue is no longer 30 g/h restated",
            without.plateauMin, withQueue.plateauMin, 1e-9,
        )
    }

    /**
     * ON A SINGLE DISH THE QUEUE IS CURRENTLY INERT, AND THAT IS THE POINT OF
     * THIS TEST — measured when the rate moved 120 -> 180 kcal/h.
     *
     * Three tests used to live here asserting that the caloric queue holds a
     * fatty dish back against the 30 g/h gram queue. They were true at 120 and
     * are false at 180, and the reason is arithmetic rather than physiology:
     *
     *   pizza 69 g carb / 30 p / 30 f = 666 kcal
     *   sieving 0.65 basis = .65*276 + .35*666 = 412.5 kcal
     *   raw rate at 180 = 69 * 180 / 412.5 = 30.1 g/h
     *   cap  = PERSONAL_CARB_APPEARANCE_PRIOR_G_PER_HOUR_V1 = 30 g/h
     *
     * So the cap binds, sieving 0.65 and 1.0 both clamp to 30, and the two
     * are indistinguishable. Only a dish fat enough to push the raw rate BELOW
     * 30 still gets a delay; pizza sits exactly on the boundary.
     *
     * In a CLUSTER the cap does not apply — `caloricLimitedFractionsV1` meters
     * kcal directly — so the queue and the sieve are alive there, which
     * `ClusterCaloricQueueTest` pins.
     *
     * Pinned rather than deleted so that raising the cap breaks this test and
     * forces the question to be re-read instead of rediscovered.
     */
    @Test
    fun `sieving separates a single fatty dish, so the cap is not clipping it`() {
        val shipped = physioForecastEngine(person).foodTiming(fatty)
        val carbsFirst = HybridForecastEngine(
            person,
            PHYSIO_SHIPPED_MACRO_TIMING_V1,
            CarbAppearancePolicyV1.PHYSIO_SHIPPED.copy(carbSieving = 1.0),
        ).foodTiming(fatty)
        val mixed = HybridForecastEngine(
            person,
            PHYSIO_SHIPPED_MACRO_TIMING_V1,
            CarbAppearancePolicyV1.PHYSIO_SHIPPED.copy(carbSieving = 0.0),
        ).foodTiming(fatty)
        assertTrue(
            "the shipped sieve is indistinguishable from carbs-first: " +
                "${shipped.plateauMin} vs ${carbsFirst.plateauMin} — the cap is clipping again",
            shipped.plateauMin > carbsFirst.plateauMin + 15.0,
        )
        assertTrue(
            "the shipped sieve is indistinguishable from perfectly mixed: " +
                "${shipped.plateauMin} vs ${mixed.plateauMin}",
            mixed.plateauMin > shipped.plateauMin + 15.0,
        )
    }
}
