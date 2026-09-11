package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mutation-checked. The property that matters is that a backfill ADDS: the
 * user's whole record of past numbers was computed against the old analysis
 * text, and a rewrite would make every earlier measurement unreproducible.
 */
class FoodStructureAcceptanceV1Test {

    private val smoothie = FoodStructureAcceptanceV1.Proposed(
        id = "smoothie", title = "смузи",
        aliases = listOf("смузи с апельсиновым соком и петрушкой", "смузи", "стакан смузи"),
        form = FoodPhysicalFormV2.LIQUID, fast = .80, medium = .15, slow = .05,
        fiberG = 0.5, proteinG = 2.0, fatG = 1.0, confidence = .7,
    )

    @Test
    fun `an alias matches however the user happened to name it`() {
        assertTrue(FoodStructureAcceptanceV1.matches(smoothie, "Смузи с апельсиновым соком и петрушкой"))
        assertTrue(FoodStructureAcceptanceV1.matches(smoothie, "стакан смузи"))
        assertTrue(FoodStructureAcceptanceV1.matches(smoothie, "  смузи  "))
        assertFalse(FoodStructureAcceptanceV1.matches(smoothie, "смузи и хумус"))
    }

    @Test
    fun `acceptance APPENDS — the prior analysis survives verbatim`() {
        val before = "НАЗВАНИЕ: смузи\nБЕЛКИ: 2 г\nЖИРЫ: 1 г"
        val after = FoodStructureAcceptanceV1.analysisAfterAccept(smoothie, before)!!
        assertTrue("the earlier text was destroyed", after.startsWith(before))
        assertTrue(after.lineSequence().any { it.startsWith("KINETICS_V2:") })
    }

    @Test
    fun `the appended line is what the model then reads`() {
        val after = FoodStructureAcceptanceV1.analysisAfterAccept(smoothie, "БЕЛКИ: 2 г")!!
        val parsed = parseFoodKineticsV2(after, 2.0, 1.0)
        assertEquals(FoodPhysicalFormV2.LIQUID, parsed.physicalForm)
        assertEquals(0.80, parsed.normalized().fastFraction, 1e-6)
        // The provenance carries WHICH dish, not just "accepted" — see F-04.
        assertEquals(FoodStructureAcceptanceV1.acceptedProvenance("smoothie"), parsed.provenance)
    }

    /** An older structured line must LOSE to the accepted one, and still be there. */
    @Test
    fun `an accepted line supersedes an earlier structure without deleting it`() {
        val old = foodKineticsLineV2(
            FoodKineticFeaturesV2(.20, .60, .20, FoodPhysicalFormV2.UNKNOWN, provenance = "macro-neutral-v2"),
        )
        val after = FoodStructureAcceptanceV1.analysisAfterAccept(smoothie, old)!!
        assertTrue("the superseded line was removed", after.contains("macro-neutral-v2"))
        assertEquals(
            FoodStructureAcceptanceV1.acceptedProvenance("smoothie"),
            parseFoodKineticsV2(after).provenance,
        )
    }

    @Test
    fun `a second tap changes nothing`() {
        val once = FoodStructureAcceptanceV1.analysisAfterAccept(smoothie, "БЕЛКИ: 2 г")!!
        assertNull("re-accepting duplicated the line", FoodStructureAcceptanceV1.analysisAfterAccept(smoothie, once))
    }

    @Test
    fun `a duration the user actually recorded is never replaced by the median`() {
        val beer = smoothie.copy(id = "beer", aliases = listOf("пиво"), intakeDurationMin = 30.0, alcohol = true)
        val recorded = "META_DURATION_MIN: 90.0"
        val after = FoodStructureAcceptanceV1.analysisAfterAccept(beer, recorded)!!
        assertEquals(
            "the user's own recorded duration was overwritten",
            1, after.lineSequence().count { it.trim().startsWith("META_DURATION_MIN:") },
        )
        assertTrue(after.contains("META_DURATION_MIN: 90.0"))
        assertTrue("alcohol was not recorded", parseAlcoholPresentV2(after))
    }

    /**
     * F-04: the accepted dish must identify itself, at ANY portion.
     *
     * A live check found the same accepted savoury pancake at two different
     * carbohydrate amounts keyed as different macro coordinates, because
     * [DishIdentityV1]'s macro coordinates are ratios to the note's own carbs.
     * A small difference in recorded grams meant three occasions of the same
     * dish pooled as two dishes plus one, missing the n>=3 bar by exactly one
     * episode.
     */
    @Test
    fun `an accepted dish keeps one identity across portions`() {
        val after = FoodStructureAcceptanceV1.analysisAfterAccept(smoothie, null)!!
        assertEquals("smoothie", FoodStructureAcceptanceV1.acceptedDishId(after))
        // A note with no accepted line has no dish id and falls back to ratios.
        assertEquals(null, FoodStructureAcceptanceV1.acceptedDishId("БЕЛКИ: 2 г"))
        // And a line from before F-04 is deliberately NOT accepted, so one more
        // tap re-applies it with the id.
        val legacy = foodKineticsLineV2(
            FoodKineticFeaturesV2(
                .8, .15, .05, FoodPhysicalFormV2.LIQUID,
                provenance = FoodStructureAcceptanceV1.ACCEPTED_PROVENANCE,
            ),
        )
        assertTrue(
            "a pre-F-04 accepted line must be re-appliable",
            !FoodStructureAcceptanceV1.isAccepted(legacy),
        )
    }

    /**
     * The counter and the write must ask the SAME question.
     *
     * `analysisAfterAccept` was made idempotent by content while the
     * screen's "already accepted" count still went by tag, so recomputed
     * dishes showed a "nothing to change" state and a disabled button. The
     * user tapped and nothing happened — the fix had shipped and could not
     * be reached.
     */
    @Test
    fun `a revised proposal counts as pending, not as already accepted`() {
        val applied = FoodStructureAcceptanceV1.analysisAfterAccept(smoothie, null)!!
        assertTrue(
            "the same proposal must read as carried",
            FoodStructureAcceptanceV1.carriesStructure(smoothie, applied),
        )
        val revised = smoothie.copy(fast = .60, medium = .30, slow = .10)
        assertTrue(
            "a REVISED proposal must not read as already carried",
            !FoodStructureAcceptanceV1.carriesStructure(revised, applied),
        )
        assertTrue(
            "and it must actually rewrite",
            FoodStructureAcceptanceV1.analysisAfterAccept(revised, applied) != null,
        )
        // Tag-level acceptance still holds — that is the generation check.
        assertTrue(FoodStructureAcceptanceV1.isAccepted(applied))
    }
}
