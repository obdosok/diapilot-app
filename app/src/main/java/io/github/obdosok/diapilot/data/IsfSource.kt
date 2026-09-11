package io.github.obdosok.diapilot.data

import android.content.Context

/**
 * WHICH OF THE TWO ISF NUMBERS IS IN FORCE — one switch, named, with a default.
 *
 * The point of this file is that the question has exactly ONE answer and one
 * place to read it. Three separate hand-ISF stores were collapsed into P1
 * for that reason, and adding an automatic learner beside it would
 * have re-opened the same hole — "what is applied" answerable only by tracing
 * which layer ran last — if the choice were implied by which store happened to
 * be non-empty rather than stated.
 *
 * Default is [Choice.MANUAL], deliberately. A walk-forward evaluation can show
 * the adaptive fit scoring better on some metrics — but a measurement that
 * favours a change is not permission to make it on the user's device while
 * they sleep. The user picks.
 */
object IsfSource {

    enum class Choice { MANUAL, ADAPTIVE }

    private const val PREFS = "isf_source_v1"
    private const val KEY = "choice"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun choice(context: Context?): Choice {
        val raw = context?.let { prefs(it).getString(KEY, null) } ?: return Choice.MANUAL
        return runCatching { Choice.valueOf(raw) }.getOrDefault(Choice.MANUAL)
    }

    fun set(context: Context, choice: Choice) {
        prefs(context).edit().putString(KEY, choice.name).apply()
    }

    /**
     * The adaptive number, but only when it is both CHOSEN and AVAILABLE.
     *
     * Returning null when the fit has not run (or refused for want of episodes)
     * is what makes the switch safe to flip early: the model then keeps the hand
     * value rather than falling back to some third thing, and the card says so.
     */
    fun adaptiveInForce(context: Context?): Double? {
        if (context == null || choice(context) != Choice.ADAPTIVE) return null
        return AdaptiveIsfRuntime.state(context)?.isf
    }
}
