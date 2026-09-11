package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.MealEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private val UTC = ZoneId.of("UTC")
private const val DAY = 24L * 3_600_000
private const val HOUR = 3_600_000L

// A midnight UTC anchor so hour-of-day math is exact.
private const val T0 = 1_736_226_000_000L - (1_736_226_000_000L % DAY)

/** A weeks-long habit: smoothie every day at 12:48, breakfast at 14:00. */
private fun habitNotes(): List<Annotation> = buildList {
    for (day in 0 until 6) {
        add(Annotation(T0 + day * DAY + (12 * 60 + 48) * 60_000L, "food", "смузи", estCarbs = 33.0, id = day * 2 + 1L))
        add(Annotation(T0 + day * DAY + 14 * HOUR, "food", "завтрак (хумус, скрэмбл)", estCarbs = 39.0, id = day * 2 + 2L))
    }
    // Rescue carbs all morning — must never become the prediction.
    for (day in 0 until 6) {
        add(Annotation(T0 + day * DAY + 12 * HOUR, "food", "декстроза ×1", estCarbs = 4.0, id = 100 + day.toLong()))
    }
}

private fun meal(onset: Long) = MealEvent(
    onsetMs = onset, peakMs = onset + 40 * 60_000, preBg = 6.0, peakBg = 9.0,
    rise = 3.0, timeToPeakMin = 40.0, bolusUnits = null, kind = MealEvent.Kind.UNANNOUNCED,
)

class FoodSuggestTest {

    // "now" = day 6, 13:00 — smoothie o'clock.
    private val now = T0 + 6 * DAY + 13 * HOUR

    @Test
    fun `dish is predicted from the hour, dextrose never wins`() {
        val p = predictDishAt(habitNotes(), now - 10 * 60_000, zone = UTC)!!
        assertEquals("смузи", p.dish)
        assertEquals(33.0, p.grams!!, 1e-9)
        assertEquals(6, p.nHistory)
    }

    @Test
    fun `off-hour trigger has no confident prediction`() {
        // 04:00 — nothing in the history eats then.
        assertNull(predictDishAt(habitNotes(), T0 + 6 * DAY + 4 * HOUR, zone = UTC))
    }

    @Test
    fun `meal-sized dose without a note suggests the dish`() {
        val s = suggestFood(
            nowMs = now, notes = habitNotes(), detectedMeals = emptyList(),
            boluses = listOf(BolusPoint(now - 20 * 60_000, 4.5, null)), zone = UTC,
        )
        assertEquals(1, s.size)
        assertEquals(SuggestTrigger.DOSE, s[0].trigger)
        assertEquals(4.5, s[0].units!!, 1e-9)
        assertEquals("смузи", s[0].prediction!!.dish)
    }

    @Test
    fun `a nearby food note silences the trigger`() {
        val logged = habitNotes() + Annotation(now - 15 * 60_000, "food", "смузи", estCarbs = 33.0, id = 500)
        val s = suggestFood(
            nowMs = now, notes = logged, detectedMeals = listOf(meal(now - 20 * 60_000)),
            boluses = listOf(BolusPoint(now - 20 * 60_000, 4.5, null)), zone = UTC,
        )
        assertTrue(s.isEmpty())
    }

    @Test
    fun `correction and small doses never suggest food`() {
        val s = suggestFood(
            nowMs = now, notes = habitNotes(), detectedMeals = emptyList(),
            boluses = listOf(
                BolusPoint(now - 30 * 60_000, 3.0, "коррекция"),
                BolusPoint(now - 40 * 60_000, 1.5, null),        // sub-meal size
                BolusPoint(now - 50 * 60_000, 2.0, "воздух"),
            ),
            zone = UTC,
        )
        assertTrue(s.isEmpty())
    }

    @Test
    fun `bolus and detection within 45 min merge into one suggestion`() {
        val s = suggestFood(
            nowMs = now, notes = habitNotes(),
            detectedMeals = listOf(meal(now - 25 * 60_000)),
            boluses = listOf(BolusPoint(now - 35 * 60_000, 5.0, "на еду")),
            zone = UTC,
        )
        assertEquals(1, s.size)
        assertEquals(SuggestTrigger.DETECTED_RISE, s[0].trigger)   // onset wins as the food time
        assertEquals(5.0, s[0].units!!, 1e-9)         // the dose tags along
        assertEquals("смузи", s[0].prediction!!.dish)
    }

    @Test
    fun `off-hour trigger still surfaces, just without a guess`() {
        val at = T0 + 6 * DAY + 4 * HOUR
        val s = suggestFood(
            nowMs = at + 10 * 60_000, notes = habitNotes(), detectedMeals = emptyList(),
            boluses = listOf(BolusPoint(at, 3.0, null)), zone = UTC,
        )
        assertEquals(1, s.size)
        assertNull(s[0].prediction)
    }
}

class DishRecallTest {

    private val sostav = "СОСТАВ: хумус ×1 = 8 угл · 100 порц\n" +
        "СОСТАВ: скрэмбл ×1 = 2 угл · 120 порц\n" +
        "СОСТАВ: хлеб ×2 = 29 угл · 60 порц"

    private fun notes() = listOf(
        // older breakfast, with a composition
        Annotation(T0 + 14 * HOUR, "food", "завтрак (хумус, скрэмбл, хлеб)", analysis = sostav, estCarbs = 39.0, id = 1),
        // newer SAME dish, composition edited to 2 slices less bread
        Annotation(
            T0 + DAY + 14 * HOUR, "food", "завтрак (хумус, скрэмбл, хлеб)",
            analysis = sostav.replace("хлеб ×2 = 29", "хлеб ×1 = 15"), estCarbs = 25.0, id = 2,
        ),
        // a dish logged WITHOUT a composition — must not be recalled
        Annotation(T0 + 12 * HOUR, "food", "смузи", estCarbs = 33.0, id = 3),
    )

    @Test
    fun `the newest composition for a dish is recalled with its own carbs`() {
        val r = recallComposition("завтрак (хумус, скрэмбл, хлеб)", lastCompositions(notes()))!!
        assertTrue("newest wins", r.analysis.contains("хлеб ×1 = 15"))
        assertEquals(25.0, r.grams!!, 1e-9)   // carbs come from the SAME note as the SOSTAV
    }

    @Test
    fun `a dish never logged with a composition has nothing to recall`() {
        assertNull(recallComposition("смузи", lastCompositions(notes())))
        assertNull(recallComposition("борщ", lastCompositions(notes())))
    }

    @Test
    fun `recall matches on the normalized name, not the exact wording`() {
        // normalizeFoodName drops the parenthetical — the chip's short label
        // must still find the full note's composition.
        val r = recallComposition("завтрак", lastCompositions(notes()))
        assertTrue("short label finds it", r != null)
    }
}
