package io.github.obdosok.diapilot

import android.app.Application
import android.util.Log
import io.github.obdosok.diapilot.data.FoodEraSettings
import io.github.obdosok.diapilot.data.HybridModelStore
import io.github.obdosok.diapilot.data.HybridRuntimeMetrics
import io.github.obdosok.diapilot.i18n.AppLocaleFormats
import io.github.obdosok.diapilot.widget.BgWidget

class DiaPilotApplication : Application() {
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // API 33+: a language picked in system settings arrives here.
        AppLocaleFormats.sync(this)
        // The widget is drawn outside any activity: repaint it in the new language.
        BgWidget.updateAll(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Number formatting in :core ("10,2" vs "10.2") follows the app
        // language, also in a process started without an activity.
        AppLocaleFormats.sync(this)
        // First, before anything that learns or reads history: every reader
        // clamps to this era. On a fresh install this stores the first-run date.
        FoodEraSettings.init(this)
        try {
            HybridModelStore.loadOrInstallBundled(this)
            assets.open("models/food_episode_observations_v11.json").use(
                HybridRuntimeMetrics::installFoodObservations,
            )
        } catch (error: Exception) {
            // Shadow failure must never affect the shipped forecast.
            Log.w("PhysioForecastBridge", "v11 model not installed: ${error.message}")
        }
    }
}
