package com.example.diapilot.data

import android.content.Context
import com.diapilot.core.analysis.Confidence
import com.diapilot.core.analysis.glycemicStats
import com.diapilot.core.analysis.labelStats
import com.diapilot.core.collector.CollectorStore
import com.example.diapilot.collect.TreatmentsPollWorker
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

    private const val SYSTEM = """Ты — ассистент приложения DiaPilot для человека с диабетом 1 типа.
Тебе дают сводку его данных (глюкоза, инсулин, еда, контекст) и вопрос.

ЖЁСТКИЕ ПРАВИЛА:
- НИКОГДА не называй конкретные дозы инсулина и не давай указаний по дозированию ("уколите X единиц" — запрещено в любой форме).
- Не назначай лечение. Формулируй наблюдения и гипотезы по данным.
- Если вопрос про изменение настроек терапии (ISF, базал) — опиши, что видно в данных, и добавь: обсудить с врачом.
- Опирайся на конкретные числа из сводки, ссылайся на них.
- Отвечай по-русски, кратко и по делу. Если данных для ответа мало — скажи прямо."""

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
                "ЕДИНИЦЫ: пользователь читает сахар в мг/дл. Все значения глюкозы ниже — " +
                    "в ммоль/л; в ответах ВСЕГДА переводи в мг/дл (×18) и указывай единицы.",
            )
        }

        sb.appendLine("СЕЙЧАС (${fmt.format(Date(now))}):")
        store.lastSensorReading()?.let {
            sb.appendLine("- глюкоза %.1f ммоль/л (%s, %d мин назад)".format(it.mmol, it.trend ?: "?", (now - it.tsMs) / 60_000))
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
                measured != null -> "измеренная кривая"
                v11Tail != null -> "персональная v11"
                else -> "резервная модель"
            }
            iob.takeIf { it > 0.05 }?.let {
                sb.appendLine(
                    "- активный инсулин (IOB): %.2f ед (%s, хвост до %.0f мин)"
                        .format(
                            it,
                            curveName,
                            dia,
                        ),
                )
            }
        }
        store.lastHeartRate()?.takeIf { now - it.tsMs < 3 * 3_600_000 }?.let {
            sb.appendLine("- пульс: %.0f (по данным часов)".format(it.bpm))
        }
        val dayHr = store.heartRate(now - 24L * 3_600_000, now)
        if (dayHr.size >= 10) {
            sb.appendLine("- пульс за 24ч: средний %.0f, макс %.0f".format(
                dayHr.map { it.bpm }.average(), dayHr.maxOf { it.bpm }))
        }

        model?.let { m ->
            sb.appendLine()
            sb.appendLine("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ (рассчитан по его истории):")
            val kernelEnd = m.kernel.lastOrNull()?.median
            if (kernelEnd != null) sb.appendLine("- полный эффект 1 ед инсулина: %.2f ммоль/л (плато кривой действия)".format(-kernelEnd))
            listOf(60.0, 120.0, 180.0).forEach { tau ->
                com.diapilot.core.analysis.kernelAt(m.kernel, tau)?.let {
                    sb.appendLine("- к %d мин после укола реализовано %.2f ммоль/л на 1 ед".format(tau.toInt(), -it))
                }
            }
            val tod = m.byTod.filterValues { it.confidence != Confidence.INSUFFICIENT && it.median != null }
            if (tod.isNotEmpty()) {
                sb.appendLine("- ISF по времени суток: " + tod.entries.joinToString("; ") {
                    "${it.key.labelRu}: %.2f (n=${it.value.nValid})".format(it.value.median)
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
            sb.appendLine("НЕДЕЛЯ: средний %.1f; в цели(3.9–10) %.0f%%; ниже %.0f%%; выше %.0f%%; эпизодов гипо %d".format(
                w.mean, w.inRangePct, w.belowPct, w.abovePct, w.hypoEpisodes))
        }

        sb.appendLine()
        sb.appendLine("ПОСЛЕДНИЕ 24 ЧАСА:")
        // Same series: raw points used to go INTO the model's prompt, so it
        // was reasoning about the wrong scale.
        val dayReadings = trustedHistory(store, context, now - 24L * 3_600_000, now)
        if (dayReadings.isNotEmpty()) {
            // Downsample to ~30-min points.
            val pts = dayReadings.filterIndexed { i, _ -> i % 6 == 0 } + dayReadings.last()
            sb.appendLine("- глюкоза: " + pts.joinToString("; ") { "%s %.1f".format(fmtT.format(Date(it.tsMs)), it.mmol) })
        }
        val dayBoluses = store.boluses(now - 24L * 3_600_000, now)
        if (dayBoluses.isNotEmpty()) {
            sb.appendLine("- болюсы: " + dayBoluses.joinToString("; ") {
                "%s %.1f ед".format(fmtT.format(Date(it.tsMs)), it.units) +
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
            sb.appendLine("- еда (детект): " + dayMeals.joinToString("; ") { m ->
                val label = labelByOnset[m.onsetMs] ?: "не размечено"
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
                "%s «%s» +%.1f за %.0f мин%s".format(
                    fmtT.format(Date(m.onsetMs)), label, m.rise, m.timeToPeakMin,
                    m.bolusUnits?.let { " (болюс %.1f)".format(it) } ?: " (без болюса)",
                ) + (note?.let { n ->
                    " [запись: «${n.content}»" +
                        (n.estCarbs?.let { g -> ", ~%.0f г углев".format(g) } ?: "") + "]"
                } ?: "")
            })
        }
        val looseNotes = notes.filter { it.id !in mergedNoteIds }
        if (looseNotes.isNotEmpty()) {
            sb.appendLine("- заметки (48ч): " + looseNotes.joinToString("; ") {
                "${fmt.format(Date(it.tsMs))} «${it.content}»" +
                    (it.estCarbs?.let { g -> " ~%.0f г углев".format(g) } ?: "")
            })
        }
        store.sleepSessions(now - 48L * 3_600_000, now).lastOrNull()?.let {
            sb.appendLine("- сон: %.1f ч (%s–%s)".format(it.durationH, fmtT.format(Date(it.startMs)), fmtT.format(Date(it.endMs))))
        }
        val basals = store.basalEvents(now - 48L * 3_600_000, now)
        if (basals.isNotEmpty()) {
            sb.appendLine("- длинный инсулин (48ч): " + basals.joinToString("; ") {
                "%s %.0f ед".format(fmt.format(Date(it.tsMs)), it.units)
            })
        }

        val foods = labelStats(store.labeledMeals(limit = 500).filter { it.event.onsetMs >= FoodEraSettings.current().startMs }).take(8)
        if (foods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("ПРОФИЛИ ЕДЫ (по повторам): " + foods.joinToString("; ") {
                "«${it.name}»×${it.count}: +%.1f за %.0f мин".format(it.avgRise, it.avgTimeToPeakMin)
            })
        }
        model?.contexts?.take(6)?.let { ctx ->
            if (ctx.isNotEmpty()) {
                sb.appendLine("КОНТЕКСТ (12ч после тега vs обычно): " + ctx.joinToString("; ") {
                    "«${it.tag}»×${it.count}: %.1f vs %.1f".format(it.meanAfter, it.meanBaseline)
                })
            }
        }
        return sb.toString()
    }

    private const val DIGEST_QUESTION =
        "Составь короткий дайджест моей недели по данным: 1) общая картина и сравнение с прошлой неделей; " +
            "2) заметные закономерности (еда, время суток, контекст); 3) на что обратить внимание. " +
            "Опирайся на числа. Без советов по дозам."

    /**
     * Per-day rows for the last 14 days — lets the model compare weeks.
     *
     * @param context needed for the meter lens — daily stats must be computed
     *        over the same series the user sees on the chart.
     */
    fun buildWeeklyContext(context: Context, store: CollectorStore): String {
        val sb = StringBuilder("ПО ДНЯМ (последние 14 суток):\n")
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
                "%s: средний %.1f; в цели %.0f%%; гипо %d; болюсов %d (Σ%.1f ед); еда: %d детектов%s".format(
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
        sendAsync(context, store, DIGEST_QUESTION, displayAs = "📋 Дайджест недели")

    // Requests must survive tab switches and screen rotation — the scope
    // lives with the process, not with the composable.
    private val requestScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

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
                ask(key, ctx, priorHistory, question)
            } catch (e: Exception) {
                "Ошибка: ${e.message}"
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                History.append(context, ChatMsg("assistant", answer))
                History.busy.value = false
            }
        }
    }

    private const val FOOD_PROMPT = """На фото — еда человека с диабетом 1 типа.
Первая строка ответа: короткое название блюда (2–4 слова, без точки в конце).
Со второй строки: состав и ориентировочная оценка углеводов по компонентам — честно обозначай неопределённость (порцию по фото видно плохо).
Для КАЖДОГО компонента добавь строку строго в формате: СОСТАВ: название ×N = X г — где название 1–3 слова в именительном падеже ЕДИНСТВЕННОГО числа, N — число штук/порций, X — углеводы ОДНОЙ штуки/порции («2 кантуччи по 11 г» → «СОСТАВ: кантуччи ×2 = 11 г»; если компонент один или непересчитываемый — «СОСТАВ: хумус = 10 г»). Сумма N·X по компонентам должна совпадать с итоговой строкой УГЛЕВОДЫ.
Оценивай консервативно: свежие овощи и листовые салаты почти без углеводов (2–5 г); яйца, мясо, рыба, сыр — около нуля.
Предпоследняя строка — строго в формате: ГИ: N (гликемический индекс блюда целиком с учётом жиров/белков, число; если неизвестно — ГИ: неизвестно).
Последняя строка ответа — строго в формате: УГЛЕВОДЫ: X–Y г (суммарный диапазон по всей порции; если оценить невозможно — УГЛЕВОДЫ: неизвестно).
Никаких рекомендаций по дозам инсулина."""

    private const val CARBS_TEXT_PROMPT = """Человек с диабетом 1 типа описал еду словами: «%s».
Оцени углеводы. Ответ: 1–3 короткие строки состава/порции (если порция не указана — возьми типичную и явно это скажи).
Первая строка ответа — строго в формате: НАЗВАНИЕ: короткое чистое название блюда (2–4 слова, именительный падеж, без количеств и весов; «2 сухарика кантуччи и чай» → «НАЗВАНИЕ: кантуччи с чаем»).
Оценивай консервативно и реалистично: свежие овощи и листовые салаты почти без углеводов (2–5 г); яйца, мясо, рыба, сыр — около нуля. Если название неоднозначно («салат» может быть овощным или оливье) — бери САМЫЙ ПРОСТОЙ типовой вариант и явно напиши допущение.
Если еда составная (несколько блюд/компонентов) — добавь для КАЖДОГО компонента строку строго в формате: СОСТАВ: название ×N = X г — где название 1–3 слова в именительном падеже ЕДИНСТВЕННОГО числа, N — число штук/порций, X — углеводы ОДНОЙ штуки/порции («2 кантуччи по 11 г» → «СОСТАВ: кантуччи ×2 = 11 г»; если компонент один или непересчитываемый — «СОСТАВ: хумус = 10 г»). Сумма N·X по компонентам должна совпадать с итоговой строкой УГЛЕВОДЫ.
Предпоследняя строка — строго в формате: ГИ: N (гликемический индекс блюда целиком с учётом жиров/белков, число; если неизвестно — ГИ: неизвестно).
Последняя строка ответа — строго в формате: УГЛЕВОДЫ: X–Y г (если оценить невозможно — УГЛЕВОДЫ: неизвестно).
Никаких рекомендаций по дозам инсулина."""

    /** The user's own history for matching dishes, so the estimate anchors on
     *  their measured reality — not a generic population guess. Observation
     *  fed to the model, never a dose. */
    private fun personalBlock(personalContext: String?): String =
        personalContext?.takeIf { it.isNotBlank() }?.let {
            "\nЛичная история этого пользователя по похожим блюдам (ОРИЕНТИР — " +
                "он уже это ел и измерил, доверяй этим цифрам больше, чем типовым):\n$it"
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
                "ИЗВЕСТНЫЕ КОМПОНЕНТЫ пользователя. Если компонент блюда совпадает с одним из " +
                    "них — бери ЕГО углеводы на порцию и записанные факты вместо справочных " +
                    "значений, и не помечай этот компонент как угаданный:",
            )
            rows.take(40).forEach { c ->
                val parts = buildList {
                    c.carbsPerPortionG?.let { add("%.0f г/порция".format(it)) }
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
                "ИЗВЕСТНЫЕ БЛЮДА пользователя. Реши: какое из этих блюд на фото, или ни одно " +
                    "(поле known_dish_id; \"none\", если ни одно). Совпадение — ТО ЖЕ блюдо, " +
                    "а не похожий класс еды: карбонара похожа на любую пасту, сомнение = none. " +
                    "У известного блюда состав НЕ переоценивай — он записан; фото нужно, чтобы " +
                    "опознать блюдо и оценить ПОРЦИЮ (known_dish_portion, доля обычной).",
            )
            dishes.forEach { d ->
                appendLine("- id=${d.id}: ${d.title}" + (d.typicalCarbsG?.let { " · обычно %.0f г".format(it) } ?: ""))
            }
        }
    }

    private fun foodTool(knownDishes: List<KnownDishForVision> = emptyList()): JSONObject {
        val component = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("name", prop("string", "Название компонента, 1–3 слова, им.п. ед.ч."))
                    .put("count", prop("integer", "Число одинаковых штук/порций (по умолчанию 1)"))
                    .put("carbs_g", prop("number", "Углеводы ОДНОЙ штуки/порции, г. НЕ распределяй сюда общий итог — у каждого компонента своё число. Овощи/салат 2–5 г; мясо, яйца, рыба, сыр ≈0."))
                    .put("portion_g", prop("number", "Натуральная масса одной порции, г, если ясно"))
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
                                "Скорость усвоения САМОГО углевода компонента — как если бы он был " +
                                    "съеден без жира и белка. Жир, белок и клетчатку модель учитывает " +
                                    "отдельно (total_fat_g, total_protein_g, total_fiber_g) и уже " +
                                    "замедляет ими всё блюдо: понижать за них speed — двойной счёт. " +
                                    "Решай по типу углевода: FAST — сахар в любом виде (варенье, мёд, " +
                                    "сироп, глазурь, сладкая паста, крем, сок, конфета), белая мука, " +
                                    "шлифованный рис, картофель. MED — цельное зерно, паста, овсянка, " +
                                    "целый фрукт, молоко. SLOW — бобовые, сырые овощи, углевод, " +
                                    "связанный клетчаткой. Жирность компонента на выбор speed не влияет.",
                            ),
                    )
                    .put("confidence", prop("number", "Уверенность в оценке УГЛЕВОДОВ этого компонента, 0..1"))
                    .put("is_rescue", prop("boolean", "true для декстрозы/сока/таблеток от гипогликемии")),
            )
            .put("required", JSONArray().put("name").put("carbs_g"))
        return JSONObject()
            .put("name", FOOD_TOOL_NAME)
            .put("description", "Структурированный разбор еды с углеводами ПО КОМПОНЕНТАМ. У каждого компонента своё число углеводов; ничего не распределять из общего итога.")
            .put(
                "input_schema",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("dish_name", prop("string", "Короткое чистое название блюда, 2–4 слова, им.п., без количеств и весов"))
                            .put("gi", prop("integer", "ГИ блюда 5..120 с учётом жиров/белков; опусти, если неизвестно"))
                            .put("total_carbs_min", prop("number", "Нижняя граница суммарных углеводов всей порции, г"))
                            .put("total_carbs_max", prop("number", "Верхняя граница суммарных углеводов всей порции, г"))
                            .put("total_protein_g", prop("number", "Белки всей порции, г"))
                            .put("total_fat_g", prop("number", "Жиры всей порции, г"))
                            .put("total_fiber_g", prop("number", "Клетчатка всей порции, г; оцени диапазон консервативно и верни среднюю оценку"))
                            .put("total_kcal", prop("number", "Энергетическая ценность всей порции, ккал"))
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
                                    .put("description","Физическая форма ВСЕГО приёма, не отдельного ингредиента. Определяй по шагам: 1) возьми компонент с наибольшими carbs_g (при равенстве — первый в списке); 2) назови его форму по тесту на жевание: пьётся, жевать не надо — LIQUID (сок, смузи, пиво, кисель, суп-пюре); ест ложкой, но не жуётся — PUREE (йогурт, каша, мороженое, пюре); жуётся, но разминается вилкой — SOFT_SOLID (хлеб, блин, выпечка, варёные овощи, творог, сыр мягкий); требует настоящего жевания — SOLID (мясо, орехи, сырые овощи, яблоко, чипсы). Соусы, начинки, гарнир и добавки форму не меняют. MIXED — только если компоненты разной формы дают почти поровну углеводов (разница меньше 10% от суммы). UNKNOWN — только если состав неизвестен. Обязательное поле: от него зависит скорость появления углеводов."),
                            )
                            .put("alcohol_present",prop("boolean","Есть ли алкоголь в приёме"))
                            .put("summary", prop("string", "1–2 короткие фразы для человека — что это и на что смотреть"))
                            .put("components", JSONObject().put("type", "array").put("items", component))
                            .put(
                                "assumptions",
                                JSONObject().put("type", "array").put(
                                    "items",
                                    JSONObject().put("type", "object").put(
                                        "properties",
                                        JSONObject()
                                            .put("component", prop("string", "Имя компонента из components, которого касается догадка; опусти, если догадка о блюде целиком"))
                                            .put("what", prop("string", "Что в описании НЕОДНОЗНАЧНО и что ты предположил. Пример: «сорт хлеба не указан, предположил белую муку»"))
                                            .put("impact", prop("string", "Как сильно выбор меняет оценку или скорость. Пример: «белый против цельнозернового меняет скорость углевода вдвое»")),
                                    ).put("required", JSONArray().put("what")),
                                    // The wholegrain-bread rule: the model
                                    // KNOWS white flour is fast and wholegrain medium;
                                    // it does not know which bread the user eats. A silent
                                    // assumption moved a large share of a meal's carbs into FAST.
                                    // Naming the guess turns it into one pointed
                                    // question instead.
                                ).put(
                                    "description",
                                    "ОБЯЗАТЕЛЬНО: каждое существенное предположение, которое пришлось " +
                                        "сделать из-за неоднозначного описания — способ приготовления, сорт/помол, " +
                                        "обработка (целый фрукт против сока), остывший крахмал, тип клетчатки. " +
                                        "Пустой список, если описание однозначно или факт есть среди известных " +
                                        "компонентов пользователя. Только факты о ЕДЕ — никогда о дозах или " +
                                        "физиологии конкретного человека.",
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
                                                "Какое из ИЗВЕСТНЫХ блюд пользователя (список в сообщении) " +
                                                    "на фото; \"none\", если ни одно или есть сомнение. " +
                                                    "Это подсказка для подтверждения человеком, не решение.",
                                            ),
                                    )
                                    put(
                                        "known_dish_portion",
                                        prop(
                                            "number",
                                            "Только при known_dish_id != none: доля ОБЫЧНОЙ порции этого " +
                                                "блюда на фото, 0.25–3.0 (1.0 = как обычно).",
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
    ): JSONObject = JSONObject()
        .put("model", MODEL)
        .put("max_tokens", maxTokens)
        .put("tools", JSONArray().put(foodTool(knownDishes)))
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

    private const val FOOD_TEXT_INSTR = """Человек с диабетом 1 типа описал еду словами: «%s». Разбери и вызови инструмент food_analysis.
Если порция не указана — возьми типичную и понизь confidence. Оценивай КОНСЕРВАТИВНО: свежие овощи и листовые салаты 2–5 г; яйца, мясо, рыба, сыр ≈0. Если название неоднозначно («салат» может быть овощным или оливье) — бери самый простой типовой вариант и понизь confidence.
Перечисляй ВСЕ компоненты блюда, включая безуглеводные (мясо, яйцо, сыр, овощи) с carbs_g = 0 — они несут жир и белок, это нужно модели.
У каждого компонента своё число углеводов — не распределяй общий итог. Сумма carbs_g × count по всем компонентам должна совпадать с серединой диапазона total_carbs_min…total_carbs_max.
Оцени также белки, жиры и ккал ВСЕЙ порции в total_protein_g, total_fat_g и total_kcal. Ккал должны быть примерно согласованы с 4×белки + 4×углеводы + 9×жиры.
Разбор должен быть воспроизводимым: на один и тот же текст — один и тот же ответ. speed и physical_form выводи по правилам из описаний полей, а не «на глаз». Жир учитывается ровно один раз — числом в total_fat_g. Белки и жиры оценивай справочным значением для блюда целиком, кратным 5 г, а не суммированием по ингредиентам.
Если дан список ИЗВЕСТНЫХ КОМПОНЕНТОВ пользователя — совпавший компонент бери из него (его углеводы, его факты), а не из справочных значений.
Каждое существенное предположение, которое пришлось сделать из-за неоднозначного описания, НАЗЫВАЙ в assumptions — не предполагай молча. Спрашивают только про еду: состав, тип углевода, обработку. Никогда не оценивай, «как быстро это поднимет сахар у него», — это физиология, она измеряется.
Никаких рекомендаций по дозам инсулина."""

    /** The complete text request, assembled without HTTP — testable like the
     *  vision one. */
    internal fun textRequestBody(
        description: String,
        personalContext: String?,
        knownComponents: List<KnownComponent>,
    ): JSONObject = foodToolBody(
        FOOD_TEXT_INSTR.format(description.trim()) +
            knownComponentsBlock(knownComponents, description) + personalBlock(personalContext),
        // 1400, raised from 700 — a live finding: the v4 output (assumptions
        // included) needs roughly 760-911 tokens, so at 700 EVERY call stopped
        // at max_tokens, postToolInput correctly refused the truncated tool
        // JSON, and the prose fallback took over — which carries no macros or
        // calories at all. Reproduced deterministically off-device with the
        // same body. The budget must clear the measured need with headroom; a
        // test pins the floor.
        maxTokens = 1400,
    )

    /** Carbs estimate from a text description (no photo). Blocking — Dispatchers.IO. */
    fun estimateCarbs(
        apiKey: String,
        description: String,
        personalContext: String? = null,
        knownComponents: List<KnownComponent> = emptyList(),
    ): String {
        val input = postToolInput(
            apiKey, textRequestBody(description, personalContext, knownComponents), FOOD_TOOL_NAME,
        )
        // An analysis with NO composition must never reach the store — callers only
        // check for a specific error-marker string, so it would be saved as-is. A name-only render is
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
                        .put("content", CARBS_TEXT_PROMPT.format(description.trim()) + personalBlock(personalContext)),
                ),
            )
        return post(apiKey, body)
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
                    .put("id", prop("string", "Неприкосновенный id входной строки"))
                    .put("protein_g", prop("number", "Белки всей указанной порции, г"))
                    .put("fat_g", prop("number", "Жиры всей указанной порции, г"))
                    .put("kcal", prop("number", "Ккал всей указанной порции")),
            )
            .put("required", JSONArray().put("id"))
        val toolName = "nutrition_batch"
        val tool = JSONObject()
            .put("name", toolName)
            .put(
                "description",
                "Пакетная оценка БЖУ и ккал исторических порций без изменения углеводов и названий.",
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
            Оцени БЕЛКИ, ЖИРЫ и ККАЛ каждой исторической порции из JSON ниже.
            Углеводы authoritative_carbs_g уже записаны человеком/моделью и являются
            фиксированным входом: не исправляй и не возвращай их. known_composition
            используй для размера и состава порции. Если сведений мало, дай умеренную
            типичную оценку, а не экстремум. Проверь энергетический баланс:
            ккал примерно 4×белки + 4×углеводы + 9×жиры (алкоголь может дать
            дополнительную энергию). Верни ровно по одному item на каждый id.

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

    private const val COMMAND_PROMPT = """Пользователь диктует команду приложению для диабетика 1 типа. Разбери её в СТРОГО ОДНУ строку JSON без пояснений и без markdown.
Поля: "action" (одно из: food, meter, bolus, basal, activity, dextrose, none) и параметры.
- food (поел/съел): {"action":"food","food":"название 1-4 слова","grams":число или null}. «поел смузи» → grams:null. «съел 60г риса» → grams:60.
- meter (замер глюкометром/калибровка/сахар): {"action":"meter","mmol":число}. Значение в ммоль/л. Если пользователь назвал мг/дл (число > 30) — раздели на 18.
- bolus (укол/болюс/уколол): {"action":"bolus","units":число,"purpose":"коррекция" или "на еду" или "докол" или null}.
- basal (базал/длинный/тресиба/тужео): {"action":"basal","units":число}.
- activity (гулял/тренировка/спорт/бег/велосипед): {"action":"activity","activity":"тренировка" или "прогулка" или "спорт" или "бег" или "велосипед"}.
- dextrose (декстроза/сок/таблетки от гипо): {"action":"dextrose"}.
- Если это НЕ команда на ввод данных (вопрос, болтовня): {"action":"none"}.
Только JSON, одна строка. Никаких рекомендаций по дозам."""

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
                        .put("content", COMMAND_PROMPT + "\n\nКоманда: «" + text.trim() + "»"),
                ),
            )
        return post(apiKey, body)
    }

    private const val FOOD_VISION_INSTR = """На фото — еда человека с диабетом 1 типа. Разбери её и вызови инструмент food_analysis.
Оценивай КОНСЕРВАТИВНО: свежие овощи и листовые салаты почти без углеводов (2–5 г); яйца, мясо, рыба, сыр — около нуля.
Перечисляй ВСЕ компоненты блюда, включая безуглеводные (мясо, яйцо, сыр, овощи) с carbs_g = 0 — они несут жир и белок, это нужно модели.
У каждого компонента своё число углеводов — НЕ распределяй общий итог по компонентам. Сумма carbs_g × count по всем компонентам должна совпадать с серединой диапазона total_carbs_min…total_carbs_max.
Оцени также белки, жиры и ккал ВСЕЙ видимой порции в total_protein_g, total_fat_g и total_kcal. Ккал должны быть примерно согласованы с 4×белки + 4×углеводы + 9×жиры.
Порцию по фото видно плохо — отражай неопределённость в поле confidence.
Разбор должен быть воспроизводимым: на одно и то же фото — один и тот же ответ. speed и physical_form выводи по правилам из описаний полей, а не «на глаз». Жир учитывается ровно один раз — числом в total_fat_g. Белки и жиры оценивай справочным значением для блюда целиком, кратным 5 г, а не суммированием по ингредиентам.
Если дан список ИЗВЕСТНЫХ КОМПОНЕНТОВ пользователя — совпавший компонент бери из него (его углеводы, его факты), а не из справочных значений.
Каждое существенное предположение, которое пришлось сделать (сорт, обработка, способ приготовления — по фото их не видно), НАЗЫВАЙ в assumptions — не предполагай молча. Никогда не оценивай, «как быстро это поднимет сахар у него».
Никаких рекомендаций по дозам инсулина."""

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
    ): JSONObject {
        val captionLine = caption?.takeIf { it.isNotBlank() && it != "фото" }
            ?.let { "\nПодпись пользователя к фото: «$it» — учти её при определении." } ?: ""
        return foodToolBody(
            imageContent(
                b64,
                FOOD_VISION_INSTR + captionLine + knownDishesBlock(knownDishes) +
                    knownComponentsBlock(knownComponents, caption) + personalBlock(personalContext),
            ),
            // 1600: the vision output is the same v4 shape plus the
            // recognition fields — same truncation math as the text path.
            maxTokens = 1600,
            knownDishes = knownDishes,
        )
    }

    /** Vision: describe a food photo, recognising the user's known dishes.
     *  Blocking — invoke on Dispatchers.IO. */
    fun describeFood(
        apiKey: String,
        jpegBytes: ByteArray,
        caption: String? = null,
        personalContext: String? = null,
        knownDishes: List<KnownDishForVision> = emptyList(),
        knownComponents: List<KnownComponent> = emptyList(),
    ): VisionResult {
        val b64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
        val input = postToolInput(
            apiKey,
            visionRequestBody(b64, caption, personalContext, knownDishes, knownComponents),
            FOOD_TOOL_NAME,
        )
        // An analysis with NO composition must never reach the store — callers only
        // check for a specific error-marker string, so it would be saved as-is. A name-only render is
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
        val captionLine = caption?.takeIf { it.isNotBlank() && it != "фото" }
            ?.let { "\nПодпись пользователя к фото: «$it» — учти её при определении." } ?: ""
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 512)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("content", imageContent(b64, FOOD_PROMPT + captionLine + personalBlock(personalContext))),
                ),
            )
        return VisionResult(post(apiKey, body))
    }

    /** Blocking HTTP call — invoke on Dispatchers.IO. */
    fun ask(apiKey: String, dataContext: String, history: List<ChatMsg>, question: String): String {
        val messages = JSONArray()
        history.takeLast(6).forEach { m ->
            messages.put(JSONObject().put("role", m.role).put("content", m.text))
        }
        messages.put(
            JSONObject().put("role", "user").put(
                "content",
                "СВОДКА ДАННЫХ:\n$dataContext\n\nВОПРОС: $question",
            ),
        )
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1024)
            .put("system", SYSTEM)
            .put("messages", messages)
        return post(apiKey, body)
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

    /** Text answer — concatenated text blocks. */
    private fun post(apiKey: String, body: JSONObject): String {
        val json = postRaw(apiKey, body)
        val content = json.getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.getString("type") == "text") append(block.getString("text"))
            }
        }.ifBlank { "Пустой ответ (stop_reason: ${json.optString("stop_reason")})" }
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
