package com.example.diapilot.data

import com.diapilot.core.hybrid.HybridCdfKnot
import com.diapilot.core.physio.InsulinShapeLandmarksV1
import com.diapilot.core.physio.InsulinShapeV1
import com.diapilot.core.physio.PersonalInsulinCurveV1
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsulinCurveRuntimeTest {
    private fun base() = ApplicationProvider.getApplicationContext<android.content.Context>()
        .assets.open("models/person_model_v11_runtime.json").use(HybridPersonModelJson::read)

    @Test fun `persisted out of bounds measured curve fails closed instead of throwing`() {
        val invalid = PersonalInsulinCurveV1(
            listOf(
                HybridCdfKnot(0.0, 0.0), HybridCdfKnot(100.0, .2),
                HybridCdfKnot(200.0, .9), HybridCdfKnot(105.0, 1.0),
            ),
            4, 3, 3.2, InsulinShapeLandmarksV1(20.0, 55.0, null, 145.0),
        )
        val applied = InsulinCurveRuntime.applyWithReason(base(), invalid)
        assertEquals(base().modelVersion, applied.model.modelVersion)
        assertEquals(base().insulin, applied.model.insulin)
        // And it says which property failed. A refusal that reaches the model as
        // «nothing happened» is how a built curve fails to reach the forecast
        // with nobody the wiser — which is exactly what the screen caught.
        assertNotNull("a refusal must carry its reason", applied.refusal)
        assertTrue(applied.refusal!!, applied.refusal.contains("monoton"))
    }

    /**
     * Anything the profile runtime can build must install. The two used to
     * derive onset and peak by different rules, so a curve could pass one bounds
     * check and be refused by the next in silence.
     */
    @Test fun `a curve built on coerced landmarks is always accepted`() {
        listOf(
            InsulinShapeLandmarksV1(11.0, 39.0, 69.0, 103.0),
            InsulinShapeLandmarksV1(21.0, 50.0, 70.0, 141.0),
            InsulinShapeLandmarksV1(5.0, 25.0, null, 95.0),
            InsulinShapeLandmarksV1(45.0, 170.0, null, 560.0),
        ).forEach { measured ->
            val (target, _) = InsulinShapeV1.coerceIntoDomain(measured)
            val knots = InsulinShapeV1.synthesize(target) ?: return@forEach
            val curve = PersonalInsulinCurveV1(knots, 8, 5, 8.0, target)
            val applied = InsulinCurveRuntime.applyWithReason(base(), curve)
            assertNull("$measured refused: ${applied.refusal}", applied.refusal)
            assertEquals(knots, applied.model.insulin.actionCdfKnots)
        }
    }

    /** Below the support floor the prior stays, and says why. */
    @Test fun `a thin curve leaves the prior in place with a reason`() {
        val target = InsulinShapeLandmarksV1(15.0, 45.0, 70.0, 140.0)
        val knots = checkNotNull(InsulinShapeV1.synthesize(target))
        val thin = PersonalInsulinCurveV1(knots, 2, 1, 2.0, target)
        val applied = InsulinCurveRuntime.applyWithReason(base(), thin)
        assertEquals(base().insulin, applied.model.insulin)
        assertTrue(applied.refusal!!, applied.refusal.contains("observations"))
    }
}
