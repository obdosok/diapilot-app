package io.github.obdosok.diapilot.data

import com.diapilot.core.hybrid.HybridForecastPoint
import com.diapilot.core.hybrid.HybridPersonModelJson
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "WHAT IF" AND THE LINE MUST COMPUTE FROM ONE MODEL.
 *
 * A live check caught the disagreement before any test did: What-if and the
 * forecast were applying a different ISF. There turned out to be two
 * causes, and the first was in a single word.
 *
 *  1. `updateWhatIf(..., physio: Boolean = false)`, and the physio arm did not
 *     pass that flag. The profile was written to the LEGACY slot, `latestPhysioWhatIf`
 *     stayed null forever, and the screen fell back to `insulinDeltaAt(twinKernel, ...)`
 *     — a different kernel with a different ISF.
 *  2. The lookup required EXACT equality of timestamps, while the lookup key on
 *     the device changed every second and a half. Even with the right slot there
 *     would have been no match.
 *
 * Both are pinned here, because either one alone leaves the defect alive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhatIfArmTest {

    private fun install() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val model = context.assets.open("models/person_model_v11_runtime.json")
            .use { HybridPersonModelJson.read(it) }
        HybridShadowRegistry.install(model, "0123456789abcdef0123456789abcdef")
    }

    private fun publish(anchorTsMs: Long, physio: Boolean) {
        HybridShadowRegistry.updateWhatIf(
            anchorTsMs,
            activityExposure = 0.0,
            baseline = listOf(
                HybridForecastPoint(
                    minutes = 0, tsMs = anchorTsMs, baseline = 6.0, scenario = 6.0,
                    low = 5.0, high = 7.0, foodDelta = 0.0, insulinActualDelta = 0.0,
                    insulinScenarioDelta = 0.0, residualDrift = 0.0,
                    backgroundDelta = 0.0, activityDirectDelta = 0.0,
                ),
            ),
            physio = physio,
        )
    }

    @Test
    fun `a physio run fills the physio slot, not the legacy one`() {
        install()
        val anchor = 1_700_000_000_000L
        publish(anchor, physio = true)
        assertNotNull(
            "physio profile not published — What-if will fall back to the legacy kernel",
            HybridShadowRegistry.physioWhatIf(anchor),
        )
    }

    @Test
    fun `the lookup tolerates the clock the screen actually uses`() {
        install()
        val anchor = 1_700_000_000_000L
        publish(anchor, physio = true)
        // The screen looks up by the first point of the DISPLAYED series, and
        // that shifts from frame to frame. Exact equality here guaranteed a miss.
        assertNotNull(
            "profile rejected because of a one-minute shift",
            HybridShadowRegistry.physioWhatIf(anchor + 60_000),
        )
    }

    @Test
    fun `a stale profile is still refused`() {
        install()
        val anchor = 1_700_000_000_000L
        publish(anchor, physio = true)
        assertNull(
            "профиль от старого прогноза принят — допуск потерял смысл",
            HybridShadowRegistry.physioWhatIf(anchor + 3 * HybridShadowRegistry.WHAT_IF_FRESH_MS),
        )
    }
}
