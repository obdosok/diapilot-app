# Accuracy report — `:tools:accuracy`

Closes audit item **M9**: "the app's own accuracy figures are not published
anywhere reproducible." This tool reproduces them from a database instead of
from comments and one table in `docs/insulin-model.md §3.1`.

## Method

The report is built from `forecast_runs` / `forecast_points`
(`app/.../data/ForecastLedger.kt`) and `glucose_readings`
(`app/.../data/SqliteCollectorStore.kt`) — **what the phone actually showed**,
not a recomputation. This matters for the same reason it matters in
`ForecastLedger` itself: a replay through a different build of the model
answers a different question than "was the app right", and the ledger exists
precisely because a replay cannot answer that one (see `docs/architecture.md`,
"What the ledger does now").

Two numbers, from the two ledger consumers that keep their points
(`ForecastLedger.DETAIL_CONSUMERS`):

1. **Forecast error at 30/60/120/180 minutes** (`consumer='main'`) — bias
   (mean(actual − predicted), so **positive = the model read low**, matching
   `ForecastLedger.Quality`), MAE and RMSE, in mmol/L. Every stored point at a
   horizon is matched to the nearest real `glucose_readings` row within 6
   minutes of its target (the same tolerance `ForecastLedger` itself uses to
   decide "what counts as the fact for a target" — not a new number invented
   for this report). A horizon with no matches prints as "no data", never as
   a silent zero.
2. **Hypo alert precision/recall** (`consumer='hypo_alert'`) — for each run,
   "predicted" = some stored point within a 45-minute lead window reads below
   3.9 mmol/L; "actual" = some real reading in that same window (plus 6
   minutes of slack) was below 3.9 mmol/L. Runs where the model refused to
   compute (`algo_version = 'refusal'`, no points) are excluded from the
   count entirely — a refusal means "the app did not look", not "the app
   looked and predicted no hypo", and folding it into either bucket would
   misrepresent the alert.

   This is deliberately **coarser** than the live rule
   (`core/.../twin/HypoAlert.kt`'s `findPredictedHypo`, which walks the dense
   5-minute forecast the twin held in memory and reads the corridor's lower
   edge at night). The ledger only ever stored the six fixed horizons in
   `ForecastLedger.HORIZONS`, so a faithful re-run of the live rule is not
   possible from this data — the coarser rule is stated as what it is rather
   than passed off as the production one.
3. **Two naive baselines beside the model**, so the MAE has something to be
   judged against. Both are computed from `glucose_readings` alone at each
   run's anchor — the last reading at or before `anchor_ts_ms`, within the
   same 6-minute tolerance: (a) **last-value**, "the glucose stays where it
   is"; (b) **linear-15**, the least-squares slope of the last 15 minutes
   continued to the horizon, clamped to 2..30 mmol/L (2.0 is the engine's own
   floor, `HYBRID_FLOOR_MMOL`). The model and both baselines are scored on
   **one common set** per horizon — a run with no reading in hand at its
   anchor contributes nothing to this block, so its `n` can be smaller than
   the model-only table's. Beside each baseline the report prints the skill
   score `1 − MAE(model) / MAE(baseline)`: positive means the model beat the
   baseline by that fraction of the baseline's error, 0 means no better,
   negative means worse. Glucose is autocorrelated, so at 30 minutes a
   last-value skill near zero is the expected finding, not a defect; the
   number to watch is where the skill against **linear-15** goes at 120–180
   minutes, because that is where insulin and food are supposed to earn their
   place. Neither baseline knows about either — that is the point.

Both metric functions (`errorStats`, `evaluateHypoAlert`,
`hypoConfusion`) and the baselines (`lastValueBaseline`, `linearBaseline`,
`skillScore`) are pure and unit-tested on hand-written vectors in
`tools/accuracy/src/test/kotlin/.../MetricsTest.kt` and `BaselineTest.kt`;
`ReportBaselinesTest.kt` then runs the whole report against a hand-built
three-table database whose expected numbers are worked out in its comments.
The end-of-action comparison below adds
`TailComparisonTest.kt` beside it, on the same terms: it reuses these metric
functions rather than growing a second implementation of them, so the only new
things it can get wrong — reading the applied tail out of a ledger row, and
assigning a run to an arm — are what that file pins.

## Comparing two end-of-action values

Added for WP-B10, to settle one disagreement with data rather than argument: the
app's owner measures an insulin action of roughly 130 minutes and sees no effect
after two hours, while `PhysioBoundsV1.insulinTailMinRange` floors a *measured*
end of action at 240, because the per-dose measurement window cannot see
further (audit M1). The hand tier now applies 120…600 as entered — see
`docs/insulin-model.md` §2.5.1 — which makes both tails runnable, and therefore
comparable.

**What this is NOT: a replay.** It does not recompute the forecast under a
counterfactual tail. That would need the engine itself (`:core`, a dependency
this module deliberately does not have — see `tools/accuracy/build.gradle.kts`),
the full event history as known at each run's own moment, the drift state the
twin held in memory, and the meter-calibration era in force for that run. It
would also answer a different question from the one the ledger exists for — "was
the app right", not "would another model have been" (`docs/architecture.md`,
"What the ledger does now"). None of that is available offline from a database
file, and inventing it would be exactly the bench mismatch discipline #7 is
about.

**What it does instead.** `forecast_runs.applied` records the insulin block the
phone was RUNNING when it drew each forecast — `onset/peak/tail isf=… ramp=…
kcal=… sieve=…`, written by `ForecastLedger.appliedSummary`. So a database that
carried one tail for a stretch and another tail afterwards already holds both
arms, produced by the shipped code on the real person. The tool splits the ledger
by that recorded tail and scores each side with **the same metric functions** the
single-arm report above uses (`errorStats`, `matchHorizon`, `evaluateHypoAlert`,
`hypoConfusion`): bias / MAE / RMSE at 30, 60, 120 and 180 minutes, plus the low
alert's precision and recall. It also reports

- every applied tail present in the ledger with its run count, so an empty arm
  can be told from a mistyped one;
- the runs whose `applied` column is missing or unparsable — they belong to no
  arm and are counted separately rather than folded into the likelier one;
- for exactly two arms, the per-horizon difference.

**The arms are NOT paired.** They are different stretches of the same person's
life — different meals, different sensors, different weeks — and nothing here
pretends otherwise; the output says so in its own last two lines. The paired
errors this module *can* compute are the ones inside a single arm: every stored
point against the reading that actually landed at its target. Across arms there
is only a difference of two samples, and `n` is the first column to read.

### How to read the result

A **too-short tail stops subtracting insulin action**, so from roughly two hours
after a bolus it predicts glucose **too high**. In this report's sign convention
(`bias = actual − predicted`, matching `ForecastLedger.Quality`) that is a
**negative** bias at the 120- and 180-minute horizons, growing with the horizon.
The output prints the sign next to the difference table so it never has to be
re-derived from memory.

⚠ **The same sign comes from under-modelled food amplitude**, which is a
separate open finding: audit **M3** (ISF, CS and DIA are not separately
identifiable from meal days) and audit **M5** (the trust ramp shrinks food,
background and drift but not insulin, so a post-meal rise is drawn at about 60%
while the dose is drawn at 100%). A negative late bias therefore **narrows** the
question to "the insulin tail, the food amplitude, or the ramp" — it does not
answer it. Two things help separate them and neither is automated here: the late
bias of runs with a **bolus and no food** in the window isolates insulin, and
`forecast_points.food_delta` / `insulin_delta` carry the two terms separately on
the model's raw scale.

**One bench result already points this way**, and it is worth knowing before
running the comparison so it is not mistaken for a fresh discovery:
`docs/forecast-engine.md` (the `tailShare` axis) records that stretching the
tail from 130 to 300 moved the bias from −0.15 to +0.14 — the short arm reading
high, exactly the sign above. That was one axis on one bench on one corpus, and
it also records that the same axis is NOT independent of ISF (the 180-minute
bias moves 1.07 mmol across the tail-share range alone). The ledger split is
the wider test, on the shipped code rather than a bench.

Read the calibration caveat below before quoting any bias number at all.

## Caveat — the calibration lens (read before trusting a bias number)

`forecast_points.mmol` is on the **meter-calibrated** scale — the number the
chart and the header actually drew (see the SCALE note at the top of
`ForecastLedger.kt`: without correction, one real device read the 15-minute
horizon at MAE 1.49 where the truth was 0.58). `glucose_readings.mmol` is the
**raw sensor** value. `ForecastLedger.inspectAt` re-applies the device's own
`MeterCalibration` before comparing the two; this tool does **not** — doing
so honestly requires rebuilding the calibration from `meter_calibrations` /
`meter_calibration_versions` for the exact era each run was produced in,
which is a second, separate piece of work (and would have been a
recomputation of a subsystem, which this WP was explicit about avoiding).

**Practical effect:** if the device's calibration offset was non-trivial and
roughly constant over the scored period, this report's bias is shifted by
about that offset from what the chart itself would have shown as "wrong by".
MAE and RMSE absorb the same shift. A maintainer reading the numbers below
should check `MeterCalibration.isActive` was false, or the offset small, for
the period scored — or treat the bias column as "bias plus/minus the
calibration lens" rather than a clean number.

## USER ACTION — running this on a real database

This report needs a database that has actually been carried by the app for a
while: `forecast_runs` is written by `ForecastLedger.record()` on every main-
screen and hypo-alert forecast, and a fresh install has none. `core/src/test/
resources` — the repository's golden set — holds only the two physio-model
JSON fixtures (`golden_runtime_v11.json`, `hybrid_runtime_model_v11.json`)
used by `:core:test`. **It has no database file at all**, so there is nothing
in the repository to run this report on today; the numbers below are a
worked example on a hand-built three-row database, not a claim about real
accuracy.

To get a real report:

1. Pull the app's database off a device that has been running for a while
   (Settings → Backup, or `adb pull /data/user/0/io.github.obdosok.diapilot/
   databases/<name>.db`, permissions allowing). It contains real glucose
   history — handle it like the medical data it is, and don't paste its
   contents anywhere public.
2. Run the tool against a **copy** (it opens the file read-only, but a copy
   costs nothing and rules out ever touching a live file):
   ```
   ./gradlew :tools:accuracy:run --args="/path/to/your-copy.db"
   ```
3. Paste the printed table into an issue or this file's own history. If the
   tool refuses instead of printing a table, its message says exactly which
   table or which rows are missing — that is the tool working as intended
   (WP-A5: "fail with a clear message instead of inventing numbers"), not a
   bug to work around.

## USER ACTION — comparing two end-of-action values on your own database

This is the procedure that settles whether the hand-entered 130 minutes or the
240-minute measured floor describes your insulin. It takes **weeks of wall
clock, not minutes of compute**: the evidence is the phone's own ledger, so each
arm has to be lived.

1. **Run one arm.** Set the end of action in Settings → Insulin → *Set
   manually* (the "end" field) and leave everything else alone. It is applied as
   entered — the card's own note states the accepted range — and every forecast
   from then on records it in `forecast_runs.applied`. Change nothing else on
   the insulin card while the arm runs: the split below is by TAIL, and a
   simultaneous ISF change lands in the same rows.
2. **Live it.** A week at least, and ideally the same kind of weeks on both
   sides — the arms are not paired, so a holiday against a working week is a
   confounder you are choosing. Note the switchover date; it is the sanity check
   on the run counts the tool prints.
3. **Run the other arm** the same way.
4. **Pull a copy of the database** exactly as in step 1 of the section above,
   and handle it as the medical data it is.
5. **Run the comparison**, naming the two tails you actually used:
   ```
   ./gradlew :tools:accuracy:run --args="/path/to/your-copy.db --compare-tail=130,300"
   ```
   The tails are matched to the recorded value within ±1 minute. If an arm
   comes back empty, the tool first prints every applied tail the ledger
   actually holds — read that list and re-run with the numbers from it.
   `--tail-tolerance=<minutes>` widens the match when a curve was re-derived a
   minute or two off; widening it far enough to merge two arms is a way to get
   a meaningless answer, so use it deliberately.

**What the answer looks like.** Read the 120- and 180-minute rows of the
difference table. A tail that is too short stops subtracting insulin action, so
it predicts glucose **too high** late after a bolus, which is a **negative**
bias in this report's convention (`bias = actual − predicted`) growing with the
horizon. If the 130-minute arm shows a clearly more negative late bias and a
worse late MAE, the short tail is under-modelling insulin. If the two arms'
late columns are indistinguishable within their `n`, the tail is not what the
late error is made of.

**What the answer is not.** Under-modelled food amplitude produces the same
sign — audit **M3** (ISF, CS and DIA are not separately identifiable from meal
days) and audit **M5** (the trust ramp shrinks food, background and drift but
not insulin, so a post-meal rise is drawn at about 60% while the dose is drawn
at 100%). So a negative late bias narrows the question to "insulin tail, food
amplitude, or ramp"; it does not close it. Also check the low-alert precision
and recall rows: a shorter tail reads less insulin on board, which mechanically
produces fewer predicted lows, so a "better precision" on the short arm can be
the arithmetic rather than the physiology. Recall — did it still catch the real
lows — is the column that cannot be gamed that way, and it is the one that
matters on a medical path.

And read the calibration caveat above: if `MeterCalibration` was active with a
non-trivial offset, and especially if it CHANGED between the two arms, the bias
columns are shifted by different amounts and the difference table is measuring
the calibration as much as the tail.

### Example output (synthetic — see the caveat above)

This is the tool's own smoke-tested shape, run against a **three-row,
hand-built** database: a synthetic example person — not a claim about real
accuracy.

```
Forecast accuracy — bias / MAE / RMSE (mmol/L), consumer='main'
horizon   n      bias      MAE      RMSE
    30 min      1   +0.200    0.200    0.200
    60 min      1   +0.400    0.400    0.400
   120 min      1   +0.600    0.600    0.600
   180 min      1   +0.700    0.700    0.700

Naive baselines from glucose_readings alone, at each run's anchor — same matched points
skill = 1 - MAE(model) / MAE(baseline): positive = the model beat it, 0 = no better, negative = worse
horizon   n      arm          bias      MAE     RMSE   skill
    30 min      1  model       +0.200    0.200    0.200
    30 min      1  last-value  +0.500    0.500    0.500  +0.600
    30 min      1  linear-15   -0.100    0.100    0.100  -1.000
   ...

Hypo alert vs what happened, consumer='hypo_alert' (3 runs scored)
TP=1  FP=1  FN=1  TN=0
precision = 0.500
recall    = 0.500
```

n=1 per horizon is the tell: this is a demonstration of the tool's output
shape, not a report anyone should read as "the model's accuracy". A real
report needs the multi-week, multi-hundred-run history a phone actually
accumulates — see USER ACTION above.

### Example output of the comparison (synthetic, and constructed)

Also a smoke test of the shape, on a hand-built twelve-run database in which
the short arm was *built* to read high late. Six runs per arm, n=6 per horizon:
nobody should read a conclusion out of this, only the layout and the signs.

```
End of action — the ledger split by the tail the phone was RUNNING
(not a replay: see docs/accuracy.md, "Comparing two end-of-action values")

Applied tails found in forecast_runs (consumer='main'):
      300 min       6 runs
      130 min       6 runs
       1 run(s) with no parsable 'applied' column — in no arm

tail 130 min (±1) — 6 'main' runs, 4 'hypo_alert' runs
  horizon   n      bias      MAE      RMSE
      30 min      6   +0.100    0.100    0.100
      60 min      6   -0.200    0.200    0.200
     120 min      6   -1.200    1.200    1.200
     180 min      6   -1.900    1.900    1.900
  low alert: TP=1 FP=0 FN=1 TN=2
  precision = 1.000 · recall = 0.500

tail 300 min (±1) — 6 'main' runs, 4 'hypo_alert' runs
  horizon   n      bias      MAE      RMSE
      30 min      6   +0.100    0.100    0.100
      60 min      6   -0.100    0.100    0.100
     120 min      6   -0.100    0.100    0.100
     180 min      6   +0.300    0.300    0.300
  low alert: TP=2 FP=1 FN=0 TN=1
  precision = 0.667 · recall = 1.000

Difference, tail 300 minus tail 130
bias = actual - predicted, so the MORE NEGATIVE arm is the one that read HIGH
horizon      d-bias     d-MAE    d-RMSE
    30 min    +0.000    +0.000    +0.000
    60 min    +0.100    -0.100    -0.100
   120 min    +1.100    -1.100    -1.100
   180 min    +2.200    -1.600    -1.600

The two arms are different stretches of life, not paired runs. Check n,
and read the caveats in docs/accuracy.md before concluding anything.
```

The two rows worth pointing at, because they are the ones a real run has to be
read the same way:

- the 130-minute arm's late bias is the more negative one and grows with the
  horizon (−1.2 at 120, −1.9 at 180) while the 30-minute row is identical
  between the arms. That pattern — the arms agreeing early and separating late
  — is what an insulin-tail difference looks like. A constant offset at every
  horizon would be something else, and probably the calibration lens.
- the short arm has the BETTER precision (1.000 against 0.667) and the worse
  recall (0.500 against 1.000): it missed a real low. That pairing is the trap.
  A shorter tail leaves less insulin on board late, so it predicts fewer lows
  full stop — precision improves by arithmetic. Recall is the column that says
  whether the real lows were still caught, and on a medical path it is the one
  that decides.
