package com.diapilot.core.hybrid

import com.diapilot.core.physio.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-language contract driven by the promoted Python artifact itself. */
class HybridForecastEngineContractTest {
    private val tolerance = 1e-9

    @Test fun `openLoopUntil is contiguous and equals manual five minute stepping`() {
        val model=promotedModel();val engine=HybridForecastEngine(model);val start=1_700_000_000_000L;val step=model.runtime.stepMin*MINUTE_MS
        val initial=HybridForecastState(start,listOf(HybridGlucosePoint(start,8.0)),
            foodHistory=listOf(HybridFoodEvent(start+10*MINUTE_MS,35.0,"meal")),
            bolusHistory=listOf(HybridBolusEvent(start+20*MINUTE_MS,2.0)),
            basalHistory=listOf(HybridBasalEvent(start-60*MINUTE_MS,8.0)))
        fun context(t:Long)=HybridOpenLoopContext(t,activityExposure=1.4*kotlin.math.exp(-(t-start)/MINUTE_MS/120.0),asleep=if(t<start+30*MINUTE_MS)1.0 else 0.0,sleepDebtHours=1.5,hoursSinceWake=(t-start)/3_600_000.0)
        val end=start+3*60*MINUTE_MS
        val actual=engine.openLoopUntil(initial,end,::context)
        val manual=buildList {
            var simulated=initial.glucoseHistory.last().mmol;var t=start
            while(t+step<=end){
                val one=engine.openLoop(initial.copy(nowMs=t,glucoseHistory=listOf(HybridGlucosePoint(t,simulated))),listOf(context(t))).single()
                add(one);simulated=one.mmol;t+=step
            }
        }
        assertEquals(36,actual.size);actual.zip(manual).forEach{(a,b)->assertEquals(b,a)}
        assertEquals(start+step,actual.first().tsMs);assertEquals(end,actual.last().tsMs)
        assertEquals(8.0+actual.sumOf{it.foodDelta+it.insulinDelta+it.backgroundDelta},actual.last().mmol,1e-9)
        val sparse=engine.openLoop(initial,(start until end step 15*MINUTE_MS).map(::context))
        assertNotEquals(actual.map{it.tsMs},sparse.map{it.tsMs})
        assertNotEquals(actual.last().mmol,sparse.last().mmol,1e-6)
    }

    @Test
    fun `exported deconvolution kernel has the exact declared onset peak and plateau`() {
        val model=promotedModel();val p=model.insulin;val engine=HybridForecastEngine(model)
        val kernel=engine.insulinKernelPoints(2.5)
        assertTrue(kernel.filter{it.tauMin<=p.onsetMin}.all{kotlin.math.abs(it.median)<1e-12})
        val fastest=kernel.zipWithNext().maxBy{(a,b)->kotlin.math.abs(b.median-a.median)}
        val ratePeak=(fastest.first.tauMin+fastest.second.tauMin)/2.0
        assertTrue("rate peak $ratePeak vs declared ${p.peakMin}",kotlin.math.abs(ratePeak-p.peakMin)<=7.5)
        assertEquals(-p.isf,kernel.last().median,1e-9)
        listOf(0.0,p.onsetMin,p.peakMin,p.tailDurationMin).forEach{minute->
            val k=kernel.last{it.tauMin<=minute}.median
            assertEquals(-p.isf*engine.insulinCdf(minute,2.5),k,1e-9)
        }
    }
    @Test
    fun `v11 IOB readout uses the forecast insulin kernel`() {
        val model = promotedModel()
        val engine = HybridForecastEngine(model)

        assertEquals(1.0, engine.insulinRemainingFraction(0.0, 2.0), tolerance)
        assertTrue(
            engine.insulinRemainingFraction(model.insulin.peakMin, 2.0) in 0.0..1.0,
        )
        assertEquals(
            0.0,
            engine.insulinRemainingFraction(model.insulin.tailDurationMin, 2.0),
            tolerance,
        )
        assertTrue(
            engine.insulinRemainingFraction(40.0, 2.0) >
                engine.insulinRemainingFraction(70.0, 2.0),
        )
    }

    @Test
    fun `empirical insulin CDF is shared by forecast and IOB`() {
        val model = promotedModel().let { base ->
            base.copy(insulin = base.insulin.copy(actionCdfKnots = listOf(
                HybridCdfKnot(0.0, 0.0),
                HybridCdfKnot(20.0, 0.0),
                HybridCdfKnot(40.0, 0.5),
                HybridCdfKnot(100.0, 1.0),
            )))
        }
        val engine = HybridForecastEngine(model)
        assertEquals(0.25, engine.insulinCdf(30.0), tolerance)
        assertEquals(0.75, engine.insulinRemainingFraction(30.0), tolerance)
        assertEquals(0.0, engine.insulinRemainingFraction(100.0), tolerance)
    }

    @Test
    fun `non-monotone empirical insulin CDF fails closed`() {
        val result = runCatching {
            promotedModel().let { base ->
                base.copy(insulin = base.insulin.copy(actionCdfKnots = listOf(
                    HybridCdfKnot(0.0, 0.0),
                    HybridCdfKnot(20.0, 0.7),
                    HybridCdfKnot(20.0, 1.0),
                )))
            }
        }
        assertTrue(result.isFailure)
    }

    /**
     * ONE ENGINE, TWO DURATIONS \u2014 the memo must not confuse them.
     *
     * `clusterMassCache` keyed on timestamp, grams and calories but not on
     * `durationMin`, which the supply curve reads. Two meals identical but for
     * how long they were eaten collided and the first computed answered for
     * both. Deliberately asks the SAME engine for both, because a fresh engine
     * per call cannot see the bug.
     */
    @Test
    fun `the cluster memo does not confuse two meals of different duration`() {
        val engine = HybridForecastEngine(promotedModel())
        val now = 1_000_000L
        fun ev(duration: Double) = HybridFoodEvent(now, 30.0, "beer", durationMin = duration)
        val instant = engine.clusteredFoodCdf(ev(0.0), listOf(ev(0.0)), 90.0)
        val spread = engine.clusteredFoodCdf(ev(60.0), listOf(ev(60.0)), 90.0)
        assertTrue("spread $spread must trail instant $instant", spread < instant - 0.05)
    }

    @Test
    fun `meal duration spreads absorption without changing the food identity`() {
        val engine = HybridForecastEngine(promotedModel())
        val now = 1_000_000L
        fun foodAt(duration: Double) = engine.forecast(
            HybridForecastState(
                nowMs = now,
                glucoseHistory = listOf(HybridGlucosePoint(now, 7.0)),
                foodHistory = listOf(
                    HybridFoodEvent(
                        tsMs = now,
                        carbsG = 30.0,
                        text = "beer",
                        durationMin = duration,
                    ),
                ),
            ),
        ).points.single { it.minutes == 60 }.foodDelta

        assertTrue(foodAt(60.0) < foodAt(0.0))
    }

    /**
     * SUPERSEDED CLAIM, REWRITTEN RATHER THAN DELETED.
     *
     * This used to assert that macros widen the interval WITHOUT touching the
     * median — correct while no macro effect was promoted. Two now are: the
     * caloric queue (fat occupies the pipe the carbohydrate must pass) and the
     * fitted fat->peak slope. So fat moving the median is the model working,
     * and the test's job changes from «the median must not move» to «it must
     * move the right way, and the interval must still widen».
     */
    @Test
    fun `known macros delay the physio median and widen its interval`() {
        val base=coercedIntoArtifactDomain(promotedModel())
        val artifact=PhysioArtifactV1("t",base,
            PosteriorV1(base.food.globalFactor,base.food.globalFactor*.9,base.food.globalFactor*1.1,0,0,null),
            PosteriorV1(base.insulin.isf,base.insulin.isf*.9,base.insulin.isf*1.1,0,0,null),
            variance=VarianceLedgerV1(.1,.1,.1,.1,.1,.1))
        val now=10_000_000L
        fun result(protein:Double?,fat:Double?)=PhysioForecastEngine(artifact).forecast(HybridForecastState(now,listOf(HybridGlucosePoint(now,6.0)),foodHistory=listOf(HybridFoodEvent(now,40.0,"same",proteinG=protein,fatG=fat))))
        val unknown=result(null,null);val high=result(60.0,60.0)
        assertEquals(unknown.points.map{it.tsMs},high.points.map{it.tsMs})
        // The interval widens RELATIVE TO THE FOOD IT IS UNCERTAIN ABOUT.
        //
        // The raw width was compared here and it was the wrong
        // quantity: every variance channel scales with |foodDelta|, and 60 g of
        // fat holds the delivery back, so at 180 min the fatty meal has a
        // SMALLER delta (3.56 vs 5.65) and therefore a narrower absolute band
        // while being the less certain of the two. `macroTimingUncertaintyExtra`
        // is a FRACTION (0.08 unknown against 0.16 here), so the fraction is
        // what the test must read.
        fun relative(r: HybridForecastResult): Double {
            val p = r.points.last()
            return (p.high - p.low) / kotlin.math.abs(p.foodDelta).coerceAtLeast(1e-9)
        }
        assertTrue(
            "known macros did not widen the relative interval: ${relative(high)} vs ${relative(unknown)}",
            relative(high) > relative(unknown),
        )
        // ...and 60 g of fat now genuinely holds the rise back. Scored at the
        // halfway point of the horizon, where a delay shows as a LOWER median;
        // at the end of a long horizon both have arrived and the two agree.
        val mid=high.points.size/2
        assertTrue(
            "60 g of fat did not delay the rise: ${high.points[mid].scenario} vs ${unknown.points[mid].scenario}",
            high.points[mid].scenario < unknown.points[mid].scenario,
        )
    }

    @Test
    fun `horizon trust cannot reapply old insulin and create a second fall`() {
        val base = promotedModel()
        val model = base.copy(
            activity = base.activity.copy(iobGamma = 0.0, foodGamma = 0.0),
            basal = base.basal.copy(scale = 0.0),
            joint = base.joint.copy(
                coefficients = HybridJointCoefficients(),
                backgroundScale = 0.0,
            ),
            trend = base.trend.copy(
                weight60 = 0.50,
                weight120 = 0.75,
                weight180 = 0.75,
            ),
        )
        val now = 10_000_000L
        val result = HybridForecastEngine(model).forecast(
            HybridForecastState(
                nowMs = now,
                glucoseHistory = listOf(HybridGlucosePoint(now, 13.8)),
                bolusHistory = listOf(
                    HybridBolusEvent(now - 45L * MINUTE_MS, 4.5),
                ),
            ),
        )
        fun value(minute: Int) = result.points.single { it.minutes == minute }.baseline
        val drop30To60 = value(30) - value(60)
        val drop60To90 = value(60) - value(90)

        assertTrue(drop30To60 > 0.0)
        assertTrue(drop60To90 > 0.0)
        assertTrue(drop60To90 <= drop30To60)
    }

    @Test
    fun `what if bolus is identical to persisting the same bolus`() {
        val engine = HybridForecastEngine(promotedModel())
        val now = 10_000_000L
        val state = HybridForecastState(
            nowMs = now,
            glucoseHistory = listOf(HybridGlucosePoint(now, 9.5)),
            activityExposure = 0.25,
        )
        val preview = engine.forecast(state, hypotheticalInsulinUnits = 2.5)
        val persisted = engine.forecast(
            state.copy(bolusHistory = listOf(HybridBolusEvent(now, 2.5))),
        )
        preview.points.zip(persisted.points).forEach { (whatIf, actual) ->
            assertEquals(actual.baseline, whatIf.scenario, tolerance)
            assertEquals(
                actual.insulinActualDelta,
                whatIf.insulinScenarioDelta,
                tolerance,
            )
        }
    }

    @Test
    fun `full causal intervention is not weakened by horizon trust`() {
        val model = promotedModel().copy(
            activity = promotedModel().activity.copy(iobGamma = 0.0),
        )
        val engine = HybridForecastEngine(model)
        val now = 10_000_000L
        val units = 2.5
        val state = HybridForecastState(
            nowMs = now,
            glucoseHistory = listOf(HybridGlucosePoint(now, 9.5)),
            bolusHistory = listOf(HybridBolusEvent(now, units)),
        )

        val full = engine.fullKnownInsulinDelta(state, 180.0)
        val intervention = engine.hypotheticalInsulinDelta(180.0, units)
        val weighted = engine.hypotheticalInsulinForecastDelta(180.0, units)

        assertEquals(intervention, full, tolerance)
        assertTrue(full < weighted)
        assertEquals(-units * model.insulin.isf, full, tolerance)
    }

    @Test
    fun `open loop observation age widens uncertainty`() {
        val engine = HybridForecastEngine(promotedModel())
        val now = 1_000_000L
        fun width(ageMin: Double) = engine.forecast(
            HybridForecastState(
                nowMs = now,
                glucoseHistory = listOf(HybridGlucosePoint(now, 7.0)),
                observationAgeMin = ageMin,
            ),
        ).points.single { it.minutes == 60 }.let { it.high - it.low }

        assertTrue(width(180.0) > width(0.0))
    }

    /**
     * THE DISH NAME MUST NOT REACH AMPLITUDE. This used to be a claim about one
     * arm ignoring the other arm's dictionary; the dictionary is gone, so the
     * test now pins the property directly: whatever the meal is CALLED, and
     * whatever components it declares, the amplitude is grams x carbohydrate
     * sensitivity and it says so in its own source label.
     *
     * Kept rather than deleted with the dictionary because the failure it
     * guards against is a FUTURE one — the next per-dish factor to be
     * introduced would land exactly here.
     */
    @Test
    fun `amplitude is grams times carb sensitivity whatever the dish is called`() {
        val base = promotedModel()
        val model = base.copy(food = base.food.copy(globalFactor = 0.2))
        val engine = HybridForecastEngine(model)
        val grams = 40.0
        val expected = grams * 0.2 * model.food.calibration
        listOf(
            HybridFoodEvent(0, grams, "unknown plain dish"),
            HybridFoodEvent(0, grams, "component dish", mapOf("x" to grams)),
            HybridFoodEvent(0, grams, "\u0441\u043c\u0443\u0437\u0438", mapOf("x" to grams)),
            HybridFoodEvent(0, grams, "entirely different name"),
        ).forEach {
            assertEquals(expected, engine.foodAmplitude(it).first, tolerance)
            assertEquals("physio_global_cs", engine.foodAmplitude(it).second)
        }
    }

    /** And the same for TIMING: two meals with identical grams, macros and
     *  duration must draw the identical curve however they are named. */
    @Test
    fun `timing ignores the dish name and the component map`() {
        val model = promotedModel()
        val now = 10_000_000L
        fun curve(event: HybridFoodEvent) = HybridForecastEngine(model)
            .forecast(
                HybridForecastState(now, listOf(HybridGlucosePoint(now, 6.0)), foodHistory = listOf(event)),
            ).points.map { it.foodDelta }
        assertEquals(
            curve(HybridFoodEvent(now, 40.0, "slow dish", mapOf("x" to 40.0), 30.0, 15.0, 15.0, "known")),
            curve(HybridFoodEvent(now, 40.0, "different", emptyMap(), 30.0, 15.0, 15.0, "known")),
        )
    }

    private fun promotedModel(): HybridPersonModel =
        HybridBlindDayParityTest().model(
            JSONObject(resource("/hybrid_runtime_model_v11.json")),
        )

    /**
     * The fixture as `PhysioRuntime.buildArtifact` would hand it over.
     *
     * Only the tests that construct a [PhysioArtifactV1] need this, and only
     * those: the artifact's constructor requires the mechanics to be inside
     * `PhysioBoundsV1`, production coerces them there first, and this helper
     * used to be skipped because the fixture's own end of action happened to
     * clear the old floor. The floor is now 240 (audit M1) and the fixture is a
     * golden file, so the coercion lives here. Every other test keeps the
     * fixture exactly as shipped.
     */
    private fun coercedIntoArtifactDomain(m: HybridPersonModel): HybridPersonModel =
        m.copy(
            insulin = m.insulin.copy(
                tailDurationMin = m.insulin.tailDurationMin
                    .coerceIn(com.diapilot.core.physio.PhysioBoundsV1().insulinTailMinRange),
            ),
        )

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream(name))
            .bufferedReader(Charsets.UTF_8)
            .readText()
}
