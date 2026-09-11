package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-05 layers 2 and 3: free-text matching with confirmation, and aliases that
 * grow from confirmations.
 *
 * The measured boundary this respects: a name as an AUTOMATIC key is rejected
 * (28 smoothies under three titles, 21% lost), a name as a CONFIRMED hint is
 * not — the error stops being silent. So [DishRecognitionV1.candidate] may only
 * ever produce a QUESTION, and structure is written exclusively through the
 * exact-alias tier, which contains nothing the user has not confirmed.
 */
class DishRecognitionV1Test {

    private fun dish(
        id: String,
        title: String,
        aliases: List<String>,
        intakes: Int,
        carbs: Double? = null,
    ) = DishRecognitionV1.KnownDish(
        FoodStructureAcceptanceV1.Proposed(
            id = id, title = title, aliases = aliases,
            form = FoodPhysicalFormV2.LIQUID, fast = .8, medium = .15, slow = .05,
            fiberG = null, proteinG = 2.0, fatG = 1.0, confidence = .6,
        ),
        intakes = intakes, typicalCarbsG = carbs,
    )

    private val smoothie = dish(
        "smoothie", "смузи (апельсиновый сок + петрушка)",
        listOf("смузи с апельсиновым соком и петрушкой", "смузи", "стакан смузи"),
        intakes = 28, carbs = 22.0,
    )
    private val beer = dish("beer", "пиво", listOf("пиво", "бутылка короны"), intakes = 34, carbs = 18.0)
    private val beerChips = dish("beer_chips", "пиво и чипсы", listOf("пиво и чипсы", "пиво чипсы"), intakes = 5, carbs = 34.0)
    private val pancakes = dish("pancakes_plain", "блины простые (2 шт)", listOf("2 блина", "2 блина, каждый 75гр"), intakes = 3, carbs = 50.0)
    private val all = listOf(smoothie, beer, beerChips, pancakes)

    // ---- Layer 2: a wording OUTSIDE the aliases yields a question, never a write

    @Test
    fun `a new wording is a candidate, not a match`() {
        val q = "обычный смузи"
        // not an alias → nothing may be written silently
        assertNull(DishRecognitionV1.exactAlias(q, all))
        assertTrue(all.none { FoodStructureAcceptanceV1.matches(it.proposed, q) })
        // but the dish is recognisable enough to ASK about
        val c = assertNotNull2(DishRecognitionV1.candidate(q, all))
        assertEquals("smoothie", c.dish.proposed.id)
    }

    @Test
    fun `the question carries the two facts that make the answer informed`() {
        val c = assertNotNull2(DishRecognitionV1.candidate("обычный смузи", all))
        val q = DishRecognitionV1.question(c)
        assertEquals("missing the repeat count: $q", 28, q.intakes)
        assertEquals("missing the usual grams: $q", 22.0, q.typicalCarbsG!!, 0.5)
        assertEquals("asks about the dish itself: $q", c.dish.proposed.title, q.title)
    }

    @Test
    fun `bare beer matches beer, not the beer-and-chips composite`() {
        assertEquals("beer", DishRecognitionV1.exactAlias("пиво", all)?.proposed?.id)
        // "pivko" (beer, informal) shares only three letters with "pivo" (beer) — below the stem
        // floor, so no candidate at all (a wrong match is worse than none).
        assertNull(DishRecognitionV1.candidate("пивко", all))
    }

    @Test
    fun `coverage prefers the plain dish for a plain wording`() {
        val c = assertNotNull2(DishRecognitionV1.candidate("блины", all))
        assertEquals("pancakes_plain", c.dish.proposed.id)
    }

    @Test
    fun `an unknown dish yields no candidate at all`() {
        assertNull(DishRecognitionV1.candidate("гречка с курицей", all))
        assertNull(DishRecognitionV1.candidate("пирог", all))
    }

    // ---- Layer 3: a confirmation ADDS the wording; the same wording then
    //      matches without a question

    @Test
    fun `after confirmation the same wording matches without a question`() {
        val wording = "обычный смузи"
        // before: question only
        assertNull(DishRecognitionV1.exactAlias(wording, all))
        // the confirmation adds the alias — this is what the app persists
        val learned = smoothie.copy(
            proposed = smoothie.proposed.copy(aliases = smoothie.proposed.aliases + wording),
        )
        val after = listOf(learned, beer, beerChips, pancakes)
        assertEquals("smoothie", DishRecognitionV1.exactAlias(wording, after)?.proposed?.id)
        // and the acceptance matcher — the thing that actually writes the
        // structure — now recognises the wording too
        assertTrue(FoodStructureAcceptanceV1.matches(learned.proposed, wording))
    }

    @Test
    fun `stop and weight words do not block the exact tier`() {
        // "a glass of smoothie" is already an alias; the same phrase with "250ml" differs
        // only by a weight token and must land on the same dish
        assertEquals("smoothie", DishRecognitionV1.exactAlias("стакан смузи 250мл", all)?.proposed?.id)
    }

    private fun <T> assertNotNull2(x: T?): T {
        assertNotNull(x)
        return x!!
    }
}
