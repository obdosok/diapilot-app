package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealSessionTest {

    private var nextId = 1L
    private fun food(tsMin: Long, name: String, carbs: Double? = null) =
        Annotation(tsMs = tsMin * 60_000, kind = "food", content = name, id = nextId++, estCarbs = carbs)

    @Test
    fun twoPhotosSameMinuteAreOneSession() {
        val s = groupMealSessions(
            listOf(food(0, "Ikea, Фрикадельки с пюре", 77.5), food(1, "Ikea, Свекольный суп", 22.5)),
        )
        assertEquals(1, s.size)
        assertEquals(100.0, s[0].totalCarbs!!, 1e-9)
        assertEquals("Ikea, Фрикадельки с пюре + Ikea, Свекольный суп", s[0].composedName)
    }

    @Test
    fun gapBeyondWindowSplitsSessions() {
        val s = groupMealSessions(listOf(food(0, "завтрак"), food(120, "обед")))
        assertEquals(2, s.size)
    }

    @Test
    fun chainedGapsStayOneSession() {
        // 0 → 40 → 80: each gap ≤ 45, the session stretches.
        val s = groupMealSessions(listOf(food(0, "суп"), food(40, "второе"), food(80, "десерт")))
        assertEquals(1, s.size)
        assertEquals(3, s[0].notes.size)
    }

    @Test
    fun dessertAfterSlowMealRemainsSeparateForCarryoverAttribution() {
        val s=groupMealSessions(listOf(
            food(0,"холодник с картошкой",45.0),food(60,"мороженое",25.0),
        ))
        assertEquals(2,s.size)
    }

    @Test
    fun rescueDextroseNeverJoinsAMeal() {
        val s = groupMealSessions(listOf(food(0, "обед", 50.0), food(10, "декстроза ×2", 8.0)))
        assertEquals(2, s.size)
    }

    @Test
    fun contextTagsAndBlankAreNotFood() {
        val tag = Annotation(tsMs = 0, kind = "text", content = "стресс", id = 99)
        assertEquals(0, groupMealSessions(listOf(tag)).size)
    }

    @Test
    fun totalCarbsNullWhenNothingKnown() {
        val s = groupMealSessions(listOf(food(0, "суп"), food(1, "хлеб")))
        assertNull(s[0].totalCarbs)
    }

    @Test
    fun sessionOfFindsByNoteId() {
        val a = food(0, "суп")
        val b = food(1, "хлеб")
        val s = groupMealSessions(listOf(a, b))
        assertEquals(s[0], sessionOf(s, b.id))
        assertNull(sessionOf(s, 12345L))
    }
}
