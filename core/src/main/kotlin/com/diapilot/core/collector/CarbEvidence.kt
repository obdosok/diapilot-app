package com.diapilot.core.collector

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import org.json.JSONObject

const val CARB_EVIDENCE_PROVENANCE_V1 = "carb-evidence-v1"
const val CARB_EVIDENCE_ROUNDING_G = 0.05
const val CARB_EVIDENCE_RELATIVE_TOLERANCE = 0.005

enum class CarbEvidenceSourceV1 {
    LABEL_WEIGHT,
    STANDARD_RECIPE_WEIGHT,
    LABEL_PORTION_ESTIMATED,
    USER_ESTIMATE,
    PRESET_TABLE,
    PHOTO_LLM,
    LEGACY_UNKNOWN,
}

/** Uncertainty is deliberately separate for amount and intake timing. */
data class CarbUncertaintyV1(
    val kind: String,
    val parameter: Double? = null,
) {
    init {
        require(kind.isNotBlank()) { "uncertainty kind is required" }
        require(parameter == null || parameter.isFinite() && parameter >= 0.0) {
            "uncertainty parameter must be finite and non-negative"
        }
    }
}

/** User/API input before owner, revision and causal timestamps are assigned. */
data class CarbEvidenceInputV1(
    val source: CarbEvidenceSourceV1,
    val userConfirmed: Boolean,
    val totalCarbsG: Double? = null,
    val labelCarbsPer100g: Double? = null,
    val weighedEdibleG: Double? = null,
    val labelCarbsPerServingG: Double? = null,
    val servings: Double? = null,
    val recipeVersion: String? = null,
    val recipeTotalCarbsG: Double? = null,
    val recipeTotalWeightG: Double? = null,
    val recipeConsumedWeightG: Double? = null,
    val recipeConsumedFraction: Double? = null,
    val intakeDurationMin: Double? = null,
    val amountUncertainty: CarbUncertaintyV1,
    val timingUncertainty: CarbUncertaintyV1,
    val evidenceHash: String? = null,
    val alcoholPresent: Boolean = false,
    /** Frozen name-independent timing facts known with this carb revision. */
    val kineticsV2: String? = null,
) {
    fun validated(): CarbEvidenceInputV1 {
        listOf(
            totalCarbsG, labelCarbsPer100g, weighedEdibleG, labelCarbsPerServingG,
            servings, recipeTotalCarbsG, recipeTotalWeightG, recipeConsumedWeightG,
            recipeConsumedFraction, intakeDurationMin,
        ).filterNotNull().forEach { require(it.isFinite() && it >= 0.0) { "carb evidence values must be finite and non-negative" } }
        require(intakeDurationMin == null || intakeDurationMin <= 240.0) { "intake duration must be <= 240 minutes" }
        evidenceHash?.let { require(it.matches(Regex("[0-9a-fA-F]{16,128}"))) { "evidence hash must be hexadecimal" } }
        kineticsV2?.let { require(it.startsWith("KINETICS_V2:")) { "kinetics must use the versioned machine line" } }

        val computed = when (source) {
            CarbEvidenceSourceV1.LABEL_WEIGHT -> {
                require(userConfirmed) { "LABEL_WEIGHT requires user confirmation" }
                val per100 = if (labelCarbsPer100g != null && weighedEdibleG != null) {
                    labelCarbsPer100g * weighedEdibleG / 100.0
                } else null
                val perServing = if (labelCarbsPerServingG != null && servings != null) {
                    labelCarbsPerServingG * servings
                } else null
                require((per100 == null) xor (perServing == null)) {
                    "LABEL_WEIGHT requires exactly one recomputable label arithmetic path"
                }
                per100 ?: perServing!!
            }
            CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT -> {
                require(userConfirmed) { "STANDARD_RECIPE_WEIGHT requires user confirmation" }
                require(!recipeVersion.isNullOrBlank()) { "recipe version is required" }
                val recipeCarbs = positive(recipeTotalCarbsG, "recipe total carbs")
                val byWeight = if (recipeTotalWeightG != null && recipeConsumedWeightG != null) {
                    require(recipeTotalWeightG > 0.0 && recipeConsumedWeightG <= recipeTotalWeightG * 4.0) {
                        "recipe weights are impossible"
                    }
                    recipeCarbs * recipeConsumedWeightG / recipeTotalWeightG
                } else null
                val byFraction = recipeConsumedFraction?.let {
                    require(it in 0.0..1.0) { "recipe consumed fraction must be between 0 and 1" }
                    recipeCarbs * it
                }
                require((byWeight == null) xor (byFraction == null)) {
                    "recipe requires exactly one consumed weight or fraction path"
                }
                byWeight ?: byFraction!!
            }
            else -> positive(totalCarbsG, "point carbohydrate estimate")
        }
        require(computed.isFinite() && computed > 0.0 && computed <= 1000.0) { "computed carbohydrates are impossible" }
        totalCarbsG?.let {
            val tolerance = max(CARB_EVIDENCE_ROUNDING_G, computed * CARB_EVIDENCE_RELATIVE_TOLERANCE)
            require(abs(it - computed) <= tolerance) { "stored total does not match recomputable arithmetic" }
        }
        return copy(totalCarbsG = computed)
    }

    private fun positive(value: Double?, label: String): Double {
        require(value != null && value.isFinite() && value > 0.0) { "$label must be positive" }
        return value
    }
}

data class CarbEvidenceV1(
    val evidenceId: String,
    val revision: Int,
    val supersedesRevision: Int?,
    val annotationId: Long,
    val mealSessionId: String,
    val eventTimeMs: Long,
    val intakeStartMs: Long,
    val intakeEndMs: Long,
    val knownAtMs: Long,
    val recordedAtMs: Long,
    val input: CarbEvidenceInputV1,
    val deleted: Boolean = false,
    val provenanceVersion: String = CARB_EVIDENCE_PROVENANCE_V1,
) {
    companion object {
        /** Strict wire parser used by journal/API import. Raw meal text is not part of this object. */
        fun parseCanonicalJson(json: String): CarbEvidenceV1 = fromJsonObject(JSONObject(json))

        fun fromJsonObject(o: JSONObject): CarbEvidenceV1 {
            fun d(name: String): Double? = if (o.has(name) && !o.isNull(name)) o.getDouble(name) else null
            fun s(name: String): String? = if (o.has(name) && !o.isNull(name)) o.getString(name) else null
            fun uncertainty(name: String): CarbUncertaintyV1 {
                val u = o.getJSONObject(name)
                return CarbUncertaintyV1(u.getString("kind"), if (u.has("parameter")) u.getDouble("parameter") else null)
            }
            val input = CarbEvidenceInputV1(
                source = CarbEvidenceSourceV1.valueOf(o.getString("source")),
                userConfirmed = o.getBoolean("user_confirmed"),
                totalCarbsG = o.getDouble("total_carbs_g"),
                labelCarbsPer100g = d("label_carbs_per_100g"),
                weighedEdibleG = d("weighed_edible_g"),
                labelCarbsPerServingG = d("label_carbs_per_serving_g"),
                servings = d("servings"),
                recipeVersion = s("recipe_version"),
                recipeTotalCarbsG = d("recipe_total_carbs_g"),
                recipeTotalWeightG = d("recipe_total_weight_g"),
                recipeConsumedWeightG = d("recipe_consumed_weight_g"),
                recipeConsumedFraction = d("recipe_consumed_fraction"),
                intakeDurationMin = d("intake_duration_min"),
                amountUncertainty = uncertainty("amount_uncertainty"),
                timingUncertainty = uncertainty("timing_uncertainty"),
                evidenceHash = s("evidence_hash"),
                alcoholPresent = o.getBoolean("alcohol_present"),
                kineticsV2 = s("kinetics_v2"),
            ).validated()
            return CarbEvidenceV1(
                evidenceId = o.getString("evidence_id"),
                revision = o.getInt("revision"),
                supersedesRevision = if (o.has("supersedes_revision")) o.getInt("supersedes_revision") else null,
                annotationId = o.getLong("annotation_id"),
                mealSessionId = o.getString("meal_session_id"),
                eventTimeMs = o.getLong("event_time_ms"),
                intakeStartMs = o.getLong("intake_start_ms"),
                intakeEndMs = o.getLong("intake_end_ms"),
                knownAtMs = o.getLong("known_at_ms"),
                recordedAtMs = o.getLong("recorded_at_ms"),
                input = input,
                deleted = o.getBoolean("deleted"),
                provenanceVersion = o.getString("provenance_version"),
            )
        }
    }
    init {
        require(evidenceId.isNotBlank() && mealSessionId.isNotBlank()) { "stable evidence and meal owner IDs are required" }
        require(revision >= 1 && supersedesRevision == revision - 1 || revision == 1 && supersedesRevision == null) {
            "revision chain must be contiguous"
        }
        require(annotationId > 0) { "annotation owner is required" }
        require(intakeStartMs <= intakeEndMs) { "intake interval is reversed" }
        require(recordedAtMs >= knownAtMs) { "recorded_at cannot precede known_at" }
        require(provenanceVersion == CARB_EVIDENCE_PROVENANCE_V1) { "unsupported provenance version" }
        if (!deleted) input.validated()
    }

    /** Contains no meal text or raw evidence reference. */
    fun canonicalJson(): String = buildString {
        val v = input.validated()
        append('{')
        field("evidence_id", evidenceId)
        number("revision", revision.toLong())
        supersedesRevision?.let { number("supersedes_revision", it.toLong()) }
        number("annotation_id", annotationId)
        field("meal_session_id", mealSessionId)
        number("event_time_ms", eventTimeMs)
        number("intake_start_ms", intakeStartMs)
        number("intake_end_ms", intakeEndMs)
        number("known_at_ms", knownAtMs)
        number("recorded_at_ms", recordedAtMs)
        field("source", v.source.name)
        bool("user_confirmed", v.userConfirmed)
        decimal("total_carbs_g", v.totalCarbsG!!)
        optionalDecimal("label_carbs_per_100g", v.labelCarbsPer100g)
        optionalDecimal("weighed_edible_g", v.weighedEdibleG)
        optionalDecimal("label_carbs_per_serving_g", v.labelCarbsPerServingG)
        optionalDecimal("servings", v.servings)
        v.recipeVersion?.let { field("recipe_version", it) }
        optionalDecimal("recipe_total_carbs_g", v.recipeTotalCarbsG)
        optionalDecimal("recipe_total_weight_g", v.recipeTotalWeightG)
        optionalDecimal("recipe_consumed_weight_g", v.recipeConsumedWeightG)
        optionalDecimal("recipe_consumed_fraction", v.recipeConsumedFraction)
        optionalDecimal("intake_duration_min", v.intakeDurationMin)
        uncertainty("amount_uncertainty", v.amountUncertainty)
        uncertainty("timing_uncertainty", v.timingUncertainty)
        v.evidenceHash?.let { field("evidence_hash", it.lowercase()) }
        bool("alcohol_present", v.alcoholPresent)
        v.kineticsV2?.let { field("kinetics_v2",it) }
        bool("deleted", deleted)
        field("provenance_version", provenanceVersion)
        append('}')
    }

    private fun StringBuilder.comma() { if (lastOrNull() != '{') append(',') }
    private fun StringBuilder.field(name: String, value: String) { comma(); append(q(name)).append(':').append(q(value)) }
    private fun StringBuilder.number(name: String, value: Long) { comma(); append(q(name)).append(':').append(value) }
    private fun StringBuilder.decimal(name: String, value: Double) { comma(); append(q(name)).append(':').append(render(value)) }
    private fun StringBuilder.optionalDecimal(name: String, value: Double?) { value?.let { decimal(name, it) } }
    private fun StringBuilder.bool(name: String, value: Boolean) { comma(); append(q(name)).append(':').append(value) }
    private fun StringBuilder.uncertainty(name: String, value: CarbUncertaintyV1) {
        comma(); append(q(name)).append(":{").append(q("kind")).append(':').append(q(value.kind))
        value.parameter?.let { append(',').append(q("parameter")).append(':').append(render(it)) }
        append('}')
    }
}

data class CarbEvidenceReadinessV1(
    val activeEvidenceCount: Int,
    val measuredCount: Int,
    val distinctLocalDayCount: Int,
    val usability: String = "UNKNOWN_UNTIL_OUTCOMES",
)


fun selectCarbEvidenceKnownAtV1(history: List<CarbEvidenceV1>, queryTimeMs: Long): CarbEvidenceV1? =
    history.filter { it.knownAtMs <= queryTimeMs }
        .maxWithOrNull(compareBy<CarbEvidenceV1> { it.revision }.thenBy { it.knownAtMs })
        ?.takeUnless { it.deleted }

fun validateRecipeRevisionV1(previous: CarbEvidenceInputV1?, next: CarbEvidenceInputV1) {
    if (previous?.source != CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT ||
        next.source != CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT ||
        previous.recipeVersion != next.recipeVersion
    ) return
    require(
        previous.recipeTotalCarbsG == next.recipeTotalCarbsG &&
            previous.recipeTotalWeightG == next.recipeTotalWeightG,
    ) { "changed recipe arithmetic requires a new recipe version" }
}

private fun q(value: String): String = buildString {
    append('"')
    value.forEach { c -> when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    } }
    append('"')
}

private fun render(value: Double): String {
    require(value.isFinite())
    return if (value == kotlin.math.floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()
}
