package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodConceptsTest {

    @Test
    fun `aliases and declensions map to the concept id`() {
        assertEquals("buckwheat", conceptFor("гречка")?.id)
        assertEquals("buckwheat", conceptFor("гречкой")?.id)         // declension via stem
        assertEquals("bread", conceptFor("хлеб")?.id)
        assertEquals("bread", conceptFor("2 куска хлеба")?.id)       // ×N / prose
        assertEquals("beer", conceptFor("бутылка короны")?.id)  // brand → beer
        assertEquals("meat_cutlet", conceptFor("отбивная")?.id)
        assertEquals("egg", conceptFor("скрэмбл")?.id)
        assertEquals("salad", conceptFor("салат из свежей моркови")?.id)
    }

    @Test
    fun `unknown component is unmapped (generic bucket)`() {
        assertNull(conceptFor("экзотический фрукт мангустин"))
    }

    @Test
    fun `exact alias beats a substring match`() {
        // "tuna paste" is fish paste, not pasta — exact alias must win.
        assertEquals("fish", conceptFor("паста тунца")?.id)
        assertEquals("pasta", conceptFor("паста")?.id)
        assertEquals("salad", conceptFor("петрушка")?.id)
    }
}

class MealFingerprintTest {

    @Test
    fun `carbs drive amplitude, carb-weighted speed drives shape, meat is tail`() {
        // Buckwheat (med carb) 45g + cutlet (protein/fat, 0 carbs). Salad-like
        // non-carb items don't move speed; meat sets fat/protein high.
        val fp = mealFingerprint(listOf("гречка" to 45.0, "котлета" to 0.0))
        assertEquals(45.0, fp.totalCarbs, 1e-9)
        assertEquals(CarbSpeed.MED, fp.carbSpeed)          // buckwheat speed
        assertEquals(MacroLevel.HIGH, fp.fatLevel)         // from cutlet
        assertEquals(MacroLevel.HIGH, fp.proteinLevel)
        assertTrue("buckwheat" in fp.carbDrivers)
        assertTrue("meat_cutlet" !in fp.carbDrivers)       // 0 carbs → not a driver
    }

    @Test
    fun `low-carb components self-silence on amplitude and speed`() {
        // Bread (fast) 30 + hummus (med) 10 + salad 0 + scramble 0. Speed is the
        // carb-weighted mix of bread(fast) and hummus(med); salad/egg don't vote.
        val fp = mealFingerprint(
            listOf("хлеб ×2" to 30.0, "хумус" to 10.0, "салат" to 0.0, "скрэмбл" to 0.0),
        )
        assertEquals(40.0, fp.totalCarbs, 1e-9)
        // 30×FAST(3) + 10×MED(2) = 110 / 40 = 2.75 → FAST(3)
        assertEquals(CarbSpeed.FAST, fp.carbSpeed)
        assertEquals(setOf("bread", "hummus"), fp.carbDrivers.toSet())
    }

    @Test
    fun `portion round-trips to carbs via density and back through the format`() {
        // buckwheat density 20/100 → 175 g portion ≈ 35 g carbs.
        assertEquals(35.0, carbsForPortion("buckwheat", 175.0)!!, 1e-6)
        val line = sostavLine("гречка", 1, 35.0, 175.0)
        val parsed = parseComponents(line).single()
        assertEquals("гречка", parsed.name)
        assertEquals(35.0, parsed.unitGrams, 1e-6)         // carbs = model number
        assertEquals(175.0, parsed.portionGrams!!, 1e-6)   // portion = human number
    }

    @Test
    fun `legacy single-number line reads as carbs`() {
        val parsed = parseComponents("СОСТАВ: хлеб ×2 = 10 г").single()
        assertEquals(10.0, parsed.unitGrams, 1e-6)
        assertNull(parsed.portionGrams)
        assertEquals(2, parsed.count)
    }

    @Test
    fun `portion-only line derives carbs from the concept`() {
        // "= 175 portion" with no explicit carbs → derive from buckwheat density.
        val parsed = parseComponents("СОСТАВ: гречка = 175 порц").single()
        assertEquals(35.0, parsed.unitGrams, 1e-6)
        assertEquals(175.0, parsed.portionGrams!!, 1e-6)
    }

    @Test
    fun `macro loads are amount-aware, not just strongest level`() {
        // A sprig of cheese must NOT flag the plate high-fat; a big portion must.
        val tiny = mealFingerprint(listOf(FingerInput("сыр", 1.0, portionGrams = 5.0)))
        assertEquals(MacroLevel.LOW, tiny.fatLevel)
        val big = mealFingerprint(listOf(FingerInput("сыр", 3.0, portionGrams = 150.0)))
        assertEquals(MacroLevel.HIGH, big.fatLevel)
        assertTrue("load in grams", big.fatGrams > tiny.fatGrams * 5)
    }

    @Test
    fun `pure protein-fat meal has no carb speed`() {
        val fp = mealFingerprint(listOf("курица" to 0.0, "салат" to 0.0))
        assertEquals(0.0, fp.totalCarbs, 1e-9)
        assertEquals(CarbSpeed.NONE, fp.carbSpeed)
        assertTrue(fp.carbDrivers.isEmpty())
    }
}

class ConceptPointFixesTest {
    @Test
    fun `root veg is a slow carb, not a zero-carb vegetable`() {
        assertEquals("root_veg", conceptFor("морковь")?.id)
        assertEquals("root_veg", conceptFor("свёкла")?.id)
        assertEquals(CarbSpeed.SLOW, conceptFor("морковь")!!.carbSpeed)
        assertEquals("vegetable", conceptFor("капуста")?.id)   // leafy stays zero-ish
    }

    @Test
    fun `bullet breakdown carbs are scaled to the confirmed estCarbs`() {
        // Non-canonical bullets sum to 30 g; confirmed estCarbs 45 → scale ×1.5.
        val analysis = "- хлеб — 20 г\n- сыр — 10 г"
        val comps = mealConceptComponents(analysis, "бутерброд", estCarbs = 45.0)
        assertEquals(45.0, comps.sumOf { it.second }, 0.5)
    }

    @Test
    fun `canonical SOSTAV is NOT rescaled`() {
        val analysis = "СОСТАВ: хлеб = 20 угл\nСОСТАВ: сыр = 10 угл"
        val comps = mealConceptComponents(analysis, "бутерброд", estCarbs = 45.0)
        assertEquals(30.0, comps.sumOf { it.second }, 0.5)   // trusts the canonical split
    }
}

class ConceptPoolsTest {

    @Test
    fun `name variants collapse into one concept pool`() {
        // Three ways the user typed smoothies + two ice creams — pooled by concept.
        val pools = conceptPools(
            listOf(
                "смузи" to 30.0,
                "смузи с апельсиновым соком и петрушкой" to 33.0,
                "фреш" to 25.0,
                "мороженое" to 24.0,
                "мороженое Магнум с фундуком" to 26.0,
            ),
        )
        val smoothie = pools.single { it.conceptId == "smoothie" }
        assertEquals(3, smoothie.occurrences)
        assertEquals(88.0, smoothie.totalCarbs, 1e-9)
        val iceCream = pools.single { it.conceptId == "ice_cream" }
        assertEquals(2, iceCream.occurrences)
    }

    @Test
    fun `unmapped names collect under one visible bucket`() {
        val pools = conceptPools(listOf("мангустин" to 10.0, "экзотика" to 5.0, "хлеб" to 20.0))
        val gap = pools.single { it.conceptId == UNMAPPED_CONCEPT }
        assertEquals(2, gap.occurrences)
        assertTrue(pools.any { it.conceptId == "bread" })
    }

    @Test
    fun `atomic dish with no SOSTAV pools by its name`() {
        val comps = mealConceptComponents(analysis = null, freeName = "Пиво Paulaner", estCarbs = 20.0)
        assertEquals(listOf("Пиво Paulaner" to 20.0), comps)
        assertEquals("beer", conceptPools(comps).single().conceptId)
    }
}

class ConceptBoundaryTest {
    @Test
    fun `short alias does not match inside an unrelated word`() {
        assertEquals("pizza", conceptFor("кусок домашней пиццы")?.id)
        assertEquals("juice", conceptFor("апельсиновый сок")?.id)
    }

    @Test
    fun `soup wins over a vegetable substring`() {
        assertEquals("soup", conceptFor("томатный суп с чечевицей")?.id)
    }
}

class PastryTest {
    @Test
    fun `berry bun is fast pastry, not slow berries`() {
        assertEquals("pastry", conceptFor("ягодянка")?.id)
        assertEquals("pastry", conceptFor("четверть ягодянки")?.id)   // genitive via stem
        assertEquals("pastry", conceptFor("ягодный пирог")?.id)
        assertEquals("berries", conceptFor("черника")?.id)             // real berries stay
        assertEquals(CarbSpeed.FAST, conceptFor("ягодянка")!!.carbSpeed)
    }
}
