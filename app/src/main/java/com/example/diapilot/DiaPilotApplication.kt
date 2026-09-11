package com.example.diapilot

import android.app.Application
import android.util.Log
import com.example.diapilot.data.FoodEraSettings
import com.example.diapilot.data.HybridModelStore
import com.example.diapilot.data.HybridRuntimeMetrics

class DiaPilotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
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
