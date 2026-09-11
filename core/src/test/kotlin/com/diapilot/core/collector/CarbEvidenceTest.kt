package com.diapilot.core.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbEvidenceTest {
    private val amount = CarbUncertaintyV1("test_amount")
    private val timing = CarbUncertaintyV1("test_timing")

    @Test fun labelPer100AndServingArithmeticAreDeterministic() {
        val per100 = CarbEvidenceInputV1(
            CarbEvidenceSourceV1.LABEL_WEIGHT, true,
            labelCarbsPer100g = 24.0, weighedEdibleG = 125.0,
            amountUncertainty = amount, timingUncertainty = timing,
        ).validated()
        assertEquals(30.0, per100.totalCarbsG!!, 1e-12)
        val serving = CarbEvidenceInputV1(
            CarbEvidenceSourceV1.LABEL_WEIGHT, true,
            labelCarbsPerServingG = 18.0, servings = 1.5,
            amountUncertainty = amount, timingUncertainty = timing,
        ).validated()
        assertEquals(27.0, serving.totalCarbsG!!, 1e-12)
    }

    @Test fun labelRequiresConfirmationAndExactlyOneRecomputablePath() {
        assertThrows(IllegalArgumentException::class.java) {
            CarbEvidenceInputV1(
                CarbEvidenceSourceV1.LABEL_WEIGHT, false,
                labelCarbsPer100g = 10.0, weighedEdibleG = 100.0,
                amountUncertainty = amount, timingUncertainty = timing,
            ).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarbEvidenceInputV1(
                CarbEvidenceSourceV1.LABEL_WEIGHT, true, totalCarbsG = 20.0,
                amountUncertainty = amount, timingUncertainty = timing,
            ).validated()
        }
    }

    @Test fun recipeUsesVersionedWeightOrFractionAndRejectsImpossibleValues() {
        val recipe = CarbEvidenceInputV1(
            CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT, true,
            recipeVersion = "smoothie-v1", recipeTotalCarbsG = 48.0,
            recipeTotalWeightG = 240.0, recipeConsumedWeightG = 200.0,
            amountUncertainty = amount, timingUncertainty = timing,
        ).validated()
        assertEquals(40.0, recipe.totalCarbsG!!, 1e-12)
        assertThrows(IllegalArgumentException::class.java) { recipe.copy(recipeConsumedWeightG = Double.NaN).validated() }
        assertThrows(IllegalArgumentException::class.java) { recipe.copy(recipeConsumedWeightG = 1200.0).validated() }
        assertThrows(IllegalArgumentException::class.java) {
            validateRecipeRevisionV1(recipe, recipe.copy(recipeTotalCarbsG = 50.0))
        }
        validateRecipeRevisionV1(recipe, recipe.copy(recipeVersion = "smoothie-v2", recipeTotalCarbsG = 50.0))
    }

    @Test fun llmPointCannotSelfPromoteToMeasuredSource() {
        val llm = CarbEvidenceInputV1(
            CarbEvidenceSourceV1.PHOTO_LLM, false, totalCarbsG = 42.0,
            amountUncertainty = amount, timingUncertainty = timing,
        ).validated()
        assertEquals(CarbEvidenceSourceV1.PHOTO_LLM, llm.source)
        assertThrows(IllegalArgumentException::class.java) {
            llm.copy(source = CarbEvidenceSourceV1.LABEL_WEIGHT, userConfirmed = true).validated()
        }
    }

    @Test fun knownAtSelectionHonorsBackdateRevisionAndTombstone() {
        val input = CarbEvidenceInputV1(
            CarbEvidenceSourceV1.USER_ESTIMATE, true, totalCarbsG = 40.0,
            amountUncertainty = amount, timingUncertainty = timing,
        )
        val first = evidence(1, null, event = 1_000, known = 2_000, input = input)
        val edited = evidence(2, 1, event = 500, known = 4_000, input = input.copy(totalCarbsG = 44.0))
        val deleted = evidence(3, 2, event = 500, known = 6_000, input = edited.input, deleted = true)
        assertNull(selectCarbEvidenceKnownAtV1(listOf(first, edited, deleted), 1_999))
        assertEquals(1, selectCarbEvidenceKnownAtV1(listOf(first, edited, deleted), 3_000)?.revision)
        assertEquals(2, selectCarbEvidenceKnownAtV1(listOf(first, edited, deleted), 5_000)?.revision)
        assertNull(selectCarbEvidenceKnownAtV1(listOf(first, edited, deleted), 7_000))
        assertEquals(4_000, edited.knownAtMs) // backdated event did not backdate knowledge
    }

    @Test fun canonicalPayloadSeparatesTimingAmountAndContainsNoMealText() {
        val input = CarbEvidenceInputV1(
            CarbEvidenceSourceV1.LABEL_WEIGHT, true,
            labelCarbsPer100g = 30.0, weighedEdibleG = 100.0, intakeDurationMin = 30.0,
            amountUncertainty = amount, timingUncertainty = timing, alcoholPresent = true,
        )
        val json = evidence(1, null, 1_000, 2_000, input).canonicalJson()
        assertTrue(json.contains("amount_uncertainty"))
        assertTrue(json.contains("timing_uncertainty"))
        assertTrue(json.contains("alcohol_present\":true"))
        assertFalse(json.contains("пиво"))
        assertFalse(json.contains("clean_cs"))
        assertEquals(json, CarbEvidenceV1.parseCanonicalJson(json).canonicalJson())
    }

    private fun evidence(
        revision: Int, supersedes: Int?, event: Long, known: Long,
        input: CarbEvidenceInputV1, deleted: Boolean = false,
    ) = CarbEvidenceV1(
        "carb:7", revision, supersedes, 7, "annotation:7", event, event,
        event + ((input.intakeDurationMin ?: 0.0) * 60_000).toLong(), known, known,
        input.validated(), deleted,
    )
}
