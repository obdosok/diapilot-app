package io.github.obdosok.diapilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
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
import com.diapilot.core.analysis.MGDL_PER_MMOL_F
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.FoodEraSettings
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.Units
import io.github.obdosok.diapilot.i18n.localized
import io.github.obdosok.diapilot.i18n.unitLabel
import kotlinx.coroutines.launch

/**
 * All user-tunable knobs in one place. Values are entered in the chosen
 * display units but stored in mmol/L (the storage truth).
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onShowOnChart: (Long) -> Unit = {}) {
    val context = LocalContext.current
    var mgdl by remember { mutableStateOf(Units.isMgdl(context)) }
    var watchServer by remember { mutableStateOf(Settings.watchServerEnabled(context)) }
    // The food-era start shown in the model card and edited next to the purge.
    // `foodEraUserSet` gates the purge: a first-run default never offers it.
    var foodEra by remember { mutableStateOf(FoodEraSettings.era(context)) }
    var foodEraUserSet by remember { mutableStateOf(FoodEraSettings.isUserSet(context)) }
    val foodEraLabel = "${foodEra.startDate.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))} " +
        foodEra.zone.id

    fun show(mmol: Double): String =
        if (mgdl) Math.round(mmol * MGDL_PER_MMOL_F).toString()
        else String.format(java.util.Locale.ROOT, "%.1f", mmol)

    fun parse(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()
            ?.let { if (mgdl) it / MGDL_PER_MMOL_F else it }
            ?.takeIf { it in 2.0..25.0 }

    var weightText by remember {
        mutableStateOf(Settings.weightKg(context)?.let { "%.0f".format(it) } ?: "")
    }
    var targetText by remember(mgdl) { mutableStateOf(show(Settings.targetMmol(context))) }
    var loText by remember(mgdl) { mutableStateOf(show(Settings.rangeLoMmol(context))) }
    var hiText by remember(mgdl) { mutableStateOf(show(Settings.rangeHiMmol(context))) }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_screen_title), style = MaterialTheme.typography.titleLarge)

        // The `no auto-teach` gate lives here rather than in a tab of its own:
        // it is a one-time confirmation flow, not something the user works with daily.
        FoodStructureAcceptanceSection(modifier = Modifier.fillMaxWidth())

        // High on the screen on purpose: this is the section under active tuning,
        // and it is also the section that can change when an alarm fires.
        PhysioTuningSection(modifier = Modifier.fillMaxWidth(), onShowOnChart = onShowOnChart)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_screen_personal_model_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                var modelStatus by remember {
                    mutableStateOf(
                        io.github.obdosok.diapilot.data.HybridModelStore.status(context),
                    )
                }
                var modelMessage by remember { mutableStateOf<String?>(null) }
                val modelScope = androidx.compose.runtime.rememberCoroutineScope()
                val modelImportLauncher =
                    androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) {
                            modelScope.launch {
                                modelMessage = context.localized().getString(R.string.settings_screen_model_checking)
                                try {
                                    val installed = kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.Dispatchers.IO,
                                    ) {
                                        context.contentResolver.openInputStream(uri).use { input ->
                                            requireNotNull(input) {
                                                context.localized().getString(R.string.settings_screen_file_unavailable)
                                            }
                                            io.github.obdosok.diapilot.data.HybridModelStore.install(
                                                context,
                                                input,
                                                "manual-import",
                                            )
                                        }
                                    }
                                    modelStatus = installed
                                    modelMessage = context.localized().getString(R.string.settings_screen_model_installed)
                                } catch (error: Exception) {
                                    modelMessage =
                                        context.localized().getString(R.string.settings_screen_model_rejected, error.message)
                                }
                            }
                        }
                    }
                modelStatus?.let { status ->
                    Text(
                        stringResource(
                            R.string.settings_screen_model_status_line,
                            status.modelVersion,
                            status.trainingDays,
                            status.trainedThrough ?: "â€”",
                            status.eligibleFrom,
                            status.eligibleTimezone,
                            status.activeSha256.take(12),
                            status.source,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: Text(
                    stringResource(R.string.settings_screen_model_not_initialized),
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        modelImportLauncher.launch(
                            arrayOf("application/json", "text/json", "*/*"),
                        )
                    }) {
                        Text(stringResource(R.string.settings_screen_import_json))
                    }
                    TextButton(
                        enabled = modelStatus?.previousSha256 != null,
                        onClick = {
                            modelScope.launch {
                                val restored = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) {
                                    io.github.obdosok.diapilot.data.HybridModelStore.rollback(context)
                                }
                                if (restored == null) {
                                    modelMessage =
                                        context.localized().getString(R.string.settings_screen_no_previous_generation)
                                } else {
                                    modelStatus = restored
                                    modelMessage =
                                        context.localized().getString(R.string.settings_screen_model_rollback_done)
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.settings_screen_rollback))
                    }
                }
                Text(
                    stringResource(R.string.settings_screen_model_accept_note, foodEraLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                modelMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        io.github.obdosok.diapilot.i18n.LanguageCard(modifier = Modifier.fillMaxWidth())

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_screen_units_title), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !mgdl,
                        onClick = { Units.setMgdl(context, false); mgdl = false },
                        label = { Text(unitLabel(mgdl = false)) },
                    )
                    FilterChip(
                        selected = mgdl,
                        onClick = { Units.setMgdl(context, true); mgdl = true },
                        label = { Text(unitLabel(mgdl = true)) },
                    )
                }
                Text(
                    stringResource(R.string.settings_screen_units_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_screen_body_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { t ->
                        weightText = t
                        Settings.setWeightKg(context, t.replace(',', '.').trim().toDoubleOrNull())
                    },
                    label = { Text(stringResource(R.string.settings_screen_weight_label)) },
                    supportingText = {
                        // The number is shown because a prior the user cannot
                        // see is a prior the user cannot disagree with â€” and the
                        // user's own measured value is the thing that should
                        // eventually replace it.
                        val cs = com.diapilot.core.analysis.CarbSensitivityPriorV1
                            .fromWeight(Settings.weightKg(context))
                        Text(stringResource(R.string.settings_screen_weight_supporting, cs))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_screen_target_range_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { t ->
                        targetText = t
                        parse(t)?.let { Settings.setTargetMmol(context, it) }
                    },
                    label = { Text(stringResource(R.string.settings_screen_target_label, unitLabel(mgdl))) },
                    supportingText = {
                        Text(stringResource(R.string.settings_screen_target_supporting))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = loText,
                        onValueChange = { t ->
                            loText = t
                            parse(t)?.let { Settings.setRangeLoMmol(context, it) }
                        },
                        label = { Text(stringResource(R.string.settings_screen_range_lo_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = hiText,
                        onValueChange = { t ->
                            hiText = t
                            parse(t)?.let { Settings.setRangeHiMmol(context, it) }
                        },
                        label = { Text(stringResource(R.string.settings_screen_range_hi_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    stringResource(R.string.settings_screen_range_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_screen_insulin_curve_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                // This card describes the model the forecast actually runs. It
                // used to read the bundled artifact unconditionally, so it
                // showed the shipped ISF and timing even when the
                // measured curve was in charge â€” and gave no way to tell a
                // learned value from a hardcoded one. Reading the model touches
                // SQLite, so it is produced off the composition thread.
                val insulinView by androidx.compose.runtime.produceState<InsulinCardView?>(null) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val store = io.github.obdosok.diapilot.data.Stores.get(context)
                        val now = System.currentTimeMillis()
                        // The SAME object the forecast runs on â€” not a second
                        // reading of the same question. See InsulinProfileRuntime.
                        val profileState = (store as? io.github.obdosok.diapilot.data.SqliteCollectorStore)
                            ?.let { sq ->
                                runCatching {
                                    io.github.obdosok.diapilot.data.InsulinProfileRuntime
                                        .state(store, sq.readableDatabase, now)
                                }.getOrNull()
                            }
                        val artifact =
                            runCatching { io.github.obdosok.diapilot.data.PhysioRuntime.artifact(store, now) }.getOrNull()
                        val model = artifact?.let {
                            val hour = java.util.Calendar.getInstance()
                                .get(java.util.Calendar.HOUR_OF_DAY).toDouble()
                            runCatching { it.personModelAt(hour) }.getOrNull()
                        } ?: io.github.obdosok.diapilot.data.HybridRuntimeMetrics.model()
                        // The ISF-deviations card was removed along with the episode
                        // pipeline it used to read from.
                        val deviations = emptyList<String>()
                        InsulinCardView(
                            model, artifact, profileState?.curve, deviations,
                            profileState?.let { io.github.obdosok.diapilot.data.InsulinProfileRuntime.explain(it, context) },
                            profileState?.refusals.orEmpty(),
                        )
                    }
                }
                val insulinModel = insulinView?.model
                val physioArtifact = insulinView?.artifact
                val measuredCurve = insulinView?.curve
                // What the data says about ISF, for the divergence line only.
                // Reported only once it rests on closed corrections; the bare
                // prior is not a second opinion worth showing next to the user's own.
                val measuredIsf = physioArtifact?.globalIsf
                    ?.takeIf { it.identifyingEpisodes > 0 }?.median
                val insulinScope = androidx.compose.runtime.rememberCoroutineScope()
                var rescanTick by remember { mutableStateOf(0) }
                Text(
                    stringResource(R.string.settings_screen_insulin_engine_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (insulinModel != null) {
                    val insulinLandmarks=com.diapilot.core.hybrid.HybridForecastEngine(insulinModel).insulinLandmarks()
                    val isf = if (mgdl) {
                        insulinModel.insulin.isf * MGDL_PER_MMOL_F
                    } else {
                        insulinModel.insulin.isf
                    }
                    // "PEAK" NAMED TWO DIFFERENT QUANTITIES HERE, three lines
                    // apart, and a user noticed: the measurement below says
                    // "peak 51" while this line says "peak 67".
                    //
                    // Both are correct. `ratePeakMin` is the TOP of the rate hump;
                    // the measured "peak" is the minute the curve reaches FULL
                    // rate, i.e. the start of the plateau. On synthetic data they
                    // diverge exactly this way: an input of 55 reads back as a
                    // peak of 66 (see the `peakmeaning` test bench).
                    // The numbers were never in disagreement â€” the labels were.
                    Text(
                        String.format(
                            java.util.Locale.ROOT,
                            stringResource(R.string.settings_screen_isf_line),
                            isf,
                            unitLabel(mgdl),
                            insulinLandmarks.onsetMin,
                            insulinLandmarks.ratePeakMin,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.settings_screen_isf_explain),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        String.format(
                            java.util.Locale.ROOT,
                            stringResource(R.string.settings_screen_main_curve_line),
                            insulinModel.insulin.shortDurationMin,
                            insulinLandmarks.effectEndMin,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Drawn by SAMPLING THE ENGINE, not by re-deriving a curve
                    // from the landmark numbers above. Whatever is in force â€”
                    // hand-entered, measured, or the parametric prior â€” this is
                    // the shape the forecast, IOB, What-if and the meal
                    // deconvolution all run, because they read the same model.
                    run {
                        val engine = com.diapilot.core.hybrid.HybridForecastEngine(insulinModel)
                        val end = insulinLandmarks.effectEndMin.coerceIn(60.0, 720.0)
                        val sampled = (0..(end / 5.0).toInt()).map { i ->
                            val t = i * 5.0
                            com.diapilot.core.hybrid.HybridCdfKnot(t, engine.insulinCdf(t))
                        }
                        InsulinShapeSpark(
                            sampled,
                            insulinLandmarks.onsetMin,
                            insulinLandmarks.ratePeakMin,
                            Modifier.fillMaxWidth(),
                        )
                    }
                    // Provenance, one line each. Without this the card cannot
                    // answer "are these numbers measured or hardcoded" â€” and a
                    // prior that silently looks like a measurement is worse than
                    // no number.
                    val measuredShape = insulinModel.insulin.actionCdfKnots.isNotEmpty()
                    Text(
                        if (measuredShape) stringResource(R.string.settings_screen_shape_measured)
                        else stringResource(R.string.settings_screen_shape_prior),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (measuredShape) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    // What the profile is, in the same numbers the model runs on.
                    insulinView?.profileLine?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (measuredShape) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // And who was refused, because a "perfectly compensated
                    // meal" is a silent zero otherwise.
                    insulinView?.refusals.orEmpty().entries.sortedByDescending { it.value }
                        .take(3).forEach { (reason, n) ->
                            Text(
                                stringResource(
                                    R.string.settings_screen_refusal_line,
                                    io.github.obdosok.diapilot.i18n.PhysioText.landmarkRefusal(context, reason),
                                    n,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    if (measuredCurve != null && !measuredShape) {
                        Text(
                            stringResource(R.string.settings_screen_curve_not_applied),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // WHY the count is what it is. "0 of 4" alone is
                    // indistinguishable from a broken gate â€” it took several
                    // install-and-pull rounds to find out which gate was
                    // holding, and every number below was already computed and
                    // stored, just never shown.
                        // ---- P1: manually entered -------------------------------
                    // Precedence: what the user enters wins over what the app
                    // measures, and the app may then only REPORT that its own
                    // numbers differ. This is also the whole answer to cold
                    // start â€” somebody two days in has no measured curve, and
                    // "wait a month" is not an answer to a user who already
                    // knows when their insulin starts.
                    var manualTick by remember { mutableStateOf(0) }
                    val stored = remember(manualTick) {
                        io.github.obdosok.diapilot.data.ManualInsulinRuntime.params(context)
                    }
                    fun show(v: Double?) = v?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: ""
                    var onsetIn by remember(manualTick) { mutableStateOf(show(stored.onsetMin)) }
                    var peakIn by remember(manualTick) { mutableStateOf(show(stored.peakMin)) }
                    var plateauIn by remember(manualTick) { mutableStateOf(show(stored.plateauEndMin)) }
                    var tailIn by remember(manualTick) { mutableStateOf(show(stored.tailMin)) }
                    var isfIn by remember(manualTick) {
                        mutableStateOf(stored.isfMmolPerU?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "")
                    }
                    var manualNote by remember { mutableStateOf<String?>(null) }
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_screen_manual_set_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_screen_manual_set_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Four landmarks, not three. The most active phase is
                        // an interval â€” "from 30 to 70, then it tapers off" â€”
                        // and a single peak forces the rate to turn over the
                        // moment it arrives, which is the triangle again.
                        listOf(
                            Triple(stringResource(R.string.settings_screen_onset_label), onsetIn, { v: String -> onsetIn = v }),
                            Triple(stringResource(R.string.settings_screen_active_from_label), peakIn, { v: String -> peakIn = v }),
                            Triple(stringResource(R.string.settings_screen_active_to_label), plateauIn, { v: String -> plateauIn = v }),
                            Triple(stringResource(R.string.settings_screen_end_label), tailIn, { v: String -> tailIn = v }),
                        ).forEach { (label, value, set) ->
                            OutlinedTextField(
                                value = value, onValueChange = set,
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = isfIn, onValueChange = { isfIn = it },
                        label = { Text("ISF") },
                        suffix = { Text(stringResource(R.string.settings_screen_isf_unit_suffix, unitLabel(mgdl))) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.material3.TextButton(onClick = {
                            fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
                            // Entry is in the unit the card displays; the model
                            // stores mmol/L. Skipping this made a mg/dl user
                            // enter 45 and get an ISF of 45 mmol.
                            val isf = num(isfIn)?.let { if (mgdl) it / MGDL_PER_MMOL_F else it }
                            val entered = com.diapilot.core.physio.ManualInsulinParamsV1(
                                onsetMin = num(onsetIn), peakMin = num(peakIn),
                                plateauEndMin = num(plateauIn),
                                tailMin = num(tailIn), isfMmolPerU = isf,
                            )
                            io.github.obdosok.diapilot.data.ManualInsulinRuntime.setParams(context, entered)
                            manualTick++
                            manualNote = if (entered.any) {
                                context.localized().getString(R.string.settings_screen_saved_recalculating)
                            } else {
                                context.localized().getString(R.string.settings_screen_cleared)
                            }
                        }) { Text(stringResource(R.string.settings_screen_apply)) }
                        androidx.compose.material3.TextButton(onClick = {
                            io.github.obdosok.diapilot.data.ManualInsulinRuntime.setParams(
                                context, com.diapilot.core.physio.ManualInsulinParamsV1.EMPTY,
                            )
                            manualTick++
                            manualNote = context.localized().getString(R.string.settings_screen_cleared_reverted)
                        }) { Text(stringResource(R.string.settings_screen_reset)) }
                    }
                    // The rejection is shown, never a silent clamp: a value
                    // quietly moved into range would teach the user that the
                    // field does something it does not.
                    val manualResolution = remember(manualTick, insulinModel, measuredCurve, measuredIsf) {
                        runCatching {
                            io.github.obdosok.diapilot.data.ManualInsulinRuntime.resolve(
                                insulinModel, stored, measuredCurve, measuredIsf,
                            )
                        }.getOrNull()
                    }
                    manualResolution?.rejected?.forEach {
                        Text(
                            when (it) {
                                com.diapilot.core.physio.InsulinParameterResolverV1.SHAPE_OUT_OF_DOMAIN ->
                                    stringResource(R.string.settings_screen_shape_rejected)
                                com.diapilot.core.physio.InsulinParameterResolverV1.ISF_OUT_OF_DOMAIN ->
                                    stringResource(R.string.settings_screen_isf_rejected)
                                else -> stringResource(R.string.settings_screen_curve_build_failed)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // The only channel left open once P1 is in force.
                    manualResolution?.divergences?.forEach { d ->
                        val unit = if (d.field == com.diapilot.core.physio.ManualInsulinParamsV1.ISF)
                            stringResource(R.string.settings_screen_isf_unit_suffix, unitLabel(mgdl))
                        else stringResource(R.string.settings_screen_unit_min)
                        val k = if (d.field == com.diapilot.core.physio.ManualInsulinParamsV1.ISF && mgdl)
                            MGDL_PER_MMOL_F else 1.0
                        val name = when (d.field) {
                            com.diapilot.core.physio.ManualInsulinParamsV1.ONSET ->
                                stringResource(R.string.settings_screen_onset_label)
                            com.diapilot.core.physio.ManualInsulinParamsV1.PEAK ->
                                stringResource(R.string.settings_screen_active_phase_start_name)
                            com.diapilot.core.physio.ManualInsulinParamsV1.PLATEAU_END ->
                                stringResource(R.string.settings_screen_active_phase_end_name)
                            com.diapilot.core.physio.ManualInsulinParamsV1.TAIL ->
                                stringResource(R.string.settings_screen_end_label)
                            else -> "ISF"
                        }
                        Text(
                            String.format(
                                java.util.Locale.ROOT,
                                stringResource(R.string.settings_screen_divergence_line),
                                name, d.measured * k, unit, d.manual * k,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    manualNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()

                    // ---- P2: review of flagged corrections ---------------------
                    // A number without its objections is not reviewable. The
                    // card could previously only say "episodes 0 of 4", which
                    // is indistinguishable from a broken gate â€” and was one,
                    // more than once.
                    // The "Your corrections" card was removed: manual dose
                    // judging was used only rarely, and the corpus it gated no
                    // longer feeds the model â€” amplitude comes from the daily
                    // balance instead. A tool nobody uses and that affects
                    // nothing is not an observation, it is decoration.

                    val isfEpisodes = physioArtifact?.globalIsf?.identifyingEpisodes ?: 0
                    Text(
                        if (isfEpisodes > 0) {
                            stringResource(R.string.settings_screen_isf_measured, isfEpisodes)
                        } else {
                            stringResource(R.string.settings_screen_isf_prior)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isfEpisodes > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_screen_engine_backup_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.settings_screen_model_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.settings_screen_manual_values_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_screen_alerts_title), style = MaterialTheme.typography.titleMedium)
                var hypoAlert by remember { mutableStateOf(Settings.hypoAlertEnabled(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_screen_hypo_forecast_label))
                    Switch(
                        checked = hypoAlert,
                        onCheckedChange = {
                            hypoAlert = it
                            Settings.setHypoAlertEnabled(context, it)
                        },
                    )
                }
                Text(
                    stringResource(R.string.settings_screen_hypo_forecast_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Alarm-stream sound (heard on silent, like xDrip) + night
                // gentle-then-escalate. Only shown when hypo alerts are on.
                if (hypoAlert) {
                    var hypoSound by remember { mutableStateOf(Settings.hypoAlertSound(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_screen_alert_sound_label))
                        Switch(
                            checked = hypoSound,
                            onCheckedChange = { hypoSound = it; Settings.setHypoAlertSound(context, it) },
                        )
                    }
                    var nightGentle by remember { mutableStateOf(Settings.hypoNightGentle(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_screen_night_gentle_label))
                        Switch(
                            checked = nightGentle,
                            onCheckedChange = { nightGentle = it; Settings.setHypoNightGentle(context, it) },
                        )
                    }
                    Text(
                        stringResource(R.string.settings_screen_alert_sound_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        val hasVib = io.github.obdosok.diapilot.collect.AlarmPlayer.hasVibrator(context)
                        io.github.obdosok.diapilot.collect.AlarmPlayer.alarm(
                            context, withSound = Settings.hypoAlertSound(context), seconds = 3,
                        )
                        android.widget.Toast.makeText(
                            context,
                            if (hasVib) context.localized().getString(R.string.settings_screen_signal_sent)
                            else context.localized().getString(R.string.settings_screen_vibrator_unavailable),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text(stringResource(R.string.settings_screen_check_signal)) }

                    var dexSnooze by remember { mutableStateOf(Settings.hypoDextroseSnooze(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_screen_dextrose_snooze_label))
                        Switch(
                            checked = dexSnooze,
                            onCheckedChange = { dexSnooze = it; Settings.setHypoDextroseSnooze(context, it) },
                        )
                    }
                    val minUnit = stringResource(R.string.settings_screen_unit_min)
                    if (dexSnooze) {
                        var snoozeMin by remember { mutableStateOf(Settings.hypoSnoozeMin(context)) }
                        StepperRow(
                            stringResource(R.string.settings_screen_dextrose_window_label),
                            snoozeMin, minUnit, 5, 45, 5,
                        ) {
                            snoozeMin = it; Settings.setHypoSnoozeMin(context, it)
                        }
                    }
                    var lowRefire by remember { mutableStateOf(Settings.hypoLowRefireMin(context)) }
                    StepperRow(
                        stringResource(R.string.settings_screen_low_refire_label),
                        lowRefire, minUnit, 3, 30, 1,
                    ) {
                        lowRefire = it; Settings.setHypoLowRefireMin(context, it)
                    }
                    var persistMin by remember { mutableStateOf(Settings.hypoPersistMin(context)) }
                    StepperRow(
                        stringResource(R.string.settings_screen_persist_label),
                        persistMin, minUnit, 10, 60, 5,
                    ) {
                        persistMin = it; Settings.setHypoPersistMin(context, it)
                    }
                    Text(
                        stringResource(R.string.settings_screen_hypo_alert_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                var hyperAlert by remember { mutableStateOf(Settings.hyperAlertEnabled(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_screen_hyper_forecast_label))
                    Switch(
                        checked = hyperAlert,
                        onCheckedChange = {
                            hyperAlert = it
                            Settings.setHyperAlertEnabled(context, it)
                        },
                    )
                }
                Text(
                    stringResource(R.string.settings_screen_hyper_forecast_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var protocol by remember { mutableStateOf(Settings.hypoProtocol(context) ?: "") }
                OutlinedTextField(
                    value = protocol,
                    onValueChange = {
                        protocol = it
                        Settings.setHypoProtocol(context, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_screen_hypo_plan_label)) },
                    placeholder = { Text(stringResource(R.string.settings_screen_hypo_plan_placeholder)) },
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.settings_screen_hypo_plan_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_screen_libre_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                // Libre NFC scan: opt-in, because the OOP2 decode reply is a
                // broadcast every listening app receives (see
                // Settings.libreNfcEnabled). Own BLE needs it: the keys come
                // from a scan.
                var libreNfc by remember { mutableStateOf(Settings.libreNfcEnabled(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_screen_libre_nfc_label),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = libreNfc,
                        onCheckedChange = {
                            libreNfc = it
                            Settings.setLibreNfcEnabled(context, it)
                        },
                    )
                }
                Text(
                    stringResource(R.string.settings_screen_libre_nfc_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var ownBle by remember { mutableStateOf(Settings.ownBleEnabled(context)) }
                val btPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        Settings.setOwnBleEnabled(context, true)
                        ownBle = true
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_screen_own_ble_label))
                    Switch(
                        checked = ownBle,
                        onCheckedChange = { on ->
                            if (on && android.os.Build.VERSION.SDK_INT >= 31 &&
                                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                btPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                Settings.setOwnBleEnabled(context, on)
                                ownBle = on
                            }
                        },
                    )
                }
                val bleState = remember(ownBle) {
                    io.github.obdosok.diapilot.data.Libre2State.load(context)
                }
                Text(
                    if (bleState != null) {
                        stringResource(
                            R.string.settings_screen_libre_state_info,
                            bleState.serial, bleState.mac, bleState.connectionIndex, bleState.unlockArray.size,
                        )
                    } else {
                        stringResource(R.string.settings_screen_libre_no_keys)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.settings_screen_libre_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var diagTick by remember { mutableStateOf(0) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_screen_diagnostics_title), style = MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.TextButton(onClick = { diagTick++ }) { Text("ðŸ”„") }
                }
                var diag by remember { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(diagTick) {
                    diag = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val now = System.currentTimeMillis()
                        val store = io.github.obdosok.diapilot.data.Stores.get(context)
                        fun age(ts: Long): String =
                            if (ts <= 0) "â€”" else context.localized().getString(R.string.settings_screen_age_minutes_ago, (now - ts) / 60_000)
                        val d = io.github.obdosok.diapilot.collect.DiagState
                        val lastMain = store.lastReading()
                        val lastMinute = store.lastMinuteReading()
                        val cal = io.github.obdosok.diapilot.data.MinuteCalCache.get(store, context)
                        buildList {
                            add(
                                context.localized().getString(
                                    R.string.settings_screen_ble_line,
                                    d.bleStatus.ifBlank { context.localized().getString(R.string.libre_ble_client_status_off) },
                                    age(d.bleLastPacketMs),
                                ),
                            )
                            add(
                                context.localized().getString(
                                    R.string.settings_screen_main_reading_line,
                                    age(lastMain?.tsMs ?: 0),
                                    lastMain?.source ?: "â€”",
                                ),
                            )
                            add(context.localized().getString(R.string.settings_screen_minute_stream_line, age(lastMinute?.tsMs ?: 0)))
                            add(
                                cal?.let {
                                    context.localized().getString(
                                        R.string.settings_screen_calibration_line,
                                        it.slope, it.intercept, it.n,
                                    )
                                } ?: context.localized().getString(R.string.settings_screen_calibration_none),
                            )
                            add(
                                io.github.obdosok.diapilot.data.Settings.libreSensorStartMs(context)
                                    .takeIf { it > 0 }
                                    ?.let {
                                        context.localized().getString(
                                            R.string.settings_screen_sensor_line_with_day,
                                            io.github.obdosok.diapilot.data.Settings.libreSensorSerial(context) ?: "â€”",
                                            (now - it) / 86_400_000.0,
                                        )
                                    }
                                    ?: context.localized().getString(
                                        R.string.settings_screen_sensor_line,
                                        io.github.obdosok.diapilot.data.Settings.libreSensorSerial(context) ?: "â€”",
                                    ),
                            )
                            add(context.localized().getString(R.string.settings_screen_service_started_line, age(d.serviceStartedMs)))
                            add(
                                context.localized().getString(
                                    R.string.settings_screen_watch_server_status_line,
                                    if (d.watchServerUp) {
                                        context.localized().getString(R.string.settings_screen_watch_server_running)
                                    } else {
                                        context.localized().getString(R.string.settings_screen_watch_server_off)
                                    },
                                ),
                            )
                            add(context.localized().getString(R.string.settings_screen_xdrip_broadcast_line, age(d.lastXdripBroadcastMs)))
                            add(
                                context.localized().getString(
                                    R.string.settings_screen_pairs_stage4_line,
                                    io.github.obdosok.diapilot.collect.Libre2PairLog.stats(context),
                                ),
                            )
                        }
                    }
                }
                diag.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_screen_data_title), style = MaterialTheme.typography.titleMedium)
                // Daily copy into the public Downloads folder: opt-in, so a
                // second installed copy of the app never writes shared storage
                // on its own (see Settings.DEFAULT_AUTO_BACKUP).
                var autoBackup by remember { mutableStateOf(Settings.autoBackupEnabled(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_screen_auto_backup_label),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = autoBackup,
                        onCheckedChange = {
                            autoBackup = it
                            Settings.setAutoBackupEnabled(context, it)
                        },
                    )
                }
                var exportStatus by remember { mutableStateOf<String?>(null) }
                val exportScope = androidx.compose.runtime.rememberCoroutineScope()
                androidx.compose.material3.TextButton(onClick = {
                    exportStatus = context.localized().getString(R.string.settings_screen_exporting)
                    exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        exportStatus = try {
                            val name = io.github.obdosok.diapilot.AppIdentity.manualExportName(
                                java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT)
                                    .format(java.util.Date()),
                            )
                            val store = io.github.obdosok.diapilot.data.Stores.get(context)
                                as io.github.obdosok.diapilot.data.SqliteCollectorStore
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                val values = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                                    put(
                                        android.provider.MediaStore.Downloads.MIME_TYPE,
                                        "application/octet-stream",
                                    )
                                }
                                val uri = context.contentResolver.insert(
                                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                                ) ?: error(context.localized().getString(R.string.settings_screen_export_create_failed))
                                context.contentResolver.openOutputStream(uri)!!.use {
                                    store.exportSnapshot(it)
                                }
                                context.localized().getString(R.string.settings_screen_export_saved_downloads, name)
                            } else {
                                val f = java.io.File(
                                    context.getExternalFilesDir(null), name,
                                )
                                f.outputStream().use { store.exportSnapshot(it) }
                                context.localized().getString(R.string.settings_screen_export_saved_path, f.path)
                            }
                        } catch (e: Exception) {
                            context.localized().getString(R.string.settings_screen_export_error, e.message)
                        }
                    }
                }) { Text(stringResource(R.string.settings_screen_export_database)) }
                exportStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // --- The food-era start: the first day learning may read. It
                // defaults to the first-run date; moving it is the user's call.
                var purgePreview by remember {
                    mutableStateOf<io.github.obdosok.diapilot.data.PreEraPurge.Result?>(null)
                }
                var purgeStatus by remember { mutableStateOf<String?>(null) }
                var purging by remember { mutableStateOf(false) }
                Text(
                    stringResource(R.string.settings_screen_era_start_hint, foodEraLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.TextButton(
                    enabled = !purging,
                    onClick = {
                        val today = java.time.LocalDate.now()
                        android.app.DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                // Off the main thread: it discards the twin, whose
                                // lock a running build may hold for seconds.
                                exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        foodEra = FoodEraSettings.setByUser(
                                            context, java.time.LocalDate.of(y, m + 1, d),
                                        )
                                        foodEraUserSet = true
                                        purgePreview = null
                                        purgeStatus = null
                                    } catch (e: IllegalArgumentException) {
                                        purgeStatus = context.localized().getString(R.string.settings_screen_era_date_future_error)
                                    }
                                }
                            },
                            foodEra.startDate.year,
                            foodEra.startDate.monthValue - 1,
                            foodEra.startDate.dayOfMonth,
                        ).apply {
                            datePicker.maxDate = today.atStartOfDay(java.time.ZoneId.systemDefault())
                                .plusDays(1).toInstant().toEpochMilli() - 1
                        }.show()
                    },
                ) { Text(stringResource(R.string.settings_screen_change_era_start)) }
                // --- Purge everything recorded before the CHOSEN era start.
                // Offered only after the user has moved the start (a first-run
                // default never makes history deletable), and deliberately
                // manual: VACUUM locks a large file, and the decision is the
                // user's, not a background job's. See PreEraPurge for WHY.
                if (foodEraUserSet) androidx.compose.material3.TextButton(
                    enabled = !purging,
                    onClick = {
                        purgeStatus = null
                        exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val boundary = io.github.obdosok.diapilot.data.PreEraPurge.boundary(context)
                                ?: return@launch
                            val store = io.github.obdosok.diapilot.data.Stores.get(context)
                                as io.github.obdosok.diapilot.data.SqliteCollectorStore
                            purgePreview = io.github.obdosok.diapilot.data.PreEraPurge.preview(store, boundary)
                        }
                    },
                ) { Text(stringResource(R.string.settings_screen_purge_before_era, foodEraLabel)) }
                purgeStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                purgePreview?.let { preview ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { purgePreview = null },
                        title = {
                            Text(pluralStringResource(R.plurals.settings_screen_purge_rows_title, preview.total, preview.total))
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(R.string.settings_screen_purge_confirm_text, foodEraLabel),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                preview.tables.forEach {
                                    Text(
                                        "${it.table}: ${it.rows}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text(
                                    stringResource(R.string.settings_screen_purge_export_first),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                purgePreview = null
                                purging = true
                                purgeStatus = context.localized().getString(R.string.settings_screen_purging)
                                exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    purgeStatus = try {
                                        val store = io.github.obdosok.diapilot.data.Stores.get(context)
                                            as io.github.obdosok.diapilot.data.SqliteCollectorStore
                                        val boundary = requireNotNull(
                                            io.github.obdosok.diapilot.data.PreEraPurge.boundary(context),
                                        ) { context.localized().getString(R.string.settings_screen_purge_era_not_selected) }
                                        val r = io.github.obdosok.diapilot.data.PreEraPurge.purge(store, boundary)
                                        context.resources.getQuantityString(
                                            R.plurals.settings_screen_purge_result,
                                            r.total, r.total, r.freedBytes / 1_048_576,
                                        )
                                    } catch (e: Exception) {
                                        context.localized().getString(R.string.settings_screen_purge_error, e.message)
                                    }
                                    purging = false
                                }
                            }) { Text(stringResource(R.string.settings_screen_delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { purgePreview = null }) { Text(stringResource(R.string.settings_screen_cancel)) }
                        },
                    )
                }
                // --- Restore: the "new phone" path. Confirmed, validated,
                // then the process restarts so every handle re-opens the DB.
                var restoreUri by remember {
                    mutableStateOf<android.net.Uri?>(null)
                }
                var restoreStatus by remember { mutableStateOf<String?>(null) }
                val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                ) { uri -> if (uri != null) restoreUri = uri }
                androidx.compose.material3.TextButton(onClick = {
                    restoreLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*"))
                }) { Text(stringResource(R.string.settings_screen_restore_backup)) }
                restoreStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                restoreUri?.let { uri ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { restoreUri = null },
                        title = { Text(stringResource(R.string.settings_screen_replace_all_data_title)) },
                        text = {
                            Text(stringResource(R.string.settings_screen_replace_all_data_text))
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                restoreUri = null
                                restoreStatus = context.localized().getString(R.string.settings_screen_restoring)
                                exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        restoreStatus = io.github.obdosok.diapilot.data.BackupRestore
                                            .restore(context, uri)
                                        kotlinx.coroutines.delay(1500)   // let the text render
                                        android.os.Process.killProcess(android.os.Process.myPid())
                                    } catch (e: Exception) {
                                        restoreStatus = context.localized().getString(R.string.settings_screen_restore_error, e.message)
                                    }
                                }
                            }) { Text(stringResource(R.string.settings_screen_replace)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { restoreUri = null },
                            ) { Text(stringResource(R.string.settings_screen_cancel)) }
                        },
                    )
                }
                // --- Companion server: live remote dashboard + backup target.
                var compUrl by remember {
                    mutableStateOf(io.github.obdosok.diapilot.data.Settings.companionUrl(context) ?: "")
                }
                var compToken by remember {
                    mutableStateOf(io.github.obdosok.diapilot.data.Settings.companionToken(context) ?: "")
                }
                androidx.compose.material3.OutlinedTextField(
                    value = compUrl,
                    onValueChange = {
                        compUrl = it
                        io.github.obdosok.diapilot.data.Settings.setCompanionUrl(context, it)
                    },
                    label = { Text(stringResource(R.string.settings_screen_companion_url_label)) },
                    placeholder = { Text("http://host:8787") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = compToken,
                    onValueChange = {
                        compToken = it
                        io.github.obdosok.diapilot.data.Settings.setCompanionToken(context, it)
                    },
                    label = { Text(stringResource(R.string.settings_screen_companion_token_label)) },
                    singleLine = true,
                    // Masked, and a password keyboard: no suggestions, and the
                    // IME does not learn the token into its dictionary.
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.settings_screen_companion_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // --- Cloud folder: the daily copy also lands in a user-picked
                // SAF directory (e.g. Google Drive) â€” the cloud app syncs it,
                // the data never touches any third-party server of ours.
                var cloudUri by remember {
                    mutableStateOf(io.github.obdosok.diapilot.data.BackupRestore.cloudFolder(context))
                }
                val cloudLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                ) { uri ->
                    if (uri != null) {
                        io.github.obdosok.diapilot.data.BackupRestore.setCloudFolder(context, uri)
                        cloudUri = uri
                    }
                }
                androidx.compose.material3.TextButton(onClick = { cloudLauncher.launch(null) }) {
                    Text(
                        if (cloudUri == null) {
                            stringResource(R.string.settings_screen_cloud_folder_choose)
                        } else {
                            stringResource(
                                R.string.settings_screen_cloud_folder_configured,
                                cloudUri?.lastPathSegment
                                    ?: stringResource(R.string.settings_screen_cloud_configured_fallback),
                            )
                        },
                    )
                }
                if (cloudUri != null) {
                    androidx.compose.material3.TextButton(onClick = {
                        io.github.obdosok.diapilot.data.BackupRestore.setCloudFolder(context, null)
                        cloudUri = null
                    }) { Text(stringResource(R.string.settings_screen_disable_cloud_copy)) }
                }
                Text(
                    stringResource(R.string.settings_screen_backup_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_screen_watch_title), style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_screen_watch_server_label))
                    Switch(
                        checked = watchServer,
                        onCheckedChange = {
                            watchServer = it
                            Settings.setWatchServerEnabled(context, it)
                        },
                    )
                }
                Text(
                    stringResource(R.string.settings_screen_watch_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (watchServer) {
                    TextButton(onClick = {
                        io.github.obdosok.diapilot.collect.HypoAlertNotifier.postWatchTest(context)
                        android.widget.Toast.makeText(
                            context,
                            context.localized().getString(R.string.settings_screen_watch_test_toast),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }) { Text(stringResource(R.string.settings_screen_watch_test_vibro)) }
                    Text(
                        stringResource(R.string.settings_screen_watch_test_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    stringResource(R.string.settings_screen_watch_dim_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                var dimMode by remember { mutableStateOf(Settings.watchNightDimMode(context)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        stringResource(R.string.settings_screen_dim_mode_off),
                        stringResource(R.string.settings_screen_dim_mode_scheduled),
                        stringResource(R.string.settings_screen_dim_mode_on_now),
                    ).forEachIndexed { i, label ->
                        androidx.compose.material3.FilterChip(
                            selected = dimMode == i,
                            onClick = { dimMode = i; Settings.setWatchNightDimMode(context, i) },
                            label = { Text(label) },
                        )
                    }
                }
                val hourUnit = stringResource(R.string.settings_screen_unit_hour)
                if (dimMode == 1) {
                    var startH by remember { mutableStateOf(Settings.watchNightStartH(context)) }
                    StepperRow(stringResource(R.string.settings_screen_dim_start_label), startH, hourUnit, 0, 23, 1) {
                        startH = it; Settings.setWatchNightStartH(context, it)
                    }
                    var endH by remember { mutableStateOf(Settings.watchNightEndH(context)) }
                    StepperRow(stringResource(R.string.settings_screen_dim_end_label), endH, hourUnit, 0, 23, 1) {
                        endH = it; Settings.setWatchNightEndH(context, it)
                    }
                }
                Text(
                    stringResource(R.string.settings_screen_watch_dim_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_screen_lock_screen_title), style = MaterialTheme.typography.titleMedium)
                var overlay by remember { mutableStateOf(Settings.overlayEnabled(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_screen_overlay_label))
                    Switch(
                        checked = overlay,
                        onCheckedChange = {
                            overlay = it
                            Settings.setOverlayEnabled(context, it)
                            if (!it) io.github.obdosok.diapilot.collect.LockScreenOverlay.hide(context)
                        },
                    )
                }
                val canDraw = io.github.obdosok.diapilot.collect.LockScreenOverlay.canDraw(context)
                if (overlay && !canDraw) {
                    Text(
                        stringResource(R.string.settings_screen_overlay_permission_needed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text(stringResource(R.string.settings_screen_grant_permission)) }
                }
                Text(
                    stringResource(R.string.settings_screen_overlay_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // THREE SWITCHES THAT DECIDED BEHAVIOR AND COULD NOT BE FLIPPED.
        //
        // Audit: a set of settings had no setter call anywhere. Some of them
        // were never read either â€” those were deleted. These three ARE READ
        // by live code and sat on their default values because there was no
        // screen for them. The exact pattern already found on `hybridV11Main`:
        // a switch nobody can reach still decides how the model behaves.
        //
        // Default values are UNCHANGED â€” flipping any of them changes
        // behavior and must be measured, not chosen by eye. This screen exists
        // precisely to make that measurement possible: this is "shadow-safe"
        // in its original form â€” "behind a toggle".
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_screen_experimental_title), style = MaterialTheme.typography.titleMedium)

                var plausibility by remember { mutableStateOf(Settings.plausibilityGate(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_screen_plausibility_label))
                    Switch(
                        checked = plausibility,
                        onCheckedChange = { plausibility = it; Settings.setPlausibilityGate(context, it) },
                    )
                }
                Text(
                    stringResource(R.string.settings_screen_plausibility_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                var nightLow by remember { mutableStateOf(Settings.nightCorridorLow(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_screen_night_low_label))
                    Switch(
                        checked = nightLow,
                        onCheckedChange = { nightLow = it; Settings.setNightCorridorLow(context, it) },
                    )
                }
                Text(
                    stringResource(R.string.settings_screen_night_low_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                var marksTeach by remember { mutableStateOf(Settings.marksTeachModel(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_screen_marks_teach_label))
                    Switch(
                        checked = marksTeach,
                        onCheckedChange = { marksTeach = it; Settings.setMarksTeachModel(context, it) },
                    )
                }
                Text(
                    stringResource(R.string.settings_screen_experimental_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A labelled âˆ’/ï¼‹ integer stepper row. */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    unit: String,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(
            enabled = value > min,
            onClick = { onChange((value - step).coerceAtLeast(min)) },
        ) { Text("âˆ’") }
        Text("$value $unit")
        TextButton(
            enabled = value < max,
            onClick = { onChange((value + step).coerceAtMost(max)) },
        ) { Text("ï¼‹") }
    }
}

/**
 * What the insulin card needs, loaded once off the composition thread.
 *
 * It carries the measured CURVE and not only its support count, because the
 * divergence line has to compare the user's hand-entered landmarks against
 * the very knots the model would otherwise have used.
 */
data class InsulinCardView(
    val model: com.diapilot.core.hybrid.HybridPersonModel?,
    val artifact: com.diapilot.core.physio.PhysioArtifactV1?,
    val curve: com.diapilot.core.physio.PersonalInsulinCurveV1?,
    /**
     * "Today is different", as observations rather than as discarded noise.
     *
     * A dose that behaved unlike the profile carries a covariate; the app's job
     * is to pair the two and say so. It never acts on them â€” see
     * [io.github.obdosok.diapilot.data.IsfDeviationRuntime].
     */
    val deviations: List<String> = emptyList(),
    /** One line: what is measured, or precisely what is missing. */
    val profileLine: String? = null,
    /** Doses that gave no landmark at all, by named reason â€” a "perfectly
     * compensated meal" is one of them and must be visible. */
    val refusals: Map<com.diapilot.core.physio.LandmarkRefusal, Int> = emptyMap(),
)
