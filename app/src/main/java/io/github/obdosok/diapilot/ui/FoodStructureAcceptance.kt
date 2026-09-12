package io.github.obdosok.diapilot.ui

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.obdosok.diapilot.LocalAppGraph
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.i18n.localized
import io.github.obdosok.diapilot.data.FoodStructureProposalRuntime
import io.github.obdosok.diapilot.data.SqliteCollectorStore
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
    val graph = LocalAppGraph.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<FoodStructureProposalRuntime.Row>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        rows = withContext(Dispatchers.IO) {
            runCatching {
                FoodStructureProposalRuntime.rows(context, graph.store as SqliteCollectorStore)
            }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(expanded) { if (expanded) reload() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.food_structure_acceptance_intro),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.food_structure_acceptance_title), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) {
                        stringResource(R.string.food_structure_acceptance_hide)
                    } else {
                        stringResource(R.string.food_structure_acceptance_show)
                    },
                )
            }
        }
        if (!expanded) {
            Text(
                stringResource(R.string.food_structure_acceptance_collapsed_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Text(
            stringResource(R.string.food_structure_acceptance_append_note),
            style = MaterialTheme.typography.bodySmall,
        )
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        rows.forEach { row ->
            Card(Modifier.padding(vertical = 2.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.proposed.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (row.proposed.alcohol) {
                            stringResource(
                                R.string.food_structure_acceptance_speed_line_alcohol,
                                pct(row.proposed.fast), pct(row.proposed.medium),
                                pct(row.proposed.slow), row.proposed.form.name,
                            )
                        } else {
                            stringResource(
                                R.string.food_structure_acceptance_speed_line,
                                pct(row.proposed.fast), pct(row.proposed.medium),
                                pct(row.proposed.slow), row.proposed.form.name,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.food_structure_acceptance_preview_line, row.previewNow, row.previewThen),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(row.proposed.why, style = MaterialTheme.typography.bodySmall)
                    if (!row.proposed.blind) Text(
                        stringResource(R.string.food_structure_acceptance_blind_warning),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (row.pending > 0) {
                            stringResource(R.string.food_structure_acceptance_pending, row.pending, row.matched)
                        } else {
                            pluralStringResource(
                                R.plurals.food_structure_acceptance_already_accepted,
                                row.alreadyAccepted, row.alreadyAccepted,
                            )
                        },
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
                                            context, graph.store as SqliteCollectorStore,
                                            row.proposed.id,
                                        )
                                    }.getOrDefault(0)
                                }
                                note = context.localized().resources.getQuantityString(
                                    R.plurals.food_structure_acceptance_note_update, n, row.proposed.title, n,
                                )
                                reload()
                                busy = false
                            }
                        },
                    ) { Text(stringResource(R.string.food_structure_acceptance_apply)) }
                }
            }
        }
        if (rows.isEmpty()) {
            Text(stringResource(R.string.food_structure_acceptance_no_rows), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun pct(x: Double) = "${Math.round(x * 100)}%"
