package io.github.obdosok.diapilot.data

import com.diapilot.core.analysis.InsulinProductDefault
import android.content.Context
import com.diapilot.core.analysis.CARB_SENS_OVERRIDE_DEFAULT
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker

/**
 * User-tunable numbers, stored in mmol/L regardless of the display units.
 *
 * - target: the IDEAL glucose the hints aim at (default 5.55 ≈ 100 mg/dl).
 *   The wrist hint's "High +X" is the excess over THIS, not over the range
 *   top — a correction lands at the ideal, not at the ceiling.
 * - range lo/hi: the green band — TIR, chart wash, hint trigger threshold.
 * - insulin profile: action curve for the local IOB (peak/duration differ
 *   between rapid analogs — NovoRapid vs Fiasp).
 */
object Settings {
    private const val KEY_TARGET = "target_mmol"
    private const val KEY_RANGE_LO = "range_lo_mmol"
    private const val KEY_RANGE_HI = "range_hi_mmol"
    private const val KEY_WATCH_SERVER = "watch_server_enabled"
    private const val KEY_INSULIN = "insulin_profile"
    private const val KEY_DIA = "insulin_dia_min"
    private const val KEY_ISF = "isf_override_mmol"
    private const val KEY_CARB_SENS = "carb_sens_mmol_per_10g"
    private const val KEY_CARB_SENS_OVERRIDE = "carb_sens_override_mmol_per_g"

    /**
     * Body weight in kilograms, or absent.
     *
     * IT IS A MODEL INPUT, not a profile field. Carbohydrate sensitivity is a
     * rise per gram distributed through a volume that scales with body mass, so
     * a single population number was implicitly assuming one body. Absent, the
     * prior falls back to its 70 kg reference — exactly the previous behaviour —
     * so nothing changes for an install that never fills it in.
     */
    private const val KEY_WEIGHT_KG = "body_weight_kg"
    private const val KEY_BOLUS_PRODUCT = "insulin_product_bolus"
    private const val KEY_BASAL_PRODUCT = "insulin_product_basal"

    /**
     * Action curves: label + (peak min, DIA min) for the exponential model.
     * Fiasp's DIA is deliberately shorter than the book value: a measured
     * curve showed no visible action past ~2.5-3 h.
     * DIA is user-overridable below; the long game is deriving it from the
     * personal insulin kernel once enough clean episodes accumulate.
     */
    enum class InsulinProfile(val label: String, val peakMin: Double, val diaMin: Double) {
        NOVORAPID("NovoRapid", 75.0, 300.0),
        FIASP("Fiasp", 55.0, 180.0),
        LYUMJEV("Lyumjev", 45.0, 180.0),
    }

    const val DEFAULT_TARGET = 5.55   // ~100 mg/dl
    const val DEFAULT_RANGE_LO = 3.9  // ~70
    const val DEFAULT_RANGE_HI = 10.0 // ~180

    private fun prefs(context: Context) =
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)

    fun targetMmol(context: Context): Double =
        prefs(context).getFloat(KEY_TARGET, DEFAULT_TARGET.toFloat()).toDouble()

    fun rangeLoMmol(context: Context): Double =
        prefs(context).getFloat(KEY_RANGE_LO, DEFAULT_RANGE_LO.toFloat()).toDouble()

    fun rangeHiMmol(context: Context): Double =
        prefs(context).getFloat(KEY_RANGE_HI, DEFAULT_RANGE_HI.toFloat()).toDouble()

    // OPT-INS FOR EVERYTHING SHARED WITH THE REST OF THE PHONE.
    //
    // Each switch below guards a resource that exists once per phone rather
    // than once per app: a loopback port, the sensor's single BLE connection,
    // the OOP2 decoder's broadcast replies, the public Downloads folder. A
    // second installed copy of DiaPilot (a different applicationId, e.g. a
    // private build next to the public one) must not touch any of them until
    // the user turns the switch on in THAT copy — so every default is off.
    // Reading xDrip's broadcast and web service, and Health Connect, stay
    // outside this list: they are read-only and cannot disturb another app.

    /** Watch face server / local API on 127.0.0.1:29863. */
    const val DEFAULT_WATCH_SERVER = false

    /** Daily automatic database copy into the public Downloads folder. */
    const val DEFAULT_AUTO_BACKUP = false

    /** Libre 2 NFC scan (the FRAM goes to OOP2 for decryption). */
    const val DEFAULT_LIBRE_NFC = false

    /** DiaPilot's own BLE link to the sensor. */
    const val DEFAULT_OWN_BLE = false

    fun watchServerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WATCH_SERVER, DEFAULT_WATCH_SERVER)

    fun autoBackupEnabled(context: Context): Boolean =
        prefs(context).getBoolean("auto_backup_downloads_enabled", DEFAULT_AUTO_BACKUP)

    fun setAutoBackupEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("auto_backup_downloads_enabled", v).apply()

    /**
     * Off, a Libre tag held to the phone is ignored with a hint. On, the scan
     * reads the sensor over NFC and asks OOP2 to decrypt it — and OOP2 answers
     * with a broadcast that every app listening for it receives, including
     * another installed copy of DiaPilot. Pen scans are not gated: they only
     * read the pen and write to this app's own database.
     */
    fun libreNfcEnabled(context: Context): Boolean =
        prefs(context).getBoolean("libre_nfc_enabled", DEFAULT_LIBRE_NFC)

    fun setLibreNfcEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("libre_nfc_enabled", v).apply()

    /**
     * Which insulin is in the pen — an INSTALLATION attribute, not something
     * read off a dose.
     *
     * None of the three dose sources carries a product: NovoPen NFC reports
     * units and a pen clock, xDrip treatments arrive with `insulinType` either
     * NULL (3533 of 3616 rows) or holding an entered-by string like
     * "xDrip NFC scan @ …", and manual entry asks only for units. So the API
     * states what this pen holds rather than inventing per-dose provenance.
     */
    fun bolusProduct(context: Context): String =
        product(context, KEY_BOLUS_PRODUCT, InsulinProductDefault.RAPID)

    fun basalProduct(context: Context): String =
        product(context, KEY_BASAL_PRODUCT, InsulinProductDefault.BASAL)

    /**
     * The stored product, or the placeholder KEY ("rapid"/"basal") when none was
     * set. A placeholder saved by an older build in Russian is rewritten to its
     * key once, here; a real product name the user typed is never touched.
     */
    private fun product(context: Context, key: String, placeholder: InsulinProductDefault): String {
        val prefs = prefs(context)
        val stored = prefs.getString(key, null) ?: return placeholder.key
        val canonical = InsulinProductDefault.canonical(stored) ?: stored
        if (canonical != stored) prefs.edit().putString(key, canonical).apply()
        return canonical
    }

    fun setBolusProduct(context:Context,value:String,knownAtMs:Long=System.currentTimeMillis(),storeOverride:SqliteCollectorStore?=null) {
        prefs(context).edit().putString(KEY_BOLUS_PRODUCT,InsulinProductDefault.canonical(value.trim())).commit()
        (storeOverride?:Stores.get(context) as? SqliteCollectorStore)?.let{store->
            PhysioContextIngestionRuntime.syncSettings(context,store,knownAtMs)
        }
    }

    fun setTargetMmol(context: Context, v: Double) =
        prefs(context).edit().putFloat(KEY_TARGET, v.toFloat()).apply()

    fun setRangeLoMmol(context: Context, v: Double) =
        prefs(context).edit().putFloat(KEY_RANGE_LO, v.toFloat()).apply()

    fun setRangeHiMmol(context: Context, v: Double) =
        prefs(context).edit().putFloat(KEY_RANGE_HI, v.toFloat()).apply()

    fun setWatchServerEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_WATCH_SERVER, v).apply()

    // `hybridV11Main` REMOVED. A rollout flag defaulting to true,
    // with no UI to change it: `setHybridV11Main` had no caller anywhere. It
    // still gated six live places — the effect surface, the COB series, the
    // watch's carb sensitivity — so a switch nobody could reach was deciding
    // whether half the physio surfaces worked. Replaced by its only possible
    // value.


    /** Null when the user has not said. */
    fun weightKg(context: Context): Double? =
        prefs(context).getFloat(KEY_WEIGHT_KG, 0f).toDouble().takeIf { it > 0.0 }

    fun setWeightKg(context: Context, kg: Double?) =
        prefs(context).edit().putFloat(KEY_WEIGHT_KG, (kg ?: 0.0).toFloat()).apply()

    fun insulinProfile(context: Context): InsulinProfile =
        prefs(context).getString(KEY_INSULIN, null)
            ?.let { name -> InsulinProfile.entries.firstOrNull { it.name == name } }
            ?: InsulinProfile.NOVORAPID


    /** Effective DIA: the user's override when set, else the profile default. */
    fun insulinDiaMin(context: Context): Double =
        prefs(context).getFloat(KEY_DIA, 0f).toDouble().takeIf { it in 60.0..600.0 }
            ?: insulinProfile(context).diaMin

    /**
     * Effective time-to-peak, kept valid for the exponential model (it
     * degenerates at peak >= DIA/2) when a short DIA override squeezes it.
     */
    fun insulinPeakMin(context: Context): Double =
        minOf(insulinProfile(context).peakMin, insulinDiaMin(context) * 0.45)


    /**
     * User-known ISF (mmol/L per unit): a calibration PRIOR for the kernel
     * amplitude. The historical episodes were recorded before food logging
     * existed, so the learned per-unit drop is diluted by invisible meals;
     * the user's lived number wins until clean episodes accumulate.
     * Null = pure data-driven kernel.
     */
    // `isfOverrideMmol` / `setIsfOverrideMmol` REMOVED. Nothing in
    // the app could set it, and it was a third place a hand ISF could live.
    // Confirmed null on the device before deleting.

    // User-known carb rise per 10 g (mmol/L): the food-side twin of the ISF
    // prior. Used while the data-driven calibration (grams-priced meals) is
    // thin; null = pure data-driven.
    /**
     * Carb sensitivity the forecast USES, in mmol per RECORDED gram — an override,
     * because the learned estimator is biased LOW independently of the grams: on
     * the same recorded grams it answers noticeably lower than a small set of
     * insulin-free repeats measure directly (`carbclean`).
     *
     * The value and the whole argument for it live in ONE place —
     * [com.diapilot.core.analysis.CARB_SENS_OVERRIDE_DEFAULT]. Do not restate it
     * here: an earlier draft of this comment argued for a different number than
     * the constant shipped, which is how the next reader "fixes" the wrong end.
     *
     * A BRIDGE, NOT A FIX, shaped so it cannot become the next hardcoded constant:
     * a setting, with the learned value still computed and stored beside it
     * (`Model.carbSensLearned`, both in the snapshot). Removed when they converge.
     * Null/0 = off, use whatever was learned.
     */
    fun carbSensOverrideMmolPerG(context: Context): Double? =
        prefs(context).getFloat(KEY_CARB_SENS_OVERRIDE, CARB_SENS_OVERRIDE_DEFAULT.toFloat())
            .toDouble().takeIf { it in 0.02..1.0 }


    fun carbSensPer10gMmol(context: Context): Double? =
        prefs(context).getFloat(KEY_CARB_SENS, 0f).toDouble().takeIf { it in 0.1..15.0 }


    // --- Companion server: personal dashboard + cloud backup target ---
    fun companionUrl(context: Context): String? =
        prefs(context).getString("companion_url", null)

    fun setCompanionUrl(context: Context, v: String?) =
        prefs(context).edit()
            .putString("companion_url", v?.trim()?.trimEnd('/')?.ifEmpty { null }).apply()

    /** Encrypted at rest; see [SecretStore]. Sent only as the Authorization header. */
    fun companionToken(context: Context): String? =
        Secrets.store(context).get(SecretStore.Secret.COMPANION_TOKEN)

    /** False when the token could not be stored securely; nothing is saved then. */
    fun setCompanionToken(context: Context, v: String?): Boolean =
        Secrets.store(context).set(SecretStore.Secret.COMPANION_TOKEN, v)

    /** The user's own pre-agreed hypo first step ("10 g soka") - reminded
     *  verbatim on predicted lows; the app never computes rescue carbs. */
    fun hypoProtocol(context: Context): String? =
        prefs(context).getString("hypo_protocol", null)

    fun setHypoProtocol(context: Context, v: String?) =
        prefs(context).edit().putString("hypo_protocol", v?.trim()?.ifEmpty { null }).apply()

    /** Declined food suggestions, keyed "fs<triggerTsMs>". Pruned to 24h on
     *  write — the suggestion window is 3h, so old keys are dead weight. */
    fun foodSuggestDismissed(context: Context): Set<String> =
        prefs(context).getStringSet("food_suggest_dismissed", emptySet()) ?: emptySet()

    fun dismissFoodSuggestion(context: Context, triggerTsMs: Long) {
        val cutoff = System.currentTimeMillis() - 24L * 3_600_000
        val kept = foodSuggestDismissed(context).filter {
            (it.removePrefix("fs").toLongOrNull() ?: 0L) > cutoff
        }
        prefs(context).edit()
            .putStringSet("food_suggest_dismissed", (kept + "fs$triggerTsMs").toSet())
            .apply()
    }

    fun hyperAlertEnabled(context: Context): Boolean =
        prefs(context).getBoolean("hyper_alert_enabled", true)

    fun setHyperAlertEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("hyper_alert_enabled", v).apply()

    /**
     * Predictive hypo alert.
     *
     * The phrase «validated by the Analysis backtest card» stood here until
     * that card and its backtest were deleted with the legacy
     * engine, so the alert rule is currently validated by nothing on the device.
     * Off-device it is scored by the `walkfwd` stand's false-alarm column.
     */
    fun hypoAlertEnabled(context: Context): Boolean =
        prefs(context).getBoolean("hypo_alert_enabled", true)

    fun setHypoAlertEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("hypo_alert_enabled", v).apply()

    /** Play the alarm TONE (on the alarm stream, heard on silent) for a hypo
     *  alert. Off = vibration only. Default on. */
    fun hypoAlertSound(context: Context): Boolean =
        prefs(context).getBoolean("hypo_alert_sound", true)

    fun setHypoAlertSound(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("hypo_alert_sound", v).apply()

    /** Night (0-7h): a gentle buzz first, escalating to the full alarm only if
     *  the hypo persists unacknowledged. Off = full alarm immediately, night
     *  and day alike. Default on. */
    fun hypoNightGentle(context: Context): Boolean =
        prefs(context).getBoolean("hypo_night_gentle", true)

    fun setHypoNightGentle(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("hypo_night_gentle", v).apply()

    /**
     * AT NIGHT, JUDGE BY THE CORRIDOR'S LOWER BOUND, NOT BY THE MEDIAN.
     *
     * The `useCorridorLow` branch existed for a while and was EFFECTIVELY DEAD: it
     * requires `health == TRUSTED`, and health was `LIMITED` on 100% of runs
     * for a stretch, for a reason belonging to another, already-removed
     * arm. A fix to `ForecastHealthV1` repaired that and thereby silently
     * ARMED a branch that nobody had turned on and nobody had measured.
     *
     * Measured on the user's history — several thousand overnight alert runs, anchor >=4.3:
     *
     *   median crosses 3.9 within 45 min ......  0
     *   LOWER BOUND crosses (this branch) ..... several
     *   reality actually dropped below 3.9 ....  0
     *
     * Several triggers and not one real low. These are runs, not
     * alarms — a cooldown would fold them into fewer episodes — and the zero
     * right-hand column means completeness CANNOT be assessed from this, not that
     * it is zero. But accuracy on the available evidence is zero, and
     * "shadow-safe first" on the hypo-alert path still binds.
     *
     * So the default is OFF — what the branch effectively was for the whole
     * stretch — but now it is a DECISION backed by numbers, not a side effect of
     * someone else's fix. It is worth turning on once a real overnight low shows
     * up that lets completeness actually be measured.
     */
    fun nightCorridorLow(context: Context): Boolean =
        prefs(context).getBoolean("night_corridor_low", false)

    fun setNightCorridorLow(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("night_corridor_low", v).apply()

    /** Gate the forecast anchor for sensor plausibility (compression lows,
     *  EOL noise) before trusting it. Default off — shadow-safe until
     *  measured. Screen: Settings -> "Experimental". */
    fun plausibilityGate(context: Context): Boolean =
        prefs(context).getBoolean("plausibility_gate", false)

    fun setPlausibilityGate(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("plausibility_gate", v).apply()

    // `notePreferredFood` REMOVED (audit). Neither getter nor setter was
    // ever called: the only reader — `Forecaster` — disappeared along with the
    // legacy food layer.
    //
    // `roughNeighbours` REMOVED. It selected the MECHANISM for neighbour
    // subtraction — rough by learned profile vs. pooled — and both were removed
    // along with the concepts layer. After that it chose between two trust-
    // penalty formulas for the SAME mechanism (engine subtraction by macros),
    // meaning the toggle remained while the meaning of its name disappeared.
    // The formula was folded into one.

    /**
     * May the user's anomaly marks reach WHAT IS LEARNED, or only what is displayed?
     *
     * Default ON: the screen exists so the user's answer returns to the model, and an off-by-
     * default gate would ship the whole thing as a viewer. The switch is here because
     * «inert until the user presses a chip» is NOT shadow-safe — the first mark changes
     * `dishCurves`, the donor corpus and (once the carb-sens override is lifted) the
     * learned coefficient, all under an unchanged shadow tag. Off, marks are still stored,
     * still shown and still revocable; only the learning gates stop, so an arm that
     * measures badly can be retired without touching the data the user produced.
     *
     * **The shadow tag must be bumped in the same commit as the first stored mark, and
     * again if this flag is flipped once marks exist** — from that point the corpus is a
     * different generation and a blended ledger A/B is unreadable. The condition is also
     * written at the gate itself in `TwinCache.get`, which is where a reader will be.
     */
    fun marksTeachModel(context: Context): Boolean =
        prefs(context).getBoolean("marks_teach_model", true)

    fun setMarksTeachModel(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("marks_teach_model", v).apply()

    // TWO HANDLES REMOVED HERE (audit) — and three stray KDoc blocks along with them.
    //
    // `noteAnchoredFood` — and this is NOT a formality, there was a quiet
    // behavior change here. The setting's default was ON, and it was hard-won: the
    // effect on alert safety was MEASURED (one real hypo missed), the gate
    // was WAIVED by the user rather than passed (JOURNAL). When the legacy food
    // layer left `Forecaster`, the only reader of this getter disappeared — and the
    // two remaining calls to `FoodSources.activeFoods` fell back to the PARAMETER
    // default, which is FALSE. The shipped behavior flipped itself, because
    // the setting and the parameter had DIFFERENT defaults. Now
    // `noteAnchoredFood = true` is stated explicitly at both call sites (`MainState`) — visible
    // to the compiler and the reader, not hidden in prefs with no screen.
    //
    // `activityModelV2` — the default was OFF and remains OFF, now as the parameter
    // default. Worth remembering why: the learned curve still WINS on a minority
    // of episodes — the mechanism carries something, but as currently built it
    // hurts more often than it helps. The way back is measurement, not a revert.
    //
    // ⚠ THREE KDoc BLOCKS IN A ROW HUNG OFF `activityModelV2`, and only the first
    // actually described it. The second talked about "a dish's own curve outranks
    // the structural mixture", the third about learned dish curves and a
    // threshold table. Both of those settings had been removed earlier, and their
    // documentation stayed glued to the next function. Lesson for later: when
    // deleting a `fun`, delete its KDoc too — otherwise it silently becomes a
    // description of its NEIGHBOR. The numbers from the third block live on in
    // JOURNAL.

    /** After a logged/detected dextrose rescue, hold alerts this long while it
     *  absorbs; if still low after, the alert re-fires (treatment fell short).
     *  Default 20 min. */
    fun hypoSnoozeMin(context: Context): Int =
        prefs(context).getInt("hypo_snooze_min", 20).coerceIn(5, 45)

    fun setHypoSnoozeMin(context: Context, v: Int) =
        prefs(context).edit().putInt("hypo_snooze_min", v.coerceIn(5, 45)).apply()

    /** A hypo that persists this long becomes "prolonged" — escalate to the
     *  full alarm and re-fire on a short cadence regardless of night-gentle.
     *  Default 25 min. */
    fun hypoPersistMin(context: Context): Int =
        prefs(context).getInt("hypo_persist_min", 25).coerceIn(10, 60)

    fun setHypoPersistMin(context: Context, v: Int) =
        prefs(context).edit().putInt("hypo_persist_min", v.coerceIn(10, 60)).apply()

    /** React to a logged dextrose rescue by pausing alerts while it absorbs.
     *  Default on. */
    fun hypoDextroseSnooze(context: Context): Boolean =
        prefs(context).getBoolean("hypo_dextrose_snooze", true)

    fun setHypoDextroseSnooze(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("hypo_dextrose_snooze", v).apply()

    /** How often a CONFIRMED low re-alerts until recovery. Default 5 min. */
    fun hypoLowRefireMin(context: Context): Int =
        prefs(context).getInt("hypo_low_refire_min", 5).coerceIn(3, 30)

    fun setHypoLowRefireMin(context: Context, v: Int) =
        prefs(context).edit().putInt("hypo_low_refire_min", v.coerceIn(3, 30)).apply()

    /** Set when the app is brought to the foreground — HypoAlertNotifier reads
     *  it as "acknowledged" so a pending gentle alert doesn't escalate. */
    fun markAppOpened(context: Context) =
        prefs(context).edit().putLong("app_opened_at_ms", System.currentTimeMillis()).apply()

    fun appOpenedAtMs(context: Context): Long =
        prefs(context).getLong("app_opened_at_ms", 0)

    /** Draw a floating BG chip over the lock screen (xDrip-style). Off by
     *  default — needs the "draw over other apps" grant. */
    fun overlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean("lockscreen_overlay", false)

    fun setOverlayEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("lockscreen_overlay", v).apply()

    /** Watch night-dim: 0=off, 1=on schedule, 2=on now (manual). The WatchServer
     *  sends the resulting flag to the face. Default 1 (schedule). */
    fun watchNightDimMode(context: Context): Int =
        prefs(context).getInt("watch_night_dim_mode", 1).coerceIn(0, 2)

    fun setWatchNightDimMode(context: Context, v: Int) =
        prefs(context).edit().putInt("watch_night_dim_mode", v.coerceIn(0, 2)).apply()

    fun watchNightStartH(context: Context): Int =
        prefs(context).getInt("watch_night_start_h", 23).coerceIn(0, 23)

    fun setWatchNightStartH(context: Context, v: Int) =
        prefs(context).edit().putInt("watch_night_start_h", v.coerceIn(0, 23)).apply()

    fun watchNightEndH(context: Context): Int =
        prefs(context).getInt("watch_night_end_h", 7).coerceIn(0, 23)

    fun setWatchNightEndH(context: Context, v: Int) =
        prefs(context).edit().putInt("watch_night_end_h", v.coerceIn(0, 23)).apply()

    /** The night-dim flag the watch face should honour right now. */
    fun watchNightDimActive(context: Context): Boolean = when (watchNightDimMode(context)) {
        2 -> true
        1 -> {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val s = watchNightStartH(context)
            val e = watchNightEndH(context)
            if (s <= e) h in s until e else (h >= s || h < e)  // wrap past midnight
        }
        else -> false
    }

    /** Experimental: DiaPilot holds the sensor's BLE link itself. */
    fun ownBleEnabled(context: Context): Boolean =
        prefs(context).getBoolean("libre_own_ble", DEFAULT_OWN_BLE)

    fun setOwnBleEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("libre_own_ble", v).apply()

    // --- Libre sensor registry (filled by the NFC scan) --------------------

    fun libreSensorSerial(context: Context): String? =
        prefs(context).getString("libre_sensor_serial", null)

    /** Sensor start (ms); 0 = unknown. Bounds the meter-calibration window. */
    fun libreSensorStartMs(context: Context): Long =
        prefs(context).getLong("libre_sensor_start_ms", 0L)

    fun setLibreSensor(context: Context, serial: String, startMs: Long) =
        prefs(context).edit()
            .putString("libre_sensor_serial", serial)
            .putLong("libre_sensor_start_ms", startMs)
            .apply()
}
