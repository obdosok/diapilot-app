package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.analysis.MGDL_PER_MMOL_F
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.physio.ManualInsulinParamsV1
import com.diapilot.core.physio.PhysioBoundsV1
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker

/**
 * FIRST RUN — what the app knows about the person before it draws a line.
 *
 * Until this existed a fresh install ran the bundled synthetic example person
 * (ISF 1.8, insulin 20/75/300, CS 0.165) through the forecast, the predictive
 * low alert and the watch hint from the first minute, and nothing on any
 * screen said so (audit M2, P2). Nobody had read a disclaimer and nobody had
 * entered their own numbers. This object is the record of both.
 *
 * TWO FACTS, STORED SEPARATELY. [completed] says the person has been through
 * the flow (or that an install that pre-dates it was recognised, see
 * [adoptExistingInstall]); [disclaimerAcceptedAtMs] says the disclaimer was
 * accepted, and when. They are separate because the adoption path sets the
 * first without the second: the author's own phone already carries a year of
 * readings and a hand ISF, so its forecast must not stop — but its owner has
 * still never pressed "I understand", and is asked to on the next launch.
 *
 * THE GATE IS EDITION-NEUTRAL. `Forecaster` asks [forecastPermitted], not
 * [io.github.obdosok.diapilot.Edition]: the store edition also completes this
 * flow (three pages instead of seven), and the retrospective replay that
 * edition keeps — "what the model would have said at that minute" — is a
 * forecast pass too. What the edition decides is which PAGES exist, see
 * [pages]; what this decides is whether ANY pass may run on this install.
 */
object Onboarding {
    /** Set once the person has been through every page (or was adopted). */
    const val KEY_COMPLETED = "onboarding_completed_v1"
    const val KEY_COMPLETED_AT = "onboarding_completed_at_ms"

    /** When the disclaimer was accepted; absent until it is. */
    const val KEY_DISCLAIMER_AT = "disclaimer_accepted_at_ms"

    /** Recorded when [adoptExistingInstall] completed the flow on the user's
     *  behalf, so a bug report can tell the two kinds of "completed" apart. */
    const val KEY_ADOPTED = "onboarding_adopted_existing_install_v1"

    /**
     * How much glucose history makes an install "existing" rather than fresh.
     * One day: a phone that has collected around the clock once is a phone
     * somebody has been using, not one that was installed to look at.
     */
    const val ADOPT_MIN_READING_SPAN_MS = 24L * 3_600_000

    private fun prefs(context: Context) =
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)

    /** The pages, in order. Which of them a build shows is [pages]. */
    enum class Page {
        DISCLAIMER,
        LANGUAGE,
        WEIGHT,
        ISF,
        INSULIN_PRESET,
        CARB_SENS,
        SUMMARY,
    }

    /**
     * The model pages exist only where a model runs forward. The store edition
     * draws no line, so asking for an ISF there would collect a number nothing
     * reads — a screen that opens onto nothing, which `docs/editions.md`
     * forbids. Disclaimer, language and body weight stay: weight feeds the
     * retrospective carb estimate in both editions.
     */
    fun pages(prospective: Boolean): List<Page> =
        if (prospective) Page.entries.toList()
        else listOf(Page.DISCLAIMER, Page.LANGUAGE, Page.WEIGHT, Page.SUMMARY)

    /** What the activity shows on launch. */
    enum class Gate {
        /** Nothing owed; the app opens. */
        NONE,

        /** The whole flow. */
        FULL,

        /** An adopted install: completed, but the disclaimer was never read. */
        DISCLAIMER_ONLY,
    }

    fun completed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPLETED, false)

    fun completedAtMs(context: Context): Long? =
        prefs(context).getLong(KEY_COMPLETED_AT, 0L).takeIf { it > 0L }

    fun disclaimerAcceptedAtMs(context: Context): Long? =
        prefs(context).getLong(KEY_DISCLAIMER_AT, 0L).takeIf { it > 0L }

    fun wasAdopted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADOPTED, false)

    fun acceptDisclaimer(context: Context, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_DISCLAIMER_AT, nowMs).commit()
    }

    /**
     * `commit`, not `apply`: the very next forecast pass — possibly on the
     * collector's thread within the same second — reads this key back through
     * [forecastPermitted], and the person who just pressed Finish expects the
     * line to appear on the redraw that follows.
     */
    fun markCompleted(context: Context, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putBoolean(KEY_COMPLETED, true)
            .putLong(KEY_COMPLETED_AT, nowMs)
            .commit()
    }

    /** Tests and the "Set up model again" path never need this; it exists so
     *  a test can put an install back to its first minute. */
    fun reset(context: Context) {
        prefs(context).edit()
            .remove(KEY_COMPLETED).remove(KEY_COMPLETED_AT)
            .remove(KEY_DISCLAIMER_AT).remove(KEY_ADOPTED)
            .commit()
    }

    /**
     * What the launch owes, after giving an existing install its due.
     * Runs a query, so call it off the main thread.
     */
    fun gate(context: Context, store: CollectorStore?): Gate {
        if (!completed(context)) adoptExistingInstall(context, store)
        return when {
            !completed(context) -> Gate.FULL
            disclaimerAcceptedAtMs(context) == null -> Gate.DISCLAIMER_ONLY
            else -> Gate.NONE
        }
    }

    /**
     * May a forecast pass run on this install? [completed], with the adoption
     * check folded in so a background consumer on a phone that pre-dates the
     * flow — the alert tick, the watch server — does not lose its line until
     * the owner happens to open the app after the update.
     */
    fun forecastPermitted(context: Context, store: CollectorStore?): Boolean =
        completed(context) || adoptExistingInstall(context, store)

    /**
     * INSTALLS THAT PRE-DATE THE FLOW are completed on their owner's behalf.
     *
     * Two things together say "this phone was already in use and its owner
     * already entered a model": at least a day of glucose readings, AND a hand
     * ISF in the manual tier. Either alone is not enough — a phone that
     * collected for a day on the example person is exactly the case the flow
     * exists for, and a hand ISF on an empty database is a test fixture.
     *
     * The disclaimer is deliberately NOT accepted here; [gate] asks for it on
     * the next launch as a single page.
     *
     * @return true when this call completed the install.
     */
    fun adoptExistingInstall(
        context: Context,
        store: CollectorStore?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (completed(context)) return false
        val span = (store as? SqliteCollectorStore)
            ?.let { runCatching { it.sensorReadingSpanMs() }.getOrNull() } ?: return false
        if (span < ADOPT_MIN_READING_SPAN_MS) return false
        if (ManualInsulinRuntime.params(context).isfMmolPerU == null) return false
        prefs(context).edit()
            .putBoolean(KEY_COMPLETED, true)
            .putLong(KEY_COMPLETED_AT, nowMs)
            .putBoolean(KEY_ADOPTED, true)
            .commit()
        return true
    }

    /**
     * Three insulin-action shapes a person can pick without owning a sensor
     * trace to read them off. Landmarks in minutes after the injection; each
     * triple lies inside the hand tier's own domain
     * (`PhysioBoundsV1.insulinTailMinManualRange`, 120..600), which is what
     * lets the ultra-rapid preset state an end of action under 300.
     *
     * [TYPICAL] is also the answer to "I don't know": it is the shipped
     * example person's shape with a slightly earlier onset, which is what a
     * rapid analog does in most adults.
     */
    enum class InsulinPreset(val onsetMin: Double, val peakMin: Double, val tailMin: Double) {
        TYPICAL(15.0, 75.0, 300.0),
        SLOWER(20.0, 90.0, 360.0),
        ULTRA_RAPID(10.0, 55.0, 240.0),
    }

    /**
     * What the pages accept, as pure functions — so the domain of each field
     * is stated once, tested without Compose, and identical to what the model
     * reads back. Entry is in the DISPLAY units the person chose; storage is
     * mmol/L, per gram, like everything else in this tree.
     */
    object Input {
        sealed interface Parsed {
            /** Nothing typed: an optional field left at its default. */
            data object Empty : Parsed

            /** Typed, but not a number in the field's domain. */
            data object Invalid : Parsed

            data class Valid(val value: Double) : Parsed
        }

        val WEIGHT_KG: ClosedFloatingPointRange<Double> = 20.0..300.0

        /** The resolver's own ISF domain (`PhysioBoundsV1`): anything outside
         *  it would be refused as ISF_OUT_OF_DOMAIN on the way into the model. */
        val ISF_MMOL_PER_U: ClosedFloatingPointRange<Double> =
            PhysioBoundsV1().let { it.isfMmolPerLUmin..it.isfMmolPerLUmax }

        /** What most adults land in — printed as orientation, never enforced. */
        val TYPICAL_ADULT_ISF_MMOL_PER_U: ClosedFloatingPointRange<Double> = 1.5..4.0

        /** `Settings.carbSensOverrideMmolPerG` accepts 0.02..1.0 per gram. */
        val CARB_SENS_MMOL_PER_10G: ClosedFloatingPointRange<Double> = 0.2..10.0

        private fun number(text: String): Double? =
            text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

        private fun parse(text: String, range: ClosedFloatingPointRange<Double>, toStored: (Double) -> Double): Parsed {
            if (text.isBlank()) return Parsed.Empty
            val typed = number(text) ?: return Parsed.Invalid
            val stored = toStored(typed)
            return if (stored in range) Parsed.Valid(stored) else Parsed.Invalid
        }

        fun weightKg(text: String): Parsed = parse(text, WEIGHT_KG) { it }

        /** mmol/L per unit; a mg/dL entry is divided by [MGDL_PER_MMOL_F]. */
        fun isfMmolPerU(text: String, mgdl: Boolean): Parsed =
            parse(text, ISF_MMOL_PER_U) { if (mgdl) it / MGDL_PER_MMOL_F else it }

        /** Per GRAM, from a per-10 g entry in the display units. */
        fun carbSensMmolPerG(text: String, mgdl: Boolean): Parsed =
            parse(text, CARB_SENS_MMOL_PER_10G) { if (mgdl) it / MGDL_PER_MMOL_F else it }
                .let { if (it is Parsed.Valid) Parsed.Valid(it.value / 10.0) else it }
    }

    /**
     * Write what the pages collected through the SAME doors the Settings
     * screen uses, so there is exactly one way each number reaches the model:
     * the manual tier for ISF and the insulin shape
     * ([ManualInsulinRuntime.setParams]), [Settings.setWeightKg] for the
     * weight, and the carb-sensitivity override that audit M4 found had no
     * setter. Nothing here knows how the model applies them.
     *
     * The insulin shape is written as three landmarks and no plateau: a preset
     * is a single-moded curve by construction, and `plateauEndMin` blank is the
     * resolver's "no opinion about the shoulder".
     */
    fun applyModelEntries(
        context: Context,
        isfMmolPerU: Double,
        preset: InsulinPreset,
        carbSensMmolPerG: Double?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        ManualInsulinRuntime.setParams(
            context,
            ManualInsulinParamsV1(
                onsetMin = preset.onsetMin,
                peakMin = preset.peakMin,
                plateauEndMin = null,
                tailMin = preset.tailMin,
                isfMmolPerU = isfMmolPerU,
            ),
            nowMs,
        )
        Settings.setCarbSensOverrideMmolPerG(context, carbSensMmolPerG)
    }
}
