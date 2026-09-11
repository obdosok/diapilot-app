# Reference for Claude Code

This folder is context for implementing the DiaPilot Android app. It is not the app's
source code, but the instructions and the validated Python logic that need to be
ported to Kotlin.

## Reading order
1. `README.md` — project map and key findings on real data.
2. `diapilot-stage1-spec.md` — spec for the current stage (data collection). The MAIN
   one right now.
3. `diapilot-stage0-spec.md`, `diapilot-stage0-twin-spec.md` — specs for the analytics
   layer (for future stages).

## What to port to Kotlin NOW (stage 1)
- `python_core/collector/xdrip_broadcast.py` — xDrip glucose broadcast + treatments parsers.
- `python_core/collector/store.py` — storage schema → maps to Room entities.
- `python_core/collector/meal_detect.py` — meal detector from the BG curve.
- `python_core/tests/test_collector.py` — edge cases (dedup, malformed data, what counts as a meal).

## What to wire in LATER (the app's analytics layer)
- `python_core/twin/`, `python_core/episodes.py`, `analysis.py`, `advisor.py`, `io_xdrip.py`.

## Target architecture
- `core` (no Android SDK dependencies): the GlucoseSource interface, Room schema, detectors.
- `app` (a thin Android layer): a BroadcastReceiver on `com.eveningoutpost.dexdrip.BgEstimate`,
  a WorkManager worker polling `127.0.0.1:17580` (xDrip's Nightscout-compatible API), the
  labelling screen.
- The core/platform boundary is set from the first commit, so a future iOS/KMP port
  doesn't break the code.

## Do not commit
An export file with real data (export.sqlite) is private medical data and has no
place in the repository.
