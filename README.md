# DiaPilot

[![CI](https://github.com/obdosok/diapilot-app/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/obdosok/diapilot-app/actions/workflows/ci.yml)

A personal assistant for living with type 1 diabetes. It collects data without
the discipline of manual logging, explains what is happening, and makes the
invisible visible. Android, Kotlin and Jetpack Compose; local-first — the data
lives on the phone.

> **Not a medical device.** DiaPilot shows computed observations on the user's
> own data. The app **never gives insulin dosing instructions**: alerts suggest
> carbohydrates, never insulin; the LLM's system prompt forbids naming doses; a
> voice or typed command can only *record* a dose the user already took, and
> only after a hard validator. Any change to therapy belongs with a doctor.
> This code has not been clinically validated and must not be relied on for
> treatment decisions.

## What it does

- **Collection without manual entry.**
  - Glucose from xDrip: the `BgEstimate` broadcast, plus xDrip's local
    Nightscout-compatible web service for backfill (about two weeks deep).
  - Libre 2 read directly: an NFC scan in the app, and an experimental, opt-in
    BLE link of DiaPilot's own. The sensor data is decrypted by the
    OOPAlgorithm2 companion app, which also supplies a per-minute stream.
  - Insulin from a NovoPen smart pen, read over NFC in the app itself, and pen
    doses that xDrip has already logged.
  - Heart rate, sleep and steps from Health Connect (for example from a Zepp
    watch).
  - Fingerstick meter checks calibrate the sensor reading (a persistent offset
    or line plus a decaying short-term correction).
- **Meals in seconds.** Meals are detected from the shape of the glucose curve.
  Labelling takes a tap: a reply straight from the notification, chips for
  frequent dishes, voice (system speech recognition), a photo analysed with
  Claude, or a barcode looked up in Open Food Facts.
- **Context notes** — injection site, sleep debt, illness, stress, alcohol,
  workout, walk, new sensor, new cartridge — as text, photo or voice, plus the
  purpose of each injection (correction, meal, top-up, air shot).
- **Personal analytics** — see [Personal analytics: what learns and what does
  not](#personal-analytics-what-learns-and-what-does-not) below for the honest
  version. In short: the *shape* of insulin action is measured from the user's
  own injections; insulin sensitivity is entered by hand with a daily balance
  shown beside it; time in range, per-dish response profiles and the effect of
  context notes are computed from recorded data.
- **A digital twin**: a three-hour glucose forecast from a physiological
  "hybrid" model (insulin action, carbohydrate appearance, background drift)
  with an uncertainty band calibrated on the user's history. The insulin curve
  is measured from the user's own doses; ISF and the curve can also be set by
  hand. A what-if view shows how a hypothetical meal or dose would move the
  forecast. Why the model has this shape, and what every knob means:
  [`docs/forecast-engine.md`](docs/forecast-engine.md).
- **Real time**: a predictive low alert, a sustained-high alert, an early
  rapid-fall warning from the minute stream, an equipment alert when the data
  stream stalls, and observations such as "insulin in the last hours is acting
  stronger than usual".
- **At a glance**: a home-screen widget, a glucose chip on the lock screen,
  and a WatchDrip-compatible local endpoint that Zepp OS watch faces can read.
- **An LLM layer** (Claude API, with the user's own key): an "ask your data"
  chat with quick prompts, a weekly digest, food analysis from text or photo,
  and commands that turn a spoken sentence into a record. A deterministic core
  computes every number; the model only explains.
- **Safety nets**: a daily automatic database backup, restore on a new phone,
  and an optional single-user companion server (`server/`) with a live
  dashboard and an off-device backup.

## Screenshots

<!--
  Slots for the gallery. The images are not committed yet; each row below
  becomes an image as soon as it can be captured without a single real glucose
  reading, dose or note in frame. Which screens, in which language, what must
  never be visible and how to capture them: docs/screenshots.md.
-->

| | | |
|---|---|---|
| **Today** — the value, the trend, the three-hour forecast with its band<br/>_(`docs/screenshots/today.png` — not captured yet)_ | **What-if** — a hypothetical dose or meal moving the line<br/>_(`docs/screenshots/chart-whatif.png` — not captured yet)_ | **Predictive low alert** — with a carbohydrate suggestion<br/>_(`docs/screenshots/hypo-alert.png` — not captured yet)_ |
| **Labelling a meal** — a reply from the notification, chips for frequent dishes<br/>_(`docs/screenshots/label-meal.png` — not captured yet)_ | **Analysis** — the measured insulin action curve, time in range<br/>_(`docs/screenshots/analysis.png` — not captured yet)_ | **The model section** — manual ISF, measured timings, calibration status<br/>_(`docs/screenshots/settings-model.png` — not captured yet)_ |

See [`docs/screenshots.md`](docs/screenshots.md) for the capture procedure and
the list of things that must never appear in an image.

## Status

**Phase B, second read — readable by a code reviewer; installable by a
careful stranger who has read this section.** The forecast, the alerts and
the collection chain run daily on one device. A fresh install now starts in an
uncalibrated mode: a first-run screen takes the disclaimer, weight, insulin
sensitivity and an insulin-action preset, and until it is finished the app
draws no forecast and fires no predictive alert — only the alerts computed from
the readings themselves. Do not install this expecting a finished product, and
do not rely on it for a treatment decision.

- **[`docs/audit.md`](docs/audit.md)** — an AI-assisted audit of this snapshot:
  every known defect by number, with severity and a file reference. Security,
  model, architecture, and release readiness. Read it before the code.
- **[`docs/roadmap.md`](docs/roadmap.md)** — where this is going, in two
  tracks, with the gate each phase has to pass.
- **[`CHANGELOG.md`](CHANGELOG.md)** — what each release changed, and the known
  issues it shipped with.

The things a reader should know up front, all of them from the audit:
insulin sensitivity is hand-entered rather than learned (M8, and the section
below); the measured insulin tail is short, because the instrument that
measures it only looks four hours ahead — the domain now refuses anything under
240 minutes and the settings card flags a measurement that lands short, but the
measurement itself is unchanged (M1); a measured or hand-entered insulin curve
loses the dose-dependence of its duration (M10); the uncertainty band is
narrower than the measured error (M12); and no APK is release-signed until the
maintainer creates the keystore the build now expects (S8).

## Install

**Releases are signed APKs on GitHub.** Each `v*` tag builds both editions
and attaches them to a [GitHub Release](../../releases) of this repository:
`diapilot-oss-<tag>.apk` (this edition: everything on),
`diapilot-store-<tag>.apk` (the same code with the forecast and sensor-direct
paths closed — see [docs/editions.md](docs/editions.md)), `SHA256SUMS` and
`CERTIFICATE.txt`. Minimum Android 8.0 (API 26). Until the first tag exists
the only way in is to build from source, which [Building](#building) covers.

**Verify the download before installing.** Sideloaded APKs have no store in
front of them, so the signature is the only proof that the file came from this
repository. The release key's certificate SHA-256 is:

```
<fingerprint to be published with the first release>
```

Check the file and the certificate with the Android SDK's `apksigner`
(`build-tools/<version>/apksigner`):

```bash
sha256sum -c SHA256SUMS                        # the file is the one CI built
apksigner verify --print-certs diapilot-oss-<tag>.apk
# Signer #1 certificate SHA-256 digest: <must equal the fingerprint above>
```

If the digest differs, do not install. A certificate whose DN reads
`CN=Android Debug` is a local build signed with the debug key, never a
release (audit S8).

**Coming from a build you made yourself?** Android ties an installed package
to its signing certificate, so an install signed with the debug key cannot be
updated by a release-signed APK — the update is refused
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Uninstalling deletes the database and
photos, so first do Settings → Data → **Export database (.sqlite)** (or take
the latest daily backup from Downloads), note your settings (API key,
thresholds, hand-entered ISF/ICR — they are not in the export), then
uninstall, install the release, and Settings → Data → **Restore from a backup
(.sqlite)**. This is a one-time step; later releases update in place.

The app is not on Google Play; whether it ever is depends on the store track
in the roadmap, and it will never ship the predictive features there — that
split is the roadmap's whole subject.

## Personal analytics: what learns and what does not

The single most misleading thing a project like this can do is imply that it
learns more than it does. So, precisely:

**Measured from the user's own data.** The *shape* of insulin action — onset,
peak, the slowdown, and the end of action — is read landmark by landmark from
every trusted injection, each landmark on its own horizon, with the sample count
shown beside it. Also measured: the meter-to-sensor calibration, time in range,
per-dish response profiles, and the effect of context notes.

**Entered by hand.** Insulin sensitivity (ISF). The pipeline that measured ISF
from clean corrections **was removed**, and earlier versions of this README
claimed it was still there — that was the overstatement this section exists to
correct. What the app does instead is show a **daily insulin/carbohydrate
balance** beside the value the user has pinned, so a value that is clearly wrong
becomes visible; switching the forecast over to that computed value is opt-in
and off by default. Carbohydrate sensitivity is hand-set too: a body-weight
prior exists in the code, but nothing yet feeds it the weight the user typed.

**Proposed, never applied on its own.** Auto-fit reads the last episodes and
*suggests* values into the tuning fields, inside a corridor around what was
measured. Nothing moves until the user taps Apply, and each knob can be locked.

**Not modelled at all.** Protein and fat change the timing of a meal, never its
amplitude. Activity scales insulin action in the structure but ships with the
coefficient at zero.

The reasoning behind each of those, with the measurements:
[`docs/insulin-model.md`](docs/insulin-model.md) for the measurement cycle,
[`docs/forecast-engine.md`](docs/forecast-engine.md) for the model's shape and
knobs.

## Architecture

```
:core    Pure Kotlin, no Android SDK.
           collector/  xDrip, Pebble and OOP2 parsers, meal detection, storage contract
           analysis/   ISF, insulin kernel, meal and dish analysis, carb parsing,
                       calibration, rapid fall, status text, LLM command guard
           hybrid/     the forecast engine (insulin, food, background, uncertainty)
           physio/     learning the insulin curve and parameters from the user's doses
           twin/       forecast types, alert logic, trend, corridors
           libre/ pen/ Libre 2 FRAM decoding, NovoPen protocol
           api/        the local event journal and HTTP routing
:app     The Android layer: SQLite store, foreground collection service,
         WorkManager poller, NFC and BLE, Health Connect, notifications and
         alerts, widget, Compose UI, Claude API client, companion sync.
server/  Optional single-user companion (FastAPI): live dashboard + backup target.
```

The boundary between core and platform has held since the first commit: all
the mathematics is testable on the JVM without a phone (over 900 unit tests in
`:core`, over 400 in `:app`), and it leaves room for Kotlin Multiplatform.

A ten-minute tour with a diagram is
[`docs/architecture.md`](docs/architecture.md); the engineering record of the
forecast itself is [`docs/forecast-engine.md`](docs/forecast-engine.md); what
was removed and why is [`docs/history.md`](docs/history.md).

The UI has five tabs: Today, History, Analysis, Chat and More (settings).

### Data flow

```
                +--BLE--> xDrip --BgEstimate broadcast (5 min)--------------+
Libre 2 sensor -+--BLE (experimental, DiaPilot's own link)--+               |
                +--NFC scan in DiaPilot---------------------+               |
                                                            v               |
                          OOPAlgorithm2 decrypts --> minute stream -------->+
                                                                            v
xDrip web service 127.0.0.1:17580 (/sgv.json backfill, treatments) --> DiaPilot
NovoPen --NFC--> DiaPilot (or through xDrip's own pen scan) ---------> (SQLite
Health Connect (heart rate, sleep, steps) ---------------------------> on the phone)

DiaPilot --> widget, lock-screen chip, notifications
         --> 127.0.0.1:29863  /info.json (WatchDrip-compatible), /api/v1/events
         --> Claude API (explicit actions only), Open Food Facts (barcode only)
         --> your own companion server (optional)
```

## Privacy by construction

- The database and the API key live in the app's private storage. There is no
  DiaPilot cloud and no account.
- Everything between apps on the phone is local: xDrip's broadcast and web
  service, OOPAlgorithm2's broadcasts and the watch endpoint (bound to
  127.0.0.1 and ::1 only).
- Data leaves the phone only in three ways, all opt-in:
  - requests to the Anthropic API with the user's own key, on an explicit
    action — they carry a compact summary built on the device (current state,
    recent events, aggregates) or the photo being analysed, not the database;
  - barcode lookups in Open Food Facts, which send the barcode only;
  - pushes to a companion server the user runs and configures (URL + token).
- Cleartext HTTP is allowed to loopback only. `network_security_config.xml`
  permits `127.0.0.1` and nothing else, so a companion server reached over the
  home LAN needs its own host added there literally, or an HTTPS tunnel in
  front of it (audit S9).
- The daily automatic backup, once switched on in Settings, is written to the
  public Downloads folder, on purpose, so it survives an uninstall. Anything
  with access to Downloads can read it — unless a backup password is set in
  Settings, in which case every backup (Downloads, cloud folder, export,
  companion upload) is an AES-256-GCM file that only that password opens, and
  DiaPilot cannot recover it.
- Databases, exports, device dumps, settings exports and companion-server
  state are gitignored: medical data does not reach git.

## Security

Found a vulnerability? Please report it privately rather than opening a public
issue — see [SECURITY.md](SECURITY.md) for how, and what is in scope.

## Building

```bash
export JAVA_HOME=<a JDK 21, e.g. the JetBrains Runtime bundled with Android Studio>
./gradlew :core:test :app:testOssDebugUnitTest :app:assembleOssDebug
# APK: app/build/outputs/apk/oss/debug/app-oss-debug.apk
```

`:app` is built in two editions — `oss` (this one: the research vehicle,
every feature on) and `store` (the same code with the forecast and the
sensor-direct paths closed). Swap `Oss` for `Store` above to build the other,
or use `:app:assembleDebug` / `:app:test` to do both. What the two carry, and
why, is [docs/editions.md](docs/editions.md).

The Android SDK must be installed (Android Studio writes its path to
`local.properties`, or set `ANDROID_HOME`). Gradle 9.4 wrapper, AGP 9.2,
Kotlin 2.2, minSdk 26, targetSdk 36; `:core` targets Java 11, and the foojay
toolchain resolver can download that JDK. Instrumented tests in
`app/src/androidTest` need a device: `./gradlew :app:connectedOssDebugAndroidTest`.

### Cutting a release

The release build type is non-debuggable so ART can compile the baseline
profile, and it runs the same unoptimised code as the debug one (no
shrinking, no obfuscation — `app/build.gradle.kts` explains). Its signing key
is read, in order, from the environment (`DIAPILOT_KEYSTORE_FILE`,
`DIAPILOT_KEYSTORE_PASSWORD`, `DIAPILOT_KEY_ALIAS`, `DIAPILOT_KEY_PASSWORD`),
then from a gitignored `keystore.properties` at the repo root (copy
[`keystore.properties.example`](keystore.properties.example)); with neither,
Gradle prints a `WARNING [audit S8]` line and signs with the debug key, which
is fine for a local smoke test and never for publishing.

```bash
# once: create the keystore and keep it, with both passwords, in a password manager
keytool -genkeypair -v -keystore diapilot-release.jks -storetype PKCS12 \
  -alias diapilot -keyalg RSA -keysize 4096 -validity 10000

# locally, with keystore.properties filled in
./gradlew :app:assembleOssRelease :app:assembleStoreRelease
# APKs: app/build/outputs/apk/oss/release/app-oss-release.apk
#       app/build/outputs/apk/store/release/app-store-release.apk

# the fingerprint users compare against (README "Install")
apksigner verify --print-certs app/build/outputs/apk/oss/release/app-oss-release.apk
```

The release itself is a tag: bump `versionCode` (and drop `-dev` from
`versionName`) in `app/build.gradle.kts`, commit, `git tag v1.4.0`, push the
tag. [`.github/workflows/release.yml`](.github/workflows/release.yml) then
builds both editions with the key from the repository secrets
`DIAPILOT_KEYSTORE_BASE64` (the `.jks`, `base64 -w0`),
`DIAPILOT_KEYSTORE_PASSWORD`, `DIAPILOT_KEY_ALIAS` and `DIAPILOT_KEY_PASSWORD`,
runs the unit tests, refuses any APK whose certificate is the debug one, and
attaches the APKs, `SHA256SUMS` and `CERTIFICATE.txt` to a **draft** GitHub
Release for the maintainer to publish. Losing the keystore means no future
release can update an existing install in place — users would go through the
export / uninstall / restore step above once more.

### Setting up the phone

1. xDrip: Inter-app settings → Broadcast locally ON, xDrip Web Service ON.
   Leave "Identify receiver" alone if other apps rely on it.
2. DiaPilot: Battery → Unrestricted, so collection keeps running in the
   background.
3. Optional: OOPAlgorithm2 (Libre 2 decryption and the minute stream), Health
   Connect permissions (heart rate, sleep, steps), "draw over other apps" for
   the lock-screen chip, an Anthropic API key in the Chat tab, and a companion
   server URL and token in settings.
4. Turn on in Settings what you use. Everything the app shares with the rest
   of the phone is off until switched on:

   | Switch | Shared resource | Default |
   | --- | --- | --- |
   | Watch → Watch face server | loopback port 29863 (watch face, `/api/v1/events`) | off |
   | Data → Daily backup to Downloads | public Downloads folder | off |
   | Libre 2 → Scan Libre 2 over NFC | the sensor over NFC, OOP2's broadcast reply | off |
   | Libre 2 → own BLE link | the sensor's single BLE connection | off |

   Reading xDrip's broadcast and web service and Health Connect needs no
   switch: it is read-only. The applicationId is `io.github.obdosok.diapilot`,
   and every name the app places outside its sandbox (FileProvider authority,
   broadcast actions, backup file names) is derived from it, so a second build
   with another applicationId installs next to this one without sharing data.

## Reference implementation

`diapilot-reference/` holds the stage specifications and the validated Python
core (stage 0: ISF and the digital twin; stage 1: data collection) that the
mathematics was ported from. The Kotlin tests mirror its contracts, so the port
is checked against an independent reference rather than against itself. A
research-era correction advisor existed at that stage; it is deliberately
excluded from this publication — the Android app never had one and never
proposes a dose.

## About this repository

- **A snapshot without history.** The development history narrates real
  readings and references personal data, so it stays private.
- **Not included:** the research tooling (JVM analysis runners and modelling
  scripts), the author's personal fitted model, and private engineering notes.
  A stray comment or two may still point at one of those by name; the
  reference points outside this snapshot.
- **The bundled model files describe a synthetic example person**
  (`app/src/main/assets/models/`, `core/src/test/resources/`): an adult with
  type 1 diabetes whose parameters were chosen by hand within typical adult
  ranges, not fitted to anyone. The default carbohydrate sensitivity in the
  code is the same example person's value.
- **Comments were translated from Russian**, and personal episodes were
  removed while translating. Quantitative statements that remain in comments
  and in `docs/` are engineering evidence from real use, kept without dates.
- **The UI is English by default, with Russian available.** Switch between
  System, English and Russian in Settings → Language; notifications and the
  widget follow the choice, and Claude answers (chat, dish and component names)
  come back in that language.
- **The offline food-note parser reads English and Russian**: food words,
  number words, and the abbreviations for grams, pieces and millilitres, in
  either language — a note never needs Claude to turn "2 slices of bread"
  into grams. Activity durations are still read in Russian minutes only. Note
  tags and injection purposes are stored as Russian tokens and shown in the UI
  language.
- **It is built around one user.** The defaults in `PersonalParams` were tuned
  on one person. A per-installation start date for regular meal logging
  (`FoodEra`, stored by `FoodEraSettings`) gates learning: it defaults to the
  first-run date and can be moved in Settings. Earlier data is kept but
  ignored; it is deleted (`PreEraPurge`) only after the user has moved the
  start date and confirmed the purge.
- **License: GPL-3.0** (see `LICENSE`). Parts of the Libre 2 transport (NFC,
  BLE, FRAM decoding) and the NovoPen protocol code are ports of
  [xDrip+](https://github.com/NightscoutFoundation/xDrip) (GPL-3.0), which
  requires it; the corresponding source files carry their own attribution
  header.

## How it was built

DiaPilot was developed AI-native over several months, with Claude Code as a
pair throughout, and with the verification discipline that requires: contract
tests against an independent reference, a deterministic core that owns every
number, and an LLM confined to explanation. That development history is not
published because it contains the maintainer's own medical data; this
repository starts from a scrubbed snapshot.

The public history is the publication work itself, done over a few days in
September 2026: comments translated to English, personal data removed,
security fixes, internationalisation, editions and tests. Most of it was
written by AI coding agents (Claude) under the maintainer's direction. The
history groups that work into one commit per work package, and each commit
carries the co-author lines of the agent commits it combines. Nothing was
merged on an agent's word:

- every change kept the full test suite green, with golden files pinned;
- mechanical checks proved that comment-only changes touched no code, that
  moving prose into documents lost no number, and that an import cleanup did
  nothing else;
- the full history was scanned for personal data and secrets before
  publishing, and CI runs a secret scan on every push.

The audit in [`docs/audit.md`](docs/audit.md) was also AI-assisted and is not
an independent third-party review. Each finding cites a file and line and is
closed only with a test. Product and safety decisions — what the store edition
may say, how the insulin tail is bounded, which alerts fire — are the
maintainer's. Not every diff has had line-by-line human review; the audit lists
the known gaps.
