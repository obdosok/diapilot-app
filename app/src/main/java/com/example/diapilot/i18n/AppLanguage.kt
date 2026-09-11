package com.example.diapilot.i18n

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The in-app language choice (Settings -> Language).
 *
 * AppCompat does the actual work: [AppCompatDelegate.setApplicationLocales]
 * hands the choice to the system on API 33+ and, below that, overrides the
 * configuration of every AppCompatActivity and persists the choice through
 * `AppLocalesMetadataHolderService` (autoStoreLocales in the manifest).
 *
 * One gap remains below API 33, and it is why this class keeps its own copy of
 * the choice: AppCompat restores its stored locale only when the first
 * activity is created. A process started by WorkManager, a BroadcastReceiver
 * or the collector service has no activity, so there
 * [AppCompatDelegate.getApplicationLocales] is empty and text would come out
 * in the system language. [effectiveLocales] falls back to the copy.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    RUSSIAN("ru");

    fun locales(): LocaleListCompat =
        if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)

    companion object {
        private const val PREFS = "app_language"
        private const val KEY_TAG = "language_tag"

        /** The choice as the Settings screen shows it. */
        fun current(context: Context): AppLanguage = fromLocales(effectiveLocales(context))

        fun fromLocales(locales: LocaleListCompat): AppLanguage =
            if (locales.isEmpty) SYSTEM
            else when (locales[0]?.language) {
                "ru" -> RUSSIAN
                "en" -> ENGLISH
                else -> SYSTEM
            }

        /**
         * Apply [language] to the whole app. Activities are recreated by
         * AppCompat (or by the system on API 33+); text built outside an
         * activity picks the choice up through [localized].
         */
        fun select(context: Context, language: AppLanguage) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TAG, language.tag).apply()
            AppCompatDelegate.setApplicationLocales(language.locales())
            AppLocaleFormats.sync(context)
            // Below API 33 no configuration change reaches the Application, so
            // repaint the widget here; on 33+ DiaPilotApplication does it once
            // the system has applied the new locale.
            if (Build.VERSION.SDK_INT < 33) {
                com.example.diapilot.widget.BgWidget.updateAll(context.applicationContext)
            }
        }

        /**
         * The requested app locales, empty for "follow the system".
         *
         * On API 33+ AppCompat asks the system — but only through a live
         * activity; without one it answers empty even when a language is set.
         * That is harmless there, because the system has already applied the
         * choice to every context of the process (see [localized]). Below 33
         * the answer comes from AppCompat when an activity has restored it and
         * from this class's own copy otherwise.
         */
        fun effectiveLocales(context: Context): LocaleListCompat {
            val fromAppCompat = AppCompatDelegate.getApplicationLocales()
            if (!fromAppCompat.isEmpty || Build.VERSION.SDK_INT >= 33) return fromAppCompat
            val tag = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TAG, null)
            return if (tag.isNullOrBlank()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        }
    }
}
