package com.example.diapilot.data

import android.content.Context
import com.diapilot.core.analysis.DishRecognitionV1
import com.diapilot.core.analysis.FoodStructureAcceptanceV1
import com.diapilot.core.collector.CollectorStore
import com.example.diapilot.collect.TreatmentsPollWorker
import org.json.JSONArray
import org.json.JSONObject

/**
 * F-05, the dialogue: the composer's questions are MOVES IN A CONVERSATION,
 * not modal windows.
 *
 * The four constraints from the registry, honored by construction:
 *  1. SAVING DOES NOT WAIT. The note is written by the ordinary submit path
 *     before any move is posted; a move only APPENDS a revision on "yes", and
 *     `setAnnotationAnalysis` stamps `analysis_known_at_ms`, so a causal
 *     replay as-of an earlier instant still does not see the confirmed
 *     structure (constraint 4 — provenance survives).
 *  2. No dosing talk lives here at all: a move's text comes from
 *     [DishRecognitionV1.question] / the assumption field, both about FOOD.
 *  3. One pointed question per note. [ask] refuses a second move for the same
 *     note, and a rejected wording is remembered so "no" is said once.
 *
 * Moves survive process death via prefs — a question asked at dinner may be
 * answered after the dishes.
 */
object DishDialogRuntime {

    /** One pending conversational move. [kind] is "dish" (is this your X?) or
     *  "assumption" (what kind of bread?). */
    data class Move(
        val tsMs: Long,
        val noteContent: String,
        val dishId: String,
        val question: String,
        val kind: String = KIND_DISH,
        val createdMs: Long,
    )

    const val KIND_DISH = "dish"
    const val KIND_ASSUMPTION = "assumption"

    private const val PREF = "dish_dialog_moves"
    val moves = androidx.compose.runtime.mutableStateListOf<Move>()
    private var loaded = false

    // ---- pure codec --------------------------------------------------------

    fun encode(list: List<Move>): String {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put("ts", m.tsMs).put("note", m.noteContent).put("dish", m.dishId)
                    .put("q", m.question).put("kind", m.kind).put("created", m.createdMs),
            )
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<Move> = try {
        raw?.let { JSONArray(it) }?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Move(
                    tsMs = o.getLong("ts"), noteContent = o.getString("note"),
                    dishId = o.getString("dish"), question = o.getString("q"),
                    kind = o.optString("kind", KIND_DISH), createdMs = o.optLong("created"),
                )
            }
        }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    // ---- state -------------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        moves.addAll(decode(prefs(context).getString(PREF, null)))
    }

    private fun persist(context: Context) {
        prefs(context).edit().putString(PREF, encode(moves.toList())).apply()
    }

    /** Post a move. One per note: a second question for the same note is
     *  refused — the dialogue asks, it does not interview. */
    fun ask(context: Context, move: Move) {
        load(context)
        if (moves.any { it.tsMs == move.tsMs && it.kind == move.kind }) return
        moves.add(move)
        while (moves.size > 8) moves.removeAt(0)
        persist(context)
    }

    private fun remove(context: Context, move: Move) {
        moves.removeAll { it.tsMs == move.tsMs && it.kind == move.kind }
        persist(context)
    }

    /**
     * "Yes" on a dish move: append the accepted structure to the saved note
     * (a REVISION — the note's earlier analysis stays readable and the stamp
     * records when this became known), and learn the wording as an alias
     * (layer 3). Returns true when a note was actually revised.
     */
    fun confirmDish(
        context: Context,
        store: CollectorStore,
        move: Move,
        dishes: List<DishRecognitionV1.KnownDish> = FoodStructureProposalRuntime.knownDishes(context),
    ): Boolean {
        val dish = dishes.firstOrNull { it.proposed.id == move.dishId } ?: return false
        val note = store.annotations(move.tsMs - 60_000L, move.tsMs + 60_000L)
            .firstOrNull { it.content == move.noteContent }
        var revised = false
        if (note != null) {
            FoodStructureAcceptanceV1.analysisAfterAccept(dish.proposed, note.analysis)?.let {
                store.setAnnotationAnalysis(note.id, it)
                revised = true
            }
        }
        DishAliasRuntime.addAlias(context, move.dishId, move.noteContent)
        remove(context, move)
        return revised
    }

    /** "No": drop the move and remember the rejection, so this wording never
     *  re-asks this dish. */
    fun rejectDish(context: Context, move: Move) {
        DishAliasRuntime.reject(context, move.dishId, move.noteContent)
        remove(context, move)
    }

    /** Dismiss without teaching anything (the (X) path — "not now"). */
    fun dismiss(context: Context, move: Move) = remove(context, move)

    /**
     * F-05 layer 5, the answer path: the clarification is APPENDED to the note
     * (a dated revision — "CLARIFICATION: [component] answer") and stored in the
     * food library under the component's name, so the next parse sees it in
     * the known-components block and the question is never asked again.
     *
     * For a dish-level assumption (no component) the note still gets the
     * clarification line; there is nothing durable to attach it to, and
     * inventing a library entry for a whole meal would be a guess of our own.
     */
    fun answerAssumption(context: Context, store: CollectorStore, move: Move, answer: String): Boolean {
        // Flattened: a typed newline must not become a machine line in the
        // note — and this path also writes the library comment, which rides
        // back into the next prompt.
        val a = answer.replace('\n', ' ').replace('\r', ' ')
            .replace(Regex("""\s{2,}"""), " ").trim()
        if (a.isEmpty()) return false
        val note = store.annotations(move.tsMs - 60_000L, move.tsMs + 60_000L)
            .firstOrNull { it.content == move.noteContent }
        var revised = false
        if (note != null) {
            val component = move.dishId.takeIf { it.isNotBlank() }?.let { "[$it] " } ?: ""
            val line = "${com.diapilot.core.analysis.CLARIFICATION_LINE_PREFIX}: $component$a"
            store.setAnnotationAnalysis(note.id, (note.analysis?.trimEnd().orEmpty() + "\n" + line).trim())
            revised = true
        }
        val component = move.dishId.takeIf { it.isNotBlank() }
        if (component != null) {
            runCatching {
                val sqlite = store as SqliteCollectorStore
                val existing = sqlite.foodLibrary()
                    .firstOrNull { it.name.trim().equals(component.trim(), true) }
                val comment = listOfNotNull(
                    existing?.comment?.takeIf { it.isNotBlank() && !it.contains(a, true) }, a,
                ).joinToString("; ")
                sqlite.upsertFoodLibrary(
                    name = existing?.name ?: component,
                    grams = existing?.grams,
                    comment = comment,
                    mediaRef = existing?.mediaRef,
                    analysis = existing?.analysis,
                    components = existing?.components ?: emptyList(),
                )
            }
        }
        remove(context, move)
        return revised
    }
}
