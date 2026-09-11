package com.example.diapilot.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The barcode path feeds the LABEL+WEIGHT evidence arithmetic, so what this
 * pins is the label semantics: per-100g in, per-portion out, weight always
 * the user's own. Parsing is tested against a real-shaped OFF v2 payload — no HTTP.
 */
class OpenFoodFactsTest {

    private fun payload(): JSONObject = JSONObject(
        """
        {"status":1,"product":{
          "product_name":"Nutella","brands":"Ferrero, Some Other",
          "serving_quantity":15,"serving_quantity_unit":"g",
          "nutriments":{
            "carbohydrates_100g":57.5,"proteins_100g":6.3,
            "fat_100g":30.9,"energy-kcal_100g":539
          }}}
        """.trimIndent(),
    )

    @Test
    fun `parses the label facts per 100 g`() {
        val p = checkNotNull(OpenFoodFacts.parse("3017620422003", payload()))
        assertTrue(p.usable)
        assertEquals("Nutella", p.name)
        assertEquals("Ferrero", p.brand)
        assertEquals(57.5, p.carbsPer100g!!, 1e-9)
        assertEquals(6.3, p.proteinPer100g!!, 1e-9)
        assertEquals(30.9, p.fatPer100g!!, 1e-9)
        assertEquals(539.0, p.kcalPer100g!!, 1e-9)
        assertEquals(15.0, p.servingG!!, 1e-9)
    }

    @Test
    fun `portion macros are label times the portion weight`() {
        assertEquals(11.5, OpenFoodFacts.portion(57.5, 20.0)!!, 1e-9)
        assertEquals(107.8, OpenFoodFacts.portion(539.0, 20.0)!!, 1e-9)
        assertNull(OpenFoodFacts.portion(null, 20.0))
    }

    @Test
    fun `a product without carbs cannot drive the label arithmetic`() {
        val json = payload()
        json.getJSONObject("product").getJSONObject("nutriments").remove("carbohydrates_100g")
        val p = checkNotNull(OpenFoodFacts.parse("123", json))
        assertFalse("без углеводов на 100 г этикеточный пересчёт невозможен", p.usable)
    }

    @Test
    fun `status 0 or a non-gram serving are refused, not guessed`() {
        assertNull(OpenFoodFacts.parse("123", JSONObject("""{"status":0}""")))
        val json = payload()
        json.getJSONObject("product").put("serving_quantity_unit", "portion")
        assertNull(
            "порция не в граммах не должна молча стать весом",
            OpenFoodFacts.parse("123", json)?.servingG,
        )
    }
}
