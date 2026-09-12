package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridActivityParams
import com.diapilot.core.hybrid.HybridBasalParams
import com.diapilot.core.hybrid.HybridBolusEvent
import com.diapilot.core.hybrid.HybridFoodParams
import com.diapilot.core.hybrid.HybridInsulinParams
import com.diapilot.core.hybrid.HybridJointCoefficients
import com.diapilot.core.hybrid.HybridJointParams
import com.diapilot.core.hybrid.HybridPersonModel
import com.diapilot.core.hybrid.HybridRuntimeParams
import com.diapilot.core.hybrid.HybridShape
import com.diapilot.core.hybrid.HybridTrendParams
import com.diapilot.core.hybrid.HybridUncertaintyParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SHARED FITTER, TESTED WHERE IT IS SHARED.
 *
 * `PhysioAutoFitV1` is now the single search behind both the laptop bench and
 * the phone's "auto-fit" screen. Its correctness therefore cannot be checked by
 * eyeballing the bench any more — a defect here reaches the device.
 *
 * The central test is a RECOVERY test: plant a known knob set, generate the
 * «real» curve from it, then start the search somewhere else and check it walks
 * back. It is the only test that can distinguish «the search descends» from
 * «the search returns whatever it was handed», and the second failure mode is
 * silent — a fitter that never moves reports its starting point as the answer,
 * which reads exactly like a confident fit.
 */
class PhysioAutoFitV1Test {

    private val now = 1_800_000_000_000L
    private val grid = (0..240 step 15).toList()

    private fun model() = HybridPersonModel(
        schemaVersion = 1,
        modelVersion = "test",
        personModelId = "autofit-test",
        runtime = HybridRuntimeParams(horizonMin = 240, stepMin = 5),
        insulin = HybridInsulinParams(
            isf = 2.0,
            isfLow = 1.5,
            isfHigh = 3.0,
            onsetMin = 20.0,
            peakMin = 55.0,
            shortDurationMin = 130.0,
            tailDurationMin = 165.0,
            tailWeight = 0.8,
            tailWeightPerUnit = 0.3,
            tailReferenceUnits = 2.5,
        ),
        food = HybridFoodParams(globalFactor = 0.165, defaultShape = HybridShape(10.0, 55.0, 180.0)),
        activity = HybridActivityParams(0.0, 180.0, 0.0, 60.0),
        basal = HybridBasalParams(60.0, 1080.0, 1800.0, 20.0, 0.0, 0.0),
        joint = HybridJointParams(HybridJointCoefficients(0.0, 0.0), 7.6, backgroundScale = 0.0),
        trend = HybridTrendParams(60, 30.0, 0.5, 0.75, 0.75),
        uncertainty = HybridUncertaintyParams(1.2, 0.7, 0.35),
    )

    private fun knobs(isf: Double, pk: Double) = PhysioAutoFitV1.Knobs(
        isf = isf,
        onsetMin = 25.0,
        fullSpeedMin = pk,
        phaseMin = 30.0,
        tailMin = 300.0,
        emptyingKcalPerHour = 200.0,
        carbSieving = 0.80,
        carbSpread = 1.0,
        trustRamp = 0.0,
    )

    /** An insulin-only stretch: no food, so ISF is identified rather than
     *  trading against a carbohydrate amplitude (the identifiability wall). */
    private fun episodeFrom(truth: PhysioAutoFitV1.Knobs): PhysioAutoFitV1.Episode {
        val blank = PhysioAutoFitV1.Episode(
            startMs = now,
            g0 = 12.0,
            foods = emptyList(),
            boluses = listOf(HybridBolusEvent(now, 4.0)),
            real = grid.map { null },
        )
        val truthCurve = PhysioAutoFitV1.curve(model(), truth, blank, grid)
        assertNotNull("the planted knobs must produce a curve", truthCurve)
        return blank.copy(real = truthCurve!!)
    }

    @Test
    fun `the search recovers a planted ISF it did not start from`() {
        val truth = knobs(isf = 3.2, pk = 55.0)
        val episode = episodeFrom(truth)
        val start = knobs(isf = 1.6, pk = 55.0)

        // Everything but ISF is locked, so this asks one question only: does the
        // descent move, and does it move to the right place.
        val locked = PhysioAutoFitV1.AXES.filter { it != "isf" }.toSet()
        val fit = PhysioAutoFitV1.fitOne(
            model(), episode, start, PhysioAutoFitV1.Metric.BALANCE, grid, locked, minPoints = 6,
        )
        assertNotNull(fit)
        assertEquals("planted ISF not recovered", 3.2, fit!!.knobs.isf, 0.08)
        assertTrue("search did not actually probe", fit.probes > 0)
        assertTrue("residual should be near zero at the truth", fit.score.shape < 0.05)
    }

    @Test
    fun `locking an axis leaves it exactly where it started`() {
        val truth = knobs(isf = 3.2, pk = 55.0)
        val episode = episodeFrom(truth)
        val start = knobs(isf = 1.6, pk = 90.0)
        val fit = PhysioAutoFitV1.fitOne(
            model(), episode, start, PhysioAutoFitV1.Metric.BALANCE, grid,
            locked = setOf("fullSpeed", "isf"), minPoints = 6,
        )
        assertNotNull(fit)
        assertEquals(90.0, fit!!.knobs.fullSpeedMin, 1e-12)
        assertEquals(1.6, fit.knobs.isf, 1e-12)
    }

    /**
     * An impossible shape must be REFUSED, never quietly replaced by the nearest
     * legal neighbour. A substituted arm once printed the previous arm's numbers
     * under the new arm's name (M-59), and in a fitter the same substitution
     * would make a rejected candidate look like a winner.
     */
    @Test
    fun `an impossible landmark order yields no model and no curve`() {
        val bad = knobs(isf = 2.0, pk = 55.0).copy(onsetMin = 120.0, fullSpeedMin = 30.0)
        assertNull(PhysioAutoFitV1.tuned(model(), bad))
        assertNull(PhysioAutoFitV1.curve(model(), bad, episodeFrom(knobs(2.0, 55.0)), grid))
    }

    /**
     * The phone runs the episodes CONCURRENTLY and assembles the answer with
     * [PhysioAutoFitV1.medianOf]; the bench runs them through [fitBatch]. Both
     * must land on the same median, or «the phone suggests something else»
     * returns by a different door than the one A-33 closed.
     */
    @Test
    fun `medianOf agrees with the batch median`() {
        val episodes = listOf(2.6, 3.0, 3.4).map { episodeFrom(knobs(isf = it, pk = 55.0)) }
        val locked = PhysioAutoFitV1.AXES.filter { it != "isf" }.toSet()
        val start = knobs(isf = 1.8, pk = 55.0)
        val batch = PhysioAutoFitV1.fitBatch(
            model(), episodes, start, PhysioAutoFitV1.Metric.BALANCE, grid, locked, minPoints = 6,
        )
        val separately = episodes.mapNotNull {
            PhysioAutoFitV1.fitOne(
                model(), it, start, PhysioAutoFitV1.Metric.BALANCE, grid, locked, minPoints = 6,
            )
        }
        assertEquals(batch.fits.size, separately.size)
        assertEquals(batch.median, PhysioAutoFitV1.medianOf(separately))
    }

    /**
     * THE SAFETY CORRIDOR MUST ACTUALLY BIND.
     *
     * The design rule is that the measured insulin timings bound the search. A
     * bounds map that is accepted and then ignored would be worse than none:
     * the card would print "corridor ±20% around measured" over a fit that
     * had left it.
     * So this plants a truth the search WANTS and checks it is refused.
     */
    @Test
    fun `bounds hold the search inside the corridor`() {
        // Truth at 90 min; the corridor allows 44-66. The fit must stop at the
        // wall rather than walk to the better-scoring answer outside it.
        val episode = episodeFrom(knobs(isf = 2.0, pk = 90.0))
        val locked = PhysioAutoFitV1.AXES.filter { it != "fullSpeed" }.toSet()
        val measured = InsulinShapeLandmarksV1(25.0, 55.0, 85.0, 165.0)
        val corridor = PhysioAutoFitV1.boundsAround(measured)
        val fit = PhysioAutoFitV1.fitOne(
            model(), episode, knobs(isf = 2.0, pk = 55.0),
            PhysioAutoFitV1.Metric.BALANCE, grid, locked, minPoints = 6, bounds = corridor,
        )
        assertNotNull(fit)
        val (lo, hi) = corridor.getValue("fullSpeed")
        assertTrue(
            "fit left the corridor: ${fit!!.knobs.fullSpeedMin} not in $lo..$hi",
            fit.knobs.fullSpeedMin in lo..hi,
        )
        // Guard against passing vacuously: without the corridor the same search
        // must reach much further, or the test proves nothing about bounding.
        val free = PhysioAutoFitV1.fitOne(
            model(), episode, knobs(isf = 2.0, pk = 55.0),
            PhysioAutoFitV1.Metric.BALANCE, grid, locked, minPoints = 6,
        )
        assertNotNull(free)
        assertTrue(
            "unbounded search stayed inside anyway (${free!!.knobs.fullSpeedMin}) — " +
                "this test would pass with bounds ignored",
            free.knobs.fullSpeedMin > hi,
        )
    }

    /**
     * A start OUTSIDE the corridor must be pulled in, not reported back.
     *
     * This was caught live on the app's own screen: the card promised "only
     * within corridor 10 · 51 · 17 · 130" above a fitted phase of 40. The candidates were
     * clamped; the starting point was not, so an unimproved axis walked out
     * through the front door.
     */
    @Test
    fun `a starting point outside the corridor is pulled inside`() {
        val episode = episodeFrom(knobs(isf = 2.0, pk = 55.0))
        val measured = InsulinShapeLandmarksV1(25.0, 55.0, 72.0, 165.0)
        val corridor = PhysioAutoFitV1.boundsAround(measured)
        val (lo, hi) = corridor.getValue("phase")
        // 40 is outside 12..22 by construction — the same shape as the real case.
        val start = knobs(isf = 2.0, pk = 55.0).copy(phaseMin = 40.0)
        assertTrue("test setup: start must be outside", start.phaseMin > hi)
        val fit = PhysioAutoFitV1.fitOne(
            model(), episode, start, PhysioAutoFitV1.Metric.BALANCE, grid,
            locked = PhysioAutoFitV1.AXES.filter { it != "phase" }.toSet(),
            minPoints = 6, bounds = corridor,
        )
        assertNotNull(fit)
        assertTrue(
            "fit reported ${fit!!.knobs.phaseMin}, outside $lo..$hi",
            fit.knobs.phaseMin in lo..hi,
        )
    }

    /** A LOCKED axis outranks the corridor — the lock is the user's instruction. */
    @Test
    fun `a locked axis keeps its value even outside the corridor`() {
        val episode = episodeFrom(knobs(isf = 2.0, pk = 55.0))
        val corridor = PhysioAutoFitV1.boundsAround(
            InsulinShapeLandmarksV1(25.0, 55.0, 72.0, 165.0),
        )
        val start = knobs(isf = 2.0, pk = 55.0).copy(phaseMin = 40.0)
        val fit = PhysioAutoFitV1.fitOne(
            model(), episode, start, PhysioAutoFitV1.Metric.BALANCE, grid,
            locked = PhysioAutoFitV1.AXES.toSet(), minPoints = 6, bounds = corridor,
        )
        assertNotNull(fit)
        assertEquals(40.0, fit!!.knobs.phaseMin, 1e-12)
    }

    @Test
    fun `plausible physiology refuses a degenerate sieve`() {
        val (lo, hi) = PhysioAutoFitV1.PLAUSIBLE_PHYSIOLOGY.getValue("sieve")
        assertTrue("a sieve of zero means carbohydrate never appears", lo > 0.0)
        assertEquals(1.0, hi, 1e-12)
        val (slo, _) = PhysioAutoFitV1.PLAUSIBLE_PHYSIOLOGY.getValue("spread")
        assertTrue("a spread of zero makes fast and slow the same substance", slo > 0.0)
    }

    @Test
    fun `the batch median is the median of the per-episode fits`() {
        val episodes = listOf(2.6, 3.0, 3.4).map { episodeFrom(knobs(isf = it, pk = 55.0)) }
        val locked = PhysioAutoFitV1.AXES.filter { it != "isf" }.toSet()
        val batch = PhysioAutoFitV1.fitBatch(
            model(), episodes, knobs(isf = 1.8, pk = 55.0),
            PhysioAutoFitV1.Metric.BALANCE, grid, locked, minPoints = 6,
        )
        assertEquals(3, batch.fits.size)
        assertEquals("median should land on the middle episode", 3.0, batch.median.isf, 0.12)
        val fitted = batch.fits.map { it.second.knobs.isf }.sorted()
        assertEquals(fitted[1], batch.median.isf, 1e-12)
    }
}
