/**
 * The hypo-alert state machine as a PURE function — extracted from the
 * Android notifier so alert sequences (predicted → confirmed low → dextrose
 * snooze → persistent escalation → recovery) are unit-testable. The notifier
 * owns notifications/sound/prefs; every decision lives here.
 *
 * Safety invariants encoded:
 *  - an implausibly low value alarms LOUD (possible artifact ≠ downgrade —
 *    a true severe hypo can read the same);
 *  - a fingerstick silences alerts only when it is in range AND newer than
 *    the sensor anchor;
 *  - a dextrose snooze never outlives the persistence window: treatment that
 *    didn't work must not be silent;
 *  - a persistent low re-fires on a short cadence regardless of night-gentle.
 */
package com.diapilot.core.twin

/** Persisted between passes (the notifier maps this to SharedPreferences). */
data class HypoAlertState(
    val episodeStartMs: Long = 0,
    val gentleAtMs: Long = 0,
    val escalated: Boolean = false,
    val lastFireMs: Long = 0,
    val lastArtifactMs: Long = 0,
    /** When the user first became ACTUALLY low this episode — the sustained-low
     *  clock. Distinct from [episodeStartMs], which opens on the first at-risk
     *  pass (a night corridor PREDICTION can open it hours before any real low).
     *  Survives a dead-band blip; resets to 0 on true recovery. */
    val lowSinceMs: Long = 0,
    /** The most recent ACTUALLY-low pass — measures how long a dead-band plateau
     *  has run, so the hold can be capped instead of lasting all night. */
    val lastLowMs: Long = 0,
    /** Low time this episode that we actually OBSERVED, summed pass to pass — the
     *  number the "prolonged low — N min" alert text is allowed to state. Not `now - lowSinceMs`:
     *  that difference silently assumes the sensor never stopped, so a 63-minute
     *  hole counted as 63 minutes of hypo. It does NOT decide anything — alarms
     *  still run on the wall clock, which may assume the worst. Appended LAST — a
     *  field inserted mid-struct breaks positional callers silently. */
    val lowObservedMs: Long = 0,
    /** The previous pass of THIS episode, low or dead-band — the ruler the
     *  observed clock measures with. Deliberately not [lastLowMs]: that one
     *  freezes during a dead-band hold, which would make a hold we watched the
     *  whole way through look like a sensor hole. */
    val lastPassMs: Long = 0,
)

/** Everything one pass needs to decide. All times in epoch ms. */
data class HypoAlertInputs(
    val nowMs: Long,
    val anchorTsMs: Long,
    val anchorMmol: Double,
    val thresholdMmol: Double,
    /** Forecast crosses the floor within the lead window (null-safe caller). */
    val predictedHit: Boolean,
    val night: Boolean,
    val nightGentle: Boolean,
    val soundOn: Boolean = true,
    /** Freshest fingerstick, if any. */
    val lastMeterMmol: Double? = null,
    val lastMeterTsMs: Long = 0,
    /** Latest logged dextrose rescue, if any. */
    val lastDextroseTsMs: Long? = null,
    val dextroseSnoozeEnabled: Boolean = true,
    val snoozeMs: Long = 20L * 60_000,
    val persistMs: Long = 25L * 60_000,
    val lowRefireMs: Long = 5L * 60_000,
    val cooldownMs: Long = 45L * 60_000,
    /** App foregrounded at — acknowledges a pending gentle alert. */
    val appOpenedAtMs: Long = 0,
    /** Band above the floor still treated as "not recovered" — the same margin the
     *  predictive rule uses, so [thresholdMmol, thresholdMmol+marginMmol) is the
     *  dead band a single blip must not clear an open low through. Appended LAST
     *  on purpose: a field inserted mid-struct breaks positional callers silently. */
    val marginMmol: Double = com.diapilot.core.PersonalParams.DEFAULT.hypoMarginMmol,
    /** The plausibility gate de-trusted the anchor as a sensor artifact (from
     *  [ForecastResult.sensorSuspect]). Default false ⇒ every existing caller and
     *  the whole cascade below is byte-identical. When true, a "verify the sensor"
     *  advisory replaces a factual SEVERE on an impossible value — WITHOUT going
     *  silent, because a real mild low can hide under the garbage. Appended LAST:
     *  a field inserted mid-struct breaks positional callers silently. */
    val sensorSuspect: Boolean = false,
)

enum class HypoAction {
    NONE,
    /** The plausibility gate says the anchor is a sensor artifact: a GENTLE
     *  "verify with a meter / trust your body" advisory, not a factual SEVERE on
     *  an impossible value — and not silence (a real mild low may hide under it). */
    SENSOR_CHECK,
    /** Critically low OR sensor artifact — full alarm, "verify NOW" wording. */
    ARTIFACT_ALARM,
    /** Confirmed below the floor — full alarm, re-fires until recovery. */
    LOW_ALARM,
    /** Low longer than the persistence window — full alarm, short cadence. */
    PERSISTENT_ALARM,
    /** Predicted hypo, night-gentle mode — quiet buzz. */
    PREDICT_GENTLE,
    /** Predicted hypo, full alarm (day, or night-gentle off). */
    PREDICT_ALARM,
    /** Gentle went unacknowledged and the risk persists — full alarm. */
    ESCALATE_ALARM,
}

data class HypoDecision(
    val action: HypoAction,
    val state: HypoAlertState,
    /** Drives the watch signal — true while an alert is live (not snoozed). */
    val alertActive: Boolean,
)

/** Below this a reading is critically low and/or a compression artifact. */
const val HYPO_ARTIFACT_FLOOR_MMOL = 1.4

/** Artifact/critical value re-alerts on the urgent cadence. */
const val HYPO_ARTIFACT_REFIRE_MS = 5L * 60_000

/** Sensor-check advisory re-fires gently — it is a "verify" prompt, not an alarm. */
const val HYPO_SENSOR_CHECK_REFIRE_MS = 10L * 60_000

/** A night-gentle alert escalates after this if unacknowledged. */
const val HYPO_ESCALATE_AFTER_MS = 5L * 60_000

/** Persistent-low re-fire cadence. */
const val HYPO_PERSIST_REFIRE_MS = 10L * 60_000

/** A fingerstick older than this no longer speaks for "now". */
const val HYPO_METER_FRESH_MS = 15L * 60_000

/**
 * How long a dead-band plateau after a real low keeps the sustained clock alive.
 * A genuine blip-above is one or two readings; past this the plateau IS a
 * recovery. Measured on the device's own anchors before this cap existed: 21
 * holds over 8 days, the longest 34 min — i.e. unbounded holds were real, not
 * theoretical, and they carried the plateau into the "prolonged, N min" duration.
 */
const val HYPO_DEADBAND_HOLD_MS = 15L * 60_000

/**
 * The longest pass-to-pass step still credited as CONTINUOUSLY OBSERVED low time.
 * The notifier refuses to judge an anchor older than 10 min and simply returns, so
 * a sensor gap leaves this machine's state untouched and no pass happens at all —
 * meaning a duration measured as `now - lowSinceMs` counts the hole as hypo. The
 * worst real case observed: an alarm that printed a prolonged-low duration
 * nearly ten times longer than the low that was actually observed.
 *
 * Beyond this step we did not see what happened, so the duration we PRINT credits
 * nothing for it: the app measures, it does not assume. Note the split — the
 * alarm DECISION (`persistentLow`) still runs on the wall clock, because assuming
 * the worst is the safe way to decide and the honest way to describe is not.
 *
 * 15 min is measured, and measured on the right series — which took two attempts.
 * The pass stream is NOT the reading tables: readings BACK-FILL (one OOP2
 * broadcast replays ~15 min of history), so a hole the app was blind through
 * looks perfectly continuous in `glucose_readings` afterwards, and
 * `minute_readings` only begins partway through the corpus. The passes are what the device
 * recorded itself, `forecast_runs WHERE consumer='hypo_alert'` — one per call of
 * the notifier. Over its 8.5 days (7 699 passes) the step is median 1.0, p99 8,
 * p99.9 40 min, max 101; steps past 15 min are 0.442% — about 4 a day, and 8 of
 * them landed inside an open low episode. Replaying both clocks over that stream:
 * of 52 persistent-alarm fires, 21 printed an inflated duration, the worst by
 * +213 minutes. So this guards a fabrication that HAPPENS, several times a week.
 */
const val HYPO_OBSERVED_STEP_MS = 15L * 60_000

fun decideHypoAlert(s: HypoAlertState, i: HypoAlertInputs): HypoDecision {
    val cleared = HypoAlertState(lastArtifactMs = s.lastArtifactMs, lastFireMs = s.lastFireMs)

    // (a0) SENSOR IMPLAUSIBLE (plausibility gate): a GENTLE "verify with a meter /
    // trust your body" advisory that PRECEDES every alarm branch — glucose cannot
    // really be 0.5, so screaming a factual SEVERE on it is wrong. Not silent: it
    // re-fires gently so a real mild low hiding under the artifact still prompts a
    // check. Default `sensorSuspect=false` ⇒ this branch never runs for any
    // existing caller, and the cascade below is byte-identical.
    if (i.sensorSuspect) {
        return if (i.nowMs - s.lastArtifactMs >= HYPO_SENSOR_CHECK_REFIRE_MS) {
            HypoDecision(HypoAction.SENSOR_CHECK, s.copy(lastArtifactMs = i.nowMs), alertActive = true)
        } else {
            HypoDecision(HypoAction.NONE, s, alertActive = true)
        }
    }

    // (a) Critically low / artifact: LOUD, urgent cadence, watch buzzes.
    // This branch leaves `lastPassMs` frozen, which is not a claim that artifact
    // minutes are hypo — it is the opposite. A short artifact run is swallowed by
    // the next low pass's step (≤15 min credits the whole span, as for any pass),
    // while a LONG one credits nothing at all, because anchors like 0.34, 0.12,
    // 0.00, −0.11 in a row are a dead sensor, not measured glucose,
    // and billing them as observed low time is the very fabrication above. The
    // alarm itself is unaffected: this branch is the loudest one there is.
    if (i.anchorMmol < HYPO_ARTIFACT_FLOOR_MMOL) {
        return if (i.nowMs - s.lastArtifactMs >= HYPO_ARTIFACT_REFIRE_MS) {
            HypoDecision(HypoAction.ARTIFACT_ALARM, s.copy(lastArtifactMs = i.nowMs), alertActive = true)
        } else {
            HypoDecision(HypoAction.NONE, s, alertActive = true)
        }
    }

    // (b) In-range fingerstick NEWER than the sensor anchor → trust it.
    val meterFine = i.lastMeterMmol != null &&
        i.lastMeterMmol >= i.thresholdMmol &&
        i.lastMeterTsMs >= i.anchorTsMs &&
        i.nowMs - i.lastMeterTsMs <= HYPO_METER_FRESH_MS
    if (meterFine) return HypoDecision(HypoAction.NONE, cleared, alertActive = false)

    // (c) Recovery vs. still-in-trouble. "Low" for the sustained clock means
    // actually below the floor; a value that has only popped into the dead band
    // [threshold, threshold+margin) after a real low has NOT recovered — a single
    // such blip must not restart the clock. It used to clear the episode: the
    // wrist went quiet, the face went green, and the persistent alarm fired
    // ~25 min late (found on 42 real sustained lows).
    val lowNow = i.anchorMmol < i.thresholdMmol
    val inDeadBand = i.anchorMmol < i.thresholdMmol + i.marginMmol   // includes lowNow
    val wasLow = s.lowSinceMs != 0L
    // A blip-above is held; a PLATEAU is a recovery. The wrist is released either
    // way: at 4.1 the user is not below the floor, and buzzing once per reading with
    // nothing on screen is the exact defect already fixed for predictions.
    val holdingBlip = wasLow && inDeadBand && i.nowMs - s.lastLowMs <= HYPO_DEADBAND_HOLD_MS
    // Credit for the interval since the previous pass of this episode — see
    // HYPO_OBSERVED_STEP_MS for why a longer one credits nothing. Computed HERE,
    // above branch (c), so a dead-band hold credits the time it watched: the hold
    // cap and the step cap are both 15 min, so a hold that runs to its maximum
    // would otherwise wipe ALL credit at a cliff — all-or-nothing, not a taper.
    // That argument stands on the two constants alone; on the pass stream it cost
    // 3 passes / 49 min, small only because holds rarely run to the cap.
    val step = if (s.lastPassMs != 0L && i.nowMs - s.lastPassMs <= HYPO_OBSERVED_STEP_MS) {
        i.nowMs - s.lastPassMs
    } else 0L
    if (!lowNow && !i.predictedHit) {
        return if (holdingBlip) {
            HypoDecision(                                           // clock survives
                HypoAction.NONE,
                s.copy(lowObservedMs = s.lowObservedMs + step, lastPassMs = i.nowMs),
                alertActive = false,
            )
        } else {
            HypoDecision(HypoAction.NONE, cleared, alertActive = false)
        }
    }

    // At risk — the episode is open (or opens now).
    val episodeStart = if (s.episodeStartMs == 0L) i.nowMs else s.episodeStartMs
    // The sustained-low clock counts from the first ACTUALLY-low pass, never from
    // the episode open (a night corridor prediction can open it hours early).
    // `holdingBlip` is repeated here on purpose: branch (c) only runs when
    // !predictedHit, so without it a dead-band value arriving WITH a prediction
    // would reset the clock — bug 2 in full. That path is unreachable today only
    // because findPredictedHypo enforces the same margin in ANOTHER file, and
    // core correctness must not rest on an invariant it cannot see.
    val stillLow = lowNow || holdingBlip
    val lowSince = if (stillLow) (if (s.lowSinceMs == 0L) i.nowMs else s.lowSinceMs) else 0L
    val lastLow = if (lowNow) i.nowMs else if (stillLow) s.lastLowMs else 0L
    // While observation is continuous this sums to exactly `nowMs - lowSinceMs`,
    // dead-band holds included — the accumulator subtracts holes and nothing else.
    val lowObserved = if (stillLow) s.lowObservedMs + step else 0L
    var st = s.copy(
        episodeStartMs = episodeStart,
        lowSinceMs = lowSince,
        lastLowMs = lastLow,
        lowObservedMs = lowObserved,
        lastPassMs = if (stillLow) i.nowMs else 0L,
    )
    // Deliberately still the WALL clock, not lowObservedMs. The two answer
    // different questions and only one of them may assume: deciding whether to
    // alarm must assume the WORST across a hole (low at one reading and low at
    // the next, minutes apart — glucose does not teleport), while the sentence we
    // then print may claim only what was actually seen. Judging persistence on
    // observed time instead was measured to cost 8 minutes of total silence —
    // wrist included, via the `treated` snooze below — at a low reading, because
    // a hole made a real persistent low look fresh again.
    val persistentLow = lowNow && lowSince != 0L && i.nowMs - lowSince >= i.persistMs

    // (d) Recently treated and not yet persistently LOW → hold while it absorbs.
    // Persistence now counts real low time, so a lingering prediction can no
    // longer break a fresh dextrose snooze, yet a treatment that didn't work still
    // can't stay silent once the low itself has lasted persistMs.
    val treated = i.dextroseSnoozeEnabled && !persistentLow &&
        i.lastDextroseTsMs != null && i.nowMs - i.lastDextroseTsMs <= i.snoozeMs
    if (treated) return HypoDecision(HypoAction.NONE, st, alertActive = false)

    // (e) Persistent low → full alarm on a short cadence, night or day. Requires
    // being ACTUALLY low right now, not merely at predicted/corridor risk: at
    // night the alert watches the wide lower CORRIDOR band, so a stable in-range
    // value (98 with a night corridor dipping below 70) kept `predictedHit` true,
    // held the episode open, and — once it had been open long enough — fired
    // "Glucose long below 70 (98)" every minute though the user was never below 70.
    // A sustained-low alarm means sustained LOW; a lingering prediction escalates
    // through the predict branch (f), not this one. "Persistent" counts from the
    // first actually-low pass (lowSinceMs), so a prediction that opened the episode
    // early no longer jumps a fresh low straight onto this 10-min cadence. The
    // DURATION this alarm prints is a separate quantity — see lowObservedMs.
    if (persistentLow) {
        return if (i.nowMs - st.lastFireMs >= HYPO_PERSIST_REFIRE_MS) {
            HypoDecision(
                HypoAction.PERSISTENT_ALARM,
                st.copy(lastFireMs = i.nowMs, escalated = true),
                alertActive = true,
            )
        } else HypoDecision(HypoAction.NONE, st, alertActive = true)
    }

    // (e2) Confirmed low right now → full alarm, re-fire until recovery.
    if (lowNow) {
        return if (i.nowMs - st.lastFireMs >= i.lowRefireMs) {
            HypoDecision(
                HypoAction.LOW_ALARM,
                st.copy(lastFireMs = i.nowMs, gentleAtMs = i.nowMs, escalated = true),
                alertActive = true,
            )
        } else HypoDecision(HypoAction.NONE, st, alertActive = true)
    }

    // (f) Predicted hypo (not low yet): gentle-then-escalate at night.
    val gentleMode = i.night && i.nightGentle
    if (st.gentleAtMs == 0L) {
        return if (i.nowMs - st.lastFireMs >= i.cooldownMs) {
            HypoDecision(
                if (gentleMode) HypoAction.PREDICT_GENTLE else HypoAction.PREDICT_ALARM,
                st.copy(lastFireMs = i.nowMs, gentleAtMs = i.nowMs, escalated = !gentleMode),
                alertActive = true,
            )
        } else HypoDecision(HypoAction.NONE, st, alertActive = true)
    }
    val acknowledged = i.appOpenedAtMs > st.gentleAtMs
    if (gentleMode && !st.escalated && !acknowledged &&
        i.nowMs - st.gentleAtMs >= HYPO_ESCALATE_AFTER_MS
    ) {
        return HypoDecision(
            HypoAction.ESCALATE_ALARM,
            st.copy(escalated = true),
            alertActive = true,
        )
    }
    // Reaching here means PREDICTED-only (every actually-low path returned above)
    // and there is nothing left to say. `alertActive` is not cosmetic: WatchServer
    // re-casts it to the wrist every 60 s and the bridge buzzes once per reading
    // while it is true. Holding it open for a prediction that has already been
    // escalated (or acknowledged) is what buzzed the user every minute all night
    // — the notification was never the culprit, it re-fires only every 10 min.
    // Release the wrist once the warning has been fully delivered; an ACTUAL low
    // re-arms it immediately through the lowNow branch above.
    // Only ESCALATION releases it. Acknowledgement deliberately does NOT (an
    // existing test pins that: opening the app suppresses the escalation but the
    // warning stands) — whether that is right is a product question, not one to
    // decide reactively while patching a bug.
    return HypoDecision(HypoAction.NONE, st, alertActive = !st.escalated)
}
