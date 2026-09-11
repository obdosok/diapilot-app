package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The concepts added to close the dictionary's holes, and the collisions they
 * could have caused. Every name here is a plausible SOSTAV line — the shape
 * where a share of silently-lost carbs was found.
 */
class NewConceptsTest {

    @Test
    fun `nuts and seeds finally have somewhere to go`() {
        // A component that used to leave its carbs unnamed.
        assertEquals("nuts", conceptFor("фисташки")?.id)
        assertEquals("nuts", conceptFor("Семечки подсолнечника")?.id)
        assertEquals("nuts", conceptFor("грецкий орех")?.id)
    }

    @Test
    fun `sweet yogurt is FAST, kefir is not — one dairy concept would be a lie`() {
        assertEquals(CarbSpeed.FAST, conceptFor("йогурт")?.carbSpeed)
        assertEquals(CarbSpeed.MED, conceptFor("кефир")?.carbSpeed)
    }

    @Test
    fun `"kefir slash yogurt (base)" resolves — the slash and the parens are cut`() {
        // A shape the app's notes actually produce; normalizeFoodName drops
        // "(base)" and takes the part before the slash.
        assertEquals("kefir", conceptFor("Кефир/йогурт (основа)")?.id)
    }

    @Test
    fun `hazelnut inside a dish name does not turn ice cream into nuts`() {
        // "hazelnut ice cream cone" must stay ice_cream — the whole point of
        // the boundary-aware matcher.
        assertEquals("ice_cream", conceptFor("мороженое рожок с фундуком")?.id)
    }

    @Test
    fun `milk does not swallow "milk chocolate"`() {
        assertEquals("chocolate", conceptFor("молочный шоколад")?.id)
    }

    @Test
    fun `the pizza's dough is still homeless — that one needs an ALIAS, not a concept`() {
        // Vision calls it "base". 55 g, 89% of the dish, and no dictionary
        // entry can guess that word — this is exactly what concept_aliases is
        // for, and why the editor now says so out loud.
        assertNull(conceptFor("основа"))
        // ...and the target it should be aliased to does exist.
        assertEquals("pizza", conceptFor("пицца")?.id)
    }

    @Test
    fun `a portion of nuts prices itself without the user knowing grams`() {
        // 30 g typical × 8 g/100 g ≈ 2.4 g of carbs.
        assertEquals(2.4, carbsForPortion("nuts", 30.0)!!, 0.05)
    }

    @Test
    fun `boiled whole-grain porridge has a concept at all`() {
        // Before fp18 "spelt porridge" matched nothing, so a meal carrying most
        // of its carbs in spelt showed a few grams of onion as its only carb driver — and
        // every concept-keyed reader, including the neighbour subtraction, saw a
        // meal that had eaten almost nothing.
        assertEquals("spelt", conceptFor("каша из спельты")?.id)
        assertEquals("spelt", conceptFor("перловка")?.id)
        assertEquals("spelt", conceptFor("булгур")?.id)
    }

    @Test
    fun `"polba" stays out — a prefix alias would price half a loaf as porridge`() {
        // A single-word alias matches as a word PREFIX. "polba" would therefore
        // claim "polbatona" (5 chars beats bread's 4) and "polbanki" (5 vs 5, and
        // spelt sits earlier in the list) — and unlike a shape change, this one
        // reaches GRAMS: the composer would offer 42 g where bread wants 14.
        // Mutation check for whoever re-adds it: put "polba" back and this fails.
        assertEquals("bread", conceptFor("полбатона хлеба")?.id)
        assertEquals("hummus", conceptFor("полбанки хумуса")?.id)
        // ...and the real word still resolves, which is why the loss is acceptable.
        assertEquals("spelt", conceptFor("спельта")?.id)
    }

    @Test
    fun `a word-prefix alias is DECLENSION tolerance, not a licence to eat other words`() {
        // "med" is a prefix of "medovye", "nut" of "nutelloy" — and before the
        // suffix bound those two claimed the honey cake for sugar and Nutella for
        // legumes (opposite kinetics: FAST/high-fat vs SLOW/high-protein).
        // Mutation check: drop the `length - na.length <= 3` clause in aliasHit
        // and both of these fail.
        assertEquals("pastry", conceptFor("Медовые коржи")?.id)
        assertEquals("nutella", conceptFor("с нутеллой")?.id)
        // ...while real declension still resolves, which is what the rule is for.
        assertEquals("bread", conceptFor("хлеба")?.id)
        assertEquals("chocolate", conceptFor("шоколадная крошка")?.id)
        // ...and a word the tightened prefix rule now REJECTS ("kartof" + "elem"
        // is four letters, not an ending) is still caught by the 5-char stem step,
        // which is why truncated stub aliases did not need the looser rule.
        assertEquals("potato", conceptFor("картофелем")?.id)
    }

    @Test
    fun `a spelt portion without grams prices itself sanely`() {
        // 200 g typical × 21 g/100 g = 42 g — in line with buckwheat (20) and
        // quinoa (21), not a number invented to make an episode fit.
        assertEquals(42.0, carbsForPortion("spelt", 200.0)!!, 0.05)
    }
}
