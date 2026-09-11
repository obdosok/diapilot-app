package com.example.diapilot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.diapilot.data.EpisodeAttributionExplanationV1
import com.example.diapilot.data.FoodCalculationRegistry
import org.junit.Rule
import org.junit.Test

class Stage10HistoryRecompositionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun primaryMealReceiptRecomposesWhenSidecarPublishes() {
        FoodCalculationRegistry.updateEpisodeAttribution(emptyMap())
        compose.setContent{Stage10MealReceipt(listOf(9001L))}
        compose.onNode(hasText("Stage 10",substring=true)).assertDoesNotExist()
        val e=EpisodeAttributionExplanationV1("v","прогноз 5,0","этому приёму отнесено 3–6","фаза не установлена","сосед","30–60%","неразрешимо","kernel","day","низкая","датчик")
        compose.runOnIdle{FoodCalculationRegistry.updateEpisodeAttribution(mapOf(9001L to e))}
        compose.onNode(hasText("Ретроспективная оценка: прогноз 5,0",substring=true)).assertIsDisplayed()
    }
}
