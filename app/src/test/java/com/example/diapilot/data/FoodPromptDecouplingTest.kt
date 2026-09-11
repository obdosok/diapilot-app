package com.example.diapilot.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two prompt rules that were bought with measurements, pinned so they
 * cannot be tidied away.
 *
 * Measured on real notes, 33 independent draws:
 *  - carb speed was contaminated by fat — correlation −0.45 between fat per
 *    carb gram and the fast fraction (n=138), and by name "pistachio paste"
 *    → MED against "cherry jam" → FAST. The model already counts fat
 *    three times (gastric slowdown, fat→peak slope, caloric queue), so that
 *    was a fourth;
 *  - physical_form agreed across a note's own draws only 45% of the time, and
 *    form is a coordinate of the dish identity key, so 10 of 11 notes could
 *    not be recognised as repeats of themselves.
 *
 * Validated on the PRODUCTION model before landing (12 calls, control in the
 * same batch): the savoury pancake went from two identity keys and a fast
 * fraction swinging 0.11↔0.96 to one key at 0.96–0.98, the pistachio pancake
 * from 0.77 to 1.00, and lentils/buckwheat/oats stayed SLOW/MED — the fix did
 * not buy stability by destroying discrimination.
 */
class FoodPromptDecouplingTest {
    private val src: String =
        File("src/main/java/com/example/diapilot/data/AskClaude.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/diapilot/data/AskClaude.kt") }
            .readText()

    @Test fun `carb speed is explicitly decoupled from fat and protein`() {
        assertTrue(
            "the speed field must say it is the carbohydrate's own speed",
            src.contains("Absorption speed of the component's OWN carbohydrate"),
        )
        assertTrue(
            "and must name the double count, or the rule reads as a preference",
            src.contains("double count"),
        )
        assertTrue(
            "fatty sweets must be listed as FAST — that is the measured failure",
            src.contains("sweet paste") && src.contains("icing"),
        )
    }

    @Test fun `physical form has a deterministic ladder, not just examples`() {
        assertTrue(
            "the form must be decided by an explicit ordered rule",
            src.contains("Decide step by step") && src.contains("largest carbs_g"),
        )
        assertTrue(
            "toppings must be declared not to move the form",
            src.contains("Sauces, fillings, side dishes and toppings do not change the form"),
        )
        assertTrue(
            "MIXED must be a numeric exception, not a judgement call",
            src.contains("less than 10% of the total"),
        )
    }

    @Test fun `the parse generation tag moved with the prompt`() {
        val kinetics = File("../core/src/main/kotlin/com/diapilot/core/analysis/FoodKineticsV2.kt")
            .let { if (it.exists()) it else File("core/src/main/kotlin/com/diapilot/core/analysis/FoodKineticsV2.kt") }
            .readText()
        // v3 accompanied the decoupling above; v4 is the F-05 generation (the
        // user's components consulted, guesses named). FoodPromptV4Test pins v4's own
        // rules — here only that the tag KEEPS moving with the prompt.
        assertTrue(
            "a materially different prompt must write a different provenance tag",
            kinetics.contains("\"llm-structured-v4\""),
        )
    }
}
