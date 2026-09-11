package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridFoodEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIGHT MUST MAKE IT INTO THE FITTING CORPUS.
 *
 * Previously an episode candidate opened ONLY on a food entry, requiring 25 g
 * of carbs and a bolus inside the window. Measured on the user's data: of 32
 * episodes not one started at night — all fell between 10:00 and 21:00. So
 * everything that was ever fitted (ISF, insulin shape, food amplitude, the
 * queue) optimized the daytime afternoon regime and was blind to the night,
 * where all of the user's hypoglycemia episodes and the three worst events in
 * the data set live.
 *
 * This test pins the second kind: an episode opened by an INJECTION, judged
 * by its DROP. Mutation: revert `for (opener in doses…)` to rejecting on food
 * before the anchor (`quietNotesMin = 300`), and the night episode disappears.
 */
class FitEpisodeBuilderNightTest {

    private val grid = listOf(60, 120, 180, 240, 300, 360)
    private val h = 3_600_000L

    /** Night: beer at 23:00, bolus at 00:20, line falling. */
    private fun nightNotes(base: Long) = listOf(
        FitEpisodeBuilderV1.Note(base - 80 * 60_000L, "пиво", 18.0, null),
    )

    private fun fall(base: Long) = { t: Long ->
        val min = (t - base) / 60_000.0
        when {
            min < -90 -> 8.0
            min < 0 -> 8.0
            else -> (8.0 - min * 0.016).coerceAtLeast(3.0)
        }
    }

    @Test
    fun `a bolus with no meal opens an episode and prior food travels with it`() {
        val base = 1_787_000_000_000L
        val res = FitEpisodeBuilderV1.build(
            fromMs = base - 6 * h,
            toMs = base + 12 * h,
            notes = nightNotes(base),
            doses = listOf(FitEpisodeBuilderV1.Dose(base, 4.0)),
            basals = emptyList(),
            grid = grid,
            at = fall(base),
            foodOf = { n ->
                HybridFoodEvent(tsMs = n.tsMs, carbsG = n.estCarbs ?: 0.0, text = n.content)
            },
        )
        val ins = res.episodes.filter { it.kind == PhysioAutoFitV1.Episode.Kind.INSULIN }
        assertEquals("укол без еды обязан открыть эпизод: " + res.funnel(), 1, ins.size)
        assertTrue(
            "предшествующая еда обязана войти в эпизод, чтобы модель её вычла",
            ins.single().foods.isNotEmpty(),
        )
        assertTrue("судим по падению", ins.single().real.filterNotNull().min() < ins.single().g0)
    }

    @Test
    fun `a bolus that only holds the line flat is refused`() {
        val base = 1_787_000_000_000L
        val res = FitEpisodeBuilderV1.build(
            fromMs = base - 6 * h,
            toMs = base + 12 * h,
            notes = nightNotes(base),
            doses = listOf(FitEpisodeBuilderV1.Dose(base, 4.0)),
            basals = emptyList(),
            grid = grid,
            at = { 8.0 },
            foodOf = { n ->
                HybridFoodEvent(tsMs = n.tsMs, carbsG = n.estCarbs ?: 0.0, text = n.content)
            },
        )
        assertEquals(
            "плоская линия не несёт сведений о падении",
            0, res.episodes.count { it.kind == PhysioAutoFitV1.Episode.Kind.INSULIN },
        )
        assertTrue(res.refusals.containsKey(FitEpisodeBuilderV1.Refusal.NO_FALL))
    }
}
