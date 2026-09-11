package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsorptionModsTest {
    private val h = 3_600_000L
    private fun note(tsMs: Long, content: String) = Annotation(tsMs, "text", content)

    @Test
    fun `psyllium before the meal stretches ttp`() {
        val meal = 100 * h
        val notes = listOf(note(meal - 20 * 60_000, "псилиум · 5 г"))
        assertEquals(DEFAULT_SLOW_TTP_MULT, absorptionTtpMultiplier(notes, meal), 1e-9)
    }

    @Test
    fun `unrelated or far notes leave ttp unchanged`() {
        val meal = 100 * h
        assertEquals(1.0, absorptionTtpMultiplier(listOf(note(meal - 20 * 60_000, "чай")), meal), 1e-9)
        // Two hours before — out of the pre-meal window.
        assertEquals(1.0, absorptionTtpMultiplier(listOf(note(meal - 2 * h, "клетчатка")), meal), 1e-9)
        assertEquals(1.0, absorptionTtpMultiplier(emptyList(), meal), 1e-9)
    }

    @Test
    fun `fiber tag is a context note, not food`() {
        assertEquals(true, isContextNote("псилиум · 5 г"))
        assertEquals(true, isContextNote("клетчатка"))
        assertEquals(false, isContextNote("гречка"))
    }
}
