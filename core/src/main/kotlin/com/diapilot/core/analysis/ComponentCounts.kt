/**
 * UNIT COUNTS RECOVERED FROM THE USER'S OWN NOTE TEXT.
 *
 * Precondition for solving dish composition as a system of equations. The
 * measured defect it addresses: a set of breakfasts whose
 * note text is byte-identical — "breakfast (hummus, salad, scramble, 2 slices of bread)" —
 * split into rows written `bread ×2` and rows written `bread` with no count at
 * all. Near-perfect separation, a **1.82× bias rather than spread**. A
 * least-squares fit over those rows fits a bimodal denominator, so no solver
 * improvement can recover from it.
 *
 * The repair does NOT touch the parser — [parseComponents] multiplies correctly.
 * It reconciles the recorded count against the count the user's own free text
 * states, and where they disagree it offers a DERIVED value **alongside** with its
 * provenance. Three rules govern this file and each one is a rule the project has
 * already paid for:
 *
 *  - **The primary note is never rewritten.** Some of the project's own corrections
 *    were retracted shortly after being applied and every retraction was only
 *    possible because the originals were intact.
 *  - **Grams are never derived from the glucose response.** The carb coefficient
 *    was derived FROM grams; running that backwards makes the model unfalsifiable
 *    and kills the anomaly detector the whole promise rests on.
 *  - **The evidence must be INDEPENDENT of the writer.** `est_carbs` and the
 *    `COMPONENT_LINE_PREFIX:` lines are both emitted by `AnnotationComposer` from one `compRows`
 *    via `sostavLine`, the exact inverse of the parser — so they agree by
 *    construction and can corroborate nothing. `content` is what the user
 *    typed; the analysis prose is what the model wrote. Only the first is
 *    independent of the row being checked, and it is the only source read here.
 */
package com.diapilot.core.analysis

/**
 * Word numerals a note actually uses, plus the inflections seen in the corpus.
 * English is DATA alongside Russian, not a second code path — same map, same
 * lookup, same 2..20 filter applied afterward in [countFromNoteText].
 */
private val WORD_NUMERALS: Map<String, Int> = mapOf(
    "один" to 1, "одна" to 1, "одно" to 1, "одного" to 1, "одну" to 1,
    "два" to 2, "две" to 2, "двух" to 2, "пара" to 2, "пары" to 2, "оба" to 2, "обе" to 2,
    "три" to 3, "трёх" to 3, "трех" to 3,
    "четыре" to 4, "четырёх" to 4, "четырех" to 4,
    "пять" to 5, "пяти" to 5, "шесть" to 6, "шести" to 6,
    "семь" to 7, "семи" to 7, "восемь" to 8, "восьми" to 8,
    "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
    "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    "couple" to 2, "pair" to 2, "both" to 2,
)

/** Mass/volume units, Russian and English — a number carrying one of these is a
 *  WEIGHT, never a count. */
private val MASS_UNITS = Regex(
    """^(г|гр|грамм\w*|кг|мл|л|литр\w*|g|gram|grams|kg|ml|l|liter|liters|litre|litres|oz|ounce|ounces|cup|cups|tbsp|tablespoons?|tsp|teaspoons?)$""",
)

/** "110g", "0.33L" — number and unit fused into one token. */
private val FUSED_MASS = Regex(
    """^\d+(?:[.,]\d+)?(г|гр|грамм\w*|кг|мл|л|g|gram|grams|kg|ml|l|oz|cup|cups|tbsp|tsp)$""",
)

/** Marks the mass in the clause as PER UNIT, so a count beside it is still real. */
private val PER_UNIT_MARKERS = setOf(
    "каждый", "каждая", "каждое", "каждого", "каждым", "по", "штука", "шт",
    "each", "every", "per", "pcs",
)

/** Marks the mass as the COMBINED weight of the counted units. */
private val AGGREGATE_MARKERS = setOf(
    "оба", "обе", "вместе", "всего", "суммарно",
    "both", "together", "total", "combined", "altogether",
)

/**
 * A unit count found in the user's own note text.
 *
 * [aggregateMass] is the honest brake on this whole mechanism. A phrase like
 * "2 pieces of a chocolate bar, 14g" names two units AND the weight of both
 * together, so the recorded weight already covers the pair and multiplying it
 * by the text's count would DOUBLE a correct row. Where the text is built that
 * way the count is reported with this flag set and no derived value is offered.
 */
data class TextCount(
    val count: Int,
    /** The matched span, verbatim, so provenance can quote the note. */
    val phrase: String,
    val aggregateMass: Boolean,
)

/** How the recorded count relates to the count the note's text states. */
enum class CountVerdict {
    /** Text and record state the same count — nothing to do. */
    AGREE,

    /** The text names N > 1, the record carries no count. The 1.82× class. */
    MISSING_IN_RECORD,

    /** The record carries a count the text does not mention. No text evidence
     *  either way — NOT a conflict, and deliberately not corrected. */
    RECORD_ONLY,

    /** Both name a count and they differ. Never auto-resolved. */
    CONFLICT,

    /** The text names a count but also gives a combined weight for the units,
     *  so the recorded figure may already be the total. See [TextCount]. */
    AMBIGUOUS,

    /** Neither side names a count above one. */
    NO_EVIDENCE,
}

/**
 * One component row with the text's own verdict on its count attached. The
 * recorded [component] is passed through UNCHANGED — [derivedTotalGrams] is a
 * second column, never a replacement.
 */
data class ReconciledComponent(
    val component: ComponentEstimate,
    val textCount: TextCount?,
    val verdict: CountVerdict,
    /** Total grams implied by the TEXT's count, or null when nothing is derivable
     *  or the derived value equals the recorded one. */
    val derivedTotalGrams: Double?,
    /** Human-readable provenance for the derived value — quotes the note. */
    val provenance: String?,
) {
    /** What a consumer should multiply when it wants the reconciled number. Falls
     *  back to the recorded total, so a caller can use it unconditionally. */
    val bestTotalGrams: Double get() = derivedTotalGrams ?: component.totalGrams
}

private fun tokenize(text: String): List<String> =
    text.lowercase().replace('ё', 'е')
        .split(Regex("""[\s,;.()\[\]«»"'\-—–:/]+"""))
        .filter { it.isNotEmpty() }

/** The count a token carries, or null when it is not a count at all. */
private fun countOf(token: String, next: String?): Int? {
    // NOTE: there is no `FUSED_MASS` check here and there must not be one. A token
    // like "110g" written in Cyrillic ends in a Cyrillic letter, so `toIntOrNull()`
    // already rejects it — a guard here was DEAD CODE, verified in review by
    // deleting it and finding no behaviour change.
    // (`FUSED_MASS` is still live in the aggregate-mass scope test below.)
    WORD_NUMERALS[token]?.let { return it }
    val n = token.toIntOrNull() ?: return null
    if (next != null && MASS_UNITS.matches(next)) return null   // "12 grams" is a weight
    return n.takeIf { it in 2..20 }                             // "×1" adds nothing
}

/**
 * The count [componentName] carries in [content], the user's own note text.
 *
 * Two forms, both present in the corpus:
 *  - a numeral BEFORE the name, up to three tokens back so an intervening unit
 *    noun and adjective still match ("2 small slices of bread" → 2);
 *  - "×N" / "xN" immediately AFTER it ("bread ×2" → 2).
 *
 * Matching stems the component's first word, because Russian inflects the noun —
 * a word for "bread" changes form depending on case, and a word for "pancakes"
 * becomes a different form after a count — and an exact match finds almost
 * nothing. The stem is shortened PROGRESSIVELY, longest first, down to a floor
 * of four characters: a short word for "butter" finds its inflected form at
 * full length and never has to fall back to a shorter fragment that would
 * collide with an unrelated word. Below four characters (short words like
 * "rice", "tea", "onion") this returns null rather than guessing — a wrong
 * count is worse than no count, the rule [lookupFoodGrams] already follows.
 * The residual collision risk is real, which is why the `counts` study prints
 * every match with the token it matched: at a moderate sample size, reading
 * the list beats trusting a heuristic.
 */
fun countFromNoteText(content: String, componentName: String): TextCount? {
    val word = normalizeFoodName(componentName).split(" ").firstOrNull()
        ?.replace('ё', 'е').orEmpty()
    if (word.length < 4) return null
    val tokens = tokenize(content)
    var stem = ""
    var at = -1
    for (len in word.length downTo 4) {
        val s = word.take(len)
        val i = tokens.indexOfFirst { it.startsWith(s) }
        if (i >= 0) { stem = s; at = i; break }
    }
    if (at < 0) return null

    // "×2" directly after the name.
    var count: Int? = null
    var phrase: String? = null
    tokens.getOrNull(at + 1)?.let { nxt ->
        Regex("""^[×x](\d+)$""").find(nxt)?.let { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 2..20 }?.let {
                count = it
                phrase = "${tokens[at]} $nxt"
            }
        }
    }
    // Otherwise a numeral up to three tokens before it.
    if (count == null) {
        for (back in 1..3) {
            val i = at - back
            if (i < 0) break
            countOf(tokens[i], tokens.getOrNull(i + 1))?.let {
                count = it
                phrase = tokens.subList(i, at + 1).joinToString(" ")
                return@let
            }
            if (count != null) break
        }
    }
    // Only counts above one carry information: "×1" and "one" say nothing the
    // recorded row does not already say.
    val c = count?.takeIf { it >= 2 } ?: return null

    // Aggregate-mass detection, over the count phrase's clause and the one after
    // it: "2 pancakes, each 75g" is per unit, "2 crackers, both weigh 12 grams" is
    // not, and a bare "...bar, 14g" defaults to aggregate because that is the
    // reading that cannot double a correct row.
    val clauses = content.lowercase().replace('ё', 'е').split(Regex("""[,;.]"""))
    val idx = clauses.indexOfFirst { it.contains(stem) }
    val scope = if (idx < 0) clauses else clauses.drop(idx).take(2)
    val scopeTokens = scope.flatMap { tokenize(it) }
    val hasMass = scopeTokens.withIndex().any { (i, t) ->
        FUSED_MASS.matches(t) || (MASS_UNITS.matches(t) && scopeTokens.getOrNull(i - 1)?.toIntOrNull() != null)
    }
    val aggregate = hasMass && when {
        scopeTokens.any { it in AGGREGATE_MARKERS } -> true
        scopeTokens.any { it in PER_UNIT_MARKERS } -> false
        else -> true
    }
    return TextCount(c, phrase.orEmpty(), aggregate)
}

/**
 * Reconcile every component of [analysis] against the counts [content] states.
 *
 * Returns one row per component in the SAME order [parseComponents] produced
 * them, with the recorded estimate untouched. Only [CountVerdict.MISSING_IN_RECORD]
 * yields a derived total; every other class is reported and left alone, including
 * [CountVerdict.CONFLICT] — two sources disagreeing is a question for the user,
 * not something a heuristic gets to settle.
 */
fun reconcileCounts(content: String, analysis: String): List<ReconciledComponent> {
    val comps = parseComponents(analysis)
    // STEM-SPAN VETO. `countFromNoteText` finds a numeral up to three tokens before the
    // component, and a prefix match can land on a different word: "3 sausages with
    // potatoes" gives the word for `potato` (stem covering its first letters) the
    // sausages' count of 3, because the inflected form of "potatoes" starts with that
    // stem. Found in review by probing the parser directly, and it is one note away
    // from silently multiplying grams — the corpus already contains both a
    // doubled-potato and a tripled-sausage entry.
    //
    // The veto belongs HERE rather than in the primitive, because only this level knows
    // the SIBLING components: a count whose matched span also contains another
    // component of the same note is not attributable, so it is refused.
    fun spanContainsSibling(phrase: String, self: String): Boolean {
        val tokens = tokenize(phrase)
        // Drop the last token — that is the component's own match.
        val before = tokens.dropLast(1)
        return comps.any { other ->
            if (normalizeFoodName(other.name) == normalizeFoodName(self)) return@any false
            val w = normalizeFoodName(other.name).split(" ").firstOrNull()
                ?.replace('ё', 'е').orEmpty()
            w.length >= 4 && (4..w.length).any { len ->
                before.any { t -> t.startsWith(w.take(len)) }
            }
        }
    }
    return comps.map { c ->
        val tc = countFromNoteText(content, c.name)
            ?.takeUnless { spanContainsSibling(it.phrase, c.name) }
        val verdict = when {
            tc == null && c.count <= 1 -> CountVerdict.NO_EVIDENCE
            tc == null -> CountVerdict.RECORD_ONLY
            // AGREE outranks AMBIGUOUS deliberately. The aggregate-mass flag only
            // decides whether a MISSING count may be applied; when the record
            // already carries the same count there is nothing left to decide, and
            // marking it ambiguous mislabelled two correct rows ("2 cutlets" in a
            // note whose only weight belongs to the mashed potatoes beside them, and
            // the cantucci whose "both weigh 12 grams" the writer had already handled).
            c.count == tc.count -> CountVerdict.AGREE
            tc.aggregateMass -> CountVerdict.AMBIGUOUS
            c.count <= 1 -> CountVerdict.MISSING_IN_RECORD
            else -> CountVerdict.CONFLICT
        }
        val derived = if (verdict == CountVerdict.MISSING_IN_RECORD) c.unitGrams * tc!!.count else null
        ReconciledComponent(
            component = c,
            textCount = tc,
            verdict = verdict,
            derivedTotalGrams = derived,
            provenance = derived?.let {
                "derived:text-count ×${tc!!.count} («${tc.phrase}»)"
            },
        )
    }
}
