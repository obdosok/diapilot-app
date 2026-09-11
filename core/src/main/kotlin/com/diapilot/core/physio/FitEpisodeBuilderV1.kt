package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridBasalEvent
import com.diapilot.core.hybrid.HybridBolusEvent
import com.diapilot.core.hybrid.HybridFoodEvent

/**
 * ONE DEFINITION OF «AN EPISODE», FOR THE BENCH AND THE DEVICE ALIKE.
 *
 * There were two, and they disagreed by a factor of two. Measured on
 * the same user's database: the bench's builder yielded ~1.17
 * episodes a day, the device's 0.57 (25 over 43.6 days). That is not a detail —
 * the adaptive ISF fit is sized in EPISODES, so the seven-day window that won
 * the walk-forward held about eight of them on the bench and three on the phone,
 * where it then refused for want of support.
 *
 * This is discipline #7 in its purest form: the stand was not computing the
 * model the device runs, and the disagreement was invisible because each side
 * looked reasonable on its own. The gates below are the union of both, every one
 * of them named, so the next disagreement has to be a deliberate argument rather
 * than a divergence nobody noticed.
 *
 * The builder is deliberately free of any store: it takes plain lists, so both
 * callers feed it the same way and a test can feed it by hand.
 */
object FitEpisodeBuilderV1 {

    /** Minutes of line BEFORE the opening event that the episode starts from. */
    const val LEAD_MIN = 15L

    /**
     * How many minutes an insulin episode must survive without food.
     *
     * Three hours: in that time the applied curve passes start, peak and most
     * of the active phase, so the fall is already measurable. Requiring more
     * does not work — meals happen every few hours, and a six-hour requirement
     * threw out 161 candidates out of roughly two hundred.
     */
    const val INSULIN_MIN_CLEAN_MIN = 180.0

    /**
     * How many minutes of food before the shot still disqualifies the episode.
     *
     * Not five hours, like the food kind: there, quiet is needed to measure the
     * meal cleanly; here, it is needed so the shot does not turn out to be a
     * MEAL bolus wearing a nighttime disguise. Forty-five minutes separates
     * «injected for a meal» from «injected afterward».
     */
    const val INSULIN_QUIET_NOTES_MIN = 45L

    /** How far back to pull food the model is required to subtract on its own. */
    const val INSULIN_FOOD_LOOKBACK_MIN = 360L

    /** Carbohydrate below which there is no excursion worth fitting. */
    const val MIN_CARBS_G = 25.0

    data class Gates(
        /**
         * Quiet before, insulin — THE MEASURED END OF ACTION, not a round number.
         *
         * The user asked the obvious question: «the timings
         * say insulin acts a bit over 2 hours, but the window is 4». That is
         * right, and it is worth episodes. The measured curve ends at 129 min
         * (`docs/insulin-model.md` §2.6), so four hours demands two full
         * lifetimes of quiet before admitting an episode.
         *
         * Measured on the database, walk-forward, window 10 days:
         *
         * | quiet | scored | adaptive | shipped | false | MISSED |
         * |---|---|---|---|---|---|
         * | 240 min | 18 | 1.799 | 2.105 | 0 / 9 | 1 / 1 |
         * | **130 min** | **23** | **1.799** | 2.105 | **0 / 8** | 1 / 1 |
         * | 0 + carried IOB | 26 | 2.230 | 2.158 | 1 / 9 | 1 / 1 |
         *
         * Five more scored episodes and the verdict unchanged. Opening the gate
         * ALL the way is a different story: with doses stacked inside two hours
         * the adaptive fit stops beating the shipped value even with the prior
         * insulin carried, so «carry it and admit everything» is NOT supported.
         *
         * ⚠ This tracks a MEASUREMENT that moves: it has already shifted by more
         * than a quarter of an hour between measurements. Re-check it against
         * `docs/insulin-model.md` §2.6 rather than treating 130 as a constant of nature.
         */
        val quietDosesMin: Long = 130,
        /** Quiet before, food. Device 5 h, bench 3 h. */
        val quietNotesMin: Long = 300,
        /**
         * How much of the scoring grid must be actually observed.
         *
         * The device demanded all but two points; the bench only checked the
         * two ENDS. For a fit this matters more than it looks: a residual taken
         * against an interpolated point is a sample of the interpolator.
         */
        val maxMissingGridPoints: Int = 2,
        /**
         * How far BEFORE the episode to carry prior boluses into its history.
         *
         * The quiet-before gate exists only because the replay used to start
         * from an empty insulin history, so a dose an hour earlier was invisible
         * and its ongoing fall got charged to the model. But the engine takes a
         * bolus HISTORY and computes the residual from the measured action curve
         * — the same arithmetic deconvolution does. Carrying the dose is strictly
         * better than refusing the episode: it uses the information instead of
         * discarding the evidence.
         *
         * Zero keeps the old behaviour, so the two can be compared rather than
         * swapped on faith.
         */
        val carryBackMin: Long = 0,
        /** A rise reality actually made. Below it the shape criterion judges nothing. */
        val minExcursionMmol: Double = 1.5,
    )

    /** Why a candidate did not become an episode. Counted, never discarded. */
    enum class Refusal {
        OVERLAPS_PREVIOUS,
        WINDOW_NOT_CLOSED,
        CARBS_TOO_FEW,
        NO_BOLUS,
        INSULIN_TOO_RECENT,
        FOOD_TOO_RECENT,
        NO_FOOD_PARSED,
        RESCUE_TREATMENT,
        NO_ANCHOR,
        GRID_TOO_GAPPY,
        NO_EXCURSION,
        /** Insulin kind: food showed up in the window — nothing left to measure insulin by. */
        FOOD_IN_WINDOW,
        /** Insulin kind: the line did not go DOWN, nothing to judge by. */
        NO_FALL,
    }

    data class Note(
        val tsMs: Long,
        val content: String,
        val estCarbs: Double?,
        val analysis: String?,
    )

    data class Dose(val tsMs: Long, val units: Double)

    data class Result(
        val episodes: List<PhysioAutoFitV1.Episode>,
        val refusals: Map<Refusal, Int>,
    ) {
        fun funnel(): String {
            val dropped = refusals.entries.sortedByDescending { it.value }
                .joinToString("; ") { "${it.key} ×${it.value}" }
            // KINDS ARE COUNTED SEPARATELY. A pool of two kinds reported as one
            // number hides exactly what the second kind exists to show: how many
            // nights are in the corpus. A single line saying "32 episodes" was
            // correct and useless.
            val meal = episodes.count { it.kind == PhysioAutoFitV1.Episode.Kind.MEAL }
            val ins = episodes.size - meal
            return "episodes ${episodes.size} (meal $meal · insulin $ins) · " +
                "refusals ${refusals.values.sum()}: $dropped"
        }
    }

    /**
     * Build every episode in `[fromMs, toMs)`, newest first.
     *
     * @param at the glucose at a moment, or null when the line does not carry it.
     * @param foodOf the note turned into a food event; the caller owns that
     *        conversion because the bench spreads a note across its portions and
     *        the device does not — a difference that belongs in the open, not
     *        hidden inside a shared builder.
     */
    fun build(
        fromMs: Long,
        toMs: Long,
        notes: List<Note>,
        doses: List<Dose>,
        basals: List<Dose>,
        grid: List<Int>,
        at: (Long) -> Double?,
        foodOf: (Note) -> HybridFoodEvent?,
        gates: Gates = Gates(),
        limit: Int = Int.MAX_VALUE,
        activityExposureAt: (Long) -> Double = { 0.0 },
    ): Result {
        val h = 3_600_000L
        val episodeMs = grid.last() * 60_000L
        val out = mutableListOf<PhysioAutoFitV1.Episode>()
        val refusals = linkedMapOf<Refusal, Int>()
        fun no(r: Refusal) { refusals[r] = (refusals[r] ?: 0) + 1 }

        val sortedNotes = notes.sortedBy { it.tsMs }
        for (opener in sortedNotes.filter { it.tsMs in fromMs until toMs }.reversed()) {
            if (out.size >= limit) break
            val t = opener.tsMs - LEAD_MIN * 60_000L
            val end = t + episodeMs
            if (end > toMs) { no(Refusal.WINDOW_NOT_CLOSED); continue }
            if (out.any { kotlin.math.abs(it.startMs - t) < episodeMs }) {
                no(Refusal.OVERLAPS_PREVIOUS); continue
            }
            val ns = sortedNotes.filter { it.tsMs in t..end }
            if (ns.sumOf { it.estCarbs ?: 0.0 } < MIN_CARBS_G) { no(Refusal.CARBS_TOO_FEW); continue }
            val b = doses.filter { it.tsMs in t..end }
            val history = doses.filter { it.tsMs in (t - gates.carryBackMin * 60_000) until t }
            if (b.isEmpty()) { no(Refusal.NO_BOLUS); continue }
            if (doses.any { it.tsMs in (t - gates.quietDosesMin * 60_000) until t }) {
                no(Refusal.INSULIN_TOO_RECENT); continue
            }
            if (sortedNotes.any { it.tsMs in (t - gates.quietNotesMin * 60_000) until t }) {
                no(Refusal.FOOD_TOO_RECENT); continue
            }
            val foods = ns.mapNotNull(foodOf)
            if (foods.isEmpty()) { no(Refusal.NO_FOOD_PARSED); continue }
            if (foods.any { it.rescueTreatment }) { no(Refusal.RESCUE_TREATMENT); continue }
            val g0 = at(t) ?: run { no(Refusal.NO_ANCHOR); null } ?: continue
            val real = grid.map { at(t + it * 60_000L) }
            if (real.count { it != null } < grid.size - gates.maxMissingGridPoints) {
                no(Refusal.GRID_TOO_GAPPY); continue
            }
            val peak = real.filterNotNull().maxOrNull()
            if (peak == null || peak - g0 < gates.minExcursionMmol) {
                no(Refusal.NO_EXCURSION); continue
            }
            out += PhysioAutoFitV1.Episode(
                startMs = t,
                g0 = g0,
                foods = foods,
                boluses = (history + b).map { HybridBolusEvent(it.tsMs, it.units) },
                basals = basals.filter { it.tsMs in (t - 48 * h)..end }
                    .map { HybridBasalEvent(it.tsMs, it.units) },
                real = real,
                label = opener.content.take(28),
                activityExposure = activityExposureAt(t),
            )
        }

        // SECOND KIND: AN EPISODE OPENED BY A SHOT.
        //
        // A mirror of the first. That one opens with food, requires carbohydrate
        // and is judged by the RISE; this one opens with a dose, requires the
        // ABSENCE of food, and is judged by the FALL. The basis is the same
        // identifiability rule as elsewhere: with food on board the trace
        // equals «insulin action minus carb arrival», two unknowns for one
        // observable, and nothing to separate them with. A dose with no food
        // zeroes out the second term.
        //
        // Why it was needed, measured: of 32 episodes in the old corpus none
        // started at night (all between 10:00 and 21:00), because the only
        // possible opener was a food entry. Night is where all the corpus's
        // hypoglycemia lives, its three worst events, and the whole complaint
        // about the insulin tail's length.
        //
        // The window is the same (`grid.last()`), the quiet gates are the same.
        // The difference is exactly three places: the opener, the food
        // requirement is inverted, and the excursion criterion looks downward.
        for (opener in doses.filter { it.tsMs in fromMs until toMs }.reversed()) {
            if (out.size >= limit) break
            val t = opener.tsMs - LEAD_MIN * 60_000L
            val end = t + episodeMs
            if (end > toMs) { no(Refusal.WINDOW_NOT_CLOSED); continue }
            // Overlap is checked ONLY against its own kind. Meal and insulin
            // episodes answer different questions and can stand next to each
            // other; mixing them into one queue would mean handing the night
            // to the evening meal just because it comes earlier in the list.
            if (out.any {
                    it.kind == PhysioAutoFitV1.Episode.Kind.INSULIN &&
                        kotlin.math.abs(it.startMs - t) < episodeMs
                }
            ) { no(Refusal.OVERLAPS_PREVIOUS); continue }
            // FOOD ENDS THE WINDOW, IT DOES NOT DISQUALIFY IT.
            //
            // The first version rejected any window with food inside, and that
            // threw out 161 candidates out of ~200: the episode window is six
            // hours, but meals happen every few hours — six clean hours almost
            // never occur. This is the same lesson already recorded about the
            // insulin landmarks: demanding ninety clean minutes for a quantity
            // that lives in the first thirty means throwing out almost all the
            // evidence.
            //
            // So the window ends at the first food, and grid points after it
            // become unobserved (null) — the counter skips them. It needs at
            // least [INSULIN_MIN_CLEAN_MIN] clean minutes, otherwise there is
            // nothing left to measure the fall against.
            val firstFoodMs = sortedNotes
                .filter { it.tsMs in t..end && (it.estCarbs ?: 0.0) > 0.0 }
                .minOfOrNull { it.tsMs }
            val cleanUntilMin = firstFoodMs?.let { (it - t) / 60_000.0 } ?: Double.MAX_VALUE
            if (cleanUntilMin < INSULIN_MIN_CLEAN_MIN) { no(Refusal.FOOD_IN_WINDOW); continue }
            // PRIOR FOOD DOES NOT DISQUALIFY — IT ENTERS THE EPISODE.
            //
            // The food kind requires five hours of food-quiet before the anchor,
            // and for it that is right. For the insulin kind, the same
            // requirement makes its nights UNMEASURABLE in principle: late meals
            // and late doses can sit less than two hours apart. Under a 300-minute
            // gate no night in the database passes — measured, zero episodes out
            // of 38 eligible nighttime doses.
            //
            // So the kind changes meaning, and that has to be said plainly: this
            // is NOT a clean insulin measurement, for which the identifiability
            // rule applies («food has no effect»). This is a FALL that the model
            // is required to reproduce given everything known — including the
            // prior food, which is passed into the episode and must be
            // subtracted by the model itself.
            //
            // The cost is honest: if the food model is wrong, the insulin verdict
            // on such an episode is contaminated. But the alternative is not
            // measuring the night at all, and the night is where all of this
            // corpus's hypoglycemia lives.
            val priorFoodMs = sortedNotes.filter {
                it.tsMs in (t - INSULIN_FOOD_LOOKBACK_MIN * 60_000) until t &&
                    (it.estCarbs ?: 0.0) > 0.0
            }
            if (sortedNotes.any {
                    it.tsMs in (t - INSULIN_QUIET_NOTES_MIN * 60_000) until t &&
                        (it.estCarbs ?: 0.0) > 0.0
                }
            ) { no(Refusal.FOOD_TOO_RECENT); continue }
            if (doses.any { it.tsMs in (t - gates.quietDosesMin * 60_000) until t }) {
                no(Refusal.INSULIN_TOO_RECENT); continue
            }
            val b = doses.filter { it.tsMs in t..end }
            val history = doses.filter { it.tsMs in (t - gates.carryBackMin * 60_000) until t }
            val g0 = at(t) ?: run { no(Refusal.NO_ANCHOR); null } ?: continue
            // Points after the first food are not observed BY THIS episode — not "missing".
            val real = grid.map { g ->
                if (g > cleanUntilMin) null else at(t + g * 60_000L)
            }
            val usable = grid.count { it <= cleanUntilMin }
            if (real.count { it != null } < usable - gates.maxMissingGridPoints) {
                no(Refusal.GRID_TOO_GAPPY); continue
            }
            val trough = real.filterNotNull().minOrNull()
            if (trough == null || g0 - trough < gates.minExcursionMmol) {
                no(Refusal.NO_FALL); continue
            }
            out += PhysioAutoFitV1.Episode(
                startMs = t,
                g0 = g0,
                // Prior food ENTERS the episode: the model must subtract it itself.
                foods = priorFoodMs.mapNotNull(foodOf),
                boluses = (history + b).map { HybridBolusEvent(it.tsMs, it.units) },
                basals = basals.filter { it.tsMs in (t - 48 * h)..end }
                    .map { HybridBasalEvent(it.tsMs, it.units) },
                real = real,
                label = "insulin %.1f U".format(opener.units),
                activityExposure = activityExposureAt(t),
                kind = PhysioAutoFitV1.Episode.Kind.INSULIN,
            )
        }
        return Result(out, refusals)
    }
}
