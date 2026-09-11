package com.example.diapilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProgressiveFoodAssetTest {
    /**
     * THE DISH DICTIONARY MUST STAY OUT OF THE ARTIFACT.
     *
     * This used to assert the opposite — that the bundled model carries twelve
     * learned whole-dish profiles, with a few common dish stems among
     * them. They were deleted with the legacy arm, and the
     * assertion is INVERTED rather than dropped: an export that put per-dish
     * coefficients back into the artifact would reintroduce name-based
     * amplitude without touching a line of engine code, and this is the only
     * place that would notice.
     */
    @Test
    fun `bundled model carries no per dish coefficients`() {
        val text = File("src/main/assets/models/person_model_v11_runtime.json")
            .readText(Charsets.UTF_8)
        listOf(
            "tier", "group_factors", "component_factors", "component_shapes",
            "promoted_prototypes", "progressive_profiles", "group_shapes",
            "group_tails", "component_tails", "similarity_threshold",
        ).forEach {
            assertTrue("the artifact still carries \"$it\"", !text.contains("\"$it\""))
        }
    }

    @Test
    fun `bundled history sidecar contains retrospective observations`() {
        val text = File("src/main/assets/models/food_episode_observations_v11.json")
            .readText(Charsets.UTF_8)
        assertTrue(text.contains("\"schema_version\": 1"))
        assertEquals(166, Regex("\\\"ts_ms\\\"\\s*:").findAll(text).count())
    }
}
