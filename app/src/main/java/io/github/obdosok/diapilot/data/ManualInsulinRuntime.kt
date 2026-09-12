package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.hybrid.HybridPersonModel
import com.diapilot.core.physio.InsulinParamTierV1
import com.diapilot.core.physio.InsulinParameterResolverV1
import com.diapilot.core.physio.InsulinShapeLandmarksV1
import com.diapilot.core.physio.ManualInsulinParamsV1
import com.diapilot.core.physio.PersonalInsulinCurveV1
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker

/**
 * P1 — the insulin shape and ISF the user set by hand.
 *
 * The stated rule: what the user enters wins over what we measure from
 * tagged corrections (P2), which wins over what we infer from untagged
 * injections behind the full gates (P3). Once P1 is in force the app may only
 * REPORT that its own numbers differ — see [InsulinParameterResolverV1.divergences].
 *
 * This is also the answer to cold start. A user a few days in has no measured
 * curve at all, and "wait a month" is not an answer for someone who already
 * knows when their insulin starts working.
 *
 * ISF is set as the BASE, not as a final answer. Context multipliers — activity,
 * time of day, the promoted ISF effect — still scale it, so entering a number
 * by hand does not flatten the fact that this quantity moves. That reflects
 * the user's own position on ISF variability: the coefficient the user wants to
 * explain (activity above, poor sleep or illness below) rides on top of this
 * value rather than replacing it.
 */
object ManualInsulinRuntime {
    private const val KEY_ONSET = "manual_insulin_onset_min"
    private const val KEY_PEAK = "manual_insulin_peak_min"
    private const val KEY_PLATEAU_END = "manual_insulin_plateau_end_min"
    private const val KEY_TAIL = "manual_insulin_tail_min"
    private const val KEY_ISF = "manual_insulin_isf"
    private const val KEY_SET_AT = "manual_insulin_set_at_ms"

    private fun prefs(context: Context) =
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)

    private const val KEY_ISF_MIGRATED = "manual_insulin_isf_migrated_v1"

    /**
     * CARRY THE OLD HAND ISF OVER, ONCE.
     *
     * The hand-set ISF used to live in `PhysioTuning.physio_isf_mmol`
     * and overwrote the artifact base. It now lives here like every other hand
     * parameter. Without this migration the switch would silently move the
     * user's applied ISF to a different fitted value — a meaningful step on
     * the model's most important parameter, on a medical device, with no
     * user action. Runs on the read path so no ordering can miss it.
     */
    private fun migrateLegacyIsf(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_ISF_MIGRATED, false)) return
        val legacy = runCatching { PhysioTuning.read(context).isfMmol }.getOrNull()
        p.edit().apply {
            if (legacy != null && legacy.isFinite() && legacy > 0.0 && !p.contains(KEY_ISF)) {
                putFloat(KEY_ISF, legacy.toFloat())
                putLong(KEY_SET_AT, System.currentTimeMillis())
            }
            putBoolean(KEY_ISF_MIGRATED, true)
        }.apply()
        // The legacy key is cleared only after its value is safely here, so an
        // interrupted migration loses nothing.
        if (legacy != null) runCatching { PhysioTuning.setIsfMmol(context, null) }
    }

    /**
     * WHAT TO SHOW IN THE CARD'S FIELDS — three tiers, and none is empty for no reason.
     *
     * An empty field means "clear the override". So showing emptiness where
     * there is no override is dangerous: a live incident showed previously
     * entered shape values getting silently erased — the fields showed empty
     * (the read path had switched to a store that never had the form's
     * values), the user pressed "Apply", and zeros got written to both stores.
     *
     * Order: the user's own override (`manual_insulin_*`) -> the earlier
     * value from the retired store (`physio_*`, where "phase" is a DURATION)
     * -> the applied model. The last tier makes pressing Apply on untouched
     * fields a no-op: whatever is already in the model goes back into it.
     *
     * `plateauEndMin` is stored as an absolute minute, the field shows a
     * duration, so it is converted by subtracting the peak. The applied model
     * does not store the plateau separately — the phase is not shown there.
     */
    fun shapeForCard(
        stored: ManualInsulinParamsV1,
        legacyOnset: Double?,
        legacyPeak: Double?,
        legacyPhase: Double?,
        legacyTail: Double?,
        appliedOnset: Double?,
        appliedPeak: Double?,
        appliedTail: Double?,
    ): Quadruple = when {
        stored.anyShape -> Quadruple(
            stored.onsetMin, stored.peakMin,
            stored.plateauEndMin?.let { end -> stored.peakMin?.let { end - it } },
            stored.tailMin,
        )
        legacyOnset != null || legacyPeak != null || legacyPhase != null || legacyTail != null ->
            Quadruple(legacyOnset, legacyPeak, legacyPhase, legacyTail)
        else -> Quadruple(appliedOnset, appliedPeak, null, appliedTail)
    }

    /** Four fields on the card: onset, peak, phase DURATION, tail. */
    data class Quadruple(
        val onsetMin: Double?,
        val peakMin: Double?,
        val phaseDurationMin: Double?,
        val tailMin: Double?,
    )

    /**
     * ASSEMBLING THE FORM FROM THE CARD'S FIELDS, where "phase" is a DURATION.
     *
     * Exists because the unit conversion used to live in the UI and so was
     * guarded by nothing. The tuning card labels the field "phase, min" and
     * explains "holds at most for the whole phase" — i.e. HOW LONG it holds;
     * the store expects [ManualInsulinParamsV1.plateauEndMin], an absolute
     * minute from the injection. The field used to be passed through as-is,
     * and for shape values where the phase duration put plateauEnd before
     * peak, the ordering was violated: the form was rejected as
     * SHAPE_OUT_OF_DOMAIN and silently replaced by the measured curve, while
     * the screen still reported the entered values as applied.
     *
     * [fallbackPeakMin] is needed when the peak is not entered: without it
     * there is nothing to convert the duration against, and silently dropping
     * it would repeat the same defect.
     */
    fun shapeFromCard(
        onsetMin: Double?,
        peakMin: Double?,
        phaseDurationMin: Double?,
        tailMin: Double?,
        isfMmolPerU: Double?,
        fallbackPeakMin: Double?,
    ): ManualInsulinParamsV1 = ManualInsulinParamsV1(
        onsetMin = onsetMin,
        peakMin = peakMin,
        plateauEndMin = phaseDurationMin?.let { ph -> (peakMin ?: fallbackPeakMin)?.plus(ph) },
        tailMin = tailMin,
        isfMmolPerU = isfMmolPerU,
    )

    fun params(context: Context?): ManualInsulinParamsV1 {
        val p = context?.let { migrateLegacyIsf(it); prefs(it) } ?: return ManualInsulinParamsV1.EMPTY
        fun num(key: String) = p.getFloat(key, Float.NaN).toDouble().takeIf { it.isFinite() }
        return ManualInsulinParamsV1(
            onsetMin = num(KEY_ONSET), peakMin = num(KEY_PEAK),
            plateauEndMin = num(KEY_PLATEAU_END), tailMin = num(KEY_TAIL),
            isfMmolPerU = num(KEY_ISF),
            setAtMs = p.getLong(KEY_SET_AT, 0L).takeIf { it > 0L },
        )
    }

    /**
     * Writes synchronously and drops the caches that hold a person model.
     *
     * A parameter the user just typed must be visible on the very next
     * redraw. `commit` rather than `apply` because [PhysioRuntime]'s artifact
     * cache is keyed on [identity], which reads these same preferences back.
     */
    fun setParams(context: Context, value: ManualInsulinParamsV1, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().apply {
            fun put(key: String, v: Double?) { if (v == null) remove(key) else putFloat(key, v.toFloat()) }
            put(KEY_ONSET, value.onsetMin); put(KEY_PEAK, value.peakMin)
            put(KEY_PLATEAU_END, value.plateauEndMin)
            put(KEY_TAIL, value.tailMin); put(KEY_ISF, value.isfMmolPerU)
            if (value.any) putLong(KEY_SET_AT, nowMs) else remove(KEY_SET_AT)
        }.commit()
        runCatching { TwinCache.invalidate() }
    }

    /** Cache identity. Must move whenever any entered value moves. */
    fun identity(context: Context?): String = params(context).let {
        "manual:${it.onsetMin}/${it.peakMin}/${it.plateauEndMin}/${it.tailMin}/${it.isfMmolPerU}"
    }

    /**
     * @param person the model AFTER the measured curve has been applied, so its
     *        landmarks are the fallback for fields left blank.
     * @param measuredIsf what the data says, for the divergence report only.
     */
    fun resolve(
        person: HybridPersonModel,
        manual: ManualInsulinParamsV1,
        measuredCurve: PersonalInsulinCurveV1?,
        measuredIsf: Double? = null,
    ): InsulinParameterResolverV1.Resolution = InsulinParameterResolverV1.resolve(
        manual = manual,
        measured = measuredCurve?.takeIf { it.ready }?.knots,
        measuredTier = InsulinParamTierV1.TAGGED_CORRECTION,
        measuredIsf = measuredIsf,
        measuredIsfTier = InsulinParamTierV1.TAGGED_CORRECTION,
        // The prior fills only the landmarks left blank. Its own curve is
        // single-moded, so it offers no plateau to inherit.
        priorLandmarks = InsulinShapeLandmarksV1(
            onsetMin = person.insulin.onsetMin, peakMin = person.insulin.peakMin,
            plateauEndMin = null, tailMin = person.insulin.tailDurationMin,
        ),
    )

    /**
     * One resolved shape reaches the model, and therefore reaches the forecast,
     * IOB, What-if and the meal deconvolution alike — they all read
     * `person.insulin`. That single path is the point: a curve the user is
     * shown but which deconvolution does not use would be a picture of a model
     * that never ran.
     */
    fun apply(person: HybridPersonModel, resolution: InsulinParameterResolverV1.Resolution): HybridPersonModel {
        val manualShape = resolution.shapeTier == InsulinParamTierV1.MANUAL
        val manualIsf = resolution.isfTier == InsulinParamTierV1.MANUAL
        if (!manualShape && !manualIsf) return person
        var insulin = person.insulin
        if (manualShape) {
            val knots = resolution.knots ?: return person
            val lm = resolution.landmarks ?: return person
            // shortDurationMin must stay strictly above the peak and at or
            // below the tail; with an ordered triple both are the same number.
            // `peakMin` holds ONE number, so it gets the middle of the active
            // phase — the best single-point summary of an interval. Nothing
            // reads it while the knots are present; it is the fallback
            // triangle's apex, and a fallback pinned to the phase's opening
            // edge would sit visibly early.
            insulin = insulin.copy(
                onsetMin = lm.onsetMin, peakMin = lm.singlePeakMin,
                shortDurationMin = lm.tailMin, tailDurationMin = lm.tailMin,
                actionCdfKnots = knots,
            )
        }
        if (manualIsf) {
            val isf = resolution.isfMmolPerU ?: return person
            // The band is rescaled, not collapsed. A hand-entered point value
            // is a better centre than ours; it is not a claim of certainty,
            // and a zero-width band would silently narrow every forecast
            // corridor and with it the hypo alert's own margin.
            val scale = if (insulin.isf > 0.0) isf / insulin.isf else 1.0
            insulin = insulin.copy(
                isf = isf,
                isfLow = (insulin.isfLow * scale).coerceAtLeast(1e-3),
                isfHigh = (insulin.isfHigh * scale).coerceAtLeast(insulin.isfLow * scale),
            )
        }
        val tag = resolution.manualFields.sorted().joinToString(",")
        return runCatching {
            person.copy(modelVersion = "${person.modelVersion}+manual:$tag", insulin = insulin)
        }.getOrElse {
            // Fail closed. An invariant violation here would collapse the whole
            // PHYSIO arm rather than ignore one hand-entered number.
            android.util.Log.w("ManualInsulin", "manual params rejected by the model contract", it)
            person
        }
    }
}
