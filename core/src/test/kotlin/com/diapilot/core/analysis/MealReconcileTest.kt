package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.LabeledMeal
import com.diapilot.core.collector.MealEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealReconcileTest {

    private val T0 = 1_748_768_400_000L
    private val MIN = 60_000L

    private fun meal(onset: Long, label: String, id: Long) =
        LabeledMeal(
            MealEvent(onset, onset + 60 * MIN, 6.0, 10.0, 4.0, 60.0, null, MealEvent.Kind.UNANNOUNCED),
            id, label,
        )

    private fun note(ts: Long, content: String) = Annotation(ts, "food", content, id = ts)

    @Test
    fun `renamed note relabels the episode it owns`() {
        // Note "a quarter of a yagodyanka" eaten at T0; the rise onset is 20 min later,
        // still labeled "yagodyanka" → propose the fix.
        val notes = listOf(note(T0, "четверть ягодянки ×2"))
        val labeled = listOf(meal(T0 + 20 * MIN, "ягодянка", 14))
        val fixes = reconcileLabels(notes, labeled)
        assertEquals(1, fixes.size)
        assertEquals("ягодянка", fixes[0].from)
        assertEquals("четверть ягодянки ×2", fixes[0].to)
        assertEquals(14L, fixes[0].labelId)
    }

    @Test
    fun `does not link a note to a rise that started BEFORE it`() {
        // Dinner rise at 20:00 (T0), a yagodyanka note some time later — the note is
        // AFTER the onset, so it must not steal the dinner episode.
        val notes = listOf(note(T0 + 134 * MIN, "четверть ягодянки"))
        val labeled = listOf(meal(T0, "немного гречки, салат, отбивная", 44))
        assertTrue(reconcileLabels(notes, labeled).isEmpty())
    }

    @Test
    fun `already-consistent labels produce no fix`() {
        val notes = listOf(note(T0, "смузи"))
        val labeled = listOf(meal(T0 + 15 * MIN, "смузи", 11))
        assertTrue(reconcileLabels(notes, labeled).isEmpty())
    }

    @Test
    fun `several notes on one rise is a composite - left untouched`() {
        // A composite breakfast: many component notes near ONE episode. The
        // episode must NOT be relabeled to any single component.
        val notes = listOf(
            note(T0, "хумус"),
            note(T0 + 2 * MIN, "скрэмбл"),
            note(T0 + 4 * MIN, "хлеб"),
        )
        val labeled = listOf(meal(T0 + 30 * MIN, "завтрак", 16))
        assertTrue(reconcileLabels(notes, labeled).isEmpty())
    }

    @Test
    fun `unrelated single note is not a re-identification`() {
        // One "hummus" note nearest a "salad" episode — different foods, no
        // shared stem → NOT proposed (only same-dish renames are).
        val notes = listOf(note(T0, "хумус"))
        val labeled = listOf(meal(T0 + 20 * MIN, "салат из свежей моркови", 84))
        assertTrue(reconcileLabels(notes, labeled).isEmpty())
    }

    @Test
    fun `canonical shortening of the same dish is proposed`() {
        // "ice cream magnum" → "ice cream" (share the stem) is a valid rename.
        val notes = listOf(note(T0, "мороженое"))
        val labeled = listOf(meal(T0 + 20 * MIN, "мороженое магнум", 26))
        val fixes = reconcileLabels(notes, labeled)
        assertEquals(1, fixes.size)
        assertEquals("мороженое", fixes[0].to)
    }
}
