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

Work done and work still planned, neither in a tag yet. The gates are in
[`docs/roadmap.md`](docs/roadmap.md); the findings are in
[`docs/audit.md`](docs/audit.md).

### Security

The September second read of [`docs/audit.md`](docs/audit.md) re-checked every
status against the code and closed what it found; the numbers are the audit's.

- **Every glucose parser is bounded, not just the broadcast.** The xDrip
  web-service backfill (`/sgv.json`, `/pebble`) and the OOPAlgorithm2 minute
  stream accepted any value above zero at any timestamp; a squatter on the
  loopback port or any app sending the OOP2 broadcast could park a reading in
  the future as the freshest anchor. Values must now sit in 20–600 mg/dL,
  backfill timestamps inside the last fourteen days and never more than five
  minutes ahead, minute-stream timestamps inside the broadcast window; the
  poll and the minute receiver are skipped while xDrip or OOPAlgorithm2 is not
  installed (audit S4, S13).
- **Stored doses have source ownership like stored readings.** A re-sync from
  one source can no longer restate a bolus recorded by another; a dose the
  user edited by hand is never overwritten (audit S12).
- **The dose fuse covers every input.** A NovoPen scan, a hand-typed bolus or
  basal and the rows of a restored backup pass the same plausibility check as
  the sockets and the LLM command; a refused pen dose is announced, not
  dropped (audit S14, S7).
- **Restore validates before it swaps.** `PRAGMA integrity_check`, a schema
  version no newer than the app's, the core tables present, no impossible
  dose; the displaced database is kept for seven days behind an "Undo last
  restore" button, and the swap happens under the store's own lock (audit S7).
- **Optional backup password.** With one set in Settings, every backup —
  Downloads, cloud folder, export, companion upload — is an AES-256-GCM file
  (`*.sqlite.enc`, format `DPBK1`, PBKDF2-HMAC-SHA256 with 200 000
  iterations) and restore asks for the password. The default stays plaintext:
  a forgotten password loses the history (audit S6).
- **Libre 2 pairing state moved into the keystore** with the other secrets,
  migrated from the plain preferences on first read (audit S16).
- **The loopback HTTP server bounds its input**: header lines, header count
  and concurrent connections are capped (audit S15); an NFC tag whose id is
  not eight bytes is ignored instead of crashing the scanner (audit S17).
- **Companion server**: uploads land in a temp file and replace the old
  backup only on success, request bodies are size-limited, a malformed push
  is a 400, and ten wrong tokens from one address lock it out for a minute;
  its README no longer suggests a cleartext LAN URL (audit S9).
- **Release signing is wired**: the build reads a key from the environment or
  a gitignored `keystore.properties`, warns loudly when it has to fall back to
  the debug key, and the tag-triggered release workflow refuses to publish a
  debug-signed APK. No key has been created yet (audit S8).

### Added

- **A first-run flow and an uncalibrated mode** (audit M2, P2): the
  disclaimer with an explicit acceptance, language, body weight, insulin
  sensitivity in either unit, one of three insulin-action presets written into
  the hand-entered tier, optional carbohydrate sensitivity (its setter did not
  exist before — audit M4), and a summary. Until the flow is finished the
  forecast is refused — no line, band, What-if, predictive low or high,
  `predictBWP` or watch prediction lines — while every reading-based alert
  keeps firing. Today and the model section show whether the model runs on
  the example person, on hand-entered values, or on a curve measured from the
  user's own doses. An install that already holds a day of readings and a
  hand-entered ISF is adopted with the disclaimer page only.
- **Two naive baselines in the accuracy tool** (audit M13): last value and a
  15-minute linear extrapolation, scored on the same set as the model per
  horizon, with the skill score beside them.
- A GitHub Releases workflow on `v*` tags, `keystore.properties.example`, and
  `:app:lintOssDebug` plus the release build type in CI.
- Two editions from one trunk: an `oss` build with the forecast, and a `store`
  build that collects, logs and summarises the past without stating anything
  about later. One branching point in the code
  ([`docs/editions.md`](docs/editions.md)).
- A **Data sources** screen (audit P6): every link of the collection chain —
  xDrip and its broadcast, its web service, Nightscout, the minute stream, the
  own-BLE link, the collector service, the battery policy, notifications, exact
  alarms, Health Connect, the overlay, NFC — with a status, one line of what
  breaks while it is red, and the system screen that fixes it; plus a banner on
  Today when data stops arriving.
- The offline food-note parser reads English as well as Russian: dish
  aliases, number words, unit words (grams, millilitres, ounces, cups,
  tablespoons, teaspoons) and per-unit markers, plus a typical-grams table for
  portion words ("2 slices", "a cup", "a handful") and diacritic-insensitive
  matching ("crème"/"creme"). The Open Food Facts barcode lookup now asks for
  the label in the app's own language (audit P1).
- A **diagnostics export that carries no values** (audit S10): a text file a
  tester can share in which every glucose value, dose, carbohydrate amount,
  note and device identifier is replaced by its unit and shape. The logs it
  draws on no longer print those values either.
- Body weight reaches the carbohydrate-sensitivity prior, so the forecast's
  global carbohydrate factor follows the weight the user entered instead of a
  fixed constant (audit M4).

### Changed

- **The bundled example person no longer drifts upward.** Its background
  intercept encoded an equilibrium near 9.5 mmol/L, which biased every fresh
  install's forecast high — the direction that hides a predicted low. The
  intercept is 0.0 now; an install still running the bundled model picks the
  new file up on the next launch (audit M11).
- The production forecast path is named for what it is: `HybridShadow` became
  `PhysioForecastBridge`; the identifier it writes into the ledger is
  unchanged (audit A10). The shipped engine is built through one factory
  instead of 23 hand-written constructor calls (audit A11).
- A failing model artifact is logged instead of silently becoming "no
  forecast" (audit A12); one stale Russian-token reader that survived the v50
  label migration reads the key now (audit A13); one mg/dL constant instead of
  three (audit A14).
- `versionName` is `1.4.0-dev`.
- The insulin tail domain starts at 240 minutes rather than 120 — the reach of
  the instrument that measures it — the bundled example person's tail moved to
  300 minutes, Auto-fit searches 240–480, and the settings card marks a
  measured end of action that lands short of the floor (audit M1, the first
  half; the measurement itself is unchanged and still lands short).

### Known issues carried into 1.4.0

- A measured or hand-entered insulin curve is dose-independent in duration;
  only the parametric fallback lengthens the tail for a larger dose
  (audit M10, pinned by test, waits for a bench).
- The uncertainty band is several-fold narrower than the measured error and
  its calibration is in-sample (audit M12).
- The xDrip broadcast can still be spoofed with a plausible value by any app
  on the phone; the bounds narrow the attack, they do not remove it (audit S3).
- `/info.json?graph=1` on the loopback watch feed still answers without a
  token (audit S2).
- Backups are plaintext unless the user sets a password (audit S6).

### Planned for 1.4.0 (phase O-B, "first users")

- The maintainer's release keystore, and the first signed APKs on GitHub
  Releases (audit S8 — the build and the workflow are ready).
- A Nightscout glucose source — the data-sources screen already has its row.

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
