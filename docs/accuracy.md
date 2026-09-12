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

Both metric functions (`errorStats`, `evaluateHypoAlert`,
`hypoConfusion`) are pure and unit-tested on hand-written vectors in
`tools/accuracy/src/test/kotlin/.../MetricsTest.kt` — no database involved in
the tests, only in the CLI.

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

Hypo alert vs what happened, consumer='hypo_alert' (3 runs scored)
TP=1  FP=1  FN=1  TN=0
precision = 0.500
recall    = 0.500
```

n=1 per horizon is the tell: this is a demonstration of the tool's output
shape, not a report anyone should read as "the model's accuracy". A real
report needs the multi-week, multi-hundred-run history a phone actually
accumulates — see USER ACTION above.
