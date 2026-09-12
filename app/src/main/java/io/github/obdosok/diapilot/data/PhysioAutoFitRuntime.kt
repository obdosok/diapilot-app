package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.analysis.MeasurementStreamCore
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.hybrid.HybridBasalEvent
import com.diapilot.core.hybrid.HybridBolusEvent
import com.diapilot.core.hybrid.hybridFoodEventFromNote
import com.diapilot.core.physio.FitEpisodeBuilderV1
import com.diapilot.core.physio.PhysioAutoFitV1
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.i18n.localized

/**
 * THE AUTO-FIT, ON THE DEVICE — episode selection and one call into the fitter.
 *
 * The mathematics is NOT here. Search, loss and tuning live in
 * [PhysioAutoFitV1], shared with the laptop bench, because two fitters over the
 * same knobs diverge and the divergence shows up only as «the phone suggests a
 * different peak than the bench did». This file does the two things the phone
 * genuinely owns: reading the user's database, and deciding what counts as an episode.
 *
 * WHAT COUNTS AS AN EPISODE, and every clause was paid for:
 *
 *  - **a meal of at least 25 g with a bolus in the window.** Below that the
 *    curve is mostly background and the fit reads noise.
 *  - **a quiet run-up** — no dose for 4 h and no food for 5 h before the anchor.
 *    Without it the window opens with unfinished insulin from a meal we are not
 *    scoring, and the fitter charges that to the knobs.
 *  - **not a rescue treatment.** Dextrose taken to stop a hypo is a different
 *    physiological situation and it dominates any corpus it enters.
 *  - **enough real readings.** A window with a sensor gap is not a quiet
 *    stretch, it is a stretch we were not watching (discipline #6), and the two
 *    look identical afterwards because readings back-fill.
 *
 * ONE STREAM. Glucose is read through [MeasurementStreamCore.chooseFrom], never
 * off the raw union of feeds: `xdrip_sgv` and `libre_ble` disagree by ~1.4 mmol
 * and report falls ~14% apart, so a corpus mixing them measures the calibration
 * rather than the body.
 *
 * The fit is a SUGGESTION. It is returned to the screen and applied only when the user
 * taps — «no auto-teach» is about learned notes, but the same reasoning holds
 * here with more force, because these knobs reach the hypo-alert path.
 */
object PhysioAutoFitRuntime {

    private const val H = 3_600_000L

    /** The window is owned by the fitter, so the phone and the bench score the
     *  same stretch. Six hours — measured, see [PhysioAutoFitV1.SCORING_GRID]. */
    val GRID: List<Int> = PhysioAutoFitV1.SCORING_GRID
    private val EPISODE_MS = GRID.last() * 60_000L

    /**
     * WHERE THE SEARCH STARTS: what is applied RIGHT NOW.
     *
     * It used to start from the shipped model, on the reasoning that a fit
     * should be absolute. Two things made that wrong once the card grew:
     *
     *  - a LOCK has to mean «keep the number I am using», not «keep the factory
     *    number I have already overridden»;
     *  - "how much better" needs a baseline, and the only baseline the user cares
     *    about is the model the phone is running today.
     *
     * Nothing compounds, because the knobs fully determine the model and the
     * model is always rebuilt from the UNTUNED base — a second fit re-derives
     * the same thing rather than stacking on the first.
     */
    fun startingKnobs(context: Context): PhysioAutoFitV1.Knobs? {
        val m = HybridModelStore.untunedModel(context) ?: return null
        val v = PhysioTuning.read(context)
        val hand = ManualInsulinRuntime.params(context)
        val shipped = com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED
        return PhysioAutoFitV1.Knobs(
            // The hand ISF moved to P1. Reading the retired store
            // here would silently start every auto-fit from the untuned base
            // instead of from what the user actually runs.
            // THE INSULIN SHAPE IS ALSO FROM P1, JUST LIKE ISF. `PhysioTuning` for
            // shape is a retired store: the card writes it to `manual_insulin_*`,
            // and reading `physio_*` here would start the auto-fit from numbers
            // that are not on the device.
            //
            // `plateauEndMin` is stored as an absolute minute, while the `phaseMin`
            // knob is a DURATION, so it is converted by subtracting the peak.
            isf = hand.isfMmolPerU ?: m.insulin.isf,
            onsetMin = hand.onsetMin ?: m.insulin.onsetMin,
            fullSpeedMin = hand.peakMin ?: m.insulin.peakMin,
            phaseMin = hand.plateauEndMin?.minus(hand.peakMin ?: m.insulin.peakMin) ?: 30.0,
            tailMin = hand.tailMin ?: m.insulin.tailDurationMin,
            emptyingKcalPerHour = v.emptyingKcalPerHour ?: shipped.emptyingKcalPerHour ?: 120.0,
            carbSieving = v.carbSieving ?: shipped.carbSieving,
            carbSpread = v.carbSpread ?: 1.0,
            trustRamp = v.trustRamp,
        )
    }

    fun buildEpisodes(store: CollectorStore, limit: Int): List<PhysioAutoFitV1.Episode> {
        val era = FoodEraSettings.current().startMs
        val now = store.readings(0, Long.MAX_VALUE).maxOfOrNull { it.tsMs } ?: return emptyList()
        val from = era - 12 * H
        val stream = MeasurementStreamCore.chooseFrom(store.sensorReadingsWithSource(from, now))
        val readings = stream.readings.sortedBy { it.tsMs }
        if (readings.isEmpty()) return emptyList()
        val doses = store.boluses(from, now).filter { it.units > 0.0 }
        val basals = store.basalEvents(era - 72 * H, now).filter { it.units > 0.0 }
        val notes = store.annotations(from, now).filter { it.kind == "food" }.sortedBy { it.tsMs }

        fun at(ts: Long): Double? {
            val b = readings.lastOrNull { it.tsMs <= ts }
            val a = readings.firstOrNull { it.tsMs >= ts }
            if (b != null && ts - b.tsMs <= 12L * 60_000L) return b.mmol
            if (b == null || a == null) return null
            val span = a.tsMs - b.tsMs
            if (span > 30L * 60_000L) return null
            return b.mmol + (ts - b.tsMs).toDouble() / span * (a.mmol - b.mmol)
        }

        // ONE BUILDER, SHARED WITH THE BENCH. The two definitions
        // had drifted apart by a factor of two in episodes per day, which is
        // the unit the adaptive ISF fit is sized in — see FitEpisodeBuilderV1.
        return FitEpisodeBuilderV1.build(
            fromMs = from,
            toMs = now,
            notes = notes.map {
                FitEpisodeBuilderV1.Note(it.tsMs, it.content, it.estCarbs, it.analysis)
            },
            doses = doses.map { FitEpisodeBuilderV1.Dose(it.tsMs, it.units) },
            basals = basals.map { FitEpisodeBuilderV1.Dose(it.tsMs, it.units) },
            grid = GRID,
            at = ::at,
            foodOf = { hybridFoodEventFromNote(it.tsMs, it.content, it.estCarbs, it.analysis) },
            limit = limit,
        ).also { android.util.Log.i("PhysioAutoFit", "builder: " + it.funnel()) }.episodes
    }

    // `run(...)` used to live here as a blocking one-shot. It is gone rather
    // than kept «just in case»: the screen now drives the fit through
    // `PhysioAutoFitController`, which owns the progress and the single-run
    // guard, and a second entry point into the same work is how two callers end
    // up disagreeing about whether a fit is in flight. Dead code that still
    // compiles is the kind that gets called by accident later.

    /**
     * THE SAFETY CORRIDOR — measured timings, plausible everything else.
     *
     * The ruling: the insulin landmarks are not a free parameter. They are read
     * segment by segment from the user's own doses, and the fit may nudge them but not
     * replace the user's pharmacology with one that scores better. On the wide table it
     * did exactly that — reaching 100-120 min for full speed, which the user's own
     * top-up rule refutes (M-82).
     *
     * Returns null when nothing has been measured yet; the caller then keeps the
     * wide table rather than inventing a corridor around a factory default,
     * which would be a safety claim with no measurement behind it.
     *
     * ISF is deliberately absent from the corridor. It drifts — with activity,
     * with the injection site, with yesterday's walk — catching that drift is
     * part of what this app is for, and the user has already applied a computed value
     * and watched their glucose improve.
     */
    fun safetyBounds(context: Context): Pair<Map<String, Pair<Double, Double>>, String>? {
        val store = Stores.get(context) as? SqliteCollectorStore ?: return null
        val measured = runCatching {
            InsulinProfileRuntime.state(store, store.readableDatabase, System.currentTimeMillis())
                .curve?.landmarks
        }.getOrNull() ?: return null
        val timing = PhysioAutoFitV1.boundsAround(measured)
        val label = context.localized().getString(
            R.string.physio_auto_fit_runtime_safety_label,
            measured.onsetMin,
            measured.peakMin,
            (measured.plateauEndMin ?: (measured.peakMin + 30.0)) - measured.peakMin,
            measured.tailMin,
        )
        // ISF GETS A CORRIDOR TOO, and this reverses an earlier ruling.
        //
        // That decision left ISF free on the reasoning that it genuinely drifts
        // and catching the drift is part of the app's job. The drift has since
        // been measured: on a ten-day window a day's ISF differs from the
        // window's by **5%**, and that figure is an UPPER bound because it still
        // contains the noise of fitting one or two curves. A +-20% corridor
        // holds five percent with room to spare, so the corridor does not stop
        // the app from catching what it was defending.
        //
        // What the free range did cost is measured on the same bench, walk-
        // forward, window 10, shape criterion:
        //
        //   with the corridor     in sample 1.587   out of sample 1.910 (75%)
        //   without (as here)     in sample 1.917   out of sample 1.979 (63%)
        //
        // Worse OUT of sample, and worse IN sample — which cannot mean the bound
        // hurts, because the wide region contains the narrow one. It means the
        // coordinate descent gets lost in the larger space. The corridor is a
        // regulariser as much as a safety rail, and dropping it bought nothing.
        //
        // Centred on the CORRECTIONS value, not on whatever is applied: seven
        // tagged falls with no food in play are the only clean measurement of
        // this quantity the corpus has.
        val isf = PhysioAutoFitV1.MEASURED_ISF_MMOL_PER_UNIT
        return (
            timing + PhysioAutoFitV1.PLAUSIBLE_PHYSIOLOGY +
                mapOf("isf" to (isf * 0.8 to isf * 1.2))
            ) to (
            label + " · ISF %.2f..%.2f".format(isf * 0.8, isf * 1.2) +
                // The lock has to be VISIBLE. A knob the fit silently declined
                // to touch reads on the card as a knob the fit agreed with.
                (
                    if (handHeldAxes(context).isEmpty()) ""
                    else context.localized()
                        .getString(R.string.physio_auto_fit_runtime_tail_held_by_hand)
                    )
            )
    }

    /**
     * THE AXIS A HAND-ENTERED END OF ACTION TAKES OFF THE TABLE.
     *
     * The tail corridor is held inside the instrument's own floor
     * ([PhysioAutoFitV1.boundsAround]) — 240 min, because a per-dose window
     * cannot see past four hours and a fit reaching under that would be fitting
     * the ruler. But a person who states 130 is not reporting a truncated
     * measurement, and a corridor that starts at 240 would propose raising
     * their number on the very first pass. So when the hand tier carries an end
     * of action, this axis is LOCKED rather than clamped: the fit keeps the
     * user's value and says so on the card.
     *
     * Only the tail. The onset and peak corridors are centred on the
     * measurement with no floor that can conflict with a hand entry, and ISF is
     * the axis the fit exists to move.
     */
    private val HAND_HELD_TAIL_AXIS = setOf("tail")

    /** Axes the user has already answered by hand, which the fit must not
     *  argue with. Empty when nothing is entered. */
    fun handHeldAxes(context: Context): Set<String> =
        if (ManualInsulinRuntime.params(context).tailMin != null) HAND_HELD_TAIL_AXIS
        else emptySet()

    /** Spread of one knob across the per-episode fits — the evidence that must
     *  travel with the median, because the user's episodes do not agree. */
    fun spread(batch: PhysioAutoFitV1.Batch, pick: (PhysioAutoFitV1.Knobs) -> Double): String {
        if (batch.fits.isEmpty()) return "—"
        val v = batch.fits.map { pick(it.second.knobs) }.sorted()
        return "%.2f…%.2f".format(v.first(), v.last())
    }
}
