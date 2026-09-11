package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "If we know a smoothie finished absorbing in 60 minutes, then it should not
 * interfere with breakfast 65 minutes after the smoothie" — the user's rule.
 *
 * Mutation-checked. Fixtures use the user's own dishes and their own calories.
 */
class FloatingSessionGapTest {

    private var id = 0L
    private fun note(minutes: Long, text: String, carbs: Double, protein: Double, fat: Double) =
        Annotation(
            id = ++id, tsMs = minutes * 60_000, kind = "food", content = text,
            mediaRef = null, analysis = "БЕЛКИ: $protein г\nЖИРЫ: $fat г", estCarbs = carbs,
        )

    private fun smoothie(min: Long) = note(min, "смузи", 22.0, 2.0, 1.0)
    private fun breakfast(min: Long) = note(min, "завтрак", 53.0, 22.0, 26.0)
    private fun crackers(min: Long) = note(min, "сухарики", 8.0, 2.0, 3.0)

    private fun extentOf(session: List<Annotation>): Double = MealCaloricExtentV1.extentMin(
        session.sumOf { it.estCarbs ?: 0.0 },
        session.sumOf { parseFoodNutrition(it.analysis).proteinG ?: 0.0 },
        session.sumOf { parseFoodNutrition(it.analysis).fatG ?: 0.0 },
    )

    private fun grouped(notes: List<Annotation>, floating: Boolean) =
        groupMealSessions(notes, extentMinOf = if (floating) ::extentOf else null)

    @Test
    fun `the rate is the shipped 30 g per hour restated, not a new number`() {
        // 30 g of carbohydrate at 4 kcal/g is 120 kcal/h — a pure-carb meal must
        // time out identically under either statement.
        assertEquals(60.0, MealCaloricExtentV1.extentMin(30.0, 0.0, 0.0), 1e-9)
        assertEquals(120.0, MealCaloricExtentV1.extentMin(60.0, 0.0, 0.0), 1e-9)
    }

    /** The user's example, verbatim: a smoothie cannot reach a meal 65 minutes later. */
    @Test
    fun `a light liquid meal does not swallow the next one`() {
        val sessions = grouped(listOf(smoothie(0), breakfast(65)), floating = true)
        assertEquals("the smoothie kept the breakfast", 2, sessions.size)
    }

    /** ...but a heavy one does, and that is the case the flat 45 gets wrong. */
    @Test
    fun `a heavy meal still reaches an hour later`() {
        val flat = grouped(listOf(breakfast(0), crackers(55)), floating = false)
        val floating = grouped(listOf(breakfast(0), crackers(55)), floating = true)
        assertEquals("the flat gap should still split these", 2, flat.size)
        assertEquals("the breakfast was still emptying at 55 min", 1, floating.size)
    }

    /** The change is ONE-DIRECTIONAL: it may join more, never less. */
    @Test
    fun `nothing that joins today stops joining`() {
        listOf(10L, 20L, 44L).forEach { gap ->
            assertEquals(
                "gap=$gap split under the floating rule",
                1, grouped(listOf(smoothie(0), crackers(gap)), floating = true).size,
            )
        }
    }

    /** Reach is measured from the session's START, so a small final course
     *  cannot cut a long dinner short. */
    @Test
    fun `a late small course does not shorten the session's reach`() {
        // Breakfast at 0 reaches ~267 min. A cracker at 200 joins; the next at
        // 250 must still join, because the session started at 0 and is not done.
        val sessions = grouped(listOf(breakfast(0), crackers(200), crackers(250)), floating = true)
        assertEquals(1, sessions.size)
    }

    /**
     * Reach is spent from the session's START, so a joined course does NOT
     * refill it.
     *
     * The previous fixture could not see this: it happened to join under both
     * readings, and mutation showed it — dropping the elapsed-time subtraction
     * failed nothing. Here the third note falls inside the reach measured from
     * the SECOND note but outside the reach measured from the FIRST, so only the
     * correct reading splits it.
     *
     *   60 g carb at 0  -> 240 kcal -> 120 min of reach
     *   4 g carb at 100 -> joins, session now 256 kcal -> 128 min FROM ZERO
     *   next at 160     -> 60 min after the last note, 160 after the start
     *
     * The last gap has to EXCEED 45 minutes or the floor decides it, and the
     * floor is not what this test is about. The first attempt used 140 and
     * failed on correct code for exactly that reason — the flat rule would have
     * joined it too, so nothing was being distinguished.
     */
    @Test
    fun `a joined course does not refill the session's reach`() {
        val big = note(0, "большой приём", 60.0, 0.0, 0.0)
        val small = note(100, "добавка", 4.0, 0.0, 0.0)
        val later = note(160, "ещё", 4.0, 0.0, 0.0)
        assertEquals(2, grouped(listOf(big, small, later), floating = true).size)
    }

    /** The cap is real and must hold, or one huge meal eats the day. */
    @Test
    fun `reach is capped at the analysis window`() {
        val huge = MealCaloricExtentV1.extentMin(300.0, 100.0, 100.0)
        assertEquals(MealCaloricExtentV1.MAX_EXTENT_MIN, huge, 1e-9)
        assertTrue(MealCaloricExtentV1.extentMin(1.0, 0.0, 0.0) >= MealCaloricExtentV1.MIN_EXTENT_MIN)
    }

    @Test
    fun `the default is exactly today's behaviour`() {
        val notes = listOf(breakfast(0), crackers(55), smoothie(200))
        assertEquals(3, groupMealSessions(notes).size)
    }
}
