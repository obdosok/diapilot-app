package com.diapilot.core.hybrid

import org.json.JSONObject
import java.io.InputStream

/**
 * JSON boundary for the shipped person model.
 *
 * MOVED FROM `app`, unchanged. It never had an Android import; it
 * sat in `app` only because that is where the asset is packaged, and the cost
 * was that NOTHING outside the phone could construct the model the phone runs
 * — so the laptop stand could not reproduce a single Hybrid/Physio number.
 * One parser, two callers, no port to drift.
 */
object HybridPersonModelJson {
    fun read(input: InputStream): HybridPersonModel =
        fromJson(JSONObject(input.bufferedReader(Charsets.UTF_8).readText()))

    fun fromJson(root: JSONObject): HybridPersonModel {
        val runtime = root.getJSONObject("runtime")
        val insulin = root.getJSONObject("insulin")
        val food = root.getJSONObject("food")
        val progressive = food.optJSONObject("progressive_profiles")
        val activity = root.getJSONObject("activity")
        val basal = root.getJSONObject("basal")
        val joint = root.getJSONObject("joint")
        val coefficients = joint.getJSONObject("coefficients")
        val trend = root.getJSONObject("trend")
        val weights = trend.getJSONObject("horizon_weights")
        val uncertainty = root.getJSONObject("uncertainty")
        return HybridPersonModel(
            schemaVersion = root.getInt("schema_version"),
            modelVersion = root.getString("model_version"),
            personModelId = root.getString("person_model_id"),
            runtime = HybridRuntimeParams(
                horizonMin = runtime.getInt("horizon_min"),
                stepMin = runtime.getInt("step_min"),
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
                actionCdfKnots = insulin.optJSONArray("action_cdf_knots")
                    ?.let { knots ->
                        (0 until knots.length()).map { index ->
                            knots.getJSONArray(index).let { point ->
                                HybridCdfKnot(point.getDouble(0), point.getDouble(1))
                            }
                        }
                    }.orEmpty(),
            ),
            food = HybridFoodParams(
                globalFactor = food.getDouble("global_factor"),
                calibration = food.getDouble("calibration"),
                defaultShape = shape(food.getJSONObject("default_shape")),
            ),
            activity = HybridActivityParams(
                iobGamma = activity.getDouble("iob_gamma"),
                tauMin = activity.getDouble("tau_min"),
                foodGamma = activity.getDouble("food_gamma"),
                foodTauMin = activity.getDouble("food_tau_min"),
            ),
            basal = HybridBasalParams(
                onsetMin = basal.getDouble("onset_min"),
                peakMin = basal.getDouble("peak_min"),
                durationMin = basal.getDouble("duration_min"),
                referenceUnits24h = basal.getDouble("reference_units_24h"),
                sensitivityMmolPerActionUnit =
                    basal.getDouble("sensitivity_mmol_per_action_unit"),
                scale = basal.getDouble("scale"),
            ),
            joint = HybridJointParams(
                coefficients = HybridJointCoefficients(
                    intercept = coefficients.getDouble("intercept"),
                    sin24 = coefficients.getDouble("sin24"),
                    cos24 = coefficients.getDouble("cos24"),
                    sin12 = coefficients.getDouble("sin12"),
                    cos12 = coefficients.getDouble("cos12"),
                    stateReversion = coefficients.getDouble("state_reversion"),
                    activityDirect = coefficients.getDouble("activity_direct"),
                    sleep = coefficients.getDouble("sleep"),
                    sleepDebt = coefficients.getDouble("sleep_debt"),
                    hoursSinceWake = coefficients.getDouble("hours_since_wake"),
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
                sigmaPerSqrtHour = uncertainty.getDouble("sigma_per_sqrt_hour"),
                foodFraction = uncertainty.getDouble("food_fraction"),
                unknownFoodExtraFraction =
                    uncertainty.getDouble("unknown_food_extra_fraction"),
                includeActiveBolusIsf =
                    uncertainty.optBoolean("include_active_bolus_isf", false),
            ),
        )
    }

    private fun shape(value: JSONObject) = HybridShape(
        delayMin = value.getDouble("delay_min"),
        peakMin = value.getDouble("peak_min"),
        durationMin = value.getDouble("duration_min"),
    )

    private fun tail(value: JSONObject) = HybridTail(
        gain = value.getDouble("gain"),
        onsetMin = value.getDouble("onset_min"),
        peakMin = value.getDouble("peak_min"),
        durationMin = value.getDouble("duration_min"),
    )

}
