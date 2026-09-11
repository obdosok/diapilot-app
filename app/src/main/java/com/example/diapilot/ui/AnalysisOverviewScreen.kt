package com.example.diapilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diapilot.core.analysis.GlycemicStats
import com.diapilot.core.analysis.glycemicStats
import com.example.diapilot.data.HybridModelStore
import com.example.diapilot.data.Settings
import com.example.diapilot.data.SqliteCollectorStore
import com.example.diapilot.data.Stores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Product-facing analysis.
 *
 * This deliberately reads only 30 days of glucose plus the already-scored
 * prospective ledgers. Opening a tab must not retrain physiology, run
 * walk-forward folds, or replay the old forecasting engine.
 */
@Composable
fun AnalysisScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var data by remember { mutableStateOf<AnalysisOverview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // `ClosedEpisodeRuntime.updates` was retired along with the pipeline it
    // fed; the screen no longer recomposes from its generation.

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                val store = Stores.get(context)
                val now = System.currentTimeMillis()
                val from30 = now - 30L * 24 * 60 * 60_000
                val from7 = now - 7L * 24 * 60 * 60_000
                val readings = store.sensorReadings(from30, now)
                val lo = Settings.rangeLoMmol(context)
                val hi = Settings.rangeHiMmol(context)
                val db = (store as? SqliteCollectorStore)?.readableDatabase
                // Analysis is a read-only projection. Learning/promotion is
                // maintained by the normal causal runtime, never by rendering.
                val artifact=com.example.diapilot.data.PhysioRuntime.artifact(store,now)
                val rescueEvents=com.diapilot.core.analysis.rescueEvents(store.annotations(from30,now),from30,now)
                val rescueSafety=com.diapilot.core.analysis.rescueSafetyEpisodesV1(rescueEvents,readings,store.boluses(from30,now))
                // Same computation the model applies: the card calls
                // AdaptiveIsfRuntime.balance instead of computing its own balance.
                val balance=com.example.diapilot.data.AdaptiveIsfRuntime.balance(store,now,windowDays=14)
                val balanceCarbSens=com.example.diapilot.data.AdaptiveIsfRuntime.carbSens(store,now)
                AnalysisOverview(
                    week = glycemicStats(readings.filter { it.tsMs >= from7 }, lo, hi),
                    month = glycemicStats(readings, lo, hi),
                    model = HybridModelStore.status(context),
                    hybridEnabled = true,
                    today = db?.let { com.example.diapilot.data.DailyDiscrepancyRuntime.view(it, now, store = store) },
                    rescueSafety=rescueSafety,
                    balance=balance,
                    balanceCarbSens=balanceCarbSens,
                )
            }
        }.onSuccess { data = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Анализ",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "То, что помогает оценить здоровье и честность прогноза.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (error != null) {
            item { OverviewCard("Не удалось прочитать метрики") { Text(error!!) } }
        } else if (data == null) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Считаю краткую статистику…",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val snapshot = data!!
            item {
                OverviewCard("Глюкоза") {
                    StatsRow("7 дней", snapshot.week)
                    StatsRow("30 дней", snapshot.month)
                }
            }
            item {
                ModelCard(snapshot)
            }
            item { TodayChangeCard(snapshot.today) }
            item { RescueSafetyCard(snapshot.rescueSafety) }
            item { DailyBalanceCard(snapshot.balance, snapshot.balanceCarbSens) }
            item {
            }
            item {
            }
            item {
                Text(
                    "Исследовательские бэктесты и переобучение больше не запускаются " +
                        "при открытии этой вкладки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class AnalysisOverview(
    val week: GlycemicStats?,
    val month: GlycemicStats?,
    val model: HybridModelStore.Status?,
    val hybridEnabled: Boolean,
    val today: com.example.diapilot.data.TodayDiscrepancyView?,
    val rescueSafety:List<com.diapilot.core.analysis.RescueSafetyEpisodeV1>,
    val balance: com.diapilot.core.physio.DailyBalanceIsfV1.Result?,
    val balanceCarbSens: Double?,
)

/**
 * DAILY BALANCE — the card that replaced "Closed causal segments".
 *
 * That one asked the same question — "what ISF would close the arithmetic" —
 * but PER SEGMENT, and its own comment admitted that at that scale meals,
 * activity, hepatic output and sensor error are observationally
 * interchangeable with insulin amplitude. It also subtracted the MODEL's
 * carb contribution, and the model delivers roughly a quarter less glucose
 * than a real meal does, so its "reconciling ISF" was systematically biased
 * high by that same amount.
 *
 * Here the same balance is computed per DAY with a 04:00 boundary, on raw
 * grams and units: over a full day the timing integrates out and most of
 * that interchangeability goes away.
 */
@Composable private fun DailyBalanceCard(
    result: com.diapilot.core.physio.DailyBalanceIsfV1.Result?,
    carbSens: Double?,
) {
    OverviewCard("Баланс по суткам") {
        Text(
            "углеводы × CS − единицы × ISF = изменение сахара за сутки. " +
                "Граница суток 04:00, не полночь: в это время он спит и не ел, поэтому " +
                "обе опорные точки берутся в покое.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result == null) {
            Text(
                "Пока нечего показать: нужно хотя бы " +
                    "${com.diapilot.core.physio.DailyBalanceIsfV1.MIN_DAYS} годных суток " +
                    "(≥${com.diapilot.core.physio.DailyBalanceIsfV1.MIN_CARBS_G.toInt()} г углеводов, " +
                    "≥${com.diapilot.core.physio.DailyBalanceIsfV1.MIN_UNITS.toInt()} ед инсулина " +
                    "и сахар на обеих границах).",
                style = MaterialTheme.typography.bodySmall,
            )
            return@OverviewCard
        }
        val stamp = java.text.SimpleDateFormat("dd.MM", java.util.Locale.getDefault())
        Text(
            "%-7s %6s %6s %7s %6s %6s".format("сутки", "углев", "ед", "dСахар", "г/ед", "ISF"),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        result.rows.forEach { d ->
            Text(
                "%-7s %6.0f %6.1f %+7.2f %6.1f %6.2f".format(
                    java.util.Locale.ROOT,
                    stamp.format(java.util.Date(d.startMs)), d.carbs, d.units, d.deltaMmol,
                    d.gramsPerUnit, d.isf(carbSens ?: 0.0),
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        Text(
            "Медиана за %d суток: ISF %.2f · размах %.2f…%.2f · CS %.3f ммоль/г".format(
                java.util.Locale.ROOT, result.days, result.isf,
                result.spread?.first ?: Double.NaN, result.spread?.second ?: Double.NaN,
                carbSens ?: Double.NaN,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Это оценка АМПЛИТУДЫ и только её: за сутки все тайминги — очередь ккал, " +
                "сито, форма инсулина — сокращаются из уравнения. Она не говорит, КОГДА " +
                "что-то произошло, и не заменяет прогноз.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable private fun RescueSafetyCard(rows:List<com.diapilot.core.analysis.RescueSafetyEpisodeV1>) {
    val prevented=rows.filter{it.outcome==com.diapilot.core.analysis.RescueOutcomeV1.AVERTED_LOW_COMPATIBLE}
    OverviewCard("Предотвращённые гипогликемии / rescue") {
        Text("Декстроза меняет исход. Поэтому отсутствие измеренной гипогликемии после неё не считается успешным прогнозом: такой эпизод цензурируется и входит в safety-метрику «потребовалось спасение».",style=MaterialTheme.typography.bodySmall)
        Text("За 30 дней: rescue-событий ${rows.size}; совместимы с предотвращённой гипогликемией ${prevented.size}.",fontWeight=FontWeight.SemiBold)
        prevented.takeLast(5).asReversed().forEach{r->
            val whenText=java.text.SimpleDateFormat("d MMM, HH:mm",java.util.Locale.getDefault()).format(java.util.Date(r.rescue.tsMs))
            Text("$whenText · ${"%.0f".format(r.rescue.grams)} г · сахар ${r.glucoseAtRescue?.let{"%.1f".format(it)}?:"?"} · скорость ${r.preSlopeMmolPerMin?.let{"%+.2f".format(it)}?:"?"} ммоль/л/мин · bolus за 4 ч ${"%.1f".format(r.activeRecentBolusUnits)} ед. Конечный исход причинно не оценивается без rescue.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Это улучшает оценку безопасности и обнаруживает stacking/ошибку прогноза. Один такой смешанный эпизод не переобучает ISF или профиль отдельного укола.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
    }
}



private fun humanEpisodeStatus(s:String)=when(s){
    "IDENTIFIED_ISF"->"изолированная коррекция: ISF идентифицируем"
    "EFFECTIVE_RESPONSE_ONLY"->"еда + инсулин: только совместный ответ"
    "CARB_ERROR_CANDIDATE"->"возможна ошибка углеводов"
    "TIMING_MISMATCH"->"итог сходится, траектория — нет"
    "SENSOR_OR_PROCESS"->"возможен датчик/процесс"
    "RETROSPECTIVE_CORRECTION"->"изолированная коррекция найдена ретроспективно; годится для диагностики, но не для автообучения"
    "CENSORED"->"окно не завершено"
    else->"не объяснено / смешано"
}



@Composable
private fun TodayChangeCard(today: com.example.diapilot.data.TodayDiscrepancyView?) {
    OverviewCard("Сегодня / текущее состояние") {
        if (today == null) Text("Пока нет завершённых 60/120-минутных causal checkpoints.") else {
            Text("Что изменилось", fontWeight = FontWeight.SemiBold); Text(today.effectiveLine); Text(today.identifiedIsfLine)
            Text(today.kineticsLine)
            Text("Что может объяснять", fontWeight = FontWeight.SemiBold); Text(today.explainedLine)
            today.contributionRows.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Что пока не объясняется", fontWeight = FontWeight.SemiBold); Text("Неаллоцированная часть остаётся unresolved/confounded.")
            Text("Какие данные смешаны", fontWeight = FontWeight.SemiBold); Text(today.mixedDataLine)
            Text("Хронология", fontWeight = FontWeight.SemiBold); today.timeline.forEach { Text(it) }
        }
    }
}

@Composable




private fun humanConclusion(r:com.diapilot.core.physio.FactorEvidenceV1)=when(r.status){
    com.diapilot.core.physio.EvidenceStatus.SOURCE_MISSING->"Для этого фактора пока нет структурированных записей."
    com.diapilot.core.physio.EvidenceStatus.NO_EXPOSURES->"Источник подключён, но подходящих событий ещё не было."
    com.diapilot.core.physio.EvidenceStatus.NOT_IDENTIFIABLE->"Данные есть, но это влияние пока нельзя отделить от еды, инсулина или других факторов."
    com.diapilot.core.physio.EvidenceStatus.INSUFFICIENT->"Подходящих независимых эпизодов пока недостаточно."
    com.diapilot.core.physio.EvidenceStatus.NULL_COMPATIBLE->"Практически значимая устойчивая связь пока не обнаружена."
    com.diapilot.core.physio.EvidenceStatus.HYPOTHESIS->"В истории есть возможный сигнал, но направление или величина ещё неустойчивы."
    com.diapilot.core.physio.EvidenceStatus.PRELIMINARY->"Предварительная связь повторяется в доступных данных, но ещё требует будущей проверки."
    com.diapilot.core.physio.EvidenceStatus.SUPPORTED->"Связь прошла prospective-проверку на будущих данных."
    com.diapilot.core.physio.EvidenceStatus.CONFLICTING->"Разные части истории показывают разные направления эффекта."
}

private fun collectionHint(id:String)=when(id){
    "protein_absorption","fat_absorption"->"Записывать Б/Ж или подтверждать разбор фото до оценки результата."
    "duration_absorption"->"Указывать длительность приёма в уточнении еды."
    "alcohol_background"->"Отмечать алкоголь в записи еды."
    "cartridge_kinetics"->"Отмечать «новая ампула» при каждой замене."
    "injection_site"->"Отмечать место укола при его изменении."
    "cartridge_heat"->"Отмечать нагрев/жару вместе с текущей ампулой."
    "stress_illness_background"->"Отмечать стресс или болезнь в момент события."
    else->"Продолжать обычный сбор данных."
}

private fun measurementDescription(id:String)=when(id){
    "activity_same_isf"->"Что считается: только чистые коррекции; сравнивается сила снижения после инсулина, если активность была до коррекции, с днями без неё. Еда и активность внутри окна исключают чистую оценку ISF."
    "activity_same_effective"->"Что считается: законченные цепочки еды + IOB/болюса. Остаточное снижение относительно модели делится на ожидаемый вклад инсулина; активность ищется от 6 часов до начала и до конца эпизода. Это суммарный эффективный ответ (включая утилизацию глюкозы мышцами), а не доказанный чистый ISF."
    else->null
}



@Composable
private fun OverviewCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun StatsRow(label: String, stats: GlycemicStats?) {
    if (stats == null) {
        Text("$label · нет данных", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                "среднее %.1f ммоль/л".format(stats.mean),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            Text("%.0f%% в диапазоне".format(stats.inRangePct), fontWeight = FontWeight.SemiBold)
            Text(
                "%.1f%% ниже · %.1f%% выше".format(stats.belowPct, stats.abovePct),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelCard(snapshot: AnalysisOverview) {
    OverviewCard("Персональная модель") {
        val model = snapshot.model
        if (model == null) {
            Text("Модель не установлена")
        } else {
            Text("Измеренная модель используется для прогноза")
            Text(
                "Обучена по ${model.trainingDays} дням · данные по ${model.trainedThrough ?: "—"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${model.modelVersion} · ${model.activeSha256.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


