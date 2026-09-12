package io.github.obdosok.diapilot.data

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

    /**
     * THE EXAMPLE PERSON'S INSULIN MUST NOT BE CLAMPED ON LOAD.
     *
     * The shipped end of action was 175 minutes against a domain floor of 120,
     * so from the first minute the bundled synthetic person ran insulin that
     * finished inside the third hour and IOB read zero on it. The floor is now
     * the instrument's own reach, 240 (audit M1), and the example carries 300 —
     * a value the domain accepts as stated rather than one
     * `PhysioRuntime.buildArtifact` has to move before it can be installed.
     */
    @Test
    fun `the bundled insulin block is inside the artifact's domain`() {
        val model = File("src/main/assets/models/person_model_v11_runtime.json")
            .inputStream().use(HybridPersonModelJson::read)
        val bounds = com.diapilot.core.physio.PhysioBoundsV1()
        assertEquals(300.0, model.insulin.tailDurationMin, 0.0)
        assertEquals(200.0, model.insulin.shortDurationMin, 0.0)
        assertTrue(
            "end of action ${model.insulin.tailDurationMin} outside ${bounds.insulinTailMinRange}",
            model.insulin.tailDurationMin in bounds.insulinTailMinRange,
        )
        assertTrue(
            "the main curve must end no later than the tail",
            model.insulin.shortDurationMin <= model.insulin.tailDurationMin,
        )
    }

    @Test
    fun `bundled history sidecar contains retrospective observations`() {
        val text = File("src/main/assets/models/food_episode_observations_v11.json")
            .readText(Charsets.UTF_8)
        assertTrue(text.contains("\"schema_version\": 1"))
        assertEquals(166, Regex("\\\"ts_ms\\\"\\s*:").findAll(text).count())
    }
}
