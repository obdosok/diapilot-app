package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * English duplicate of [CarbsTest]'s language-sensitive cases (WP-B5). Marker
 * parsing was never Russian-specific — `CARBS_LINE_PREFIX`/`COMPONENT_LINE_PREFIX`
 * are English already, and [normalizeFoodName] only trims and lowercases — but
 * an English-speaking user's own dish names deserve their own corpus rather
 * than an inference from the Russian one. Same shapes, English input.
 */
class EnglishCarbsTest {
    @Test
    fun rangeYieldsMidpoint() {
        val a = "Pasta carbonara\nCOMPOSITION: pasta, bacon.\nCARBS: 40-60 g"
        assertEquals(50.0, parseCarbsEstimate(a)!!, 1e-9)
    }

    @Test
    fun hyphenRangeAndTilde() {
        assertEquals(50.0, parseCarbsEstimate("CARBS: 40-60 g")!!, 1e-9)
        assertEquals(55.0, parseCarbsEstimate("CARBS: ~55 g")!!, 1e-9)
    }

    @Test
    fun commaDecimal() {
        assertEquals(12.5, parseCarbsEstimate("CARBS: 12,5 g")!!, 1e-9)
    }

    @Test
    fun missingLineOrNumberIsNull() {
        assertNull(parseCarbsEstimate("Margherita pizza\nlots of carbs"))
        assertNull(parseCarbsEstimate("CARBS: cannot estimate"))
    }

    @Test
    fun insaneValuesRejected() {
        assertNull(parseCarbsEstimate("CARBS: 2026 g"))
        assertNull(parseCarbsEstimate("CARBS: 0 g"))
    }

    @Test
    fun giParsedFromItsOwnLine() {
        val a = "Pasta\nCARBS: 40-60 g\nGI: 45"
        assertEquals(45.0, parseGiEstimate(a)!!, 1e-9)
        assertEquals(50.0, parseCarbsEstimate(a)!!, 1e-9)  // carbs line untouched
    }

    @Test
    fun componentsParsedNameAndGrams() {
        val a = """Four-part breakfast.
            COMPOSITION: scramble = 2 g
            COMPOSITION: salad = 5 g
            COMPOSITION: hummus = 10-14 g
            COMPOSITION: bread = 12 g
            GI: 55
            CARBS: 29-33 g"""
        val parts = parseComponentsEstimate(a)
        assertEquals(4, parts.size)
        assertEquals("scramble" to 2.0, parts[0])
        assertEquals("hummus" to 12.0, parts[2])  // range → midpoint
        assertEquals(0, parseComponentsEstimate("CARBS: 30 g").size)
    }

    @Test
    fun componentCountsPeeledToPerUnitGrams() {
        val a = """Biscotti with tea.
            COMPOSITION: biscotti x2 = 11 g
            COMPOSITION: hummus = 10 g
            CARBS: 32 g"""
        val comps = parseComponents(a)
        assertEquals(2, comps.size)
        assertEquals(ComponentEstimate("biscotti", 11.0, 2), comps[0])
        assertEquals(22.0, comps[0].totalGrams, 1e-9)
        assertEquals(ComponentEstimate("hummus", 10.0, 1), comps[1])
        assertEquals("biscotti" to 11.0, parseComponentsEstimate(a)[0])
    }

    @Test
    fun foodNamesNormalizeToTransferableKeys() {
        assertEquals("buckwheat", normalizeFoodName("Buckwheat (cooked, medium portion ~150-180 g)"))
        assertEquals("buckwheat", normalizeFoodName("buckwheat, a little"))
        assertEquals("bread", normalizeFoodName("bread x2"))
        assertEquals("cabbage salad with", normalizeFoodName("Cabbage salad with radish and dressing"))
        assertEquals("cutlet", normalizeFoodName("- Cutlet/pancake ~40 g"))
        // Diacritics fold before matching: a borrowed word typed with its
        // accents and the same word typed plain must land on the same key.
        assertEquals(normalizeFoodName("creme brulee"), normalizeFoodName("crème brûlée"))
    }

    @Test
    fun markdownEmphasisDoesNotForkThePoolKey() {
        assertEquals("salad", normalizeFoodName("Salad** (tomato, cucumber, greens, dressing)"))
        assertEquals("salad", normalizeFoodName("**Salad**"))
        assertEquals("hummus", normalizeFoodName("- __Hummus__"))
        assertEquals(
            normalizeFoodName("Salad (tomato, cucumber, greens)"),
            normalizeFoodName("Salad** (tomato, cucumber, greens, dressing)"),
        )
        assertEquals("bread", normalizeFoodName("**bread ×2**"))
    }

    @Test
    fun gramsLookupTransfersAcrossCombos() {
        val dict = mapOf("buckwheat" to 35.0, "salad from cabbage" to 5.0, "bread" to 12.0)
        // exact after normalization
        assertEquals(35.0, lookupFoodGrams(dict, "Buckwheat (cooked ~160 g)"))
        // unambiguous prefix relation, both directions
        assertEquals(5.0, lookupFoodGrams(dict, "salad"))
        assertEquals(12.0, lookupFoodGrams(dict, "bread wholemeal"))
        // unknown → null, never a guess
        assertNull(lookupFoodGrams(dict, "dumplings"))
        // ambiguous prefix → null (wrong transfer is worse than none)
        val twoSalads = dict + ("salad with olives" to 25.0)
        assertNull(lookupFoodGrams(twoSalads, "salad"))
    }

    @Test
    fun structuredAnalysisSerializesToCanonicalWithoutSmear() {
        val a = FoodAnalysisOut(
            dishName = "buckwheat with sausages and cucumber",
            gi = 45,
            totalCarbsMin = 30.0, totalCarbsMax = 40.0,
            summary = "Buckwheat is the main carb source; sausages and cucumber carry almost none.",
            components = listOf(
                FoodComponentOut("buckwheat", carbsPerUnit = 30.0, portionPerUnit = 150.0, speed = "MED", confidence = 0.8),
                FoodComponentOut("sausages", carbsPerUnit = 1.0, count = 2, confidence = 0.7),
                FoodComponentOut("cucumber", carbsPerUnit = 0.0, confidence = 0.9),
            ),
        )
        val text = serializeFoodAnalysis(a)

        assertTrue(hasCanonicalComposition(text))

        val comps = parseComponents(text).associate { it.name to it.totalGrams }
        assertEquals(30.0, comps["buckwheat"]!!, 1e-9)
        assertEquals(2.0, comps["sausages"]!!, 1e-9)   // 1 g × 2 units
        assertEquals(0.0, comps["cucumber"]!!, 1e-9)    // present, and worth exactly 0 carbs

        assertEquals("buckwheat with sausages and cucumber", parseNameSuggestion(text))
        assertEquals(45.0, parseGiEstimate(text)!!, 1e-9)
        assertEquals(35.0, parseCarbsEstimate(text)!!, 1e-9)  // 30-40 → midpoint
    }

    @Test
    fun nutritionRoundTripsAndLegacyCanDeriveCalories() {
        val text = serializeFoodAnalysis(
            FoodAnalysisOut(
                dishName = "buckwheat with cutlet",
                totalCarbsMin = 38.0,
                totalCarbsMax = 42.0,
                totalProteinG = 24.5,
                totalFatG = 18.0,
                totalKcal = 420.0,
                components = listOf(FoodComponentOut("buckwheat", 40.0)),
            ),
        )
        val parsed = parseFoodNutrition(text)
        assertEquals(24.5, parsed.proteinG!!, 1e-9)
        assertEquals(18.0, parsed.fatG!!, 1e-9)
        assertEquals(420.0, parsed.kcal!!, 1e-9)

        val withoutExplicitKcal = "PROTEIN: 10 g\nFAT: 5 g\nCARBS: 20 g"
        assertEquals(165.0, parseFoodNutrition(withoutExplicitKcal).kcal!!, 1e-9)
        assertEquals(FoodNutrition(), parseFoodNutrition("an old analysis with no nutrients"))
    }
}
