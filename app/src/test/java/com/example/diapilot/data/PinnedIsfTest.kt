package com.example.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.hybrid.HybridPersonModelJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A HAND-SET ISF IS FINAL, AND THE LEARNER'S ANSWER STAYS VISIBLE.
 *
 * The defect this pins was measured live: the tuning card
 * held one ISF and the model ran a different, higher value, because
 * `closed_episode_global_isf` multiplied the manual value by its promoted
 * effect (a double-digit percent swing over the observed range). Nothing was
 * broken — the learner did what it was built to do — but the user's own
 * number lost, silently, and What-if had been drawing insulin noticeably
 * stronger than the user believed.
 *
 * Asserted against the REAL `PhysioRuntime.artifact()`, not against a stub of
 * the arithmetic: a test that recomputes the rule it is checking proves only
 * that the test agrees with itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinnedIsfTest {

    private fun installShippedModel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = context.assets.open("models/person_model_v11_runtime.json")
            .use { HybridPersonModelJson.read(it) }
        HybridShadowRegistry.install(model, "0123456789abcdef0123456789abcdef")
    }

    @Test
    fun `the pinned ISF is the number the artifact carries`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        installShippedModel()
        pin(context, 1.85)
        val pinned = SqliteCollectorStore(context, "pinned-isf-a.sqlite").use {
            requireNotNull(PhysioRuntime.artifact(it))
        }
        assertTrue("the pin was not recorded on the artifact", pinned.isfPinnedByHand)
        assertEquals(
            "the learner moved a hand-set ISF",
            1.85, pinned.globalIsf.median, 1e-6,
        )
        // ...and it reaches the model the forecast actually runs, which is the
        // step the audit found missing: the artifact agreeing is not enough if
        // `personModelAt` resolves something else.
        assertEquals(
            "the pin did not reach person.insulin.isf",
            1.85, pinned.personModelAt(12.0).insulin.isf, 1e-6,
        )
        // THE BAND MUST NOT COLLAPSE. A zero-width ISF posterior narrows every
        // forecast corridor and with it the hypo alert's own margin; the pin is
        // a better centre than ours, not a claim of certainty.
        assertTrue(
            "a hand-set ISF collapsed the band to a point",
            pinned.globalIsf.p90 - pinned.globalIsf.p10 > 1e-6,
        )
    }

    @Test
    fun `clearing the pin hands ISF back to the learner`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        installShippedModel()
        pin(context, 1.85)
        SqliteCollectorStore(context, "pinned-isf-b.sqlite").use {
            assertTrue(requireNotNull(PhysioRuntime.artifact(it)).isfPinnedByHand)
        }

        pin(context, null)
        assertNull(
            "the switch to adaptive did not drop the pin",
            ManualInsulinRuntime.params(context).isfMmolPerU,
        )
        val free = SqliteCollectorStore(context, "pinned-isf-c.sqlite").use {
            requireNotNull(PhysioRuntime.artifact(it))
        }
        assertTrue("the artifact still claims a pin", !free.isfPinnedByHand)

        // CONTRACT CHANGED, AND THIS IS NOT BENDING THE TEST TO FIT THE CODE.
        //
        // This used to assert "without a pin there must be no alternative —
        // the learner IS the applied value". That was true and it was a
        // fourth door to the ISF axis: with the pin removed, `isfPosterior`
        // went to `learnedIsfPosterior` — the answer from
        // `DailyResponseEstimatorV1`. The user chooses "learn from the daily
        // balance", and a different learner would have been applied.
        //
        // Now the axis has exactly two doors — hand and daily balance — both
        // arriving through `mechanics.insulin.isf`. The learner remains an
        // observation.
        assertEquals(
            "removing the pin handed the axis to a third-party learner — the fourth door is back",
            free.personModelAt(12.0).insulin.isf, free.globalIsf.median, 1e-9,
        )
    }

    /**
     * THE FOURTH DOOR MUST NOT OPEN BY ITSELF.
     *
     * `DailyResponseEstimatorV1` is currently silent (`NOT_IDENTIFIABLE`, n=1
     * against the n>=6 gate), so the defect was invisible and would have woken
     * up on its own once six corrections accumulated over three days. The test
     * checks the INVARIANT, not the current
     * silence: the applied ISF must match what
     * `mechanics.insulin.isf` carries, regardless of the learner's state.
     */
    @Test
    fun `the applied ISF always comes from the two intended doors`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        installShippedModel()
        for (value in listOf(1.85, 1.60, null)) {
            pin(context, value)
            SqliteCollectorStore(context, "isf-doors-${value ?: "none"}.sqlite").use { store ->
                val a = requireNotNull(PhysioRuntime.artifact(store))
                assertEquals(
                    "applied ISF disagreed with the mechanics at pin=$value",
                    a.personModelAt(12.0).insulin.isf, a.globalIsf.median, 1e-9,
                )
                if (value != null) {
                    assertEquals("the hand value did not arrive", value, a.globalIsf.median, 1e-6)
                }
            }
        }
    }

    /**
     * THE MIGRATION, AND IT IS THE DANGEROUS ONE.
     *
     * The hand ISF used to live in `PhysioTuning.physio_isf_mmol`.
     * If the move to P1 fails to carry it, the applied ISF steps silently from
     * the hand value to the bundled base (or its promoted value) with no user action.
     * This test exists so that failure cannot be silent.
     */
    @Test
    fun `a legacy hand-set ISF is carried into the single door`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        installShippedModel()
        pin(context, null)
        PhysioTuning.setIsfMmol(context, 1.85)

        val migrated = SqliteCollectorStore(context, "pinned-isf-d.sqlite").use {
            requireNotNull(PhysioRuntime.artifact(it))
        }
        assertTrue("the legacy pin was lost", migrated.isfPinnedByHand)
        assertEquals(
            "the applied ISF moved during the migration",
            1.85, migrated.personModelAt(12.0).insulin.isf, 1e-6,
        )
        assertEquals(
            "the value did not land in the single door",
            1.85, requireNotNull(ManualInsulinRuntime.params(context).isfMmolPerU), 1e-6,
        )
        assertNull(
            "the legacy store still holds an ISF, so there are still two doors",
            PhysioTuning.read(context).isfMmol,
        )
    }

    private fun pin(context: android.content.Context, isf: Double?) {
        PhysioTuning.setIsfMmol(context, null)
        ManualInsulinRuntime.setParams(
            context,
            com.diapilot.core.physio.ManualInsulinParamsV1(isfMmolPerU = isf),
        )
    }
}
