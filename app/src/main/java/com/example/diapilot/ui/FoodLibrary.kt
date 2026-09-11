package com.example.diapilot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.diapilot.core.analysis.FoodMemory
import com.diapilot.core.analysis.normalizeFoodName
import com.example.diapilot.data.SqliteCollectorStore
import com.example.diapilot.data.SqliteCollectorStore.FoodLibEntry
import com.example.diapilot.data.SqliteCollectorStore.RecipeItem
import com.example.diapilot.data.Stores
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Food library: every dish the app knows — eaten ones (labels + food memory)
 * and reference-only entries added ahead of time.
 *
 * Two-layer model: the TITLE is the dish's identity (glycemic stats attach to
 * the whole meal — the body digests it together), the RECIPE is a structured
 * component list for carb arithmetic (carbs are additive). Components are
 * per-unit products shared across dishes — "bread" learned in one meal
 * prices it in another meal too. Facts only, never dosing advice.
 */

private data class LibRow(
    val name: String,
    val lib: FoodLibEntry?,    // user-curated row (food_library), null if derived-only
    val derivedGrams: Double?, // learned from notes/vision
    val memory: FoodMemory?,   // null = never eaten (reference-only entry)
) {
    val grams: Double? get() = lib?.grams ?: derivedGrams
}

@Composable
internal fun FoodLibraryDialog(
    foodMemory: Map<String, FoodMemory>,
    carbsByFood: Map<String, Double>,
    recentFoodTexts: List<String> = emptyList(),
    recentFoodCarbs: Map<String, Double> = emptyMap(),
    initialDish: String? = null,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { Stores.get(context) as? SqliteCollectorStore }
    var libRows by remember { mutableStateOf<List<FoodLibEntry>>(emptyList()) }
    var reloadTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadTick) {
        libRows = withContext(Dispatchers.IO) { store?.foodLibrary().orEmpty() }
    }
    fun reload() { reloadTick++; onChanged() }

    // Substrate for a future component-level model: how much of the eaten
    // history already carries a component split, and the vocabulary building up.
    var substrate by remember { mutableStateOf<com.diapilot.core.analysis.ComponentSubstrate?>(null) }
    LaunchedEffect(reloadTick) {
        substrate = withContext(Dispatchers.IO) {
            val recipes = store?.foodLibrary().orEmpty()
                .filter { it.components.isNotEmpty() }
                .associate { e ->
                    normalizeFoodName(e.name) to e.components.map {
                        com.diapilot.core.analysis.ComponentEstimate(
                            it.name, it.grams ?: 0.0, it.count.coerceAtLeast(1),
                        )
                    }
                }
            val notes = store?.annotations(com.example.diapilot.data.FoodEraSettings.current().startMs, System.currentTimeMillis()).orEmpty()
            com.diapilot.core.analysis.componentSubstrate(notes, recipes)
        }
    }

    // Concept pools: every logged meal grouped by concept-id (not by name).
    // "smoothie"/"smoothie with orange juice"/"fresh juice" collapse into one
    // pool — visible proof the model pools without any manual renaming.
    var pools by remember { mutableStateOf<List<com.diapilot.core.analysis.ConceptPool>>(emptyList()) }
    LaunchedEffect(reloadTick) {
        pools = withContext(Dispatchers.IO) {
            val notes = store?.annotations(com.example.diapilot.data.FoodEraSettings.current().startMs, System.currentTimeMillis()).orEmpty()
            com.diapilot.core.analysis.conceptPoolsFromAnnotations(notes)
        }
    }

    // PER-CONCEPT PROFILES WERE REMOVED FROM THIS SCREEN. The breakdown rested
    // on pools whose ttp is quantized, whose tail is empty, and whose onset can
    // spread many-fold on a single dish — showing that as "what the model
    // learned" is misleading.
    var deconvStats by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reloadTick) {
        val m = withContext(Dispatchers.IO) {
            com.example.diapilot.data.TwinCache.getForForecast(store!!, context)
        } ?: return@LaunchedEffect
        // Validation stats: how much of the history the deconvolution recovered,
        // and how clean it is (censored share, mmol/g and ttp spread).
        deconvStats = withContext(Dispatchers.IO) {
            val corpus = m.fingerprintCorpus
            if (corpus.isEmpty()) return@withContext null
            val totalNotes = store?.annotations(com.example.diapilot.data.FoodEraSettings.current().startMs, System.currentTimeMillis()).orEmpty()
                .count { it.kind == "food" && (it.estCarbs ?: 0.0) > 0 }
            val censored = corpus.count { !it.peakObserved }
            fun median(xs: List<Double>) = xs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
            val ttpMed = median(corpus.filter { it.peakObserved }.map { it.ttpMin })
            val perG = median(corpus.filter { it.carbGrams > 0 }.map { it.peakRise / it.carbGrams })
            val onsets = corpus.mapNotNull { it.onsetLagMin }
            val onsetMed = if (onsets.isEmpty()) null else median(onsets)
            "%d наблюдений из %d заметок · цензур. %d%%%s · ttp медиана %.0f мин · %.2f ммоль/г".format(
                corpus.size, totalNotes,
                if (corpus.isNotEmpty()) 100 * censored / corpus.size else 0,
                onsetMed?.let { " · начало медиана %.0f мин".format(it) } ?: "",
                ttpMed, perG,
            )
        }
    }

    // Union: library entries + every dish ever eaten (food memory keys).
    val rows = remember(libRows, foodMemory, carbsByFood) {
        // Eaten-history lookup by NORMALIZED name: a library "quarter of a berry
        // bun" must find the episode labeled "quarter of a berry bun ×2" (the
        // ×N and the case differ, the dish doesn't). Exact key wins; else a
        // normalized hit.
        val memByNorm = foodMemory.entries.groupBy { normalizeFoodName(it.key) }
        fun memFor(name: String): com.diapilot.core.analysis.FoodMemory? =
            foodMemory[name] ?: memByNorm[normalizeFoodName(name)]
                ?.maxByOrNull { it.value.episodes.size }?.value
        val byName = LinkedHashMap<String, LibRow>()
        libRows.forEach { e ->
            byName[e.name] = LibRow(
                name = e.name, lib = e,
                derivedGrams = carbsByFood[normalizeFoodName(e.name)],
                memory = memFor(e.name),
            )
        }
        foodMemory.keys.sorted().forEach { n ->
            if (n !in byName) {
                byName[n] = LibRow(
                    name = n, lib = null,
                    derivedGrams = carbsByFood[normalizeFoodName(n)],
                    memory = memFor(n),
                )
            }
        }
        // Food NOTES too: a meal logged but never detector-labeled ("Ikea,
        // meatballs…") is still a meal the user expects to find here.
        recentFoodTexts.forEach { n ->
            if (byName.keys.none { it.equals(n, ignoreCase = true) }) {
                byName[n] = LibRow(
                    name = n, lib = null,
                    derivedGrams = recentFoodCarbs[n.trim().lowercase()]
                        ?: carbsByFood[normalizeFoodName(n)],
                    memory = memFor(n),
                )
            }
        }
        byName.values.sortedBy { it.name.lowercase() }
    }
    val universe = remember(rows, recentFoodTexts) {
        (rows.map { it.name } + recentFoodTexts).distinctBy { it.lowercase() }
    }
    // Per-unit grams lookup shared by list and editor: curated library value
    // first, then the derived dictionary.
    val libGramsByName = remember(libRows) {
        libRows.mapNotNull { e -> e.grams?.let { e.name to it } }.toMap()
    }
    fun gramsOf(n: String): Double? =
        libGramsByName[n]
            ?: libGramsByName.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value
            ?: com.diapilot.core.analysis.lookupFoodGrams(carbsByFood, n)

    // Which dishes contain a given component: curated recipe first, else the
    // composite-title parse. This is the component-level memory bridge.
    fun componentsOfRow(r: LibRow): Set<String> =
        r.lib?.components?.takeIf { it.isNotEmpty() }
            ?.map { normalizeFoodName(it.name) }?.filter { it.isNotEmpty() }?.toSet()
            ?: parseMealParts(r.name).takeIf { it.size > 1 }?.keys
                ?.map { normalizeFoodName(it) }?.filter { it.isNotEmpty() }?.toSet()
                .orEmpty()
    fun usedIn(component: String): List<Pair<String, FoodMemory?>> {
        val key = normalizeFoodName(component).takeIf { it.isNotEmpty() } ?: return emptyList()
        return rows.filter { key in componentsOfRow(it) }
            .map { it.name to it.memory }
            .sortedByDescending { it.second?.episodes?.size ?: 0 }
    }

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<LibRow?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    // Concept reassignment: a raw name the user tapped in the pool card.
    var assigning by remember { mutableStateOf<String?>(null) }
    // Deep-link from history: open straight into a dish's editor (its LLM
    // breakdown + recipe). A dish not yet in rows gets a synthetic entry.
    var initialConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(initialDish, rows) {
        if (!initialConsumed && initialDish != null) {
            editing = rows.firstOrNull { it.name.equals(initialDish, ignoreCase = true) }
                ?: LibRow(initialDish, lib = null, derivedGrams = null, memory = foodMemory[initialDish])
            initialConsumed = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📚 Библиотека еды",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { addingNew = true }) { Text("＋ Продукт") }
                    IconButton(onClick = onDismiss) { Text("✕") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val shown = rows.filter {
                    query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) ||
                        it.lib?.components.orEmpty().any { c ->
                            c.name.contains(query.trim(), ignoreCase = true)
                        }
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f).imePadding(),
                ) {
                    substrate?.takeIf { it.totalMeals > 0 }?.let { s ->
                        item(key = "__substrate__") {
                            var open by remember { mutableStateOf(false) }
                            OutlinedCard(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val pct = 100 * s.withComposition / s.totalMeals.coerceAtLeast(1)
                                    Text(
                                        "🧩 Субстрат для компонентной модели: " +
                                            "${s.withComposition} из ${s.totalMeals} приёмов с составом ($pct%)",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "Приёмы с разбором на компоненты — обучающая база под будущую " +
                                            "модель по компонентам. Растёт по мере логирования составных " +
                                            "блюд. Тап — словарь компонентов.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (open) {
                                        Divider()
                                        s.vocabulary.take(30).forEach { cs ->
                                            Text(
                                                "%s · %d приёмов · %.0f г всего".format(cs.name, cs.meals, cs.totalGrams),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        if (s.vocabulary.size > 30) {
                                            Text(
                                                "…и ещё ${s.vocabulary.size - 30}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    pools.takeIf { it.isNotEmpty() }?.let { ps ->
                        item(key = "__concepts__") {
                            var open by remember { mutableStateOf(false) }
                            val mapped = ps.filter { it.mapped }
                            val gap = ps.firstOrNull { !it.mapped }
                            val poolsWithVariants = mapped.count { it.aliases.size > 1 }
                            OutlinedCard(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "🍱 Концепты: ${mapped.size} на ${mapped.sumOf { it.occurrences }} записей" +
                                            (gap?.let { " · ? не распознано: ${it.occurrences}" } ?: ""),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "История сведена по concept-id, а не по названию. " +
                                            "$poolsWithVariants концепт(ов) собрали разные имена в один пул — " +
                                            "переименовывать вручную не нужно. Тап — раскрыть.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (open) {
                                        Divider()
                                        Text(
                                            "Тап по имени → переназначить концепт вручную.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        mapped.forEach { p ->
                                            val speed = when (p.carbSpeed) {
                                                com.diapilot.core.analysis.CarbSpeed.FAST -> "быстро"
                                                com.diapilot.core.analysis.CarbSpeed.MED -> "средне"
                                                com.diapilot.core.analysis.CarbSpeed.SLOW -> "медленно"
                                                com.diapilot.core.analysis.CarbSpeed.NONE -> "—"
                                            }
                                            Text(
                                                "%s · ×%d · %.0f г · %s".format(
                                                    p.label, p.occurrences, p.totalCarbs, speed,
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            // The names that pooled here — each tappable to reassign.
                                            p.aliases.take(8).forEach { a ->
                                                Text(
                                                    "  ← $a",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { assigning = a }
                                                        .padding(vertical = 2.dp),
                                                )
                                            }
                                            if (p.aliases.size > 8) {
                                                Text(
                                                    "  …и ещё ${p.aliases.size - 8}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        gap?.takeIf { it.aliases.isNotEmpty() }?.let { g ->
                                            Divider()
                                            Text(
                                                "? нет концепта (${g.occurrences}) — тап, чтобы назначить:",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            g.aliases.take(20).forEach { a ->
                                                Text(
                                                    "  • $a",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { assigning = a }
                                                        .padding(vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(shown, key = { it.name }) { row ->
                        OutlinedCard(
                            onClick = { editing = row },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                row.lib?.mediaRef?.let {
                                    PhotoThumbPublic(it, row.name, size = 40)
                                    Spacer(Modifier.padding(4.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            row.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        val comps = row.lib?.components.orEmpty()
                                        val g = row.grams ?: comps.takeIf { it.isNotEmpty() }?.let { cs ->
                                            val known = cs.map { c -> (c.grams ?: gramsOf(c.name))?.times(c.count) }
                                            if (known.all { it != null }) known.filterNotNull().sum() else null
                                        }
                                        g?.let {
                                            Text(
                                                "${it.toInt()} г" + if (row.lib?.grams != null) " ✎" else "",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                    row.lib?.components?.takeIf { it.isNotEmpty() }?.let { comps ->
                                        Text(
                                            "🧩 " + comps.joinToString(" + ") { c ->
                                                if (c.count > 1) "${c.name} ×${c.count}" else c.name
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    val mem = row.memory
                                    Text(
                                        buildString {
                                            if (mem != null) {
                                                append("×${mem.episodes.size}")
                                                append(" · подъём ~%.1f".format(mem.avgRise))
                                                if (mem.underDosedCount > 0) append(" · ⚠ докол ×${mem.underDosedCount}")
                                            } else {
                                                append("ещё не ели")
                                            }
                                            row.lib?.comment?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                if (query.isBlank()) "Пока пусто — добавьте продукт или поешьте 🙂"
                                else "Ничего не найдено. «＋ Продукт» добавит «${query.trim()}».",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (addingNew) {
        FoodEntryEditor(
            row = LibRow(query.trim(), lib = null, derivedGrams = null, memory = null),
            isNew = true,
            store = store,
            universe = universe,
            gramsOf = ::gramsOf,
            usedIn = emptyList(),
            otherDishes = emptyList(),
            onDone = { addingNew = false; reload() },
            onDismiss = { addingNew = false },
        )
    }
    editing?.let { row ->
        FoodEntryEditor(
            row = row,
            isNew = false,
            store = store,
            universe = universe,
            gramsOf = ::gramsOf,
            usedIn = usedIn(row.name),
            // Merge targets: every OTHER dish, so a true duplicate can be
            // folded into its twin.
            otherDishes = rows.map { it.name }.filter { !it.equals(row.name, ignoreCase = true) },
            onDone = { editing = null; reload() },
            onDismiss = { editing = null },
        )
    }
    assigning?.let { rawName ->
        ConceptAssignDialog(
            name = rawName,
            current = com.diapilot.core.analysis.conceptFor(rawName)?.id,
            onAssign = { conceptId ->
                assigning = null
                Thread {
                    store?.upsertConceptAlias(rawName, conceptId)
                    store?.let { com.diapilot.core.analysis.setUserConceptAliases(it.conceptAliases()) }
                    // Donor corpus (in the twin model) maps names→concepts too —
                    // rebuild it so the reassignment reaches the shadow forecast.
                    com.example.diapilot.data.TwinCache.invalidate()
                    android.os.Handler(android.os.Looper.getMainLooper()).post { reload() }
                }.start()
            },
            onReset = {
                assigning = null
                Thread {
                    store?.deleteConceptAlias(rawName)
                    store?.let { com.diapilot.core.analysis.setUserConceptAliases(it.conceptAliases()) }
                    com.example.diapilot.data.TwinCache.invalidate()
                    android.os.Handler(android.os.Looper.getMainLooper()).post { reload() }
                }.start()
            },
            onDismiss = { assigning = null },
        )
    }
}

// Assign a raw food name to a concept-id by hand — the Level-1 concept editor.
// The concept ARCHETYPES stay in code; this only records the user's name→concept
// override (persisted, layered over the built-in table via setUserConceptAliases).
/** Assign a component name to a concept. Reused by the note editor: the loss
 *  warning ("⚠ 55 g not recognized") and its fix must live in the same place —
 *  the alias table sat empty for a long stretch while this dialog hid in the library. */
@Composable
fun ConceptAssignDialog(
    name: String,
    current: String?,
    onAssign: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    fun speed(c: com.diapilot.core.analysis.FoodConcept) = when (c.carbSpeed) {
        com.diapilot.core.analysis.CarbSpeed.FAST -> "быстро"
        com.diapilot.core.analysis.CarbSpeed.MED -> "средне"
        com.diapilot.core.analysis.CarbSpeed.SLOW -> "медленно"
        com.diapilot.core.analysis.CarbSpeed.NONE -> "без углев"
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Концепт для «$name»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Сейчас: " + (current ?: "не распознано") +
                        ". Выбери архетип — модель учит по concept-id, не по имени.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it },
                    label = { Text("Фильтр концептов") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val shown = com.diapilot.core.analysis.FOOD_CONCEPTS.filter {
                    q.isBlank() || it.id.contains(q.trim(), ignoreCase = true) ||
                        it.aliasesRu.any { a -> a.contains(q.trim(), ignoreCase = true) }
                }
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    shown.forEach { c ->
                        val sel = c.id == current
                        Text(
                            (if (sel) "● " else "○ ") + "${c.id} · ${speed(c)}" +
                                " · " + c.aliasesRu.take(3).joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (sel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAssign(c.id) }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReset) { Text("Сбросить (по умолчанию)") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Dish editor, constructor-style: the TITLE stays whatever the user calls the
 * dish; the recipe below is structured components (name × count × per-unit
 * grams). Carbs auto-sum from the recipe and stay LIVE (stored grams = null)
 * unless the user overrides the number by hand. 📷 + Vision / 🤖 text parse
 * fill both the estimate and — for a composite — the recipe itself.
 */
@Composable
private fun FoodEntryEditor(
    row: LibRow,
    isNew: Boolean,
    store: SqliteCollectorStore?,
    universe: List<String>,
    gramsOf: (String) -> Double?,
    usedIn: List<Pair<String, FoodMemory?>>,
    otherDishes: List<String>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(row.name) }
    var comment by remember { mutableStateOf(row.lib?.comment ?: "") }
    var mediaRef by remember { mutableStateOf(row.lib?.mediaRef) }
    var analysis by remember { mutableStateOf(row.lib?.analysis) }
    var analyzing by remember { mutableStateOf(false) }
    var showAnalysis by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var adoptedCount by remember { mutableIntStateOf(0) }
    // Products the LLM parse OFFERS but has not written — see adoptComponents.
    var pendingProducts by remember {
        mutableStateOf<List<com.diapilot.core.analysis.ComponentEstimate>>(emptyList())
    }
    var suggestedName by remember { mutableStateOf<String?>(null) }
    var newComp by remember { mutableStateOf("") }
    // Clean-title suggestion: the name-marker line (text LLM parse) or the
    // photo parse's first line (Vision's first line IS the title).
    fun adoptNameSuggestion(result: String, viaPhoto: Boolean) {
        val s = com.diapilot.core.analysis.parseNameSuggestion(result)
            ?: result.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.takeIf { viaPhoto && it.length <= 40 && !it.contains(':') }
        suggestedName = s?.takeIf { !it.equals(name.trim(), ignoreCase = true) }
    }
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }

    // Recipe: stored structure first; else bootstrap from a parseable
    // composite title ("cabbage soup + bread ×2") so legacy title-only rows
    // become a structured recipe.
    var recipe by remember {
        mutableStateOf(
            row.lib?.components?.takeIf { it.isNotEmpty() }
                ?: parseMealParts(row.name)
                    .takeIf { it.size > 1 }
                    ?.map { (n, c) -> RecipeItem(n, c, gramsOf(n)) }
                    .orEmpty(),
        )
    }
    fun recipeSum(items: List<RecipeItem>): Double? {
        if (items.isEmpty()) return null
        val known = items.map { c -> (c.grams ?: gramsOf(c.name))?.times(c.count) }
        return if (known.all { it != null }) known.filterNotNull().sum() else null
    }
    var gramsText by remember {
        mutableStateOf(
            row.lib?.grams?.let { fmtG(it) }
                ?: recipeSum(
                    row.lib?.components?.takeIf { it.isNotEmpty() }
                        ?: emptyList(),
                )?.let { fmtG(it) }
                ?: row.derivedGrams?.let { fmtG(it) } ?: "",
        )
    }
    // While true, the carbs number follows the recipe; a manual edit of the
    // field breaks the link (and stores the override on save).
    var gramsLive by remember { mutableStateOf(row.lib?.grams == null && recipe.isNotEmpty()) }
    // Bootstrap-from-title recipes are a guess; until the user touches the
    // constructor, an LLM parse may replace them wholesale.
    var recipeEdited by remember { mutableStateOf(row.lib?.components?.isNotEmpty() == true) }
    fun applyRecipe(items: List<RecipeItem>, userEdit: Boolean) {
        val oldCount = recipe.sumOf { it.count }.coerceAtLeast(1)
        recipe = items
        if (userEdit) recipeEdited = true
        val sum = recipeSum(items)
        when {
            sum != null && (gramsLive || gramsText.isBlank()) -> {
                gramsText = fmtG(sum); gramsLive = true
            }
            sum == null && gramsLive -> {
                // Per-unit grams unknown — scale the live total by the
                // portion-count ratio, same rule as the composer.
                val newCount = items.sumOf { it.count }
                val cur = gramsText.replace(',', '.').toDoubleOrNull()
                if (cur != null && newCount > 0 && newCount != oldCount) {
                    gramsText = fmtG(cur / oldCount * newCount)
                }
            }
        }
    }
    fun setRecipe(items: List<RecipeItem>) = applyRecipe(items, userEdit = true)

    // The LLM parse's composition lines are per-unit products — write each into
    // the library as its own entry (never clobbering a curated one) and, when
    // the recipe is still empty, adopt them AS the recipe.
    fun adoptComponents(result: String) {
        val comps = com.diapilot.core.analysis.parseComponents(result)
        if (comps.isEmpty()) return
        // Adopt as the recipe while the constructor is untouched — the LLM
        // parse's per-unit split beats a bootstrap guess from the title.
        // A single component that just restates the dish is skipped.
        val meaningful = comps.size >= 2 ||
            normalizeFoodName(comps[0].name) != normalizeFoodName(name)
        if (meaningful && (recipe.isEmpty() || !recipeEdited)) {
            applyRecipe(comps.map { RecipeItem(it.name, it.count, it.unitGrams) }, userEdit = false)
        }
        if (comps.size < 2) return
        // NO AUTO-TEACH: an LLM parse is a SUGGESTION. This only SCANS for products the
        // library does not have yet and offers them; the write happens solely on an
        // explicit tap. It used to upsert straight to the store on this background
        // thread, persisting an unconfirmed LLM guess that then quietly biased every
        // later grams prefill through carbsByFood.
        Thread {
            val s = store ?: return@Thread
            val existing = s.foodLibrary().map { it.name.lowercase() }.toSet()
            val fresh = comps.filter { it.name.lowercase() !in existing }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                pendingProducts = fresh
                adoptedCount = 0
            }
        }.start()
    }

    /** The ONLY path that writes LLM parse components into the library — explicit accept. */
    fun confirmAdoptProducts() {
        val fresh = pendingProducts
        if (fresh.isEmpty()) return
        pendingProducts = emptyList()
        Thread {
            val s = store ?: return@Thread
            fresh.forEach { c -> s.upsertFoodLibrary(c.name, c.unitGrams, null, null, null) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                adoptedCount = fresh.size
            }
        }.start()
    }

    fun analyzePhoto(ref: String) {
        val key = com.example.diapilot.data.AskClaude.apiKey(context) ?: return
        analyzing = true
        scope.launch(Dispatchers.IO) {
            val result = try {
                val bytes = loadScaledJpeg(File(photosDir(context), ref))
                    ?: error("Фото не найдено")
                com.example.diapilot.data.AskClaude.describeFood(
                    key, bytes,
                    caption = name.ifBlank { null },
                ).text
            } catch (e: Exception) {
                "Ошибка: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                analyzing = false
                analysis = result
                if (!result.startsWith("Ошибка")) {
                    adoptComponents(result)
                    if (recipe.isEmpty()) {
                        com.diapilot.core.analysis.parseCarbsEstimate(result)?.let { gramsText = fmtG(it) }
                    }
                    adoptNameSuggestion(result, viaPhoto = true)
                    showAnalysis = true
                }
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        pendingPhoto = null
        if (ok && file != null && file.length() > 0) {
            mediaRef = file.name
            analyzePhoto(file.name)
        } else {
            file?.delete()
        }
    }
    fun capturePhoto() {
        val file = File(photosDir(context), "IMG_${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        takePicture.launch(
            FileProvider.getUriForFile(context, "com.example.diapilot.fileprovider", file),
        )
    }
    val speak = rememberSpeechInput { spoken ->
        name = if (name.isBlank()) spoken else "$name $spoken"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isNew) "Новый продукт" else "«${row.name}»",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (!isNew && otherDishes.isNotEmpty()) {
                        IconButton(onClick = { merging = true }) { Text("🔀") }
                    }
                    if (row.lib != null) {
                        IconButton(onClick = { confirmDelete = true }) { Text("🗑") }
                    }
                    IconButton(onClick = onDismiss) { Text("✕") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    mediaRef?.let { PhotoThumbPublic(it, name, size = 56) }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        label = { Text("Название блюда") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (mediaRef != null) 8.dp else 0.dp),
                    )
                    IconButton(onClick = speak) { Text("🎤") }
                    IconButton(onClick = ::capturePhoto) { Text("📷") }
                }
                // --- Recipe constructor -------------------------------------
                Text(
                    if (recipe.isEmpty()) "Состав (для составного блюда):"
                    else "Состав — граммы за 1 шт/порцию:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                recipe.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                val c = item.count - 1
                                setRecipe(
                                    if (c <= 0) recipe.filterIndexed { j, _ -> j != i }
                                    else recipe.mapIndexed { j, r -> if (j == i) r.copy(count = c) else r },
                                )
                            },
                            modifier = Modifier.width(36.dp),
                        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                        Text("×${item.count}", style = MaterialTheme.typography.bodyMedium)
                        IconButton(
                            onClick = {
                                setRecipe(
                                    recipe.mapIndexed { j, r ->
                                        if (j == i) r.copy(count = (r.count + 1).coerceAtMost(20)) else r
                                    },
                                )
                            },
                            modifier = Modifier.width(36.dp),
                        ) { Text("＋", style = MaterialTheme.typography.titleMedium) }
                        OutlinedTextField(
                            value = item.grams?.let { fmtG(it) }
                                ?: gramsOf(item.name)?.let { fmtG(it) } ?: "",
                            onValueChange = { txt ->
                                val g = txt.replace(',', '.').toDoubleOrNull()
                                setRecipe(
                                    recipe.mapIndexed { j, r -> if (j == i) r.copy(grams = g) else r },
                                )
                            },
                            label = { Text("г") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(84.dp).padding(start = 4.dp),
                        )
                        IconButton(
                            onClick = { setRecipe(recipe.filterIndexed { j, _ -> j != i }) },
                            modifier = Modifier.width(36.dp),
                        ) { Text("✕", color = MaterialTheme.colorScheme.error) }
                    }
                }
                // Add a component: free text or a known product from history.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newComp,
                        onValueChange = { newComp = it },
                        label = { Text("Добавить компонент") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newComp.isNotBlank(),
                        onClick = {
                            val n = newComp.trim()
                            setRecipe(recipe + RecipeItem(n, 1, gramsOf(n)))
                            newComp = ""
                        },
                    ) { Text("＋") }
                }
                run {
                    val q = newComp.trim()
                    val matches = if (q.length >= 2) {
                        universe.filter {
                            it.contains(q, ignoreCase = true) &&
                                recipe.none { r -> r.name.equals(it, ignoreCase = true) } &&
                                !it.equals(row.name, ignoreCase = true)
                        }.take(4)
                    } else emptyList()
                    matches.forEach { n ->
                        val g = gramsOf(n)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    setRecipe(recipe + RecipeItem(n, 1, g))
                                    newComp = ""
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "＋ $n" + (g?.let { " · ~%.0f г".format(it) } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                // --- Totals -------------------------------------------------
                OutlinedTextField(
                    value = gramsText,
                    onValueChange = {
                        gramsText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }
                        gramsLive = false
                    },
                    label = {
                        Text(
                            when {
                                gramsLive && recipe.isNotEmpty() -> "Углеводы, г (сумма состава — живая)"
                                row.lib?.grams == null && row.derivedGrams != null ->
                                    "Углеводы, г (из истории: ${fmtG(row.derivedGrams)})"
                                else -> "Углеводы, г"
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!gramsLive && recipe.isNotEmpty()) {
                    recipeSum(recipe)?.let { s ->
                        TextButton(
                            onClick = { gramsText = fmtG(s); gramsLive = true },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("↺ вернуть сумму состава (${fmtG(s)} г)") }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Комментарий (порция, рецепт…)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // LLM: Vision when a photo is attached, text-only otherwise.
                if (com.example.diapilot.data.AskClaude.apiKey(context) != null) {
                    TextButton(
                        enabled = !analyzing && (name.isNotBlank() || mediaRef != null),
                        onClick = {
                            mediaRef?.let { analyzePhoto(it); return@TextButton }
                            analyzing = true
                            scope.launch(Dispatchers.IO) {
                                val result = try {
                                    com.example.diapilot.data.AskClaude.estimateCarbs(
                                        com.example.diapilot.data.AskClaude.apiKey(context)!!,
                                        listOf(name, comment).filter { it.isNotBlank() }
                                            .joinToString(" — "),
                                    )
                                } catch (e: Exception) {
                                    "Ошибка: ${e.message}"
                                }
                                withContext(Dispatchers.Main) {
                                    analyzing = false
                                    analysis = result
                                    if (!result.startsWith("Ошибка")) {
                                        adoptComponents(result)
                                        if (recipe.isEmpty()) {
                                            com.diapilot.core.analysis.parseCarbsEstimate(result)
                                                ?.let { gramsText = fmtG(it) }
                                        }
                                        adoptNameSuggestion(result, viaPhoto = false)
                                        showAnalysis = true
                                    }
                                }
                            }
                        },
                    ) {
                        Text(
                            when {
                                analyzing -> "Разбираю…"
                                mediaRef != null -> if (analysis == null) "🤖 Разобрать фото" else "🔄 Пересчитать по фото"
                                analysis == null -> "🤖 Углеводы по описанию"
                                else -> "🔄 Пересчитать по описанию"
                            },
                        )
                    }
                }
                suggestedName?.let { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { name = s; suggestedName = null }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            "✨ Назвать «$s» — тап, чтобы применить",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (pendingProducts.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { confirmAdoptProducts() }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            "＋ Записать ${pendingProducts.size} продукт(а) в библиотеку — тап, чтобы принять",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (adoptedCount > 0) {
                    Text(
                        "＋$adoptedCount компонент(а) записано в библиотеку отдельными продуктами",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                analysis?.let { a ->
                    TextButton(
                        onClick = { showAnalysis = !showAnalysis },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text(if (showAnalysis) "▾ разбор" else "▸ разбор") }
                    if (showAnalysis) Text(a, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                // Component memory: the dish is a PRODUCT inside other meals
                // — their measured episodes are its indirect history ("bread"
                // met in a breakfast prices it in an evening sandwich too).
                if (usedIn.isNotEmpty()) {
                    Divider()
                    val eps = usedIn.mapNotNull { it.second }.flatMap { it.episodes }
                    Text(
                        "Встречается в блюдах (${usedIn.size})" +
                            if (eps.isNotEmpty()) {
                                " · суммарно ×${eps.size} · подъём ~%.1f"
                                    .format(eps.map { it.rise }.average())
                            } else "",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        usedIn.take(6).forEach { (dish, mem) ->
                            Text(
                                "• $dish" + (mem?.let {
                                    " · ×${it.episodes.size} · подъём ~%.1f".format(it.avgRise)
                                } ?: " · ещё не ели"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (usedIn.size > 6) {
                            Text(
                                "…и ещё ${usedIn.size - 6}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Episodes: how the dish actually behaved. Facts, no advice.
                row.memory?.let { mem ->
                    Divider()
                    // SHAPE × SIZE diagnostic (food-v6 groundwork): the dish's
                    // own mmol/gram vs the flat average, and what it predicts
                    // for a chosen portion. NOT yet wired into the forecast —
                    // shown here to validate the portion model against reality.
                    mem.perGramRise?.let { perG ->
                        val portion = row.grams ?: mem.typicalGrams
                        Text(
                            buildString {
                                append("Форма × порция: ~%.2f ммоль/г".format(perG))
                                append(" · чистых ${mem.perGramN}")
                                portion?.let {
                                    append(" · при ${it.toInt()}г → +%.1f".format(perG * it))
                                }
                                append(" (плоское среднее +%.1f)".format(mem.avgRise))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text("Эпизоды (${mem.episodes.size})", style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        mem.episodes.take(8).forEach { e ->
                            Text(
                                buildString {
                                    append(fmt.format(Date(e.onsetMs)))
                                    append(" · +%.1f за %d мин".format(e.rise, e.timeToPeakMin.toInt()))
                                    e.grams?.let { append(" · %dг".format(it.toInt())) }
                                    e.effectiveDose?.let { append(" · %.1f ед".format(it)) }
                                    if (e.underDosed) append(" ⚠")
                                    e.lagMin?.let { append(" · лаг %d мин".format(it.toInt())) }
                                    // Honesty: the rise was measured on a
                                    // background force — not the dish's pure own.
                                    e.background?.let { append(" · фон: $it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (mem.episodes.size > 8) {
                            Text(
                                "…и ещё ${mem.episodes.size - 8}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    TextButton(onClick = {
                        val nn = name.trim()
                        if (nn.isEmpty()) { error = "Название пустое"; return@TextButton }
                        val entered = gramsText.replace(',', '.').toDoubleOrNull()
                        // Live sum is NOT stored as a number: grams=null keeps
                        // the dish following its components forever.
                        val grams = if (gramsLive && recipe.isNotEmpty()) null else entered
                        val cmt = comment.trim().ifBlank { null }
                        val ref = mediaRef
                        val ana = analysis
                        val rcp = recipe
                        Thread {
                            val s = store ?: return@Thread
                            // Rename refusal = a dish with that name already
                            // has its own history; merging is deliberate.
                            val renamedOk = isNew || nn == row.name || s.renameDish(row.name, nn)
                            if (renamedOk) {
                                if (!isNew && nn != row.name) s.deleteFoodLibrary(row.name)
                                s.upsertFoodLibrary(nn, grams, cmt, ref, ana, rcp)
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (renamedOk) onDone()
                                else error = "«$nn» уже есть — переименование слило бы две истории"
                            }
                        }.start()
                    }) { Text("Сохранить") }
                }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Убрать из библиотеки?") },
            text = { Text("История приёмов не пострадает — удалится только справочная запись.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    Thread {
                        store?.deleteFoodLibrary(row.name)
                        android.os.Handler(android.os.Looper.getMainLooper()).post(onDone)
                    }.start()
                }) { Text("Убрать", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }

    if (merging) {
        MergeDishDialog(
            from = row.name,
            others = otherDishes,
            onMerge = { into ->
                merging = false
                Thread {
                    store?.mergeDish(row.name, into)
                    android.os.Handler(android.os.Looper.getMainLooper()).post(onDone)
                }.start()
            },
            onDismiss = { merging = false },
        )
    }
}

/**
 * Deliberate, destructive merge of a TRUE duplicate: pick the surviving dish,
 * confirm that the two episode histories become one. NOT for "2 beers" vs
 * "beer" (different portions — use a recipe beer ×2 instead).
 */
@Composable
private fun MergeDishDialog(
    from: String,
    others: List<String>,
    onMerge: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Объединить «$from» →") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Для НАСТОЯЩИХ дублей — одно блюдо под двумя именами. " +
                        "Истории эпизодов сольются в выбранное блюдо, «$from» исчезнет. " +
                        "Разные порции (2 пива / пиво) объединять НЕ нужно — им лучше рецепт.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it; target = null },
                    label = { Text("В какое блюдо") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val matches = others.filter {
                    q.isBlank() || it.contains(q.trim(), ignoreCase = true)
                }.take(6)
                matches.forEach { n ->
                    val sel = n == target
                    Text(
                        (if (sel) "● " else "○ ") + n,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { target = n; q = n }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = target != null,
                onClick = { target?.let(onMerge) },
            ) { Text("Объединить", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun fmtG(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
