package com.example.diapilot.ui

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
import androidx.compose.ui.unit.dp
import com.diapilot.core.analysis.MGDL_PER_MMOL_F
import com.example.diapilot.data.FoodEraSettings
import com.example.diapilot.data.Settings
import com.example.diapilot.data.Units
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
        Text("Настройки", style = MaterialTheme.typography.titleLarge)

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
                    "Персональная модель",
                    style = MaterialTheme.typography.titleMedium,
                )
                var modelStatus by remember {
                    mutableStateOf(
                        com.example.diapilot.data.HybridModelStore.status(context),
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
                                modelMessage = "Проверяю модель…"
                                try {
                                    val installed = kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.Dispatchers.IO,
                                    ) {
                                        context.contentResolver.openInputStream(uri).use { input ->
                                            requireNotNull(input) { "файл недоступен" }
                                            com.example.diapilot.data.HybridModelStore.install(
                                                context,
                                                input,
                                                "manual-import",
                                            )
                                        }
                                    }
                                    modelStatus = installed
                                    modelMessage = "Модель установлена атомарно"
                                } catch (error: Exception) {
                                    modelMessage = "Модель отклонена: ${error.message}"
                                }
                            }
                        }
                    }
                modelStatus?.let { status ->
                    Text(
                        buildString {
                            append("${status.modelVersion} · ")
                            append("${status.trainingDays} дн. · ")
                            append("до ${status.trainedThrough ?: "—"}\n")
                            append("данные с ${status.eligibleFrom} ")
                            append("(${status.eligibleTimezone})\n")
                            append("SHA ${status.activeSha256.take(12)}… · ")
                            append(status.source)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: Text(
                    "Модель ещё не инициализирована",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        modelImportLauncher.launch(
                            arrayOf("application/json", "text/json", "*/*"),
                        )
                    }) {
                        Text("Импортировать JSON")
                    }
                    TextButton(
                        enabled = modelStatus?.previousSha256 != null,
                        onClick = {
                            modelScope.launch {
                                val restored = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) {
                                    com.example.diapilot.data.HybridModelStore.rollback(context)
                                }
                                if (restored == null) {
                                    modelMessage = "Нет предыдущего поколения"
                                } else {
                                    modelStatus = restored
                                    modelMessage = "Выполнен rollback модели"
                                }
                            }
                        },
                    ) {
                        Text("Откатить")
                    }
                }
                Text(
                    "Принимается только модель этого человека, обученная на данных " +
                        "не раньше $foodEraLabel. Ошибка импорта не влияет " +
                        "на текущий прогноз.",
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Единицы глюкозы", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !mgdl,
                        onClick = { Units.setMgdl(context, false); mgdl = false },
                        label = { Text("ммоль/л") },
                    )
                    FilterChip(
                        selected = mgdl,
                        onClick = { Units.setMgdl(context, true); mgdl = true },
                        label = { Text("мг/дл") },
                    )
                }
                Text(
                    "Только отображение: данные и аналитика хранятся в ммоль/л, " +
                        "переключение мгновенное и безопасное.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Тело", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { t ->
                        weightText = t
                        Settings.setWeightKg(context, t.replace(',', '.').trim().toDoubleOrNull())
                    },
                    label = { Text("Вес, кг") },
                    supportingText = {
                        // The number is shown because a prior the user cannot
                        // see is a prior the user cannot disagree with — and the
                        // user's own measured value is the thing that should
                        // eventually replace it.
                        val cs = com.diapilot.core.analysis.CarbSensitivityPriorV1
                            .fromWeight(Settings.weightKg(context))
                        Text(
                            "Стартовая оценка углеводов: %.3f ммоль/л на грамм. ".format(cs) +
                                "Грамм распределяется в объёме, пропорциональном массе тела, " +
                                "поэтому оценка обратна весу. Измерения по вашим данным её заменят.",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Цель и диапазон", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { t ->
                        targetText = t
                        parse(t)?.let { Settings.setTargetMmol(context, it) }
                    },
                    label = { Text("Идеальный сахар (${com.diapilot.core.analysis.unitLabel(mgdl)})") },
                    supportingText = {
                        Text("Подсказки «High +X» считают превышение относительно него")
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
                        label = { Text("Диапазон: низ") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = hiText,
                        onValueChange = { t ->
                            hiText = t
                            parse(t)?.let { Settings.setRangeHiMmol(context, it) }
                        },
                        label = { Text("Диапазон: верх") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Диапазон — зелёная зона: TIR, полоса на графике и порог, " +
                        "после которого подсказка вообще подаёт голос.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Инсулин · измеренная кривая", style = MaterialTheme.typography.titleMedium)
                // This card describes the model the forecast actually runs. It
                // used to read the bundled artifact unconditionally, so it
                // showed the shipped ISF and timing even when the
                // measured curve was in charge — and gave no way to tell a
                // learned value from a hardcoded one. Reading the model touches
                // SQLite, so it is produced off the composition thread.
                val insulinView by androidx.compose.runtime.produceState<InsulinCardView?>(null) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val store = com.example.diapilot.data.Stores.get(context)
                        val now = System.currentTimeMillis()
                        // The SAME object the forecast runs on — not a second
                        // reading of the same question. See InsulinProfileRuntime.
                        val profileState = (store as? com.example.diapilot.data.SqliteCollectorStore)
                            ?.let { sq ->
                                runCatching {
                                    com.example.diapilot.data.InsulinProfileRuntime
                                        .state(store, sq.readableDatabase, now)
                                }.getOrNull()
                            }
                        val artifact =
                            runCatching { com.example.diapilot.data.PhysioRuntime.artifact(store, now) }.getOrNull()
                        val model = artifact?.let {
                            val hour = java.util.Calendar.getInstance()
                                .get(java.util.Calendar.HOUR_OF_DAY).toDouble()
                            runCatching { it.personModelAt(hour) }.getOrNull()
                        } ?: com.example.diapilot.data.HybridRuntimeMetrics.model()
                        // The ISF-deviations card was removed along with the episode
                        // pipeline it used to read from.
                        val deviations = emptyList<String>()
                        InsulinCardView(
                            model, artifact, profileState?.curve, deviations,
                            profileState?.let { com.example.diapilot.data.InsulinProfileRuntime.explain(it) },
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
                    "Один движок на всё: линия, What-if, часы и гипо-тревога считают по измеренной " +
                        "кривой — старт 11 · пик ~60 · хвост 120, ISF из ваших коррекций. Разошедшихся рук больше нет.",
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
                    // The numbers were never in disagreement — the labels were.
                    Text(
                        "ISF %.1f %s/ед · начало %.0f мин · вершина скорости %.0f мин"
                            .format(
                                java.util.Locale.ROOT,
                                isf,
                                com.diapilot.core.analysis.unitLabel(mgdl),
                                insulinLandmarks.onsetMin,
                                insulinLandmarks.ratePeakMin,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Это ТА ЖЕ кривая, что измерена ниже, прочитанная другой линейкой: " +
                            "«вершина скорости» — верхушка горба, «пик» в измерении — минута " +
                            "выхода на полную скорость. Расходятся примерно на 15 минут по " +
                            "построению, а не по ошибке.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Основная кривая %.0f мин · дозозависимый хвост до %.0f мин"
                            .format(
                                java.util.Locale.ROOT,
                                insulinModel.insulin.shortDurationMin,
                                insulinLandmarks.effectEndMin,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Drawn by SAMPLING THE ENGINE, not by re-deriving a curve
                    // from the landmark numbers above. Whatever is in force —
                    // hand-entered, measured, or the parametric prior — this is
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
                    // answer "are these numbers measured or hardcoded" — and a
                    // prior that silently looks like a measurement is worse than
                    // no number.
                    val measuredShape = insulinModel.insulin.actionCdfKnots.isNotEmpty()
                    Text(
                        if (measuredShape) "⏱ Форма измерена по вашим уколам"
                        else "⏱ Форма — популяционный приор, это НЕ ваши данные",
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
                                "не дали ориентиров: $reason — $n",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    if (measuredCurve != null && !measuredShape) {
                        Text(
                            "кривая построена, но НЕ применена к прогнозу — см. расхождения артефакта",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // WHY the count is what it is. "0 of 4" alone is
                    // indistinguishable from a broken gate — it took several
                    // install-and-pull rounds to find out which gate was
                    // holding, and every number below was already computed and
                    // stored, just never shown.
                        // ---- P1: manually entered -------------------------------
                    // Precedence: what the user enters wins over what the app
                    // measures, and the app may then only REPORT that its own
                    // numbers differ. This is also the whole answer to cold
                    // start — somebody two days in has no measured curve, and
                    // "wait a month" is not an answer to a user who already
                    // knows when their insulin starts.
                    var manualTick by remember { mutableStateOf(0) }
                    val stored = remember(manualTick) {
                        com.example.diapilot.data.ManualInsulinRuntime.params(context)
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
                    Text("Задать вручную", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Минуты от укола: начало действия · активная фаза с и до · полный конец. " +
                            "Пустое поле — считаем сами. Заполненное имеет приоритет над измеренным, " +
                            "и мы сможем только сказать, что у нас вышло иначе.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Four landmarks, not three. The most active phase is
                        // an interval — "from 30 to 70, then it tapers off" —
                        // and a single peak forces the rate to turn over the
                        // moment it arrives, which is the triangle again.
                        listOf(
                            Triple("начало", onsetIn, { v: String -> onsetIn = v }),
                            Triple("активно с", peakIn, { v: String -> peakIn = v }),
                            Triple("активно до", plateauIn, { v: String -> plateauIn = v }),
                            Triple("конец", tailIn, { v: String -> tailIn = v }),
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
                        suffix = { Text("${com.diapilot.core.analysis.unitLabel(mgdl)}/ед") },
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
                            com.example.diapilot.data.ManualInsulinRuntime.setParams(context, entered)
                            manualTick++
                            manualNote = if (entered.any) "Сохранено — пересчитываю прогноз" else "Очищено"
                        }) { Text("Применить") }
                        androidx.compose.material3.TextButton(onClick = {
                            com.example.diapilot.data.ManualInsulinRuntime.setParams(
                                context, com.diapilot.core.physio.ManualInsulinParamsV1.EMPTY,
                            )
                            manualTick++
                            manualNote = "Очищено — вернулись к измеренному"
                        }) { Text("Сбросить") }
                    }
                    // The rejection is shown, never a silent clamp: a value
                    // quietly moved into range would teach the user that the
                    // field does something it does not.
                    val manualResolution = remember(manualTick, insulinModel, measuredCurve, measuredIsf) {
                        runCatching {
                            com.example.diapilot.data.ManualInsulinRuntime.resolve(
                                insulinModel, stored, measuredCurve, measuredIsf,
                            )
                        }.getOrNull()
                    }
                    manualResolution?.rejected?.forEach {
                        Text(
                            when (it) {
                                com.diapilot.core.physio.InsulinParameterResolverV1.SHAPE_OUT_OF_DOMAIN ->
                                    "⚠ Форма не принята: начало < пик < конец, и каждое в физиологических границах"
                                com.diapilot.core.physio.InsulinParameterResolverV1.ISF_OUT_OF_DOMAIN ->
                                    "⚠ ISF не принят: вне физиологических границ"
                                else -> "⚠ Не удалось построить кривую по этим числам"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // The only channel left open once P1 is in force.
                    manualResolution?.divergences?.forEach { d ->
                        val unit = if (d.field == com.diapilot.core.physio.ManualInsulinParamsV1.ISF)
                            "${com.diapilot.core.analysis.unitLabel(mgdl)}/ед" else "мин"
                        val k = if (d.field == com.diapilot.core.physio.ManualInsulinParamsV1.ISF && mgdl)
                            MGDL_PER_MMOL_F else 1.0
                        val name = when (d.field) {
                            com.diapilot.core.physio.ManualInsulinParamsV1.ONSET -> "начало"
                            com.diapilot.core.physio.ManualInsulinParamsV1.PEAK -> "начало активной фазы"
                            com.diapilot.core.physio.ManualInsulinParamsV1.PLATEAU_END -> "конец активной фазы"
                            com.diapilot.core.physio.ManualInsulinParamsV1.TAIL -> "конец"
                            else -> "ISF"
                        }
                        Text(
                            "≠ По вашим данным %s %.2f %s, у вас задано %.2f — используем ваше"
                                .format(java.util.Locale.ROOT, name, d.measured * k, unit, d.manual * k),
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
                    // is indistinguishable from a broken gate — and was one,
                    // more than once.
                    // The "Your corrections" card was removed: manual dose
                    // judging was used only rarely, and the corpus it gated no
                    // longer feeds the model — amplitude comes from the daily
                    // balance instead. A tool nobody uses and that affects
                    // nothing is not an observation, it is decoration.

                    val isfEpisodes = physioArtifact?.globalIsf?.identifyingEpisodes ?: 0
                    Text(
                        if (isfEpisodes > 0) {
                            "💉 ISF измерен по закрытым коррекциям · эпизодов $isfEpisodes"
                        } else {
                            "💉 ISF — приор артефакта, своих закрытых коррекций пока нет"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isfEpisodes > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Один движок на всё: линия, What-if, часы и гипо-тревога читают одну модель. " +
                            "Запасной твин включается, только если физио-артефакт не построился.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Персональная модель недоступна. До её восстановления работает резервный DiaPilot.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Старые ручные значения сохранены только для аварийного отката и в обычной работе не участвуют.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Алерты", style = MaterialTheme.typography.titleMedium)
                var hypoAlert by remember { mutableStateOf(Settings.hypoAlertEnabled(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Прогноз гипогликемии (за ~45 мин)")
                    Switch(
                        checked = hypoAlert,
                        onCheckedChange = {
                            hypoAlert = it
                            Settings.setHypoAlertEnabled(context, it)
                        },
                    )
                }
                Text(
                    "Модель смотрит на 45 минут вперёд каждую минуту данных. " +
                        "Качество правила — в Анализе, карточка «Гипо-алерт»: " +
                        "точность, ловля, ложные в день.",
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
                        Text("Звук алерта (слышно на «без звука»)")
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
                        Text("Ночью мягко, потом громче")
                        Switch(
                            checked = nightGentle,
                            onCheckedChange = { nightGentle = it; Settings.setHypoNightGentle(context, it) },
                        )
                    }
                    Text(
                        "Звук играет на АЛАРМ-канале — слышно, даже когда телефон на «без звука» " +
                            "(но громкость будильника должна быть поднята, и DiaPilot добавлен в " +
                            "исключения «Не беспокоить», если он включён ночью). " +
                            "Ночью (0-7ч) сначала тихая вибрация, через 5 мин без реакции — полный будильник.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        val hasVib = com.example.diapilot.collect.AlarmPlayer.hasVibrator(context)
                        com.example.diapilot.collect.AlarmPlayer.alarm(
                            context, withSound = Settings.hypoAlertSound(context), seconds = 3,
                        )
                        android.widget.Toast.makeText(
                            context,
                            if (hasVib) "Сигнал отправлен (вибро есть)" else "⚠ Вибромотор недоступен",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text("🔔 Проверить сигнал") }

                    var dexSnooze by remember { mutableStateOf(Settings.hypoDextroseSnooze(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Пауза алертов после декстрозы")
                        Switch(
                            checked = dexSnooze,
                            onCheckedChange = { dexSnooze = it; Settings.setHypoDextroseSnooze(context, it) },
                        )
                    }
                    if (dexSnooze) {
                        var snoozeMin by remember { mutableStateOf(Settings.hypoSnoozeMin(context)) }
                        StepperRow("Окно всасывания декстрозы", snoozeMin, "мин", 5, 45, 5) {
                            snoozeMin = it; Settings.setHypoSnoozeMin(context, it)
                        }
                    }
                    var lowRefire by remember { mutableStateOf(Settings.hypoLowRefireMin(context)) }
                    StepperRow("Повтор при низком каждые", lowRefire, "мин", 3, 30, 1) {
                        lowRefire = it; Settings.setHypoLowRefireMin(context, it)
                    }
                    var persistMin by remember { mutableStateOf(Settings.hypoPersistMin(context)) }
                    StepperRow("Затяжная гипа → громче через", persistMin, "мин", 10, 60, 5) {
                        persistMin = it; Settings.setHypoPersistMin(context, it)
                    }
                    Text(
                        "Съел декстрозу — алерты молчат на окно всасывания; если через него сахар " +
                            "всё ещё низкий, алерт вернётся (лечение не помогло). Если гипа длится " +
                            "дольше «затяжной» — полный будильник и повтор каждые 10 мин, даже в " +
                            "мягком ночном режиме. Замер глюкометром в норме — гасит алерты. " +
                            "Неправдоподобно низкие значения (артефакт сенсора) → «проверьте сахар», " +
                            "не полный алярм.",
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
                    Text("Прогноз выхода вверх (за ~40 мин)")
                    Switch(
                        checked = hyperAlert,
                        onCheckedChange = {
                            hyperAlert = it
                            Settings.setHyperAlertEnabled(context, it)
                        },
                    )
                }
                Text(
                    "Экспериментально: предупреждение до пересечения верхней " +
                        "границы — меньше взлётов к 300 начинается с 40 минут форы. " +
                        "Ночью гипо-алерт чувствительнее (нижний край коридора).",
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
                    label = { Text("Мой план при гипо (согласуйте с врачом)") },
                    placeholder = { Text("например: 10 г сока, проверка через 15 мин") },
                    singleLine = true,
                )
                Text(
                    "Алерт напоминает ВАШ план дословно — приложение не вычисляет " +
                        "лечение. Пусто — алерт просто попросит проверить сахар.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Libre 2 — свой приём (эксперимент)", style = MaterialTheme.typography.titleMedium)
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
                    Text("DiaPilot сам держит BLE-связь с сенсором")
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
                    com.example.diapilot.data.Libre2State.load(context)
                }
                Text(
                    if (bleState != null) {
                        "Сенсор ${bleState.serial} · MAC ${bleState.mac} · " +
                            "соединение №${bleState.connectionIndex} · " +
                            "ключей: ${bleState.unlockArray.size}"
                    } else {
                        "Ключей нет. Включите тумблер и отсканируйте сенсор (NFC) — " +
                            "скан включит стриминг на DiaPilot."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "⚠ Сенсор держит одно BLE-соединение: включение забирает поток у xDrip. " +
                        "Вернуть xDrip — пересканировать сенсор в нём. Расшифровка — через OOP2.",
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
                    Text("Диагностика", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.TextButton(onClick = { diagTick++ }) { Text("🔄") }
                }
                var diag by remember { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(diagTick) {
                    diag = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val now = System.currentTimeMillis()
                        val store = com.example.diapilot.data.Stores.get(context)
                        fun age(ts: Long): String =
                            if (ts <= 0) "—" else "${(now - ts) / 60_000} мин назад"
                        val d = com.example.diapilot.collect.DiagState
                        val lastMain = store.lastReading()
                        val lastMinute = store.lastMinuteReading()
                        val cal = com.example.diapilot.data.MinuteCalCache.get(store, context)
                        buildList {
                            add("BLE: ${d.bleStatus} · пакет ${age(d.bleLastPacketMs)}")
                            add(
                                "Основные показания: ${age(lastMain?.tsMs ?: 0)} " +
                                    "(${lastMain?.source ?: "—"})",
                            )
                            add("Минутный поток: ${age(lastMinute?.tsMs ?: 0)}")
                            add(
                                cal?.let {
                                    "Калибровка мин→осн: ×%.2f %+.1f (n=%d)".format(
                                        it.slope, it.intercept, it.n,
                                    )
                                } ?: "Калибровка мин→осн: нет",
                            )
                            add(
                                "Сенсор: ${com.example.diapilot.data.Settings.libreSensorSerial(context) ?: "—"}" +
                                    (com.example.diapilot.data.Settings.libreSensorStartMs(context)
                                        .takeIf { it > 0 }
                                        ?.let { " · день %.1f".format((now - it) / 86_400_000.0) } ?: ""),
                            )
                            add("Сервис запущен: ${age(d.serviceStartedMs)}")
                            add("Сервер часов: ${if (d.watchServerUp) "работает" else "выключен"}")
                            add("xDrip-бродкаст: ${age(d.lastXdripBroadcastMs)}")
                            add(
                                "Пары для этапа 4: " +
                                    com.example.diapilot.collect.Libre2PairLog.stats(context),
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
                Text("Данные", style = MaterialTheme.typography.titleMedium)
                var exportStatus by remember { mutableStateOf<String?>(null) }
                val exportScope = androidx.compose.runtime.rememberCoroutineScope()
                androidx.compose.material3.TextButton(onClick = {
                    exportStatus = "Экспортирую…"
                    exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        exportStatus = try {
                            val name = "diapilot-backup-" +
                                java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT)
                                    .format(java.util.Date()) + ".sqlite"
                            val store = com.example.diapilot.data.Stores.get(context)
                                as com.example.diapilot.data.SqliteCollectorStore
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
                                ) ?: error("не удалось создать файл")
                                context.contentResolver.openOutputStream(uri)!!.use {
                                    store.exportSnapshot(it)
                                }
                                "Сохранено в Загрузки: $name"
                            } else {
                                val f = java.io.File(
                                    context.getExternalFilesDir(null), name,
                                )
                                f.outputStream().use { store.exportSnapshot(it) }
                                "Сохранено: ${f.path}"
                            }
                        } catch (e: Exception) {
                            "Ошибка экспорта: ${e.message}"
                        }
                    }
                }) { Text("💾 Экспорт базы (.sqlite)") }
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
                    mutableStateOf<com.example.diapilot.data.PreEraPurge.Result?>(null)
                }
                var purgeStatus by remember { mutableStateOf<String?>(null) }
                var purging by remember { mutableStateOf(false) }
                Text(
                    "Начало периода обучения: $foodEraLabel. Данные до этой даты " +
                        "хранятся и видны в истории, но модель на них не учится.",
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
                                        purgeStatus = "Дата не может быть в будущем"
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
                ) { Text("📅 Изменить начало периода") }
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
                            val boundary = com.example.diapilot.data.PreEraPurge.boundary(context)
                                ?: return@launch
                            val store = com.example.diapilot.data.Stores.get(context)
                                as com.example.diapilot.data.SqliteCollectorStore
                            purgePreview = com.example.diapilot.data.PreEraPurge.preview(store, boundary)
                        }
                    },
                ) { Text("🧹 Удалить данные до $foodEraLabel") }
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
                        title = { Text("Удалить ${preview.total} строк?") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Всё, записанное до $foodEraLabel, " +
                                        "будет удалено безвозвратно. Эти записи сделаны до " +
                                        "выбранного начала периода обучения, и модель на них " +
                                        "уже не учится.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                preview.tables.forEach {
                                    Text(
                                        "${it.table}: ${it.rows}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text(
                                    "Сначала сделайте «Экспорт базы» — восстановить будет неоткуда.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                purgePreview = null
                                purging = true
                                purgeStatus = "Удаляю и сжимаю базу…"
                                exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    purgeStatus = try {
                                        val store = com.example.diapilot.data.Stores.get(context)
                                            as com.example.diapilot.data.SqliteCollectorStore
                                        val boundary = requireNotNull(
                                            com.example.diapilot.data.PreEraPurge.boundary(context),
                                        ) { "начало периода не выбрано" }
                                        val r = com.example.diapilot.data.PreEraPurge.purge(store, boundary)
                                        "Удалено ${r.total} строк, освобождено " +
                                            "${r.freedBytes / 1_048_576} МБ"
                                    } catch (e: Exception) {
                                        "Ошибка очистки: ${e.message}"
                                    }
                                    purging = false
                                }
                            }) { Text("Удалить") }
                        },
                        dismissButton = {
                            TextButton(onClick = { purgePreview = null }) { Text("Отмена") }
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
                }) { Text("♻️ Восстановить из копии (.sqlite)") }
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
                        title = { Text("Заменить все данные?") },
                        text = {
                            Text(
                                "Текущая база будет полностью заменена выбранной копией. " +
                                    "Приложение перезапустится. Действие необратимо — " +
                                    "если сомневаешься, сначала сделай экспорт текущей базы.",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                restoreUri = null
                                restoreStatus = "Восстанавливаю…"
                                exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        restoreStatus = com.example.diapilot.data.BackupRestore
                                            .restore(context, uri)
                                        kotlinx.coroutines.delay(1500)   // let the text render
                                        android.os.Process.killProcess(android.os.Process.myPid())
                                    } catch (e: Exception) {
                                        restoreStatus = "Ошибка восстановления: ${e.message}"
                                    }
                                }
                            }) { Text("Заменить") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { restoreUri = null },
                            ) { Text("Отмена") }
                        },
                    )
                }
                // --- Companion server: live remote dashboard + backup target.
                var compUrl by remember {
                    mutableStateOf(com.example.diapilot.data.Settings.companionUrl(context) ?: "")
                }
                var compToken by remember {
                    mutableStateOf(com.example.diapilot.data.Settings.companionToken(context) ?: "")
                }
                androidx.compose.material3.OutlinedTextField(
                    value = compUrl,
                    onValueChange = {
                        compUrl = it
                        com.example.diapilot.data.Settings.setCompanionUrl(context, it)
                    },
                    label = { Text("Компаньон-сервер (URL)") },
                    placeholder = { Text("http://host:8787") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = compToken,
                    onValueChange = {
                        compToken = it
                        com.example.diapilot.data.Settings.setCompanionToken(context, it)
                    },
                    label = { Text("Токен компаньона") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Личный дашборд в браузере: сахар, тренд, прогноз, события — " +
                        "обновляется каждую минуту; туда же уезжает суточная копия базы. " +
                        "Сервер — server/ в репозитории. Пусто = выключено.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // --- Cloud folder: the daily copy also lands in a user-picked
                // SAF directory (e.g. Google Drive) — the cloud app syncs it,
                // the data never touches any third-party server of ours.
                var cloudUri by remember {
                    mutableStateOf(com.example.diapilot.data.BackupRestore.cloudFolder(context))
                }
                val cloudLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                ) { uri ->
                    if (uri != null) {
                        com.example.diapilot.data.BackupRestore.setCloudFolder(context, uri)
                        cloudUri = uri
                    }
                }
                androidx.compose.material3.TextButton(onClick = { cloudLauncher.launch(null) }) {
                    Text(
                        if (cloudUri == null) "☁️ Папка облачной копии — выбрать"
                        else "☁️ Облачная копия: ${cloudUri?.lastPathSegment ?: "настроена"} · сменить",
                    )
                }
                if (cloudUri != null) {
                    androidx.compose.material3.TextButton(onClick = {
                        com.example.diapilot.data.BackupRestore.setCloudFolder(context, null)
                        cloudUri = null
                    }) { Text("Отключить облачную копию") }
                }
                Text(
                    "Полная копия всех данных (глюкоза, инсулин, еда, разметка) — " +
                        "в папку Загрузки. Плюс автокопия раз в сутки (7 файлов по дням " +
                        "недели, diapilot-auto-backup-N) — и в облачную папку, если " +
                        "выбрана (укажи папку Google Диска — синхронизирует сам Диск, " +
                        "данные не уходят никуда, кроме твоего облака). " +
                        "Восстановление — путь переезда на новый телефон.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Часы", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Сервер для циферблата (порт 29863)")
                    Switch(
                        checked = watchServer,
                        onCheckedChange = {
                            watchServer = it
                            Settings.setWatchServerEnabled(context, it)
                        },
                    )
                }
                Text(
                    "WatchDrip-совместимый локальный сервер для Zepp OS. " +
                        "Изменение применяется после перезапуска приложения.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (watchServer) {
                    TextButton(onClick = {
                        com.example.diapilot.collect.HypoAlertNotifier.postWatchTest(context)
                        android.widget.Toast.makeText(
                            context,
                            "Тест на часы (флаг «низко» на 3 мин) — вибрация в течение ~1-2 мин",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }) { Text("⌚ Тест вибро на часах") }
                    Text(
                        "Проверяет ОБА пути: флаг «низко» серверу циферблата (45с) и " +
                            "зеркалируемое уведомление (как реальный алерт). Часы завибрируют, " +
                            "если включён либо low-alarm циферблата, либо зеркалирование " +
                            "уведомлений Zepp с вибрацией.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    "Ночной тусклый AOD на часах",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                var dimMode by remember { mutableStateOf(Settings.watchNightDimMode(context)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Выкл", "По времени", "Вкл сейчас").forEachIndexed { i, label ->
                        androidx.compose.material3.FilterChip(
                            selected = dimMode == i,
                            onClick = { dimMode = i; Settings.setWatchNightDimMode(context, i) },
                            label = { Text(label) },
                        )
                    }
                }
                if (dimMode == 1) {
                    var startH by remember { mutableStateOf(Settings.watchNightStartH(context)) }
                    StepperRow("С", startH, "ч", 0, 23, 1) { startH = it; Settings.setWatchNightStartH(context, it) }
                    var endH by remember { mutableStateOf(Settings.watchNightEndH(context)) }
                    StepperRow("До", endH, "ч", 0, 23, 1) { endH = it; Settings.setWatchNightEndH(context, it) }
                }
                Text(
                    "Ночью циферблат почти гаснет (тусклые часы + сахар, красное при низком), " +
                        "график и остальное скрыты — можно держать AOD ночью, не слепя. " +
                        "Флаг едет на часы с данными (~минута задержки).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Экран блокировки", style = MaterialTheme.typography.titleMedium)
                var overlay by remember { mutableStateOf(Settings.overlayEnabled(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Показывать сахар поверх экрана блокировки")
                    Switch(
                        checked = overlay,
                        onCheckedChange = {
                            overlay = it
                            Settings.setOverlayEnabled(context, it)
                            if (!it) com.example.diapilot.collect.LockScreenOverlay.hide(context)
                        },
                    )
                }
                val canDraw = com.example.diapilot.collect.LockScreenOverlay.canDraw(context)
                if (overlay && !canDraw) {
                    Text(
                        "⚠ Нужно разрешение «Поверх других приложений».",
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
                    }) { Text("Выдать разрешение") }
                }
                Text(
                    "Плавающая плашка с сахаром/стрелкой — как у xDrip, видна не разблокируя. " +
                        "На MIUI дополнительно включите «Отображение всплывающих окон» и показ " +
                        "на экране блокировки в разрешениях DiaPilot.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // THREE SWITCHES THAT DECIDED BEHAVIOR AND COULD NOT BE FLIPPED.
        //
        // Audit: a set of settings had no setter call anywhere. Some of them
        // were never read either — those were deleted. These three ARE READ
        // by live code and sat on their default values because there was no
        // screen for them. The exact pattern already found on `hybridV11Main`:
        // a switch nobody can reach still decides how the model behaves.
        //
        // Default values are UNCHANGED — flipping any of them changes
        // behavior and must be measured, not chosen by eye. This screen exists
        // precisely to make that measurement possible: this is "shadow-safe"
        // in its original form — "behind a toggle".
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Экспериментальное", style = MaterialTheme.typography.titleMedium)

                var plausibility by remember { mutableStateOf(Settings.plausibilityGate(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Не доверять неправдоподобному якорю")
                    Switch(
                        checked = plausibility,
                        onCheckedChange = { plausibility = it; Settings.setPlausibilityGate(context, it) },
                    )
                }
                Text(
                    "Показание ниже пола 2.0, всплывшее из разрыва или входящее в " +
                        "артефактный выброс, перестаёт считаться надёжной опорой: прогноз " +
                        "теряет статус TRUSTED и называет причину. Правдоподобные якоря не " +
                        "трогает. Читает путь гипо-тревоги — включайте, наблюдая.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                var nightLow by remember { mutableStateOf(Settings.nightCorridorLow(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Ночью судить по нижней границе коридора")
                    Switch(
                        checked = nightLow,
                        onCheckedChange = { nightLow = it; Settings.setNightCorridorLow(context, it) },
                    )
                }
                Text(
                    "Ночная гипо-тревога срабатывает по НИЖНЕЙ границе прогноза, а не по " +
                        "центральной линии — раньше и чаще. На проверенной истории это давало " +
                        "много поводов без настоящих ночных низких, поэтому по умолчанию " +
                        "выключено. Включать, когда появится настоящий низкий, на котором " +
                        "видно полноту.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                var marksTeach by remember { mutableStateOf(Settings.marksTeachModel(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Отметки еды учат модель")
                    Switch(
                        checked = marksTeach,
                        onCheckedChange = { marksTeach = it; Settings.setMarksTeachModel(context, it) },
                    )
                }
                Text(
                    "Выключение любой из трёх меняет то, что модель учит, и вступает в " +
                        "силу после пересчёта корпуса.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A labelled −/＋ integer stepper row. */
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
        ) { Text("−") }
        Text("$value $unit")
        TextButton(
            enabled = value < max,
            onClick = { onChange((value + step).coerceAtMost(max)) },
        ) { Text("＋") }
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
     * is to pair the two and say so. It never acts on them — see
     * [com.example.diapilot.data.IsfDeviationRuntime].
     */
    val deviations: List<String> = emptyList(),
    /** One line: what is measured, or precisely what is missing. */
    val profileLine: String? = null,
    /** Doses that gave no landmark at all, by named reason — a "perfectly
     * compensated meal" is one of them and must be visible. */
    val refusals: Map<String, Int> = emptyMap(),
)
