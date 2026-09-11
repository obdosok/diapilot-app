package com.example.diapilot.data

import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.twin.ForecastInputs
import com.diapilot.core.twin.ForecastResult
import com.diapilot.core.twin.PersonalModel

/**
 * The app-side adapter for the core ForecastEngine: gathers the causal
 * snapshot from the store and calls the ONE forecast function every
 * consumer (screen, watch, widget, hypo alert) must share. If you are
 * about to call predictWithCorridor directly from app code — stop, use
 * this, or production and validation will diverge again.
 */
object Forecaster {
    // THE CACHE WAS REMOVED ALONG WITH WHAT IT MADE CHEAPER.
    //
    // It existed for the legacy pass: autosens is 14 hours of readings
    // plus a fit, and it used to be computed for every consumer (screen,
    // watch, widget, alert, companion — all within one minute). The physio
    // run is measured on-device at tens of milliseconds, so there is nothing
    // left worth caching, and a cache keyed on something that doesn't cover
    // every knob catches discrepancies silently.

    /** Uptime at class load — the process clock the quiet window is measured on. */
    private val PROCESS_START_MS = android.os.SystemClock.elapsedRealtime()
    private const val LEARN_QUIET_AFTER_START_MS = 30_000L

    /**
     * Forecast from [anchorTsMs]/[anchorMmol] with everything knowable now.
     * [minutePoints] already calibrated; empty disables momentum.
     */
    fun forecast(
        store: CollectorStore,
        model: TwinCache.Model,
        nowMs: Long,
        anchorTsMs: Long,
        anchorMmol: Double,
        minutePoints: List<GlucosePoint> = emptyList(),
        horizonMin: Double = 180.0,
        /** Non-null = this forecast is SHOWN to that consumer ("main" /
         *  "watch" / "widget" / "hypo_alert") and gets recorded in the
         *  prospective ledger (deduplicated). Null = internal/what-if. */
        recordAs: String? = null,
        /** Gate the anchor for sensor plausibility (compression lows, EOL
         *  noise) before trusting it. Default off. */
        plausibilityGate: Boolean = false,
        /** NULL = NO RUN HAPPENED, not "the run came out bad". Became
         *  nullable once the legacy substitution was removed: before that a
         *  line was always returned, because a second twin was substituted in
         *  on a physio failure. Distinguishing "no line" from "weak line" is
         *  the consumer's job — HypoAlertNotifier already does this and falls
         *  back to the raw reading. */
    ): ForecastResult? {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = anchorTsMs }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        // THE LEGACY FOOD LAYER IS NO LONGER COMPUTED HERE.
        //
        // `FoodSources.activeFoods` — dish curves, fingerprints, profile
        // rises — used to be called on EVERY pass and, after the legacy twin
        // was removed, fed exactly one thing: the dedup hash in the ledger. A
        // full model computation for the sake of a hash guarding a row about
        // ANOTHER model. The hash now comes from the run that actually
        // happened (`HybridShadowRun.dedupHash`).
        //
        // THREE SETTINGS FELL AWAY WITH IT — `notePreferredFood`,
        // `noteAnchoredFood`, `activityModelV2`. They only reached this far,
        // meaning they no longer changed the line or the alert; the
        // parameters were removed so this is visible to the compiler, not
        // only to measurement. The Settings entries and UI toggles still
        // stand — removing them is a separate decision.
        val boluses = store.boluses(
            anchorTsMs - com.diapilot.core.twin.BOLUS_LOOKBACK_MS, nowMs,
        )

        // Cache check — the expensive half (autosens + simulation) is skipped
        // on a hit; the ledger below still records per-consumer (idempotent).
        // The horizon is NORMALIZED to 180 min so the widget (60), hypo alert
        // (50) and screen/watch (180) share one entry — callers get a slice.
        val fullHorizon = maxOf(horizonMin, 180.0)
        // THE LEGACY ARM IS REMOVED, and with it autosens and the cache.
        //
        // A second, full forecast used to be computed here on a different
        // model — to hand the physio arm four template fields and to serve
        // as a fallback arm. Both reasons are gone: the template reason,
        // because physio assembles the result itself (see HybridShadow); the
        // fallback-arm reason, by the user's decision: a line from a
        // different model looks normal, while silence is noticeable.
        //
        // The cache was needed precisely for this cost (autosens — 14 hours
        // of readings plus a fit, per consumer); the physio run is measured
        // on-device at tens of milliseconds against several seconds for the
        // whole legacy pass.
        //
        // What physio took from here and now computes directly:
        val regime = com.diapilot.core.twin.classifyRegime(
            anchorTsMs,
            store.meals(anchorTsMs - 3L * 3_600_000, anchorTsMs).map { it.onsetMs }.sorted(),
            boluses,
            model.activityWindows,
            hour,
        )
        // The gate itself lives in core/analysis and has nothing to do with
        // the engine — it reads readings, not the model. The window and
        // calibration are unchanged.
        val sensorSuspect: com.diapilot.core.analysis.SuspectReason? =
            if (plausibilityGate) {
                val window = model.calibrateReadings(
                    store.sensorReadings(anchorTsMs - 40L * 60_000, anchorTsMs),
                )
                if (window.isEmpty()) null
                else com.diapilot.core.analysis.plausibilityGate(window.sortedBy { it.tsMs })
                    .lastOrNull { it.point.tsMs <= anchorTsMs }
                    ?.takeIf { !it.ok }?.reason
            } else null
        // v11 runs on the same causal anchor with its own food, insulin,
        // ONE ENGINE. The v11 arm was removed by the user's
        // decision after a paired A/B measurement found no meaningful difference
        // between the arms at any horizon — the day-paired medians were tiny
        // against the between-day spread. Running a second
        // engine on every forecast to feed a comparison with no power is the
        // definition of a rudiment.
        //
        // The base twin `result` above stays: PHYSIO takes it as `liveMetadata`
        // (health, reasons, the momentum tail), not as its forecast.
        val contextFlags = DailyDiscrepancyRuntime.currentFactorIds(store, nowMs)
        // LEARNING IS NOT ON THE DRAWING PATH.
        //
        // This ran SYNCHRONOUSLY, and the user measured the cost: logging one
        // meal note took several seconds before the chart moved. Instrumented,
        // `Forecaster.forecast` took multiple seconds while the physio forecast
        // inside it took a small fraction of that. Nearly all of the time was here.
        //
        // The mechanism: `maintain` skips when its input hash is unchanged, and
        // that hash is built from `physio_model_input_revisions_v1`, which a
        // trigger on `annotations` bumps. So logging a meal guaranteed a full
        // maintenance run — research matrix, global CS, promotions — and the
        // forecast waited for all of it.
        //
        // WHY DEFERRING IS CORRECT AND NOT A SHORTCUT: what `maintain` produces
        // is what the model has LEARNED, and learning cannot change in the
        // instant a meal is logged. The forecast reads the previous snapshot,
        // which is the same snapshot it would have read a second earlier. The
        // user's own framing: nearly every parameter is slow-moving; there is
        // no need to recompute more often than every couple of days.
        //
        // Fire-and-forget on the maintenance queue, the same one the
        // closed-episode pass uses, so the two learning jobs share one thread
        // and cannot run against each other on the same SQLite file.
        if (recordAs == "main" && store is SqliteCollectorStore) {
            val ctx = store.appContext
            com.example.diapilot.UiMaintenanceQueue.schedule {
                try {
                    // NOT DURING THE OPENING BURST.
                    //
                    // The whole chain runs inside one `beginTransaction`, and a
                    // cold process must run it once — `lastLearnAtMs` is
                    // process-local, so the interval floor cannot cover the
                    // first pass. Measured right after a fresh install, the gap
                    // between the fast `Forecaster.forecast` timing and the slow
                    // live-forecast timing closed within tens of milliseconds of
                    // this chain's own log line.
                    //
                    // Half a minute is chosen against what the learners
                    // measure, not against the animation: the adaptive-ISF
                    // window is ten days and promotion ramps on independent
                    // days, so nothing downstream can tell. The screen settles
                    // long before it.
                    if (android.os.SystemClock.elapsedRealtime() - PROCESS_START_MS < LEARN_QUIET_AFTER_START_MS) {
                        return@schedule
                    }
                    // Learning maintenance was removed: there is nothing left to learn.
                } catch (e: Exception) {
                    android.util.Log.w("Forecaster", "physio learning maintenance failed: ${e.message}")
                }
            }
        }
        val physioArtifact = try { PhysioRuntime.artifact(store, nowMs) } catch (_: Exception) { null }
        val tPhysio0 = android.os.SystemClock.elapsedRealtime()
        val physioShadow = try {
            if (physioArtifact != null) HybridShadow.forecast(
                store, model, anchorTsMs, anchorMmol, regime, sensorSuspect,
                knowledgeTsMs = nowMs,
                // ONE LINE FOR THE SCREEN AND FOR THE ALERT.
                //
                // The flag was `recordAs == "main"`, meaning the screen got the
                // full effect of insulin already injected (`fullKnownInsulinDelta`),
                // while the alert got the horizon-truncated version. Two
                // consumers of the same model were doing different arithmetic.
                //
                // Measured across a large set of paired real forecasts over about
                // a week: at the one-hour mark the discrepancy exceeded a full
                // mmol in about a third of passes, with a sizeable maximum. And it
                // was asymmetric exactly where it matters for a decision: among
                // passes where the SCREEN read below 5.0, the alert read higher
                // than the screen far more often than the reverse, with a
                // meaningful average gap, and a notable share of passes had the
                // screen below 3.9 while the alert did not.
                //
                // Found by external review; the direction of the fix is that the
                // alert sees MORE insulin, so its line runs lower and the alert
                // fires earlier. The cost in false positives has to be measured
                // separately, before shipping.
                fullCausalInsulinDisplay = true,
                personModelOverride = physioArtifact.personModelAt(java.util.Calendar.getInstance().apply { timeInMillis = anchorTsMs }.get(java.util.Calendar.HOUR_OF_DAY).toDouble(),contextFlags),

                physioVariance = physioArtifact.variance,
                physioMacroTiming = physioArtifact.macroTiming,
                contextFlags=contextFlags,
                physioArtifact=physioArtifact,
            ) else null
        } catch (e: Exception) { android.util.Log.w("Forecaster", "physio forecast failed: ${e.message}"); null }
        // A shadow generation starts only on an immutable, ledgered main run.
        // Preview/widget/What-if calls must never move its evaluation start.
        // `ownerContextFlags` was REMOVED: it cost three database scans
        // per run and was read by NOBODY — a leftover from the candidate
        // pairs, which were removed around the same time. The modifiers it
        // was collected for don't exist either.
        // CANDIDATE PAIRS ARE AN EXPERIMENT, NOT A SCREEN INPUT.
        //
        // Each candidate costs TWO extra full forecasts (baseline + candidate),
        // and this ran on every foreground pass. Measured on a real device: a
        // live-forecast pass took several seconds, repeated for every refresh, with the
        // screen waiting on it — the user reported the app hanging and this is the
        // larger half of why.
        //
        // Nothing on screen reads these runs; they exist to fill the paired
        // ledger. Sampling them every few minutes instead of every pass costs
        // the experiment almost nothing — the pairs are minute-cadence and
        // autocorrelated, which the registry already warns about when quoting
        // them — and it also slows the ledger's growth, which is most of a
        // large database file.
        android.util.Log.i(
            "ForecastPerf",
            "physioShadow in ${android.os.SystemClock.elapsedRealtime() - tPhysio0} ms",
        )
        // THE LEDGER RECORDS THE LINE THAT WAS SHOWN. Restored after a regression.
        //
        // `184ba345` removed the second `record` call along with the legacy
        // arm, and what survived was the one recording `result` — the BASE
        // TWIN — under `FORECAST_ALGO_VERSION`. So for a while the app
        // stored a forecast nobody saw, while the physio line it actually drew
        // went unrecorded and unscored. Two things broke silently with it: the
        // discipline-#7 acceptance test («arm A must reproduce the STORED
        // forecast_runs») had nothing to reproduce, and
        // `ForecastLedger.recentHybridAnomalies` — which selects
        // `algo_version = HYBRID_V11_SHADOW_ALGO_VERSION AND consumer='main'`
        // — was empty by construction.
        //
        // The tag comes off the run itself rather than from a constant chosen
        // here, so the label cannot drift from the model that produced it.
        // NO SECOND MODEL — SO NO LINE. The user's decision.
        //
        // This used to have `?: result` — the legacy-twin substitution. It
        // reads as "a weak curve is better than none", but for consumers
        // that isn't true: silence is visible (an empty chart, a missing
        // arrow), while a line from a different model looks completely
        // normal. The reading-based alert still stands — HypoAlertNotifier
        // knows how to fire without a forecast, see `forecastOk` there.
        // AN ABSENT RUN MUST BE LOUD (class A-02) — AND RECORDED.
        //
        // The first version of this place stayed silent: a row with no
        // points is easy to mistake for a fake one. The user's objection is
        // decisive — the alternative to a fake row is not silence, but a
        // RECORDED REFUSAL. Readings back-fill
        // (discipline #6), so a hole in `forecast_runs` cannot be told apart
        // from "the app wasn't working", and it was exactly the
        // `forecast-v15` rows that led to finding a multi-hour physio outage:
        // there had been a substitution — and there was a trace.
        // Having removed the substitution, we are obligated to leave a trace.
        if (physioShadow == null) {
            val msg = "PHYSIO unavailable for ${recordAs ?: "preview"} — no forecast"
            if (recordAs == "hypo_alert") android.util.Log.e("Forecaster", msg)
            else android.util.Log.w("Forecaster", msg)
            // A log is seen only by someone who is watching; a refusal must be COUNTABLE.
            if (recordAs != null) try {
                (store as? SqliteCollectorStore)?.writableDatabase?.let { db ->
                    ForecastLedger.recordRefusal(
                        db, anchorTsMs, anchorMmol, nowMs, recordAs,
                        reason = if (physioArtifact == null) "no physio artifact"
                        else "physio arm returned no run",
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("Forecaster", "refusal record failed: ${e.message}")
            }
            return null
        }
        val shown = physioShadow.result
        val shownVersion = physioShadow.algorithmVersion
        // THE PROSPECTIVE CANDIDATE A/B IS GONE, by the user's
        // decision: the ledger's purpose was never clear to the user, who
        // checks any hypothesis on the chart by comparing halves of the
        // history against each other, and found this style of hypothesis
        // testing more likely to mislead than to help.
        //
        // The user is right, and the code already agreed: the ONE learner
        // that reaches production, `closed_episode_global_isf`, carries
        // `ApplicationPolicyV1.MEASURED_BASE`, which by its own comment "is not
        // a candidate awaiting a prospective verdict" — it goes live on its
        // SUPPORT gates. The other candidate hypotheses sat at SHADOW_CANDIDATE and
        // never graduated. So a large table of `physio_parallel_scores` gated
        // nothing, while each due pass cost TWO extra full forecasts per
        // candidate.
        //
        // A prospective ledger earns its place when replay is impossible —
        // many users, a shifting population, no access to their data. At N=1
        // every byte of the history is here, so walk-forward on it strictly
        // dominates. What the ledger still does that replay cannot is prove
        // that the SHIPPED code ran; that is `forecast_runs` above, which
        // stays.
        if (recordAs != null) {
            // Hash of everything the forecast could see: a recompute with the
            // same knowledge dedupes; new bolus/food = a new run.
            val inputHash = physioShadow.dedupHash
            // THE SCREEN'S OWN LEDGER WRITE IS DEFERRED — see
            // [LedgerWriteQueue]. It waits on the single SQLite writer, which
            // the learning transaction can hold for seconds; measured at
            // several seconds on the pass that followed a logged meal.
            //
            // ONLY the screen. `hypo_alert`, `watch` and `widget` run on
            // workers where nothing is waiting on a frame, and `forecast_runs`
            // with `consumer='hypo_alert'` is the app's heartbeat — the series
            // discipline #6 says to size blind holes against. Deferring that
            // one would turn a killed process into a false "we were not
            // looking" hole, which is exactly the fiction that rule exists to
            // prevent.
            fun writeLedger(body: () -> Unit) =
                if (recordAs == "main") LedgerWriteQueue.submit(body) else body()
            try {
                (store as? SqliteCollectorStore)?.writableDatabase?.let { db -> writeLedger {
                    // `HybridWhatIfLedger.captureRecent` was REMOVED: it
                    // froze "no dose against actual" on every pass into
                    // seven tables, and none of the three reader functions had
                    // a caller left. The same class of thing as the removed
                    // prospective A/B — machinery that gates nothing.
                    val tLed0 = android.os.SystemClock.elapsedRealtime()
                    val tLed1 = tLed0
                    ForecastLedger.record(
                        db, shown, anchorTsMs, anchorMmol, nowMs, recordAs, inputHash,
                        algoVersion = shownVersion,
                        applied = ForecastLedger.appliedSummary(
                            physioArtifact?.personModelAt(
                                java.util.Calendar.getInstance()
                                    .apply { timeInMillis = anchorTsMs }
                                    .get(java.util.Calendar.HOUR_OF_DAY).toDouble(),
                                contextFlags,
                            ),
                        ),
                    )
                    val tLed2 = android.os.SystemClock.elapsedRealtime()
                    if (tLed2 - tLed0 >= 200) android.util.Log.i(
                        "ForecastPerf",
                        "леджер ($recordAs): whatIf ${tLed1 - tLed0} ms · " +
                            "forecast ${tLed2 - tLed1} ms",
                    )
                } 
                    // THE ARM PAIR IS GONE WITH THE ARM. `recordPair` compared
                    // LEGACY_V11 against PHYSIO_V1, and there is no legacy run
                    // left to compare against — M-119 measured that comparison
                    // and found no difference at any horizon. The CANDIDATE
                    // pairs stay: they compare a physio baseline against a
                    // physio candidate for ONE hypothesis, which is still a
                    // live question and still promotes or refutes.
                }
            } catch (e: Exception) {
                android.util.Log.w("Forecaster", "ledger record failed: ${e.message}")
            }
        }
        // Callers asking for a shorter horizon get their slice of the shared
        // full-horizon result (the ledger above keeps the full curve).
        return if (horizonMin < fullHorizon) {
            val cutoff = anchorTsMs + (horizonMin * 60_000).toLong()
            shown.copy(points = shown.points.filter { it.tsMs <= cutoff })
        } else shown
    }
}
