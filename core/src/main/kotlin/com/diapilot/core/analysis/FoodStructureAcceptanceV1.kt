package com.diapilot.core.analysis

/**
 * Accepting a proposed food structure — the `no auto-teach` boundary.
 *
 * A suggestion becomes a stored fact only when the user taps accept, so the
 * decision logic lives here where a test can hold it, and the screen only calls
 * it. Two properties matter more than the UI:
 *
 * 1. **Backfill ADDS, it does not rewrite.** The accepted line is appended to
 *    the note's existing analysis, and [explicitFoodKineticsV2] reads the LAST
 *    such line — so the previous content stays readable and "as it was before
 *    acceptance" is recoverable by dropping the appended line. The whole record
 *    of past numbers was computed against the old text; destroying it would make
 *    every earlier measurement unreproducible.
 * 2. **The moment of acceptance is recorded**, because `setAnnotationAnalysis`
 *    stamps `analysis_known_at_ms`. A causal replay as-of an earlier instant
 *    still does not see the structure, which is what keeps a backfill from
 *    letting today's knowledge answer yesterday's forecast.
 *
 * Matching past notes is by ALIAS, i.e. by name — the one place a name is
 * allowed, because here the user is personally confirming "these notes are
 * this dish". Nothing name-derived enters the model: what is written is the
 * structure, and the count of affected notes is shown before the user taps.
 */
object FoodStructureAcceptanceV1 {

    const val ACCEPTED_PROVENANCE = "accepted-v1"

    /**
     * The provenance an accepted note carries: the tag AND which dish it is.
     *
     * F-04, measured. Without the id the only thing left to identify
     * a dish by was `DishIdentityV1` (later deleted with the learned-dish
     * timing layer), whose macro coordinates were RATIOS to the
     * note's own carbohydrate — so the same accepted dish at 50 g and at 45 g
     * produced p5|f6 and p6|f7 and pooled as two dishes. A five-gram difference
     * in portion size stopped the model from recognising a regularly-eaten dish;
     * the pool missed the n>=3 bar by exactly one.
     *
     * An accepted dish does not need to be re-derived: the user already said
     * which dish it is. Ratios remain the right key for everything NOT accepted.
     */
    fun acceptedProvenance(id: String): String = "$ACCEPTED_PROVENANCE:$id"

    /**
     * The dish id inside an already-parsed provenance string
     * ("accepted-v1:smoothie" → "smoothie"), or null for any other provenance.
     *
     * Exists so a causally-built event can answer for its dish WITHOUT
     * re-reading the note — the forecast path holds features parsed as-of the
     * anchor, and going back to the note would quietly ignore that cutoff.
     */
    fun dishIdFromProvenance(provenance: String?): String? {
        val marker = "$ACCEPTED_PROVENANCE:"
        if (provenance == null || !provenance.startsWith(marker)) return null
        return provenance.removePrefix(marker)
            .takeWhile { it != ';' && !it.isWhitespace() }
            .takeIf { it.isNotBlank() }
    }

    /** The accepted dish id carried by this analysis, if any. */
    fun acceptedDishId(analysis: String?): String? {
        val line = analysis?.lineSequence()?.lastOrNull {
            it.trim().startsWith("$FOOD_KINETICS_PREFIX_V2:", true)
        } ?: return null
        val marker = "source=$ACCEPTED_PROVENANCE:"
        val at = line.indexOf(marker)
        if (at < 0) return null
        return line.substring(at + marker.length).takeWhile { it != ';' && !it.isWhitespace() }
            .takeIf { it.isNotBlank() }
    }

    data class Proposed(
        val id: String,
        val title: String,
        val aliases: List<String>,
        val form: FoodPhysicalFormV2,
        val fast: Double,
        val medium: Double,
        val slow: Double,
        val fiberG: Double?,
        val proteinG: Double?,
        val fatG: Double?,
        val confidence: Double,
        val alcohol: Boolean = false,
        val intakeDurationMin: Double? = null,
        val blind: Boolean = true,
        val why: String = "",
    ) {
        fun features(): FoodKineticFeaturesV2 = FoodKineticFeaturesV2(
            fastFraction = fast, mediumFraction = medium, slowFraction = slow,
            physicalForm = form, fiberG = fiberG, confidence = confidence,
            provenance = acceptedProvenance(id), alcoholPresent = alcohol,
            proteinG = proteinG, fatG = fatG,
        )
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^а-яёa-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun matches(proposed: Proposed, noteText: String): Boolean {
        val n = normalize(noteText)
        return proposed.aliases.any { normalize(it) == n }
    }

    /**
     * True when the note already carries EXACTLY this proposal's structure.
     *
     * The counter behind the accept button must ask the same question the write
     * asks, or the screen reports "already accepted" for a note that would in fact
     * change — which is what happened once: [analysisAfterAccept] was made
     * idempotent by CONTENT while this stayed idempotent by TAG, so five
     * recomputed dishes showed "0 to change" and a dead button. The user
     * tapped and nothing happened.
     */
    fun carriesStructure(proposed: Proposed, analysis: String?): Boolean {
        val current = analysis?.lineSequence()?.lastOrNull {
            it.trim().startsWith("$FOOD_KINETICS_PREFIX_V2:", true)
        }?.trim() ?: return false
        return current == foodKineticsLineV2(proposed.features()).trim()
    }

    /** True when this note already carries SOME accepted structure with a dish
     *  id — the generation check, not the content check. Use
     *  [carriesStructure] when the question is «would accepting change this». */
    fun isAccepted(analysis: String?): Boolean {
        val line = analysis?.lineSequence()?.lastOrNull {
            it.trim().startsWith("$FOOD_KINETICS_PREFIX_V2:", true)
        } ?: return false
        return "source=$ACCEPTED_PROVENANCE:" in line
    }

    /**
     * The note's analysis after acceptance. Returns null when nothing would
     * change, so a second tap is a no-op rather than a duplicated line — the
     * screen can be re-opened without growing the record.
     */
    fun analysisAfterAccept(proposed: Proposed, analysis: String?): String? {
        // IDEMPOTENT BY CONTENT, not by the tag. Guarding on "is it accepted at
        // all" meant a REVISED proposal could never reach a note that had taken
        // the old one — found when five composite dishes were
        // recomputed under the v3 prompt (ice cream's carb speed 0.50 -> 1.00,
        // its form SOFT_SOLID -> PUREE, which its own description had said all
        // along) and nothing would have applied. A second tap with the SAME
        // proposal is still a no-op, which is what the guard was for.
        val next = foodKineticsLineV2(proposed.features())
        val current = analysis?.lineSequence()?.lastOrNull {
            it.trim().startsWith("$FOOD_KINETICS_PREFIX_V2:", true)
        }?.trim()
        if (current == next.trim()) return null
        val out = StringBuilder(analysis?.trimEnd().orEmpty())
        fun line(text: String) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(text)
        }
        line(next)
        // Intake spread is a separate fact and the note may already carry its
        // own; never overwrite a duration the user actually recorded with a median.
        val duration = proposed.intakeDurationMin?.takeIf { it > 0.0 }
        if (duration != null &&
            out.lineSequence().none { it.trim().startsWith("META_DURATION_MIN:", true) }
        ) line("META_DURATION_MIN: $duration")
        if (proposed.alcohol &&
            out.lineSequence().none { it.trim().startsWith("$ALCOHOL_LINE_PREFIX_V2:", true) }
        ) line("$ALCOHOL_LINE_PREFIX_V2: true")
        return out.toString()
    }
}
