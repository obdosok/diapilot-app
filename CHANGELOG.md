# Changelog

All notable changes to DiaPilot are recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims at [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Two things to know about this file. **It starts at 1.3.0**: the development
history before the public snapshot narrates real readings, so it stays private
and is not reconstructed here — see "About this repository" in the
[README](README.md). And **the tracks are numbered together**: the OSS build
carries the plain tag, the store build takes the same tag with `-store` about a
week later, as [`docs/roadmap.md`](docs/roadmap.md) describes.

## [Unreleased]

Work that is planned but not in a tag yet. The gates are in
[`docs/roadmap.md`](docs/roadmap.md); the findings are in
[`docs/audit.md`](docs/audit.md).

### Planned for 1.4.0 (phase O-B, "first users")

- Onboarding: the disclaimer, manual ISF / ICR / weight, insulin presets, and a
  visible calibration status, so the bundled example person no longer drives a
  stranger's forecast on day one (audit M2, P2).
- Body weight wired into the carbohydrate-sensitivity prior (audit M4).
- Insulin tail domain raised so a five-hour tail is reachable, and a warning
  when the measured end of action lands short of it (audit M1).
- Release signing with a real upload key, and signed APKs on GitHub Releases
  (audit S8).
- An English food parser: the offline note reader is Russian-only today
  (audit P1).
- A Nightscout glucose source, and a diagnostics export that carries no values.

## [1.3.0] — phase A, "portfolio" — September 2026

The release that makes the repository readable by a stranger: the security
findings that harm a third-party user today are closed, and the engineering
record moved out of the code into documents. No model mathematics changed in
this release — the golden files in `core/src/test/resources` are byte-identical.

### Security

Closes the high findings from [`docs/audit.md`](docs/audit.md). The threat model
is other apps on the same phone; [`SECURITY.md`](SECURITY.md) states it in full.

- **The local HTTP server now requires a token.** Recording a treatment is
  POST-only and needs a per-installation token, and so does the event
  journal a companion app reads history from; neither is open any longer to
  every app on the device holding `INTERNET`. The token is minted from
  `SecureRandom`, kept in the platform keystore, and read off the Settings
  screen (audit S1, and the journal half of S2). The watch-face feed
  `/info.json` is deliberately unchanged — it is a fixed contract an
  existing watch face depends on — so that half of S2 stays open.
- **Every insulin input is bounded.** A dose arriving from the watch endpoint,
  from xDrip's treatments, or from an LLM command passes the same plausibility
  and magnitude check before it is written; a zero or absurd value is refused
  rather than stored (audit S1, S4).
- **Glucose broadcasts are bounded and attributed.** Incoming readings must fall
  in a physiological range and inside a time window around now, and a reading
  from one source can no longer silently rewrite stored history recorded by
  another (audit S3).
- **The plausibility gate on the forecast anchor is on by default** instead of
  being an unreachable flag (audit S5).
- A scanned barcode is validated before it is put in a lookup URL
  (audit S11).

### Added

- [`docs/forecast-engine.md`](docs/forecast-engine.md) — the engineering record
  of the forecast: why the model has the shape it has, what each of the engine's
  and the fitter's knobs means, and an index of what was measured and rejected.
  It replaces the essay that used to live in four constructors (audit A9).
- [`docs/history.md`](docs/history.md) — the dated audit logs, unabridged: what
  was removed and why, the food and insulin branch audits, the closed questions.
- [`docs/screenshots.md`](docs/screenshots.md) — which screens go in the README,
  in which language, what must never be in frame, and how to capture them.
- [`CHANGELOG.md`](CHANGELOG.md) — this file.
- `:tools:accuracy` and [`docs/accuracy.md`](docs/accuracy.md) — a JVM tool
  that reads a forecast ledger and reports bias, MAE and RMSE per horizon
  plus the low alert's precision and recall, so the app's accuracy claims
  become reproducible instead of living in comments (audit M9). It depends
  on neither `:app` nor `:core` and ships in no APK.
- A Status section in the README pointing at the audit and the roadmap, an
  Install placeholder, and slots for the screenshot gallery.

### Changed

- [`docs/architecture.md`](docs/architecture.md) is now a ten-minute overview
  with one diagram — module boundary, data flow, where the model lives. Its
  historical half moved to `docs/history.md` unchanged.
- The four model files that carried the essay —
  `core/hybrid/HybridForecastEngine.kt`,
  `core/physio/SegmentLandmarksV1.kt`, `core/physio/PhysioAutoFitV1.kt` and
  `app/data/PhysioRuntime.kt` — keep a short KDoc per parameter plus a pointer
  to the section that explains it. Comments only; no code and no numbers
  changed.
- [`SECURITY.md`](SECURITY.md) states the threat model and the protections the
  app relies on, not only the reporting channel.
- Screens no longer reach for the database themselves: `MainActivity` builds
  one `AppGraph` and publishes it, and every composable takes its store from
  there. Services, workers and the widget still use the process-wide handle,
  so this closes the UI half of audit A1 and no more.
- Every inline `io.github.obdosok.diapilot.*` reference in `:app` is an
  import and a short name — 502 of them across 40 files (audit A8).
- The README no longer claims that insulin sensitivity is learned from clean
  corrections. That pipeline was removed: the applied ISF is entered by hand,
  the insulin *shape* is what is measured, and a daily balance is shown
  alongside (audit M8).

### Known issues

Carried openly rather than closed. The full list, by number and with file
references, is [`docs/audit.md`](docs/audit.md).

- There is no uncalibrated mode: from the first minute the bundled synthetic
  example person drives the forecast, the low alert and the watch hint
  (audit M2).
- The measured insulin tail is short by construction — the instrument's window
  cannot reach a five-hour end of action (audit M1).
- Horizon shrinkage is applied to food, background and drift but not to
  insulin, which biases the line low exactly where the low alert decides
  (audit M5).
- Protein and fat move timing only, never amplitude (audit M6).
- The release build is still signed with the debug keystore (audit S8), and
  backups are written unencrypted (audit S6).
- The offline food-note parser reads Russian under an English UI (audit P1).
- The accuracy tool exists, but no figures are published with it yet: the
  report needs a real device database, and the bundled golden set contains
  no ledger to run it against (audit M9).
- The watch-face feed on the loopback port still answers without a token,
  and `?graph=1` returns three hours of history with it (audit S2).

DiaPilot is not a medical device and has not been clinically validated.
