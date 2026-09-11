package com.diapilot.core.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = 1_748_768_400_000L
private const val MIN = 60_000L

class QuietMealsTest {

    /** Flat readings around a food note: the dose was right. */
    private fun quietScenario(store: FakeStore) {
        (-30..150 step 5).forEach { m ->
            store.upsertReading(Reading(BASE + m * MIN, 6.5 * 18.0182, 6.5 + 0.02 * (m % 3), null))
        }
        store.upsertInsulin(InsulinEvent(BASE - 10 * MIN, 4.0, "Fiasp"))
        // A real food note is kind="food" (every food-entry path sets it).
        store.addAnnotation(Annotation(BASE, "food", "паста карбонара"))
    }

    private val now = BASE + 3 * 60 * MIN  // 3h later

    @Test
    fun `quiet food note becomes a labeled success episode`() {
        val store = FakeStore()
        quietScenario(store)
        assertEquals(1, promoteQuietMeals(store, now))

        val labeled = store.labeledMeals()
        assertEquals(1, labeled.size)
        assertEquals("паста карбонара", labeled[0].labelName)
        val e = labeled[0].event
        assertTrue("rise stays small: ${e.rise}", e.rise < 0.5)
        assertEquals(4.0, e.bolusUnits!!, 1e-9)     // the correct dose, remembered
        assertEquals(MealEvent.Kind.ANNOUNCED, e.kind)
    }

    @Test
    fun `promotion is idempotent`() {
        val store = FakeStore()
        quietScenario(store)
        promoteQuietMeals(store, now)
        assertEquals(0, promoteQuietMeals(store, now))
        assertEquals(1, store.labeledMeals().size)
    }

    @Test
    fun `not promoted while the wait period runs`() {
        val store = FakeStore()
        quietScenario(store)
        assertEquals(0, promoteQuietMeals(store, BASE + 60 * MIN))  // only 1h passed
    }

    @Test
    fun `matched detection blocks promotion`() {
        val store = FakeStore()
        quietScenario(store)
        // The detector found a rise 40 min after the note — already an event.
        store.addMealEvent(
            MealEvent(BASE + 40 * MIN, BASE + 80 * MIN, 6.5, 9.5, 3.0, 40.0, null, MealEvent.Kind.UNANNOUNCED),
        )
        assertEquals(0, promoteQuietMeals(store, now))
    }

    @Test
    fun `context tags are never promoted`() {
        val store = FakeStore()
        (-30..150 step 5).forEach { m ->
            store.upsertReading(Reading(BASE + m * MIN, 117.0, 6.5, null))
        }
        store.addAnnotation(Annotation(BASE, "text", "недосып"))
        assertEquals(0, promoteQuietMeals(store, now))
    }

    @Test
    fun `a free-text observation is never promoted to a meal`() {
        // "slept poorly" isn't in CONTEXT_TAGS, but it's kind="text" (not food)
        // and carries no grams — it must NOT become a phantom meal episode.
        val store = FakeStore()
        quietScenario(store)   // insulin + flat curve, as for a real quiet meal
        store.addAnnotation(Annotation(BASE + MIN, "text", "плохо спал"))
        assertEquals(1, promoteQuietMeals(store, now))          // only the food note
        assertEquals(
            listOf("паста карбонара"),
            store.labeledMeals().map { it.labelName },
        )
    }
}
