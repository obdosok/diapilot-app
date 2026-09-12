package io.github.obdosok.diapilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The plausibility gate is ON for a phone nobody has configured.
 *
 * It is not a tuning knob: it decides whether an anchor counts as a
 * measurement at all, and off means the forecast and the hypo alert take a
 * compression low or an end-of-life artifact at face value. A fresh install
 * must not have to find that out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlausibilityGateDefaultTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `an unconfigured install distrusts an implausible anchor`() {
        assertTrue(Settings.plausibilityGate(context))
    }

    @Test fun `the user can still switch it off and back on`() {
        Settings.setPlausibilityGate(context, false)
        assertFalse(Settings.plausibilityGate(context))
        Settings.setPlausibilityGate(context, true)
        assertTrue(Settings.plausibilityGate(context))
    }
}
