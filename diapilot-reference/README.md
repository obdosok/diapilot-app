# DiaPilot Stage 0

A research CLI tool: from an xDrip export (SQLite) it computes a personal ISF,
validates the "digital twin" idea, and gives computed observations about corrections.
A personal tool, **not a medical product**: it shows computations together with the data
they are based on, and gives no dosing instructions.

## Installation
```
pip install pandas numpy matplotlib
```

## Commands
```
# 1. Personal ISF: full report + charts
python -m python_core analyze export.sqlite --configured-isf 2.0
python -m python_core analyze export.sqlite --max-dose 3      # cleaner of meal contamination

# 2. Digital twin validation (retrospective)
python -m python_core twin export.sqlite --eval-days 30 --fingersticks-per-day 6

# 3. Correction calculator ("second opinion", not a prescription)
python -m python_core suggest export.sqlite --bg 12.0 --iob 90:3

# 4. Retrospective review of real corrections
python -m python_core review export.sqlite --window-days 90
```

## Structure
- `python_core/io_xdrip.py` — reading and normalizing xDrip SQLite
- `python_core/episodes.py` — detection of clean correction episodes
- `python_core/analysis.py` — ISF aggregates, confidence levels
- `python_core/report.py` — ISF report + charts
- `python_core/twin/` — digital twin (kernel G(τ), simulator, experiment, report)
- `python_core/cli.py` — CLI (analyze / twin / suggest / review). A
  research-era correction advisor existed at this stage; it is deliberately
  excluded from this publication, and the Android app never had one — the app
  does not propose doses.
- `tests/` — 30 contract tests + synthetic database generator

## Specifications
- `diapilot-stage0-spec.md` — part 1 (ISF)
- `diapilot-stage0-twin-spec.md` — part 2 (digital twin)

## Key findings on real data
- Carbs are not recorded anywhere in the database → the main limiting factor for the
  analysis; hence the priority on automatic meal detection in stage 1.
- The apparent ISF drops with dose size (higher at low doses, lower at high doses) — a
  signature of meal boluses masquerading as corrections.
- The regimen can shift over time (a lifestyle change): median dose size drops, the
  share of large boluses drops, and sensitivity increases. Hence the calculator
  computes over a recent window rather than the full history.
- An insulin-only twin beats a naive baseline only over a short recalibration horizon;
  over a long horizon it is sunk by unrecorded meals — the quantitative case for
  "smart fingerstick" features and meal data collection.

## Stage 1 (data collection) — core
- `python_core/collector/xdrip_broadcast.py` — xDrip parsers (glucose broadcast + treatments)
- `python_core/collector/store.py` — local SQLite schema (core + annotations)
- `python_core/collector/meal_detect.py` — automatic meal detection from the BG curve
- Command: `python -m python_core collect-detect export.sqlite`
- Specification: `diapilot-stage1-spec.md`

Integration with xDrip (verified): glucose — broadcast `com.eveningoutpost.dexdrip.BgEstimate`
(extras BgEstimate mg/dL, Time ms, BgSlopeName); insulin — a local Nightscout-compatible
web service from xDrip on 127.0.0.1:17580. Both channels are local, no cloud.
