package io.github.obdosok.diapilot.data

import android.database.sqlite.SQLiteDatabase
import com.diapilot.core.twin.ForecastResult

/**
 * Prospective forecast ledger — the only honest answer to "did the model get
 * better?". The in-sample backtest and even walk-forward both re-tell the
 * past; this records the forecast that was ACTUALLY produced for a consumer
 * (main screen / watch / widget / hypo alert) at the moment it was produced,
 * then scores it against the sensor facts that arrived later.
 *
 * Immutability contract: runs and points are INSERT-only (deduplicated by
 * consumer+anchor+algo+input hash — Compose recompositions and background
 * refreshes must not spam rows). Later data never edits a stored run; scoring
 * writes to its own table.
 *
 * Scoring is sensor-only: a meter fingerstick is a calibration input, not a
 * fact. Scores are split by event_after (a bolus/food landed between forecast
 * and target): those runs aren't deleted — they measure unconditional
 * real-world quality — but the conditional "no new events" slice is what the
 * corridor promises (50%/80% coverage) and what model comparisons should use.
 *
 * SCALE — the bug this table shipped with for its first nine days. The forecast
 * is produced in METER-CALIBRATED space (TwinCache.calScale / MainState's
 * calibratedReadings; it is what the chart and the header draw), while the
 * facts arrive from the store RAW. Scoring the two against each other subtracts
 * the calibration itself: with the user's +1.65 mmol intercept every horizon
 * read ≈1.5 mmol pessimistic, and a 15-minute forecast scored MAE 1.49 where
 * the truth was 0.58. So the fact is calibrated here, and [calOffsetMmol]
 * records HOW MUCH lens was applied — a score row with a NULL offset is a
 * legacy, uncalibrated one. See [rescoreUncalibrated].
 */
object ForecastLedger {


    /** Fixed evaluation horizons (minutes). */
    val HORIZONS = intArrayOf(15, 30, 60, 90, 120, 180)

    /**
     * Prefix shared by every generation of the arm that is actually displayed.
     * `forecast-v15-…` is the base twin: recorded, never shown.
     */
    const val PHYSIO_FAMILY = "forecast-v11-kotlin-shadow"

    /** Minimum spacing between stored screen runs — one CGM cadence. See [record]. */
    const val SCREEN_SAMPLE_MS = 5L * 60_000

    /** How far from a target a reading may sit and still count as its fact. */
    private const val FACT_TOLERANCE_MS = 6L * 60_000

    /** Consumers whose points and scores are kept. See [record]. */
    val DETAIL_CONSUMERS = setOf("main", "hypo_alert")

    /** `algo_version` for refusal rows: NOT the model version — the model did not run. */
    const val REFUSAL_ALGO_VERSION = "refusal"


    /** Sensor fact must sit within this window of the target. */
    private const val MATCH_MS = 5 * 60_000L
    /** No sensor point this long after the target → NO_OBSERVATION. */
    private const val GIVE_UP_MS = 30 * 60_000L
    private const val SCORE_BATCH = 400
    /** Rows per transaction in the one-time repair — see [rescoreUncalibrated]. */
    private const val REPAIR_CHUNK = 5_000

    val DDL = listOf(
        """CREATE TABLE IF NOT EXISTS forecast_runs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at_ms INTEGER NOT NULL,
            anchor_ts_ms INTEGER NOT NULL,
            anchor_mmol REAL NOT NULL,
            consumer TEXT NOT NULL,
            algo_version TEXT NOT NULL,
            regime TEXT,
            health TEXT,
            input_hash TEXT NOT NULL,
            applied TEXT,
            UNIQUE(consumer, anchor_ts_ms, algo_version, input_hash)
        )""",
        """CREATE TABLE IF NOT EXISTS forecast_points (
            run_id INTEGER NOT NULL,
            horizon_min INTEGER NOT NULL,
            target_ts_ms INTEGER NOT NULL,
            mmol REAL NOT NULL, lo REAL NOT NULL, hi REAL NOT NULL,
            lo_mid REAL NOT NULL, hi_mid REAL NOT NULL,
            -- WHAT THE POINT IS MADE OF, on the model's RAW scale: `mmol` above
            -- is calibrated to the meter, the terms below are not, and they
            -- cannot be added to it directly (see `PredictedPoint.foodDelta`).
            -- NULL = the point is not from the physio engine.
            food_delta REAL, insulin_delta REAL,
            drift_delta REAL, background_delta REAL,
            PRIMARY KEY(run_id, horizon_min)
        )""",
        """CREATE INDEX IF NOT EXISTS idx_forecast_points_target
            ON forecast_points(target_ts_ms)""",
        // "The newest run" is asked for on ordinary screen paths, and this
        // ledger is append-only — it grows into hundreds of thousands of rows
        // with every pass. Without the index that lookup is a full scan plus a
        // sort, measured at tens of milliseconds per call on a real database
        // file. Not the multi-minute hang, but waste repeated all day.
        //
        // It belongs HERE, beside its table. The first attempt put it in the
        // store's DDL list, which runs before `ForecastLedger` has created
        // `forecast_runs` — every app test that opens a database failed.
        """CREATE INDEX IF NOT EXISTS idx_forecast_runs_created
            ON forecast_runs(created_at_ms)""",
        
    )



    /**
     * The last forecast this app SHOWED, for the first frame after a cold start.
     *
     * Reported by the user: after leaving the app closed for a few minutes and
     * reopening it, the forecast line was missing for a few seconds.
     * `loadLivePreview` draws the chart before the model runs, and it had no
     * line to draw — on a cold process there is no previous state to carry one
     * from, so the first frame showed history only, with the window still
     * ending at "now" instead of reaching into the forecast. A screenshot
     * confirmed exactly that frame.
     *
     * The app has been storing its own forecasts all along — the series that
     * solved an earlier discrepancy investigation — so the first frame can draw
     * the last one instead of nothing.
     *
     * FRESHNESS IS THE WHOLE SAFETY ARGUMENT. A forecast is a statement about a
     * particular anchor; drawn against a glucose that has since moved, the line
     * would start off the current point and read as "the line lies" when
     * nothing lied. So a run is used only while its anchor is within
     * [PREVIEW_MAX_ANCHOR_AGE_MS] of the reading being displayed, and it is
     * replaced by the live pass within about a second either way.
     */
    const val PREVIEW_MAX_ANCHOR_AGE_MS = 10L * 60_000

    fun lastShownPrediction(
        db: SQLiteDatabase,
        consumer: String,
        anchorTsMs: Long,
        maxAnchorAgeMs: Long = PREVIEW_MAX_ANCHOR_AGE_MS,
    ): List<com.diapilot.core.twin.PredictedPoint> {
        val runId = db.rawQuery(
            "SELECT id FROM forecast_runs WHERE consumer=? AND anchor_ts_ms>=? AND anchor_ts_ms<=? " +
                "ORDER BY created_at_ms DESC LIMIT 1",
            arrayOf(consumer, (anchorTsMs - maxAnchorAgeMs).toString(), anchorTsMs.toString()),
        ).use { if (it.moveToFirst()) it.getLong(0) else return emptyList() }
        return db.rawQuery(
            "SELECT target_ts_ms,mmol,lo,hi,lo_mid,hi_mid FROM forecast_points " +
                "WHERE run_id=? ORDER BY target_ts_ms",
            arrayOf(runId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        com.diapilot.core.twin.PredictedPoint(
                            tsMs = c.getLong(0), mmol = c.getDouble(1),
                            lo = c.getDouble(2), hi = c.getDouble(3),
                            loMid = if (c.isNull(4)) c.getDouble(2) else c.getDouble(4),
                            hiMid = if (c.isNull(5)) c.getDouble(3) else c.getDouble(5),
                        ),
                    )
                }
            }
        }
    }

    /**
     * WHAT THE MODEL BELIEVED, not only which algorithm ran.
     *
     * `algo_version` says "shadow-13"; it does not say what ISF that build was
     * applying at the time. On this device those are different questions: the
     * promotion ledger can move the applied ISF several times in one evening
     * without a single new correction, and `PhysioTuning` /
     * `ManualInsulinRuntime` can override all of it by hand — a live discrepancy
     * was once found between the configured curve/ISF and the one actually
     * applied.
     *
     * So the run carries the applied insulin curve and ISF, short and flat.
     * This is what makes "which coefficients produced the line in this
     * screenshot" answerable at all — it was not, before this column existed.
     *
     * AND SINCE THEN THIS IS NOT ONLY INSULIN — anchors and ISF turned out to be
     * not enough. The user turned off the trust ramp by hand
     * (`physio_trust_ramp` = 0.0), while the bench spent a whole day fitting
     * and scoring with ramp 1.0 — that is, evaluating a model whose every
     * increment on the hour is halved, while the device delivers the full
     * increment. The discrepancy lived for a day unnoticed precisely because the
     * `applied` column did not carry it, even though it exists exactly for
     * "what the model believed".
     *
     * HORIZON WEIGHTS were added — the ramp's effect, not its raw value: the
     * weights are enough for the bench to check itself, and they survive any
     * change to how the ramp is specified. Plus the carb-appearance policy, for
     * the same reason.
     *
     * The format is single-line and backward compatible: the old parser in
     * `RebuiltModel.appliedFromLedger` reads the head and `isf=` and keeps
     * working on old rows.
     */
    fun appliedSummary(person: com.diapilot.core.hybrid.HybridPersonModel?): String? =
        person?.let { m ->
            val i = m.insulin
            val shipped = com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED
            "%.0f/%.0f/%.0f isf=%.3f ramp=%.2f/%.2f/%.2f kcal=%.0f sieve=%.2f".format(
                java.util.Locale.ROOT, i.onsetMin, i.peakMin, i.tailDurationMin, i.isf,
                m.trend.weight60, m.trend.weight120, m.trend.weight180,
                m.food.emptyingKcalPerHourOverride ?: shipped.emptyingKcalPerHour ?: 0.0,
                m.food.carbSievingOverride ?: shipped.carbSieving,
            )
        }

    /**
     * WHAT THE APP DREW AT ONE MOMENT, and what actually happened.
     *
     * The user's own request: click a point on the chart and show which
     * forecast the app drew at that point, so it can be compared visually with
     * what actually happened — useful for tuning coefficients and analyzing the
     * model.
     *
     * This is the ledger's one user-facing job, and the only one that could not
     * be done by replaying history off-device: a replay reconstructs what the
     * model WOULD have said, from the full reading series including points that
     * back-filled afterwards. This says what it DID say, from the knowledge it
     * had at the time — including the coefficients it was applying, once that
     * column was added.
     */
    data class InspectedRun(
        val anchorTsMs: Long,
        val anchorMmol: Double,
        val algoVersion: String,
        /** Applied insulin curve and ISF; null on runs recorded before this column existed. */
        val applied: String?,
        val health: String?,
        val points: List<InspectedPoint>,
        /**
         * Meals inside the forecast window whose CARBS became known only after
         * the anchor — so this run was blind to them.
         *
         * The user's own point: a meal that was logged late poisons the ledger,
         * but is not a problem for walk-forward. Exactly right,
         * and it decides how the error column may be read. Measured on real
         * usage: a large share of meals have their grams known more than five
         * minutes after the mark, with a long tail beyond that. Where that
         * happens the miss is
         * about LOGGING LATENCY, not about the model, and the card says so
         * rather than letting the number be read as a model error.
         */
        val blindMeals: Int,
        val firstBlindMealMs: Long?,
    )

    data class InspectedPoint(
        val horizonMin: Int,
        val targetTsMs: Long,
        val predictedMmol: Double,
        val lo: Double,
        val hi: Double,
        /** Null while the target is still in the future or was never scored. */
        val actualMmol: Double?,
    )

    /**
     * The run whose ANCHOR is nearest [tsMs], within [toleranceMs].
     *
     * Anchor, not creation time: the anchor is the moment the line was drawn
     * FROM, which is what a finger on the chart is pointing at.
     */
    fun inspectAt(
        db: SQLiteDatabase,
        tsMs: Long,
        consumer: String = "main",
        toleranceMs: Long = 30L * 60_000,
        /** The calibration lens the forecast lived on. See the note inside. */
        cal: com.diapilot.core.analysis.MeterCalibration? = null,
    ): InspectedRun? {
        // THE PHYSIO ARM FIRST, and this is not a preference.
        //
        // For a stretch during development the ledger held BOTH tags at the same
        // anchors: the physio arm, which was the line actually shown, and the base
        // twin, which was recorded beside it and never displayed. Picking by
        // proximity alone would answer "what did the app draw here" with a
        // curve nobody ever drew — the same defect that went unnoticed for a
        // day in the recording path itself.
        val run = db.rawQuery(
            "SELECT id, anchor_ts_ms, anchor_mmol, algo_version, applied, health FROM forecast_runs " +
                "WHERE consumer=? AND anchor_ts_ms BETWEEN ? AND ? " +
                "ORDER BY (CASE WHEN algo_version LIKE ? THEN 0 ELSE 1 END), " +
                "ABS(anchor_ts_ms - ?) LIMIT 1",
            arrayOf(
                consumer, (tsMs - toleranceMs).toString(), (tsMs + toleranceMs).toString(),
                "$PHYSIO_FAMILY%", tsMs.toString(),
            ),
        ).use { c ->
            if (!c.moveToFirst()) return null
            InspectedRun(
                anchorTsMs = c.getLong(1), anchorMmol = c.getDouble(2),
                algoVersion = c.getString(3), applied = c.getString(4),
                health = c.getString(5), points = emptyList(),
                blindMeals = 0, firstBlindMealMs = null,
            ) to c.getLong(0)
        }
        // THE FACT IS COMPUTED, NOT STORED.
        //
        // `forecast_scores` held it — reality at each target, the error, the
        // corridor hits — and every one of those is derivable from the raw
        // tables: the reading at the target time, minus the prediction that is
        // in the row beside it. It was a large single-reader cache, refilled by
        // a maintenance step that cost a noticeable amount of time each
        // pass. The user's own observation, and it is right: that data can be
        // reconstructed by running a walk-forward.
        //
        // THE LENS IS THE PART THAT MUST NOT BE DROPPED WITH IT. The stored
        // fact was put onto the forecast's own scale first — `cal.correctedAt`
        // — and comparing a calibrated forecast against a RAW fact is exactly
        // the error that once forced a corridor claim to be retracted,
        // charging a meaningful amount of spurious calibration to the model. So
        // the same lens is applied here, and callers must pass it.
        val points = db.rawQuery(
            "SELECT horizon_min, target_ts_ms, mmol, lo, hi FROM forecast_points " +
                "WHERE run_id = ? ORDER BY horizon_min",
            arrayOf(run.second.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val target = c.getLong(1)
                    val fact = db.rawQuery(
                        "SELECT ts_ms, mmol FROM glucose_readings WHERE ts_ms BETWEEN ? AND ? " +
                            "ORDER BY ABS(ts_ms - ?) LIMIT 1",
                        arrayOf(
                            (target - FACT_TOLERANCE_MS).toString(),
                            (target + FACT_TOLERANCE_MS).toString(),
                            target.toString(),
                        ),
                    ).use { f ->
                        if (f.moveToFirst()) {
                            val raw = f.getDouble(1)
                            cal?.correctedAt(f.getLong(0), raw) ?: raw
                        } else null
                    }
                    add(
                        InspectedPoint(
                            horizonMin = c.getInt(0), targetTsMs = target,
                            predictedMmol = c.getDouble(2), lo = c.getDouble(3), hi = c.getDouble(4),
                            actualMmol = fact,
                        ),
                    )
                }
            }
        }
        val horizonEnd = points.maxOfOrNull { it.targetTsMs } ?: run.first.anchorTsMs
        val blind = db.rawQuery(
            "SELECT ts_ms FROM annotations WHERE kind='food' AND ts_ms BETWEEN ? AND ? " +
                "AND carbs_known_at_ms IS NOT NULL AND carbs_known_at_ms > ? ORDER BY ts_ms",
            arrayOf(
                (run.first.anchorTsMs - 6L * 3_600_000).toString(),
                horizonEnd.toString(),
                run.first.anchorTsMs.toString(),
            ),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        return run.first.copy(
            points = points,
            blindMeals = blind.size,
            firstBlindMealMs = blind.firstOrNull(),
        )
    }

    /**
     * Persist one produced forecast. Idempotent: an identical run (same
     * consumer, anchor, algo version and input hash) inserts nothing.
     */
    fun record(
        db: SQLiteDatabase,
        result: ForecastResult,
        anchorTsMs: Long,
        anchorMmol: Double,
        nowMs: Long,
        consumer: String,
        inputHash: String,
        // Which model produced this run. Defaults to the live algo; the
        // fingerprint SHADOW passes its own tag so quality() can A/B the two
        // on the SAME anchors without either overwriting the other.
        algoVersion: String = com.diapilot.core.twin.FORECAST_ALGO_VERSION,
        /** See [appliedSummary]. Null only when no person model was in force. */
        applied: String? = null,
    ) {
        if (result.points.isEmpty()) return
        // AND WHOSE PASS IS WORTH RECORDING AT ALL.
        //
        // A row per pass used to be written for every consumer, thousands a
        // day, on the reasoning that it is the app's heartbeat — the only
        // series that can say whether the app was LOOKING, because readings
        // back-fill.
        //
        // That reasoning had exactly one live consumer, the back-fill proof in
        // `IsfEvidenceRuntime`, and it was removed around the same time: for a
        // retrospective measurement the current calibrated readings ARE the
        // evidence, and a missing stretch shows in their timestamps. Nothing
        // now reads a `watch` or `widget` run — they are shorter-horizon slices
        // of the same forecast, recorded and never asked about.
        //
        // `hypo_alert` stays because its stream is what discipline #6 reads to
        // size blind holes in the ALERT path, and `main` because the retro
        // line, the first frame and the long-press card all hang off it.
        if (consumer !in DETAIL_CONSUMERS) return
        // ONE STORED RUN PER READING.
        //
        // Measured over a multi-week stretch of real use: screen runs noticeably
        // outnumbered readings — a stored forecast every few minutes, more than
        // one per CGM point. A forecast cannot meaningfully change until a new reading or a
        // new event arrives, so the extra rows record the clock moving, not the
        // model saying something different.
        //
        // Both consumers of this table survive the thinning: the long-press
        // card finds the run within five minutes of where the finger landed,
        // and the discipline-#7 acceptance test — «can a replay reproduce what
        // the device actually ran» — needs one run per reading, not three.
        //
        // `hypo_alert` is deliberately NOT sampled. Its rows are the only
        // record of whether the app was watching when it failed to warn, and
        // thinning that series would make a hole ambiguous at exactly the
        // resolution the question is asked.
        if (consumer == "main") {
            val nearby = db.rawQuery(
                "SELECT 1 FROM forecast_runs WHERE consumer='main' " +
                    "AND anchor_ts_ms BETWEEN ? AND ? LIMIT 1",
                arrayOf(
                    (anchorTsMs - SCREEN_SAMPLE_MS).toString(),
                    (anchorTsMs + SCREEN_SAMPLE_MS).toString(),
                ),
            ).use { it.moveToFirst() }
            if (nearby) return
        }
        // WHOSE DETAIL IS WORTH KEEPING. The heartbeat row is written for every
        // consumer — it is what says the app was LOOKING (discipline #6) — but
        // the six points and their scores are only ever read back for two
        // things, and both are about a line a person can see: does a replay
        // reproduce what the device ran (the discipline-#7 acceptance test),
        // and what did the model believe at that moment. `watch` and `widget`
        // are slices of the same forecast at a shorter horizon; storing their
        // points again is ~60% of the detail volume for no second question.
        val keepDetail = consumer in DETAIL_CONSUMERS
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT OR IGNORE INTO forecast_runs " +
                    "(created_at_ms, anchor_ts_ms, anchor_mmol, consumer, " +
                    "algo_version, regime, health, input_hash, applied) VALUES (?,?,?,?,?,?,?,?,?)",
            )
            st.bindLong(1, nowMs)
            st.bindLong(2, anchorTsMs)
            st.bindDouble(3, anchorMmol)
            st.bindString(4, consumer)
            st.bindString(5, algoVersion)
            st.bindString(6, result.regime.name)
            st.bindString(7, result.health.name)
            st.bindString(8, inputHash)
            if (applied != null) st.bindString(9, applied) else st.bindNull(9)
            val runId = st.executeInsert()
            if (runId != -1L && keepDetail) {        // -1 = duplicate, nothing to add
                for (h in HORIZONS) {
                    val target = anchorTsMs + h * 60_000L
                    val p = result.points.minByOrNull { kotlin.math.abs(it.tsMs - target) }
                        ?.takeIf { kotlin.math.abs(it.tsMs - target) <= 150_000 } ?: continue
                    db.execSQL(
                        // COLUMNS ARE LISTED EXPLICITLY. An unnamed `VALUES (?,?,...)`
                        // breaks silently the day the table gains a
                        // column — and it just did.
                        "INSERT OR IGNORE INTO forecast_points" +
                            "(run_id,horizon_min,target_ts_ms,mmol,lo,hi,lo_mid,hi_mid," +
                            "food_delta,insulin_delta,drift_delta,background_delta) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(
                            runId, h, target, p.mmol, p.lo, p.hi, p.loMid, p.hiMid,
                            p.foodDelta, p.insulinDelta, p.driftDelta, p.backgroundDelta,
                        ),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * A REFUSAL THAT NAMES ITSELF.
     *
     * When the physio arm failed to compute, there is no line — and it was
     * tempting to stay silent there: a row with no points is easy to mistake
     * for a fake one. The user's objection, and it is decisive: silence is
     * INDISTINGUISHABLE from "the app wasn't working". Readings back-fill
     * (discipline #6), so a hole cannot be seen through them, and
     * `forecast_runs` is the only series that remembers whether the app was
     * LOOKING.
     *
     * A multi-hour physio outage was once found exactly this way: the ledger
     * held `forecast-v15` rows — there had been a substitution, and there was a
     * trace. Having removed the substitution, we are obligated to leave a
     * trace, or the next such incident becomes invisible.
     *
     * The row is HONEST and looks like nothing else: `health = INSUFFICIENT_DATA`,
     * the reason is in `applied`, there are no points. No point-reader will pick
     * it up — `forecast_points` is empty for it, and `quality()`/the retro line
     * go through points. Counting such rows is both possible and necessary.
     *
     * Thinning of `main` does NOT apply: a refusal is an event, not a frame, and
     * five minutes of silence around it would say "we looked and everything is
     * fine".
     */
    fun recordRefusal(
        db: android.database.sqlite.SQLiteDatabase,
        anchorTsMs: Long,
        anchorMmol: Double,
        nowMs: Long,
        consumer: String,
        reason: String,
    ) {
        if (consumer !in DETAIL_CONSUMERS) return
        val st = db.compileStatement(
            "INSERT OR IGNORE INTO forecast_runs " +
                "(created_at_ms, anchor_ts_ms, anchor_mmol, consumer, " +
                "algo_version, regime, health, input_hash, applied) VALUES (?,?,?,?,?,?,?,?,?)",
        )
        st.bindLong(1, nowMs)
        st.bindLong(2, anchorTsMs)
        st.bindDouble(3, anchorMmol)
        st.bindString(4, consumer)
        // A refusal tag, not a model tag: putting the algorithm version here
        // would record that the algorithm ran successfully.
        st.bindString(5, REFUSAL_ALGO_VERSION)
        st.bindNull(6)
        st.bindString(7, com.diapilot.core.twin.ForecastHealth.INSUFFICIENT_DATA.name)
        // The UNIQUE key is (consumer, anchor, algo, hash). The reason lives
        // inside the hash: two DIFFERENT refusals on the same anchor are
        // distinguishable, and a repeat of the same one collapses.
        st.bindString(8, reason.hashCode().toString(16))
        st.bindString(9, reason)
        st.executeInsert()
    }


    /**
     * Repair the score rows written before the fact was calibrated (see the
     * SCALE note on this object). Idempotent and self-terminating: a row is
     * touched only while its [cal_offset_mmol] is NULL.
     *
     * The lens is NOT re-derived from today's calibration — that would judge an
     * old forecast through a newer meter check. It is read back off the run
     * itself: `anchor_mmol` was stored calibrated, so `anchor_mmol −
     * raw_sensor(anchor_ts)` is the offset that run was produced under.
     *
     * KNOWN RESIDUES, measured — this repair is a large improvement, not an
     * exact inverse, and the older rows are the shakier half:
     *
     *  - The lens was NOT constant. Zero for the ledger's first three days,
     *    swinging ±1.3 (the transient layer) through 07-17, and only exactly
     *    +1.655 from 07-18 on. So the repair's size varies by era, and the
     *    v2–v5 rows are corrected by a lens that was genuinely moving.
     *  - The offset is taken at the ANCHOR's time and applied to a fact up to
     *    3 h later, while live scoring evaluates the lens at the FACT's time.
     *    Where the transient was active that leaves sd 0.27 at h=15 rising to
     *    0.65 at h=180; in the flat era it vanishes.
     *  - `anchor_mmol` often sits on the minute-cal promoted reading, off the
     *    5-min grid, so the raw side is interpolated rather than snapped —
     *    snapping carried sd 0.15 of pure grid artefact.
     *
     * Returns the number of rows repaired.
     */
    @Volatile private var rescoreChecked = false


    /** One horizon's prospective quality (optionally the no-events slice). */
    data class Quality(
        val horizonMin: Int,
        val n: Int,
        val maeMmol: Double,
        val biasMmol: Double,   // mean(actual − predicted): + = model too low
        val innerCoverage: Double,  // fraction inside p75/p25 band (target ~0.5)
        val outerCoverage: Double,  // fraction inside p90/p10 band (target ~0.8)
    )


    /** Per-horizon: the corridor half-width the model PROMISED vs the one the
     *  prospective errors actually NEEDED. The corridor is calibrated on
     *  in-sample residuals (optimistically small); this exposes the honest
     *  band the live errors demand — the exact target for a future ledger-
     *  driven recalibration. inner = 50% band (p25..p75), outer = 80%
     *  (p10..p90). Half-width = the symmetric spread / 2. */
    data class CorridorNeed(
        val horizonMin: Int,
        val promisedInnerHalf: Double,
        val neededInnerHalf: Double,
        val promisedOuterHalf: Double,
        val neededOuterHalf: Double,
        val n: Int,
    )




}
