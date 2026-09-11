package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.MealEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = 1_748_768_400_000L
private const val MIN = 60_000L

private fun meal(onset: Long) = MealEvent(
    onsetMs = onset, peakMs = onset + 60 * MIN, preBg = 6.0, peakBg = 9.0,
    rise = 3.0, timeToPeakMin = 60.0, bolusUnits = null, kind = MealEvent.Kind.UNANNOUNCED,
)

class SuggestMealLabelTest {

    @Test
    fun `food note before onset is suggested`() {
        val notes = listOf(Annotation(BASE - 20 * MIN, "food", "смузи", id = 1))
        assertEquals("смузи", suggestMealLabel(meal(BASE), notes))
    }

    @Test
    fun `nearest of several notes wins`() {
        val notes = listOf(
            Annotation(BASE - 80 * MIN, "food", "кофе", id = 1),
            Annotation(BASE - 10 * MIN, "food", "смузи", id = 2),
        )
        assertEquals("смузи", suggestMealLabel(meal(BASE), notes))
    }

    @Test
    fun `context tags and distant notes are not suggested`() {
        val notes = listOf(
            Annotation(BASE - 10 * MIN, "text", "алкоголь", id = 1),   // context tag
            Annotation(BASE - 10 * MIN, "text", "коррекция", id = 2),  // bolus tag
            Annotation(BASE - 5 * 60 * MIN, "text", "борщ", id = 3),   // too old
        )
        assertNull(suggestMealLabel(meal(BASE), notes))
    }

    @Test
    fun `a free-text observation is never a dish name`() {
        // "slept poorly" isn't in CONTEXT_TAGS but is kind="text" with no grams —
        // it must not name a detected rise (it is not food).
        val notes = listOf(Annotation(BASE - 10 * MIN, "text", "плохо спал", id = 1))
        assertNull(suggestMealLabel(meal(BASE), notes))
    }
}

class SysLabelsTest {

    private fun labeled(onset: Long, label: String) = com.diapilot.core.collector.LabeledMeal(
        meal(onset), labelId = 1, labelName = label,
    )

    @Test
    fun `continuations credit the nearest preceding food`() {
        val meals = listOf(
            labeled(BASE, "пицца"),
            labeled(BASE + 3 * 60 * MIN, SysLabels.CONTINUATION),
            labeled(BASE + 24 * 60 * MIN, "пицца"),
            labeled(BASE + 27 * 60 * MIN, SysLabels.CONTINUATION),
            labeled(BASE + 48 * 60 * MIN, "суп"),
        )
        val stats = continuationStats(meals)
        assertEquals(listOf("пицца" to 2), stats)
    }

    @Test
    fun `continuation with no preceding food in window is ignored`() {
        val meals = listOf(
            labeled(BASE, "суп"),
            labeled(BASE + 10 * 60 * MIN, SysLabels.CONTINUATION), // 10h later
        )
        assertEquals(0, continuationStats(meals, windowMs = 5L * 3_600_000).size)
    }

    @Test
    fun `system labels are excluded from food profiles`() {
        val stats = labelStats(
            listOf(
                labeled(BASE, "пицца"),
                labeled(BASE + MIN, SysLabels.DAWN),
                labeled(BASE + 2 * MIN, SysLabels.CONTINUATION),
            ),
        )
        assertEquals(1, stats.size)
        assertEquals("пицца", stats[0].name)
    }
}

class ContextStatsTest {

    /** Flat 6.0 baseline; after each tagged moment, 12h of elevated 12.0. */
    private fun scenario(): Pair<List<GlucosePoint>, List<Annotation>> {
        val tagTimes = listOf(BASE + 24 * 60 * MIN, BASE + 96 * 60 * MIN)
        val readings = (0 until 8 * 24 * 12).map { i ->  // 8 days, 5-min cadence
            val ts = BASE + i * 5 * MIN
            val elevated = tagTimes.any { t -> ts in t..(t + 12 * 60 * MIN) }
            GlucosePoint(ts, if (elevated) 12.0 else 6.0)
        }
        val notes = tagTimes.mapIndexed { i, t -> Annotation(t, "text", "алкоголь", id = i + 1L) }
        return readings to notes
    }

    @Test
    fun `after-tag stats differ from baseline`() {
        val (readings, notes) = scenario()
        val stats = contextStats(readings, notes)
        assertEquals(1, stats.size)
        val s = stats[0]
        assertEquals("алкоголь", s.tag)
        assertEquals(2, s.count)
        assertEquals(12.0, s.meanAfter, 0.1)
        assertTrue(s.meanBaseline < 7.0)
        assertEquals(0.0, s.tirAfter, 1.0)       // elevated windows fully above range
        assertTrue(s.tirBaseline > 80)
    }

    @Test
    fun `single occurrence below minCount is dropped`() {
        val (readings, _) = scenario()
        val stats = contextStats(readings, listOf(Annotation(BASE, "text", "разово", id = 9)))
        assertEquals(0, stats.size)
    }

    @Test
    fun `photo-only notes are ignored`() {
        val (readings, _) = scenario()
        val notes = List(3) { Annotation(BASE + it * 60 * MIN, "photo", "фото", "f.jpg", id = it + 1L) }
        assertEquals(0, contextStats(readings, notes).size)
    }
}
