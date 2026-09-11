package com.example.diapilot.data

import android.content.Context
import com.diapilot.core.analysis.FoodPhysicalFormV2
import com.diapilot.core.analysis.FoodStructureAcceptanceV1
import com.diapilot.core.analysis.isFoodNote
import org.json.JSONObject

/**
 * Loads the bundled structure proposal and applies an ACCEPTED one.
 *
 * `no auto-teach`: nothing here runs on its own. [accept] is called from a tap,
 * and until then the proposal is inert text in an asset.
 *
 * The backfill ADDS a line to each matching note rather than rewriting it, and
 * `setAnnotationAnalysis` stamps `analysis_known_at_ms` — so a causal replay
 * as-of an earlier instant still does not see the structure. That is what keeps
 * today's acceptance from answering yesterday's forecast, and it is why this is
 * a revision rather than a correction of the record.
 */
object FoodStructureProposalRuntime {

    const val ASSET = "models/food_structure_proposal_v1.json"

    data class Row(
        val proposed: FoodStructureAcceptanceV1.Proposed,
        val previewNow: String,
        val previewThen: String,
        val matched: Int,
        val alreadyAccepted: Int,
    ) {
        val pending: Int get() = matched - alreadyAccepted
    }

    private data class ParsedDish(
        val proposed: FoodStructureAcceptanceV1.Proposed,
        val previewNow: String,
        val previewThen: String,
        val intakes: Int,
        val typicalCarbsG: Double?,
    )

    private fun parse(context: Context): List<ParsedDish> {
        val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val dishes = JSONObject(text).getJSONArray("dishes")
        return (0 until dishes.length()).map { i ->
            val d = dishes.getJSONObject(i)
            val aliases = d.getJSONArray("aliases")
            ParsedDish(
                proposed = FoodStructureAcceptanceV1.Proposed(
                    id = d.getString("id"),
                    title = d.getString("title"),
                    aliases = (0 until aliases.length()).map(aliases::getString),
                    form = FoodPhysicalFormV2.valueOf(d.getString("form")),
                    fast = d.getDouble("fast"), medium = d.getDouble("medium"), slow = d.getDouble("slow"),
                    fiberG = d.optDouble("fiber_g").takeIf { !it.isNaN() },
                    proteinG = d.optDouble("protein_g").takeIf { !it.isNaN() },
                    fatG = d.optDouble("fat_g").takeIf { !it.isNaN() },
                    confidence = d.getDouble("confidence"),
                    alcohol = d.optBoolean("alcohol", false),
                    intakeDurationMin = d.optDouble("intake_duration_min").takeIf { !it.isNaN() && it > 0.0 },
                    blind = d.optBoolean("blind", true),
                    why = d.optString("why", ""),
                ),
                previewNow = d.optString("preview_now", "—"),
                previewThen = d.optString("preview_then", "—"),
                intakes = d.optInt("intakes", 0),
                typicalCarbsG = d.optDouble("carbs_g").takeIf { !it.isNaN() && it > 0.0 },
            )
        }
    }

    /**
     * F-05: the dishes the composer RECOGNISES — the asset rows with the
     * aliases the user has confirmed since (layer 3) merged in. Learned aliases
     * append after the asset's, so `aliases.first()` stays the canonical
     * wording that [accept] writes to the library.
     */
    fun knownDishes(context: Context): List<com.diapilot.core.analysis.DishRecognitionV1.KnownDish> {
        val learned = DishAliasRuntime.learned(context)
        return parse(context).map { row ->
            com.diapilot.core.analysis.DishRecognitionV1.KnownDish(
                proposed = row.proposed.copy(
                    aliases = row.proposed.aliases +
                        learned[row.proposed.id].orEmpty()
                            .filterNot { w -> row.proposed.aliases.any { it.equals(w, true) } },
                ),
                intakes = row.intakes,
                typicalCarbsG = row.typicalCarbsG,
            )
        }
    }

    /**
     * Dish ids the user has ACCEPTED at least once — read off the record
     * itself, not a flag, so the state cannot drift from the notes. Only these
     * may match without a question: for an unaccepted dish even an exact alias
     * is still a proposal, and applying it silently would be auto-teach.
     */
    fun acceptedDishIds(store: SqliteCollectorStore): Set<String> =
        store.annotations(0, Long.MAX_VALUE)
            .mapNotNullTo(mutableSetOf()) { FoodStructureAcceptanceV1.acceptedDishId(it.analysis) }

    /** What accepting would touch, counted BEFORE the user taps — informed
     *  consent is the whole point of the screen, and "applies to N notes" is
     *  the fact that makes it informed. */
    fun rows(context: Context, store: SqliteCollectorStore): List<Row> {
        val notes = store.annotations(0, Long.MAX_VALUE).filter(::isFoodNote)
        // Learned aliases (layer 3) participate in the match: a wording the
        // user confirmed IS this dish, so the backfill counter must see it too.
        val merged = knownDishes(context)
        return parse(context).map { row ->
            val p = merged.firstOrNull { it.proposed.id == row.proposed.id }?.proposed ?: row.proposed
            val mine = notes.filter { FoodStructureAcceptanceV1.matches(p, it.content) }
            Row(
                proposed = p, previewNow = row.previewNow, previewThen = row.previewThen,
                matched = mine.size,
                // Counted by CONTENT, exactly as the write decides — see
                // FoodStructureAcceptanceV1.carriesStructure. Counting by tag
                // made a revised proposal look already-applied.
                alreadyAccepted = mine.count { FoodStructureAcceptanceV1.carriesStructure(p, it.analysis) },
            )
        }
    }

    /** Returns how many notes were revised. Idempotent: a second tap is a no-op. */
    fun accept(context: Context, store: SqliteCollectorStore, id: String): Int {
        val proposed = knownDishes(context).firstOrNull { it.proposed.id == id }?.proposed ?: return 0
        var revised = 0
        for (note in store.annotations(0, Long.MAX_VALUE).filter(::isFoodNote)) {
            if (!FoodStructureAcceptanceV1.matches(proposed, note.content)) continue
            val next = FoodStructureAcceptanceV1.analysisAfterAccept(proposed, note.analysis) ?: continue
            store.setAnnotationAnalysis(note.id, next)
            revised++
        }
        // Forward as well as back: the same structure rides the next note of this
        // dish through the library the composer already reuses.
        //
        // Every OTHER field of the entry is carried through untouched. Writing
        // nulls here would silently drop the grams and the composition already
        // saved for the item — a known bottled drink might carry 18 g and a
        // component split — and an acceptance that quietly deletes recorded
        // facts is not an addition.
        val canonical = proposed.aliases.firstOrNull() ?: proposed.title
        runCatching {
            val existing = store.foodLibrary().firstOrNull { it.name.trim().equals(canonical.trim(), true) }
            store.upsertFoodLibrary(
                name = existing?.name ?: canonical,
                grams = existing?.grams,
                comment = existing?.comment,
                mediaRef = existing?.mediaRef,
                analysis = FoodStructureAcceptanceV1.analysisAfterAccept(proposed, existing?.analysis)
                    ?: existing?.analysis,
                components = existing?.components ?: emptyList(),
            )
        }
        return revised
    }
}
