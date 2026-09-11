package io.github.obdosok.diapilot.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-05 layer 2: the known-dishes list REALLY reaches the vision request —
 * asserted on the request assembly, not on a model's answer.
 *
 * Why this test exists: the photo path saw none of the user's history. The
 * personal block was built from the TEXT query, and a captionless photo has
 * no text (`q.length < 3` → null) — so a frequently eaten dish was parsed
 * from scratch every time. The list travels in two places, both pinned here:
 * the message text (so the model can compare) and the tool schema's enum (so
 * the answer cannot be a free-text id).
 */
class FoodVisionRecognitionTest {

    private val dishes = listOf(
        AskClaude.KnownDishForVision("smoothie", "смузи (апельсиновый сок + петрушка)", 22.0),
        AskClaude.KnownDishForVision("pancakes_ham_cheese", "блины с сыром и ветчиной (2 шт)", 50.0),
    )

    private fun body(): JSONObject =
        AskClaude.visionRequestBody("QkFTRTY0", caption = null, personalContext = null, knownDishes = dishes)

    private fun messageText(body: JSONObject): String {
        val content = body.getJSONArray("messages").getJSONObject(0).get("content") as JSONArray
        return (0 until content.length())
            .mapNotNull { content.optJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("\n") { it.getString("text") }
    }

    private fun schema(body: JSONObject): JSONObject =
        body.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema")
            .getJSONObject("properties")

    @Test
    fun `the dish list travels in the message text`() {
        val text = messageText(body())
        assertTrue("no \"which of these, or none\" question", "which of these dishes is in the photo, or none" in text)
        assertTrue("no smoothie id", "id=smoothie" in text)
        assertTrue("no pancakes id", "id=pancakes_ham_cheese" in text)
        assertTrue("no typical portion", "usually 22 g" in text)
        // recognition ≠ re-estimation: the canon owns the composition
        assertTrue("does not say the composition of a known dish is not re-estimated", "do NOT re-estimate the composition" in text)
    }

    @Test
    fun `the schema enum pins the answer to the known dishes plus none`() {
        val props = schema(body())
        val enum = props.getJSONObject("known_dish_id").getJSONArray("enum")
        val values = (0 until enum.length()).map(enum::getString)
        assertEquals(listOf("smoothie", "pancakes_ham_cheese", "none"), values)
        assertTrue(props.has("known_dish_portion"))
    }

    @Test
    fun `without known dishes the schema stays as it was`() {
        val b = AskClaude.visionRequestBody("QkFTRTY0", null, null, emptyList())
        val props = schema(b)
        assertFalse(props.has("known_dish_id"))
        assertFalse(props.has("known_dish_portion"))
        assertFalse("an empty block must not land in the text", "KNOWN DISHES" in messageText(b))
    }

    @Test
    fun `the request still carries the image and the forced tool choice`() {
        val b = body()
        val content = b.getJSONArray("messages").getJSONObject(0).get("content") as JSONArray
        assertTrue(
            (0 until content.length()).any { content.getJSONObject(it).optString("type") == "image" },
        )
        assertEquals("food_analysis", b.getJSONObject("tool_choice").getString("name"))
    }
}
