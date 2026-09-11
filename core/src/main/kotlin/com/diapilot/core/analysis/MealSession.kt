/**
 * Meal sessions: several food notes logged close together are ONE meal —
 * a starter photographed a minute before the main course, a dessert twenty
 * minutes after. Derived on the fly from note timestamps (no schema), so the
 * whole history regroups retroactively when the rules improve.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation

/** Rescue carbs are food, but merging them into a meal misstates both. */
const val RESCUE_NOTE_PREFIX = "декстроза"

/** A note that names something eaten (vs a context tag or system label). */
fun isFoodNote(n: Annotation): Boolean =
    n.content.isNotBlank() &&
        (n.kind == "food" || n.estCarbs != null) &&
        !isContextNote(n.content) &&
        n.content.lowercase() !in SysLabels.ALL

data class MealSession(
    val notes: List<Annotation>,   // ≥1, ascending by time
) {
    val startMs: Long get() = notes.first().tsMs
    val endMs: Long get() = notes.last().tsMs
    val isComposite: Boolean get() = notes.size > 1

    /** Sum of known grams; null when no part has an estimate. */
    val totalCarbs: Double? get() =
        notes.mapNotNull { it.estCarbs }.takeIf { it.isNotEmpty() }?.sum()

    /** "soup + meatballs" — the session as one dish name. */
    val composedName: String get() =
        notes.map { it.content.trim() }.distinct().joinToString(" + ")
}

/**
 * Cluster food notes into sessions by adjacent gap: a note within [gapMs]
 * of the previous one continues the same meal. Rescue dextrose never joins
 * a meal — treating a hypo mid-dinner is not a course of the dinner.
 */
fun groupMealSessions(
    notes: List<Annotation>,
    gapMs: Long = 45L * 60_000,
    /**
     * FLOATING GAP. When set, a note continues the previous meal for as long as
     * that meal is still delivering, instead of a flat 45 minutes.
     *
     * The user's rule: if we know a smoothie finished absorbing in 60 minutes, it
     * should not still be interfering with breakfast 65 minutes after the
     * smoothie. Measured on the record, the flat gap joins 32 of 176 adjacent
     * pairs while 111 more fall between 45 and 300 minutes — the previous meal
     * still emptying, the pair scored as independent, and the follower learning
     * its amplitude off a curve that still carries the earlier food.
     *
     * NULL BY DEFAULT, so every existing caller keeps today's behaviour exactly
     * and the change can be measured against it rather than assumed.
     *
     * The extent is measured from the SESSION SO FAR, not from its last note:
     * a meal that has been accumulating calories keeps delivering from its own
     * start, and asking only the latest bite would let a long dinner be closed
     * by a small final course.
     */
    extentMinOf: ((List<Annotation>) -> Double)? = null,
): List<MealSession> {
    val food = notes.filter(::isFoodNote).sortedBy { it.tsMs }
    if (food.isEmpty()) return emptyList()
    val sessions = mutableListOf<MutableList<Annotation>>()
    food.forEach { n ->
        val rescue = n.content.startsWith(RESCUE_NOTE_PREFIX, ignoreCase = true)
        val cur = sessions.lastOrNull()
        val curRescue = cur?.last()?.content?.startsWith(RESCUE_NOTE_PREFIX, ignoreCase = true)
        val reachMs = if (cur == null || extentMinOf == null) gapMs else {
            val fromStart = (extentMinOf(cur) * 60_000).toLong() - (cur.last().tsMs - cur.first().tsMs)
            maxOf(gapMs, fromStart)
        }
        if (cur != null && n.tsMs - cur.last().tsMs <= reachMs && rescue == curRescue) {
            cur.add(n)
        } else {
            sessions.add(mutableListOf(n))
        }
    }
    return sessions.map { MealSession(it) }
}

/** The session containing [noteId], or null. */
fun sessionOf(sessions: List<MealSession>, noteId: Long): MealSession? =
    sessions.firstOrNull { s -> s.notes.any { it.id == noteId } }
