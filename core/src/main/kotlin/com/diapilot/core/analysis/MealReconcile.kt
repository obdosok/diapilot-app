/**
 * Reconcile the two representations of "what was eaten" that drift apart:
 * the food NOTE (what you typed/renamed) and the detected EPISODE's label
 * (what the model learns). Editing a note's text used to leave the episode
 * on its old label — the library then showed a "not eaten" message and the model
 * learned the wrong dish. This computes the fixes as a reviewable PLAN; nothing is
 * applied here (the UI shows a before/after comparison and the user approves).
 *
 * Linking is DIRECTIONAL and nearest-wins to avoid false matches: a rise is
 * caused by food eaten shortly BEFORE its onset, so a note owns the episode
 * whose onset falls just after the note — a note written at 22:14 does not own
 * a dinner rise that started at 20:00.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.LabeledMeal

data class LabelFix(
    val onsetMs: Long,
    val labelId: Long,
    val from: String,   // current episode label
    val to: String,     // the note's (own) name it should carry
    val noteTsMs: Long,
)

/** Slight pre-onset slack (detection jitter) … up to a 2 h absorption lag. */
private const val LINK_BEFORE_MS = 15L * 60_000
private const val LINK_AFTER_MS = 120L * 60_000

/** Word stems (5-char prefixes) — tolerant of Russian declension so
 *  singular and plural forms of a dish name match. Short connectors
 *  ("from"/"with"/"and") are dropped. */
private fun stems(name: String): Set<String> =
    name.split(" ").filter { it.length >= 4 }.map { it.take(5) }.toSet()

/**
 * Same dish? Only a RENAME/canonicalization of one dish is auto-proposed
 * (shared stem or containment) — never a re-identification to an unrelated
 * food. This is what keeps "breakfast"→"scramble" (a component) and "sport"→
 * "dextrose" (a neighbour) out of the plan; "berry bun"→"quarter berry bun"
 * and "Magnum ice cream"→"ice cream" stay in.
 */
private fun sameDish(a: String, b: String): Boolean =
    a.contains(b) || b.contains(a) || (stems(a) intersect stems(b)).isNotEmpty()

/**
 * Episodes whose label disagrees with the food note that owns them.
 * [notes] = all annotations (food ones are used); [labeled] = labeled meals.
 * One fix per episode (the nearest owning note wins).
 */
fun reconcileLabels(
    notes: List<Annotation>,
    labeled: List<LabeledMeal>,
): List<LabelFix> {
    val eps = labeled.sortedBy { it.event.onsetMs }
    val foodNotes = notes.filter {
        it.kind == "food" && it.content.isNotBlank() &&
            !isContextNote(it.content) &&
            normalizeFoodName(it.content).let { n -> n.isNotEmpty() && n !in SysLabels.ALL }
    }
    // Assign each food note to the episode it most likely caused (nearest in
    // the directional window). Then an episode is UNAMBIGUOUSLY owned only when
    // exactly ONE note assigned to it — several notes on one rise means a
    // COMPOSITE meal (breakfast = hummus+scramble+bread), which must NOT be relabeled
    // to a single component. This precision kills the bulk of false proposals.
    val assigned = HashMap<Long, MutableList<Annotation>>()  // onset -> notes
    val epByOnset = eps.associateBy { it.event.onsetMs }
    for (note in foodNotes) {
        val cand = eps
            .filter { it.event.onsetMs in (note.tsMs - LINK_BEFORE_MS)..(note.tsMs + LINK_AFTER_MS) }
            .minByOrNull { kotlin.math.abs(it.event.onsetMs - note.tsMs) }
            ?: continue
        assigned.getOrPut(cand.event.onsetMs) { mutableListOf() }.add(note)
    }
    val fixes = mutableListOf<LabelFix>()
    for ((onset, ns) in assigned) {
        if (ns.size != 1) continue  // ambiguous / composite → leave it
        val ep = epByOnset[onset] ?: continue
        val note = ns[0]
        val cur = normalizeFoodName(ep.labelName)
        val want = normalizeFoodName(note.content)
        if (cur == want || want.isEmpty()) continue
        if (!sameDish(cur, want)) continue  // only same-dish renames, never re-ID
        fixes.add(
            LabelFix(
                onsetMs = onset,
                labelId = ep.labelId,
                from = ep.labelName,
                to = note.content.trim(),
                noteTsMs = note.tsMs,
            ),
        )
    }
    return fixes.sortedByDescending { it.onsetMs }
}
