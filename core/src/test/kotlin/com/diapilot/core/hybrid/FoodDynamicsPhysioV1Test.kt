package com.diapilot.core.hybrid

import com.diapilot.core.physio.*
import com.diapilot.core.analysis.FoodKineticFeaturesV2
import com.diapilot.core.analysis.FoodPhysicalFormV2
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FoodDynamicsPhysioV1Test {
    private val model by lazy {
        HybridBlindDayParityTest().model(JSONObject(checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json")).bufferedReader().readText()))
    }
    @Test fun `feature mixture ignores title and fat extends observable tail`() {
        val engine=HybridForecastEngine(model.copy(food=model.food.copy(globalFactor=.165)))
        val k=FoodKineticFeaturesV2(.25,.50,.25,FoodPhysicalFormV2.MIXED,6.0,.7,"test")
        val a=HybridFoodEvent(0,40.0,"juice ice cream",kineticFeatures=k,proteinG=5.0,fatG=2.0)
        val b=a.copy(text="arbitrary words")
        assertEquals(engine.foodTiming(a),engine.foodTiming(b))
        val fatty=a.copy(proteinG=25.0,fatG=35.0)
        assertTrue(engine.foodTiming(fatty).plateauMin>engine.foodTiming(a).plateauMin)
    }

    @Test fun `small slow fraction does not rename trace tail as main plateau`() {
        val engine=HybridForecastEngine(model.copy(food=model.food.copy(globalFactor=.165)))
        val k=FoodKineticFeaturesV2(.40,.55,.05,FoodPhysicalFormV2.MIXED,3.0,.8,"test")
        val timing=engine.foodTiming(HybridFoodEvent(0,45.0,"arbitrary",kineticFeatures=k,proteinG=18.0,fatG=20.0))
        assertTrue("main response must finish well before trace tail",timing.plateauMin+60<timing.tailEndMin)
        assertTrue("main response should remain a useful meal landmark",timing.plateauMin<240)
        assertTrue(timing.tailEndMin>300)
    }

    @Test fun `completed fast intake is locked before later complex meal`() {
        val engine=HybridForecastEngine(model)
        val fastK=FoodKineticFeaturesV2(.95,.05,0.0,FoodPhysicalFormV2.LIQUID,0.0,.9,"test")
        val slowK=FoodKineticFeaturesV2(.10,.55,.35,FoodPhysicalFormV2.MIXED,8.0,.8,"test")
        val smoothie=HybridFoodEvent(0,22.0,"ignored",kineticFeatures=fastK)
        val breakfast=HybridFoodEvent(75*MINUTE_MS,45.0,"ignored",kineticFeatures=slowK,proteinG=30.0,fatG=35.0)
        val standalone=engine.foodCdf(smoothie,180.0)
        val clustered=engine.clusteredFoodCdf(smoothie,listOf(smoothie,breakfast),180.0)
        assertEquals(standalone,clustered,1e-9)
        val earlyAssessment=engine.mealClusterAssessment(smoothie,listOf(smoothie,breakfast))
        val laterAssessment=engine.mealClusterAssessment(breakfast,listOf(smoothie,breakfast))
        assertTrue(earlyAssessment.realisedFractionAtNext!!>=.90)
        assertEquals(1,earlyAssessment.memberIds.size)
        assertEquals(1,laterAssessment.memberIds.size)
    }

    @Test fun `later macros slow only unrealised remainder and prior fat slows dessert`() {
        val engine=HybridForecastEngine(model)
        val fastK=FoodKineticFeaturesV2(.85,.15,0.0,FoodPhysicalFormV2.LIQUID,0.0,.9,"test")
        val complexK=FoodKineticFeaturesV2(.10,.55,.35,FoodPhysicalFormV2.MIXED,8.0,.8,"test")
        val early=HybridFoodEvent(0,25.0,"ignored",kineticFeatures=fastK)
        val fatty=HybridFoodEvent(20*MINUTE_MS,45.0,"ignored",kineticFeatures=complexK,proteinG=35.0,fatG=40.0)
        assertTrue(engine.clusteredFoodCdf(early,listOf(early,fatty),120.0)<engine.foodCdf(early,120.0))
        val dessert=HybridFoodEvent(60*MINUTE_MS,20.0,"ignored",kineticFeatures=fastK)
        assertTrue(engine.clusteredFoodCdf(dessert,listOf(fatty,dessert),60.0)<engine.foodCdf(dessert,60.0))
        assertEquals(engine.foodAmplitude(early).first,engine.foodAmplitude(early.copy(text="anything" )).first,0.0)
    }

    @Test fun `dextrose rescue is short and never stretched by a meal cluster`() {
        val engine=HybridForecastEngine(model)
        val rescue=HybridFoodEvent(0,5.0,"ignored",rescueTreatment=true)
        val fatty=HybridFoodEvent(5*MINUTE_MS,70.0,"ignored",proteinG=20.0,fatG=35.0)
        val timing=engine.foodTiming(rescue)
        assertEquals(10.0,timing.onsetMin,.25)
        assertTrue(timing.plateauMin in 24.0..29.0)
        assertEquals(30.0,timing.tailEndMin,.25)
        assertEquals(engine.foodCdf(rescue,25.0),engine.clusteredFoodCdf(rescue,listOf(rescue,fatty),25.0),1e-12)
    }

    /** A QUEUE MAY NOT DELIVER FASTER THAN IT POURS.
     *
     * The bound used to be the literal 126 min with the comment «90% of 70 g at
     * 30 g/h» — that is, it was the CAP's number, not a physiological one, and
     * it silently stopped meaning anything when the cap became derived. Stated
     * against the rate instead, it holds at any setting and still catches the
     * failure it was written for: 70 g arriving essentially at once.
     */
    @Test fun `seventy grams cannot arrive faster than the queue pours them`() {
        val engine=HybridForecastEngine(model)
        val fastK=FoodKineticFeaturesV2(.9,.1,0.0,FoodPhysicalFormV2.LIQUID,0.0,.9,"test")
        val meal=HybridFoodEvent(0,70.0,"ignored",kineticFeatures=fastK)
        val rate=caloricCarbRateGPerHourV1(70.0, 0.0, 0.0)
        val floor=0.9 * 70.0 / rate * 60.0
        assertTrue(
            "90% arrived before the queue could pour it: ${engine.foodTiming(meal).plateauMin} < $floor",
            engine.foodTiming(meal).plateauMin >= floor,
        )
        assertTrue(engine.foodTiming(meal).tailEndMin >= floor)
    }

    @Test fun `batched throughput timeline is identical to repeated aligned snapshots`() {
        val members=listOf(
            CarbAppearanceMemberV1("a",0,45.0),
            CarbAppearanceMemberV1("b",30*MINUTE_MS,35.0),
        )
        val targets=(15..240 step 15).map{it*MINUTE_MS}
        fun desired(m:CarbAppearanceMemberV1,at:Long):Double=
            (((at-m.startMs)/MINUTE_MS.toDouble())/75.0).coerceIn(0.0,1.0)
        val batch=throughputLimitedFractionTimelineV1(members,targets,::desired)
        targets.forEach{target->
            val repeated=throughputLimitedFractionsV1(members,target,::desired)
            members.forEach{m->assertEquals(repeated.getValue(m.id),batch.getValue(target).getValue(m.id),1e-12)}
        }
    }
}
