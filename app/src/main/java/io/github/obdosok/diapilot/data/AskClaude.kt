package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.analysis.Confidence
import com.diapilot.core.analysis.glycemicStats
import com.diapilot.core.analysis.labelStats
import com.diapilot.core.collector.CollectorStore
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker
import io.github.obdosok.diapilot.i18n.LlmLanguage
import io.github.obdosok.diapilot.i18n.localized
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Ask your data": the user's question + a compact feature summary of their
 * own data -> Claude -> a plain-language answer grounded in the numbers.
 *
 * Safety invariant: all numbers are computed by the deterministic core and
 * shipped as context. The LLM explains; it is explicitly forbidden (system
 * prompt) from producing dosing instructions.
 *
 * Privacy: the summary leaves the device only when the user taps "send",
 * straight to the Anthropic API over the user's own key. No middleman.
 */
object AskClaude {

    const val PREF_API_KEY = "anthropic_api_key"
    private const val MODEL = "claude-opus-4-8"

    data class ChatMsg(val role: String, val text: String)  // role: user | assistant
    data class NutritionBatchInput(
        val id: String,
        val dish: String,
        val carbsG: Double?,
        val composition: String?,
        val missingFields: Set<String> = setOf("protein_g", "fat_g", "kcal"),
    )
    data class NutritionBatchOut(
        val id: String,
        val proteinG: Double?,
        val fatG: Double?,
        val kcal: Double?,
    )

    private fun systemPrompt(context: Context): String = """You are the assistant for the DiaPilot app, for a person with type 1 diabetes.
You are given a summary of their data (glucose, insulin, food, context) and a question.

STRICT RULES:
- NEVER name specific insulin doses or give dosing instructions ("inject X units" is forbidden in any form).
- Do not prescribe treatment. State observations and hypotheses from the data.
- If the question is about changing therapy settings (ISF, basal) — describe what the data shows, and add: discuss it with a doctor.
- Base your answer on specific numbers from the summary, and reference them.
- Be brief and to the point. If there isn't enough data to answer, say so directly.
${LlmLanguage.replyInstruction(context)}"""

    /**
     * Chat history shared across recompositions and persisted to prefs —
     * switching tabs or restarting the app must not lose the conversation.
     */
    object History {
        private const val PREF_CHAT = "ask_chat_history"
        private const val MAX = 50
        val messages = androidx.compose.runtime.mutableStateListOf<ChatMsg>()
        val busy = androidx.compose.runtime.mutableStateOf(false)
        private var loaded = false

        fun load(context: Context) {
            if (loaded) return
            loaded = true
            val raw = context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
                .getString(PREF_CHAT, null) ?: return
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    messages.add(ChatMsg(o.getString("role"), o.getString("text")))
                }
            }
        }

        fun append(context: Context, msg: ChatMsg) {
            messages.add(msg)
            while (messages.size > MAX) messages.removeAt(0)
            val arr = JSONArray()
            messages.forEach { arr.put(JSONObject().put("role", it.role).put("text", it.text)) }
            context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_CHAT, arr.toString()).apply()
        }

        fun clear(context: Context) {
            messages.clear()
            context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
                .edit().remove(PREF_CHAT).apply()
        }
    }

    fun apiKey(context: Context): String? =
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
            .getString(PREF_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_API_KEY, key.trim()).apply()
    }

    /** Compact, LLM-friendly summary of the user's data. Recomputed per question. */
    fun buildContext(context: Context, store: CollectorStore): String {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("d.MM HH:mm", Locale.getDefault())
        val fmtT = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sb = StringBuilder()

        val model = TwinCache.get(store, context)

        // Data below stays in mmol/L (the storage truth); when the user reads
        // mg/dl, instruct the model to answer in their units instead of
        // converting every line here.
        if (Units.isMgdl(context)) {
            sb.appendLine(
                "UNITS: the user reads glucose in mg/dL. All glucose values below are " +
                    "in mmol/L; in your answers ALWAYS convert to mg/dL (×18) and state the units.",
            )
        }

        sb.appendLine("NOW (${fmt.format(Date(now))}):")
        store.lastSensorReading()?.let {
            sb.appendLine("- glucose %.1f mmol/L (%s, %d min ago)".format(it.mmol, it.trend ?: "?", (now - it.tsMs) / 60_000))
        }
        run {
            // The same curve the forecast used. Briefing the assistant on a
            // three-times-longer tail than the model it is being asked about
            // is how a wrong premise re-enters as an answer (A-26).
            val iob = HybridRuntimeMetrics.surfaceIobUnits(store, context, now) ?: 0.0
            val measured = HybridRuntimeMetrics.physioInsulinLandmarks(store, now)
            val v11Tail = HybridRuntimeMetrics.insulinDurationMin()
            val dia = measured?.effectEndMin ?: v11Tail ?: Settings.insulinDiaMin(context)
            val curveName = when {
                measured != null -> "measured curve"
                v11Tail != null -> "personal v11"
                else -> "fallback model"
            }
            iob.takeIf { it > 0.05 }?.let {
                sb.appendLine(
                    "- insulin on board (IOB): %.2f U (%s, tail to %.0f min)"
                        .format(
                            it,
                            curveName,
                            dia,
                        ),
                )
            }
        }
        store.lastHeartRate()?.takeIf { now - it.tsMs < 3 * 3_600_000 }?.let {
            sb.appendLine("- heart rate: %.0f (from watch data)".format(it.bpm))
        }
        val dayHr = store.heartRate(now - 24L * 3_600_000, now)
        if (dayHr.size >= 10) {
            sb.appendLine("- heart rate over 24h: avg %.0f, max %.0f".format(
                dayHr.map { it.bpm }.average(), dayHr.maxOf { it.bpm }))
        }

        model?.let { m ->
            sb.appendLine()
            sb.appendLine("USER PROFILE (computed from their history):")
            val kernelEnd = m.kernel.lastOrNull()?.median
            if (kernelEnd != null) sb.appendLine("- full effect of 1 U of insulin: %.2f mmol/L (action-curve plateau)".format(-kernelEnd))
            listOf(60.0, 120.0, 180.0).forEach { tau ->
                com.diapilot.core.analysis.kernelAt(m.kernel, tau)?.let {
                    sb.appendLine("- by %d min after the injection, %.2f mmol/L per 1 U is realized".format(tau.toInt(), -it))
                }
            }
            val tod = m.byTod.filterValues { it.confidence != Confidence.INSUFFICIENT && it.median != null }
            if (tod.isNotEmpty()) {
                sb.appendLine("- ISF by time of day: " + tod.entries.joinToString("; ") {
                    "${io.github.obdosok.diapilot.i18n.IsfText.todPromptLabel(it.key)}: %.2f (n=${it.value.nValid})".format(it.value.median)
                })
            }
        }

        // A CALIBRATED SERIES, NOT A RAW UNION OF STREAMS.
        //
        // This used to be `store.readings(...)` — raw `glucose_readings` with
        // no meter lens and no plausibility gate, while the chart, the TIR
        // tile and the watch all go through `trustedHistory`. Measured over a
        // week on real data: the raw series gave "below 3.9 — 13.8%",
        // calibrated gave 0.7%; above 10 gave 13.9% against 32.9%.
        //
        // In other words, the ONE screen that explains a user's own control
        // in words was inverting the diagnosis: saying "your problem is
        // hypos" when the problem was in the upper third. Found by external
        // review.
        glycemicStats(trustedHistory(store, context, now - 7L * 24 * 3_600_000, now))?.let { w ->
            sb.appendLine()
            sb.appendLine("WEEK: avg %.1f; in range (3.9-10) %.0f%%; below %.0f%%; above %.0f%%; hypo episodes %d".format(
                w.mean, w.inRangePct, w.belowPct, w.abovePct, w.hypoEpisodes))
        }

        sb.appendLine()
        sb.appendLine("LAST 24 HOURS:")
        // Same series: raw points used to go INTO the model's prompt, so it
        // was reasoning about the wrong scale.
        val dayReadings = trustedHistory(store, context, now - 24L * 3_600_000, now)
        if (dayReadings.isNotEmpty()) {
            // Downsample to ~30-min points.
            val pts = dayReadings.filterIndexed { i, _ -> i % 6 == 0 } + dayReadings.last()
            sb.appendLine("- glucose: " + pts.joinToString("; ") { "%s %.1f".format(fmtT.format(Date(it.tsMs)), it.mmol) })
        }
        val dayBoluses = store.boluses(now - 24L * 3_600_000, now)
        if (dayBoluses.isNotEmpty()) {
            sb.appendLine("- boluses: " + dayBoluses.joinToString("; ") {
                "%s %.1f U".format(fmtT.format(Date(it.tsMs)), it.units) +
                    (it.purpose?.let { p -> " ($p)" } ?: "")
            })
        }
        val labelByOnset = store.labeledMeals(limit = 100).filter { it.event.onsetMs >= FoodEraSettings.current().startMs }.associate { it.event.onsetMs to it.labelName }
        val dayMeals = store.meals(now - 24L * 3_600_000, now)
        val notes = store.annotations(now - 48L * 3_600_000, now)
        // A meal and its food note are ONE event: fold the note (portion
        // wording + grams) into the meal line so the model can't count the
        // dish twice.
        val mergedNoteIds = mutableSetOf<Long>()
        if (dayMeals.isNotEmpty()) {
            sb.appendLine("- food (detected): " + dayMeals.joinToString("; ") { m ->
                val label = labelByOnset[m.onsetMs] ?: "unlabeled"
                // System-labeled rises are "not food" — keep food notes apart.
                val sys = label in com.diapilot.core.analysis.SysLabels.ALL
                val note = if (sys) null else notes
                    .filter {
                        it.estCarbs != null || it.content.isNotBlank()
                    }
                    .filter {
                        !com.diapilot.core.analysis.isContextNote(it.content) &&
                            it.tsMs in (m.onsetMs - 90L * 60_000)..(m.onsetMs + 30L * 60_000)
                    }
                    .minByOrNull { kotlin.math.abs(it.tsMs - m.onsetMs) }
                note?.let { mergedNoteIds.add(it.id) }
                "%s \"%s\" +%.1f over %.0f min%s".format(
                    fmtT.format(Date(m.onsetMs)), label, m.rise, m.timeToPeakMin,
                    m.bolusUnits?.let { " (bolus %.1f)".format(it) } ?: " (no bolus)",
                ) + (note?.let { n ->
                    " [note: \"${n.content}\"" +
                        (n.estCarbs?.let { g -> ", ~%.0f g carbs".format(g) } ?: "") + "]"
                } ?: "")
            })
        }
        val looseNotes = notes.filter { it.id !in mergedNoteIds }
        if (looseNotes.isNotEmpty()) {
            sb.appendLine("- notes (48h): " + looseNotes.joinToString("; ") {
                "${fmt.format(Date(it.tsMs))} \"${it.content}\"" +
                    (it.estCarbs?.let { g -> " ~%.0f g carbs".format(g) } ?: "")
            })
        }
        store.sleepSessions(now - 48L * 3_600_000, now).lastOrNull()?.let {
            sb.appendLine("- sleep: %.1f h (%s-%s)".format(it.durationH, fmtT.format(Date(it.startMs)), fmtT.format(Date(it.endMs))))
        }
        val basals = store.basalEvents(now - 48L * 3_600_000, now)
        if (basals.isNotEmpty()) {
            sb.appendLine("- basal (48h): " + basals.joinToString("; ") {
                "%s %.0f U".format(fmt.format(Date(it.tsMs)), it.units)
            })
        }

        val foods = labelStats(store.labeledMeals(limit = 500).filter { it.event.onsetMs >= FoodEraSettings.current().startMs }).take(8)
        if (foods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("FOOD PROFILES (by repeats): " + foods.joinToString("; ") {
                "\"${it.name}\"×${it.count}: +%.1f over %.0f min".format(it.avgRise, it.avgTimeToPeakMin)
            })
        }
        model?.contexts?.take(6)?.let { ctx ->
            if (ctx.isNotEmpty()) {
                sb.appendLine("CONTEXT (12h after tag vs usual): " + ctx.joinToString("; ") {
                    "\"${it.tag}\"×${it.count}: %.1f vs %.1f".format(it.meanAfter, it.meanBaseline)
                })
            }
        }
        return sb.toString()
    }

    private const val DIGEST_QUESTION =
        "Write a short digest of my week from the data: 1) the overall picture and a comparison with last week; " +
            "2) notable patterns (food, time of day, context); 3) what to pay attention to. " +
            "Base it on the numbers. No dose advice."

    /**
     * Per-day rows for the last 14 days — lets the model compare weeks.
     *
     * @param context needed for the meter lens — daily stats must be computed
     *        over the same series the user sees on the chart.
     */
    fun buildWeeklyContext(context: Context, store: CollectorStore): String {
        val sb = StringBuilder("BY DAY (last 14 days):\n")
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val fmt = SimpleDateFormat("EE d.MM", Locale.getDefault())
        val labelByOnset = store.labeledMeals(limit = 300).filter { it.event.onsetMs >= FoodEraSettings.current().startMs }.associate { it.event.onsetMs to it.labelName }
        for (back in 13 downTo 0) {
            val dayStart = cal.timeInMillis - back * 24L * 3_600_000
            val dayEnd = dayStart + 24L * 3_600_000
            val stats = glycemicStats(trustedHistory(store, context, dayStart, dayEnd)) ?: continue
            val boluses = store.boluses(dayStart, dayEnd)
            val meals = store.meals(dayStart, dayEnd)
            val labels = meals.mapNotNull { labelByOnset[it.onsetMs] }
            sb.appendLine(
                "%s: avg %.1f; in range %.0f%%; hypo %d; boluses %d (Σ%.1f U); food: %d detected%s".format(
                    fmt.format(Date(dayStart)), stats.mean, stats.inRangePct, stats.hypoEpisodes,
                    boluses.size, boluses.sumOf { it.units }, meals.size,
                    if (labels.isNotEmpty()) " (${labels.joinToString(", ")})" else "",
                ),
            )
        }
        return sb.toString()
    }

    /** Fire the weekly digest as a chat turn. */
    fun sendDigest(context: Context, store: CollectorStore) =
        sendAsync(
            context, store, DIGEST_QUESTION,
            displayAs = context.localized().getString(R.string.ask_claude_digest_display),
        )

    // Requests must survive tab switches and screen rotation — the scope
    // lives with the process, not with the composable.
    private val requestScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * A failed call as the user sees it ("Error: …"), in the app language.
     * Display only: callers track the failure themselves (the call threw) and
     * never store this text as an analysis.
     */
    fun errorText(context: Context, e: Throwable): String =
        context.localized().getString(R.string.ask_claude_error, e.message ?: e.javaClass.simpleName)

    /** Fire a question; history and busy-state update via [History]. */
    fun sendAsync(
        context: Context,
        store: CollectorStore,
        question: String,
        displayAs: String? = null,
    ) {
        val key = apiKey(context) ?: return
        if (History.busy.value) return
        History.append(context, ChatMsg("user", displayAs ?: question))
        History.busy.value = true
        val priorHistory = History.messages.dropLast(1).toList()
        requestScope.launch {
            val answer = try {
                val ctx = buildContext(context, store) +
                    if (displayAs != null) "\n\n" + buildWeeklyContext(context, store) else ""
                ask(key, ctx, priorHistory, question, context)
            } catch (e: Exception) {
                errorText(context, e)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                History.append(context, ChatMsg("assistant", answer))
                History.busy.value = false
            }
        }
    }

    private fun foodPrompt(replyLanguage: String) = """A photo shows the food of a person with type 1 diabetes.
First line of the answer: a short dish name (2-4 words, no trailing period).
From the second line: the composition and an estimated carb breakdown by component — state uncertainty honestly (the portion is hard to judge from a photo).
For EACH component add a line in exactly this format: COMPOSITION: name ×N = X g — where name is 1-3 words in the nominative singular, N is the number of items/portions, and X is the carbs of ONE item/portion (example: "2 cookies at 11 g each" → "COMPOSITION: cookie ×2 = 11 g"; if there is one component or it can't be counted — "COMPOSITION: hummus = 10 g"). The sum of N·X across components must match the final CARBS line.
Estimate conservatively: fresh vegetables and leafy salads have almost no carbs (2-5 g); eggs, meat, fish, cheese — close to zero.
The second-to-last line — in exactly this format: GI: N (the glycemic index of the whole dish, accounting for fat/protein, a number; if unknown — GI: unknown).
The last line of the answer — in exactly this format: CARBS: X–Y g (the total range for the whole portion; if it can't be estimated — CARBS: unknown).
Write these line prefixes (COMPOSITION:, GI:, CARBS:) exactly as given, in English, whatever the reply language. Write the dish name in $replyLanguage.
Never any dose recommendations."""

    private fun carbsTextPrompt(replyLanguage: String) = """A person with type 1 diabetes described their food in words: "%s".
Estimate the carbs. Answer: 1-3 short lines of composition/portion (if the portion isn't stated, assume a typical one and say so explicitly).
The first line of the answer — in exactly this format: NAME: a short clean dish name (2-4 words, nominative case, no quantities or weights; example: "2 crackers and tea" → "NAME: crackers with tea").
Estimate conservatively and realistically: fresh vegetables and leafy salads have almost no carbs (2-5 g); eggs, meat, fish, cheese — close to zero. If the name is ambiguous ("salad" could be a vegetable salad or a mayo-based one) — pick the SIMPLEST typical variant and state the assumption explicitly.
If the food has several components — add a line for EACH component in exactly this format: COMPOSITION: name ×N = X g — where name is 1-3 words in the nominative singular, N is the number of items/portions, and X is the carbs of ONE item/portion (example: "2 cookies at 11 g each" → "COMPOSITION: cookie ×2 = 11 g"; if there is one component or it can't be counted — "COMPOSITION: hummus = 10 g"). The sum of N·X across components must match the final CARBS line.
The second-to-last line — in exactly this format: GI: N (the glycemic index of the whole dish, accounting for fat/protein, a number; if unknown — GI: unknown).
The last line of the answer — in exactly this format: CARBS: X–Y g (if it can't be estimated — CARBS: unknown).
Write these line prefixes (NAME:, COMPOSITION:, GI:, CARBS:) exactly as given, in English, whatever the reply language. Write the dish name in $replyLanguage.
Never any dose recommendations."""

    /** The user's own history for matching dishes, so the estimate anchors on
     *  their measured reality — not a generic population guess. Observation
     *  fed to the model, never a dose. */
    private fun personalBlock(personalContext: String?): String =
        personalContext?.takeIf { it.isNotBlank() }?.let {
            "\nThis user's personal history for similar dishes (A GUIDE — " +
                "they have already eaten and measured this; trust these numbers more than typical ones):\n$it"
        } ?: ""

    // ---- Structured food output (tool-use) --------------------------------
    // The model returns a VALIDATED food_analysis call with carbs PER COMPONENT,
    // which we render into the canonical text via core serializeFoodAnalysis. Each
    // component carries its own carbs, so the prose→grams smear (a cucumber getting
    // a share of the total, then seeding a pool) can no longer happen. The old
    // prose prompts remain as a fallback for the rare no-tool response.
    private const val FOOD_TOOL_NAME = "food_analysis"

    private fun prop(type: String, desc: String) =
        JSONObject().put("type", type).put("description", desc)

    /**
     * F-05 layer 2: what the vision call needs to know about a dish in order
     * to RECOGNISE it instead of parsing it from scratch. Recognition and
     * portion are the photo's only jobs — the composition of a known dish
     * lives in the canon and is never re-estimated from pixels.
     */
    data class KnownDishForVision(
        val id: String,
        val title: String,
        val typicalCarbsG: Double?,
    )

    /**
     * F-05 layer 4: a component the user has already priced — a known food item
     * with a fixed portion size and known macros — where a parse that reaches
     * for the generic reference value instead is guessing where it could know.
     * [facts] carries confirmed answers to earlier assumption questions
     * ("bread — wholegrain"), which is how a clarified guess is never guessed
     * again.
     */
    data class KnownComponent(
        val name: String,
        val carbsPerPortionG: Double? = null,
        val facts: String? = null,
    )

    /**
     * [forText] filters the block to components the description actually
     * names. Measured in an ablation (3 arms x 3 draws): the UNFILTERED
     * 30-row block destabilised carb speed — the savoury pancake wobbled
     * 0.11↔1.0 exactly as before the v3 decoupling, ice cream drew MED 2/3 —
     * while the filtered block is stable (0.98 ×3 / 1.0 ×3) and still lands
     * the wholegrain-bread fact (breakfast 0.00 fast ×3). No text (a
     * captionless photo) → no block: stability outranks a speculative match.
     */
    internal fun knownComponentsBlock(components: List<KnownComponent>, forText: String?): String {
        val text = forText?.takeIf { it.isNotBlank() } ?: return ""
        val rows = components
            .filter { it.carbsPerPortionG != null || !it.facts.isNullOrBlank() }
            .filter { com.diapilot.core.analysis.DishRecognitionV1.mentioned(it.name, text) }
        if (rows.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine(
                "The user's KNOWN COMPONENTS. If a dish component matches one of " +
                    "these — use ITS carbs per portion and the recorded facts instead of reference " +
                    "values, and do not mark this component as guessed:",
            )
            rows.take(40).forEach { c ->
                val parts = buildList {
                    c.carbsPerPortionG?.let { add("%.0f g/portion".format(it)) }
                    c.facts?.takeIf { it.isNotBlank() }?.let { add(it.replace('\n', ' ').take(120)) }
                }
                appendLine("- ${c.name}: ${parts.joinToString(" · ")}")
            }
        }
    }

    /** The «which of these, or none» block. Shipped INSIDE the message body so
     *  a test can hold the request assembly rather than the model's answer. */
    internal fun knownDishesBlock(dishes: List<KnownDishForVision>): String {
        if (dishes.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine(
                "The user's KNOWN DISHES. Decide: which of these dishes is in the photo, or none " +
                    "(the known_dish_id field; \"none\" if none match). A match means the SAME dish, " +
                    "not a similar food class: carbonara looks like any pasta — when unsure, use none. " +
                    "For a known dish, do NOT re-estimate the composition — it is already recorded; the " +
                    "photo is only there to recognize the dish and estimate the PORTION " +
                    "(known_dish_portion, a fraction of the usual).",
            )
            dishes.forEach { d ->
                appendLine("- id=${d.id}: ${d.title}" + (d.typicalCarbsG?.let { " · usually %.0f g".format(it) } ?: ""))
            }
        }
    }

    private fun foodTool(knownDishes: List<KnownDishForVision> = emptyList(), replyLanguage: String = "English"): JSONObject {
        val component = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("name", prop("string", "Component name, 1-3 words, nominative singular, in $replyLanguage."))
                    .put("count", prop("integer", "Number of identical items/portions (default 1)"))
                    .put("carbs_g", prop("number", "Carbs of ONE item/portion, in grams. Do NOT split the total across this field — each component has its own number. Vegetables/salad 2-5 g; meat, eggs, fish, cheese ≈0."))
                    .put("portion_g", prop("number", "Natural weight of one portion, in grams, if it's clear"))
                    .put(
                        "speed",
                        JSONObject().put("type", "string")
                            .put("enum", JSONArray().put("FAST").put("MED").put("SLOW"))
                            // The old one-liner ("carb absorption speed of the component")
                            // did not say whose speed, and the model answered with the
                            // DISH's: measured over a batch of real notes, the correlation
                            // between fat-per-carb and the fast fraction is -0.45, and
                            // fatty spreads and creams by name came back MED while a
                            // fruit preserve came back FAST.
                            // The sugar is the same; the slow part is the fat — which the
                            // model already counts three times (gastric slowdown, fat→peak
                            // slope, caloric queue). Lowering speed for it was a FOURTH.
                            .put(
                                "description",
                                "Absorption speed of the component's OWN carbohydrate — as if it were " +
                                    "eaten without fat and protein. The model accounts for fat, protein and " +
                                    "fiber separately (total_fat_g, total_protein_g, total_fiber_g), and " +
                                    "already slows the whole dish with them: lowering speed for them too is a " +
                                    "double count. Decide by the TYPE of carbohydrate: FAST — sugar in any " +
                                    "form (jam, honey, syrup, icing, sweet paste, cream, juice, candy), white " +
                                    "flour, polished rice, potato. MED — whole grain, pasta, oats, whole " +
                                    "fruit, milk. SLOW — legumes, raw vegetables, carbohydrate bound by " +
                                    "fiber. The component's fat content does not affect the speed choice.",
                            ),
                    )
                    .put("confidence", prop("number", "Confidence in this component's CARB estimate, 0..1"))
                    .put("is_rescue", prop("boolean", "true for dextrose/juice/tablets for a hypo")),
            )
            .put("required", JSONArray().put("name").put("carbs_g"))
        return JSONObject()
            .put("name", FOOD_TOOL_NAME)
            .put("description", "Structured food breakdown with carbs PER COMPONENT. Each component has its own carb number; nothing is split out of a total.")
            .put(
                "input_schema",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("dish_name", prop("string", "Short clean dish name, 2-4 words, nominative case, no quantities or weights, in $replyLanguage."))
                            .put("gi", prop("integer", "Dish GI 5..120 accounting for fat/protein; omit if unknown"))
                            .put("total_carbs_min", prop("number", "Lower bound of total carbs for the whole portion, g"))
                            .put("total_carbs_max", prop("number", "Upper bound of total carbs for the whole portion, g"))
                            .put("total_protein_g", prop("number", "Protein for the whole portion, g"))
                            .put("total_fat_g", prop("number", "Fat for the whole portion, g"))
                            .put("total_fiber_g", prop("number", "Fiber for the whole portion, g; estimate the range conservatively and return the average"))
                            .put("total_kcal", prop("number", "Energy value of the whole portion, kcal"))
                            .put(
                                "physical_form",
                                JSONObject().put("type","string")
                                    .put("enum",JSONArray().put("LIQUID").put("PUREE").put("SOFT_SOLID").put("SOLID").put("MIXED").put("UNKNOWN"))
                                    // Examples without a decision rule left compound meals
                                    // to chance: the same layered-dish description came
                                    // back SOLID, SOFT_SOLID and MIXED across three draws,
                                    // and form is a coordinate of the dish identity key —
                                    // so 10 of 11 notes could not be recognised as repeats
                                    // of themselves. The ladder below is exhaustive and
                                    // ordered, so two draws cannot legitimately differ.
                                    .put("description","Physical form of the WHOLE meal, not a single ingredient. Decide step by step: 1) take the component with the largest carbs_g (tie — the first one listed); 2) name its form by the chewing test: drinkable, no chewing needed — LIQUID (juice, smoothie, beer, fruit kissel, cream soup); eaten with a spoon but not chewed — PUREE (yogurt, porridge, ice cream, mash); chewed but mashes with a fork — SOFT_SOLID (bread, pancake, pastry, boiled vegetables, cottage cheese, soft cheese); needs real chewing — SOLID (meat, nuts, raw vegetables, apple, chips). Sauces, fillings, side dishes and toppings do not change the form. MIXED — only if components of different forms carry nearly equal carbs (the difference is less than 10% of the total). UNKNOWN — only if the composition is unknown. A required field: the speed carbs appear at depends on it."),
                            )
                            .put("alcohol_present",prop("boolean","Whether the meal contains alcohol"))
                            .put("summary", prop("string", "1-2 short sentences for a person — what this is and what to watch, in $replyLanguage."))
                            .put("components", JSONObject().put("type", "array").put("items", component))
                            .put(
                                "assumptions",
                                JSONObject().put("type", "array").put(
                                    "items",
                                    JSONObject().put("type", "object").put(
                                        "properties",
                                        JSONObject()
                                            .put("component", prop("string", "Name of the component from components this guess concerns; omit if the guess is about the whole dish"))
                                            .put("what", prop("string", "What in the description was AMBIGUOUS and what you assumed. Example: \"the type of bread wasn't stated, assumed white flour\""))
                                            .put("impact", prop("string", "How much the choice changes the estimate or the speed. Example: \"white versus whole-grain doubles the carb speed\"")),
                                    ).put("required", JSONArray().put("what")),
                                    // The wholegrain-bread rule: the model
                                    // KNOWS white flour is fast and wholegrain medium;
                                    // it does not know which bread the user eats. A silent
                                    // assumption moved a large share of a meal's carbs into FAST.
                                    // Naming the guess turns it into one pointed
                                    // question instead.
                                ).put(
                                    "description",
                                    "REQUIRED: every material assumption you had to make because the " +
                                        "description was ambiguous — cooking method, variety/milling, processing " +
                                        "(whole fruit versus juice), cooled starch, fiber type. An empty list if " +
                                        "the description is unambiguous or the fact is among the user's known " +
                                        "components. Only facts about FOOD — never about doses or a specific " +
                                        "person's physiology.",
                                ),
                            )
                            .apply {
                                // Recognition fields exist only when there is
                                // something to recognise — the enum pins the
                                // answer to the user's dishes, no free-text id.
                                if (knownDishes.isNotEmpty()) {
                                    put(
                                        "known_dish_id",
                                        JSONObject().put("type", "string")
                                            .put(
                                                "enum",
                                                JSONArray().apply {
                                                    knownDishes.forEach { put(it.id) }
                                                    put("none")
                                                },
                                            )
                                            .put(
                                                "description",
                                                "Which of the user's KNOWN dishes (list in the message) is in " +
                                                    "the photo; \"none\" if none match or there is doubt. This is a " +
                                                    "hint for the person to confirm, not a decision.",
                                            ),
                                    )
                                    put(
                                        "known_dish_portion",
                                        prop(
                                            "number",
                                            "Only when known_dish_id != none: the fraction of this dish's USUAL " +
                                                "portion shown in the photo, 0.25-3.0 (1.0 = as usual).",
                                        ),
                                    )
                                }
                            },
                    )
                    // dish_name is REQUIRED: without it serializeFoodAnalysis has no
                    // first line, the summary becomes the note's name, and that name
                    // is the pool key via normalizeFoodName.
                    //
                    // physical_form joined the schema later. It was offered and
                    // optional, so the model simply omitted it and the parser fell
                    // back to UNKNOWN — a live check on a smoothie note showed its
                    // card reading "UNKNOWN" for a drink. That is not a harmless gap:
                    // `physioFeatureShapes` reads the form for both a delay and a
                    // stretch, and LIQUID versus UNKNOWN moves the whole curve by
                    // about 18% (delay -5 min, stretch x0.82). A field the shape
                    // depends on cannot be optional.
                    .put("required", JSONArray().put("dish_name").put("components").put("physical_form")),
            )
    }

    internal fun foodToolBody(
        userContent: Any,
        maxTokens: Int,
        knownDishes: List<KnownDishForVision> = emptyList(),
        replyLanguage: String = "English",
    ): JSONObject = JSONObject()
        .put("model", MODEL)
        .put("max_tokens", maxTokens)
        .put("tools", JSONArray().put(foodTool(knownDishes, replyLanguage)))
        .put("tool_choice", JSONObject().put("type", "tool").put("name", FOOD_TOOL_NAME))
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userContent)))

    /** Map the validated tool input into the core [com.diapilot.core.analysis.FoodAnalysisOut]. */
    private fun toFoodAnalysis(input: JSONObject): com.diapilot.core.analysis.FoodAnalysisOut {
        val comps = input.optJSONArray("components")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = c.optString("name").trim()
                if (name.isEmpty() || !c.has("carbs_g")) return@mapNotNull null
                com.diapilot.core.analysis.FoodComponentOut(
                    name = name,
                    // Clamp to what parseComponents will accept on the way back in
                    // (carbs < 500, ×N ≤ 20). Otherwise the round-trip silently
                    // loses carbs (×25 → ×20) or drops the component entirely.
                    carbsPerUnit = c.optDouble("carbs_g", 0.0)
                        .let { if (it.isNaN()) 0.0 else it }.coerceIn(0.0, 499.0),
                    count = c.optInt("count", 1).coerceIn(1, 20),
                    portionPerUnit = if (c.has("portion_g")) c.optDouble("portion_g").takeIf { !it.isNaN() } else null,
                    speed = c.optString("speed").takeIf { it.isNotBlank() },
                    confidence = if (c.has("confidence")) c.optDouble("confidence").takeIf { !it.isNaN() } else null,
                    isRescue = c.optBoolean("is_rescue", false),
                )
            }
        } ?: emptyList()
        val assumptions = input.optJSONArray("assumptions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val g = arr.optJSONObject(i) ?: return@mapNotNull null
                val what = g.optString("what").trim()
                if (what.isEmpty()) return@mapNotNull null
                com.diapilot.core.analysis.FoodAssumptionOut(
                    component = g.optString("component").trim().takeIf { it.isNotEmpty() },
                    what = what,
                    impact = g.optString("impact").trim().takeIf { it.isNotEmpty() },
                )
            }
        }.orEmpty()
        return com.diapilot.core.analysis.FoodAnalysisOut(
            assumptions = assumptions,
            dishName = input.optString("dish_name").takeIf { it.isNotBlank() },
            gi = if (input.has("gi")) input.optInt("gi").takeIf { it in 5..120 } else null,
            totalCarbsMin = if (input.has("total_carbs_min")) input.optDouble("total_carbs_min").takeIf { !it.isNaN() } else null,
            totalCarbsMax = if (input.has("total_carbs_max")) input.optDouble("total_carbs_max").takeIf { !it.isNaN() } else null,
            totalProteinG = if (input.has("total_protein_g")) input.optDouble("total_protein_g").takeIf { !it.isNaN() && it >= 0 } else null,
            totalFatG = if (input.has("total_fat_g")) input.optDouble("total_fat_g").takeIf { !it.isNaN() && it >= 0 } else null,
            totalKcal = if (input.has("total_kcal")) input.optDouble("total_kcal").takeIf { !it.isNaN() && it >= 0 } else null,
            physicalForm = input.optString("physical_form").let { raw ->
                runCatching { com.diapilot.core.analysis.FoodPhysicalFormV2.valueOf(raw.uppercase()) }.getOrDefault(com.diapilot.core.analysis.FoodPhysicalFormV2.UNKNOWN)
            },
            totalFiberG = if(input.has("total_fiber_g"))input.optDouble("total_fiber_g").takeIf{!it.isNaN()&&it>=0}else null,
            alcoholPresent = input.optBoolean("alcohol_present",false),
            summary = input.optString("summary").takeIf { it.isNotBlank() },
            components = comps,
        )
    }

    private fun foodTextInstr(replyLanguage: String) = """A person with type 1 diabetes described their food in words: "%s". Parse it and call the food_analysis tool.
If the portion isn't stated, assume a typical one and lower confidence. Estimate CONSERVATIVELY: fresh vegetables and leafy salads 2-5 g; eggs, meat, fish, cheese ≈0. If the name is ambiguous ("salad" could be a vegetable salad or a mayo-based one) — pick the simplest typical variant and lower confidence.
List ALL components of the dish, including carb-free ones (meat, egg, cheese, vegetables) with carbs_g = 0 — they carry fat and protein, which the model needs.
Each component has its own carb number — do not split the total across them. The sum of carbs_g × count across all components must match the middle of the total_carbs_min…total_carbs_max range.
Also estimate protein, fat and kcal for the WHOLE portion in total_protein_g, total_fat_g and total_kcal. Kcal should be roughly consistent with 4×protein + 4×carbs + 9×fat.
The breakdown must be reproducible: the same text should give the same answer. Derive speed and physical_form from the field-description rules, not "by eye". Fat is counted exactly once — as the total_fat_g number. Estimate protein and fat with a reference value for the whole dish, rounded to 5 g, not by summing ingredients.
If the message below lists the user's previously recorded ingredients, use a matching one from it (its carbs, its facts) instead of reference values.
Name every material assumption you had to make because the description was ambiguous in assumptions — never assume silently. Only ask about the food: composition, type of carbohydrate, preparation. Never estimate "how fast this will raise the user's glucose" — that is physiology, and it is measured.
Write the dish name, component names and summary in $replyLanguage.
Never any dose recommendations."""

    /** The complete text request, assembled without HTTP — testable like the
     *  vision one. */
    internal fun textRequestBody(
        description: String,
        personalContext: String?,
        knownComponents: List<KnownComponent>,
        replyLanguage: String = "English",
    ): JSONObject = foodToolBody(
        foodTextInstr(replyLanguage).format(description.trim()) +
            knownComponentsBlock(knownComponents, description) + personalBlock(personalContext),
        // 1400, raised from 700 — a live finding: the v4 output (assumptions
        // included) needs roughly 760-911 tokens, so at 700 EVERY call stopped
        // at max_tokens, postToolInput correctly refused the truncated tool
        // JSON, and the prose fallback took over — which carries no macros or
        // calories at all. Reproduced deterministically off-device with the
        // same body. The budget must clear the measured need with headroom; a
        // test pins the floor.
        maxTokens = 1400,
        replyLanguage = replyLanguage,
    )

    /** Carbs estimate from a text description (no photo). Blocking — Dispatchers.IO.
     *  [context], when given, requests the dish/component names in the app's
     *  language; without it the model answers in English. */
    fun estimateCarbs(
        apiKey: String,
        description: String,
        personalContext: String? = null,
        knownComponents: List<KnownComponent> = emptyList(),
        context: Context? = null,
    ): String {
        val replyLanguage = context?.let { LlmLanguage.replyLanguage(it) } ?: "English"
        val input = postToolInput(
            apiKey, textRequestBody(description, personalContext, knownComponents, replyLanguage), FOOD_TOOL_NAME,
        )
        // An analysis with NO composition must never reach the store — callers only
        // check whether the call threw, so it would be saved as-is. A name-only render is
        // non-blank, so blankness alone is not enough: require components.
        input?.let { toFoodAnalysis(it) }
            ?.takeIf { it.components.isNotEmpty() }
            ?.let { com.diapilot.core.analysis.serializeFoodAnalysis(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // Fallback: the original prose path (a no-tool response is rare with a
        // forced tool_choice, but never leave the caller empty-handed).
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 256)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("content", carbsTextPrompt(replyLanguage).format(description.trim()) + personalBlock(personalContext)),
                ),
            )
        return post(apiKey, body, replyLanguage)
    }

    /**
     * Nutrition-only enrichment for historical food. All dishes travel in ONE
     * request (capped by the caller); existing names/carbs/composition are
     * inputs and cannot be rewritten by this tool.
     */
    fun estimateNutritionBatch(
        apiKey: String,
        entries: List<NutritionBatchInput>,
    ): List<NutritionBatchOut> {
        if (entries.isEmpty()) return emptyList()
        val itemSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("id", prop("string", "Untouchable id of the input row"))
                    .put("protein_g", prop("number", "Protein of the whole stated portion, g"))
                    .put("fat_g", prop("number", "Fat of the whole stated portion, g"))
                    .put("kcal", prop("number", "Kcal of the whole stated portion")),
            )
            .put("required", JSONArray().put("id"))
        val toolName = "nutrition_batch"
        val tool = JSONObject()
            .put("name", toolName)
            .put(
                "description",
                "Batch estimate of protein/fat/kcal for historical portions without changing carbs or names.",
            )
            .put(
                "input_schema",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "items",
                            JSONObject().put("type", "array").put("items", itemSchema),
                        ),
                    )
                    .put("required", JSONArray().put("items")),
            )
        val inputRows = JSONArray()
        entries.forEach { e ->
            inputRows.put(
                JSONObject()
                    .put("id", e.id)
                    .put("dish", e.dish)
                    .put("missing_fields", JSONArray(e.missingFields.toList()))
                    .apply {
                        e.carbsG?.let { put("authoritative_carbs_g", it) }
                        e.composition?.takeIf { it.isNotBlank() }
                            ?.let { put("known_composition", it.take(700)) }
                    },
            )
        }
        val instruction = """
            IMPORTANT: estimate and return only the keys listed in each item's
            missing_fields. Other nutrition fields are already stored; do not
            re-estimate or return them.
            Estimate the PROTEIN, FAT and KCAL of each historical portion from the JSON below.
            The authoritative_carbs_g carbs are already recorded by the person/model and are a
            fixed input: do not correct or return them. Use known_composition
            for the portion's size and composition. If there is little information, give a
            moderate typical estimate, not an extreme. Check the energy balance:
            kcal roughly 4×protein + 4×carbs + 9×fat (alcohol can add
            extra energy). Return exactly one item per id.

            INPUT:
            $inputRows
        """.trimIndent()
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 4096)
            .put("tools", JSONArray().put(tool))
            .put("tool_choice", JSONObject().put("type", "tool").put("name", toolName))
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", instruction)),
            )
        val input = postToolInput(apiKey, body, toolName) ?: return emptyList()
        val allowed = entries.mapTo(HashSet()) { it.id }
        return input.optJSONArray("items")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val row = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = row.optString("id")
                val p = row.optDouble("protein_g", Double.NaN)
                val f = row.optDouble("fat_g", Double.NaN)
                val k = row.optDouble("kcal", Double.NaN)
                if (id !in allowed || listOf(p, f, k).none { it.isFinite() }) {
                    null
                } else {
                    NutritionBatchOut(
                        id,
                        p.takeIf { it.isFinite() }?.coerceIn(0.0, 500.0),
                        f.takeIf { it.isFinite() }?.coerceIn(0.0, 500.0),
                        k.takeIf { it.isFinite() }?.coerceIn(0.0, 5_000.0),
                    )
                }
            }.distinctBy { it.id }
        }.orEmpty()
    }

    private const val COMMAND_PROMPT = """The user dictates a command to an app for a person with type 1 diabetes. Parse it into EXACTLY ONE line of JSON, with no explanation and no markdown.
The command may be in Russian or English.
Fields: "action" (one of: food, meter, bolus, basal, activity, dextrose, none) and parameters.
- food (ate/had): {"action":"food","food":"name, 1-4 words","grams":number or null}. "поел смузи" → grams:null. "съел 60г риса" → grams:60.
- meter (meter reading/calibration/glucose): {"action":"meter","mmol":number}. The value is in mmol/L. If the user said mg/dL (number > 30) — divide by 18.
- bolus (injection/bolus): {"action":"bolus","units":number,"purpose":"correction" or "meal_bolus" or "top_up" or null}. Write these purpose values exactly as given.
- basal (basal/long-acting/tresiba/toujeo): {"action":"basal","units":number}.
- activity (walked/workout/sport/run/bike): {"action":"activity","activity":"workout" or "walk" or "sport" or "run" or "bike"}. Write these activity values exactly as given.
- dextrose (dextrose/juice/hypo tablets): {"action":"dextrose"}.
- If this is NOT a data-entry command (a question, chit-chat): {"action":"none"}.
JSON only, one line. Never any dose recommendations."""

    /** Parse a natural-language data-entry command into a structured JSON
     *  action. Blocking — Dispatchers.IO. Returns the raw JSON string. */
    fun parseCommand(apiKey: String, text: String): String {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 128)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("content", COMMAND_PROMPT + "\n\nCommand: “" + text.trim() + "”"),
                ),
            )
        return post(apiKey, body)
    }

    private fun foodVisionInstr(replyLanguage: String) = """A photo shows the food of a person with type 1 diabetes. Parse it and call the food_analysis tool.
Estimate CONSERVATIVELY: fresh vegetables and leafy salads have almost no carbs (2-5 g); eggs, meat, fish, cheese — close to zero.
List ALL components of the dish, including carb-free ones (meat, egg, cheese, vegetables) with carbs_g = 0 — they carry fat and protein, which the model needs.
Each component has its own carb number — do NOT split the total across the components. The sum of carbs_g × count across all components must match the middle of the total_carbs_min…total_carbs_max range.
Also estimate protein, fat and kcal for the WHOLE visible portion in total_protein_g, total_fat_g and total_kcal. Kcal should be roughly consistent with 4×protein + 4×carbs + 9×fat.
The portion is hard to judge from a photo — reflect the uncertainty in the confidence field.
The breakdown must be reproducible: the same photo should give the same answer. Derive speed and physical_form from the field-description rules, not "by eye". Fat is counted exactly once — as the total_fat_g number. Estimate protein and fat with a reference value for the whole dish, rounded to 5 g, not by summing ingredients.
If the message below lists the user's previously recorded ingredients, use a matching one from it (its carbs, its facts) instead of reference values.
Name every material assumption you had to make (variety, processing, cooking method — not visible in a photo) in assumptions — never assume silently. Never estimate "how fast this will raise the user's glucose".
Write the dish name, component names and summary in $replyLanguage.
Never any dose recommendations."""

    /**
     * A vision answer: the serialized analysis PLUS the recognition hint.
     *
     * The hint is deliberately OUTSIDE the text: the text is what a caller may
     * store, and a candidate must never ride into storage uninvited — it
     * becomes structure only after the strict photo confirmation (a wrong
     * match writes someone else's composition, which is harder to notice than
     * wrong grams).
     */
    data class VisionResult(
        val text: String,
        val knownDishId: String? = null,
        val knownDishPortion: Double? = null,
    )

    private fun imageContent(b64: String, instr: String) = JSONArray()
        .put(
            JSONObject().put("type", "image").put(
                "source",
                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64),
            ),
        )
        .put(JSONObject().put("type", "text").put("text", instr))

    /**
     * The COMPLETE vision request, assembled without HTTP — the F-05 test
     * pins that the known-dishes list really reaches the request body (both
     * the message text and the schema enum), because "the photo path can't
     * see history" was exactly a request-assembly defect: `foodPersonalContext`
     * needed a text query and a captionless photo had none.
     */
    internal fun visionRequestBody(
        b64: String,
        caption: String?,
        personalContext: String?,
        knownDishes: List<KnownDishForVision>,
        knownComponents: List<KnownComponent> = emptyList(),
        replyLanguage: String = "English",
    ): JSONObject {
        val captionLine = caption?.takeIf { it.isNotBlank() && com.diapilot.core.analysis.NoteTag.of(it) != com.diapilot.core.analysis.NoteTag.PHOTO }
            ?.let { "\nThe user's caption for the photo: \"$it\" — take it into account when identifying the dish." } ?: ""
        return foodToolBody(
            imageContent(
                b64,
                foodVisionInstr(replyLanguage) + captionLine + knownDishesBlock(knownDishes) +
                    knownComponentsBlock(knownComponents, caption) + personalBlock(personalContext),
            ),
            // 1600: the vision output is the same v4 shape plus the
            // recognition fields — same truncation math as the text path.
            maxTokens = 1600,
            knownDishes = knownDishes,
            replyLanguage = replyLanguage,
        )
    }

    /** Vision: describe a food photo, recognising the user's known dishes.
     *  Blocking — invoke on Dispatchers.IO. [context], when given, requests
     *  the dish/component names in the app's language; without it the model
     *  answers in English. */
    fun describeFood(
        apiKey: String,
        jpegBytes: ByteArray,
        caption: String? = null,
        personalContext: String? = null,
        knownDishes: List<KnownDishForVision> = emptyList(),
        knownComponents: List<KnownComponent> = emptyList(),
        context: Context? = null,
    ): VisionResult {
        val replyLanguage = context?.let { LlmLanguage.replyLanguage(it) } ?: "English"
        val b64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
        val input = postToolInput(
            apiKey,
            visionRequestBody(b64, caption, personalContext, knownDishes, knownComponents, replyLanguage),
            FOOD_TOOL_NAME,
        )
        // An analysis with NO composition must never reach the store — callers only
        // check whether the call threw, so it would be saved as-is. A name-only render is
        // non-blank, so blankness alone is not enough: require components.
        input?.let { toFoodAnalysis(it) }
            ?.takeIf { it.components.isNotEmpty() }
            ?.let { com.diapilot.core.analysis.serializeFoodAnalysis(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                val id = input.optString("known_dish_id")
                    .takeIf { it.isNotBlank() && it != "none" && knownDishes.any { d -> d.id == it } }
                return VisionResult(
                    text = text,
                    knownDishId = id,
                    knownDishPortion = if (id != null && input.has("known_dish_portion")) {
                        input.optDouble("known_dish_portion").takeIf { !it.isNaN() && it in 0.1..4.0 }
                    } else null,
                )
            }
        // Fallback: the original prose vision path (rare with a forced tool_choice).
        val captionLine = caption?.takeIf { it.isNotBlank() && com.diapilot.core.analysis.NoteTag.of(it) != com.diapilot.core.analysis.NoteTag.PHOTO }
            ?.let { "\nThe user's caption for the photo: \"$it\" — take it into account when identifying the dish." } ?: ""
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 512)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("content", imageContent(b64, foodPrompt(replyLanguage) + captionLine + personalBlock(personalContext))),
                ),
            )
        return VisionResult(post(apiKey, body, replyLanguage))
    }

    /** Blocking HTTP call — invoke on Dispatchers.IO. [context] picks the
     *  reply language (see [LlmLanguage.replyInstruction]); only called
     *  internally, always with the caller's Context. */
    fun ask(apiKey: String, dataContext: String, history: List<ChatMsg>, question: String, context: Context): String {
        val messages = JSONArray()
        history.takeLast(6).forEach { m ->
            messages.put(JSONObject().put("role", m.role).put("content", m.text))
        }
        messages.put(
            JSONObject().put("role", "user").put(
                "content",
                "DATA SUMMARY:\n$dataContext\n\nQUESTION: $question",
            ),
        )
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1024)
            .put("system", systemPrompt(context))
            .put("messages", messages)
        return post(apiKey, body, LlmLanguage.replyLanguage(context))
    }

    /** Blocking HTTP call — the full parsed response JSON. Invoke on Dispatchers.IO. */
    private fun postRaw(apiKey: String, body: JSONObject): JSONObject {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                val msg = runCatching { JSONObject(err).getJSONObject("error").getString("message") }
                    .getOrDefault(err.take(200))
                throw RuntimeException("API ${conn.responseCode}: $msg")
            }
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    /** Text answer — concatenated text blocks. [replyLanguage] only decides
     *  the rare empty-response fallback message below. */
    private fun post(apiKey: String, body: JSONObject, replyLanguage: String = "English"): String {
        val json = postRaw(apiKey, body)
        val content = json.getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.getString("type") == "text") append(block.getString("text"))
            }
        }.ifBlank {
            if (replyLanguage == "Russian") "Пустой ответ (stop_reason: ${json.optString("stop_reason")})"
            else "Empty response (stop_reason: ${json.optString("stop_reason")})"
        }
    }

    /** The forced tool call's validated input object, or null when the model
     *  returned no such block (caller falls back to the prose path). */
    private fun postToolInput(apiKey: String, body: JSONObject, toolName: String): JSONObject? {
        val json = postRaw(apiKey, body)
        // A tool call cut off by the token limit carries partial/empty arguments —
        // WORSE than prose, because it still looks structured. Refuse it and let
        // the caller fall back.
        if (json.optString("stop_reason") == "max_tokens") return null
        val content = json.optJSONArray("content") ?: return null
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "tool_use" && block.optString("name") == toolName) {
                return block.optJSONObject("input")
            }
        }
        return null
    }
}
