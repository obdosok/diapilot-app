package com.diapilot.core.hybrid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBlindDayParityTest {
    private val tolerance = 1e-9
    private fun verifyBlindDay(resource: String) {
        val root = JSONObject(
            checkNotNull(javaClass.getResourceAsStream(resource))
                .bufferedReader(Charsets.UTF_8)
                .readText(),
        )
        assertTrue(root.getJSONObject("protocol").getBoolean("no_cgm_reanchor"))
        assertEquals("test_frozen", root.getJSONObject("protocol").getString("split"))
        val model = model(root.getJSONObject("model"))
        val input = root.getJSONObject("input")
        val anchor = input.getJSONObject("anchor")
        val state = HybridForecastState(
            nowMs = anchor.getLong("ts_ms"),
            glucoseHistory = listOf(
                HybridGlucosePoint(
                    anchor.getLong("ts_ms"),
                    anchor.getDouble("mmol"),
                ),
            ),
            foodHistory = input.getJSONArray("food_history").objects().map { o ->
                val components = o.getJSONObject("components")
                HybridFoodEvent(
                    tsMs = o.getLong("ts_ms"),
                    carbsG = o.getDouble("carbs_g"),
                    text = o.getString("text"),
                    components = components.keys().asSequence()
                        .associateWith(components::getDouble),
                )
            },
            bolusHistory = input.getJSONArray("bolus_history").objects().map { o ->
                HybridBolusEvent(o.getLong("ts_ms"), o.getDouble("units"))
            },
            basalHistory = input.getJSONArray("basal_history").objects().map { o ->
                HybridBasalEvent(o.getLong("ts_ms"), o.getDouble("units"))
            },
        )
        val contexts = input.getJSONArray("contexts").objects().map { o ->
            HybridOpenLoopContext(
                tsMs = o.getLong("ts_ms"),
                activityExposure = o.getDouble("activity_exposure"),
                asleep = o.getDouble("asleep"),
                sleepDebtHours = o.getDouble("sleep_debt_hours"),
                hoursSinceWake = o.getDouble("hours_since_wake"),
            )
        }
        val engine = HybridForecastEngine(model)
        val actualPoints = engine.openLoop(state, contexts)
        val expectedPoints = root.getJSONObject("expected")
            .getJSONArray("points").objects()
        assertEquals(expectedPoints.size, actualPoints.size)
        actualPoints.zip(expectedPoints).forEach { (actual, expected) ->
            assertEquals(expected.getLong("ts_ms"), actual.tsMs)
            // FOOD PARITY ENDED WITH THE V11 FOOD MODEL.
            //
            // The Python reference these fixtures were frozen against
            // implements the dish dictionary — per-group factors and shapes,
            // component factors, whole-dish profiles — which this engine no
            // longer has. So `food_delta` (and `mmol`, which contains it) can
            // no longer agree, and re-freezing them would turn a
            // CROSS-IMPLEMENTATION check into the engine comparing itself.
            //
            // The insulin and background terms are untouched by that deletion,
            // and they are still compared against Python byte for byte. That is
            // the part of the guard that still means something.
            assertEquals(expected.getDouble("insulin_delta"), actual.insulinDelta, tolerance)
            assertEquals(
                expected.getDouble("background_delta"),
                actual.backgroundDelta,
                tolerance,
            )
        }

        val observed = root.getJSONArray("actual").objects().map { o ->
            HybridGlucosePoint(o.getLong("ts_ms"), o.getDouble("mmol"))
        }
        val score = engine.scoreOpenLoop(actualPoints, observed)
        val expectedScore = root.getJSONObject("expected").getJSONObject("score")
        assertEquals(expectedScore.getInt("n"), score.n)
        assertEquals(expectedScore.getDouble("mae"), score.mae!!, tolerance)
        assertEquals(expectedScore.getDouble("rmse"), score.rmse!!, tolerance)
        assertEquals(expectedScore.getDouble("bias"), score.bias!!, tolerance)
        assertEquals(expectedScore.getDouble("end_error"), score.endError!!, tolerance)
    }

    internal fun model(root: JSONObject): HybridPersonModel {
        val runtime = root.getJSONObject("runtime")
        val insulin = root.getJSONObject("insulin")
        val food = root.getJSONObject("food")
        val activity = root.getJSONObject("activity")
        val basal = root.getJSONObject("basal")
        val joint = root.getJSONObject("joint")
        val c = joint.getJSONObject("coefficients")
        val trend = root.getJSONObject("trend")
        val weights = trend.getJSONObject("horizon_weights")
        val uncertainty = root.getJSONObject("uncertainty")
        return HybridPersonModel(
            schemaVersion = root.getInt("schema_version"),
            modelVersion = root.getString("model_version"),
            personModelId = root.getString("person_model_id"),
            runtime = HybridRuntimeParams(
                runtime.getInt("horizon_min"),
                runtime.getInt("step_min"),
            ),
            insulin = HybridInsulinParams(
                isf = insulin.getDouble("isf"),
                isfLow = insulin.getDouble("isf_low"),
                isfHigh = insulin.getDouble("isf_high"),
                onsetMin = insulin.getDouble("onset_min"),
                peakMin = insulin.getDouble("peak_min"),
                shortDurationMin = insulin.getDouble("short_duration_min"),
                tailDurationMin = insulin.getDouble("tail_duration_min"),
                tailWeight = insulin.getDouble("tail_weight"),
                tailWeightPerUnit = insulin.getDouble("tail_weight_per_unit"),
                tailReferenceUnits = insulin.getDouble("tail_reference_units"),
            ),
            food = HybridFoodParams(
                globalFactor = food.getDouble("global_factor"),
                calibration = food.getDouble("calibration"),
                defaultShape = shape(food.getJSONObject("default_shape")),
            ),
            activity = HybridActivityParams(
                activity.getDouble("iob_gamma"),
                activity.getDouble("tau_min"),
                activity.getDouble("food_gamma"),
                activity.getDouble("food_tau_min"),
            ),
            basal = HybridBasalParams(
                basal.getDouble("onset_min"),
                basal.getDouble("peak_min"),
                basal.getDouble("duration_min"),
                basal.getDouble("reference_units_24h"),
                basal.getDouble("sensitivity_mmol_per_action_unit"),
                basal.getDouble("scale"),
            ),
            joint = HybridJointParams(
                coefficients = HybridJointCoefficients(
                    intercept = c.getDouble("intercept"),
                    sin24 = c.getDouble("sin24"),
                    cos24 = c.getDouble("cos24"),
                    sin12 = c.getDouble("sin12"),
                    cos12 = c.getDouble("cos12"),
                    stateReversion = c.getDouble("state_reversion"),
                    activityDirect = c.getDouble("activity_direct"),
                    sleep = c.getDouble("sleep"),
                    sleepDebt = c.getDouble("sleep_debt"),
                    hoursSinceWake = c.getDouble("hours_since_wake"),
                ),
                targetGlucose = joint.getDouble("target_glucose"),
                backgroundScale = joint.getDouble("background_scale"),
            ),
            trend = HybridTrendParams(
                windowMin = trend.getInt("window_min"),
                tauMin = trend.getDouble("tau_min"),
                weight60 = weights.getDouble("60"),
                weight120 = weights.getDouble("120"),
                weight180 = weights.getDouble("180"),
            ),
            uncertainty = HybridUncertaintyParams(
                uncertainty.getDouble("sigma_per_sqrt_hour"),
                uncertainty.getDouble("food_fraction"),
                uncertainty.getDouble("unknown_food_extra_fraction"),
            ),
        )
    }

    private fun shape(o: JSONObject) = HybridShape(
        o.getDouble("delay_min"),
        o.getDouble("peak_min"),
        o.getDouble("duration_min"),
    )

    private fun tail(o: JSONObject) = HybridTail(
        o.getDouble("gain"),
        o.getDouble("onset_min"),
        o.getDouble("peak_min"),
        o.getDouble("duration_min"),
    )

    private fun doubles(o: JSONObject) =
        o.keys().asSequence().associateWith(o::getDouble)

    private fun shapes(o: JSONObject) =
        o.keys().asSequence().associateWith { shape(o.getJSONObject(it)) }

    private fun tails(o: JSONObject) =
        o.keys().asSequence().associateWith { tail(o.getJSONObject(it)) }

    private fun prototypes(a: JSONArray) = (0 until a.length()).map { index ->
        val o = a.getJSONObject(index)
        val tokens = o.getJSONArray("tokens")
        HybridFoodPrototype(
            o.getString("group"),
            (0 until tokens.length()).map(tokens::getString).toSet(),
        )
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map(::getJSONObject)
}
