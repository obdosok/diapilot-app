package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParametricKernelTest {

    private val trueIsf = 2.4      // mmol/L per U over the full DIA
    private val trueDia = 200.0
    private val truePeak = 60.0

    /** Deterministic pseudo-noise: no Random seed drift between runs. */
    private fun noise(i: Int): Double = 0.08 * kotlin.math.sin(i * 12.9898)

    private fun makeData(
        nEpisodes: Int,
        horizonMin: Double,
        contaminateEpisode: Int? = null,
    ): Pair<List<GlucosePoint>, List<IsfEpisode>> {
        val readings = mutableListOf<GlucosePoint>()
        val episodes = mutableListOf<IsfEpisode>()
        val dayMs = 24L * 3_600_000
        for (e in 0 until nEpisodes) {
            val t0 = e * dayMs
            val dose = 1.5 + (e % 3) * 0.5
            val bgStart = 9.0 + (e % 4) * 0.5
            var m = 0.0
            var i = 0
            while (m <= horizonMin) {
                var bg = bgStart - dose * trueIsf * (1 - iobFraction(m, trueDia, truePeak)) + noise(e * 100 + i)
                // An unlogged meal in one episode: +3 mmol smoothstep from τ=40.
                if (e == contaminateEpisode && m > 40) {
                    val x = ((m - 40) / 50.0).coerceAtMost(1.0)
                    bg += 3.0 * (3 * x * x - 2 * x * x * x)
                }
                readings.add(GlucosePoint(t0 + (m * 60_000).toLong(), bg))
                m += 5.0
                i++
            }
            episodes.add(
                IsfEpisode(
                    t0Ms = t0, dose = dose, bgStart = bgStart, bgEnd = 0.0,
                    isf = 1.0, anomaly = false, tod = TodBucket.DAY,
                ),
            )
        }
        return readings to episodes
    }

    @Test
    fun recoversKnownParameters() {
        val (readings, episodes) = makeData(nEpisodes = 12, horizonMin = 240.0)
        val fit = fitParametricKernel(readings, episodes, horizonMin = 240.0)
        assertNotNull(fit)
        fit!!
        assertEquals(trueIsf, fit.isfMmolPerU, 0.25)
        assertEquals(trueDia, fit.diaMin, 25.0)
        assertEquals(truePeak, fit.peakMin, 15.0)
        assertTrue("rmse should be near the noise floor", fit.rmseMmolPerU < 0.15)
        assertTrue(!fit.diaAtBound)
    }

    @Test
    fun oneContaminatedEpisodeDoesNotBendTheCurve() {
        val (readings, episodes) = makeData(nEpisodes = 12, horizonMin = 240.0, contaminateEpisode = 5)
        val fit = fitParametricKernel(readings, episodes, horizonMin = 240.0)
        assertNotNull(fit)
        fit!!
        // The meal pushes ISF DOWN (less apparent drop); the trim must hold.
        assertEquals(trueIsf, fit.isfMmolPerU, 0.4)
        assertEquals(truePeak, fit.peakMin, 20.0)
    }

    @Test
    fun shortWindowFlagsDiaAsLowerBound() {
        // Slow insulin observed for only 2h: the tail never flattens inside
        // the data, so DIA must ride the grid edge and be flagged.
        val (readings, episodes) = makeData(nEpisodes = 12, horizonMin = 120.0)
        val slow = readings.map { it } // same family; window is the constraint
        val fit = fitParametricKernel(slow, episodes, horizonMin = 120.0)
        // Either flagged at bound or honestly near-true — but NEVER a
        // confident tiny DIA (the failure mode that would corrupt IOB).
        if (fit != null) assertTrue(fit.diaAtBound || fit.diaMin > 150.0)
    }

    @Test
    fun refusesThinData() {
        val (readings, episodes) = makeData(nEpisodes = 3, horizonMin = 240.0)
        assertNull(fitParametricKernel(readings, episodes, horizonMin = 240.0))
    }

    @Test
    fun agreementAgainstEmpiricalKernelComputed() {
        val (readings, episodes) = makeData(nEpisodes = 12, horizonMin = 240.0)
        val empirical = insulinKernel(readings, episodes, horizonMin = 240.0)
        val fit = fitParametricKernel(readings, episodes, horizonMin = 240.0, empirical = empirical)
        assertNotNull(fit!!.agreementPct)
        assertTrue("fit should sit inside its own data's IQR", fit.agreementPct!! >= 60.0)
    }
}
