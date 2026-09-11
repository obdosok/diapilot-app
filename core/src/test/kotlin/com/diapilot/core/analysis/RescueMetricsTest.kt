package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.BolusPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueMetricsTest {

    private val day = 86_400_000L

    private fun note(tsMs: Long, content: String, carbs: Double? = null) =
        Annotation(tsMs = tsMs, kind = "food", content = content, estCarbs = carbs)

    @Test
    fun `picks only dextrose notes inside the window`() {
        val notes = listOf(
            note(1_000, "декстроза ×2", 8.0),
            note(2_000, "суп + фрикадельки", 40.0),   // a meal, not a rescue
            note(3_000, "Декстроза ×1", 4.0),          // case-insensitive prefix
            note(9_999, "декстроза ×1", 4.0),          // outside [0, 3_000]
        )
        val ev = rescueEvents(notes, fromMs = 0, toMs = 3_000)
        assertEquals(2, ev.size)
        assertEquals(listOf(1_000L, 3_000L), ev.map { it.tsMs })
        assertEquals(listOf(2, 1), ev.map { it.tablets })
        assertEquals(12.0, ev.sumOf { it.grams }, 1e-9)
    }

    @Test
    fun `a note without carbs counts as one tablet, never dropped`() {
        val ev = rescueEvents(listOf(note(500, "декстроза")), fromMs = 0, toMs = 1_000)
        assertEquals(1, ev.size)
        assertEquals(1, ev.single().tablets)
        assertEquals(DEXTROSE_TABLET_G, ev.single().grams, 1e-9)
    }

    @Test
    fun `rescues per week is a real rate over the span`() {
        // 3 rescues across a 14-day span ⇒ 1.5 per week.
        val notes = (0..2).map { note(it * day, "декстроза ×1", 4.0) }
        val ev = rescueEvents(notes, fromMs = 0, toMs = 14 * day)
        assertEquals(1.5, rescuesPerWeek(ev, fromMs = 0, toMs = 14 * day)!!, 1e-9)
    }

    @Test
    fun `too-short a span yields no rate rather than a wild one`() {
        val ev = rescueEvents(listOf(note(0, "декстроза ×1", 4.0)), 0, day)
        assertNull(rescuesPerWeek(ev, fromMs = 0, toMs = day, minDays = 3))
    }

    @Test
    fun `events come back ascending regardless of input order`() {
        val notes = listOf(
            note(5_000, "декстроза ×1", 4.0),
            note(1_000, "декстроза ×1", 4.0),
        )
        val ev = rescueEvents(notes, 0, 10_000)
        assertTrue(ev.map { it.tsMs } == ev.map { it.tsMs }.sorted())
    }

    @Test fun `steep fall bought by rescue is a censored safety failure not a clean success`() {
        val t=2_000_000L
        val rescue=RescueEvent(t,40.0,8)
        val glucose=listOf(
            GlucosePoint(t-25*60_000L,15.7),GlucosePoint(t,10.6),
            GlucosePoint(t+15*60_000L,6.6),GlucosePoint(t+30*60_000L,8.5),
        )
        val result=rescueSafetyEpisodesV1(listOf(rescue),glucose,listOf(BolusPoint(t-22*60_000L,6.0))).single()
        assertEquals(RescueOutcomeV1.AVERTED_LOW_COMPATIBLE,result.outcome)
        assertEquals(6.0,result.activeRecentBolusUnits,1e-9)
        assertTrue(result.linearMinutesToLow!!<45)
    }
}
