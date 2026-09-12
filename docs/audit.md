# Audit — September 2026

An external read of the `public` snapshot: code quality, security, the
forecast model, architecture, and release readiness. Everything below was
checked against the code at commit `0122a82`; file references are to that
tree. `./gradlew :core:test` was run (844 tests, 0 failures).

Severity: **H** — harms a third-party user today; **M** — must be closed
before a release to strangers; **L** — hygiene.

**Status** is as of the phase-A gate (tag `v1.3.0`) and is one of:
**Closed**, with the branch that closed it; **Open**; or **Deferred**,
with the phase in `roadmap.md` that plans it. A finding is Closed only
when every mechanism it names is addressed — where phase A closed part of
one, the status stays Open and says which part moved. The findings
themselves are unchanged; only the Status column and two file references
were updated.

---

## Security

The app has almost no remote surface (no server, no account; outbound only
HTTPS to Anthropic and Open Food Facts). The real surface is **other apps on
the same phone** and **the media the backups land on**.

| # | S | Status | Finding | Where |
|---|---|---|---|---|
| S1 | H | Closed (phaseA/a1) | `GET /add_treatments?insulin=X` on `127.0.0.1:29863` writes a bolus with no authentication, no fuse (`takeIf { it > 0 }`), no method/Origin/Host check; a GET with a side effect. Any app holding `INTERNET` can inject a dose. The LLM path has `commandMaxBolusUnits`; this path has nothing. | `app/.../collect/WatchServer.kt:394` |
| S2 | H | Open — a1 gated `/api/v1/events`; `/info.json?graph=1` is still unauthenticated | The same socket serves `/info.json?graph=1` and `/api/v1/events` — the full glucose / insulin / meal history — without authentication. | `app/.../api/DiaForApi.kt` |
| S3 | H | Closed (phaseA/a1) | Glucose spoofing: `XdripBgReceiver` is `exported="true"` with no permission and is also registered `RECEIVER_EXPORTED`; the OOP2 receiver likewise. `parseBgBroadcast` accepts any `mgdl > 0` and any `ts > 0`. `upsertReading` uses `ON CONFLICT(ts_ms) DO UPDATE`, so stored history can be rewritten at any timestamp. Worst case is not a false alarm but a suppressed real one: a forged "8.0 now" becomes the forecast anchor. | `AndroidManifest.xml:96`, `core/.../collector/XdripBroadcast.kt:56`, `app/.../data/SqliteCollectorStore.kt:960`, `collect/CollectorService.kt:67,232` |
| S4 | H | Closed (phaseA/a1) | The xDrip web service on `127.0.0.1:17580` is polled without `api-secret`. With xDrip not running, any app can bind the port and feed `/sgv.json` and `/treatments`; insulin from treatments is written with no fuse. | `app/.../collect/TreatmentsPollWorker.kt:113,244` |
| S5 | M | Closed (phaseA/a1) | The plausibility gate is off by default and applies at read time only. | `app/.../data/Settings.kt:349` |
| S6 | M | Deferred — O-C (backup password / restore undo) | The whole medical database is backed up unencrypted to three places: public Downloads, an arbitrary SAF folder (i.e. a third-party cloud, contradicting "data lives on the phone"), and the companion server (`data/backups/`, plaintext). | `app/.../data/BackupRestore.kt` |
| S7 | M | Deferred — O-C (backup password / restore undo) | `restore()` runs no `PRAGMA integrity_check` and overwrites the live database with no rollback copy. | `BackupRestore.kt:73` |
| S8 | M | Deferred — O-B (release signing) | The release build is signed with the debug keystore (well-known password, unprotected file). Anyone who obtains it can sign an "update" that installs over the user's data. | `app/build.gradle.kts:62` |
| S9 | L | Open — a2 corrected the README claim; the server's own limits remain | Companion server: `/api/push` reads unbounded JSON into memory, `/api/backup` writes unbounded uploads to disk, no rate limit on token guesses. `server/README.md` suggests `http://<LAN IP>:8787`, which the app's network security config refuses (cleartext is loopback-only); the root README claims "one LAN host listed" but the file lists none. | `server/main.py`, `app/src/main/res/xml/network_security_config.xml` |
| S10 | L | Open | Glucose values and hypo decisions are logged at INFO level via `android.util.Log`; no log stripping in release. Readable only via adb / bug report, but a shared bug report carries hours of history. | `collect/HypoAlertNotifier.kt:395`, `collect/XdripBgReceiver.kt:60` |
| S11 | L | Closed (phaseA/a1) | The barcode is interpolated into the OFF URL unencoded. Scanner formats are EAN/UPC only, so practically inert; validate `^\d{8,14}$` anyway. | `app/.../data/OpenFoodFacts.kt:70` |

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

**Checked and sound:** `SecretStore` (AES-256-GCM in AndroidKeyStore, AAD bound
to the secret's name, never rewrites plaintext, logs only the name and the
exception class); manifest (`allowBackup=false` + `dataExtractionRules`,
FileProvider not exported and limited to `photos/`, `LabelActionReceiver` /
`BootReceiver` not exported); the LLM command path (parse → `CommandGuard` →
confirm card → user tap, never an automatic write; API key only in the
`x-api-key` header; chat is read-only); SQL is parameterised, interpolations
are table/column constants only; FRAM/NFC parsing is bounds-checked; no
WebView, no `exec`; no secrets in git, `local.properties` ignored, the gitleaks
allowlist is narrow; `HttpURLConnection` will not follow https→http.

---

## Model

| # | S | Status | Finding | Where |
|---|---|---|---|---|
| M1 | H | Deferred — O-B (tail domain 240+), O-D (plateau end landmark) | **Insulin tail is short by construction.** Example model ends at 175 min; domain floor is 120. Against an exponential curve (peak 75, DIA 6 h) the example has 85% acted at 120 min vs ~55%, and 100% at 180 vs ~79% — IOB reads zero on the third hour. The cause is the *instrument*, not a constant: the per-dose window is capped at `CAP_MS = 240 min` and closed by the next injection; the end landmark needs `TAIL_HORIZON_MIN = 200` clean minutes; and "end" is defined as the rate returning to the pre-dose slope, which a rising background meets early (an earlier internal finding, M-54; the plateau alternative is computed but never substituted). A 240-minute ruler cannot measure five hours, so every user's measured end lands in ~150–230 and the 120..600 domain never clamps it. Manual timings (P1) win and accept tail 120..600, but Auto-fit's corridor is ±20% around the *measured* tail and will propose pulling it back unless the knob is locked. | `app/.../data/InsulinProfileRuntime.kt:44`, `core/.../physio/SegmentLandmarksV1.kt:104,278`, `core/.../physio/PhysioContracts.kt:18`, `core/.../physio/PhysioAutoFitV1.kt:364` |
| M2 | H | Deferred — O-B (onboarding) | **No "uncalibrated" mode.** From the first minute the bundled synthetic person (ISF 1.8, insulin 20/75/175, CS 0.165) drives the forecast, the hypo alert and the watch hint. No onboarding screen, no calibration status; the only "not measured yet" strings live inside the tuning section. | `app/.../data/HybridModelStore.kt:24` |
| M3 | M | Deferred — O-D (needs n>1) | ISF, CS and DIA are not separately identifiable from meal days, and the pipeline holds CS constant while reading DIA off the CGM. The measured bias of +4.53 mmol at h=180 is attributed to food amplitude, but a short insulin tail produces the same sign; the fitter itself notes that an ISF read off the bias arm is "partly a food deficit wearing an insulin label". | `docs/architecture.md` §5, `core/.../physio/PhysioAutoFitV1.kt` (header) |
| M4 | M | Deferred — O-B (weight → CS) | **Weight → CS is not wired.** The weight field exists and `CarbSensitivityPriorV1.fromWeight` exists, but the result is only shown as a hint under the field; `foodDynamicsGlobalPriorV1(weightKg)` is never called with a weight. CS is also outside Auto-fit (deliberately, for identifiability), so manual/weight is the only door. | `app/.../ui/SettingsScreen.kt:242`, `app/.../data/PhysioRuntime.kt:80` |
| M5 | M | Deferred — O-C (false-alarm feedback), O-D (ramp bench) | **Horizon shrinkage is applied asymmetrically.** `backboneWeight` multiplies each 5-min increment by 1.0 → 0.5@60 → 0.75@120 → 0.75@180; for a 2 U dose that is 59–63% of the physiological effect. It is a fitted trust ramp (Auto-fit axis `ramp`), not physiology: uncertainty belongs in the band, and shrinking the mean rewards a miscalibrated model by hiding amplitude errors. Since `fullCausalInsulinDisplay = true`, insulin on board is drawn in full on both screen and alert, and the What-if dose is deliberately not shrunk — so today the ramp cuts **food, background and drift only**. After a meal with a bolus the rise is drawn at ~60% and the dose at 100%: the line is biased low exactly where the low alert decides. This matches the 15 false hypo alarms / 35 episodes in the recorded episode table. | `core/.../hybrid/HybridForecastEngine.kt:1415,1508`, `app/.../data/Forecaster.kt:205`, `app/.../data/HybridShadow.kt:198` |
| M6 | M | Deferred — O-D (only after a bench) | Protein and fat are timing-only (delay / tail), never amplitude — "ONE RULE: grams × CS". Gluconeogenesis from protein and fat-induced insulin resistance are not modelled; low-carb / high-protein meals forecast low on the 3–5 h horizon. | `HybridForecastEngine.kt:508` |
| M7 | L | Open | Activity: the structure exists (exposure with τ=150 min scaling insulin action), but the example model ships `iob_gamma = food_gamma = 0`. | `app/src/main/assets/models/person_model_v11_runtime.json` |
| M8 | L | Closed (phaseA/a2) | README promises "ISF from clean corrections with confidence levels"; `docs/insulin-model.md §3` says that pipeline was removed and the applied ISF is hand-pinned, with the daily balance shown alongside. The README overstates what learns. | `README.md`, `docs/insulin-model.md` |
| M9 | L | Closed (phaseA/a5) — the tool is reproducible; the figures still need a device database | The app's own accuracy figures are not published anywhere reproducible: 15 false hypo alarms / 35 episodes with the pinned ISF, 2 missed real hypos of 3, MAE ~2.1 mmol on meal episodes, 60-min bias +1.05…+1.5, 180-min bias up to +4.5. They live in comments and one doc table. | `docs/insulin-model.md §3.1`, engine comments |

**Sound:** the person model is a portable artifact ("the runtime contains no
patient-specific defaults"); the engine reads no clock, DB or prefs and is
replayable; the food side (three carb bases, physical form, fibre, a caloric
gastric queue at 180 kcal/h with 0.65 sieving, shared pipe for clustered
meals) is richer than oref0/Loop; dose-dependent insulin duration; residual
drift with τ=30 as a retrospective correction; state reversion evaluated at
the forecast level rather than the anchor; hypotheses recorded with numbers
and rejected alternatives.

### Cold start — what actually happens

| Step | Reality |
|---|---|
| Manual ISF and insulin shape | Present, tier P1, validated against domain bounds (`ManualInsulinParamsV1.kt:496`). |
| Manual CS | Present (`carb_sens_override_mmol_per_g`, default 0.165). |
| Measured insulin timings | Continuous; the "clean" arm switches in at 8 samples, before that "from all doses (too few clean ones yet)". Applied automatically unless a field is set by hand (manual → measured → prior). |
| ISF learning | Daily balance, needs ≥3 valid days in a 10-day window; applied only with `IsfSource = ADAPTIVE` (default `MANUAL`). |
| Auto-fit | "Auto-fit from the last 10 episodes", needs ≥3 episodes; axes `isf, onset, fullSpeed, phase, tail, tailShare, kcal, sieve, spread, ramp`; timings within ±20% of measured, ISF free 1–5; **proposes into fields, applied only by Apply**; per-knob lock. Does not fit CS or the carb triangles themselves (only `spread`; triangles are importable via `carbTrianglesOverride`). |
| Collection-only phase | **Does not exist** — see M2. |

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
| A3 | M | Open | Research bench ships in the APK: `HybridShadow` ("shadow-9"), `HybridShadowRegistry`, `ForecastComparisonRegistry`, `PhysioExperimentalLedger`; `Stage7–10` in 10 files, `V1/V2` suffixes in 29; 26 constructor knobs on the engine, 9 `*Override` fields on the model; settings with getter, setter and zero setter calls (`setActivityModelV2`, `setNotePreferredFood`) — both since removed, and the count re-derived over every settings holder is now one (`setBolusProduct`, left because its getter is not a constant); flags: fixed in 258cf38, see `architecture.md` § "Settings that cannot be configured". | `app/.../data/HybridShadow*.kt`, `PhysioTuning.kt`, `Settings.kt` |
| A4 | M | Open | Four concurrency models at once: 27 raw `Thread`, 10 `Handler`, 82 `Dispatchers.IO`, WorkManager; 66 `@Volatile`, 10 `synchronized`. The `PhysioRuntime` cache key has nine components, each added after a bug (`tuningIdentity` "was missing" — stale model served while the screen said "applied"). | `app/.../data/PhysioRuntime.kt` |
| A5 | L | Open | Twelve `*Runtime` singletons, each with its own cache, lock and manual `invalidate()`. | `app/.../data/*Runtime.kt` |
| A6 | L | Open | `CollectorStore` is a 70-method interface; `SqliteCollectorStore` 2295 lines; `AnnotationComposer` 3335, `GlucoseChart` 2172, `LabelScreen` 2068, `MainActivity` 1801, `MainState` 1707. | `core/.../collector/CollectorStore.kt` |
| A7 | L | Open | Package cycles: `data ↔ collect` (12 / 13 files), `collect → widget`, `i18n → widget`. Packages are folders, not layers. | `app/` |
| A8 | L | Closed (phase-A gate, WP-A4) | 472 fully-qualified `io.github.obdosok.diapilot.*` references inline instead of imports. | `app/` |
| A9 | L | Closed (phaseA/a2) | The engineering diary lives in the engine's constructor (556 comment lines of 1776, most before the first parameter). It belongs in `docs/`. | `core/.../hybrid/HybridForecastEngine.kt:24` → `docs/forecast-engine.md` |

Not "built without understanding": 41 "used to be", 31 "was removed", 12
"tombstone", 22 "discipline #7" comments; `docs/architecture.md` is a wiring
map with a "what no longer exists" section and an audit that found and removed
24 unreferenced declarations, ~1250 lines of unrendered UI and a 1336-line
runtime that fed nothing. The debt is known and chosen.

---

## Product and release

| # | S | Status | Finding | Where |
|---|---|---|---|---|
| P1 | H | Deferred — O-B (English food parser) | The offline food parser is Russian-only under an English UI. Structure is language-neutral: 51 concepts with English ids and Russian alias lists (188), number words and unit regexes in `ComponentCounts.kt`, 5-char stem matching. English = data, not code: alias lists, `one/two/a couple/half`, `g\|gram\|ml\|oz\|cup\|tbsp\|slice` with typical-gram mapping, `each/per/pcs`. | `core/.../analysis/FoodConcepts.kt:91`, `ComponentCounts.kt:35,44` |
| P2 | H | Deferred — O-B (onboarding) | No onboarding: disclaimer, ISF / ICR / weight entry, insulin timings with sane defaults, calibration status — see M2. |  |
| P3 | M | Deferred — S-1 (compliance), O-B (signing) | Google Play: `USE_EXACT_ALARM` is restricted to alarm/timer/calendar apps (drop it, keep `SCHEDULE_EXACT_ALARM`); FGS `specialUse` needs a declaration and is reviewed strictly; no hosted privacy policy; no prominent disclosure before health data leaves for the LLM; no in-app report control for AI-generated content; Health Connect and Health-apps declarations; debug signing (S8); closed testing (12 testers / 14 days) for a personal account. |  |
| P4 | M | Open | Regulatory status: a predictive hypoglycaemia alert plus a carbohydrate hint is a medical purpose by function; in the EU that is MDR (rule 11), and a disclaimer does not settle it. xDrip+ / AAPS stay off the stores for this reason. |  |
| P5 | M | Open | Apple: no iOS code; GPL-3.0 ports of xDrip+ cannot be relicensed for App Store terms; half the architecture (broadcast, 24/7 service, lock-screen overlay, localhost watch server, OOP2) has no iOS equivalent. |  |
| P6 | L | Deferred — O-B (Nightscout), S-1 (Juggluco) | The xDrip + OOPAlgorithm2 + Health Connect dependency chain is fragile on a stranger's phone. |  |

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
