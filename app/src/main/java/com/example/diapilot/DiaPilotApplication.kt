package com.example.diapilot

import android.app.Application
import android.util.Log
import com.example.diapilot.data.FoodEraSettings
import com.example.diapilot.data.HybridModelStore
import com.example.diapilot.data.HybridRuntimeMetrics

class DiaPilotApplication : Application() {
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // API 33+: a language picked in system settings arrives here.
        com.example.diapilot.i18n.AppLocaleFormats.sync(this)
        // The widget is drawn outside any activity: repaint it in the new language.
        com.example.diapilot.widget.BgWidget.updateAll(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Number formatting in :core ("10,2" vs "10.2") follows the app
        // language, also in a process started without an activity.
        com.example.diapilot.i18n.AppLocaleFormats.sync(this)
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
            Log.w("HybridShadow", "v11 model not installed: ${error.message}")
        }
    }
}
