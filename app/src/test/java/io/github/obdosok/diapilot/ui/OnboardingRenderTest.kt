package io.github.obdosok.diapilot.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.obdosok.diapilot.AppGraph
import io.github.obdosok.diapilot.LocalAppGraph
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.Onboarding
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first page a stranger sees renders, and it cannot be passed without the
 * checkbox: the disclaimer is the one page whose acceptance is a stored fact,
 * so the button that stamps it must be inert until the box is ticked, and
 * pressing it must leave the timestamp behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingRenderTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun string(id: Int) = context.getString(id)

    @Test fun `the disclaimer must be ticked before it can be accepted, and acceptance is stamped`() {
        Onboarding.reset(context)
        compose.setContent {
            CompositionLocalProvider(LocalAppGraph provides AppGraph(context)) {
                OnboardingScreen(gate = Onboarding.Gate.FULL, onDone = {})
            }
        }
        compose.onNodeWithText(string(R.string.onboarding_disclaimer_title)).assertExists()
        val accept = compose.onNodeWithText(string(R.string.onboarding_disclaimer_button))
        accept.assertIsNotEnabled()
        assertNull(Onboarding.disclaimerAcceptedAtMs(context))

        compose.onNode(hasText(string(R.string.onboarding_disclaimer_accept), substring = true)).performClick()
        accept.assertIsEnabled()
        accept.performClick()
        compose.waitForIdle()
        assertNotNull("pressing Accept stamps the acceptance", Onboarding.disclaimerAcceptedAtMs(context))
        // And the flow moved on to the language page rather than finishing.
        compose.onNodeWithText(string(R.string.language_title)).assertExists()
        assertTrue("the flow is not completed by the disclaimer alone", !Onboarding.completed(context))
    }

    @Test fun `an adopted install sees the disclaimer alone and is done after it`() {
        Onboarding.reset(context)
        Onboarding.markCompleted(context)
        var done = false
        compose.setContent {
            CompositionLocalProvider(LocalAppGraph provides AppGraph(context)) {
                OnboardingScreen(gate = Onboarding.Gate.DISCLAIMER_ONLY, onDone = { done = true })
            }
        }
        compose.onNode(hasText(string(R.string.onboarding_disclaimer_accept), substring = true)).performClick()
        compose.onNodeWithText(string(R.string.onboarding_disclaimer_button)).performClick()
        compose.waitForIdle()
        assertTrue(done)
        assertNotNull(Onboarding.disclaimerAcceptedAtMs(context))
    }
}
