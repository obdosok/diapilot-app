# Audit — September 2026

An AI-assisted read of the `public` snapshot, performed with Claude: code quality, security, the
forecast model, architecture, and release readiness. Everything below was
checked against the code at commit `0122a82`; file references are to that
tree. `./gradlew :core:test` was run (844 tests, 0 failures).

Severity: **H** — harms a third-party user today; **M** — must be closed
before a release to strangers; **L** — hygiene.

**Second read, September 2026.** A second AI-assisted pass over the tree after
phase B checked every Status cell against the code rather than against this
file, and found three of them overstated: S3 and S4 said Closed while the
web-service poll and the OOP2 minute stream were still bounded only by `> 0`,
and the note under the security table said minute readings "never reach
analytics" while `GlucosePresentation.forecastAnchor` prefers them. The
re-audit added S12–S17, M10–M13 and A10–A14 below; the `review/*` branches
named in the Status column are what closed them, and everything that branch
set changed is in `CHANGELOG.md` under Unreleased.

**Status** started at the phase-A gate (tag `v1.3.0`) and is kept current
as later phases close findings. It is one of: **Closed**, with the branch
that closed it; **Open**; or **Deferred**, with the phase in `roadmap.md`
that plans it. A finding is Closed only when every mechanism it names is
addressed — where a phase closed part of one, the status stays Open and
says which part moved. The findings themselves are unchanged; only the
Status column and a few file references were updated.

---

## Security

The app has almost no remote surface (no server, no account; outbound only
HTTPS to Anthropic and Open Food Facts). The real surface is **other apps on
the same phone** and **the media the backups land on**.

| # | S | Status | Finding | Where |
|---|---|---|---|---|
| S1 | H | Closed (phaseA/a1) | `GET /add_treatments?insulin=X` on `127.0.0.1:29863` writes a bolus with no authentication, no fuse (`takeIf { it > 0 }`), no method/Origin/Host check; a GET with a side effect. Any app holding `INTERNET` can inject a dose. The LLM path has `commandMaxBolusUnits`; this path has nothing. | `app/.../collect/WatchServer.kt:394` |
| S2 | H | Open — a1 gated `/api/v1/events`; `/info.json?graph=1` is still unauthenticated | The same socket serves `/info.json?graph=1` and `/api/v1/events` — the full glucose / insulin / meal history — without authentication. | `app/.../api/DiaForApi.kt` |
| S3 | H | Open (mitigated) — a1 added the 20–600 range, the −20/+5 min window and same-source ownership on `glucose_readings`; review/a-inputs gave the OOP2 receiver the installed check and `parseOop2Trend` the same bounds. Residual: an Android broadcast carries no sender identity, so a plausible "140 now" from any app lands under `source="xdrip_broadcast"` and can restate the real row at that grid slot. Closing it needs a permission-protected receiver or xDrip's Identify receiver, both of which change the xDrip setup every user has | Glucose spoofing: `XdripBgReceiver` is `exported="true"` with no permission and is also registered `RECEIVER_EXPORTED`; the OOP2 receiver likewise. `parseBgBroadcast` accepts any `mgdl > 0` and any `ts > 0`. `upsertReading` uses `ON CONFLICT(ts_ms) DO UPDATE`, so stored history can be rewritten at any timestamp. Worst case is not a false alarm but a suppressed real one: a forged "8.0 now" becomes the forecast anchor. | `AndroidManifest.xml:96`, `core/.../collector/XdripBroadcast.kt:56`, `app/.../data/SqliteCollectorStore.kt:960`, `collect/CollectorService.kt:67,232` |
| S4 | H | Closed (review/a-inputs) — a1's `api-secret` header authenticates the phone to whoever holds the port and nothing about the port's holder; the poll also ran unconditionally and `parseSgvEntries` / `parsePebbleBg` were bounded only by `> 0`, so a squatter on 17580 could park a reading an hour in the future as the freshest anchor. Now: 20–600 mg/dL, timestamps inside [now − 14 d, now + 5 min] (the backfill depth on the past side, the broadcast window on the future side), and the poll is skipped altogether while xDrip is not installed | The xDrip web service on `127.0.0.1:17580` is polled without `api-secret`. With xDrip not running, any app can bind the port and feed `/sgv.json` and `/treatments`; insulin from treatments is written with no fuse. | `app/.../collect/TreatmentsPollWorker.kt:113,244` |
| S5 | M | Closed (phaseA/a1) | The plausibility gate is off by default and applies at read time only. | `app/.../data/Settings.kt:349` |
| S6 | M | Open (mitigated) — review/b-backup added an optional backup password: with one set, every writer (Downloads, SAF folder, Settings export, companion upload) produces a `DPBK1` file — AES-256-GCM in 1 MiB chunks, key from PBKDF2-HMAC-SHA256 with 200 000 iterations and a per-file salt — named `*.sqlite.enc`, and restore asks for the password. The default stays plaintext on purpose: a forgotten password loses six weeks of history, and onboarding does not yet ask for one | The whole medical database is backed up unencrypted to three places: public Downloads, an arbitrary SAF folder (i.e. a third-party cloud, contradicting "data lives on the phone"), and the companion server (`data/backups/`, plaintext). | `app/.../data/BackupRestore.kt` |
| S7 | M | Closed (review/b-backup) — `restore()` now runs `PRAGMA integrity_check`, refuses a `user_version` above `DB_VERSION` or below 1, requires the three core tables, refuses a file whose `insulin_events` or `basal_events` carry a dose above the fuse, keeps the displaced database as `restore_rollback.sqlite` for seven days behind an Undo button, removes `-journal` with the other sidecars, and swaps under the `Stores` monitor so no handle reopens the old file mid-swap | `restore()` runs no `PRAGMA integrity_check` and overwrites the live database with no rollback copy. | `BackupRestore.kt:73` |
| S8 | M | Closed in code (review/e-release) — the release build type reads a signing key from `DIAPILOT_KEYSTORE_*` environment variables or a gitignored `keystore.properties`, falls back to the debug key only with a loud `[audit S8]` warning, and the tag-triggered release workflow refuses to publish an APK whose certificate is `CN=Android Debug`. The keystore itself has not been created yet, so no APK is release-signed until the maintainer runs the `keytool` line in `keystore.properties.example` | The release build is signed with the debug keystore (well-known password, unprotected file). Anyone who obtains it can sign an "update" that installs over the user's data. | `app/build.gradle.kts:62` |
| S9 | L | Closed (review/b-backup) — `/api/backup` writes to a temp file and `os.replace`s on success, both endpoints answer 413 above `COMPANION_MAX_BACKUP_BYTES` / `COMPANION_MAX_PUSH_BYTES`, a non-object push body is a 400, ten failed tokens from one address lock it out for 60 s, and `server/README.md` no longer suggests a cleartext LAN URL | Companion server: `/api/push` reads unbounded JSON into memory, `/api/backup` writes unbounded uploads to disk, no rate limit on token guesses. `server/README.md` suggests `http://<LAN IP>:8787`, which the app's network security config refuses (cleartext is loopback-only); the root README claims "one LAN host listed" but the file lists none. | `server/main.py`, `app/src/main/res/xml/network_security_config.xml` |
| S10 | L | Closed (phaseB/b7) | Glucose values and hypo decisions are logged at INFO level via `android.util.Log`; no log stripping in release. Readable only via adb / bug report, but a shared bug report carries hours of history. | `collect/HypoAlertNotifier.kt:395`, `collect/XdripBgReceiver.kt:60` |
| S11 | L | Closed (phaseA/a1) | The barcode is interpolated into the OFF URL unencoded. Scanner formats are EAN/UPC only, so practically inert; validate `^\d{8,14}$` anyway. | `app/.../data/OpenFoodFacts.kt:70` |
| S12 | H | Closed (review/a-inputs) | **Insulin history had no source ownership.** The "one source owns a timestamp" rule of S3 was applied to `glucose_readings` only; `upsertInsulin` let any writer restate any row. Chain: `/info.json` (open, S2) returns the last bolus time; a squatter on 17580 (S4) serves `/treatments` with that timestamp and `insulin: 0.1`; the real 6 U becomes 0.1 U, IOB collapses, the forecast reads high, a real low is suppressed — the threat model's own worst case. Now `… WHERE insulin_events.source IS excluded.source`; a hand edit (`user_edited = 1`) still wins over every re-sync. Writers and their `source`: `xdrip_treatments`, `watch`, `pen_nfc`, `manual`. | `app/.../data/SqliteCollectorStore.kt` `upsertInsulin` |
| S13 | H | Closed (review/a-inputs) | **The OOP2 minute stream reached the forecast anchor unbounded.** The receiver was `RECEIVER_EXPORTED` with no installed check and `parseOop2Trend` accepted any `mgdl > 0` at any timestamp; this file said the stream "never reaches analytics", but `GlucosePresentation.forecastAnchor` prefers the minute reading when it is newer than the main one, and that anchor feeds the watch, the companion push and the hypo pass. Now: the receiver drops every intent while `com.hg4.oopalgorithm.oopalgorithm2` is absent, the payload timestamp must sit inside −20/+5 min, each value inside 20–600 mg/dL, and the two decode-reply receivers in `LibreOop2Bridge` carry the same check. | `collect/CollectorService.kt`, `core/.../collector/XdripBroadcast.kt`, `data/GlucosePresentation.kt` |
| S14 | M | Closed (review/a-inputs) | The dose fuse (`commandMaxBolusUnits` = 12, `commandMaxBasalUnits` = 40) covered the two sockets and the LLM command but not a NovoPen scan or a hand-typed bolus/basal — the "three inputs never socket-fed" left behind by phase A. All three now pass `validateCommandValues`; a refused pen dose is announced on every scan rather than dropped, and a refused typed dose shows the guard's own reason. | `nfc/PenDoseSaver.kt`, `MainActivity.kt`, `ui/AnnotationComposer.kt` |
| S15 | L | Closed (review/a-inputs) | The loopback HTTP parser read header lines with no length or count cap on a thread per connection, so a peer on the same phone could exhaust the process that also runs the alert pipeline. Now 8 KB per line (414/431), 64 headers, 16 concurrent connections (503 in-line, no thread). | `collect/WatchServer.kt` |
| S16 | L | Closed (review/b-backup) | Libre 2 pairing material — sensor UID, patch info, the unlock array — lived in plain `SharedPreferences` while this file said secrets stay in the keystore. Now a `SecretStore` entry, migrated from the plaintext on first read and the plaintext deleted. | `data/Libre2State.kt` |
| S17 | L | Closed (review/a-inputs) | Reader mode accepts NFC-A/B tags too; `uid[6]` in the NFC scanner and an 8-byte `copyInto` in `LibreFram` threw on a shorter id — a crash, not corruption. Ids that are not 8 bytes are now ignored with a diagnostic line. | `nfc/LibreNfcScanner.kt`, `core/.../libre/LibreFram.kt` |

**What phase A left behind here.** `/info.json` is deliberately still
open: it is the frozen watch-face feed, and `?graph=1` serves 3 h of
history with it (S2). The OOP2 receiver named in S3 did not get the
value window or the xDrip-installed check — it feeds `minute_readings`,
which never reaches analytics, but it does anchor own-BLE readings
through the minute calibration. `parseSgvEntries` is still bounded only
by `> 0`, because the sgv backfill legitimately returns ~14 days and a
window around now cannot apply to it. And the dose fuse now covers both
sockets but not the three inputs that were never socket-fed: a
hand-typed bolus or basal, a NovoPen scan, and restore from the app's
own export.

**What the second read closed here (review/a-inputs, review/b-backup,
review/e-release).** Every sentence of the paragraph above except the first
is now false in the right direction: the OOP2 receiver has the value window
and the installed check (S13), `parseSgvEntries` is bounded on both sides —
fourteen days into the past, five minutes into the future (S4) — and the
fuse covers the pen, the hand entry and the restored file (S14, S7). What is
still open by choice: `/info.json?graph=1` (S2), the plaintext default for
backups (S6), the residual broadcast spoof (S3), and the keystore the
maintainer still has to create (S8).

**What phase B closed here (b7).** S10 was two halves. The logs: every line
that printed a glucose value, a dose, a carbohydrate amount or a device
identifier now prints the unit and the decision instead — `collect/`
(`HypoAlertNotifier`, `XdripBgReceiver`, `CollectorService`,
`TreatmentsPollWorker`) and `nfc/PenNfcScanner`. The bug report: the
collection chain's own lines are kept in an in-memory ring
(`diag/DiagLog.kt`) and leave the phone through a value-free export
(`diag/DiagnosticsBundle.kt`), so answering "did collection work on your
phone" no longer needs `adb` or a bug report carrying hours of history.
`data/DiagnosticsExport.kt` — the maintainer's own `diag.json`, which does
carry values — is untouched and still in the release build; moving it out is a
later package.

**Checked and sound:** `SecretStore` (AES-256-GCM in AndroidKeyStore, AAD bound
to the secret's name, never rewrites plaintext, logs only the name and the
exception class); manifest (`allowBackup=false` + `dataExtractionRules`,
FileProvider not exported and limited to two named subdirectories —
`photos/` plus the `diagnostics/` path b7 added, never a root open onto the
sandbox, `LabelActionReceiver` / `BootReceiver` not exported); the LLM
command path (parse → `CommandGuard` → confirm card → user tap, never an
automatic write; API key only in the `x-api-key` header; chat is read-only);
SQL is parameterised, interpolations are table/column constants only; FRAM
parsing is size-checked (the UID length was the one gap, S17); no WebView, no `exec`; no secrets in git,
`local.properties` ignored, the gitleaks allowlist is narrow;
`HttpURLConnection` will not follow https→http.

---

## Model

| # | S | Status | Finding | Where |
|---|---|---|---|---|
| M1 | H | Open — b3 raised the domain floor to 240 (the instrument's own reach), moved the example model to tail 300 / main curve 200, pulled the Auto-fit `tail` corridor to 240..480 and marks a measured end under the floor on the settings card; b10 split the domain in two so the clamp applies only where an instrument feeds the model on its own — the MANUAL tier now has `insulinTailMinManualRange` = 120..600 and is applied as entered end to end, the measured landmark is stored and displayed as measured rather than as the floor it was lifted to, the Auto-fit corridor is held inside the floor instead of proposing under it, a hand-set tail locks that axis, and `:tools:accuracy` can split a ledger by the tail the phone was running. The INSTRUMENT is still untouched — `CAP_MS`, `TAIL_HORIZON_MIN` and the pre-dose-slope definition of "end" are the same, so every MEASUREMENT still lands in ~150..230 and is clamped rather than believed. The plateau end landmark stays deferred — O-D | **Insulin tail is short by construction.** Example model ends at 175 min; domain floor is 120. Against an exponential curve (peak 75, DIA 6 h) the example has 85% acted at 120 min vs ~55%, and 100% at 180 vs ~79% — IOB reads zero on the third hour. The cause is the *instrument*, not a constant: the per-dose window is capped at `CAP_MS = 240 min` and closed by the next injection; the end landmark needs `TAIL_HORIZON_MIN = 200` clean minutes; and "end" is defined as the rate returning to the pre-dose slope, which a rising background meets early (an earlier internal finding, M-54; the plateau alternative is computed but never substituted). A 240-minute ruler cannot measure five hours, so every user's measured end lands in ~150–230 and the 120..600 domain never clamps it. Manual timings (P1) win and accept tail 120..600, but Auto-fit's corridor is ±20% around the *measured* tail and will propose pulling it back unless the knob is locked. | `app/.../data/InsulinProfileRuntime.kt:44`, `core/.../physio/SegmentLandmarksV1.kt:104,278`, `core/.../physio/PhysioContracts.kt:18`, `core/.../physio/PhysioAutoFitV1.kt:364` |
| M2 | H | Closed (review/c-onboarding) — a first-run flow (disclaimer with an explicit acceptance, language, weight, ISF in either unit, an insulin-action preset written into the MANUAL tier, optional carb sensitivity, a summary) and an uncalibrated mode behind it: until the flow is finished `Forecaster` returns null and records a refusal, so there is no line, band, What-if, predictive low/high, `predictBWP` or watch prediction line, while every reading-based alert keeps firing. Today and the model section carry a `ModelCalibrationStatus` card — example person / hand-entered / measured from the user's doses. An install that already holds a day of readings and a hand ISF is adopted with the disclaimer page only | **No "uncalibrated" mode.** From the first minute the bundled synthetic person (ISF 1.8, insulin 20/75/175, CS 0.165) drives the forecast, the hypo alert and the watch hint. No onboarding screen, no calibration status; the only "not measured yet" strings live inside the tuning section. | `app/.../data/HybridModelStore.kt:24` |
| M3 | M | Deferred — O-D (needs n>1) | ISF, CS and DIA are not separately identifiable from meal days, and the pipeline holds CS constant while reading DIA off the CGM. The measured bias of +4.53 mmol at h=180 is attributed to food amplitude, but a short insulin tail produces the same sign; the fitter itself notes that an ISF read off the bias arm is "partly a food deficit wearing an insulin label". | `docs/architecture.md` §5, `core/.../physio/PhysioAutoFitV1.kt` (header) |
| M4 | M | Closed (phaseB/b3, setter in review/c-onboarding) — `foodDynamicsGlobalPriorV1` is called with `Settings.weightKg`, its median becomes the artifact's `globalCs` and so `food.globalFactor` on the live forecast path; the chain is stored manual override → weight → the 0.165 constant, and body weight is part of the artifact cache key. Tier one (`carb_sens_override_mmol_per_g`) now has a setter and the onboarding flow writes it | **Weight → CS is not wired.** The weight field exists and `CarbSensitivityPriorV1.fromWeight` exists, but the result is only shown as a hint under the field; `foodDynamicsGlobalPriorV1(weightKg)` is never called with a weight. CS is also outside Auto-fit (deliberately, for identifiability), so manual/weight is the only door. | `app/.../ui/SettingsScreen.kt:242`, `app/.../data/PhysioRuntime.kt:80` |
| M5 | M | Deferred — O-C (false-alarm feedback), O-D (ramp bench) | **Horizon shrinkage is applied asymmetrically.** `backboneWeight` multiplies each 5-min increment by 1.0 → 0.5@60 → 0.75@120 → 0.75@180; for a 2 U dose that is 59–63% of the physiological effect. It is a fitted trust ramp (Auto-fit axis `ramp`), not physiology: uncertainty belongs in the band, and shrinking the mean rewards a miscalibrated model by hiding amplitude errors. Since `fullCausalInsulinDisplay = true`, insulin on board is drawn in full on both screen and alert, and the What-if dose is deliberately not shrunk — so today the ramp cuts **food, background and drift only**. After a meal with a bolus the rise is drawn at ~60% and the dose at 100%: the line is biased low exactly where the low alert decides. This matches the 15 false hypo alarms / 35 episodes in the recorded episode table. | `core/.../hybrid/HybridForecastEngine.kt:1415,1508`, `app/.../data/Forecaster.kt:205`, `app/.../data/PhysioForecastBridge.kt` (was `HybridShadow.kt:198`) |
| M6 | M | Deferred — O-D (only after a bench) | Protein and fat are timing-only (delay / tail), never amplitude — "ONE RULE: grams × CS". Gluconeogenesis from protein and fat-induced insulin resistance are not modelled; low-carb / high-protein meals forecast low on the 3–5 h horizon. | `HybridForecastEngine.kt:508` |
| M7 | L | Open | Activity: the structure exists (exposure with τ=150 min scaling insulin action), but the example model ships `iob_gamma = food_gamma = 0`. | `app/src/main/assets/models/person_model_v11_runtime.json` |
| M8 | L | Closed (phaseA/a2) | README promises "ISF from clean corrections with confidence levels"; `docs/insulin-model.md §3` says that pipeline was removed and the applied ISF is hand-pinned, with the daily balance shown alongside. The README overstates what learns. | `README.md`, `docs/insulin-model.md` |
| M9 | L | Closed (phaseA/a5) — the tool is reproducible; the figures still need a device database | The app's own accuracy figures are not published anywhere reproducible: 15 false hypo alarms / 35 episodes with the pinned ISF, 2 missed real hypos of 3, MAE ~2.1 mmol on meal episodes, 60-min bias +1.05…+1.5, 180-min bias up to +4.5. They live in comments and one doc table. | `docs/insulin-model.md §3.1`, engine comments |
| M10 | M | Open — recorded, pinned by test, not changed (review/d-model-arch) | **Dose-dependent duration exists only on the parametric arm.** `insulinCdf(ageMin, units)` returns from the `actionCdfKnots` branch before `units` is read, so the moment a measured or hand-entered curve is installed — which is every phone past its first days — `tailWeightPerUnit` stops doing anything and a 10 U dose ends when a 1 U dose does. This file listed "dose-dependent insulin duration" under Sound; it is sound for the raw bundled asset only. Changing it is a model change on the alert path and waits for a bench (O-D); until then `InsulinKnotsDoseIndependenceTest` pins the limitation by name. | `core/.../hybrid/HybridForecastEngine.kt` `insulinCdf`, `HybridDomain.kt` `actionCdfKnots` |
| M11 | H | Closed (review/d-model-arch) | **The bundled example person encoded a basal deficit.** `joint.coefficients.intercept 0.09` (mmol per 30 min) against `state_reversion 0.034` and `target_glucose 6.9` gives an event-free equilibrium of 6.9 + 0.09/0.034 ≈ 9.5 mmol/L, so a fresh install drifted every forecast upward — the direction that hides a predicted low. The intercept is now 0.0 and `ArtifactHonestyTest` asserts the example person has no built-in drift. `HybridModelStore` re-installs a bundled model whose SHA-256 changed, so an install still on the bundled model — the author's included — picks this up on next launch; an imported artifact is left alone. | `app/src/main/assets/models/person_model_v11_runtime.json` |
| M12 | M | Deferred — O-D (needs a residual corpus from n>1) | **The uncertainty band is several-fold too narrow.** The asset's `sigma_per_sqrt_hour` is zeroed on the physio arm and replaced by six hard-coded variance terms (`PHYSIO_BASE_VARIANCE_HEALTHY_V1`) composed as 1.28·RSS: ±0.4 mmol/L at 60 min and ±0.7 at 180 with nothing on board, against the project's own MAE of ~2.1 on meal episodes. It grows as √h only, the ledger calls its calibration "in-sample, optimistically small", and `ForecastHealthV1`'s wide-corridor reason (half-width > 3.5 at 60 min) is unreachable with it. The night-time `useCorridorLow` rule reads this band and is default-off, which is the correct default while this stands. | `core/.../physio/PhysioV1.kt`, `data/ForecastLedger.kt` |
| M13 | M | Half closed (review/d-model-arch) — baselines in the tool; the walk-forward stays O-D | **No baseline and no held-out validation in code.** `PhysioAutoFitV1.score` fits in-sample; "walk-forward" and "held-out half" appear in comments and docs only, no function splits an episode set. `:tools:accuracy` now prints two naive baselines beside the model on one common set per horizon — last value, and a 15-minute least-squares line — with the skill score 1 − MAE(model)/MAE(baseline), so a published MAE can be judged against a ruler. The walk-forward itself still needs other people's databases. | `core/.../physio/PhysioAutoFitV1.kt`, `tools/accuracy/.../Metrics.kt` |

**Sound:** the person model is a portable artifact ("the runtime contains no
patient-specific defaults"); the engine reads no clock, DB or prefs and is
replayable; the food side (three carb bases, physical form, fibre, a caloric
gastric queue at 180 kcal/h with 0.65 sieving, shared pipe for clustered
meals) is richer than oref0/Loop; dose-dependent insulin duration on the
parametric arm only (M10); residual
drift with τ=30 as a retrospective correction; state reversion evaluated at
the forecast level rather than the anchor; hypotheses recorded with numbers
and rejected alternatives.

### Cold start — what actually happens

| Step | Reality |
|---|---|
| Manual ISF and insulin shape | Present, tier P1, validated against the HAND tier's own domain and applied as entered — end of action 120..600 (`PhysioBoundsV1.insulinTailMinManualRange`), checked at `ManualInsulinParamsV1.kt:541` and never clamped; out of range the shape is refused and said so. |
| Manual CS | Present (`carb_sens_override_mmol_per_g`, default 0.165). |
| Measured insulin timings | Continuous; the "clean" arm switches in at 8 samples, before that "from all doses (too few clean ones yet)". Applied automatically unless a field is set by hand (manual → measured → prior). |
| ISF learning | Daily balance, needs ≥3 valid days in a 10-day window; applied only with `IsfSource = ADAPTIVE` (default `MANUAL`). |
| Auto-fit | "Auto-fit from the last 10 episodes", needs ≥3 episodes; axes `isf, onset, fullSpeed, phase, tail, tailShare, kcal, sieve, spread, ramp`; timings within ±20% of measured, ISF free 1–5; **proposes into fields, applied only by Apply**; per-knob lock. The `tail` band is additionally held inside the instrument's own 240..480 (`PhysioAutoFitV1.boundsAround`, b10) instead of proposing under the floor and discarding the candidates in silence, and the axis is locked outright when an end of action has been entered by hand. Does not fit CS or the carb triangles themselves (only `spread`; triangles are importable via `carbTrianglesOverride`). |
| Collection-only phase | **Exists since review/c-onboarding**: until the first-run flow is finished the forecast is refused and only reading-based alerts fire — see M2. |

---

## Architecture and code

`:core` is designed: zero `android` imports across 107 files; the package
graph is almost a DAG (`collector ← analysis ← twin/hybrid ← physio ← api`,
one minor `analysis ↔ hybrid` cycle); one entry per quantity after documented
consolidation. `:app` grew as the harness of a research model, and the debt
is concentrated there.

| # | S | Status | Finding | Where |
|---|---|---|---|---|
| A1 | M | Open — a3 closed the UI half; static `Settings.*` and the `*Runtime` singletons remain | No ViewModel, DI or repository. `Stores.get(context)` at 98 sites (51 in `MainActivity`), static `Settings.*` at 61; composables hit SQLite directly (`AnnotationComposer` 9, `SettingsScreen` 5). | `app/.../MainActivity.kt`, `ui/*.kt` |
| A2 | M | Deferred — S-4 (needs chart/alerts out of `:app`) | "Recompute everything" state: one `UiState`, `loadState` (~1200 lines) called from 28 sites; the `withHistoryFrom(state).stabiliseHistoryAgainst(state)` patch treats a symptom of the pattern. `DataPulse` is used at 3 sites — no reactive path. | `app/.../MainState.kt:511`, `MainActivity.kt:634` |
| A3 | M | Open — b0 built the two flavors the separation needs (`editions.md`); review/d-model-arch renamed the live path (A10) and removed `ForecastComparisonRegistry`; `Stage9EpisodeRuntime` and `PhysioExperimentalLedger` still ship in both editions | Research bench ships in the APK: `HybridShadow` ("shadow-9"), `HybridShadowRegistry`, `ForecastComparisonRegistry`, `PhysioExperimentalLedger`; `Stage7–10` in 10 files, `V1/V2` suffixes in 29; 26 constructor knobs on the engine, 9 `*Override` fields on the model; settings with getter, setter and zero setter calls (`setActivityModelV2`, `setNotePreferredFood`) — both since removed, and the count re-derived over every settings holder is now one (`setBolusProduct`, left because its getter is not a constant); flags: fixed in 258cf38, see `architecture.md` § "Settings that cannot be configured". | `app/.../data/HybridShadow*.kt`, `PhysioTuning.kt`, `Settings.kt` |
| A4 | M | Open | Four concurrency models at once: 27 raw `Thread`, 10 `Handler`, 82 `Dispatchers.IO`, WorkManager; 66 `@Volatile`, 10 `synchronized`. The `PhysioRuntime` cache key has nine components, each added after a bug (`tuningIdentity` "was missing" — stale model served while the screen said "applied"). | `app/.../data/PhysioRuntime.kt` |
| A5 | L | Open | Twelve `*Runtime` singletons, each with its own cache, lock and manual `invalidate()`. | `app/.../data/*Runtime.kt` |
| A6 | L | Open | `CollectorStore` is a 70-method interface; `SqliteCollectorStore` 2295 lines; `AnnotationComposer` 3335, `GlucoseChart` 2172, `LabelScreen` 2068, `MainActivity` 1801, `MainState` 1707. | `core/.../collector/CollectorStore.kt` |
| A7 | L | Open | Package cycles: `data ↔ collect` (12 / 13 files), `collect → widget`, `i18n → widget`. Packages are folders, not layers. | `app/` |
| A8 | L | Closed (phase-A gate, WP-A4) | 472 fully-qualified `io.github.obdosok.diapilot.*` references inline instead of imports. | `app/` |
| A9 | L | Closed (phaseA/a2) | The engineering diary lives in the engine's constructor (556 comment lines of 1776, most before the first parameter). It belongs in `docs/`. | `core/.../hybrid/HybridForecastEngine.kt:24` → `docs/forecast-engine.md` |
| A10 | M | Closed (review/d-model-arch) | **The production forecast path was named `HybridShadow`.** `Forecaster` called `HybridShadow.forecast` for every line on screen, in the alert and on the watch; the ledger tag it writes is `…-shadow-13-…`. A reader took it for a parallel experiment. Renamed `PhysioForecastBridge` / `PhysioForecastRegistry` / `PhysioForecastRun`; the tag string is byte-identical because it is a stored identifier the accuracy tool reads. | `app/.../data/PhysioForecastBridge.kt` |
| A11 | L | Closed (review/d-model-arch) | `HybridForecastEngine(` was constructed by hand at 23 sites in 9 files (10 in `HybridRuntimeMetrics`) beside a `physioForecastEngine` factory that carries the shipped defaults. Every site that meant "the shipped engine" now calls the factory; the one remaining constructor is the What-if `physio = false` arm and says so. | `data/HybridRuntimeMetrics.kt`, `core/.../hybrid/PhysioEngineFactory.kt` |
| A12 | L | Closed (review/d-model-arch) | `Forecaster` swallowed a failing `PhysioRuntime.artifact` into `null` with no log while `architecture.md` §2 argued that a missing line is safe because it "logs an error". It logs now (class name only); three of the four empty catch bodies elsewhere got a line, the fourth a comment saying why silence is right. | `data/Forecaster.kt` |
| A13 | L | Closed (review/d-model-arch) | One stale reader survived the v50 label migration: `DailyDiscrepancyRuntime` matched `purpose.contains("корр")` against a column that now holds `correction`. The Russian literals inside the v8 and v19 migration steps are NOT stale — `onUpgrade` runs them before v50 on any old file, and `HistoricalMigrationLiteralsTest` shows the order matters. A hard-coded Russian error string in `AskClaude` became a resource in both languages. | `data/DailyDiscrepancyRuntime.kt`, `data/SqliteCollectorStore.kt` |
| A14 | L | Closed (review/d-model-arch) | Three values for mg/dL per mmol/L (18.0182, 18.016, 18.0) in three files. One constant now. | `core/.../collector/XdripBroadcast.kt` |

Not "built without understanding": 41 "used to be", 31 "was removed", 12
"tombstone", 22 "discipline #7" comments; `docs/architecture.md` is a wiring
map with a "what no longer exists" section and an audit that found and removed
24 unreferenced declarations, ~1250 lines of unrendered UI and a 1336-line
runtime that fed nothing. The debt is known and chosen.

---

## Product and release

| # | S | Status | Finding | Where |
|---|---|---|---|---|
| P1 | H | Closed (phaseB/b5) | The offline food parser was Russian-only under an English UI. The table had it at 51 concepts; the code always had 52 — fixed here, doc error, not a code one. Structure was language-neutral: 52 concepts with English ids and Russian alias lists (199), number words and unit regexes in `ComponentCounts.kt`, 5-char stem matching. b5 added the English side as data — alias lists, `one/two/.../ten/couple/pair/both`, English `g\|gram\|grams\|ml\|oz\|cup\|tbsp\|tsp` mass units, `each/every/per/pcs` per-unit markers, a portion-word (`slice/cup/tbsp/tsp/glass/bottle/handful`, English and Russian) typical-grams table, and diacritic stripping — without touching the matching logic, the stem length or any threshold. | `core/.../analysis/FoodConcepts.kt:91`, `ComponentCounts.kt:35,44` |
| P2 | H | Closed (review/c-onboarding) — see M2; the flow is reachable again from More → Personal model → "Set up model again", and the store edition runs the disclaimer, language, weight and summary pages only | No onboarding: disclaimer, ISF / ICR / weight entry, insulin timings with sane defaults, calibration status — see M2. |  |
| P3 | M | Open — b0 closed the `USE_EXACT_ALARM` item (oss manifest only, `SCHEDULE_EXACT_ALARM` in both) and gave S-0 its flavor; review/e-release closed the signing half in code (S8); the disclosure before health data leaves for the LLM, the privacy policy and the report control stay with S-1 | Google Play: `USE_EXACT_ALARM` is restricted to alarm/timer/calendar apps (drop it, keep `SCHEDULE_EXACT_ALARM`); FGS `specialUse` needs a declaration and is reviewed strictly; no hosted privacy policy; no prominent disclosure before health data leaves for the LLM; no in-app report control for AI-generated content; Health Connect and Health-apps declarations; debug signing (S8); closed testing (12 testers / 14 days) for a personal account. |  |
| P4 | M | Open | Regulatory status: a predictive hypoglycaemia alert plus a carbohydrate hint is a medical purpose by function; in the EU that is MDR (rule 11), and a disclaimer does not settle it. xDrip+ / AAPS stay off the stores for this reason. |  |
| P5 | M | Open | Apple: no iOS code; GPL-3.0 ports of xDrip+ cannot be relicensed for App Store terms; half the architecture (broadcast, 24/7 service, lock-screen overlay, localhost watch server, OOP2) has no iOS equivalent. |  |
| P6 | L | Open — b1 added the Data sources screen (every link with a status, one line of what breaks, and a Fix); the chain itself is unchanged, and Nightscout (O-B) and Juggluco (S-1) are still the way out of the dependency | The xDrip + OOPAlgorithm2 + Health Connect dependency chain is fragile on a stranger's phone. | `app/.../collect/DataSources.kt`, `ui/DataSourcesScreen.kt` |
| P7 | H | Closed (phaseB/b9) | **No alert could fire without OOPAlgorithm2.** `HypoAlertNotifier.maybeNotify` — the predictive low *and* the reading-driven low, sustained low, sensor artifact and sustained high — `RapidFallNotifier.maybeNotify` and `CompanionSync.pushIfDue` had exactly one caller each, and it was the tail of the OOP2 minute receiver. The stall notification had one too, inside the poll worker's `if (ownBle)`. So on a phone without that third-party app — every stranger's phone, and the whole point of the store edition — the app kept collecting, drawing, updating the widget and answering the watch while no alarm of any kind could fire, and nothing on any screen said so. Not a finding phase B created; re-derived at its gate as "decision 1" and closed here. b9 introduced one entry point (`collect/AlertTick.kt`) called from every path a reading arrives on, plus the existing 15-minute `TreatmentsPollWorker` as the periodic backstop, and gave the Data sources screen a leading Alerts row that states whether an alarm can fire and why not. | `app/.../collect/AlertTick.kt`, and its callers `CollectorService.kt:216`, `XdripBgReceiver.kt:91`, `TreatmentsPollWorker.kt:185`, `MainActivity.kt:261,1131,1148`; `collect/DataSources.kt` |

**What phase B closed here (b9), and what it deliberately changed.** P7 is the
only finding in this file that was not in the original AI-assisted read: it was
re-derived at the phase-B gate and is closed by the branch that added it. One
entry point, `collect/AlertTick.kt`, now evaluates the whole alert set — low,
high, rapid fall, stall — plus the companion push and the widget, and is called
after the reading is stored from the xDrip broadcast receiver, the OOP2 minute
stream, the web-service poll, the Libre NFC scan and a hand-entered fingerstick.
The same poll worker that fetches `/sgv.json` calls it at the end of every run
whether or not anything arrived, which is the backstop for a phone whose only
source is that poll. Two things the tick decides that the notifiers do not, and
both are the caller's job rather than a threshold change: which series the
rapid-fall slope is measured over (the per-minute stream while it is live,
otherwise a thirty-minute window on the five-minute grid, because the detector's
ten-minute default can never hold five points there), and which sentence the
stall notification carries (the NFC advice only where the app owns the sensor
link). The stall alert therefore now fires on every phone rather than in
own-BLE mode alone — a deliberate extension, and the one place b9 changed what a
user sees rather than who calls it.

---

## Suggested order

1. S1 + one fuse on **every** insulin input (watch, xDrip treatments, LLM);
   S3/S4 bounds (mg/dL 20–600, time window, no cross-source overwrite,
   refuse broadcasts when xDrip is not installed, plausibility gate on by
   default, send `api-secret`).
2. M1: floor of `insulinTailMinRange` to 240, example tail to 300, warn when
   measured end < 240; then the plateau end landmark, measured walk-forward.
3. M2 + M4 + P2 as one first-run screen.
4. M5: either weight insulin too or drop the ramp into the band — measure
   false alarms either way before shipping.
5. S6–S8, P1.
6. A3 (bench flavor) → A1 (composition root) → A2 / A4 → A6 → A7–A9 last
   (mechanical, touches everything).

**Where this order stands after phase B.** Step 1 closed at the phase-A gate.
Step 2 took its first half — the floor, the example tail and the warning — and
left the instrument and the plateau landmark to O-D. b10 then corrected that
half's own side effect: one constant had been serving the instrument's clamp
AND the hand tier's validation, so raising the floor silently took with it the
user's ability to state a shorter end of action. They are separate constants
now, and a stated tail is applied as entered; the instrument itself is still the
open half. Step 3 has M4 closed
**without** the first-run screen it assumed: the weight chain reaches the
forecast on its own, and the screen is still owed M2 and P2 (and the setter
that would let it write the manual tier). Step 5 has P1 done and S6–S8 open.
Steps 4 and 6 are untouched, though b0 built the flavor mechanism A3's fix
needs.

**And one step that was not on the list.** P7 sits ahead of all of it: an
onboarding screen, a release key and a second glucose source are all worth less
on a phone where no alarm can fire, so b9 took it before the three items O-B
still owes.

**Where this order stands after the second read.** Step 1 is closed for real
now — S4 and the new S12/S13 were the two doors the phase-A fix had left
ajar, and the fuse reaches every input (S14, S7). Step 3 is done as one
first-run screen (M2, P2, the M4 setter). Step 5 has S7 closed, S6 behind an
optional password, S8 waiting on a keystore. Step 6 took A10–A14 and left
A1, A2, A4–A7 as they were. Of the three items O-B owed `v1.4.0`, the
Nightscout source is the one still open.
