# Editions — one trunk, two flavors

Companion to [`roadmap.md`](roadmap.md) (the plan) and
[`audit.md`](audit.md) (the findings). This file describes the **scaffold**: how
the two editions are built, what each one carries, and the single place in the
code that decides.

## The two flavors

| | `oss` | `store` |
|---|---|---|
| Gradle flavor | `oss` (the default) | `store` |
| applicationId | `io.github.obdosok.diapilot` | `io.github.obdosok.diapilot.store` |
| App name (release) | `DiaPilot` | `DiaPilot Log` — **placeholder** |
| App name (debug) | `DiaPilot debug` | `DiaPilot Log debug` |
| Variant tasks | `assembleOssDebug`, `testOssDebugUnitTest` | `assembleStoreDebug`, `testStoreDebugUnitTest` |
| `Edition.prospective` | `true` | `false` |
| `Edition.sensorDirect` | `true` | `false` |

The store name is a **placeholder and an open decision** — `roadmap.md` lists
"store name" as a checkpoint before O-B. It lives in
`app/src/store/res/values/strings.xml` as `app_name`, `translatable="false"`
like the shared one (a brand is the same in both languages, so there is no
`values-ru` counterpart).

There are **two** of them, because resource priority puts the build type above
the flavor: `src/debug` already renames every debug install "DiaPilot debug",
so without `app/src/storeDebug/res/values/strings.xml` the two editions' debug
builds would carry the same launcher label — which is the one thing that
renaming exists to prevent. Renaming the edition means editing those two
strings and nothing else.

The **suffix** rather than a second id: every name the app shares with the rest
of the phone derives from `applicationId` (see `AppIdentity`) — the
FileProvider authority, the broadcast actions, the backup file names — so a
suffixed id makes the two editions installable side by side, each with its own
database and its own backups. Which edition eventually keeps the clean id is
also a "before O-B" checkpoint.

## The tense rule

The rule that decides where a feature is born, and it is a rule about **the
sentence the user reads**, not about the algorithm behind it:

> "Will / in N minutes / eat / remaining" → `oss`.
> "Now / usually / last time / this week" → both editions.

So a retrospective surface may use the forecast engine freely: "what the model
said an hour ago, against what actually happened" is a past-tense fact and
ships in both. What the store edition never does is **state, draw or publish
something about later**.

The corollary, and the thing to check in review: where a surface loses its
content it must read as **absent**, never as a value. The line is not drawn,
the card is not composed, the JSON key is not written. `null` propagates
through this codebase's surfaces as "unknown"; `0.0` reads as "finished
acting", which is a different and wrong claim. Nothing is deleted for an
edition — a gated feature stays in the code and is simply not reachable.

## Where the line is

Copied from [`roadmap.md`](roadmap.md) so this file can be read on its own. It
describes the **destination**, not what the scaffold has already moved — see
"What the scaffold gates today" below for the difference.

| | Store | OSS |
|---|---|---|
| Glucose sources | xDrip, Juggluco, Nightscout, LibreLinkUp (later), Health Connect, BLE meters (later), CSV import (later), NovoPen NFC | + NFC Libre, OOP2, own BLE |
| Alerts | threshold now, rapid fall by slope, data stalled | + predictive low, sustained high, rapid fall by projection |
| Model surfaces | day decomposition (past), yesterday's forecast vs actual, measured insulin curve as a fact, dish response profiles, patterns, AGP report | + 3 h line, band, What-if, IOB, Auto-fit, tuning, day-10 checkpoint |
| Watch feed | value, trend, TIR, last dose | + `predictBWP`, `hypoAlert`, `predict*` lines |
| LLM | chat about the past, digest, food from text/photo — with disclosure and a report control | same |
| Wording | shows / summarises / your typical response | predict / forecast / will |

## The single branching point

`app/src/main/java/io/github/obdosok/diapilot/Edition.kt` — one object, two
booleans, read from `BuildConfig` fields the flavors declare. **Nothing else
in the app reads `BuildConfig.FLAVOR`**; every gate reads `Edition.prospective`
or `Edition.sensorDirect`.

## What the scaffold gates today

### `Edition.prospective`

| capability | gate |
|---|---|
| 3 h forecast line, uncertainty band | `MainState.kt` — `forecastResult` is not computed, so `prediction` is empty and the chart's line and band are not drawn |
| What-if | `TodayScreen.kt` — `hasWhatIfEngine` / `activityWhatIfAvailable`; the panel is not composed and the chart's what-if overlays stay empty |
| insulin on board | `HybridRuntimeMetrics.surfaceIobUnits` / `surfaceIobSeries` — the ONE entry every IOB surface reads (header, status line, chart rail, widget, watch, LLM briefing); returns `null` / empty, which those surfaces already render as absent |
| tuning surfaces, Auto-fit included | `SettingsScreen.kt` — `PhysioTuningSection` is not composed |
| predictive low, predicted high | `HypoAlertNotifier.kt` — the model is not fetched, so `forecastOk` is false and `predictedHit` false. The reading-driven alarms (observed low, sustained low, sensor artifact, sustained high) are untouched |
| `predict*` in `/info.json` | `WatchServer.kt` — `predictIOB` is written only when IOB is known; `predictionPoints` returns empty, which removes `predictBWP` and the `predict` / `predLo` / `predHi` graph lines |
| forecast in the companion push | `CompanionSync.kt` — `forecast` and `status_line` are omitted from the payload, not sent empty (the dashboard defaults both) |
| background model builds | the three off-screen builders are the three consumers above: the collector heartbeat's alert call, the watch payload and the companion push. Plus `MainActivity`'s launch-time "APPLIED to model" thread, which builds the physio artifact only to report the forecast model |

### `Edition.sensorDirect`

| capability | gate |
|---|---|
| NFC Libre scanning | `MainActivity.onResume` omits `FLAG_READER_NFC_V`, so an ISO-15693 tag is never dispatched to the app; `Settings.libreNfcEnabled` is false regardless of the stored preference; the Settings card is not composed. The **NovoPen** scan stays in both editions — it reads a pen's dose log, not a sensor |
| OOP2 decoded minute stream | `CollectorService.kt` — the payload is not parsed and no minute reading is written. The receiver itself stays registered in both editions; see the finding below |
| own BLE link | `Settings.ownBleEnabled` is false, so the client is never started and the collector never enters own-BLE mode; `BLUETOOTH_CONNECT` is declared in the `oss` manifest only |
| the OOP2 and own-BLE rows of the Data sources screen | `collect/DataSources.kt` — `dataSourceRows` does not build them, so the screen is two rows shorter rather than reporting on sources this edition has no path to. The NFC row stays in both, because the NovoPen scan needs the radio there too; only its one-line explanation differs, and the store edition's does not mention a sensor scan |

### The manifest

`app/src/main/AndroidManifest.xml` is shared; `app/src/oss/AndroidManifest.xml`
adds the two permissions the store edition must not ask for:

- **`USE_EXACT_ALARM`** — Play restricts it to alarm/timer/calendar apps
  (audit P3). `SCHEDULE_EXACT_ALARM` stays in the shared manifest, so the store
  edition keeps the capability and only loses the automatic grant.
- **`BLUETOOTH_CONNECT`** — its only caller is the own-BLE client.

## Two things this scaffold does not settle

**The alerts are coupled to OOP2.** The tail of `CollectorService`'s OOP2
receiver is the app's only per-minute heartbeat: the xDrip receiver stores a
reading and returns, and the periodic worker polls but never alerts. Gating the
receiver off would therefore have taken every alert with it, in the edition
whose entire job is collection. So both editions still register it and only the
ingestion is gated.

Re-derived at the phase-B gate, because this paragraph used to claim more than
the code does. What that tail is the **only** caller of:
`HypoAlertNotifier.maybeNotify` (so *all* of it — the predictive low and the
reading-driven low, sustained low, sensor artifact and sustained high),
`RapidFallNotifier.maybeNotify` and `CompanionSync.pushIfDue`. What survives
without it: the widget, which also has the 30-minute system period in
`widget_bg_info.xml` as a safety net, so it goes stale rather than dead; the
watch long-poll, which `XdripBgReceiver` releases too; and the stall
notification, which the worker raises. The bug is therefore narrower and worse
than "no heartbeat" — on a phone without OOPAlgorithm2 the app keeps
collecting, drawing and answering the watch while **no alarm can fire at all**,
which is the one thing a user would never infer from a working screen. Not a
bug any of these packages created. Separating the alerts from that source
belongs in O-B, next to the Nightscout source. The data-sources screen has
since landed and does not fix this — it reports it: the OOP2 row's own line
names the beat, which is what makes such a phone visible to its owner instead
of only to this file.

**Sustained high.** `roadmap.md` puts it in the OSS column, but it is an
observation over past readings ("it has been above 13.9 for an hour"), it
deliberately does not read the forecast, and by the tense rule it belongs in
both editions. The scaffold leaves it in both. If the roadmap's placement was
deliberate — a wording risk for the Health-apps declaration rather than a tense
one — say so and it moves behind `Edition.prospective` in one line.

Two smaller judgement calls, recorded so they are not re-decided silently:

- **Rapid fall** stays in both editions. Its detection is a slope over the
  per-minute stream with no model in it, which is the roadmap's "rapid fall by
  slope"; the 20-minute number in its text is a linear extrapolation of that
  slope, not a forecast. In the store edition it has no minute stream to read
  and simply never fires.
- **COB** ("what is left of what was eaten") is still in the status line in
  both editions. It is future tense by the rule and the roadmap's "carb hint"
  row is adjacent, but the work package named IOB and not COB, and the carb
  hint proper (`predictBWP`) is gated.

## The contract test

`app/src/test/java/io/github/obdosok/diapilot/EditionContractTest.kt` runs on
**both** flavors: every assertion is written against `BuildConfig.FLAVOR`, so
the same source proves "the oss build has it" on one run and "the store build
does not" on the other. It checks the two booleans against the flavor, the
applicationId suffix, the capability table, the `/info.json` field set, the
watch graph's line names, the companion payload's field set, IOB being unknown
rather than zero, the two sensor-direct switches, and the three manifest
permissions.

Its prospective assertions are deliberately **one-sided**, and the class says
why: a forecast needs a built person model, which needs weeks of a real
person's episodes, so "the oss build draws a 3 h line" is not a claim a unit
test can make — on an empty database both editions draw nothing, for different
reasons. What it does assert is the direction a store submission turns on: with
data in place, the store edition publishes and computes nothing about later.
