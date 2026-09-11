/**
 * ForecastTypes — the SHARED vocabulary of a forecast: what goes in
 * ([ForecastInputs], [PersonalModel]), what comes out ([ForecastResult],
 * [AppliedIsf], [ForecastHealth]), and the algorithm version tags.
 *
 * Split out of ForecastEngine.kt, and the legacy `fun forecast`
 * it was split from is gone since; these types are NOT legacy — both arms speak them, the hypo
 * alert reads them, and the ledger records the version tags. Same package as
 * before on purpose: no consumer changes an import, and the compiler checks it.
 *
 * Research observations only — never dosing advice.
 */
package com.diapilot.core.twin

import com.diapilot.core.analysis.IsfAggregate
import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.analysis.TodBucket
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint

/** Everything knowable at forecast time. Foods must be knownAtMs-honest. */
data class ForecastInputs(
    val nowMs: Long,
    val anchorTsMs: Long,
    val anchorMmol: Double,
    val boluses: List<BolusPoint>,
    val foods: List<ActiveFood>,
    /** Minute stream tail for momentum; empty = momentum off. */
    val minutePoints: List<GlucosePoint> = emptyList(),
    /** Trustworthy 5-min grid slope (mmol/min) at the anchor. When present,
     *  the fitted momentum velocity is reconciled to it — the 1-min stream
     *  over-steepens during rapid change (see [reconcileMomentumToGrid]). */
    val gridVelMmolPerMin: Double? = null,
    /** For regime classification (backward-looking). */
    val mealOnsetsMs: List<Long> = emptyList(),
    val activityWindows: List<com.diapilot.core.analysis.ActivityWindow> = emptyList(),
    val hour: Int,
    /** Extra kernel multiplier applied AFTER the diurnal scale — the
     *  autosens dial (validation A/B passes its trailing ratio here). */
    val kernelScale: Double = 1.0,
    /** Recent calibrated readings up to and including the anchor, for the
     *  plausibility gate (fast-fall / gap / excursion need a series). Empty
     *  disables the gate regardless of [plausibilityGate]. */
    val anchorWindow: List<GlucosePoint> = emptyList(),
    /** SHADOW TOGGLE (default off ⇒ byte-identical), reachable from the Settings
     *  screen's experimental section since this option existed — until then it had a setter with no
     *  caller, so the default was the only value it could ever take. When on, an
     *  implausible
     *  anchor (≤0, below the 2.0 floor, emerging low from a gap, or a member of
     *  an artifact excursion) is de-trusted — health drops out of TRUSTED and
     *  [ForecastResult.sensorSuspect] carries the reason. Plausible anchors are
     *  untouched even when this is on. */
    val plausibilityGate: Boolean = false,
)

/** The trained personal parameters, versioned. */
data class PersonalModel(
    val kernel: List<KernelPoint>,
    val byTod: Map<TodBucket, IsfAggregate> = emptyMap(),
    val corridors: RegimeCorridors? = null,
    val globalCorridor: Corridor,
    val kernelEpisodes: Int = 0,
    /** Kish effective count after recency/era weighting — the honest size. */
    val effectiveEpisodes: Double = kernelEpisodes.toDouble(),
    val version: String = "twin-1",
)

enum class ForecastHealth { TRUSTED, LIMITED, STALE, INSUFFICIENT_DATA }

/**
 * Version of the forecast ALGORITHM (not the app): bump on any change to the
 * simulator, kernel fitting, ISF, food model, momentum, corridor or input
 * filtering — the prospective ledger compares quality across these versions.
 * History: v1 = symmetric unconditional corridor; v2 = conditional asymmetric
 * signed-quantile corridor + momentum trend source; v3 = borrowed component
 * kinetics (first-time dishes inherit ttp from measured component twins);
 * v4 = momentum velocity reconciled to the frozen grid slope (the 1-min
 * stream retroactively over-steepens during rapid change); v5 = food model on
 * the meter-calibrated scale (dish curves + profile rises + autosens inputs)
 * and insulin-adjusted profile rises (a dish eaten on IOB no longer looks
 * weaker than it is).
 */
// v6: FOOD ONSET LAG. foodDelta started rising at tau=0 — food does not. A
// note-driven meal now waits FOOD_ONSET_LAG_MIN (16, the measured median of
// note→rise-onset, n=62) before absorbing; a DETECTOR meal gets none, its onset
// already IS the rise. Removes a systematic undershoot: bias +0.64 → +0.35 mmol
// on the clean era (905 anchors), mae 1.83 → 1.82.
// v7: SPENT IS MONOTONE. On a measured snapshot the kernel deepened to
// -1.757 at tau=165 and rebounded to -1.409 by 200 — the forecast had insulin
// RAISING glucose by 0.348 mmol/U in hour three. Iob already refused to believe
// that; the forecast does now too. It also opened with six POSITIVE bins
// (tau=0..25, max +0.076), which are clamped: a fresh bolus cannot raise sugar.
// AMPLITUDE IS UNCHANGED (appliedIsf stays 1.4829). Applied BEFORE the ISF
// rescale: scaling and a running minimum commute, so this re-shapes against a
// FIXED plateau — mass moves out of the first hour into the tail, total per-unit
// drop identical. Applying it AFTER would make the deepest bin the plateau
// (+18.5%); that bundle was built, measured WORSE at every horizon, and dropped.
// Measured paired on insulin-opened segments (`segmentsab`, 20 segments, same
// segments both arms): mae improves at every horizon — 0.61->0.56, 1.41->1.27,
// 2.53->2.21, 1.56->1.22, 1.68->1.18, 2.20->2.12 — and bias improves at five of
// six, h=60 +1.19->+0.96 (the front-loaded over-prediction) and h=180
// -0.86->-0.20 (the under-predicted tail). h=90 is the exception, -0.49->-0.63.
// Replayed alert rule on the CALIBRATED scale, food era: false alarms 4.35 ->
// 4.03/day at unchanged recall (8/27) and unchanged 25 min lead. NOTE this is
// the kernel reshape in isolation: a real v7 build also re-derives carbSens,
// the regime corridors and activityDrop FROM the new kernel, and none of those
// were in either arm. Re-run against a v7 snapshot once one exists.
// n IS SMALL and the horizons are NESTED subsets of one 20-segment set — read
// the sign, not the magnitude, and treat "improves at every horizon" as one
// observation restated, not six.
// Rejected on the way here, measured: a flat amplitude raise to what the tagged
// corrections read (2.7-2.9).
// v8: THE AMPLITUDE. appliedIsf rose by roughly two thirds, and the unconfirmed
// corpus is out of the amplitude entirely.
// Four sources say what one unit does to this body and only one said "weak":
// rule-1500, rule-1800 and the trusted-correction centre roughly agreed, while the
// corpus read far lower. 97.5% of the corpus predates food logging, where "no logged carbs in the
// window" passes VACUOUSLY because nothing was logged at all - meal boluses
// entered as corrections and the drop came out too small. It now supplies SHAPE
// only; the amplitude is trusted corrections against a POPULATION PRIOR from
// this body's own TDD. A new user gets the prior on day one.
// SAY IT PLAINLY: the trusted blend is 14%, so the applied value is THE TDD PRIOR WITH A
// SMALL PERSONAL CORRECTION, not "measured directly". It becomes a
// measurement as the era grows.
// GATES: the disqualifying and measurement windows are ALIGNED (events were
// checked to +150 while bgEnd was the median over +120..+180, so anything at
// +160 moved the measurement without being allowed to reject the episode), and
// the boundary is placed where the v7 monotone kernel actually ends (~145-150;
// 75% done at 90, 94% at 120). Activity DOWN-WEIGHTS instead of rejecting.
// Confirmed corrections passing: 1 of 8 -> 4 of 8.
// WEIGHTS ARE A RULE STATED IN ADVANCE, applied to every axis: dose by
// inverse variance ((dose/2.5)^2, since ISF = drop/dose), activity by 0.3.
// Robustness checked as required: dropping the two heaviest trusted episodes
// moves the answer by 0.05.
// NOT tuned by forecast error - anchors followed by unlogged food punish a
// stronger kernel, so pooled sweeps run off toward zero insulin and their argmin
// sits on the edge of any grid. Acceptance: agreement of the trusted sources,
// plus the alert-path grid (no barrier anywhere in 1.9-2.86: recall flat, false
// alarms +15%, lead 25 -> 31 min).
// v9: THE FOOD AMPLITUDE. carbSens 0.1026 -> 0.23 mmol per RECORDED gram, by an
// explicit override, because the learned estimator under-reads INDEPENDENTLY of
// the grams: on the very same recorded grams it answers 0.1026 where seven
// insulin-free repeats measure 0.1515. This is the LIVE tag and not only the
// shadow, because the food line grows x2.4 for every consumer — screen, watch,
// widget, hypo alert and the ledger's own base arm.
// ALERT-SAFETY MEASURED before the change, not after: alarms byte-identical
// (77, recall 43.8%, lead 32) with ZERO missed and ZERO delayed across 32 real
// hypo onsets — measured AT THE SHIPPED 0.23, not interpolated to it. The null was
// probed rather than trusted: the axis does move at larger values (0.30 -> 76
// alerts, 0.60 -> 75 and lead 32->30), so 0.23 sits just below the resolution of
// one alarm, and 0 missed held at every probe up to x5.
// See CarbsCalibration.CARB_SENS_OVERRIDE_DEFAULT for how the default is chosen.
// v10: CALIBRATED ACTIVITY WINDOWS reach the model. The window builder in
// TwinCache moved from the legacy detector (steps ≥40/min, HR ≥1.25×median ≈95)
// to the calibrated one (steps ≥80/≥20 ∪ HR ≥120/≥20 ∪ notes) that had sat
// reviewed-but-uncalled in core for a while. It changes WHAT IS LEARNED:
// the ISF episode detector no longer down-weights corrections sitting in phantom
// bouts, and the food corpus's soft contamination is lifted off 15 of 71
// episodes — which in v9 reaches the LIVE amplitude (the corpus is the food
// amplitude source since fp19), so the base tag bumps, not only the shadow.
// Measured before the bump (`ActWinStudy`/`ActChartDump`, both harness studies
// deleted with the legacy engine): 36 of 47 food-era
// windows were PHANTOM under the old bar; the calibrated⊆legacy relation was
// re-checked on fresh data rather than assumed (Activity.detectActivityWindows
// warns it is not a code invariant).
const val FORECAST_ALGO_VERSION = "forecast-v15-rescue-throughput-aggregate"

/**
 * Generation tag of the food model. Born as the Phase-1 fingerprint SHADOW —
 * hence the name and the `fp*` entries below — and it no longer does that job.
 *
 * ⚠ IT IS NOT AN A/B TAG ANY MORE (audited after the fact). The prospective
 * candidate ledger was deleted, and this constant now has exactly
 * two live readers:
 *
 *  - `MainState.projectionIdentity` — the CACHE KEY of the history cards, and
 *  - `DiagnosticsExport` — one field in the dump.
 *
 * Nothing pairs it against anything, so «promote when it wins on MAE/coverage»
 * — which this comment claimed for a month after the mechanism was gone —
 * describes no code.
 *
 * WHY IT STILL MUST BE BUMPED, for the surviving reason: a stored history card
 * carries the tag it was computed under. Without a bump the screen keeps
 * serving cards made by the PREVIOUS food model — a «pastry at +604» failure
 * seen directly. So bump on any material change to the corpus or the food model, and
 * note what changed. There is no longer a cost to bumping often: there is no
 * paired verdict left to reset.
 */
// The log below is PROVENANCE, not decoration: a `forecast_runs` row or a
// stored card can only be attributed to a model if the tag it carries is
// explained somewhere. v18/v19/v26 exist only here; earlier versions are
// explained in the project's own change history.
// fp2: note-anchored deconvolution + censoring + two-component absorption.
// fp3: SESSION-anchored — the corpus learns a meal (450b7d1) and the query asks
//   with the meal's composition (grams stay per-note). Also the honest reset:
//   fp2 shipped and the corpus then changed underneath it FOUR times
//   (3f45130, 8c7f412, 5cc689a, 450b7d1) with no bump, exactly what the note
//   above forbids — so «fp2» in the ledger is an average of five models and
//   can't answer note-vs-label. fp3 starts one identifiable generation.
// fp4: rides v6's lag (the shadow reshapes v6's foods), plus the dictionary
// repairs — nuts/yogurt/kefir concepts and user overrides now reach fingerprints.
// fp5: censored episodes rejoin the pool (Kaplan-Meier). fp4 kept only the meals
// that peaked before the next meal truncated them — which selected FOR the fast
// ones — so its dictionary is a different model, not a noisier fp5: bread 60→120,
// dextrose 52→60, smoothie 45→60. Blending the two under one tag would answer no
// question at all.
// fp6: an all-censored pool stops falling to the speed prior. fp5 only let KM
// run when SOME donor saw a peak, so the pools censoring hurts most — the long
// meals — still answered with the FAST prior: the daily breakfast said 40 while
// all three of its episodes were still rising at 90-120. Now it says 120 and
// calls itself CENSORED_BOUND (a floor from real episodes, not a measurement).
// A floor below the prior is discarded — «at least 45» does not argue with 60.
// fp7: the corpus can now isolate ingredients — a mixed meal with the known
// components subtracted yields a per-ingredient observation (ComponentDeconv).
// Fires ZERO times on today's data (no «known ingredient + one unknown» meal
// exists yet), so fp7 == fp6 for now; the tag is set AHEAD of the first firing
// so the shadow doesn't change identity silently the day a component isolates.
// fp8: peak-censoring fixed. A plateaued meal (rise stopped, no insulin) was
// marked «still rising, censored» whenever its flat top's argmax landed on the
// last checkpoint — 42% of the corpus censored. The honest test is the SLOPE at
// the cut: plateau/decline = peak seen. Corpus censoring 42%→27%; smoothie's
// false-censored plateaus rejoin the observed pool, breakfast becomes a real
// POOLED 120 instead of a censored floor.
// fp9: activity is a SOFT confounder now — down-weight, not discard. Dropping
// every meal eaten during activity lost 2 of 6 daily breakfasts and, corpus-
// wide, HALF the data; a meal eaten while active is still a real observation,
// activity just shifted it (confidence 0.35, ~3 such ≈ 1 clean). Corpus 33→57
// (capture 42%→73%), estimates stable (breakfast 120, smoothie 60 unchanged).
// fp10: FORWARD carryover. A meal whose neighbour before it is a KNOWN (POOLED)
// meal still absorbing gets that neighbour's contribution subtracted from its
// window — the same delta trick as insulin (dishResponseCurve.foodCarryover),
// bootstrapped over passes (buildKnown → rebuild curves). One crackers episode,
// which read +9 mmol («crackers + breakfast tail»), becomes ~+4 alone. Estimates
// stable (smoothie 60, dextrose 75). The BACKWARD step (extend the window
// THROUGH a known snack) is deferred — it regressed smoothie 60→120 by reaching
// into unlogged meals; needs an unlogged-meal guard.
// fp11: ttp = time the curve first REACHES its peak, not the argmax. Chasing why
// the (deferred) backward pass drifted smoothie to 120 exposed the argmax as a
// weak estimator generally: on any plateau it clings to whatever late point
// noise made highest. Fixed forward too — dextrose 75→60, smoothie 60→45 (both
// fast carbs, argmax had over-stated them); genuinely-late meals unchanged
// (breakfast 120, beer 90). Also refactors the carryover iteration into
// iteratedSessionCurves + adds the (off-by-default) extendThroughKnown plumbing.
// fp12: amplitude-plausibility down-weight. A meal cannot raise glucose >0.5
// mmol/g — above that is contamination, chiefly rescue dextrose over a hypo
// (4 g reading +4.9 mmol = 1.2 mmol/g holds the insulin over-shoot the kernel
// under-counted). Such episodes kept but heavily down-weighted (0.2). Dextrose's
// gross-contaminated slow episodes stop dragging it: pooled-but-wrong ttp 60 →
// fast prior 40 (its clean daytime episodes are ~30; the night-rescue ones stay
// genuinely slow, so the spread is honestly wide → prior). Other concepts
// unchanged.
// fp13: a pooling KEY must carry REAL carbs. Without an explicit composition the
// estimate is split across every named component, so a ~0-carb concept (cucumber,
// leafy veg, egg) picked up a few grams and became a carbDriver — "buckwheat with
// cucumber" then pooled with pizza/potato via the shared «vegetable», and the
// breakfast keyed on egg+hummus+salad instead of hummus+bread. Now only carb-
// DENSE concepts (≥10 g/100g) are keys, with a fallback for genuinely low-carb
// meals (a salad keeps its veg key). Traced live: buckwheat's key buckwheat+vegetable
// → buckwheat; breakfast egg+hummus+salad → hummus+bread.
// fp14: NOT a food-model change — the base moved under it. v7's monotone kernel
// is an INPUT to the deconvolution (`deconvolvedMealObservations` recovers food
// as observed dBG minus the kernel's effect), so every recovered episode has a
// DIFFERENT insulin contribution added back: SHALLOWER over tau=30..150 (by up
// to 0.21 mmol/U) and deeper only past ~170. Measured on `funnel`, the corpus
// genuinely moves — beer flips SPEED_PRIOR ttp 40 -> POOLED ttp 90, chips and
// pastry swap censoring verdicts, dextrose gains an episode. That is a
// user-visible forecast change for those dishes, and the reason for the bump. Bumped
// so the ledger does not blend a corpus built on the rebounded kernel with one
// built on the monotone kernel under a single tag — the exact failure the note
// above forbids, and the one that made «fp2» an average of five models.
// fp15: the base moved again - v8 raises the kernel amplitude ~1.7x, and the
// deconvolution recovers food as observed dBG MINUS the kernel's effect, so
// every recovered episode now has far more insulin added back. NOTE FOR THE NEXT
// TASK: the per-concept onset lags (12/22/23/30/40 min) were measured on the
// weak kernel and CANNOT be reused - re-measure them here first.
// fp16: THE POOLING ESTIMATOR. Every CENTRE the concept layer pools - onset,
// tail fraction, per-gram amplitude, and Kaplan-Meier's no-censoring path - moves
// from a weighted MEDIAN to a weighted TRIMMED MEAN. The weight is a product of
// four factors (similarity x recency x nEpisodes x confidence), so weights span
// orders of magnitude by construction and a weighted median answers with
// whichever group the cumulative sum crosses half inside. Same failure that
// produced an "87th-percentile median" in the ISF work; same fix.
// KM's two FLOOR paths (all-censored, and the never-halved fallback) keep the
// median deliberately: the floor argument is a property OF the median - every
// true ttp >= its own bound, so median(truth) >= median(bounds) - and a trimmed
// mean does not have it. The IQR gate stays a quantile for the same class of
// reason: it measures a spread.
// MEASURED FIRST on the real weight vectors, which
// narrowed the claim: the two weighted estimators disagree by a median 19% of the
// flat median at n=2 and 0-9% at n>=6, so this is mostly a SMALL-POOL defect - a
// weighted median over two donors returns one of them. But NOT only: beer's
// onset pool (n=10) is the single largest disagreement in the audit, 37.3 -> 29.5,
// 43% of its flat median.
// WHAT MOVED, funnel view, snapshot kernel: ttp on exactly ONE concept,
// cereal_puff 120 -> 103. Onsets on eleven, biggest chocolate 73 -> 52,
// pancake+nutella 64 -> 55, pancake+sugar 30 -> 36. perGram - an AMPLITUDE that
// multiplies grams into mmol - on eight of twenty-two, cereal_puff +29%, pancake
// -8%, beer -4%. The corpus itself shifts too (`buildKnown` consumes these), so
// the tag bump is earned.
// BLAST RADIUS, checked rather than assumed: `predictKinetics` is called by
// `cobFoods` (the COB number on screen), `fingerprintShapedFoods` (the SHADOW
// forecast), `buildKnown` (the corpus) and `conceptProfiles` (UI cards). The LIVE
// forecast line does not call it - `activeFoods` never does - and the backtest /
// walk-forward block is byte-identical across this change.
// fp17: PER-CONCEPT ONSET, GATED BY TIGHTNESS. fingerprintShapedFoods copied
// rise/ttp/tail but not onsetLagMin, so every concept still started absorbing at
// the flat 16 min while the corpus knew hummus starts at ~40 and chips at ~11.
// Now applied - but ONLY where the concept's own onset pool is TIGHT (IQR/median
// <= 0.35, >= 4 donors): the audit found the spread is a MISSING INPUT for the
// things drunk/eaten over a variable stretch (beer 1.08, dextrose 1.02), not
// noise, so those keep the flat prior. On this corpus the gate opens for hummus,
// bread and chips and stays shut for beer, dextrose, smoothie, ice_cream. Onset
// is applied INDEPENDENTLY of the ttp-shaping decision (bread's onset is tight
// while its ttp is a prior) and never onto an EXACT_CURVE food (its measured
// curve already encodes the onset). Error direction: a later start draws the
// post-meal dip DEEPER - conservative for the hypo alert - but shows «nothing
// happening» for longer after eating.
// BLAST RADIUS is the SHADOW/displayed line and What-if only (recordAs=="main");
// the alert, watch, widget and ledger-base arms never call fingerprintShapedFoods
// and are untouched. The negative TIMING verdict from the shape experiment does
// NOT transfer: it was measured on six dextrose/beer segments of seven - exactly
// the concepts this gate EXCLUDES - and there were no tight-food segments at all.
// Also new and unshipped: `applyShape=false` selects an amplitude-only reshape
// (concept size, base timing) for the shape experiment's third arm.
// fp18 — CONCEPT `spelt` (spelt/farro/bulgur/pearl barley). Not a behaviour change
// in any formula: it changes WHAT THE CORPUS IS KEYED ON. Boiled whole-grain
// porridges matched no alias, so a meal carrying most of its grams in spelt had
// NO carb driver for those grams — every concept-keyed reader saw a few grams of onion.
// The neighbour-overlap audit is what surfaced it: episodes falsely recorded as
// CLEAN while an earlier meal was still absorbing fell 6 -> 1 on the food era.
// Corpus COMPOSITION moved (new concept keys), so the generations must not blend
// — this bump resets the paired A/B deliberately, orchestrator's call.
// Blast radius measured before the bump: 2 meals in all history;
// the path is display + What-if only — but read that as shape AND amplitude, not
// timing alone: those grams become a carb DRIVER, so a per-component corpus row
// can carry them and move the learned per-gram rise. Grams themselves unmoved
// (both notes have an explicit carbs tag, and `Carbs.kt` prefers the carbs tag).
//
// SAME BUMP, second change — the alias matcher, found by generalising a review
// finding instead of patching the one alias it named. A single-word alias matched
// as a word PREFIX with no bound on the leftover, so it swallowed unrelated words:
// a "honey" stub took "honey pastries" for `sugar`, a "legume" stub took "Nutella"
// for `legume` (opposite kinetics), a "spelt" stub would have taken an unrelated
// "loaf of bread" word — and that last
// one reaches a GRAMS number in the composer. The prefix rule exists for Russian
// declension, so the leftover is now bounded to an inflection (≤3 chars);
// truncated stub aliases are reached by the 5-char stem step instead.
// Measured over every distinct component name in history: ONE row moves.
// A honey-pastry dish moves sugar → pastry (new explicit pastry-family aliases).
//
// CONSEQUENCE, stated because three earlier reports quote the old key: a
// honey-cake dish pools as **pastry+chocolate**, not sugar+chocolate. Its
// own recovered numbers are unchanged (the key drives pooling, not the curve),
// but its donor pool roughly doubles (effN 2.4 → 4.7) and the concept's tail
// verdict is «no tail» at EVERY neighbour-shape assumption, where the sugar pool
// flipped to a tail on two of them.
// fp19: THE DETECTOR LEAVES THE FORECAST'S CALCULATION PATH (`noteAnchoredFood`,
// toggle, default OFF ⇒ live behaviour byte-identical). Food amplitude and shape
// come from the user's notes + the note-anchored deconvolution corpus; BOTH
// detector-derived branches go, not only the obvious one:
//   · `labelStats` profiles — read `meal_events.rise` directly;
//   · `dishCurves` — `dishResponseCurve(onsets = labeled meal ONSETS)`, so a
//     «measured curve» is measured around a DETECTOR onset and inherits its
//     fragmentation. Removing only the first leaves the detector in under another
//     name — the plan that named only `labelStats` would have missed this.
// Ladder: measured curve (predictPerGramRise + predictKinetics) → concept prior →
// grams × carbSens, implemented as `fingerprintShapedFoods` reshaping the grams
// base, so the fallback is continuous rather than three branches that can disagree.
// Evidence it is worth doing: the label profile for a triple "ice cream" entry
// reads 19.54 mmol where the note corpus measures 6.35 for the same
// 50 g — the detector counted a double portion as an inflated fragment.
//
// The base prefix moves v8 → v9 in the same breath, and that is a REPAIR, not
// cosmetics: the shadow has been riding v9's amplitude (carbSens 0.23) since
// 26c077b while still recording itself as «forecast-v8+…», so the ledger has been
// filing a v9-based shadow under a v8 label. Both changes reset the paired A/B —
// deliberately, since neither generation's stats describe the other.
// fp20: rides v10's calibrated activity windows. The note-anchored corpus is
// recovered with the food corpus's SOFT contamination, which is built from the
// activity windows, so lifting phantom bouts off 15 of 71 episodes re-weights
// the very corpus the shadow pools — a material change, bumped so the ledger
// does not blend a corpus deconvolved against phantom windows with one that is not.
//
// NOT bumped for a later markdown fix in `normalizeFoodName`, and the reason
// is a measurement rather than a judgement about size. The fix looked like a
// pool-key change — a wildcard "salad" key collapsing into plain "salad" — and was
// bumped to fp21 on that reasoning, then reverted when the effect was actually
// measured on a fresh data pull: `funnel` and the full default analysis are
// BYTE-IDENTICAL before and after. The fork never reached pooling, because
// `conceptFor` already resolved the wildcard salad key through its alias step
// (a ≤3-char inflectional suffix is allowed, and
// the 5-char stem step would have caught it regardless). Only the component
// INVENTORY moves, 80 keys → 79, and nothing downstream of the forecast reads it.
// A bump here would have reset a paired A/B opened hours earlier by fp20 in
// exchange for no measured model movement — the ledger's memory is the scarce
// thing, so an inert change does not get to spend it. Re-measure and bump if the
// fix ever does move a corpus.
// fp21: NINE CONCEPTS ADDED, and this IS a corpus change — the earlier judgement that
// the anchor work was inert was correct for the PRIOR CONVERSION and wrong once the
// concepts landed on top of it.
//
// The concept audit found eight components carrying 58 g of carbs with NO concept at all,
// invisible to every pool: they inflated a meal's total while contributing to no dish's
// amplitude. Adding `cold_soup`, `jam`, `cream_sweet`, `sour_cream`, `sausage`, `mushroom`,
// `fried_onion`, `gravy` moves those grams into `carbDrivers`, which is what
// `predictPerGramRise` pools over — so the shadow's amplitude for the affected dishes
// changes. `beer_nonalc` additionally SPLITS a meal out of `beer`'s pool: non-alcoholic
// beer carries two to three times the carbs and was previously filed under CHIPS, because
// its 25 g matched no concept and fell out of the drivers entirely.
//
// Inert, and NOT the reason for this bump: `priorInDishGramSpace` and `carbSensitivity`'s
// convert-back are both no-ops until a row carries `carbs_source = 'anchor'`, and the phone
// has none. They ride along with the bump rather than earning it.
// fp22: ONE SENSOR SERIES PER DAY FOR THE NOTE-ANCHORED CORPUS.
//
// A handful of food-era days are the only ones where two transports write on DIFFERENT SCALES
// (`xdrip_sgv` and `libre_ble`, ~0.58 mmol apart, interleaved at ~20 s — ~3.1x the step noise
// of a single-source day). Two CONCURRENT transports also occur on a couple of other days; the
// scale gap is what distinguishes these four days. The offset is LEVEL-DEPENDENT by bucket median
// (3–5 mmol +0.41 · 9–11 +1.05), so large meals were distorted more than small ones. On
// three of those days NOT ONE meal of 22 reached the corpus, not even censored.
//
// Measured by intervention rather than correlation: preferring the primary series per day
// returns 20 of 51 invisible meals, observed peaks
// 49 → 63, losing none. A per-window variant is strictly worse — 18 returned and 3 observed
// peaks LOST while their meals survived.
//
// The corpus moves materially, so the pools move — on the DAY rule, which is what ships:
//   cereal_puff +73.3% · ice_cream −11.1% · bread −5.2% · hummus −5.4% · dextrose +1.5%
//   smoothie +1.2% · beer ±0.0% (its ttp moves for `dextrose` only, 53 → 45 min)
// An earlier revision of this comment quoted `beer` +19.6% / `ice_cream` −17.0% and omitted
// `cereal_puff`: those are the per-WINDOW arm's numbers, which is the arm we rejected. The
// study compared against the wrong arm — rule 7 with a credible-looking output.
//
// THE CORPUS REACHES THE HYPO ALERT, so record it here rather than claiming insulation:
// `Forecaster.kt` takes `corpus = fingerprintCorpus` whenever `noteAnchoredFood` is set, and
// that setting DEFAULTS ON and is unset in every pulled prefs file — so `hypo_alert` forecasts
// through this corpus like the screen does. Effect bounded by amplitude, not by a walk-forward
// (see the fp22 JOURNAL entry): 97 of 126 sessions move their live per-gram, 34 up / 63 down,
// mostly within ±5%, with one +31.5% outlier (a cracker-snack concept) driven by a returned rise of
// 0.770 mmol/g — impossible in the recorded gram space, so the outlier is a bad row recovered,
// not a gain. The gate is not passed; it was lifted by the user.
//
// Bumped because `forecast_runs` must not blend a corpus deconvolved off an interleaved
// sawtooth with one deconvolved off a single series.
// v12: PHYSIO consumes a measured correction-derived insulin CDF. Old paired
// runs were produced with a forced fixed triangle and are not comparable.
// v13: the curve PHYSIO integrates is a different object again. It is now
// smoothed — landmarks read off the aggregate, the shape between them rebuilt
// by InsulinShapeV1 rather than served as the PAVA staircase — and an
// out-of-domain reading is coerced and named instead of discarded, so runs that
// previously fell back to the prior in silence now carry a measured curve.
// v14: the amplitude corpus moved from PEAK height to the TOTAL rise
// (peak + observed tail). carbSensPerGram has always been consumed as a total —
// foodDelta multiplies it by fractions that integrate to one — while the corpus
// term supplied a peak, so food was learned in one unit and applied in another.
// Episodes whose tail was never observed leave the amplitude estimate rather
// than counting as tail-free. Measured: +10% beer, +11% smoothie, +33% ice
// cream, +49% the long breakfast; corpus 98 -> 64 episodes.
// v17 — WAVE 2. Not bumped when it landed, which was a miss: the
// hook only watches FoodCurve/FingerprintKinetics/TwinCache/FoodSources and this
// change lives in HybridForecastEngine plus new files, so nothing stopped it.
// The rule is about MATERIAL change to the corpus or the food model, and this is
// squarely that:
//   · a dish's own measured timing now outranks the structural mixture at n>=3,
//     which had never reached this arm at all (physioMacroShape was dead code);
//   · the pool is keyed by DishIdentityV1 and fed from the DECONVOLUTION corpus
//     rather than dishCurves, which yielded one curve out of 234 labelled meals;
//   · 128 notes carry accepted structure, so the same note now parses to a
//     different shape than it did yesterday.
// Live effect: smoothie 5/55/158 -> 15/45/73, beer -> 10/75/119.
// The bump RESETS the paired A/B on purpose — the prospective test only means
// something if it does not blend these generations with the previous ones.
// v18 — the fitted fat->peak slope reaches the UNKNOWN-dish branch.
// It had lived only in physioMacroShape, which since v17 runs when a learned
// pool wins, i.e. for the two dishes that already have their own curve; an
// unfamiliar dish took physioFeatureShapes, where fat moved the peak by about
// 0.9 min per 10 g. Now +17.0 min per 10 g (OLS over 48 episodes, carbs held as
// control, bootstrap +8.4..+25.5, positive in 600 of 600 draws), and the shared
// slope is suppressed for dishes that have their own measured curve so the two
// cannot double count.
// v19 — CALORIC gastric emptying replaces the fitted fat slope.
// The carbohydrate queue is now limited by the meal's CALORIES: 120 kcal/h,
// which IS the shipped 30 g/h restated (30 x 4), so a pure-carbohydrate meal is
// untouched to the digit while fat and protein now occupy the same pipe.
// Measured against observed time-to-peak over 48 episodes, by rank because the
// corpus reads a level and the model an appearance curve:
//   gram queue, no slope      +0.43
//   gram queue + fitted 17    +0.48   <- v18, shipped for four hours
//   CALORIC queue, no slope   +0.52
//   caloric queue + slope     +0.53
// The queue alone beats the coefficient it replaces, so v18's fat slope is
// retired: one physiological rate instead of a fitted number, and it corrects
// the tail as well as the peak. MIXED also stops being slower than SOLID.
// v26: the fat->peak slope retired to 0 and the pre-queue
// fat/protein priors dropped from the shape's delay and tail on the caloric
// arm. Both billed the fat the caloric queue already meters. Measured over 157
// logged meals: 60-minute bias +1.52 -> +1.05 mmol, 120-minute +2.51 -> +1.80.
// A corpus deconvolved before this tag was built under a slower food model.
// v27 (F-05): two material changes to what the food model sees.
// (1) The learned dish pool is finally FOUND for accepted dishes — the lookup
// went by ratio key while pools were keyed dish:<id>, so 5 of the 6 usable
// pools (beer, smoothie, energy drink, ice cream, breakfast) never reached the forecast.
// (2) The parse generation moved llm-structured-v3 -> v4 (user-supplied components
// consulted, guesses named) and the canonical structures were recomputed by
// v4 consensus (beer 0.45->1.0 fast carbs, smoothie 0.8->0.96, biscotti 0.35->1.0).
// Ledger runs across this boundary compare different food models.
// (A-19): the boundary-bound test now also censors a peak REACHED
// on a steep climb, not only one the late phase proves kept rising. On the
// corpus that moves 12 more episodes out of the timing corpus (38 -> 50) and
// removes two pools that consisted entirely of lower bounds — breakfast and
// beer+chips. No surviving pool's peak moves, which is what a correct
// censoring gate should look like. The CORPUS changed, so the tag moves.
// Later: refuses a fast fraction above 0.2 on a dish
// carrying more than 15 g of fat, AT READ TIME — so every note already stored is
// re-interpreted, not just new ones. That changes what the corpus MEANS, which
// is the case this tag exists for. Measured on 24 held-out episodes: MAE@60
// 1.047 -> 0.693 with @120 and @180 unchanged. See JOURNAL M-105.
const val FORECAST_ALGO_VERSION_FP_SHADOW =
    "forecast-v27+measured-insulin-cdf+queue-carries-the-fat+f05-dish-pools-found+" +
        "llm-structured-v4-canon+a19-approach-censoring+f10-level-crossing-template+" +
        "fat-is-not-fast-carb+dish-curves-deleted"

/**
 * The sensitivity a forecast ACTUALLY ran on, read off the very arrays it used.
 * Reported rather than recomputed for a reason: every consumer that derived its
 * own lookalike has drifted from the real one. The autosens sentence on the main
 * screen was computed from UNCALIBRATED readings up to `now` and without the
 * `nAnchors >= 6` gate, while the forecast applied a dial computed on the
 * calibrated scale up to the ANCHOR — so the app could announce a sensitivity it
 * had not used. Show this; do not re-derive it.
 *
 * [baseMmolPerU] is the kernel plateau (≈ full ISF, see InsulinKernel), before any
 * scaling; the product of the two factors with it is [effectiveMmolPerU].
 */
data class AppliedIsf(
    val baseMmolPerU: Double,
    /** Diurnal factor for this forecast's hour — 1.0 when the hour is unlearned. */
    val todFactor: Double,
    /** Autosens dial, 1.0 when it did not earn the right to speak. */
    val autosensFactor: Double,
    val effectiveMmolPerU: Double,
)

data class ForecastResult(
    val points: List<PredictedPoint>,
    val regime: Regime,
    val corridor: Corridor,
    val momentumUsed: Boolean,
    val health: ForecastHealth,
    /** Why the health is not TRUSTED, as data; the app renders the text. */
    val healthReasons: List<HealthReason>,
    val modelVersion: String,
    /** Autosens dial actually applied to the kernel this forecast — so a
     *  what-if increment can use the SAME effective sensitivity. */
    val kernelScale: Double = 1.0,
    /** The sensitivity this run applied, for display. Null when there is no
     *  kernel to read it from (INSUFFICIENT_DATA). */
    val appliedIsf: AppliedIsf? = null,
    /** Non-null when the plausibility gate de-trusted the anchor as a sensor
     *  artifact (only possible with [ForecastInputs.plausibilityGate] on). The
     *  hypo alert reads this to say «sensor implausible, verify» instead of
     *  screaming a factual SEVERE on a 0.5 mmol reading. Null = plausible. */
    val sensorSuspect: com.diapilot.core.analysis.SuspectReason? = null,
)

// Internal: the alert backtest applies the SAME trust thresholds.
internal const val STALE_ANCHOR_MS = 15L * 60_000
internal const val WIDE_CORRIDOR_60_MMOL = 3.5
internal const val THIN_EPISODES = 10
