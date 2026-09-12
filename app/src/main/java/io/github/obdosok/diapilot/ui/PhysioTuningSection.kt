package io.github.obdosok.diapilot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import com.diapilot.core.physio.PhysioAutoFitV1
import io.github.obdosok.diapilot.LocalAppGraph
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.AdaptiveIsfRuntime
import io.github.obdosok.diapilot.data.HybridModelStore
import io.github.obdosok.diapilot.data.IsfSource
import io.github.obdosok.diapilot.data.ManualInsulinRuntime
import io.github.obdosok.diapilot.data.PhysioAutoFitController
import io.github.obdosok.diapilot.data.PhysioAutoFitRuntime
import io.github.obdosok.diapilot.data.PhysioRuntime
import io.github.obdosok.diapilot.data.PhysioTuning
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.i18n.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * THE BENCH'S KNOBS, ON THE PHONE — the screen for [PhysioTuning].
 *
 * Everything starts empty, and empty means "as shipped". Nothing here takes
 * effect until the user types a number, and the card says so at the top rather
 * than leaving them to infer it from blank fields.
 *
 * Three deliberate choices in the interaction:
 *
 *  - **Values are not applied on every keystroke.** A half-typed "4" on the way
 *    to "40" is a valid number, and applying it would re-install the model with
 *    a four-minute insulin onset. So the fields hold text and one button
 *    commits, which is also the moment the model is re-tuned.
 *  - **The trust ramp is a slider, not a field**, because it is the one knob
 *    that is a decision rather than a measurement: 1.00 is today, 0.00 is the
 *    model without the shrinkage. There is no number to type.
 *  - **The auto-fit fills the fields and stops.** It never applies itself.
 *    These knobs reach the hypo-alert path, and a screen that quietly installed
 *    a fitted model would be making a dosing-adjacent decision on the user's behalf.
 *
 * The card names what the measurement says and what is NOT yet known, because
 * the user is the one who will be woken by an alarm computed with these numbers.
 */
@Composable
fun PhysioTuningSection(modifier: Modifier = Modifier, onShowOnChart: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val graph = LocalAppGraph.current
    var benchPaste by remember { mutableStateOf("") }
    var triStatus by remember { mutableStateOf("") }
    // Twelve editable strings seeded from what is ACTUALLY applied — the imported
    // set if there is one, the shipped defaults otherwise.
    var triFields by remember {
        mutableStateOf(
            PhysioTuning.effectiveTriangles(context).map { "%.2f".format(it).trimEnd('0').trimEnd('.') },
        )
    }
    val initial = remember { PhysioTuning.read(context) }

    fun s(v: Double?) = v?.let { "%.0f".format(it) } ?: ""
    fun s2(v: Double?) = v?.let { "%.2f".format(it) } ?: ""

    var ramp by remember { mutableStateOf(initial.trustRamp.toFloat()) }
    // THE FORM FIELDS READ FROM THE SAME PLACE THEY WRITE TO. `initial` used to
    // be `physio_*`, which the form no longer saves to; after "Apply fields" and
    // returning to the tab it would show the old numbers over the new ones.
    //
    // "Phase" is a DURATION, but storage holds an absolute end minute: we
    // convert by subtraction. THREE LEVELS, AND THE LAST IS THE APPLIED MODEL.
    //
    // An empty field on this card means "clear the override", and that is
    // correct. But then it must not SIT empty by accident: a stale read once
    // made the fields look empty (the read had been pointed at storage the
    // form never wrote to), the user pressed "Apply fields", and zeros erased
    // the fitted values from the older storage. The setting disappeared
    // silently, because "empty" meant both "not set" and "clear what's set" at
    // once.
    //
    // Now the card always shows what's ACTUALLY IN EFFECT: its own override,
    // else the previous value from the retired storage, else whatever is
    // actually applied to the model. Pressing "Apply" on untouched fields
    // becomes a no-op.
    val liveInsulin = remember {
        PhysioRuntime
            .artifact(graph.store)
            ?.personModelAt(12.0, emptySet())?.insulin
    }
    val shownShape = remember {
        ManualInsulinRuntime.shapeForCard(
            stored = ManualInsulinRuntime.params(context),
            legacyOnset = initial.onsetMin,
            legacyPeak = initial.fullSpeedMin,
            legacyPhase = initial.phaseMin,
            legacyTail = initial.tailMin,
            appliedOnset = liveInsulin?.onsetMin,
            appliedPeak = liveInsulin?.peakMin,
            appliedTail = liveInsulin?.tailDurationMin,
        )
    }
    var on by remember { mutableStateOf(s(shownShape.onsetMin)) }
    var pk by remember { mutableStateOf(s(shownShape.peakMin)) }
    var phase by remember { mutableStateOf(s(shownShape.phaseDurationMin)) }
    var tail by remember { mutableStateOf(s(shownShape.tailMin)) }
    // ISF lives in P1 now, not in `initial` — seeding it from the retired store
    // showed an empty field while a pinned value was being applied.
    var isf by remember {
        mutableStateOf(s2(ManualInsulinRuntime.params(context).isfMmolPerU))
    }
    var kcal by remember { mutableStateOf(s(initial.emptyingKcalPerHour)) }
    var sieve by remember { mutableStateOf(s2(initial.carbSieving)) }
    var spread by remember { mutableStateOf(s2(initial.carbSpread)) }
    var applied by remember { mutableStateOf(PhysioTuning.summary(initial, context)) }

    // The fit's state lives in a process-scoped holder, NOT in this
    // composition. Leaving the tab used to cancel the run and re-enable the
    // button with nothing to show for it.
    val fit by PhysioAutoFitController.state.collectAsState()
    var offered by remember { mutableStateOf(false) }
    // Axis names as `PhysioAutoFitV1.AXES` spells them; a locked axis is simply
    // not searched, so it keeps the value that is applied today.
    var locks by remember { mutableStateOf(setOf<String>()) }

    fun num(t: String): Double? = t.trim().replace(',', '.').toDoubleOrNull()

    fun commit() {
        PhysioTuning.setTrustRamp(context, ramp.toDouble())
        PhysioTuning.setOnsetMin(context, num(on))
        PhysioTuning.setFullSpeedMin(context, num(pk))
        PhysioTuning.setPhaseMin(context, num(phase))
        PhysioTuning.setTailMin(context, num(tail))
        // ISF no longer has a store of its own — it goes to P1 below, the one
        // place a hand-set insulin parameter lives.
        PhysioTuning.setIsfMmol(context, null)
        // THE SHAPE FIELDS NOW REACH THE MODEL.
        //
        // They used to write `physio_*` keys, while the model reads
        // `manual_insulin_*` — two namespaces for one quantity, so everything
        // typed here was stored, echoed back by the card as "applied", and
        // silently ignored. A live check showed the configured shape and the
        // applied shape diverging — the segment-measured curve was used
        // instead, because the override never arrived. The resolver was not
        // even rejecting it; there was no path.
        //
        // Nothing changes until the user presses "Apply fields": the values
        // already stored stay inert until this runs, so a control that was
        // decorative becomes real without moving the model behind the user's back.
        // "PHASE" IS A DURATION, BUT STORAGE EXPECTS AN ABSOLUTE MINUTE.
        //
        // The field label and the card text say "holds at most for the whole
        // phase", i.e. HOW LONG IT HOLDS. `ManualInsulinParamsV1.plateauEndMin`
        // is WHEN IT ENDED, an absolute minute from the injection. The field
        // used to be written there as-is, and on a real set of numbers that
        // produced onset < peak > plateauEnd out of order — `shapeUsable` =
        // false, the shape was rejected as SHAPE_OUT_OF_DOMAIN and silently
        // replaced by the measured one.
        //
        // In other words, an earlier fix (writing to `manual_insulin_*`
        // instead of `physio_*`) gave the field a path but not the right
        // units: the configured shape and the applied shape still diverged,
        // confirmed against a live model export.
        //
        // Peak is taken from the field, or from the applied value when the
        // field is empty: without it there is nothing to turn the duration
        // into, and silently dropping it would repeat the same defect.
        ManualInsulinRuntime.setParams(
            context,
            ManualInsulinRuntime.shapeFromCard(
                onsetMin = num(on),
                peakMin = num(pk),
                phaseDurationMin = num(phase),
                tailMin = num(tail),
                isfMmolPerU = num(isf),
                fallbackPeakMin = PhysioAutoFitRuntime.startingKnobs(context)?.fullSpeedMin,
            ),
        )
        PhysioTuning.setEmptyingKcalPerHour(context, num(kcal))
        PhysioTuning.setCarbSieving(context, num(sieve))
        PhysioTuning.setCarbSpread(context, num(spread))
        HybridModelStore.retune(context)
        applied = PhysioTuning.summary(PhysioTuning.read(context), context)
    }

    /**
     * Loads a fit into the fields WITHOUT applying it, and only when the user asks.
     *
     * Not automatic on completion: the user may have typed their own numbers in
     * the meantime, and coming back to the tab would silently overwrite them.
     */
    fun offer(k: PhysioAutoFitV1.Knobs) {
        ramp = k.trustRamp.toFloat()
        on = "%.0f".format(k.onsetMin)
        pk = "%.0f".format(k.fullSpeedMin)
        phase = "%.0f".format(k.phaseMin)
        tail = "%.0f".format(k.tailMin)
        isf = "%.2f".format(k.isf)
        kcal = "%.0f".format(k.emptyingKcalPerHour)
        sieve = "%.2f".format(k.carbSieving)
        spread = "%.2f".format(k.carbSpread)
        offered = true
    }

    fun describe(
        androidContext: android.content.Context,
        metric: PhysioAutoFitV1.Metric,
        b: PhysioAutoFitV1.Batch,
    ): String {
        val text = androidContext.localized()
        val name = text.getString(
            if (metric == PhysioAutoFitV1.Metric.SHAPE) {
                R.string.physio_tuning_section_metric_shape
            } else {
                R.string.physio_tuning_section_metric_balance
            },
        )
        if (b.fits.isEmpty()) return text.getString(R.string.physio_tuning_section_describe_no_episodes)
        val m = b.median
        val lines = listOf(
            text.resources.getQuantityString(R.plurals.physio_tuning_section_describe_episodes_metric, b.fits.size, b.fits.size, name),
            text.getString(
                R.string.physio_tuning_section_describe_isf,
                m.isf, PhysioAutoFitRuntime.spread(b) { it.isf },
            ),
            text.getString(
                R.string.physio_tuning_section_describe_insulin,
                m.onsetMin, m.fullSpeedMin, m.phaseMin, m.tailMin,
            ),
            text.getString(
                R.string.physio_tuning_section_describe_full_speed_spread,
                PhysioAutoFitRuntime.spread(b) { it.fullSpeedMin },
            ),
            text.getString(
                R.string.physio_tuning_section_describe_totals,
                m.emptyingKcalPerHour, m.carbSieving, m.carbSpread, m.trustRamp,
            ),
        )
        return lines.joinToString(separator = System.lineSeparator())
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.physio_tuning_section_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.physio_tuning_section_intro_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // "APPLIED" MEANS APPLIED — READ FROM THE MODEL, NOT FROM SETTINGS.
            //
            // The line used to be built from `PhysioTuning.read`, i.e. from
            // settings storage, and so showed the entered value even when the
            // resolver had rejected it — the configured shape and the applied
            // shape could silently diverge with no way to see it. Now the form
            // and the ISF come from the installed model, while the queue, the
            // sieve and the spread come from settings, where they actually live.
            val liveShape = PhysioRuntime
                .artifact(graph.store)
                ?.personModelAt(12.0, emptySet())?.insulin
            Text(
                stringResource(
                    R.string.physio_tuning_section_currently_applied,
                    liveShape?.let {
                        stringResource(
                            R.string.physio_tuning_section_applied_shape,
                            it.onsetMin, it.peakMin, it.tailDurationMin, it.isf,
                        )
                    } ?: stringResource(R.string.physio_tuning_section_no_model),
                    applied,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            // WHICH SOURCE WON, not just what the number is. This card used to
            // say "applied" over a shape that never left these preferences, and
            // nothing on screen could have told the user.
            Text(
                stringResource(R.string.physio_tuning_section_curve_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- WHERE THESE NUMBERS ACTUALLY LAND -------------------------
            //
            // The natural question is "physio v1 is selected, so everything
            // must be correct, right?" and the honest answer needed three
            // settings, two of which there was no reason to know were separate.
            // A tuning screen
            // that cannot say which consumers it reaches invites exactly the
            // mistake this whole audit is about: turning knobs on a model the
            // screen does not draw.
            //
            // Read live rather than remembered, so switching an arm elsewhere
            // and coming back shows the truth.
            // Learned dish curves reach the forecast only on the physio arm and
            // only for dishes whose pool produced a usable template. Off the main
            // thread and from the SAVED snapshot rather than a Twin build: this
            // is a label on a settings card, and it must not make opening
            // Settings wait for the model (the same rule History lives by).
            // ONE IMPORT, NOT TWELVE SLIDERS.
            //
            // The carb triangles are a set of twelve numbers that only make
            // sense together, and two of the sliders already here — the "type
            // difference" slider above all — are exactly reachable by moving
            // them. The bench
            // fitter locks that axis the moment a triangle is free; a settings
            // screen with twelve more controls would let both be set against
            // each other with nothing to say so. Pasting a bench export applies
            // the set atomically and neutralises `carbSpread` with it.
            Text(
                stringResource(R.string.physio_tuning_section_carb_shape_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.physio_tuning_section_carb_shape_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (PhysioTuning.read(context).carbTriangles != null) {
                    stringResource(R.string.physio_tuning_section_triangles_custom)
                } else {
                    stringResource(R.string.physio_tuning_section_triangles_shipped)
                },
                style = MaterialTheme.typography.labelMedium,
            )
            // SHOWN AND EDITABLE. The first version could only accept a set,
            // never display one, so "which triangles are active right now" was
            // unanswerable from the phone. The objection to twelve fields was
            // that `carbSpread` would be set against them by hand — the applier
            // now neutralises it whenever a set is present, so what is left is
            // only the ordering, and that is clamped on save.
            val triangleLabels = androidx.compose.ui.res.stringArrayResource(R.array.physio_tuning_triangle_labels)
            triFields.chunked(4).forEachIndexed { row, group ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.forEachIndexed { col, value ->
                        val idx = row * 4 + col
                        OutlinedTextField(
                            value = value,
                            onValueChange = { nv ->
                                triFields = triFields.toMutableList().also { it[idx] = nv }
                            },
                            label = { Text(triangleLabels[idx], maxLines = 1) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            val statusNotAllFields = stringResource(R.string.physio_tuning_section_status_not_all_fields)
            val statusAppliedReordered = stringResource(R.string.physio_tuning_section_status_applied_reordered)
            val statusApplied = stringResource(R.string.physio_tuning_section_status_applied)
            val statusRevertedShipped = stringResource(R.string.physio_tuning_section_status_reverted_shipped)
            val statusParseFailed = stringResource(R.string.physio_tuning_section_status_parse_failed)
            val statusAppliedFromExport = stringResource(R.string.physio_tuning_section_status_applied_from_export)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val nums = triFields.mapNotNull { it.trim().replace(',', '.').toDoubleOrNull() }
                    if (nums.size != 12) {
                        triStatus = statusNotAllFields
                    } else {
                        val ordered = PhysioTuning.orderTriangles(nums)
                        PhysioTuning.setCarbTriangles(context, ordered)
                        spread = "1"
                        PhysioTuning.setCarbSpread(context, 1.0)
                        HybridModelStore.retune(context)
                        applied = PhysioTuning.summary(PhysioTuning.read(context), context)
                        triFields = ordered.map { "%.2f".format(it).trimEnd('0').trimEnd('.') }
                        triStatus = if (ordered != nums) statusAppliedReordered else statusApplied
                    }
                }) { Text(stringResource(R.string.physio_tuning_section_apply_fields_button)) }
                Button(onClick = {
                    PhysioTuning.setCarbTriangles(context, null)
                    HybridModelStore.retune(context)
                    applied = PhysioTuning.summary(PhysioTuning.read(context), context)
                    triFields = PhysioTuning.effectiveTriangles(context)
                        .map { "%.2f".format(it).trimEnd('0').trimEnd('.') }
                    triStatus = statusRevertedShipped
                }) { Text(stringResource(R.string.physio_tuning_section_reset_button)) }
            }
            OutlinedTextField(
                value = benchPaste,
                onValueChange = { benchPaste = it },
                label = { Text(stringResource(R.string.physio_tuning_section_paste_json_label)) },
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = benchPaste.isNotBlank(),
                    onClick = {
                        val tri = PhysioTuning.trianglesFromBenchExport(benchPaste)
                        if (tri == null) {
                            triStatus = statusParseFailed
                        } else {
                            PhysioTuning.setCarbTriangles(context, tri)
                            spread = "1"
                            PhysioTuning.setCarbSpread(context, 1.0)
                            HybridModelStore.retune(context)
                            applied = PhysioTuning.summary(PhysioTuning.read(context), context)
                            triFields = PhysioTuning.effectiveTriangles(context)
                                .map { "%.2f".format(it).trimEnd('0').trimEnd('.') }
                            triStatus = statusAppliedFromExport
                        }
                    },
                ) { Text(stringResource(R.string.physio_tuning_section_apply_set_button)) }
            }
            if (triStatus.isNotBlank()) {
                Text(triStatus, style = MaterialTheme.typography.labelMedium)
            }

            Text(
                stringResource(R.string.physio_tuning_section_reach_title),
                style = MaterialTheme.typography.titleSmall,
            )
            // WHICH SURFACES vs WHICH VALUE — two different questions, and this
            // card used to answer only the first while looking like it answered
            // both. There is one engine, so every surface reads the same model;
            // that does NOT mean the number typed here is the number that runs.
            //
            // A live check showed the hand-set shape and ISF diverging from
            // what was actually applied. The insulin LANDMARKS enter
            // `InsulinParameterResolverV1` as `priorLandmarks`, the lowest tier,
            // so a sufficiently large measured curve overrides them; the ISF
            // is a BASE that `closed_episode_global_isf` then scales by its
            // promoted effect. Both are by design and neither was visible here.
            listOf(
                Triple(R.string.physio_tuning_section_reach_forecast, R.string.physio_tuning_section_reach_yes, true),
                Triple(R.string.physio_tuning_section_reach_hypo_alert, R.string.physio_tuning_section_reach_yes, true),
                Triple(
                    R.string.physio_tuning_section_reach_watch_widget,
                    R.string.physio_tuning_section_reach_yes,
                    true,
                ),
                Triple(
                    R.string.physio_tuning_section_reach_insulin_timing,
                    R.string.physio_tuning_section_reach_timing_verdict,
                    false,
                ),
                Triple(R.string.physio_tuning_section_reach_isf, R.string.physio_tuning_section_reach_isf_verdict, false),
            ).forEach { (who, verdict, full) ->
                Text(
                    stringResource(R.string.physio_tuning_section_reach_row, stringResource(who), stringResource(verdict)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (full) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            Text(
                stringResource(R.string.physio_tuning_section_reach_log_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.physio_tuning_section_reach_model_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                stringResource(R.string.physio_tuning_section_trust_ramp_label, ramp),
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(value = ramp, onValueChange = { ramp = it }, valueRange = 0f..1f, steps = 19)
            Text(
                when {
                    ramp >= 0.99 -> stringResource(R.string.physio_tuning_section_ramp_full)
                    ramp <= 0.01 -> stringResource(R.string.physio_tuning_section_ramp_zero)
                    else -> stringResource(R.string.physio_tuning_section_ramp_partial, 100 * (1 - 0.5 * ramp))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = on,
                    onValueChange = { on = it },
                    label = { Text(stringResource(R.string.physio_tuning_section_label_onset)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = pk,
                    onValueChange = { pk = it },
                    label = { Text(stringResource(R.string.physio_tuning_section_label_full_speed)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = phase,
                    onValueChange = { phase = it },
                    label = { Text(stringResource(R.string.physio_tuning_section_label_phase)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = tail,
                    onValueChange = { tail = it },
                    label = { Text(stringResource(R.string.physio_tuning_section_label_tail)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.physio_tuning_section_curve_shape_explainer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = isf,
                    onValueChange = { isf = it },
                    label = { Text(stringResource(R.string.physio_tuning_section_label_isf)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = kcal,
                    onValueChange = { kcal = it },
                    label = { Text(stringResource(R.string.physio_tuning_section_label_kcal)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            // THE ALTERNATIVE, VISIBLE RATHER THAN MERELY SUPPRESSED.
            //
            // A hand-set ISF is final and the learner no longer
            // scales it. But a pinned number that cannot be compared with what
            // the body is currently saying is how one keeps trusting it long
            // after it stopped being true — so the learner keeps running and
            // its answer is shown here, with one tap to hand control back.
            val artifactIsf by androidx.compose.runtime.produceState<
                Triple<Double, Double?, Int>?
                >(null, isf) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val store = graph.store
                        val a = PhysioRuntime.artifact(
                            store, System.currentTimeMillis(),
                        ) ?: return@runCatching null
                        Triple(
                            a.globalIsf.median,
                            a.learnedIsfAlternative?.median,
                            a.learnedIsfAlternative?.identifyingEpisodes ?: 0,
                        )
                    }.getOrNull()
                }
            }
            // TWO NUMBERS, SIDE BY SIDE, AND ONE SWITCH.
            //
            // The request was for an adaptive computed number to sit next to
            // the manual-entry field, so the two could be compared and a
            // decision made. The two are computed by entirely different
            // evidence — the field is the user's own judgement, the adaptive
            // number is what would have made the last several days of the
            // user's own forecasts right — so putting them anywhere but next
            // to each other makes the choice unmeasurable by eye.
            val adaptive = remember(isf) {
                AdaptiveIsfRuntime.state(context)
            }
            var source by remember {
                mutableStateOf(IsfSource.choice(context))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (adaptive == null) {
                        pluralStringResource(
                            R.plurals.physio_tuning_section_adaptive_pending,
                            com.diapilot.core.physio.DailyBalanceIsfV1.MIN_DAYS,
                            com.diapilot.core.physio.DailyBalanceIsfV1.MIN_DAYS,
                            com.diapilot.core.physio.DailyBalanceIsfV1.WINDOW_DAYS,
                        )
                    } else {
                        // "days", not "episodes": the estimator's unit changed
                        // and a label naming the old unit would misdescribe
                        // what the number is counted from.
                        pluralStringResource(
                            R.plurals.physio_tuning_section_adaptive_ready,
                            adaptive.days,
                            adaptive.isf, adaptive.days,
                            com.diapilot.core.physio.DailyBalanceIsfV1.WINDOW_DAYS,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Switch(
                    checked = source == IsfSource.Choice.ADAPTIVE,
                    // A switch with nothing behind it would silently keep the
                    // hand value while claiming otherwise — see IsfSource.
                    enabled = adaptive != null,
                    onCheckedChange = { on ->
                        source = if (on) IsfSource.Choice.ADAPTIVE
                        else IsfSource.Choice.MANUAL
                        IsfSource.set(context, source)
                    },
                )
            }
            Text(
                if (source == IsfSource.Choice.ADAPTIVE) {
                    stringResource(R.string.physio_tuning_section_isf_source_adaptive_note)
                } else {
                    stringResource(R.string.physio_tuning_section_isf_source_manual_note)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            artifactIsf?.let { (applied, learned, episodes) ->
                Text(
                    stringResource(R.string.physio_tuning_section_isf_applied, applied),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (learned != null) {
                    Text(
                        // THE OFFER CARRIES A WARNING. On one real history a walk-forward
                        // check on a repaired bench run, paired by day, found
                        // the learner's value losing to the pinned one on
                        // every column. A control
                        // that offers a possibly worse number must say so,
                        // or it is an invitation dressed as information.
                        pluralStringResource(R.plurals.physio_tuning_section_isf_alternative, episodes, learned, episodes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            // Switch to adaptive = drop the pin. The learner then
                            // owns ISF again, from the next artifact build.
                            //
                            // THE PIN MOVED TO P1 and this button kept clearing
                            // the retired store, which made it a no-op —
                            // the worst possible failure for a control whose whole
                            // job is to hand ISF back. Timings are preserved: this
                            // button is about ISF only.
                            isf = ""
                            val kept = ManualInsulinRuntime.params(context)
                            ManualInsulinRuntime.setParams(
                                context, kept.copy(isfMmolPerU = null),
                            )
                            PhysioTuning.setIsfMmol(context, null)
                        }) { Text(stringResource(R.string.physio_tuning_section_switch_to_adaptive_button)) }
                        TextButton(onClick = {
                            isf = "%.3f".format(java.util.Locale.ROOT, learned)
                        }) { Text(stringResource(R.string.physio_tuning_section_pin_value_button)) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = sieve,
                    onValueChange = { sieve = it },
                    label = { Text(stringResource(R.string.physio_tuning_section_label_sieve)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = spread,
                    onValueChange = { spread = it },
                    label = { Text(stringResource(R.string.physio_tuning_section_label_spread)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { commit() }) { Text(stringResource(R.string.physio_tuning_section_apply_button)) }
                TextButton(
                    onClick = {
                        ramp = 1f
                        on = ""
                        pk = ""
                        phase = ""
                        tail = ""
                        isf = ""
                        kcal = ""
                        sieve = ""
                        spread = ""
                        offered = false
                        PhysioAutoFitController.clear()
                        PhysioTuning.reset(context)
                        HybridModelStore.retune(context)
                        applied = PhysioTuning.summary(PhysioTuning.read(context), context)
                    },
                ) { Text(stringResource(R.string.physio_tuning_section_revert_shipped_button)) }
            }

            Text(
                stringResource(R.string.physio_tuning_section_autofit_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.physio_tuning_section_autofit_explainer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.physio_tuning_section_autofit_lock_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(
                listOf(
                    "isf" to R.string.physio_tuning_section_axis_isf,
                    "onset" to R.string.physio_tuning_section_axis_onset,
                    "fullSpeed" to R.string.physio_tuning_section_axis_full_speed,
                    "phase" to R.string.physio_tuning_section_axis_phase,
                ),
                listOf(
                    "tail" to R.string.physio_tuning_section_axis_tail,
                    "kcal" to R.string.physio_tuning_section_axis_kcal,
                    "sieve" to R.string.physio_tuning_section_axis_sieve,
                    "spread" to R.string.physio_tuning_section_axis_spread,
                ),
                listOf(
                    "ramp" to R.string.physio_tuning_section_axis_ramp,
                    "fastShift" to R.string.physio_tuning_section_axis_fast_shift,
                ),
            ).forEach { group ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    group.forEach { (axis, label) ->
                        FilterChip(
                            selected = axis in locks,
                            onClick = {
                                locks = if (axis in locks) locks - axis else locks + axis
                            },
                            label = {
                                Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            }
            val running = fit as? PhysioAutoFitController.State.Running
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    R.string.physio_tuning_section_metric_shape to PhysioAutoFitV1.Metric.SHAPE,
                    R.string.physio_tuning_section_metric_balance to PhysioAutoFitV1.Metric.BALANCE,
                ).forEach { (name, metric) ->
                    Button(
                        // Disabled from the SHARED state, so it stays disabled
                        // across a tab switch, a rotation and a trip to another
                        // app — the run is no longer tied to this composition.
                        enabled = running == null,
                        onClick = {
                            offered = false
                            // Start from THE FIELDS, so a lock keeps the number
                            // the user is looking at rather than the committed one.
                            // Empty field = fall back to what is applied.
                            val applied = PhysioAutoFitRuntime.startingKnobs(context)
                            PhysioAutoFitController.start(
                                context, metric, limit = 10, locked = locks,
                                startOverride = applied?.copy(
                                    isf = num(isf) ?: applied.isf,
                                    onsetMin = num(on) ?: applied.onsetMin,
                                    fullSpeedMin = num(pk) ?: applied.fullSpeedMin,
                                    phaseMin = num(phase) ?: applied.phaseMin,
                                    tailMin = num(tail) ?: applied.tailMin,
                                    emptyingKcalPerHour = num(kcal) ?: applied.emptyingKcalPerHour,
                                    carbSieving = num(sieve) ?: applied.carbSieving,
                                    carbSpread = num(spread) ?: applied.carbSpread,
                                    trustRamp = ramp.toDouble(),
                                ),
                            )
                        },
                    ) { Text(stringResource(name)) }
                }
                if (running != null) {
                    TextButton(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.physio_tuning_section_running_indicator))
                    }
                }
            }

            when (val f = fit) {
                is PhysioAutoFitController.State.Running -> {
                    val label = stringResource(
                        if (f.metric == PhysioAutoFitV1.Metric.SHAPE) {
                            R.string.physio_tuning_section_metric_shape
                        } else {
                            R.string.physio_tuning_section_metric_balance
                        },
                    )
                    Text(
                        if (f.total == 0) {
                            stringResource(R.string.physio_tuning_section_running_collecting, label)
                        } else {
                            stringResource(R.string.physio_tuning_section_running_progress, label, f.done, f.total)
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (f.total == 0) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { f.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        stringResource(R.string.physio_tuning_section_running_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is PhysioAutoFitController.State.Done -> {
                    Text(describe(context, f.metric, f.batch), style = MaterialTheme.typography.labelMedium)

                    // WHAT IT BOUGHT, against what is applied today, on the same
                    // episodes. Both numbers are shown even when the fit made
                    // one of them worse — the search optimises ONE of them, and
                    // hiding the other is how "improvement" stops meaning
                    // anything.
                    HorizontalDivider()
                    val dShape = f.shapeAfter - f.shapeBefore
                    val dBias = f.biasAfter - f.biasBefore
                    Text(
                        f.corridor,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        pluralStringResource(R.plurals.physio_tuning_section_done_against_applied, f.batch.fits.size, f.batch.fits.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            R.string.physio_tuning_section_done_shape_bias,
                            f.shapeBefore, f.shapeAfter, dShape,
                            f.biasBefore, f.biasAfter, dBias,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        when {
                            dShape < -0.05 && dBias <= 0.05 ->
                                stringResource(R.string.physio_tuning_section_outcome_both_better)
                            dShape < -0.05 -> stringResource(R.string.physio_tuning_section_outcome_shape_better)
                            dBias < -0.05 && dShape > 0.05 ->
                                stringResource(R.string.physio_tuning_section_outcome_bias_better)
                            else -> stringResource(R.string.physio_tuning_section_outcome_noise)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // WHICH EPISODES, NAMED. A median over ten stretches the user
                    // cannot see is a number they have to take on trust, and the
                    // whole point of this card is that they do not have to.
                    // Each row is what the fitter actually scored, and tapping
                    // it puts the chart on that moment so the user can look.
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.physio_tuning_section_episodes_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    f.batch.fits.sortedByDescending { it.first.startMs }.forEach { (e, one) ->
                        EpisodeRow(e, one, onShowOnChart)
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            enabled = f.batch.fits.isNotEmpty(),
                            onClick = { offer(f.batch.median) },
                        ) { Text(stringResource(R.string.physio_tuning_section_load_into_fields_button)) }
                        TextButton(onClick = {
                            offered = false
                            PhysioAutoFitController.clear()
                        }) { Text(stringResource(R.string.physio_tuning_section_clear_button)) }
                    }
                }

                is PhysioAutoFitController.State.Failed ->
                    Text(f.note, style = MaterialTheme.typography.labelMedium)

                PhysioAutoFitController.State.Idle -> Unit
            }

            if (offered) {
                Text(
                    stringResource(R.string.physio_tuning_section_offered_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                stringResource(R.string.physio_tuning_section_measured_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.physio_tuning_section_not_measured_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One line of the auto-fit's evidence.
 *
 * It carries what was asked for and nothing else: WHEN, HOW LONG, WHAT HAPPENED
 * inside, and what this stretch alone wanted. The per-episode knobs are shown
 * beside the median deliberately — the spread between these rows is the reason
 * the median is quoted with a range, and a row that disagrees loudly with the
 * others is exactly the one worth opening on the chart.
 */
@Composable
private fun EpisodeRow(
    e: PhysioAutoFitV1.Episode,
    fit: PhysioAutoFitV1.Fit,
    onShowOnChart: (Long) -> Unit,
) {
    val fmt = remember { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()) }
    val hours = PhysioAutoFitRuntime.GRID.last() / 60.0
    val carbs = e.foods.sumOf { it.carbsG }
    val units = e.boluses.sumOf { it.units }
    // Relative minutes, because "+15 min" says what a clock time does not: how
    // the meal and the dose sat against each other inside the window.
    val events = buildList {
        e.foods.forEach {
            add(
                (it.tsMs - e.startMs) / 60_000 to
                    stringResource(R.string.physio_tuning_section_episode_food, it.carbsG),
            )
        }
        e.boluses.forEach {
            add(
                (it.tsMs - e.startMs) / 60_000 to
                    stringResource(R.string.physio_tuning_section_episode_units, it.units),
            )
        }
    }.sortedBy { it.first }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onShowOnChart(e.startMs) }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(
                R.string.physio_tuning_section_episode_summary,
                fmt.format(java.util.Date(e.startMs)), hours, carbs, units,
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        if (e.label.isNotBlank()) {
            Text(
                e.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val eventTexts = events.map {
            stringResource(R.string.physio_tuning_section_episode_offset, it.first, it.second)
        }
        Text(
            eventTexts.joinToString("  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.physio_tuning_section_episode_wants,
                fit.knobs.isf, fit.knobs.fullSpeedMin, fit.score.shape,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
