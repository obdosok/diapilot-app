package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.hybrid.CarbAppearancePolicyV1
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * THE UPGRADE MUST NOT INHERIT A DECOMPOSITION FROM THE OLD PHYSIOLOGY.
 *
 * Review finding: Stage9 switched to the caloric queue
 * with sieving while the disk cache version still said v11, so receipts
 * already on disk — built on the flat 30 g/h — could restore
 * after the update and be shown as current. The history would then be a silent
 * mixture of two physiologies, which is worse than either one alone: nothing in
 * a mixed corpus says which row belongs to which model.
 *
 * Two tests of different kinds, because the version string is only half the
 * protection: the stored file must be REJECTED, and the version must be DERIVED
 * from the policy so that retuning the sieving invalidates on its own — the
 * half that survives somebody forgetting to bump a constant, which is precisely
 * what happened here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearancePolicyUpgradeTest {

    private val diskFile = "stage10_history_receipts.json"

    private fun clean(context: android.content.Context) {
        File(context.filesDir, diskFile).delete()
        FoodCalculationRegistry.resetEpisodeStateForTest()
    }

    @Test
    fun `a receipt file written by the previous physiology is refused`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        clean(context)
        val explanation = EpisodeAttributionExplanationV1(
            "v-old", "p", "a", "f", "o", "x", "r", "k", "d", "low", "c",
        )
        FoodCalculationRegistry.updateEpisodeAttribution(
            mapOf(77L to explanation), generation = 1, processedClusters = 3, totalClusters = 3,
        )
        assertTrue(FoodCalculationRegistry.persist(context, "source-A"))

        // Rewrite exactly what the phone holds today: the same receipts under
        // the version string that shipped before the queue.
        val stored = JSONObject(File(context.filesDir, diskFile).readText())
        stored.put("algorithmVersion", "time-resolved-attribution-v11-rescue-throughput-aggregate")
        File(context.filesDir, diskFile).writeText(stored.toString())

        FoodCalculationRegistry.resetEpisodeStateForTest()
        assertFalse(
            "a 30 g/h decomposition restored as current after the upgrade",
            FoodCalculationRegistry.restore(context, "source-A"),
        )
        assertNull(FoodCalculationRegistry.getEpisode(77L))
        assertEquals(0, FoodCalculationRegistry.episodeFlow.value.receipts.size)
        clean(context)
    }

    /** A file written by THIS build still restores — otherwise the fix is just
     *  a permanent cache-buster and every cold start pays for a full recompute. */
    @Test
    fun `a receipt file written by this physiology still restores`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        clean(context)
        val explanation = EpisodeAttributionExplanationV1(
            "v-current", "p", "a", "f", "o", "x", "r", "k", "d", "low", "c",
        )
        FoodCalculationRegistry.updateEpisodeAttribution(
            mapOf(78L to explanation), generation = 1, processedClusters = 2, totalClusters = 2,
        )
        assertTrue(FoodCalculationRegistry.persist(context, "source-B"))
        FoodCalculationRegistry.resetEpisodeStateForTest()
        assertTrue(FoodCalculationRegistry.restore(context, "source-B"))
        assertEquals(explanation, FoodCalculationRegistry.getEpisode(78L))
        clean(context)
    }

    /**
     * THE VERSION IS DERIVED, NOT WRITTEN DOWN. Retuning the sieving must move
     * the cache version without anybody editing a string — the failure mode
     * that produced this whole review point.
     */
    @Test
    fun `the cache version carries the appearance policy`() {
        assertTrue(
            "the cache version does not name the policy: ${Stage9EpisodeRuntime.CACHE_VERSION}",
            Stage9EpisodeRuntime.CACHE_VERSION.endsWith(
                CarbAppearancePolicyV1.PHYSIO_SHIPPED.signature(),
            ),
        )
        // The invariant is «past the v11 generation», not «equal to v12» — the
        // literal broke on the v13 bump while the property it stood for held.
        assertFalse(
            "the version still claims v11: ${Stage9EpisodeRuntime.CACHE_VERSION}",
            Stage9EpisodeRuntime.CACHE_VERSION.contains("v11"),
        )
        assertTrue(
            "the version names no generation: ${Stage9EpisodeRuntime.CACHE_VERSION}",
            Regex("""-v(\d+)""").find(Stage9EpisodeRuntime.CACHE_VERSION)
                ?.groupValues?.get(1)?.toInt()?.let { it >= 12 } == true,
        )
        // A retuned policy would produce a different version string, so the
        // stored file above would be refused without a manual bump.
        val retuned = CarbAppearancePolicyV1.PHYSIO_SHIPPED.copy(carbSieving = 0.35)
        assertFalse(
            Stage9EpisodeRuntime.CACHE_VERSION.endsWith(retuned.signature()),
        )
    }
}
