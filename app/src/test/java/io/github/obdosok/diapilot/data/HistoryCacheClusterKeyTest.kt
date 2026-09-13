package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.collector.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE CACHE MUST OWN THE MEAL, NOT ONLY THE DISH.
 *
 * Three defects found in one place, each of
 * which alone would have made the cluster card invisible in production:
 *
 *  1. the key held only the note, so adding a second dish beside a cached dish
 *     left that dish's card describing a curve that had changed;
 *  2. `compute` was handed the MISSING rows only, so a single miss was
 *     recomputed as though that dish had been eaten alone — the cluster curve
 *     could not exist even on a cold cache;
 *  3. neither the cluster fields nor `halfArrivalMin` were serialised, so
 *     whatever was computed survived until the first cache write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryCacheClusterKeyTest {

    private val kinetics =
        "KINETICS_V2: fast=.05;medium=.90;slow=.05;form=SOLID;confidence=.6;source=test;alcohol=false"

    private fun note(id: Long, tsMin: Long, text: String, carbs: Double, p: Double, f: Double) =
        Annotation(
            tsMin * 60_000, "food", text, estCarbs = carbs,
            analysis = "БЕЛКИ: $p г\nЖИРЫ: $f г\n$kinetics", id = id,
        )

    private val pizza = note(1, 0, "пицца", 69.0, 30.0, 30.0)
    private val beer = note(2, 45, "пиво", 18.0, 2.0, 0.0)

    private fun store(): SqliteCollectorStore =
        SqliteCollectorStore(ApplicationProvider.getApplicationContext())

    /** The real readouts, so the test cannot pass against a stub whose numbers
     *  never move. */
    private fun compute(notes: List<Annotation>): Map<Long, HybridFoodReadout> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = context.assets.open("models/person_model_v11_runtime.json")
            .use { com.diapilot.core.hybrid.HybridPersonModelJson.read(it) }
        PhysioForecastRegistry.install(model, "history-cache-cluster-key")
        val artifact = requireNotNull(PhysioRuntime.artifact())
        return HybridRuntimeMetrics.foodReadoutsForModel(
            artifact.personModelAt(19.0), notes, macroTiming = artifact.macroTiming, physioArtifact = artifact,
        )
    }

    @Test
    fun `adding a dish invalidates the card of the dish already cached`() {
        val store = store()
        val alone = HistoryFoodProjectionCache.getOrCompute(
            store, "model-identity-1", listOf(pizza), ::compute,
        )
        val standalone = requireNotNull(alone[1L])
        assertEquals("the dish was cached as part of a meal that did not exist", 1, standalone.clusterMembers)

        val together = HistoryFoodProjectionCache.getOrCompute(
            store, "model-identity-1", listOf(pizza, beer), ::compute,
        )
        val inCluster = requireNotNull(together[1L])
        assertTrue(
            "the dish's card was served from cache after another dish joined the meal",
            inCluster.clusterMembers > 1,
        )
        assertNotNull(inCluster.clusterHalfArrivalMin)
    }

    /**
     * A COLD MISS ON ONE ROW STILL SEES THE MEAL. This is the defect the key
     * alone would not have fixed: `compute` used to receive the missing rows
     * only, so the first computation of a queued dish had no neighbours at all.
     */
    @Test
    fun `a single missing row is computed with its neighbours`() {
        val store = store()
        HistoryFoodProjectionCache.getOrCompute(store, "model-identity-2", listOf(pizza, beer), ::compute)
        // EXACTLY ONE MISS. A neighbour's contribution depends on its time and
        // its macros, never on its title, so the neighbour signature omits the
        // text — which is what lets a pure rename miss on that row alone while
        // the other dish still hits. The first version of this test changed the
        // other dish's carbs and invalidated BOTH rows, so `compute(missing)`
        // and `compute(context)` were handed the same list and the mutation
        // survived.
        val renamed = pizza.copy(content = "пицца пепперони")
        val warmed = HistoryFoodProjectionCache.getOrCompute(
            store, "model-identity-2", listOf(renamed, beer), ::compute,
        )
        val pizzaRow = requireNotNull(warmed[1L])
        assertTrue(
            "the recomputed dish lost its meal: ${pizzaRow.clusterMembers}",
            pizzaRow.clusterMembers > 1,
        )
    }

    /** And what was computed must survive the round trip through SQLite. */
    @Test
    fun `cluster fields and half-arrival survive the cache`() {
        val store = store()
        val first = requireNotNull(
            HistoryFoodProjectionCache.getOrCompute(
                store, "model-identity-3", listOf(pizza, beer), ::compute,
            )[2L],
        )
        val cached = requireNotNull(
            HistoryFoodProjectionCache.getOrCompute(
                store, "model-identity-3", listOf(pizza, beer), ::compute,
            )[2L],
        )
        assertEquals(first.halfArrivalMin, cached.halfArrivalMin)
        assertEquals(first.clusterMembers, cached.clusterMembers)
        assertEquals(first.clusterHalfArrivalMin, cached.clusterHalfArrivalMin)
        assertEquals(first.clusterDurationMin, cached.clusterDurationMin)
        assertEquals(first.clusterPriorRealised, cached.clusterPriorRealised)
        // The model curve feeds the card's "Model/Actual" spark pair; a field
        // missing from the serialisation lives until the first cache write.
        assertEquals(first.modelCurveMmol, cached.modelCurveMmol)
        assertTrue("the model curve was not sampled at all", first.modelCurveMmol.size >= 3)
        // `half` and `peak` differ on a fatty dish, which is why the missing
        // field mattered: falling back to peak was not a harmless default.
        assertTrue(
            "half-arrival equals the peak, so this test cannot see the fallback",
            first.halfArrivalMin != first.peakMin,
        )
    }

    /** A dish eaten far from anything else keeps a lone card. */
    @Test
    fun `a distant dish is not dragged into a meal`() {
        val store = store()
        val far = note(3, 12 * 60, "смузи", 22.0, 2.0, 1.0)
        val rows = HistoryFoodProjectionCache.getOrCompute(
            store, "model-identity-4", listOf(pizza, far), ::compute,
        )
        val smoothie = requireNotNull(rows[3L])
        assertEquals(1, smoothie.clusterMembers)
        assertNull(smoothie.clusterHalfArrivalMin)
    }
}
