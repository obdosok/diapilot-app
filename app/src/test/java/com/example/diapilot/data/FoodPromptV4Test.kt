package com.example.diapilot.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-05 layers 4–5, pinned the way FoodPromptDecouplingTest pins v3: the prompt
 * rules by SOURCE (so they cannot be tidied away), the generation tag
 * separately, and the request assembly for the known-components block.
 */
class FoodPromptV4Test {
    private val src: String =
        File("src/main/java/com/example/diapilot/data/AskClaude.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/diapilot/data/AskClaude.kt") }
            .readText()

    // ---- layer 5: the model must NAME what it had to guess -----------------

    @Test fun `the prompt requires naming every material assumption`() {
        assertTrue(
            "the text instruction must require naming guesses",
            src.contains("НАЗЫВАЙ в assumptions — не предполагай молча"),
        )
        assertTrue(
            "and the same duty in the photo instruction",
            src.split("FOOD_VISION_INSTR")[1].contains("НАЗЫВАЙ в assumptions"),
        )
    }

    @Test fun `the food-vs-physiology boundary is stated in the schema`() {
        // The −0.45 defect was the model answering "how fast for the user" when
        // asked about the food. The assumptions field must not reopen it.
        assertTrue(
            src.contains("Только факты о ЕДЕ — никогда о дозах или") ||
                src.contains("никогда о дозах"),
        )
        assertTrue(
            "the instruction must forbid the question \"how fast will this raise the user's glucose\"",
            src.contains("как быстро это поднимет сахар у него"),
        )
    }

    @Test fun `the assumptions field rides the tool schema`() {
        val body = AskClaude.textRequestBody("2 куска хлеба", null, emptyList())
        val props = body.getJSONArray("tools").getJSONObject(0)
            .getJSONObject("input_schema").getJSONObject("properties")
        assertTrue(props.has("assumptions"))
        val items = props.getJSONObject("assumptions").getJSONObject("items")
        assertTrue(items.getJSONObject("properties").has("component"))
        assertTrue(items.getJSONObject("properties").has("what"))
        assertTrue(items.getJSONObject("properties").has("impact"))
    }

    // ---- layer 4: the user's own components, not reference values ---------------------

    @Test fun `known components reach the request body — mentioned ones only`() {
        val body = AskClaude.textRequestBody(
            "хумус с хлебом", null,
            listOf(
                AskClaude.KnownComponent("хумус", 8.0, null),
                AskClaude.KnownComponent("хлеб", 10.0, "цельнозерновой"),
                // NOT mentioned in the text — must be filtered out: measured,
                // an unfiltered library block destabilised carb
                // speed (savoury pancake 0.11↔1.0, ice cream MED 2/3).
                AskClaude.KnownComponent("гречка", 30.0, null),
                AskClaude.KnownComponent("нутелла", 8.0, null),
            ),
        )
        val text = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue("no known-components block", "ИЗВЕСТНЫЕ КОМПОНЕНТЫ" in text)
        assertTrue("the user's hummus is missing", "хумус: 8 г/порция" in text)
        assertTrue("the confirmed fact about the bread did not arrive", "цельнозерновой" in text)
        assertFalse("the unmentioned buckwheat must be filtered out", "гречка" in text)
        assertFalse("the unmentioned nutella must be filtered out", "нутелла" in text)
        assertTrue(
            "the rule \"use the user's own values instead of reference ones\" must be in the instruction",
            "бери ЕГО углеводы на порцию и записанные факты вместо справочных" in text,
        )
    }

    @Test fun `an empty library adds no block`() {
        val body = AskClaude.textRequestBody("яблоко", null, emptyList())
        val text = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertFalse("ИЗВЕСТНЫЕ КОМПОНЕНТЫ" in text)
    }

    private fun visionText(caption: String?): String {
        val body = AskClaude.visionRequestBody(
            "QkFTRTY0", caption, null, emptyList(),
            listOf(AskClaude.KnownComponent("хлеб", 10.0, "цельнозерновой")),
        )
        val content = body.getJSONArray("messages").getJSONObject(0).get("content") as JSONArray
        return (0 until content.length())
            .mapNotNull { content.optJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("\n") { it.getString("text") }
    }

    @Test fun `vision gets the components when the caption names them`() {
        assertTrue("ИЗВЕСТНЫЕ КОМПОНЕНТЫ" in visionText("тост с хлебом"))
    }

    @Test fun `a captionless photo gets no component block — stability over a speculative match`() {
        assertFalse("ИЗВЕСТНЫЕ КОМПОНЕНТЫ" in visionText(null))
    }

    // ---- the response budget clears the v4 output --------------------------

    @Test fun `the token budget clears the measured v4 output with headroom`() {
        // Live finding: at 700 every v4 call stopped at max_tokens
        // (measured need 760-911 with assumptions), the truncated tool JSON
        // was refused, and the prose fallback — which has no macros/kcal —
        // silently took over. The budget must never fall back under the
        // measured need again.
        val text = AskClaude.textRequestBody("2 куска хлеба", null, emptyList())
        assertTrue("text budget below the measured v4 need", text.getInt("max_tokens") >= 1200)
        val vision = AskClaude.visionRequestBody("QkFTRTY0", null, null, emptyList())
        assertTrue("vision budget below the measured v4 need", vision.getInt("max_tokens") >= 1200)
    }

    // ---- the generation tag moved with the prompt --------------------------

    @Test fun `the parse generation tag is v4`() {
        val kinetics = File("../core/src/main/kotlin/com/diapilot/core/analysis/FoodKineticsV2.kt")
            .let { if (it.exists()) it else File("core/src/main/kotlin/com/diapilot/core/analysis/FoodKineticsV2.kt") }
            .readText()
        assertTrue(
            "a materially different prompt must write a different tag",
            kinetics.contains("\"llm-structured-v4\""),
        )
        assertFalse(
            "the old tag must not remain active (as a literal in quotes)",
            kinetics.contains("\"llm-structured-v3\""),
        )
    }
}
