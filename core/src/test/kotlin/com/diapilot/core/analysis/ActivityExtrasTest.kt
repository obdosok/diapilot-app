package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.HrPoint
import com.diapilot.core.collector.StepBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityExtrasTest {
    private val h = 3_600_000L

    @Test
    fun stepWindowsFromBriskWalk() {
        // 30 min of ~60 steps/min buckets → one bout.
        val steps = (0 until 30).map { StepBucket(it * 60_000L, (it + 1) * 60_000L, 60) }
        val w = sustainedStepWindows(steps)
        assertEquals(1, w.size)
        assertTrue(w[0].elevatedMin >= 25)
    }

    @Test
    fun idleBucketsAreNotActivity() {
        val steps = (0 until 30).map { StepBucket(it * 60_000L, (it + 1) * 60_000L, 5) }
        assertTrue(sustainedStepWindows(steps).isEmpty())
    }

    @Test
    fun mergeUnionsOverlappingBouts() {
        val a = listOf(ActivityWindow(0, 30 * 60_000, 25))
        val b = listOf(ActivityWindow(20 * 60_000, 50 * 60_000, 20))  // overlaps a
        val m = mergeActivityWindows(a, b)
        assertEquals(1, m.size)
        assertEquals(0, m[0].startMs)
        assertEquals(50 * 60_000, m[0].endMs)
        assertEquals(25, m[0].elevatedMin)   // larger estimate kept
    }

    @Test
    fun mergeKeepsSeparateBouts() {
        val a = listOf(ActivityWindow(0, 30 * 60_000, 25))
        val b = listOf(ActivityWindow(3 * h, 3 * h + 30 * 60_000, 25))
        assertEquals(2, mergeActivityWindows(a, b).size)
    }

    @Test
    fun nightAfterEveningActivityRunsLower() {
        // Day 0 evening walk; night of day 1 has lows. Day 3 night (no prior
        // walk) sits comfortably. Need ≥3 nights each side.
        val day = 24 * h
        fun hourOf(ts: Long) = ((ts / h) % 24).toInt()
        fun dayOf(ts: Long) = ts / day
        val windows = listOf(0, 2, 4).map { d ->
            val end = d * day + 20 * h              // 20:00 evening bout on days 0,2,4
            ActivityWindow(end - 30 * 60_000, end, 25)
        }
        val readings = mutableListOf<GlucosePoint>()
        // Nights (01:00-05:00) of days 1,3,5 → after activity (low ~3.5);
        // nights of days 6,7,8 → baseline (~7.0).
        fun night(d: Long, base: Double) {
            for (m in 0 until 240 step 5) readings.add(GlucosePoint(d * day + h + m * 60_000L, base))
        }
        night(1, 3.5); night(3, 3.6); night(5, 3.4)
        night(6, 7.0); night(7, 7.2); night(8, 6.9)
        val o = activityNightOutcomes(windows, readings, ::hourOf, ::dayOf, rangeLo = 3.9)!!
        assertEquals(3, o.nightsAfterActivity)
        assertEquals(3, o.baselineNights)
        assertTrue(o.lowsAfterActivity == 3 && o.baselineLows == 0)
        assertTrue(o.meanAfterActivity < o.meanBaseline)
    }

    @Test
    fun manualActivityTagsBecomeWindows() {
        val notes = listOf(
            com.diapilot.core.collector.Annotation(10 * h, "tag", "прогулка"),
            com.diapilot.core.collector.Annotation(20 * h, "text", "Тренировка"),  // case-insensitive
            com.diapilot.core.collector.Annotation(30 * h, "food", "смузи"),        // not activity
        )
        val w = activityWindowsFromNotes(notes, defaultMin = 40)
        assertEquals(2, w.size)
        assertEquals(40, w[0].elevatedMin)
        assertEquals(10 * h + 40 * 60_000L, w[0].endMs)
    }

    @Test
    fun durationRidesInTheNoteText() {
        val notes = listOf(
            // "finished" entry: the composer backdates ts, text carries length.
            com.diapilot.core.collector.Annotation(10 * h, "tag", "прогулка · 25 мин"),
            com.diapilot.core.collector.Annotation(20 * h, "tag", "Тренировка · 90 мин"),
            com.diapilot.core.collector.Annotation(30 * h, "tag", "бег"),          // bare = default
            com.diapilot.core.collector.Annotation(40 * h, "tag", "прогулка · 999 мин"), // clamped
        )
        val w = activityWindowsFromNotes(notes, defaultMin = 40)
        assertEquals(4, w.size)
        assertEquals(25, w[0].elevatedMin)
        assertEquals(10 * h + 25 * 60_000L, w[0].endMs)
        assertEquals(90, w[1].elevatedMin)
        assertEquals(40, w[2].elevatedMin)
        assertEquals(360, w[3].elevatedMin)
    }

    @Test
    fun nightOutcomesNullWithoutEnoughNights() {
        assertNull(activityNightOutcomes(emptyList(), emptyList(), { 0 }, { 0 }, 3.9))
    }

    // ── The shared detector (one definition for model + chart) ───────────────

    // CADENCES RECOMPUTED ONTO THE TRUE SCALE. The old 55 and 95 were set
    // against a feed where steps from two Health Connect apps were summed
    // and came out double. In true steps that is 27 and 47 — and 47 matches
    // a measured walking cadence exactly (150 min, 47.4 steps/min).
    //
    // THE POINT OF THE TEST IS UNCHANGED: the calibrated detector must still
    // drop a shuffle around a shop and keep a real walk. Only the numbers
    // were rewritten, and rewritten in the same direction the data moved.

    /** A shuffle around a shop: 30 min at ~27 steps/min. Clears the legacy
     *  floor of ≥20, fails the calibrated ≥40 — this is exactly the phantom class. */
    private fun strollSteps() =
        (0 until 30).map { StepBucket(it * 60_000L, (it + 1) * 60_000L, 27) }

    /** A real walk: 25 min at ~47 steps/min — a measured cadence. */
    private fun walkSteps() =
        (0 until 25).map { StepBucket(it * 60_000L, (it + 1) * 60_000L, 47) }

    @Test
    fun calibratedDropsTheStrollAndKeepsTheWalk() {
        assertTrue(
            "legacy must count a 27/min shuffle as activity",
            detectActivityWindows(emptyList(), strollSteps(), emptyList(), calibrated = false).isNotEmpty(),
        )
        assertTrue(
            "calibrated must NOT count a 27/min shuffle as activity",
            detectActivityWindows(emptyList(), strollSteps(), emptyList(), calibrated = true).isEmpty(),
        )
        assertEquals(
            1,
            detectActivityWindows(emptyList(), walkSteps(), emptyList(), calibrated = true).size,
        )
    }

    @Test
    fun calibratedHrPathNeedsAbsolute120NotAMultipleOfRest() {
        // Resting ~76 with a long stretch at 95: 1.25×median ≈ 95 ⇒ the LEGACY
        // path fires (this is how plain sleep became a bout); ≥120 does not.
        val hr = buildList {
            for (m in 0 until 200) add(HrPoint(m * 60_000L, 76.0))
            for (m in 200 until 240) add(HrPoint(m * 60_000L, 95.0))
        }
        assertTrue(
            "legacy HR path fires on 95 bpm",
            detectActivityWindows(hr, emptyList(), emptyList(), calibrated = false).isNotEmpty(),
        )
        assertTrue(
            "calibrated HR path must need ≥120",
            detectActivityWindows(hr, emptyList(), emptyList(), calibrated = true).isEmpty(),
        )
        val cardio = buildList {
            for (m in 0 until 200) add(HrPoint(m * 60_000L, 76.0))
            for (m in 200 until 230) add(HrPoint(m * 60_000L, 125.0))
        }
        assertEquals(
            1,
            detectActivityWindows(cardio, emptyList(), emptyList(), calibrated = true).size,
        )
    }

    @Test
    fun notesCountInBothModes() {
        val notes = listOf(
            com.diapilot.core.collector.Annotation(10 * h, "tag", "прогулка · 30 мин"),
        )
        for (cal in listOf(false, true)) {
            val w = detectActivityWindows(emptyList(), emptyList(), notes, calibrated = cal)
            assertEquals("notes must survive calibrated=$cal", 1, w.size)
            assertEquals(30, w[0].elevatedMin)
        }
    }

    /** The ≥20-MINUTE floor is the real separator (a shop trip has brisk bursts
     *  but no duration), so pin it with a bout that is brisk enough and just
     *  SHORT enough. Without this, ACTIVITY_MIN_MINUTES 20→10 survives every
     *  other test here. */
    @Test
    fun calibratedNeedsTwentySustainedMinutesNotJustCadence() {
        // 15 min at 95 steps/min — clears the cadence bar, fails the duration one.
        val brisk15 = (0 until 15).map { StepBucket(it * 60_000L, (it + 1) * 60_000L, 95) }
        assertTrue(
            "a 15-min brisk burst must NOT count — duration is the separator",
            detectActivityWindows(emptyList(), brisk15, emptyList(), calibrated = true).isEmpty(),
        )
        // 22 min of the same cadence does count.
        val brisk22 = (0 until 22).map { StepBucket(it * 60_000L, (it + 1) * 60_000L, 95) }
        assertEquals(
            1, detectActivityWindows(emptyList(), brisk22, emptyList(), calibrated = true).size,
        )
        // And the HR floor needs its own duration too: 15 min at 125 bpm is out.
        val hr15 = buildList {
            for (m in 0 until 200) add(HrPoint(m * 60_000L, 70.0))
            for (m in 200 until 215) add(HrPoint(m * 60_000L, 125.0))
        }
        assertTrue(
            detectActivityWindows(hr15, emptyList(), emptyList(), calibrated = true).isEmpty(),
        )
    }

    /** The LEGACY values themselves, stated as literals rather than re-derived
     *  from the same defaults — [legacyModeMatchesTheOldHandRolledComposition]
     *  compares two calls to the same primitives, so it pins the composition but
     *  moves with any default it is supposed to be guarding. */
    @Test
    fun legacyThresholdsAreTheOldLiteralValues() {
        // LITERALS RECOMPUTED: 40/min → 20/min. The duration (12 min) is NOT
        // touched — it is measured in minutes, and it was the step scale that moved.
        //
        // This test is a guard against the defaults quietly drifting, and it
        // fired exactly for that reason when the floor was halved. The edit
        // is deliberate: the old 40 was set against a feed where steps from
        // two Health Connect apps were summed and came out double
        // (`StepStream`). The literals stay literals and are still not
        // derived from the defaults.

        // 20/min for 12 min — the legacy floor: 23/min × 13 min must count…
        val justOver = (0 until 13).map { StepBucket(it * 60_000L, (it + 1) * 60_000L, 23) }
        assertEquals(
            1, detectActivityWindows(emptyList(), justOver, emptyList(), calibrated = false).size,
        )
        // …17/min (below 20) must not, at any duration.
        val underCadence = (0 until 40).map { StepBucket(it * 60_000L, (it + 1) * 60_000L, 17) }
        assertTrue(
            detectActivityWindows(emptyList(), underCadence, emptyList(), calibrated = false).isEmpty(),
        )
        // …and 23/min for only 10 min (under 12) must not either.
        val tooShort = (0 until 10).map { StepBucket(it * 60_000L, (it + 1) * 60_000L, 23) }
        assertTrue(
            detectActivityWindows(emptyList(), tooShort, emptyList(), calibrated = false).isEmpty(),
        )
    }

    /** calibrated=false must reproduce the legacy composition EXACTLY — this is
     *  the toggle-off byte-identity the whole change rests on. */
    @Test
    fun legacyModeMatchesTheOldHandRolledComposition() {
        val hr = buildList {
            for (m in 0 until 200) add(HrPoint(m * 60_000L, 70.0))
            for (m in 200 until 240) add(HrPoint(m * 60_000L, 100.0))
        }
        val steps = strollSteps()
        val notes = listOf(com.diapilot.core.collector.Annotation(50 * h, "tag", "бег"))
        val expected = mergeActivityWindows(
            mergeActivityWindows(
                hrBaseline(hr)?.let { sustainedHrWindows(hr, it) } ?: emptyList(),
                sustainedStepWindows(steps),
            ),
            activityWindowsFromNotes(notes),
        )
        assertEquals(expected, detectActivityWindows(hr, steps, notes, calibrated = false))
    }
}
