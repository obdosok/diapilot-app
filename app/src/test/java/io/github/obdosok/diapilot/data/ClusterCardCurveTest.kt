package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.collector.Annotation
import com.diapilot.core.hybrid.HybridPersonModelJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE CARD MUST SHOW THE CURVE THAT WAS APPLIED.
 *
 * Review finding: History built every receipt from the dish's own
 * standalone curve while the forecast beside it drew that dish inside a shared
 * gastric pipe. A dessert after a fatty dish was shown a curve nobody used.
 *
 * The fixture is a FATTY first dish with a small dessert behind it, because
 * that is the only arrangement where the two curves separate: behind a lean
 * meal the pipe drains fast enough that the dessert waits for nothing, and such
 * a fixture would pass with the defect live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterCardCurveTest {

    private val kinetics =
        "KINETICS_V2: fast=.05;medium=.90;slow=.05;form=SOLID;confidence=.6;source=test;alcohol=false"

    private fun note(id: Long, tsMin: Long, text: String, carbs: Double, p: Double, f: Double) =
        Annotation(
            tsMin * 60_000, "food", text, estCarbs = carbs,
            analysis = "БЕЛКИ: $p г\nЖИРЫ: $f г\n$kinetics", id = id,
        )

    private fun readouts(notes: List<Annotation>): Map<Long, HybridFoodReadout> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = context.assets.open("models/person_model_v11_runtime.json")
            .use { HybridPersonModelJson.read(it) }
        PhysioForecastRegistry.install(model, "cluster-card-curve")
        val artifact = requireNotNull(PhysioRuntime.artifact())
        return HybridRuntimeMetrics.foodReadoutsForModel(
            artifact.personModelAt(19.0), notes, macroTiming = artifact.macroTiming, physioArtifact = artifact,
        )
    }

    @Test
    fun `a dessert behind a fatty meal is shown the curve it actually got`() {
        val pizza = note(1, 0, "пицца", 69.0, 30.0, 30.0)
        val dessert = note(2, 40, "мороженое", 26.0, 4.0, 22.0)
        val rows = readouts(listOf(pizza, dessert))
        val ice = requireNotNull(rows[2L])
        assertTrue("the dessert was not recognised as part of a meal", ice.clusterMembers > 1)
        assertNotNull("no applied curve on the card", ice.clusterHalfArrivalMin)
        assertTrue(
            "the card shows the standalone curve for a queued dish: " +
                "${ice.clusterHalfArrivalMin} vs ${ice.halfArrivalMin}",
            ice.clusterHalfArrivalMin!! > ice.halfArrivalMin + 20,
        )
        // And it must say WHY: the fatty dish was still in the stomach.
        val realised = requireNotNull(ice.clusterPriorRealised)
        assertTrue("prior realisation out of range: $realised", realised in 0.0..1.0)
        assertTrue("the fatty dish is reported as already gone at +40 min: $realised", realised < 0.5)
    }

    /**
     * "Predecessor N% absorbed" over a three-dish meal.
     *
     * SAID PLAINLY: this does NOT pin that the number comes from the applied
     * curve rather than each dish's own. Mutating `clusteredFoodCdf` to
     * `foodCdf` here fails nothing — with sieving at 0.65 the pipe is not
     * binding on these dishes at the moment the beer lands, so the two agree.
     * The implementation uses the applied curve because that is what the
     * sentence claims; no fixture I built could tell the difference, and
     * pretending otherwise in a test name would be worse than saying so.
     */
    @Test
    fun `prior realisation is reported for a three-dish meal`() {
        val notes = listOf(
            note(1, 0, "пицца", 69.0, 30.0, 30.0),
            note(2, 40, "мороженое", 26.0, 4.0, 22.0),
            note(3, 100, "пиво", 18.0, 2.0, 0.0),
        )
        val beer = requireNotNull(readouts(notes)[3L])
        val realised = requireNotNull(beer.clusterPriorRealised)
        assertTrue("prior realisation out of range: $realised", realised in 0.0..1.0)
        assertTrue("beer lands on an empty stomach after 1000 kcal: $realised", realised < 0.55)
    }

    /**
     * "PREDECESSOR ABSORBED" IS ABOUT THIS MEAL, NOT ABOUT THE DAY.
     *
     * A live check showed the card reporting the previous dish as 95%
     * absorbed beside a fatty dish eaten 113 minutes earlier, which the
     * model's own curve puts under half. The number averaged every earlier note
     * in the History batch — a lunch and an afternoon dessert, both long
     * finished — instead of the members of this meal.
     */
    @Test
    fun `prior realisation ignores meals outside the current meal`() {
        val lunch = note(9, -7 * 60, "обед", 100.0, 20.0, 20.0)
        val pizza = note(1, 0, "пицца", 69.0, 30.0, 30.0)
        val cake = note(4, 113, "торт", 35.0, 5.0, 12.0)
        val withLunch = requireNotNull(readouts(listOf(lunch, pizza, cake))[4L])
        val withoutLunch = requireNotNull(readouts(listOf(pizza, cake))[4L])
        assertEquals(
            "a meal seven hours earlier moved \"predecessor absorbed\"",
            withoutLunch.clusterPriorRealised!!,
            withLunch.clusterPriorRealised!!,
            1e-9,
        )
        assertTrue(
            "the fatty dish reads as finished 113 minutes in: ${withLunch.clusterPriorRealised}",
            withLunch.clusterPriorRealised!! < 0.75,
        )
    }

    /** A lone dish carries no cluster fields at all — so a card can never show
     *  "in this meal" for something eaten by itself. */
    @Test
    fun `a lone dish has no applied-curve line`() {
        val rows = readouts(listOf(note(1, 0, "пицца", 69.0, 30.0, 30.0)))
        val only = requireNotNull(rows[1L])
        assertEquals(1, only.clusterMembers)
        assertNull(only.clusterHalfArrivalMin)
        assertNull(only.clusterDurationMin)
        assertNull(only.clusterPriorRealised)
    }
}
