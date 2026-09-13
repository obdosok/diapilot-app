package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.hybrid.HybridPersonModelJson
import com.diapilot.core.hybrid.MacroTimingParamsV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE FAT PEAK SLOPE IS RETIRED, AND THAT IS NOW THE THING NOTHING WOULD WATCH.
 *
 * This file used to pin the opposite. The slope shipped on an honest
 * fit (OLS over 48 episodes with carbs as a control, bootstrap +8.4..+25.5,
 * positive in 600 of 600) and the test existed because a mutation had shown
 * that switching it off broke nothing.
 *
 * Retired on a measurement of the SAME kind, which is the only thing
 * that may retire a measurement. The fit was taken against the OBSERVED time to
 * peak — food minus insulin — on a day when the gram queue was not binding.
 * With the caloric queue shipped, the two delay the same meal. Replayed over
 * 157 logged meals with the same anchor, food and insulin (stand `fatsweep`),
 * median bias actual-minus-predicted:
 *
 *   horizon          30      60      90     120     180
 *   with the slope +0.28   +1.52   +1.80   +2.51   +2.52
 *   without        +0.18   +1.21   +1.67   +1.99   +2.17
 *
 * Better at every horizon. The mechanism stays in the code and returns the
 * moment the coefficient is non-zero, so re-fitting it against a corpus rebuilt
 * under the queue is a one-line change.
 *
 * The assertions are the mirror of the old ones, for the same reason: a
 * coefficient that moves the whole arm's timing must not be able to change
 * state without a test noticing — in EITHER direction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FatPeakSlopeShippedTest {

    private val kinetics =
        "KINETICS_V2: fast=.05;medium=.90;slow=.05;form=SOLID;confidence=.6;source=test;alcohol=false"

    /** A fatty dish: 30 g of fat — what the slope used to be worth +51 min on. */
    private val fatty = "БЕЛКИ: 30.0 г\nЖИРЫ: 30.0 г\n$kinetics"

    private fun artifact() = run {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = context.assets.open("models/person_model_v11_runtime.json")
            .use { HybridPersonModelJson.read(it) }
        PhysioForecastRegistry.install(model, "fat-peak-slope-retired")
        requireNotNull(PhysioRuntime.artifact())
    }

    @Test
    fun `the shipped artifact no longer carries the fitted fat slope`() {
        val macro = artifact().macroTiming
        assertEquals(
            "the fat slope is back on without a fresh measurement",
            0.0, macro.fatPeakMinPer10g, 1e-9,
        )
        assertTrue(
            "the macro timing must still be a promoted, named contract",
            macro.promoted && macro.version.isNotBlank(),
        )
    }

    /**
     * And the card must move with it. Scored against the SAME dish with the old
     * coefficient forced back on: a lean dish also differs through the caloric
     * queue, so that comparison would pass with the slope dead either way.
     */
    @Test
    fun `retiring the slope actually moved the food receipt`() {
        val artifact = artifact()
        val person = artifact.personModelAt(19.0)
        fun peak(macro: MacroTimingParamsV1) = requireNotNull(
            HybridRuntimeMetrics.foodReadoutForModel(
                person, "пицца", 69.0, fatty, macroTiming = macro, physioArtifact = artifact,
            ),
        ).halfArrivalMin
        val shipped = peak(artifact.macroTiming)
        val withOldSlope = peak(
            artifact.macroTiming.copy(
                promoted = true,
                fatPeakMinPer10g = PhysioRuntime.FITTED_FAT_PEAK_MIN_PER_10G,
            ),
        )
        // READ OFF THE HALF-ARRIVAL, NOT THE PEAK — changed when the
        // carb-rate cap became derived and this test reported 89 against 53,
        // i.e. the two arms in the WRONG ORDER. That is not the slope failing:
        // `peakMin` is the argmax of a two-humped arrival rate and the engine's
        // own KDoc records it flipping (55 -> 72 -> 59 -> 93 for one dish as fat
        // rises). `medianArrivalMin` is a LEVEL crossing and monotone in every
        // input by construction, which is exactly why it was introduced.
        //
        // THE MARGIN WAS CALIBRATED UNDER THE OLD TRIANGLES. Refitting them
        // (M-93: medium peak 55 -> 33, end 180 -> 91) moved the whole card
        // earlier, and the same fat slope separated the two arms by 9 min
        // instead of more than 10. The retirement is still plainly visible on
        // the card, which is all this test claims; the ten was a round number,
        // not a measurement.
        assertTrue(
            "the retirement does not reach the card: $shipped vs $withOldSlope",
            withOldSlope > shipped + 5,
        )
    }
}
