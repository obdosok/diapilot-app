package io.github.obdosok.diapilot.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * This context with the app language applied — use it for every string built
 * outside an activity: notifications, the widget, WorkManager workers,
 * services, BroadcastReceivers.
 *
 * ```
 * val text = context.localized()
 * builder.setContentTitle(text.getString(R.string.meal_notifier_title, time))
 * ```
 *
 * Use the returned context for `getString` / `resources` only; keep passing the
 * original context to system APIs (NotificationManager, PendingIntent, ...).
 *
 * API 33+: the system applies the per-app language to every context of the
 * process, so this returns `this`. Below 33 AppCompat only overrides
 * activities, so the application context (and a service's own context) still
 * carries the system language; this wraps it in a configuration with the
 * chosen locale. "System" returns `this` everywhere.
 */
fun Context.localized(): Context {
    if (Build.VERSION.SDK_INT >= 33) return this
    val wanted = AppLanguage.effectiveLocales(this)
    if (wanted.isEmpty) return this
    val have = resources.configuration.locales
    if (!have.isEmpty && have[0].language == wanted[0]?.language) return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(wanted.toLanguageTags()))
    return createConfigurationContext(config)
}

/** The language the UI text actually resolves to ("en", "ru", or a system
 *  language the app does not ship, which then renders as English). */
fun Context.uiLanguage(): String =
    localized().resources.configuration.locales.let { if (it.isEmpty) "en" else it[0].language }

/** True when the app's text resolves to Russian. */
fun Context.isRussianUi(): Boolean = uiLanguage() == "ru"

/**
 * Language-dependent formatting that has to work without a Context.
 *
 * Core's `fmtBg` reads [com.diapilot.core.analysis.BgFormat.decimalComma], and
 * [unitLabel] reads the unit names cached here. Both follow the UI language:
 * a decimal comma and the Russian unit name in Russian, "10.2 mmol/L" in
 * English. [sync] runs at process
 * start, on every MainActivity creation (a language switch recreates it), on a
 * configuration change and from [AppLanguage.select].
 */
object AppLocaleFormats {
    @Volatile private var mmolLabel = "mmol/L"
    @Volatile private var mgdlLabel = "mg/dL"

    fun sync(context: Context) {
        val res = context.localized()
        com.diapilot.core.analysis.BgFormat.decimalComma = res.isRussianUi()
        mmolLabel = res.getString(io.github.obdosok.diapilot.R.string.unit_mmol_l)
        mgdlLabel = res.getString(io.github.obdosok.diapilot.R.string.unit_mg_dl)
    }

    fun unitLabel(mgdl: Boolean): String = if (mgdl) mgdlLabel else mmolLabel
}

/** "mmol/L" / "mg/dL" in the UI language. No Context needed. */
fun unitLabel(mgdl: Boolean): String = AppLocaleFormats.unitLabel(mgdl)
