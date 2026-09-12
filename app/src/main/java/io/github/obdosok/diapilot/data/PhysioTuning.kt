package io.github.obdosok.diapilot.data

import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker
import io.github.obdosok.diapilot.i18n.localized
import android.content.Context
import com.diapilot.core.hybrid.HybridPersonModel
import com.diapilot.core.physio.InsulinShapeLandmarksV1
import com.diapilot.core.physio.InsulinShapeV1

/**
 * THE BENCH'S KNOBS, ON THE PHONE.
 *
 * A laptop bench (`tools/EpisodeServer`) has spent time fitting these eight
 * numbers against the user's own episodes, and the fits have moved far enough from what
 * ships that the user asked to try them on the device. This is that surface.
 *
 * THREE RULES SHAPE IT, and none is decoration:
 *
 *  1. **Every default is «exactly what ships today».** Null means «do not
 *     touch», and the trust ramp defaults to 1.00 — the shipped ramp. Installing
 *     this build changes NOTHING until the user moves something. That matters more
 *     than usual here: these knobs reach the hypo-alert path, and an install
 *     that silently re-timed the user's alarms is not a test, it is a surprise at 3am.
 *
 *  2. **The tuning rides on the person model**, not on the engine call. There
 *     are fourteen `physioForecastEngine(...)` call sites in the app; a value
 *     threaded through fourteen places is a value that will be missed in one,
 *     and a missed one computes the OLD model under the NEW label. Riding on
 *     the model also puts the tuning into the artifact hash, so every row in
 *     `physio_parallel_runs` records which tuning produced it.
 *
 *  3. **It is a bridge, not a new home for constants.** The moment a value here
 *     is measured and promoted it belongs in the artifact, and this knob should
 *     go back to null. A setting that quietly becomes the real model is the
 *     failure mode `CARB_SENS_OVERRIDE_DEFAULT` already warns about.
 *
 * WHAT THE KNOBS MEAN, in the words the bench uses:
 *
 * | knob | what it is |
 * |---|---|
 * | trust in horizon | `backboneWeight`. 1.00 = shipped ramp (half the physiology withheld at h=60); 0.00 = none |
 * | onset / full speed / phase / tail | the four insulin landmarks; the curve accelerates to "full speed", holds through "phase", then decays to "tail" |
 * | ISF | mmol per unit |
 * | kcal/h · sieve | the gastric queue |
 * | type difference | how far apart fast/medium/slow carbs stand; 1.00 = shipped |
 *
 * The measurements behind them: M-83 (the ramp withholds half the food at the
 * hour mark, peak under-called in 26 of 28 episodes), M-84 (the ramp converts
 * insulin TIMING into AMPLITUDE, breaking `units x ISF`), M-86/M-87 (with the
 * ramp off and basal on board, the fitted peak lands at 40-42 min in thirteen
 * of twenty-two episodes — where the user's own description of their insulin already was).
 */
object PhysioTuning {

    private const val K_TRI = "physio_carb_triangles"
    private const val K_RAMP = "physio_trust_ramp"
    private const val K_ON = "physio_onset_min"
    private const val K_PK = "physio_full_speed_min"
    private const val K_PHASE = "physio_phase_min"
    private const val K_TAIL = "physio_tail_min"
    private const val K_ISF = "physio_isf_mmol"
    private const val K_KCAL = "physio_emptying_kcal_h"
    private const val K_SIEVE = "physio_carb_sieving"
    private const val K_SPREAD = "physio_carb_spread"

    /** Every field null / 1.0 means «ship as built». */
    data class Values(
        val trustRamp: Double = 1.0,
        val onsetMin: Double? = null,
        val fullSpeedMin: Double? = null,
        val phaseMin: Double? = null,
        val tailMin: Double? = null,
        val isfMmol: Double? = null,
        val emptyingKcalPerHour: Double? = null,
        val carbSieving: Double? = null,
        val carbSpread: Double? = null,
        /**
         * The twelve carb-triangle numbers, applied as ONE set or not at all.
         *
         * NOT twelve sliders, deliberately. `carbSpread` and `carbTimeScale` are
         * exactly reachable by moving these — the bench's fitter locks both the
         * moment a triangle axis is free, and nothing on a settings screen would
         * do that. Offered as an import of a bench set, the redundancy cannot be
         * exercised by hand.
         *
         * Order: fast delay/peak/end/macro, medium ..., slow ... — the order the
         * bench card shows and the export writes.
         */
        val carbTriangles: List<Double>? = null,
    ) {
        val touched: Boolean
            get() = carbTriangles != null || trustRamp != 1.0 || onsetMin != null || fullSpeedMin != null ||
                phaseMin != null || tailMin != null || isfMmol != null ||
                emptyingKcalPerHour != null || carbSieving != null || carbSpread != null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            TreatmentsPollWorker.PREFS,
            Context.MODE_PRIVATE,
        )

    private fun opt(context: Context, key: String, range: ClosedFloatingPointRange<Double>): Double? =
        prefs(context).getFloat(key, 0f).toDouble().takeIf { it in range }

    /**
     * Twelve comma-separated numbers, or absent. Refused as a SET if it does not
     * parse to exactly twelve — a partial triangle would silently mix half a
     * bench set with half the shipped one, which is the worst of both.
     */
    private fun readTriangles(context: Context): List<Double>? {
        val raw = prefs(context).getString(K_TRI, null) ?: return null
        val parts = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        return parts.takeIf { it.size == 12 }
    }

    /**
     * What the model is drawing with right now — the imported set, or the
     * shipped defaults when nothing is imported.
     *
     * The screen had no way to show this: it could only accept a new set, so
     * «what is applied» was unanswerable from the phone. A settings control that
     * writes but cannot read is half a control.
     */
    fun effectiveTriangles(context: Context): List<Double> =
        readTriangles(context) ?: com.diapilot.core.hybrid.CarbTrianglesV1().let {
            listOf(
                it.fastDelayMin, it.fastPeakMin, it.fastEndMin, it.fastMacroShare,
                it.mediumDelayMin, it.mediumPeakMin, it.mediumEndMin, it.mediumMacroShare,
                it.slowDelayMin, it.slowPeakMin, it.slowEndMin, it.slowMacroShare,
            )
        }

    /**
     * Delay < peak < end within each type, or the shape is not a triangle.
     *
     * The bench's fitter applies exactly this before handing knobs to the engine
     * (`Knobs.triangles`), because coordinate descent moves one axis at a time
     * and will propose a peak before its own onset. Hand entry can do the same,
     * and without the clamp the engine would silently coerce it and draw a curve
     * the numbers on screen do not describe.
     */
    fun orderTriangles(v: List<Double>): List<Double> {
        if (v.size != 12) return v
        val out = v.toMutableList()
        listOf(0, 4, 8).forEach { i ->
            out[i + 1] = maxOf(out[i + 1], out[i] + 5.0)
            out[i + 2] = maxOf(out[i + 2], out[i + 1] + 10.0)
            out[i + 3] = out[i + 3].coerceIn(0.0, 2.0)
        }
        return out
    }

    fun setCarbTriangles(context: Context, v: List<Double>?) {
        val e = prefs(context).edit()
        if (v == null || v.size != 12) e.remove(K_TRI)
        else e.putString(K_TRI, orderTriangles(v).joinToString(",") { "%.4f".format(it) })
        e.apply()
    }

    fun read(context: Context) = Values(
        carbTriangles = readTriangles(context),
        trustRamp = prefs(context).getFloat(K_RAMP, 1f).toDouble().coerceIn(0.0, 1.0),
        onsetMin = opt(context, K_ON, 1.0..60.0),
        fullSpeedMin = opt(context, K_PK, 10.0..160.0),
        phaseMin = opt(context, K_PHASE, 1.0..120.0),
        tailMin = opt(context, K_TAIL, 60.0..480.0),
        isfMmol = opt(context, K_ISF, 0.3..8.0),
        emptyingKcalPerHour = opt(context, K_KCAL, 40.0..600.0),
        carbSieving = opt(context, K_SIEVE, 0.05..1.0),
        carbSpread = opt(context, K_SPREAD, 0.0..2.0),
    )

    private fun put(context: Context, key: String, v: Double?) =
        prefs(context).edit().apply {
            if (v == null) remove(key) else putFloat(key, v.toFloat())
        }.apply()

    fun setTrustRamp(context: Context, v: Double) =
        prefs(context).edit().putFloat(K_RAMP, v.coerceIn(0.0, 1.0).toFloat()).apply()

    fun setOnsetMin(context: Context, v: Double?) = put(context, K_ON, v)
    fun setFullSpeedMin(context: Context, v: Double?) = put(context, K_PK, v)
    fun setPhaseMin(context: Context, v: Double?) = put(context, K_PHASE, v)
    fun setTailMin(context: Context, v: Double?) = put(context, K_TAIL, v)
    fun setIsfMmol(context: Context, v: Double?) = put(context, K_ISF, v)
    fun setEmptyingKcalPerHour(context: Context, v: Double?) = put(context, K_KCAL, v)
    fun setCarbSieving(context: Context, v: Double?) = put(context, K_SIEVE, v)
    fun setCarbSpread(context: Context, v: Double?) = put(context, K_SPREAD, v)

    fun reset(context: Context) = prefs(context).edit()
        .remove(K_RAMP).remove(K_ON).remove(K_PK).remove(K_PHASE).remove(K_TAIL)
        .remove(K_ISF).remove(K_KCAL).remove(K_SIEVE).remove(K_SPREAD).remove(K_TRI).apply()

    /**
     * Applies the tuning to an installed model. Returns the SAME instance when
     * nothing is set, so the untouched path is bit-identical rather than merely
     * equivalent — a rebuilt-but-equal model would still change the artifact
     * hash and split the A/B ledger for no reason.
     *
     * The insulin curve is synthesized exactly the way the bench does it, with
     * `TAIL_SHARE` pinning the tail at a fifth, so a landmark set that looks
     * right on the laptop draws the same curve here. If the four landmarks do
     * not form a valid shape the INSULIN PART IS DROPPED and the rest still
     * applies — refusing loudly beats substituting a neighbour silently (M-59).
     */
    fun apply(model: HybridPersonModel, v: Values): HybridPersonModel {
        if (!v.touched) return model
        val ins = model.insulin
        // THE SHAPE NO LONGER ARRIVES HERE. The hand curve lives in
        // P1 (`ManualInsulinRuntime` -> `ManualInsulinParamsV1.resolve`), and
        // a second path to the same value would mean two answers to
        // "which shape is applied" — exactly what made the screen show
        // a mismatch between the configured and the applied landmarks.
        //
        // What remains here: the queue, the sieve, the spread and the triangles.
        val knots: Pair<com.diapilot.core.physio.InsulinShapeLandmarksV1, List<com.diapilot.core.hybrid.HybridCdfKnot>>? = null
        val insulin = ins.copy(
            // ISF IS NO LONGER SET HERE — ONE DOOR.
            //
            // This line overwrote the artifact BASE with the hand value, which
            // had two consequences nobody intended: the learner's promotion
            // percent was applied to the user's hand number instead of to the measured
            // base (so the "learned alternative" was a hybrid of the two), and the
            // hand pin lived at a different layer from the OTHER hand pin
            // (`ManualInsulinRuntime`), so the screen could not say which won.
            // The hand ISF now goes through P1 like the hand timings do.
            isf = ins.isf,
            onsetMin = knots?.first?.onsetMin ?: ins.onsetMin,
            peakMin = knots?.first?.peakMin ?: ins.peakMin,
            shortDurationMin = knots?.first?.tailMin ?: ins.shortDurationMin,
            tailDurationMin = knots?.first?.tailMin ?: ins.tailDurationMin,
            actionCdfKnots = knots?.second ?: ins.actionCdfKnots,
        )
        // SPREAD IS NEUTRALISED WHEN TRIANGLES ARE SET, because it is exactly
        // reachable from them: it collapses fast and slow toward medium, which
        // is a statement about the same twelve numbers. The bench's fitter locks
        // it automatically whenever a triangle axis is free; here the applier
        // does the same, so the two cannot be set against each other by hand.
        val triangles = v.carbTriangles?.let {
            com.diapilot.core.hybrid.CarbTrianglesV1(
                fastDelayMin = it[0], fastPeakMin = it[1], fastEndMin = it[2], fastMacroShare = it[3],
                mediumDelayMin = it[4], mediumPeakMin = it[5], mediumEndMin = it[6], mediumMacroShare = it[7],
                slowDelayMin = it[8], slowPeakMin = it[9], slowEndMin = it[10], slowMacroShare = it[11],
            )
        }
        // 1.0 keeps the model's own weight; 0.0 lifts it to 1.0 (no shrinkage).
        fun mix(w: Double) = 1.0 - (1.0 - w) * v.trustRamp
        return model.copy(
            insulin = insulin,
            food = model.food.copy(
                emptyingKcalPerHourOverride = v.emptyingKcalPerHour,
                carbSievingOverride = v.carbSieving,
                carbSpreadOverride = if (triangles != null) 1.0 else v.carbSpread,
                carbTrianglesOverride = triangles,
            ),
            trend = model.trend.copy(
                weight60 = mix(model.trend.weight60),
                weight120 = mix(model.trend.weight120),
                weight180 = mix(model.trend.weight180),
            ),
        )
    }

    /**
     * Reads a bench export and pulls the twelve triangle numbers out of it.
     *
     * The bench writes `{"triangles": {"fastDelay": .., ...}}` alongside the
     * curve and the meals; only that object is read. Returns null on anything
     * that does not yield exactly twelve finite numbers, so a truncated paste
     * cannot half-apply.
     */
    fun trianglesFromBenchExport(json: String): List<Double>? = runCatching {
        val o = org.json.JSONObject(json).getJSONObject("triangles")
        val keys = listOf(
            "fastDelay", "fastPeak", "fastEnd", "fastMacroShare",
            "medDelay", "medPeak", "medEnd", "medMacroShare",
            "slowDelay", "slowPeak", "slowEnd", "slowMacroShare",
        )
        keys.map { o.getDouble(it) }.takeIf { list ->
            list.size == 12 && list.all { it.isFinite() }
        }
    }.getOrNull()

    fun apply(context: Context, model: HybridPersonModel): HybridPersonModel =
        apply(model, read(context))

    // One line for the settings screen and for the log, so what is applied is
    // readable without opening nine rows.
    /**
     * EVERY field, verbatim, for the artifact identity — not for a human.
     *
     * `summary` is lossy on purpose: it prints only what was touched, rounded,
     * in Russian. Using it as an identity meant two DIFFERENT tunings could
     * share a string, and worse, four knobs never reach it at all — the artifact
     * id was built from `mechanics.insulin` and `food.defaultShape`, and the
     * kcal, sieve, spread and ramp overrides live outside both. So changing any
     * of those produced a different model under an UNCHANGED artifact id, and
     * every paired run in `physio_parallel_runs` averaged two engines with
     * nothing on any screen to say so. Same defect the carb triangles had; this
     * is the general fix.
     */
    fun identity(v: Values): String = listOf(
        v.trustRamp, v.onsetMin, v.fullSpeedMin, v.phaseMin, v.tailMin, v.isfMmol,
        v.emptyingKcalPerHour, v.carbSieving, v.carbSpread,
    ).joinToString("|") { it?.toString() ?: "-" } +
        "|tri=" + (v.carbTriangles?.joinToString(",") { "%.4f".format(it) } ?: "-")

    /** What the tuning card shows as applied, in the UI language. Cache keys
     *  and logs use [identity], which does not change with the language. */
    fun summary(v: Values, context: Context): String {
        val text = context.localized()
        if (!v.touched) return text.getString(R.string.physio_tuning_as_shipped)
        val parts = buildList {
            if (v.trustRamp != 1.0) add(text.getString(R.string.physio_tuning_trust, "%.2f".format(v.trustRamp)))
            if (v.onsetMin != null || v.fullSpeedMin != null ||
                v.phaseMin != null || v.tailMin != null
            ) {
                add(
                    text.getString(
                        R.string.physio_tuning_insulin,
                        v.onsetMin?.let { "%.0f".format(it) } ?: "—",
                        v.fullSpeedMin?.let { "%.0f".format(it) } ?: "—",
                        v.phaseMin?.let { "%.0f".format(it) } ?: "—",
                        v.tailMin?.let { "%.0f".format(it) } ?: "—",
                    ),
                )
            }
            v.isfMmol?.let { add("ISF %.2f".format(it)) }
            v.emptyingKcalPerHour?.let { add(text.getString(R.string.physio_tuning_kcal_per_hour, "%.0f".format(it))) }
            v.carbSieving?.let { add(text.getString(R.string.physio_tuning_sieve, "%.2f".format(it))) }
            v.carbSpread?.let { add(text.getString(R.string.physio_tuning_types, "%.2f".format(it))) }
        }
        return parts.joinToString(" · ")
    }
}
