package io.github.obdosok.diapilot.data

import android.database.sqlite.SQLiteDatabase
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.physio.AssembledProfileV1
import com.diapilot.core.physio.InsulinShapeV1
import com.diapilot.core.physio.PersonalInsulinCurveV1
import com.diapilot.core.physio.SegmentLandmarkReaderV1
import com.diapilot.core.physio.SegmentLandmarksV1
import com.diapilot.core.physio.isTrustedDosePurposeV1
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.i18n.PhysioText
import io.github.obdosok.diapilot.i18n.localized
import java.time.Instant
import java.time.ZoneId

/**
 * THE insulin timing profile — one implementation, one path to the model.
 *
 * The method, settled with the user:
 *
 *  1. **Every dose is a candidate**, not only labelled corrections. Shape is
 *     normalized to its own amplitude, so a wrong carb count barely moves it;
 *     n is what matters and n is 5–8× larger.
 *  2. **Each landmark is admitted on its own horizon** — onset needs 45 clean
 *     minutes, the tail needs 200. One dose can contribute a good onset and no
 *     tail, and that is the point.
 *  3. **A landmark is confounded by food acting AT IT**, not by food anywhere in
 *     the window and not by the pre-dose slope. Both of the other two were tried
 *     and both were wrong: the slope cannot see a pre-meal bolus, and
 *     window-wide contamination collapsed the clean arm to n=7.
 *  4. **The curve is built on the food-free arm.** Not because it is cleaner but
 *     because it is the only arm that is IDENTIFIED: with food on board the
 *     trace is `insulin − carbs`, and a uniform shift of one trades exactly
 *     against the other. The confounded arm is reported beside it as what the
 *     user will actually see when injecting onto a meal.
 *  5. **Amplitude does NOT come from here.** ISF stays on trusted corrections
 *     (D-03), because amplitude is exactly what a wrong carb count corrupts.
 *
 * This replaces the receipt-CDF aggregation that used to feed `person.insulin`.
 * Keeping both would have been a fifth representation of insulin in an app that
 * has already paid for having four.
 */
object InsulinProfileRuntime {
    private const val CAP_MS = 240 * 60_000L
    private const val FOOD_LOOKBACK_MS = 6 * 3_600_000L

    /** Air shots deliver nothing to the body, so they neither confound a window
     * nor carry an action to read. */
    private fun isAir(purpose: String?) = com.diapilot.core.analysis.isPrimePurpose(purpose)

    data class State(
        val curve: PersonalInsulinCurveV1?,
        val profile: AssembledProfileV1?,
        /** Doses that produced no landmark at all, by named reason. */
        val refusals: Map<com.diapilot.core.physio.LandmarkRefusal, Int>,
        val dosesConsidered: Int,
    )

    /**
     * Raw inputs, read ONCE and then filtered in memory.
     *
     * The doses, meals and readings do not depend on the causal cutoff; only
     * which of them are in scope does.
     */
    private data class Inputs(
        val boluses: List<com.diapilot.core.collector.BolusPoint>,
        val foods: List<Long>,
        val readings: List<com.diapilot.core.collector.GlucosePoint>,
    )
    private data class InputKey(val dbPath: String, val doses: Int, val lastBolus: Long, val fromMs: Long)
    @Volatile private var inputs: Pair<InputKey, Inputs>? = null

    private fun inputs(store: CollectorStore, db: SQLiteDatabase, upToMs: Long): Inputs {
        val from = FoodEraSettings.current().startMs
        val all = store.boluses(from, upToMs).filter { it.units > 0.0 && !isAir(it.purpose) }
        val key = InputKey(db.path, all.size, all.lastOrNull()?.tsMs ?: 0L, from)
        inputs?.takeIf { it.first == key }?.let { return it.second }
        return Inputs(
            boluses = all,
            foods = store.annotations(from - FOOD_LOOKBACK_MS, upToMs)
                .filter { it.kind == "food" }.map { it.tsMs }.sorted(),
            // The SAME single calibration the amplitude is measured on — see
            // [MeasurementStream]. Landmarks are read as departures from the
            // pre-dose inertia, so a stream that alternates between two
            // calibrations 1.4 mmol apart manufactures departures where the
            // glucose did nothing, and the whole earlier corpus was on such
            // a stream.
            readings = MeasurementStream.choose(store, from - CAP_MS, upToMs).readings,
        ).also { inputs = key to it }
    }

    private data class Key(val dbPath: String, val doses: Int, val lastBolus: Long)
    @Volatile private var cached: Pair<Key, State>? = null

    /**
     * The profile is computed ONCE per data revision, NOT per causal cutoff.
     *
     * This is a deliberate departure from the receipt's usual known-at
     * discipline, and it is worth stating plainly rather than burying.
     *
     * The closed-episode rebuild asks for the artifact once per FOOD and BOLUS
     * event, each with its own `knownAt`. Reading the profile at each of those
     * cutoffs is O(days x doses x readings) — the segment reader scans the
     * reading list per dose — and it does not merely cost: a live measurement
     * keyed on the exact millisecond held 90% of a core for many minutes
     * without finishing a pass; bucketed to the day and with inputs cached it
     * still completed only a fraction of the windows inside its budget. Two
     * attempts to make it causal-per-event both left the device unable to
     * finish a single rebuild.
     *
     * What is given up: a receipt records the timing profile as it stands when
     * the receipt is DERIVED, not as it stood at the event's own known-at. What
     * that costs is bounded, because the profile is a person-level, timing-only
     * quantity that needs >=3 independent days to exist and moves on the scale
     * of weeks — and because every generation change re-derives the whole
     * history anyway, so receipts are never a mixture of profile vintages.
     *
     * What is kept, and matters more: ONE curve, everywhere. The forecast, IOB,
     * deconvolution, What-if and every counterfactual integrate the same object.
     */
    fun state(store: CollectorStore, db: SQLiteDatabase, @Suppress("UNUSED_PARAMETER") asOfMs: Long): State {
        val now = System.currentTimeMillis()
        val all = inputs(store, db, now)
        val key = Key(
            db.path, all.boluses.size, all.boluses.lastOrNull()?.tsMs ?: 0L,
        )
        cached?.takeIf { it.first == key }?.let { return it.second }
        return compute(db, all, all.boluses, now).also {
            cached = key to it
            // The profile is otherwise invisible outside the settings screen,
            // and a change of measurement stream has to be checkable against the
            // previous build without tapping through the UI.
            android.util.Log.i("InsulinProfile", explain(it))
        }
    }

    private fun compute(
        db: SQLiteDatabase,
        all: Inputs,
        boluses: List<com.diapilot.core.collector.BolusPoint>,
        asOfMs: Long,
    ): State {
        val foods = all.foods.filter { it <= asOfMs }
        val readings = all.readings.filter { it.tsMs <= asOfMs }

        /**
         * The dose's own interval ends at the next INJECTION — food does not
         * close it.
         *
         * A meal used to end the window, and that silently discarded every
         * pre-meal bolus: dose at 0, meal at 15, window 15 minutes, nothing
         * measurable — even though the onset at ~12 min happens BEFORE the meal
         * and is perfectly clean. Food is not a wall, it is a confounder with a
         * time extent, and per-landmark confounding is exactly the mechanism
         * that expresses that. A second injection IS a wall: two insulin actions
         * overlapping are not separable from one trace.
         */
        fun cleanUntil(tsMs: Long): Long {
            val cap = tsMs + CAP_MS
            return boluses.firstOrNull { it.tsMs > tsMs && it.tsMs <= cap }?.tsMs ?: cap
        }

        val refusals = LinkedHashMap<com.diapilot.core.physio.LandmarkRefusal, Int>()
        val samples = mutableListOf<SegmentLandmarksV1>()
        val contributingDays = LinkedHashSet<Long>()
        boluses.forEach { b ->
            // VERDICTS WERE REMOVED along with the review card: the user
            // judged doses once, and there is no longer a screen to judge new
            // ones. A handful of previously rejected doses return to the
            // shape-fitting corpus — the effect was measured live and
            // recorded in the commit.
            val until = cleanUntil(b.tsMs)
            val foodMinutes = foods
                .filter { it >= b.tsMs - FOOD_LOOKBACK_MS && it <= until }
                .map { (it - b.tsMs) / 60_000.0 }
            val seg = SegmentLandmarkReaderV1.read(readings, b.tsMs, until, foodMinutes)
            if (seg.any) {
                samples += seg
                contributingDays += Instant.ofEpochMilli(b.tsMs)
                    .atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            } else seg.refusal?.let { refusals[it] = (refusals[it] ?: 0) + 1 }
        }

        if (samples.isEmpty()) return State(null, null, refusals, boluses.size)
        val profile = SegmentLandmarkReaderV1.assemble(samples)
        val measured = profile.landmarks
            ?: return State(null, profile, refusals, boluses.size)
        // Coerce INTO the artifact's domain and name what moved.
        //
        // Losing this in the rewrite is what the screen caught: a user's own
        // measured end of action can fall under the population floor, so the
        // curve was built, displayed, and then refused by the code that
        // installs it. A population bound may move a measurement; it may not
        // silently discard one.
        val (landmarks, coerced) = InsulinShapeV1.coerceIntoDomain(measured)
        // ONE shape implementation: the same rate-then-integrate construction
        // the manual (P1) path uses, so the curve drawn, the curve forecast and
        // the curve deconvolution subtracts are the same object.
        val knots = InsulinShapeV1.synthesize(landmarks)
            ?: return State(null, profile, refusals, boluses.size)
        val curve = PersonalInsulinCurveV1(
            knots = knots,
            observations = samples.size,
            independentDays = contributingDays.size,
            supportWeight = samples.size.toDouble(),
            landmarks = landmarks,
            measuredKnots = knots,
            coerced = coerced,
            usesUntagged = samples.isNotEmpty() &&
                boluses.none { isTrustedDosePurposeV1(it.purpose) },
        )
        return State(curve, profile, refusals, boluses.size)
    }

    /**
     * The measured end of action, when it came out BELOW the domain floor.
     *
     * Null means the measurement cleared the floor and there is nothing to mark.
     *
     * WHY THIS IS NOT A DEFECT IN THE USER'S BODY. The instrument cannot see
     * past four hours: [CAP_MS] closes a dose's window at 240 minutes and the
     * next injection closes it earlier, `TAIL_HORIZON_MIN` needs 200 clean
     * minutes inside that, and "end" is the rate returning to the pre-dose
     * slope, which a rising background meets early. So a measured end of, say,
     * 180 is where the window ran out, not where the insulin did — and the
     * screen has to say so, because the number it sits next to looks exactly
     * like a measurement (audit M1).
     *
     * Read from the profile's own `tailEnd`, i.e. BEFORE `coerceIntoDomain`
     * lifts it to the floor. The coerced value is what the model runs; this is
     * what the doses actually showed, and the mark is about the difference.
     */
    fun measuredTailBelowFloorMin(state: State): Double? =
        state.profile?.tailEnd?.minute?.takeIf { it < TAIL_DOMAIN_FLOOR_MIN }

    /** The floor the mark above compares against — the artifact's own domain. */
    val TAIL_DOMAIN_FLOOR_MIN: Double =
        com.diapilot.core.physio.PhysioBoundsV1().insulinTailMinRange.start

    /**
     * One line for the screen: what is in use, or precisely what is missing.
     * [context] renders the landmark messages in the UI language; without one
     * (the log line) they come out as their data form.
     */
    fun explain(state: State, context: android.content.Context? = null): String {
        val p = state.profile
        val curve = state.curve
        fun coercedText(moved: List<com.diapilot.core.physio.CoercedLandmark>) =
            context?.let { PhysioText.coerced(it, moved) } ?: moved.joinToString(", ")
        fun conflictText(c: com.diapilot.core.physio.OrderingConflict) =
            context?.let { PhysioText.orderingConflict(it, c) } ?: c.toString()
        if (context == null) {
            // Developer log line only (see the call site below) — plain English
            // in its data form, no resources: there is no Context to resolve them.
            return when {
                curve != null && p != null ->
                    "measured from %d doses, %d days · onset %.0f · peak %.0f · active until %.0f · end %.0f min"
                        .format(
                            curve.observations, curve.independentDays,
                            curve.landmarks.onsetMin, curve.landmarks.peakMin,
                            curve.landmarks.activeEndMin, curve.landmarks.tailMin,
                        ) +
                        (if (p.arm == com.diapilot.core.physio.ProfileArmV1.FOOD_FREE)
                            " · from doses with no food active"
                        else " · from all doses (too few clean ones yet)") +
                        (if (curve.coerced.isEmpty()) ""
                        else " · coerced to the model bounds: ${coercedText(curve.coerced)}")
                p?.orderingConflict != null -> "curve not built: ${conflictText(p.orderingConflict!!)}"
                p != null -> "not enough landmarks: " + listOf(
                    p.onset.on(p.arm)?.let { "onset ${it.samples}" } ?: "no onset",
                    p.peakRate.on(p.arm)?.let { "peak ${it.samples}" } ?: "no peak",
                    p.tailEnd?.let { "end ${it.samples}" } ?: "no end",
                ).joinToString(" · ")
                else -> "prior only: no dose produced a landmark, out of ${state.dosesConsidered}"
            }
        }
        val res = context.localized()
        return when {
            curve != null && p != null ->
                res.resources.getQuantityString(
                    R.plurals.insulin_profile_runtime_measured, curve.observations,
                    curve.observations, curve.independentDays,
                    curve.landmarks.onsetMin, curve.landmarks.peakMin,
                    curve.landmarks.activeEndMin, curve.landmarks.tailMin,
                ) +
                    // The arm is part of the answer. When it flips, the profile
                    // can move by several minutes in one step, and without this
                    // the user would read that as the model changing its mind
                    // about their body rather than as one population replacing
                    // another.
                    res.getString(
                        if (p.arm == com.diapilot.core.physio.ProfileArmV1.FOOD_FREE)
                            R.string.insulin_profile_runtime_arm_food_free
                        else R.string.insulin_profile_runtime_arm_mixed,
                    ) +
                    (if (curve.coerced.isEmpty()) ""
                    else res.getString(R.string.insulin_profile_runtime_coerced, coercedText(curve.coerced)))
            p?.orderingConflict != null ->
                res.getString(R.string.insulin_profile_runtime_no_curve, conflictText(p.orderingConflict!!))
            p != null -> res.getString(
                R.string.insulin_profile_runtime_missing_landmarks,
                listOf(
                    p.onset.on(p.arm)?.let {
                        res.getString(R.string.insulin_profile_runtime_onset_present, it.samples)
                    } ?: res.getString(R.string.insulin_profile_runtime_onset_missing),
                    p.peakRate.on(p.arm)?.let {
                        res.getString(R.string.insulin_profile_runtime_peak_present, it.samples)
                    } ?: res.getString(R.string.insulin_profile_runtime_peak_missing),
                    p.tailEnd?.let {
                        res.getString(R.string.insulin_profile_runtime_tail_present, it.samples)
                    } ?: res.getString(R.string.insulin_profile_runtime_tail_missing),
                ).joinToString(" · "),
            )
            else -> res.getString(R.string.insulin_profile_runtime_prior_only, state.dosesConsidered)
        }
    }
}
