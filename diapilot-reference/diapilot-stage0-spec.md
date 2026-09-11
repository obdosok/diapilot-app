# Spec: DiaPilot Stage 0 — computing a personal ISF from an xDrip export

> A self-contained specification for implementation by a coding agent (Claude Code).
> How to run: put this file in an empty folder next to the xDrip export file and give
> the agent the prompt:
> "Implement the project per the spec in diapilot-stage0-spec.md. The data is in
> export.sqlite. Work through the steps in the 'Order of work' section, and show the
> result after each step."

## 1. Goal

A Python CLI tool that computes a **personal ISF** (Insulin Sensitivity Factor — how
many mmol/L 1 unit of insulin lowers glucose by) from an xDrip database export
(SQLite), using "clean" correction boluses, broken down by time of day with
statistical confidence levels, and compares the result against any value the user
supplies.

This is a research tool for a single user, not a medical product. It **gives no
dosing recommendations** — only computed observations, shown together with the data
they are based on.

## 2. Input data

- An SQLite file — an xDrip database export (`export.sqlite` in the project root).
- **The xDrip DB schema can differ between versions. The first step is to inspect the
  actual schema**, not assume names. Expected entities:
  - Glucose readings: a table like `BgReadings` (fields like `timestamp` in ms epoch,
    `calculated_value` in mg/dL).
  - Treatment records: a table like `Treatments` (fields like `timestamp`, `insulin`
    (units), `carbs` (g)).
- Units: glucose is stored in mg/dL; all output is in mmol/L (divide by 18.0182). Time
  is the user's local time (confirm the timezone from the data or the user; default
  UTC, so that results do not depend on the machine running the analysis).
- If the structure doesn't match expectations, stop and show the user the actual
  schema with a question, rather than guessing.

## 3. Key definitions

### Clean correction episode
A bolus whose effect on glucose is not confounded by food or other boluses:

- Bolus B at time t0, dose ≥ 0.5 units.
- **No carbs** in the window [t0 − 2 h; t0 + 4 h] (carbs = 0 or NULL in every
  Treatments row in the window).
- **No other boluses** in the window [t0 − 3 h; t0 + 4 h].
- Starting glucose BG(t0) ≥ 8.0 mmol/L (a correction is given for a high reading).
- CGM coverage: at least 1 point in [t0 − 15 min; t0] and ≥ 80% point coverage in
  [t0; t0 + 4 h] (xDrip's expected step is ~5 min).
- The episode is dropped if glucose fell below 3.9 mmol/L within [t0; t0+4h] (likely
  intervention — a hypo treated with food that may not have been recorded).

### Per-episode ISF calculation
- BG_start = median of readings in [t0 − 15 min; t0].
- BG_end = median of readings in [t0 + 3 h; t0 + 4 h] (by this time most of the rapid
  insulin analog's action has run its course).
- episode_ISF = (BG_start − BG_end) / dose, in mmol/L per unit.
- Episodes with episode_ISF ≤ 0 are not silently discarded — they are counted and
  shown separately as anomalies (a rise despite a correction is an interesting
  signal: unrecorded food, infusion failure, dawn phenomenon).

### Aggregate confidence levels
- < 5 valid episodes: "insufficient data" — episode list only, no aggregate.
- 5–14: "preliminary estimate" — median + IQR.
- ≥ 15: "stable estimate" — median + IQR + breakdown by time of day.

### Breakdown by time of day
Four windows by t0: night 00–06, morning 06–12, afternoon 12–18, evening 18–24. Each
window gets its own aggregate with its own confidence level (based on the number of
episodes in that window).

## 4. Functional requirements

1. `analyze` — the main command: path to sqlite → full report.
2. Report (stdout + a `report.md` file):
   - data period, total readings, total boluses;
   - number of clean episodes found (+ how many candidates were rejected and why — a
     table of rejection reasons);
   - episode table: date/time, dose, BG_start → BG_end, ISF, anomaly flag;
   - aggregate: median ISF, IQR, confidence level;
   - breakdown by time of day (if there is enough data);
   - comparison with the configured value: option `--configured-isf X` → "computed
     median ISF: Y; configured: X; deviation: Z%", with the wording "discuss with your
     doctor before changing settings" when the deviation is > 20%.
3. Charts (PNG in `./output/`):
   - an overlay of all clean episodes: normalized ΔBG curves from bolus time
     (0–4 h), semi-transparent, with a median curve on top. This is a visual
     precursor to a "personal insulin action curve";
   - histogram of per-episode ISF;
   - ISF by time of day (box plot), if there are ≥ 15 episodes.
4. Options: `--min-dose`, `--start-bg`, `--tz`, window thresholds — with the defaults
   from section 3, so the result's sensitivity to the criteria can be explored.

## 5. Non-functional requirements

- Python 3.11+, dependencies: pandas, numpy, matplotlib; sqlite3 from stdlib. No heavy
  ML libraries — stage 0 is plain statistics.
- Structure: package `diapilot_stage0/` (io_xdrip.py — reading and normalization;
  episodes.py — detection; analysis.py — calculations; report.py; cli.py), `tests/`.
- **Tests are mandatory and written before implementation** (contract): a synthetic
  mini-DB fixture — a generator that creates a sqlite file with given
  readings/boluses; cases: a clean episode is found; an episode with carbs in the
  window is dropped; an episode with a hypo is dropped; overlapping boluses are
  dropped; ISF is computed correctly on synthetic data with a known answer;
  confidence levels switch based on episode count.
- Code and comments are in English; the `report.md` report is in Russian.
- No network calls: the tool is fully offline (medical data never leaves the
  machine).

## 6. Explicit non-goals for stage 0

- No UI, mobile app, servers, or LLM.
- No basal calculation, carb ratio, or injection-site curves (those are stage 1+).
- No dosing recommendations — computed observations only.
- The digital twin is a separate specification, to follow after this one succeeds.

## 7. Order of work (for the agent)

1. Inspect the actual sqlite schema (`.tables`, PRAGMA) — show the user the tables/
   fields found and confirm the mapping.
2. Write the tests (section 5) with the synthetic DB generator.
3. Implement io → episodes → analysis → report until the tests are green.
4. Run it on a real export, show the report.
5. Review together with the user: does the tool find the corrections the user
   expects; is the ISF plausible; how many episodes are lost at each filter — loosen
   thresholds via options if needed and check the sensitivity.

## 8. Acceptance criteria

- Tests green; on a synthetic DB with a known ISF the tool returns it within ±5%.
- On a real export: the report is generated, rejection reasons are transparent, all
  numbers are reproducible on a repeat run.
- In one run, the user can get an answer to: "what is the user's actual ISF from the
  data for this period, with what confidence, and how does it differ from what's configured in
  xDrip."
