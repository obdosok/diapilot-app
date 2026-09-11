package com.diapilot.core.analysis

/**
 * F-05: the composer RECOGNISES a dish instead of re-deriving it every time.
 *
 * The trap this must not fall into is measured: a name as an
 * AUTOMATIC key loses over a fifth of the best-understood dish — the top
 * example covers dozens of intakes under three different titles. A name as a
 * CONFIRMED hint is a different thing: the match is shown and the user taps,
 * so a wrong match stops being silent and `no auto-teach` holds — the
 * structure the user confirms is one they already accepted for the dish.
 *
 * Two tiers, deliberately distinct:
 *  - [exactAlias] — the wording is ALREADY a confirmed alias of an accepted
 *    dish. The user has said "this wording is this dish" before (either in the
 *    curated asset accepted, or by answering a match question — layer 3), so
 *    the structure applies without a question. Applying it is not auto-teach:
 *    the teaching moment already happened, on an explicit tap.
 *  - [candidate] — the wording is NEW. The best guess is offered as a QUESTION
 *    ("is this your smoothie? seen this many times, usually this many grams")
 *    and nothing is written until the user answers. A confirmed answer both
 *    applies the structure and grows the alias list (layer 3), so the
 *    vocabulary converges on how the user actually writes.
 *
 * Pure logic only — persistence of learned aliases and the dialogue state is
 * the app's business.
 */
object DishRecognitionV1 {

    /** A dish the app knows: the accepted-structure proposal plus the two
     *  facts the confirmation question needs (how often, how much usually). */
    data class KnownDish(
        val proposed: FoodStructureAcceptanceV1.Proposed,
        val intakes: Int = 0,
        val typicalCarbsG: Double? = null,
    )

    /** A match that NEEDS CONFIRMATION. [matchedAlias] is what the query hit;
     *  [coverage] is how much of that alias the query explains (0..1). */
    data class Candidate(
        val dish: KnownDish,
        val matchedAlias: String,
        val coverage: Double,
    )

    /**
     * Words that describe portion or habit, not identity. "usual smoothie" and
     * "glass of smoothie" are the same dish; "usual" must not block the match nor
     * count toward it.
     */
    private val STOP = setOf(
        "обычный", "обычная", "обычное", "мой", "моя", "моё", "мое",
        "стакан", "чашка", "кружка", "бутылка", "банка", "тарелка", "порция",
        "немного", "чуть", "примерно", "около", "штука", "шт",
        "и", "с", "со", "на", "в", "из", "по", "от", "до", "без", "для",
        "каждый", "каждая", "каждое", "оба", "обе", "всего",
        "маленький", "маленькая", "большой", "большая", "снова", "опять",
    )

    private fun clean(s: String): String = s.lowercase()
        .replace('ё', 'е')
        .replace(Regex("""[^а-яa-z0-9 ]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /**
     * Weight tokens ("75g", "0.5L", "12") say how much, never what — both
     * tiers drop them, because grams stay the user's and editable regardless.
     *
     * STOP words drop only in the CANDIDATE tier. The exact tier must contain
     * nothing the user has not confirmed: "glass of smoothie" is a confirmed
     * alias, "usual smoothie" is not, and folding "usual" away would let the
     * second apply structure silently — precisely the auto-key the measured
     * loss argues against.
     */
    private fun tokens(s: String, dropStop: Boolean): List<String> = clean(s)
        .split(' ')
        .filter { it.isNotBlank() && (!dropStop || it !in STOP) }
        .filterNot { it.matches(Regex("""\d+([.,]\d+)?(г|гр|грамм|мл|л|кг|шт)?""")) }

    /**
     * Two tokens name the same word when equal or when they share a stem —
     * a common prefix of at least four letters covering all but the ending
     * (Russian noun-case endings on the same word). Four letters is deliberate:
     * two unrelated words sharing just two letters must not meet.
     */
    internal fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        val common = a.commonPrefixWith(b).length
        return common >= 4 && common >= minOf(a.length, b.length) - 2
    }

    /** The wording IS a known alias — normalized, weight tokens dropped, every
     *  word kept and required to line up. */
    fun exactAlias(query: String, dishes: List<KnownDish>): KnownDish? {
        val q = tokens(query, dropStop = false)
        if (q.isEmpty()) return null
        return dishes.firstOrNull { d ->
            d.proposed.aliases.any { alias ->
                val a = tokens(alias, dropStop = false)
                a.isNotEmpty() && a.size == q.size &&
                    a.zip(q).all { (x, y) -> sameWord(x, y) }
            }
        }
    }

    // The best UNCONFIRMED match for a new wording, or null when nothing is
    // close enough to ask about. One candidate, never a list — the dialogue
    // rule is ONE pointed question where it changes the record, not a survey.
    //
    // A candidate requires every significant query token to appear in the
    // alias/title (the user must not have named things the dish is not), and is
    // ranked by how much of the alias the query covers — so "pancakes" prefers
    // "2 pancakes" (full coverage) over "pancake with pistachio spread" (a third).
    // Ties break by intake count: the dish eaten often is the likelier guess.
    /**
     * A candidate must explain MOST of the dish it proposes, not merely fit
     * inside it. Replayed on the real corpus in review: without
     * this floor, several out of a handful of questions were wrong — "chips" asked as
     * "beer and chips", "pistachios 120g" asked as "ice cream", each at coverage 0.50 —
     * and one careless "yes" would promote the wording into the silent tier
     * forever. The good matches all sit at 1.0.
     */
    private const val MIN_COVERAGE = 0.6

    fun candidate(query: String, dishes: List<KnownDish>): Candidate? {
        val q = tokens(query, dropStop = true)
        if (q.isEmpty()) return null
        var best: Candidate? = null
        for (d in dishes) {
            for (alias in d.proposed.aliases + d.proposed.title) {
                val a = tokens(alias, dropStop = true)
                if (a.isEmpty()) continue
                // every query token must land on some alias token
                if (!q.all { qt -> a.any { at -> sameWord(qt, at) } }) continue
                val coverage = a.count { at -> q.any { qt -> sameWord(qt, at) } }.toDouble() / a.size
                if (coverage < MIN_COVERAGE) continue
                val cand = Candidate(d, alias, coverage)
                if (best == null ||
                    cand.coverage > best!!.coverage + 1e-9 ||
                    (cand.coverage >= best!!.coverage - 1e-9 && cand.dish.intakes > best!!.dish.intakes)
                ) best = cand
            }
        }
        return best
    }

    /** What a save should do about dish identity — the one decision the
     *  composer takes, extracted so a test can hold it. */
    sealed class Resolution {
        /** Structure rides the save: the user picked the dish, or the wording is a
         *  confirmed alias of a dish the user has accepted. */
        data class Apply(val dish: KnownDish) : Resolution()

        /** Save plain, then ASK — the wording is new, or the dish has never
         *  been accepted, so even an exact alias is still a proposal. */
        data class Ask(val candidate: Candidate) : Resolution()

        object None : Resolution()
    }

    /**
     * [picked] — an explicit tap on the "Repeat" list, which IS a
     * confirmation and needs no prior acceptance. The silent tier below it is
     * gated on [acceptedIds]: for a dish never accepted, an asset alias is
     * somebody's guess, and applying it without a question would be auto-teach.
     */
    fun resolve(
        content: String,
        dishes: List<KnownDish>,
        acceptedIds: Set<String>,
        picked: KnownDish? = null,
        isRejected: (dishId: String) -> Boolean = { false },
    ): Resolution {
        picked?.let { return Resolution.Apply(it) }
        exactAlias(content, dishes)
            ?.takeIf { it.proposed.id in acceptedIds }
            ?.let { return Resolution.Apply(it) }
        candidate(content, dishes)
            ?.takeIf { !isRejected(it.dish.proposed.id) }
            ?.let { return Resolution.Ask(it) }
        return Resolution.None
    }

    /**
     * Is [name] actually mentioned in [text]? Every name token must land on a
     * text token (same stem rule as matching).
     *
     * Exists for the known-components block (layer 4): measured in review,
     * an UNFILTERED library block destabilised the carb speed of
     * component-rich dishes — the savoury pancake wobbled 0.11↔1.0 exactly as
     * before the v3 decoupling, and ice cream drew MED in 2 of 3. Filtered to
     * the components the text names, the same dishes are stable (0.98 ×3,
     * 1.0 ×3) and the wholegrain-bread fact still lands (breakfast 0.00 fast
     * ×3). Relevance is not politeness here — it is what keeps the parse
     * deterministic.
     */
    fun mentioned(name: String, text: String): Boolean {
        val nt = tokens(name, dropStop = true)
        if (nt.isEmpty()) return false
        val tt = tokens(text, dropStop = false)
        return nt.all { n -> tt.any { t -> sameWord(n, t) } }
    }

    /** The confirmation move: what is asked, with the two facts that make the
     *  answer informed — how often the user ate it, and the usual grams. The
     *  app words it ("Is this your smoothie? (28 times, usually 22 g)"). */
    fun question(c: Candidate): DishQuestion = DishQuestion(
        title = c.dish.proposed.title,
        intakes = c.dish.intakes.takeIf { it > 0 },
        typicalCarbsG = c.dish.typicalCarbsG,
    )

    /** [intakes] null when the dish was never eaten; [typicalCarbsG] null when unknown. */
    data class DishQuestion(val title: String, val intakes: Int?, val typicalCarbsG: Double?)
}
