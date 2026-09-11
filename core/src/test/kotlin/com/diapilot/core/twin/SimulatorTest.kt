/** Contract tests per twin spec §6: exact reconstruction, skill vs persistence, corridor. */
package com.diapilot.core.twin

import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private const val BASE = 1_748_768_400_000L
private const val MIN = 60_000L

/** Linear kernel: 0 at τ=0 falling to -2.0 at τ=180, flat after. */
private fun linearKernel(): List<KernelPoint> =
    (0..48).map { i ->
        val tau = i * 5.0
        val v = if (tau >= 180) -2.0 else -2.0 * tau / 180.0
        KernelPoint(tau, v, v - 0.2, v + 0.2, 10)
    }

class SimulatorTest {

    @Test
    fun `no boluses predicts flat`() {
        val sim = simulateForward(BASE, 8.0, emptyList(), linearKernel())
        assertTrue(sim.all { abs(it.mmol - 8.0) < 1e-9 })
    }

    @Test
    fun `bolus at anchor reproduces the kernel exactly`() {
        // Spec acceptance: on synthetic data without drift, MAE < 0.1.
        val sim = simulateForward(BASE, 10.0, listOf(BolusPoint(BASE, 1.5)), linearKernel())
        val at90 = sim.first { it.tsMs == BASE + 90 * MIN }
        val at180 = sim.first { it.tsMs == BASE + 180 * MIN }
        assertEquals(10.0 - 1.5, at90.mmol, 0.1)       // half of -2.0/U * 1.5U
        assertEquals(10.0 - 3.0, at180.mmol, 0.1)      // full effect
        assertEquals(10.0, sim.first().mmol, 1e-9)     // anchor itself unchanged
    }

    @Test
    fun `pre-anchor bolus contributes only its remaining action`() {
        // Bolus 90 min before the anchor: half already realized, half to come.
        val sim = simulateForward(BASE, 9.0, listOf(BolusPoint(BASE - 90 * MIN, 2.0)), linearKernel())
        val at90 = sim.first { it.tsMs == BASE + 90 * MIN }  // bolus τ=180 by then
        assertEquals(9.0 - 2.0, at90.mmol, 0.1)              // remaining half of 2U*2.0
        // After that the kernel is flat — nothing more happens.
        assertEquals(at90.mmol, sim.last().mmol, 0.1)
    }

    @Test
    fun `twin beats persistence on a clean correction`() {
        // Truth: linear fall matching the kernel. Persistence holds flat.
        val kernel = linearKernel()
        val bolus = listOf(BolusPoint(BASE, 2.0))
        val truth = (0..36).map { i ->
            val tau = i * 5.0
            GlucosePoint(BASE + (tau * MIN).toLong(), 10.0 + 2.0 * g(kernel, tau))
        }
        val sim = simulateForward(BASE, 10.0, bolus, kernel)
        val maeTwin = truth.zip(sim).sumOf { (t, p) -> abs(t.mmol - p.mmol) } / truth.size
        val maePersist = truth.sumOf { abs(it.mmol - 10.0) } / truth.size
        assertTrue(maeTwin < 0.05)
        assertTrue("skill score must be positive", 1 - maeTwin / maePersist > 0)
    }

    @Test
    fun `corridor widens with time and covers noisy synthetic data`() {
        // Flat truth + deterministic "noise"; no insulin.
        val readings = (0 until 2000).map { i ->
            val noise = 0.4 * kotlin.math.sin(i * 0.7)
            GlucosePoint(BASE + i * 5 * MIN, 7.0 + noise)
        }
        val corridor = calibrateCorridor(readings, emptyList(), linearKernel())
        assertTrue(corridor.w0 >= 0.2)
        assertTrue(corridor.halfWidth(120.0) > corridor.halfWidth(15.0))

        // Coverage: predictions from any anchor stay inside w for this noise level.
        val pred = predictWithCorridor(BASE, 7.0, emptyList(), linearKernel(), corridor)
        val covered = readings.take(37).count { r ->
            val p = pred.firstOrNull { it.tsMs == r.tsMs } ?: return@count false
            r.mmol in p.lo..p.hi
        }
        assertTrue(covered >= 30)
    }

    @Test
    fun `active food raises the prediction by its learned response`() {
        // Smoothie eaten at the anchor: +3.0 over 60 min, no insulin.
        val food = ActiveFood(onsetMs = BASE, rise = 3.0, timeToPeakMin = 60.0)
        val sim = simulateForward(BASE, 7.0, emptyList(), linearKernel(), foods = listOf(food))
        assertEquals(7.0, sim.first().mmol, 1e-9)
        assertEquals(8.5, sim.first { it.tsMs == BASE + 30 * MIN }.mmol, 1e-9) // smoothstep(0.5)=0.5
        assertEquals(10.0, sim.first { it.tsMs == BASE + 60 * MIN }.mmol, 1e-9)
        assertEquals(10.0, sim.last().mmol, 1e-9) // plateau after peak
    }

    @Test
    fun `food that peaked before the anchor adds nothing`() {
        val food = ActiveFood(onsetMs = BASE - 2 * 60 * MIN, rise = 4.0, timeToPeakMin = 60.0)
        val sim = simulateForward(BASE, 9.0, emptyList(), linearKernel(), foods = listOf(food))
        assertTrue(sim.all { abs(it.mmol - 9.0) < 1e-9 })
    }

    @Test
    fun `mid-absorption food contributes only the remaining rise`() {
        // Eaten 30 min before the anchor: half realized, half to come.
        val food = ActiveFood(onsetMs = BASE - 30 * MIN, rise = 3.0, timeToPeakMin = 60.0)
        val sim = simulateForward(BASE, 8.0, emptyList(), linearKernel(), foods = listOf(food))
        assertEquals(8.0, sim.first().mmol, 1e-9)
        assertEquals(9.5, sim.first { it.tsMs == BASE + 30 * MIN }.mmol, 1e-9)
        assertEquals(9.5, sim.last().mmol, 1e-9)
    }

    @Test
    fun `late food phase starts after early peak and remains active`() {
        val food = ActiveFood(
            onsetMs = BASE,
            rise = 3.0,
            timeToPeakMin = 60.0,
            tailRise = 2.0,
            tailTtpMin = 240.0,
            tailStartMin = 60.0,
        )
        val fromMeal = simulateForward(BASE, 7.0, emptyList(), linearKernel(), foods = listOf(food))
        // No tail is realized before its delayed start.
        assertEquals(10.0, fromMeal.first { it.tsMs == BASE + 60 * MIN }.mmol, 1e-9)
        assertTrue(fromMeal.first { it.tsMs == BASE + 120 * MIN }.mmol > 10.0)

        // At hour two the fast phase has ended, but the late phase must not
        // disappear from the remaining forecast.
        val fromHourTwo = simulateForward(
            BASE + 120 * MIN, 10.5, emptyList(), linearKernel(), foods = listOf(food),
        )
        assertTrue(fromHourTwo.last().mmol > 10.5)
    }

    @Test
    fun `food and insulin superpose`() {
        // Meal + bolus at the anchor: rise realizes over 1h, insulin pulls
        // -2/U over 3h; end state = 7 + 3 - 3 = 7.
        val food = ActiveFood(onsetMs = BASE, rise = 3.0, timeToPeakMin = 60.0)
        val sim = simulateForward(
            BASE, 7.0, listOf(BolusPoint(BASE, 1.5)), linearKernel(), foods = listOf(food),
        )
        assertEquals(7.0, sim.first { it.tsMs == BASE + 180 * MIN }.mmol, 0.1)
        // Peak lands mid-way: food faster than insulin.
        assertTrue(sim.maxOf { it.mmol } > 8.0)
    }

    @Test
    fun `kernel boundary conditions`() {
        val kernel = linearKernel()
        assertEquals(0.0, g(kernel, -10.0), 1e-9)
        assertEquals(0.0, g(kernel, 0.0), 1e-9)
        assertEquals(-2.0, g(kernel, 400.0), 1e-9)
    }

    @Test
    fun `signed bins yield an asymmetric corridor`() {
        // Residuals skewed down: reality mostly UNDERSHOOTS the model
        // (post-bolus physics). The lower band must earn real width, the
        // upper must stay thin — a symmetric band would lie upward.
        val bins = Array(12) { mutableListOf<Double>() }
        for (b in bins.indices) {
            repeat(30) { i ->
                // 25 draws scattered down to -2.0, 5 mildly up to +0.3.
                bins[b].add(if (i < 25) -2.0 * (i + 1) / 25.0 else 0.3 * (i - 24) / 5.0)
            }
        }
        val c = corridorFromSignedBins(bins, binMin = 15.0, minPerBin = 20, minBins = 3)!!
        assertTrue("down side dominates: dn=${c.dnAt(60.0)} up=${c.upAt(60.0)}",
            c.dnAt(60.0) > c.upAt(60.0) * 2)
        // The inner band nests inside the outer on both sides.
        assertTrue(c.midUpAt(60.0) <= c.upAt(60.0) + 1e-9)
        assertTrue(c.midDnAt(60.0) <= c.dnAt(60.0) + 1e-9)
        // predictWithCorridor carries the asymmetry into the points.
        val pts = predictWithCorridor(BASE, 8.0, emptyList(), linearKernel(), c)
        val p60 = pts.first { it.tsMs == BASE + 60 * MIN }
        assertTrue(8.0 - p60.lo > (p60.hi - 8.0) * 2)
        assertTrue(p60.loMid >= p60.lo && p60.hiMid <= p60.hi)
    }

    @Test
    fun `intervals nest at every horizon even with hostile coefficients`() {
        // Inner fitted STEEPER than outer (independent quantile regressions
        // can do this): the accessors must clamp, lo≤loMid≤m≤hiMid≤hi always.
        val c = Corridor(
            w0 = 1.0, k = 0.1,
            upW0 = 0.3, upK = 0.05, dnW0 = 0.4, dnK = 0.06,
            midUpW0 = 0.1, midUpK = 0.5,   // overtakes up after ~11 min
            midDnW0 = 0.9, midDnK = 0.01,  // starts wider than dn
        )
        var dt = 0.0
        while (dt <= 360.0) {
            assertTrue("up@$dt", c.midUpAt(dt) <= c.upAt(dt) + 1e-12)
            assertTrue("dn@$dt", c.midDnAt(dt) <= c.dnAt(dt) + 1e-12)
            dt += 5.0
        }
        val pts = predictWithCorridor(BASE, 8.0, emptyList(), linearKernel(), c, horizonMin = 360.0)
        for (p in pts) {
            assertTrue(p.lo <= p.loMid && p.loMid <= p.mmol)
            assertTrue(p.mmol <= p.hiMid && p.hiMid <= p.hi)
        }
    }

    @Test
    fun `truncation honors knownAt not onset`() {
        // Detector meal: onset 12:00, detected 13:00, anchor 11:45. The live
        // forecast kept showing (wrong) numbers until 13:00 — residuals up to
        // knownAt belong in the corridor; cutting at onset hides them.
        val anchor = BASE
        val food = ActiveFood(
            onsetMs = anchor + 15 * MIN,            // "12:00"
            rise = 3.0, timeToPeakMin = 60.0,
            knownAtMs = anchor + 75 * MIN,          // "13:00"
        )
        assertEquals(anchor + 75 * MIN, firstEventAfter(anchor, emptyList(), listOf(food)))
        // Manually logged food (knownAt == onset) truncates at onset.
        val manual = food.copy(knownAtMs = food.onsetMs)
        assertEquals(anchor + 15 * MIN, firstEventAfter(anchor, emptyList(), listOf(manual)))
        // A bolus beats a later food.
        assertEquals(
            anchor + 5 * MIN,
            firstEventAfter(anchor, listOf(BolusPoint(anchor + 5 * MIN, 2.0)), listOf(food)),
        )
    }

    @Test
    fun `corridor is conditional - residuals stop at the first new event`() {
        // Flat glucose for 3h, then a bolus at +90min followed by a huge
        // unmodeled plunge. Conditional calibration must NOT charge that
        // post-event plunge to the corridor: a live forecast is redrawn at
        // the bolus. One anchor (thin stream) → compare against a run where
        // the same plunge happens with NO event logged.
        fun stream(plungeExplained: Boolean): Corridor {
            val readings = (0..36).map { i ->
                val t = BASE + i * 5 * MIN
                val v = if (i <= 18) 8.0 else 8.0 - (i - 18) * 0.5   // plunge after +90m
                GlucosePoint(t, v)
            }
            val boluses = if (plungeExplained) listOf(BolusPoint(BASE + 90 * MIN, 5.0)) else emptyList()
            return calibrateCorridor(
                readings, boluses, linearKernel(),
                anchorStepMin = 500.0,  // single anchor at the start
            )
        }
        // Too-thin data falls back either way — so compare widths at +150min:
        // with the bolus logged, residuals stop at +90m and the plunge never
        // enters; without it, the plunge lands in the far bins.
        val explained = stream(true)
        val unexplained = stream(false)
        assertTrue(
            "explained=${explained.dnAt(150.0)} unexplained=${unexplained.dnAt(150.0)}",
            explained.dnAt(150.0) <= unexplained.dnAt(150.0),
        )
    }
}

class TwoGammaFoodTest {
    private fun foodAt(food: ActiveFood, tau: Double) = foodDelta(food, tau)

    @Test
    fun `two-gamma fast component saturates near its rise, monotone increasing`() {
        val food = ActiveFood(
            onsetMs = 0L, rise = 3.0, timeToPeakMin = 40.0,
            absorption = TwoGamma(fastRise = 3.0, slowRise = 0.0, fastPeakMin = 40.0, slowPeakMin = 160.0),
        )
        val d40 = foodAt(food, 40.0)
        val d120 = foodAt(food, 120.0)
        // fastRise is the measured plateau: ~90%+ realized BY the peak time.
        assertTrue("near the plateau by the peak, got $d40", d40 in 2.6..3.0)
        assertTrue("at plateau by 120, got $d120", d120 > 2.9)
        assertTrue("monotone", foodAt(food, 20.0) < d40 && d40 < d120)
    }

    @Test
    fun `a slow component keeps a pizza rising long after the fast peak`() {
        val smoothie = ActiveFood(0L, 3.0, 40.0, absorption = TwoGamma(3.0, 0.0, 40.0, 160.0))
        val pizza = ActiveFood(0L, 3.0, 40.0, absorption = TwoGamma(3.0, 3.0, 40.0, 200.0))
        // By 90 min both are mostly through the fast phase; the pizza's slow
        // component then adds a lot more by 3-4 h, the smoothie almost nothing.
        val smoothieLate = foodAt(smoothie, 240.0) - foodAt(smoothie, 90.0)
        val pizzaLate = foodAt(pizza, 240.0) - foodAt(pizza, 90.0)
        assertTrue("smoothie flat late (${smoothieLate})", smoothieLate < 0.4)
        assertTrue("pizza climbs late (${pizzaLate})", pizzaLate > 1.0)
    }

    @Test
    fun `no absorption falls back to the legacy smoothstep`() {
        val food = ActiveFood(0L, rise = 3.0, timeToPeakMin = 40.0)   // absorption null
        assertEquals(3.0, foodAt(food, 40.0), 1e-9)   // smoothstep reaches rise at ttp
        assertEquals(0.0, foodAt(food, 0.0), 1e-9)
    }
}
