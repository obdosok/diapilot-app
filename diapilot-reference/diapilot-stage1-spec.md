# Spec: DiaPilot Stage 1 — frictionless data collection

> Continues stage 0. Moves from analyzing a finished export to live on-device data
> collection. Part of the logic (the collection core) is implemented and tested in
> Python; the mobile wrapper (Android) is a separate layer on top of that core.

## 1. Goal

A companion app that **itself** collects data for later analysis (stages 0/2+):
glucose and insulin — automatically from xDrip; meals — via automatic detection from
the curve's shape, with after-the-fact labelling that takes five seconds; context
(injection site, sleep debt, illness, insulin change) — via free-form annotations.
The main principle from the design document: **no recording discipline required**.
Anything that requires a manual diary dies within two weeks.

The motivation is confirmed by stage 0 data: across the whole export, carbs are never
recorded, and that is the main limiting factor for every calculation. Stage 1 closes
that gap.

## 2. Integration with xDrip (verified facts)

**Two channels, both local, no cloud:**

- **Glucose — broadcast.** xDrip sends an `Intent` with action
  `com.eveningoutpost.dexdrip.BgEstimate` on every new reading (~5 min). Extras:
  - `com.eveningoutpost.dexdrip.Extras.BgEstimate` — double, **mg/dL** (divide by 18.0182);
  - `com.eveningoutpost.dexdrip.Extras.Time` — long, ms epoch;
  - `com.eveningoutpost.dexdrip.Extras.BgSlopeName` — trend (`Flat`, `SingleUp`, …);
  - `com.eveningoutpost.dexdrip.Extras.Raw`, `…SensorBattery` — optional.
  Enabled in xDrip via: Inter-app settings → Broadcast locally ON. The app registers a
  `BroadcastReceiver` for this action (for Android 11+, add `<queries>` for the xDrip
  package).
- **Insulin/carbs — local web service.** xDrip runs a Nightscout-compatible REST
  service on `127.0.0.1:17580` (loopback, private). Treatments are polled from there
  (an endpoint like `/api/v1/treatments`, fields `insulin`, `carbs`,
  `created_at`/`timestamp`, `eventType`). Poll, for example, every 5–15 min, catching
  up on gaps.

**Important (verify on-device):** the exact extras and the availability of the
treatments endpoint depend on the xDrip version and settings. The parsing core must
tolerate missing fields and log what it doesn't recognize rather than crash. The
glucose broadcast is glucose-only; don't expect insulin in it.

## 3. Data architecture (two layers)

### Hard core (fixed schema)
- `glucose_readings(ts_utc, mgdl, mmol, trend, source)` — from the broadcast.
- `insulin_events(ts_utc, units, insulin_type, source)` — from treatments.
(Later: heart rate, sleep, activity — from Health Connect; not required in stage 1.)

### Flexible context (free-form annotations)
- `annotations(ts_utc, kind, content, media_ref)` — text/voice/photo/tag, no required
  fields.
- `meal_events(...)` — automatic detection (section 4), awaiting labelling.
- `meal_labels(name, use_count)` — personal dish labels; repeated use of a label ×N
  plus the actual glucose response trains an absorption profile (no gram counts
  needed).

**Structure is derived on the way out, not required on the way in.** Annotations are
stored raw; extracting normalized features (injection site → thigh; sleep debt;
illness with a date) is a separate layer (LLM, stage 2+). The schema does not need to
know the list of factors in advance.

## 4. Automatic meal detection

A meal shows up in BG as a sustained rise. The detector (over the glucose + bolus
stream):

- **Rise.** From a local minimum (trough) to a peak: a net increase ≥
  `rise_threshold` (default 2.0 mmol/L) within ≤ `rise_window` (default 90 min), with
  a predominantly positive slope. One rise = one candidate.
- **Bolus linkage.** A bolus in the window `[onset − 45 min; onset + 20 min]`
  (accounting for pre-bolusing). A bolus present → "announced meal"; none → "unannounced
  / missed bolus" (also worth asking about). A bolus without a rise (glucose falling)
  is a correction, not a meal.
- **Event.** `onset, peak, pre_bg, peak_bg, rise, time_to_peak, bolus_units|None, kind`.
- Dedup of overlapping events; filtering of micro-fluctuations below the threshold.

The detector uses the same BG-curve math as the twin, and it is **complementary** to
the stage 0 clean-correction detector: what the meal detector flags as an announced
meal is exactly the "large isolated boluses" that were contaminating the ISF
estimate. Automatic detection removes them from the ISF analysis and turns them into
labelled meals.

## 5. After-the-fact labelling (5-second UX)

On a detector event, a short prompt: "looks like you ate around 13:40 — what was it?"
The answer: a tap on a frequent dish label, a photo with no caption, a voice note, or
two words. A growing vocabulary of frequent labels becomes quick-entry buttons. No
gram counts.

## 6. Functional requirements (core, implemented and tested in Python)

1. `parse_bg_broadcast(extras) -> Reading` — normalizes xDrip broadcast extras into a
   reading (mg/dL→mmol/L, ms→ts), tolerant of gaps.
2. `parse_treatments(json) -> list[InsulinEvent]` — from a Nightscout-compatible
   response.
3. `Store` (SQLite) — the section 3 schema; idempotent writes (dedup by ts), range
   queries.
4. `detect_meals(readings, boluses, cfg) -> list[MealEvent]` — section 4.
5. CLI `collect-detect export.sqlite` — run the meal detector over an existing export
   and show the meals found (validation on historical data before the mobile
   wrapper).

Mobile wrapper (outside the Python core, a separate Android project): a
`BroadcastReceiver` for glucose; a periodic worker polling `127.0.0.1:17580`; a local
DB (the same schema); a labelling screen.

## 7. Non-functional requirements

- Core: Python 3.11+, stdlib sqlite3 + numpy/pandas; portable to the mobile layer by
  logic.
- **Tests before implementation:** broadcast parser (valid/malformed extras);
  treatments parser; idempotent writes to the Store; meal detector (rise with bolus →
  announced meal; rise without bolus → unannounced; drop on correction → not a meal;
  micro-fluctuation → ignored; dedup).
- Fully offline, data never leaves the device (privacy is a core product feature).
- Code/comments in English; user-facing text in Russian.

## 8. Order of work (for the agent)

1. Implement the collection core: Store schema + broadcast/treatments parsers + meal
   detector, with tests (until green). Show the structure and the tests.
2. Run the meal detector on the real stage 0 export: how many meals found, announced/
   unannounced, overlap with the "large isolated boluses" from the ISF analysis. Show
   the results.
3. Reconcile the meal detector and the correction detector: show that meal labelling
   cleans up the ISF sample (recompute ISF, excluding BG rises from the windows).
4. Design the mobile wrapper: receiver manifest, polling worker, labelling screen — as
   an implementation plan for Android Studio (outside this environment).

## 9. Acceptance criteria

- Tests green; parsers are robust to malformed/incomplete data.
- On a real export the detector finds plausible meals; announced meals overlap with
  large boluses; unannounced rises are flagged separately.
- It is shown that automatic meal labelling improves the cleanliness of the ISF
  sample (fewer masked meal boluses among "corrections").
- A mobile wrapper plan is ready, tied to the verified xDrip format.
