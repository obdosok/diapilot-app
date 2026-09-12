package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import com.diapilot.core.hybrid.HybridForecastEngine
import com.diapilot.core.physio.InsulinParamTierV1
import com.diapilot.core.physio.ManualInsulinParamsV1
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1 end to end: what the user typed must reach the model the app RUNS.
 *
 * The landmark numbers on the settings card were already right while the
 * forecast used a different curve — that is the failure this pins. Every
 * assertion below reads the engine, not the parameters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualInsulinRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * A synthetic hand-entered shape, distinct from the shipped prior.
     *
     * The end of action is 300 because this fixture is about the OTHER
     * landmarks — the assertions below probe the curve out to five hours and a
     * shorter tail would leave them nothing to read. A short hand entry is a
     * case of its own and has its own test
     * ([aHandEnteredEndOfActionOf130ReachesTheKernelUnchanged]); it is no
     * longer a refusal, and `insulinTailMinManualRange` is why.
     */
    private val entered = ManualInsulinParamsV1(onsetMin = 27.0, peakMin = 55.0, tailMin = 300.0, isfMmolPerU = 2.5)

    private fun model() = context.assets.open("models/person_model_v11_runtime.json").use {
        HybridPersonModelJson.read(it)
    }

    @After fun clear() {
        ManualInsulinRuntime.setParams(context, ManualInsulinParamsV1.EMPTY)
    }

    @Test fun everyFieldSurvivesAWriteAndRead() {
        ManualInsulinRuntime.setParams(context, entered)
        val back = ManualInsulinRuntime.params(context)
        assertEquals(27.0, checkNotNull(back.onsetMin), 1e-3)
        assertEquals(55.0, checkNotNull(back.peakMin), 1e-3)
        assertEquals(300.0, checkNotNull(back.tailMin), 1e-3)
        assertEquals(2.5, checkNotNull(back.isfMmolPerU), 1e-3)
        assertNotNull(back.setAtMs)
    }

    @Test fun clearingRemovesEveryFieldRatherThanZeroingIt() {
        ManualInsulinRuntime.setParams(context, entered)
        ManualInsulinRuntime.setParams(context, ManualInsulinParamsV1.EMPTY)
        val back = ManualInsulinRuntime.params(context)
        assertTrue("a cleared field must read as unset, not as 0", !back.any)
    }

    /**
     * The one that matters. `insulinCdf` is what the forecast integrates AND
     * what the meal deconvolution subtracts, so if it moves, both moved.
     */
    @Test fun theEngineRunsTheHandEnteredShape() {
        val base = model()
        val before = HybridForecastEngine(base)
        val resolution = ManualInsulinRuntime.resolve(base, entered, measuredCurve = null)
        val after = HybridForecastEngine(ManualInsulinRuntime.apply(base, resolution))

        assertEquals(InsulinParamTierV1.MANUAL, resolution.shapeTier)
        // Exactly zero AT the stated onset — the grid is anchored on it, so a
        // five-minute leak cannot creep into the minutes the user called quiet.
        assertEquals(0.0, after.insulinCdf(27.0), 1e-9)
        assertTrue("action must have started by 35 min", after.insulinCdf(35.0) > 0.0)
        assertEquals(1.0, after.insulinCdf(300.0), 1e-6)
        // Compared across the whole curve rather than at one minute: the
        // shipped prior starts at 30, so a single early probe would agree with
        // the entered 27 by accident and the test would pass while nothing had moved.
        val gap = (0..36).maxOf { abs(before.insulinCdf(it * 5.0) - after.insulinCdf(it * 5.0)) }
        assertTrue("the entered shape must actually displace the prior: $gap", gap > .05)
    }

    /**
     * Point-for-point, not landmark-for-landmark.
     *
     * Asserting only onset/peak/end passes while the engine quietly falls back
     * to its triangular density — the shape the user rejected by name. The
     * knots must BE the curve the engine integrates.
     */
    @Test fun theEngineIntegratesTheResolvedKnotsAndNotATriangle() {
        val base = model()
        val resolution = ManualInsulinRuntime.resolve(base, entered, measuredCurve = null)
        val knots = checkNotNull(resolution.knots)
        val engine = HybridForecastEngine(ManualInsulinRuntime.apply(base, resolution))
        knots.forEach {
            assertEquals(
                "the engine must integrate the resolved curve at ${it.minute} min",
                it.fraction, engine.insulinCdf(it.minute), 1e-6,
            )
        }
        // And that curve is not the triangle: a triangular density is already
        // near half its mass at the peak.
        val atPeak = engine.insulinCdf(55.0)
        assertTrue("triangular at the peak: $atPeak", atPeak < .45)
    }

    @Test fun theEngineRunsTheHandEnteredIsf() {
        val base = model()
        val after = ManualInsulinRuntime.apply(
            base, ManualInsulinRuntime.resolve(base, ManualInsulinParamsV1(isfMmolPerU = 2.5), null),
        )
        assertEquals(2.5, after.insulin.isf, 1e-9)
        // The band moves with it. A collapsed band would narrow every forecast
        // corridor, and with it the hypo alert's own margin.
        assertTrue(after.insulin.isfHigh > after.insulin.isfLow)
        assertEquals(
            "the band keeps its relative width",
            base.insulin.isfHigh / base.insulin.isfLow,
            after.insulin.isfHigh / after.insulin.isfLow,
            1e-6,
        )
    }

    @Test fun nothingEnteredChangesNothing() {
        val base = model()
        val after = ManualInsulinRuntime.apply(
            base, ManualInsulinRuntime.resolve(base, ManualInsulinParamsV1.EMPTY, null),
        )
        assertEquals(base, after)
    }

    /**
     * THE OWNER'S OWN NUMBER, END TO END: 130 minutes.
     *
     * The app's owner measures roughly 130 min and sees no insulin effect after
     * two hours. Entering that used to produce 240 in the model: the resolver
     * checked the entry against `insulinTailMinRange`, which is the measuring
     * WINDOW'S reach, so the shape was refused as out of domain and the
     * previous curve silently stayed in force while the card kept showing 130.
     *
     * This walks the whole chain the WP named — the stored setting, the
     * resolver, what `apply` writes, and the kernel the forecast integrates —
     * because a value that survives three of the four is still not applied.
     */
    @Test fun aHandEnteredEndOfActionOf130ReachesTheKernelUnchanged() {
        val short = ManualInsulinParamsV1(onsetMin = 27.0, peakMin = 55.0, tailMin = 130.0)
        ManualInsulinRuntime.setParams(context, short)
        assertEquals(130.0, checkNotNull(ManualInsulinRuntime.params(context).tailMin), 1e-3)

        val base = model()
        val resolution = ManualInsulinRuntime.resolve(
            base, ManualInsulinRuntime.params(context), measuredCurve = null,
        )
        assertTrue("unexpected refusal: ${resolution.rejected}", resolution.rejected.isEmpty())
        assertEquals(InsulinParamTierV1.MANUAL, resolution.shapeTier)
        assertEquals(130.0, checkNotNull(resolution.landmarks).tailMin, 1e-9)

        val applied = ManualInsulinRuntime.apply(base, resolution)
        assertEquals(130.0, applied.insulin.tailDurationMin, 1e-9)
        assertEquals(130.0, applied.insulin.shortDurationMin, 1e-9)

        // The kernel, which is what the forecast subtracts, IOB reports and
        // What-if scales. Its length IS the applied end of action.
        val engine = HybridForecastEngine(applied)
        assertEquals(130.0, engine.insulinKernelPoints(1.0).last().tauMin, 1e-9)
        assertEquals(1.0, engine.insulinCdf(130.0), 1e-6)
        assertTrue(
            "insulin must still be acting at 100 min: ${engine.insulinCdf(100.0)}",
            engine.insulinCdf(100.0) < .999,
        )
    }

    /**
     * And the ARTIFACT the app actually serves carries it too — the layer that
     * used to throw on it, taking the whole PHYSIO arm down rather than one
     * number with it.
     */
    @Test fun theArtifactServesAShortHandEnteredEndOfAction() {
        val name = "manual-short-tail-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            HybridShadowRegistry.install(model(), "manual-short-tail-test")
            ManualInsulinRuntime.setParams(
                context, ManualInsulinParamsV1(onsetMin = 27.0, peakMin = 55.0, tailMin = 130.0),
            )
            val served = PhysioRuntime.artifact(store, System.currentTimeMillis())
                ?.personModelAt(12.0)
            assertNotNull("the artifact must be built, not refused", served)
            assertEquals(130.0, served!!.insulin.tailDurationMin, 1e-9)
        }
        context.deleteDatabase(name)
    }

    /**
     * A hand-entered value is model input, so the artifact cache must not
     * outlive it. This reads the artifact the app would serve, before and
     * after — not the preferences.
     */
    @Test fun theArtifactCacheDoesNotOutliveAnEntry() {
        val name = "manual-insulin-${System.nanoTime()}.sqlite"
        SqliteCollectorStore(context, name).use { store ->
            HybridShadowRegistry.install(model(), "manual-insulin-test")
            val now = System.currentTimeMillis()
            val before = PhysioRuntime.artifact(store, now)?.personModelAt(12.0)
            assertNotNull(before)
            ManualInsulinRuntime.setParams(context, entered)
            val after = PhysioRuntime.artifact(store, now)?.personModelAt(12.0)
            assertNotNull(after)
            assertEquals(27.0, after!!.insulin.onsetMin, 1e-3)
            assertNotEquals(
                "a cache keyed without the entry would serve the old curve",
                before!!.insulin.onsetMin, after.insulin.onsetMin,
            )
        }
        context.deleteDatabase(name)
    }
}
