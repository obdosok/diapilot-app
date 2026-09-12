package io.github.obdosok.diapilot.data

import io.github.obdosok.diapilot.i18n.uiLanguage
import android.util.Log
import com.diapilot.core.analysis.ContextStats
import com.diapilot.core.analysis.IsfAggregate
import com.diapilot.core.analysis.IsfConfig
import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.analysis.TodBucket
import com.diapilot.core.analysis.aggregateByTod
import com.diapilot.core.analysis.applyMarks
import com.diapilot.core.analysis.contextStats
import com.diapilot.core.analysis.detectIsfEpisodes
import com.diapilot.core.analysis.excludingMarked
import com.diapilot.core.analysis.insulinKernel
import com.diapilot.core.analysis.onsetsAllowedForShape
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.twin.Corridor
import com.diapilot.core.twin.calibrateCorridor

/**
 * Cached twin model: the insulin kernel G(τ) and the calibrated uncertainty
 * corridor. Building it walks the full history (episode detection + corridor
 * calibration), so it is recomputed at most every [TTL_MS] — the curve moves
 * with weeks of data, not minutes.
 */
object TwinCache {

    private val stage10Generation=java.util.concurrent.atomic.AtomicLong(0)

    private val stage9Executor =
        java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(1),
            java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    internal fun scheduleStage9FailOpen(task: (Long) -> Unit): Long {
        val generation = stage10Generation.incrementAndGet()
        FoodCalculationRegistry.expectEpisodeGeneration(generation)
        try {
            stage9Executor.execute {
                try {
                    task(generation)
                } catch (t: Throwable) {
                    Log.w(TAG, "Stage10 sidecar failed open", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Stage9 sidecar not scheduled", t)
        }
        return generation
    }
    internal fun invalidateStage10Eligibility(context: android.content.Context, ids: Set<Long>): Long {
        // Propose rather than pre-increment: the registry can prove that an
        // identical blocked set is already enforced, in which case neither
        // the global generation nor disk snapshot should churn.
        val reserved =
            FoodCalculationRegistry.invalidateClosedEpisodeEligibility(
                ids,
                stage10Generation.get() + 1,
                context
            )
        stage10Generation.accumulateAndGet(reserved) { a, b -> maxOf(a, b) }
        return reserved
    }
    internal fun <T> publishThenScheduleStage9(
        model: T,
        publish: (T) -> Unit,
        task: (Long) -> Unit
    ): T {
        publish(model)
        scheduleStage9FailOpen(task)
        return model
    }


    data class Model(
        val kernel: List<KernelPoint>,
        val corridor: Corridor,
        // Per-regime widths (night/activity/meal/injection/quiet); corridor
        // above stays the global fallback for historical drawing.
        val corridors: com.diapilot.core.twin.RegimeCorridors? = null,
        val byTod: Map<TodBucket, IsfAggregate>,
        val contexts: List<ContextStats>,
        val carbSens: com.diapilot.core.analysis.CarbSensitivity?,
        /** What the estimator LEARNED, kept beside the effective value even when
         *  an override replaces it. The divergence is the signal: when these two
         *  converge the override can be removed. Never null-out to save a field. */
        val carbSensLearned: com.diapilot.core.analysis.CarbSensitivity? = null,
        // Pure data-driven values BEFORE the user priors — the Settings
        // screen offers them as "computed from your data".
        val learnedCarbSens: com.diapilot.core.analysis.CarbSensitivity? = null,
        /** RAW corpus plateau — DIAGNOSTIC ONLY. It reads insulin roughly half
         *  as strong as every trusted source, and offering it to the user as
         *  "your ISF" is how 1.48 got into the override field. */
        val learnedIsfMmolPerU: Double = 0.0,
        /** The amplitude the model wants: trusted corrections + TDD prior.
         *  THIS is what the Settings button offers. */
        val estimatedIsfMmolPerU: Double = 0.0,
        val kernelEpisodes: Int = 0,
        val effectiveEpisodes: Double = 0.0,
        val reliableFoodEraStartMs: Long? = null,
        val freshIsfEpisodes: Int = 0,
        val freshIsfBlend: Double = 0.0,
        // Exercise: sustained-HR bouts (30d) + the learned drop coefficient
        // (mmol per elevated minute); learnedActivityDrop null = default used.
        val activityWindows: List<com.diapilot.core.analysis.ActivityWindow> = emptyList(),
        val activityDropPerMin: Double = com.diapilot.core.twin.DEFAULT_ACTIVITY_DROP_PER_MIN,
        val learnedActivityDrop: Double? = null,
        // Prolonged post-exercise sensitization (mmol per elevated minute over
        // the delayed 3–9h window) — an evening walk drifting into a night low.
        val postActivityDropPerMin: Double = 0.0,
        val doseChange: com.diapilot.core.analysis.DosingBehaviorChange? = null,
        val sensitivityChange: com.diapilot.core.analysis.SensitivityRegimeChange? = null,
        /** Phase-1 concept model: well-measured dishes reduced to
         *  (fingerprint → measured shape), the donor pool the fingerprint
         *  shadow forecast borrows ttp/tail from. */
        val fingerprintCorpus: List<com.diapilot.core.analysis.MealObservation> = emptyList(),
        /**
         * The kernels the DECONVOLUTION subtracted with, one per hour of day.
         *
         * Written to the snapshot so the laptop stand can reproduce this corpus.
         * Without them the stand rebuilt its own kernel and got a materially
         * different coefficient than the phone had — and since recovered food is
         * (glucose - insulin - neighbours), that alone made the same meal
         * read very differently in the two places. Several measurements were taken against
         * a model nobody runs before the acceptance stand caught it.
         */
        val deconvKernelsByHour: Map<Int, List<KernelPoint>> = emptyMap(),
        /**
         * THE USER'S ANSWERS THAT WERE APPLIED TO THIS BUILD — their words, not ours.
         *
         * Carried in the model (and in the snapshot) rather than only in the DB because the
         * harness must be able to replay the model THAT RAN. A stand that reads the current
         * marks table while scoring a forecast built under different marks computes a
         * different model than the phone did, which is the mistake that produced six
         * confident wrong numbers in three days.
         */
        val mealMarks: List<com.diapilot.core.analysis.MealMark> = emptyList(),
        /** Persistent meter-cal slope at build time: detector rises (raw
         *  scale) × this = the calibrated scale the forecast anchors on. */
        val foodRiseScale: Double = 1.0,
        /** Persistent meter-cal intercept — with the slope, lets runtime
         *  consumers (autosens) put raw readings on the model's scale. */
        val meterInterceptMmol: Double = 0.0,
        /** Calibration epoch start: the lens applies to data at/after this
         *  (the current sensor); older sensors' data stays raw. */
        val calEpochStartMs: Long = 0L,
    ) {
        /** Raw sensor points → the model's (meter-calibrated) scale. */
        fun calibrateReadings(
            pts: List<com.diapilot.core.collector.GlucosePoint>,
        ): List<com.diapilot.core.collector.GlucosePoint> =
            if (foodRiseScale == 1.0 && meterInterceptMmol == 0.0) pts
            else pts.map {
                if (it.tsMs >= calEpochStartMs) {
                    com.diapilot.core.collector.GlucosePoint(
                        it.tsMs, foodRiseScale * it.mmol + meterInterceptMmol,
                    )
                } else it
            }
    }

    private const val TAG = "TwinCache"
    private const val TTL_MS = 6L * 3_600_000

    /**
     * How long one pass may keep slicing the receipt backlog.
     *
     * Was 45 s, and that was a battery bug shipped the same evening it was
     * written. Background passes run about once a minute on a fresh reading, so a
     * 45-second drain is a 75% duty cycle — measured on a real phone at a high
     * CPU load with the app BACKGROUNDED, which overnight is a flat battery and an
     * alarm that may not be there in the morning.
     *
     * Six seconds keeps the point of A-37 — the queue drains itself and the user
     * never taps "Continue calculation" — at roughly a tenth of the cost, and it
     * is still six times more per pass than the single slice it replaced. A
     * long backlog now takes several passes instead of one, which is exactly
     * the trade a background diagnostic should make.
     */
    private const val STAGE10_DRAIN_MS = 6_000L
    private const val STAGE10_MAX_SLICES = 6
    private const val MIN_EPISODES = 15

    /**
     * Sensor readings in the food era below which [get] returns null and no
     * model exists at all — about two days of CGM. Named rather than written
     * at the gate because the diagnostics export quotes it: "readings in era:
     * 310 (the build gate is 500)" is the difference between a broken install
     * and a new one.
     */
    const val MIN_READINGS_FOR_BUILD = 500

    /**
     * How far BEFORE the calibration epoch a correction candidate is dropped.
     * The lens turns on at an instant inside a continuous CGM stream, so any
     * observation window crossing it would read a fabricated step of the whole
     * intercept. Covers the longest obsWindowMin (210) plus the end window, with
     * room to spare — being generous here costs a handful of candidates once per
     * sensor, and being wrong costs a silently corrupted ISF.
     */
    private const val STRADDLE_GUARD_MS = 240L * 60_000

    // The gates live in core (KernelGates) so the harness measures the SAME
    // ones — they used to be duplicated here and re-typed in every study, and
    // the walk-forward quietly ran stock IsfConfig() defaults instead.
    private val KERNEL_CFG = com.diapilot.core.analysis.KernelGates.PRODUCTION
    private val KERNEL_CFG_FAST = com.diapilot.core.analysis.KernelGates.PRODUCTION_FAST

    @Volatile
    private var cached: Model? = null

    @Volatile
    private var builtAtMs: Long = 0

    /**
     * Model for BACKGROUND forecast consumers (widget, watch, hypo alert,
     * companion): fresh memory → disk snapshot → stale whatever-exists.
     * NEVER builds — after a process restart the first widget tick used to
     * trigger full episode detection over the whole history. Building is
     * centralized in the poll worker (every ~15 min) and the main screen;
     * a background consumer briefly running on a slightly stale model is
     * the right trade against re-fitting on every heartbeat.
     */
    fun getForForecast(store: CollectorStore, context: android.content.Context): Model? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - builtAtMs < TTL_MS) return it }
        if (!diskChecked) {
            synchronized(this) {
                if (!diskChecked) {
                    diskRestored = TwinSnapshot.load(context)
                    diskChecked = true
                }
            }
        }
        // A snapshot that records a DIFFERENT food era was learned from history
        // the user has since excluded (or without history since admitted), so a
        // background consumer must not run it. A snapshot written before the
        // era joined the key records 0 and is still served, as before.
        val disk = diskRestored?.takeIf {
            it.key.foodEraStartMs == 0L ||
                it.key.foodEraStartMs == FoodEraSettings.current().startMs
        }
        disk?.let { if (now - it.builtAtMs < TTL_MS) return it.model }
        return cached ?: disk?.model
    }

    @Volatile
    private var diskRestored: TwinSnapshot.Restored? = null

    @Volatile
    private var diskChecked = false

    @Volatile
    private var builtKey: TwinCacheKey? = null

    /**
     * Force a rebuild on the next [get]. Call after data that changes what the
     * model would learn: NFC history backfill, a new food/dose entry, a dose
     * correction, a (re)label — otherwise the 6h TTL keeps a kernel/corridor
     * that never saw the new points until it expires.
     */
    @Synchronized
    fun invalidate() {
        // WHO threw the model away, by name. A build costs ~7.5 s on the phone, so an
        // unnecessary invalidation is not a cache miss — it is the battery bill. The
        // ledger showed a second build starting 15 s after the first and there was no
        // way to tell which of the ~20 call sites did it. Cheap: invalidations are
        // supposed to be rare, and if this line ever floods, that IS the finding.
        Log.i(TAG, "invalidate() ← ${Throwable().stackTrace.getOrNull(1)}")
        cached = null
        builtAtMs = 0
        builtKey = null
    }

    /**
     * The user moved the food-era start: everything in memory was learned
     * under the old boundary. Drops the built model AND the restored disk
     * snapshot, whose key now names the wrong era (see [getForForecast]).
     */
    @Synchronized
    fun onFoodEraChanged() {
        invalidate()
        diskRestored = null
        diskChecked = true
    }

    /** What the cache HOLDS, with nothing built and nothing loaded. */
    data class Peek(val model: Model?, val builtAtMs: Long, val fromDisk: Boolean)

    /**
     * A read-only look at the cache, for the diagnostics export.
     *
     * IT MUST NOT BUILD AND MUST NOT TOUCH THE DISK. [get] costs ~7.5 s on the
     * phone and [getForForecast] loads the snapshot on its first call; a button
     * that reports readiness would then be the thing that creates it, and the
     * report would say "a model exists" about a model it had just made. What
     * this returns is the honest answer to "does this process have a model
     * yet", which is the question a tester's file has to answer.
     */
    @Synchronized
    fun peek(): Peek {
        cached?.let { return Peek(it, builtAtMs, false) }
        return diskRestored?.let { Peek(it.model, it.builtAtMs, true) } ?: Peek(null, 0, false)
    }

    /**
     * Returns the model, rebuilding if stale; null while data is insufficient.
     * Insulin-profile settings (fast-DIA episode windows, user ISF prior)
     * come from [context].
     */
    @Synchronized
    fun get(store: CollectorStore, context: android.content.Context): Model? {
        val now = System.currentTimeMillis()
        val fastInsulin = Settings.insulinDiaMin(context) <= 210.0
        // The override is part of the model identity: without it here, changing
        // the setting would leave a stale twin in the cache.
        // The user's answers about meals the model could not account for. Read FIRST,
        // because they are part of the model's identity: re-marking a meal must rebuild
        // the twin, or the mark looks inert and the next session measures a model that
        // silently ignored it.
        // A HARD OFF SWITCH, and it exists because "inert until the user presses a chip" is not
        // the same as shadow-safe: the moment the first mark lands, `dishCurves` and the
        // donor corpus change under an unchanged shadow tag. Off, marks are still stored
        // and still shown — only the LEARNING gates stop. Default ON, because the whole
        // point of the screen is that the user's answer reaches the model.
        //
        // ┌─ CONDITION ATTACHED TO THIS GATE, and it is not a note-to-self ────────────┐
        // │ `FORECAST_ALGO_VERSION_FP_SHADOW` was NOT bumped when this layer landed,   │
        // │ deliberately: with an empty `meal_marks` table every gate below is the     │
        // │ identity, so a bump would reset the paired ledger A/B for nothing.         │
        // │                                                                            │
        // │ **BUMP IT IN THE SAME COMMIT AS THE FIRST STORED MARK.** From that moment  │
        // │ the corpus and `dishCurves` belong to a different generation, and a ledger │
        // │ that blends "before the user's answers" with "after" produces a paired A/B that │
        // │ cannot be read at all — which is worse than a reset one.                   │
        // │                                                                            │
        // │ The same applies to switching this pref: turning it off is also a          │
        // │ generation change once marks exist.                                        │
        // └────────────────────────────────────────────────────────────────────────────┘
        val marksTeach = Settings.marksTeachModel(context)
        val marks =
            if (marksTeach) com.diapilot.core.analysis.MealMarks(store.mealMarks())
            else com.diapilot.core.analysis.MealMarks.EMPTY
        val key =
            TwinCacheKey(
                fastInsulin,
                Settings.carbSensPer10gMmol(context),
                Settings.carbSensOverrideMmolPerG(context),
                marks.stamp(),
                Stage9EpisodeRuntime.CACHE_VERSION,
                HybridShadowRegistry.modelSha256().orEmpty(),
                FoodEraSettings.current().startMs,
            )
        cached?.let { if (now - builtAtMs < TTL_MS && builtKey == key) return it }

        // A COLD PROCESS IS NOT A STALE MODEL. `getForForecast` has always
        // served the disk snapshot, but `get()` looked only at `cached`, so
        // every process restart rebuilt a twin that was already on disk and
        // still inside its TTL — measured at a few seconds on a real phone,
        // against a database where the corpus had not moved.
        //
        // The key stored in the snapshot is what makes this safe, and until
        // now it was written and never read: the comment on
        // [TwinCacheKey.marksStamp] says so in as many words. Adopting a
        // snapshot whose key differs would resurrect exactly the staleness
        // that key exists to prevent, so the comparison is the whole gate.
        if (cached == null) {
            if (!diskChecked) {
                diskRestored = TwinSnapshot.load(context)
                diskChecked = true
            }
            diskRestored?.let { restored ->
                if (now - restored.builtAtMs < TTL_MS && restored.key == key) {
                    cached = restored.model
                    builtAtMs = restored.builtAtMs
                    builtKey = restored.key
                    Log.i(
                        TAG,
                        "disk snapshot accepted — age " +
                            "${(now - restored.builtAtMs) / 60_000} min, no build needed",
                    )
                    return restored.model
                }
            }
        }

        // ——— BUILD LEDGER ———
        // The app writes no logs at all, so when it started burning CPU and dying the
        // only instrument available was `top` — which cannot say WHO asked for a build,
        // WHY, or how long it took. One line in, one line out, per build. Builds are
        // rare by construction (6 h TTL + explicit invalidations), so this is a handful
        // of lines a day, not a stream; `adb logcat -s TwinCache` is the whole tool.
        val startedAtMs = android.os.SystemClock.elapsedRealtime()
        val reason =
            when {
                cached == null -> "no in-memory model (cold process or invalidate)"
                builtKey != key -> "settings/marks changed"
                else -> "TTL expired"
            }
        Log.i(TAG, "build START — $reason, on '${Thread.currentThread().name}'")
        fun sinceStart() = android.os.SystemClock.elapsedRealtime() - startedAtMs

        // Sensor-only: meter fingersticks are a different scale — they
        // calibrate (as a lens) and show as points, but must not sit inside
        // the trace the kernel/ISF are learned from.
        val foodEraStart = FoodEraSettings.current().startMs
        val readings = store.sensorReadings(foodEraStart, now)
        if (readings.size < MIN_READINGS_FOR_BUILD) return null
        val tLoad = sinceStart()

        // The meter lens must exist BEFORE episode detection, not after it. It
        // used to be built ~90 lines below, so detectIsfEpisodes saw RAW values —
        // and while a pure offset cancels in a DIFFERENCE (the measured ISF was
        // therefore fine), it does NOT cancel in a LEVEL: the startBg and hypo
        // gates were judged on a scale understated by the intercept.
        //
        // HONEST SCOPE: this makes the gates correct FROM THE EPOCH ON, not
        // retroactively — the epoch is days old and the corpus spans months, so
        // most episodes are still admitted on raw levels. Measured effect on the
        // corpus when this landed: ZERO (101/244 episodes, rejection table
        // byte-identical). No tagged correction dies to a level gate; they die to
        // other_bolus_in_window and activity_in_window (see IsfDetection.rejectedAt).
        // An earlier version of this comment claimed one specific correction as
        // a "proven victim of a phantom hypo" — the per-candidate telemetry added
        // in the same change DISPROVED that: it is rejected for activity, which is
        // evaluated before any level gate. Left here as the reason not to trust a
        // context line again.
        val buildCal = MeterCalCache.get(store, context)
        val trainingCalTimeline = MeterCalCache.trainingTimeline(store)
        val foodRiseScale = buildCal?.slope ?: 1.0
        val meterInterceptMmol = buildCal?.interceptMmol ?: 0.0
        // Calibration EPOCH: the affine fit is built from the CURRENT sensor's
        // meter pairs (the fit window is sensor-bounded). Applying its lens to
        // older sensors' readings would extrapolate — data before the epoch
        // stays raw (identity), which is honest: no meter truth exists there.
        val calEpochStartMs = run {
            val ws =
                Settings.libreSensorStartMs(context)
                    .takeIf { it > 0 }
                    ?.let { (now - it).coerceIn(3_600_000L, 14L * 24 * 3_600_000) }
                    ?: (14L * 24 * 3_600_000)
            now - ws
        }
        fun calScale(
            pts: List<com.diapilot.core.collector.GlucosePoint>,
        ): List<com.diapilot.core.collector.GlucosePoint> =
            if (trainingCalTimeline.isEmpty()) pts
            else
                pts.map { point ->
                    trainingCalTimeline
                        .lastOrNull {
                            point.tsMs in it.validFromMs..it.validUntilMs
                        }
                        ?.apply(point) ?: point
                }
        // One scale for everything downstream of the lens — the principle this
        // file already states for the forecast, now also honoured by ISF.
        val calReadings = calScale(readings)

        val boluses = store.boluses(foodEraStart, now)
        val annotationsAll = store.annotations(foodEraStart, now)
        // Event -> the meal that owns it, built once: production's note↔meal matcher plus
        // the corpus's session grouping. Every mark gate keyed on a detector onset goes
        // through this and nothing else.
        val markOwners = com.diapilot.core.analysis.mealMarkOwners(annotationsAll)
        // Logged food = ground truth (always disqualifies an ISF episode);
        // detector-inferred meals = a heuristic rise a "correction"-tagged
        // correction is allowed to override (see detectIsfEpisodes).
        val loggedFoodOnsets =
            annotationsAll
                .filter { it.estCarbs != null || it.kind == "food" }
                .map { it.tsMs }
                .distinct()
        val detectedFoodOnsets = store.meals(foodEraStart, now).map { it.onsetMs }.distinct()
        // Fixed experiment boundary. Deriving this from the oldest food note
        // lets an import/edit silently change the training population.
        val reliableFoodEraStartMs: Long? = foodEraStart
        // Activity windows BEFORE episode detection: a correction inside a
        // bout (muscle drain) or its sensitization tail (a nocturnal shot
        // after an evening 2.5h walk) measures insulin+exercise and inflates
        // the learned ISF. Windows over the full history — episodes span it.
        //
        // CALIBRATED detector (steps ≥80/min ≥20 min ∪ HR ≥120 bpm ≥20 min ∪
        // notes). The tool has lived in core for a while, reviewed, but the
        // model still built its windows with the legacy 40-steps / 1.25×median
        // thresholds — which call a store stroll and, via the HR path, plain
        // resting movement "activity". Measured cost of the old bar (`ActWinStudy`,
        // a harness study since deleted — the number below is the record):
        // 36 of 47 food-era windows PHANTOM, 15 of 71 corpus episodes down-weighted
        // by them, and in v9 that reaches the LIVE amplitude (fp19). This is where
        // the windows are BUILT, so switching here fixes every consumer — the ISF
        // episode detector below, the food corpus's soft contamination, the regime
        // corridor and the chart — from one place.
        //
        // NOT invariant-safe by assumption (see Activity.detectActivityWindows):
        // calibrated ⊆ legacy holds on THIS corpus but is not a property of the
        // code, so the switch is measured on fresh data, never assumed.
        val actWindows =
            com.diapilot.core.analysis.detectActivityWindows(
                hr = store.heartRate(foodEraStart, now),
                steps = store.steps(foodEraStart, now),
                notes = annotationsAll,
                calibrated = true,
            )
        // The epoch is a STEP, not a seam: the CGM stream runs straight through a
        // sensor change (measured: 141 points in ±6 h around it, max gap 5 min,
        // none across it), so an episode window spanning the epoch sees a
        // fabricated jump of the whole intercept — worth intercept/dose mmol per
        // unit of measured ISF. Exactly one candidate straddles it today, and it
        // survives only because a logged meal rejects it first: we were saved by
        // luck. Drop the straddlers so "no corruption" is a property, not an
        // accident. STRADDLE_GUARD_MS covers the longest observation window.
        val bolusesForIsf = boluses.filterNot {
            it.tsMs in (calEpochStartMs - STRADDLE_GUARD_MS)..calEpochStartMs
        }
        val det =
            detectIsfEpisodes(
                readings = calReadings,
                boluses = bolusesForIsf,
                foodOnsetsMs = loggedFoodOnsets,
                detectedFoodOnsetsMs = detectedFoodOnsets,
                cfg = if (fastInsulin) KERNEL_CFG_FAST else KERNEL_CFG,
                activityWindows = actWindows,
            )
        val episodes = det.episodes.filter { !it.anomaly }
        // MIN_EPISODES is a LEARNING gate, not an availability gate.  After
        // the fixed food-era cutoff an existing user can legitimately have a
        // dense month of CGM/food but fewer than 15 clean isolated correction
        // windows.  Returning null here erased the entire forward line.  Keep
        // the physiological insulin prior and wide uncertainty until enough
        // personal correction evidence exists; do not pretend six episodes
        // identified a new kernel.
        val personalizedKernelReady = episodes.size >= MIN_EPISODES
        if (!personalizedKernelReady) {
            Log.i(TAG, "Twin prior mode: ${episodes.size} episodes (< $MIN_EPISODES learning gate)")
        }
        // Recency + era weights: the body changes unmarked, fresh episodes
        // outvote the blind era; a dosing-regime break discounts what's
        // before it (user's own doses dropped & split at some point).
        val doseChange =
            if (personalizedKernelReady)
                com.diapilot.core.analysis.detectDoseRegimeChange(boluses, episodes = episodes)
            else null
        val sensitivityChange =
            if (personalizedKernelReady)
                com.diapilot.core.analysis.detectSensitivityRegimeChange(episodes)
            else null
        val physiologicalChangeMs =
            listOfNotNull(
                    doseChange?.takeIf { it.responseConfirmed }?.changeMs,
                    sensitivityChange?.changeMs,
                )
                .maxOrNull()
        val epWeights =
            com.diapilot.core.analysis.episodeWeights(
                episodes,
                now,
                // A confirmed dose/response shift OR a direct persistent ISF
                // shift discounts the old physiology.
                changepointMs = physiologicalChangeMs,
            )
        // Same scale as the episodes it learns from. Shape is affine-invariant
        // (a constant offset cancels in every difference) and the amplitude is
        // re-pinned below, so this is consistency, not a change of physics —
        // WHILE THE SLOPE IS 1.0. It is today (a pure intercept). Once 4+ meter
        // checks spread over a BG range produce a real slope, episode.isf, the
        // kernel plateau and amplitudeEstimate all scale by it, and the LIVE ISF
        // amplitude moves on that day. Probably more correct, but it will not
        // look like a code change — so expect it.
        val priorPerson =
            PhysioRuntime.artifact(store, now)
                ?.personModelAt(
                    java.util.Calendar.getInstance()
                        .apply { timeInMillis = now }
                        .get(java.util.Calendar.HOUR_OF_DAY)
                        .toDouble(),
                ) ?: HybridRuntimeMetrics.model()
        val learned =
            if (personalizedKernelReady) {
                insulinKernel(calReadings, episodes, weights = epWeights)
            } else {
                priorPerson?.let {
                    com.diapilot.core.hybrid.HybridForecastEngine(it).insulinKernelPoints(1.0)
                } ?: emptyList()
            }
        if (learned.isEmpty()) return null
        // The amplitude comes from TRUSTED corrections against a population
        // prior built from this body's own total daily dose — never from the
        // corpus, 97.5% of which predates food logging.
        val tdd =
            com.diapilot.core.analysis.totalDailyDose(
                boluses = boluses,
                basals = store.basalEvents(foodEraStart, now),
                nowMs = now,
            )
        val prior = tdd?.let { com.diapilot.core.analysis.isfPriorFromTdd(it) }
        val amplitudeEstimate =
            if (!personalizedKernelReady) {
                null
            } else if (prior == null || reliableFoodEraStartMs == null) {
                Log.w(
                    TAG,
                    "No ISF prior (tdd=$tdd, era=$reliableFoodEraStartMs) — kernel keeps its own scale"
                )
                null
            } else {
                com.diapilot.core.analysis.estimateCurrentIsfAmplitude(
                    episodes,
                    reliableFoodEraStartMs,
                    prior,
                )
            }
        // ISF prior: the learned SHAPE is kept, the amplitude is rescaled to
        // the user's known per-unit drop — historical episodes predate food
        // logging, so their measured drop is diluted by invisible meals.
        val kernel =
            if (!personalizedKernelReady) learned
            else
                run {
                    // BEFORE the rescale. Scaling and a running minimum COMMUTE, so this
                    // ordering is not cosmetic: monotone-first re-shapes the curve
                    // against a FIXED plateau — mass moves out of the first hour into
                    // the tail and the total per-unit drop is unchanged — while
                    // monotone-last would make the deepest bin the new plateau and raise
                    // the amplitude ~18%. Measured, the amplitude half of that bundle is
                    // harmful on its own (worse at every horizon on insulin-opened
                    // segments) and the reshape carries the entire gain. It is also the
                    // half that cannot be justified: the "deepest measured drop" is the
                    // minimum of 27 noisy plateau bins, and a perfectly flat curve with
                    // this bin-to-bin noise would hand back ~14% of it as selection.
                    // No estimate means no amplitude EVIDENCE — keep the learned shape
                    // at its own scale rather than pinning it to the unconfirmed corpus.
                    // The hand ISF used to be able to pin this target too
                    // (`Settings.isfOverrideMmol`). That was a THIRD door with no setter
                    // anywhere in the app; verified null on a real device before
                    // removing it, so this is a deletion of dead weight, not a change of
                    // behaviour. The hand ISF now has exactly one home: P1.
                    val target =
                        amplitudeEstimate?.mmolPerUnit
                            ?: -com.diapilot.core.analysis.monotoneKernel(learned).last().median
                    com.diapilot.core.analysis.shippedKernel(learned, target)
                }
        // The physio engine owns insulin timing. The legacy Twin kernel above
        // remains the learned correction evidence/forecast input, but food
        // deconvolution must subtract the profile whose onset/peak/tail the UI
        // and the engine actually declare.
        val deconvPhysio = PhysioRuntime.artifact(store, now)
        val deconvLegacy = HybridRuntimeMetrics.model()
        val deconvKernelCache = HashMap<Pair<Long, Double>, List<KernelPoint>>()
        val deconvKernelForBolus: (com.diapilot.core.collector.BolusPoint) -> List<KernelPoint> = { b ->
            deconvKernelCache.getOrPut(b.tsMs to b.units) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = b.tsMs }
                val hour =
                    cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60.0
                val person = deconvPhysio?.personModelAt(hour) ?: deconvLegacy
                person?.let {
                    com.diapilot.core.hybrid.HybridForecastEngine(it).insulinKernelPoints(b.units)
                } ?: kernel
            }
        }
        val tKernel = sinceStart()
        // Calibrate the corridor on the FOOD-LOGGED era when it's thick
        // enough (honest residuals), else the recent 30 days; and let the
        // calibration simulations know the food — otherwise every meal's
        // rise lands in the residuals and the corridor stays inflated.
        val eraStart = annotationsAll.filter { it.estCarbs != null }.minOfOrNull { it.tsMs }
        // A corridor needs statistics: a short era gave implausibly wide p80
        // bands ("quiet" wider than "after meal") — switch to the clean
        // era only once it can carry the estimate.
        val calFrom =
            if (eraStart != null && now - eraStart > 10L * 86_400_000) {
                maxOf(eraStart, now - 30L * 24 * 3_600_000)
            } else {
                now - 30L * 24 * 3_600_000
            }
        // Persistent meter calibration: everything the LIVE forecast consumes
        // (corridor widths, carb sensitivity, dish curves, profile rises) is
        // computed on the CALIBRATED scale — the scale the forecast anchor and
        // the kernel amplitude (user ISF) live on. The lens itself is now built
        // ABOVE, before episode detection, so the ISF gates share that scale too.
        val recent = calScale(readings.filter { it.tsMs > calFrom })
        // 30-day slice of the full-history windows computed above — the
        // regime classifier / exercise coefficients work on recent bouts.
        val actWindows30 = actWindows.filter { it.endMs > now - 30L * 24 * 3_600_000 }
        // One residual pass, widths bucketed by REGIME: quiet stretches get
        // an honestly narrow band, absorbing meals an honestly wide one.
        val calCal = java.util.Calendar.getInstance()
        val corridors =
            com.diapilot.core.twin.calibrateRegimeCorridors(
                recent,
                boluses,
                kernel,
                foods =
                    FoodSources.historicalFoods(
                        store,
                        null,
                        riseScale = foodRiseScale,
                        riseScaleFromMs = calEpochStartMs,
                    ),
                mealOnsetsMs = store.meals(calFrom, now).map { it.onsetMs },
                activityWindows = actWindows30,
                hourOf = { ts ->
                    calCal.timeInMillis = ts
                    calCal.get(java.util.Calendar.HOUR_OF_DAY)
                },
            )
        val corridor =
            corridors?.global
                ?: calibrateCorridor(
                    recent,
                    boluses,
                    kernel,
                    foods =
                        FoodSources.historicalFoods(
                            store,
                            null,
                            riseScale = foodRiseScale,
                            riseScaleFromMs = calEpochStartMs,
                        ),
                )
        val tCorridor = sinceStart()
        val byTod = if (personalizedKernelReady) aggregateByTod(episodes) else emptyMap()
        // `annotationsAll` is the SAME query — re-issued here since forever. Left as
        // it is for now (it is measured below, not assumed to be free), but if the
        // ledger ever shows this segment dominated by loading rather than computing,
        // this is the first thing to look at.
        // The live Twin corpus is deliberately unchanged by the observational
        // Stage 11 sidecar. Eligibility is applied to the experimental Stage9/
        // Stage10 receipt corpus below; forecast changes require promotion.
        val annotations = store.annotations(foodEraStart, now)
        val contexts = contextStats(readings, annotations)
        val tContexts = sinceStart()
        // Carbs sensitivity: meals carrying a grams estimate calibrate the
        // grams→mmol scale the prediction uses for first-time dishes.
        val labelByOnset =
            store
                .labeledMeals(limit = 500)
                .filter { it.event.onsetMs >= foodEraStart }
                .associate { it.event.onsetMs to it.labelName }
        val tLabels = sinceStart()
        val learnedSens =
            com.diapilot.core.analysis.carbSensitivity(
                com.diapilot.core.analysis
                    .carbEpisodes(
                        store.meals(foodEraStart, now),
                        labelByOnset,
                        annotations,
                        // Exercise skews both scales — episodes with elevated HR are
                        // excluded from calibration.
                        hr =
                            store.heartRate(
                                FoodEraSettings.current().clampFrom(now - 60L * 24 * 3_600_000),
                                now
                            ),
                        // Detector rises are raw-scale; the kernel term subtracted in
                        // glucoseEffect is meter-scale — put the rise there too, or
                        // the grams→mmol ratio inherits the calibration slope error.
                    )
                    .map {
                        if (it.onsetMs >= calEpochStartMs) it.copy(rise = it.rise * foodRiseScale)
                        else it
                    }
                    // ANSWERED meals leave the amplitude learner. This is the LIVE coefficient
                    // — `carbSensitivity` over detector episodes — so a "there were more grams"
                    // answer that did not land here would be a screen, not a correction. Episodes are
                    // resolved through their OWNING meal: a mark sits on the note-anchored
                    // session start and these are keyed by the DETECTOR's onset. Resolution by
                    // proximity was measured to reach a neighbour's evidence in a third of
                    // cases — see `mealMarkOwners`.
                    .excludingMarked(marks, markOwners),
                kernel,
                kernelForEpisode = { e ->
                    deconvKernelForBolus(
                        com.diapilot.core.collector.BolusPoint(e.onsetMs, e.bolusUnits ?: 1.0)
                    )
                },
            )
        // Carb-side prior, symmetric to the ISF one: the user's known
        // rise-per-10g wins while priced-meal calibration is thin (<10 meals).
        val sensPrior = Settings.carbSensPer10gMmol(context)?.div(10.0)
        val carbSens =
            when {
                sensPrior != null && (learnedSens == null || learnedSens.n < 10) ->
                    com.diapilot.core.analysis.CarbSensitivity(
                        mmolPerGram = sensPrior,
                        q1 = sensPrior * 0.7,
                        q3 = sensPrior * 1.3,
                        n = 0,
                    )
                else -> learnedSens
            }
        // The OVERRIDE is applied last and wins over both. The learned value is
        // carried alongside, not overwritten — an assumption must stay visibly an
        // assumption, which is precisely what the tablet constant did not do.
        val carbSensEffective =
            com.diapilot.core.analysis.effectiveCarbSens(
                carbSens,
                Settings.carbSensOverrideMmolPerG(context),
            )
        val tCarbSens = sinceStart()
        Log.i(
            TAG,
            "Twin built: ${episodes.size} episodes, kernel ${kernel.size} pts, " +
                "corridor w0=%.2f k=%.3f".format(corridor.w0, corridor.k) +
                (carbSens?.let { ", carbs %.3f mmol/g (n=%d)".format(it.mmolPerGram, it.n) } ?: ""),
        )
        // Exercise: the drop coefficient learned from clean (food-free)
        // bouts, conservative default otherwise (windows computed above).
        val learnedDrop =
            com.diapilot.core.twin.learnActivityDropPerMin(
                readings = readings.filter { it.tsMs > now - 30L * 24 * 3_600_000 },
                boluses = boluses,
                kernel = kernel,
                windows = actWindows30,
                foodOnsetsMs =
                    store
                        .meals(FoodEraSettings.current().clampFrom(now - 30L * 24 * 3_600_000), now)
                        .map { it.onsetMs },
            )
        // Prolonged post-exercise sensitization (evening walk → night low).
        val postActivityDrop =
            com.diapilot.core.twin.learnPostActivityDropPerMin(
                readings = readings.filter { it.tsMs > now - 30L * 24 * 3_600_000 },
                boluses = boluses,
                kernel = kernel,
                windows = actWindows30,
                foodOnsetsMs =
                    store
                        .meals(FoodEraSettings.current().clampFrom(now - 30L * 24 * 3_600_000), now)
                        .map { it.onsetMs },
            ) ?: 0.0
        val tActivity = sinceStart()

        // Curves on the METER-CALIBRATED scale (persistent layer only — the
        // transient models a recent check's residual, meaningless on months of
        // history). The kernel amplitude is ISF-pinned (meter scale), so
        // subtracting it from calibrated readings is consistent.
        val curveReadings = calReadings // identical to the ISF series — one full-history map, not two
        // Non-food confounders (activity / illness) that corrupt ANY
        // insulin-adjusted meal profile. Shared by dish curves and the note-
        // anchored corpus; food↔food overlap is handled separately per builder.
        // HARD confounders disqualify a meal (illness/alcohol/stress genuinely
        // corrupt the whole day; a fresh sensor's first hours are noise). SOFT
        // ones (activity) only down-weight it — the meal is real, activity just
        // shifted its response, and dropping it lost 2 of 6 daily breakfasts.
        val confounderWindows = buildList {
            annotationsAll.forEach { n ->
                // The whole note is the tag, in any stored form (key or word).
                val tag = com.diapilot.core.analysis.NoteTag.of(n.content)
                when (tag) {
                    com.diapilot.core.analysis.NoteTag.ILL,
                    com.diapilot.core.analysis.NoteTag.STRESS,
                    com.diapilot.core.analysis.NoteTag.ALCOHOL ->
                        add(
                            com.diapilot.core.analysis.FoodContaminationWindow(
                                n.tsMs - 3L * 3_600_000,
                                n.tsMs + 24L * 3_600_000
                            )
                        )
                    com.diapilot.core.analysis.NoteTag.NEW_SENSOR ->
                        add(
                            com.diapilot.core.analysis.FoodContaminationWindow(
                                n.tsMs - 60L * 60_000,
                                n.tsMs + 6L * 3_600_000
                            )
                        )
                    else -> Unit
                }
            }
        }
        val softConfounderWindows = buildList {
            actWindows30.forEach {
                add(
                    com.diapilot.core.analysis.FoodContaminationWindow(
                        it.startMs,
                        it.endMs + 90L * 60_000
                    )
                )
            }
            annotationsAll.forEach { n ->
                val tag = com.diapilot.core.analysis.NoteTag.of(n.content)
                if (
                    tag == com.diapilot.core.analysis.NoteTag.WORKOUT ||
                        tag == com.diapilot.core.analysis.NoteTag.WALK
                ) {
                    add(
                        com.diapilot.core.analysis.FoodContaminationWindow(
                            n.tsMs - 60L * 60_000,
                            n.tsMs + 6L * 3_600_000
                        )
                    )
                }
            }
        }
        // Rescue carbs (dextrose) spike a NEIGHBOURING dish's curve, so dish
        // curves treat them as contamination. But in the note-anchored corpus a
        // dextrose note IS a food onset (in allOnsets) that already truncates its
        // neighbours' clean windows — and its own rescue window would otherwise
        // self-block the very fast-carb episode we want to learn. So rescue
        // windows go to dish curves only, NOT to the deconvolution.
        val rescueWindows = annotationsAll.mapNotNull { n ->
            if (com.diapilot.core.analysis.isRescueNote(n.content)) {
                com.diapilot.core.analysis.FoodContaminationWindow(
                    n.tsMs - 30L * 60_000,
                    n.tsMs + 120L * 60_000
                )
            } else null
        }
        // Dish curves keep the old all-or-nothing exclusion (hard + soft), only
        // the note-anchored corpus below distinguishes soft = down-weight.
        val nonFoodContaminations = confounderWindows + softConfounderWindows + rescueWindows
        val tDish = sinceStart()
        // Phase-1 donor corpus, NOTE-ANCHORED via deconvolution: every logged
        // food note's absorption curve = observed ΔBG + insulin (kernel) added
        // back — so a well-dosed meal (flat glucose, invisible to the detector)
        // recovers its full profile. No longer gated on the detector finding a
        // rise; the note IS the anchor + identity. predictKinetics pools by
        // concept across dish names. Calibrated readings + same contamination
        // windows as the dish curves.
        // ONE SERIES PER DAY FOR THE CORPUS, and deliberately for the corpus ALONE.
        //
        // There was a stretch of days where `xdrip_sgv` and `libre_ble` both write, roughly
        // half an mmol apart and interleaved at ~20 s — a sawtooth with several times the step
        // noise of a single-source day, and the offset is LEVEL-DEPENDENT (larger at a higher
        // level, near-zero at a lower one), so large meals were distorted more than small ones.
        // On those overlapping days not one meal reached the corpus. Restricting to one source
        // recovers most of the meals and observed peaks, losing none.
        //
        // The narrowest place that fixes the corpus: this call, not `curveReadings` itself.
        // `curveReadings` also feeds the dish curves and the ISF episodes, so leaving it alone
        // keeps the change to what the corpus LEARNS.
        //
        // But «narrow» is not «insulated», and the first draft of this comment claimed the
        // latter. The alert reads its ANCHOR from the store directly — that part is untouched —
        // while its FOOD comes from this corpus: `Forecaster` takes `corpus = fingerprintCorpus`
        // whenever `noteAnchoredFood` is set, and that default is ON. So `hypo_alert` forecasts
        // through the filtered corpus. Bounded by amplitude in the fp22 record, not waved past.
        //
        // OPEN, deliberately not fixed here: the dossier keeps the UNFILTERED series so its raw
        // curve still shows what the sensors actually recorded — defensible — but
        // `neighbourOverlapAudit` RE-RUNS the deconvolution on that same unfiltered series. So
        // for the newly-visible meals the "how much belongs to the neighbour" subtraction is
        // computed across the sawtooth while the row it explains came from one source. Showing a
        // raw curve is one thing; computing a SUBTRACTION on a different series than the row is
        // the "the user debugs our bookkeeping instead of their physiology" failure the dossier exists
        // to avoid. Needs its own decision about which series a subtraction belongs to.
        // ONE RULER FOR THE CORPUS TOO.
        //
        // This used to call `preferPrimarySensor`, which kept one series PER
        // DAY. That was already ruled insufficient — "per-window selection
        // fixes the sawtooth and leaves the deeper error, where the corpus mixes
        // calibrations ACROSS episodes and its median measures nothing" — and the
        // gap was real: measured on many food-era days, the two rules kept
        // different sets on a meaningful fraction of them, so the deconvolution
        // corpus and every ISF/landmark measurement were reading different
        // glucose on a couple of days out of every five.
        val corpusReadings =
            calScale(
                com.diapilot.core.analysis.MeasurementStreamCore.chooseFrom(
                        store.sensorReadingsWithSource(foodEraStart, now),
                    )
                    .readings,
            )
        // One kernel per hour, from the SAME provider deconvKernelForBolus uses.
        // Cheap (24 entries) and exact while the person model carries a measured
        // action CDF, where the shape does not depend on the dose.
        val deconvKernelsByHour =
            (0..23)
                .mapNotNull { hour ->
                    (deconvPhysio?.personModelAt(hour.toDouble()) ?: deconvLegacy)?.let { person ->
                        hour to
                            com.diapilot.core.hybrid
                                .HybridForecastEngine(person)
                                .insulinKernelPoints(1.0)
                    }
                }
                .toMap()
        val fingerprintCorpus =
            com.diapilot.core.analysis
                .deconvolvedMealObservations(
                    // Never let an open meal teach its partial rise as a completed
                    // amplitude/peak and immediately feed that answer back into its
                    // own live forecast. Six hours covers the longest food window.
                    notes = annotationsAll.filter { it.tsMs <= now - 6L * 3_600_000L },
                    readings = corpusReadings,
                    boluses = boluses,
                    kernel = kernel,
                    nowMs = now,
                    contaminationWindows = confounderWindows,
                    softContaminationWindows = softConfounderWindows,
                    // NEIGHBOUR SUBTRACTION. Off, a meal is subtracted from its follower
                    // only when EVERY ingredient of it is already pooled — a gate that by
                    // construction never opens for a dessert, so that whole class was
                    // learning its amplitude off curves still carrying the previous meal.
                    // The effective carb sensitivity is what a not-yet-measured neighbour
                    // is modelled at, so it must be the EFFECTIVE value, not the learned
                    // one: the arm would otherwise subtract neighbours at an amplitude the
                    // forecast does not use.
                    carbSensPerGram = carbSensEffective?.mmolPerGram,
                    kernelForBolus = deconvKernelForBolus,
                    // SUBTRACT A NEIGHBOUR WITH THE CURVE THE FORECAST DRAWS, at the
                    // neighbour's OWN measured amplitude where a previous pass found one.
                    // Enabled BY THE USER'S DECISION after reviewing their own
                    // cards: a dish without a concept profile was subtracted as
                    // zero, so the dish behind it was credited with a large spurious rise for its grams.
                    // Measured: that episode's error falls sharply, episodes above twice the
                    // global scale drop by more than half, and meals with no neighbour do not move
                    // at all. It converges over passes (the largest per-pass change shrinks by
                    // more than an order of magnitude).
                    // Known and NOT yet closed: a residual under-read on meals that
                    // had a neighbour, part of which is window censoring.
                    neighbourAppearance =
                        (deconvPhysio?.personModelAt(12.0) ?: deconvLegacy)?.let { person ->
                            com.diapilot.core.analysis.NeighbourContributionV1.factory(
                                com.diapilot.core.hybrid.physioForecastEngine(person),
                                carbSensEffective?.mmolPerGram ?: .165,
                                // AT THE GLOBAL SCALE, not at the neighbour's own recovered
                                // amplitude. Re-measured on the stand that finally
                                // reproduces most of this corpus: the same episode's error drops
                                // substantially more at the global scale than at its
                                // neighbour's own, and corpus-wide the episodes above twice
                                // the scale drop further with the global scale than without it.
                                // The "own amplitude" refinement was justified on a stand that
                                // computed something else — the earlier dish's own recovered
                                // value is depressed BECAUSE the later dish took its rise, so
                                // subtracting it at that value cannot give the rise back.
                                useOwnAmplitude = false,
                            )
                        },
                    // DIVIDE THE INSULIN THE WAY THE CARBOHYDRATE IS DIVIDED.
                    // Measured on the stand that reproduces most of this corpus: the
                    // window returned ALL the insulin but shared out only the food, so
                    // one flat-glucose episode with substantial insulin in the window was
                    // charged a large spurious rise for its grams. Dividing the meal's
                    // food signal by expected carb delivery corrects it sharply, and
                    // corpus-wide the episodes above twice the scale drop by more than half with
                    // the tail percentile falling similarly. A lone meal has a share of 1 and is untouched.
                    divideInsulinLikeCarbs = true,
                )
                // The answers reach the donor pool LAST, after the deconvolution has run. That
                // order is deliberate: a marked meal still truncates its neighbours' clean
                // windows and still gets subtracted from them — it happened, and pretending it
                // did not would corrupt the meals around it. What changes is only what may be
                // LEARNED from it, and rows are neutralised in place rather than dropped so the
                // triage screen can still show them and the user can still change their mind.
                .applyMarks(marks)
        val tCorpus = sinceStart()
        // DOSSIERS ARE NO LONGER BUILT, by the user's decision:
        // "a component breakdown, if it shows nonsense, is not worth having."
        //
        // Of everything in `MealDossierSelection` the app read ONE field —
        // `audits` — which produced a single flag, `overlap_unresolved`,
        // already covered by `row.neighbourResolved`. Nobody read the `shown` field.
        // The build itself ran on EVERY twin rebuild regardless.
        val tDossier = sinceStart()
        val rt = Runtime.getRuntime()
        Log.i(
            TAG,
            // Every segment is named for what runs INSIDE it, not for the call that
            // ends it — the first version of this line labelled a 3387 ms segment
            // «dish» when the dish curves were a few ms of it and the corridor
            // calibration was the rest.
            "build DONE in $tDossier ms — load $tLoad, episodes+kernel ${tKernel - tLoad}, " +
                "corridors ${tCorridor - tKernel}, contextStats ${tContexts - tCorridor}, " +
                "labeledMeals ${tLabels - tContexts}, carbSens ${tCarbSens - tLabels}, " +
                "activity ${tActivity - tCarbSens}, " +
                "corpus ${tCorpus - tDish}, dossier ${tDossier - tCorpus}; " +
                "readings ${readings.size}, corpus ${fingerprintCorpus.size} rows, " +
                "heap ${(rt.totalMemory() - rt.freeMemory()) shr 20}/${rt.maxMemory() shr 20} MB",
        )
        return Model(
                kernel,
                corridor,
                corridors = corridors,
                byTod = byTod,
                contexts = contexts,
                carbSens = carbSensEffective,
                carbSensLearned = carbSens,
                learnedCarbSens = learnedSens,
                learnedIsfMmolPerU = if (personalizedKernelReady) -learned.last().median else 0.0,
                kernelEpisodes = episodes.size,
                effectiveEpisodes =
                    if (personalizedKernelReady)
                        com.diapilot.core.analysis.effectiveSampleSize(epWeights)
                    else 0.0,
                reliableFoodEraStartMs = reliableFoodEraStartMs,
                estimatedIsfMmolPerU = amplitudeEstimate?.mmolPerUnit ?: 0.0,
                freshIsfEpisodes = amplitudeEstimate?.freshEpisodes ?: 0,
                freshIsfBlend = amplitudeEstimate?.blend ?: 0.0,
                activityWindows = actWindows30,
                activityDropPerMin =
                    learnedDrop ?: com.diapilot.core.twin.DEFAULT_ACTIVITY_DROP_PER_MIN,
                learnedActivityDrop = learnedDrop,
                postActivityDropPerMin = postActivityDrop,
                doseChange = doseChange,
                sensitivityChange = sensitivityChange,
                fingerprintCorpus = fingerprintCorpus,
                deconvKernelsByHour = deconvKernelsByHour,
                mealMarks = marks.active,
                foodRiseScale = foodRiseScale,
                meterInterceptMmol = meterInterceptMmol,
                calEpochStartMs = calEpochStartMs,
            )
            .also {
                cached = it
                builtAtMs = now
                builtKey = key
                // Persist for background consumers across process restarts.
                TwinSnapshot.save(context, it, now, key)
                diskRestored = TwinSnapshot.Restored(it, now, key)
                diskChecked = true
                // Model is safely published BEFORE the diagnostic is scheduled.
                // Exceptions, OOM and queue rejection fail open; the sidecar has a
                // time budget and can never block this forecast/alert caller.
                val stage10SourceKey =
                    (store as? SqliteCollectorStore)?.let {
                        FoodCalculationRegistry.sourceKey(it, context, now)
                    }
                if (stage10SourceKey == null || !FoodCalculationRegistry.isCurrent(stage10SourceKey)) {
                    publishThenScheduleStage9(
                        Unit,
                        {},
                        task = { generation ->
                            // Closed-episode eligibility controls learning weight, not
                            // whether the user may see a diagnostic receipt. Filtering
                            // here made History blank for every estimated meal.
                            val diagnosticAnnotations = annotationsAll
                            // Receipts are display text: after a language switch the whole
                            // history is rebuilt, not only the hot tail.
                            val fullRefresh =
                                FoodCalculationRegistry.needsFullRefresh(now) ||
                                    FoodCalculationRegistry.needsLanguageRefresh(context)
                            val receiptLanguage = context.uiLanguage()
                            // The visible hot tail is 72 h; eight preceding hours are
                            // included so a meal/bolus just before the boundary can
                            // still contribute its physiological tail to the first day.
                            val lookbackMs =
                                if (fullRefresh) 30L * 24 * 3_600_000L else 80L * 3_600_000L
                            val replaceIds =
                                annotationsAll
                                    .asSequence()
                                    .filter {
                                        it.tsMs >= now - lookbackMs &&
                                            it.tsMs <= now &&
                                            com.diapilot.core.analysis.isFoodNote(it)
                                    }
                                    .map { it.id }
                                    .toSet()
                            // ---- THE QUEUE NOW DRAINS ITSELF ----------------------
                            //
                            // A slice used to run once per full Twin build and stop when
                            // its two-second budget expired, leaving "Continue calculation"
                            // for the user to tap — repeatedly, and each tap invalidated the
                            // whole Twin and rebuilt it just to earn one more slice.
                            //
                            // Worse after the history refresh was put on a change stamp
                            // (A-35): full builds became rare on the History tab, which
                            // is exactly the tab where the receipts are read, so the
                            // backlog would have stopped draining altogether.
                            //
                            // So the slicing loops HERE instead. Each slice keeps its
                            // two-second budget, so nothing hogs a core; between slices
                            // the thread yields; and each one publishes, so the count on
                            // screen climbs while the user watches. The wall-clock cap exists
                            // because this is a diagnostic sidecar, not the forecast: if
                            // a very long backlog does not finish inside it, the stored
                            // offset carries to the next pass exactly as before.
                            var result =
                                Stage9EpisodeRuntime.buildDetailed(
                                    diagnosticAnnotations,
                                    corpusReadings,
                                    boluses,
                                    episodes,
                                    reliableFoodEraStartMs,
                                    now,
                                    budgetMs = FoodCalculationRegistry.takeContinuationBudget(),
                                    carbEvidenceAsOf = { id, asOf ->
                                        store.carbEvidenceKnownAt(id, asOf)
                                    },
                                    lookbackMs = lookbackMs,
                                    personModelForBolus = { b ->
                                        val cal =
                                            java.util.Calendar.getInstance().apply {
                                                timeInMillis = b.tsMs
                                            }
                                        val hour =
                                            cal.get(java.util.Calendar.HOUR_OF_DAY) +
                                                cal.get(java.util.Calendar.MINUTE) / 60.0
                                        deconvPhysio?.personModelAt(hour) ?: deconvLegacy
                                    },
                                    clusterOffset = FoodCalculationRegistry.continuationOffset(),
                                    context = context,
                                )
                            var accepted =
                                FoodCalculationRegistry.updateEpisodeAttributionWindow(
                                    result.receipts,
                                    replaceIds,
                                    generation,
                                    result.complete,
                                    result.processedClusters,
                                    result.totalClusters,
                                    result.budgetLimited,
                                    fullRefresh,
                                    now,
                                    result.nextOffset,
                                    language = receiptLanguage,
                                )
                            if (accepted && stage10SourceKey != null)
                                FoodCalculationRegistry.persist(context, stage10SourceKey)
                            val drainUntil = android.os.SystemClock.elapsedRealtime() + STAGE10_DRAIN_MS
                            var guard = 0
                            while (
                                accepted &&
                                    !result.complete &&
                                    result.nextOffset > 0 &&
                                    android.os.SystemClock.elapsedRealtime() < drainUntil &&
                                    guard++ < STAGE10_MAX_SLICES
                            ) {
                                // Yield between slices: this runs while the user is using the
                                // app, and a sidecar that makes the journal stutter is
                                // its own bug report.
                                try {
                                    Thread.sleep(120L)
                                } catch (_: InterruptedException) {
                                    break
                                }
                                result =
                                    Stage9EpisodeRuntime.buildDetailed(
                                        diagnosticAnnotations,
                                        corpusReadings,
                                        boluses,
                                        episodes,
                                        reliableFoodEraStartMs,
                                        now,
                                        budgetMs = 2_000L,
                                        carbEvidenceAsOf = { id, asOf ->
                                            store.carbEvidenceKnownAt(id, asOf)
                                        },
                                        lookbackMs = lookbackMs,
                                        personModelForBolus = { b ->
                                            val cal =
                                                java.util.Calendar.getInstance().apply {
                                                    timeInMillis = b.tsMs
                                                }
                                            val hour =
                                                cal.get(java.util.Calendar.HOUR_OF_DAY) +
                                                    cal.get(java.util.Calendar.MINUTE) / 60.0
                                            deconvPhysio?.personModelAt(hour) ?: deconvLegacy
                                        },
                                        clusterOffset = result.nextOffset,
                                        context = context,
                                    )
                                accepted =
                                    FoodCalculationRegistry.updateEpisodeAttributionWindow(
                                        result.receipts,
                                        replaceIds,
                                        generation,
                                        result.complete,
                                        result.processedClusters,
                                        result.totalClusters,
                                        result.budgetLimited,
                                        fullRefresh,
                                        now,
                                        result.nextOffset,
                                        language = receiptLanguage,
                                    )
                                if (accepted && stage10SourceKey != null)
                                    FoodCalculationRegistry.persist(context, stage10SourceKey)
                            }
                        }
                    )
                } else {
                    Log.i(TAG, "Stage10 receipts restored/current — deconvolution skipped")
                }
                // Diagnostics dump — the Kotlin-computed food-model view, so it can
                // be exported/inspected without re-deriving it by hand.
                try {
                    DiagnosticsExport.write(context, store, it, now)
                } catch (_: Exception) {}
            }
    }
}
