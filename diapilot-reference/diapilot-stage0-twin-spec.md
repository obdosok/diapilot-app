# Spec: DiaPilot Stage 0 (part 2) — retrospective digital twin validation

> A self-contained specification for a coding agent. Continues `diapilot-stage0-spec.md`
> (personal ISF calculation). Uses the same `diapilot_stage0/` codebase and the same
> `export.sqlite` export.

## 1. Goal

Test the product's central hypothesis (section 5 of the design document) against real
retrospective data: **can the glucose trajectory between sparse fingerstick readings
be reconstructed from insulin alone plus a personal model, more accurately than naive
baseline methods — and does the uncertainty band honestly widen over time?**

This is a stress test of the digital twin idea under worst-case conditions: **carbs
are not recorded**, so the twin fundamentally cannot see food. Stage 0's job is not to
build a good predictor but to **measure** how much glucose variability is explained by
insulin and recalibration, where the twin works and where it goes blind, and to get
empirical answers to the open questions in section 11 (minimum sensor-wearing period;
UX justification for the uncertainty band).

Not a medical product. No hypoglycemia predictions, no recommendations.

## 2. Input data

The same `export.sqlite` (xDrip): `BgReadings` (glucose), `Treatments` (insulin). There
is no carb data — that is the experiment's condition, not a shortcoming. Units and
timezone are as in part 1 (mmol/L, the user's timezone, default UTC).

## 3. Twin model (minimal, honest)

### Insulin response kernel G(τ)
`G(τ)` is the median, over clean corrections (the part 1 detector), of
`BG(t0+τ) − BG(t0)`, normalized by dose, for τ ∈ [0; T_kernel] (T_kernel ≥ 240 min).
`G(τ) ≤ 0`, decreases monotonically and flattens out near the end of the analog's
action. This is exactly the median curve from the `episodes_overlay.png` chart. It
already blends ISF and the action-curve shape — a separate ISF model is not needed.

The kernel is built **only on the training window** (data before the start of the
evaluated period), so there is no leakage from the future.

### Direct simulation from an anchor point
Given a known glucose value `B_a` at anchor time `T_a` (a fingerstick reading), the
forecast at time `T > T_a` is:

```
BG_model(T) = B_a + Σ_i D_i · [G(T − t_i) − G(T_a − t_i)] + drift · (T − T_a)
```

- the sum runs over each bolus with dose `D_i` at time `t_i` in the window
  [T_a − 6h; T] (`G(x)=0` for `x<0`, `G(x)=G(T_kernel)` for `x>T_kernel`);
- the difference `[G(T−t_i) − G(T_a−t_i)]` is the **increment** of insulin action
  between the anchor and T;
- `drift` is a constant term absorbing everything the twin cannot see (basal, dawn
  phenomenon, unrecorded food, endogenous glucose production). **Default drift = 0**
  (maximally honest: the twin knows only insulin). The `--learn-drift` option
  estimates a constant drift on the training window as the median residual during
  "quiet" stretches.

### Uncertainty band
The band's half-width grows with time since the last reading:
`w(Δt) = w0 + k·√Δt`. Parameters `w0, k` are calibrated on the training window from
the growth of the residual's absolute value. The band is a mandatory part of the
output: the twin never outputs a bare number.

## 4. Experiment protocol

1. **Split.** The evaluated window is the last `--eval-days` (default 30) of the data
   period. Everything before it is the training window (for G and drift). No leakage
   from the future.
2. **Virtual fingersticks.** In the evaluated window, place "fingerstick" events on a
   fixed everyday schedule (default 07:00, 12:00, 18:00, 22:00 local time — 4/day;
   option `--fingersticks-per-day`). The reading's value is the nearest real CGM
   reading within ±15 min (this is the fingerstick "truth"). The schedule **does not
   depend on model error** — no leakage.
3. **Segment-wise simulation.** Between adjacent readings `[s_j, s_{j+1}]`, forecast
   forward from `s_j` (anchor = the true value at `s_j`). At `s_{j+1}`, snap to truth
   (recalibration). Score the forecast at every 5-min point of the hidden CGM within
   the segment.
4. **Baseline methods (mandatory — the twin is meaningless without them).**
   - `persistence`: hold the last reading flat until the next one (insulin-blind).
   - `interpolation` (a reference oracle, uses the future reading — **not a
     predictor**, an upper bound): a straight line between `s_j` and `s_{j+1}`. Show
     separately, labeled as an oracle.
5. **Metrics.** MAE and RMSE (mmol/L):
   - overall; by time-since-reading bin (0–30, 30–60, 60–120, 120+ min) — checks the
     growth of error; by night/day segment — where the twin goes blind on food.
   - **Skill score** of the twin against persistence: `1 − MAE_twin / MAE_persist`.
     Key figure: >0 means the twin beats naive persistence.
   - **Band calibration**: the fraction of hidden points that fall inside the band
     `w(Δt)`. Expected to be close to nominal; systematic under-coverage → the band is
     too narrow (dangerous), over-coverage → uselessly wide.
6. **Minimum sensor-wearing period** (answers open question 11). Sweep the training
   window length (1, 2, 4, 8 weeks before the evaluated period): how G and eval-MAE
   change. Show at what amount of data the estimate stabilizes.

## 5. Functional requirements

1. A `twin` command in the same CLI: `analyze` (part 1) and `twin` (part 2).
2. Report (`twin_report.md`, in Russian): training/evaluation period; number of
   virtual readings and segments; a metrics table for twin/persistence/interpolation
   (overall + by bin + night/day); skill score; band calibration; training-window
   sweep summary; an honest statement of "where the twin works, where it goes blind."
3. Charts (PNG in `./output/`):
   - the learned kernel G(τ) with per-episode spread;
   - a fragment of the evaluated window (2–3 days): real CGM, anchor readings, the
     twin's curve + band fill, the persistence curve — a visual honesty check;
   - MAE by time-since-reading bin (twin vs persistence) — growth of error and band;
   - the band calibration curve.
4. Options: `--eval-days`, `--fingersticks-per-day`, `--learn-drift`, `--train-weeks`
   (for the sweep), `--tz`, detector thresholds inherited from part 1.

## 6. Non-functional requirements

- The same codebase and stack (Python 3.11+, pandas/numpy/matplotlib, sqlite3 from
  stdlib). A new subpackage `diapilot_stage0/twin/` (kernel.py — G(τ); simulator.py —
  the direct model; experiment.py — split/readings/segments/metrics; twin_report.py).
- **Tests before implementation** (contract): synthetic data with known G and boluses
  → (a) the simulator exactly reconstructs the trajectory without drift; (b) with one
  correction the twin beats persistence; (c) the band covers ≥ nominal on synthetic
  data with known noise; (d) the split does not peek into the future (G built on
  train does not depend on eval data); (e) virtual readings are placed on schedule
  and snap to the nearest CGM point.
- Code/comments in English; report in Russian. Fully offline.

## 7. Order of work (for the agent)

1. Extract and show the personal kernel G(τ) from the clean episodes (training
   window). Chart + key numbers (drop per unit at 60/120/180/240 min, flattening
   point).
2. Write the tests (section 6) with a synthetic generator (extend the part 1
   fixture).
3. Implement kernel → simulator → experiment → report until the tests are green.
4. Run on a real export: split, readings, metrics, charts. Show the report.
5. Review with the user: does the twin beat persistence, and where; is the band
   honest; from what training period does the estimate stabilize; interpretation of
   "the twin is blind to unrecorded food."

## 8. Acceptance criteria

- Tests green; on synthetic data without drift, the simulator reconstructs the
  trajectory with MAE < 0.1 mmol/L; on synthetic data with a correction, skill score
  against persistence > 0.
- On a real export: the report and charts are generated, metrics are reproducible,
  the split has no leakage, the band is calibrated.
- The user gets an honest answer: **how much glucose variability the twin explains
  through insulin and recalibration, how quickly uncertainty grows between readings,
  and where the lack of meal data breaks the reconstruction** — i.e. whether the twin
  idea is valid, and under what constraints.
