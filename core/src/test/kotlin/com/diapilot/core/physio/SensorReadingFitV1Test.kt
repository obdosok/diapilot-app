package com.diapilot.core.physio

import com.diapilot.core.collector.GlucosePoint
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read the correction the way its owner reads it.
 *
 * The user's description: plateau or light slope · injection · the
 * trajectory breaks · steepest fall · slowing · tail. These pin that the
 * estimator finds those five things on a line that has them, and does not
 * invent them on a line that does not.
 */
class SensorReadingFitV1Test {
    private val bolus = 1_800_000_000_000L
    private fun at(min: Double) = bolus + (min * 60_000).toLong()

    /** Fraction of action delivered by `m`, with a real onset and a tail. */
    private fun action(m: Double, onset: Double = 30.0, end: Double = 150.0): Double {
        val u = ((m - onset) / (end - onset)).coerceIn(0.0, 1.0)
        return u * u * (3.0 - 2.0 * u)
    }

    /**
     * A typical example: 17.2 before the dose, down to about 3 over three hours. Flat
     * before, a clean break, a steepest stretch, a slowing, a tail.
     */
    private fun line(
        preSlopePerHour: Double = 0.0,
        onset: Double = 30.0,
        end: Double = 150.0,
        drop: Double = 14.2,
        noise: Double = 0.0,
    ) = (-30..200 step 5).map { m ->
        val t = m.toDouble()
        val base = 17.2 + preSlopePerHour * (t.coerceAtMost(60.0)) / 60.0
        val wobble = if (noise == 0.0) 0.0 else noise * (if ((m / 5) % 2 == 0) 1.0 else -1.0)
        GlucosePoint(at(t), base - drop * action(t, onset, end) + wobble)
    }

    private fun read(readings: List<GlucosePoint>, units: Double = 3.5) =
        SensorCorrectionReaderV1.read(readings, bolus, units, at(200.0))

    // ---- the five things the user looks at --------------------------------

    @Test fun itFindsTheBreakTheSteepestFallAndTheTail() {
        val f = checkNotNull(read(line()))
        assertTrue("landmarks must be ordered: $f", f.ordered)
        assertTrue("the break sits near the real onset: ${f.onsetMin}", abs(f.onsetMin - 30.0) <= 15.0)
        assertTrue("the steepest fall sits mid-curve: ${f.peakRateMin}", f.peakRateMin in 60.0..120.0)
        assertTrue("and the line settles before the window ends: ${f.tailEndMin}", f.tailEndMin >= 120.0)
    }

    @Test fun itMeasuresTheDropTheSensorActuallyShows() {
        val f = checkNotNull(read(line(drop = 14.2), units = 3.5))
        assertEquals("the drop is what the line did", 14.2, f.dropMmol, 1.5)
        assertEquals("and ISF follows from it", 14.2 / 3.5, f.isfMmolPerU, .5)
    }

    /**
     * The point of the whole estimator: no simulated background. Whatever the
     * line already carried stays inside the line, so a food model that
     * overshoots cannot turn a good correction into a negative score.
     */
    @Test fun aLightPreSlopeIsCarriedAsInertiaNotAsAConfounder() {
        val flat = checkNotNull(read(line(preSlopePerHour = 0.0)))
        val drifting = checkNotNull(read(line(preSlopePerHour = 1.5)))
        assertTrue("a drifting line still reads: ${drifting.isfMmolPerU}", drifting.isfMmolPerU > 0.0)
        assertTrue(
            "and reads close to the flat one: ${flat.isfMmolPerU} vs ${drifting.isfMmolPerU}",
            abs(flat.isfMmolPerU - drifting.isfMmolPerU) < 1.5,
        )
    }

    @Test fun aLaterOnsetIsReadAsALaterOnset() {
        val early = checkNotNull(read(line(onset = 20.0)))
        val late = checkNotNull(read(line(onset = 50.0)))
        assertTrue(
            "30 minutes later must read later: ${early.onsetMin} vs ${late.onsetMin}",
            late.onsetMin > early.onsetMin + 10.0,
        )
    }

    @Test fun theCurveItReturnsIsAUsableCdf() {
        val f = checkNotNull(read(line()))
        assertTrue(f.cdf.size >= 4)
        assertEquals(0.0, f.cdf.first().fraction, 1e-9)
        assertEquals(1.0, f.cdf.last().fraction, 1e-9)
        assertTrue(f.cdf.zipWithNext().all { (a, b) -> b.minute > a.minute && b.fraction >= a.fraction })
    }

    /**
     * The failure the detector was rebuilt for. Measured on the user's device:
     * five of eight doses read a break at 3-15 min on lines whose
     * owner reads the fall from 25-30. A ripple after the injection is not a
     * trajectory change.
     */
    @Test fun aRippleAfterTheInjectionIsNotTheBreak() {
        // A dip that lasts long enough to survive smoothing and then recovers
        // fully — the shape of a swallow, a lean, a compression low.
        val rippled = line(onset = 40.0).map {
            val t = (it.tsMs - bolus) / 60_000.0
            if (t in 5.0..20.0) GlucosePoint(it.tsMs, it.mmol - 1.2) else it
        }
        val f = checkNotNull(read(rippled))
        assertTrue(
            "the break must sit at the real fall (40), not at the ripple (5): ${f.onsetMin}",
            abs(f.onsetMin - 40.0) < abs(f.onsetMin - 5.0),
        )
    }

    /** A restless line raises its own bar rather than tripping over itself. */
    @Test fun aNoisyLineDoesNotBreakEarlierThanAQuietOne() {
        val quiet = checkNotNull(read(line(onset = 40.0)))
        val restless = checkNotNull(read(line(onset = 40.0, noise = .45)))
        assertTrue(
            "noise must not pull the break forward: ${quiet.onsetMin} vs ${restless.onsetMin}",
            restless.onsetMin >= quiet.onsetMin - 10.0,
        )
    }

    /**
     * A single dip is noise, not the onset. CGM jitters by a few tenths every
     * few minutes; without a hold requirement the break would be read at the
     * first flicker after the injection, which is exactly the number the
     * patient would notice as wrong.
     */
    @Test fun oneFlickerBeforeTheRealBreakIsNotTheOnset() {
        val flickered = line().map {
            val t = (it.tsMs - bolus) / 60_000.0
            if (t == 10.0) GlucosePoint(it.tsMs, it.mmol - 0.9) else it
        }
        val f = checkNotNull(read(flickered))
        assertTrue("a 5-minute dip must not become the onset: ${f.onsetMin}", f.onsetMin >= 20.0)
    }

    /**
     * Inertia decays. A pre-dose drift projected across three hours inflates
     * the baseline, and with it the drop credited to insulin — the same failure
     * that put a counterfactual at -6.6 mmol/L.
     */
    @Test fun aSteepInertiaIsHeldSoTheDropStaysHonest() {
        val steady = checkNotNull(read(line(preSlopePerHour = 0.0), units = 3.5))
        val drifting = checkNotNull(read(line(preSlopePerHour = 3.0), units = 3.5))
        assertTrue(
            "an extrapolated inertia inflates the drop: ${steady.dropMmol} vs ${drifting.dropMmol}",
            drifting.dropMmol - steady.dropMmol < 3.5,
        )
    }

    // ---- what it refuses --------------------------------------------------

    @Test fun aLineThatNeverBreaksIsNotACorrection() {
        // Glucose simply drifts down at its pre-dose rate throughout.
        val drifting = (-30..200 step 5).map { GlucosePoint(at(it.toDouble()), 12.0 - it * .004) }
        assertNull("no break, no reading", read(drifting))
    }

    @Test fun aLineThatOnlyRisesIsNotACorrection() {
        val rising = (-30..200 step 5).map { GlucosePoint(at(it.toDouble()), 8.0 + it * .02) }
        assertNull(read(rising))
    }

    @Test fun tooLittleLineEitherSideIsNotReadable() {
        val short = (-5..20 step 5).map { GlucosePoint(at(it.toDouble()), 12.0 - it * .05) }
        assertNull(read(short))
    }

    /** Roughness is reported, not judged — the user's own "smooth line" criterion. */
    @Test fun noiseShowsUpAsRoughnessWithoutBreakingTheReading() {
        val clean = checkNotNull(read(line()))
        val noisy = checkNotNull(read(line(noise = .35)))
        assertTrue("a jittery line must read rougher", noisy.roughnessMmol > clean.roughnessMmol)
        assertTrue("but still read: ${noisy.isfMmolPerU}", noisy.isfMmolPerU > 0.0)
    }
}
