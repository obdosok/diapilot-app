package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.LabeledMeal
import com.diapilot.core.collector.MealEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = 1_748_768_400_000L
private const val MIN = 60_000L

private fun meal(onset: Long, rise: Double = 4.0, ttp: Double = 60.0, bolus: Double? = null) =
    MealEvent(onset, onset + (ttp * MIN).toLong(), 6.0, 6.0 + rise, rise, ttp, bolus,
        if (bolus != null) MealEvent.Kind.ANNOUNCED else MealEvent.Kind.UNANNOUNCED)

private fun labeled(onset: Long, label: String, rise: Double = 4.0, ttp: Double = 60.0, bolus: Double? = null) =
    LabeledMeal(meal(onset, rise, ttp, bolus), 1, label)

class FoodMemoryTest {

    @Test
    fun `memory aggregates episodes with continuations as top-ups`() {
        val labeledMeals = listOf(
            labeled(BASE, "пицца", rise = 5.0, bolus = 3.0),
            labeled(BASE + 2 * 60 * MIN, SysLabels.CONTINUATION, rise = 2.0, bolus = 3.0),
            labeled(BASE + 14L * 24 * 60 * MIN, "пицца", rise = 4.5, bolus = 5.0),
        )
        val memory = foodMemory("пицца", labeledMeals, emptyList())!!
        assertEquals(2, memory.episodes.size)
        // Newest first: the 5.0-only episode.
        assertEquals(5.0, memory.episodes[0].effectiveDose!!, 1e-9)
        assertTrue(!memory.episodes[0].underDosed)
        // Older: 3.0 primary + 3.0 continuation top-up, flagged under-dosed.
        assertEquals(6.0, memory.episodes[1].effectiveDose!!, 1e-9)
        assertTrue(memory.episodes[1].underDosed)
        assertEquals(5.5, memory.avgEffectiveDose!!, 0.2)  // recency-weighted
        assertEquals(1, memory.underDosedCount)
    }

    @Test
    fun `dokol-purpose shots count once`() {
        val labeledMeals = listOf(labeled(BASE, "пицца", bolus = 3.0))
        val boluses = listOf(BolusPoint(BASE + 100 * MIN, 2.0, purpose = "докол"))
        val memory = foodMemory("пицца", labeledMeals, boluses)!!
        assertEquals(5.0, memory.episodes[0].effectiveDose!!, 1e-9)
        assertTrue(memory.episodes[0].underDosed)
    }

    @Test
    fun `absorption lag from the manual entry to the rise onset`() {
        val labeledMeals = listOf(
            labeled(BASE, "суп", bolus = 3.0),
            labeled(BASE + 24L * 60 * MIN, "суп", bolus = 3.0),
        )
        val notes = listOf(
            com.diapilot.core.collector.Annotation(BASE - 40 * MIN, "text", "суп", id = 1),
            com.diapilot.core.collector.Annotation(BASE + 24L * 60 * MIN - 20 * MIN, "text", "суп", id = 2),
        )
        val memory = foodMemory("суп", labeledMeals, emptyList(), notes)!!
        assertEquals(20.0, memory.episodes[0].lagMin!!, 1e-9)
        assertEquals(40.0, memory.episodes[1].lagMin!!, 1e-9)
        assertEquals(30.0, memory.avgLagMin!!, 0.5)  // recency-weighted
    }

    @Test
    fun `per-gram separates shape from portion, clean episodes only`() {
        // Same dish, two portions: 30 g → +3.0, 60 g → +6.0. The flat average
        // (+4.5) hides that it's linear in grams; per-gram recovers 0.1 mmol/g.
        val labeledMeals = listOf(
            labeled(BASE, "смузи", rise = 3.0, bolus = 1.0),
            labeled(BASE + 24L * 60 * MIN, "смузи", rise = 6.0, bolus = 2.0),
        )
        val notes = listOf(
            com.diapilot.core.collector.Annotation(BASE - 10 * MIN, "text", "смузи", id = 1, estCarbs = 30.0),
            com.diapilot.core.collector.Annotation(BASE + 24L * 60 * MIN - 10 * MIN, "text", "смузи", id = 2, estCarbs = 60.0),
        )
        // No kernel → raw rise per gram.
        val m = foodMemory("смузи", labeledMeals, emptyList(), notes)!!
        assertEquals(0.1, m.perGramRise!!, 1e-6)
        assertEquals(2, m.perGramN)
        assertEquals(45.0, m.typicalGrams!!, 1e-9)
        // A contaminated episode (pre-meal insulin ≥1u) is excluded from per-gram.
        val boluses = listOf(BolusPoint(BASE + 24L * 60 * MIN - 60 * MIN, 2.0))
        val m2 = foodMemory("смузи", labeledMeals, boluses, notes)!!
        assertEquals(1, m2.perGramN)  // only the clean first episode
        assertEquals(0.1, m2.perGramRise!!, 1e-6)
    }

    @Test
    fun `unknown and system labels have no memory`() {
        assertNull(foodMemory("суп", emptyList(), emptyList()))
        assertNull(foodMemory(SysLabels.DAWN, listOf(labeled(BASE, SysLabels.DAWN)), emptyList()))
    }

    @Test
    fun `similar foods by response profile`() {
        val stats = listOf(
            LabelStats("пицца", 3, avgRise = 5.0, avgTimeToPeakMin = 65.0, avgBolus = 4.0, unannounced = 0),
            LabelStats("бутики", 2, avgRise = 4.6, avgTimeToPeakMin = 58.0, avgBolus = 3.0, unannounced = 0),
            LabelStats("смузи", 4, avgRise = 5.2, avgTimeToPeakMin = 25.0, avgBolus = 2.5, unannounced = 1),
            LabelStats("яблоко", 1, avgRise = 4.8, avgTimeToPeakMin = 60.0, avgBolus = null, unannounced = 1),
        )
        val similar = similarFoods("пицца", stats)
        // pastries close; smoothie too fast (ttp differs); apple has n<2.
        assertEquals(listOf("бутики"), similar.map { it.name })
    }

    @Test
    fun `similar foods by shared recipe components`() {
        val byDish = mapOf(
            "бутылка лагера" to setOf("пиво"),
            "бутылка пива корона" to setOf("пиво"),
            "летний завтрак" to setOf("скрэмбл", "хлеб", "хумус"),
            "бутик с паштетом" to setOf("хлеб", "паштет"),
            "смузи" to setOf("смузи"),
        )
        // A NEVER-eaten new beer connects to the measured one through "beer".
        assertEquals(
            listOf("бутылка пива корона"),
            similarByComponents("бутылка лагера", byDish),
        )
        // "bread" bridges breakfast and pastry; ties prefer the simpler dish.
        assertEquals(
            listOf("бутик с паштетом"),
            similarByComponents("летний завтрак", byDish),
        )
        assertEquals(emptyList<String>(), similarByComponents("смузи", byDish))
        assertEquals(emptyList<String>(), similarByComponents("нет такого", byDish))
    }

    @Test
    fun `borrowed ttp pools measured component twins`() {
        val stats = listOf(
            LabelStats("пиво портер", 3, avgRise = 4.4, avgTimeToPeakMin = 90.0, avgBolus = null, unannounced = 1),
            LabelStats("лагер", 1, avgRise = 4.7, avgTimeToPeakMin = 40.0, avgBolus = null, unannounced = 0),
            LabelStats("смузи", 4, avgRise = 5.1, avgTimeToPeakMin = 25.0, avgBolus = 2.0, unannounced = 1),
        )
        val donors = mapOf(
            "пиво портер" to setOf("пиво"),
            "лагер" to setOf("пиво"),
            "смузи" to setOf("смузи"),
        )
        // New beer inherits the measured beer ttp; the 1-episode lager
        // is below minCount and does not vote.
        assertEquals(
            90.0,
            borrowedComponentTtp(setOf("пиво"), donors, stats)!!,
            1e-9,
        )
        // No component data → null (caller falls back to GI/avg).
        assertNull(borrowedComponentTtp(setOf("борщ"), donors, stats))
        assertNull(borrowedComponentTtp(emptySet(), donors, stats))
    }
}
