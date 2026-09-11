/**
 * EXTERNAL CARB ANCHORS — rewriting a dish's recorded grams to a measurement made
 * OUTSIDE this model.
 *
 * The guiding principle here, and it is general: on more than one occasion, a
 * computation ran on numbers already known to be wrong, and the methods got
 * rejected instead of the data getting fixed. So when an external measurement
 * exists, the data is corrected first and everything downstream is re-measured
 * on it.
 *
 * "External" is the whole load-bearing word. A smoothie might be 200 g of fresh
 * juice from two oranges with the pulp discarded (a recorded fact about how it
 * was made), and `CARB_PER_100G["juice"]` = 11 gives 22 g. That is a
 * package/portion fact plus a density, not an inference from the glucose
 * response.
 *
 * **Grams are NEVER derived from the glucose rise.** The carb coefficient was derived
 * FROM grams; running that backwards makes the model unfalsifiable and destroys the
 * anomaly detector the whole app exists for. An anchor that cannot be stated without
 * mentioning a glucose curve is not an anchor.
 *
 * Four properties this file guarantees, each one paid for:
 *
 *  - **PROPORTIONAL, not flat.** The recorded values are not all identical (some rows
 *    differ from others), and overwriting every row with one fixed number would erase
 *    the user's own distinction between a full glass and a smaller one. A factor
 *    preserves it.
 *  - **IDEMPOTENT.** [carbsSource] == [ANCHOR_SOURCE] means already done, so a second
 *    run cannot multiply again. The dextrose migration was caught with exactly this bug
 *    (the marker was set after the loop rather than per row).
 *  - **The `COMPONENT_LINE_PREFIX` lines move too.** They were forgotten on dextrose, leaving
 *    `parseComponents` reading a different total than `est_carbs` for the same meal —
 *    two numbers for one meal, indefinitely.
 *  - **The user's own TEXT is never touched.** Only grams and provenance change.
 *    Some corrections were retracted shortly after being applied, and every retraction
 *    was only possible because the originals were intact.
 */
package com.diapilot.core.analysis

/** `annotations.carbs_source` for a figure derived from an EXTERNAL measurement —
 *  distinct from `manual` (came through the editor) and from a generated constant. */
const val ANCHOR_SOURCE = "anchor"

/**
 * One dish whose true carbs are known from outside the model.
 *
 * [factor] is `trueGrams / recordedGrams`, applied to whatever the row records, so the
 * user's own portion differences survive. [basis] is the external evidence in words
 * and is carried into the provenance string — an anchor whose basis cannot be written
 * down is not one.
 */
data class CarbAnchor(
    val conceptId: String,
    /** The dish word, English; it names the anchor in the provenance string. */
    val label: String,
    val trueGrams: Double,
    val recordedGrams: Double,
    val basis: String,
    /**
     * Plausible carbs for ONE unit of this dish. A row whose per-unit grams fall outside
     * it is refused — and this is a RULE where a hand-written exclusion list would have
     * been, which matters because a list stops covering new rows the day it is written.
     *
     * PER UNIT, not per note: "beer ×2" records grams for two bottles together, so dividing by the
     * count the note's own text states ([countFromNoteText]) is what makes the range
     * comparable across rows. Getting that backwards would refuse every multi-bottle
     * evening — the majority of the useful ones.
     *
     * Null = no range check (the dish has no meaningful unit).
     */
    val perUnitRange: ClosedFloatingPointRange<Double>? = null,
    /**
     * The dish word in the other supported languages. The note's own text states
     * the count ("beer ×2"), and the user writes it in their language, so the
     * count is looked for under [label] and each of these.
     */
    val words: List<String> = emptyList(),
) {
    /** The count the note's own text states for this dish, under any of its words. */
    fun countIn(content: String): TextCount? =
        (listOf(label) + words).firstNotNullOfOrNull { countFromNoteText(content, it) }

    val factor: Double get() = trueGrams / recordedGrams

    /** True when the anchor only stamps PROVENANCE — the recorded grams are already
     *  correct and nothing is rescaled. */
    val provenanceOnly: Boolean get() = trueGrams == recordedGrams
}

/**
 * The anchors established so far. Only dishes with an EXTERNAL measurement belong
 * here — see `carb-scale-anchored-externally` for the ones still open (pancakes, ice
 * cream, beer, spelt).
 */
val CARB_ANCHORS: List<CarbAnchor> = listOf(
    CarbAnchor(
        conceptId = "beer",
        label = "beer",
        words = listOf("пиво"),
        // PROVENANCE ONLY — the grams are already right and nothing is rescaled. A 0.5 L
        // wheat/dark bottle at 3.5–4 g/100 ml is 17.5–20 g, and the records read 18–20.
        // What this anchor buys is not a correction but the RIGHT to be treated as true
        // grams: it stops the pool from being blended with a recorded-gram prior.
        trueGrams = 18.0,
        recordedGrams = 18.0,
        basis = "0.5 L bottle, wheat/dark, 3.5–4 g/100 ml ⇒ 17.5–20 g; " +
            "recorded 18–20, “beer ×2” = two bottles",
        // The range does the excluding, and it lands exactly on the rows that should be
        // out: "beer, pistachios" and "beer and chips" notes are not beer alone; a couple
        // of other rows are unexplained; a non-alcoholic beer brand is a DIFFERENT dish
        // (non-alcoholic beer carries two to three times the carbs, as the user noted) and
        // must not share a pool; a smaller import bottle is CORRECT for its own size but is
        // not this 0.5 L anchor.
        perUnitRange = 17.5..20.0,
    ),
    CarbAnchor(
        conceptId = "smoothie",
        label = "smoothie",
        trueGrams = 22.0,
        recordedGrams = 33.0,
        basis = "200 g of juice freshly squeezed from two oranges, pulp discarded; " +
            "CARB_PER_100G[juice]=11 ⇒ 22 g",
        words = listOf("смузи"),
    ),
)

/** What an anchor would change on one note. Null from [anchorRewrite] = nothing to do. */
data class AnchorRewrite(
    val anchor: CarbAnchor,
    val oldEstCarbs: Double,
    val newEstCarbs: Double,
    /** Rewritten analysis text, or null when the note carries no matching `COMPONENT_LINE_PREFIX`
     *  line and the text is therefore left byte-identical. */
    val newAnalysis: String?,
    val provenance: String,
)

/** Round to one decimal — the precision the column already uses. */
private fun round1(x: Double): Double = Math.round(x * 10.0) / 10.0

/**
 * The rewrite [anchor] implies for this note, or null when it does not apply.
 *
 * Returns null when: the note names a different dish, it carries no grams, or it is
 * ALREADY anchored ([ANCHOR_SOURCE]) — that last one is the idempotency guard and it
 * is checked per note, never per batch.
 */
fun anchorRewrite(
    content: String,
    analysis: String?,
    estCarbs: Double?,
    carbsSource: String?,
    anchor: CarbAnchor,
): AnchorRewrite? {
    if (carbsSource == ANCHOR_SOURCE) return null            // idempotency
    val grams = estCarbs?.takeIf { it > 0 } ?: return null
    // MATCH ON THE COMPONENT, wherever it sits — not only on the note's title.
    // `conceptFor("beer and chips")` returns CHIPS, not beer (which is also why the corpus
    // files that entry under `chips`), so a title-only test refuses every mixed plate
    // before its composition is even read. An anchor is a fact about an INGREDIENT.
    val titleMatches = conceptFor(content)?.id == anchor.conceptId
    val compositionMatches = analysis?.takeIf { hasCanonicalComposition(it) }
        ?.let { a -> parseComponents(a).any { conceptFor(it.name)?.id == anchor.conceptId } } == true
    if (!titleMatches && !compositionMatches) return null
    // A MIXED NOTE MUST NOT HAVE ITS OTHER COMPONENTS RESCALED.
    //
    // `est_carbs` is the whole meal while the factor belongs to ONE dish, so scaling the
    // total would move the bread by the smoothie's factor. It does not fire on the
    // smoothie notes (all pure), but beer is next and it has several mixed rows —
    // "beer and chips", "beer, pistachios", and the smaller bottle — so "one note away"
    // becomes "several notes" on the very next step.
    //
    // With a canonical composition the anchored part is scaled and the rest is carried
    // through untouched. Without one, the note names a single dish and the whole figure is
    // that dish's.
    val comps = analysis?.takeIf { hasCanonicalComposition(it) }?.let { parseComponents(it) }
    val anchoredOld = comps?.filter { conceptFor(it.name)?.id == anchor.conceptId }
        ?.sumOf { it.totalGrams }
    val othersOld = comps?.filter { conceptFor(it.name)?.id != anchor.conceptId }
        ?.sumOf { it.totalGrams }
    // A composition that names this dish nowhere is not ours to touch, whatever the
    // note's title says.
    if (comps != null && (anchoredOld ?: 0.0) <= 0.0) return null
    // AND A PARTLY-ANCHORED ROW IS REFUSED OUTRIGHT, because `carbs_source` stamps the
    // WHOLE row. Marking "beer and chips" as `anchor` would claim the chips' grams are
    // externally measured too, and `gramsAnchored` — which is read per NOTE, not per
    // component — would then be a lie for half the plate. The scaling logic above is
    // still needed: it protects a note whose title IS the anchored dish but which
    // carries other components. Splitting a row's provenance per component is a schema
    // change, not something to fake with a flag.
    if ((othersOld ?: 0.0) > 0.0) return null

    // PER-UNIT plausibility on the ANCHORED part, using the count the user's own text
    // states — not on the whole-meal total, which would refuse a legitimate mixed plate
    // for carrying a side dish.
    val ourGrams = anchoredOld ?: grams
    anchor.perUnitRange?.let { range ->
        val units = anchor.countIn(content)?.count ?: 1
        if (ourGrams / units !in range) return null
    }
    val newEst = if (comps == null) round1(grams * anchor.factor)
    else round1(ourGrams * anchor.factor + (othersOld ?: 0.0))
    return AnchorRewrite(
        anchor = anchor,
        oldEstCarbs = grams,
        newEstCarbs = newEst,
        newAnalysis = analysis?.let { rescaleComposition(it, anchor) },
        provenance = "anchor:${anchor.label} ×%.3f (%s)".format(anchor.factor, anchor.basis),
    )
}

/**
 * Scale the `COMPONENT_LINE_PREFIX` lines belonging to [anchor]'s concept, leaving every other line —
 * and every other component — byte-identical. Null when nothing changed, so a caller
 * can tell "no composition to fix" from "composition rewritten".
 *
 * Rendered back through [sostavLine], the same writer the editor uses, so a scaled line
 * cannot end up in a format the parser reads differently from the one it wrote.
 */
internal fun rescaleComposition(analysis: String, anchor: CarbAnchor): String? {
    if (!hasCanonicalComposition(analysis)) return null
    var changed = false
    val out = analysis.lineSequence().map { raw ->
        val line = raw.trim()
        if (!isMarkerLine(line, COMPONENT_LINE_PREFIX)) return@map raw
        // Re-parse THIS line alone so the component's name/count/portion come from the
        // production parser rather than from a second hand-rolled reading of the format.
        val c = parseComponents(line).singleOrNull() ?: return@map raw
        if (conceptFor(c.name)?.id != anchor.conceptId) return@map raw
        // ONLY WHEN THE VALUE MOVES. Re-rendering through `sostavLine` also NORMALISES the
        // format — a legacy "= 18 g" becomes the canonical "= 18 carbs" — and on a
        // provenance-only anchor (factor 1.0) that was the ONLY difference. It got written
        // to one real note before this guard existed: the parsed value was identical, but
        // it was an unannounced edit to the user's stored text AND the dry run had
        // promised zero text changes. A write that differs from what the dry run showed is
        // the whole failure mode this study is built to avoid.
        val scaled = round1(c.unitGrams * anchor.factor)
        if (scaled == round1(c.unitGrams)) return@map raw
        changed = true
        // Written back in the line's own form (older Russian marker stays Russian),
        // so the rewrite changes the number and nothing else.
        sostavLine(c.name, c.count, scaled, c.portionGrams, legacy = isLegacyCompositionLine(line))
    }.toList().joinToString("\n")
    return if (changed) out else null
}

// ══════════════════════════════════════════════════════════════════════════════
// TEMPORARY BRIDGE — not a mechanism. Read the removal condition before building
// anything on top of this.
// ══════════════════════════════════════════════════════════════════════════════
//
// The user asked the right question: "is it even correct to adjust coefficients like
// this — shouldn't the grams of the other dishes get sorted out instead?"
//
// It is not adjustment. Nothing here changes a measurement; it puts episodes on ONE ruler
// before they are averaged, because a mean over metres and feet is meaningless. That much
// is correct.
//
// **But this exists only because ONE dish of seventy-nine is anchored, and the risk is
// staying here.** While the bridge works there is no pressure to anchor the rest — and
// anchoring the rest is the actual goal. So: yes, the grams of the other dishes need
// sorting out, and this is what lets us get there without breaking the forecast today.
//
// ## Removal condition, stated so it can be checked rather than argued
//
// When ANCHORED episodes become the MAJORITY of the amplitude-bearing corpus, the
// direction must be REVERSED: re-learn the global coefficient in TRUE grams, and convert
// the remaining UN-anchored dishes instead. The counted path there:
//
// ```
// dextrose   ~30 entries   anchored (preset, package label)
// smoothie   ~16           anchored
// beer       ~14           anchored (provenance only)
// ─────────────────────────────────────────────────────────────
// about 60 of ~130 entries — just under half
// ```
//
// After beer the corpus is close to the flip point. What remains has no external anchor
// without weighing: breakfast, salad, pancakes, soup, berry dessert, pizza, rice,
// hummus — roughly forty more entries. `anchor` prints the current share on every run, so
// the trigger is observable and nobody has to remember it.
//
// ## The constraint that keeps the reversal cheap
//
// **This conversion must stay in ONE place per direction and must not spread.** Verified
// at the time of writing: the forward direction is [priorInDishGramSpace], called from
// exactly one site (`predictPerGramRise`); the backward direction is one line in
// `carbSensitivity`. If it spreads through the code, the reversal stops being an edit and
// becomes a project.
/**
 * The global prior expressed in the gram space THIS dish's grams live in.
 *
 * TEMPORARY — see the bridge note directly above for the removal condition.
 *
 * This is the minimal correct fix for the sag the smoothie migration exposed, and it is
 * NOT «weaken the shrinkage». `predictPerGramRise` blends `k·pooled + (1−k)·prior`. After
 * a dish is anchored its pooled per-gram is on TRUE grams while the prior is on RECORDED
 * grams that are ~1.5× over-stated, so the blend mixes two spaces and drags a true-gram
 * coefficient toward an over-stated-gram one. Measured: the smoothie's forecast fell to
 * 81% of its previous value, and «improving» the prior made it 75%.
 *
 * Converting the prior fixes the actual defect and leaves the thin-pool protection
 * exactly where it is — the pool keeps the same weight `k` it has today. Raising `k`
 * instead would remove a safety mechanism whose stated reason («never let a thin pool own
 * the amplitude of a medical forecast») is untouched by the grams becoming exact: an exact
 * denominator does not turn four observations into a statistic.
 *
 * The conversion is arithmetic, not a fudge: the prior is mmol per RECORDED gram, and for
 * an anchored dish `recorded = true / factor`, so per TRUE gram it is `prior / factor`.
 * A MIXED dish is converted by its carb-weighted mean factor, so a plate that is half
 * anchored moves half as far.
 */
fun priorInDishGramSpace(
    target: MealFingerprint,
    carbSensPerGram: Double?,
    /**
     * Are THIS dish's grams actually anchored yet?
     *
     * DEFAULT FALSE, AND THE DEFAULT IS THE POINT. A first cut keyed the conversion on
     * «an anchor EXISTS for this concept», which would have converted the prior the
     * moment the code shipped — while the notes still held RECORDED grams. The model
     * would then multiply 33 g by a true-gram prior and over-call the dish by 1.5×,
     * i.e. the mirror image of the sag it was written to fix. The conversion must follow
     * the DATA, so the caller passes the note's own provenance and nothing moves until
     * `carbs_source = 'anchor'` is actually on the row.
     */
    gramsAnchored: Boolean = false,
): Double? {
    val prior = carbSensPerGram ?: return null
    if (!gramsAnchored) return prior
    val comps = target.components.filter { it.carbGrams > 0 }
    val total = comps.sumOf { it.carbGrams }
    if (total <= 0) return prior
    val weighted = comps.sumOf { c ->
        val f = CARB_ANCHORS.firstOrNull { it.conceptId == c.conceptId }?.factor ?: 1.0
        c.carbGrams * f
    } / total
    return if (weighted <= 0) prior else prior / weighted
}
