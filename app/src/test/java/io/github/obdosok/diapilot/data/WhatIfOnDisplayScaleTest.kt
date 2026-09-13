package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.hybrid.HybridPersonModelJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "WHAT IF" RETURNS DELTAS ON THE DISPLAY SCALE, not on the model's raw scale.
 *
 * The screen draws `p.mmol + d` (TodayScreen), where `p` is the forecast
 * AFTER the meter lens, and `d` comes from [HybridWhatIfProfile]. The lens
 * is affine: `TwinCache.calibrateReadings` gives `foodRiseScale * raw +
 * intercept`, and on a real sensor that slope can be well above 1. So a
 * model change of `delta` is worth `slope * delta` on screen, and a raw
 * delta understated the "What-if" effect by exactly that factor.
 *
 * Practical meaning: to bring the line down to target, the card asked for
 * noticeably more units than needed. A live report matched this symptom
 * exactly — the What-if card asking for more insulin than the user's own
 * correction needed — after which the user injected less than the app
 * suggested. At the time this was blamed on a too-weak ISF; that was
 * corrected, and the multiplier bug remained.
 *
 * The lens offset is NOT applied to the difference: it already lives in
 * `p.mmol`.
 *
 * The test holds a PROPERTY, not a number: the delta at scale s must be
 * exactly s times the delta at scale 1. That way it survives a sensor change
 * and any recalibration of the lens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhatIfOnDisplayScaleTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun profile(scale: Double): HybridWhatIfProfile {
        val model = context.assets.open("models/person_model_v11_runtime.json")
            .use { HybridPersonModelJson.read(it) }
        PhysioForecastRegistry.install(model, "whatif-scale")
        PhysioForecastRegistry.updateWhatIf(
            anchorTsMs = 1_787_000_000_000L,
            activityExposure = 0.0,
            baseline = emptyList(),
            modelOverride = model,
            physio = true,
            displayScale = scale,
        )
        return requireNotNull(PhysioForecastRegistry.physioWhatIf(1_787_000_000_000L))
    }

    @Test
    fun `the insulin delta scales with the meter lens`() {
        val lens = 1.2459340128472292          // a representative sensor slope
        val raw = profile(1.0).insulinDelta(60.0, 3.0, 0.0)
        val shown = profile(lens).insulinDelta(60.0, 3.0, 0.0)
        assertTrue("three units must lower something, or the test holds nothing",
            kotlin.math.abs(raw) > 0.5)
        assertEquals(
            "the \"What-if\" delta lands on the CALIBRATED line, so it must " +
                "carry the lens slope: raw %.3f, expected %.3f".format(raw, raw * lens),
            raw * lens, shown, 1e-9,
        )
    }

    @Test
    fun `the hypothetical food delta scales too`() {
        val lens = 1.2459340128472292
        val raw = profile(1.0).foodDelta(90.0, 40.0)
        val shown = profile(lens).foodDelta(90.0, 40.0)
        assertTrue("forty grams must raise something", kotlin.math.abs(raw) > 0.5)
        assertEquals(
            "hypothetical food lands on the same line as a hypothetical injection",
            raw * lens, shown, 1e-9,
        )
    }
}
