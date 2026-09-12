/**
 * Carbohydrate estimates — stage "carbs" step 1.
 *
 * The Vision food analysis ends with a machine-readable line
 * `CARBS_LINE_PREFIX: 40–60 g` (or `~50 g`). This module extracts a single point
 * estimate from that text so it can be stored as a number and later refined
 * by repeat calibration against the user's own insulin kernel.
 */
package com.diapilot.core.analysis

/*
 * LLM PROTOCOL MARKERS. New analyses carry the English markers below, whatever
 * the reply language; the prompts ask for them and the serializer writes them.
 * Stored analyses and restored backups contain the older Russian markers
 * ([LEGACY_LINE_PREFIXES]), so every parser reads BOTH forms, forever — test a
 * line with [isMarkerLine], never with a bare `startsWith`.
 */

/** Prefix of the machine-readable carbs line the Vision prompt requires. */
const val CARBS_LINE_PREFIX = "CARBS"
const val PROTEIN_LINE_PREFIX = "PROTEIN"
const val FAT_LINE_PREFIX = "FAT"
const val KCAL_LINE_PREFIX = "KCAL"

/** Per-component confidence/speed line (see [serializeFoodAnalysis]). */
const val META_LINE_PREFIX = "META"

/** Composition-line tags after "=": carbs of one unit, natural portion of one unit. */
const val COMPONENT_CARBS_TAG = "carbs"
const val COMPONENT_PORTION_TAG = "portion"

/** The older Russian composition tags, read forever. */
const val LEGACY_COMPONENT_CARBS_TAG = "угл"
const val LEGACY_COMPONENT_PORTION_TAG = "порц"

/** Marker -> the Russian marker older builds asked for and stored. */
val LEGACY_LINE_PREFIXES: Map<String, String> = mapOf(
    "CARBS" to "УГЛЕВОДЫ",
    "PROTEIN" to "БЕЛКИ",
    "FAT" to "ЖИРЫ",
    "KCAL" to "ККАЛ",
    "GI" to "ГИ",
    "NAME" to "НАЗВАНИЕ",
    "COMPOSITION" to "СОСТАВ",
    "ASSUMPTION" to "ДОПУЩЕНИЕ",
    "CLARIFICATION" to "УТОЧНЕНИЕ",
    "META" to "МЕТА",
)

/** Both spellings of [prefix]: the current marker and its legacy Russian form. */
fun markerForms(prefix: String): List<String> = listOfNotNull(prefix, LEGACY_LINE_PREFIXES[prefix])

/**
 * Is [line] a [prefix] machine line?
 *
 * The current English marker must be followed by a colon ("CARBS: 40 g"), so
 * a prose line such as "Carbs mostly come from rice" is never read as data.
 * The legacy Russian marker keeps the exact rule each parser applied to it
 * before: carbs and nutrition lines matched on the bare word, the GI marker on the word
 * followed by a colon or a space, every other marker on the word plus colon.
 */
fun isMarkerLine(line: String, prefix: String): Boolean {
    val t = line.trim()
    if (Regex("""^${Regex.escape(prefix)}\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
    val legacy = LEGACY_LINE_PREFIXES[prefix] ?: return false
    return when (prefix) {
        CARBS_LINE_PREFIX, PROTEIN_LINE_PREFIX, FAT_LINE_PREFIX, KCAL_LINE_PREFIX ->
            t.startsWith(legacy, ignoreCase = true)
        GI_LINE_PREFIX ->
            t.startsWith("$legacy:", ignoreCase = true) || t.startsWith("$legacy ", ignoreCase = true)
        else -> t.startsWith("$legacy:", ignoreCase = true)
    }
}

data class FoodNutrition(
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val kcal: Double? = null,
) {
    operator fun plus(other: FoodNutrition) = FoodNutrition(
        proteinG = listOfNotNull(proteinG, other.proteinG).takeIf { it.isNotEmpty() }?.sum(),
        fatG = listOfNotNull(fatG, other.fatG).takeIf { it.isNotEmpty() }?.sum(),
        kcal = listOfNotNull(kcal, other.kcal).takeIf { it.isNotEmpty() }?.sum(),
    )
}

/** Machine-readable nutrition totals stored inside the existing analysis blob.
 * Old notes simply return null fields, so no database migration is required. */
fun parseFoodNutrition(analysis: String?): FoodNutrition {
    fun value(prefix: String): Double? {
        val raw = analysis?.lineSequence()
            ?.lastOrNull { isMarkerLine(it, prefix) }
            ?.substringAfter(':', "")
            ?: return null
        return Regex("""\d+(?:[.,]\d+)?""").find(raw)?.value
            ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it >= 0.0 }
    }
    val protein = value(PROTEIN_LINE_PREFIX)
    val fat = value(FAT_LINE_PREFIX)
    val explicitKcal = value(KCAL_LINE_PREFIX)
    val computedKcal = if (explicitKcal == null && protein != null && fat != null) {
        val carbs = analysis?.let(::parseCarbsEstimate)
        carbs?.let { 4.0 * protein + 9.0 * fat + 4.0 * it }
    } else null
    return FoodNutrition(protein, fat, explicitKcal ?: computedKcal)
}

fun nutritionLines(n: FoodNutrition): String = buildString {
    n.proteinG?.let {
        appendLine("$PROTEIN_LINE_PREFIX: ${"%.1f".format(java.util.Locale.ROOT, it)} g")
    }
    n.fatG?.let {
        appendLine("$FAT_LINE_PREFIX: ${"%.1f".format(java.util.Locale.ROOT, it)} g")
    }
    n.kcal?.let {
        appendLine("$KCAL_LINE_PREFIX: ${"%.0f".format(java.util.Locale.ROOT, it)}")
    }
}.trimEnd()

/** Replace only the machine-readable nutrition totals. The LLM must never
 * rewrite a historical dish name, its authoritative carbs, composition or
 * free-form explanation during a batch nutrition pass. */
fun withFoodNutrition(analysis: String?, nutrition: FoodNutrition): String {
    val kept = analysis.orEmpty().lineSequence()
        .filterNot { line ->
            isMarkerLine(line, PROTEIN_LINE_PREFIX) ||
                isMarkerLine(line, FAT_LINE_PREFIX) ||
                isMarkerLine(line, KCAL_LINE_PREFIX)
        }
        .joinToString("\n")
        .trim()
    val lines = nutritionLines(nutrition)
    return listOf(kept, lines).filter { it.isNotBlank() }.joinToString("\n")
}

/**
 * Extract a carbs point estimate (grams) from an LLM food analysis.
 *
 * Looks for the last line starting with [CARBS_LINE_PREFIX]; a range
 * ("40–60", "40-60") yields the midpoint, a single value ("~55") is taken
 * as is. Comma decimals accepted. Returns null when no such line exists or
 * it carries no number — the caller keeps the field empty rather than
 * guessing.
 */
fun parseCarbsEstimate(analysis: String): Double? {
    val line = analysis.lineSequence()
        .map { it.trim() }
        .lastOrNull { isMarkerLine(it, CARBS_LINE_PREFIX) }
        ?: return null
    val numbers = Regex("""\d+(?:[.,]\d+)?""")
        .findAll(line)
        .map { it.value.replace(',', '.').toDouble() }
        .toList()
    return when {
        numbers.isEmpty() -> null
        numbers.size == 1 -> numbers[0]
        else -> (numbers[0] + numbers[1]) / 2.0  // range → midpoint
    }?.takeIf { it > 0 && it < 1000 }  // sanity: junk like a year stays out
}

/** Prefix of the machine-readable glycemic-index line in LLM food analyses. */
const val GI_LINE_PREFIX = "GI"

/**
 * Extract the glycemic index from an LLM food analysis — same contract as
 * [parseCarbsEstimate]: last `GI_LINE_PREFIX:` line, range → midpoint, null when absent
 * or senseless.
 */
fun parseGiEstimate(analysis: String): Double? {
    val line = analysis.lineSequence()
        .map { it.trim() }
        .lastOrNull { isMarkerLine(it, GI_LINE_PREFIX) }
        ?: return null
    val numbers = Regex("""\d+(?:[.,]\d+)?""")
        .findAll(line)
        .map { it.value.replace(',', '.').toDouble() }
        .toList()
    return when {
        numbers.isEmpty() -> null
        numbers.size == 1 -> numbers[0]
        else -> (numbers[0] + numbers[1]) / 2.0
    }?.takeIf { it in 5.0..120.0 }
}

/**
 * GI → expected time-to-peak prior for a dish never eaten before: pure
 * glucose (GI 100) peaks in ~35 min, slow carbs (GI 30) closer to 90.
 * Only a PRIOR — labeled repeats override it with the measured profile.
 */
fun giTimeToPeakMin(gi: Double): Double = (115.0 - 0.85 * gi).coerceIn(35.0, 100.0)

/** Prefix of the machine-readable clean-title line in LLM food analyses. */
const val TITLE_LINE_PREFIX = "NAME"

/**
 * Extract the LLM's clean dish title (`TITLE_LINE_PREFIX: cantucci with tea`) — the
 * one-tap rename source for messy free-text entries. Null when absent,
 * blank, or implausibly long.
 */
fun parseNameSuggestion(analysis: String): String? =
    analysis.lineSequence()
        .map { it.trim() }
        .firstOrNull { isMarkerLine(it, TITLE_LINE_PREFIX) }
        ?.substringAfter(':')?.trim()?.trim('«', '»', '"', '.', ' ')
        ?.takeIf { it.isNotEmpty() && it.length <= 40 }

/** Prefix of machine-readable per-component lines in LLM food analyses. */
const val COMPONENT_LINE_PREFIX = "COMPOSITION"

/**
 * Diacritics stripped so "crème" and "creme" reach the same key — and, as a
 * side effect, so does a stressed Cyrillic vowel typed in its plain form,
 * which decomposes the same way. NFD splits a base letter from its combining
 * accent mark (Mn = "Mark, nonspacing"); dropping those marks and
 * re-composing leaves the base letters untouched, so this changes only
 * accented input and nothing else.
 */
private fun stripDiacritics(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{Mn}+"""), "")

/**
 * Composite meals, step 1: knowledge transfer needs NAMES that match.
 * The LLM writes something like "Buckwheat (cooked, medium portion ~150–180 g)", the
 * user types "buckwheat" — without normalization the grams learned in one combo
 * never reach another. Lowercase; parentheticals, qualifiers after
 * comma/tilde and portion suffixes dropped; at most the first three words
 * kept ("cabbage and radish salad" → "cabbage salad").
 *
 * MARKDOWN IS STRIPPED, and that is a fix rather than tidiness. The LLM writes
 * its component bullets in bold — e.g. "- **Salad** (tomatoes, cucumbers…) — ~5–8 g" —
 * and the emphasis characters survived into the KEY, so `salad**` became a pool
 * of its own beside `salad`. Measured on a sample pull: a small but consistent
 * share of salad rows sat in the forked key, and the same class shows up wherever
 * the model decides to emphasise a word. Nothing legitimate is lost — `*` and `_`
 * are not part of any dish name — and the cost of leaving it is silent: a forked
 * key does not error, it just pools less.
 */
fun normalizeFoodName(raw: String): String =
    stripDiacritics(raw).lowercase()
        .substringBefore('(')
        .substringBefore(',')
        .substringBefore('/')                          // "cutlet/pancake" → first alternative
        .substringBefore('~').substringBefore('≈')
        .replace(Regex("""[*_`]"""), "")               // "**salad**" → "salad"
        .replace(Regex("""\s*[×x]\s*\d+\s*$"""), "")   // "bread ×2" → "bread"
        .trim().trim('-', '•', '.', ':', ' ')
        .split(Regex("""\s+"""))
        .take(3)
        .joinToString(" ")

/**
 * Grams for [name] in a component dictionary keyed by NORMALIZED names.
 * Exact normalized hit first; otherwise a prefix relation ("salad" vs
 * "cabbage salad") — but only when it is unambiguous. A wrong transfer
 * is worse than no transfer.
 */
fun lookupFoodGrams(byNormName: Map<String, Double>, name: String): Double? {
    val n = normalizeFoodName(name)
    if (n.isEmpty()) return null
    byNormName[n]?.let { return it }
    val hits = byNormName.entries.filter {
        it.key.startsWith("$n ") || n.startsWith("${it.key} ")
    }
    return hits.singleOrNull()?.value
}

/** A composite-meal component from an LLM parse: [unitGrams] is carbs of
 *  ONE unit/portion, [count] the number of units — "cantucci ×2 = 11 g"
 *  teaches cantucci = 11 g/unit, so "×3" next time needs no LLM call. */
data class ComponentEstimate(
    val name: String,
    val unitGrams: Double,          // CARBS per one unit (the model's number)
    val count: Int,
    val portionGrams: Double? = null, // natural portion per unit, if known
) {
    val totalGrams: Double get() = unitGrams * count
    val totalPortion: Double? get() = portionGrams?.let { it * count }
}

private fun numBefore(text: String, tag: String): Double? =
    Regex("""([\d]+(?:[.,]\d+)?)\s*$tag""").find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

/**
 * Parse `COMPONENT_LINE_PREFIX:` lines. New format carries PORTION and CARBS explicitly:
 *   `COMPONENT_LINE_PREFIX: buckwheat ×1 = 35 carbs · 175 portion`  (carbs = model, portion = human)
 * Legacy `COMPONENT_LINE_PREFIX: bread ×2 = 10 g` reads the single number as CARBS (original
 * spec), portion unknown. When only a portion is present, carbs are derived
 * from the concept's density. X is per ONE unit; "×N" scales it.
 */
fun parseComponents(analysis: String): List<ComponentEstimate> =
    analysis.lineSequence()
        .map { it.trim() }
        .filter { isMarkerLine(it, COMPONENT_LINE_PREFIX) }
        .mapNotNull { line ->
            val rest = line.substringAfter(':').trim()
            var name = rest.substringBefore('=').trim().trim('-', '•', '*', ' ')
            var count = 1
            Regex("""[×x]\s*(\d+)\s*$""").find(name)?.let { m ->
                count = m.groupValues[1].toInt().coerceIn(1, 20)
                name = name.removeRange(m.range).trim()
            }
            val eq = rest.substringAfter('=', "")
            val portion = numBefore(eq, COMPONENT_PORTION_TAG) ?: numBefore(eq, LEGACY_COMPONENT_PORTION_TAG)
            val carbsTag = numBefore(eq, COMPONENT_CARBS_TAG) ?: numBefore(eq, LEGACY_COMPONENT_CARBS_TAG)
            val carbs = when {
                carbsTag != null -> carbsTag
                portion != null -> carbsForPortion(conceptFor(name)?.id, portion)
                else -> {
                    // Legacy "= X g" / "= X–Y g": single number or range midpoint.
                    val ns = Regex("""\d+(?:[.,]\d+)?""").findAll(eq)
                        .map { it.value.replace(',', '.').toDouble() }.toList()
                    when {
                        ns.isEmpty() -> null
                        ns.size == 1 -> ns[0]
                        else -> (ns[0] + ns[1]) / 2.0
                    }
                }
            }
            if (name.isNotEmpty() && carbs != null && carbs >= 0 && carbs < 500) {
                ComponentEstimate(name, carbs, count, portion)
            } else null
        }
        .toList()
        .ifEmpty {
            parseComponentBullets(analysis).map { (n, g) -> ComponentEstimate(n, g, 1) }
        }

/**
 * True when the analysis carries the CANONICAL machine `COMPONENT_LINE_PREFIX:` lines
 * (what the editor writes), not just prose bullets. Callers use this to decide whether
 * the component sum is trustworthy as the meal total: canonical = user-curated →
 * authoritative; bullet-only = raw LLM range midpoints → keep the stored estCarbs.
 */
fun hasCanonicalComposition(analysis: String?): Boolean =
    analysis?.lineSequence()?.any { isMarkerLine(it, COMPONENT_LINE_PREFIX) } == true

/**
 * The canonical `COMPONENT_LINE_PREFIX:` line for a component (portion + carbs):
 * "COMPOSITION: bread ×2 = 12 carbs · 35 portion".
 *
 * [legacy] writes the older Russian marker and tags instead. Only a rewrite of
 * a line that is ALREADY in that form uses it, so editing a number in a stored
 * analysis changes the number and nothing else.
 */
fun sostavLine(
    name: String,
    count: Int,
    carbs: Double,
    portionGrams: Double?,
    legacy: Boolean = false,
): String = buildString {
    val marker = if (legacy) LEGACY_LINE_PREFIXES.getValue(COMPONENT_LINE_PREFIX) else COMPONENT_LINE_PREFIX
    val carbsTag = if (legacy) LEGACY_COMPONENT_CARBS_TAG else COMPONENT_CARBS_TAG
    val portionTag = if (legacy) LEGACY_COMPONENT_PORTION_TAG else COMPONENT_PORTION_TAG
    append("$marker: ${name.trim()}")
    if (count > 1) append(" ×$count")
    append(" = %.0f $carbsTag".format(carbs))
    portionGrams?.takeIf { it > 0 }?.let { append(" · %.0f $portionTag".format(it)) }
}

/** True when [line] is a composition line in the older Russian form. */
fun isLegacyCompositionLine(line: String): Boolean =
    line.trim().startsWith("${LEGACY_LINE_PREFIXES.getValue(COMPONENT_LINE_PREFIX)}:", ignoreCase = true)

/** Per-UNIT grams by component name — the dictionary-learning view of
 *  [parseComponents], kept for existing callers. */
fun parseComponentsEstimate(analysis: String): List<Pair<String, Double>> =
    parseComponents(analysis).map { it.name to it.unitGrams }

/**
 * A structured food analysis straight from the LLM's tool call. The SOURCE now
 * carries carbs PER COMPONENT, so nothing is ever redistributed —
 * [serializeFoodAnalysis] renders this into the canonical machine format the rest
 * of the pipeline already reads, which makes every such note "canonical"
 * ([hasCanonicalComposition] == true, so bulletScale == 1.0). The old
 * prose→grams smear — the one that gave a cucumber carbs it never had and let it
 * become a pool key — is then impossible by construction, not by careful parsing.
 */
data class FoodComponentOut(
    val name: String,
    val carbsPerUnit: Double,          // carbs of ONE unit — the model's number, NEVER distributed
    val count: Int = 1,
    val portionPerUnit: Double? = null,
    val speed: String? = null,         // FAST|MED|SLOW — a CarbSpeed prior (captured now, weighted later)
    val confidence: Double? = null,    // 0..1 in the carb estimate (captured now, weighted later)
    val isRescue: Boolean = false,     // dextrose/juice — marked so it never seeds a food pool
)

/**
 * F-05 layer 5: something the model had to GUESS because the description does
 * not say — named out loud instead of silently assumed.
 *
 * A mislabeled bread is the type specimen: the model correctly knows
 * white flour is fast and wholegrain is medium; it did not know which bread the
 * user eats, silently assumed white, and moved a large share of the meal's
 * carbohydrate into FAST. The defect was not the knowledge — it was that the
 * assumption had no channel to become a question.
 */
data class FoodAssumptionOut(
    /** Which component the guess is about, when the model can say (its name
     *  from `components`) — this is what lets the answer land in the library
     *  and never be asked again. */
    val component: String? = null,
    val what: String,
    val impact: String? = null,
)

data class FoodAnalysisOut(
    val dishName: String? = null,
    val gi: Int? = null,
    val totalCarbsMin: Double? = null,
    val totalCarbsMax: Double? = null,
    val summary: String? = null,
    val totalProteinG: Double? = null,
    val totalFatG: Double? = null,
    val totalKcal: Double? = null,
    val physicalForm: FoodPhysicalFormV2 = FoodPhysicalFormV2.UNKNOWN,
    val totalFiberG: Double? = null,
    val alcoholPresent: Boolean = false,
    val components: List<FoodComponentOut> = emptyList(),
    val assumptions: List<FoodAssumptionOut> = emptyList(),
)

const val ASSUMPTION_LINE_PREFIX = "ASSUMPTION"
const val CLARIFICATION_LINE_PREFIX = "CLARIFICATION"

/** The stored assumptions of a note, newest analysis last — what the dialogue
 *  turns into ONE pointed question. */
fun parseFoodAssumptions(analysis: String?): List<FoodAssumptionOut> =
    analysis?.lineSequence()
        ?.filter { isMarkerLine(it, ASSUMPTION_LINE_PREFIX) }
        ?.map { line ->
            val body = line.substringAfter(':').trim()
            val component = Regex("""^\[([^\]]+)\]\s*""").find(body)?.groupValues?.get(1)
            val rest = body.replace(Regex("""^\[([^\]]+)\]\s*"""), "")
            FoodAssumptionOut(
                component = component?.takeIf { it.isNotBlank() },
                what = rest.substringBefore(" — ").trim(),
                impact = rest.substringAfter(" — ", "").trim().takeIf { it.isNotEmpty() },
            )
        }
        ?.filter { it.what.isNotBlank() }
        ?.toList()
        .orEmpty()

/** True when the note already carries an answer for this assumption's
 *  component — the question was asked once and must not come back. */
fun hasClarification(analysis: String?, component: String?): Boolean =
    component != null && analysis?.lineSequence()?.any {
        val t = it.trim()
        isMarkerLine(t, CLARIFICATION_LINE_PREFIX) &&
            t.substringAfter(':').trim().startsWith("[$component]", ignoreCase = true)
    } == true

/**
 * Render a structured analysis into the canonical text the pipeline reads: the
 * name (both conventions — bare first line for vision, `TITLE_LINE_PREFIX:` for the text
 * path), a human summary, one canonical `COMPONENT_LINE_PREFIX:` line per component (via
 * [sostavLine], so carbs stay per-component and are never rescaled), then `GI_LINE_PREFIX:`
 * and `CARBS_LINE_PREFIX:`. Per-component confidence/speed ride on a "META:" line the
 * parsers ignore, preserved in the stored analysis for the later weighting stage.
 */
fun serializeFoodAnalysis(a: FoodAnalysisOut): String = buildString {
    // Free-text fields are FLATTENED to one line AND DEFANGED. A newline inside
    // dish_name or summary would let the model inject its own fake component line
    // (e.g. "COMPONENT_LINE_PREFIX: candy = 90 carbs") — and so would a value that
    // merely STARTS with a machine prefix, because parseComponents collects EVERY
    // matching line and parseNameSuggestion takes the FIRST. The text is data, not
    // markup we can trust. (GI is left alone: its parser takes the LAST match,
    // which is always ours, and the two-letter prefix would false-positive on real
    // dish names.)
    fun oneLine(s: String?): String {
        val flat = s?.replace('\n', ' ')?.replace('\r', ' ')?.trim()
            ?.replace(Regex("""\s{2,}"""), " ").orEmpty()
        // Both marker forms are reserved: the parsers read the legacy ones too.
        val reserved = isMarkerLine(flat, COMPONENT_LINE_PREFIX) ||
            isMarkerLine(flat, TITLE_LINE_PREFIX) ||
            isMarkerLine(flat, CARBS_LINE_PREFIX) ||
            // A dish name opening with a fake CLARIFICATION_LINE_PREFIX would make
            // hasClarification true and silently suppress the layer-5
            // question — the exact defect the layer exists to remove.
            isMarkerLine(flat, ASSUMPTION_LINE_PREFIX) ||
            isMarkerLine(flat, CLARIFICATION_LINE_PREFIX)
        return if (reserved) "· $flat" else flat
    }

    // A name is REQUIRED by the first-line contract: without one the SUMMARY
    // becomes line 1, and the photo path prefills line 1 as the note's name —
    // a whole sentence would become the dish. But the fallback must not borrow a
    // SINGLE component's name for a composite meal: labelling "buckwheat + sausages +
    // cucumber" as "buckwheat" pools it into the pure-buckwheat concept with a quite
    // different fat/protein load. A junk unique key contaminates nothing; a wrong
    // transfer is worse than no transfer — the rule lookupFoodGrams already obeys.
    val fallbackName = when (a.components.size) {
        0 -> ""
        1 -> a.components[0].name
        else -> a.components.sortedByDescending { it.carbsPerUnit * it.count }
            .take(2).joinToString(" + ") { it.name.trim() }
    }
    val name = oneLine(a.dishName).ifEmpty { oneLine(fallbackName) }
    if (name.isNotEmpty()) {
        appendLine(name)                          // vision convention: first line = name
        appendLine("$TITLE_LINE_PREFIX: $name")   // text convention: parseNameSuggestion reads this
    }
    // Never emit the summary when there is no name — it would become line 1.
    oneLine(a.summary).takeIf { it.isNotEmpty() && name.isNotEmpty() }?.let { appendLine(it) }
    // EVERY component is emitted, including 0-carb ones. Dropping them is
    // tempting («a cucumber must never seed a pool») and wrong twice over:
    // [MealFingerprint] sums fat/protein/fibre over ALL components, so deleting
    // the meat row silently flips a dish's macro load (measured: fat HIGH→LOW,
    // protein HIGH→MED) and with it donor selection in predictKinetics; and the
    // pool protection already lives where it belongs — `carbDrivers` filters on
    // carbGrams > 0 AND MIN_DRIVER_DENSITY_G. Filter for the corpus THERE, never
    // by deleting rows from the stored note.
    a.components.forEach {
        appendLine(sostavLine(it.name, it.count, it.carbsPerUnit, it.portionPerUnit))
    }
    val meta = a.components.mapNotNull { c ->
        val bits = buildList {
            // Locale.ROOT: the default locale wrote «conf 0,60» on the phone and
            // «conf 0.60» in tests — one serializer, two corpus formats. And the
            // separator must not be a comma, or it collides with the decimal.
            c.confidence?.let { add(String.format(java.util.Locale.ROOT, "conf %.2f", it)) }
            c.speed?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
            if (c.isRescue) add("rescue")
        }
        if (bits.isEmpty()) null else "${c.name.trim()} [${bits.joinToString(" · ")}]"
    }
    if (meta.isNotEmpty()) appendLine("$META_LINE_PREFIX: " + meta.joinToString("; "))
    // Layer 5: guesses are stored NAMED, so the dialogue can ask one pointed
    // question and a later reader can see what was assumption rather than
    // fact. Defanged like every other free-text field.
    a.assumptions.forEach { g ->
        val component = oneLine(g.component).removePrefix("· ")
            .takeIf { it.isNotEmpty() }?.let { "[$it] " } ?: ""
        val impact = oneLine(g.impact).removePrefix("· ")
            .takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""
        val body = oneLine(g.what).removePrefix("· ")
        if (body.isNotEmpty()) appendLine("$ASSUMPTION_LINE_PREFIX: $component$body$impact")
    }
    nutritionLines(
        FoodNutrition(a.totalProteinG, a.totalFatG, a.totalKcal),
    ).takeIf { it.isNotBlank() }?.let { appendLine(it) }
    appendLine(foodKineticsLineV2(kineticsFromStructuredFoodV2(a)))
    a.gi?.let { appendLine("$GI_LINE_PREFIX: $it") }
    // A model that returns min > max must not silently lose the range.
    val lo = listOfNotNull(a.totalCarbsMin, a.totalCarbsMax).minOrNull()
    val hi = listOfNotNull(a.totalCarbsMin, a.totalCarbsMax).maxOrNull()
    when {
        lo != null && hi != null && hi > lo ->
            appendLine("$CARBS_LINE_PREFIX: " + String.format(java.util.Locale.ROOT, "%.0f–%.0f g", lo, hi))
        lo != null -> appendLine("$CARBS_LINE_PREFIX: " + String.format(java.util.Locale.ROOT, "%.0f g", lo))
    }
}.trimEnd('\n')

/**
 * Fallback for free-form Vision answers that skip the strict COMPONENT_LINE_PREFIX format:
 * "- Mashed potatoes (large portion) — main source, ~25–35 g."
 * A bullet counts only when its tail names grams — that keeps prose
 * bullets ("- pairs well with...") out.
 */
private fun parseComponentBullets(analysis: String): List<Pair<String, Double>> =
    analysis.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("-") || it.startsWith("\u2022") }
        .mapNotNull { line ->
            val m = Regex("""^[-\u2022]\s*(.+?)\s*[\u2014\u2013:]\s*(.+)$""").find(line) ?: return@mapNotNull null
            val name = m.groupValues[1].trim().trim('*', ' ')
            // Conditional prose ("\u0435\u0441\u043b\u0438 \u0441\u043c\u0443\u0437\u0438...", "\u0432\u043e\u0437\u043c\u043e\u0436\u043d\u044b\u0435 \u0434\u043e\u0431\u0430\u0432\u043a\u0438") is
            // speculation about the dish, not a component of it.
            val firstWord = name.lowercase().substringBefore(' ')
            if (firstWord == "\u0435\u0441\u043b\u0438" || firstWord.startsWith("\u0432\u043e\u0437\u043c\u043e\u0436\u043d")) return@mapNotNull null
            val tail = m.groupValues[2]
            val g = Regex("""[~\u2248]?\s*(\d+(?:[.,]\d+)?)(?:\s*[\u2013\u2014-]\s*(\d+(?:[.,]\d+)?))?\s*(?:г|g)\b""")
                .find(tail) ?: return@mapNotNull null
            val lo = g.groupValues[1].replace(',', '.').toDouble()
            val hi = g.groupValues[2].takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDouble()
            val grams = if (hi != null) (lo + hi) / 2.0 else lo
            if (name.isNotEmpty() && grams < 500) name to grams else null
        }
        .toList()
