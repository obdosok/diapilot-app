package io.github.obdosok.diapilot

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.obdosok.diapilot.ui.AnalysisScreen
import io.github.obdosok.diapilot.ui.SettingsScreen
import org.junit.Rule
import org.junit.Test

class Stage7UiTest {
    @get:Rule val compose = createComposeRule()

    /** These screens read their store from the graph, so the test provides one. */
    @Composable
    private fun WithGraph(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalAppGraph provides AppGraph(LocalContext.current)) {
            MaterialTheme { content() }
        }

    @Test fun settingsAlwaysShowsThreeForecastModes() {
        compose.setContent { WithGraph { SettingsScreen() } }
        compose.onNodeWithText("Legacy v11").assertExists()
        compose.onNodeWithText("Physio v1").assertExists()
        compose.onNodeWithText("Сравнить").assertExists()
    }

    @Test fun analysisKeepsTodayAndFullRegistryVisible() {
        compose.setContent { WithGraph { AnalysisScreen() } }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Сегодня / текущее состояние").fetchSemanticsNode(); true }.getOrDefault(false) }
        compose.onNodeWithText("Сегодня / текущее состояние").assertExists()
        compose.onNodeWithText("Полный реестр гипотез").assertExists()
    }
}
