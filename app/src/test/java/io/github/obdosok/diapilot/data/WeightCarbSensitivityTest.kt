package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.analysis.CARB_SENS_OVERRIDE_DEFAULT
import com.diapilot.core.analysis.CarbSensitivityPriorV1
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WEIGHT REACHES CARB SENSITIVITY (audit M4).
 *
 * Everything needed already existed and nothing was wired: the weight field
 * stored a number, [CarbSensitivityPriorV1] turned a weight into a rise per
 * gram, and the only place the two met was a hint under the text field. The
 * model ran one population constant for every body.
 *
 * These tests pin the wiring at both ends — the chain that decides the number,
 * and the cache that would otherwise keep serving the previous one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeightCarbSensitivityTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun prefs() = context.getSharedPreferences(
        io.github.obdosok.diapilot.collect.TreatmentsPollWorker.PREFS,
        android.content.Context.MODE_PRIVATE,
    )

    @After fun clear() {
        Settings.setWeightKg(context, null)
        prefs().edit().remove("carb_sens_override_mmol_per_g").apply()
    }

    private fun model() = context.assets.open("models/person_model_v11_runtime.json").use {
        HybridPersonModelJson.read(it)
    }

    /**
     * NOTHING CHANGES FOR AN INSTALL THAT NEVER FILLED IT IN, and it has to be
     * exact: two integration tests pin the bundled artifact's contract to the
     * digit, and a round trip through an implied weight once returned a value
     * one ulp away from the constant.
     */
    @Test fun `with no weight and no override the prior is the shipped constant`() {
        assertEquals(
            0.165,
            PhysioRuntime.foodDynamicsGlobalPriorV1().median,
            0.0,
        )
    }

    @Test fun `the weight the user entered replaces the population constant`() {
        val heavy = PhysioRuntime.foodDynamicsGlobalPriorV1(weightKg = 100.0)
        val light = PhysioRuntime.foodDynamicsGlobalPriorV1(weightKg = 55.0)

        assertEquals(CarbSensitivityPriorV1.fromWeight(100.0), heavy.median, 1e-12)
        assertEquals(CarbSensitivityPriorV1.fromWeight(55.0), light.median, 1e-12)
        assertTrue("a larger body must rise less per gram", heavy.median < light.median)
        // The band is rescaled, not re-derived: nothing was measured about the
        // spread, so it keeps its previous width relative to the median.
        val reference = PhysioRuntime.foodDynamicsGlobalPriorV1()
        assertEquals(
            reference.p10 / reference.median,
            heavy.p10 / heavy.median,
            1e-12,
        )
        assertEquals(
            reference.p90 / reference.median,
            light.p90 / light.median,
            1e-12,
        )
    }

    /** Manual beats weight beats the default, in that order and in one place. */
    @Test fun `a hand set override outranks the weight derived prior`() {
        val override = 0.21
        assertNotEquals(override, CarbSensitivityPriorV1.fromWeight(100.0), 1e-6)
        assertEquals(
            override,
            PhysioRuntime.foodDynamicsGlobalPriorV1(weightKg = 100.0, overrideMmolPerG = override).median,
            1e-12,
        )
        assertEquals(
            "and it outranks the default just as plainly",
            override,
            PhysioRuntime.foodDynamicsGlobalPriorV1(overrideMmolPerG = override).median,
            1e-12,
        )
    }

    /**
     * `Settings.carbSensOverrideMmolPerG` can never answer null — its default
     * IS the shipped constant — so it cannot be the first tier of a chain. The
     * stored reader is what tells a value somebody chose from the value every
     * install reads, and without that distinction the weight would never win.
     */
    @Test fun `the stored override is absent until an install actually wrote one`() {
        assertNull(Settings.storedCarbSensOverrideMmolPerG(context))
        assertEquals(
            CARB_SENS_OVERRIDE_DEFAULT,
            requireNotNull(Settings.carbSensOverrideMmolPerG(context)),
            1e-6,
        )
        prefs().edit().putFloat("carb_sens_override_mmol_per_g", 0.21f).apply()
        assertEquals(0.21, requireNotNull(Settings.storedCarbSensOverrideMmolPerG(context)), 1e-6)
    }

    /**
     * THE CACHE KEY, which is the half that makes the field feel alive. The
     * artifact is cached per model-input revision; without the weight in that
     * key, typing a weight would leave the previous food amplitude serving for
     * the rest of the process and the field would look inert.
     */
    @Test fun `the artifact cache does not outlive a weight change`() {
        val name = "weight-cs-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            PhysioForecastRegistry.install(model(), "weight-cs-test")
            val now = System.currentTimeMillis()

            val before = PhysioRuntime.artifact(store, now)
            assertNotNull(before)
            assertEquals(
                "with no weight the artifact keeps the shipped constant",
                0.165, before!!.globalCs.median, 0.0,
            )

            Settings.setWeightKg(context, 100.0)
            val after = PhysioRuntime.artifact(store, now)
            assertNotNull(after)
            assertEquals(
                CarbSensitivityPriorV1.fromWeight(100.0),
                after!!.globalCs.median,
                1e-6,
            )
            assertNotEquals(
                "a cache keyed without the weight would serve the old amplitude",
                before.globalCs.median, after.globalCs.median,
            )
            // And it reaches the model the forecast runs, not just the posterior:
            // `personModelAt` is what `Forecaster` hands to the engine.
            assertEquals(
                after.globalCs.median,
                after.personModelAt(12.0).food.globalFactor,
                1e-12,
            )
        }
    }

    /**
     * The posterior is required to stay inside `PhysioBoundsV1`, and the
     * plausible weight range keeps the scaled band there at both ends — 35 kg
     * and 200 kg are the extremes the prior itself clamps to.
     */
    @Test fun `the weight derived posterior stays inside the artifact's bounds`() {
        val bounds = com.diapilot.core.physio.PhysioBoundsV1()
        listOf(35.0, 70.0, 200.0).forEach { kg ->
            val p = PhysioRuntime.foodDynamicsGlobalPriorV1(weightKg = kg)
            assertTrue("$kg kg: p10 ${p.p10} under the bound", p.p10 >= bounds.globalCsMmolPerLGMin)
            assertTrue("$kg kg: p90 ${p.p90} over the bound", p.p90 <= bounds.globalCsMmolPerLGMax)
        }
    }
}
