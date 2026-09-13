package com.diapilot.core.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE KNOTS BRANCH IGNORES THE DOSE — a recorded limitation (audit M10), not a
 * property anyone chose.
 *
 * `HybridForecastEngine.insulinCdf(ageMin, units)` has two constructions. The
 * parametric one mixes a short and a long triangle with a weight that grows
 * with `units` (`tailWeightPerUnit`), which is the "dose-dependent insulin
 * duration" the audit lists as sound. The empirical one — `actionCdfKnots`,
 * present whenever a curve was measured, entered by hand or fitted — returns
 * before `units` is read. Every install that has a measured curve therefore
 * runs WITHOUT dose dependence, and the audit sentence is true only of the
 * fallback.
 *
 * This test pins the current behaviour so that the day someone makes the knots
 * curve dose-aware, this fails, and the change ships deliberately with a bench
 * rather than as a side effect. It does not say the behaviour is right.
 */
class InsulinKnotsDoseIndependenceTest {
    private val tolerance = 1e-12

    private fun shipped(): HybridPersonModel =
        javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json")!!.use(HybridPersonModelJson::read)

    private val knots = listOf(
        HybridCdfKnot(0.0, 0.0),
        HybridCdfKnot(20.0, 0.0),
        HybridCdfKnot(75.0, 0.5),
        HybridCdfKnot(150.0, 0.9),
        HybridCdfKnot(300.0, 1.0),
    )

    @Test
    fun `knots curve ignores dose size - recorded limitation, see audit M10`() {
        val base = shipped()
        val withKnots = base.copy(insulin = base.insulin.copy(actionCdfKnots = knots))
        val engine = HybridForecastEngine(withKnots)
        for (age in listOf(10.0, 40.0, 75.0, 120.0, 200.0, 280.0)) {
            val small = engine.insulinCdf(age, units = 1.0)
            val large = engine.insulinCdf(age, units = 10.0)
            assertEquals(
                "with knots present the CDF at $age min must not depend on the dose — " +
                    "if it now does, the audit M10 entry and the KDoc on insulinCdf are stale " +
                    "and the change needs a bench",
                small, large, tolerance,
            )
        }
    }

    @Test
    fun `the parametric fallback is where the dose dependence lives`() {
        // The same model without knots, with a non-zero per-unit tail weight:
        // a larger dose acts longer, so less of it has acted by the same age.
        val base = shipped()
        val parametric = base.copy(
            insulin = base.insulin.copy(
                actionCdfKnots = emptyList(),
                tailWeight = 0.5,
                tailWeightPerUnit = 0.1,
                tailReferenceUnits = 2.0,
            ),
        )
        val engine = HybridForecastEngine(parametric)
        val age = (parametric.insulin.peakMin + parametric.insulin.shortDurationMin) / 2.0
        val small = engine.insulinCdf(age, units = 1.0)
        val large = engine.insulinCdf(age, units = 6.0)
        assertNotEquals("the fallback must read the dose", small, large, tolerance)
        assertTrue("a larger dose has acted less by the same age", large < small)
    }
}
