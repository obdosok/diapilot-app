package com.example.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.hybrid.CarbTrianglesV1
import com.diapilot.core.hybrid.HybridPersonModel
import com.diapilot.core.hybrid.HybridPersonModelJson
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Importing a bench set of carb triangles, and the two ways it must refuse.
 *
 * The set is twelve numbers that only mean anything together, so the failure to
 * guard against is a HALF application: eight numbers parsed, four left shipped,
 * and a food shape nobody has ever measured. The second guard is redundancy —
 * `carbSpread` collapses fast and slow toward medium, which is a statement about
 * these same twelve, and the bench's fitter locks it the moment a triangle axis
 * is free. The applier has to do the same or the two can be set against each
 * other by hand with nothing on screen to say so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarbTriangleImportTest {

    private fun shipped(): HybridPersonModel =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .assets.open("models/person_model_v11_runtime.json")
            .use { HybridPersonModelJson.read(it) }

    private val benchExport = """
        {"unit":"mmol","hours":8,
         "triangles":{"fastDelay":5,"fastPeak":19,"fastEnd":74,"fastMacroShare":0.45,
                      "medDelay":10,"medPeak":33,"medEnd":91,"medMacroShare":0.48,
                      "slowDelay":15,"slowPeak":63,"slowEnd":326,"slowMacroShare":1},
         "foods":[]}
    """.trimIndent()

    @Test
    fun `a bench export yields the twelve numbers in card order`() {
        val tri = PhysioTuning.trianglesFromBenchExport(benchExport)
        assertEquals(
            listOf(5.0, 19.0, 74.0, 0.45, 10.0, 33.0, 91.0, 0.48, 15.0, 63.0, 326.0, 1.0),
            tri,
        )
    }

    /** A truncated paste must yield nothing rather than a partial shape. */
    @Test
    fun `a short set is refused whole`() {
        val short = """{"triangles":{"fastDelay":5,"fastPeak":19,"fastEnd":74}}"""
        assertNull(PhysioTuning.trianglesFromBenchExport(short))
    }

    @Test
    fun `rubbish is refused rather than thrown`() {
        assertNull(PhysioTuning.trianglesFromBenchExport("не json"))
        assertNull(PhysioTuning.trianglesFromBenchExport("{}"))
    }

    /**
     * THE REDUNDANCY GUARD. With a set applied, `carbSpread` must arrive at the
     * model as neutral whatever the user left in that field — otherwise the two
     * controls describe the same twelve numbers and fight.
     */
    @Test
    fun `an applied set neutralises the spread override`() {
        val model = shipped()
        val tuned = PhysioTuning.apply(
            model,
            PhysioTuning.Values(
                carbSpread = 0.4,
                carbTriangles = listOf(5.0, 19.0, 74.0, 0.45, 10.0, 33.0, 91.0, 0.48, 15.0, 63.0, 326.0, 1.0),
            ),
        )
        assertEquals(1.0, tuned.food.carbSpreadOverride!!, 1e-9)
        assertEquals(33.0, tuned.food.carbTrianglesOverride!!.mediumPeakMin, 1e-9)
    }

    /** Without a set, the spread the user asked for must survive untouched. */
    @Test
    fun `without a set the spread is left alone`() {
        val tuned = PhysioTuning.apply(
            shipped(),
            PhysioTuning.Values(carbSpread = 0.4),
        )
        assertEquals(0.4, tuned.food.carbSpreadOverride!!, 1e-9)
        assertNull(tuned.food.carbTrianglesOverride)
    }

    /**
     * The identity must MOVE with the triangles, or every paired run in
     * `physio_parallel_runs` averages two food models. This is the general
     * version of the defect the shipped generation string fixed for code
     * changes — a user-set shape needs the VALUES in the identity, not a version.
     */
    @Test
    fun `the tuning identity distinguishes two different sets`() {
        val a = PhysioTuning.Values(
            carbTriangles = listOf(5.0, 19.0, 74.0, 0.45, 10.0, 33.0, 91.0, 0.48, 15.0, 63.0, 326.0, 1.0),
        )
        val b = PhysioTuning.Values(
            carbTriangles = listOf(5.0, 25.0, 75.0, 0.45, 10.0, 55.0, 180.0, 0.75, 15.0, 95.0, 330.0, 1.0),
        )
        assertNotEquals(PhysioTuning.identity(a), PhysioTuning.identity(b))
        assertTrue(PhysioTuning.identity(a) != PhysioTuning.identity(PhysioTuning.Values()))
    }

    /**
     * And it must move for the four knobs that were invisible to it before:
     * kcal, sieve, spread and the ramp all live outside `mechanics.insulin` and
     * `food.defaultShape`, which is all the artifact id used to carry.
     */
    @Test
    fun `the tuning identity distinguishes the previously invisible knobs`() {
        val base = PhysioTuning.Values()
        listOf(
            PhysioTuning.Values(emptyingKcalPerHour = 173.0),
            PhysioTuning.Values(carbSieving = 0.4),
            PhysioTuning.Values(carbSpread = 0.5),
            PhysioTuning.Values(trustRamp = 0.0),
        ).forEach {
            assertNotEquals(
                "this knob does not reach the artifact identity",
                PhysioTuning.identity(base), PhysioTuning.identity(it),
            )
        }
    }

    /** Defaults must still be the shipped triangles when nothing is imported. */
    @Test
    fun `no import leaves the shipped shape`() {
        assertEquals(33.0, CarbTrianglesV1().mediumPeakMin, 1e-9)
        assertNull(PhysioTuning.Values().carbTriangles)
    }
}
