/**
 * Carbs stage 2: reverse calibration against the user's own data.
 *
 * A meal's observed rise understates its true glucose impact — part of the
 * carbs was silently eaten by the covering bolus. The personal insulin kernel
 * G(τ) says exactly how much: by the time the peak is reached, the bolus has
 * absorbed |G(timeToPeak)| mmol/L per unit. So
 *
 *   totalEffect = rise − units · G(timeToPeak)          (G ≤ 0)
 *
 * Meals with a known carbs estimate (Vision / manual) then calibrate a
 * personal carb sensitivity (mmol/L per gram); meals without one get an
 * effective grams figure computed backwards from their own response.
 * Descriptive only — never a dosing instruction.
 */
package com.diapilot.core.analysis

/** One meal episode reduced to what carbs math needs. */
data class CarbEpisode(
    val onsetMs: Long,
    val rise: Double,           // mmol/L, onset → peak
    val timeToPeakMin: Double,
    val bolusUnits: Double?,    // covering bolus + top-ups within the episode
    val estCarbs: Double?,      // grams; null = unknown
    val active: Boolean = false,  // exercise overlapped — insulin/glucose scale is off
    // Hypo rescue (dextrose at low BG): counter-regulation + the insulin that
    // caused the low contaminate the response — never calibration material.
    val rescue: Boolean = false,
    /**
     * The gram FACTOR of this episode's external anchor (`trueGrams / recordedGrams`),
     * or null when the grams are the user's own recorded estimate.
     *
     * It is a factor rather than a boolean because the right treatment is a CONVERSION,
     * not a drop. This coefficient is defined on RECORDED grams (see the doc above), and
     * an anchored row's grams are TRUE grams — so dividing by the factor puts the episode
     * back in the coefficient's own space and it keeps contributing.
     *
     * EXCLUDING them was tried first and measured worse: dropping the anchored smoothie
     * episodes from the corpus moved the learned global coefficient up substantially with
     * nothing about the remaining episodes changed. Those episodes sat in the low half of
     * the distribution and their low ratio was CORRECT for recorded grams — the recorded
     * grams really did produce that rise. Removing valid evidence to avoid a unit clash is
     * the wrong trade when the units can simply be converted.
     */
    val anchorFactor: Double? = null,
)

/**
 * The carb sensitivity the forecast runs on, in mmol per **RECORDED** gram.
 *
 * READ THE UNIT FIRST — it is the whole point, and getting it wrong shipped a
 * measurable over-prediction for a whole evening.
 *
 * The model multiplies the grams the app actually HAS, which are the user's notes,
 * and those can be over-stated relative to an external measurement (a logged
 * smoothie portion, say, versus its true juice content). So the
 * number that belongs here lives in RECORDED-gram space. The physiological
 * coefficient — what a gram of carbohydrate really does to this body — is a
 * different quantity, measured on TRUE grams, and it is somewhat lower:
 *
 *   smoothie  (rise) mmol / true grams  = a value   INSULIN-FREE, no kernel in it
 *   a dense dessert  (rise) mmol / true grams  = a similar value   deconvolved, so it INHERITS the kernel
 *   clean-note learner on exact grams = a similar value   (small n, rescues excluded)
 *
 * **Both numbers are right; they answer different questions.** The true-gram
 * value is knowledge about the body and the target this constant converges on as
 * the grams pipeline is repaired. This constant is what the model applies TODAY,
 * and it must sit below the true-gram value by roughly the amount the records are
 * inflated — which is what the insulin-free note learner measures.
 *
 * HOW THE CONFUSION WAS CAUGHT, because the mechanism matters more than the value.
 * The higher physiological number briefly shipped as the default. The
 * forecast-error A/B had already said it was the worst of three arms (with an
 * interior optimum, so the metric was not simply preferring less food). That
 * result was then set aside on a correct objection — "that lower number is not
 * physiology, it is a measure of how badly meals get logged" — but the wrong
 * conclusion was drawn from it: the physiological number was put where recorded
 * grams are multiplied. A live check the same day, on a meal with no insulin in
 * the window, confirmed the forecast overshot the observed rise, and the user
 * then ate more than the logged amount.
 *
 * The error was largest exactly where it was predicted to be: a NEW dish, no
 * measured curve, no label profile, so bare `grams × carbSens` — the one branch
 * this constant fully owns.
 *
 * IT IS STILL A BRIDGE. It is pinned rather than learned because the LIVE learner
 * is `carbSensitivity(carbEpisodes(...))`, which reads detector rises and answers
 * low; the honest replacement is [carbSensFromCleanNotes], which is note-anchored
 * and insulin-free, but wiring it into `TwinCache` means moving the corpus build
 * ahead of the coefficient and deserves its own measurement.
 *
 * THE SHIPPED NUMBER is the bundled synthetic example person's value (ISF 1.80
 * mmol/L per unit over a carb ratio of about 11 g per unit), not a measurement of
 * anyone. A real install should replace it with its own estimate.
 */
const val CARB_SENS_OVERRIDE_DEFAULT = 0.165

// The semantics were the whole question, and they are settled: in
// `HybridForecastEngine.foodDelta` the food term is
// `amplitude × activityMultiplier × (fastFraction + lateFraction)`, and those
// fractions integrate to one over the meal. So **this constant is the TOTAL
// glucose a gram adds**, not the height of the peak: the full glucose increase,
// not the peak.
//
// On real data three routes agreed to within about a quarter of each other: the
// deconvolution corpus at FULL amplitude (peak + late rise) for smaller meals,
// the same corpus for larger meals, and several insulin-free smoothies of an
// identical recipe.
//
// The smoothie number is a LOWER bound: every one of those windows is cut by the
// next meal, so the rise had not finished. It is quoted because it
// is the cleanest natural experiment in the database — the last bolus was many
// hours earlier, and the recipe is the same every time, so gram error
// cannot be what varies. Its spread is much tighter than the corpus-wide spread,
// which is the model measuring itself: fix the grams and the scatter halves.
//
// Why a lower, peak-based value is not wrong-in-itself: [carbSensFromCleanNotes]
// reads a PEAK rise. Peak height understates a large slow meal, because part of
// its carbohydrate arrives after the peak — and measured on real data that bias
// has the shape you would expect, per-gram peak falling by more than half from
// small to large meals.
//
// KNOWN AND NOT FIXED HERE: [predictPerGramRise] blends this prior with a corpus
// term of `o.peakRise / o.carbGrams` — peak space — so the POOLED branch still
// mixes a peak-derived estimate into a slot that means total rise. This change
// only corrects the branch the constant owns outright, `grams × carbSens` for a
// dish with no pool. Repairing the pooled branch means moving the corpus term to
// `peakRise + tailRise` for tail-observed episodes, which changes what is learned
// and deserves its own measured pass.

/**
 * What the forecast should use: the override when one is set, else what was
 * learned. Pure, so "the override wins and the learned value is preserved" is
 * testable without a phone. Returns null only when there is neither.
 */
fun effectiveCarbSens(learned: CarbSensitivity?, overrideMmolPerG: Double?): CarbSensitivity? {
    if (overrideMmolPerG == null || overrideMmolPerG <= 0.0) return learned
    // n = 0 marks "not measured from meals" — the same convention the carb-side
    // prior already uses, so nothing downstream mistakes it for evidence.
    return CarbSensitivity(
        mmolPerGram = overrideMmolPerG,
        q1 = overrideMmolPerG * 0.7, q3 = overrideMmolPerG * 1.3, n = 0,
    )
}

data class CarbSensitivity(
    val mmolPerGram: Double,    // median over calibrating meals
    val q1: Double,
    val q3: Double,
    val n: Int,                 // meals with known carbs that calibrated it
)

/**
 * Total glucose impact of the meal at its peak: the visible rise plus the
 * part the bolus absorbed. Null when the kernel doesn't cover timeToPeak
 * (never guess — a wrong insulin share corrupts the calibration).
 */
fun glucoseEffect(e: CarbEpisode, kernel: List<KernelPoint>): Double? {
    val units = e.bolusUnits ?: 0.0
    if (units == 0.0) return e.rise
    val g = kernelAt(kernel, e.timeToPeakMin) ?: return null
    return e.rise - units * g
}

/**
 * Personal carb sensitivity from meals that carry a carbs estimate.
 * Tiny meals (< minCarbs g) are skipped — their relative carb error is huge.
 * Episodes overlapping exercise are skipped too: activity accelerates both
 * insulin action and glucose uptake, so they'd drag the scale down.
 */
fun carbSensitivity(
    episodes: List<CarbEpisode>,
    kernel: List<KernelPoint>,
    minCarbs: Double = 15.0,
    minMeals: Int = 3,
    kernelForEpisode:((CarbEpisode)->List<KernelPoint>)?=null,
): CarbSensitivity? {
    val ratios = episodes.mapNotNull { e ->
        if (e.active || e.rescue) return@mapNotNull null
        // TEMPORARY BRIDGE — the BACKWARD direction of the one described in
        // `CarbAnchors.priorInDishGramSpace`. It exists because anchored dishes are a
        // MINORITY; once they are the majority this inverts, the global is re-learned in
        // TRUE grams, and the conversion moves to the un-anchored remainder instead. Keep
        // it to this one line so that reversal stays an edit rather than a project.
        //
        // ANCHORED grams are converted back into RECORDED-gram space, not dropped. This
        // coefficient is DEFINED on recorded grams, so an episode whose denominator is
        // true grams would otherwise make the median an average over two units. Dividing
        // by the anchor's factor restores the very number the user had written, so the
        // episode contributes exactly what it always did and the coefficient stays
        // homogeneous. Measured: with this conversion the learned global is UNCHANGED by
        // an anchor migration; excluding the episodes instead moved it +30%.
        val carbs = e.estCarbs?.let { g -> e.anchorFactor?.let { g / it } ?: g }
            ?.takeIf { it >= minCarbs } ?: return@mapNotNull null
        glucoseEffect(e, kernelForEpisode?.invoke(e)?:kernel)?.takeIf { it > 0 }?.let { it / carbs }
    }
    if (ratios.size < minMeals) return null
    val sorted = ratios.toDoubleArray().sortedArray()
    return CarbSensitivity(
        mmolPerGram = percentile(sorted, 50.0),
        q1 = percentile(sorted, 25.0),
        q3 = percentile(sorted, 75.0),
        n = ratios.size,
    )
}

/**
 * Effective carbs of a meal, inferred from the user's own response: how many
 * grams would explain this episode at the calibrated sensitivity. This is the
 * number that matters for the twin — "the label says 60, but for you it works
 * out to 45".
 */
fun effectiveCarbs(
    e: CarbEpisode,
    kernel: List<KernelPoint>,
    sens: CarbSensitivity,
): Double? =
    glucoseEffect(e, kernel)?.takeIf { it > 0 }?.let { it / sens.mmolPerGram }

/**
 * Reduce detected meals to [CarbEpisode]s: system-labeled rises (dawn,
 * continuation, unknown) are not food; the carbs figure comes from the
 * nearest food note in the usual merge window [onset − 90m, onset + 30m].
 */
fun carbEpisodes(
    meals: List<com.diapilot.core.collector.MealEvent>,
    labelByOnset: Map<Long, String>,
    notes: List<com.diapilot.core.collector.Annotation>,
    hr: List<com.diapilot.core.collector.HrPoint> = emptyList(),
    beforeMs: Long = 90L * 60_000,
    afterMs: Long = 30L * 60_000,
): List<CarbEpisode> {
    val carbNotes = notes.filter {
        it.estCarbs != null && !isContextNote(it.content)
    }
    val baseline = hrBaseline(hr)
    // One index for every meal's window — see [HrIndex]. Built only when it will
    // actually be consulted (`active` short-circuits on a null baseline).
    val hrIndex = if (baseline == null) null else HrIndex.of(hr)
    return meals
        .filter { labelByOnset[it.onsetMs] !in SysLabels.ALL }
        .map { m ->
            val note = carbNotes
                .filter { it.tsMs in (m.onsetMs - beforeMs)..(m.onsetMs + afterMs) }
                .minByOrNull { kotlin.math.abs(it.tsMs - m.onsetMs) }
            CarbEpisode(
                onsetMs = m.onsetMs,
                rise = m.rise,
                timeToPeakMin = m.timeToPeakMin,
                bolusUnits = m.bolusUnits,
                estCarbs = note?.estCarbs,
                // Exercise window: eating → past the peak. A walk 30 min
                // after the meal lands squarely in it.
                active = baseline != null && hasActivity(
                    hrIndex!!,
                    (note?.tsMs ?: m.onsetMs),
                    m.onsetMs + (m.timeToPeakMin * 60_000).toLong() + 60L * 60_000,
                    baseline,
                ),
                rescue = m.preBg < 4.2,
                anchorFactor = if (note?.carbsSource != ANCHOR_SOURCE) null
            else note?.let { n -> CARB_ANCHORS.firstOrNull { it.conceptId == conceptFor(n.content)?.id }?.factor },
            )
        }
}

/**
 * Measured quiet-window drift: a sedentary, sober,
 * non-dawn stretch glides at this rate with no bolus acting. Basal is ALWAYS on,
 * so an uncorrected rise UNDER-states the food by this much per hour.
 */
const val BASAL_DRIFT_MMOL_PER_H = -0.51

/**
 * AN INDICATOR OF LOGGING QUALITY — **NOT a candidate carb coefficient.**
 * Read this paragraph before using the number anywhere.
 *
 * What it returns is physiology MULTIPLIED BY logging quality, because it divides
 * an observed rise by the RECORDED grams. It answers a notably lower value while
 * the externally-anchored physiological value is higher — and that gap is not a
 * disagreement about the body, it is the amount by which the recorded grams are
 * over-stated (a logged smoothie portion, say, versus its true content).
 *
 * WHERE THE OVER-STATEMENT IS **NOT** — audited separately, because an
 * earlier draft of this very comment named the wrong culprit and the claim then
 * propagated into other briefs.
 *
 * **The PARSER is exonerated; the WRITER is not.** Keep those apart — the first
 * draft of this retraction collapsed them and got the repair backwards.
 *
 * `"bread ×2 = 14 carbs"` means 14 per slice, and [ComponentEstimate.totalGrams]
 * multiplies by the count, so [parseComponents] already reads the doubled total.
 * The evidence is ONE arm, not two: the LLM's own `CARBS_LINE_PREFIX:` line. The
 * tempting second arm — Σ(unitGrams × count) == `est_carbs` on essentially every
 * note — is a TAUTOLOGY and must not be quoted: `AnnotationComposer` writes
 * `est_carbs` AND the `COMPONENT_LINE_PREFIX` lines from the same `compRows` via
 * `sostavLine`, the exact inverse of the parser, so the two agree by construction
 * on every hand-edited note. The clean case is a note with `carbs_source='llm'`,
 * never edited: a component line listing bread ×2 among several items sums, with
 * the count applied, to a total that matches the model's own carbs-line midpoint.
 * Drop the ×N and the sum falls outside the range the same model wrote. That
 * single note carries the refutation.
 *
 * **What IS halved — a REAL ratio of roughly 1.8×, whose magnitude the retracted
 * brief had right and whose mechanism it had wrong.** A set of breakfasts with
 * byte-identical content ("breakfast: hummus, salad, scramble, 2 slices of
 * bread") split PERFECTLY in two: some write `bread ×2` with the count explicit,
 * others write `bread` with no count at all. The totals separate cleanly between
 * the two groups; under random assignment that separation is highly unlikely by
 * chance. The ratio between the two groups' means is close to 1.82×, and the same
 * split shows up on another ingredient. This is BIAS, not spread — an earlier
 * draft here called it spread and thereby pointed at the wrong repair (better
 * gram estimates) instead of the right one: make the count consistent at WRITE
 * time, or back-fill it. Never by re-deriving grams from the rise, which is
 * circular and kills the anomaly channel.
 *
 * A live write path that does exactly this: `AnnotationComposer`'s "one-tap
 * save" button sums [parseComponentsEstimate], which returns `name to unitGrams`
 * and **discards the count**, so `est_carbs` on that path under-states by the ×N
 * factor. No note in the sampled pull is confirmed to have come through it, but
 * it is the alleged defect — alive, and in the writer rather than the reader.
 *
 * The other two known gram errors enter from outside `COMPONENT_LINE_PREFIX`
 * entirely: the hardcoded tablet constant (dextrose, under-stated; [DEXTROSE_TABLET_G]
 * is **kept as shipped, and deliberately so** — read its own comment, the change
 * moves the sustained-hypo alert and needs an alert-safety measurement first;
 * only the DB rows were back-filled) and a hand-typed re-used label (smoothie,
 * over-stated, where every note carries no `COMPONENT_LINE_PREFIX` line at all, so
 * nothing there is extraction at all).
 *
 * Shipping it as the coefficient would stamp the logging error into the model of
 * the body, and it is a DEAD END by construction: repair the smoothie grams and
 * this number becomes wrong overnight, degrading the forecast by exactly the
 * amount it was "right" by. See the grams↔coefficient coupling, which is a hard
 * constraint, not a caution.
 *
 * **The useful reading: the gap between this and the override measures INPUT
 * QUALITY, and it should CLIMB toward the physiological value as the pipeline is
 * repaired.** That makes it a progress meter for the grams work. An A/B scoring it
 * against forecast error cannot settle anything either — it is derived from
 * recorded grams and then scored on those same grams, so it wins by construction.
 *
 * CARB SENSITIVITY LEARNED FROM THE USER'S NOTES, ON THE INSULIN-FREE SUBSET.
 *
 * This is the replacement for [carbSensitivity], and the difference is not the
 * estimator — it is what the number is measured FROM.
 *
 * [carbSensitivity] reads `carbEpisodes`, built from `meal_events`: DETECTOR rises. That
 * makes the shipped coefficient inherit every detector defect — fragmentation
 * (one meal split into 2–3 events, each divided by the whole meal's grams) and
 * phantom meals built out of sensor compression artifacts. It is why the learned
 * value sat well below every external anchor, and why the physiological override
 * had to be bridged in by hand in an earlier forecast version.
 *
 * Here the anchor is the NOTE and the amplitude comes from the deconvolution
 * corpus, restricted to episodes where **no insulin acted at all**. That
 * restriction is the point, not a tidiness gate: the corpus recovers a curve by
 * adding the kernel's effect BACK, so carbs and sensitivity are one ridge when
 * both are fitted from the same windows — a meal eaten with zero units on board
 * is off that ridge, because whatever glucose did IS the food and there is
 * nothing to add back. The estimate therefore does not inherit the kernel's
 * error, which no previous carb-sensitivity number could say.
 *
 * TWO LIMITS THAT BELONG IN EVERY QUOTE OF THE RESULT:
 *  - it is a DAYTIME estimate. Insulin-free meals cluster around midday (a
 *    smoothie the user typically doses for later than the meal itself), so this
 *    does not speak for the evening cell.
 *  - basal is always acting. [basalDriftMmolPerH] is subtracted as a CONSTANT,
 *    which is a correction, not a control.
 *
 * @param corpus note-anchored observations ([deconvolvedMealObservations]).
 * @param boluses every insulin event in range — do NOT pre-filter by type, that
 *   column is null on the overwhelming majority of rows.
 * @param insulinLookbackMin how far back a bolus still counts as acting. 120 is
 *   the loose gate; the kernel runs ~240, so the strict gate is 240. A set that
 *   only exists at the loose gate is a LOW-insulin set, not an insulin-free one —
 *   report which was used.
 */
fun carbSensFromCleanNotes(
    corpus: List<MealObservation>,
    boluses: List<com.diapilot.core.collector.BolusPoint>,
    minCarbs: Double = 15.0,
    minMeals: Int = 3,
    insulinLookbackMin: Long = 240L,
    basalDriftMmolPerH: Double = BASAL_DRIFT_MMOL_PER_H,
    /**
     * TRUE ÷ RECORDED grams for this episode, from an EXTERNAL anchor only
     * (package label, weighing, molar mass) — never fitted, or this re-creates
     * the carbs↔sensitivity ridge in new packaging. Default 1.0 = «no anchor,
     * take the note at its word», which is what the plain estimator does.
     *
     * With it, the result stops being «physiology × logging quality» and becomes
     * physiology alone — for the subset where the anchor exists. That subset is
     * the ONLY place a carb coefficient can be measured rather than fitted.
     */
    gramQuality: (MealObservation) -> Double = { 1.0 },
    /**
     * The size floor FOR THIS EPISODE. Default: the flat [minCarbs].
     *
     * Why it is a function. The flat floor exists because «a small meal's
     * RELATIVE gram error is huge» — true for food estimated by eye, and exactly
     * BACKWARDS for dextrose, whose grams come off a package with no error at
     * all. The gate was measuring the SIZE of the portion when what makes an
     * episode unusable is the PRECISION of its grams. Measured cost of that
     * confusion: 31 insulin-free, exactly-gramed dextrose episodes — the cleanest
     * records in the whole database — were excluded from the one measurement that
     * needed them most, because a tablet is 5.1 g and the floor was 15.
     *
     * The caller supplies provenance (`annotations.carbs_source`): externally
     * anchored grams get a low floor, eyeball estimates keep the high one.
     */
    minCarbsFor: (MealObservation) -> Double = { minCarbs },
    /**
     * Was this a HYPO RESCUE? Such an episode must never calibrate, and the
     * insulin-free gate does NOT catch it: the bolus that caused the low is often
     * older than the lookback, so a rescue reads as a clean insulin-free meal.
     * What it actually contains is the liver's counter-regulatory dump credited
     * to the tablets.
     *
     * MEASURED, and the physical ceiling is what exposed it: on this corpus 29 of
     * 31 dextrose notes are taken below 4.5 mmol (many below 2). With them in,
     * the estimate reads **0.42 mmol/g — ABOVE the 0.371 ceiling for pure glucose
     * at zero clearance** (1 g = 5.551 mmol into ~15 L ECF). A result above the
     * ceiling is not a fast-carb finding, it is proof the window contains glucose
     * the food did not supply.
     *
     * [carbSensitivity] has carried a `rescue` flag for exactly this reason since
     * it was written; this estimator was built without one, which is how a
     * provenance fix that correctly admitted 31 exact-gram episodes immediately
     * produced an impossible number.
     */
    isRescue: (MealObservation) -> Boolean = { false },
): CarbSensitivity? {
    val ratios = corpus.mapNotNull { o ->
        // Whole-meal observations only. A component-decomposed row describes ONE
        // ingredient of a meal that is also present as a whole — pooling both
        // would count the same eating twice, with the same grams on both sides.
        if (o.component != null) return@mapNotNull null
        if (isRescue(o)) return@mapNotNull null
        // A censored episode was cut off while still rising: its peak, and so its
        // mmol/g, is a LOWER BOUND. Averaging bounds in with observations biases
        // the coefficient down — the exact direction that produced 0.10.
        if (!o.peakObserved) return@mapNotNull null
        // TRUE grams, when an external anchor knows them. Applied BEFORE the size
        // gate so a meal is judged small or large on what was really eaten.
        val q = gramQuality(o)
        if (q <= 0.0) return@mapNotNull null
        val grams = o.carbGrams * q
        if (grams < minCarbsFor(o) || o.peakRise <= 0.0) return@mapNotNull null
        // The window this meal was actually measured over: eating → its own peak.
        val endMs = o.onsetMs + (((o.onsetLagMin ?: 0.0) + o.ttpMin) * 60_000).toLong()
        val units = boluses
            .filter { it.tsMs in (o.onsetMs - insulinLookbackMin * 60_000)..endMs }
            .sumOf { it.units }
        if (units > 0.0) return@mapNotNull null
        // Basal pushed glucose DOWN for the whole window, so the food's true
        // appearance is larger than the observed rise by that much.
        val hours = ((endMs - o.onsetMs) / 3_600_000.0).coerceAtLeast(0.0)
        val corrected = o.peakRise - basalDriftMmolPerH * hours
        (corrected / grams).takeIf { it > 0.0 }
    }
    if (ratios.size < minMeals) return null
    val sorted = ratios.toDoubleArray().sortedArray()
    return CarbSensitivity(
        mmolPerGram = percentile(sorted, 50.0),
        q1 = percentile(sorted, 25.0),
        q3 = percentile(sorted, 75.0),
        n = ratios.size,
    )
}
