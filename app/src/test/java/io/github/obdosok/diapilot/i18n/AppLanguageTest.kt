package io.github.obdosok.diapilot.i18n

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.analysis.fmtBg
import io.github.obdosok.diapilot.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Settings -> Language selection. API 28 on purpose: below 33 AppCompat
 * (not the system) owns the choice, and that is the path with the gap
 * [AppLanguage] closes for processes that never start an activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppLanguageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @After fun backToSystem() = AppLanguage.select(context, AppLanguage.SYSTEM)

    @Test fun `system is the default and an English system gives English`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.current(context))
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
        assertEquals("System", context.localized().getString(R.string.language_system))
        assertEquals("English", LlmLanguage.replyLanguage(context))
    }

    @Test fun `choosing Russian switches the text, the numbers and the reply language`() {
        AppLanguage.select(context, AppLanguage.RUSSIAN)

        assertEquals(AppLanguage.RUSSIAN, AppLanguage.current(context))
        assertEquals("ru", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        assertEquals("Системный", context.localized().getString(R.string.language_system))
        assertEquals("Reply in Russian.", LlmLanguage.replyInstruction(context))
        assertEquals("10,2", fmtBg(10.2, mgdl = false))
        assertEquals("ммоль/л", unitLabel(mgdl = false))
    }

    @Test @Config(qualifiers = "ru")
    fun `choosing English overrides a Russian system`() {
        assertEquals("Системный", context.localized().getString(R.string.language_system))

        AppLanguage.select(context, AppLanguage.ENGLISH)

        assertEquals(AppLanguage.ENGLISH, AppLanguage.current(context))
        assertEquals("System", context.localized().getString(R.string.language_system))
        assertEquals("English", LlmLanguage.replyLanguage(context))
        assertEquals("10.2", fmtBg(10.2, mgdl = false))
        assertEquals("mg/dL", unitLabel(mgdl = true))
    }

    @Test fun `going back to system clears the app locale`() {
        AppLanguage.select(context, AppLanguage.RUSSIAN)
        AppLanguage.select(context, AppLanguage.SYSTEM)

        assertEquals(AppLanguage.SYSTEM, AppLanguage.current(context))
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
        assertEquals("System", context.localized().getString(R.string.language_system))
    }

    @Test fun `a process without an activity still renders the chosen language`() {
        AppLanguage.select(context, AppLanguage.RUSSIAN)
        // A worker or receiver process: AppCompat has not restored its stored
        // locale because no activity was created.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

        assertEquals(AppLanguage.RUSSIAN, AppLanguage.current(context))
        assertEquals("Системный", context.localized().getString(R.string.language_system))
    }

    @Test fun `each language is named in itself whatever the current language`() {
        AppLanguage.select(context, AppLanguage.RUSSIAN)
        val ru = context.localized()
        assertEquals("English", ru.getString(R.string.language_english))
        assertEquals("Русский", ru.getString(R.string.language_russian))

        AppLanguage.select(context, AppLanguage.ENGLISH)
        val en = context.localized()
        assertEquals("English", en.getString(R.string.language_english))
        assertEquals("Русский", en.getString(R.string.language_russian))
    }

    @Test fun `unknown system languages count as system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLocales(LocaleListCompat.forLanguageTags("de")))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromLocales(LocaleListCompat.forLanguageTags("ru-RU")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLocales(LocaleListCompat.forLanguageTags("en-GB")))
    }
}
