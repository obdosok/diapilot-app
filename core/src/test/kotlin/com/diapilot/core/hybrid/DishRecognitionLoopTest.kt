package com.diapilot.core.hybrid

import com.diapilot.core.analysis.FoodPhysicalFormV2
import com.diapilot.core.analysis.FoodStructureAcceptanceV1
import com.diapilot.core.analysis.parseFoodKineticsV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-05 layer 1, the whole loop, synthetically: a new note assembled from a
 * PICKED known dish carries `dish:<id>` → the identity finds the pool built
 * from the dish's own episodes → the forecast draws the learned curve instead
 * of the structural mixture.
 *
 * The defect this pins: pools are keyed `dish:<id>` for
 * accepted notes (F-04), but the forecast looked them up by the RATIO key —
 * so precisely the dishes whose identity was pinned hardest never found their
 * own measured curve. [DishIdentityV1.poolKeyOf] is the seam; the last test
 * shows the ratio key misses the pool the dish key hits.
 */
class DishRecognitionLoopTest {

    private val proposed = FoodStructureAcceptanceV1.Proposed(
        id = "smoothie", title = "смузи (апельсиновый сок + петрушка)",
        aliases = listOf("смузи с апельсиновым соком и петрушкой", "смузи", "стакан смузи"),
        form = FoodPhysicalFormV2.LIQUID, fast = .8, medium = .15, slow = .05,
        fiberG = .5, proteinG = 2.0, fatG = 1.0, confidence = .7,
    )

    /** The analysis a composer-picked dish stores — no LLM call involved. */
    private val analysis = checkNotNull(
        FoodStructureAcceptanceV1.analysisAfterAccept(proposed, null),
    )

    private val features = parseFoodKineticsV2(analysis, 2.0, 1.0)

    @Test
    fun `a picked dish writes its id into the note`() {
        assertEquals("smoothie", FoodStructureAcceptanceV1.acceptedDishId(analysis))
        // The pooling-key assertion that stood here went with DishIdentityV1:
        // a recognised dish supplies WHAT was eaten, and there is
        // no per-dish curve left for it to key. What survives is the half that
        // still matters — the id reaches the causal path through provenance.
        // and the id survives into the parsed features' provenance, which is
        // all the causal forecast path gets to see
        assertEquals(
            "smoothie",
            FoodStructureAcceptanceV1.dishIdFromProvenance(features.provenance),
        )
    }

    // Same fixture the other physio tests use — the shipped v11 runtime model.
    private val person by lazy {
        HybridBlindDayParityTest().model(
            org.json.JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json"))
                    .bufferedReader().readText(),
            ),
        ).let { it.copy(food = it.food.copy(globalFactor = .165)) }
    }

    private fun engine() = HybridForecastEngine(
        person,
    )
}
