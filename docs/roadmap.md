# Roadmap — two editions, one trunk

Companion to `audit.md` (findings) and `editions.md` (the feature
split, once written). This is the plan; statuses live in the audit.

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
act", future tense); LibreLinkUp (standalone for Libre users vs. an unofficial
Abbott API).

## OSS track

| Phase | Content | Gate |
|---|---|---|
| **O-A Portfolio** | close S1–S5; engine diary → docs; architecture overview + history split; README with screenshots, Status, Install; CHANGELOG; audit with Status column; imports instead of FQNs; optional accuracy report on the golden set | audit committed with statuses; tag `v1.3.0`; announce to code reviewers |
| **O-B First users** | edition scaffold; data-sources screen; onboarding with manual ISF/ICR/weight and insulin presets (tail 300); weight → CS; tail domain 240+; release signing + GitHub Releases; English food parser; Nightscout source; diagnostics export without values | `v1.4.0`; 5 strangers' phones report data for 7 days via diagnostics export |
| **O-C Usable by day 10** | day-10 checkpoint; threshold alerts alongside predictive; backup password / restore undo; accuracy report | `v1.5.0`; ≥3 users reach a measured model; first false-alarm feedback |
| **O-D Model on n>1** | M1 end landmark by plateau; M5 ramp policy bench; cold-start calibration on several people; M6 protein/fat amplitude only after a bench | every number change ships with a multi-person walk-forward |
| **O-E Distribution** | F-Droid — needs a flavor without ML Kit (ZXing); reproducible build | accepted |

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
