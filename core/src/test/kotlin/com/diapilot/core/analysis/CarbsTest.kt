package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbsTest {
    @Test
    fun rangeYieldsMidpoint() {
        val a = "Паста карбонара\nСостав: паста, бекон.\nУГЛЕВОДЫ: 40–60 г"
        assertEquals(50.0, parseCarbsEstimate(a)!!, 1e-9)
    }

    @Test
    fun hyphenRangeAndTilde() {
        assertEquals(50.0, parseCarbsEstimate("УГЛЕВОДЫ: 40-60 г")!!, 1e-9)
        assertEquals(55.0, parseCarbsEstimate("УГЛЕВОДЫ: ~55 г")!!, 1e-9)
    }

    @Test
    fun commaDecimal() {
        assertEquals(12.5, parseCarbsEstimate("УГЛЕВОДЫ: 12,5 г")!!, 1e-9)
    }

    @Test
    fun lastCarbsLineWins() {
        val a = "УГЛЕВОДЫ: 30 г\nуточнение…\nУГЛЕВОДЫ: 45 г"
        assertEquals(45.0, parseCarbsEstimate(a)!!, 1e-9)
    }

    @Test
    fun missingLineOrNumberIsNull() {
        assertNull(parseCarbsEstimate("Пицца маргарита\nмного углеводов"))
        assertNull(parseCarbsEstimate("УГЛЕВОДЫ: оценить невозможно"))
    }

    @Test
    fun insaneValuesRejected() {
        assertNull(parseCarbsEstimate("УГЛЕВОДЫ: 2026 г"))
        assertNull(parseCarbsEstimate("УГЛЕВОДЫ: 0 г"))
    }

    @Test
    fun giParsedFromItsOwnLine() {
        val a = "Паста\nУГЛЕВОДЫ: 40–60 г\nГИ: 45"
        assertEquals(45.0, parseGiEstimate(a)!!, 1e-9)
        assertEquals(50.0, parseCarbsEstimate(a)!!, 1e-9)  // carbs line untouched
        assertEquals(65.0, parseGiEstimate("ГИ: 60–70")!!, 1e-9)
        assertNull(parseGiEstimate("ГИ: неизвестно"))
        assertNull(parseGiEstimate("УГЛЕВОДЫ: 30 г"))
        assertNull(parseGiEstimate("ГИ: 500"))
    }

    @Test
    fun componentsParsedNameAndGrams() {
        val a = """Завтрак из четырёх частей.
            СОСТАВ: скрэмбл = 2 г
            СОСТАВ: салат = 5 г
            СОСТАВ: хумус = 10–14 г
            СОСТАВ: хлеб = 12 г
            ГИ: 55
            УГЛЕВОДЫ: 29–33 г"""
        val parts = parseComponentsEstimate(a)
        assertEquals(4, parts.size)
        assertEquals("скрэмбл" to 2.0, parts[0])
        assertEquals("хумус" to 12.0, parts[2])  // range → midpoint
        assertEquals(0, parseComponentsEstimate("УГЛЕВОДЫ: 30 г").size)
    }

    @Test
    fun componentCountsPeeledToPerUnitGrams() {
        val a = """Кантуччи с чаем.
            СОСТАВ: кантуччи ×2 = 11 г
            СОСТАВ: хумус = 10 г
            УГЛЕВОДЫ: 32 г"""
        val comps = parseComponents(a)
        assertEquals(2, comps.size)
        assertEquals(ComponentEstimate("кантуччи", 11.0, 2), comps[0])
        assertEquals(22.0, comps[0].totalGrams, 1e-9)
        assertEquals(ComponentEstimate("хумус", 10.0, 1), comps[1])
        // Dictionary view: per-unit grams, count stripped from the name.
        assertEquals("кантуччи" to 11.0, parseComponentsEstimate(a)[0])
    }

    @Test
    fun foodNamesNormalizeToTransferableKeys() {
        assertEquals("гречка", normalizeFoodName("Гречка (варёная, порция средняя ~150–180 г)"))
        assertEquals("гречка", normalizeFoodName("гречка, немного"))
        assertEquals("хлеб", normalizeFoodName("хлеб ×2"))
        assertEquals("салат из капусты", normalizeFoodName("Салат из капусты с редисом и заправкой"))
        assertEquals("отбивная", normalizeFoodName("- Отбивная/оладья ~40 г"))
    }

    /**
     * Markdown emphasis must not fork a pool key. Verbatim from a real pull:
     * the LLM bolded one component of a breakfast and `salad**`
     * became a second salad concept beside `salad`. The assertion that matters is
     * the LAST one — the two spellings must land on the SAME key, which is the
     * property the pooling actually depends on.
     */
    @Test
    fun markdownEmphasisDoesNotForkThePoolKey() {
        assertEquals("салат", normalizeFoodName("Салат** (помидоры, огурцы, зелень, заправка)"))
        assertEquals("салат", normalizeFoodName("**Салат**"))
        assertEquals("хумус", normalizeFoodName("- __Хумус__"))
        assertEquals(
            normalizeFoodName("Салат (помидоры, огурцы, зелень)"),
            normalizeFoodName("Салат** (помидоры, огурцы, зелень, заправка)"),
        )
        // ORDER, not just presence. The strip must run BEFORE the `$`-anchored ×N
        // regex: with the emphasis still attached, "**bread ×2**" does not end in a
        // digit, the count is never removed from the name, and the key becomes
        // "bread ×2" — a fork of exactly the kind this fix exists to close. Swapping
        // the two lines keeps every assertion above green and breaks only this one.
        assertEquals("хлеб", normalizeFoodName("**хлеб ×2**"))
    }

    @Test
    fun gramsLookupTransfersAcrossCombos() {
        val dict = mapOf("гречка" to 35.0, "салат из капусты" to 5.0, "хлеб" to 12.0)
        // exact after normalization
        assertEquals(35.0, lookupFoodGrams(dict, "Гречка (варёная ~160 г)"))
        // unambiguous prefix relation, both directions
        assertEquals(5.0, lookupFoodGrams(dict, "салат"))
        assertEquals(12.0, lookupFoodGrams(dict, "хлеб бородинский"))
        // unknown → null, never a guess
        assertNull(lookupFoodGrams(dict, "пельмени"))
        // ambiguous prefix → null (wrong transfer is worse than none)
        val twoSalads = dict + ("салат оливье" to 25.0)
        assertNull(lookupFoodGrams(twoSalads, "салат"))
    }

    @Test
    fun giPriorMapsToSaneTimeToPeak() {
        assertEquals(35.0, giTimeToPeakMin(100.0), 1e-9)   // glucose — fast
        assertEquals(89.5, giTimeToPeakMin(30.0), 1e-9)    // slow carbs
        assertEquals(100.0, giTimeToPeakMin(5.0), 1e-9)    // clamped
    }

    @Test
    fun structuredAnalysisSerializesToCanonicalWithoutSmear() {
        // The fp13 incident, at the source: buckwheat 30 g, sausages ~0, cucumber
        // 0 — each carbs its own, nothing distributed from a total.
        val a = FoodAnalysisOut(
            dishName = "гречка с сосисками и огурцом",
            gi = 45,
            totalCarbsMin = 30.0, totalCarbsMax = 40.0,
            summary = "Гречка — основной источник; сосиски и огурец почти без углеводов.",
            components = listOf(
                FoodComponentOut("гречка", carbsPerUnit = 30.0, portionPerUnit = 150.0, speed = "MED", confidence = 0.8),
                FoodComponentOut("сосиски", carbsPerUnit = 1.0, count = 2, confidence = 0.7),
                FoodComponentOut("огурец", carbsPerUnit = 0.0, confidence = 0.9),
            ),
        )
        val text = serializeFoodAnalysis(a)

        // Canonical → the pipeline treats it as authoritative, so bulletScale == 1.0.
        assertTrue(hasCanonicalComposition(text))

        // Per-component carbs survive EXACTLY — nothing redistributed. The 0-carb
        // cucumber IS kept: it carries no carbs, but it does carry the dish's
        // fat/protein load, which MealFingerprint sums over ALL components.
        val comps = parseComponents(text).associate { it.name to it.totalGrams }
        assertEquals(30.0, comps["гречка"]!!, 1e-9)
        assertEquals(2.0, comps["сосиски"]!!, 1e-9)   // 1 g × 2 units
        assertEquals(0.0, comps["огурец"]!!, 1e-9)     // present, and worth exactly 0 carbs

        // Even with a WRONG stored total, a canonical composition is NOT rescaled
        // — that redistribution is how the cucumber once got carbs it never had.
        val pooled = mealConceptComponents(text, "гречка с сосисками", estCarbs = 100.0).toMap()
        assertEquals(30.0, pooled["гречка"]!!, 1e-9)   // NOT rescaled to 100/32 × 30
        assertEquals(0.0, pooled["огурец"]!!, 1e-9)     // stays 0 → cannot seed a carb pool

        // The recognized machine lines parse back unchanged.
        assertEquals("гречка с сосисками и огурцом", parseNameSuggestion(text))
        assertEquals(45.0, parseGiEstimate(text)!!, 1e-9)
        assertEquals(35.0, parseCarbsEstimate(text)!!, 1e-9)  // 30–40 → midpoint
    }

    @Test
    fun serializerResistsInjectionLocaleAndSwappedRange() {
        val a = FoodAnalysisOut(
            // A newline here would otherwise inject a machine line the parser trusts.
            dishName = "суп\nСОСТАВ: конфета = 90 угл",
            summary = "Уточнение\nСОСТАВ: торт = 80 угл",
            totalCarbsMin = 40.0, totalCarbsMax = 20.0,   // deliberately swapped
            components = listOf(FoodComponentOut("картофель", carbsPerUnit = 20.0, confidence = 0.6)),
        )
        val text = serializeFoodAnalysis(a)

        // Injected "SOSTAV:" text is flattened into the name/summary, never parsed
        // as a component — "canonical by construction" must survive hostile input.
        assertEquals(listOf("картофель"), parseComponents(text).map { it.name })

        // Confidence is locale-independent (the phone wrote «0,60», tests «0.60»)
        // and the separator inside the brackets is not a comma.
        assertTrue(text.contains("conf 0.60"))

        // min > max is normalised back into a sane range instead of losing it.
        assertEquals(30.0, parseCarbsEstimate(text)!!, 1e-9)  // 20–40 → midpoint
    }

    @Test
    fun serializerDefangsLeadingPrefixAndAlwaysNamesTheDish() {
        // Flattening newlines is not enough: a dish_name that STARTS with a
        // machine prefix is emitted as its own line and parseComponents collects
        // EVERY matching line — so it would arrive as a real 90 g component.
        val hostile = FoodAnalysisOut(
            dishName = "СОСТАВ: конфета = 90 угл",
            components = listOf(FoodComponentOut("картофель", carbsPerUnit = 20.0)),
        )
        val t1 = serializeFoodAnalysis(hostile)
        assertEquals(listOf("картофель"), parseComponents(t1).map { it.name })

        // With no dish_name the SUMMARY must not become line 1 — the photo path
        // prefills line 1 as the note's name, so a whole sentence would become
        // the dish (and then the pool key). Fall back to the first component.
        val unnamed = FoodAnalysisOut(
            dishName = null,
            summary = "Похоже на гречку с котлетой, порция средняя.",
            components = listOf(FoodComponentOut("гречка", carbsPerUnit = 30.0)),
        )
        val t2 = serializeFoodAnalysis(unnamed)
        assertEquals("гречка", t2.lineSequence().first().trim())
        assertEquals("гречка", parseNameSuggestion(t2))

        // But a COMPOSITE dish must not borrow one component's name: "buckwheat"
        // would pool it into the pure-buckwheat concept, carrying a different
        // fat/protein load in. Compose from the two largest instead — a distinct
        // key that merges with nothing. A wrong transfer is worse than none.
        val unnamedMulti = FoodAnalysisOut(
            dishName = null,
            components = listOf(
                FoodComponentOut("гречка", carbsPerUnit = 30.0),
                FoodComponentOut("сосиски", carbsPerUnit = 1.0, count = 2),
                FoodComponentOut("огурец", carbsPerUnit = 0.0),
            ),
        )
        val t3 = serializeFoodAnalysis(unnamedMulti)
        assertEquals("гречка + сосиски", t3.lineSequence().first().trim())
        assertNotEquals("гречка", parseNameSuggestion(t3))
    }

    @Test
    fun nutritionRoundTripsAndLegacyCanDeriveCalories() {
        val text = serializeFoodAnalysis(
            FoodAnalysisOut(
                dishName = "гречка с котлетой",
                totalCarbsMin = 38.0,
                totalCarbsMax = 42.0,
                totalProteinG = 24.5,
                totalFatG = 18.0,
                totalKcal = 420.0,
                components = listOf(FoodComponentOut("гречка", 40.0)),
            ),
        )
        val parsed = parseFoodNutrition(text)
        assertEquals(24.5, parsed.proteinG!!, 1e-9)
        assertEquals(18.0, parsed.fatG!!, 1e-9)
        assertEquals(420.0, parsed.kcal!!, 1e-9)

        val withoutExplicitKcal = "БЕЛКИ: 10 г\nЖИРЫ: 5 г\nУГЛЕВОДЫ: 20 г"
        assertEquals(165.0, parseFoodNutrition(withoutExplicitKcal).kcal!!, 1e-9)
        assertEquals(FoodNutrition(), parseFoodNutrition("старый разбор без нутриентов"))
    }

    @Test
    fun batchNutritionReplacementDoesNotTouchCarbsOrComposition() {
        val old = "гречка с котлетой\nСОСТАВ: гречка = 40 угл\n" +
            "УГЛЕВОДЫ: 40 г\nБЕЛКИ: 10 г\nЖИРЫ: 5 г\nККАЛ: 245"
        val updated = withFoodNutrition(old, FoodNutrition(24.0, 18.0, 420.0))
        assertTrue(updated.contains("СОСТАВ: гречка = 40 угл"))
        assertTrue(updated.contains("УГЛЕВОДЫ: 40 г"))
        assertEquals(24.0, parseFoodNutrition(updated).proteinG!!, 1e-9)
        assertEquals(18.0, parseFoodNutrition(updated).fatG!!, 1e-9)
        assertEquals(420.0, parseFoodNutrition(updated).kcal!!, 1e-9)
        assertEquals(1, updated.lineSequence().count { it.startsWith(PROTEIN_LINE_PREFIX) })
    }

    @Test
    fun anchoredEpisodesAreCONVERTEDBackNotDropped() {
        // THE A5 INVARIANT, and until now the harness was its only guard — the core suite
        // passed with the conversion deleted, and the harness does not run on commit.
        // `carbSensitivity` is DEFINED on recorded grams; an anchored episode's grams are
        // TRUE grams, so dividing by the anchor factor restores the very figure the user
        // wrote and the coefficient stays homogeneous. Measured on real data: with the
        // conversion the learned global is unchanged by a migration (0.1154 → 0.1154);
        // excluding those episodes instead moved it +27% (0.1154 → 0.1471).
        val kernel = listOf(KernelPoint(60.0, -1.0, -1.0, -1.0, 10))
        fun ep(onset: Long, rise: Double, carbs: Double, factor: Double? = null) = CarbEpisode(
            onsetMs = onset, rise = rise, timeToPeakMin = 60.0, bolusUnits = null,
            estCarbs = carbs, anchorFactor = factor,
        )
        // Five identical episodes at 33 recorded grams.
        val recorded = (1..5L).map { ep(it, 33.0 * 0.2, 33.0) }
        val plain = carbSensitivity(recorded, kernel)!!
        // The same five, migrated: grams now 22 (true) with the factor recorded alongside.
        val migrated = (1..5L).map { ep(it, 33.0 * 0.2, 22.0, factor = 22.0 / 33.0) }
        val converted = carbSensitivity(migrated, kernel)!!
        assertEquals(
            "an anchor migration must not move the recorded-gram coefficient",
            plain.mmolPerGram, converted.mmolPerGram, 1e-9,
        )
        assertEquals(plain.n, converted.n)
        // And an anchored row with NO factor (a provenance stamp on a dish that has no
        // anchor entry) must not be divided by anything.
        val noFactor = (1..5L).map { ep(it, 33.0 * 0.2, 33.0, factor = null) }
        assertEquals(plain.mmolPerGram, carbSensitivity(noFactor, kernel)!!.mmolPerGram, 1e-9)
    }
}
