package io.github.obdosok.diapilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.diapilot.core.analysis.CarbSensitivityPriorV1
import com.diapilot.core.analysis.fmtBg
import io.github.obdosok.diapilot.Edition
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.HybridModelStore
import io.github.obdosok.diapilot.data.Onboarding
import io.github.obdosok.diapilot.data.Onboarding.Input.Parsed
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.Units
import io.github.obdosok.diapilot.i18n.LanguageCard
import io.github.obdosok.diapilot.i18n.unitLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FIRST RUN: the pages a person goes through before the app draws anything
 * from a model — see [Onboarding] for what is stored and why.
 *
 * Nothing is written until Finish, except the two things that are their own
 * act: accepting the disclaimer (stamped the moment the button is pressed, so
 * a process death after it does not ask again) and picking a language (which
 * recreates the activity by nature — the page index and every field live in
 * `rememberSaveable` so the flow resumes where it was).
 *
 * The page list comes from [Onboarding.pages]: the store edition sees the
 * disclaimer, the language, the weight and the summary; the model pages exist
 * only where a model runs forward.
 */
@Composable
fun OnboardingScreen(
    gate: Onboarding.Gate,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember(gate) {
        if (gate == Onboarding.Gate.DISCLAIMER_ONLY) listOf(Onboarding.Page.DISCLAIMER)
        else Onboarding.pages(Edition.prospective)
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val draft = rememberOnboardingDraft(context)
    var finishing by remember { mutableStateOf(false) }
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    val last = index >= pages.lastIndex

    fun finish() {
        if (finishing) return
        finishing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                if (gate != Onboarding.Gate.DISCLAIMER_ONLY) {
                    // `Onboarding.Input` has already vetted every field: the
                    // Next button of each page is disabled on an invalid entry,
                    // so what reaches here is Empty or Valid.
                    Settings.setWeightKg(context, (draft.weight() as? Parsed.Valid)?.value)
                    if (Edition.prospective) {
                        val isf = (draft.isf() as? Parsed.Valid)?.value
                        if (isf != null) {
                            Onboarding.applyModelEntries(
                                context, isf, draft.preset, (draft.carb() as? Parsed.Valid)?.value,
                            )
                        }
                    }
                    Onboarding.markCompleted(context)
                    // The hand tier is read on the artifact path, not baked into
                    // the installed model — this re-enters the one install path
                    // so the tuning and the registry see the new numbers at once,
                    // the same call the Settings card makes after Apply.
                    runCatching { HybridModelStore.retune(context) }
                }
            }
            onDone()
        }
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.titleLarge)
        if (pages.size > 1) {
            Text(
                stringResource(R.string.onboarding_step, index + 1, pages.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { (index + 1).toFloat() / pages.size },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when (page) {
            Onboarding.Page.DISCLAIMER -> DisclaimerPage(draft)
            Onboarding.Page.LANGUAGE -> LanguagePage()
            Onboarding.Page.WEIGHT -> WeightPage(draft)
            Onboarding.Page.ISF -> IsfPage(draft)
            Onboarding.Page.INSULIN_PRESET -> PresetPage(draft)
            Onboarding.Page.CARB_SENS -> CarbPage(draft)
            Onboarding.Page.SUMMARY -> SummaryPage(draft)
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index > 0) {
                TextButton(onClick = { index-- }) { Text(stringResource(R.string.onboarding_back)) }
            } else {
                Spacer(Modifier)
            }
            Button(
                enabled = !finishing && pageValid(page, draft),
                onClick = {
                    // The disclaimer is its own act, recorded when the button
                    // is pressed rather than at Finish, and only once: a
                    // re-run from Settings keeps the original acceptance date.
                    if (page == Onboarding.Page.DISCLAIMER && draft.acceptedAtMs == null) {
                        Onboarding.acceptDisclaimer(context)
                        draft.acceptedAtMs = Onboarding.disclaimerAcceptedAtMs(context)
                    }
                    if (last) finish() else index++
                },
            ) {
                Text(
                    stringResource(
                        when {
                            page == Onboarding.Page.DISCLAIMER -> R.string.onboarding_disclaimer_button
                            last -> R.string.onboarding_finish
                            else -> R.string.onboarding_next
                        },
                    ),
                )
            }
        }
    }
}

/**
 * Everything the pages collect, as text exactly as typed, so a field the
 * person is still editing never snaps to a parsed value under their thumb.
 * Survives activity recreation (the language page) through [Saver].
 */
internal class OnboardingDraft(
    accepted: Boolean,
    acceptedAtMs: Long?,
    weightText: String,
    isfText: String,
    mgdl: Boolean,
    preset: Onboarding.InsulinPreset,
    carbText: String,
) {
    var accepted by mutableStateOf(accepted)
    var acceptedAtMs by mutableStateOf(acceptedAtMs)
    var weightText by mutableStateOf(weightText)
    var isfText by mutableStateOf(isfText)
    var mgdl by mutableStateOf(mgdl)
    var preset by mutableStateOf(preset)
    var carbText by mutableStateOf(carbText)

    fun weight(): Parsed = Onboarding.Input.weightKg(weightText)
    fun isf(): Parsed = Onboarding.Input.isfMmolPerU(isfText, mgdl)
    fun carb(): Parsed = Onboarding.Input.carbSensMmolPerG(carbText, mgdl)

    companion object {
        /** Bundle-friendly: the absent acceptance date travels as -1. */
        val Saver: Saver<OnboardingDraft, Any> = listSaver(
            save = {
                listOf(
                    it.accepted, it.acceptedAtMs ?: -1L, it.weightText, it.isfText,
                    it.mgdl, it.preset.name, it.carbText,
                )
            },
            restore = {
                OnboardingDraft(
                    accepted = it[0] as Boolean,
                    acceptedAtMs = (it[1] as Long).takeIf { ms -> ms > 0L },
                    weightText = it[2] as String,
                    isfText = it[3] as String,
                    mgdl = it[4] as Boolean,
                    preset = Onboarding.InsulinPreset.valueOf(it[5] as String),
                    carbText = it[6] as String,
                )
            },
        )
    }
}

/**
 * A re-run from Settings starts from what is already stored, so "Set up model
 * again" reads as editing rather than as an empty form; a fresh install
 * starts empty with the typical preset selected.
 */
@Composable
private fun rememberOnboardingDraft(context: android.content.Context): OnboardingDraft =
    rememberSaveable(saver = OnboardingDraft.Saver) {
        val stored = io.github.obdosok.diapilot.data.ManualInsulinRuntime.params(context)
        val acceptedAt = Onboarding.disclaimerAcceptedAtMs(context)
        val mgdl = Units.isMgdl(context)
        OnboardingDraft(
            accepted = acceptedAt != null,
            acceptedAtMs = acceptedAt,
            weightText = Settings.weightKg(context)?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: "",
            isfText = stored.isfMmolPerU?.let { fmtBg(it, mgdl).replace(',', '.') } ?: "",
            mgdl = mgdl,
            preset = Onboarding.InsulinPreset.entries.firstOrNull {
                it.onsetMin == stored.onsetMin && it.peakMin == stored.peakMin && it.tailMin == stored.tailMin
            } ?: Onboarding.InsulinPreset.TYPICAL,
            carbText = Settings.storedCarbSensOverrideMmolPerG(context)
                ?.let { fmtBg(it * 10.0, mgdl).replace(',', '.') } ?: "",
        )
    }

private fun pageValid(page: Onboarding.Page, draft: OnboardingDraft): Boolean = when (page) {
    Onboarding.Page.DISCLAIMER -> draft.accepted
    Onboarding.Page.WEIGHT -> draft.weight() !is Parsed.Invalid
    Onboarding.Page.ISF -> draft.isf() is Parsed.Valid
    Onboarding.Page.CARB_SENS -> draft.carb() !is Parsed.Invalid
    else -> true
}

@Composable
private fun PageCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DisclaimerPage(draft: OnboardingDraft) {
    PageCard(stringResource(R.string.onboarding_disclaimer_title)) {
        Body(stringResource(R.string.onboarding_disclaimer_body_1))
        Body(stringResource(R.string.onboarding_disclaimer_body_2))
        Body(stringResource(R.string.onboarding_disclaimer_body_3))
        Body(stringResource(R.string.onboarding_disclaimer_body_4))
        // The whole row toggles, not only the box: the sentence is the thing
        // being agreed to, and a tap on it must count.
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = draft.accepted, role = Role.Checkbox, onValueChange = { draft.accepted = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = draft.accepted, onCheckedChange = null)
            Text(
                stringResource(R.string.onboarding_disclaimer_accept),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
        draft.acceptedAtMs?.let {
            Hint(
                stringResource(
                    R.string.onboarding_disclaimer_accepted_on,
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)),
                ),
            )
        }
    }
}

@Composable
private fun LanguagePage() {
    LanguageCard(Modifier.fillMaxWidth())
    Hint(stringResource(R.string.onboarding_language_intro))
}

@Composable
private fun WeightPage(draft: OnboardingDraft) {
    PageCard(stringResource(R.string.onboarding_weight_title)) {
        Body(stringResource(R.string.onboarding_weight_body))
        OutlinedTextField(
            value = draft.weightText,
            onValueChange = { draft.weightText = it },
            label = { Text(stringResource(R.string.settings_screen_weight_label)) },
            singleLine = true,
            isError = draft.weight() is Parsed.Invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.weight() is Parsed.Invalid) Hint(stringResource(R.string.onboarding_weight_invalid))
    }
}

/** Two display-unit chips; the choice is also the app's display setting. */
@Composable
private fun UnitChips(draft: OnboardingDraft) {
    val context = LocalContext.current
    Hint(stringResource(R.string.onboarding_isf_units))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false, true).forEach { mgdl ->
            FilterChip(
                selected = draft.mgdl == mgdl,
                onClick = {
                    if (draft.mgdl != mgdl) {
                        // Convert what was typed rather than dropping it: a
                        // number in the old unit is a valid entry, not a typo.
                        (draft.isf() as? Parsed.Valid)?.let { draft.isfText = fmtBg(it.value, mgdl).replace(',', '.') }
                        (draft.carb() as? Parsed.Valid)?.let {
                            draft.carbText = fmtBg(it.value * 10.0, mgdl).replace(',', '.')
                        }
                        draft.mgdl = mgdl
                        Units.setMgdl(context, mgdl)
                    }
                },
                label = { Text(unitLabel(mgdl)) },
            )
        }
    }
}

@Composable
private fun IsfPage(draft: OnboardingDraft) {
    val mgdl = draft.mgdl
    fun show(mmol: Double) = "${fmtBg(mmol, mgdl)} ${unitLabel(mgdl)}"
    PageCard(stringResource(R.string.onboarding_isf_title)) {
        Body(
            stringResource(
                R.string.onboarding_isf_body,
                show(Onboarding.Input.TYPICAL_ADULT_ISF_MMOL_PER_U.start),
                show(Onboarding.Input.TYPICAL_ADULT_ISF_MMOL_PER_U.endInclusive),
            ),
        )
        UnitChips(draft)
        OutlinedTextField(
            value = draft.isfText,
            onValueChange = { draft.isfText = it },
            label = { Text(stringResource(R.string.onboarding_isf_label, unitLabel(mgdl))) },
            singleLine = true,
            isError = draft.isfText.isNotBlank() && draft.isf() !is Parsed.Valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.isf() !is Parsed.Valid) {
            Hint(
                stringResource(
                    R.string.onboarding_isf_required,
                    show(Onboarding.Input.ISF_MMOL_PER_U.start),
                    show(Onboarding.Input.ISF_MMOL_PER_U.endInclusive),
                ),
            )
        }
    }
}

@Composable
private fun presetLabel(preset: Onboarding.InsulinPreset): String = stringResource(
    when (preset) {
        Onboarding.InsulinPreset.TYPICAL -> R.string.onboarding_preset_typical
        Onboarding.InsulinPreset.SLOWER -> R.string.onboarding_preset_slower
        Onboarding.InsulinPreset.ULTRA_RAPID -> R.string.onboarding_preset_ultra
    },
)

@Composable
private fun PresetPage(draft: OnboardingDraft) {
    PageCard(stringResource(R.string.onboarding_preset_title)) {
        Body(stringResource(R.string.onboarding_preset_body))
        Onboarding.InsulinPreset.entries.forEach { preset ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = draft.preset == preset, onClick = { draft.preset = preset })
                Column(Modifier.weight(1f)) {
                    Text(presetLabel(preset), style = MaterialTheme.typography.bodyMedium)
                    Hint(
                        stringResource(
                            R.string.onboarding_preset_landmarks,
                            preset.onsetMin.toInt(), preset.peakMin.toInt(), preset.tailMin.toInt(),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CarbPage(draft: OnboardingDraft) {
    val mgdl = draft.mgdl
    fun show(mmol: Double) = "${fmtBg(mmol, mgdl)} ${unitLabel(mgdl)}"
    val fromWeight = CarbSensitivityPriorV1.fromWeight((draft.weight() as? Parsed.Valid)?.value) * 10.0
    PageCard(stringResource(R.string.onboarding_carb_title)) {
        Body(stringResource(R.string.onboarding_carb_body, show(fromWeight)))
        OutlinedTextField(
            value = draft.carbText,
            onValueChange = { draft.carbText = it },
            label = { Text(stringResource(R.string.onboarding_carb_label, unitLabel(mgdl))) },
            singleLine = true,
            isError = draft.carb() is Parsed.Invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.carb() is Parsed.Invalid) {
            Hint(
                stringResource(
                    R.string.onboarding_carb_invalid,
                    show(Onboarding.Input.CARB_SENS_MMOL_PER_10G.start),
                    show(Onboarding.Input.CARB_SENS_MMOL_PER_10G.endInclusive),
                ),
            )
        }
    }
}

@Composable
private fun SummaryPage(draft: OnboardingDraft) {
    val mgdl = draft.mgdl
    fun show(mmol: Double) = "${fmtBg(mmol, mgdl)} ${unitLabel(mgdl)}"
    val weight = (draft.weight() as? Parsed.Valid)?.value
    PageCard(stringResource(R.string.onboarding_summary_title)) {
        Body(stringResource(R.string.onboarding_summary_hand_entered))
        Body(
            weight?.let { stringResource(R.string.onboarding_summary_weight, "%.0f".format(java.util.Locale.ROOT, it)) }
                ?: stringResource(R.string.onboarding_summary_weight_none),
        )
        if (Edition.prospective) {
            (draft.isf() as? Parsed.Valid)?.let { Body(stringResource(R.string.onboarding_summary_isf, show(it.value))) }
            Body(stringResource(R.string.onboarding_summary_preset, presetLabel(draft.preset)))
            Body(
                (draft.carb() as? Parsed.Valid)?.let {
                    stringResource(R.string.onboarding_summary_carb, show(it.value * 10.0))
                } ?: stringResource(
                    R.string.onboarding_summary_carb_from_weight,
                    show(CarbSensitivityPriorV1.fromWeight(weight) * 10.0),
                ),
            )
            Hint(stringResource(R.string.onboarding_summary_measured_note))
        }
        Hint(stringResource(R.string.onboarding_summary_change_note))
    }
}
