# Roadmap — two editions, one trunk

Companion to [`audit.md`](audit.md) (findings) and
[`editions.md`](editions.md) (the feature split, and the scaffold that
enforces it). This is the plan; statuses live in the audit.

## Principles

1. **One trunk, two flavors.** `oss` and `store` are built from the same code.
   `Edition` is the only branching point; a feature is born on its side.
2. **The tense rule** decides disputed cases. "Will / in N minutes / eat /
   remaining" → `oss`. "Now / usually / last time / this week" → `store`.
3. **OSS is the head.** It is the research vehicle and gets every feature
   first; the store edition lags one release and never receives a feature
   before OSS.
4. **Two announcement moments, two readiness bars.** The repository is read
   by code reviewers as soon as the security findings are closed and the
   README carries screenshots; users are invited only once a stranger's phone
   is known to collect data.

## Where the line is

| | Store | OSS |
|---|---|---|
| Glucose sources | xDrip, Juggluco, Nightscout, LibreLinkUp (later), Health Connect, BLE meters (later), CSV import (later), NovoPen NFC | + NFC Libre, OOP2, own BLE |
| Alerts | threshold now, rapid fall by slope, data stalled | + predictive low, sustained high, rapid fall by projection |
| Model surfaces | day decomposition (past), yesterday's forecast vs actual, measured insulin curve as a fact, dish response profiles, patterns, AGP report | + 3 h line, band, What-if, IOB, Auto-fit, tuning, day-10 checkpoint |
| Watch feed | value, trend, TIR, last dose | + `predictBWP`, `hypoAlert`, `predict*` lines |
| LLM | chat about the past, digest, food from text/photo — with disclosure and a report control | same |
| Wording | shows / summarises / your typical response | predict / forecast / will |

Open decisions: IOB in store (recommended: no — it is "how much is still to
act", future tense; the scaffold has it OFF in store, which is that
recommendation applied and still reversible in one line); LibreLinkUp
(standalone for Libre users vs. an unofficial Abbott API); **sustained high**,
which this table puts in the OSS column although it is past tense and reads no
forecast — the scaffold leaves it in both, see `editions.md`.

## Editions and tiers

Three versions reach a user, built from **two flavors and one runtime flag** —
never three code paths:

- **compile time**: `oss` and `store`, branching only through
  `Edition { prospective, sensorDirect }` (see [`editions.md`](editions.md)).
- **run time, store only**: an entitlement (free / paid). In `oss` the
  entitlement is the constant "everything" and no billing code is compiled in.

Every feature answers two independent questions: **is it allowed in this
edition** (regulatory) and **is it included in this tier** (commercial).

| Feature | oss | store free | store paid |
|---|---|---|---|
| Logbook, manual entry, barcode, own history, full export | yes | yes | yes |
| Threshold alerts, data-stalled detector | yes | yes | yes |
| Photo and voice capture | yes, own key | no | yes |
| Automatic meal detection from the curve | yes | no | yes |
| Dish response profiles, weekly digest, doctor report | yes | no | yes |
| Cloud backup | yes, own folder | no | yes |
| Forecast line, band, What-if, IOB, tuning, predictive alerts | yes | never | never |
| NFC Libre, OOP2, own BLE | yes | never | never |
| Billing and subscription | no | yes | yes |

The two divergences have different causes and different lifetimes. Prospective
features and sensor-direct paths stay out of the store because of MDR and Play
policy — permanently. Photo, detection and digests stay out of the free tier
because of money — a pricing decision, revisable.

**Rules that do not bend.** A person's own data is never behind a paywall:
viewing history and full export are free in every version, and so are alerts.
The free store tier is a complete logbook, not a demo. And there are two
legitimate ways not to pay — bring your own model key, or use the OSS build;
the pricing page says so, because in this niche transparency is the marketing.

### What that costs in code

Three requirements, cheap now and expensive later:

1. **One declaration per feature.** A capability registry where each entry
   records whether it needs `prospective`, `sensorDirect` or a paid
   entitlement. A screen asks "is this capability available", never "which
   flavor am I". Without it the checks spread through the code the way
   `Stores.get` did (audit A1).
2. **Billing lives in `src/store/` only.** Not taste — licence: the Play
   Billing library is proprietary and the published code is GPL-3.0. Billing in
   shared code would create a licence conflict and close the F-Droid route
   (O-E).
3. **Tests as a matrix.** Alongside `EditionContractTest`, a tier test with a
   fake entitlement: two editions by two tiers. The load-bearing assertions are
   that no tier in `store` reaches a prospective feature, and that `oss` never
   asks about an entitlement.

A fourth, not about flavors but about the same bill: **food resolution behind
one interface with a cache** — local dish library, then a shared cache keyed by
normalised name and barcode, then the model only on a miss, with a counter for
model calls. People eat the same things, so the cache is what keeps the per-user
model cost near zero; and both "own key" and "subscription" then travel the same
path.

## Sources: the way off the xDrip dependency

The dependency chain (xDrip + OOPAlgorithm2 + Health Connect) is the biggest
obstacle on a stranger's phone (audit P6) and the standing risk of this plan.
Two ways out, in order of cost:

- **Nightscout** (O-B) — for users who already run it.
- **LibreLinkUp** — the official FreeStyle LibreLink app shares to a
  LibreLinkUp connection, and the data is read back from the vendor cloud. No
  xDrip, no NFC, no root, no Bluetooth permission: the whole Data-sources
  screen collapses to one row for such a user. The cost is an unofficial API
  that has broken before, a vendor-cloud round trip and a delay, so the source
  is **retrospective only** — good for the store edition, never for an alert.
  Worth pulling forward from S-3 to S-1 for exactly that reason.

## Companion website: deliberately not built

A browser view of one's own history already exists for these users —
Nightscout, and the vendors' own web views. A hosted site of our own would add
server-side storage of medical data, an authentication surface, data-controller
duties under GDPR and hosting cost, and it would contradict the
privacy-by-construction claim that is this project's strongest argument.

The same needs, answered cheaper: a **doctor report as a PDF** generated on the
device for "see it on a big screen" and "show it to a clinician", and the
self-hosted companion server already in `server/` for power users who want a
dashboard. Revisit only with a clinic-side buyer who needs several patients and
roles — that is S-3, a different customer, and not an extension of the
consumer app.

## OSS track

| Phase | Content | Gate |
|---|---|---|
| **O-A Portfolio** | close S1–S5; engine diary → docs; architecture overview + history split; README with screenshots, Status, Install; CHANGELOG; audit with Status column; imports instead of FQNs; optional accuracy report on the golden set | audit committed with statuses; tag `v1.3.0`; announce to code reviewers |
| **O-B First users** | edition scaffold (**done**, see [`editions.md`](editions.md)); data-sources screen (**done**); alerts independent of OOPAlgorithm2 (**done**, `collect/AlertTick.kt` — audit P7); onboarding with manual ISF/ICR/weight and insulin presets (tail 300); weight → CS (**done**); tail domain 240+ (**done**, the automatic floor only — the hand tier keeps 120..600 and the end landmark itself is O-D); release signing + GitHub Releases; English food parser (**done**); Nightscout source; diagnostics export without values (**done**, `diag/` — see [`architecture.md`](architecture.md) §7) | `v1.4.0`; 5 strangers' phones report data for 7 days via diagnostics export |
| **O-C Usable by day 10** | day-10 checkpoint; threshold alerts alongside predictive; backup password / restore undo; accuracy report | `v1.5.0`; ≥3 users reach a measured model; first false-alarm feedback |
| **O-D Model on n>1** | M1 end landmark by plateau; M5 ramp policy bench; cold-start calibration on several people; M6 protein/fat amplitude only after a bench | every number change ships with a multi-person walk-forward |
| **O-E Distribution** | F-Droid — needs a flavor without ML Kit (ZXing); reproducible build | accepted |

**What O-B still owes `v1.4.0`.** Nine of its ten items are in `public`: the
edition scaffold, the data-sources screen, the alert path off OOPAlgorithm2,
weight → CS, the tail domain (both halves of it — the automatic floor and the
hand tier's own range), the English food parser, the value-free diagnostics
export, and — from the September second read — **onboarding** with the
disclaimer, manual ISF / weight, insulin presets and an uncalibrated mode
behind it (audit M2, P2; a preset is written into the hand tier, whose domain
is 120..600, so the presets themselves decide what they offer, and the shipped
example person stays at 300), and **release signing** in the build and the
release workflow (S8 — the upload key itself still has to be created; until
then the build falls back to the debug key and says so). One item is not:
the **Nightscout source** (P6), whose row already exists on the data-sources
screen and needs one argument wired — plus one `AlertTick.fire` call and one
`Source` entry, which is all a new source needs to get the whole alert set.
Phase B also planned to move the research bench behind a build of its own
(A3); that did not run, so the bench still ships in both editions. The same
second read pulled two O-C items forward — restore validation with an undo
(S7) and the optional backup password (S6) — and the accuracy tool's naive
baselines (M13). There is no `v1.4.0` tag until Nightscout lands and the
keystore exists.

**One bug the gate inherited rather than fixed, and re-derived — now closed by
`phaseB/b9`.** On a phone without OOPAlgorithm2 installed — which is every
stranger's phone — **no alert could fire at all** in either edition:
`HypoAlertNotifier.maybeNotify` (the predictive low *and* the reading-driven
low, sustained low, sensor artifact and sustained high), `RapidFallNotifier`
and `CompanionSync.pushIfDue` had exactly one caller each, and it was the tail
of the OOP2 receiver; the stall notification had one too, inside the poll
worker's own-BLE branch. The app still collected, drew and answered the watch,
so nothing on screen said the alarms were gone. `editions.md` used to describe
this as "no heartbeat" and count the widget and the watch among the casualties;
both survived — the widget on its 30-minute system period, the watch long-poll
through `XdripBgReceiver` — which made the bug narrower and worse than the note
claimed.

b9 took it ahead of the three items above, because an onboarding screen, a
release key and a second glucose source are all worth less on a phone where no
alarm can fire. `collect/AlertTick.kt` is now the single entry point, called
from every path a reading arrives on (broadcast, OOP2 minute stream, web poll,
NFC scan, hand-entered fingerstick) and from the existing 15-minute
`TreatmentsPollWorker` as the periodic backstop; the Data sources screen leads
with an **Alerts** row saying whether an alarm can fire and, if not, why. Two
consequences worth stating plainly: the data-stalled detector now fires on every
phone rather than in own-BLE mode alone, and the rapid-fall alert is reachable
without the per-minute stream for the first time — fitted over a thirty-minute
window on the five-minute grid, with the detector's own slope threshold and
projection untouched. Recorded as audit finding **P7**.

**Decisions the phase-B packages raised and did not take** (each is one line of
code away, none of them was the package's to settle):

- ~~**A hand-entered insulin tail under 240 min is now refused.**~~ **Taken in
  phaseB/b10**, the way this line proposed. The manual tier has a bound of its
  own — `PhysioBoundsV1.insulinTailMinManualRange` = 120..600 — checked at the
  resolver and carried through the artifact's own invariant, so a stated end of
  action reaches the kernel, IOB, the forecast, What-if and the alert exactly as
  entered. `insulinTailMinRange` = 240..600 keeps serving the AUTOMATIC doors:
  the measured clamp, the Auto-fit corridor and the bundled asset. The corridor
  is now held inside that floor instead of proposing under it and discarding the
  candidates one at a time, and with a tail set by hand the fit locks that axis
  rather than arguing with it. See `docs/insulin-model.md` §2.5.1, and the
  end-of-action comparison added to `docs/accuracy.md` for how to settle the
  underlying disagreement with a database instead of a ruling.
- **Carbohydrates on board in the store edition.** IOB is off there; COB —
  "what is left of what was eaten" — is the same tense and is still in the
  status line in both, because the package that gated IOB named IOB.
- **The OOP2 row reads red on an OSS phone that never installed OOP2.** True,
  and alarming to someone who runs on the xDrip broadcast alone. The
  alternative is an opt-in preference so the row reads "off"; there is none
  today.
- **The Nightscout row says "not configured" and promises the next release.**
  Confirm that wording, or hide the row until the source exists.
- **The diagnostics export's body is English in both editions.** The button,
  hint and share sheet around it are translated; the log lines inside it cannot
  be. Only the header block could be.
- ~~**`settings_screen_insulin_engine_note`** still offers "tail 120" as a
  worked example, which is no longer a legal value.~~ **Overtaken by
  phaseB/b10**, in both directions: the string no longer quotes a worked
  example at all (it states the two ranges), and 120 is a legal hand-entered
  value again. `physio_tuning_section_curve_shape_explainer`'s stale bench
  quote of "28 · 40 · 30 · 165" went with it — the fitter's own corridor
  cannot produce 165.
- **The rapid-fall alert now fires in the store edition, and its text says
  "in ~20 min could be X".** b9 gave the detector a five-minute-grid fallback,
  so the alarm the table calls "rapid fall by slope" is reachable there for the
  first time — carrying a sentence about later that the tense rule assigns to
  `oss`, and that the table's own OSS column calls "rapid fall by projection".
  Drop the projection clause from the store edition's text, or gate the alert
  behind `Edition.prospective` there. One line either way; nobody has chosen.
- **The data-stalled notification now reaches a phone that never had a
  reading.** It used to fire only in own-BLE mode. On a fresh install with no
  source configured it will arrive within about fifteen minutes and then every
  forty-five — which is the honest half of P7 ("nothing said the alarms were
  gone"), but it lands on someone who has not finished setting the app up,
  because onboarding does not exist yet. Holding it back until a first reading
  has ever arrived is one condition in `AlertTick.stall`, and it costs the "you
  installed this and it never worked" signal.

## Store track

| Phase | Content | Gate |
|---|---|---|
| **S-0 Channel** (end of O-B) | Play app, **internal testing** track with the store flavor — no review, auto-updates, same friends | store build runs next to OSS on ≥3 phones; collection works without prospective features |
| **S-1 Compliance** (with O-C) | privacy policy on Pages; disclosure before LLM/OFF; report control; manifest without `USE_EXACT_ALARM`; FGS justification; console checklist; upload keystore; Juggluco verified | closed testing running (12 testers, 14 days) |
| **S-2 Production** (after O-A/O-C) | listing by the wording rule; Health-apps declaration "displays and summarises the user's own data, no prediction"; submit | approved → link in README; rejected → stays on closed track as beta, OSS unaffected, rejection text drives S-3 |
| **S-3 Own value** | LibreLinkUp, Dexcom official API (retrospective, delayed — a fit), BLE meters, CSV import; AGP report; day decomposition; patterns; write to Health Connect | store positioned as "the logbook that fills itself + a report for the doctor" |
| **S-4 Strategic** | Twin: the OSS forecast as a second app over `DiaForApi`, so a Play user adds prediction without reinstalling | requires moving chart/alerts out of `:app`; done with A2 |

## How the tracks interact

- Releases: OSS tags `vX.Y.Z`; store takes the same tag with `-store` on the
  next track about a week later; bug fixes land in both from one commit.
- Every work package is tagged `oss | store | both`; both flavors must build.
- Testers are the same people; the Play closed track needs them in a group or
  on an opt-in link — arrange during O-B.
- Different applicationIds → separate databases; store → OSS migration is
  export/restore until S-4.
- Never in store: future line, IOB, predictive alerts, carb hint, What-if,
  Auto-fit, sensor-direct paths.

## Checkpoints

| When | Decision |
|---|---|
| before O-B | IOB in store; which edition keeps the clean applicationId; store name |
| before the first store build | free/paid line per the table above; photo quota; whether the doctor report is the paid anchor |
| after O-B | from 5 phones' diagnostics: what breaks in the collection chain → content of O-C |
| after O-C | false-alarm feedback: whether the M5 bench comes first in O-D |
| S-2 | announce to user communities; LibreLinkUp yes/no |
| S-3+ | Twin — only if the store has users the OSS build does not |

## Risks

- One maintainer, two tracks — the store lags and takes only finished work; no
  store-only development before S-3.
- xDrip dependency — Nightscout in O-B and Juggluco in S-1 are the cheapest
  way out.
- Abbott — only at S-3 (LibreLinkUp), reversible.
- n=1 stays n=1 until O-B produces other people's databases; everything in
  O-D waits for that.

## Out of launch scope

Recorded so they stop being re-discovered: the engine constructor's sweep knobs
and the `V1` / `StageN` names in class names (audit A3's remainder), store
compliance beyond S-1, threshold alerts as a replacement rather than an
addition, backup passwords, audit A2 and A4-A7, M3, M5, M6, M7, other data
sources, and dependency-injection frameworks.
