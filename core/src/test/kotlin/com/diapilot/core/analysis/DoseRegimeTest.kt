package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseRegimeTest {

    private val dayMs = 86_400_000L

    private fun doses(weeks: Int, unitsPerShot: Double, startWeek: Int = 0) =
        (0 until weeks * 7).flatMap { d ->
            (0 until 5).map { k ->
                BolusPoint((startWeek * 7 + d) * dayMs + k * 3L * 3_600_000, unitsPerShot)
            }
        }

    @Test
    fun findsTheDoseDrop() {
        // 8 weeks of 8U shots, then 8 weeks of 3.5U (dropped & split).
        val boluses = doses(8, 8.0) + doses(8, 3.5, startWeek = 8)
        val c = detectDoseRegimeChange(boluses)
        assertNotNull(c)
        c!!
        assertEquals(8.0, c.beforeMedianU, 0.5)
        assertEquals(3.5, c.afterMedianU, 0.5)
        // The break lands near week 8.
        assertEquals(8 * 7 * dayMs.toDouble(), c.changeMs.toDouble(), 14.0 * dayMs)
    }

    @Test
    fun stableDosingHasNoChangepoint() {
        assertNull(detectDoseRegimeChange(doses(16, 6.0)))
    }

    @Test
    fun recencyWeightsHalveMonthByMonth() {
        val now = 200L * dayMs
        fun ep(ageDays: Long) = IsfEpisode(
            t0Ms = now - ageDays * dayMs, dose = 2.0, bgStart = 10.0, bgEnd = 8.0,
            isf = 1.0, anomaly = false, tod = TodBucket.DAY,
        )
        val eps = listOf(ep(0), ep(35), ep(70))
        val w = episodeWeights(eps, now, halfLifeDays = 35.0)
        assertEquals(1.0, w[0], 1e-6)
        assertEquals(0.5, w[1], 0.01)
        assertEquals(0.25, w[2], 0.01)
        // Pre-changepoint discount stacks on top.
        val w2 = episodeWeights(eps, now, halfLifeDays = 35.0, changepointMs = now - 50 * dayMs)
        assertEquals(0.25 * 0.3, w2[2], 0.01)
    }

    @Test
    fun weightedKernelFollowsTheRecentBody() {
        // Old era: 1 mmol/U drop; recent era: 2 mmol/U. The weighted kernel
        // plateau must sit much closer to the recent body.
        val now = 100L * dayMs
        val readings = mutableListOf<GlucosePoint>()
        val episodes = mutableListOf<IsfEpisode>()
        fun addEpisode(t0: Long, isf: Double) {
            episodes.add(IsfEpisode(t0, 1.0, 10.0, 10.0 - isf, isf, false, TodBucket.DAY))
            var m = 0.0
            while (m <= 240.0) {
                readings.add(GlucosePoint(t0 + (m * 60_000).toLong(), 10.0 - isf * minOf(m / 120.0, 1.0)))
                m += 5.0
            }
        }
        for (d in 0 until 10) addEpisode(d * dayMs, 1.0)                  // 90+ days old
        for (d in 0 until 10) addEpisode((95 + d / 2) * dayMs + (d % 2) * 12 * 3_600_000, 2.0)
        val w = episodeWeights(episodes, now, halfLifeDays = 35.0)
        val kernel = insulinKernel(readings, episodes, weights = w)
        val plateau = -kernel.last().median
        assertTrue("plateau $plateau should be near the recent 2.0", plateau > 1.7)
    }

    @Test
    fun changeIsBehaviorUntilResponseConfirmsIt() {
        val dayMs = 86_400_000L
        val boluses = doses(8, 8.0) + doses(8, 3.5, startWeek = 8)
        // Episodes with the SAME ISF on both sides -> behavior only.
        fun ep(week: Int, isf: Double) = IsfEpisode(
            week * 7L * dayMs, 2.0, 10.0, 10.0 - isf, isf, false, TodBucket.DAY,
        )
        val sameIsf = (0..5).map { ep(it, 1.0) } + (9..14).map { ep(it, 1.0) }
        val c1 = detectDoseRegimeChange(boluses, episodes = sameIsf)!!
        assertTrue(!c1.responseConfirmed)
        // Episodes whose ISF doubled after the break -> confirmed.
        val shifted = (0..5).map { ep(it, 1.0) } + (9..14).map { ep(it, 2.0) }
        val c2 = detectDoseRegimeChange(boluses, episodes = shifted)!!
        assertTrue(c2.responseConfirmed)
        // ISF moved the WRONG way (doses dropped but insulin got WEAKER):
        // conflicting directions must not count as confirmation.
        val conflicting = (0..5).map { ep(it, 2.0) } + (9..14).map { ep(it, 1.0) }
        val c3 = detectDoseRegimeChange(boluses, episodes = conflicting)!!
        assertTrue(!c3.responseConfirmed)
    }

    @Test
    fun effectiveSampleSizeCollapsesWhenFewDominate() {
        // 10 equal weights -> ESS 10.
        assertEquals(10.0, effectiveSampleSize(DoubleArray(10) { 1.0 }), 1e-6)
        // 9 tiny + 1 huge -> ESS near 1.
        val w = DoubleArray(10) { if (it == 0) 1.0 else 0.01 }
        assertTrue("ess ${effectiveSampleSize(w)}", effectiveSampleSize(w) < 1.5)
    }

    @Test
    fun detectsPersistentSensitivityShiftWithoutDoseChange() {
        fun ep(day: Int, isf: Double) = IsfEpisode(
            day * dayMs, 2.0, 10.0, 10.0 - isf, isf, false, TodBucket.DAY,
        )
        val eps = (0 until 8).map { ep(it, 1.0 + (it % 2) * 0.03) } +
            (8 until 16).map { ep(it, 1.6 + (it % 2) * 0.03) }
        val change = detectSensitivityRegimeChange(eps)
        assertNotNull(change)
        assertTrue(change!!.afterIsf > change.beforeIsf * 1.4)
    }

    @Test
    fun noisyButStableSensitivityHasNoShift() {
        val eps = (0 until 20).map { day ->
            val isf = 1.2 + (day % 4 - 2) * 0.04
            IsfEpisode(day * dayMs, 2.0, 10.0, 10.0 - isf, isf, false, TodBucket.DAY)
        }
        assertNull(detectSensitivityRegimeChange(eps))
    }

    private val eraStart = 100L * dayMs
    private fun ampEp(day: Long, isf: Double, dose: Double = 2.5, inBout: Boolean = false) =
        IsfEpisode(
            day * dayMs, dose, 10.0, 10.0 - isf * dose, isf, false, TodBucket.DAY,
            activityContaminated = inBout,
        )
    /** The blind-era corpus: many episodes, all reading insulin as weak. */
    private val biasedCorpus = (0L until 20L).map { ampEp(it, 1.0) }
    /** TDD 38.8 U/day -> rule-1800 2.58, this body's real numbers. */
    private val prior = IsfPrior(2.58, 38.8, clamped = false, tddIncomplete = false)

    @Test
    fun theEighteenHundredRuleReproducesThisBodysNumber() {
        val p = isfPriorFromTdd(TddWindow(38.82, 266.0, 278.0, 14, 14))!!
        assertEquals(2.58, p.mmolPerUnit, 0.01)
        assertTrue(!p.clamped && !p.tddIncomplete)
    }

    @Test
    fun totalDailyDoseCountsBasalAndBolusOverTheDAYSOBSERVED() {
        // The defect this pins: `basal_events` on this device starts much later
        // than the boluses go back to, and dividing by a nominal 14 days
        // understated TDD ~2x for every earlier window. TDD is in the
        // DENOMINATOR of the prior, so understating it overstates sensitivity.
        val now = 100L * dayMs
        val bol = (0 until 14).map { BolusPoint(now - it * dayMs, 19.0) }
        val bas = (0 until 14).map { BolusPoint(now - it * dayMs, 20.0) }
        assertEquals(39.0, totalDailyDose(bol, bas, now, 14)!!.unitsPerDay, 0.5)
        // Three days of history must report THREE days of rate, not 3/14 of it.
        val young = (0 until 3).map { BolusPoint(now - it * dayMs, 19.0) }
        val youngBas = (0 until 3).map { BolusPoint(now - it * dayMs, 20.0) }
        val t = totalDailyDose(young, youngBas, now, 14)!!
        assertEquals(3.0, t.daysCovered, 0.5)
        assertEquals(39.0, t.unitsPerDay, 3.0)
    }

    @Test
    fun aTddWithoutBasalIsRefusedNotHalved() {
        // Half a TDD doubles the prior straight into the clamp. Refusing is the
        // only honest answer: the caller then keeps the learned shape unscaled.
        val now = 100L * dayMs
        val bol = (0 until 14).map { BolusPoint(now - it * dayMs, 19.0) }
        val t = totalDailyDose(bol, emptyList(), now, 14)!!
        assertTrue(t.basalSparse)
        assertNull("no basal record => no prior", isfPriorFromTdd(t))
    }

    @Test
    fun withNoTrustedCorrectionsTheAnswerIsThePopulationPrior() {
        // A NEW USER has no history and must still get a usable amplitude on day
        // one. The old form returned the corpus plateau here — 1.48 for this
        // patient, against four other sources saying 2.1-2.7.
        val a = estimateCurrentIsfAmplitude(biasedCorpus, eraStart, prior)
        assertEquals(2.58, a.mmolPerUnit, 1e-9)
        assertEquals(0, a.freshEpisodes)
    }

    @Test
    fun theUnconfirmedCorpusCannotMoveTheAmplitudeAtAll() {
        // THE INVARIANT THIS SIGNATURE EXISTS FOR: same trusted episodes, wildly
        // different pre-era corpus, identical answer. It used to be 70% of it.
        val trusted = (100L until 103L).map { ampEp(it, 2.2) }
        val weak = estimateCurrentIsfAmplitude((0L until 20L).map { ampEp(it, 0.4) } + trusted, eraStart, prior)
        val strong = estimateCurrentIsfAmplitude((0L until 20L).map { ampEp(it, 9.0) } + trusted, eraStart, prior)
        assertEquals(weak.mmolPerUnit, strong.mmolPerUnit, 1e-12)
    }

    @Test
    fun theEraCutIsHardNotADecay() {
        // One millisecond before the era buys no vote at all. Muting the blind
        // era instead of excluding it is what left the old estimate at 1.48.
        val eps = biasedCorpus + listOf(ampEp(99, 9.0), ampEp(100, 2.2))
        assertEquals(1, estimateCurrentIsfAmplitude(eps, eraStart, prior).freshEpisodes)
    }

    @Test
    fun theDoseWeightIsInverseVariance() {
        // ISF = drop/dose, so a sensor error on the drop becomes error/dose on
        // the ISF: precision scales with dose, weight with dose squared.
        assertEquals(1.0, isfEpisodeWeight(ampEp(100, 2.0, dose = 2.5)), 1e-9)
        assertEquals(0.16, isfEpisodeWeight(ampEp(100, 2.0, dose = 1.0)), 1e-9)
        assertEquals(0.64, isfEpisodeWeight(ampEp(100, 2.0, dose = 2.0)), 1e-9)
        // Capped: one large shot must not dominate the set.
        assertEquals(1.0, isfEpisodeWeight(ampEp(100, 2.0, dose = 10.0)), 1e-9)
    }

    @Test
    fun activityStillCostsMostOfTheVoteButNeverAllOfIt() {
        val clean = isfEpisodeWeight(ampEp(100, 2.0, dose = 2.5))
        val inBout = isfEpisodeWeight(ampEp(100, 2.0, dose = 2.5, inBout = true))
        assertEquals(ACTIVITY_IN_BOUT_WEIGHT * clean, inBout, 1e-9)
        assertTrue("never zero", inBout > 0.0)
        assertTrue("never full", inBout < clean)
    }

    @Test
    fun theTrimmedMeanDoesNotJumpToTheHeavyTail() {
        // THE DEFECT THIS REPLACED, with the real numbers. Six episodes at 0.3
        // and two at 1.0: the six never reach half the weight, so a weighted
        // MEDIAN lands on the seventh value — the 87th percentile, 3.37, against
        // an unweighted median of 2.21. The trimmed mean must stay in the body.
        val pairs = listOf(
            1.47 to 0.3, 1.69 to 0.3, 1.90 to 0.3,
            2.06 to 0.3, 2.36 to 0.3, 2.38 to 0.3,
            3.37 to 1.0, 3.77 to 1.0,
        )
        val centre = weightedTrimmedMean(pairs)
        assertTrue("centre $centre must not reach the heavy tail", centre < 3.0)
        assertTrue("centre $centre must not collapse below the body", centre > 2.0)
        // For the record, because it is the reason this function exists: a
        // WEIGHTED MEDIAN on this same set returns 3.37 — the seventh of eight
        // values — because six weights of 0.3 never reach half of 3.8.
    }

    @Test
    fun theTrimIsWhatSurvivesAnOutlier() {
        // Where the trim earns its place: one absurd value among ordinary ones.
        // A plain weighted mean follows it; the trimmed mean does not. (On the
        // heavy-tail set above the trim moves the answer UP, not down — trimming
        // by WEIGHT removes more of the light low values than of the heavy high
        // ones. That is why the dose rule, which flattens the weights, is the
        // load-bearing half and the trim is the guard against a freak episode.)
        val high = listOf(2.0 to 1.0, 2.1 to 1.0, 2.2 to 1.0, 2.3 to 1.0, 9.0 to 1.0)
        assertTrue("plain mean must be dragged up", weightedTrimmedMean(high, trim = 0.0) > 3.0)
        assertTrue("trimmed must not be", weightedTrimmedMean(high) < 2.6)
        // BOTH ends, or half the guard can be deleted unnoticed: a mutation that
        // zeroed only the lower bound survived the high-outlier case alone.
        val low = listOf(0.2 to 1.0, 2.0 to 1.0, 2.1 to 1.0, 2.2 to 1.0, 2.3 to 1.0)
        assertTrue("plain mean must be dragged down", weightedTrimmedMean(low, trim = 0.0) < 1.9)
        assertTrue("trimmed must not be", weightedTrimmedMean(low) > 1.95)
    }

    @Test
    fun theBlendIsPriorAndTrustedInProportionToEffectiveWeight() {
        // Pins the arithmetic itself: two clean 2.5 U episodes are effective
        // weight 2.0, i.e. 20% of fullWeightEpisodes, so the answer is 80%
        // prior and 20% of where the trusted corrections centre.
        val eps = biasedCorpus + listOf(ampEp(100, 2.0, dose = 2.5), ampEp(101, 2.0, dose = 2.5))
        val a = estimateCurrentIsfAmplitude(eps, eraStart, prior)
        assertEquals(2.0, a.effectiveTrusted, 1e-9)
        assertEquals(0.2, a.blend, 1e-9)
        assertEquals(2.0, a.trustedCentre, 1e-9)
        assertEquals(2.58 * 0.8 + 2.0 * 0.2, a.mmolPerUnit, 1e-9)
    }

    @Test
    fun theDisqualifyingWindowMustCoverTheMeasurementWindow() {
        // The gap that shipped for months: events were checked over -90..+150
        // while bgEnd was the median over +120..+180, so a bolus or meal at +160
        // could move the measurement without ever being allowed to reject the
        // episode. Disqualification must cover measurement, on every config.
        listOf(KernelGates.PRODUCTION, KernelGates.PRODUCTION_FAST).forEach { cfg ->
            assertTrue(
                "bolus window ${cfg.bolusWindowMin} must cover end ${cfg.endWindowMin}",
                cfg.bolusWindowMin.endInclusive >= cfg.endWindowMin.endInclusive,
            )
            assertTrue(
                "carb window ${cfg.carbWindowMin} must cover end ${cfg.endWindowMin}",
                cfg.carbWindowMin.endInclusive >= cfg.endWindowMin.endInclusive,
            )
            // EQUAL, not merely "at least". Watching for disqualifying events
            // beyond the measured interval rejects episodes for something that
            // cannot have touched the measurement — that is how a hypo at +162
            // killed a confirmed correction measured over +120..+150.
            assertEquals(
                "observation must end exactly where the measurement does",
                cfg.endWindowMin.endInclusive, cfg.obsWindowMin, 1e-9,
            )
        }
    }

    @Test
    fun theAnswerDoesNotRestOnTheTwoHeaviestEpisodes() {
        // The robustness check the rule has to survive: drop the two heaviest
        // trusted episodes and the amplitude must barely move. If it does move,
        // the rule is being carried by whichever pair escaped every flag.
        val trusted = listOf(
            ampEp(100, 1.83, dose = 3.5), ampEp(101, 2.12, dose = 3.0),
            ampEp(102, 1.86, dose = 3.0), ampEp(103, 2.22, dose = 2.0),
            ampEp(104, 2.55, dose = 2.0), ampEp(105, 4.55, dose = 1.0),
        )
        val all = estimateCurrentIsfAmplitude(biasedCorpus + trusted, eraStart, prior)
        val heaviest = trusted.sortedByDescending { isfEpisodeWeight(it) }.take(2)
        val without = biasedCorpus + trusted.filterNot { e -> heaviest.any { it.t0Ms == e.t0Ms } }
        val cut = estimateCurrentIsfAmplitude(without, eraStart, prior)
        assertEquals(all.mmolPerUnit, cut.mmolPerUnit, 0.15)
    }

    @Test
    fun onlyPhysicalImpossibilityExcludesAnEpisode() {
        // Deviating from the current mean must NOT exclude: that rule is
        // circular and would bury the corrections that exposed the problem.
        val eps = biasedCorpus + listOf(
            ampEp(100, 2.2),
            IsfEpisode(101 * dayMs, 2.0, 10.0, 12.0, -1.0, true, TodBucket.DAY),
            ampEp(102, 2.2),
        )
        assertEquals(2, estimateCurrentIsfAmplitude(eps, eraStart, prior).freshEpisodes)
    }
}
