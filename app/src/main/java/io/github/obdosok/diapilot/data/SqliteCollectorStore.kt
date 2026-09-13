package io.github.obdosok.diapilot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.CarbEvidenceInputV1
import com.diapilot.core.collector.CarbEvidenceReadinessV1
import com.diapilot.core.collector.CarbEvidenceSourceV1
import com.diapilot.core.collector.CarbEvidenceV1
import com.diapilot.core.collector.CarbUncertaintyV1
import com.diapilot.core.collector.validateRecipeRevisionV1
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.collector.HrPoint
import com.diapilot.core.collector.InsulinEvent
import com.diapilot.core.collector.SleepSession
import com.diapilot.core.collector.LabeledMeal
import com.diapilot.core.collector.MealEvent
import com.diapilot.core.collector.Reading
import com.diapilot.core.collector.ContextExposureV1

/**
 * SQLite implementation of [CollectorStore]. Schema mirrors
 * python_core/collector/store.py; all data stays on-device.
 */
class SqliteCollectorStore(context: Context, dbName: String = "diapilot.sqlite") :
    SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION), CollectorStore {

    internal val appContext: Context = context.applicationContext
    internal fun <T> withWritableDb(block: (SQLiteDatabase) -> T): T = block(writableDatabase)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL — READERS MUST NOT WAIT ON THE WRITER.
        //
        // `SQLiteOpenHelper` defaults to a rollback journal (the device
        // reported `journal=persist`), where a write takes an EXCLUSIVE lock
        // and every reader blocks until it commits. Measured on a real device,
        // opening cold: three concurrent forecast passes against a
        // large database file while the closed-episode pass wrote for over a second, and
        // `loadState` took over ten seconds — the pause the user reported as "the forecast
        // line doesn't show for 5-10 seconds". With WAL the same open is ~0.9 s.
        //
        // This is not a new capability: `exportSnapshot` has always
        // checkpointed WAL before copying the file and `BackupRestore` drops
        // the sidecars on swap. The mode they were written for was simply
        // never turned on.
        //
        // Per-connection here rather than `setWriteAheadLoggingEnabled` on the
        // helper: the helper-level switch forces `PRAGMA journal_mode=WAL` on
        // databases that cannot take it — Robolectric's read-only and
        // in-memory files throw SQLITE_CANTOPEN — and a test store failing to
        // open is not a trade worth making for a mode the device applies
        // either way.
        runCatching { db.enableWriteAheadLogging() }
        // The default page cache is 2 MB (`cache=-2000`) against a 412 MB
        // file whose ledgers hold ~2.4 M rows, so a cold pass re-reads the
        // same B-tree interior pages from flash over and over. 16 MB is still
        // small next to the app's heap and covers the working set of a
        // forecast pass.
        runCatching { db.execSQL("PRAGMA cache_size=-16000") }
    }

    /**
     * Cheap identity for the persisted Stage-10 History sidecar. Appending a
     * five-minute CGM point must not invalidate every historical receipt, so
     * glucose advances in six-hour finalized buckets; edits to food, insulin,
     * basal, activity or causal evidence invalidate immediately via triggers.
     */
    internal fun stage10ReceiptCacheKey(version: String, nowMs: Long): String {
        val db = readableDatabase
        ensureModelInputRevisionTracking(db)
        val material =
            setOf(
                "annotations",
                "insulin_events",
                "basal_events",
                "steps",
                "sleep_sessions",
                "carb_evidence_v1",
                "physio_context_exposures_v1"
            )
        val revisions =
            db.rawQuery(
                    "SELECT source_table,revision FROM physio_model_input_revisions_v1 ORDER BY source_table",
                    null,
                )
                .use { c ->
                    buildString {
                        while (c.moveToNext()) if (c.getString(0) in material)
                            append(c.getString(0)).append(':').append(c.getLong(1)).append(';')
                    }
                }
        val latestGlucose =
            db.rawQuery(
                    "SELECT MAX(ts_ms) FROM glucose_readings WHERE ts_ms<=?",
                    arrayOf(nowMs.toString())
                )
                .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
        val finalizedBucket = latestGlucose / (6L * 3_600_000L)
        val raw = "$version|$revisions|g6h=$finalizedBucket"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DB_VERSION = 50

        /**
         * Schema v8: adopt the legacy purpose NOTES (the pre-attribute
         * mechanism — a note reading "коррекция" or "на еду" at the bolus's own
         * timestamp) into `insulin_events.purpose`, then drop them: one entity,
         * one row.
         *
         * THE RUSSIAN LITERALS ARE CORRECT HERE, AND STALE ANYWHERE ELSE. A
         * database at v7 holds exactly these tokens — that is what the app
         * wrote then — and every upgrade step runs in ascending order, so this
         * one always sees the data BEFORE v50 (`LabelMigrationV1`) converts
         * tokens to keys. Rewriting them to `BolusPurpose.CORRECTION.key` would
         * make the step match nothing on the databases it exists for. Live
         * readers go through `BolusPurpose.of` / `PRIME_PURPOSES_SQL`, which
         * accept every stored form. Extracted from `onUpgrade` only so the
         * ordering argument can be shown by a test rather than asserted in a
         * comment; the SQL is byte-identical to the v8 step.
         */
        internal fun adoptLegacyPurposeNotesV8(db: SQLiteDatabase) {
            db.execSQL(
                """UPDATE insulin_events SET purpose = (
                    SELECT a.content FROM annotations a
                    WHERE a.ts_ms = insulin_events.ts_ms
                      AND a.content IN ('коррекция','на еду') LIMIT 1
                ) WHERE purpose IS NULL AND EXISTS (
                    SELECT 1 FROM annotations a WHERE a.ts_ms = insulin_events.ts_ms
                      AND a.content IN ('коррекция','на еду'))""",
            )
            db.execSQL(
                "DELETE FROM annotations WHERE content IN ('коррекция','на еду') " +
                    "AND ts_ms IN (SELECT ts_ms FROM insulin_events)",
            )
        }

        /** The activity note words as of schema v18 (data, for that historical step only). */
        private val LEGACY_V18_ACTIVITY_TAGS =
            listOf("прогулка", "тренировка", "спорт", "зал", "бег", "велосипед", "walk", "workout")

        /** SQL list of every stored spelling of the air-shot purpose. */
        private val PRIME_PURPOSES_SQL =
            com.diapilot.core.analysis.PRIME_PURPOSE_FORMS.joinToString(",") { "'${it.replace("'", "''")}'" }

        internal const val MODEL_INPUT_REVISIONS_DDL =
            """CREATE TABLE IF NOT EXISTS physio_model_input_revisions_v1 (
                source_table TEXT PRIMARY KEY,
                revision INTEGER NOT NULL,
                last_change_ms INTEGER NOT NULL
            )"""
        private val MODEL_INPUT_TABLES = listOf(
            "annotations", "glucose_readings", "insulin_events", "basal_events",
            "steps", "sleep_sessions", "carb_evidence_v1", "physio_context_exposures_v1",
            "physio_parallel_scores", "physio_daily_checkpoints",
        )
        internal val MODEL_INPUT_REVISION_TRIGGERS: List<String> = MODEL_INPUT_TABLES.flatMap { table ->
            listOf("insert", "update", "delete").map { operation ->
                """CREATE TRIGGER IF NOT EXISTS physio_dirty_${table}_${operation}
                   AFTER ${operation.uppercase()} ON $table BEGIN
                     INSERT OR REPLACE INTO physio_model_input_revisions_v1(source_table,revision,last_change_ms)
                     VALUES('$table',COALESCE((SELECT revision+1 FROM physio_model_input_revisions_v1 WHERE source_table='$table'),1),CAST(strftime('%s','now') AS INTEGER)*1000);
                   END"""
            }
        }

        internal fun ensureModelInputRevisionTracking(db: SQLiteDatabase) {
            db.execSQL(MODEL_INPUT_REVISIONS_DDL)
            val existing =
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                    buildSet { while (c.moveToNext()) add(c.getString(0)) }
                }
            MODEL_INPUT_TABLES.filter(existing::contains).forEach { table ->
                db.execSQL(
                    "INSERT OR IGNORE INTO physio_model_input_revisions_v1(source_table,revision,last_change_ms) VALUES(?,0,0)",
                    arrayOf<Any?>(table),
                )
                MODEL_INPUT_REVISION_TRIGGERS.filter { it.contains(" ON $table BEGIN") }
                    .forEach(db::execSQL)
            }
        }

        internal const val CONTEXT_EXPOSURES_DDL =
            """CREATE TABLE IF NOT EXISTS physio_context_exposures_v1 (
                exposure_id TEXT PRIMARY KEY,
                source_type TEXT NOT NULL,
                event_start_ms INTEGER NOT NULL,
                event_end_ms INTEGER NOT NULL,
                known_at_ms INTEGER NOT NULL,
                recorded_at_ms INTEGER NOT NULL,
                value REAL,
                unit TEXT,
                context_json TEXT NOT NULL
            )"""
        internal const val CONTEXT_EXPOSURES_INDEX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_physio_context_source_time ON physio_context_exposures_v1(source_type,event_start_ms,known_at_ms)"

        internal const val CARB_EVIDENCE_DDL =
            """CREATE TABLE IF NOT EXISTS carb_evidence_v1 (
                evidence_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                supersedes_revision INTEGER,
                annotation_id INTEGER NOT NULL,
                meal_session_id TEXT NOT NULL,
                event_time_ms INTEGER NOT NULL,
                intake_start_ms INTEGER NOT NULL,
                intake_end_ms INTEGER NOT NULL,
                known_at_ms INTEGER NOT NULL,
                recorded_at_ms INTEGER NOT NULL,
                source TEXT NOT NULL,
                user_confirmed INTEGER NOT NULL,
                total_carbs_g REAL NOT NULL,
                label_carbs_per_100g REAL,
                weighed_edible_g REAL,
                label_carbs_per_serving_g REAL,
                servings REAL,
                recipe_version TEXT,
                recipe_total_carbs_g REAL,
                recipe_total_weight_g REAL,
                recipe_consumed_weight_g REAL,
                recipe_consumed_fraction REAL,
                amount_uncertainty_json TEXT NOT NULL,
                timing_uncertainty_json TEXT NOT NULL,
                evidence_hash TEXT,
                alcohol_present INTEGER NOT NULL DEFAULT 0,
                kinetics_v2 TEXT,
                deleted INTEGER NOT NULL DEFAULT 0,
                provenance_version TEXT NOT NULL,
                PRIMARY KEY(evidence_id, revision)
            )"""
        internal const val CARB_EVIDENCE_OWNER_INDEX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_carb_evidence_owner_known ON carb_evidence_v1(annotation_id, known_at_ms, revision)"
        internal const val CARB_EVIDENCE_SOURCE_INDEX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_carb_evidence_source_active ON carb_evidence_v1(source, deleted)"
        private const val CARB_EVIDENCE_COLUMNS =
            "evidence_id, revision, supersedes_revision, annotation_id, meal_session_id, " +
                "event_time_ms, intake_start_ms, intake_end_ms, known_at_ms, recorded_at_ms, " +
                "source, user_confirmed, total_carbs_g, label_carbs_per_100g, weighed_edible_g, " +
                "label_carbs_per_serving_g, servings, recipe_version, recipe_total_carbs_g, " +
                "recipe_total_weight_g, recipe_consumed_weight_g, recipe_consumed_fraction, " +
                "amount_uncertainty_json, timing_uncertainty_json, evidence_hash, alcohol_present, " +
                "kinetics_v2, deleted, provenance_version"

        /** Immutable value that was actually shown to the user at this sensor
         * timestamp. Raw sensor rows remain raw; changing calibration later can
         * no longer redraw the historical line. */
        private const val PRESENTED_GLUCOSE_DDL =
            """CREATE TABLE IF NOT EXISTS presented_glucose (
                ts_ms INTEGER PRIMARY KEY,
                mmol REAL NOT NULL,
                captured_at_ms INTEGER NOT NULL,
                source TEXT
            )"""

        // Tombstones: a user-deleted bolus must stay dead even though the
        // xDrip treatments poll keeps returning it.
        private const val DELETED_BOLUSES_DDL =
            """CREATE TABLE IF NOT EXISTS deleted_boluses (
                ts_ms INTEGER PRIMARY KEY
            )"""

        private const val BASAL_TABLE_DDL =
            """CREATE TABLE IF NOT EXISTS basal_events (
                ts_ms INTEGER PRIMARY KEY, units REAL
            )"""

        private const val MINUTE_TABLE_DDL =
            """CREATE TABLE IF NOT EXISTS minute_readings (
                ts_ms INTEGER PRIMARY KEY, mgdl REAL, mmol REAL, source TEXT
            )"""

        private const val HR_TABLE_DDL =
            """CREATE TABLE IF NOT EXISTS heart_rate (
                ts_ms INTEGER PRIMARY KEY, bpm REAL
            )"""

        private const val SLEEP_TABLE_DDL =
            """CREATE TABLE IF NOT EXISTS sleep_sessions (
                start_ms INTEGER PRIMARY KEY, end_ms INTEGER
            )"""

        private const val PEN_DOSES_DDL =
            """CREATE TABLE IF NOT EXISTS pen_doses (
                uuid TEXT PRIMARY KEY, ts_ms INTEGER
            )"""

        private const val FOOD_LIBRARY_DDL =
            """CREATE TABLE IF NOT EXISTS food_library (
                name TEXT PRIMARY KEY, grams REAL, comment TEXT,
                media_ref TEXT, analysis TEXT, components TEXT
            )"""

        // User name→concept-id overrides for the fingerprint model. The concept
        // archetypes live in code (FoodConcepts); this table only records the
        // user's hand-assignments, layered on top via setUserConceptAliases.
        private const val CONCEPT_ALIASES_DDL =
            """CREATE TABLE IF NOT EXISTS concept_aliases (
                alias TEXT PRIMARY KEY, concept_id TEXT
            )"""

        // v24 — the user's edits to the concept dictionary. Every column is
        // nullable = «keep whatever the built-in says»; a row whose id is NOT
        // built in IS a new concept (speed required, or it can't answer
        // anything). Density first: concepts are a carb-density table before
        // they are a model key, and density was the one field nobody could edit.
        private const val CONCEPT_OVERRIDES_DDL =
            """CREATE TABLE IF NOT EXISTS concept_overrides (
                concept_id TEXT PRIMARY KEY,
                carb_per_100g REAL, portion_g REAL,
                speed TEXT, fat TEXT, protein TEXT, fiber TEXT,
                aliases TEXT, created_ms INTEGER
            )"""

        private const val STEPS_TABLE_DDL =
            """CREATE TABLE IF NOT EXISTS steps (
                start_ms INTEGER PRIMARY KEY, end_ms INTEGER, count INTEGER,
                source TEXT
            )"""

        // v26 — the user's answer about a meal the model could not account for.
        //
        // A SEPARATE TABLE, not a column, and the reasoning is recorded on the interface
        // (`CollectorStore.mealMarks`): the meal is keyed by its NOTE-anchored session
        // start and often has no `meal_events` row at all; provenance and reversibility
        // need rows, not fields; and the primary record must stay untouched.
        //
        // APPEND-ONLY. Changing an answer revokes the previous row and inserts a new one,
        // so `SELECT * ORDER BY created_at_ms` reads back the sequence of what the user thought.
        // Nothing here is ever UPDATEd except `revoked_at_ms`.
        private const val MEAL_MARKS_DDL =
            """CREATE TABLE IF NOT EXISTS meal_marks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                onset_ms INTEGER NOT NULL,
                kind TEXT NOT NULL,
                comment TEXT,
                author TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                revoked_at_ms INTEGER
            )"""

        private const val MEAL_MARKS_INDEX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_meal_marks_onset ON meal_marks(onset_ms)"


        private const val METER_CALIBRATIONS_DDL =
            """CREATE TABLE IF NOT EXISTS meter_calibrations (
                sensor_key TEXT PRIMARY KEY,
                sensor_start_ms INTEGER NOT NULL,
                slope REAL NOT NULL,
                intercept_mmol REAL NOT NULL,
                n_checks INTEGER NOT NULL,
                transient_offset_mmol REAL,
                transient_check_ts_ms INTEGER,
                transient_half_life_ms INTEGER,
                source_count INTEGER NOT NULL,
                source_latest_ts_ms INTEGER NOT NULL,
                source_hash INTEGER NOT NULL,
                fitted_at_ms INTEGER NOT NULL
            )"""

        /**
         * Causal, append-only calibration history. `meter_calibrations` remains
         * the latest rebuild cache; these rows answer the different question
         * "which lens was known at this moment?". A later fingerstick must not
         * redraw an already observed night.
         */
        private const val METER_CALIBRATION_VERSIONS_DDL =
            """CREATE TABLE IF NOT EXISTS meter_calibration_versions (
                sensor_key TEXT NOT NULL,
                sensor_start_ms INTEGER NOT NULL,
                slope REAL NOT NULL,
                intercept_mmol REAL NOT NULL,
                n_checks INTEGER NOT NULL,
                transient_offset_mmol REAL,
                transient_check_ts_ms INTEGER,
                transient_half_life_ms INTEGER,
                source_count INTEGER NOT NULL,
                source_latest_ts_ms INTEGER NOT NULL,
                source_hash INTEGER NOT NULL,
                fitted_at_ms INTEGER NOT NULL,
                PRIMARY KEY(sensor_key, source_hash)
            )"""

        private val SCHEMA = listOf(
            PEN_DOSES_DDL,
            DELETED_BOLUSES_DDL,
            """CREATE TABLE IF NOT EXISTS glucose_readings (
                ts_ms INTEGER PRIMARY KEY, mgdl REAL, mmol REAL, trend TEXT, source TEXT
            )""",
            """CREATE TABLE IF NOT EXISTS insulin_events (
                ts_ms INTEGER PRIMARY KEY, units REAL, insulin_type TEXT, source TEXT, purpose TEXT,
                user_edited INTEGER DEFAULT 0
            )""",
            """CREATE TABLE IF NOT EXISTS annotations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms INTEGER, kind TEXT, content TEXT, media_ref TEXT, analysis TEXT,
                est_carbs REAL, carbs_known_at_ms INTEGER, carbs_source TEXT,
                analysis_known_at_ms INTEGER
            )""",
            """CREATE TABLE IF NOT EXISTS meal_events (
                onset_ms INTEGER PRIMARY KEY, peak_ms INTEGER, pre_bg REAL, peak_bg REAL,
                rise REAL, time_to_peak_min REAL, bolus_units REAL, kind TEXT, label_id INTEGER,
                label_assigned_at_ms INTEGER
            )""",
            """CREATE TABLE IF NOT EXISTS meal_labels (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, use_count INTEGER DEFAULT 0
            )""",
            MINUTE_TABLE_DDL,
            HR_TABLE_DDL,
            SLEEP_TABLE_DDL,
            STEPS_TABLE_DDL,
            BASAL_TABLE_DDL,
            FOOD_LIBRARY_DDL,
            CONCEPT_ALIASES_DDL,
            CONCEPT_OVERRIDES_DDL,
            MEAL_MARKS_DDL,
            MEAL_MARKS_INDEX_DDL,
            METER_CALIBRATIONS_DDL,
            METER_CALIBRATION_VERSIONS_DDL,
            PRESENTED_GLUCOSE_DDL,
            CARB_EVIDENCE_DDL,
            CARB_EVIDENCE_OWNER_INDEX_DDL,
            CARB_EVIDENCE_SOURCE_INDEX_DDL,
            CONTEXT_EXPOSURES_DDL,
            CONTEXT_EXPOSURES_INDEX_DDL,
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
        ForecastLedger.DDL.forEach(db::execSQL)
        PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        PhysioExperimentalLedger.ensureClosedInvalidationTracking(db)
        ensureModelInputRevisionTracking(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(MINUTE_TABLE_DDL)
        if (oldVersion < 3) {
            db.execSQL(HR_TABLE_DDL)
            db.execSQL(SLEEP_TABLE_DDL)
        }
        if (oldVersion < 4) {
            // v3 stored the OOP2 trend reversed and unquantized (near-duplicate
            // rows, sawtooth). Display-only cache — wipe, it refills in minutes.
            db.execSQL("DELETE FROM minute_readings")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE annotations ADD COLUMN analysis TEXT")
        }
        if (oldVersion < 6) {
            db.execSQL(BASAL_TABLE_DDL)
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE insulin_events ADD COLUMN purpose TEXT")
        }
        if (oldVersion < 8) adoptLegacyPurposeNotesV8(db)
        if (oldVersion < 9) {
            // Food notes get their own kind so they stop polluting the
            // context-note suggestions. Existing ones identified by matching
            // a known meal label.
            db.execSQL(
                "UPDATE annotations SET kind = 'food' " +
                    "WHERE content IN (SELECT name FROM meal_labels)",
            )
        }
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE annotations ADD COLUMN est_carbs REAL")
        }
        if (oldVersion < 11) {
            db.execSQL(PEN_DOSES_DDL)
        }
        if (oldVersion < 12) {
            db.execSQL(DELETED_BOLUSES_DDL)
            db.execSQL("ALTER TABLE insulin_events ADD COLUMN user_edited INTEGER DEFAULT 0")
        }
        if (oldVersion < 13) {
            db.execSQL(STEPS_TABLE_DDL)
        }
        if (oldVersion < 14) {
            // Provenance for the causal pipeline (can't be backfilled):
            // WHEN carbs became known and from what source; WHEN a label
            // was assigned. Old rows honestly stay NULL = unknown.
            db.execSQL("ALTER TABLE annotations ADD COLUMN carbs_known_at_ms INTEGER")
            db.execSQL("ALTER TABLE annotations ADD COLUMN carbs_source TEXT")
            db.execSQL("ALTER TABLE meal_events ADD COLUMN label_assigned_at_ms INTEGER")
        }
        if (oldVersion < 15) {
            // Prospective forecast ledger: immutable record of every forecast
            // actually shown, scored later against sensor facts.
            ForecastLedger.DDL.forEach(db::execSQL)
        }
        // v27 used to create the prospective "What if" A/B tables — seven of
        // them, written on every run. The apparatus was removed: none of
        // the three read functions had a caller. The migration step is left
        // empty so the migration numbering does not shift; the tables
        // themselves are dropped by `LedgerRetention.dropRetiredLedgers`.
        if (oldVersion < 28) db.execSQL(METER_CALIBRATIONS_DDL)
        if (oldVersion < 29) {
            db.execSQL(METER_CALIBRATION_VERSIONS_DDL)
            // Preserve the latest lens already present on v28. Its original
            // fitted_at timestamp is retained, so the causal boundary is not
            // moved to upgrade time.
            db.execSQL(
                """INSERT OR IGNORE INTO meter_calibration_versions
                   SELECT sensor_key, sensor_start_ms, slope, intercept_mmol, n_checks,
                          transient_offset_mmol, transient_check_ts_ms, transient_half_life_ms,
                          source_count, source_latest_ts_ms, source_hash, fitted_at_ms
                   FROM meter_calibrations""",
            )
        }
        if (oldVersion < 30) db.execSQL(PRESENTED_GLUCOSE_DDL)
        if (oldVersion < 31) migrateBeerIntakeDuration(db)
        if (oldVersion < 32) {
            // Instrumentation only: old annotation/source rows remain byte-for-byte
            // untouched and are never upgraded to measured evidence.
            db.execSQL(CARB_EVIDENCE_DDL)
            db.execSQL(CARB_EVIDENCE_OWNER_INDEX_DDL)
            db.execSQL(CARB_EVIDENCE_SOURCE_INDEX_DDL)
        }
        if (oldVersion < 33) PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        if (oldVersion < 34) db.execSQL("ALTER TABLE annotations ADD COLUMN analysis_known_at_ms INTEGER")
        if (oldVersion < 35) {
            db.execSQL(CONTEXT_EXPOSURES_DDL)
            db.execSQL(CONTEXT_EXPOSURES_INDEX_DDL)
            PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        }
        if (oldVersion < 36) {
            // Stage 7 auto-learning ledgers are additive and append-only.
            // No historical annotation/evidence row is rewritten.
            PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        }
        if (oldVersion < 37) ensureModelInputRevisionTracking(db)
        if (oldVersion < 38) PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        if (oldVersion < 39) PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        if (oldVersion < 40) PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        if (oldVersion < 40) PhysioExperimentalLedger.ensureClosedInvalidationTracking(db)
        if (oldVersion < 41) PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        if (oldVersion < 42) PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        if (oldVersion < 43) {
            // The strict food-era contract changes the admissible training
            // corpus, not the user's facts.  Discard only rebuildable learned
            // products made by older binaries; raw glucose, insulin, food,
            // sleep, activity, context and their arrival journals survive.
            PhysioExperimentalLedger.DDL.forEach(db::execSQL)
            PhysioExperimentalLedger.ensureClosedInvalidationTracking(db)
            db.execSQL("DROP TABLE IF EXISTS physio_learning_snapshots_v1")
            listOf(
                "physio_daily_checkpoints",
                "physio_factor_promotion_ledger",
                "physio_global_cs_state",
                "physio_closed_episodes",
                "physio_closed_isf_evidence_v1",
                "physio_closed_maintenance_v1",
                "physio_closed_run_status_v1",
                "physio_closed_input_invalidations_v1",
            ).forEach { table -> db.execSQL("DELETE FROM $table") }
            db.execSQL("UPDATE physio_closed_invalidation_state_v1 SET last_rowid=0 WHERE singleton=1")
        }
        if(oldVersion<44){
            runCatching{db.execSQL("ALTER TABLE carb_evidence_v1 ADD COLUMN kinetics_v2 TEXT")}
        }
        // The user's rulings on their own tagged corrections. Purely additive:
        // an empty table means «nothing ruled on», which is the default state.
        if(oldVersion<45)PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        if (oldVersion < 16) {
            // One-time cleanup of detector-drift siblings: live incremental
            // scans re-estimated a still-growing rise's onset a few minutes
            // apart and inserted duplicates (scanMeals now dedupes). Drop the
            // UNLABELED copies that start within 30 min after another meal —
            // user labels always survive.
            db.execSQL(
                """DELETE FROM meal_events WHERE label_id IS NULL AND EXISTS (
                    SELECT 1 FROM meal_events e2
                    WHERE e2.onset_ms < meal_events.onset_ms
                      AND meal_events.onset_ms - e2.onset_ms < 30 * 60 * 1000
                )""",
            )
        }
        if (oldVersion < 17) {
            // v16 spared LABELED siblings — but the auto-labeler had stamped
            // the SAME label onto every drift-duplicate (dextrose rebound ×3),
            // so they all survived. Same label within 30 min = the same event;
            // the earliest (fullest rise) wins. Distinct labels stay untouched.
            db.execSQL(
                """DELETE FROM meal_events WHERE EXISTS (
                    SELECT 1 FROM meal_events e2
                    WHERE e2.onset_ms < meal_events.onset_ms
                      AND meal_events.onset_ms - e2.onset_ms < 30 * 60 * 1000
                      AND (meal_events.label_id IS NULL
                           OR e2.label_id = meal_events.label_id)
                )""",
            )
        }
        if (oldVersion < 18) {
            // Un-label meals wrongly tagged with an ACTIVITY note (a walk
            // label with a duration suffix slipped past an exact-match filter — now
            // isContextNote-guarded). The rise itself is real (food still
            // absorbing), so keep the event but drop the bad label; it
            // returns to the queue / gets correctly auto-labeled.
            // Match a bare activity tag OR "tag · N min" — but NOT the system
            // label `ContextAnalysis.SPORT` (a legit non-food rise category),
            // which a naive prefix LIKE on that label's own name would wrongly catch.
            // Frozen: the activity words this step was written against (the data
            // it cleans predates v50's keys), not today's ACTIVITY_TAGS.
            val activityLike = LEGACY_V18_ACTIVITY_TAGS
                .joinToString(" OR ") { "lower(name) = '$it' OR lower(name) LIKE '$it · %'" }
            db.execSQL(
                """UPDATE meal_events SET label_id = NULL, label_assigned_at_ms = NULL
                   WHERE label_id IN (SELECT id FROM meal_labels WHERE $activityLike)""",
            )
            // And retire the orphaned activity "meal" labels themselves.
            db.execSQL(
                """DELETE FROM meal_labels WHERE ($activityLike)
                   AND id NOT IN (SELECT label_id FROM meal_events WHERE label_id IS NOT NULL)""",
            )
        }
        if (oldVersion < 19) {
            // EXCLUSIVE bolus↔meal pairing recompute: a shot between two
            // close meals was summed into BOTH (dinner + snack each claimed
            // it), inflating both dishes' effective doses. Each bolus now
            // belongs only to the NEAREST onset whose window (-45..+20 min)
            // covers it; kind follows. Detector fixed the same way for new
            // scans; this heals history (7 doubles + missed shots).
            // 'воздух' IS THE TOKEN A v18 DATABASE HOLDS. This step runs on
            // the data as it was before v50 converted purposes to keys (the
            // v50 step is kept last for exactly that reason), so the literal
            // is the correct one here and must not be "fixed" to the key —
            // `HistoricalMigrationLiteralsTest` shows what the wrong order
            // does. Live reads use `PRIME_PURPOSES_SQL` instead.
            db.execSQL(
                """UPDATE meal_events SET bolus_units = (
                    SELECT SUM(units) FROM insulin_events b
                    WHERE COALESCE(b.purpose,'') != 'воздух'
                      AND b.ts_ms BETWEEN meal_events.onset_ms - 2700000
                                      AND meal_events.onset_ms + 1200000
                      AND NOT EXISTS (
                        SELECT 1 FROM meal_events m2
                        WHERE m2.onset_ms != meal_events.onset_ms
                          AND b.ts_ms BETWEEN m2.onset_ms - 2700000
                                          AND m2.onset_ms + 1200000
                          AND ABS(b.ts_ms - m2.onset_ms) < ABS(b.ts_ms - meal_events.onset_ms)
                      )
                )""",
            )
            db.execSQL(
                """UPDATE meal_events SET kind =
                    CASE WHEN bolus_units IS NOT NULL THEN 'announced'
                         ELSE 'unannounced' END""",
            )
        }
        if (oldVersion < 20) {
            // Food library: user-curated reference grams/comments per dish —
            // overrides derived grams in prefill/autocomplete, lets a dish
            // exist BEFORE it was ever eaten.
            db.execSQL(FOOD_LIBRARY_DDL)
        }
        if (oldVersion == 20) {
            // v20 created the table without photo/LLM columns; earlier
            // versions get the full DDL above, so alter ONLY exactly-20.
            db.execSQL("ALTER TABLE food_library ADD COLUMN media_ref TEXT")
            db.execSQL("ALTER TABLE food_library ADD COLUMN analysis TEXT")
        }
        if (oldVersion in 20..21) {
            // Structured recipe: a composite dish keeps its TITLE (the
            // identity glycemic stats attach to) and a components list —
            // [{"n":"bread","c":2,"g":12}] — for carb arithmetic. Versions
            // below 20 got the full DDL above.
            db.execSQL("ALTER TABLE food_library ADD COLUMN components TEXT")
        }
        if (oldVersion < 23) {
            // User name→concept-id overrides for the fingerprint model.
            db.execSQL(CONCEPT_ALIASES_DDL)
        }
        if (oldVersion < 24) db.execSQL(CONCEPT_OVERRIDES_DDL)
        if (oldVersion < 25) {
            // The ledger scored a meter-calibrated forecast against a RAW fact,
            // costing every horizon the calibration itself (+1.65 mmol here).
            // The column records the lens applied to each fact; NULL marks the
            // legacy rows, which ForecastLedger.rescoreUncalibrated repairs on
            // the next reload (off the IO thread, not inside this open).
        }
        if (oldVersion < 26) {
            // Pure addition: an empty table changes nothing the model computes, so this
            // upgrade cannot move a forecast. That is the property that lets the anomaly
            // screen land without re-taking any measurement.
            db.execSQL(MEAL_MARKS_DDL)
            db.execSQL(MEAL_MARKS_INDEX_DDL)
        }
        if (oldVersion < 47) PhysioExperimentalLedger.DDL.forEach(db::execSQL)
        // v48 — STEP SOURCE. Before this, Health Connect summed records from two
        // apps into one total roughly double the truth (a third-party tracker's
        // daily count against a much larger one in the database). The column is added
        // empty: legacy rows differ by bucket duration, and `StepStream` can
        // handle that — but a reconstruction from an indirect signal is labeled
        // as such.
        if (oldVersion < 48) {
            runCatching { db.execSQL("ALTER TABLE steps ADD COLUMN source TEXT") }
        }
        if (oldVersion < 46) {
            // `applied` — the insulin curve and ISF the model was actually
            // using for this run. See [ForecastLedger.appliedSummary]: the tag
            // says which ALGORITHM ran, and on this device that has never been
            // the same question as what it BELIEVED. Pure addition; a NULL on
            // every existing row is honest, because those runs did not record
            // it.
            runCatching { db.execSQL("ALTER TABLE forecast_runs ADD COLUMN applied TEXT") }
        }
        // v49 — WHAT THE LINE ADDED UP TO. The engine computes four terms on
        // every step, but only the total was stored, so the question "why did
        // the run land where it did when mass-balance gives a different number"
        // could not be answered from an export — a stand-side reconstruction
        // came close but did not name the cause. NULL on old rows is honest:
        // those runs never recorded this.
        if (oldVersion < 49) {
            listOf("food_delta", "insulin_delta", "drift_delta", "background_delta")
                .forEach { col ->
                    runCatching { db.execSQL("ALTER TABLE forecast_points ADD COLUMN $col REAL") }
                }
        }
        // v50 — LANGUAGE-NEUTRAL STORED LABELS. Bolus purposes, tag-shaped notes
        // and system meal labels move from Russian tokens to English keys (see
        // [LabelMigrationV1] for exactly what is and is not converted). Kept LAST:
        // the historical steps above match the Russian tokens they were written
        // against (v8, v18, v19), so they must see the data before it changes.
        if (oldVersion < 50) LabelMigrationV1.migrate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (db.isReadOnly) return
        // A restored backup may carry any schema version, including one from a
        // build that never had the v50 step; converting on open covers it. When
        // everything is already a key this is a read-only check.
        LabelMigrationV1.migrate(db)
    }

    // ---- food library -----------------------------------------------------

    /** One recipe line: [grams] = carbs of ONE unit, null = unknown (falls
     *  back to the derived dictionary at use time). */
    data class RecipeItem(val name: String, val count: Int, val grams: Double?)

    /** A reference dish: user-curated grams/comment, optional photo,
     *  LLM parse and structured recipe — may exist before ever eaten.
     *  [grams] null + non-empty [components] ⇒ carbs are the recipe sum. */
    data class FoodLibEntry(
        val name: String,
        val grams: Double?,
        val comment: String?,
        val mediaRef: String? = null,
        val analysis: String? = null,
        val components: List<RecipeItem> = emptyList(),
    )

    private fun encodeRecipe(items: List<RecipeItem>): String? =
        items.takeIf { it.isNotEmpty() }?.let { list ->
            org.json.JSONArray().also { arr ->
                list.forEach { r ->
                    arr.put(
                        org.json.JSONObject()
                            .put("n", r.name)
                            .put("c", r.count)
                            .apply { r.grams?.let { put("g", it) } },
                    )
                }
            }.toString()
        }

    private fun decodeRecipe(raw: String?): List<RecipeItem> = try {
        raw?.let { org.json.JSONArray(it) }?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("n")
                if (n.isBlank()) null
                else RecipeItem(
                    name = n,
                    count = o.optInt("c", 1).coerceIn(1, 20),
                    grams = if (o.has("g")) o.optDouble("g").takeIf { !it.isNaN() } else null,
                )
            }
        }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    fun foodLibrary(): List<FoodLibEntry> =
        readableDatabase.rawQuery(
            "SELECT name, grams, comment, media_ref, analysis, components FROM food_library ORDER BY name",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        FoodLibEntry(
                            name = c.getString(0),
                            grams = if (c.isNull(1)) null else c.getDouble(1),
                            comment = if (c.isNull(2)) null else c.getString(2),
                            mediaRef = if (c.isNull(3)) null else c.getString(3),
                            analysis = if (c.isNull(4)) null else c.getString(4),
                            components = decodeRecipe(if (c.isNull(5)) null else c.getString(5)),
                        ),
                    )
                }
            }
        }

    fun upsertFoodLibrary(
        name: String,
        grams: Double?,
        comment: String?,
        mediaRef: String? = null,
        analysis: String? = null,
        components: List<RecipeItem> = emptyList(),
    ) {
        writableDatabase.execSQL(
            "INSERT INTO food_library (name, grams, comment, media_ref, analysis, components) " +
                "VALUES (?,?,?,?,?,?) " +
                "ON CONFLICT(name) DO UPDATE SET grams=excluded.grams, comment=excluded.comment, " +
                "media_ref=excluded.media_ref, analysis=excluded.analysis, components=excluded.components",
            arrayOf<Any?>(
                name.trim(), grams, comment?.ifBlank { null }, mediaRef, analysis,
                encodeRecipe(components),
            ),
        )
    }

    fun deleteFoodLibrary(name: String) {
        writableDatabase.execSQL("DELETE FROM food_library WHERE name = ?", arrayOf(name))
    }

    // ---- concept aliases (user name→concept-id overrides) -----------------

    /** Every user override, alias→concept-id. Feed to setUserConceptAliases. */
    fun conceptAliases(): Map<String, String> =
        readableDatabase.rawQuery("SELECT alias, concept_id FROM concept_aliases", null).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val a = c.getString(0); val id = c.getString(1)
                    if (!a.isNullOrBlank() && !id.isNullOrBlank()) put(a, id)
                }
            }
        }

    /** The user's edits to the concept dictionary (v24). */
    fun conceptOverrides(): List<com.diapilot.core.analysis.ConceptOverride> =
        readableDatabase.rawQuery(
            "SELECT concept_id, carb_per_100g, portion_g, speed, fat, protein, fiber, aliases " +
                "FROM concept_overrides", null,
        ).use { c ->
            buildList {
                fun lvl(s: String?) = s?.let {
                    runCatching { com.diapilot.core.analysis.MacroLevel.valueOf(it) }.getOrNull()
                }
                while (c.moveToNext()) {
                    add(
                        com.diapilot.core.analysis.ConceptOverride(
                            id = c.getString(0),
                            carbPer100g = if (c.isNull(1)) null else c.getDouble(1),
                            portionG = if (c.isNull(2)) null else c.getDouble(2),
                            carbSpeed = c.getString(3)?.let {
                                runCatching { com.diapilot.core.analysis.CarbSpeed.valueOf(it) }.getOrNull()
                            },
                            fat = lvl(c.getString(4)),
                            protein = lvl(c.getString(5)),
                            fiber = lvl(c.getString(6)),
                            aliasesRu = c.getString(7)?.split(",")
                                ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                        ),
                    )
                }
            }
        }



    fun upsertConceptAlias(alias: String, conceptId: String) {
        writableDatabase.execSQL(
            "INSERT INTO concept_aliases (alias, concept_id) VALUES (?,?) " +
                "ON CONFLICT(alias) DO UPDATE SET concept_id=excluded.concept_id",
            arrayOf(alias.trim().lowercase(), conceptId),
        )
    }

    fun deleteConceptAlias(alias: String) {
        writableDatabase.execSQL(
            "DELETE FROM concept_aliases WHERE alias = ?", arrayOf(alias.trim().lowercase()),
        )
    }

    /** Rename a dish everywhere it is a KEY: meal label + library row.
     *  Refuses (false) when the target label already exists — merging two
     *  dish histories is a deliberate action, not a rename side effect. */
    fun renameDish(oldName: String, newName: String): Boolean {
        val nn = newName.trim()
        if (nn.isEmpty() || nn.equals(oldName, ignoreCase = false)) return false
        val db = writableDatabase
        val clash = db.rawQuery(
            "SELECT COUNT(*) FROM meal_labels WHERE name = ?", arrayOf(nn),
        ).use { it.moveToFirst(); it.getLong(0) } > 0
        if (clash) return false
        db.execSQL("UPDATE meal_labels SET name = ? WHERE name = ?", arrayOf(nn, oldName))
        db.execSQL(
            "UPDATE OR IGNORE food_library SET name = ? WHERE name = ?", arrayOf(nn, oldName),
        )
        // Food NOTES carry the dish identity too (auto-label, history rows,
        // the library's notes-derived listing) — rename them along, exact
        // full-content matches only.
        db.execSQL(
            "UPDATE annotations SET content = ? WHERE content = ? AND kind = 'food'",
            arrayOf(nn, oldName),
        )
        return true
    }

    /**
     * MERGE two dishes into one identity ([from] → [into]) — the deliberate,
     * DESTRUCTIVE opposite of [renameDish]'s clash refusal. Use ONLY for true
     * duplicates (same dish under two names): the two episode histories become
     * one. Repoints meal_events to the surviving label, folds notes, drops the
     * dead label and its library row. No-op self-merge / empty target.
     */
    fun mergeDish(from: String, into: String): Boolean {
        val target = into.trim()
        if (target.isEmpty() || target.equals(from, ignoreCase = false)) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            fun labelId(name: String): Long? = db.rawQuery(
                "SELECT id FROM meal_labels WHERE name = ?", arrayOf(name),
            ).use { if (it.moveToFirst()) it.getLong(0) else null }

            val fromId = labelId(from)
            // Ensure the target label exists so meal_events can repoint to it.
            var intoId = labelId(target)
            if (intoId == null) {
                db.execSQL("INSERT INTO meal_labels (name, use_count) VALUES (?, 0)", arrayOf(target))
                intoId = labelId(target)
            }
            if (fromId != null && intoId != null && fromId != intoId) {
                db.execSQL(
                    "UPDATE meal_events SET label_id = ? WHERE label_id = ?",
                    arrayOf(intoId, fromId),
                )
                db.execSQL("DELETE FROM meal_labels WHERE id = ?", arrayOf(fromId))
            }
            // Notes and the dead library row follow the identity.
            db.execSQL(
                "UPDATE annotations SET content = ? WHERE content = ? AND kind = 'food'",
                arrayOf(target, from),
            )
            db.execSQL("DELETE FROM food_library WHERE name = ?", arrayOf(from))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    /**
     * Copy a consistent snapshot of the database into [out]. WAL is
     * checkpointed first so the single main file carries everything.
     */
    fun exportSnapshot(out: java.io.OutputStream) {
        val db = writableDatabase
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        java.io.File(db.path).inputStream().use { it.copyTo(out) }
    }

    // --- hard core -------------------------------------------------------

    /**
     * ONE SOURCE OWNS A TIMESTAMP.
     *
     * The upsert used to rewrite whatever sat at `ts_ms` whoever wrote it, so a
     * single forged broadcast could restate an hour of history at timestamps of
     * its choosing. A writer may now only revise its OWN row: the `DO UPDATE`
     * carries `WHERE source IS excluded.source` (`IS`, not `=`, so a legacy row
     * with a NULL source matches a NULL source and nothing else), and a
     * different source finds the slot taken and is dropped.
     *
     * The first writer therefore wins, which is also what the 5-minute grid is
     * documented to be — the FROZEN real-time record, with the web-service
     * backfill filling gaps rather than restating landed points.
     *
     * The arrival row is written under the same condition: it records that a
     * fact was OBSERVED, and a value that was not stored was not observed by
     * the model.
     */
    override fun upsertReading(reading: Reading) {
        val db = writableDatabase
        db.execSQL(
            "INSERT INTO glucose_readings VALUES (?,?,?,?,?) " +
                "ON CONFLICT(ts_ms) DO UPDATE SET mgdl=excluded.mgdl, mmol=excluded.mmol " +
                "WHERE glucose_readings.source IS excluded.source",
            arrayOf<Any?>(reading.tsMs, reading.mgdl, reading.mmol, reading.trend, reading.source),
        )
        val observed = System.currentTimeMillis()
        val live =
            reading.source != "libre_nfc" && kotlin.math.abs(observed - reading.tsMs) <= 10 * 60_000L
        db.execSQL(
            "INSERT OR IGNORE INTO physio_cgm_arrivals_v2(ts_ms,fact_hash,observed_at_ms,source,live) " +
                "SELECT ?,?,?,?,? WHERE EXISTS " +
                "(SELECT 1 FROM glucose_readings WHERE ts_ms=? AND source IS ?)",
            arrayOf<Any?>(
                reading.tsMs,
                PhysioArrivalIdentity.glucose(reading),
                observed,
                reading.source,
                if (live) 1 else 0,
                reading.tsMs,
                reading.source,
            )
        )
    }

    override fun backfillMainReading(reading: Reading, toleranceMs: Long): Boolean {
        val db = writableDatabase
        // Atomic: the tolerance check + insert run in one transaction so a
        // concurrent xDrip write can't slip a point in between them. INSERT OR
        // IGNORE additionally guards the exact-ts primary key against a
        // same-ms write (no crash). Returns whether a row was actually added.
        // A real point (any source) within tolerance already owns this slot —
        // NFC's coarse 15-minute backfill must not clobber it.
        // Numbers are inlined as integer LITERALS (our own Longs, no injection):
        // bound as string args, SQLite compares `numeric <= 'text'`, which is
        // ALWAYS true (a number sorts before any text) — that check then
        // skipped every point and nothing was ever backfilled.
        db.beginTransaction()
        try {
            val exists =
                android.database.DatabaseUtils.longForQuery(
                    db,
                    "SELECT EXISTS(SELECT 1 FROM glucose_readings " +
                        "WHERE ABS(ts_ms - ${reading.tsMs}) <= $toleranceMs)",
                    null,
                )
            if (exists != 0L) {
                db.setTransactionSuccessful()
                return false
            }
            db.execSQL(
                "INSERT OR IGNORE INTO glucose_readings VALUES (?,?,?,?,?)",
                arrayOf<Any?>(reading.tsMs, reading.mgdl, reading.mmol, reading.trend, reading.source),
            )
            val inserted = android.database.DatabaseUtils.longForQuery(db, "SELECT changes()", null) > 0
            if (inserted)
                db.execSQL(
                    "INSERT OR IGNORE INTO physio_cgm_arrivals_v2(ts_ms,fact_hash,observed_at_ms,source,live) VALUES(?,?,?,?,0)",
                    arrayOf<Any?>(
                        reading.tsMs,
                        PhysioArrivalIdentity.glucose(reading),
                        System.currentTimeMillis(),
                        reading.source
                    )
                )
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    /**
     * A CHEAP FINGERPRINT OF EVERYTHING THE HISTORY SCREEN IS BUILT FROM.
     *
     * The journal used to be re-read and rebuilt on a five-minute timer, which
     * flickered it under the reader for no reason: those facts only change when
     * the user logs, edits or deletes something. This answers «did they change» with
     * one query of counts and newest timestamps, so the expensive projection can
     * be triggered by the data instead of by the clock.
     *
     * Counts as well as maxima, because a DELETE moves no maximum. Meals are
     * keyed by `onset_ms`, and boluses live in `insulin_events` — the table
     * names were checked against a real database rather than guessed; the
     * first draft asked for a `bolus_events` that has never existed, and it
     * would have thrown on every tick.
     */
    fun historyFactsStamp(): String = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM annotations), (SELECT IFNULL(MAX(ts_ms),0) FROM annotations), " +
            "(SELECT COUNT(*) FROM insulin_events), (SELECT IFNULL(MAX(ts_ms),0) FROM insulin_events), " +
            "(SELECT COUNT(*) FROM meal_events), (SELECT IFNULL(MAX(onset_ms),0) FROM meal_events), " +
            "(SELECT COUNT(*) FROM basal_events), (SELECT IFNULL(MAX(ts_ms),0) FROM basal_events)",
        null,
    ).use { c ->
        if (!c.moveToFirst()) "" else (0 until c.columnCount).joinToString("|") { c.getLong(it).toString() }
    }

    /**
     * THE DEVICE-VALIDITY CEILING BELONGS ON THE HISTORY TOO.
     *
     * `1.0 .. 35.0` has guarded ANCHOR selection since the activation-high fix,
     * but not the series the trend is computed from — so «what is the glucose
     * now» was protected and «what has it been doing» was not.
     *
     * Measured on real data. A couple of readings out of tens of thousands carry a
     * mg/dL number in the mmol column, both from `libre_nfc`, i.e. values roughly
     * 18x too large, unconverted. One of them sat shortly before an anchor
     * reading, so the trend read a wildly steep slope and extrapolated it:
     * hundreds of stored forecast points below zero, well negative at the
     * minimum, across many runs, a meaningful share of them in the hypo-alert pass.
     *
     * The floor added the same day stops the trajectory; this stops the input.
     * Both are needed — a floor alone would keep feeding the trend a lie.
     */
    private val VALID_MMOL = "AND mmol BETWEEN 1.0 AND 35.0 "
    override fun sensorReadings(fromMs: Long, toMs: Long): List<GlucosePoint> =
        readableDatabase.rawQuery(
            "SELECT ts_ms, mmol FROM glucose_readings WHERE ts_ms BETWEEN ? AND ? " +
                VALID_MMOL +
                "AND (source IS NULL OR source != 'meter') ORDER BY ts_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(GlucosePoint(c.getLong(0), c.getDouble(1))) }
        }

    override fun sensorReadingsWithSource(fromMs: Long, toMs: Long): List<Pair<GlucosePoint, String?>> =
        readableDatabase.rawQuery(
            "SELECT ts_ms, mmol, source FROM glucose_readings WHERE ts_ms BETWEEN ? AND ? " +
                VALID_MMOL +
                "AND (source IS NULL OR source != 'meter') ORDER BY ts_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(GlucosePoint(c.getLong(0), c.getDouble(1)) to c.getString(2))
                }
            }
        }

    override fun backfilledReadingTimestamps(fromMs: Long, toMs: Long): Set<Long> =
        readableDatabase.rawQuery(
            "SELECT ts_ms FROM glucose_readings WHERE source = 'libre_nfc' " +
                "AND ts_ms BETWEEN ? AND ?",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }

    override fun penDoseSeen(uuid: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM pen_doses WHERE uuid = ?", arrayOf(uuid),
        ).use { it.moveToFirst() }

    override fun markPenDose(uuid: String, tsMs: Long) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO pen_doses (uuid, ts_ms) VALUES (?, ?)",
            arrayOf<Any?>(uuid, tsMs),
        )
    }

    override fun nonSyncInsulinTs(fromMs: Long): Set<Long> =
        readableDatabase.rawQuery(
            "SELECT ts_ms FROM insulin_events WHERE ts_ms >= ? " +
                "AND source IS NOT NULL AND source != 'xdrip_treatments'",
            arrayOf(fromMs.toString()),
        ).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    /**
     * One dose per timestamp, and the slot belongs to whoever wrote it first.
     *
     * The same rule [upsertReading] applies to glucose: a re-sync from the
     * SAME source revises its own row (xDrip corrects a treatment, the poll
     * fetches it again), a write from a DIFFERENT source at an occupied
     * timestamp is dropped, and units the user edited by hand
     * ([setBolusUnits]) survive both. Without the source clause a bolus was
     * rewritable by anything that knew its timestamp — the watch socket, the
     * loopback poll, an import — and a dose the forecast and the hypo alert
     * are computed from is not a value one source may overwrite for another
     * (`SECURITY.md`, "one source cannot silently rewrite another's history").
     *
     * First writer wins, on purpose. The one legitimate collision is a
     * NovoPen dose seen twice, by DiaPilot's own NFC scan (`pen_nfc`) and by
     * the pen history xDrip scanned (`xdrip_treatments`), and both paths
     * already de-duplicate across sources by units within a few minutes
     * (`PenDoseSaver`, [dedupeSyncedBoluses]); an exact same-millisecond twin
     * would now be dropped here instead of counted once — never twice.
     *
     * The sources in play: `xdrip_treatments` (the poll), `watch`
     * (`POST /add_treatments`), `pen_nfc`, `manual` (typed and the confirmed
     * command), and `xdrip_import` (a historical one-shot import). Tombstoned
     * shots stay dead for every source: the poll re-fetches deleted
     * treatments forever, and the user's delete must win.
     */
    override fun upsertInsulin(event: InsulinEvent) {
        val db = writableDatabase
        db.execSQL(
            // Named columns: purpose and user-edited units are user-owned and
            // must survive re-syncs.
            "INSERT INTO insulin_events (ts_ms, units, insulin_type, source) " +
                "SELECT ?1, ?2, ?3, ?4 WHERE NOT EXISTS " +
                "(SELECT 1 FROM deleted_boluses WHERE ts_ms = ?1) " +
                "ON CONFLICT(ts_ms) DO UPDATE SET units = CASE " +
                "WHEN insulin_events.user_edited = 1 THEN insulin_events.units " +
                "ELSE excluded.units END " +
                "WHERE insulin_events.source IS excluded.source",
            arrayOf<Any?>(event.tsMs, event.units, event.insulinType, event.source),
        )
        val observedAtMs = System.currentTimeMillis()
        val live = kotlin.math.abs(observedAtMs - event.tsMs) <= 15 * 60_000L
        val stored =
            db.rawQuery(
                    "SELECT units,insulin_type,source FROM insulin_events WHERE ts_ms=?",
                    arrayOf(event.tsMs.toString())
                )
                .use { c ->
                    if (c.moveToFirst()) Triple(c.getDouble(0), c.getString(1), c.getString(2))
                    else null
                }
        stored?.let {
            db.execSQL(
                "INSERT OR IGNORE INTO physio_bolus_arrivals_v2(ts_ms,fact_hash,observed_at_ms,source,live) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(
                    event.tsMs,
                    PhysioArrivalIdentity.bolus(event.tsMs, it.first, it.second, it.third),
                    observedAtMs,
                    it.third,
                    if (live) 1 else 0
                )
            )
        }
    }

    override fun setBolusUnits(tsMs: Long, units: Double) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE insulin_events SET units = ?, user_edited = 1 WHERE ts_ms = ?",
            arrayOf<Any?>(units, tsMs),
        )
        val row =
            db.rawQuery(
                    "SELECT insulin_type,source FROM insulin_events WHERE ts_ms=?",
                    arrayOf(tsMs.toString())
                )
                .use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
        row?.let {
            val observed = System.currentTimeMillis();
            db.execSQL(
                "INSERT OR IGNORE INTO physio_bolus_arrivals_v2(ts_ms,fact_hash,observed_at_ms,source,live) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(
                    tsMs,
                    PhysioArrivalIdentity.bolus(tsMs, units, it.first, it.second),
                    observed,
                    it.second,
                    if (kotlin.math.abs(observed - tsMs) <= 15 * 60_000L) 1 else 0
                )
            )
        }
    }

    /** A successful Health Connect steps query proves that an empty interval
     * means no recorded steps, rather than missing permission/sync. */
    internal fun recordActivityCoverage(
        fromMs: Long,
        toMs: Long,
        observedAtMs: Long,
        source: String = "health_connect_steps"
    ) {
        require(toMs >= fromMs)
        val db = writableDatabase
        db.beginTransaction();
        try {
            val merged =
                db.rawQuery(
                        "SELECT MIN(from_ms),MAX(to_ms) FROM physio_activity_coverage_v1 WHERE from_ms<=? AND to_ms>=?",
                        arrayOf(toMs.toString(), fromMs.toString())
                    )
                    .use { c ->
                        if (c.moveToFirst() && !c.isNull(0))
                            minOf(fromMs, c.getLong(0)) to maxOf(toMs, c.getLong(1))
                        else fromMs to toMs
                    }
            // Append the consolidated interval; older rows remain immutable.
            db.execSQL(
                "INSERT OR IGNORE INTO physio_activity_coverage_v1(from_ms,to_ms,observed_at_ms,source) VALUES(?,?,?,?)",
                arrayOf<Any?>(merged.first, merged.second, observedAtMs, source)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun setBolusPurpose(tsMs: Long, purpose: String?) {
        // Stored as the language-neutral key; a word in any language is accepted.
        writableDatabase.execSQL(
            "UPDATE insulin_events SET purpose = ? WHERE ts_ms = ?",
            arrayOf<Any?>(com.diapilot.core.analysis.BolusPurpose.canonical(purpose), tsMs),
        )
    }

    override fun addBolusWithPurpose(
        tsMs: Long,
        units: Double,
        purpose: String?,
        source: String,
    ) {
        // One transaction: the purpose UPDATE can never observe a database
        // without its bolus row (or be reordered against another writer).
        val db = writableDatabase
        db.beginTransaction()
        try {
            upsertInsulin(InsulinEvent(tsMs, units, "bolus", source = source))
            if (purpose != null) setBolusPurpose(tsMs, purpose)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun deleteInsulin(tsMs: Long) {
        writableDatabase.execSQL("DELETE FROM insulin_events WHERE ts_ms = ?", arrayOf(tsMs))
    }

    override fun deleteInsulinForever(tsMs: Long) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO deleted_boluses (ts_ms) VALUES (?)", arrayOf<Any?>(tsMs))
        db.execSQL("DELETE FROM insulin_events WHERE ts_ms = ?", arrayOf<Any?>(tsMs))
    }

    override fun deleteReading(tsMs: Long) {
        writableDatabase.execSQL("DELETE FROM glucose_readings WHERE ts_ms = ?", arrayOf<Any?>(tsMs))
    }

    override fun meterReadings(fromMs: Long, toMs: Long): List<Reading> =
        readableDatabase.rawQuery(
            "SELECT ts_ms, mgdl, mmol FROM glucose_readings " +
                "WHERE source = 'meter' AND ts_ms BETWEEN ? AND ? ORDER BY ts_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Reading(c.getLong(0), c.getDouble(1), c.getDouble(2), trend = null, source = "meter"))
                }
            }
        }

    override fun dedupeSyncedBoluses(windowMs: Long): Int {
        val db = writableDatabase
        // If the synced copy carries a user-set purpose the non-sync twin
        // lacks, move it over before the row dies.
        // xDrip-origin rows: the live treatments sync and the one-shot DB
        // import. Everything else (pen_nfc, manual, watch) is first-party.
        val sync = "(source IS NULL OR source IN ('xdrip_treatments','xdrip_import'))"
        val own = "(source IS NOT NULL AND source NOT IN ('xdrip_treatments','xdrip_import'))"
        db.execSQL(
            """UPDATE insulin_events SET purpose = (
                SELECT s.purpose FROM insulin_events s
                WHERE $sync
                  AND s.purpose IS NOT NULL
                  AND ABS(s.ts_ms - insulin_events.ts_ms) <= ?1
                  AND ABS(s.units - insulin_events.units) < 0.05 LIMIT 1
            ) WHERE purpose IS NULL AND $own""",
            arrayOf<Any?>(windowMs),
        )
        db.execSQL(
            """DELETE FROM insulin_events
               WHERE $sync
                 AND EXISTS (
                    SELECT 1 FROM insulin_events e
                    WHERE e.source IS NOT NULL
                      AND e.source NOT IN ('xdrip_treatments','xdrip_import')
                      AND ABS(e.ts_ms - insulin_events.ts_ms) <= ?1
                      AND ABS(e.units - insulin_events.units) < 0.05
                 )""",
            arrayOf<Any?>(windowMs),
        )
        return android.database.DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
    }

    // --- per-minute raw stream (display only) -------------------------------

    override fun upsertMinuteReading(reading: Reading) {
        writableDatabase.execSQL(
            "INSERT INTO minute_readings VALUES (?,?,?,?) " +
                "ON CONFLICT(ts_ms) DO UPDATE SET mgdl=excluded.mgdl, mmol=excluded.mmol",
            arrayOf<Any?>(reading.tsMs, reading.mgdl, reading.mmol, reading.source),
        )
    }

    override fun minuteReadings(fromMs: Long, toMs: Long): List<GlucosePoint> =
        readableDatabase.rawQuery(
            "SELECT ts_ms, mmol FROM minute_readings WHERE ts_ms BETWEEN ? AND ? " +
                "AND mmol BETWEEN 1.0 AND 35.0 ORDER BY ts_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(GlucosePoint(c.getLong(0), c.getDouble(1))) }
        }

    override fun lastMinuteReading(): Reading? =
        readableDatabase.rawQuery(
            "SELECT ts_ms, mgdl, mmol, source FROM minute_readings " +
                "WHERE mmol BETWEEN 1.0 AND 35.0 ORDER BY ts_ms DESC LIMIT 1",
            null,
        ).use { c ->
            if (!c.moveToFirst()) null
            else Reading(c.getLong(0), c.getDouble(1), c.getDouble(2), trend = null, source = c.getString(3) ?: "oop2_ble")
        }

    // --- basal insulin (logged here, not in xDrip) ---------------------------

    override fun upsertBasal(tsMs: Long, units: Double) {
        writableDatabase.execSQL(
            "INSERT INTO basal_events VALUES (?,?) ON CONFLICT(ts_ms) DO UPDATE SET units=excluded.units",
            arrayOf<Any?>(tsMs, units),
        )
    }

    override fun basalEvents(fromMs: Long, toMs: Long): List<BolusPoint> =
        readableDatabase.rawQuery(
            "SELECT ts_ms, units FROM basal_events WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(BolusPoint(c.getLong(0), c.getDouble(1))) }
        }

    override fun deleteBasal(tsMs: Long) {
        writableDatabase.execSQL("DELETE FROM basal_events WHERE ts_ms = ?", arrayOf(tsMs))
    }

    // --- physiology (Health Connect) ----------------------------------------

    override fun upsertHeartRate(point: HrPoint) {
        writableDatabase.execSQL(
            "INSERT INTO heart_rate VALUES (?,?) ON CONFLICT(ts_ms) DO UPDATE SET bpm=excluded.bpm",
            arrayOf<Any?>(point.tsMs, point.bpm),
        )
    }

    override fun heartRate(fromMs: Long, toMs: Long): List<HrPoint> =
        readableDatabase.rawQuery(
            "SELECT ts_ms, bpm FROM heart_rate WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(HrPoint(c.getLong(0), c.getDouble(1))) }
        }

    override fun lastHeartRate(): HrPoint? =
        readableDatabase.rawQuery(
            "SELECT ts_ms, bpm FROM heart_rate ORDER BY ts_ms DESC LIMIT 1", null,
        ).use { c -> if (c.moveToFirst()) HrPoint(c.getLong(0), c.getDouble(1)) else null }

    override fun upsertSteps(bucket: com.diapilot.core.collector.StepBucket) {
        writableDatabase.execSQL(
            "INSERT INTO steps VALUES (?,?,?,?) " +
                "ON CONFLICT(start_ms) DO UPDATE SET end_ms=excluded.end_ms, " +
                "count=excluded.count, source=excluded.source",
            arrayOf(bucket.startMs, bucket.endMs, bucket.count, bucket.source),
        )
    }

    /**
     * ONE SOURCE, NOT THE SUM OF ALL — the rule lives here so six
     * readers get it without each one being edited.
     *
     * This used to return a mixture: Health Connect hands back records from
     * every app, their bucket boundaries differ, `start_ms` is the primary key,
     * so nothing got overwritten and everything got summed. The user caught this
     * by comparing against another tracker and finding roughly double the step
     * count. See [StepStream] — it has the table, the measurement, and why the
     * choice goes by interval count rather than by sum.
     */
    override fun steps(fromMs: Long, toMs: Long): List<com.diapilot.core.collector.StepBucket> =
        com.diapilot.core.collector.StepStream.choose(stepsRaw(fromMs, toMs))

    /** The mixture as it lies in the table — for diagnostics and for the choice itself. */
    internal fun stepsRaw(fromMs: Long, toMs: Long): List<com.diapilot.core.collector.StepBucket> =
        readableDatabase.rawQuery(
            "SELECT start_ms, end_ms, count, source FROM steps " +
                "WHERE end_ms >= ? AND start_ms <= ? ORDER BY start_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        com.diapilot.core.collector.StepBucket(
                            c.getLong(0), c.getLong(1), c.getLong(2),
                            if (c.isNull(3)) null else c.getString(3),
                        ),
                    )
                }
            }
        }

    override fun upsertSleepSession(session: SleepSession) {
        writableDatabase.execSQL(
            "INSERT INTO sleep_sessions VALUES (?,?) ON CONFLICT(start_ms) DO UPDATE SET end_ms=excluded.end_ms",
            arrayOf<Any?>(session.startMs, session.endMs),
        )
    }

    override fun sleepSessions(fromMs: Long, toMs: Long): List<SleepSession> =
        readableDatabase.rawQuery(
            "SELECT start_ms, end_ms FROM sleep_sessions WHERE end_ms >= ? AND start_ms <= ? ORDER BY start_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(SleepSession(c.getLong(0), c.getLong(1))) }
        }

    override fun appendContextExposure(exposure: ContextExposureV1): Boolean {
        val values = ContentValues().apply {
            put("exposure_id", exposure.exposureId); put("source_type", exposure.sourceType)
            put("event_start_ms", exposure.eventStartMs); put("event_end_ms", exposure.eventEndMs)
            put("known_at_ms", exposure.knownAtMs); put("recorded_at_ms", exposure.recordedAtMs)
            if (exposure.value == null) putNull("value") else put("value", exposure.value)
            if (exposure.unit == null) putNull("unit") else put("unit", exposure.unit)
            put("context_json", exposure.contextJson)
        }
        return writableDatabase.insertWithOnConflict(
            "physio_context_exposures_v1", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    override fun contextExposures(sourceType: String, fromMs: Long, toMs: Long, asOfMs: Long): List<ContextExposureV1> =
        readableDatabase.rawQuery(
            """SELECT exposure_id,source_type,event_start_ms,event_end_ms,known_at_ms,recorded_at_ms,value,unit,context_json
               FROM physio_context_exposures_v1
               WHERE source_type=? AND event_end_ms>=? AND event_start_ms<=? AND known_at_ms<=?
               ORDER BY event_start_ms,known_at_ms,exposure_id""",
            arrayOf(sourceType, fromMs.toString(), toMs.toString(), asOfMs.toString()),
        ).use { c -> buildList {
            while (c.moveToNext()) add(ContextExposureV1(
                c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5),
                if (c.isNull(6)) null else c.getDouble(6), if (c.isNull(7)) null else c.getString(7), c.getString(8),
            ))
        } }

    fun upsertMeterCalibration(record: MeterCalibrationRecord) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("sensor_key", record.sensorKey)
            put("sensor_start_ms", record.sensorStartMs)
            put("slope", record.slope)
            put("intercept_mmol", record.interceptMmol)
            put("n_checks", record.nChecks)
            put("transient_offset_mmol", record.transientOffsetMmol)
            put("transient_check_ts_ms", record.transientCheckTsMs)
            put("transient_half_life_ms", record.transientHalfLifeMs)
            put("source_count", record.sourceCount)
            put("source_latest_ts_ms", record.sourceLatestTsMs)
            put("source_hash", record.sourceHash)
            put("fitted_at_ms", record.fittedAtMs)
        }
        db.insertWithOnConflict(
            "meter_calibrations",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        db.insertWithOnConflict(
            "meter_calibration_versions",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /**
     * One-time diary correction requested for the existing beer history.
     *
     * Beer was logged as an instantaneous carb event even though a serving is
     * normally consumed over roughly half an hour.  Keep every original field
     * and analysis line, replacing only the machine-readable intake duration.
     * New databases do not run upgrades; new meals use the editor field.
     */
    private fun migrateBeerIntakeDuration(db: SQLiteDatabase) {
        val candidates = mutableListOf<Pair<Long, String?>>()
        db.query(
            "annotations",
            arrayOf("id", "content", "analysis"),
            "kind = 'food' OR est_carbs IS NOT NULL",
            null, null, null, null,
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val contentIndex = cursor.getColumnIndexOrThrow("content")
            val analysisIndex = cursor.getColumnIndexOrThrow("analysis")
            while (cursor.moveToNext()) {
                val content = cursor.getString(contentIndex).orEmpty()
                val analysis = cursor.getString(analysisIndex)
                val searchable = "$content\n${analysis.orEmpty()}".lowercase()
                // Historical note tokens, matched as the diary held them at
                // v30 — data for this one-time step, not UI text and not a
                // reader anything live goes through.
                if (
                    "пив" in searchable || "паулан" in searchable ||
                    "paulan" in searchable || "beer" in searchable
                ) {
                    candidates += cursor.getLong(idIndex) to analysis
                }
            }
        }
        candidates.forEach { (id, oldAnalysis) ->
            val kept = oldAnalysis.orEmpty().lineSequence()
                .filterNot { it.trim().startsWith("META_DURATION_MIN:", ignoreCase = true) }
                .joinToString("\n").trim()
            val updated = listOf(kept, "META_DURATION_MIN: 30")
                .filter(String::isNotBlank)
                .joinToString("\n")
            db.execSQL(
                "UPDATE annotations SET analysis = ? WHERE id = ?",
                arrayOf<Any?>(updated, id),
            )
        }
        android.util.Log.i("DbMigration", "v31: beer duration=30 min on ${candidates.size} meals")
    }

    /** First displayed value wins: this is an audit snapshot, not another
     * recalculating cache. */
    fun rememberPresentedGlucose(
        tsMs: Long,
        mmol: Double,
        source: String?,
        capturedAtMs: Long = System.currentTimeMillis(),
    ) {
        val db=writableDatabase
        db.execSQL(
            "INSERT OR IGNORE INTO presented_glucose(ts_ms, mmol, captured_at_ms, source) " +
                "VALUES (?,?,?,?)",
            arrayOf<Any?>(tsMs, mmol, capturedAtMs, source),
        )
    }

    fun presentedGlucose(fromMs: Long, toMs: Long): List<GlucosePoint> =
        readableDatabase.rawQuery(
            "SELECT ts_ms, mmol FROM presented_glucose WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(GlucosePoint(c.getLong(0), c.getDouble(1))) }
        }

    fun ensureMeterCalibrationVersion(record: MeterCalibrationRecord) {
        writableDatabase.insertWithOnConflict(
            "meter_calibration_versions",
            null,
            ContentValues().apply {
                put("sensor_key", record.sensorKey)
                put("sensor_start_ms", record.sensorStartMs)
                put("slope", record.slope)
                put("intercept_mmol", record.interceptMmol)
                put("n_checks", record.nChecks)
                put("transient_offset_mmol", record.transientOffsetMmol)
                put("transient_check_ts_ms", record.transientCheckTsMs)
                put("transient_half_life_ms", record.transientHalfLifeMs)
                put("source_count", record.sourceCount)
                put("source_latest_ts_ms", record.sourceLatestTsMs)
                put("source_hash", record.sourceHash)
                put("fitted_at_ms", record.fittedAtMs)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun meterCalibration(sensorKey: String): MeterCalibrationRecord? =
        meterCalibrations("WHERE sensor_key = ?", arrayOf(sensorKey)).firstOrNull()

    fun deleteMeterCalibration(sensorKey: String) {
        writableDatabase.delete("meter_calibrations", "sensor_key = ?", arrayOf(sensorKey))
    }

    fun meterCalibrations(): List<MeterCalibrationRecord> =
        meterCalibrations("", emptyArray())

    fun meterCalibrationVersions(): List<MeterCalibrationRecord> =
        meterCalibrationRows("meter_calibration_versions", "", emptyArray(), "fitted_at_ms")

    private fun meterCalibrations(
        where: String,
        args: Array<String>,
    ): List<MeterCalibrationRecord> =
        meterCalibrationRows("meter_calibrations", where, args, "sensor_start_ms")

    private fun meterCalibrationRows(
        table: String,
        where: String,
        args: Array<String>,
        orderBy: String,
    ): List<MeterCalibrationRecord> = readableDatabase.rawQuery(
        """SELECT sensor_key, sensor_start_ms, slope, intercept_mmol, n_checks,
                  transient_offset_mmol, transient_check_ts_ms, transient_half_life_ms,
                  source_count, source_latest_ts_ms, source_hash, fitted_at_ms
           FROM $table $where ORDER BY $orderBy""",
        args,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    MeterCalibrationRecord(
                        sensorKey = c.getString(0),
                        sensorStartMs = c.getLong(1),
                        slope = c.getDouble(2),
                        interceptMmol = c.getDouble(3),
                        nChecks = c.getInt(4),
                        transientOffsetMmol = if (c.isNull(5)) null else c.getDouble(5),
                        transientCheckTsMs = if (c.isNull(6)) null else c.getLong(6),
                        transientHalfLifeMs = if (c.isNull(7)) null else c.getLong(7),
                        sourceCount = c.getInt(8),
                        sourceLatestTsMs = c.getLong(9),
                        sourceHash = c.getInt(10),
                        fittedAtMs = c.getLong(11),
                    ),
                )
            }
        }
    }

    // --- flexible context --------------------------------------------------

    override fun addAnnotation(input: Annotation): Long {
        // A tag-shaped note is stored as its key form (any language ->
        // "walk · 40 min"); the user's own words pass through unchanged.
        val annotation =
            input.copy(content = com.diapilot.core.analysis.canonicalNoteContent(input.content))
        val db = writableDatabase
        val knownAt =
            annotation.analysisKnownAtMs ?: annotation.carbsKnownAtMs ?: System.currentTimeMillis()
        var id = -1L;
        var structured = false
        db.beginTransaction()
        try {
            id =
                db.insert(
                    "annotations",
                    null,
                    ContentValues().apply {
                        put("ts_ms", annotation.tsMs)
                        put("kind", annotation.kind)
                        put("content", annotation.content)
                        put("media_ref", annotation.mediaRef)
                        put("analysis", annotation.analysis)
                        put("analysis_known_at_ms", annotation.analysisKnownAtMs)
                        put("est_carbs", annotation.estCarbs)
                        if (annotation.estCarbs != null) {
                            // Null is historical unknown provenance, never an alias for
                            // event time or import time. Live callers must pass the actual
                            // entry time; strict causal paths exclude null.
                            put("carbs_known_at_ms", annotation.carbsKnownAtMs)
                            put("carbs_source", annotation.carbsSource)
                        }
                    }
                )
            PhysioContextIngestionRuntime.fromCanonicalAnnotation(
                    annotation.copy(id = id),
                    knownAt,
                    appContext
                )
                ?.let { row ->
                    structured = appendContextExposure(row)
                }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
    }

    override fun annotations(fromMs: Long, toMs: Long): List<Annotation> =
        readableDatabase.rawQuery(
            "SELECT id, ts_ms, kind, content, media_ref, analysis, est_carbs, carbs_source, " +
                "carbs_known_at_ms, analysis_known_at_ms FROM annotations " +
                "WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms DESC",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Annotation(
                        id = c.getLong(0), tsMs = c.getLong(1), kind = c.getString(2),
                        content = c.getString(3), mediaRef = c.getString(4),
                        analysis = c.getString(5),
                        estCarbs = if (c.isNull(6)) null else c.getDouble(6),
                        // Provenance reaches the model layer for the first time. It has to:
                        // an `anchor` row's grams are TRUE grams while every other row is
                        // the user's recorded estimate, and `carbSensitivity` must not
                        // average the two spaces.
                        carbsSource = c.getString(7),
                        carbsKnownAtMs = if (c.isNull(8)) null else c.getLong(8),
                        analysisKnownAtMs = if (c.isNull(9)) null else c.getLong(9),
                    )
                )
            }
        }

    override fun setAnnotationAnalysis(id: Long, analysis: String) {
        writableDatabase.execSQL(
            "UPDATE annotations SET analysis = ?, analysis_known_at_ms = ? WHERE id = ?",
            arrayOf<Any?>(analysis, System.currentTimeMillis(), id),
        )
    }

    override fun setAnnotationCarbs(id: Long, grams: Double?, source: String) {
        // Provenance: WHEN the figure became known and from what source —
        // the causal pipeline's food-availability moment. Cleared with it.
        writableDatabase.execSQL(
            "UPDATE annotations SET est_carbs = ?, carbs_known_at_ms = ?, carbs_source = ? WHERE id = ?",
            arrayOf<Any?>(
                grams,
                if (grams != null) System.currentTimeMillis() else null,
                if (grams != null) source.takeUnless { it == "anchor" } ?: "manual" else null,
                id,
            ),
        )
    }

    override fun appendCarbEvidence(
        annotationId: Long,
        eventTimeMs: Long,
        input: CarbEvidenceInputV1,
        knownAtMs: Long,
        recordedAtMs: Long,
    ): CarbEvidenceV1 {
        val normalized = input.validated()
        require(recordedAtMs >= knownAtMs) { "recorded_at cannot precede known_at" }
        val durationMs = ((normalized.intakeDurationMin ?: 0.0) * 60_000.0).toLong()
        val db = writableDatabase
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            val evidenceId = "carb:$annotationId"
            val previous = db.rawQuery(
                "SELECT MAX(revision) FROM carb_evidence_v1 WHERE evidence_id = ?",
                arrayOf(evidenceId),
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0 }
            val priorEvidence = if (previous > 0) db.rawQuery(
                "SELECT $CARB_EVIDENCE_COLUMNS FROM carb_evidence_v1 WHERE evidence_id=? ORDER BY revision DESC LIMIT 1",
                arrayOf(evidenceId),
            ).use { c -> if (c.moveToFirst()) readCarbEvidence(c) else null } else null
            require(priorEvidence == null || knownAtMs >= priorEvidence.knownAtMs) {
                "known_at must be monotonic across revisions"
            }
            require(priorEvidence == null || recordedAtMs >= priorEvidence.recordedAtMs) {
                "recorded_at must be monotonic across revisions"
            }
            if (previous > 0 && normalized.source == CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT) {
                validateRecipeRevisionV1(priorEvidence?.input, normalized)
            }
            val evidence = CarbEvidenceV1(
                evidenceId = evidenceId,
                revision = previous + 1,
                supersedesRevision = previous.takeIf { it > 0 },
                annotationId = annotationId,
                mealSessionId = "annotation:$annotationId",
                eventTimeMs = eventTimeMs,
                intakeStartMs = eventTimeMs,
                intakeEndMs = Math.addExact(eventTimeMs, durationMs),
                knownAtMs = knownAtMs,
                recordedAtMs = recordedAtMs,
                input = normalized,
            )
            insertCarbEvidence(db, evidence)
            val compatibilitySource = when (normalized.source) {
                CarbEvidenceSourceV1.LABEL_WEIGHT -> "anchor"
                CarbEvidenceSourceV1.PHOTO_LLM -> "llm"
                CarbEvidenceSourceV1.PRESET_TABLE -> "preset"
                else -> "manual"
            }
            db.execSQL(
                "UPDATE annotations SET est_carbs = ?, carbs_known_at_ms = ?, carbs_source = ? WHERE id = ?",
                arrayOf<Any?>(normalized.totalCarbsG, knownAtMs, compatibilitySource, annotationId),
            )
            if (ownsTransaction) db.setTransactionSuccessful()
            return evidence
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    override fun addFoodWithCarbEvidence(
        annotation: Annotation,
        analysis: String,
        input: CarbEvidenceInputV1,
        knownAtMs: Long,
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = addAnnotation(annotation)
            setAnnotationAnalysis(id, analysis)
            appendCarbEvidence(id, annotation.tsMs, input, knownAtMs, knownAtMs)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    private fun insertCarbEvidence(db: SQLiteDatabase, evidence: CarbEvidenceV1) {
        val v = evidence.input.validated()
        db.insertOrThrow(
            "carb_evidence_v1",
            null,
            ContentValues().apply {
                put("evidence_id", evidence.evidenceId)
                put("revision", evidence.revision)
                put("supersedes_revision", evidence.supersedesRevision)
                put("annotation_id", evidence.annotationId)
                put("meal_session_id", evidence.mealSessionId)
                put("event_time_ms", evidence.eventTimeMs)
                put("intake_start_ms", evidence.intakeStartMs)
                put("intake_end_ms", evidence.intakeEndMs)
                put("known_at_ms", evidence.knownAtMs)
                put("recorded_at_ms", evidence.recordedAtMs)
                put("source", v.source.name)
                put("user_confirmed", if (v.userConfirmed) 1 else 0)
                put("total_carbs_g", v.totalCarbsG)
                put("label_carbs_per_100g", v.labelCarbsPer100g)
                put("weighed_edible_g", v.weighedEdibleG)
                put("label_carbs_per_serving_g", v.labelCarbsPerServingG)
                put("servings", v.servings)
                put("recipe_version", v.recipeVersion)
                put("recipe_total_carbs_g", v.recipeTotalCarbsG)
                put("recipe_total_weight_g", v.recipeTotalWeightG)
                put("recipe_consumed_weight_g", v.recipeConsumedWeightG)
                put("recipe_consumed_fraction", v.recipeConsumedFraction)
                put("amount_uncertainty_json", uncertaintyJson(v.amountUncertainty))
                put("timing_uncertainty_json", uncertaintyJson(v.timingUncertainty))
                put("evidence_hash", v.evidenceHash)
                put("alcohol_present", if (v.alcoholPresent) 1 else 0)
                put("kinetics_v2", v.kineticsV2)
                put("deleted", if (evidence.deleted) 1 else 0)
                put("provenance_version", evidence.provenanceVersion)
            }
        )
    }

    override fun carbEvidenceHistory(annotationId: Long): List<CarbEvidenceV1> =
        readableDatabase.rawQuery(
            "SELECT $CARB_EVIDENCE_COLUMNS FROM carb_evidence_v1 WHERE annotation_id = ? ORDER BY revision",
            arrayOf(annotationId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(readCarbEvidence(c)) } }

    override fun carbEvidenceKnownAt(annotationId: Long, queryTimeMs: Long): CarbEvidenceV1? =
        readableDatabase.rawQuery(
            "SELECT $CARB_EVIDENCE_COLUMNS FROM carb_evidence_v1 " +
                "WHERE annotation_id = ? AND known_at_ms <= ? ORDER BY revision DESC LIMIT 1",
            arrayOf(annotationId.toString(), queryTimeMs.toString()),
        ).use { c -> if (c.moveToFirst()) readCarbEvidence(c).takeUnless { it.deleted } else null }

    override fun tombstoneCarbEvidence(annotationId: Long, eventTimeMs: Long, knownAtMs: Long): CarbEvidenceV1? {
        val previous = carbEvidenceHistory(annotationId).lastOrNull() ?: return null
        if (previous.deleted) return previous
        require(knownAtMs >= previous.knownAtMs && knownAtMs >= previous.recordedAtMs) {
            "tombstone knowledge time must be monotonic"
        }
        val tombstone = previous.copy(
            revision = previous.revision + 1,
            supersedesRevision = previous.revision,
            eventTimeMs = eventTimeMs,
            intakeStartMs = eventTimeMs,
            intakeEndMs = eventTimeMs,
            knownAtMs = knownAtMs,
            recordedAtMs = knownAtMs,
            deleted = true,
        )
        insertCarbEvidence(writableDatabase, tombstone)
        return tombstone
    }

    override fun importCarbEvidence(evidence: CarbEvidenceV1): Boolean {
        if (!evidence.deleted) evidence.input.validated()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT $CARB_EVIDENCE_COLUMNS FROM carb_evidence_v1 WHERE evidence_id=? AND revision=?",
                arrayOf(evidence.evidenceId, evidence.revision.toString()),
            ).use { c -> if (c.moveToFirst()) readCarbEvidence(c) else null }
            if (existing != null) {
                require(existing.canonicalJson() == evidence.canonicalJson()) { "conflicting imported evidence revision" }
                db.setTransactionSuccessful()
                return false
            }
            if (evidence.revision > 1) {
                val priorEvidence = db.rawQuery(
                    "SELECT $CARB_EVIDENCE_COLUMNS FROM carb_evidence_v1 WHERE evidence_id=? ORDER BY revision DESC LIMIT 1",
                    arrayOf(evidence.evidenceId),
                ).use { c -> if (c.moveToFirst()) readCarbEvidence(c) else null }
                require(priorEvidence != null && priorEvidence.revision == evidence.revision - 1 && evidence.supersedesRevision == priorEvidence.revision) {
                    "import must preserve a contiguous supersedes chain"
                }
                require(evidence.knownAtMs >= priorEvidence.knownAtMs && evidence.recordedAtMs >= priorEvidence.recordedAtMs) {
                    "imported causal timestamps must be monotonic"
                }
            }
            insertCarbEvidence(db, evidence)
            if (!evidence.deleted) {
                val source = when (evidence.input.source) {
                    CarbEvidenceSourceV1.LABEL_WEIGHT -> "anchor"
                    CarbEvidenceSourceV1.PHOTO_LLM -> "llm"
                    CarbEvidenceSourceV1.PRESET_TABLE -> "preset"
                    else -> "manual"
                }
                db.execSQL(
                    "UPDATE annotations SET est_carbs=?, carbs_known_at_ms=?, carbs_source=? WHERE id=?",
                    arrayOf<Any?>(evidence.input.totalCarbsG, evidence.knownAtMs, source, evidence.annotationId),
                )
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    override fun latestStandardRecipeEvidence(mealName: String): CarbEvidenceV1? =
        readableDatabase.rawQuery(
            "SELECT $CARB_EVIDENCE_COLUMNS FROM carb_evidence_v1 e " +
                "WHERE e.annotation_id IN (SELECT id FROM annotations WHERE lower(trim(content))=lower(trim(?))) " +
                "AND e.source = ? AND e.deleted = 0 " +
                "AND e.revision = (SELECT MAX(x.revision) FROM carb_evidence_v1 x WHERE x.evidence_id=e.evidence_id) " +
                "ORDER BY e.known_at_ms DESC LIMIT 1",
            arrayOf(mealName, CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT.name),
        ).use { c -> if (c.moveToFirst()) readCarbEvidence(c) else null }

    override fun carbEvidenceReadiness(): CarbEvidenceReadinessV1 = readableDatabase.rawQuery(
        """SELECT COUNT(*),
                  SUM(CASE WHEN source IN ('LABEL_WEIGHT','STANDARD_RECIPE_WEIGHT') THEN 1 ELSE 0 END),
                  COUNT(DISTINCT date(event_time_ms/1000,'unixepoch','localtime'))
           FROM carb_evidence_v1 e
           WHERE deleted=0 AND revision=(SELECT MAX(x.revision) FROM carb_evidence_v1 x WHERE x.evidence_id=e.evidence_id)""",
        null,
    ).use { c ->
        c.moveToFirst()
        CarbEvidenceReadinessV1(c.getInt(0), if (c.isNull(1)) 0 else c.getInt(1), c.getInt(2))
    }

    private fun uncertaintyJson(value: CarbUncertaintyV1): String = org.json.JSONObject()
        .put("kind", value.kind).apply { value.parameter?.let { put("parameter", it) } }.toString()

    private fun uncertainty(raw: String): CarbUncertaintyV1 = org.json.JSONObject(raw).let {
        CarbUncertaintyV1(it.getString("kind"), if (it.has("parameter")) it.getDouble("parameter") else null)
    }

    private fun readCarbEvidence(c: android.database.Cursor): CarbEvidenceV1 {
        fun d(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
        val input = CarbEvidenceInputV1(
            source = CarbEvidenceSourceV1.valueOf(c.getString(10)),
            userConfirmed = c.getInt(11) != 0,
            totalCarbsG = c.getDouble(12),
            labelCarbsPer100g = d(13), weighedEdibleG = d(14),
            labelCarbsPerServingG = d(15), servings = d(16),
            recipeVersion = c.getString(17), recipeTotalCarbsG = d(18),
            recipeTotalWeightG = d(19), recipeConsumedWeightG = d(20),
            recipeConsumedFraction = d(21),
            intakeDurationMin = (c.getLong(7) - c.getLong(6)).takeIf { it > 0L }?.div(60_000.0),
            amountUncertainty = uncertainty(c.getString(22)),
            timingUncertainty = uncertainty(c.getString(23)),
            evidenceHash = c.getString(24), alcoholPresent = c.getInt(25) != 0,
            kineticsV2 = c.getString(26),
        )
        return CarbEvidenceV1(
            evidenceId = c.getString(0), revision = c.getInt(1),
            supersedesRevision = if (c.isNull(2)) null else c.getInt(2),
            annotationId = c.getLong(3), mealSessionId = c.getString(4),
            eventTimeMs = c.getLong(5), intakeStartMs = c.getLong(6), intakeEndMs = c.getLong(7),
            knownAtMs = c.getLong(8), recordedAtMs = c.getLong(9), input = input,
            deleted = c.getInt(27) != 0, provenanceVersion = c.getString(28),
        )
    }

    override fun updateAnnotation(id: Long, tsMs: Long, content: String, mediaRef: String?) {
        writableDatabase.execSQL(
            "UPDATE annotations SET ts_ms = ?, content = ?, media_ref = ? WHERE id = ?",
            arrayOf<Any?>(tsMs, com.diapilot.core.analysis.canonicalNoteContent(content), mediaRef, id),
        )
    }

    override fun deleteAnnotation(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val eventTime = db.rawQuery(
                "SELECT ts_ms FROM annotations WHERE id = ?", arrayOf(id.toString()),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else System.currentTimeMillis() }
            tombstoneCarbEvidence(id, eventTime, System.currentTimeMillis())
            db.execSQL("DELETE FROM annotations WHERE id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun topAnnotationTexts(limit: Int): List<String> =
        // Context suggestions: food notes (kind='food') excluded by design.
        readableDatabase.rawQuery(
            "SELECT content, COUNT(*) c FROM annotations WHERE kind = 'text' " +
                "GROUP BY content ORDER BY c DESC, MAX(ts_ms) DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    override fun getOrCreateLabel(input: String): Long {
        // A system label in any language is stored as its key ("dawn", ...).
        val name = com.diapilot.core.analysis.canonicalMealLabel(input)
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO meal_labels (name) VALUES (?)", arrayOf(name))
        db.execSQL("UPDATE meal_labels SET use_count = use_count + 1 WHERE name = ?", arrayOf(name))
        return db.rawQuery("SELECT id FROM meal_labels WHERE name = ?", arrayOf(name)).use {
            it.moveToFirst(); it.getLong(0)
        }
    }

    override fun addMealEvent(event: MealEvent, labelId: Long?) {
        // Re-detection updates the metrics but never clobbers a user-assigned label.
        writableDatabase.execSQL(
            "INSERT INTO meal_events " +
                "(onset_ms, peak_ms, pre_bg, peak_bg, rise, time_to_peak_min, bolus_units, kind, label_id)" +
                " VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(onset_ms) DO UPDATE SET peak_ms=excluded.peak_ms, " +
                "pre_bg=excluded.pre_bg, peak_bg=excluded.peak_bg, rise=excluded.rise, " +
                "time_to_peak_min=excluded.time_to_peak_min, bolus_units=excluded.bolus_units, " +
                "kind=excluded.kind",
            arrayOf<Any?>(
                event.onsetMs, event.peakMs, event.preBg, event.peakBg, event.rise,
                event.timeToPeakMin, event.bolusUnits, event.kind.name.lowercase(), labelId,
            ),
        )
    }

    override fun setMealLabel(onsetMs: Long, labelId: Long) {
        writableDatabase.execSQL(
            "UPDATE meal_events SET label_id = ?, label_assigned_at_ms = ? WHERE onset_ms = ?",
            arrayOf(labelId, System.currentTimeMillis(), onsetMs),
        )
    }

    // --- queries -----------------------------------------------------------

    override fun readings(fromMs: Long, toMs: Long): List<GlucosePoint> =
        readableDatabase.rawQuery(
            "SELECT ts_ms, mmol FROM glucose_readings WHERE ts_ms BETWEEN ? AND ? " + VALID_MMOL + "ORDER BY ts_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(GlucosePoint(c.getLong(0), c.getDouble(1))) }
        }

    override fun lastReading(): Reading? =
        readableDatabase.rawQuery(
            "SELECT ts_ms, mgdl, mmol, trend, source FROM glucose_readings " +
                "WHERE mmol BETWEEN 1.0 AND 35.0 ORDER BY ts_ms DESC LIMIT 1",
            null,
        ).use { c ->
            if (!c.moveToFirst()) null
            else Reading(
                tsMs = c.getLong(0), mgdl = c.getDouble(1), mmol = c.getDouble(2),
                trend = c.getString(3), source = c.getString(4) ?: "unknown",
            )
        }

    override fun lastSensorReading(): Reading? =
        readableDatabase.rawQuery(
            "SELECT ts_ms, mgdl, mmol, trend, source FROM glucose_readings " +
                "WHERE (source IS NULL OR source != 'meter') " +
                "AND mmol BETWEEN 1.0 AND 35.0 ORDER BY ts_ms DESC LIMIT 1",
            null,
        ).use { c ->
            if (!c.moveToFirst()) null
            else Reading(
                tsMs = c.getLong(0), mgdl = c.getDouble(1), mmol = c.getDouble(2),
                trend = c.getString(3), source = c.getString(4) ?: "unknown",
            )
        }

    override fun boluses(fromMs: Long, toMs: Long): List<BolusPoint> =
        // Air shots (new-cartridge purge, marked with the "air" purpose tag) never entered the
        // body: excluded from everything analytic. The audit view uses
        // bolusesAll().
        readableDatabase.rawQuery(
            "SELECT ts_ms, units, purpose FROM insulin_events " +
                "WHERE ts_ms BETWEEN ? AND ? AND (purpose IS NULL OR purpose NOT IN ($PRIME_PURPOSES_SQL)) " +
                "ORDER BY ts_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(BolusPoint(c.getLong(0), c.getDouble(1), c.getString(2)))
            }
        }

    override fun bolusesAll(fromMs: Long, toMs: Long): List<BolusPoint> =
        readableDatabase.rawQuery(
            "SELECT ts_ms, units, purpose FROM insulin_events WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(BolusPoint(c.getLong(0), c.getDouble(1), c.getString(2)))
            }
        }

    override fun bulkUpsert(readings: List<Reading>, events: List<InsulinEvent>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            readings.forEach(::upsertReading)
            events.forEach(::upsertInsulin)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun unlabeledMeals(sinceMs: Long): List<MealEvent> =
        queryMeals("label_id IS NULL AND onset_ms >= ?", arrayOf(sinceMs.toString()))

    override fun meals(fromMs: Long, toMs: Long): List<MealEvent> =
        // label_id = -1 marks a dismissed false positive.
        queryMeals(
            "onset_ms BETWEEN ? AND ? AND (label_id IS NULL OR label_id != -1)",
            arrayOf(fromMs.toString(), toMs.toString()),
        )

    override fun dismissMeal(onsetMs: Long) {
        writableDatabase.execSQL(
            "UPDATE meal_events SET label_id = -1 WHERE onset_ms = ?",
            arrayOf(onsetMs),
        )
    }

    private fun queryMeals(where: String, args: Array<String>): List<MealEvent> =
        readableDatabase.rawQuery(
            "SELECT onset_ms, peak_ms, pre_bg, peak_bg, rise, time_to_peak_min, bolus_units, kind " +
                "FROM meal_events WHERE $where ORDER BY onset_ms DESC",
            args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    MealEvent(
                        onsetMs = c.getLong(0), peakMs = c.getLong(1),
                        preBg = c.getDouble(2), peakBg = c.getDouble(3),
                        rise = c.getDouble(4), timeToPeakMin = c.getDouble(5),
                        bolusUnits = if (c.isNull(6)) null else c.getDouble(6),
                        kind = if (c.getString(7) == "announced") MealEvent.Kind.ANNOUNCED
                        else MealEvent.Kind.UNANNOUNCED,
                    )
                )
            }
        }

    override fun labeledMeals(limit: Int): List<LabeledMeal> =
        readableDatabase.rawQuery(
            "SELECT m.onset_ms, m.peak_ms, m.pre_bg, m.peak_bg, m.rise, m.time_to_peak_min, " +
                "m.bolus_units, m.kind, l.id, l.name " +
                "FROM meal_events m JOIN meal_labels l ON m.label_id = l.id " +
                "ORDER BY m.onset_ms DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    LabeledMeal(
                        event = MealEvent(
                            onsetMs = c.getLong(0), peakMs = c.getLong(1),
                            preBg = c.getDouble(2), peakBg = c.getDouble(3),
                            rise = c.getDouble(4), timeToPeakMin = c.getDouble(5),
                            bolusUnits = if (c.isNull(6)) null else c.getDouble(6),
                            kind = if (c.getString(7) == "announced") MealEvent.Kind.ANNOUNCED
                            else MealEvent.Kind.UNANNOUNCED,
                        ),
                        labelId = c.getLong(8),
                        labelName = c.getString(9),
                    )
                )
            }
        }

    override fun decrementLabelUse(labelId: Long) {
        writableDatabase.execSQL(
            "UPDATE meal_labels SET use_count = MAX(use_count - 1, 0) WHERE id = ?",
            arrayOf(labelId),
        )
    }

    override fun topLabels(limit: Int): List<Pair<Long, String>> =
        readableDatabase.rawQuery(
            "SELECT id, name FROM meal_labels ORDER BY use_count DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
        }

    /**
     * Timestamp of the oldest glucose/insulin fact on file, or null when there
     * is none. The local API's history backfill needs a floor to walk up from;
     * a hardcoded date would either miss data or burn passes over empty years.
     */
    fun oldestFactMs(): Long? = readableDatabase.rawQuery(
        "SELECT MIN(t) FROM (SELECT MIN(ts_ms) t FROM glucose_readings " +
            "UNION ALL SELECT MIN(ts_ms) FROM insulin_events " +
            "UNION ALL SELECT MIN(ts_ms) FROM basal_events)",
        null,
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /**
     * How long a stretch of SENSOR readings this file holds (newest minus
     * oldest), or null with none. Meter readings are left out: a fingerstick
     * entered on the day of the install is not a day of collection.
     *
     * `Onboarding.adoptExistingInstall` reads it to tell a phone that was
     * already in use before the first-run flow existed from a fresh one.
     */
    fun sensorReadingSpanMs(): Long? = readableDatabase.rawQuery(
        "SELECT MIN(ts_ms), MAX(ts_ms) FROM glucose_readings " +
            "WHERE (source IS NULL OR source != 'meter')",
        null,
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(1) - it.getLong(0) else null }

    override fun count(table: String): Long {
        require(table.matches(Regex("[a-z_]+"))) { "bad table name" }
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            it.moveToFirst(); it.getLong(0)
        }
    }

    // ---- anomaly marks ----------------------------------------------------

    override fun mealMarks(): List<com.diapilot.core.analysis.MealMark> =
        readableDatabase.rawQuery(
            "SELECT id, onset_ms, kind, comment, author, created_at_ms, revoked_at_ms " +
                "FROM meal_marks ORDER BY created_at_ms",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    // An unparseable kind is SKIPPED, not defaulted. A mark whose meaning we
                    // cannot read must not silently become "unknown" — that would invent a
                    // human statement, and this table's whole value is that every row in it
                    // was said by a person.
                    val kind = runCatching {
                        com.diapilot.core.analysis.MarkKind.valueOf(c.getString(2))
                    }.getOrNull() ?: continue
                    add(
                        com.diapilot.core.analysis.MealMark(
                            id = c.getLong(0),
                            onsetMs = c.getLong(1),
                            kind = kind,
                            comment = if (c.isNull(3)) null else c.getString(3),
                            author = c.getString(4),
                            createdAtMs = c.getLong(5),
                            revokedAtMs = if (c.isNull(6)) null else c.getLong(6),
                        ),
                    )
                }
            }
        }

    override fun addMealMark(mark: com.diapilot.core.analysis.MealMark): Long {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            // Revoke-then-insert in ONE transaction. Two independent writes could interleave
            // and leave a meal with two active marks (or none) — the same class of defect
            // that lost a bolus purpose when insert and tag raced.
            db.execSQL(
                "UPDATE meal_marks SET revoked_at_ms = ? WHERE onset_ms = ? AND revoked_at_ms IS NULL",
                arrayOf(mark.createdAtMs, mark.onsetMs),
            )
            val v = android.content.ContentValues().apply {
                put("onset_ms", mark.onsetMs)
                put("kind", mark.kind.name)
                put("comment", mark.comment?.takeIf { it.isNotBlank() })
                put("author", mark.author)
                put("created_at_ms", mark.createdAtMs)
            }
            val id = db.insert("meal_marks", null, v)
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    override fun revokeMealMark(onsetMs: Long, atMs: Long) {
        writableDatabase.execSQL(
            "UPDATE meal_marks SET revoked_at_ms = ? WHERE onset_ms = ? AND revoked_at_ms IS NULL",
            arrayOf(atMs, onsetMs),
        )
    }
}
