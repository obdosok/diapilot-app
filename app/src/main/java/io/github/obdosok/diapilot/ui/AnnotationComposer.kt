package io.github.obdosok.diapilot.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.analysis.NoteTag
import io.github.obdosok.diapilot.AppIdentity
import io.github.obdosok.diapilot.DEXTROSE_TABLET_G
import io.github.obdosok.diapilot.LocalAppGraph
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.AskClaude
import io.github.obdosok.diapilot.data.DishAliasRuntime
import io.github.obdosok.diapilot.data.DishDialogRuntime
import io.github.obdosok.diapilot.data.FoodStructureProposalRuntime
import io.github.obdosok.diapilot.data.OpenFoodFacts
import io.github.obdosok.diapilot.data.SqliteCollectorStore
import io.github.obdosok.diapilot.data.TwinCache
import io.github.obdosok.diapilot.data.Units
import io.github.obdosok.diapilot.i18n.CommandText
import io.github.obdosok.diapilot.i18n.FoodText
import io.github.obdosok.diapilot.i18n.TokenText
import io.github.obdosok.diapilot.i18n.localized
import io.github.obdosok.diapilot.i18n.uiLanguage
import io.github.obdosok.diapilot.i18n.unitLabel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Free-form context annotations — the "flexible context" half of the stage-1
 * data architecture. Stored raw (structure-on-output per spec §3): text,
 * photo (private app storage), or an intent tag pinned to a recent bolus.
 * The quick-entry dictionary grows from the user's own frequent notes.
 */
private val STARTER_TAGS = listOf(
    NoteTag.INJECTION_BELLY, NoteTag.INJECTION_THIGH, NoteTag.INJECTION_ARM,
    NoteTag.SLEEP_DEBT, NoteTag.ILL, NoteTag.STRESS, NoteTag.ALCOHOL, NoteTag.WORKOUT, NoteTag.WALK,
    NoteTag.NEW_SENSOR, NoteTag.NEW_CARTRIDGE, NoteTag.CARTRIDGE_WARMING, NoteTag.DAWN,
).map { it.key }

/** Time-offset chips: annotations are usually entered after the fact. Labels
 *  come from `R.array.annotation_composer_offset_labels` (same order). */
private val OFFSET_MS = listOf(0L, 30L * 60_000, 60L * 60_000, 120L * 60_000, 240L * 60_000)

private val COMMA_CONJUNCTIONS = setOf(
    "и", "а", "но", "или", "с", "со", "на", "от", "до", "без",
    // Quantifier prose: "each 75g", "both weigh 12 grams", "150g total"
    // describe portions, they are not dishes.
    "каждый", "каждая", "каждое", "оба", "обе", "всего", "примерно", "около", "по",
)

/** A comma-segment that looks like a DISH name, not a clause: short, few
 *  words, not opening with a digit or conjunction/quantifier, no weight
 *  tokens ("2 pancakes, each 75g" is one dish described, not two). */
private fun looksLikeDish(seg: String): Boolean {
    val name = Regex("""[×x]\s*\d+$""").replace(seg, "").trim()
    if (name.isEmpty() || name.length > 22) return false
    val words = name.split(Regex("\\s+"))
    if (words.size > 3) return false
    if (name.first().isDigit()) return false
    if (Regex("""\d+\s*(г|гр|грамм)""").containsMatchIn(name.lowercase())) return false
    return words.first().lowercase() !in COMMA_CONJUNCTIONS
}

// "porridge, bread ×2, coffee" → {porridge→1, bread→2, coffee→1}. Inverse of composed():
// lets an APP-COMPOSED note become editable parts again. Free-form text with
// incidental commas ("2 crackers, both weigh 12 g, and tea") is NOT split — a
// meal is treated as composite only when EVERY segment looks like a dish;
// otherwise the whole content is one part (its quantity still editable).
/** Split on [sep] at paren depth 0 only: "breakfast (hummus, salad)" is ONE
 *  dish whose composition rides inside the brackets, not four. */
private fun splitTopLevel(content: String, sep: Char): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var depth = 0
    for (ch in content) {
        when {
            ch == '(' -> { depth++; cur.append(ch) }
            ch == ')' -> { depth = (depth - 1).coerceAtLeast(0); cur.append(ch) }
            ch == sep && depth == 0 -> { out += cur.toString(); cur.clear() }
            else -> cur.append(ch)
        }
    }
    out += cur.toString()
    return out.map { it.trim() }.filter { it.isNotEmpty() }
}

internal fun parseMealParts(content: String): Map<String, Int> {
    fun peel(part: String): Pair<String, Int> {
        val m = Regex("""^(.*?)\s*[×x]\s*(\d+)$""").find(part)
        return if (m != null) m.groupValues[1].trim() to m.groupValues[2].toInt().coerceIn(1, 20)
        else part to 1
    }
    // "cabbage soup + bread with butter and pâté ×2": "+" is an EXPLICIT dish
    // separator the user typed on purpose — split unconditionally, the
    // dish-likeness gate is for ambiguous commas only.
    val plusSegs = splitTopLevel(content, '+')
    if (plusSegs.size >= 2) return plusSegs.associate { peel(it) }
    val segs = splitTopLevel(content, ',')
    if (segs.size <= 1 || !segs.all(::looksLikeDish)) {
        // Whole content = one dish. Still peel a trailing ×N so a bumped
        // quantity round-trips ("roll with butter ×2" → name, count 2).
        val whole = content.trim()
        if (whole.isEmpty()) return emptyMap()
        return mapOf(peel(whole))
    }
    return segs.associate { peel(it) }
}

/** Inverse of [parseMealParts]. Joins with ", " while every name would
 *  survive the comma gate; otherwise " + " — so a long-named dish round-trips
 *  as a separate part instead of collapsing back into one. */
internal fun composeMealParts(parts: Map<String, Int>): String {
    val sep = if (parts.keys.all(::looksLikeDish)) ", " else " + "
    return parts.entries.joinToString(sep) { (n, c) -> if (c > 1) "$n ×$c" else n }
}

// Intent tags for a bolus: pins the annotation to the shot's timestamp.
/**
 * Shot intents: "correction" — high BG away from food (clean ISF material);
 * "for a meal" — covering a meal upfront; "top-up" — catching up on food already
 * eaten when the first dose fell short (counts into the meal's effective dose,
 * never into ISF).
 */
private val BOLUS_TAGS = com.diapilot.core.analysis.BolusPurpose.entries.map { it.key }

/**
 * Compact "the user has eaten this before" context for the food LLM — past
 * logged grams + the measured glucose rise for dishes matching [query]. Makes
 * the estimate anchor on the user's own reality instead of a generic guess.
 * Null when nothing matches. Observation only — never a dose.
 */
private fun foodPersonalContext(
    query: String,
    carbsByFood: Map<String, Double>,
    recentFoodCarbs: Map<String, Double>,
    foodMemories: Map<String, com.diapilot.core.analysis.FoodMemory>,
    mgdl: Boolean,
): String? {
    val q = com.diapilot.core.analysis.normalizeFoodName(query.trim())
    if (q.length < 3) return null
    val matches = (carbsByFood.keys + foodMemories.keys + recentFoodCarbs.keys)
        .distinctBy { it.lowercase() }
        .filter { name ->
            val n = com.diapilot.core.analysis.normalizeFoodName(name)
            n.isNotEmpty() && (n == q || n.startsWith(q) || q.startsWith(n) || n.contains(q) || q.contains(n))
        }
        .take(3)
    val lines = matches.mapNotNull { name ->
        val grams = com.diapilot.core.analysis.lookupFoodGrams(carbsByFood, name)
            ?: recentFoodCarbs[name.trim().lowercase()]
        val mem = foodMemories[name]?.takeIf { it.episodes.isNotEmpty() }
        // Sent to the LLM as prompt context, not shown to the user — English,
        // not a resource (same treatment as AskClaude.buildContext, I18N.md §8).
        val parts = buildList {
            grams?.let { add("~%.0f g".format(it)) }
            mem?.let { add("rise +${com.diapilot.core.analysis.fmtBg(it.avgRise, mgdl)} (×${it.episodes.size})") }
        }
        if (parts.isEmpty()) null else "\"$name\": ${parts.joinToString(", ")}"
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** A natural-language command parsed into a structured data-entry action. */
data class ParsedCommand(
    val action: String,
    val food: String? = null, val grams: Double? = null,
    val mmol: Double? = null,
    val units: Double? = null, val purpose: String? = null,
    val activity: String? = null,
) {
    // Needs a Context (translated text, and bolus purpose / activity go
    // through TokenText); every call site is inside AnnotationComposer, which
    // always has one.
    fun describe(mgdl: Boolean, context: android.content.Context): String? {
        val res = context.localized()
        return when (action) {
            // food/activity may still be null here: this also renders the
            // "recognized but blocked" message before validateCommandValues
            // runs. `?: "null"` preserves that pre-existing display quirk.
            "food" -> grams?.let {
                res.getString(R.string.annotation_composer_command_food_grams, food ?: "null", it)
            } ?: res.getString(R.string.annotation_composer_command_food_no_grams, food ?: "null")
            "meter" -> mmol?.let {
                res.getString(R.string.annotation_composer_command_meter, com.diapilot.core.analysis.fmtBg(it, mgdl))
            } ?: res.getString(R.string.annotation_composer_command_meter_unknown)
            "bolus" -> {
                val u = units ?: 0.0
                val purposeLabel = TokenText.bolusPurpose(context, purpose)
                purposeLabel?.let { res.getString(R.string.annotation_composer_command_bolus_with_purpose, u, it) }
                    ?: res.getString(R.string.annotation_composer_command_bolus, u)
            }
            "basal" -> res.getString(R.string.annotation_composer_command_basal, units ?: 0.0)
            "activity" -> res.getString(
                R.string.annotation_composer_command_activity,
                TokenText.noteTag(context, activity ?: "null"),
            )
            "dextrose" -> res.getString(R.string.annotation_composer_command_dextrose)
            else -> null
        }
    }
}

/**
 * Date→time picker chain for exact event timestamps — reconstructing history
 * (yesterday's 2.5h walk, a missed meal) needs full past-day placement, not a
 * today-only offset. Future timestamps are clamped to now.
 */
internal fun pickDateTime(
    context: android.content.Context,
    initialMs: Long?,
    onPicked: (Long) -> Unit,
) {
    val cal = java.util.Calendar.getInstance()
    initialMs?.let { cal.timeInMillis = it }
    android.app.DatePickerDialog(
        context,
        { _, y, mo, d ->
            android.app.TimePickerDialog(
                context,
                { _, h, mi ->
                    val c = java.util.Calendar.getInstance().apply {
                        set(y, mo, d, h, mi, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    onPicked(c.timeInMillis.coerceAtMost(System.currentTimeMillis()))
                },
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                true,
            ).show()
        },
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH),
        cal.get(java.util.Calendar.DAY_OF_MONTH),
    ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
}

/** Extract the JSON object the command LLM returned; null if unparseable. */
fun parseCommandJson(raw: String): ParsedCommand? = try {
    val s = "{" + raw.substringAfter('{', "").substringBeforeLast('}') + "}"
    val o = org.json.JSONObject(s)
    fun str(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
    fun num(k: String) = o.optDouble(k).takeIf { !it.isNaN() && it > 0 }
    ParsedCommand(
        action = o.optString("action", "none"),
        food = str("food"), grams = num("grams"),
        mmol = num("mmol"), units = num("units"),
        purpose = str("purpose"), activity = str("activity"),
    )
} catch (e: Exception) {
    null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnnotationComposer(
    frequentTexts: List<String>,
    recentBoluses: List<BolusPoint>,
    foodLabels: List<String> = emptyList(),
    foodMemories: Map<String, com.diapilot.core.analysis.FoodMemory> = emptyMap(),
    similarFood: Map<String, List<String>> = emptyMap(),
    carbsByFood: Map<String, Double> = emptyMap(),
    mealComponents: Map<String, List<String>> = emptyMap(),
    compositeParts: Map<String, List<Triple<String, Int, Double?>>> = emptyMap(),
    recentFoodTexts: List<String> = emptyList(),
    recentFoodCarbs: Map<String, Double> = emptyMap(),
    compositionByFood: Map<String, com.diapilot.core.analysis.DishRecall> = emptyMap(),
    photoByFood: Map<String, String> = emptyMap(),
    typicalMeals: List<String> = emptyList(),
    bgAtShot: Map<Long, Double> = emptyMap(),
    onAdd: (tsMs: Long, text: String, mediaRef: String?, kind: String, estCarbs: Double?) -> Unit,
    onAddFoodWithAnalysis: (tsMs: Long, text: String, mediaRef: String?, estCarbs: Double?, analysis: String) -> Unit =
        { ts, t, m, c, _ -> onAdd(ts, t, m, "food", c) },
    onAddFoodEvidence: (
        tsMs: Long, text: String, mediaRef: String?, estCarbs: Double?, analysis: String,
        evidence: com.diapilot.core.collector.CarbEvidenceInputV1,
    ) -> Unit = { ts, t, m, c, a, _ -> onAddFoodWithAnalysis(ts, t, m, c, a) },
    onAddBasal: (tsMs: Long, units: Double) -> Unit = { _, _ -> },
    onAddBolus: (tsMs: Long, units: Double) -> Unit = { _, _ -> },
    onAddMeter: (tsMs: Long, mmol: Double) -> Unit = { _, _ -> },
    movingFast: Boolean = false,     // glucose changing fast — warn on meter calibration
    onTagBolus: (tsMs: Long, purpose: String) -> Unit = { _, _ -> },
    // Atomic bolus+purpose for the command path; the default falls back to
    // the two separate callbacks for hosts that haven't wired it.
    onAddBolusWithPurpose: (tsMs: Long, units: Double, purpose: String?) -> Unit =
        { ts, u, p -> onAddBolus(ts, u); p?.let { onTagBolus(ts, it) } },
    alwaysOpen: Boolean = false,      // modal host: no collapse header, stays open
    onSubmitted: () -> Unit = {},     // modal host closes the sheet here
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val graph = LocalAppGraph.current
    var expanded by remember { mutableStateOf(alwaysOpen) }
    var text by remember { mutableStateOf("") }
    // A picked composite logs under its OWN NAME (text); its ingredients ride
    // here as editable composition metadata — the label never becomes a comma-list.
    // [(name, count, gramsPerUnit)]. Empty = a plain (non-composite) meal.
    var composedOf by remember { mutableStateOf<List<Triple<String, Int, Double?>>>(emptyList()) }
    var offsetIdx by remember { mutableStateOf(0) }
    // Exact minute override — for reconstructing events after the fact (a
    // hypo, a meter check, dextrose while the phone was dead): 30-min chips
    // are too coarse for history that trains the model.
    var customTs by remember { mutableStateOf<Long?>(null) }
    var taggingBolus by remember { mutableStateOf<BolusPoint?>(null) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }

    // Four clear modes instead of one wall of controls. Declared before
    // submit() so the note kind can depend on the active mode.
    var mode by remember { mutableStateOf(0) }  // 0 meal, 1 note, 2 injection, 3 basal
    // Dictionary grams first; a verbatim past note ("Ikea, soup" = 22.5g)
    // answers when normalization can't (comma-cut names).
    fun gramsFor(name: String): Double? =
        com.diapilot.core.analysis.lookupFoodGrams(carbsByFood, name)
            ?: recentFoodCarbs[name.trim().lowercase()]

    // Optional grams for meal mode; read by submit() so the photo path gets it too.
    var foodCarbsText by remember { mutableStateOf("") }
    // 0 ordinary estimate, 1 recomputable label+weight, 2 versioned recipe.
    var evidenceMode by remember { mutableStateOf(0) }
    var labelPer100Text by remember { mutableStateOf("") }
    var labelWeightText by remember { mutableStateOf("") }
    var labelPerServingText by remember { mutableStateOf("") }
    var labelServingsText by remember { mutableStateOf("") }
    var recipeTotalCarbsText by remember { mutableStateOf("") }
    var recipeTotalWeightText by remember { mutableStateOf("") }
    var recipeConsumedWeightText by remember { mutableStateOf("") }
    var recipeFractionText by remember { mutableStateOf("") }
    var recipeVersionText by remember { mutableStateOf("v1") }
    var alcoholPresent by remember { mutableStateOf(false) }
    var evidenceError by remember { mutableStateOf<String?>(null) }
    var showEvidenceDetails by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(text) {
        // Vocabulary match on the typed dish name (Russian input, with the
        // trivial English synonym), not UI text.
        val normalized = com.diapilot.core.analysis.normalizeFoodName(text)
        if (normalized.contains("пиво") || normalized.contains("beer")) {
            showEvidenceDetails = true
        }
    }
    var foodDurationText by remember { mutableStateOf("") }
    var foodProteinText by remember { mutableStateOf("") }
    var foodFatText by remember { mutableStateOf("") }
    var foodKcalText by remember { mutableStateOf("") }
    // Barcode → label facts (Open Food Facts). The scan only PREFILLS the
    // label+weight evidence fields — visible, editable, and inert until the user confirms.
    var scannedProduct by remember {
        mutableStateOf<OpenFoodFacts.Product?>(null)
    }
    var scanBusy by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    // Command mode: natural-language data entry.
    var cmdText by remember { mutableStateOf("") }
    var cmdBusy by remember { mutableStateOf(false) }
    var cmdParsed by remember { mutableStateOf<ParsedCommand?>(null) }
    var cmdError by remember { mutableStateOf<String?>(null) }
    // Autofill from history must never clobber a hand-typed value.
    var carbsEdited by remember { mutableStateOf(false) }

    // F-05: the dishes the composer RECOGNISES (accepted structure + learned
    // aliases) and which of them the user has accepted at least once. Loaded off
    // the main thread; empty until then — recognition simply stays quiet.
    var knownDishes by remember {
        mutableStateOf<List<com.diapilot.core.analysis.DishRecognitionV1.KnownDish>>(emptyList())
    }
    var acceptedDishIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // F-05 layer 4: the user's own components (the personal library's grams and
    // confirmed facts) ride into every parse, so hummus is the user's own
    // known-weight hummus, and a bread question once answered is never guessed again.
    var knownComponents by remember {
        mutableStateOf<List<AskClaude.KnownComponent>>(emptyList())
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            knownDishes = runCatching {
                FoodStructureProposalRuntime.knownDishes(context)
            }.getOrDefault(emptyList())
            acceptedDishIds = runCatching {
                FoodStructureProposalRuntime.acceptedDishIds(
                    graph.store as SqliteCollectorStore,
                )
            }.getOrDefault(emptySet())
            knownComponents = runCatching {
                (graph.store as SqliteCollectorStore)
                    .foodLibrary()
                    .map {
                        AskClaude.KnownComponent(
                            it.name, it.grams, it.comment,
                        )
                    }
            }.getOrDefault(emptyList())
        }
    }
    // A dish PICKED from the "repeat this" list — an explicit confirmation, so
    // its structure and id ride the save. Dropped the moment the text stops
    // being the picked wording: edited text may be a different meal.
    var pendingDish by remember {
        mutableStateOf<com.diapilot.core.analysis.DishRecognitionV1.KnownDish?>(null)
    }
    var pendingDishWording by remember { mutableStateOf<String?>(null) }
    // Edited text may be a different meal — the picked dish's structure must
    // not ride a wording the user changed ("same pancakes, but no cheese today").
    androidx.compose.runtime.LaunchedEffect(text) {
        if (pendingDishWording != null && text.trim() != pendingDishWording) {
            pendingDish = null
            pendingDishWording = null
        }
    }

    // Photo-first state (food mode): the shot is HELD, auto-analyzed by
    // Vision, and only the user's ✓ turns it into a note — one confirm tap.
    var heldPhoto by remember { mutableStateOf<String?>(null) }
    var photoAnalyzing by remember { mutableStateOf(false) }
    var aiRaw by remember { mutableStateOf<String?>(null) }
    var aiAnswer by remember { mutableStateOf<String?>(null) }
    // True while aiRaw holds an error message (the LLM call failed), not an analysis.
    var aiFailed by remember { mutableStateOf(false) }
    var aiComponents by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    // F-05 layer 2f: vision's recognition HINT — dish + portion-vs-usual.
    // Stricter than the text tier (carbonara looks like any cream pasta), so
    // it is only ever a card with the dish's own past photo next to it; the
    // tap is what turns it into structure.
    var visionCandidate by remember {
        mutableStateOf<Pair<com.diapilot.core.analysis.DishRecognitionV1.KnownDish, Double?>?>(null)
    }
    val aiScope = androidx.compose.runtime.rememberCoroutineScope()

    fun adoptAiResult(r: String, viaPhoto: Boolean, failed: Boolean = false) {
        aiRaw = r
        aiFailed = failed
        aiAnswer = (if (viaPhoto) "📷 " else "") +
            r.lineSequence().filter { it.isNotBlank() }.joinToString(" · ").take(240)
        aiComponents = com.diapilot.core.analysis.parseComponentsEstimate(r)
        com.diapilot.core.analysis.parseCarbsEstimate(r)?.let {
            foodCarbsText = if (it == Math.floor(it)) it.toInt().toString() else it.toString()
            carbsEdited = true  // survive autofill recompute
        }
        com.diapilot.core.analysis.parseFoodNutrition(r).let { nutrition ->
            nutrition.proteinG?.let { foodProteinText = "%.1f".format(it) }
            nutrition.fatG?.let { foodFatText = "%.1f".format(it) }
            nutrition.kcal?.let { foodKcalText = "%.0f".format(it) }
        }
        // Vision's first line is the dish name — prefill an empty field.
        if (viaPhoto && text.isBlank() && !failed) {
            r.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.trimEnd('.')?.let { text = it }
        }
    }

    fun analyzeHeldPhoto(ref: String) {
        val key = AskClaude.apiKey(context) ?: return
        photoAnalyzing = true
        val caption = text
        val pc = foodPersonalContext(
            caption, carbsByFood, recentFoodCarbs, foodMemories,
            Units.isMgdl(context),
        )
        // The photo path finally SEES the user's history: the known dishes
        // travel in the request, and the question becomes "which of these, or
        // none" instead of a from-scratch parse of a dish logged many times before.
        val forVision = knownDishes.map {
            AskClaude.KnownDishForVision(
                it.proposed.id, it.proposed.title, it.typicalCarbsG,
            )
        }
        val dishesNow = knownDishes
        aiScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var failed = false
            val r = try {
                val bytes = loadScaledJpeg(File(photosDir(context), ref)) ?: error("Photo not found")
                AskClaude.describeFood(
                    key, bytes, caption = caption, personalContext = pc,
                    knownDishes = forVision, knownComponents = knownComponents,
                    context = context,
                )
            } catch (e: Exception) {
                failed = true
                AskClaude.VisionResult(AskClaude.errorText(context, e))
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                photoAnalyzing = false
                adoptAiResult(r.text, viaPhoto = true, failed = failed)
                visionCandidate = r.knownDishId
                    ?.let { id -> dishesNow.firstOrNull { it.proposed.id == id } }
                    ?.let { it to r.knownDishPortion }
            }
        }
    }

    /**
     * Scan a product barcode and prefill the LABEL+WEIGHT evidence path: the
     * label's per-100g carbs land in the recomputable field, the weight stays
     * user-entered and required — "the portion stays mandatory" (Stage 6)
     * inherited literally. Nothing is written on scan.
     */
    fun scanBarcode() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_8,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_A,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_E,
            )
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { code ->
                val ean = code.rawValue?.trim()
                if (ean.isNullOrEmpty()) return@addOnSuccessListener
                scanBusy = true
                scanStatus = context.localized().getString(R.string.annotation_composer_barcode_searching, ean)
                aiScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val product = OpenFoodFacts.lookup(ean, context.uiLanguage())
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        scanBusy = false
                        if (product == null || !product.usable) {
                            scannedProduct = null
                            scanStatus = context.localized()
                                .getString(R.string.annotation_composer_barcode_not_found, ean)
                        } else {
                            scannedProduct = product
                            scanStatus = null
                            showEvidenceDetails = true
                            evidenceMode = 1
                            labelPer100Text = "%.1f".format(product.carbsPer100g)
                            // The package's own serving prefills the weight —
                            // only into an EMPTY field; a user-entered portion stands.
                            if (labelWeightText.isBlank() && product.servingG != null) {
                                labelWeightText = "%.0f".format(product.servingG)
                            }
                            if (text.isBlank()) {
                                text = listOfNotNull(product.name, product.brand)
                                    .joinToString(" · ")
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                scanStatus = context.localized()
                    .getString(R.string.annotation_composer_scanner_unavailable, e.message)
            }
    }

    // Portion macros follow the label: per-100g × the entered weight. They
    // ride the same editable nutrition fields — a later hand edit wins
    // until the weight changes again (the numbers DERIVE from the label).
    androidx.compose.runtime.LaunchedEffect(labelWeightText, scannedProduct) {
        val p = scannedProduct ?: return@LaunchedEffect
        val w = labelWeightText.trim().replace(',', '.').toDoubleOrNull() ?: return@LaunchedEffect
        if (w <= 0.0) return@LaunchedEffect
        OpenFoodFacts.portion(p.proteinPer100g, w)
            ?.let { foodProteinText = "%.1f".format(it) }
        OpenFoodFacts.portion(p.fatPer100g, w)
            ?.let { foodFatText = "%.1f".format(it) }
        OpenFoodFacts.portion(p.kcalPer100g, w)
            ?.let { foodKcalText = "%.0f".format(it) }
    }

    // The entry timestamp every mode shares: exact minute wins over chips.
    fun entryTs(): Long = customTs ?: (System.currentTimeMillis() - OFFSET_MS[offsetIdx])

    fun enteredNutrition(): com.diapilot.core.analysis.FoodNutrition {
        fun number(value: String): Double? = value.trim().replace(',', '.')
            .toDoubleOrNull()?.takeIf { it >= 0.0 }
        return com.diapilot.core.analysis.FoodNutrition(
            proteinG = number(foodProteinText),
            fatG = number(foodFatText),
            kcal = number(foodKcalText),
        )
    }

    fun withEnteredNutrition(base: String): String =
        com.diapilot.core.analysis.withFoodNutrition(base, enteredNutrition())


    fun resetAll() {
        text = ""
        composedOf = emptyList()
        foodCarbsText = ""
        evidenceMode = 0
        labelPer100Text = ""; labelWeightText = ""
        labelPerServingText = ""; labelServingsText = ""
        recipeTotalCarbsText = ""; recipeTotalWeightText = ""
        recipeConsumedWeightText = ""; recipeFractionText = ""; recipeVersionText = "v1"
        alcoholPresent = false
        evidenceError = null
        showEvidenceDetails = false
        foodDurationText = ""
        foodProteinText = ""
        foodFatText = ""
        foodKcalText = ""
        scannedProduct = null
        scanBusy = false
        scanStatus = null
        carbsEdited = false
        offsetIdx = 0
        customTs = null
        taggingBolus = null
        pendingDish = null
        pendingDishWording = null
        heldPhoto = null
        photoAnalyzing = false
        aiRaw = null; aiAnswer = null; aiFailed = false; aiComponents = emptyList()
        visionCandidate = null
        expanded = alwaysOpen
        onSubmitted()
    }

    fun evidenceInput(): com.diapilot.core.collector.CarbEvidenceInputV1? {
        fun n(raw: String): Double? = raw.trim().replace(',', '.').toDoubleOrNull()
        val duration = n(foodDurationText)?.takeIf { it > 0.0 }
        val timing = com.diapilot.core.collector.CarbUncertaintyV1(
            if (duration == null) "intake_time_point" else "user_entered_duration",
        )
        val candidate = when (evidenceMode) {
            1 -> com.diapilot.core.collector.CarbEvidenceInputV1(
                source = com.diapilot.core.collector.CarbEvidenceSourceV1.LABEL_WEIGHT,
                userConfirmed = true,
                labelCarbsPer100g = n(labelPer100Text), weighedEdibleG = n(labelWeightText),
                labelCarbsPerServingG = n(labelPerServingText), servings = n(labelServingsText),
                intakeDurationMin = duration,
                amountUncertainty = com.diapilot.core.collector.CarbUncertaintyV1("recomputed_label_rounding"),
                timingUncertainty = timing, alcoholPresent = alcoholPresent,
            )
            2 -> com.diapilot.core.collector.CarbEvidenceInputV1(
                source = com.diapilot.core.collector.CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT,
                userConfirmed = true, recipeVersion = recipeVersionText.trim(),
                recipeTotalCarbsG = n(recipeTotalCarbsText),
                recipeTotalWeightG = n(recipeTotalWeightText),
                recipeConsumedWeightG = n(recipeConsumedWeightText),
                recipeConsumedFraction = n(recipeFractionText), intakeDurationMin = duration,
                amountUncertainty = com.diapilot.core.collector.CarbUncertaintyV1("ingredient_density_and_rounding"),
                timingUncertainty = timing, alcoholPresent = alcoholPresent,
            )
            else -> null
        }
        return candidate?.let { runCatching { it.validated() }.getOrNull() }
    }

    fun submit(content: String, tsMs: Long = entryTs(), mediaRef: String? = null) {
        val photo = mediaRef ?: heldPhoto
        if (content.isBlank() && photo == null) return
        val kind = when {
            mode == 0 -> "food"                  // meal mode: first-class food note
            photo != null -> "photo"
            else -> "text"
        }
        val carbs = if (mode == 0) {
            foodCarbsText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
        } else null
        val analysis = aiRaw?.takeIf { mode == 0 && !aiFailed }
        val structured = evidenceInput()
        if (mode == 0 && evidenceMode != 0 && structured == null) {
            evidenceError = context.localized().getString(R.string.annotation_composer_evidence_incomplete)
            return
        }
        val metadata = buildList {
            foodDurationText.trim().replace(',', '.').toDoubleOrNull()
                ?.takeIf { it > 0 }
                ?.let { add("META_DURATION_MIN: ${it.coerceAtMost(240.0)}") }
            // The scanned barcode rides as provenance: a later reader can tell
            // label-from-scan from label-typed-by-hand. Parsers ignore it.
            if (scannedProduct != null && mode == 0) {
                add("META_BARCODE: ${scannedProduct!!.barcode} · Open Food Facts")
            }
            // ALCOHOL RIDES HERE. The chip previously reached storage only
            // through `evidenceInput()`, which builds an object in the label
            // and recipe modes and returns null in the mode actually used day
            // to day — so `alcohol_present = 1` was silently dropped from most
            // evidence rows that mentioned an alcoholic drink. On this line it
            // also survives a note with no carbohydrate at all, which is the
            // case that matters: a dry drink often goes unlogged precisely
            // because "there are no carbs there", which used to turn every
            // such night into a guess for the alcohol study.
            if (alcoholPresent) add("${com.diapilot.core.analysis.ALCOHOL_LINE_PREFIX_V2}: true")
        }
        val hasNutrition = listOf(foodProteinText, foodFatText, foodKcalText)
            .any { it.isNotBlank() }
        // F-05: does this save RECOGNISE its dish? A picked dish or a confirmed
        // alias of an accepted dish applies the canonical structure (with its
        // id) right here — the pool grows by itself and the learned curve
        // applies to the forecast. A merely-similar wording saves PLAIN and
        // becomes a question afterwards: the note never waits for the dialogue.
        val resolution = if (mode == 0 && content.isNotBlank()) {
            com.diapilot.core.analysis.DishRecognitionV1.resolve(
                content.trim(), knownDishes, acceptedDishIds,
                picked = pendingDish?.takeIf { content.trim() == pendingDishWording },
                isRejected = { id ->
                    DishAliasRuntime.isRejected(context, id, content.trim())
                },
            )
        } else com.diapilot.core.analysis.DishRecognitionV1.Resolution.None
        val dishApplied =
            (resolution as? com.diapilot.core.analysis.DishRecognitionV1.Resolution.Apply)?.dish
        if (analysis != null || structured != null || dishApplied != null ||
            (mode == 0 && (metadata.isNotEmpty() || hasNutrition))
        ) {
            val baseAnalysis = buildList {
                analysis?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                addAll(metadata)
            }.joinToString("\n")
            var storedAnalysis = withEnteredNutrition(baseAnalysis)
            dishApplied?.let { d ->
                // Appended LAST, so the parser's "last KINETICS line wins" rule
                // makes the canon authoritative while any LLM composition above
                // it stays readable. Grams are NOT touched: the dish gives
                // structure, never quantity.
                storedAnalysis = com.diapilot.core.analysis.FoodStructureAcceptanceV1
                    .analysisAfterAccept(d.proposed, storedAnalysis) ?: storedAnalysis
            }
            // The fallback content below is the stored placeholder token for a
            // photo-only entry (TokenText.NOTE_TAGS maps it to a translated
            // label for display) — not UI text.
            if (structured != null) onAddFoodEvidence(
                tsMs, content.trim().ifEmpty { NoteTag.PHOTO.key }, photo,
                structured.totalCarbsG, storedAnalysis, structured,
            ) else onAddFoodWithAnalysis(
                tsMs, content.trim().ifEmpty { NoteTag.PHOTO.key }, photo, carbs, storedAnalysis,
            )
        } else {
            onAdd(tsMs, content.trim().ifEmpty { NoteTag.PHOTO.key }, photo, kind, carbs)
        }
        val askMove = resolution as? com.diapilot.core.analysis.DishRecognitionV1.Resolution.Ask
        askMove?.let { ask ->
            DishDialogRuntime.ask(
                context,
                DishDialogRuntime.Move(
                    tsMs = tsMs, noteContent = content.trim(),
                    dishId = ask.candidate.dish.proposed.id,
                    question = FoodText.dishQuestion(
                        context, com.diapilot.core.analysis.DishRecognitionV1.question(ask.candidate),
                    ),
                    createdMs = System.currentTimeMillis(),
                ),
            )
        }
        // Layer 5: the parse NAMED a guess — turn the first unanswered one
        // into a single pointed question, but never two questions for one
        // note: the dish question outranks it.
        if (askMove == null && mode == 0 && analysis != null) {
            com.diapilot.core.analysis.parseFoodAssumptions(analysis)
                .firstOrNull { !com.diapilot.core.analysis.hasClarification(analysis, it.component) }
                ?.let { g ->
                    DishDialogRuntime.ask(
                        context,
                        DishDialogRuntime.Move(
                            tsMs = tsMs, noteContent = content.trim().ifEmpty { NoteTag.PHOTO.key },
                            dishId = g.component.orEmpty(),
                            question = g.what + (g.impact?.let { " — $it" } ?: ""),
                            kind = DishDialogRuntime.KIND_ASSUMPTION,
                            createdMs = System.currentTimeMillis(),
                        ),
                    )
                }
        }
        resetAll()
    }

    /**
     * One-tap add from a frequent chip. A repeat dish is repeat COMPOSITION —
     * a habitual meal tends to be the same few components every time — so if
     * this dish was ever logged with a canonical composition, carry it (and
     * its own carbs) forward. Logging just the NAME meant retyping the whole
     * split in the editor afterwards, which is exactly the friction that
     * leaves the diary incomplete. Carbs come from the SAME note as the
     * composition, or the two would contradict each other.
     */
    fun submitChip(tag: String) {
        val recall = com.diapilot.core.analysis.recallComposition(tag, compositionByFood)
        if (mode == 0 && recall != null) {
            onAddFoodWithAnalysis(entryTs(), recall.dish, heldPhoto, recall.grams, recall.analysis)
            resetAll()
        } else {
            submit(tag)
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        pendingPhoto = null
        if (ok && file != null && file.length() > 0) {
            if (mode == 0) {
                // Photo-first: hold the shot, let Vision fill the card,
                // the user only confirms.
                heldPhoto = file.name
                analyzeHeldPhoto(file.name)
            } else {
                submit(text, mediaRef = file.name)
            }
        } else {
            file?.delete()
        }
    }

    fun capturePhoto() {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        val uri = FileProvider.getUriForFile(context, AppIdentity.FILE_PROVIDER_AUTHORITY, file)
        takePicture.launch(uri)
    }

    // Gallery path: sometimes the dish photo already exists (sent by someone,
    // shot earlier) — picking it beats re-shooting. Copied into our photos dir
    // so the note's mediaRef outlives the gallery original, then the exact
    // same held-photo/Vision flow as the camera.
    val pickFromGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { inp ->
                file.outputStream().use { inp.copyTo(it) }
            }
            if (file.length() > 0) {
                if (mode == 0) {
                    heldPhoto = file.name
                    analyzeHeldPhoto(file.name)
                } else {
                    submit(text, mediaRef = file.name)
                }
            } else {
                file.delete()
            }
        } catch (e: Exception) { /* picker failed — nothing held */ }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        val modes = stringArrayResource(R.array.annotation_composer_modes)
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!alwaysOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.annotation_composer_section_title), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            stringResource(
                                if (expanded) R.string.annotation_composer_collapse
                                else R.string.annotation_composer_expand,
                            ),
                        )
                    }
                }
            }
            if (expanded) {
                // What are we adding? One compact segmented row — modes are
                // navigation, not content, they must not compete with it.
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    modes.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == i,
                            onClick = { mode = i },
                            shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(
                                index = i, count = modes.size,
                            ),
                            icon = {},
                            label = {
                                Text(
                                    m,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                        )
                    }
                }
                // When: "now" is the invisible default; the chips row appears
                // only when the user actually needs to back-date.
                var showTime by remember { mutableStateOf(false) }
                val offsetLabels = stringArrayResource(R.array.annotation_composer_offset_labels)
                run {
                    TextButton(
                        onClick = { showTime = !showTime },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 4.dp, vertical = 0.dp,
                        ),
                    ) {
                        val timeLabel = customTs?.let {
                            SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it))
                        } ?: offsetLabels.getOrElse(offsetIdx) { "" }
                        Text(
                            "🕐 $timeLabel ${if (showTime) "▴" else "▾"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (showTime) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OFFSET_MS.forEachIndexed { i, _ ->
                                FilterChip(
                                    selected = customTs == null && offsetIdx == i,
                                    onClick = { customTs = null; offsetIdx = i; showTime = false },
                                    label = { Text(offsetLabels.getOrElse(i) { "" }) },
                                )
                            }
                            // Exact minute — for reconstructing history (a hypo,
                            // a meter check, dextrose while the phone was off).
                            FilterChip(
                                selected = customTs != null,
                                onClick = {
                                    // Date first, then time: past-DAY entries
                                    // (yesterday's walk, an old meal) need the
                                    // full picker, not a today-only heuristic.
                                    pickDateTime(context, customTs) { ts ->
                                        customTs = ts
                                        showTime = false
                                    }
                                },
                                label = { Text(stringResource(R.string.annotation_composer_time_exact)) },
                            )
                        }
                    }
                }

                when (mode) {
                    // --- Food: compose a meal from parts + text/voice/photo ---
                    0 -> {
                        // A meal is dishes WITH portions: "bread ×2" is not
                        // "bread". Map name → count; grams scale with the count.
                        var parts by remember { mutableStateOf(mapOf<String, Int>()) }
                        fun composed(): String {
                            val chips = composeMealParts(parts)
                            val free = text.trim()
                            return listOf(chips, free).filter { it.isNotBlank() }
                                .joinToString(if (chips.contains(" + ")) " + " else ", ")
                        }
                        // The composition-marker lines the analysis carries, so
                        // future decomposition can read the split; sum = the carbs.
                        fun composedAnalysis(): String? = composedOf.takeIf { it.isNotEmpty() }
                            ?.joinToString("\n") { (n, c, g) ->
                                val per = g?.let { " = %.0f ${com.diapilot.core.analysis.COMPONENT_CARBS_TAG}".format(it) } ?: ""
                                "${com.diapilot.core.analysis.COMPONENT_LINE_PREFIX}: $n${if (c > 1) " ×$c" else ""}$per"
                            }
                        fun composedCarbs(): Double? = composedOf
                            .mapNotNull { (_, c, g) -> g?.times(c) }
                            .takeIf { it.isNotEmpty() }?.sum()
                        val speak = rememberSpeechInput { spoken ->
                            text = if (text.isBlank()) spoken else "$text $spoken"
                        }
                        // Photo-first: unknown food = one shutter tap. Vision
                        // fills the card (name, grams, components), the user
                        // only confirms with ✓.
                        if (heldPhoto == null && parts.isEmpty() && text.isBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.FilledTonalButton(
                                    onClick = ::capturePhoto,
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.annotation_composer_capture_photo)) }
                                androidx.compose.material3.FilledTonalButton(
                                    onClick = { pickFromGallery.launch("image/*") },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.annotation_composer_pick_gallery)) }
                                androidx.compose.material3.FilledTonalButton(
                                    onClick = ::scanBarcode,
                                    enabled = !scanBusy,
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.annotation_composer_scan_barcode)) }
                            }
                        }
                        scanStatus?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        scannedProduct?.let { p ->
                            val productName = listOfNotNull(p.name, p.brand).joinToString(" · ").ifEmpty { p.barcode }
                            // scannedProduct is only ever set from a `usable` product (carbsPer100g != null).
                            val carbsPer100g = p.carbsPer100g ?: 0.0
                            Text(
                                p.servingG?.let { s ->
                                    stringResource(
                                        R.string.annotation_composer_scanned_product_serving,
                                        productName, carbsPer100g, s,
                                    )
                                } ?: stringResource(
                                    R.string.annotation_composer_scanned_product,
                                    productName, carbsPer100g,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        heldPhoto?.let { ref ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PhotoThumbPublic(ref, stringResource(R.string.annotation_composer_photo_dish_desc), size = 56)
                                Text(
                                    stringResource(
                                        if (photoAnalyzing) R.string.annotation_composer_photo_analyzing
                                        else R.string.annotation_composer_photo_ready,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                )
                                IconButton(onClick = {
                                    File(photosDir(context), ref).delete()
                                    heldPhoto = null
                                    aiRaw = null; aiAnswer = null; aiFailed = false; aiComponents = emptyList()
                                    visionCandidate = null
                                }) { Text("✕") }
                            }
                        }
                        // F-05 layer 2f: the photo was RECOGNISED — confirm it
                        // against the dish's own past photo, never silently.
                        visionCandidate?.let { (kd, portion) ->
                            val pastPhoto = (kd.proposed.aliases + kd.proposed.title)
                                .firstNotNullOfOrNull { photoByFood[it] }
                            Card {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        portion?.let {
                                            stringResource(
                                                R.string.annotation_composer_vision_candidate_portion,
                                                kd.proposed.title, it * 100,
                                            )
                                        } ?: stringResource(R.string.annotation_composer_vision_candidate, kd.proposed.title),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    pastPhoto?.let { p ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            PhotoThumbPublic(p, stringResource(R.string.annotation_composer_photo_past_desc), size = 56)
                                            Text(
                                                stringResource(R.string.annotation_composer_past_photo_caption),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        androidx.compose.material3.FilledTonalButton(onClick = {
                                            val wording = kd.proposed.aliases.firstOrNull() ?: kd.proposed.title
                                            pendingDish = kd
                                            pendingDishWording = wording
                                            text = wording
                                            // portion × usual grams prefills;
                                            // the number stays editable by the user
                                            kd.typicalCarbsG?.let { g ->
                                                val v = g * (portion ?: 1.0)
                                                foodCarbsText = "%.0f".format(v)
                                                carbsEdited = true
                                            }
                                            visionCandidate = null
                                        }) { Text(stringResource(R.string.annotation_composer_confirm_yes)) }
                                        TextButton(onClick = { visionCandidate = null }) { Text(stringResource(R.string.annotation_composer_confirm_no)) }
                                    }
                                }
                            }
                        }
                        // The content comes FIRST: what was eaten. Everything
                        // else (chips, time, stats) is secondary chrome.
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.annotation_composer_food_placeholder)) },
                            minLines = 1,
                            maxLines = 4,
                        )
                        // Autocomplete over everything ever eaten (labels, priced
                        // notes, food memory): substring match, tap = add as a
                        // part — the meal composes from known dishes, grams sum
                        // themselves, and each row reminds what the dish does.
                        val mgdl = Units.isMgdl(context)
                        val universe = remember(foodLabels, carbsByFood, foodMemories, recentFoodTexts) {
                            (foodLabels + carbsByFood.keys + foodMemories.keys + recentFoodTexts)
                                .distinctBy { it.lowercase() }
                        }
                        val q = text.trim()
                        if (q.length >= 2) {
                            universe.withIndex()
                                .filter { (_, name) ->
                                    name.contains(q, ignoreCase = true) &&
                                        !name.equals(q, ignoreCase = true) && name !in parts
                                }
                                .sortedWith(
                                    compareBy<IndexedValue<String>>(
                                        { (_, name) ->
                                            when {
                                                name.startsWith(q, ignoreCase = true) -> 0
                                                name.split(Regex("""[\s,;/]+"""))
                                                    .any { it.startsWith(q, ignoreCase = true) } -> 1
                                                else -> 2
                                            }
                                        },
                                        { it.index },
                                    ),
                                )
                                .take(5)
                                .forEach { (_, name) ->
                                val grams = gramsFor(name)
                                // A composite (library recipe with components)
                                // is logged under its OWN NAME (stable label →
                                // its own curve); its ingredients ride as
                                // editable composition metadata. A plain dish adds ×1.
                                val recipe = compositeParts[
                                    com.diapilot.core.analysis.normalizeFoodName(name),
                                ]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (recipe != null && recipe.isNotEmpty()) {
                                                text = name          // own name = the label
                                                composedOf = recipe  // components as metadata
                                            } else {
                                                parts = parts + (name to 1)
                                                text = ""
                                            }
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (recipe != null && recipe.isNotEmpty())
                                            stringResource(R.string.annotation_composer_add_dish_composite, name)
                                        else stringResource(R.string.annotation_composer_add_dish, name),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        listOfNotNull(
                                            grams?.let { stringResource(R.string.annotation_composer_approx_grams, it) },
                                            recipe?.takeIf { it.isNotEmpty() }
                                                ?.let { stringResource(R.string.annotation_composer_has_composition) },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        // Picked composite: its ingredients shown as editable
                        // portions UNDER the own name (label stays the name).
                        // −/＋ tune today's portion (1 vs 2 slices of bread); grams
                        // and the composition metadata follow. ✕ drops a component.
                        if (composedOf.isNotEmpty()) {
                            Text(
                                stringResource(R.string.annotation_composer_composition_header, text.trim()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                composedOf.forEach { (n, c, g) ->
                                    SuggestionChip(
                                        onClick = { composedOf = composedOf.filterNot { it.first == n } },
                                        label = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    "−",
                                                    modifier = Modifier
                                                        .clickable {
                                                            composedOf = composedOf.mapNotNull {
                                                                if (it.first != n) it
                                                                else if (it.second <= 1) null
                                                                else it.copy(second = it.second - 1)
                                                            }
                                                        }
                                                        .padding(horizontal = 4.dp),
                                                )
                                                // Each chip carries its own grams
                                                // (they exist in the recipe, they
                                                // just weren't shown) — otherwise
                                                // the total below is a number from
                                                // nowhere. Editing them per
                                                // ingredient lives in ✎.
                                                Text(
                                                    buildString {
                                                        append(n)
                                                        if (c > 1) append(" ×$c")
                                                    }.let { base ->
                                                        g?.let {
                                                            stringResource(R.string.annotation_composer_component_with_grams, base, it * c)
                                                        } ?: base
                                                    },
                                                )
                                                Text(
                                                    "＋",
                                                    modifier = Modifier
                                                        .clickable {
                                                            composedOf = composedOf.map {
                                                                if (it.first == n) it.copy(second = it.second + 1) else it
                                                            }
                                                        }
                                                        .padding(horizontal = 4.dp),
                                                )
                                                Text(
                                                    " ✕",
                                                    color = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.clickable {
                                                        composedOf = composedOf.filterNot { it.first == n }
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                            composedCarbs()?.let {
                                Text(
                                    stringResource(R.string.annotation_composer_composed_carbs_summary, it, text.trim()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // Composite memory: the typed/picked meal has a known
                        // past split — offer its components as one-tap chips
                        // (add all, or pick them one by one). Suppressed while a
                        // composite is already loaded (composedOf drives it).
                        val typedNorm =
                            if (composedOf.isNotEmpty()) ""
                            else com.diapilot.core.analysis.normalizeFoodName(text.trim())
                        val compKey = when {
                            typedNorm.length >= 3 ->
                                mealComponents.keys.firstOrNull { it == typedNorm }
                                    ?: mealComponents.keys.filter { it.startsWith(typedNorm) }.minByOrNull { it.length }
                                    ?: mealComponents.keys.filter { it.contains(typedNorm) }.minByOrNull { it.length }
                            else -> null
                        } ?: parts.keys.map { com.diapilot.core.analysis.normalizeFoodName(it) }
                            .firstOrNull { mealComponents.containsKey(it) }
                        compKey?.let { key ->
                            val comps = mealComponents.getValue(key)
                                .filter { c -> parts.keys.none { com.diapilot.core.analysis.normalizeFoodName(it) == c } }
                            // Splitting a dish REPLACES it — "cabbage soup" must
                            // not sit in the list next to its own cabbage, or the
                            // grams are counted twice (the dish's carbs plus the
                            // carbs of what the dish is made of) and the first
                            // "ingredient" is the whole dish.
                            fun splitOut(p: Map<String, Int>) = p.filterKeys {
                                com.diapilot.core.analysis.normalizeFoodName(it) != key
                            }
                            if (comps.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.annotation_composer_last_composition),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    comps.forEach { c ->
                                        SuggestionChip(
                                            onClick = { parts = splitOut(parts) + (c to 1) },
                                            label = { Text(stringResource(R.string.annotation_composer_add_component, c)) },
                                        )
                                    }
                                    if (comps.size >= 2) {
                                        SuggestionChip(
                                            onClick = {
                                                parts = splitOut(parts) + comps.associateWith { 1 }
                                                // the composite name made its point
                                                if (typedNorm.length >= 3 && key.contains(typedNorm)) text = ""
                                            },
                                            label = { Text(stringResource(R.string.annotation_composer_add_all)) },
                                        )
                                    }
                                }
                            }
                        }
                        // Portions: each picked dish gets a −/+ stepper row.
                        parts.forEach { (dish, count) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    gramsFor(dish)?.let {
                                        stringResource(R.string.annotation_composer_dish_with_grams, dish, it * count)
                                    } ?: dish,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    parts = if (count <= 1) parts - dish
                                    else parts + (dish to count - 1)
                                }) { Text("−") }
                                Text("×$count", style = MaterialTheme.typography.titleSmall)
                                IconButton(onClick = {
                                    parts = parts + (dish to count + 1)
                                }) { Text("＋") }
                            }
                        }
                        // Repeat dishes already priced (LLM or by hand) prefill
                        // the grams — portions multiply; names go through the
                        // normalized component dictionary, so "buckwheat" typed
                        // today finds the grams Vision priced inside a combo
                        // logged earlier (composite meals step 1).
                        // A loaded composite OWNS the number: its carbs are the sum
                        // of its own components, so the field has to follow the
                        // chips. It didn't — the carbs field sat frozen while ×2 on
                        // the bread changed nothing, and the note went in with a
                        // carb count that contradicted its own composition.
                        // (composedCarbs is null when no component has grams —
                        // then the dish's own dictionary entry is still the best
                        // answer.)
                        val knownGrams = composedCarbs() ?: (
                            parts.mapNotNull { (n, c) -> gramsFor(n)?.times(c) } +
                                listOfNotNull(gramsFor(text.trim()))
                            )
                            .takeIf { it.isNotEmpty() }?.sum()
                        androidx.compose.runtime.LaunchedEffect(knownGrams) {
                            if (!carbsEdited) {
                                foodCarbsText = knownGrams?.let {
                                    if (it == Math.floor(it)) it.toInt().toString() else "%.1f".format(it)
                                } ?: ""
                            }
                        }
                        // F-05 layer 1 — "repeat": the dishes the app KNOWS,
                        // one tap pulls the id, structure and macros; the grams
                        // stay user-editable. No intelligence, cannot be
                        // wrong: the tap itself is the confirmation.
                        if (knownDishes.isNotEmpty() && parts.isEmpty() &&
                            text.isBlank() && composedOf.isEmpty() && heldPhoto == null
                        ) {
                            Text(
                                stringResource(R.string.annotation_composer_repeat_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                knownDishes.sortedByDescending { it.intakes }.take(6).forEach { kd ->
                                    SuggestionChip(
                                        onClick = {
                                            val wording = kd.proposed.aliases.firstOrNull()
                                                ?: kd.proposed.title
                                            pendingDish = kd
                                            pendingDishWording = wording
                                            text = wording
                                            kd.typicalCarbsG?.let {
                                                foodCarbsText = if (it == Math.floor(it)) {
                                                    it.toInt().toString()
                                                } else "%.1f".format(it)
                                                carbsEdited = true
                                            }
                                            kd.proposed.proteinG?.let { foodProteinText = "%.1f".format(it) }
                                            kd.proposed.fatG?.let { foodFatText = "%.1f".format(it) }
                                            if (kd.proposed.alcohol) alcoholPresent = true
                                        },
                                        label = {
                                            val title = kd.proposed.title.take(22)
                                            Text(
                                                kd.typicalCarbsG?.let {
                                                    stringResource(R.string.annotation_composer_component_with_grams, title, it)
                                                } ?: title,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        pendingDish?.let { kd ->
                            Text(
                                stringResource(R.string.annotation_composer_dish_recognized, kd.proposed.title),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // One-tap repeat of a typical meal at this hour —
                        // shown only while the composer is empty.
                        val typical = typicalMeals.filter { it !in foodLabels }.take(2)
                        if (parts.isEmpty() && text.isBlank() && typical.isNotEmpty()) {
                            Text(
                                stringResource(R.string.annotation_composer_typical_time),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                typical.forEach { meal ->
                                    SuggestionChip(
                                        onClick = { parts = parseMealParts(meal) },
                                        label = { Text(meal.take(36) + if (meal.length > 36) "…" else "") },
                                    )
                                }
                            }
                        }
                        // Frequent dishes stay one compact tap lane. Detailed
                        // response memory appears once after selection, not in
                        // autocomplete, cards and a third summary at once.
                        if (foodLabels.isNotEmpty() && text.isBlank()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                foodLabels.take(5).forEach { dish ->
                                    val sel = dish in parts
                                    androidx.compose.material3.FilterChip(
                                        selected = sel,
                                        onClick = {
                                            parts = if (sel) parts - dish else parts + (dish to 1)
                                        },
                                        label = {
                                            val truncated = dish.take(18)
                                            val withGrams = gramsFor(dish)?.let {
                                                stringResource(R.string.annotation_composer_component_with_grams, truncated, it)
                                            } ?: truncated
                                            Text(withGrams + if (dish.length > 18) "…" else "")
                                        },
                                    )
                                }
                            }
                        }
                        // Past-episode behaviour of the entered dish — how sugar
                        // and doses actually went last times. The note is the
                        // identity now; this is the "recall" that used to live in
                        // the labeling queue, surfaced right where you log.
                        run {
                            val name = text.trim().ifEmpty { parts.keys.firstOrNull()?.trim() ?: "" }
                            val mem = if (name.length < 2) null else
                                foodMemories[name] ?: foodMemories.entries.firstOrNull {
                                    com.diapilot.core.analysis.normalizeFoodName(it.key) ==
                                        com.diapilot.core.analysis.normalizeFoodName(name)
                                }?.value
                            mem?.takeIf { it.episodes.isNotEmpty() }?.let { m ->
                                val peak = com.diapilot.core.analysis.fmtBg(m.avgRise, mgdl)
                                val dose = m.avgEffectiveDose
                                val line = if (m.underDosedCount > 0) {
                                    dose?.let {
                                        stringResource(
                                            R.string.annotation_composer_food_memory_topup_dose,
                                            name, peak, it, m.underDosedCount, m.episodes.size,
                                        )
                                    } ?: stringResource(
                                        R.string.annotation_composer_food_memory_topup,
                                        name, peak, m.underDosedCount, m.episodes.size,
                                    )
                                } else {
                                    dose?.let {
                                        stringResource(
                                            R.string.annotation_composer_food_memory_times_dose,
                                            name, peak, it, m.episodes.size,
                                        )
                                    } ?: stringResource(
                                        R.string.annotation_composer_food_memory_times,
                                        name, peak, m.episodes.size,
                                    )
                                }
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                        // Portion quick-pick: when the typed dish maps to a
                        // concept, translate a NATURAL portion into carbs — the
                        // user picks "usual / 1½ portions" without knowing grams.
                        run {
                            val concept = com.diapilot.core.analysis.conceptFor(text)
                            val typical = concept?.let { com.diapilot.core.analysis.typicalCarbs(it.id) }
                            if (text.isNotBlank() && typical != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.annotation_composer_portion_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    val usualLabel = stringResource(R.string.annotation_composer_portion_usual)
                                    listOf("½" to 0.5, usualLabel to 1.0, "1½" to 1.5, "2×" to 2.0)
                                        .forEach { (lbl, mult) ->
                                            androidx.compose.material3.AssistChip(
                                                onClick = {
                                                    foodCarbsText = "%.0f".format(typical * mult)
                                                    carbsEdited = true
                                                },
                                                label = { Text(lbl) },
                                            )
                                        }
                                }
                            }
                        }
                        // Action row: grams + voice + photo + submit, under the
                        // description so the field gets the full width.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = foodCarbsText,
                                onValueChange = { foodCarbsText = it; carbsEdited = true },
                                modifier = Modifier.width(96.dp),
                                label = { Text(stringResource(R.string.annotation_composer_carbs_g_label)) },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                ),
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            IconButton(onClick = speak) { Text("🎤") }
                            IconButton(onClick = {
                                text = composed(); parts = emptyMap(); capturePhoto()
                            }) { Text("📷") }
                            androidx.compose.material3.FilledTonalIconButton(onClick = {
                                text = composed(); parts = emptyMap()
                                pickFromGallery.launch("image/*")
                            }) { Text("🖼") }
                            val defaultMealLabel = stringResource(R.string.annotation_composer_default_meal_label)
                            val evidenceIncompleteMsg = stringResource(R.string.annotation_composer_evidence_incomplete)
                            androidx.compose.material3.FilledTonalIconButton(onClick = evidenceSubmit@ {
                                if (composedOf.isNotEmpty()) {
                                    // Composite: OWN NAME is the label; the split
                                    // rides as composition metadata (base for future
                                    // decomposition), grams from the portions.
                                    val name = text.trim().ifEmpty { defaultMealLabel }
                                    val carbs = foodCarbsText.trim().replace(',', '.')
                                        .toDoubleOrNull()?.takeIf { it > 0 } ?: composedCarbs()
                                    val enriched = withEnteredNutrition(buildList {
                                        composedAnalysis()?.let(::add)
                                        foodDurationText.trim().replace(',', '.').toDoubleOrNull()
                                            ?.takeIf { it > 0 }
                                            ?.let { add("META_DURATION_MIN: ${it.coerceAtMost(240.0)}") }
                                    }.joinToString("\n"))
                                    val evidence = evidenceInput()
                                    if (evidenceMode != 0 && evidence == null) {
                                        evidenceError = evidenceIncompleteMsg
                                        return@evidenceSubmit
                                    }
                                    if (evidence != null) onAddFoodEvidence(
                                        entryTs(), name, heldPhoto, evidence.totalCarbsG, enriched, evidence,
                                    ) else onAddFoodWithAnalysis(entryTs(), name, heldPhoto, carbs, enriched)
                                    composedOf = emptyList()
                                    resetAll()
                                } else {
                                    val c = composed()
                                    // A frequent chip is the same recipe, not
                                    // merely the same caption. Preserve its
                                    // structured composition / kinetics.
                                    val repeat = if (
                                        text.isBlank() && parts.size == 1 &&
                                        parts.values.singleOrNull() == 1
                                    ) com.diapilot.core.analysis.recallComposition(
                                        parts.keys.single(), compositionByFood,
                                    ) else null
                                    parts = emptyMap()
                                    if (repeat != null) {
                                        onAddFoodWithAnalysis(
                                            entryTs(), repeat.dish, heldPhoto,
                                            repeat.grams, repeat.analysis,
                                        )
                                        resetAll()
                                    } else submit(c)
                                }
                            }) { Text("✓") }
                        }
                        TextButton(onClick = { showEvidenceDetails = !showEvidenceDetails }) {
                            Text(
                                stringResource(
                                    if (showEvidenceDetails) R.string.annotation_composer_evidence_toggle_hide
                                    else R.string.annotation_composer_evidence_toggle_show,
                                ),
                            )
                        }
                        if (showEvidenceDetails) {
                        val evidenceModeLabels = stringArrayResource(R.array.annotation_composer_evidence_source_modes)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 1, 2).zip(evidenceModeLabels.toList()).forEach { (id, label) ->
                                FilterChip(
                                    selected = evidenceMode == id,
                                    onClick = { evidenceMode = id; evidenceError = null },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = foodDurationText,
                                onValueChange = { foodDurationText = it },
                                modifier = Modifier.width(118.dp),
                                label = { Text(stringResource(R.string.annotation_composer_intake_duration_label)) },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                ),
                            )
                            FilterChip(
                                selected = alcoholPresent,
                                onClick = { alcoholPresent = !alcoholPresent },
                                label = { Text(stringResource(R.string.annotation_composer_alcohol_label)) },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        if (evidenceMode == 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.annotation_composer_label_evidence_hint), style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = ::scanBarcode, enabled = !scanBusy) {
                                    Text(
                                        stringResource(
                                            if (scanBusy) R.string.annotation_composer_scan_searching
                                            else R.string.annotation_composer_scan_action,
                                        ),
                                    )
                                }
                            }
                            val labelFields = stringArrayResource(R.array.annotation_composer_label_weight_fields)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    Triple(labelFields[0], labelPer100Text) { v: String -> labelPer100Text = v },
                                    Triple(labelFields[1], labelWeightText) { v: String -> labelWeightText = v },
                                    Triple(labelFields[2], labelPerServingText) { v: String -> labelPerServingText = v },
                                    Triple(labelFields[3], labelServingsText) { v: String -> labelServingsText = v },
                                ).forEach { (label, value, set) ->
                                    OutlinedTextField(
                                        value = value, onValueChange = set, label = { Text(label) },
                                        singleLine = true, modifier = Modifier.width(122.dp),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                        ),
                                    )
                                }
                            }
                        }
                        if (evidenceMode == 2) {
                            Text(stringResource(R.string.annotation_composer_recipe_version_hint), style = MaterialTheme.typography.labelSmall)
                            if (text.isNotBlank()) {
                                TextButton(onClick = {
                                    graph.store
                                        .latestStandardRecipeEvidence(text)?.input?.let { saved ->
                                            recipeVersionText = saved.recipeVersion ?: "v1"
                                            recipeTotalCarbsText = saved.recipeTotalCarbsG?.toString() ?: ""
                                            recipeTotalWeightText = saved.recipeTotalWeightG?.toString() ?: ""
                                            recipeConsumedWeightText = ""
                                            recipeFractionText = ""
                                        }
                                }) { Text(stringResource(R.string.annotation_composer_fill_saved_recipe)) }
                            }
                            val recipeFields = stringArrayResource(R.array.annotation_composer_recipe_fields)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    Triple(recipeFields[0], recipeVersionText) { v: String -> recipeVersionText = v },
                                    Triple(recipeFields[1], recipeTotalCarbsText) { v: String -> recipeTotalCarbsText = v },
                                    Triple(recipeFields[2], recipeTotalWeightText) { v: String -> recipeTotalWeightText = v },
                                    Triple(recipeFields[3], recipeConsumedWeightText) { v: String -> recipeConsumedWeightText = v },
                                    Triple(recipeFields[4], recipeFractionText) { v: String -> recipeFractionText = v },
                                ).forEach { (label, value, set) ->
                                    OutlinedTextField(
                                        value = value, onValueChange = set, label = { Text(label) },
                                        singleLine = true, modifier = Modifier.width(122.dp),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                        ),
                                    )
                                }
                            }
                        }
                        if (evidenceMode != 0) {
                            val computed = evidenceInput()?.totalCarbsG
                            Text(
                                computed?.let { stringResource(R.string.annotation_composer_recomputed, it) }
                                    ?: (evidenceError ?: stringResource(R.string.annotation_composer_fill_full_path)),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (computed == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }
                        }
                        Text(
                            stringResource(R.string.annotation_composer_nutrition_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val nutritionFields = stringArrayResource(R.array.annotation_composer_nutrition_fields)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                Triple(nutritionFields[0], foodProteinText) { value: String -> foodProteinText = value },
                                Triple(nutritionFields[1], foodFatText) { value: String -> foodFatText = value },
                                Triple(nutritionFields[2], foodKcalText) { value: String -> foodKcalText = value },
                            ).forEach { (label, value, setter) ->
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = {
                                        setter(it.filter { char ->
                                            char.isDigit() || char == '.' || char == ','
                                        })
                                    },
                                    modifier = Modifier.width(96.dp),
                                    label = { Text(label) },
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                    ),
                                )
                            }
                        }
                        // No photo at hand? The LLM often knows the carbs of a
                        // named product ("a Magnum bar", "a bottle of beer") from text alone.
                        var aiBusy by remember { mutableStateOf(false) }
                        if (AskClaude.apiKey(context) != null) {
                            aiAnswer?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row {
                                TextButton(
                                    enabled = !aiBusy && !photoAnalyzing &&
                                        (text.isNotBlank() || parts.isNotEmpty() || heldPhoto != null),
                                    onClick = {
                                        // A held fresh shot re-analyzes with the
                                        // current text as caption; else a history
                                        // photo of the dish; else text-only.
                                        heldPhoto?.let { analyzeHeldPhoto(it); return@TextButton }
                                        aiBusy = true
                                        val desc = composed()
                                        val pc = foodPersonalContext(
                                            desc, carbsByFood, recentFoodCarbs, foodMemories,
                                            Units.isMgdl(context),
                                        )
                                        val photoRef = (
                                            parts.keys.mapNotNull { photoByFood[it] } +
                                                listOfNotNull(photoByFood[text.trim()])
                                            ).firstOrNull()
                                        aiScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            var failed = false
                                            val r = try {
                                                val key = AskClaude.apiKey(context)!!
                                                val bytes = photoRef?.let {
                                                    loadScaledJpeg(File(photosDir(context), it))
                                                }
                                                if (bytes != null) {
                                                    AskClaude.describeFood(
                                                        key, bytes, caption = desc, personalContext = pc,
                                                        context = context,
                                                    ).text
                                                } else {
                                                    AskClaude.estimateCarbs(
                                                        key, desc, personalContext = pc,
                                                        knownComponents = knownComponents,
                                                        context = context,
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                failed = true
                                                AskClaude.errorText(context, e)
                                            }
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                aiBusy = false
                                                adoptAiResult(r, viaPhoto = photoRef != null, failed = failed)
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        stringResource(
                                            when {
                                                aiBusy || photoAnalyzing -> R.string.annotation_composer_computing_carbs
                                                heldPhoto != null -> R.string.annotation_composer_recompute_with_text
                                                else -> R.string.annotation_composer_carbs_from_description
                                            },
                                        ),
                                    )
                                }
                                // Composite meal → ONE history item; the split
                                // lives in the cached analysis, and the dish
                                // dictionary learns "bread = 12 g" from there.
                                if (aiComponents.size >= 2) {
                                    val evidenceNotConfirmedMsg =
                                        stringResource(R.string.annotation_composer_evidence_not_confirmed)
                                    TextButton(onClick = aiSubmit@ {
                                        val ts = entryTs()
                                        val content = aiComponents.joinToString(", ") { it.first }
                                        // TOTAL, not per-unit. `aiComponents` comes
                                        // from parseComponentsEstimate, which maps
                                        // `name to unitGrams` and DISCARDS the count
                                        // — correct for the dish dictionary above
                                        // ("bread = 12 g" is per slice and must stay
                                        // per slice), and wrong here, where the
                                        // number becomes `est_carbs` for the whole
                                        // meal. Summing unitGrams under-stated the
                                        // meal by exactly the ×N factor: "bread ×2 =
                                        // 14" contributed 14 instead of 28. Found
                                        // while auditing the READER, which turned out
                                        // to be innocent — this writer is where "the
                                        // multiplier is lost" was true.
                                        val total = com.diapilot.core.analysis
                                            .parseComponents(aiRaw ?: "")
                                            .sumOf { it.totalGrams }.takeIf { it > 0 }
                                            ?: aiComponents.sumOf { it.second }.takeIf { it > 0 }
                                            ?: foodCarbsText.trim().replace(',', '.').toDoubleOrNull()
                                        val enriched = withEnteredNutrition(buildList {
                                            aiRaw?.let(::add)
                                            foodDurationText.trim().replace(',', '.').toDoubleOrNull()
                                                ?.takeIf { it > 0 }
                                                ?.let { add("META_DURATION_MIN: ${it.coerceAtMost(240.0)}") }
                                        }.joinToString("\n"))
                                        val evidence = evidenceInput()
                                        if (evidenceMode != 0 && evidence == null) {
                                            evidenceError = evidenceNotConfirmedMsg
                                            return@aiSubmit
                                        }
                                        if (evidence != null) onAddFoodEvidence(
                                            ts, content, heldPhoto, evidence.totalCarbsG, enriched, evidence,
                                        ) else onAddFoodWithAnalysis(ts, content, heldPhoto, total, enriched)
                                        parts = emptyMap()
                                        resetAll()
                                    }) {
                                        Text(
                                            pluralStringResource(
                                                R.plurals.annotation_composer_one_entry_components,
                                                aiComponents.size, aiComponents.size,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // --- Note: dictionary + text/voice/photo -----------------
                    1 -> {
                        // Absorption modifiers: taken BEFORE/with a meal, they
                        // slow the carb peak (fiber/psyllium/fat). Feeds the
                        // food kinetics, not just a passive note.
                        Text(
                            stringResource(R.string.annotation_composer_absorption_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val absorptionLabels = stringArrayResource(R.array.annotation_composer_absorption_modifiers)
                            listOf(
                                absorptionLabels[0] to NoteTag.PSYLLIUM.key,
                                absorptionLabels[1] to NoteTag.FIBER.key,
                                absorptionLabels[2] to NoteTag.FATTY.key,
                            ).forEach { (disp, tag) ->
                                SuggestionChip(onClick = { submit(tag) }, label = { Text(disp) })
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Personal recurring context notes first, then the
                            // full starter dictionary — always visible. Tags are
                            // STORED keys (core's NoteTag); only their displayed
                            // label is translated.
                            (frequentTexts + STARTER_TAGS).distinct()
                                .filter {
                                    // Activity notes (incl. "walk · 40 min")
                                    // have their own icon row above.
                                    it.substringBefore('·').trim().lowercase() !in
                                        com.diapilot.core.analysis.ACTIVITY_TAGS
                                }
                                .forEach { tag ->
                                    SuggestionChip(
                                        onClick = { submitChip(tag) },
                                        label = { Text(TokenText.noteTag(context, tag)) },
                                    )
                                }
                        }
                        val speak = rememberSpeechInput { spoken ->
                            text = if (text.isBlank()) spoken else "$text $spoken"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(R.string.annotation_composer_note_placeholder)) },
                                singleLine = true,
                            )
                            IconButton(onClick = speak) { Text("🎤") }
                            IconButton(onClick = ::capturePhoto) { Text("📷") }
                            IconButton(onClick = { submit(text) }) { Text("✓") }
                        }
                    }
                    // --- Activity: its own first-class section ---
                    2 -> {
                        // Activity — its own glanceable row with icons. One tap
                        // logs a bout (default 40 min) that FEEDS the exercise
                        // model (acute drop + post-activity night sensitization),
                        // not just a passive note.
                        Text(
                            stringResource(R.string.annotation_composer_activity_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Two-step entry: pick the type, then duration and the
                        // TIME DIRECTION. A finished walk must land BEHIND now
                        // (note ts = start), or the model feeds a phantom
                        // future glucose drain into the forecast.
                        var pendingActivity by remember { mutableStateOf<NoteTag?>(null) }
                        var activityMin by remember { mutableStateOf(40) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val activityLabels = stringArrayResource(R.array.annotation_composer_activity_types)
                            listOf(
                                activityLabels[0] to NoteTag.WORKOUT,
                                activityLabels[1] to NoteTag.WALK,
                                activityLabels[2] to NoteTag.BIKE,
                                activityLabels[3] to NoteTag.RUN,
                            ).forEach { (disp, tag) ->
                                FilterChip(
                                    selected = pendingActivity == tag,
                                    onClick = {
                                        pendingActivity = if (pendingActivity == tag) null else tag
                                    },
                                    label = { Text(disp) },
                                )
                            }
                        }
                        pendingActivity?.let { tag ->
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Long walks matter: the model scales both the
                                // acute drain and the overnight sensitization
                                // by elevated minutes — 150 min is a big input.
                                listOf(15, 30, 40, 60, 90, 120, 150, 180).forEach { m ->
                                    FilterChip(
                                        selected = activityMin == m,
                                        onClick = { activityMin = m },
                                        label = {
                                            Text(
                                                if (m < 60) stringResource(R.string.annotation_composer_minutes_compact, m)
                                                else {
                                                    stringResource(
                                                        R.string.annotation_composer_hours_minutes_compact,
                                                        m / 60, m % 60,
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                            // The submitted note content below is the activity
                            // key plus the core-parsed minute-duration suffix
                            // ("walk · 40 min") — stored data, the same in every language.
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                androidx.compose.material3.FilledTonalButton(onClick = {
                                    // Starting now (or at the picked time).
                                    submit(com.diapilot.core.analysis.activityNote(tag, activityMin), tsMs = entryTs())
                                    pendingActivity = null
                                }) { Text(stringResource(R.string.annotation_composer_activity_start)) }
                                androidx.compose.material3.FilledTonalButton(onClick = {
                                    // Just finished: the bout STARTED duration ago.
                                    submit(
                                        com.diapilot.core.analysis.activityNote(tag, activityMin),
                                        tsMs = entryTs() - activityMin * 60_000L,
                                    )
                                    pendingActivity = null
                                }) { Text(stringResource(R.string.annotation_composer_activity_finished)) }
                            }
                            Text(
                                stringResource(R.string.annotation_composer_activity_finished_hint, activityMin),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // --- Insulin: manual bolus/basal entry + tag recent shots ---
                    3 -> {
                        var insKind by remember { mutableStateOf(0) }  // 0 bolus, 1 basal
                        var insUnits by remember { mutableStateOf("") }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = insKind == 0,
                                onClick = { insKind = 0 },
                                label = { Text(stringResource(R.string.annotation_composer_insulin_kind_bolus)) },
                            )
                            FilterChip(
                                selected = insKind == 1,
                                onClick = { insKind = 1 },
                                label = { Text(stringResource(R.string.annotation_composer_insulin_kind_basal)) },
                            )
                            OutlinedTextField(
                                value = insUnits,
                                onValueChange = { insUnits = it },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.annotation_composer_units_label)) },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                ),
                            )
                            androidx.compose.material3.FilledTonalIconButton(onClick = {
                                insUnits.replace(',', '.').toDoubleOrNull()
                                    ?.takeIf { it > 0 && it < 100 }?.let { u ->
                                        val ts = entryTs()
                                        if (insKind == 0) onAddBolus(ts, u) else onAddBasal(ts, u)
                                        insUnits = ""
                                        resetAll()
                                    }
                            }) { Text("✓") }
                        }
                        Text(
                            stringResource(R.string.annotation_composer_manual_entry_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (recentBoluses.isEmpty()) {
                            Text(
                                stringResource(R.string.annotation_composer_no_recent_boluses),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                stringResource(R.string.annotation_composer_recent_boluses_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                recentBoluses.takeLast(4).reversed().forEach { b ->
                                    val agoMin = (System.currentTimeMillis() - b.tsMs) / 60_000
                                    FilterChip(
                                        // Compare by timestamp: the object is
                                        // recreated on every state reload.
                                        selected = taggingBolus?.tsMs == b.tsMs,
                                        onClick = {
                                            taggingBolus = if (taggingBolus?.tsMs == b.tsMs) null else b
                                        },
                                        label = {
                                            val ago = if (agoMin >= 60) {
                                                stringResource(R.string.annotation_composer_hours_minutes_ago, agoMin / 60, agoMin % 60)
                                            } else {
                                                stringResource(R.string.annotation_composer_minutes_ago, agoMin)
                                            }
                                            val bg = bgAtShot[b.tsMs]?.let {
                                                com.diapilot.core.analysis.fmtBg(it, Units.isMgdl(context))
                                            }
                                            val purposeLabel = TokenText.bolusPurpose(context, b.purpose)
                                            Text(
                                                when {
                                                    bg != null && purposeLabel != null -> stringResource(
                                                        R.string.annotation_composer_bolus_chip_bg_purpose,
                                                        b.units, ago, bg, purposeLabel,
                                                    )
                                                    bg != null -> stringResource(
                                                        R.string.annotation_composer_bolus_chip_bg, b.units, ago, bg,
                                                    )
                                                    purposeLabel != null -> stringResource(
                                                        R.string.annotation_composer_bolus_chip_purpose,
                                                        b.units, ago, purposeLabel,
                                                    )
                                                    else -> stringResource(R.string.annotation_composer_bolus_chip, b.units, ago)
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                            taggingBolus?.let { b ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    BOLUS_TAGS.forEach { tag ->
                                        SuggestionChip(
                                            onClick = {
                                                onTagBolus(b.tsMs, tag)
                                                // Stay open: the user may want to
                                                // tag the next shot too; the chip's
                                                // updated "· $tag" suffix confirms.
                                                taggingBolus = null
                                            },
                                            label = { Text(TokenText.bolusPurpose(context, tag) ?: tag) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // --- Meter: a manual glucometer reading ---------------------
                    4 -> {
                        var bgText by remember { mutableStateOf("") }
                        val mgdl = Units.isMgdl(context)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = bgText,
                                onValueChange = { bgText = it },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.annotation_composer_meter_label,
                                            unitLabel(mgdl),
                                        ),
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                ),
                            )
                            androidx.compose.material3.FilledTonalIconButton(onClick = {
                                val v = bgText.trim().replace(',', '.').toDoubleOrNull()
                                val mmol = v?.let {
                                    if (mgdl) it / com.diapilot.core.analysis.MGDL_PER_MMOL_F else it
                                }
                                if (mmol != null && mmol in 1.0..35.0) {
                                    onAddMeter(entryTs(), mmol)
                                    bgText = ""
                                    resetAll()
                                }
                            }) { Text("✓") }
                        }
                        if (movingFast) {
                            Text(
                                stringResource(R.string.annotation_composer_meter_moving_fast_warning),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            stringResource(R.string.annotation_composer_meter_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // --- Command: natural-language data entry -------------------
                    5 -> {
                        val hasKey = AskClaude.apiKey(context) != null
                        Text(
                            stringResource(R.string.annotation_composer_command_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = cmdText,
                                onValueChange = { cmdText = it; cmdParsed = null; cmdError = null },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(R.string.annotation_composer_command_placeholder)) },
                                maxLines = 2,
                            )
                            val speak = rememberSpeechInput { spoken ->
                                cmdText = if (cmdText.isBlank()) spoken else "$cmdText $spoken"
                            }
                            IconButton(onClick = speak) { Text("🎤") }
                        }
                        if (hasKey) {
                            val notUnderstoodMsg = stringResource(R.string.annotation_composer_command_not_understood)
                            val blockedTemplate = stringResource(R.string.annotation_composer_command_blocked)
                            TextButton(
                                enabled = !cmdBusy && cmdText.isNotBlank(),
                                onClick = {
                                    cmdBusy = true; cmdError = null
                                    aiScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val r = try {
                                            val key = AskClaude.apiKey(context)!!
                                            parseCommandJson(AskClaude.parseCommand(key, cmdText))
                                        } catch (e: Exception) {
                                            null
                                        }
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            cmdBusy = false
                                            val mgdl2 = Units.isMgdl(context)
                                            if (r == null || r.action == "none" || r.describe(mgdl2, context) == null) {
                                                cmdError = notUnderstoodMsg
                                            } else {
                                                // Hard gate: LLM values never reach the DB
                                                // unvalidated. On violation nothing is offered
                                                // to write — show what was recognized and why
                                                // it is blocked; manual entry is the way out.
                                                val block = com.diapilot.core.analysis.validateCommandValues(
                                                    action = r.action, mmol = r.mmol,
                                                    units = r.units, grams = r.grams,
                                                    food = r.food, purpose = r.purpose,
                                                    activity = r.activity,
                                                )
                                                if (block != null) {
                                                    cmdError = blockedTemplate.format(
                                                        r.describe(mgdl2, context),
                                                        CommandText.block(context, block),
                                                    )
                                                } else cmdParsed = r
                                            }
                                        }
                                    }
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (cmdBusy) R.string.annotation_composer_command_parsing
                                        else R.string.annotation_composer_command_parse,
                                    ),
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.annotation_composer_command_need_key),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        cmdError?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        cmdParsed?.let { pc ->
                            val mgdl2 = Units.isMgdl(context)
                            Card(
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            ) {
                                Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.annotation_composer_command_add_preview, pc.describe(mgdl2, context) ?: ""),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val defaultFoodLabel = stringResource(R.string.annotation_composer_default_food_label)
                                        androidx.compose.material3.FilledTonalButton(onClick = {
                                            val ts = System.currentTimeMillis()
                                            when (pc.action) {
                                                "food" -> onAdd(ts, pc.food ?: defaultFoodLabel, null, "food", pc.grams)
                                                "meter" -> pc.mmol?.let { onAddMeter(ts, it) }
                                                "bolus" -> pc.units?.let {
                                                    onAddBolusWithPurpose(ts, it, pc.purpose)
                                                }
                                                "basal" -> pc.units?.let { onAddBasal(ts, it) }
                                                // Stored keys: the activity in any language becomes
                                                // its tag key (the store canonicalizes), the default is
                                                // a workout, and the rescue note is "dextrose ×1".
                                                "activity" -> onAdd(ts, pc.activity ?: NoteTag.WORKOUT.key, null, "tag", null)
                                                "dextrose" -> onAdd(ts, com.diapilot.core.analysis.rescueNote(1), null, "food", DEXTROSE_TABLET_G)
                                            }
                                            cmdParsed = null; cmdText = ""
                                            resetAll()
                                        }) { Text(stringResource(R.string.annotation_composer_command_add)) }
                                        TextButton(onClick = { cmdParsed = null }) { Text(stringResource(R.string.annotation_composer_cancel)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun photosDir(context: android.content.Context): File =
    File(context.filesDir, "photos").apply { mkdirs() }

/** Load a photo downscaled to ~maxDim px and re-compressed — API-friendly size. */
internal fun loadScaledJpeg(file: File, maxDim: Int = 1024): ByteArray? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    val bitmap = BitmapFactory.decodeFile(
        file.path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    return java.io.ByteArrayOutputStream().use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        out.toByteArray()
    }
}

@Composable
private fun PhotoThumb(name: String, description: String, size: Int = 40) {
    val context = LocalContext.current
    val bitmap = remember(name) {
        val f = File(photosDir(context), name)
        if (f.exists()) {
            BitmapFactory.decodeFile(
                f.path,
                BitmapFactory.Options().apply { inSampleSize = 8 },
            )
        } else null
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
    }
}

/** Public thumbnail for other screens (history merge rows). */
@Composable
fun PhotoThumbPublic(name: String, description: String, size: Int = 40) =
    PhotoThumb(name, description, size)

@Composable
fun AnnotationRow(
    annotation: Annotation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = remember { SimpleDateFormat("HH:mm, d MMM", Locale.getDefault()) }
    // Type-at-a-glance icon so a list of notes isn't a wall of identical rows.
    val head = annotation.content.substringBefore('·').trim().lowercase()
    val icon = when {
        com.diapilot.core.analysis.isRescueNote(head) -> "🍬"
        head in com.diapilot.core.analysis.ACTIVITY_TAGS -> "💪"
        head in com.diapilot.core.analysis.ABSORPTION_SLOW_TAGS -> "🌾"
        annotation.mediaRef != null -> "📷"
        annotation.kind == "food" || annotation.estCarbs != null -> "🍽"
        else -> "📝"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The stored content's HEAD may be a known token (a note tag, an
        // activity, the rescue prefix); TokenText.noteTag translates it and
        // passes anything else through unchanged.
        val displayContent = TokenText.noteTag(LocalContext.current, annotation.content)
        annotation.mediaRef?.let { PhotoThumb(it, displayContent) }
        Text(
            "$icon ${fmt.format(Date(annotation.tsMs))} · $displayContent",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        // Edit affordance: the row IS clickable, the pencil says so.
        Text(
            "✎",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

/** One editable component (Compose-observable): the user edits the natural
 *  PORTION (g); carbs (the model's number) auto-fill from the concept density
 *  and can be overridden. */
private class CompRow(name: String, count: Int, portion: Double?, carbs: Double) {
    var name by mutableStateOf(name)
    var count by mutableStateOf(count.coerceAtLeast(1))
    var portionText by mutableStateOf(portion?.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "")
    var carbsText by mutableStateOf(carbs.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "")

    /** Recompute carbs: from the entered portion, else — when no portion is
     *  known (unit count only) — from the concept's typical serving. */
    fun deriveCarbs() {
        val id = com.diapilot.core.analysis.conceptFor(name)?.id
        val p = portionText.trim().replace(',', '.').toDoubleOrNull()
        val carbs = if (p != null) com.diapilot.core.analysis.carbsForPortion(id, p)
        else com.diapilot.core.analysis.typicalCarbs(id)
        carbs?.let { carbsText = "%.0f".format(it) }
    }
}

/**
 * Inline editor for an existing annotation: text, timestamp (±30 min steps),
 * retake photo, delete with confirmation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnnotationEditor(
    annotation: Annotation,
    onSave: (tsMs: Long, content: String, mediaRef: String?) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    /**
     * "Repeat" — log the SAME dish for NOW.
     *
     * Null hides the button: repeating only makes sense for food, and this
     * editor also opens for context notes.
     *
     * The photo is deliberately NOT copied: the shot shows the PAST meal, and
     * attaching it to the new one would create a record that claims more than
     * it knows. The composition travels through the parsed text, not the picture.
     */
    onRepeat: (() -> Unit)? = null,
    /** Half portion / double — see `FoodPortionScalingV1`. Null hides the buttons. */
    onScalePortion: ((Double) -> Unit)? = null,
    // Food convenience, same as adding: autocomplete + quick-pick dishes.
    foodLabels: List<String> = emptyList(),
    carbsByFood: Map<String, Double> = emptyMap(),
    recentFoodTexts: List<String> = emptyList(),
    foodMemories: Map<String, com.diapilot.core.analysis.FoodMemory> = emptyMap(),
    recentFoodCarbs: Map<String, Double> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val graph = LocalAppGraph.current
    // A stored tag ("walk · 40 min") is edited in the UI language; saving stores
    // it back as the key (the store canonicalizes tag-shaped notes).
    var text by remember(annotation.id) { mutableStateOf(TokenText.noteTag(context, annotation.content)) }
    var tsMs by remember(annotation.id) { mutableStateOf(annotation.tsMs) }
    var mediaRef by remember(annotation.id) { mutableStateOf(annotation.mediaRef) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var analysis by remember(annotation.id) { mutableStateOf(annotation.analysis) }
    // True while [analysis] holds an error message rather than an analysis.
    var analysisFailed by remember(annotation.id) { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    // Grams as text: "" = unknown; comma decimals accepted.
    // Which component is being taught to the dictionary (⚠ tapped).
    var assigningConcept by remember(annotation.id) { mutableStateOf<String?>(null) }
    var carbsText by remember(annotation.id) {
        mutableStateOf(annotation.estCarbs?.let {
            if (it == Math.floor(it)) it.toInt().toString() else it.toString()
        } ?: "")
    }
    val legacyExactMarker = remember(annotation.id) {
        annotation.analysis.orEmpty().lineSequence().any {
            it.trim().equals("META_EXACT_CARBS: true", ignoreCase = true)
        }
    }
    val currentEvidence = remember(annotation.id) {
        graph.store.carbEvidenceKnownAt(annotation.id, Long.MAX_VALUE)
    }
    val initialInput = currentEvidence?.input
    var evidenceMode by remember(annotation.id) { mutableStateOf(when (initialInput?.source) {
        com.diapilot.core.collector.CarbEvidenceSourceV1.LABEL_WEIGHT -> 1
        com.diapilot.core.collector.CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT -> 2
        else -> 0
    }) }
    fun fmtEvidence(v: Double?): String = v?.let { if (it == Math.floor(it)) it.toInt().toString() else it.toString() } ?: ""
    var labelPer100Text by remember(annotation.id) { mutableStateOf(fmtEvidence(initialInput?.labelCarbsPer100g)) }
    var labelWeightText by remember(annotation.id) { mutableStateOf(fmtEvidence(initialInput?.weighedEdibleG)) }
    var labelPerServingText by remember(annotation.id) { mutableStateOf(fmtEvidence(initialInput?.labelCarbsPerServingG)) }
    var labelServingsText by remember(annotation.id) { mutableStateOf(fmtEvidence(initialInput?.servings)) }
    var recipeVersionText by remember(annotation.id) { mutableStateOf(initialInput?.recipeVersion ?: "v1") }
    var recipeTotalCarbsText by remember(annotation.id) { mutableStateOf(fmtEvidence(initialInput?.recipeTotalCarbsG)) }
    var recipeTotalWeightText by remember(annotation.id) { mutableStateOf(fmtEvidence(initialInput?.recipeTotalWeightG)) }
    var recipeConsumedWeightText by remember(annotation.id) { mutableStateOf(fmtEvidence(initialInput?.recipeConsumedWeightG)) }
    var recipeFractionText by remember(annotation.id) { mutableStateOf(fmtEvidence(initialInput?.recipeConsumedFraction)) }
    var alcoholPresent by remember(annotation.id) { mutableStateOf(initialInput?.alcoholPresent ?: false) }
    var evidenceError by remember(annotation.id) { mutableStateOf<String?>(null) }
    var showEvidenceDetails by remember(annotation.id) {
        mutableStateOf(
            currentEvidence != null || legacyExactMarker ||
                annotation.analysis.orEmpty().contains("META_DURATION_MIN:", ignoreCase = true),
        )
    }
    var durationText by remember(annotation.id) {
        val value = annotation.analysis.orEmpty().lineSequence()
            .firstOrNull { it.trim().startsWith("META_DURATION_MIN:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.replace(',', '.')?.toDoubleOrNull()
        mutableStateOf(value?.let { if (it == Math.floor(it)) it.toInt().toString() else it.toString() } ?: "")
    }
    val savedNutrition = remember(annotation.id) {
        com.diapilot.core.analysis.parseFoodNutrition(annotation.analysis)
    }
    var proteinText by remember(annotation.id) {
        mutableStateOf(savedNutrition.proteinG?.let { "%.1f".format(it) } ?: "")
    }
    var fatText by remember(annotation.id) {
        mutableStateOf(savedNutrition.fatG?.let { "%.1f".format(it) } ?: "")
    }
    var kcalText by remember(annotation.id) {
        mutableStateOf(savedNutrition.kcal?.let { "%.0f".format(it) } ?: "")
    }
    fun applyNutrition(result: String) {
        val n = com.diapilot.core.analysis.parseFoodNutrition(result)
        n.proteinG?.let { proteinText = "%.1f".format(it) }
        n.fatG?.let { fatText = "%.1f".format(it) }
        n.kcal?.let { kcalText = "%.0f".format(it) }
    }
    fun parsedCarbs(): Double? =
        carbsText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    fun editorEvidenceInput(total: Double?): com.diapilot.core.collector.CarbEvidenceInputV1? {
        fun n(raw: String): Double? = raw.trim().replace(',', '.').toDoubleOrNull()
        val duration = n(durationText)?.takeIf { it > 0.0 }
        val timing = com.diapilot.core.collector.CarbUncertaintyV1(
            if (duration == null) "intake_time_point" else "user_entered_duration",
        )
        val candidate = when (evidenceMode) {
            1 -> com.diapilot.core.collector.CarbEvidenceInputV1(
                source = com.diapilot.core.collector.CarbEvidenceSourceV1.LABEL_WEIGHT,
                userConfirmed = true, labelCarbsPer100g = n(labelPer100Text),
                weighedEdibleG = n(labelWeightText), labelCarbsPerServingG = n(labelPerServingText),
                servings = n(labelServingsText), intakeDurationMin = duration,
                amountUncertainty = com.diapilot.core.collector.CarbUncertaintyV1("recomputed_label_rounding"),
                timingUncertainty = timing, alcoholPresent = alcoholPresent,
            )
            2 -> com.diapilot.core.collector.CarbEvidenceInputV1(
                source = com.diapilot.core.collector.CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT,
                userConfirmed = true, recipeVersion = recipeVersionText.trim(),
                recipeTotalCarbsG = n(recipeTotalCarbsText), recipeTotalWeightG = n(recipeTotalWeightText),
                recipeConsumedWeightG = n(recipeConsumedWeightText), recipeConsumedFraction = n(recipeFractionText),
                intakeDurationMin = duration,
                amountUncertainty = com.diapilot.core.collector.CarbUncertaintyV1("ingredient_density_and_rounding"),
                timingUncertainty = timing, alcoholPresent = alcoholPresent,
            )
            else -> if (currentEvidence != null && total != null) com.diapilot.core.collector.CarbEvidenceInputV1(
                source = com.diapilot.core.collector.CarbEvidenceSourceV1.USER_ESTIMATE,
                userConfirmed = true, totalCarbsG = total, intakeDurationMin = duration,
                amountUncertainty = com.diapilot.core.collector.CarbUncertaintyV1("user_point_estimate"),
                timingUncertainty = timing, alcoholPresent = alcoholPresent,
            ) else null
        }
        return candidate?.let { runCatching { it.validated() }.getOrNull() }
    }
    // Editable component split, seeded from the saved composition. Save
    // writes it back (the model's units); the dish name stays free.
    val compRows = remember(annotation.id) {
        com.diapilot.core.analysis.parseComponents(annotation.analysis ?: "")
            .map { CompRow(it.name, it.count, it.portionGrams, it.unitGrams) }
            .toMutableStateList()
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("HH:mm, d MMM", Locale.getDefault()) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        pendingPhoto = null
        if (ok && file != null && file.length() > 0) {
            mediaRef = file.name  // old file cleaned up on save
        } else {
            file?.delete()
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Header owns the destructive action (with its confirm dialog) —
            // the bottom row is then a clean Cancel/Save pair.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "🕐 ${fmt.format(Date(tsMs))} ✎",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    // Tap the timestamp for the FULL date+time picker —
                    // ±30m steppers can't move an event to another day.
                    // weight(1f) so the buttons (incl. 🗑) NEVER get pushed
                    // off-screen by a long timestamp.
                    modifier = Modifier
                        .weight(1f)
                        .clickable { pickDateTime(context, tsMs) { tsMs = it } },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { tsMs -= 30 * 60_000 },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    ) { Text(stringResource(R.string.annotation_composer_minus_30m)) }
                    TextButton(
                        onClick = {
                            tsMs = (tsMs + 30 * 60_000).coerceAtMost(System.currentTimeMillis())
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    ) { Text(stringResource(R.string.annotation_composer_plus_30m)) }
                    IconButton(onClick = { confirmDelete = true }) { Text("🗑") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                onRepeat?.let { repeat ->
                    TextButton(
                        onClick = repeat,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    ) { Text(stringResource(R.string.annotation_composer_repeat_now)) }
                }
                onScalePortion?.let { scale ->
                    TextButton(
                        onClick = { scale(0.5) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    ) { Text(stringResource(R.string.annotation_composer_half_portion)) }
                    TextButton(
                        onClick = { scale(2.0) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    ) { Text(stringResource(R.string.annotation_composer_double_portion)) }
                }
            }
            val speakEdit = rememberSpeechInput { spoken ->
                text = if (text.isBlank()) spoken else "$text $spoken"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                mediaRef?.let { PhotoThumb(it, text, size = 56) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (mediaRef != null) 8.dp else 0.dp),
                    singleLine = true,
                )
                IconButton(onClick = speakEdit) { Text("🎤") }
                IconButton(onClick = {
                    val file = File(photosDir(context), "IMG_${System.currentTimeMillis()}.jpg")
                    pendingPhoto = file
                    takePicture.launch(
                        FileProvider.getUriForFile(context, AppIdentity.FILE_PROVIDER_AUTHORITY, file),
                    )
                }) { Text("📷") }
            }
            if (annotation.kind == "food" || annotation.estCarbs != null) {
                val editQ = text.trim()
                val editUniverse = remember(foodLabels, carbsByFood, foodMemories, recentFoodTexts) {
                    (recentFoodTexts + foodLabels + carbsByFood.keys + foodMemories.keys)
                        .distinctBy { it.lowercase() }
                }
                if (editQ.length >= 2) {
                    editUniverse.withIndex()
                        .filter { (_, name) ->
                            name.contains(editQ, ignoreCase = true) &&
                                !name.equals(editQ, ignoreCase = true)
                        }
                        .sortedWith(
                            compareBy<IndexedValue<String>>(
                                { (_, name) ->
                                    when {
                                        name.startsWith(editQ, ignoreCase = true) -> 0
                                        name.split(Regex("""[\s,;/]+"""))
                                            .any { it.startsWith(editQ, ignoreCase = true) } -> 1
                                        else -> 2
                                    }
                                },
                                { it.index },
                            ),
                        )
                        .take(4)
                        .forEach { (_, name) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        text = name
                                        recentFoodCarbs[name]?.let {
                                            carbsText = if (it == Math.floor(it)) {
                                                it.toInt().toString()
                                            } else "%.1f".format(it)
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(name, modifier = Modifier.weight(1f))
                                recentFoodCarbs[name]?.let {
                                    Text(
                                        stringResource(R.string.annotation_composer_approx_grams, it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                }
            }
            // Activity note: duration as chips (rides in the text, the parser
            // reads "· N min"); the timestamp above is the bout's START.
            run {
                val head = text.substringBefore('·').trim().lowercase()
                if (head in com.diapilot.core.analysis.ACTIVITY_TAGS) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 40, 60, 90, 120, 150, 180).forEach { m ->
                            val cur = com.diapilot.core.analysis.ACTIVITY_MINUTES_REGEX.find(text)
                                ?.groupValues?.get(1)?.toIntOrNull()
                            FilterChip(
                                selected = cur == m,
                                // The field shows the note in the UI language; the
                                // store saves it back as "<key> · N min".
                                onClick = {
                                    text = "$head · " + context.localized().getString(R.string.note_tag_minutes, m)
                                },
                                label = {
                                    Text(
                                        if (m < 60) stringResource(R.string.annotation_composer_minutes_compact, m)
                                        else {
                                            stringResource(
                                                R.string.annotation_composer_hours_minutes_compact,
                                                m / 60, m % 60,
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.annotation_composer_note_time_is_start),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Composite-meal editing, same machinery as the composer: the
            // content IS a comma-list of dishes ("beer, chips ×2"), parseable
            // both ways. Parts chips + steppers + add-from-history — turn a
            // note into a multi-dish meal without retyping.
            if (annotation.kind == "food" || annotation.estCarbs != null) {
                // EDITABLE component split (the model's units): each row is
                // name + count + grams + ✕, plus "add component". Saved back to
                // the annotation's composition on Save. The human dish name
                // (text above) is separate and free.
                // A column legend for rows that don't exist is noise: ice cream,
                // beer, meatballs — most dishes are ONE thing and never get a
                // split. "add component" below stays, it is how a split starts.
                if (compRows.isNotEmpty()) {
                    Text(
                        stringResource(R.string.annotation_composer_component_split_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // TWO lines per component, not five controls jammed into one.
                // Crammed, the numeric fields were 54.dp wide with words inside
                // ("portion", "carbs") — the placeholder wrapped mid-word and blew
                // the row's height up. Units live OUTSIDE the fields now, so there
                // is nothing left in them to wrap.
                compRows.forEach { comp ->
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = comp.name,
                                onValueChange = { comp.name = it; comp.deriveCarbs() },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.annotation_composer_component_placeholder)) },
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "✕",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .clickable { compRows.remove(comp) }
                                    .padding(start = 10.dp, end = 2.dp),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                "−",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .clickable { if (comp.count > 1) comp.count-- }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            Text("×${comp.count}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "＋",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .clickable { comp.count++ }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            OutlinedTextField(
                                value = comp.portionText,
                                onValueChange = { v ->
                                    comp.portionText = v.filter { it.isDigit() || it == '.' || it == ',' }
                                    comp.deriveCarbs()
                                },
                                modifier = Modifier.width(68.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.annotation_composer_grams_arrow),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = comp.carbsText,
                                onValueChange = { v ->
                                    comp.carbsText = v.filter { it.isDigit() || it == '.' || it == ',' }
                                },
                                modifier = Modifier.width(68.dp).padding(start = 6.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.annotation_composer_grams_unit),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // THE MODEL LOSES CARBS SILENTLY. carbDrivers is built as
                        // `mapNotNull { conceptId }` — a component the dictionary
                        // doesn't recognise is dropped without a word. "Pepperoni
                        // pizza" came out of Vision as "base = 55 g", which
                        // matches nothing, so most of the pizza vanished and the
                        // model filed it under "vegetables + cheese" — by its garnish.
                        // Across a real diary that can be a meaningful share of all
                        // carbs, invisible. Nobody ever used the alias table (0 rows)
                        // because nothing ever said anything was missing.
                        val grams = comp.carbsText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                        if (comp.name.isNotBlank() && grams > 0 &&
                            com.diapilot.core.analysis.conceptFor(comp.name) == null
                        ) {
                            // Tappable: the loss and its fix belong in the same
                            // place. The alias dialog has existed all along — in
                            // the food library, three screens away, and the table
                            // it writes has 0 rows after eight months.
                            Text(
                                stringResource(
                                    R.string.annotation_composer_concept_missing_warning,
                                    comp.name.trim(), grams * comp.count,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clickable { assigningConcept = comp.name.trim() }
                                    .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        if (compRows.isEmpty()) {
                            // Splitting a plain dish starts FROM that dish: it is
                            // its own first component, carrying its name and its
                            // carbs. Starting from a blank row threw both away —
                            // the carbs field vanished (a split owns the number)
                            // and the total became 0. Worse than cosmetic: save
                            // takes `sum.takeIf { it > 0 }`, so 0 → null and
                            // Save ERASED the 40 g from the note.
                            compRows.add(CompRow(text.trim(), 1, null, parsedCarbs() ?: 0.0))
                        } else {
                            compRows.add(CompRow("", 1, null, 0.0))
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (compRows.isEmpty()) R.string.annotation_composer_split_into_components
                            else R.string.annotation_composer_add_component_plain,
                        ),
                    )
                }

                // Total carbs = SUM of the component carbs (live). The old
                // "number of portions" chips and "from history" list are gone —
                // the 🧩 editor above is the single source of composition now.
                //
                // ONLY with components. The sum of an empty split is not "0 g",
                // it is nothing — and printed as "total carbs: 0" it sat
                // right under a "carbs: 40" field on every plain dish, the
                // screen contradicting itself about the one number that matters.
                if (compRows.isNotEmpty()) {
                    val compCarbsSum = compRows.sumOf {
                        (it.carbsText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0) * it.count
                    }
                    Text(
                        stringResource(R.string.annotation_composer_total_carbs, compCarbsSum),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // And what the MODEL will get out of that total — the number
                    // that was never shown. A total that looks fine while the
                    // model sees only a fraction of it is the difference between
                    // a pizza and a side salad.
                    val lost = compRows.sumOf { c ->
                        val g = (c.carbsText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0) * c.count
                        if (c.name.isNotBlank() && com.diapilot.core.analysis.conceptFor(c.name) == null) g else 0.0
                    }
                    if (lost > 0 && compCarbsSum > 0) {
                        Text(
                            stringResource(
                                R.string.annotation_composer_model_sees_partial,
                                compCarbsSum - lost, compCarbsSum, 100 * lost / compCarbsSum,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            // Manual carbs — only when there is NO component split (a plain note).
            if ((annotation.kind == "food" || annotation.mediaRef != null || annotation.estCarbs != null) &&
                compRows.isEmpty()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = carbsText,
                        onValueChange = { carbsText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.annotation_composer_carbs_g_field_label)) },
                        placeholder = { Text(stringResource(R.string.annotation_composer_example_45)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        ),
                    )
                }
            }
            if (annotation.kind == "food" || annotation.estCarbs != null) {
                TextButton(onClick = { showEvidenceDetails = !showEvidenceDetails }) {
                    Text(
                        stringResource(
                            if (showEvidenceDetails) R.string.annotation_composer_evidence_toggle_hide
                            else R.string.annotation_composer_evidence_toggle_show,
                        ),
                    )
                }
                if (showEvidenceDetails) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { value ->
                            durationText = value.filter { it.isDigit() || it == '.' || it == ',' }
                        },
                        modifier = Modifier.width(150.dp),
                        label = { Text(stringResource(R.string.annotation_composer_duration_min_label)) },
                        placeholder = { Text(stringResource(R.string.annotation_composer_example_30)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        ),
                    )
                    FilterChip(
                        selected = alcoholPresent,
                        onClick = { alcoholPresent = !alcoholPresent },
                        label = { Text(stringResource(R.string.annotation_composer_alcohol_label)) },
                    )
                }
                val evidenceModeLabels2 = stringArrayResource(R.array.annotation_composer_evidence_source_modes)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1, 2).zip(evidenceModeLabels2.toList()).forEach { (id, label) ->
                        FilterChip(selected = evidenceMode == id, onClick = { evidenceMode = id }, label = { Text(label) })
                    }
                }
                if (legacyExactMarker && currentEvidence == null) {
                    Text(
                        stringResource(R.string.annotation_composer_legacy_exact_flag_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (evidenceMode == 1) {
                    val labelFields2 = stringArrayResource(R.array.annotation_composer_label_weight_fields)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple(labelFields2[0], labelPer100Text) { v: String -> labelPer100Text = v },
                            Triple(labelFields2[1], labelWeightText) { v: String -> labelWeightText = v },
                            Triple(labelFields2[2], labelPerServingText) { v: String -> labelPerServingText = v },
                            Triple(labelFields2[3], labelServingsText) { v: String -> labelServingsText = v },
                        ).forEach { (label, value, set) ->
                            OutlinedTextField(value = value, onValueChange = set, label = { Text(label) },
                                singleLine = true, modifier = Modifier.width(122.dp))
                        }
                    }
                }
                if (evidenceMode == 2) {
                    val recipeFields2 = stringArrayResource(R.array.annotation_composer_recipe_fields)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple(recipeFields2[0], recipeVersionText) { v: String -> recipeVersionText = v },
                            Triple(recipeFields2[1], recipeTotalCarbsText) { v: String -> recipeTotalCarbsText = v },
                            Triple(recipeFields2[2], recipeTotalWeightText) { v: String -> recipeTotalWeightText = v },
                            Triple(recipeFields2[3], recipeConsumedWeightText) { v: String -> recipeConsumedWeightText = v },
                            Triple(recipeFields2[4], recipeFractionText) { v: String -> recipeFractionText = v },
                        ).forEach { (label, value, set) ->
                            OutlinedTextField(value = value, onValueChange = set, label = { Text(label) },
                                singleLine = true, modifier = Modifier.width(122.dp))
                        }
                    }
                }
                evidenceError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 90).forEach { minutes ->
                        FilterChip(
                            selected = durationText.replace(',', '.').toDoubleOrNull() == minutes.toDouble(),
                            onClick = { durationText = minutes.toString() },
                            label = { Text(stringResource(R.string.annotation_composer_minutes_short, minutes)) },
                        )
                    }
                    if (durationText.isNotBlank()) {
                        TextButton(onClick = { durationText = "" }) { Text(stringResource(R.string.annotation_composer_immediately)) }
                    }
                }
                Text(
                    stringResource(R.string.annotation_composer_amount_duration_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            if (annotation.kind == "food" || annotation.estCarbs != null) {
                Text(
                    stringResource(R.string.annotation_composer_nutrition_estimate_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val nutritionFields2 = stringArrayResource(R.array.annotation_composer_nutrition_fields)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple(nutritionFields2[0], proteinText) { v: String -> proteinText = v },
                        Triple(nutritionFields2[1], fatText) { v: String -> fatText = v },
                        Triple(nutritionFields2[2], kcalText) { v: String -> kcalText = v },
                    ).forEach { (label, value, set) ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = { set(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
                            label = { Text(label) },
                            singleLine = true,
                            modifier = Modifier.width(96.dp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            ),
                        )
                    }
                }
            }
            // Analysis is reference material, not reading matter: collapsed to
            // one toggle line until asked (it was drowning the whole editor).
            var showAnalysis by remember(annotation.id) { mutableStateOf(false) }

            // No photo: the LLM can still price a described product/dish.
            if (mediaRef == null && annotation.kind == "food" &&
                AskClaude.apiKey(context) != null
            ) {
                analysis?.let { a ->
                    TextButton(onClick = { showAnalysis = !showAnalysis }) {
                        Text(
                            stringResource(
                                if (showAnalysis) R.string.annotation_composer_analysis_expanded
                                else R.string.annotation_composer_analysis_collapsed,
                            ),
                        )
                    }
                    if (showAnalysis) Text(a, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !analyzing && text.isNotBlank(), onClick = {
                    analyzing = true
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        var failed = false
                        val result = try {
                            AskClaude.estimateCarbs(
                                AskClaude.apiKey(context)!!, text,
                                context = context,
                            )
                        } catch (e: Exception) {
                            failed = true
                            AskClaude.errorText(context, e)
                        }
                        var grams: Double? = null
                        if (!failed) {
                            val store = graph.store
                            store.setAnnotationAnalysis(annotation.id, result)
                            grams = com.diapilot.core.analysis.parseCarbsEstimate(result)
                            grams?.let { store.setAnnotationCarbs(annotation.id, it, source = "llm") }
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            analysis = result
                            analysisFailed = failed
                            analyzing = false
                            applyNutrition(result)
                            grams?.let {
                                carbsText = if (it == Math.floor(it)) it.toInt().toString() else it.toString()
                            }
                        }
                    }
                }) {
                    Text(
                        stringResource(
                            when {
                                analyzing -> R.string.annotation_composer_computing_carbs
                                analysis == null -> R.string.annotation_composer_carbs_from_description
                                else -> R.string.annotation_composer_recompute_from_description
                            },
                        ),
                    )
                }
            }
            // Claude Vision: describe the food photo; the note text goes along as
            // a caption, so typing a clarification + regenerate refines the result.
            if (mediaRef != null && AskClaude.apiKey(context) != null) {
                fun analyze() {
                    analyzing = true
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        var failed = false
                        val result = try {
                            val bytes = loadScaledJpeg(File(photosDir(context), mediaRef!!))
                                ?: error("Photo not found")
                            AskClaude.describeFood(
                                AskClaude.apiKey(context)!!, bytes,
                                caption = text,
                                context = context,
                            ).text
                        } catch (e: Exception) {
                            failed = true
                            AskClaude.errorText(context, e)
                        }
                        // Paid for once — cache on the annotation itself.
                        var parsedFromVision: Double? = null
                        if (!failed) {
                            val store = graph.store
                            store.setAnnotationAnalysis(annotation.id, result)
                            parsedFromVision =
                                com.diapilot.core.analysis.parseCarbsEstimate(result)
                            parsedFromVision?.let { store.setAnnotationCarbs(annotation.id, it, source = "llm") }
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            analysis = result
                            analysisFailed = failed
                            analyzing = false
                            applyNutrition(result)
                            parsedFromVision?.let {
                                carbsText = if (it == Math.floor(it)) it.toInt().toString() else it.toString()
                            }
                        }
                    }
                }
                analysis?.let { a ->
                    val dishName = a.lineSequence().firstOrNull()?.trim()?.trimEnd('.')
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showAnalysis = !showAnalysis }) {
                            Text(
                                stringResource(
                                    if (showAnalysis) R.string.annotation_composer_analysis_expanded
                                    else R.string.annotation_composer_analysis_collapsed,
                                ),
                            )
                        }
                        if (!dishName.isNullOrBlank() && !analysisFailed && dishName != text) {
                            TextButton(onClick = { text = dishName }) { Text(stringResource(R.string.annotation_composer_use_as_name)) }
                        }
                    }
                    if (showAnalysis) Text(a, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !analyzing, onClick = ::analyze) {
                    Text(
                        stringResource(
                            when {
                                analyzing -> R.string.annotation_composer_photo_analyzing
                                analysis == null -> R.string.annotation_composer_whats_in_photo
                                else -> R.string.annotation_composer_regenerate
                            },
                        ),
                    )
                }
            }
            assigningConcept?.let { raw ->
                ConceptAssignDialog(
                    name = raw,
                    current = com.diapilot.core.analysis.conceptFor(raw)?.id,
                    onAssign = { id ->
                        assigningConcept = null
                        Thread {
                            val st = graph.store
                                as? SqliteCollectorStore
                            st?.upsertConceptAlias(raw, id)
                            st?.let { com.diapilot.core.analysis.setUserConceptAliases(it.conceptAliases()) }
                            // The donor corpus maps names→concepts too — rebuild
                            // it or the fix never reaches the model.
                            TwinCache.invalidate()
                        }.start()
                    },
                    onReset = {
                        assigningConcept = null
                        Thread {
                            val st = graph.store
                                as? SqliteCollectorStore
                            st?.deleteConceptAlias(raw)
                            st?.let { com.diapilot.core.analysis.setUserConceptAliases(it.conceptAliases()) }
                            TwinCache.invalidate()
                        }.start()
                    },
                    onDismiss = { assigningConcept = null },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.annotation_composer_cancel)) }
                val fillFullPathMsg = stringResource(R.string.annotation_composer_fill_recomputable_path)
                val newRecipeVersionMsg = stringResource(R.string.annotation_composer_new_recipe_version_needed)
                TextButton(onClick = saveAnnotation@ {
                    if (text.isNotBlank() || mediaRef != null) {
                        val store = graph.store
                        // Total carbs = sum of the component carbs when a split
                        // exists; the manual field otherwise.
                        val total = if (compRows.isNotEmpty()) {
                            compRows.sumOf {
                                (it.carbsText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0) * it.count
                            }.takeIf { it > 0 }
                        } else parsedCarbs()
                        val structured = editorEvidenceInput(total)
                        if (evidenceMode != 0 && structured == null) {
                            evidenceError = fillFullPathMsg
                            return@saveAnnotation
                        }
                        if (structured != null && runCatching {
                            com.diapilot.core.collector.validateRecipeRevisionV1(currentEvidence?.input, structured)
                        }.isFailure) {
                            evidenceError = newRecipeVersionMsg
                            return@saveAnnotation
                        }
                        if (structured != null) {
                            val now = System.currentTimeMillis()
                            store.appendCarbEvidence(annotation.id, tsMs, structured, now, now)
                        } else {
                            store.setAnnotationCarbs(annotation.id, total, source = "manual")
                        }
                        // Persist the edited composition (carbs + portion),
                        // preserving any prose lines; blank when no components remain.
                        val sostav = compRows.filter { it.name.isNotBlank() }.joinToString("\n") { c ->
                            com.diapilot.core.analysis.sostavLine(
                                c.name, c.count,
                                c.carbsText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0,
                                c.portionText.trim().replace(',', '.').toDoubleOrNull(),
                            )
                        }
                        // Both marker forms: a stored analysis may carry the older Russian ones.
                        val machinePrefixes = listOf(
                            com.diapilot.core.analysis.COMPONENT_LINE_PREFIX,
                            com.diapilot.core.analysis.PROTEIN_LINE_PREFIX,
                            com.diapilot.core.analysis.FAT_LINE_PREFIX,
                            com.diapilot.core.analysis.KCAL_LINE_PREFIX,
                        ).flatMap { com.diapilot.core.analysis.markerForms(it) } + listOf(
                            "META_EXACT_CARBS",
                            "META_DURATION_MIN",
                        )
                        val prose = (analysis ?: annotation.analysis ?: "").lineSequence()
                            .filterNot {
                                val line = it.trim()
                                machinePrefixes.any { prefix ->
                                    line.startsWith("$prefix:", ignoreCase = true)
                                }
                            }
                            .joinToString("\n").trim()
                        fun parsed(v: String): Double? =
                            v.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
                        val nutrition = com.diapilot.core.analysis.nutritionLines(
                            com.diapilot.core.analysis.FoodNutrition(
                                proteinG = parsed(proteinText),
                                fatG = parsed(fatText),
                                kcal = parsed(kcalText),
                            ),
                        )
                        val metadata = buildList {
                            durationText.trim().replace(',', '.').toDoubleOrNull()
                                ?.takeIf { it > 0.0 }
                                ?.coerceAtMost(240.0)
                                ?.let { duration ->
                                    val rendered = if (duration == Math.floor(duration)) {
                                        duration.toInt().toString()
                                    } else duration.toString()
                                    add("META_DURATION_MIN: $rendered")
                                }
                        }.joinToString("\n")
                        val newAnalysis = listOf(prose, sostav, nutrition, metadata)
                            .filter { it.isNotBlank() }.joinToString("\n")
                        store.setAnnotationAnalysis(annotation.id, newAnalysis)
                        // The fallback content below is the stored placeholder
                        // token (matches TokenText.NOTE_TAGS / submit()'s own
                        // default), not UI text.
                        onSave(tsMs, text.trim().ifEmpty { NoteTag.PHOTO.key }, mediaRef)
                    }
                }) { Text(stringResource(R.string.annotation_composer_save)) }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.annotation_composer_delete_note_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.annotation_composer_delete_note_confirm,
                        TokenText.noteTag(LocalContext.current, annotation.content),
                        fmt.format(Date(annotation.tsMs)),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.annotation_composer_delete_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.annotation_composer_cancel)) }
            },
        )
    }
}
