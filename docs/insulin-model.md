# Insulin model — the whole cycle, and how to reproduce it

This document lets a fresh session get **the same numbers on this baseline**
from a short prompt, instead of redoing a full multi-day analysis pass.

The numbers below were captured from the device via logcat, tags
`InsulinProfile`, `PhysioTuning`, `PhysioRuntime`, `AdaptiveIsf`. (Tags
`IsfEvidence` and `ClosedEpisode` still appeared in an earlier revision of this
document — that code no longer exists, see §3.)
If your run gives something different — **first look for a bench mismatch, not
the output** (discipline #7).

> ⚠ **If the question is "which ISF is applied" — the answer is the
> hand-pinned value**, and the log shows this as the word `pinned by hand true`.
> The manual ISF lives in exactly one place (`ManualInsulinRuntime`, P1); there
> used to be three.
>
> And this is NOT a tuning mistake: on the real history walk-forward showed the
> pinned value beating the measured tagged-correction corpus median on every
> column, including false alarms (§5.1). That teacher was later removed — see §4–5.
>
> The only alternative near the field now is the **daily balance**. Over ten
> days it read lower; across the whole history it matched the hand-pinned
> value to within 0.01 by two independent paths.

---

## 0. What is actually measured

Two quantities, measured **separately and in this order**:

| | what it is | corpus | why |
|---|---|---|---|
| **shape** | onset / peak / slowdown / end | **every** dose | shape is normalized to its own amplitude, so a wrong carb count barely moves it. n matters, and n is 5–8x larger |
| **amplitude** (ISF) | mmol per unit | **tagged corrections only** | amplitude is exactly what a wrong carb count breaks. Trust matters, not n |

The order is a requirement of **identifiability**, not a preference. With food
on board the trace is `insulin action − carb appearance`: two unknowns on one
observable, and a uniform shift in insulin trades off exactly against a shift
in food. The only thing that breaks the degeneracy is a dose where food is not
acting.

Decided by the user, measured, closed.

---

## 1. Common input: ONE stream

`glucose_readings` is a merge of feeds, not a single series.
`MeasurementStreamCore` takes the feed with the most readings **over the whole
range** and returns only that one. Every measurement is required to go through
it.

Two details that were missing from the first revision of this document:

- **The choice is made on the RAW count**, before the plausibility filter.
  Otherwise a feed whose outliers get filtered out would lose the selection to
  the very fact of its own cleanup. Readings dropped as implausible are
  counted separately (`refusedImplausible`).
- **`SAME_SENSOR_GROUPS` = {`libre_ble`, `libre_nfc`}** is ONE sensor, read
  over BLE and by the phone. They do not compete for calibration, so they are
  merged into one feed instead of competing. That is why the ISF funnel prints
  "main stream (libre_ble+libre_nfc)".

Why not choose by time window: `xdrip_sgv` stopped writing at some point, so a
window-based choice gave sgv readings to some earlier doses and ble to later
ones. On the same doses sgv shows a drop about **14% larger** — and that feeds
straight into ISF.

The live forecast anchor is NOT part of this: "what is the glucose right now"
is answered by the freshest reading from any feed.

---

## 2. Shape — `InsulinProfileRuntime` + `SegmentLandmarkReaderV1`

### 2.1 What goes into the sample

Every dose from the food-tracking era (the configured `FoodEra` start), except
`purpose` = "air". **The "rejected by the user" filter no longer exists** —
verdicts were removed along with the review card, and the four rejected doses
went back into the shape corpus (303 → 307).

**A dose's window is closed only by the next INJECTION**, capped at `CAP_MS` =
240 min. Food does not close the window — it is a confounder that extends over
time, not a wall. Food used to close it, and that silently threw out every
pre-meal bolus (dose at 0, food at 15 → a 15-minute window), even though onset
happens around minute 12, BEFORE the food.

### 2.2 Each landmark on its own horizon

Not "the whole dose is valid or not", but per segment: a dose can give a good
onset and no usable tail.

| landmark | clean-minutes horizon |
|---|---|
| action onset / visible drop | `ONSET_HORIZON_MIN` = 45 |
| peak rate | `PEAK_HORIZON_MIN` = 100 |
| slowdown | `SLOWDOWN_HORIZON_MIN` = 150 |
| end | `TAIL_HORIZON_MIN` = 200 |

Ordering within a dose is checked: `MIN_RISE_SPAN_MIN` = 10 between onset and
peak, `MIN_PHASE_SPAN_MIN` = 5 between the other phases. The smoother's edge is
not read: `trustedToMin = recordedToMin − 2 × SMOOTH_HALF_WIDTH_MIN`, plus
`EDGE_MARGIN_MIN` = 10.

### 2.3 Two onsets, both carried forward

- **action onset** — the trajectory leaves the pre-dose baseline. Goes into
  the curve.
- **visible drop** — the line turns downward. Shown alongside.

On a flat background they coincide (paired median gap exactly 0); with rising
food they diverge by about 5 minutes. The curve **must not** be built on the
second one: that is a property of insulin AND background together, and it
would bake the user's typical meals into their insulin curve.

### 2.4 The confounder — "food is acting AT THIS landmark"

Not the pre-dose slope, and not "food anywhere in the window". Both were tried
and both are wrong:

- the slope does not show a pre-meal bolus — it lies flat on the baseline;
- "food anywhere in the window" collapsed the clean arm down to n=7. Food at
  minute 200 cannot confound an onset at minute 20.

Food acts from `FOOD_ACTION_DELAY_MIN` = 15 to `FOOD_ABSORPTION_MIN` = 180
minutes after it is logged. A rising background
(`RISING_BACKGROUND_MMOL_PER_H` = 0.8) does not reject a sample, it gives it a
weight of `RISING_BACKGROUND_WEIGHT` = 0.5.

### 2.5 Assembly

Weighted median over the samples, separately for "food not acting" (flat) and
"food acting" (rising). **One arm for the whole profile:** the switch to flat
happens only if onset, peak AND visible drop all collect
`MIN_CONDITIONED_SAMPLES` = 8 flat samples; otherwise it stays POOLED. The
profile never mixes the two populations.

Then `InsulinShapeV1.synthesize`. **For this profile the `synthesizePlateau`
branch applies, not the gamma one**: since "active until" is greater than
the peak, there is a plateau landmark — the rate ramps up with a
smoothstep from onset to peak, holds a plateau until the plateau end, and
decays exponentially with λ = (tail − plateau)/3, i.e. about 5% of peak rate at
the stated end. The gamma-density branch is for a profile WITHOUT a plateau
landmark. It's the same construction as the manual entry (P1), so the plotted
curve, the forecast curve, and the curve recovered by deconvolution are all the
same object.

⚠ **`person.insulin.peakMin` is NOT the measured peak.** What goes there is
`singlePeakMin` = (peak + active-until)/2, while the measured peak lives
separately in the nodes and landmarks. So a log line "APPLIED to model: peak X"
and a log line "InsulinProfile: peak Y" with different numbers do not
contradict each other — they are two different numbers with similar names, and
that has already caused confusion.

Then `coerceIntoDomain` clamps to population bounds and **names what moved**.
The bound is `insulinTailMinRange` = 240…600 min.

⚠ **The floor now clamps, and that is deliberate.** It was 120, which no bolus
analogue reaches, so it accepted every reading the instrument could produce.
The instrument cannot see past four hours — `CAP_MS` = 240, `TAIL_HORIZON_MIN` =
200 clean minutes inside it, and "end" is the rate returning to the pre-dose
slope, which a rising background meets early — so every measured end lands in
roughly 150…230 and nothing was ever clamped. The floor is the instrument's own
reach: a measurement under it is a window limit, the settings screen marks it as
one, and the installed curve carries 240 rather than the short reading. This
does not lengthen the measurement; the plateau end landmark that would is
deferred (audit M1, roadmap O-D).

### 2.6 Expected result on this baseline

The two log lines have this shape (values in angle brackets come from the
device's own data):

```
InsulinProfile: measured from <N> injections, <D> days · onset <a> · peak <b> ·
active until <c> · end <d> min · from injections with no food acting

PhysioTuning: segments: doses <M> · branch FOOD_FREE · onset n=… ·
visible drop n=… · peak n=… · applied slowdown <c> (n=…) ·
end <d> (n=…) · rejections: landmarks read but none passed
validation ×…, line not readable (too few points before/after) ×…, window
shorter than onset horizon ×…, line does not break — compensated ×…
```

**The funnel balances exactly:** doses = doses with at least one landmark
read + rejections. (An earlier version also had a "rejected by the user" term;
verdicts were removed along with the review card, and those doses went back
into the corpus.) Recompute the funnel on the device before quoting it.

`doses <M>` is `boluses.size`, every injection from the food-tracking era;
`from <N> injections` is `samples.size`. Two different counters, both needed.

### 2.7 What conditioning on food would give for the LATE pair of landmarks

Early landmarks are conditioned on "food is acting AT THIS moment", late ones
are not. Why, was never measured directly; it was an open question, not a
defect. Measured on one real device, all three numbers from the same raw median:

| landmark | no food vs all (=applied) | with food vs all |
|---|---|---|
| slowdown | about 6 min **later** | about 1 min earlier |
| end | about 6 min **earlier** | about 1 min later |

**Conclusion: not worth changing, the question is closed.** Three reasons.

1. **The effect is five times weaker than on the early landmarks.** Food shifts
   onset and peak by more than ten minutes each — larger than the onset
   itself. Here it is about ±6 minutes, i.e. 6–9%. And the sign differs: for
   slowdown the no-food branch is later, for end it is earlier. Mixed-sign ±6
   at n≈20 is sampling noise, not a mechanism.
2. **Conditioning would cost 80% of the sample.**
3. **For the end landmark it isn't even feasible with the current logging.**
   The applied end does not come from the median at all — it comes from
   `censoredTailMedian`, a Kaplan–Meier-style estimator that accounts for
   windows closed by the next injection before the tail is reached. Its
   **censoring correction was about 20 minutes, three times the entire food
   effect**. There is nothing to split it
   by branch with: a censored observation carries only `tailCensoredAtMin`,
   without `confoundedByFood`, because at the moment it is cut off it is
   unknown whether food was acting. Conditioning the end landmark honestly
   requires logging that first.

⚠ **A side effect worth keeping in mind: the curve is stitched from two
corpora.** The early landmarks are taken from the FOOD_FREE branch, while the
applied slowdown nearly matches the "with food" branch — because about four
fifths of the slowdown observations and three quarters of the end observations
were captured while food was acting. The seam is small (about 6 minutes) and
measured, but it exists.

Both branches are computed in `SegmentLandmarksV1.assemble`
(`slowdownConditioned`, `tailConditioned`) and printed in the startup log.
They are **not applied** — the fields exist so the question can be answered
from the log instead of being reopened.

---

## 3. Amplitude — NO LONGER MEASURED. The door is closed

This section used to describe `IsfEvidenceRuntime` in about eighty lines:
candidates by the `Admission.TAGGED` tag, a window, four gates, a per-row
weight, a single-dose band, and a funnel of "22 doses measured · 8 accepted ·
14 rejected". All of that was accurate at one point and was removed later,
along with the review card, by the user's decision ("the review card isn't
needed, remove it along with the verdicts").

**Reading this section as a description of a live subsystem is a mistake, and
it has already cost real debugging time: `IsfEvidenceRuntime`,
`CorrectionVerdictRuntime`, `ClosedEpisodeRuntime` do not exist in any file
anymore.** The full description of the method is in git history, before the
commit that removed the card; there is no need to reconstruct it from memory.

**What is left of amplitude.**

| source | status |
|---|---|
| hand-pinned ISF (P1) | **applied** — the only door |
| daily balance `DailyBalanceIsfV1` | computed daily, shown alongside, applied only via the `IsfSource` switch |
| tagged-correction corpus (a handful of doses) | **frozen** — cannot grow further, there is no channel for verdicts |

The last row is the real cost of that decision, and it is worth keeping in
mind: that handful of corrections is all there will be, until some way to ask
the user is built. A verdict cannot be derived from the line alone — an earlier
revision of this section measured exactly that and showed why (a
plateau-based rule gives a spread of several-fold, because
evening "plateaus" are boluses for an unlogged dinner).

### 3.1 Adaptive ISF window: measured, but what was applied is NOT what won

Measured on a bench that reads `forecast_runs.applied` (i.e. the "as shipped"
arm genuinely reproduces the device — discipline #7), over 35 episodes:

| window | shape | bias | paired @180 | false hypos |
|---|---|---|---|---|
| pinned hand value | 2.170 | +2.132 | reference | 15 |
| **7 days** | **1.880** | **+0.114** | **−1.120 (17/8)** | **2** |
| 10 days (in code) | 2.094 | +0.322 | −0.774 (17/8) | 5 |
| 14 days | 1.796 | +0.415 | −0.774 (17/8) | 6 |
| 21 days | 1.748 | +1.028 | −0.584 (18/7) | 8 |

`DailyBalanceIsfV1.WINDOW_DAYS` = **10**, meaning the winning 7-day window is
not applied. This is deliberate: changing the window is a model change on the
alerting path, and "shadow-safe first" still binds here. Two caveats to the
table, both mandatory when reading it:

1. **The false-hypo column is not independent of the ISF axis.** Weaker
   insulin → mechanically fewer predicted dips below 3.9. The independent
   column is MISSED real hypos, and it is flat across this bench (2 in every
   arm, three real hypos in the set).
2. The estimator's own daily ISF runs noticeably below the
   tagged-correction corpus median. Two instruments on the same axis
   disagree by about 30%; that is the open question, not the window length.

⚠ Extending the window during a quiet week (`AdaptiveIsfWindowV1.choose`:
10 → 14 → 21 → 28 → 45, until support gates pass) was **written and then
removed as unreachable** — the live path never called it. The idea is sound:
a fixed ten-day window during a quiet week gives a refusal instead of an
answer, and ISF jumps between the base value and the estimate. The live
`DailyBalanceIsfV1` now simply returns null when fewer than 3 valid days are
available. Restore it from git, and validate through walk-forward, not a
silent edit.
---

## 4–5. Aggregation and promotion — REMOVED

This section used to describe two stages: folding the correction corpus down
to a single number (`aggregateClosedIsfEvidenceV1`, an adaptive window,
recency weighting, three sources of band width) and applying it as a
percentage through the `closed_episode_global_isf` contract, with a cap, a
ramp and decay.

**Neither exists anymore.** Not because they were poorly written, but because
the concept changed: ISF is now learned from the **daily balance**
(`DailyBalanceIsfV1`), not from tagged corrections. And that pipeline's own
walk-forward evaluation rejected it: the corpus value against the pinned one gave zero
better days out of twelve on shape, and twice as many false hypo alarms
(§5.1).

One fact is worth remembering, because it justifies removing the whole
framework: `closed_episode_global_isf` was **the only contract in this
codebase's history to reach `PROMOTED_MEDIAN`** (816 revisions). All thirteen
others are `DESCRIPTIVE_ONLY`, meaning they were observed and never applied.
Along with it went both carb-sensitivity teachers (`GlobalCsRuntime` and
`EpisodeAmplitudeRuntime`): CS is a constant in the current design, and in
code it already was one — `globalCsScale` always equaled zero.

**Nothing is left of §3.** An earlier revision of this section stated that the
per-dose measurement in `IsfEvidenceRuntime` was live. It was later removed
along with the review card, and the `physio_isf_dose_review_v1` table no
longer has anything reading it. Per-dose ISF observation no longer exists in
the app.

⚠ **Open:** a weight-based CS calculation is written
(`CarbSensitivityPriorV1.fromWeight`, inversely proportional to body mass) but
not applied — `baseCs` uses a flat constant. The mechanism exists,
the door is closed.

---

## 6. Where this feeds into

```
InsulinProfileRuntime (shape) ──┐
                                ├→ mechanics.insulin → PhysioArtifactV1
ManualInsulinRuntime (ISF, P1) ─┘      → personModelAt(hour) → HybridForecastEngine
       ↑
   IsfSource: hand (default) | AdaptiveIsfRuntime (daily balance)
```

forecast, deconvolution (`TwinCache` via `insulinKernelPoints`), What-if, IOB
(`physioIobUnits`), metrics. **There is no second path.**

⚠ **Two caveats from an earlier revision are lifted — both were verified.**

- **"The alert, the widget and the watch use a second path on the v11 arm" —
  INCORRECT.** `HypoAlertNotifier` calls the same `Forecaster.forecast` with
  the same model from `TwinCache`, and `Forecaster` returns
  `shown = physioShadow?.result ?: result` — i.e. the physio arm, falling back
  to the base digital twin only when physio is unavailable (and that fallback
  is logged as ERROR for `hypo_alert`). The `alertForecastOwner` setting no
  longer exists. There is one arm.
- **"The population IOB curve is alive as a fallback path" — LIFTED**: there
  is not a single call to `core/analysis/iobUnits` in `app/src/main` or
  `core/src/main`. `iobFraction` is used instead, and every caller passes its
  own `dia`/`peak` — that is a fitted SHAPE, not a population default.

**There is no longer an episode pipeline.** `ClosedEpisodeRuntime` (~1336
lines) never fed anything into the model and has been removed; the anomaly
dossier and the analysis screens were switched over to direct reads. If you
find a reference to it, it is now a tombstone, not a pointer.

**The prospective ledger A/B test has been removed**, by the user's decision.
Hypotheses are now checked with walk-forward evaluation: half the history for
fitting, the other half for validation, scored paired by day. `forecast_runs`
remain — they prove that the SHIPPED code actually ran (discipline #7), which
a replay cannot give you.

---

## 7. Prompt for a fresh session

> Check the insulin model on this baseline against `docs/insulin-model.md`.
> Capture the numbers from the device via logcat, tags `InsulinProfile`,
> `PhysioTuning`, `PhysioRuntime`, `AdaptiveIsf`, and compare against §2.6 and
> §3.1. Sections §3.6/§4/§5 no longer exist — their code has been removed. If
> something does not match — first check that the bench is evaluating the same
> model as the phone (discipline #7), and only then draw a conclusion.

What should match, as one list:

| | expected |
|---|---|
| main stream | `libre_ble` + `libre_nfc` (one group) |
| shape | the §2.6 line: doses, days, onset, peak, active until, end · arm FOOD_FREE |
| shape funnel | doses = doses with a landmark + rejections |
| applied shape | onset · peak = `singlePeakMin` · tail = the censored end |
| **applied ISF** | **the hand-pinned value (the only door — P1); the daily-balance value shown as the alternative** |
| bench | `appliedIsf` must be read from `forecast_runs.applied`, never reconstructed |

The numbers will shift as new corrections come in — that is expected and by
design. They must not shift **from a restart, a rebuild, or a recompute on the
same baseline**.

**One trap to close on.** A RELEASE build is not `debuggable` — `adb run-as`
does not work on it, meaning any pull of the database or read of
`shared_prefs` silently returns nothing rather than an error. If you need the database or prefs, install a debug
build rather than drawing conclusions from empty output. This trap is easy to
hit more than once in a single debugging session.
