package com.example.diapilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.diapilot.data.FoodStructureProposalRuntime
import com.example.diapilot.data.SqliteCollectorStore
import com.example.diapilot.data.Stores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The `no auto-teach` gate for food structure: a suggestion becomes a stored
 * fact only here, one dish at a time, with the number of records it will touch
 * shown BEFORE the tap.
 *
 * The before/after minutes are not recomputed here. They were produced by the
 * laptop stand on the engine that reproduced 61 of 61 of this phone's stored
 * cards; a second model inside the UI is precisely the divergence those numbers
 * exist to prevent.
 */
@Composable
fun FoodStructureAcceptanceSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<FoodStructureProposalRuntime.Row>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        rows = withContext(Dispatchers.IO) {
            runCatching {
                FoodStructureProposalRuntime.rows(context, Stores.get(context) as SqliteCollectorStore)
            }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(expanded) { if (expanded) reload() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Распознанное блюдо говорит модели, ЧТО вы съели — граммы и БЖУ. СКОРОСТЬ " +
                "считается из этого состава и калорийной очереди, одинаково для всех блюд.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Структура повторяющихся блюд", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "скрыть" else "открыть") }
        }
        if (!expanded) {
            Text(
                "Форма и скорость углеводов для блюд, которые повторяются. " +
                    "Ничего не применяется без вашего подтверждения.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Text(
            "Принятие ДОПОЛНЯЕТ запись, а не переписывает: прежний текст остаётся, " +
                "а момент принятия фиксируется, поэтому прошлые расчёты воспроизводимы.",
            style = MaterialTheme.typography.bodySmall,
        )
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        rows.forEach { row ->
            Card(Modifier.padding(vertical = 2.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.proposed.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "быстрые ${pct(row.proposed.fast)} · средние ${pct(row.proposed.medium)} · " +
                            "медленные ${pct(row.proposed.slow)} · ${row.proposed.form.name}" +
                            if (row.proposed.alcohol) " · алкоголь" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "старт/пик/90%: сейчас ${row.previewNow} → станет ${row.previewThen} мин",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(row.proposed.why, style = MaterialTheme.typography.bodySmall)
                    if (!row.proposed.blind) Text(
                        "⚠ оценка делалась при уже известной наблюдённой медиане этого блюда — " +
                            "в подтверждающем тесте не участвует",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (row.pending > 0) "будет изменено записей: ${row.pending} из ${row.matched}"
                        else "уже принято (${row.alreadyAccepted} записей)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        enabled = !busy && row.pending > 0,
                        onClick = {
                            busy = true
                            scope.launch {
                                val n = withContext(Dispatchers.IO) {
                                    runCatching {
                                        FoodStructureProposalRuntime.accept(
                                            context, Stores.get(context) as SqliteCollectorStore,
                                            row.proposed.id,
                                        )
                                    }.getOrDefault(0)
                                }
                                note = "«${row.proposed.title}»: изменено записей $n"
                                reload()
                                busy = false
                            }
                        },
                    ) { Text("Применить") }
                }
            }
        }
        if (rows.isEmpty()) Text("Предложение не загрузилось.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun pct(x: Double) = "${Math.round(x * 100)}%"
