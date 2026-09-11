package com.diapilot.core.hybrid

// THE Physio arm's engine — one constructor, deliberately.
//
// Every physio consumer builds its engine HERE. A new setting is one edit, and
// a missed call site is a compile error rather than a silent second model —
// which is the same reason `MeasurementStream`, the note-to-event builder and
// the person-model parser were each collapsed to one implementation.
//
// WHY, measured: the caloric queue landed at two call sites and
// missed six. The live forecast used it while What-if, closed-episode closure
// and two learners each built their own engine with the shipped defaults. Not
// only a display disagreement — closure read `tailEndMin` to decide when a meal
// stops being active, so a forecast saying 250 minutes while closure said 150
// fed truncated curves back into the corpus the forecast then learned from.
// (Two of those consumers no longer exist; the argument is about the shape of
// the mistake, not about them.)

/**
 * THE MACRO TIMING THE PHYSIO ARM SHIPS — one constant, same reason as the
 * engine constructor above.
 *
 * ⚠ READ THE VALUE, NOT THE STORY: `fatPeakMinPer10g` IS ZERO. All four
 * coefficients are zero. The paragraphs below explain how a fitted +17 min per
 * 10 g of fat came and went in a day; none of them describes what ships.
 *
 * FITTED, THEN RETIRED A DAY LATER. The +17 was honest work — OLS over 48
 * episodes with carbohydrate held as a control, bootstrap +8.4..+25.5, positive
 * in 600 of 600 draws — but it was fitted against the OBSERVED time to peak,
 * which is food minus insulin, and on a day when the gram queue was not
 * binding. With the caloric queue shipped, the two delay the same meal.
 *
 * Measured over 157 logged meals (stand `fatsweep`, all variants given the same
 * anchor, food and insulin), median bias actual-minus-predicted:
 *
 *   horizon           30      60      90     120     180
 *   with the slope  +0.28   +1.52   +1.80   +2.51   +2.52
 *   without         +0.18   +1.21   +1.67   +1.99   +2.17
 *   without + M-11  -0.04   +1.05   +1.39   +1.80   +1.95
 *
 * Better at every horizon, on every variant that drops it. The queue keeps the
 * physiology the slope was standing in for; the slope was the second bill.
 *
 * The mechanism stays in the code and returns the moment this is non-zero, so
 * re-fitting it against a corpus rebuilt under the queue is a one-line change
 * rather than an archaeology exercise. The other three coefficients stay at
 * zero because the corpus has no observable to fit them against.
 */
val PHYSIO_SHIPPED_MACRO_TIMING_V1 = MacroTimingParamsV1(
    "macro-timing-v5-queue-carries-the-fat",
    promoted = true,
    fatPeakMinPer10g = 0.0,
)

fun physioForecastEngine(
    person: HybridPersonModel,
    macroTiming: MacroTimingParamsV1 = PHYSIO_SHIPPED_MACRO_TIMING_V1,
    /**
     * Only for SWEEPS. The shipped policy is the engine's own default; a
     * study that wants a different sieving or rate says so here and nowhere
     * else.
     */
    appearance: CarbAppearancePolicyV1? = null,
    /** Sweep knob for M-11; the shipped value is DERIVED from `appearance`. */
    gastricMacroPrior: Boolean = true,
    /** Sweep knobs only; 1.0 each is what ships. See [HybridForecastEngine]. */
    macroGastricScale: Double = 1.0,
    macroTailScale: Double = 1.0,
    /** Sweep knob: 1.0 ships, 0.0 makes all three carb types identical. */
    carbSpread: Double? = null,
    /** Sweep knob: the time axis of the population carb triangles. */
    carbTimeScale: Double? = null,
    /** Sweep knob: scalar on `grams x CS`. */
    foodAmpScale: Double? = null,
    /** A/B only: `false` restores the earlier anchor-frozen background. */
    steppedBackgroundFeedback: Boolean = true,
    /** Sweep-only handles on terms that never had one. Neutral by default. */
    formStretchScale: Double = 1.0,
    formDelayScale: Double = 1.0,
    fiberScale: Double = 1.0,
    peakGastricShare: Double = 0.35,
    fastTimeScale: Double = 1.0,
    mediumTimeScale: Double = 1.0,
    slowTimeScale: Double = 1.0,
    macroShareScale: Double = 1.0,
    /** Length of the food tail, independent of its onset and peak. */
    foodTailScale: Double? = null,
    /** Sweep only. Null lets the model's own override speak, then what ships. */
    carbTriangles: CarbTrianglesV1? = null,
): HybridForecastEngine {
    // PRECEDENCE, and it only reads one way: an explicit argument is a SWEEP and
    // always wins; otherwise the model's own override; otherwise what ships.
    // Resolving it here rather than at fourteen call sites is the whole point of
    // putting the override on the model.
    val shipped = CarbAppearancePolicyV1.PHYSIO_SHIPPED
    val resolved = appearance ?: CarbAppearancePolicyV1(
        person.food.emptyingKcalPerHourOverride ?: shipped.emptyingKcalPerHour,
        person.food.carbSievingOverride ?: shipped.carbSieving,
    )
    return HybridForecastEngine(
        person,
        macroTiming,
        resolved,
        gastricMacroPrior,
        macroGastricScale,
        macroTailScale,
        carbSpread ?: person.food.carbSpreadOverride ?: 1.0,
        carbTimeScale ?: person.food.carbTimeScaleOverride ?: 1.0,
        foodAmpScale ?: person.food.foodAmpOverride ?: 1.0,
        steppedBackgroundFeedback,
        formStretchScale,
        formDelayScale,
        fiberScale,
        peakGastricShare,
        carbTriangles ?: person.food.carbTrianglesOverride ?: CarbTrianglesV1(),
        fastTimeScale,
        mediumTimeScale,
        slowTimeScale,
        macroShareScale,
        foodTailScale ?: person.food.foodTailScaleOverride ?: 1.0,
    )
}
