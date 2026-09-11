package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.analysis.MeasurementStreamCore
import com.diapilot.core.physio.DailyBalanceIsfV1

/**
 * THE APP LEARNS ITS OWN ISF, DAILY, FROM THE DAY'S BALANCE.
 *
 * Requested by the user — "let the app learn and pick reasonable values on its
 * own" — after the alternative was measured and found wanting: the correction
 * corpus needs a label added by hand, a review workflow that was used only
 * briefly and then abandoned, and it still produces a materially worse fit than
 * a hand-set ISF on every column.
 *
 * This path needs none of that. Over each of the last
 * [DailyBalanceIsfV1.WINDOW_DAYS] days it solves `carbs x CS - units x ISF = dG`
 * and takes the median. The evidence is every day of eating and injecting, not
 * the handful of doses someone thought to label.
 *
 * ⚠ IT IS NOT A REPLAY, AND THIS HEADER SAID OTHERWISE FOR A DAY. The first
 * version swept fifteen candidate ISFs over every episode in the window and kept
 * the one that would have made those forecasts right; it was replaced the same
 * afternoon (see [refit]) and the header describing it survived the code. The
 * distinction matters for reading the number: a balance integrates over the day,
 * so it is immune to a wrong insulin SHAPE, which the replay was not.
 *
 * See [DailyBalanceIsfV1] for the walk-forward that justifies it — including the
 * one column that could have vetoed it (missed real hypos, flat at 2 across
 * every arm) and the two honest limits on that column.
 */
object AdaptiveIsfRuntime {

    private const val PREFS = "adaptive_isf_v1"
    private const val KEY_ISF = "isf"
    private const val KEY_DAYS = "days"
    /** Pre-rename key, read once so an existing fit does not display as "0 days". */
    private const val KEY_DAYS_LEGACY = "episodes"
    private const val KEY_AT = "fitted_at_ms"
    private const val KEY_FROM = "window_from_ms"
    private const val KEY_TO = "window_to_ms"
    private const val KEY_RECIPE = "recipe"

    /**
     * WHAT THIS FIT WAS COOKED FROM.
     *
     * A daily throttle expires a fit by AGE, and that is not the only way one
     * goes stale: change the window, the candidate grid or the episode gates and
     * yesterday's number is no longer an answer to today's question. It stayed
     * on screen beside the hand-set value looking current — a live check caught
     * this when the quiet-before gate was changed and the card kept showing a
     * number fitted under the old value.
     */
    private fun recipe(): String =
        "w=${DailyBalanceIsfV1.WINDOW_DAYS}" +
            "/q=${DailyBalanceIsfV1.BOUNDARY_HOUR}" +
            "/n=${DailyBalanceIsfV1.MIN_DAYS}" +
            "/cs=balance-v1"

    /**
     * Daily. The window moves a day at a time, so a second fit inside one day
     * re-derives the same number at the cost of a full replay.
     */
    private const val REFIT_INTERVAL_MS = 24L * 3_600_000

    data class State(
        val isf: Double,
        /**
         * DAYS that carried a balance, not episodes.
         *
         * It was called `episodes` until this fit replaced the replay it came
         * from — while holding `Result.days`. The card printed "days" off a
         * field named `episodes`, which is the shape of a number that later
         * gets quoted wrong.
         */
        val days: Int,
        val fittedAtMs: Long,
        val windowFromMs: Long,
        val windowToMs: Long,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The one place the fit is written down.
     *
     * `internal` rather than private so a test can put a known fit in place and
     * assert it reaches the model. A test that wrote these keys itself would
     * pin the storage layout instead of the behaviour, and fail for the wrong
     * reason the day the layout changes.
     */
    internal fun persist(
        context: Context,
        isf: Double,
        days: Int,
        nowMs: Long,
        fromMs: Long,
        toMs: Long,
    ) {
        prefs(context).edit()
            .putFloat(KEY_ISF, isf.toFloat())
            .putInt(KEY_DAYS, days)
            .putLong(KEY_AT, nowMs)
            .putLong(KEY_FROM, fromMs)
            .putLong(KEY_TO, toMs)
            .putString(KEY_RECIPE, recipe())
            .apply()
    }

    /** Forget the fit — used when the evidence behind it is withdrawn. */
    internal fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** The last fit, or null when none has succeeded yet. */
    fun state(context: Context?): State? {
        val p = context?.let { prefs(it) } ?: return null
        val isf = p.getFloat(KEY_ISF, Float.NaN).toDouble().takeIf { it.isFinite() } ?: return null
        return State(
            isf = isf,
            days = p.getInt(KEY_DAYS, p.getInt(KEY_DAYS_LEGACY, 0)),
            fittedAtMs = p.getLong(KEY_AT, 0L),
            windowFromMs = p.getLong(KEY_FROM, 0L),
            windowToMs = p.getLong(KEY_TO, 0L),
        )
    }

    /**
     * Refit if a day has passed. Safe to call on every foreground pass.
     *
     * Returns the state in force afterwards, refit or not, so a caller can log
     * what it is using without a second read.
     */
    fun refreshIfDue(
        context: Context,
        store: CollectorStore,
        nowMs: Long = System.currentTimeMillis(),
    ): State? {
        android.util.Log.i("AdaptiveIsf", "checking refit schedule")
        val current = state(context)
        val fresh = current != null && nowMs - current.fittedAtMs < REFIT_INTERVAL_MS
        val sameRecipe = prefs(context).getString(KEY_RECIPE, null) == recipe()
        if (fresh && sameRecipe) return current
        if (fresh) android.util.Log.i(
            "AdaptiveIsf",
            "recipe changed (${prefs(context).getString(KEY_RECIPE, "none")} -> ${recipe()}) — refitting early",
        )
        return runCatching { refit(context, store, nowMs) }.getOrNull() ?: current
    }

    /**
     * The balance itself, WITHOUT persisting — so the screen and the estimate
     * are the same arithmetic rather than two implementations of it.
     *
     * This used to sweep fifteen ISF candidates over every episode in the
     * window, replaying the whole forecast each time. It was removed after the
     * user's own read of the two approaches turned out to match the
     * measurement: the balance looked more plausible, tracked drift well, and
     * looked more physiological, while the sweep took a long time to compute
     * and returned nonsense. See [DailyBalanceIsfV1] for both what the balance
     * buys and what it costs.
     *
     * The carb sensitivity comes from the artifact, never from a constant: ISF
     * scales linearly with it, so a hardcoded value would put a population
     * number inside a personal measurement.
     *
     * The card that draws this replaced an earlier "completed causal segments"
     * card, which asked the same question per EPISODE. Sharing this function is the whole
     * point: a card that recomputed the balance its own way would drift from the
     * number the model uses, which is the defect this session spent the day on.
     */
    fun balance(
        store: CollectorStore,
        nowMs: Long,
        windowDays: Int = DailyBalanceIsfV1.WINDOW_DAYS,
        carbSensOverride: Double? = null,
    ): DailyBalanceIsfV1.Result? {
        val carbSens = carbSensOverride
            ?: PhysioRuntime.artifact(store, nowMs)?.globalCs?.median
            ?: return null

        val zone = java.time.ZoneId.systemDefault()
        val dayMs = 24L * 3_600_000
        fun boundaryOf(t: Long): Long {
            val z = java.time.Instant.ofEpochMilli(t).atZone(zone)
            val b = z.toLocalDate().atTime(DailyBalanceIsfV1.BOUNDARY_HOUR, 0).atZone(zone)
            return (if (b.toInstant().toEpochMilli() > t) b.minusDays(1) else b)
                .toInstant().toEpochMilli()
        }
        val last = boundaryOf(nowMs)
        val starts = (windowDays downTo 1).map { last - it * dayMs }

        val from = starts.first() - 2 * dayMs
        val readings = MeasurementStreamCore
            .chooseFrom(store.sensorReadingsWithSource(from, nowMs)).readings
            .sortedBy { it.tsMs }
        val doses = store.boluses(from, nowMs).filter { it.units > 0.0 }
        val notes = store.annotations(from, nowMs).filter { it.kind == "food" }

        // The endpoint reading must be NEAR its boundary. Reaching across a gap
        // for the nearest value would silently compare 04:00 with 09:00 and
        // charge five hours of unrelated glucose to the day.
        fun at(ts: Long): Double? = readings
            .minByOrNull { kotlin.math.abs(it.tsMs - ts) }
            ?.takeIf { kotlin.math.abs(it.tsMs - ts) <= 30L * 60_000 }?.mmol

        return DailyBalanceIsfV1.estimate(
            dayStarts = starts,
            dayMs = dayMs,
            carbSens = carbSens,
            carbsIn = { a, b -> notes.filter { it.tsMs in a until b }.sumOf { it.estCarbs ?: 0.0 } },
            unitsIn = { a, b -> doses.filter { it.tsMs in a until b }.sumOf { it.units } },
            glucoseAt = ::at,
        )
    }

    /** The carb sensitivity the balance is using, for the card to state. */
    fun carbSens(store: CollectorStore, nowMs: Long): Double? =
        PhysioRuntime.artifact(store, nowMs)?.globalCs?.median

    fun refit(context: Context, store: CollectorStore, nowMs: Long): State? {
        val result = balance(store, nowMs)
        if (result == null) {
            android.util.Log.i(
                "AdaptiveIsf",
                "estimate rejected: fewer than ${DailyBalanceIsfV1.MIN_DAYS} valid days " +
                    "out of ${DailyBalanceIsfV1.WINDOW_DAYS} (need >=${DailyBalanceIsfV1.MIN_CARBS_G.toInt()} g " +
                    "and >=${DailyBalanceIsfV1.MIN_UNITS.toInt()} u per day, plus glucose at both boundaries)",
            )
            return null
        }
        android.util.Log.i(
            "AdaptiveIsf",
            "balance: ISF %.2f over %d of %d days · range %.2f..%.2f".format(
                java.util.Locale.ROOT, result.isf, result.days, DailyBalanceIsfV1.WINDOW_DAYS,
                result.spread?.first ?: Double.NaN, result.spread?.second ?: Double.NaN,
            ),
        )
        persist(context, result.isf, result.days, nowMs, result.fromMs, result.toMs)
        return state(context)
    }
}
