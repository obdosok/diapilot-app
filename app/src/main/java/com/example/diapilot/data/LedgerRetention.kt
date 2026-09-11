package com.example.diapilot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Retention for the forecast ledgers — the question `PreEraPurge` explicitly
 * left open ("`forecast_*`, `physio_*` … carry their own generation tags and
 * retention question") and nobody answered.
 *
 * WHAT IT COSTS: a live device pull can show the forecast-ledger tables
 * outnumbering actual glucose readings by two orders of magnitude, in a
 * database file hundreds of megabytes large. The app pays for it on every
 * pass: reading and scoring against that much ledger detail measurably slows
 * a full pass. (The `ClosedEpisode` publish path referenced by the original
 * measurement no longer exists — the episode pipeline was removed. The
 * measurement is kept because it is WHY this file exists.)
 *
 * WHAT MAY BE DROPPED, and the reasoning is about the LEDGER'S PURPOSE rather
 * than about age. The per-horizon detail exists to serve a paired A/B, and
 * every bump of the algo tag RESETS that A/B — so detail belonging to a
 * superseded generation cannot contribute to any verdict that will ever be
 * read again. A long-lived device can carry dozens of generations; only a
 * couple are ever live.
 *
 * WHAT MAY NEVER BE DROPPED, and this is the important half:
 *
 *  - `forecast_runs` is the app's OBSERVATION HEARTBEAT. Discipline #6: readings
 *    back-fill, so `glucose_readings` cannot tell you whether the app was
 *    LOOKING, and one row per pass is the only series that can. Deleting old
 *    runs would destroy the ability to tell «the line lied» from «we were not
 *    watching» — the distinction the whole anomaly idea rests on. It is also
 *    cheap: 204k narrow rows.
 *  - `physio_parallel_runs` likewise carries the pairing metadata at one row per
 *    run, and is small.
 *
 * So the rule is: keep every RUN for ever, keep per-horizon DETAIL only for
 * generations still in use, and put a floor under it in days so that a tag bump
 * cannot erase the week around it.
 *
 * THE ACTIVE SET IS READ FROM THE DATA, NOT FROM THE CODE CONSTANTS. It is
 * tempting to use `FORECAST_ALGO_VERSION` / `FORECAST_ALGO_VERSION_FP_SHADOW`,
 * and it would be wrong: `forecast_runs.algo_version` carries the ENGINE
 * version, while the FP shadow tag is a different string living in a different
 * column. On the pull above the live pair was `forecast-v15-…` and
 * `forecast-v11-kotlin-shadow-12-…`, and neither equals the shadow constant.
 * Deriving «active» as «whatever versions were written inside
 * [ACTIVE_WINDOW_DAYS]» is self-maintaining and cannot mistake the live shadow
 * arm for a dead one.
 *
 * Measured effect: the large majority of ledger rows are removable under
 * these rules once superseded generations are pruned.
 */
object LedgerRetention {

    /**
     * Detail newer than this survives whatever its generation — so a tag bumped
     * yesterday does not take the week around it down, and a generation that
     * has only just started is never judged dead for being small.
     */
    const val FLOOR_DAYS = 7L

    /**
     * How far back to look for «what is this build actually running».
     *
     * IT MUST BE SHORTER THAN [FLOOR_DAYS], and the first draft got this wrong
     * in a way only a test caught: with one window doing both jobs, a run newer
     * than the floor put its own version into the active set, so the
     * «older than the floor» clause could never fire on its own and the floor
     * was decorative. Two windows, and each does one job — the short one
     * answers «is this generation alive», the long one answers «is this row
     * recent enough to keep regardless».
     */
    const val ACTIVE_WINDOW_DAYS = 2L

    // THE HEARTBEAT IS CHEAP AND KEEPS LONG; THE DETAIL IS EXPENSIVE AND KEEPS
    // SHORT. Without a time-based floor the ledger grows without bound.
    //
    // The generation sweep above only prunes runs whose `algo_version` is no
    // longer live, so a stable tag prunes NOTHING — and a tag can stay stable
    // for weeks at a time. Measured on a typical write rate with one arm
    // recording: thousands of runs, tens of thousands of points and scores a
    // day, which adds up to tens of megabytes a month, forever.
    //
    // The split follows what each series is FOR:
    //
    // - `forecast_runs` is the app's own heartbeat — one row per pass, the
    // only series that can say whether the app was LOOKING, because
    // readings back-fill and cannot (discipline #6). At ~0.24 MB/day it is
    // affordable for months and that is where the blind-hole analysis
    // lives.
    // - `forecast_points` / `forecast_scores` serve two questions that are
    // both about the RECENT past: does a replay reproduce what the device
    // actually ran (the discipline-#7 acceptance test), and did the line
    // leave its corridor lately (the anomaly card). Neither reaches back
    // months, and hypothesis testing is done by walk-forward over the
    // history instead — the user's own method.
    /**
     * How long the SCREEN's own points and scores survive.
     *
     * Longer than everything else on purpose, and it is the cheapest series in
     * the ledger: the screen makes far fewer passes a day than the alert
     * path, so sixty days of it is still a small amount of storage. That buys
     * the thing the user actually wants from this ledger — clicking a point
     * on the chart and seeing which forecast the app was drawing at that
     * point — over two months rather than one week, together with the
     * `applied` coefficients that produced it.
     */
    const val SCREEN_DETAIL_DAYS = 60L

    /** Everything else with detail — today only `hypo_alert`. */
    const val DETAIL_DAYS = 7L

    /**
     * How long one row per pass survives.
     *
     * THIRTY DAYS, cut down from a much longer window once the funnel became
     * countable.
     *
     * ⚠ THE REASON GIVEN WHEN THIS WAS SET IS GONE, AND THE NUMBER STILL
     * STANDS — on a different reason, which is worth stating rather than
     * leaving the old one to rot. It used to be: "the pass stream has exactly
     * one consumer, `ObservationProof.hasContinuousForecastObservation`, and
     * that verdict is written ONTO the episode when it closes, so the raw
     * stream is only needed while a window is being decided". Both halves
     * were later removed — the episode pipeline and `ObservationProof`
     * itself. The stream now has NO programmatic consumer at all.
     *
     * It is kept anyway, and this is the surviving reason: it is the only series
     * that records whether the app was LOOKING. Readings back-fill (discipline
     * #6), so a stretch the app was blind through looks continuous afterwards;
     * one row per pass does not. A twenty-hour physio outage was
     * findable ONLY because these rows existed and carried the wrong algo tag.
     * Thirty days is how far back a human question about «was it running?»
     * realistically reaches.
     *
     * IT CANNOT GO BELOW [SCREEN_DETAIL_DAYS], and the tests caught the first
     * attempt at 30: `forecast_points` references `run_id`, and `inspectAt`
     * reaches the points THROUGH the run, so pruning a run whose points are
     * still kept orphans them and the long-press card goes blank while the data
     * is still there. Sixty is that floor, and it also covers the 20.5-day
     * blind-hole analysis discipline #6 runs off this same stream.
     */
    const val HEARTBEAT_DAYS = 60L

    /** How many learning-matrix revisions survive — one per maintenance pass,
     *  ~10 KB each, and only the newest is ever read. */
    const val LEARNING_SNAPSHOTS_KEPT = 30L

    /**
     * How long paired A/B scores survive — and why this is NOT [FLOOR_DAYS].
     *
     * THE FIRST VERSION OF THIS FILE GOT IT WRONG AND IT COST DATA. The rule
     * above is «per-horizon detail exists to serve a paired A/B, and a tag bump
     * resets that A/B, so detail from a superseded generation cannot contribute
     * to any verdict that will ever be read again». That is true of
     * `forecast_points` and `forecast_scores`. It is NOT true of
     * `physio_parallel_scores`: that table does not SERVE the verdict, it IS the
     * verdict. Pruning it at seven days was measured to remove the large
     * majority of the paired evidence, collapsing a multi-day comparison down
     * to just a couple of days — and the pooled summary the app shows changed
     * shape because of it, not because anything about the model had.
     *
     * NINETY DAYS RATHER THAN A LARGER FLOOR, because the re-take that followed
     * showed how much history this comparison needs: over a multi-day window
     * the day-paired difference between the arms was small compared to the
     * between-day spread at the horizon examined. The noise is an order of
     * magnitude above the effect, so a verdict needs many more days than seven,
     * and keeping them is cheap — the rows are eight numeric columns.
     *
     * Revisit if the table outgrows its usefulness; do not shorten it because a
     * sweep looked like it should free more.
     */

    private const val PREFS = "ledger_retention"
    private const val KEY_LAST_MS = "last_run_ms"

    /** At most once a day; the work is a few DELETEs but they touch millions of rows. */
    private const val EVERY_MS = 24 * 3_600_000L

    data class Result(
        val points: Int,
        val scores: Int,
        val generationsKept: Int,
        val generationsSeen: Int,
    ) {
        val total: Int get() = points + scores
    }

    /**
     * What a sweep WOULD remove. Read-only, safe from a dialog — the same shape
     * `PreEraPurge.preview` has, and for the same reason: a destructive
     * maintenance action on a user's own history should be inspectable
     * before it runs.
     */
    fun preview(db: SQLiteDatabase, nowMs: Long = System.currentTimeMillis()): Result =
        sweep(db, nowMs, dryRun = true)

    /** Runs the sweep. Returns what was removed. */
    fun run(db: SQLiteDatabase, nowMs: Long = System.currentTimeMillis()): Result =
        sweep(db, nowMs, dryRun = false)

    private fun count(db: SQLiteDatabase, sql: String, args: Array<String>): Int =
        db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun sweep(db: SQLiteDatabase, nowMs: Long, dryRun: Boolean): Result {
        val floor = nowMs - FLOOR_DAYS * 24 * 3_600_000L
        val activeSince = nowMs - ACTIVE_WINDOW_DAYS * 24 * 3_600_000L
        // The clock is the DEVICE clock and the rows carry their own; if the
        // device clock ever went backwards, `floor` could sit before every row
        // and the sweep would be a no-op. That is the safe direction, and it is
        // why the floor is compared against the data rather than assumed.
        val active = db.rawQuery(
            "SELECT DISTINCT algo_version FROM forecast_runs WHERE created_at_ms > ?",
            arrayOf(activeSince.toString()),
        ).use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let(::add) } }
            // REFUSAL ROWS ARE NOT A MODEL GENERATION, and this is not pedantry.
            //
            // "Alive" here means anything written in the last few days, and
            // "dead" means a generation that stopped being written. Leave the
            // `refusal` tag in this list, and during an outage where physio
            // stays silent for a day, refusals become the ONLY live
            // generation, and every real run gets swept away at once. That
            // is, cleanup would wipe out history exactly when it is needed most.
            //
            // Hence the behavior below: a window made only of refusals means
            // "there was no real forecast", and the sweep declines to run.
            .filter { it != ForecastLedger.REFUSAL_ALGO_VERSION }
        val seen = count(db, "SELECT COUNT(DISTINCT algo_version) FROM forecast_runs", emptyArray())

        // No active generation means the app wrote no forecast at all inside the
        // active window. Refuse rather than treat every generation as dead — a
        // quiet stretch is exactly when a sweep would delete the most and be the
        // most wrong.
        if (active.isEmpty()) return Result(0, 0, 0, seen)

        // Refusals age ONLY by time, like live rows: they are tiny (no
        // points) and answer the question "was the app looking", which gets
        // asked after the fact.
        val keep = active + ForecastLedger.REFUSAL_ALGO_VERSION
        val ph = keep.joinToString(",") { "?" }
        val args = (keep + listOf(floor.toString())).toTypedArray()
        // A run is DEAD when its generation is gone AND it is older than the
        // floor. Both conditions, never either.
        // Disjoint from the time floor below, so a row past BOTH is counted
        // once. `Result.total` is «rows a sweep removes», and double-counting
        // it would make the retention log describe work it did not do.
        // Disjoint from the time floors below, so a row past BOTH is counted
        // once — and the exclusion must use the SAME per-consumer floors, or a
        // row falls through the gap between the two sweeps and is never removed.
        val screenFloorMs = nowMs - SCREEN_DETAIL_DAYS * 24 * 3_600_000L
        val detailFloorMs = nowMs - DETAIL_DAYS * 24 * 3_600_000L
        val deadRuns =
            "SELECT id FROM forecast_runs WHERE algo_version NOT IN ($ph) AND created_at_ms <= ? " +
                "AND NOT ((consumer = 'main' AND created_at_ms <= $screenFloorMs) " +
                "OR (consumer <> 'main' AND created_at_ms <= $detailFloorMs))"

        val p = count(db, "SELECT COUNT(*) FROM forecast_points WHERE run_id IN ($deadRuns)", args)
        // THE LEARNING SNAPSHOT KEEPS ITS LAST FEW REVISIONS.
        //
        // One row per maintenance pass, each a ~10 KB JSON of the research
        // matrix. Left unbounded, this table can become the single largest
        // one outside the forecast ledger, larger than every glucose,
        // insulin and food table put together.
        //
        // Both readers — `readMatrix` and `status` — take the NEWEST revision.
        // Nothing reads the history. Thirty is a month of daily passes, kept so
        // a question like "when did this factor change stage" stays answerable
        // without a backup.
        // Fails open: the table is created by the learning runtime, and a
        // store that has never learned simply does not have it yet.
        if (!dryRun) {
            runCatching {
                db.execSQL(
                    "DELETE FROM physio_learning_snapshots_v1 WHERE revision <= " +
                        "(SELECT COALESCE(MAX(revision),0) - $LEARNING_SNAPSHOTS_KEPT " +
                        "FROM physio_learning_snapshots_v1)",
                )
            }
        }

        // Time-floor sweep, independent of generation — see [DETAIL_DAYS].
        val detailFloor = detailFloorMs
        val heartbeatFloor = nowMs - HEARTBEAT_DAYS * 24 * 3_600_000L
        val screenFloor = screenFloorMs
        // Per-consumer floor: the screen's line is what a person points at
        // later, the alert path's is only ever read as an acceptance test.
        val staleRuns =
            "SELECT id FROM forecast_runs WHERE (consumer = 'main' AND created_at_ms <= ?) " +
                "OR (consumer <> 'main' AND created_at_ms <= ?)"
        val staleArgs = arrayOf(screenFloor.toString(), detailFloor.toString())
        val oldP = count(db, "SELECT COUNT(*) FROM forecast_points WHERE run_id IN ($staleRuns)", staleArgs)
        if (!dryRun && oldP > 0) {
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM forecast_points WHERE run_id IN ($staleRuns)", staleArgs)
                // The heartbeat outlives its own detail by design: the row that
                // says «a pass happened at this minute» is what discipline #6
                // reads, and it costs a tenth of the detail it points at.
                db.execSQL("DELETE FROM forecast_runs WHERE created_at_ms <= ?",
                    arrayOf(heartbeatFloor.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (!dryRun && p > 0) {
            // One transaction: a sweep interrupted between the two forecast
            // tables would leave scores whose points are gone, and the paired
            // reader joins them.
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM forecast_points WHERE run_id IN ($deadRuns)", args)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return Result(p + oldP, 0, active.size, seen)
    }

    // Daily hook for the poll worker.
    //
    // VACUUM IS NOT CALLED HERE, deliberately. `PreEraPurge` records why: it
    // takes an exclusive lock on a file this size, so the collector cannot
    // write while it runs and a sensor packet would be dropped. Freed pages are
    // reused by the next inserts, so the file stops GROWING immediately even
    // without it; reclaiming the bytes is a user-initiated action next to the
    // existing purge button, not something a background worker does behind a
    // live sensor link.
    /**
     * Drop the retired prospective-A/B tables, once.
     *
     * `physio_parallel_runs` / `physio_parallel_scores` stopped having a writer
     * when the candidate A/B was removed. On a device that had run the
     * prospective comparison, these tables could hold the majority of the
     * database file — and gated nothing: the only learner that reaches
     * production carries `ApplicationPolicyV1.MEASURED_BASE`, which by its
     * own comment "is not a candidate awaiting a prospective verdict".
     *
     * NOT ON `onOpen`, and not without VACUUM. A DROP of a million-row table is
     * seconds of work holding the single SQLite writer, which is exactly the
     * class of stall the same day's work removed from the event path; and
     * without VACUUM the pages are merely freed for reuse, so the file stops
     * growing but never shrinks. Both run here, on the maintenance queue,
     * behind a one-shot flag.
     */
    fun dropRetiredLedgers(context: Context, store: SqliteCollectorStore) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PARALLEL_DROPPED, false)) return
        runCatching {
            val db = store.writableDatabase
            val before = java.io.File(db.path).length() / 1_048_576
            // `forecast_scores` went the same way, and for the
            // same reason: every column in it is derivable from the reading at
            // the target and the prediction in the row beside it — a cache
            // with a single reader.
            db.execSQL("DROP TABLE IF EXISTS forecast_scores")
            // Nothing has written these since the prospective A/B went:
            // `physio_shadow_candidates` held its candidates, and the `_v1`
            // arrival journals were superseded by `_v2` long ago. All three
            // were still being created on every upgrade and carried forward
            // untouched.
            db.execSQL("DROP TABLE IF EXISTS physio_shadow_candidates")
            db.execSQL("DROP TABLE IF EXISTS physio_cgm_arrivals_v1")
            db.execSQL("DROP TABLE IF EXISTS physio_bolus_arrivals_v1")
            // Seven tables of the prospective "What if" that was removed.
            // `HybridWhatIfLedger` wrote them on every pass and on every
            // slider move, and three of its read functions — `quality`,
            // `decisionSummary`, `prospectiveInsulinProfile` — had no caller
            // at all. The same class of issue as the prospective A/B that was
            // removed earlier: a mechanism accumulating an answer to a
            // question nobody was asking.
            listOf(
                "hybrid_whatif_runs_v2", "hybrid_whatif_points_v2",
                "hybrid_whatif_scores_v2", "hybrid_whatif_intents_v3",
                "hybrid_whatif_intent_points_v3", "hybrid_whatif_decisions_v3",
                "hybrid_whatif_decision_scores_v3",
            ).forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            // Runs of consumers we stopped recording. They have
            // no points and no reader; retention would never reach them,
            // because they are younger than the heartbeat floor and the floor
            // does not know about consumers.
            db.execSQL(
                "DELETE FROM forecast_runs WHERE consumer NOT IN ('main','hypo_alert')",
            )
            db.execSQL("DROP TABLE IF EXISTS physio_parallel_scores")
            db.execSQL("DROP TABLE IF EXISTS physio_parallel_runs")
            db.execSQL("VACUUM")
            val after = java.io.File(db.path).length() / 1_048_576
            android.util.Log.i(
                "LedgerRetention",
                "prospective A/B dropped: ${before} MB -> ${after} MB",
            )
        }.onFailure { android.util.Log.w("LedgerRetention", "drop failed open", it) }
            .onSuccess { prefs.edit().putBoolean(KEY_PARALLEL_DROPPED, true).apply() }
    }

    /**
     * THE VERSION IN THE KEY NAME IS NOT DECORATION, IT IS A CONDITION FOR IT TO RUN.
     *
     * The flag is one-shot: once set, it permanently closes the door to
     * [dropRetiredLedgers]. On a device where an earlier version of this key
     * is already set, any table ADDED here later would never get dropped, and
     * the log would report success anyway, because execution never reaches
     * `runCatching`. A silent no-op reporting itself as work done.
     *
     * Hence the rule: **added a DROP — bump the key version.**
     * The current version added the seven `hybrid_whatif_*` tables.
     */
    private const val KEY_PARALLEL_DROPPED = "parallel_ab_dropped_v5"

    /**
     * Sweep once and give the bytes back, on request.
     *
     * The ordinary sweep DELETEs, which frees pages for reuse but never shrinks
     * the file — the ledger stops growing and stays the size it reached.
     * A live measurement showed the three ledger tables making up the large
     * majority of rows in the file, and applying the current floors would
     * leave only a fraction of that. The rest is backlog that predates the floors.
     *
     * `VACUUM` rewrites the file without the free pages, so it is the only way
     * to hand that back. It is a one-shot behind a flag rather than part of the
     * daily sweep because it rewrites the WHOLE file while holding the writer —
     * exactly the stall this day's work removed from the event path.
     */
    fun reclaimOnce(context: Context, store: SqliteCollectorStore) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_RECLAIMED, false)) return
        runCatching {
            val db = store.writableDatabase
            val before = java.io.File(db.path).length() / 1_048_576
            val swept = run(db, System.currentTimeMillis())
            db.execSQL("VACUUM")
            val after = java.io.File(db.path).length() / 1_048_576
            android.util.Log.i(
                "LedgerRetention",
                "подрезано: точек ${swept.points} · оценок ${swept.scores}; " +
                    "$before МБ -> $after МБ",
            )
        }.onFailure { android.util.Log.w("LedgerRetention", "reclaim failed open", it) }
            .onSuccess { prefs.edit().putBoolean(KEY_RECLAIMED, true).apply() }
    }

    private const val KEY_RECLAIMED = "ledger_reclaimed_v2"

    fun runIfDue(context: Context, store: SqliteCollectorStore): Result? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_MS, 0L) < EVERY_MS) return null
        return runCatching { run(store.writableDatabase, now) }
            .onSuccess {
                prefs.edit().putLong(KEY_LAST_MS, now).apply()
                android.util.Log.i(
                    "LedgerRetention",
                    "removed points=${it.points} scores=${it.scores} " +
                        "kept ${it.generationsKept} of ${it.generationsSeen} generations",
                )
            }
            .onFailure { android.util.Log.w("LedgerRetention", "sweep failed open", it) }
            .getOrNull()
    }
}
