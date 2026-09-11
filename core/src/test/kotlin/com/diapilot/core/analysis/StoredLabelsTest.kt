package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Language-neutral stored labels: keys are stored and compared, every supported
 * language's words are read, and the notes older builds stored in Russian keep
 * working exactly as before.
 */
class StoredLabelsTest {

    private fun note(content: String, t: Long = 0L, kind: String = "text") =
        Annotation(t, kind, content, id = t)

    // ---- keys ----------------------------------------------------------------

    @Test fun `keys are stable english snake case and unique`() {
        val keys = BolusPurpose.entries.map { it.key } + NoteTag.entries.map { it.key } + SysLabels.KEYS
        keys.forEach { assertTrue(it, Regex("^[a-z]+(_[a-z]+)*$").matches(it)) }
        assertEquals(BolusPurpose.entries.size, BolusPurpose.entries.map { it.key }.toSet().size)
        assertEquals(NoteTag.entries.size, NoteTag.entries.map { it.key }.toSet().size)
        assertEquals(listOf("correction", "meal_bolus", "top_up", "prime"), BolusPurpose.entries.map { it.key })
        assertEquals(setOf("correction", "meal_bolus", "top_up", "prime"), BOLUS_PURPOSES)
    }

    @Test fun `every tag and purpose has words in every supported language`() {
        // Adding a language means adding one column; a tag without words in some
        // language would be unreadable when typed in it.
        for (lang in LabelLanguage.entries) {
            BolusPurpose.entries.forEach { p ->
                assertTrue("$lang $p", BolusPurpose.WORDS.getValue(lang)[p].orEmpty().isNotEmpty())
            }
            SysLabels.KEYS.forEach { k -> assertTrue("$lang $k", SysLabels.WORDS.getValue(lang)[k].orEmpty().isNotEmpty()) }
        }
        NoteTag.entries.forEach { t -> assertTrue("RU $t", NoteTag.WORDS.getValue(LabelLanguage.RU)[t].orEmpty().isNotEmpty()) }
    }

    // ---- bolus purposes --------------------------------------------------------

    @Test fun `purposes read the stored russian tokens the english words and the keys`() {
        assertEquals(BolusPurpose.CORRECTION, BolusPurpose.of("коррекция"))
        assertEquals(BolusPurpose.CORRECTION, BolusPurpose.of(" Коррекция "))
        assertEquals(BolusPurpose.MEAL, BolusPurpose.of("на еду"))
        assertEquals(BolusPurpose.TOP_UP, BolusPurpose.of("докол"))
        assertEquals(BolusPurpose.PRIME, BolusPurpose.of("воздух"))
        assertEquals(BolusPurpose.CORRECTION, BolusPurpose.of("correction"))
        assertEquals(BolusPurpose.MEAL, BolusPurpose.of("for food"))
        assertEquals(BolusPurpose.TOP_UP, BolusPurpose.of("top-up"))
        assertEquals(BolusPurpose.PRIME, BolusPurpose.of("air shot"))
        BolusPurpose.entries.forEach { assertEquals(it, BolusPurpose.of(it.key)) }
        assertNull(BolusPurpose.of("banana"))
        assertNull(BolusPurpose.of(null))
    }

    @Test fun `canonical purpose stores the key and leaves unknown text alone`() {
        assertEquals("meal_bolus", BolusPurpose.canonical("на еду"))
        assertEquals("prime", BolusPurpose.canonical("воздух"))
        assertEquals("что-то своё", BolusPurpose.canonical("что-то своё"))
        assertNull(BolusPurpose.canonical(null))
        assertTrue(isPrimePurpose("воздух"))
        assertTrue(isPrimePurpose("prime"))
        assertFalse(isPrimePurpose("correction"))
        assertTrue("воздух" in PRIME_PURPOSE_FORMS && "prime" in PRIME_PURPOSE_FORMS)
    }

    @Test fun `the command guard accepts a purpose in any language and still blocks unknown ones`() {
        listOf("correction", "коррекция", "for food", "на еду", "top_up", "докол").forEach {
            assertNull(it, validateCommandValues("bolus", units = 2.0, purpose = it))
        }
        assertEquals(CommandBlock.UnknownPurpose("banana"), validateCommandValues("bolus", units = 2.0, purpose = "banana"))
    }

    @Test fun `trusted dose purposes read both forms`() {
        assertTrue(com.diapilot.core.physio.isTrustedDosePurposeV1("correction"))
        assertTrue(com.diapilot.core.physio.isTrustedDosePurposeV1("top_up"))
        assertTrue(com.diapilot.core.physio.isTrustedDosePurposeV1("коррекция"))
        assertFalse(com.diapilot.core.physio.isTrustedDosePurposeV1("meal_bolus"))
        assertFalse(com.diapilot.core.physio.isTrustedDosePurposeV1("prime"))
    }

    // ---- note tags: old russian notes and new english notes -----------------------

    @Test fun `an old russian activity note and a new english one give the same bout`() {
        val old = activityWindowsFromNotes(listOf(note("прогулка · 40 мин")))
        val key = activityWindowsFromNotes(listOf(note("walk · 40 min")))
        val typed = activityWindowsFromNotes(listOf(note("Walk · 40 min")))
        assertEquals(40, old.single().elevatedMin)
        assertEquals(old, key)
        assertEquals(old, typed)
        // A bare tag keeps the default duration in every language.
        assertEquals(40, activityWindowsFromNotes(listOf(note("тренировка"))).single().elevatedMin)
        assertEquals(40, activityWindowsFromNotes(listOf(note("workout"))).single().elevatedMin)
        assertEquals(25, activityWindowsFromNotes(listOf(note("cycling · 25 min"))).single().elevatedMin)
    }

    @Test fun `context notes are context in every language and a food note named meal stays food`() {
        listOf("недосып", "sleep debt", "sleep_debt", "укол в живот", "injection in the belly", "фото", "photo",
            "коррекция", "correction", "прогулка · 40 мин", "walk · 40 min", "псилиум · 5 г", "psyllium").forEach {
            assertTrue(it, isContextNote(it))
        }
        assertFalse(isContextNote("гречка с курицей"))
        assertFalse(isContextNote("meal"))
        assertTrue(isFoodNote(Annotation(0, "food", "meal", estCarbs = 30.0)))
    }

    @Test fun `absorption modifiers read both languages`() {
        val onset = 60L * 60_000
        assertEquals(DEFAULT_SLOW_TTP_MULT, absorptionTtpMultiplier(listOf(note("псилиум · 5 г", onset - 10 * 60_000)), onset), 1e-9)
        assertEquals(DEFAULT_SLOW_TTP_MULT, absorptionTtpMultiplier(listOf(note("psyllium", onset - 10 * 60_000)), onset), 1e-9)
        assertEquals(1.0, absorptionTtpMultiplier(listOf(note("rice", onset - 10 * 60_000)), onset), 1e-9)
    }

    @Test fun `rescue notes are recognised in both forms and never join a meal`() {
        assertTrue(isRescueNote("декстроза ×2"))
        assertTrue(isRescueNote("dextrose ×2"))
        assertTrue(isRescueNote("Dextrose"))
        assertFalse(isRescueNote("гречка"))
        assertFalse(isRescueNote(null))
        val food = { t: Long, c: String -> Annotation(t, "food", c, id = t, estCarbs = 10.0) }
        val sessions = groupMealSessions(listOf(food(0, "гречка"), food(60_000, "dextrose ×1"), food(120_000, "декстроза ×1")))
        assertEquals(listOf(listOf("гречка"), listOf("dextrose ×1", "декстроза ×1")), sessions.map { s -> s.notes.map { it.content } })
    }

    @Test fun `canonical note content converts only tag shaped notes`() {
        assertEquals("walk", canonicalNoteContent("прогулка"))
        assertEquals("walk · 40 min", canonicalNoteContent("прогулка · 40 мин"))
        assertEquals("walk · 40 min", canonicalNoteContent("Walk · 40 min"))
        assertEquals("sleep_debt", canonicalNoteContent("недосып"))
        assertEquals("injection_belly", canonicalNoteContent("укол в живот"))
        assertEquals("psyllium", canonicalNoteContent("псиллиум"))
        assertEquals("dawn", canonicalNoteContent("утренняя заря"))
        assertEquals("photo", canonicalNoteContent("фото"))
        assertEquals("correction", canonicalNoteContent("коррекция"))
        assertEquals("dextrose ×2", canonicalNoteContent("декстроза ×2"))
        assertEquals("dextrose", canonicalNoteContent("декстроза"))
        // The user's own words are never rewritten.
        listOf("гречка с курицей", "Прогулка по парку", "псилиум · 5 г", "walk to the shop", "meal", "", "  ")
            .forEach { assertEquals(it, canonicalNoteContent(it)) }
    }

    @Test fun `canonical forms are fixed points so the conversion is idempotent`() {
        val forms = NoteTag.entries.flatMap { t -> listOf(t.key) + LabelLanguage.entries.flatMap { NoteTag.WORDS[it]?.get(t).orEmpty() } } +
            BolusPurpose.ALL_FORMS + listOf("прогулка · 40 мин", "walk · 15 min", "декстроза ×3", "гречка")
        forms.forEach { f ->
            val once = canonicalNoteContent(f)
            assertEquals(f, once, canonicalNoteContent(once))
        }
    }

    // ---- system meal labels ---------------------------------------------------------

    @Test fun `system labels read the russian labels the english words and the keys`() {
        assertEquals(SysLabels.DAWN, SysLabels.keyOf("утренняя заря"))
        assertEquals(SysLabels.CONTINUATION, SysLabels.keyOf("продолжение еды"))
        assertEquals(SysLabels.SPORT, SysLabels.keyOf("спорт/адреналин"))
        assertEquals(SysLabels.UNKNOWN, SysLabels.keyOf("не знаю"))
        assertEquals(SysLabels.UNKNOWN, SysLabels.keyOf("don't know"))
        SysLabels.KEYS.forEach { assertEquals(it, SysLabels.keyOf(it)) }
        assertNull(SysLabels.keyOf("гречка"))
        assertTrue("утренняя заря" in SysLabels.ALL && "dawn" in SysLabels.ALL)
        assertEquals("гречка", canonicalMealLabel("гречка"))
        assertEquals("dawn", canonicalMealLabel("утренняя заря"))
        assertEquals("dextrose ×2", canonicalMealLabel("декстроза ×2"))
    }

    @Test fun `continuation stats count old and new continuation labels alike`() {
        fun lm(onset: Long, label: String) = com.diapilot.core.collector.LabeledMeal(
            com.diapilot.core.collector.MealEvent(onset, onset + 1, 5.0, 8.0, 3.0, 40.0, null,
                com.diapilot.core.collector.MealEvent.Kind.UNANNOUNCED),
            label.hashCode().toLong(),
            label,
        )
        val h = 3_600_000L
        val stats = continuationStats(listOf(lm(0, "гречка"), lm(h, "продолжение еды"), lm(10 * h, "гречка"), lm(11 * h, "continuation")))
        assertEquals(listOf("гречка" to 2), stats)
    }

    // ---- insulin product placeholders ---------------------------------------------------

    @Test fun `product placeholders map to keys and a real product stays`() {
        assertEquals("rapid", InsulinProductDefault.canonical("быстрый"))
        assertEquals("basal", InsulinProductDefault.canonical("базальный"))
        assertEquals("rapid", InsulinProductDefault.canonical("rapid"))
        assertEquals("Fiasp", InsulinProductDefault.canonical("Fiasp"))
    }
}
