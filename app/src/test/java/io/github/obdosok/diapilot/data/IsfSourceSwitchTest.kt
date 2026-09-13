package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.hybrid.HybridPersonModelJson
import com.diapilot.core.physio.ManualInsulinParamsV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE SWITCH MUST ACTUALLY SWITCH.
 *
 * This exact failure happened once: the "Switch to adaptive" control
 * kept clearing a store the pin had moved out of, so the control did nothing at
 * all while looking like it worked. A control on the ISF axis that silently
 * no-ops is worse than no control — it makes the user believe they changed
 * what the forecast runs on when it did not.
 *
 * So this asserts against the REAL `PhysioRuntime.artifact()`, on the value the
 * forecast actually reads (`personModelAt(...).insulin.isf`), not on the
 * preference that was written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IsfSourceSwitchTest {

    private fun installShippedModel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = context.assets.open("models/person_model_v11_runtime.json")
            .use { HybridPersonModelJson.read(it) }
        PhysioForecastRegistry.install(model, "0123456789abcdef0123456789abcdef")
    }

    private fun appliedIsf(name: String): Double =
        SqliteCollectorStore(ApplicationProvider.getApplicationContext(), name).use {
            requireNotNull(PhysioRuntime.artifact(it)).personModelAt(12.0).insulin.isf
        }

    private fun setUpPin(context: android.content.Context, isf: Double?) {
        PhysioTuning.setIsfMmol(context, null)
        ManualInsulinRuntime.setParams(context, ManualInsulinParamsV1(isfMmolPerU = isf))
    }

    @Test
    fun `flipping the switch changes the ISF the forecast runs on`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        installShippedModel()
        setUpPin(context, 1.85)
        AdaptiveIsfRuntime.persist(context, isf = 1.60, days = 9, nowMs = 1L, fromMs = 1L, toMs = 2L)

        IsfSource.set(context, IsfSource.Choice.MANUAL)
        assertEquals("the user's own number must be in force on MANUAL", 1.85, appliedIsf("isf-src-a.sqlite"), 1e-6)

        IsfSource.set(context, IsfSource.Choice.ADAPTIVE)
        assertEquals("the switch did not reach the model", 1.60, appliedIsf("isf-src-b.sqlite"), 1e-6)
    }

    @Test
    fun `on ADAPTIVE the artifact stops claiming the user's number is pinned`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        installShippedModel()
        setUpPin(context, 1.85)
        AdaptiveIsfRuntime.persist(context, isf = 1.60, days = 9, nowMs = 1L, fromMs = 1L, toMs = 2L)
        IsfSource.set(context, IsfSource.Choice.ADAPTIVE)

        val artifact = SqliteCollectorStore(context, "isf-src-c.sqlite").use {
            requireNotNull(PhysioRuntime.artifact(it))
        }
        assertFalse(
            "the card would tell the user their value is protected while the learner runs",
            artifact.isfPinnedByHand,
        )
        // The band travels with the centre rather than collapsing onto it: a
        // zero-width ISF posterior narrows the forecast corridor and with it the
        // hypo alert's margin, whoever supplied the number.
        assertTrue(
            "the learned ISF collapsed the band",
            artifact.globalIsf.p90 - artifact.globalIsf.p10 > 1e-6,
        )
    }

    /**
     * The dangerous case: the switch is flipped before the fit has ever run.
     *
     * The model must keep the user's hand value, not fall back to the bundled
     * base — which would be a silent step in a direction nobody chose.
     */
    @Test
    fun `ADAPTIVE with no fit yet keeps the user's number rather than the bundled base`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        installShippedModel()
        setUpPin(context, 1.85)
        AdaptiveIsfRuntime.clear(context)
        IsfSource.set(context, IsfSource.Choice.ADAPTIVE)

        assertEquals(
            "an empty learner must not hand ISF back to the bundled base",
            1.85, appliedIsf("isf-src-d.sqlite"), 1e-6,
        )
    }
}
