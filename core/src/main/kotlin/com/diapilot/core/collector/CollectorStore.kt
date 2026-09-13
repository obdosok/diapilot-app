/**
 * Platform-agnostic contracts for the stage-1 collector.
 *
 * The core defines what is stored and how data flows in; the platform layer
 * (Android app) supplies the SQLite/Room implementation and the xDrip wiring.
 * Schema mirrors python_core/collector/store.py.
 */
package com.diapilot.core.collector

/** Source of glucose readings (xDrip broadcast on Android, files/fixtures in tests). */
fun interface GlucoseSource {
    fun subscribe(onReading: (Reading) -> Unit)
}

data class HrPoint(val tsMs: Long, val bpm: Double)

/**
 * Steps over an interval, as Health Connect delivers them (bucketed).
 *
 * @param source the app that recorded the interval (`dataOrigin.packageName`).
 *        Null for older rows recorded before the source was tracked — back
 *        then two apps could sum into one total twice the truth.
 *        See [StepStream].
 */
data class StepBucket(
    val startMs: Long,
    val endMs: Long,
    val count: Long,
    val source: String? = null,
)

data class SleepSession(val startMs: Long, val endMs: Long) {
    val durationH: Double get() = (endMs - startMs) / 3_600_000.0
}

/** Generic causal exposure channel for sources added after the app binary (weather/provider/manual context). */
data class ContextExposureV1(
    val exposureId: String,
    val sourceType: String,
    val eventStartMs: Long,
    val eventEndMs: Long,
    val knownAtMs: Long,
    val recordedAtMs: Long,
    val value: Double? = null,
    val unit: String? = null,
    val contextJson: String = "{}",
) {
    init {
        require(exposureId.isNotBlank() && sourceType.isNotBlank())
        require(eventEndMs >= eventStartMs && knownAtMs >= 0 && recordedAtMs >= knownAtMs)
        require(value == null || value.isFinite())
    }
}

data class LabeledMeal(
    val event: MealEvent,
    val labelId: Long,
    val labelName: String,
)

data class Annotation(
    val tsMs: Long,
    val kind: String,       // "text" | "food" | "voice" | "photo" | "tag"
    val content: String,
    val mediaRef: String? = null,
    val id: Long = 0,       // assigned by the store
    val analysis: String? = null,  // cached LLM description (photo notes)
    val estCarbs: Double? = null,  // grams; Vision estimate or user-entered
    /**
     * `annotations.carbs_source` is the legacy compatibility view. A future `anchor`
     * may be materialized from structured measured CarbEvidence; an old `anchor` alone
     * is not proof of label/weight arithmetic. `manual` = came through
     * the editor, `llm` = the model's estimate, `preset` = a stored constant, null =
     * unrecorded.
     *
     * The column has existed since the schema was written and **had never reached the
     * model layer**, which is how a hardcoded tablet constant spent six weeks posing as
     * an observation. It reaches it now because two gram SPACES have to be kept apart:
     * structured evidence and recorded estimates must remain distinguishable.
     */
    val carbsSource: String? = null,
    /** Wall-clock moment at which [estCarbs] became available to a forecast. */
    val carbsKnownAtMs: Long? = null,
    /** Wall-clock availability of [analysis]; legacy null is causally unknown. */
    val analysisKnownAtMs: Long? = null,
)

/**
 * Local store: hard core (glucose/insulin) + flexible context (annotations,
 * meal events, meal labels). Writes are idempotent (dedup by timestamp).
 */
interface CollectorStore {
    fun upsertReading(reading: Reading)

    /**
     * Insert a main reading ONLY if no reading already exists within
     * [toleranceMs] — a gap-filler (e.g. NFC history after a BLE outage)
     * that must never overwrite a real 5-minute point or a meter check.
     * Returns true when a row was written. Default keeps test doubles simple.
     */
    fun backfillMainReading(reading: Reading, toleranceMs: Long): Boolean {
        upsertReading(reading); return true
    }

    /** Timestamps of NFC-backfilled main readings in [fromMs,toMs] — the
     *  chart bridges their coarse 15-min spans into a continuous line. */
    fun backfilledReadingTimestamps(fromMs: Long, toMs: Long): Set<Long> = emptySet()

    /**
     * Sensor-only readings: meter checks (a different instrument on its own
     * scale, kept as a calibration reference) are EXCLUDED from the trace
     * used to LEARN the insulin response — a fingerstick beside a sensor
     * point would otherwise skew a window median (e.g. a check taken right
     * before a correction, landing in the bgStart window). Defaults to
     * [readings] for simple test doubles.
     */
    fun sensorReadings(fromMs: Long, toMs: Long): List<GlucosePoint> = readings(fromMs, toMs)

    /**
     * The same CGM series WITH each row's transport, so a caller can prefer one series over
     * another — see `MeasurementStreamCore.chooseFrom`. Needed because two transports wrote the same
     * sensor era on scales ~0.58 mmol apart and interleaving them cost a quarter of the
     * food era its corpus rows.
     *
     * Defaulted to «no source known» so an implementor that cannot answer keeps compiling
     * and simply gets the old behaviour (the preference becomes a no-op).
     */
    fun sensorReadingsWithSource(fromMs: Long, toMs: Long): List<Pair<GlucosePoint, String?>> =
        sensorReadings(fromMs, toMs).map { it to null }

    /**
     * Latest SENSOR reading — a meter fingerstick is a reference point, not
     * the live value or the forecast anchor (a different instrument on its
     * own scale would otherwise become "current sugar" and mix into the CGM
     * trend). Every live consumer (header, forecast, widget, watch, alerts)
     * reads this; the meter still shows as its own 🩸 point. Defaults to
     * [lastReading] for simple test doubles.
     */
    fun lastSensorReading(): Reading? = lastReading()

    /**
     * Store a dose at its timestamp. The timestamp is the identity, and the
     * slot belongs to the source that wrote it first: a repeat from the same
     * source revises it, a write from another source at an occupied slot is
     * dropped, and units the user edited by hand are kept through both. The
     * SQLite implementation states the reasoning.
     */
    fun upsertInsulin(event: InsulinEvent)

    /** Remove an insulin event (deleted at the source, e.g. in xDrip). */
    fun deleteInsulin(tsMs: Long)

    /**
     * User-initiated delete: tombstoned so the treatments poll cannot
     * resurrect it. deleteInsulin() stays for the sync's own bookkeeping.
     */
    fun deleteInsulinForever(tsMs: Long) = deleteInsulin(tsMs)

    /** Correct a bolus dose by hand; the edit survives re-syncs. */
    fun setBolusUnits(tsMs: Long, units: Double) {}

    /** Remove a glucose reading (manual meter entries only, via UI). */
    fun deleteReading(tsMs: Long) {}

    /** Manually entered glucometer readings (source = "meter"). */
    fun meterReadings(fromMs: Long, toMs: Long): List<Reading> = emptyList()

    /**
     * Timestamps of insulin events that did NOT come from the xDrip
     * treatments sync (manual, watch, pen NFC). The deletion sync must
     * never touch them — xDrip knows nothing about their existence.
     */
    fun nonSyncInsulinTs(fromMs: Long): Set<Long> = emptySet()

    /** Pen-scan dose identity ledger: rescans must not duplicate doses. */
    fun penDoseSeen(uuid: String): Boolean = false
    fun markPenDose(uuid: String, tsMs: Long) {}

    /**
     * Drop xDrip-synced boluses that duplicate a non-sync dose (pen NFC,
     * manual, watch) — same units within [windowMs]. Happens when the same
     * shot enters both ways (e.g. the pen history was also scanned into
     * xDrip): clocks disagree by seconds, so the ts-keyed upsert can't
     * collapse them. The non-sync copy wins. Returns rows removed.
     */
    fun dedupeSyncedBoluses(windowMs: Long = 3 * 60_000): Int = 0

    /**
     * Intent of a shot — an attribute of the bolus itself, not a separate
     * event. Survives the xDrip re-sync (upserts touch units only).
     */
    fun setBolusPurpose(tsMs: Long, purpose: String?)

    /**
     * Bolus + purpose as ONE write. The LLM command path used to fire two
     * independent coroutines (insert, then tag) — with the tag able to run
     * FIRST, its UPDATE hitting no row and the purpose silently lost. The
     * SQLite implementation wraps both statements in a transaction; this
     * default at least guarantees the order for simple test doubles.
     */
    fun addBolusWithPurpose(
        tsMs: Long,
        units: Double,
        purpose: String?,
        source: String = "manual",
    ) {
        upsertInsulin(InsulinEvent(tsMs, units, "bolus", source = source))
        if (purpose != null) setBolusPurpose(tsMs, purpose)
    }

    /**
     * Per-minute raw stream (OOP2) — a different calibration scale from the
     * main readings, stored apart and NEVER fed into analytics. Display only.
     */
    fun upsertMinuteReading(reading: Reading)
    fun minuteReadings(fromMs: Long, toMs: Long): List<GlucosePoint>
    fun lastMinuteReading(): Reading?

    /**
     * Long-acting (basal) insulin — logged in DiaPilot directly (not worth
     * keeping in xDrip). Kept apart from bolus events: it must never enter
     * ISF candidates, the kernel or the twin's bolus superposition.
     */
    fun upsertBasal(tsMs: Long, units: Double)
    fun basalEvents(fromMs: Long, toMs: Long): List<BolusPoint>
    fun deleteBasal(tsMs: Long)

    // Physiology from Health Connect (heart rate, sleep) — spec §3 "later".
    fun upsertHeartRate(point: HrPoint)
    fun heartRate(fromMs: Long, toMs: Long): List<HrPoint>
    fun lastHeartRate(): HrPoint?
    fun upsertSleepSession(session: SleepSession)
    fun sleepSessions(fromMs: Long, toMs: Long): List<SleepSession>
    // Steps: collected NOW for the exercise-v2 analytics later — history
    // cannot be backfilled, and step cadence is the activity-TYPE signal
    // (walk vs bike vs swim) that heart rate alone can't provide.
    fun upsertSteps(bucket: StepBucket)
    fun steps(fromMs: Long, toMs: Long): List<StepBucket>

    /** Append-only typed extension point. Readers must still filter by [ContextExposureV1.knownAtMs]. */
    fun appendContextExposure(exposure: ContextExposureV1): Boolean = false
    fun contextExposures(sourceType: String, fromMs: Long, toMs: Long, asOfMs: Long): List<ContextExposureV1> = emptyList()

    /** Bulk write for imports; implementations should wrap in one transaction. */
    fun bulkUpsert(readings: List<Reading>, events: List<InsulinEvent>) {
        readings.forEach(::upsertReading)
        events.forEach(::upsertInsulin)
    }
    fun addAnnotation(annotation: Annotation): Long
    fun annotations(fromMs: Long, toMs: Long): List<Annotation>
    fun updateAnnotation(id: Long, tsMs: Long, content: String, mediaRef: String?)
    fun deleteAnnotation(id: Long)

    /** Cache an LLM analysis on the annotation — paid for once, kept forever. */
    fun setAnnotationAnalysis(id: Long, analysis: String)

    // Carbs estimate in grams (null clears). Vision-derived or user-edited.
    /** [source]: "manual" | "llm" — provenance for the causal pipeline;
     *  the write moment is stamped as carbsKnownAtMs by implementations. */
    fun setAnnotationCarbs(id: Long, grams: Double?, source: String = "manual") {}

    /** Append-only carbohydrate evidence. Default methods preserve existing test doubles. */
    fun appendCarbEvidence(
        annotationId: Long,
        eventTimeMs: Long,
        input: CarbEvidenceInputV1,
        knownAtMs: Long = System.currentTimeMillis(),
        recordedAtMs: Long = knownAtMs,
    ): CarbEvidenceV1? = null

    fun carbEvidenceHistory(annotationId: Long): List<CarbEvidenceV1> = emptyList()
    fun carbEvidenceKnownAt(annotationId: Long, queryTimeMs: Long): CarbEvidenceV1? =
        selectCarbEvidenceKnownAtV1(carbEvidenceHistory(annotationId), queryTimeMs)

    fun tombstoneCarbEvidence(
        annotationId: Long,
        eventTimeMs: Long,
        knownAtMs: Long = System.currentTimeMillis(),
    ): CarbEvidenceV1? = null

    fun carbEvidenceReadiness(): CarbEvidenceReadinessV1 = CarbEvidenceReadinessV1(0, 0, 0)
    fun latestStandardRecipeEvidence(mealName: String): CarbEvidenceV1? = null
    fun importCarbEvidence(evidence: CarbEvidenceV1): Boolean = false
    fun importCarbEvidenceJson(canonicalJson: String): Boolean =
        importCarbEvidence(CarbEvidenceV1.parseCanonicalJson(canonicalJson))
    fun addFoodWithCarbEvidence(
        annotation: Annotation,
        analysis: String,
        input: CarbEvidenceInputV1,
        knownAtMs: Long = System.currentTimeMillis(),
    ): Long {
        val id = addAnnotation(annotation)
        setAnnotationAnalysis(id, analysis)
        appendCarbEvidence(id, annotation.tsMs, input, knownAtMs, knownAtMs)
        return id
    }

    /** Most frequent annotation texts — the personal quick-entry dictionary. */
    fun topAnnotationTexts(limit: Int = 8): List<String>
    fun getOrCreateLabel(name: String): Long
    /** Insert or refresh a detected meal; an existing user label must be preserved. */
    fun addMealEvent(event: MealEvent, labelId: Long? = null)

    /**
     * Mark a detected meal as a false positive. It disappears from all
     * queries (labeling queue, analytics food filter, prediction) but the
     * row stays, so the idempotent rescan cannot resurrect it.
     */
    fun dismissMeal(onsetMs: Long)
    fun setMealLabel(onsetMs: Long, labelId: Long)

    fun readings(fromMs: Long, toMs: Long): List<GlucosePoint>
    fun lastReading(): Reading?
    /** Body-effective boluses: air shots (purpose [com.diapilot.core.api.DiaForFacts.PURPOSE_AIR]) excluded. */
    fun boluses(fromMs: Long, toMs: Long): List<BolusPoint>

    /** Every stored bolus including air shots — the audit/history view. */
    fun bolusesAll(fromMs: Long, toMs: Long): List<BolusPoint> = boluses(fromMs, toMs)
    /** Unlabeled meals with onset >= sinceMs (0 = all). UI asks only about recent ones. */
    fun unlabeledMeals(sinceMs: Long = 0): List<MealEvent>
    fun meals(fromMs: Long, toMs: Long): List<MealEvent>
    fun labeledMeals(limit: Int = 50): List<LabeledMeal>
    fun decrementLabelUse(labelId: Long)
    fun topLabels(limit: Int = 8): List<Pair<Long, String>>
    fun count(table: String): Long

    // ---- anomaly marks: the user's answer, as a LAYER over primary data ----
    //
    // These live in their own table on purpose, and the alternative was seriously
    // considered and rejected. A column on `meal_events` cannot hold them: the meal the
    // user marks is keyed by its NOTE-anchored session start, and plenty of meals have
    // no detector event at all (a well-dosed meal is invisible to the detector — that is
    // why the deconvolution corpus exists). A column also has nowhere to put who said it,
    // when, or what it replaced, and un-saying it would mean writing over the field. Above
    // all, a mark must never touch what the user originally recorded: corrections have been
    // retracted within days of being made, and every one of those retractions
    // was only possible because the originals were intact.

    /** Every mark ever written, revoked ones included — history is the point. */
    fun mealMarks(): List<com.diapilot.core.analysis.MealMark> = emptyList()

    /**
     * Record an answer. Implementations REVOKE any active mark on the same meal first, so
     * changing one's mind appends rather than overwrites and the sequence survives.
     */
    fun addMealMark(mark: com.diapilot.core.analysis.MealMark): Long = 0

    /** Take an answer back. The meal returns to being unmarked; nothing is deleted. */
    fun revokeMealMark(onsetMs: Long, atMs: Long) {}
}

/** Reassign a meal's label, keeping label use counts honest. */
fun CollectorStore.relabelMeal(onsetMs: Long, oldLabelId: Long, newName: String) {
    decrementLabelUse(oldLabelId)
    setMealLabel(onsetMs, getOrCreateLabel(newName.trim()))
}
