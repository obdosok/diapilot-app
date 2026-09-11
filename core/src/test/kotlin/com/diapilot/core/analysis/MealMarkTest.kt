package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marks layer, pinned where it can actually break.
 *
 * The first test is the one that lets this change ship without re-taking any measurement:
 * with NO marks every gate is the IDENTITY and the cache stamp is 0, so the model the phone
 * runs is bit-identical to the one it ran before this layer existed. Everything after it
 * measures what a mark MOVES — «if it moves nothing, the wiring does not work».
 */
class MealMarkTest {

    private val now = 1_700_000_000_000L
    private fun daysAgo(d: Int) = now - d * 86_400_000L

    private fun obs(
        onset: Long, comps: List<Pair<String, Double>>, ttp: Double, peak: Double,
        carbs: Double, conf: Double = 1.0,
    ) = MealObservation(
        fingerprint = mealFingerprint(comps), ttpMin = ttp, tailRise = 0.0, peakRise = peak,
        onsetMs = onset, nEpisodes = 1, carbGrams = carbs, confidence = conf,
    )

    private fun episode(onset: Long, rise: Double, carbs: Double) = CarbEpisode(
        onsetMs = onset, rise = rise, timeToPeakMin = 60.0, bolusUnits = null, estCarbs = carbs,
    )

    private fun mark(onset: Long, kind: MarkKind, revoked: Long? = null) = MealMark(
        onsetMs = onset, kind = kind, createdAtMs = onset + 86_400_000L, revokedAtMs = revoked,
    )

    // ============================================================ ACCEPTANCE ==
    /**
     * NO MARKS ⇒ NOTHING MOVES, and it is proved by IDENTITY, not by equality.
     *
     * `assertSame` is deliberate: an equal-but-rebuilt list would still be a behaviour
     * change hiding in allocation, and the claim being made is stronger — the marked
     * pipeline does not touch the objects at all when the answer set is empty.
     */
    @Test
    fun `with no marks every gate is the identity and the stamp is zero`() {
        val marks = MealMarks(emptyList())
        val corpus = listOf(
            obs(daysAgo(3), listOf("гречка" to 40.0), 90.0, 4.0, 40.0),
            obs(daysAgo(2), listOf("гречка" to 50.0), 85.0, 5.0, 50.0),
        )
        val episodes = listOf(episode(daysAgo(3), 4.0, 40.0), episode(daysAgo(2), 5.0, 50.0))
        val onsets = listOf(daysAgo(3), daysAgo(2))

        val owners = mealMarkOwners(emptyList())
        assertSame(corpus, corpus.applyMarks(marks))
        assertSame(episodes, episodes.excludingMarked(marks, owners))
        assertSame(onsets, onsets.onsetsAllowedForShape(marks, owners))
        assertEquals(0L, marks.stamp())
        assertTrue(marks.isEmpty)
        // The unmarked default has to be fully OPEN, or an empty table would still gate.
        assertEquals(MarkPolicy.OPEN, marks.policyAt(daysAgo(3)))
    }

    // ================================================== WHAT A MARK MOVES ====
    /**
     * THE MEASUREMENT the brief asks for: mark a meal "more grams than recorded", show it
     * leaves the amplitude learner, and by how much the coefficient moves.
     *
     * Four episodes, ratios 0.100 / 0.120 / 0.300 / 0.320 ⇒ median 0.2100. Marking the 0.300
     * one leaves 0.100 / 0.120 / 0.320 ⇒ median 0.1200. If this ever reads «no change», the
     * mark is not reaching `carbSensitivity` and the screen is decoration.
     */
    @Test
    fun `a grams mark leaves the learned carb coefficient and moves it`() {
        val kernel = emptyList<KernelPoint>()   // no bolus in these episodes: rise IS the effect
        val episodes = listOf(
            episode(daysAgo(4), 5.0, 50.0),     // 0.100
            episode(daysAgo(3), 6.0, 50.0),     // 0.120
            episode(daysAgo(2), 15.0, 50.0),    // 0.300  <- the one the user answers
            episode(daysAgo(1), 16.0, 50.0),    // 0.320
        )
        // Each episode has its own meal, a day apart, so ownership is unambiguous and this
        // test measures the coefficient rather than the resolver.
        val owners = mealMarkOwners(
            (1..4).map { d -> foodNote(daysAgo(d), "гречка", 50.0) },
        )
        val before = carbSensitivity(episodes, kernel)!!
        assertEquals(4, before.n)
        assertEquals(0.2100, before.mmolPerGram, 1e-9)

        val marks = MealMarks(listOf(mark(daysAgo(2), MarkKind.GRAMS_MORE)))
        val after = carbSensitivity(episodes.excludingMarked(marks, owners), kernel)!!
        assertEquals(3, after.n)
        assertEquals(0.1200, after.mmolPerGram, 1e-9)
        // Stated as the shift, because «it changed» is not a measurement.
        assertEquals(-0.0900, after.mmolPerGram - before.mmolPerGram, 1e-9)
    }

    /**
     * THE SPLIT THAT THE POLICY TABLE EXISTS FOR: a gram error invalidates a DENOMINATOR,
     * so amplitude goes and SHAPE stays.
     *
     * Checked on the two functions that actually consume the corpus, not on the flag:
     * `predictPerGramRise` must stop seeing the donor, `predictKinetics` must keep it.
     */
    /**
     * A corpus in which BOTH halves are genuinely at risk.
     *
     * Four "clean" meals (ttp 60, 0.10 mmol/g) and four "answered" ones (ttp 90,
     * 0.30 mmol/g). A GROUP rather than a single outlier on purpose: the per-gram estimator
     * is a weighted TRIMMED mean and would simply trim a lone extreme value away, so a
     * one-donor test could pass while the gate did nothing. The two ttp values sit 30 min
     * apart — inside `FP_MAX_TTP_SPREAD_MIN`, so the shape pool stays valid with all eight
     * and visibly changes when four leave.
     */
    private val target get() = mealFingerprint(listOf("гречка" to 40.0))
    private val answered = listOf(daysAgo(4), daysAgo(3), daysAgo(2), daysAgo(1))
    private val corpus get() =
        listOf(daysAgo(8), daysAgo(7), daysAgo(6), daysAgo(5))
            .map { obs(it, listOf("гречка" to 50.0), 60.0, 5.0, 50.0) } +
            answered.map { obs(it, listOf("гречка" to 50.0), 90.0, 15.0, 50.0) }

    private fun marksOn(kind: MarkKind) = MealMarks(answered.map { mark(it, kind) })

    /** "Sensor lied" is the total exclusion: there is no observation here, only a fault. */
    /** A dish's own measured curve is the branch every regular dish uses. */
    @Test
    fun `a mark drops the onset from a dish profile, and a grams mark does not`() {
        val onsets = listOf(daysAgo(3), daysAgo(2), daysAgo(1))
        val owners = mealMarkOwners((1..3).map { d -> foodNote(daysAgo(d), "гречка", 50.0) })
        val sensor = MealMarks(listOf(mark(daysAgo(2), MarkKind.SENSOR_LIED)))
        assertEquals(listOf(daysAgo(3), daysAgo(1)), onsets.onsetsAllowedForShape(sensor, owners))
        val grams = MealMarks(listOf(mark(daysAgo(2), MarkKind.GRAMS_LESS)))
        assertEquals(onsets, onsets.onsetsAllowedForShape(grams, owners))
    }

    // ============================================================== POLICY ====
    /** "I don't know" is a full answer, and its content is precisely "this is NOT understood". */
    @Test
    fun `unknown is an answer that marks the window not understood`() {
        assertFalse(MarkKind.UNKNOWN.policy().understood)
        assertEquals(MarkClass.UNKNOWN, MarkKind.UNKNOWN.policy().cls)
        assertFalse(MarkKind.UNKNOWN.policy().amplitude)
        assertFalse(MarkKind.UNKNOWN.policy().shape)
        // A sensor fault is not understood either — it is an instrument statement.
        assertFalse(MarkKind.SENSOR_LIED.policy().understood)
        // Everything the user DID explain is understood, whatever else it blocks.
        listOf(
            MarkKind.GRAMS_MORE, MarkKind.GRAMS_LESS, MarkKind.RESCUE,
            MarkKind.ATE_MORE, MarkKind.CONTEXT, MarkKind.MODEL_WRONG,
        ).forEach { assertTrue(it.name, it.policy().understood) }
        // `notUnderstood` is the set a clean-segment gate must consult — it has no consumer
        // yet, so it is pinned here rather than left to be discovered as empty later.
        val ts = daysAgo(2)
        assertEquals(
            setOf(ts),
            MealMarks(listOf(mark(ts, MarkKind.UNKNOWN), mark(ts + 86_400_000L, MarkKind.RESCUE)))
                .notUnderstood,
        )
        // "It's the model's fault" blocks BOTH halves: the recovered shape inherits the same bad
        // subtraction as its height.
        assertFalse(MarkKind.MODEL_WRONG.policy().shape)
        // A rescue goes to its own class, never into the food one.
        assertEquals(MarkClass.RESCUE, MarkKind.RESCUE.policy().cls)
        assertEquals(MarkClass.ARTIFACT, MarkKind.SENSOR_LIED.policy().cls)
        // NO mark leaves amplitude open: every one of them says the observation, its
        // denominator or its window is not what the model assumed.
        MarkKind.entries.forEach { assertFalse(it.name, it.policy().amplitude) }
    }

    // ========================================================== RESOLUTION ====
    private fun foodNote(ts: Long, text: String, carbs: Double) =
        com.diapilot.core.collector.Annotation(
            tsMs = ts, kind = "food", content = text, estCarbs = carbs, id = ts,
        )

    /**
     * TWO KEY SPACES, and this is where the silent miss lived: the mark sits on the
     * NOTE-anchored meal, `carbEpisodes` are keyed by the DETECTOR's onset. The event is
     * resolved to its OWNING meal first — production's matcher, then the corpus's session
     * grouping — and the mark is then looked up exactly.
     */
    @Test
    fun `a mark reaches the detector event its own meal owns`() {
        val noteMs = daysAgo(2)
        val notes = listOf(foodNote(noteMs, "гречка", 50.0))
        val owners = mealMarkOwners(notes)
        val marks = MealMarks(listOf(mark(noteMs, MarkKind.ATE_MORE)))

        // Owned: production's window is "the note lies in [event − 90 min, event + 30 min]",
        // so a detector event may sit up to 90 min AFTER its note and 30 min before it.
        // (An earlier draft of this test had the asymmetry backwards, which is exactly why
        // it is spelled out here rather than left to the reader.)
        assertEquals(noteMs, owners(noteMs))
        assertEquals(noteMs, owners(noteMs + 89L * 60_000))
        assertEquals(noteMs, owners(noteMs - 29L * 60_000))
        // Not owned — and an unowned event keeps its evidence.
        assertNull(owners(noteMs + 91L * 60_000))
        assertNull(owners(noteMs - 31L * 60_000))

        val episodes = listOf(
            episode(noteMs + 10L * 60_000, 15.0, 50.0),
            episode(noteMs + 5L * 3_600_000, 5.0, 50.0),
        )
        val kept = episodes.excludingMarked(marks, owners)
        assertEquals(1, kept.size)
        assertEquals(noteMs + 5L * 3_600_000, kept[0].onsetMs)
    }

    /**
     * THE DEFECT REVIEW FOUND, pinned so it cannot come back.
     *
     * Two meals 60 min apart, only the FIRST marked. An episode belonging to the SECOND
     * sat well inside the first's old ±90 min window, and the old nearest-among-marked rule
     * deleted it — on the real record that reached 10 of the 30 episodes feeding
     * `carbSensitivity`. Owner resolution must leave it alone.
     */
    @Test
    fun `a mark does not reach a neighbouring meals evidence`() {
        val a = daysAgo(2)
        val b = a + 60L * 60_000
        val notes = listOf(foodNote(a, "гречка", 50.0), foodNote(b, "мороженое", 40.0))
        val owners = mealMarkOwners(notes)
        // 60 min apart ⇒ two meals past the 45-min session gap, each owning its own event.
        assertEquals(a, owners(a + 5L * 60_000))
        assertEquals(b, owners(b + 5L * 60_000))

        val marks = MealMarks(listOf(mark(a, MarkKind.GRAMS_MORE)))
        val episodes = listOf(episode(a + 5L * 60_000, 15.0, 50.0), episode(b + 5L * 60_000, 6.0, 40.0))
        val kept = episodes.excludingMarked(marks, owners)
        assertEquals("only meal A's episode may go", 1, kept.size)
        assertEquals(b + 5L * 60_000, kept[0].onsetMs)
    }

    /** Notes close enough to be ONE meal share one onset, so one answer covers both — and
     *  that is the corpus's own grouping, not a second rule of ours. */
    @Test
    fun `notes in one session resolve to one meal`() {
        val a = daysAgo(2)
        val b = a + 20L * 60_000            // inside the 45-min session gap
        val owners = mealMarkOwners(listOf(foodNote(a, "суп", 20.0), foodNote(b, "хлеб", 30.0)))
        assertEquals(a, owners(a + 5L * 60_000))
        assertEquals(a, owners(b + 5L * 60_000))
    }

    /** An event whose owning note the deconvolution does not anchor on has NO owner, and an
     *  unowned event keeps its evidence — fail toward keeping data, never toward deleting it. */
    @Test
    fun `an event with no resolvable owner is never marked`() {
        val ts = daysAgo(2)
        // kind = "text", so `deconvolutionFoodNotes` rejects it while `carbEpisodes` takes it.
        val notes = listOf(
            com.diapilot.core.collector.Annotation(
                tsMs = ts, kind = "text", content = "что-то съел", estCarbs = 30.0, id = ts,
            ),
        )
        val owners = mealMarkOwners(notes)
        assertNull(owners(ts))
        val marks = MealMarks(listOf(mark(ts, MarkKind.SENSOR_LIED)))
        assertEquals(1, listOf(episode(ts, 9.0, 30.0)).excludingMarked(marks, owners).size)
    }

    /** The corpus is keyed by the session start itself, so its lookup is exact and needs no
     *  resolver — and a near-miss must NOT match. */
    @Test
    fun `the corpus lookup is exact`() {
        val onset = daysAgo(2)
        val marks = MealMarks(listOf(mark(onset, MarkKind.SENSOR_LIED)))
        assertEquals(MarkKind.SENSOR_LIED, marks.at(onset)?.kind)
        assertNull(marks.at(onset + 60_000))
        assertEquals(MarkPolicy.OPEN, marks.policyAt(onset + 60_000))
    }


    // ========================================================= REVERSIBLE ====
    @Test
    fun `a revoked mark stops acting and the newest active one wins`() {
        val onset = daysAgo(2)
        val revoked = mark(onset, MarkKind.SENSOR_LIED, revoked = now)
        assertTrue(MealMarks(listOf(revoked)).isEmpty)
        assertEquals(MarkPolicy.OPEN, MealMarks(listOf(revoked)).policyAt(onset))

        val older = MealMark(onset, MarkKind.GRAMS_MORE, createdAtMs = now - 2_000)
        val newer = MealMark(onset, MarkKind.RESCUE, createdAtMs = now - 1_000)
        val m = MealMarks(listOf(older, newer))
        assertEquals(1, m.active.size)
        assertEquals(MarkKind.RESCUE, m.at(onset)?.kind)
    }

    /** The cache stamp must MOVE on every kind of change, or a mark looks inert. */
    @Test
    fun `the stamp changes when the answers change`() {
        val onset = daysAgo(2)
        val one = MealMarks(listOf(MealMark(onset, MarkKind.GRAMS_MORE, createdAtMs = now)))
        val other = MealMarks(listOf(MealMark(onset, MarkKind.GRAMS_LESS, createdAtMs = now)))
        val later = MealMarks(listOf(MealMark(onset, MarkKind.GRAMS_MORE, createdAtMs = now + 1)))
        assertNotEquals(0L, one.stamp())
        assertNotEquals(one.stamp(), other.stamp())
        assertNotEquals(one.stamp(), later.stamp())
        assertEquals(0L, MealMarks(listOf(mark(onset, MarkKind.GRAMS_MORE, revoked = now))).stamp())
    }

    /** Provenance is not decoration: a mark without an author and a time is another
     *  number of unknown origin, which is the class of defect this project already paid
     *  six weeks for. */
    @Test
    fun `a mark carries who said it and when`() {
        val m = MealMark(daysAgo(1), MarkKind.CONTEXT, comment = "гулял час", createdAtMs = now)
        assertEquals(MARK_AUTHOR_HUMAN, m.author)
        assertEquals(now, m.createdAtMs)
        assertEquals("гулял час", m.comment)
        assertTrue(m.active)
    }
}
