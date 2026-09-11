# DiaPilot Architecture — Branch and Wiring Map

This is **a map of what the code does now**, not of how it was designed. Each
branch is described by one rule: who writes the input → who computes → who
reads the output. If something has no reader, that is stated outright.

> **Sections 0–7 were re-captured against the actual tree.** The previous
> revision (marked "sections 0-7 checked and correct") went stale within two
> days and became the main source of broken references in the repository: an
> audit found roughly fifty dangling pointers here, including entire paths —
> `core/twin/Autosens.kt`, `core/twin/Deviation.kt`, `data/IsfEvidenceRuntime.kt`,
> `data/ClosedEpisodeRuntime.kt`, `analysis/ComponentSystem.kt`,
> `analysis/DishIdentityV1.kt`, `ui/ConceptsScreen.kt`. Every name below has
> been verified against the file's existence and size.
>
> **Sections after §8 are dated audit logs — they are HISTORICAL.** References
> in them were deliberately left unfixed: they describe the state as of their
> own point in time. Do not read them as a map.

Size (`src/main` only, no tests): `core` 24.7k lines / 107 files,
`app` 36.1k / 95, the `tools` test rigs 45.5k / 173 (research code, not part
of the production wiring — but `walkfwd` and `corpusparity` live there too,
and they are not one-off scripts).

---

## 0. End-to-end Flow

```
  sensor  ─┬─ BLE (LibreBleClient)          ─┐
           ├─ NFC (LibreNfcScanner + OOP2)   │
           ├─ xDrip broadcast (XdripBgReceiver)
           └─ xDrip HTTP (TreatmentsPollWorker)
                                             ↓
                               SqliteCollectorStore   ← THE ONLY store
                                             ↓
                         MeterCalCache / MinuteCalCache / MeasurementStream
                                             ↓
                                      TwinCache (corpus assembly)
                         ├─ deconvolution corpus (fingerprintCorpus)
                         └─ kernel/band for history deconvolution
                                             ↓
                                      PhysioRuntime → PhysioArtifactV1
                                             ↓
                           Forecaster → HybridShadow → HybridForecastEngine
                                             ↓
        ┌───────────┬────────────┬──────────────┬───────────┬─────────────┐
      screen      What-if     hypo alert       watch       widget       ledger
   (MainState)  (WhatIf)  (HypoAlertNotifier) (WatchServer) (BgWidget)
```

**One engine, with no fallback anymore.** The previous revision of this
document said the twin `core/twin/forecast` remained as a fallback path. It no
longer exists: the legacy engine was removed entirely, and `Forecaster`, when
physio is unavailable, **returns null and logs an ERROR** instead of drawing a
line from a different model. The reasoning: a line from another model looks
normal, while silence is noticeable.

What survives in `core/twin` is not the engine but **vocabulary and rules**:
`ForecastTypes.kt` (types + version tags), `HypoAlert.kt` / `HypoAlertLogic.kt`
(the alert rule — reads points, not the engine), `Momentum.kt`, `RegimeV1.kt`,
`RegimeCorridor.kt`, `Simulator.kt`, `ActivityEffect.kt`, `ForecastHealthV1.kt`.

---

## 1. Glucose Collection and Calibration

| what | where | status |
|---|---|---|
| BLE Libre 2 | `collect/LibreBleClient.kt` (520), `data/Libre2State.kt` | opt-in, live |
| NFC Libre | `nfc/LibreNfcScanner.kt`, `nfc/LibreOop2Bridge.kt`, `core/libre/LibreFram.kt` | live |
| xDrip broadcast | `collect/XdripBgReceiver.kt`, `core/collector/XdripBroadcast.kt` | live |
| xDrip HTTP | `collect/TreatmentsPollWorker.kt` (307) | live |
| process keep-alive | `collect/CollectorService.kt`, `collect/BootReceiver.kt` | live |
| storage | `data/SqliteCollectorStore.kt` (2123) | hub, largest file in `data` |

**Calibration is three different mechanisms, and they are easy to confuse:**

- `MeterCalCache` — the meter-to-sensor lens (`slope` / `intercept` / a
  decaying transient), from `core/analysis/MeterCal.kt`. This is what the
  offline rig must apply (M-123).
- `MinuteCalCache` — a bridge from the per-minute OOP2 stream to the primary
  one.
- `MeasurementStream` — the one-calibration rule: a measurement reads from ONE
  stream (`core/analysis/MeasurementStreamCore.kt`, 110). It does not apply to
  the live forecast anchor — that always takes the freshest reading from any
  channel.

**A floor and a ceiling on glucose.** `libre_nfc` once logged a value in mg/dL
into the mmol column by mistake. The trend read a huge negative rate and
extrapolated to a physically implausible low value. Now: readings outside
1.0…35.0 never leave the store (`SqliteCollectorStore`, three selectors), and
the forecast never drops below `HYBRID_FLOOR_MMOL` = 2.0 and **counts** how
many points hit the floor (`floorClampedPoints`) — hitting the floor is a
model failure, not a hypo.

> The section about `core/analysis/SensorPreference.kt` was dropped: the file
> no longer exists.

---

## 2. Insulin

The order is strict, and it is an IDENTIFIABILITY requirement, not a
preference: insulin first, on segments with no active meal, then meal against
fixed insulin.

| layer | file | role |
|---|---|---|
| per-segment timings | `core/physio/SegmentLandmarksV1.kt` (678) | start/peak/end from EVERY injection; `SegmentLandmarkReaderV1` is an object INSIDE this file, not a separate one |
| profile assembly | `data/InsulinProfileRuntime.kt` (243) | dose funnel → shape |
| one curve for everything | `core/physio/PersonalInsulinCurveV1.kt` (58) | the ONLY path to `person.insulin` |
| **ISF, a single door** | `core/physio/ManualInsulinParamsV1.kt` (591) + `data/ManualInsulinRuntime.kt` (178) | P1, hand-pinned |
| adaptive ISF | `core/physio/DailyBalanceIsfV1.kt` (152) + `data/AdaptiveIsfRuntime.kt` (243) | a daily balance, recomputed once a day |
| switch | `data/IsfSource.kt` (49) | manual ⇄ adaptive, default is MANUAL |
| episodes for fitting | `core/physio/FitEpisodeBuilderV1.kt` (200) | ONE episode definition shared by the offline rig and the on-device path |

**Three ISF doors were collapsed into one.** The applied value is read from
the `APPLIED to model` log line, NOT from the `configured` line — the latter
shows what sits on the settings screen, and the two can diverge.

**What was removed — don't go looking for it:** `IsfEvidenceRuntime`
(per-dose ISF measurement), `CorrectionVerdictRuntime` + the review card,
`ClosedEpisodeRuntime` + `ClosedCausalEpisodeV1` (the episode pipeline,
~1336 lines), `IsfDeviationRuntime`, `core/twin/Autosens.kt`. Together with
the review card, **the only door through which the correction corpus could
grow was closed** — see `docs/insulin-model.md` §3.

**The "population IOB curve" trap is CLOSED** (checked, then checked again):
`core/analysis/iobUnits` has zero call sites in `app/src/main` or
`core/src/main`. `iobFraction` is used instead, and every caller passes its
own `dia` / `peak` — a fitted SHAPE, not a population default. There is no
second answer hiding behind `?:`.

---

## 3. Meals

**Amplitude is one formula:** `grams × globalFactor × calibration`. The dish
dictionary was removed (M-128), learned curves were removed (M-129). The
starting value comes from body weight, `core/analysis/CarbSensitivityPriorV1.kt`.

⚠ **The weight-based CS calculation is written but NOT applied** — `baseCs`
takes a flat constant (the synthetic example person's 0.165 mmol/L per gram).
The mechanism exists; the door is closed.

**Timing is four layers, all keyed on composition, none on the dish name:**

1. `core/hybrid/CarbTrianglesV1.kt` (107) — population triangles
   (fast / medium / slow) that every dish is mixed from;
2. `core/analysis/FoodKineticsV2.kt` — macro shares, shape, fiber → mix
   weights;
3. `core/hybrid/CarbAppearancePolicyV1.kt` — the caloric queue and sieve: how
   many carbs can leave the stomach at all;
4. `core/hybrid/MealClusterKineticsV1.kt` — meals close together share one
   pipe.

| supporting piece | file | status |
|---|---|---|
| grams from text | `core/analysis/Carbs.kt`, `CarbsCalibration.kt` | live |
| gram provenance | `core/collector/CarbEvidence.kt` (286) | live |
| meal sessions | `core/analysis/MealSession.kt` | live |
| dish memory | `core/analysis/FoodMemory.kt` | history card |
| dish recognition | `core/analysis/DishRecognitionV1.kt`, `data/DishDialogRuntime.kt` (193) | gives WHAT, not HOW FAST |

**`FoodCurve`, `FingerprintKinetics`, `FoodConcepts` are NOT vestigial.** An
earlier pass flagged them as removal candidates, and that was wrong: they are
not an old forecast branch but the CORPUS infrastructure that
`PhysioAutoFitV1` fits on.

⚠ **An open, measured defect:** meal amplitude comes up short. A ×1.25
multiplier with a fixed ISF moves the bias from 4.53 to 1.21 at h=180;
stretching the triangles makes it worse. Suspects are `carbSieving` at 0.65
and `macroShare` at 0.48.

---

## 4. Basal and Activity

| what | where |
|---|---|
| basal | `HybridBasalEvent` + `person.basal`, decorated by `PhysioV1.decorateBasal` |
| activity detection | `core/analysis/Activity.kt` (heart rate from Health Connect) |
| exposure | `core/analysis/ActivityExposureV1.kt`, `core/twin/ActivityEffect.kt` (188) |
| source | `data/HealthConnectSync.kt` (heart rate + sleep from Zepp) |
| night | `core/analysis/ActivityNight.kt` — observational only |

---

## 5. Input: Composer, NFC Pen, History

| what | where | size |
|---|---|---|
| meal composer | `ui/AnnotationComposer.kt` | **3093** — the largest file in the project |
| structure acceptance | `ui/FoodStructureAcceptance.kt`, `core/analysis/FoodStructureAcceptanceV1.kt` | the "no auto-teach" boundary |
| NovoPen | `nfc/PenNfcScanner.kt` + `core/pen/*` (1132) | live |
| barcodes | `data/OpenFoodFacts.kt` | live |
| history | `LabelScreen.kt` (1986), `data/HistoryFoodProjectionCache.kt` | live |
| dish library | `ui/FoodLibrary.kt` (1389) | live |

---

## 6. Surfaces

| surface | file | fed by |
|---|---|---|
| main screen | `TodayScreen.kt` (1112) ← `MainState.kt` (1752) | `Forecaster` |
| chart | `ui/GlucoseChart.kt` (1735) | `MainState` |
| watch | `collect/WatchServer.kt` | `Forecaster` |
| widget | `widget/BgWidget.kt` (286) | `Forecaster` |
| lock screen | `collect/LockScreenOverlay.kt` | `GlucosePresentation` |
| companion app | `api/DiaForApi.kt`, `core/api/*` | event journal |
| companion server | `collect/CompanionSync.kt` | `Forecaster` |
| settings | `ui/SettingsScreen.kt` (1316) | `data/Settings.kt` |

**What-if and the main line now compute from the same model — this was a
fix.** The mismatch was spotted by eye before any test caught it:
`updateWhatIf(..., physio = false)` defaulted the physio-arm flag off, the
profile fell through to the LEGACY slot, and the screen picked up a different
kernel with a different ISF. `WhatIfArmTest` pins this; the `physio`
parameter no longer has a default value — that default was the defect.

---

## 7. Alerts

| alert | file | input |
|---|---|---|
| hypo (predictive) | `collect/HypoAlertNotifier.kt` + `core/twin/HypoAlert.kt` + `HypoAlertLogic.kt` | `Forecaster(recordAs="hypo_alert")` |
| rapid fall | `collect/RapidFallNotifier.kt` (72) + `core/analysis/RapidFall.kt` | per-minute stream |
| stream stalled | `collect/StreamStallNotifier.kt` (85) | observation |
| "looks like you ate" | `collect/MealNotifier.kt` (106) | `core/collector/MealDetect.kt` |

> The "glucose off-model" alert (`DeviationNotifier` + `core/twin/Deviation.kt`)
> was removed together with the legacy twin: it was a leftover of the twin and
> computed deviation from a model the app no longer ships.

**The hypo threshold of 3.9 mmol/L against the alert's own trigger range of
4.4–4.5 mmol/L is an open question**, logged as tech debt.

**Historical alert backtesting was also removed** (`hypoAlertBacktest`,
`HypoAlertReport`): it replayed the rule through `fun forecast` — that is,
through the removed engine. The rule itself (`findPredictedHypo`,
`findFallVelocity`, `decideHypoAlert`) is untouched: it reads points and
readings, not the engine.

---

## 8. Ledgers and Measurement

**This section was rewritten.** In one pass, more was removed from here than
remained.

| row | file | what and why |
|---|---|---|
| `forecast_runs` | `data/ForecastLedger.kt` | header of a saved forecast: anchor, model tag, and **`applied`** — the ISF and insulin curve actually used. Only `main` and `hypo_alert` |
| `forecast_points` | same file | six horizons of the displayed line, at most one run every 5 minutes |
| What-if | `data/HybridWhatIfLedger.kt` | what would have happened with a different dose |
| retention | `data/LedgerRetention.kt` | **works**: screen 60 days, alert 7, runs 60, training snapshots 30 revisions |

### What no longer exists

- **`forecast_scores`** — removed. It held nothing original: the actual value
  is the reading at the target time, the error is forecast minus actual, and
  hitting the band is a comparison against `lo`/`hi` from the neighboring row.
  It was a cache of 171,000 rows and 14 MB with a single reader. The actual
  value is now computed on the spot, **through the same calibration lens** —
  comparing a calibrated forecast against a raw actual value is a mistake
  that once forced a band conclusion to be retracted.
- **Prospective A/B testing** (`physio_parallel_runs`/`_scores`, 1.23M rows) —
  removed by decision. The code agrees: the only teacher that ever reaches
  production carries `MEASURED_BASE`, which by its own comment is "not a
  candidate awaiting a prospective verdict". The remaining 27 hypotheses were
  never shipped.
- **Watch and widget runs** — not written: they're short slices of the same
  forecast.
- **`physio_shadow_candidates`** (12,690 rows) and two `_v1` arrival logs.

### What the ledger does now

**Nothing for the model.** ISF is computed by `IsfEvidenceRuntime` directly
from glucose and injections; carb sensitivity by `GlobalCsRuntime` from notes
and readings. The ledger is a record of what the app **displayed**, and it
feeds:

1. the first frame after a cold start — instead of an empty chart;
2. a long press on the chart — two lines and a strip with the coefficients;
3. discipline #7 acceptance — whether a recomputation can reproduce what the
   phone actually drew.

Item 3 is the one thing walk-forward cannot do, and only for the historical
line: the model parameters from that period were not stored anywhere before
the `applied` column was added.

### Observation Heartbeat

`forecast_runs` with `consumer='hypo_alert'` is the only row that can answer
"did the model get it wrong, or was the app asleep." A replay cannot answer
this: it computes from the same readings, and those get backfilled. It was
deliberately NOT thinned out.

The `main` rows are not a heartbeat but a forecast header; calling them a
heartbeat was a holdover from an earlier framing and caused confusion.

## What the Audit Found Immediately

1. **`physioArm` in `HybridShadow` was a dead fork** — both call sites sent
   `true`. Removed.
2. **24 top-level declarations with zero references** out of 1151.
3. **6 composables that nothing renders**: `AnomalyDialog` (582 lines),
   `ConceptsDialog` (328), `CardTitleWithHelp` (121), `FoodMemoryCard` (86),
   `KernelChart` (140), `LabeledMealRow` — about 1250 lines of unreachable UI.
4. **Comments vs. code:** a mechanical check (symbols/files that don't exist)
   finds only 1 genuine drift across all of `app`+`core` — and it was
   introduced during this cleanup. Semantic drift is not caught this way and
   needs a close read.

---

# Food Branch Audit — Historical

## The Live Chain, in Full

This is everything involved in computing meals for the forecast. Eight files,
~1100 lines:

```
  note ──► HybridFoodEventFromNote ──► HybridFoodEvent
                    │        (grams: Carbs → CarbEvidence → CarbProvenanceV1)
                    └─ parseFoodKineticsV2  (protein/fat/carb shares, shape, fiber)
                       ConceptPools.mealConceptComponents  (components)
                                      ↓
   AMPLITUDE ─ foodAmplitudeUnscaled ─ grams × globalFactor × calibration
                                       (starts from body weight: CarbSensitivityPriorV1)
                                      ↓
   TIMING ─ physioFeatureShapes ─ CarbTrianglesV1 (3 triangles, mixed by macros)
                  └ no macros → physioMacroShape ─ food.defaultShape 10/55/180
                                      ↓
   QUEUE ─ CarbAppearancePolicyV1 (kcal/h + sieve)
             MealCaloricExtentV1 (kcal per meal) · MealClusterKineticsV1 (one pipe)
```

Nearby meals share the pipe through `clusteredFoodCdfWithinSegment`; the
cluster is computed once and memoized — the key now includes meal duration
(defect M-128).

## What Was Removed

| file | lines | why |
|---|---|---|
| `analysis/ComponentSystem.kt` | 563 | zero references anywhere in the repo, tests included |
| `analysis/CarbRatio.kt` | 73 | zero references; ICR is computed nowhere |
| `analysis/DishIdentityV1.kt` | 147 | the key for learned-dish pools, and the pools were removed in M-129 |
| `ui/ConceptsScreen.kt` | 328 | `ConceptsDialog` is called from nowhere |
| `ui/FoodMemoryCard.kt` | 86 | the composable is never called; the `FoodMemory` data itself is still live |
| `tools/ComposeSystemStudy.kt` | — | a rig for `ComponentSystem` |
| `tools/QuietStretches.kt` | — | same |

Three more files were checked and **kept**, though they looked vestigial:

- **`ConceptPools`** — live: `mealConceptComponents` calls
  `HybridFoodEventFromNote`, i.e. it is the "note → event" boundary;
- **`ComponentDeconvolution`** (400) — **later removed entirely.** At the time
  of an earlier audit pass it was genuinely internal machinery of `FoodCurve`;
  shortly after, it was removed along with the concept layer. This entry is
  kept as a deliberate artifact, marked superseded: it shows that "kept
  because it's live" is a verdict tied to a point in time, not a permanent
  guarantee;
- **`ComponentCounts`** (273) — `countFromNoteText` feeds `CarbAnchors`, which
  feeds gram calibration.

> ⚠ **WHAT ELSE WENT STALE IN THIS DOCUMENT AFTER THAT POINT.** Removed
> entirely: `ComponentDeconvolution`, `MealDossierBuilder`, `MealDossier`, the
> predictors `predictKinetics` / `predictPerGramRise` / `conceptProfiles` in
> `FingerprintKinetics`, and `FoodSources` shrank to a single function,
> `historicalFoods` (was 756 lines, now 80). The `roughNeighbours` setting was
> removed. Anywhere below that still describes these names as working is
> history.

**Conclusion on the "meal vestiges" from the first pass: there aren't any.**
Concepts and fingerprints are not an old forecast branch but the CORPUS
infrastructure that `PhysioAutoFitV1` fits on. They can only be removed
together with auto-fitting.

## Comments vs. Code

`CarbTrianglesV1` — the KDoc claimed:

- "the fast triangle barely moved (25 → 19, 75 → 74)" — in the code it is
  **10/33/91**, folded into the medium one (tagged `tri-v3`);
- "slow carbs take the full delay, fast carbs less than half" — all three
  `macroShare` values equal **0.48**.

Both statements sat directly above the current values and read as
descriptions of them. They were corrected, keeping the historical note — it
explains where the "medium" bucket came from.

## Closed: Queue Set to 180, Ceiling Derived

The user's instruction: "raise it to 180, rewrite the tests… raise the ceiling
too." `PERSONAL_EMPTYING_KCAL_PER_HOUR_V1 = 180.0`, and `maxRateGPerHour` is
no longer a constant — it is derived as `kcalPerHour / KCAL_PER_CARB_GRAM_V1`,
because two independent numbers for the same physical quantity were silently
drifting apart. At the same time, a KDoc claiming the ceiling was "not on the
physio path" was removed: it was.

Below is the original framing of the question — it explains why this took a
full day to resolve.

## (Historical) Open Question for the User: Queue at 120 vs. 180

**The constant in code was `PERSONAL_EMPTYING_KCAL_PER_HOUR_V1 = 120.0`. The
phone applied 180. Forty places in the rigs hardcode 180, nine hardcode 120.**

That means every conclusion since M-100 — triangles, sieve, the ISF axis —
was measured at 180, while a fresh install would get 120.

120 was chosen so the caloric queue would match the old gram-based one
EXACTLY (30 g × 4 kcal = 120 kcal/h), and that was the right first step: the
migration became verifiably a no-op. `CarbSievingTest` pins exactly this
identity.

Raising it to 180 breaks **eight tests**, because the identity "sieve 1.0 =
gram-based queue" only holds at 120. An audit does not resolve this: retuning
a measured model parameter should not be a side effect of code cleanup. The
gap is now visible in the code, not just in one device's configuration.

---

# Insulin Branch Audit — Historical

## No Dead Code

Of 33 files checked, none is without a consumer. Two — `CorrectionEvidenceV1`
and `InsulinTailFromAmplitudeV1` — are used only by rigs and tests; this is
bench code that lives in `core`, not a vestige.

**The tool lied in the dangerous direction**, and it's worth recording:
call-site analysis declared `IsfDeviationRuntime` (199 lines) unused, though
`SettingsScreen` calls it. The cause: the comment regex swallows a stretch of
code whenever the file contains `/*` inside a string literal. The rule after
this incident: **re-check any "nobody calls this" verdict with a raw grep
before deleting anything.**

## A Previously Documented Trap No Longer Exists — Resolved

Project notes used to warn: "a population IOB curve (DIA 300, peak 75) lives
in `core/analysis/Iob.kt`; every caller has it behind `?:`, kicking in when
there is no artifact — a fallback path that answers differently."

Checked: **nobody imports** the core's `iobUnits` in `app/src/main` or
`core/src/main`. Eleven similar-looking calls are actually
`HybridRuntimeMetrics.iobUnits`, a different function that runs on the
learned kernel. `kernelIobUnits` is the same story. Both live only in rigs
and tests.

What's actually used from this file is `iobFraction`, and **every call passes
its own `dia`/`peak`** (`ParametricKernel`, `EpisodeKernelV1`) — a parametric
SHAPE that gets fitted, not a population default. There is no second answer
behind `?:`.

## Tooling Defect: the Log Said "Applied" While Printing Settings

`MainActivity` printed `PhysioTuning.summary(PhysioTuning.read(...))` under
the word "applied". This is **prefs**, not what actually reached the model,
and for insulin the difference is real:

```
HybridModelStore.PhysioTuning.apply(...)   → values enter the artifact
PhysioRuntime: safeBase → InsulinCurveRuntime → InsulinPriorV1.widen
             → ManualInsulinRuntime.apply(resolve(manual, measured, prior))
```

In `InsulinParameterResolverV1`, tuning landmarks land in **`priorLandmarks`
— the lowest tier**. A finished measured curve (`TAGGED_CORRECTION`)
overrides them. So the log could claim a specific value was "applied" while
something else actually was.

This was split into two log lines: **"configured"** (prefs) and **"APPLIED to
model"** — the latter reads `artifact.personModelAt(...).insulin` off the
main thread and prints start/peak/tail/ISF together with the artifact id.

## The Wiring, as It Is

| tier | source | priority |
|---|---|---|
| P1 manual input | `ManualInsulinParamsV1` via `ManualInsulinRuntime` | highest |
| measured curve | `PersonalInsulinCurveV1` (`TAGGED_CORRECTION`) | medium |
| prior | `person.insulin` after `PhysioTuning` + `InsulinPriorV1.widen` | lowest |

There is one resolver (`InsulinParameterResolverV1`), one point of
application (`PhysioRuntime`), and the claimed invariant "one path to
`person.insulin`" **holds** — but three tiers write into it, and before this
audit no log showed whose answer won.

---

# UI Backlog, Sorted by Map Branch

A backlog list from the user. Each item is assigned to a branch, so it gets
resolved together with that branch's audit instead of as a separate pile.

## Done Immediately — Main Screen (the "Surfaces" branch)

Removed: "forecast limited — why?", "🧠 Learning: ISF n/10", "About the
forecast", "Label meals", "How the last meal was calculated", the red card
"glucose above/below forecast", and the tooltip comparing two arms (down to
one arm as of M-128).

**Vertical scrolling is fixed by the same cut**, not separately: the chart
canvas height is computed as `boxH − topBlockHeight − whatIf − chrome`. Every
row removed literally hands pixels back to the chart.

The cards' CALCULATIONS in `MainState` went with them, since nothing else
read them: `learningLine` (iterated `dishCurves`), `suggestMeterCheck`
(queried `meterReadings` for a full day on every pass), `forecastHealthReasons`,
`hybridV11Active`. Warm `loadState`: **2419 → 1332 ms**.

## Mapping the Remaining Items

| item | branch | what it actually checks |
|---|---|---|
| 2. "Repeat" in history | **Input** (`LabelScreen` → `AnnotationComposer`) | uses `FoodMemory`/`DishRecognitionV1` — what was kept as "dish gives WHAT" |
| 2b. "half portion / double" | **Meals, amplitude** | scaling grams and macros — input from `CarbEvidence`, `CarbProvenanceV1` |
| 2c. composer as chat | **Input**, a separate redesign | |
| 3. meal card in history | **Meals** + `HistoryFoodProjectionCache` | which fields are still relevant after M-128/M-129 |
| 3b. Food library | **Meals**, `ui/FoodLibrary.kt` (1390) | the only consumer of `ComponentSubstrate` |
| 3c. History maintenance, Fill in macros, Anomalies V11 | **Meals** / **Ledgers** | "Anomalies V11" is a name left over from a removed arm |
| 5. Analysis | **Surfaces**, `AnalysisOverviewScreen` (660) | ←0 incoming references; half the cards may compute from removed data |
| 6. Settings: "personal model V11", "Your corrections" | **Insulin** | "V11" is a name left over from a removed arm; the corrections list is `CorrectionReviewRuntime` |

**A pattern worth naming:** three items out of six are NAMES of removed
entities, left behind on the screens ("V11", "Anomalies V11", the arm
comparison). This is the same "comment vs. code" drift, just in UI strings,
and it's caught the same way — by searching for names that no longer exist in
the model.



---

# Status Snapshot — Historical

## What Happens When the Screen Opens

```
ledger preview (last line)                   ~60 ms   ← first frame
loadState allowBuild=false                  ~650 ms   ← full state
  of which Forecaster.forecast              ~150 ms
twin: snapshot accepted from disk              —      ← no rebuild
training                              after 30 sec, once a day
```

It used to take an 11441 ms first frame and an empty chart.

Four causes, each measured:

1. **WAL was off.** `SQLiteOpenHelper` defaults to a rollback journal, where a
   write blocks every reader. `exportSnapshot`, meanwhile, always did a
   `wal_checkpoint` — the mode the code was written for was simply never
   turned on.
2. **`noteFingerprints` re-read the entire food era on every pass** —
   2366 ms, all notes across roughly 50 days with LLM-parsed analysis, just to
   cover 6 hours of meals. It was narrowed to a ±90-minute window around the
   active meal. **The question was later closed a different way:** both
   `noteFingerprints` and `fingerprintShapedFoods` were removed entirely —
   they had no callers left — so there was nothing left to optimize.
3. **`TwinCache.get()` never checked disk** — it rebuilt from scratch on
   every process restart (3.7–4.1 s), even though a snapshot sat right there
   within its TTL.
4. **Candidate A/B and ledger writes sat on the screen's critical path** —
   `candidates(2) in 7624 ms` and `ledger: whatIf 7021 ms` before the first
   frame.

## The Corpus Funnel — What All the Machinery Produces

Produced by the (not included) analysis runner's `episodes` report over the
collected data. Across the collected data, 12,323 episodes yielded:

```
CENSORED / UNEXPLAINED_CONFOUNDED   4337
CLOSED   / TIMING_MISMATCH          3013
CLOSED   / UNEXPLAINED_CONFOUNDED   2666
CLOSED   / EFFECTIVE_RESPONSE_ONLY  1560
IDENTIFIED_ISF                         0
```

`food_learning=true` — **0 out of 7471**. The fully successful state never
occurs once, and the largest closed bucket, `TIMING_MISMATCH`, means the
model's shape doesn't match the trace.

The direct ISF reader, the same funnel in one log line:

```
doses measured 22 · accepted 8 · rejected 14:
  window shorter than 90 min ×6 · primary stream doesn't cover it ×5 · rejected by the user ×3
```

**Eight doses is not the result of broken gates — it is the number of
candidates.**

## Database Size

| | |
|---|---|
| starting point | 416 MB |
| now | 55 MB |
| steady state | ~35 MB |

Ledger growth 2.4 → ~1.2 MB/day, now bounded instead of unbounded.

## Loose Ends

**The episode pipeline is alive and doesn't feed the model.** 1336 lines in
`ClosedEpisodeRuntime` plus `core/physio/ClosedCausalEpisodeV1`. Three
surfaces read it — the "Closed causal segments" card in Analysis, "Your
corrections" and "Today, differently" in settings — and none of them affect
the line.

One thing blocks removing it: **`CorrectionReviewRuntime` is the only way to
grow the amplitude corpus** — overnight correction boluses can
only be reconstructed by asking the user. It reads episode payloads because
that's the only place recording "there was a dose, and here's what's wrong
with it."

Half the path is already done: `physio_isf_dose_review_v1` records every
measured dose with its rejection reason, ISF, range, start, and curve.
Missing are `shapeCorrelation` and `sensorProbability`, which the direct
reader doesn't compute — it has range width (`roughness/drop`) instead. This
is a scale substitution on the screen where the user makes the call on a
dose, and it needs the user's own decision, not one made for them.

**The activity-coverage gate was arithmetically impossible to satisfy** and
has been fixed, but the effect only reaches flags with no readers: nobody
queries `foodLearningEligible` or `isfLearningEligible`. The episode schema
version was bumped to v42, and a rebuild runs in the background.

**Settings that cannot be configured.** Six boolean flags have a getter, a
setter, and a default value — and **not a single call to the setter anywhere
in the app**. These are compile-time constants wearing a settings interface:
code that reads them looks like a toggle, suggesting the user can change it,
when nothing ever calls the setter.

| flag | value forever | what it means |
|---|---|---|
| `note_preferred_food` | **false** | logged food does NOT win over the meal detector |
| `activity_model_v2` | **false** | the activity model v2 never runs |
| `plausibility_gate` | **false** | the anchor is never plausibility-checked |
| `rough_neighbours` | false | — |
| `marks_teach_model` | true | labelling answers reach the model |
| `note_anchored_food` | true | the fingerprint path is enabled |

`hybridV11Main` was the seventh such flag and has since been removed: it
guarded six live spots — the effects surface, the COB row, carb sensitivity
on the watch — meaning an unreachable switch was deciding whether half the
physio surfaces worked at all.

The remaining six were left untouched: three of them disable entire model
paths (`activity_model_v2` in full), and removing them would mean making a
decision about the model, not about cleanup.

**Unreviewed compiler warnings:** `SegmentLandmarksV1.kt:483,501` "condition
is always true" in the insulin landmark code, and `TwinSnapshot.kt:397`
"inferred type is Nothing?, but String was expected". Both are in model code,
both worth a look.

**Not yet reviewed:** `minute_readings` (71k rows, starting only partway
through the collected history) and `presented_glucose` (31k, duplicates
readings after calibration) — 3.3 MB combined; the question isn't the size
but whether they're needed.

## How to Measure Instead of Guessing

| tool | question |
|---|---|
| the analysis runner's `episodes` report (not included) | what the episode corpus produces |
| log `IsfEvidence: doses measured` | why there are this many doses and not more |
| log `PhysioTuning: applied / ledger` | what the model actually applies and whether the tag matches |
| a long press on the chart | what was drawn then versus what the model would draw now |
| `MainStatePerf` | where the time goes on the open path |

A rule that cost an entire evening to learn: **look at the funnel, not the
nearest available label.** Three confidently wrong conclusions were all made
the same way — by whatever signal happened to be at hand, instead of counting
what got filtered out.
