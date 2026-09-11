package com.example.diapilot.collect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.diapilot.MainActivity
import com.example.diapilot.R
import com.example.diapilot.i18n.localized

/**
 * The predictive hypo alert — fires when the twin's median forecast crosses
 * the range floor within the lead window while the current value is still
 * fine. Validated by the in-app backtest (Analysis card) BEFORE being
 * trusted; 45-minute cooldown; never names an insulin dose — the hint is
 * carbs, per the app's hard rule.
 */
object HypoAlertNotifier {

    private const val TAG = "HypoAlert"

    /** True while a hypo alert is ACTIVE (predicted or low, not snoozed) — the
     *  WatchServer ORs it into isLow so the watch face vibrates on OUR smart,
     *  predictive alert, not just a raw threshold. In-process (both run in
     *  CollectorService). */
    @Volatile
    var alertActive: Boolean = false
        private set

    /** Test window: the watch server treats "low" as true until this time, so
     *  a settings button can prove the watch vibrates end-to-end. */
    @Volatile
    var watchTestUntilMs: Long = 0L

    /** True if a hypo signal should reach the watch NOW (real alert or test). */
    fun watchSignalActive(): Boolean =
        alertActive || System.currentTimeMillis() < watchTestUntilMs

    /**
     * End-to-end watch test. Real alerts reach the wrist TWO ways: the watch
     * face's own low-alarm on the server's isLow flag (path A), and Zepp
     * mirroring the phone notification (path B — the one that actually fired
     * for the user). This exercises BOTH: it flips isLow for 45s AND posts a
     * normal (mirror-able) notification so the wrist buzzes via Zepp.
     */
    fun postWatchTest(context: Context) {
        val now = System.currentTimeMillis()
        // 3 min: the bridge casts to the watch once a minute, so the isLow
        // flag must outlive at least one full cast to reliably reach the
        // watch app-service (and its sleep-mode vibration).
        watchTestUntilMs = now + 180_000
        val text = context.localized()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WATCH_TEST, text.getString(R.string.hypo_alert_notifier_watch_test_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = text.getString(R.string.hypo_alert_notifier_watch_test_channel_desc) },
        )
        val hhmmss = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(now))
        // Cancel the previous one and post with a UNIQUE id + fresh content:
        // re-posting the SAME id just updates in place (no new "arrival"), so
        // Zepp mirrored it only once. A distinct id + changing text makes every
        // press a genuinely new notification the watch buzzes on.
        nm.cancel(NOTIF_ID_WATCH_TEST)
        val n = NotificationCompat.Builder(context, CHANNEL_WATCH_TEST)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(text.getString(R.string.hypo_alert_notifier_watch_test_title, hhmmss))
            .setContentText(text.getString(R.string.hypo_alert_notifier_watch_test_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setWhen(now)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        nm.notify(NOTIF_ID_WATCH_TEST + (now % 1000).toInt(), n)
    }
    // v2: the channel is recreated silent (AlarmPlayer drives audio) — a new
    // id so the old sound-carrying channel's settings don't linger.
    private const val CHANNEL = "hypo_alert_v2"
    private const val CHANNEL_HYPER = "hyper_alert"
    private const val CHANNEL_WATCH_TEST = "watch_test"
    private const val NOTIF_ID = 3001
    private const val NOTIF_ID_WATCH_TEST = 3003
    private const val NOTIF_ID_HYPER = 3002
    private val COOLDOWN_MS =
        (com.diapilot.core.PersonalParams.DEFAULT.hypoCooldownMin * 60_000).toLong()
    private const val PREF_LAST = "hypo_alert_last_ms"
    private const val PREF_LAST_HIGH = "hyper_alert_last_ms"
    /** A separate cooldown: "sustained high" and "rising out of range" are
     *  different events, and one must not suppress the other. */
    private const val PREF_LAST_SUSTAINED = "sustained_high_last_ms"
    // Gentle-then-escalate state (night mode): when the gentle nudge began,
    // and whether it has already grown into the full alarm.
    private const val PREF_GENTLE_AT = "hypo_gentle_at_ms"
    private const val PREF_ESCALATED = "hypo_escalated"
    private const val PREF_EPISODE_START = "hypo_episode_start_ms"
    private const val PREF_LOW_SINCE = "hypo_low_since_ms"
    private const val PREF_LAST_LOW = "hypo_last_low_ms"
    private const val PREF_LOW_OBSERVED = "hypo_low_observed_ms"
    private const val PREF_LAST_PASS = "hypo_last_pass_ms"
    private const val PREF_ARTIFACT_LAST = "hypo_artifact_last_ms"
    // Thresholds/cadences live in core HypoAlertLogic (unit-tested).

    private fun snoozeMs(context: Context): Long =
        com.example.diapilot.data.Settings.hypoSnoozeMin(context) * 60_000L

    private fun persistMs(context: Context): Long =
        com.example.diapilot.data.Settings.hypoPersistMin(context) * 60_000L

    /** Latest dextrose rescue (logged note) within [windowMs], else null. */
    private fun recentDextroseMs(
        store: com.diapilot.core.collector.CollectorStore,
        now: Long,
        windowMs: Long,
    ): Long? = store.annotations(now - windowMs, now)
        .filter { it.content.startsWith(com.diapilot.core.analysis.RESCUE_NOTE_PREFIX, ignoreCase = true) }
        .maxByOrNull { it.tsMs }?.tsMs

    fun maybeNotify(context: Context, store: com.diapilot.core.collector.CollectorStore) {
        try {
            val text = context.localized()
            val hypoOn = com.example.diapilot.data.Settings.hypoAlertEnabled(context)
            val hyperOn = com.example.diapilot.data.Settings.hyperAlertEnabled(context)
            // Disabled alerts must also stop the watch signal.
            if (!hypoOn) alertActive = false
            if (!hypoOn && !hyperOn) return
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(
                TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE,
            )

            val lastMain = store.lastSensorReading() ?: return

            // Freshest calibrated anchor (same promotion as the header).
            val minuteCal = com.example.diapilot.data.MinuteCalCache.get(store, context)
            val lm = store.lastMinuteReading()
            var anchorTs = lastMain.tsMs
            var anchorMmol = lastMain.mmol
            if (minuteCal != null && lm != null && lm.tsMs > lastMain.tsMs + 90_000) {
                anchorTs = lm.tsMs
                anchorMmol = minuteCal.apply(lm.mmol)
            }
            // Match the HEADER's number: apply the meter-check calibration too.
            // Without it the alert showed a raw 39 while the screen (and the
            // value the user trusts) showed a calibrated 62 — and the threshold
            // was judged on the wrong scale.
            com.example.diapilot.data.MeterCalCache.get(store, context)?.let {
                anchorMmol = it.correctedAt(anchorTs, anchorMmol)
            }
            if (now - anchorTs > 10 * 60_000) return  // stale reading — can't judge

            // Forecast for the PREDICTIVE alert only — but degrade gracefully:
            // a value already below the floor MUST alert from the reading alone,
            // even when the twin is unbuilt or the forecast is untrustworthy
            // (the old hard returns here meant a confirmed 43 mg/dl could be
            // silent). forecastOk gates only the prediction and the hyper side.
            val model = com.example.diapilot.data.TwinCache.getForForecast(store, context)
            val result = if (model != null) {
                try {
                    com.example.diapilot.data.Forecaster.forecast(
                        store, model, now,
                        anchorTsMs = anchorTs, anchorMmol = anchorMmol,
                        minutePoints = if (minuteCal != null) {
                            store.minuteReadings(anchorTs - 15L * 60_000, anchorTs)
                                .map { com.diapilot.core.collector.GlucosePoint(it.tsMs, minuteCal.apply(it.mmol)) }
                        } else emptyList(),
                        horizonMin = 50.0,
                        recordAs = "hypo_alert",
                        plausibilityGate = com.example.diapilot.data.Settings.plausibilityGate(context),
                        // THREE FOOD TOGGLES REMOVED: they only led into the
                        // legacy food layer, which is no longer on the
                        // forecast path. The alert and the screen now count
                        // food through ONE path — the physio arm's causal
                        // corpus — so the disagreement about "what food was
                        // counted", which these toggles existed to reconcile,
                        // is now structurally impossible rather than merely
                        // disabled.
                        // ONE source of truth for the safety owner (A-01). The
                        // default is still the frozen v11 incumbent; what this
                        // removes is the hardcode that ignored the setting, so
                        // the screen and the alarm can no longer disagree about
                        // who owns the alert without that being an explicit,
                        // recorded choice.
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "forecast failed: ${e.message}"); null
                }
            } else null
            val forecastOk = result != null &&
                result.health != com.diapilot.core.twin.ForecastHealth.STALE &&
                result.health != com.diapilot.core.twin.ForecastHealth.INSUFFICIENT_DATA
            val prediction = if (forecastOk) result!!.points else emptyList()
            val mgdl = com.example.diapilot.data.Units.isMgdl(context)
            val fmtT = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val pp = com.diapilot.core.PersonalParams.DEFAULT

            // ---- low side: episode state machine (the urgent one). ALL the
            // decision logic lives in core decideHypoAlert (unit-tested
            // sequences); this block only maps prefs↔state, composes the
            // texts and drives the notification + AlarmPlayer.
            if (hypoOn) {
                val threshold = com.example.diapilot.data.Settings.rangeLoMmol(context)
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val night = hour in 0..6
                val sound = com.example.diapilot.data.Settings.hypoAlertSound(context)
                val hit = if (forecastOk) com.diapilot.core.twin.findPredictedHypo(
                    prediction, anchorTs, anchorMmol, threshold,
                    // EXPLICIT TOGGLE, default OFF — see Settings.nightCorridorLow.
                    // A ForecastHealthV1 fix repaired forecast health and thereby
                    // silently armed this branch: on real history it produces
                    // many alerts against zero genuine night lows.
                    useCorridorLow = night &&
                        result!!.health == com.diapilot.core.twin.ForecastHealth.TRUSTED &&
                        com.example.diapilot.data.Settings.nightCorridorLow(context),
                ) else null
                val lastMeter = store.meterReadings(now - 30L * 60_000, now).lastOrNull()
                val state = com.diapilot.core.twin.HypoAlertState(
                    episodeStartMs = prefs.getLong(PREF_EPISODE_START, 0),
                    gentleAtMs = prefs.getLong(PREF_GENTLE_AT, 0),
                    escalated = prefs.getBoolean(PREF_ESCALATED, false),
                    lastFireMs = prefs.getLong(PREF_LAST, 0),
                    lastArtifactMs = prefs.getLong(PREF_ARTIFACT_LAST, 0),
                    lowSinceMs = prefs.getLong(PREF_LOW_SINCE, 0),
                    lastLowMs = prefs.getLong(PREF_LAST_LOW, 0),
                    lowObservedMs = prefs.getLong(PREF_LOW_OBSERVED, 0),
                    lastPassMs = prefs.getLong(PREF_LAST_PASS, 0),
                )
                val decision = com.diapilot.core.twin.decideHypoAlert(
                    state,
                    com.diapilot.core.twin.HypoAlertInputs(
                        nowMs = now,
                        anchorTsMs = anchorTs,
                        anchorMmol = anchorMmol,
                        thresholdMmol = threshold,
                        predictedHit = hit != null,
                        night = night,
                        nightGentle = com.example.diapilot.data.Settings.hypoNightGentle(context),
                        soundOn = sound,
                        lastMeterMmol = lastMeter?.mmol,
                        lastMeterTsMs = lastMeter?.tsMs ?: 0,
                        lastDextroseTsMs = recentDextroseMs(store, now, snoozeMs(context)),
                        dextroseSnoozeEnabled = com.example.diapilot.data.Settings.hypoDextroseSnooze(context),
                        snoozeMs = snoozeMs(context),
                        persistMs = persistMs(context),
                        lowRefireMs = com.example.diapilot.data.Settings.hypoLowRefireMin(context) * 60_000L,
                        cooldownMs = COOLDOWN_MS,
                        appOpenedAtMs = com.example.diapilot.data.Settings.appOpenedAtMs(context),
                        sensorSuspect = result?.sensorSuspect != null,
                    ),
                )
                // Persist the new state + the watch flag.
                alertActive = decision.alertActive
                prefs.edit()
                    .putLong(PREF_EPISODE_START, decision.state.episodeStartMs)
                    .putLong(PREF_GENTLE_AT, decision.state.gentleAtMs)
                    .putBoolean(PREF_ESCALATED, decision.state.escalated)
                    .putLong(PREF_LAST, decision.state.lastFireMs)
                    .putLong(PREF_ARTIFACT_LAST, decision.state.lastArtifactMs)
                    .putLong(PREF_LOW_SINCE, decision.state.lowSinceMs)
                    .putLong(PREF_LAST_LOW, decision.state.lastLowMs)
                    .putLong(PREF_LOW_OBSERVED, decision.state.lowObservedMs)
                    .putLong(PREF_LAST_PASS, decision.state.lastPassMs)
                    .apply()

                val protocol = com.example.diapilot.data.Settings.hypoProtocol(context)
                val protocolLine = protocol?.takeIf { it.isNotBlank() }
                    ?.let { text.getString(R.string.hypo_alert_notifier_protocol_line, it) }
                    ?: text.getString(R.string.hypo_alert_notifier_protocol_default)
                val predictText = hit?.let {
                    text.getString(
                        R.string.hypo_alert_notifier_predict_text,
                        com.diapilot.core.analysis.fmtBg(threshold, mgdl),
                        fmtT.format(java.util.Date(it.crossTsMs)),
                        com.diapilot.core.analysis.fmtBg(it.minMmol, mgdl),
                        protocolLine,
                    )
                } ?: protocolLine
                when (decision.action) {
                    com.diapilot.core.twin.HypoAction.NONE -> {}
                    com.diapilot.core.twin.HypoAction.ARTIFACT_ALARM -> {
                        postHypo(
                            context,
                            text.getString(R.string.hypo_alert_notifier_artifact_title),
                            text.getString(
                                R.string.hypo_alert_notifier_artifact_text,
                                com.diapilot.core.analysis.fmtBg(anchorMmol, mgdl),
                            ),
                        )
                        AlarmPlayer.alarm(context, withSound = sound)
                    }
                    com.diapilot.core.twin.HypoAction.PERSISTENT_ALARM -> {
                        // Duration of the ACTUAL low, not of the episode (which a
                        // night corridor prediction can open hours before any low)
                        // — and only the part we OBSERVED. The staleness `return`
                        // above leaves this state untouched, so the wall-clock
                        // difference silently billed sensor holes as hypo: on this
                        // device 21 of 52 of these alarms overstated the duration,
                        // the worst by 213 min. The ALARM still fires on wall time
                        // (assume the worst); only the sentence is held to what
                        // was seen, and it says so when the two disagree.
                        val mins = decision.state.lowObservedMs / 60_000
                        val wallMins = decision.state.lowSinceMs
                            .takeIf { it > 0 }?.let { (now - it) / 60_000 } ?: mins
                        val blindMins = wallMins - mins
                        // Say "no reliable data", never "sensor was silent": an
                        // artifact stretch goes uncredited too, and there the
                        // sensor was talking loudly — a live check found a single
                        // overnight stretch of dozens of artifact anchors, i.e.
                        // the app woke the user repeatedly while nominally
                        // "silent". The claim is about what could not be
                        // MEASURED. The title carries both numbers rather than
                        // the observed one alone, because the first low pass
                        // credits 0 and a "sustained low — 0 min" headline would
                        // contradict itself.
                        val blind = blindMins >= 5
                        postHypo(
                            context,
                            if (blind) {
                                text.getString(R.string.hypo_alert_notifier_persistent_title_blind, mins, wallMins)
                            } else {
                                text.getString(R.string.hypo_alert_notifier_persistent_title, mins)
                            },
                            if (blind) {
                                text.getString(
                                    R.string.hypo_alert_notifier_persistent_text_blind,
                                    com.diapilot.core.analysis.fmtBg(threshold, mgdl),
                                    com.diapilot.core.analysis.fmtBg(anchorMmol, mgdl),
                                    blindMins,
                                    protocolLine,
                                )
                            } else {
                                text.getString(
                                    R.string.hypo_alert_notifier_persistent_text,
                                    com.diapilot.core.analysis.fmtBg(threshold, mgdl),
                                    com.diapilot.core.analysis.fmtBg(anchorMmol, mgdl),
                                    protocolLine,
                                )
                            },
                        )
                        AlarmPlayer.alarm(context, withSound = sound)
                    }
                    com.diapilot.core.twin.HypoAction.LOW_ALARM -> {
                        postHypo(
                            context,
                            text.getString(
                                R.string.hypo_alert_notifier_low_title,
                                com.diapilot.core.analysis.fmtBg(anchorMmol, mgdl),
                            ),
                            text.getString(
                                R.string.hypo_alert_notifier_low_text,
                                com.diapilot.core.analysis.fmtBg(threshold, mgdl),
                                protocolLine,
                            ),
                        )
                        AlarmPlayer.alarm(context, withSound = sound)
                    }
                    com.diapilot.core.twin.HypoAction.PREDICT_GENTLE -> {
                        postHypo(
                            context,
                            text.getString(R.string.hypo_alert_notifier_predict_title, hit?.leadMin ?: 0.0),
                            predictText,
                        )
                        AlarmPlayer.gentle(context)
                    }
                    com.diapilot.core.twin.HypoAction.SENSOR_CHECK -> {
                        postHypo(
                            context,
                            text.getString(R.string.hypo_alert_notifier_sensor_check_title),
                            text.getString(
                                R.string.hypo_alert_notifier_sensor_check_text,
                                com.diapilot.core.analysis.fmtBg(anchorMmol, mgdl),
                            ),
                        )
                        AlarmPlayer.gentle(context)
                    }
                    com.diapilot.core.twin.HypoAction.PREDICT_ALARM,
                    com.diapilot.core.twin.HypoAction.ESCALATE_ALARM,
                    -> {
                        postHypo(
                            context,
                            text.getString(R.string.hypo_alert_notifier_predict_title, hit?.leadMin ?: 0.0),
                            predictText,
                        )
                        AlarmPlayer.alarm(context, withSound = sound)
                    }
                }
                if (decision.action != com.diapilot.core.twin.HypoAction.NONE) {
                    Log.i(TAG, "hypo ${decision.action} bg=%.1f".format(anchorMmol))
                    // An ACTIVE low-side alert owns priority — never warn about a
                    // high while a hypo is in play. But when the low side is quiet
                    // (action == NONE) we must fall through: an unconditional
                    // return here left the high-side alert dead whenever hypo
                    // alerts were on (the default), so it never fired at all.
                    return
                }
            }

            // ---- SUSTAINED HIGH: a state, not a crossing ------------
            //
            // A gap named by two external reviews: time-in-range data showed a
            // large share of time above 10 and a smaller but still significant
            // share above 13.9, and that was the main measured problem — served
            // by zero mechanisms. `findPredictedHigh` catches the ENTRY upward
            // and refuses on its first line to run when glucose is already
            // high. A crossing that already happened cannot be caught that
            // way; a second question is needed — "how long has this lasted".
            //
            // The forecast is deliberately NOT read here: this is an
            // observation over readings, so it works even when the model
            // can't be trusted — and health was LIMITED on 100% of runs for
            // eleven days straight in one stretch. The threshold and rate
            // are measured, see `SustainedHigh`: 13.9 and one hour give a low
            // daily rate of firing, while the range ceiling would give a much
            // higher one.
            if (hyperOn &&
                now - prefs.getLong(PREF_LAST_SUSTAINED, 0)
                >= com.diapilot.core.twin.SustainedHigh.COOLDOWN_MIN * 60_000
            ) {
                val tail = com.example.diapilot.data.trustedHistory(
                    store, context, now - 6L * 3_600_000, now,
                ).map { it.tsMs to it.mmol }
                com.diapilot.core.twin.SustainedHigh.evaluate(tail, now)?.let { v ->
                    prefs.edit().putLong(PREF_LAST_SUSTAINED, now).apply()
                    notify(
                        context,
                        title = text.getString(R.string.hypo_alert_notifier_sustained_high_title, v.minutesAbove),
                        text = text.getString(
                            R.string.hypo_alert_notifier_sustained_high_text,
                            com.diapilot.core.analysis.fmtBg(
                                com.diapilot.core.twin.SustainedHigh.THRESHOLD_MMOL, mgdl,
                            ),
                            com.diapilot.core.analysis.fmtBg(v.peakMmol, mgdl),
                        ),
                    )
                    Log.i(TAG, "sustained high: %d min, peak %.1f".format(v.minutesAbove, v.peakMmol))
                }
            }

            // ---- high side (fewer 300s start 40 minutes earlier) ---------
            if (hyperOn && forecastOk &&
                now - prefs.getLong(PREF_LAST_HIGH, 0) >= (pp.hyperCooldownMin * 60_000).toLong()
            ) {
                val ceiling = com.example.diapilot.data.Settings.rangeHiMmol(context)
                val hit = com.diapilot.core.twin.findPredictedHigh(
                    prediction, anchorTs, anchorMmol, ceiling,
                )
                if (hit != null) {
                    prefs.edit().putLong(PREF_LAST_HIGH, now).apply()
                    notify(
                        context,
                        title = text.getString(R.string.hypo_alert_notifier_hyper_predict_title, hit.leadMin),
                        text = text.getString(
                            R.string.hypo_alert_notifier_hyper_predict_text,
                            com.diapilot.core.analysis.fmtBg(ceiling, mgdl),
                            fmtT.format(java.util.Date(hit.crossTsMs)),
                            com.diapilot.core.analysis.fmtBg(hit.maxMmol, mgdl),
                        ),
                    )
                    Log.i(TAG, "hyper fired: lead=%.0f min, crest=%.1f".format(hit.leadMin, hit.maxMmol))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
        }
    }

    /** Hypo notification — VISUAL only. Sound/vibration are driven by
     *  [AlarmPlayer] on the alarm stream (heard on silent); the channel itself
     *  is silent so we don't double-buzz or get swallowed by the ringer. */
    private fun postHypo(context: Context, title: String, text: String) {
        val localizedText = context.localized()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL, localizedText.getString(R.string.hypo_alert_notifier_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = localizedText.getString(R.string.hypo_alert_notifier_channel_desc)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        nm.notify(NOTIF_ID, notification)
    }

    /** Hyper notification — a high is not a wake-you-now emergency, so it uses
     *  a plain high-importance channel (its own sound, respects the ringer). */
    private fun notify(context: Context, title: String, text: String) {
        val localizedText = context.localized()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HYPER, localizedText.getString(R.string.hypo_alert_notifier_hyper_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = localizedText.getString(R.string.hypo_alert_notifier_hyper_channel_desc) },
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_HYPER)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        nm.notify(NOTIF_ID_HYPER, notification)
    }
}
