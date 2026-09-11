/**
 * MEAL MARKS — the user's own answer to a meal the model could not account for.
 *
 * [MealDossier] carries the EVIDENCE and deliberately states no cause. This file is the
 * other half: what the user says back, and what the model is then allowed to learn from that
 * meal. It is a LAYER, never an edit — the note, its grams and the detected event stay
 * byte-identical, and a mark can be revoked. Over a three-day span three of our own
 * "corrections" were retracted, and each time the only reason that was possible is that
 * the originals were intact. A mark that rewrote the note would have destroyed that.
 *
 * ## The mark never advises and never derives grams
 *
 * "grams were more" does NOT mean "add 40%". There is no number in a mark, on purpose:
 * back-computing grams from the glucose response is circular (the coefficient came FROM
 * grams and rises), it makes the model unfalsifiable, and it kills the anomaly channel —
 * if grams are read off the line, the line can never lie. The mark says only "this
 * denominator is not trustworthy", which is a statement about the RECORD, not about the
 * body.
 *
 * ## How a mark reaches the model, and why it is a neutralisation rather than a delete
 *
 * A marked row STAYS in the corpus and is neutralised in place ([applyMarks]):
 *
 *   amplitude off -> `carbGrams = 0`   — `predictPerGramRise` skips carb-less rows and
 *                                        does not count them toward its shrinkage n
 *   shape off     -> `confidence = 0`  — `fpDonorWeight` multiplies by confidence, so the
 *                                        donor's pool weight becomes exactly 0
 *
 * Deleting the row instead would make the meal disappear from the very screen the user uses to
 * revise their own answer, which is a one-way door — the class of defect the "meal
 * continuation" row already taught us to avoid. Both conventions above are the ones those two
 * functions already document for genuinely-unknown grams and soft-confounded episodes; no
 * new mechanism is introduced, so nothing downstream has to learn about marks.
 *
 * ## Two key spaces — the trap this file has to survive, and the first version got it wrong
 *
 * The dossier's meal is keyed by its NOTE-anchored session start (`MealObservation
 * .onsetMs`); `meal_events` are keyed by the DETECTOR's onset, and the two differ by up to
 * an hour and a half.
 *
 * The first version resolved that by a WINDOW: a mark claimed any event within
 * [-30 min, +90 min], nearest wins. Its comment called that "the mirror of production's
 * note↔meal matcher", and review measured that it is not. Production picks the nearest
 * **carb note among all notes**; nearest-among-MARKED-onsets is a different function, and
 * they agree only when the true owner happens to be marked too. On this record **10 of the
 * 30 detector episodes that feed `carbSensitivity` sat inside a NEIGHBOURING meal's
 * window** — so marking one meal could silently delete a different meal's evidence. On a
 * five-meals-a-day diet that is not an edge case.
 *
 * So the window is gone. A mark is looked up by EXACT onset, and a consumer keyed on
 * detector events first resolves each event to its owning meal with [mealMarkOwners],
 * which is production's own matcher followed by the corpus's own session grouping. When an
 * event has no resolvable owner the answer is "no mark" — evidence is kept, never deleted
 * on a guess.
 */
package com.diapilot.core.analysis

/** Written into `meal_marks.author`. A mark is a human statement; nothing automated may
 *  write one, or "the user said so" stops meaning anything. */
const val MARK_AUTHOR_HUMAN = "human"

/** Production's own note↔meal merge window ([carbEpisodes]): the note that describes a
 *  detector event lies within [onset − BEFORE, onset + AFTER]. Used ONLY by
 *  [mealMarkOwners] to find an event's owning meal — never to widen a mark's reach. */
const val MEAL_NOTE_BEFORE_MS = 90L * 60_000
const val MEAL_NOTE_AFTER_MS = 30L * 60_000

/**
 * What the user says happened. These are the user's own words, not diagnoses of ours —
 * the button list is deliberately the one the user asked for, including "I don't know".
 */
enum class MarkKind(val ru: String, val emoji: String) {
    /** The plate held more than the note says. */
    GRAMS_MORE("граммов было больше", "⬆"),

    /** The plate held less than the note says. */
    GRAMS_LESS("граммов было меньше", "⬇"),

    /** A hypo was being treated. Counter-regulation, not a meal. */
    RESCUE("это было купирование", "🍬"),

    /** The trace is an instrument fault (compression, an impossible value). */
    SENSOR_LIED("сенсор врал", "📉"),

    /** Something was eaten that never reached a note. */
    ATE_MORE("съел что-то ещё", "🍴"),

    /** Activity, stress or illness acted inside the window. */
    CONTEXT("активность/стресс/болезнь", "🏃"),

    /**
     * OUR arithmetic, not the user's body — the kernel add-back or a neighbour subtraction
     * built most of this "rise".
     *
     * NOT in the brief's button list, and added because the real queue demanded it: of the
     * twelve meals the selection surfaces on the current pull, EIGHT have an add-back
     * share of 70% or more, i.e. the breach is mostly a number we computed rather than
     * glucose anyone observed. Without this chip the user's only outlets were "I don't know" or
     * "grams were more" — and the second is actively harmful there: it destroys a
     * sound amplitude observation while the actual error sits in the subtrahend, teaching
     * the corpus to trust our own kernel error and forget the data that contradicted it.
     */
    MODEL_WRONG("дело в модели — ядро/вычитание", "🧮"),

    /**
     * NOT A REFUSAL — a full answer, and the one that carries the most information about
     * our own blindness. It marks the window as NOT UNDERSTOOD, which is different from
     * "ordinary", and such a window must never be counted as a clean stretch. See
     * [MarkPolicy.understood].
     */
    UNKNOWN("не знаю", "❓"),
}

/** What class of event the meal turns out to be. */
enum class MarkClass { FOOD, RESCUE, ARTIFACT, UNKNOWN }

/**
 * What the model may still learn from a meal carrying this mark.
 *
 * @property amplitude may it teach mmol per gram (the SIZE half)?
 * @property shape may it teach onset/time-to-peak/tail (the TIMING half)?
 * @property understood is the window explained? False = it must not be counted as a clean
 *   segment, whatever else is true of it.
 * @property cls which class the meal belongs to — food, a rescue, an instrument artifact.
 */
data class MarkPolicy(
    val amplitude: Boolean,
    val shape: Boolean,
    val understood: Boolean,
    val cls: MarkClass,
) {
    /** The unmarked default: a meal teaches everything and is its own class. */
    companion object {
        val OPEN = MarkPolicy(amplitude = true, shape = true, understood = true, cls = MarkClass.FOOD)
    }
}

/**
 * THE POLICY TABLE. Read the `shape` column: it is the only non-obvious one, and it is the
 * same shape/amplitude split already settled on for the insulin side — take the SHAPE
 * where n is what matters and pin the AMPLITUDE where trust is what matters.
 *
 * "grams were more/less" invalidates a DENOMINATOR. When the food started, when it
 * peaked and whether it had a late tail are all read off the curve and do not divide by
 * grams at all, so that evidence survives intact and there is no reason to burn it. Every
 * other mark says something is wrong with the CURVE itself (an artifact, an untracked
 * driver, an unexplained window), and a curve you cannot trust teaches no timing either.
 */
fun MarkKind.policy(): MarkPolicy = when (this) {
    MarkKind.GRAMS_MORE, MarkKind.GRAMS_LESS ->
        MarkPolicy(amplitude = false, shape = true, understood = true, cls = MarkClass.FOOD)
    // A rescue is counter-regulation plus the insulin that caused the low. It is a real,
    // understood event — it simply is not food, and `carbSensitivity` has excluded the
    // class since it was written. The mark is how the user can say so for an episode
    // whose `preBg` gate missed it.
    MarkKind.RESCUE ->
        MarkPolicy(amplitude = false, shape = false, understood = true, cls = MarkClass.RESCUE)
    // Out entirely: there is no observation here, only an instrument fault.
    MarkKind.SENSOR_LIED ->
        MarkPolicy(amplitude = false, shape = false, understood = false, cls = MarkClass.ARTIFACT)
    // The user knows what happened; we do not know the denominator OR the shape, because part of
    // the rise belongs to food that has no record at all.
    MarkKind.ATE_MORE ->
        MarkPolicy(amplitude = false, shape = false, understood = true, cls = MarkClass.FOOD)
    // Explained, and by something we do not yet model as a term. Until activity IS a term
    // (see the gates-become-terms direction) the episode cannot teach food.
    MarkKind.CONTEXT ->
        MarkPolicy(amplitude = false, shape = false, understood = true, cls = MarkClass.FOOD)
    // The window is understood — nothing untracked acted, our subtrahend is simply wrong.
    // Both halves go: the recovered curve's SHAPE inherits the same bad subtraction as its
    // height, so keeping timing here would be keeping the part the user just said is ours.
    MarkKind.MODEL_WRONG ->
        MarkPolicy(amplitude = false, shape = false, understood = true, cls = MarkClass.FOOD)
    MarkKind.UNKNOWN ->
        MarkPolicy(amplitude = false, shape = false, understood = false, cls = MarkClass.UNKNOWN)
}

/**
 * One human statement about one meal.
 *
 * Append-only in the store: changing an answer REVOKES the old row and writes a new one, so
 * the sequence of what the user thought is preserved. [revokedAtMs] is what makes it reversible;
 * [author] and [createdAtMs] are the provenance that stops a mark from becoming another
 * number of unknown origin sitting in the database.
 */
data class MealMark(
    /** The meal's note-anchored session start — the key the dossier shows. */
    val onsetMs: Long,
    val kind: MarkKind,
    /** Free text. The user often remembers context the data does not hold. */
    val comment: String? = null,
    val author: String = MARK_AUTHOR_HUMAN,
    val createdAtMs: Long,
    val revokedAtMs: Long? = null,
    val id: Long = 0,
) {
    val active: Boolean get() = revokedAtMs == null
}

/**
 * The active marks, resolvable from either key space.
 *
 * Construct it from every row in the store — it keeps only the active ones, and where two
 * are somehow active for the same meal the NEWEST wins (a repair path, not an expectation).
 */
class MealMarks(all: List<MealMark>) {

    val active: List<MealMark> =
        all.filter { it.active }
            .groupBy { it.onsetMs }
            .map { (_, ms) -> ms.maxBy { it.createdAtMs } }
            .sortedBy { it.onsetMs }

    val isEmpty: Boolean get() = active.isEmpty()

    /**
     * The mark on the meal whose onset is EXACTLY [onsetMs].
     *
     * No tolerance, and that is a repair rather than a simplification — see the file
     * header. A consumer keyed on anything other than the corpus's own session start must
     * resolve the owner first ([mealMarkOwners]); guessing by proximity was measured to
     * reach a neighbour's evidence in a third of cases.
     *
     * The cost of exactness is an ORPHAN: if the session grouping later moves a boundary,
     * a mark stops matching its meal. That failure is visible (the meal returns to the
     * queue and the answer still shows under "already marked") and it errs toward keeping
     * evidence, which is the direction to fail in.
     */
    fun at(onsetMs: Long): MealMark? = active.firstOrNull { it.onsetMs == onsetMs }

    fun policyAt(onsetMs: Long): MarkPolicy = at(onsetMs)?.kind?.policy() ?: MarkPolicy.OPEN

    /** The meals the user marked "unknown" / "sensor lied" for: NOT understood, so no
     *  segment containing one may ever be counted as clean. Exposed as data because
     *  nothing consumes it yet — the segment scorer's clean-window gate is the next step,
     *  and a flag with no reader must be visibly a flag with no reader. */
    val notUnderstood: Set<Long>
        get() = active.filterNot { it.kind.policy().understood }.map { it.onsetMs }.toSet()

    /**
     * A stamp that changes whenever the answer set changes — the twin's cache key reads it,
     * so re-marking a meal rebuilds the model instead of leaving a stale one in place. An
     * EMPTY set stamps 0, which is exactly what a pre-marks snapshot restores as, so nothing
     * rebuilds until the first mark is written.
     */
    fun stamp(): Long = active.fold(0L) { acc, m ->
        acc * 31 + m.onsetMs + m.kind.ordinal * 7L + m.createdAtMs
    }

    companion object {
        val EMPTY = MealMarks(emptyList())
    }
}

/**
 * Apply the marks to a donor corpus: rows are NEUTRALISED, never dropped.
 *
 * See the file header for why. The two knobs are the ones the corpus already understands —
 * grams of 0 means «no amplitude evidence» to [predictPerGramRise], confidence of 0 means
 * «weight this donor at nothing» to [fpDonorWeight].
 */
fun List<MealObservation>.applyMarks(marks: MealMarks): List<MealObservation> {
    if (marks.isEmpty) return this
    return map { o ->
        val p = marks.policyAt(o.onsetMs)
        if (p.amplitude && p.shape) o
        else o.copy(
            carbGrams = if (p.amplitude) o.carbGrams else 0.0,
            confidence = if (p.shape) o.confidence else 0.0,
        )
    }
}

/**
 * Which meal OWNS a detector event — production's own matcher, then the corpus's own
 * session grouping, and nothing invented in between.
 *
 * Step 1 is `carbEpisodes`' rule verbatim: the nearest carb-bearing, non-context note
 * within [onset − 90 min, onset + 30 min]. Step 2 is `deconvolvedMealObservations`' rule
 * verbatim: that note's session, whose start IS the corpus onset a mark is keyed by. Both
 * halves are copied from the code that defines them rather than approximated, because an
 * approximation here deletes the wrong meal's evidence.
 *
 * Returns null when no note owns the event, or when the owning note is not one the
 * deconvolution anchors on (a `kind != "food"` note carrying grams qualifies for
 * `carbEpisodes` and not for the corpus). Null means "no mark applies" — evidence kept.
 */
fun mealMarkOwners(notes: List<com.diapilot.core.collector.Annotation>): (Long) -> Long? {
    val sessions = groupMealSessions(deconvolutionFoodNotes(notes))
    val startByNoteId: Map<Long, Long> = buildMap {
        sessions.forEach { s -> s.notes.forEach { put(it.id, s.startMs) } }
    }
    val carbNotes = notes.filter { it.estCarbs != null && !isContextNote(it.content) }
    return { eventMs ->
        carbNotes
            .filter { it.tsMs in (eventMs - MEAL_NOTE_BEFORE_MS)..(eventMs + MEAL_NOTE_AFTER_MS) }
            .minByOrNull { kotlin.math.abs(it.tsMs - eventMs) }
            ?.let { startByNoteId[it.id] }
    }
}

/**
 * Drop the marked episodes from the DETECTOR-keyed carb-sensitivity learner.
 *
 * Here a drop is right where a neutralisation was right above: [carbSensitivity] reduces
 * each episode to one ratio and has no weight to turn down, and the episode list is not
 * what any screen renders, so nothing becomes unreachable.
 *
 * @param ownerOf from [mealMarkOwners]. Required rather than defaulted: a caller that
 *   forgot it would silently key detector onsets against note-anchored marks and match
 *   almost nothing, which is a silent miss rather than a compile error.
 */
fun List<CarbEpisode>.excludingMarked(
    marks: MealMarks,
    ownerOf: (Long) -> Long?,
): List<CarbEpisode> {
    if (marks.isEmpty) return this
    return filter { e -> ownerOf(e.onsetMs)?.let { marks.policyAt(it).amplitude } ?: true }
}

/**
 * Drop the marked onsets from a label/dish profile.
 *
 * This is the branch that matters most in practice: a dish the user eats regularly is
 * forecast from its own measured curve, so a marked meal left in `dishCurves` keeps
 * teaching the dish even after the user has said the record is wrong. The gate is
 * [MarkPolicy.shape] — a dish curve is a SHAPE, and a gram error does not corrupt one.
 *
 * These are DETECTOR onsets (`labeledMeals`), so they take the same owner resolution as
 * the episodes above. Review measured only one disagreement in 122 here — but "rare" is
 * not "right", and a second resolution rule is how the two branches start to diverge.
 */
fun List<Long>.onsetsAllowedForShape(
    marks: MealMarks,
    ownerOf: (Long) -> Long?,
): List<Long> {
    if (marks.isEmpty) return this
    return filter { onset -> ownerOf(onset)?.let { marks.policyAt(it).shape } ?: true }
}
