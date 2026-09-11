package io.github.obdosok.diapilot.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.analysis.BolusPurpose
import com.diapilot.core.analysis.NoteTag
import com.diapilot.core.analysis.SysLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val CYRILLIC = Regex("[\\u0400-\\u04FF]")

/** Stored keys round-trip to display text in English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoredLabelTextEnglishTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `every key has an English label`() {
        NoteTag.entries.forEach { t ->
            val label = TokenText.noteTag(context, t.key)
            assertFalse("$t: $label", CYRILLIC.containsMatchIn(label))
            assertEquals(TokenText.noteTag(context, t), label)
        }
        BolusPurpose.entries.forEach { p ->
            val label = TokenText.bolusPurpose(context, p.key)!!
            assertFalse("$p: $label", CYRILLIC.containsMatchIn(label))
            assertEquals(TokenText.bolusPurpose(context, p), label)
        }
        SysLabels.KEYS.forEach { assertFalse(it, CYRILLIC.containsMatchIn(FoodText.mealLabel(context, it))) }
    }

    @Test fun `keys and old russian tokens render the same English text`() {
        assertEquals("walk · 40 min", TokenText.noteTag(context, "walk · 40 min"))
        assertEquals("walk · 40 min", TokenText.noteTag(context, "прогулка · 40 мин"))
        assertEquals("dextrose ×2", TokenText.noteTag(context, "dextrose ×2"))
        assertEquals("dextrose ×2", TokenText.noteTag(context, "декстроза ×2"))
        assertEquals("sleep debt", TokenText.noteTag(context, "sleep_debt"))
        assertEquals("sleep debt", TokenText.noteTag(context, "недосып"))
        assertEquals("for food", TokenText.bolusPurpose(context, "meal_bolus"))
        assertEquals("for food", TokenText.bolusPurpose(context, "на еду"))
        assertEquals("air shot", TokenText.bolusPurpose(context, "prime"))
        assertEquals("dawn phenomenon", FoodText.mealLabel(context, SysLabels.DAWN))
        assertEquals("dawn phenomenon", FoodText.mealLabel(context, "утренняя заря"))
        assertEquals("dextrose ×2", FoodText.mealLabel(context, "dextrose ×2"))
    }

    @Test fun `the user's own words are shown as typed`() {
        assertEquals("гречка с курицей", TokenText.noteTag(context, "гречка с курицей"))
        assertEquals("walk to the shop", TokenText.noteTag(context, "walk to the shop"))
        assertEquals("гречка", FoodText.mealLabel(context, "гречка"))
        assertEquals("профилактика", TokenText.bolusPurpose(context, "профилактика"))
    }
}

/** Stored keys round-trip to display text in Russian: the same words older builds stored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class StoredLabelTextRussianTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `every key shows the Russian word older builds stored`() {
        NoteTag.entries.forEach { t ->
            val label = TokenText.noteTag(context, t.key)
            assertTrue("$t: $label", CYRILLIC.containsMatchIn(label))
        }
        assertEquals("прогулка · 40 мин", TokenText.noteTag(context, "walk · 40 min"))
        assertEquals("декстроза ×2", TokenText.noteTag(context, "dextrose ×2"))
        assertEquals("недосып", TokenText.noteTag(context, "sleep_debt"))
        assertEquals("укол в живот", TokenText.noteTag(context, "injection_belly"))
        assertEquals("утренняя заря", TokenText.noteTag(context, "dawn"))
        assertEquals("коррекция", TokenText.bolusPurpose(context, "correction"))
        assertEquals("на еду", TokenText.bolusPurpose(context, "meal_bolus"))
        assertEquals("докол", TokenText.bolusPurpose(context, "top_up"))
        assertEquals("воздух", TokenText.bolusPurpose(context, "prime"))
        assertEquals("продолжение еды", FoodText.mealLabel(context, SysLabels.CONTINUATION))
        assertEquals("спорт/адреналин", FoodText.mealLabel(context, SysLabels.SPORT))
        assertEquals("не знаю", FoodText.mealLabel(context, SysLabels.UNKNOWN))
    }

    @Test fun `an English note is shown in Russian and an old Russian note exactly as written`() {
        assertEquals("прогулка · 40 мин", TokenText.noteTag(context, "Walk · 40 min"))
        assertEquals("на еду", TokenText.bolusPurpose(context, "for food"))
        assertEquals("Прогулка", TokenText.noteTag(context, "Прогулка"))
        assertEquals("прогулка · 40 мин", TokenText.noteTag(context, "прогулка · 40 мин"))
        assertEquals("гречка с курицей", TokenText.noteTag(context, "гречка с курицей"))
    }
}
