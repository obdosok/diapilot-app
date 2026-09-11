package com.example.diapilot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.diapilot.data.EpisodeAttributionExplanationV1
import com.example.diapilot.data.FoodCalculationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Stage10HistoryRecompositionRobolectricTest {
    @get:Rule val compose=createComposeRule()

    @Test fun openPrimaryHistoryProjectionRecomposesOnSidecarPublication() {
        FoodCalculationRegistry.resetEpisodeStateForTest()
        compose.setContent{Stage10MealReceipt(listOf(9101L))}
        compose.onNode(hasText("Stage 10",substring=true)).assertDoesNotExist()
        val receipt=EpisodeAttributionExplanationV1("v","прогноз 5,0","этому приёму отнесено 3–6","фаза не установлена","сосед","30–60%","неразрешимо","kernel","day","низкая","датчик")
        compose.runOnIdle{FoodCalculationRegistry.updateEpisodeAttribution(mapOf(9101L to receipt))}
        // The collapsed receipt shows only its resolution line
        // (one fact per card; even the diagnostic label was cut as
        // noise). This test pins recomposition-on-publication, so
        // it asserts the resolution text that publication reveals.
        compose.onNode(hasText("неразрешимо",substring=true)).assertIsDisplayed()
        compose.onNode(hasText("Как рассчитано?",substring=true)).assertIsDisplayed()
    }
}
