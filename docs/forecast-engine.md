# The forecast engine — shape, knobs, and what was rejected

This document is the engineering record of DiaPilot's three-hour forecast: why
the model has the shape it has, which alternatives were measured and dropped,
and what every knob means. It used to live as an essay inside the constructors
of four files; the code now carries a short KDoc and a pointer to the section
here.

The four files this document explains:

| File | What it owns | Section |
|---|---|---|
| `core/src/main/kotlin/com/diapilot/core/hybrid/HybridForecastEngine.kt` | the causal forecast itself: insulin, food, background, uncertainty | [2](#2-the-engine-and-its-knobs) |
| `core/src/main/kotlin/com/diapilot/core/physio/SegmentLandmarksV1.kt` | reading the insulin shape off the user's own doses | [3](#3-reading-the-insulin-shape-from-segments) |
| `core/src/main/kotlin/com/diapilot/core/physio/PhysioAutoFitV1.kt` | the one fitter shared by the laptop bench and the phone | [4](#4-the-fitter-one-search-two-callers) |
| `app/src/main/java/io/github/obdosok/diapilot/data/PhysioRuntime.kt` | serving one immutable artifact per model revision on the device | [5](#5-serving-the-artifact-on-the-device) |

Related reading: [`insulin-model.md`](insulin-model.md) for the measurement
cycle end to end, [`architecture.md`](architecture.md) for where these modules
sit, [`audit.md`](audit.md) for the open findings against the model, and
[`history.md`](history.md) for the older branch audits.

A note on the numbers below. They are engineering evidence from real use on one
person, kept without dates. Several of them are quoted to three or four
decimals because that is the resolution at which two arms of an experiment were
distinguished, not because the underlying physiology is known that precisely.
Audit finding M9 is that these figures are not yet reproducible from a
published corpus; until they are, treat each as "this is what was observed on
the stand named beside it".

---

## 1. The shape of the model, in one page

The engine is a pure Kotlin port of the v11 causal runtime. It does not read a
database, a clock, preferences, or live glucose after the supplied anchor. That
is what makes blind replay and Python/Kotlin parity testable, and it is the
reason every number in the forecast can be reproduced from a recorded state.

Four contributions are summed on a five-minute grid:

- **insulin** — a personalized, dose-dependent action CDF, whose landmarks are
  measured from the user's own doses (section 3) or entered by hand;
- **food** — grams times carbohydrate sensitivity, shaped by a mixture of three
  population triangles and metered by a gastric queue (section 2);
- **background** — the drift the body has without an intervention, plus a
  reversion of the trajectory toward a target level;
- **uncertainty** — a band calibrated on the user's own residuals.

Two rules run through the whole design and explain most of the choices below.

**Amplitude and timing are measured on different evidence.** Insulin timing is
read segment by segment off every trusted dose; insulin amplitude (ISF) comes
from tagged corrections only; food amplitude is `grams x CS` and nothing else.
Mixing them makes the fit unidentifiable, and the failure is silent: the search
pays for a missing food term with the insulin tail, or reads a food deficit as
weak insulin. Section 4 lists the three times this happened.

**Composition is expressed in the timing, never as a hidden amplitude
multiplier.** The dish dictionary that used to sit in front of the amplitude —
per-group factors, per-component factors, whole-dish "progressive profiles" —
went with the legacy arm. What a meal contains moves *when* the glucose
arrives (macro shares, the caloric queue, the physical form), not *how much*.

---

## 2. The engine and its knobs

`HybridForecastEngine`'s constructor is a list of physiological policies and
sweep handles. Every default is the value that ships, so a caller that omits an
argument gets exactly the behaviour on the phone; the only way to get anything
else is to ask for it by name, which the studies do, deliberately, when
sweeping it.

### 2.1 One rule for food amplitude

`foodAmplitude` is `grams x globalFactor x calibration`, and the scale below is
applied at exactly one place. The unscaled body has four return sites, and
scaling them individually is how one gets missed (discipline #7).

### 2.2 `appearance` — how carbohydrate leaves the stomach

Defaulted from the shipped arm, not from a constant, so the two cannot
disagree.

This used to be two loose parameters (`emptyingKcalPerHour`, then
`carbSieving`), each defaulting to a value rather than to the arm. That shape is
what let the caloric queue reach the forecast while six other physio consumers
kept the gram queue, and it needed a factory plus a parity test to hold the
line. Now forgetting the argument yields the arm's own physiology.

The queue's own invariant is written as `min(desiredCdf, delivered/carbBasis)`:
a queue may bound arrival, never manufacture it. That invariant is why the
gastric rate stops mattering once the pipe is wide enough — see section 4.4.

### 2.3 `gastricMacroPrior` — fat and protein in the shape's delay and tail

**Kept on, and that is the measured decision, not inertia.**

Dropping these priors does improve the trajectory (60-minute bias +1.52 ->
+1.34 mmol over 157 meals), but `FoodDynamicsPhysioV1Test` caught what the
aggregate cannot see: without the fat tail the queue's throttle turns delivery
into a long flat ramp, and the argmax of the rate falls at the START of it. A
fatty meal then peaks at 27 min against a lean one at 58 — fatty food peaking
EARLIER than lean, which is worse than the double count it was meant to fix,
and it is the landmark the card shows the user.

So the knob stays, off, until the peak landmark is derived from something that a
throttle cannot invert. The median-arrival crossing already exists for exactly
this reason — the original note pointed at `food-model.md` §4, a private
engineering note that is not part of this snapshot.

The historical note on keying, kept because it cost a test run: keying it on
`appearance.caloric` was the first attempt and `CarbSievingTest` refused it
within the hour. Sieving 1.0 is still a caloric queue, so the priors survived
there while the gram queue kept them too, and the pinned identity «sieving 1.0
== gram queue» broke. The physiology does not depend on which arithmetic meters
the stomach, so neither does this.

Only `physioFeatureShapes` reads it, so the v11 arm behind the alert, widget and
watch is untouched — it never enters this branch.

Measured over 157 logged meals (stand `fatsweep`): dropping these priors alone
moves the 60-minute bias +1.52 -> +1.34 mmol; together with retiring the
fat->peak slope, +1.52 -> +1.05.

### 2.4 `macroGastricScale`, `macroTailScale` — sweeping that prior

Sweep knobs for the fat/protein gastric prior. 1.0 is the shipped value, so a
caller that omits them gets exactly the behaviour that ships.

Why they are separate: the diagnosis they exist to test says fat delays the
START too much, not that it lasts too long. On a fast-food burger — 50 g
carbohydrate, 35 g fat, split 92% fast — the shipped prior delivers 2.79 mmol
by 60 min where the same meal with fat zeroed delivers 4.38, and a comparison
trace rose 5.9 mmol in 69 minutes. Scaling the delay and the tail together
could not tell «too slow to start» from «too long to finish», and those want
opposite corrections.

The knob is a SCALE rather than a replacement coefficient on purpose: at 0.0 the
term is exactly the `queueMetersMacros` case that already exists, so the
sweep's endpoints are both already-shipped behaviours and only the interior is
new.

### 2.5 `carbSpread` — how far apart the three carbohydrate bases stand

1.0 ships.

The user's claim, and it is NOT «carb type does not matter»: «the difference
between them is smaller than the model thinks — the priors are population
averages, and they are wrong». The three triangles — fast 5/25/75, medium
10/55/180, slow 15/95/330 — are population constants never fitted on the user
(a known open gap). If reality separates the user's fast and slow carbohydrate
less than those numbers do, every mixture weight is amplified by a spread that
is too wide.

That also explains why the WEIGHTS measured as the strongest lever (M-74): a
lever is powerful in proportion to how far apart its ends sit. Moving weights
could never distinguish «type matters» from «the priors exaggerate type»; this
knob separates them by collapsing the bases toward the MEDIUM one without
touching a single weight.

At 1.0 nothing changes; at 0.0 all three share the medium curve and
carbohydrate type stops existing.

### 2.6 `carbTimeScale` — how fast the user's carbohydrate appears

Relative to the population triangles.

The three bases — fast 5/25/75, medium 10/55/180, slow 15/95/330 — are a
POPULATION prior and have never been measured on the user, unlike the insulin
landmarks, which are read from the user's own doses segment by segment. The
shortfall hunt (A-45) ran out of other suspects: the amplitude is whole, the
caloric queue is flat across a factor of ten, carb TYPE moves the two-hour
delivery by one point, and the fat/protein prior turned out to trade fatty
episodes against lean ones rather than fix either (A-46). What is left is the
base triangles themselves being slower than the user's gut.

This scales their TIME AXIS — delay, peak and end together, all three bases
equally — so 0.8 means «the user's carbohydrate arrives a fifth sooner than the
textbook». It deliberately does NOT touch amplitude: how much arrives is
`grams x CS` and that part measures correct.

Scaling all three by one number rather than fitting nine is the point: one
parameter, measurable on a corpus this small, and it cannot quietly become a
per-dish fudge.

### 2.7 `foodAmpScale` — how much glucose a meal delivers

Scales `grams x CS x this`.

Added because the walk-forward stand could not ask the question it was
measuring. Its fit had ten axes and NONE of them touched food amplitude, so the
only lever that could lift a too-flat line was ISF — and with ISF held in its
measured corridor the search pinned to the corridor FLOOR on every window. That
reads as «the loss wants weak insulin»; it actually means «the loss was denied
the knob it needed».

Deliberately a SCALAR on the existing amplitude, not a replacement for
`carbSens`: it has been measured three times on real data (F-03 opened, closed
on the phone corpus, re-measured; A-18 killed the size gradient), and the only
surviving deviation is +25% on large meals via the tail. A scalar that lands
near 1.0 confirms that record; one that lands at the ceiling says the deficit is
not amplitude at all.

**Rescue is exempt.** The corpus rule forbids learning amplitude on dextrose —
at hypo the liver contributes and the grams are not the whole story — so a
rescue inside a scored window must not be scaled by a knob fitted on meals.

### 2.8 `foodTailScale` — how long food keeps arriving

Independent of when it starts and peaks.

Added because the fit was paying for its absence with the INSULIN tail.
Measured on the corpus: the model under-calls the excursion by a flat 1.86-1.94
mmol at every phase/tail pair tried, and the deficit sits in the 120+ band. The
two knobs that could have fixed it did not: `foodAmp` has the largest leverage
of any axis (4.68) and the fit leaves it at 1.04; `carbTimeScale` sits at 0.99.
Neither is used because both have the WRONG SHAPE — one lifts the whole curve,
the other slides the whole curve, and the early band is already the best of the
three (0.539).

So the search took the nearest thing it did have: a longer insulin tail, which
removes less glucose late and props the line up. That is why the fitted tail
lands at 156 against an observed end of action of 85-100, and why the shape
domain (`tail > peak + phase + 15`) then makes the user's own reading literally
unreachable.

This scales the END of each base triangle only — delay and peak untouched — so
it can lengthen the arrival without moving its start. If the fit takes it and
releases the insulin tail toward the observed 90-100, the compensation is
confirmed and the debt is paid in the right place.

### 2.9 `carbTriangles`, `fastTimeScale`, `mediumTimeScale`, `slowTimeScale`, `macroShareScale`

The three base triangles, separately — the last unexplored surface.

`carbTimeScale` moves all three together, which is the right knob when the
question is «does the user's carbohydrate arrive sooner than the textbook». It
cannot answer «is the FAST basis right while the SLOW one is not», and that
question has never been asked: the nine numbers behind fast(5/25/75),
medium(10/55/180) and slow(15/95/330) have never moved except uniformly.

One scale per basis rather than nine free numbers: three parameters are
estimable on this corpus, nine are not, and a per-basis time dilation is the
same physical statement as `carbTimeScale` made three times.

`macroShareScale` touches the other untouched term — how much of the macro delay
each basis receives (.45 fast, .75 medium, 1.0 slow).

### 2.10 `formStretchScale`, `formDelayScale`, `fiberScale`, `peakGastricShare`

Sweep-only handles on terms that never had one.

An inventory of the physio food path found six mechanisms with no knob at all,
so none of them had ever been swept and none could appear in any hypothesis
verdict. Three of the four `MacroTimingParamsV1` fields turned out to be
unreachable for parsed food in the same audit — measured, not argued: they
returned the base score to three decimals at every value including +30 min per
10 g. A term that cannot be moved cannot be evidence, and a term nobody moved is
not evidence either.

These four exist to find out which of the six can move the SHAPE at all. Every
default is the neutral value, so nothing ships changed; a knob that earns a
place becomes a real parameter afterwards, and the rest stay constants with a
measurement behind them instead of a guess.

`formStretchScale` scales the DEVIATION from 1.0, so 0 collapses the
physical-form stretch to «all foods stretch alike» and 2 doubles the spread
between a liquid and a solid. The others are plain multipliers.

### 2.11 `steppedBackgroundFeedback` — an A/B switch for the background fix

So the change can be measured against itself rather than asserted.

`true` (default) steps the reversion with the trajectory. `false` reproduces the
behaviour shipped before this fix — the anchor level held constant for the whole
horizon — and exists ONLY so a stand can run both arms on the same episodes. It
is not a setting and must not become one: the old branch is a defect, kept alive
exactly as long as it takes to publish the difference it makes.

### 2.12 The horizon trust ramp

`backboneWeight` multiplies each five-minute increment of the statistical
backbone. It is a fitted trust ramp, not physiology, and audit finding M5 is
open against it: shrinking the mean rewards a miscalibrated model by hiding
amplitude errors, and because insulin is drawn in full the ramp today cuts food,
background and drift only. Read M5 in [`audit.md`](audit.md) before touching it;
the fitter reaches it as the `ramp` axis (section 4.3).

---

## 3. Reading the insulin shape from segments

`SegmentLandmarksV1` and `SegmentLandmarkReaderV1` read the insulin profile from
SEGMENTS, not from whole clean episodes.

The user's proposal: "we can be smarter than searching for a whole clean
injection, and assemble the profile from segments instead. Here the onset is
visible — take it. Here the peak is visible — take it. Seeing the onset does not
even need an isolated episode: it is readable even with food on board."

The measurement bears this out. Requiring ninety clean minutes to establish a
quantity that lives in the first thirty throws away most of the evidence: on one
device only 14 of 21 trusted doses own such a window, and of those 14 the TAIL —
the one landmark that genuinely needs the long window — was censored on 2
anyway, while the onset was readable on all of them. One admission rule for four
quantities with different information horizons is one rule too few.

So each landmark is admitted on ITS OWN horizon, and the profile is assembled
from per-landmark medians. Two consequences worth stating plainly:

- **n now differs per landmark, and that is the honest result.** The early
  profile becomes well-determined while the tail stays openly thin — which is
  exactly what CGM can and cannot see (M-01: the residual 5–15% of action
  stretched over hours is below the instrument's resolution).
- **The assembled profile is no single dose's profile.** Ordering is therefore
  checked after assembly and reported, never silently repaired.

On reading the onset THROUGH food, which is the sharpest part of this idea: the
baseline here is the pre-dose inertia, so the break is measured against the rise
that was already happening. That is what makes it legible with food on board.
But it also means the onset found this way is «where insulin overcame the
ongoing rise», which is LATER than where insulin began to act — the same two
quantities M-02 separates. Doses with a fast pre-dose rise therefore carry a
reduced weight rather than being excluded, and the two populations stay
distinguishable in `LandmarkSampleV1.confoundedByFood`.

### 3.1 Is food acting anywhere in this dose's window?

`LandmarkSampleV1.confoundedByFood` was **corrected**. It used to be "was the
line already climbing when the dose landed", read off the pre-dose slope alone —
and that misses the commonest case entirely. A bolus given BEFORE the meal sits
on a flat line, so the backward-looking test called it quiet, while the food
arrived immediately after and pushed every landmark later.

Measured directly: split by pre-dose slope, the onset moved +0 min; split by
whether food was logged within an hour, the SAME onset moved +10, the visible
fall +12 and the peak +8. The confound was there all along; the flag was looking
the wrong way down the time axis.

### 3.2 The five landmarks, and why two of them are not interchangeable

**`onset` — action begins:** the trajectory departs from its pre-dose inertia.
With a climbing background this happens while glucose is still rising — the rise
merely stops being a rise. It is the quantity the model's action curve starts
from, because it is a property of the insulin.

**`visibleFall` — the visible fall begins:** the line actually turns down.
Decided with the user — carry both, do not choose. Measured directly the two
differ by about ten minutes (14 vs ~25), and the gap is not error: it is the
interval in which insulin is cancelling a rise that has not yet reversed. It is
what the user reads off their own screen, so the app must be able to state it in
those terms — but it is a property of insulin AND background together, so it
must never be what the action curve is built on. See M-02.

**`peakRate`, `slowdown`, `tailEnd`** complete the shape. The curve contract
(`AssembledProfileV1.landmarks`) deliberately excludes `visibleFall`: building
the action CDF on «when the line turned down» would bake the user's typical
background into the insulin — on a quiet night the same insulin turns the line
down sooner, and the curve would then claim the insulin itself was faster. The
visible fall is reported beside the curve, never inside it.

### 3.3 `tailCensoredAtMin` — the dose whose insulin outlasted its window

A right-censored tail.

M-22, measured directly: only 54 of 249 doses yield a tail at all, and 43 more
reach the slowdown and are then dropped for never returning to the pre-dose rate
inside the window. Dropping them is not neutral — those are precisely the LONG
tails, so the surviving median is taken over the doses that happened to finish
in time and reads short by construction. Widening the window moved the pooled
median 113 -> 141, and the direct horizon reading (M-53) puts the true end at
240-300.

Reporting the window end AS the tail fabricates a landmark, so `tailEnd` stays
null in that case. What is added is the LOWER BOUND: this dose's tail is at
least this long. The aggregate then estimates the median with censoring rather
than ignoring it.

This is the instrument side of audit finding M1: a 240-minute ruler cannot
measure five hours.

### 3.4 `tailPlateauMin` — the end of action without a background reference

«The rate stopped recovering» rather than «the rate came back to the pre-dose
slope».

The second defect of the estimator, M-54. `tailEnd` declares the end where the
observed rate returns to `breakRate = baseSlope − floor`, the slope the line had
BEFORE the dose. That quantity belongs to insulin AND background together: if
the background climbs while insulin still works — dawn, a late meal, the liver —
the observed rate meets the old slope early and the dose is declared finished
while it is still removing glucose. The user's own observation named it first:
the end is the PLATEAU.

A plateau is background-free. A rising background lifts the whole rate curve but
does not make it FLAT, so «the rate stopped changing» survives exactly the
confound that «the rate crossed a threshold» does not.

Computed alongside and reported, never substituted: the shipped landmark keeps
its old meaning until this one is measured against it. Substituting it is
roadmap phase O-D.

### 3.5 `refusal` — why a dose contributed nothing

Never a silent empty result.

The user's question: "what if this is an ideal diabetic who perfectly
compensates for food? The line barely moves — then we would never compute any
timing at all." That is right, and the reader does refuse such a dose (a line
that never breaks has no landmarks to read). The danger is that it refused in
SILENCE, so a corpus could be dominated by the doses where compensation FAILED
without anyone being able to see it. A refusal that names itself is a refusal
that can be counted.

### 3.6 Conditioning: one arm for the whole profile

`ConditionedLandmarkV1` exists because a landmark turned out to depend on
whether the line was already climbing.

Measured directly over 210 doses: the break is identical either way (21 vs 21
min) and so is the slowdown (68 vs 68), but the PEAK moves +9 min and the
visible fall +4 when food is on board. Insulin has to cancel the rise before it
can produce its steepest fall, so a pooled median for those two is not a
property of the insulin — it is a property of how many meal boluses happen to be
in the sample.

`flat` is therefore what the action curve is built on, and `rising` is what the
user will actually see when correcting on top of a meal.

**The arm is decided once for the whole profile.** It used to be decided per
landmark, and that produced two failures that only a replay over growing history
exposed: at one point in the corpus's growth the onset fell back to the pooled
arm (7 samples, one short) while the visible fall had already switched to the
food-free one, so the profile compared 21 min against 14 and refused itself for
being out of order. And later the onset crossed the threshold and JUMPED from 21
to 11 — which reads as the model changing its mind about the body rather than as
one arm replacing another.

Below `MIN_CONDITIONED_SAMPLES` the conditioned arm is thinner than the pooled
one it would replace, and conditioning costs more than the confound.

`AssembledProfileV1` therefore conditions the onset too, since the corrected
flag showed it is not immune after all: split by pre-dose slope the onset moved
+0, split by food actually acting it moves +10. The first split simply could not
see a pre-meal bolus. The visible fall is background-dependent BY DEFINITION —
it is the interaction; the peak rate is background-dependent by measurement (+9
min), not by definition.

### 3.7 `cancellingRiseMin` — paired, not a difference of medians

How long insulin spends cancelling a rise before the line turns down: the median
of per-dose differences.

A difference of two medians is not this quantity. The two landmarks are pooled
over different (and differently sized) sets of doses, so their medians can
differ by five minutes while every dose that reports both shows zero. Measured
directly, that is exactly what happens: medians 14 and 18, paired median 0 — the
gap is not a property of the insulin, it is a property of the doses given while
food was still climbing.

---

## 4. The fitter: one search, two callers

`PhysioAutoFitV1` is **one fitter, used by both the laptop bench and the phone.**

The laptop bench grew a coordinate descent over these knobs, and the phone needs
the same thing so fitting can happen on the device without pulling a database.
Writing it twice is not an option: two fitters over the same knobs WILL diverge,
and the divergence will not announce itself — it will show up as «the phone
suggests a different peak than the bench did» with no way to tell which one is
the model. That is discipline #7 arriving before the mistake instead of after
it, for once.

So the search, the loss and the tuning all live in one file, and both callers are
thin: the bench supplies episodes read from a pulled DB, the phone supplies
episodes read from its own, and neither owns any of the mathematics.

### 4.1 What it fits

Each axis is a knob the week's measurements made contestable.

| knob | why it is here |
|---|---|
| `isf` | the amplitude; measured 2.41-2.50 from tagged corrections |
| `onsetMin` `fullSpeedMin` `phaseMin` `tailMin` | the insulin shape, read directly off the device |
| `emptyingKcalPerHour` `carbSieving` | the gastric queue |
| `carbSpread` | how far apart fast/medium/slow stand |
| `trustRamp` | `backboneWeight`; 1.00 ships, and it withholds half the physiology at h=60 (M-83) |

The settled amplitude is `MEASURED_ISF_MMOL_PER_UNIT = 2.50`: seven tagged
corrections, median 2.50 — «timing from every dose, amplitude from
corrections». It lives in the fitter so the bench and the phone bound ISF by the
same number.

### 4.2 Three metrics, reported separately and never collapsed

They fail differently, and a single number hides which failure happened.

- **`SHAPE`** — residual spread with its own mean removed. «When», not «how
  much». Blind to a constant offset.
- **`BIAS`** — that mean. Blind to everything else.
- **`BALANCE`** — their sum, which is what a person usually means by «closest»,
  offered as a third choice rather than as the only one.

**A known confound, stated because the caller must show it:** with food still
under-delivered, BIAS pulls ISF DOWN to correct a mean the food should have
carried (M-86 — shape wanted 2.0-2.2 on the same episode where bias wanted
1.74). An ISF read off the bias or balance arm is partly a food deficit wearing
an insulin label. This is audit finding M3.

`Batch` reports per-episode fits plus the median of each knob across them. The
MEDIAN is the answer, and the per-episode list is the evidence — a median with no
spread beside it hides whether the episodes agreed, and they do not.

### 4.3 The axes, one at a time

`Knobs` is a tuning point. Nulls are not allowed there — the fitter always works
with concrete numbers; it is the SETTINGS layer that distinguishes «not set»
from «set to the shipped value».

**`tailShare` — share of the dose arriving after the active phase.** An axis
that used to be missing. Without it, the hypothesis "insulin acts longer" was
untestable, and that only became clear once someone tried to test it. The
`tailMin` knob does not change the strength of the late action, only its SPREAD:
the share is fixed at `InsulinShapeV1.TAIL_SHARE = 0.20`, so stretching the tail
spreads that same 20% wider and makes the late action WEAKER. Measured:
stretching 130 -> 300 moves the bias −0.15 -> +0.14, in the direction opposite
to what was wanted.

Two external reviews recommended "stretch the tail to 240-300 so the model sees
late drops". The correct axis for that is this one: it moves dose mass into the
later hours without touching the area under the curve.

The claim that this axis "does NOT substitute for ISF" turned out to be WRONG.
The area under the CDF is invariant by construction: `synthesizePlateau`
normalizes on its own integral and pins the last node to 1.0 at `tailMin`, so the
full dose is delivered by minute 129 regardless of the share. Yet the bias at
minute 180 still moves by 1.07 mmol (−1.95 at a share of 0.10 vs −0.88 at 0.50,
bench `isfshape`) — a magnitude quite comparable to an ISF step.

So the engine is sensitive to the PATH, not only the total, and a suspect is
named: `HybridForecastEngine.backgroundDelta` contains `- stateReversion *
(current - targetGlucose)`, where `current` is the running predicted line
(`steppedBackgroundFeedback`, section 2.11). A different trajectory for the same
delivered dose gives a different accumulated pullback. **The mechanism is not
confirmed** — the sign this reasoning predicts does not match what was observed,
and reasoning from code instead of measuring already cost real time (discipline
#1). The MAGNITUDE is measured; reading it as "a share free of ISF" is wrong, and
both axes need to be fitted with that in mind.

**`fastShift` — moves mass between the carb bases.** `+0.2` makes every dish a
fifth more «fast», with the remainder re-shared between medium and slow in their
old proportion. Distinct from `carbSpread`, which moves the BASES apart rather
than the weights between them — one asks «is this dish fast», the other «how
different is fast from slow at all».

**`foodAmp` — scalar on `grams x CS`. The axis this fitter was missing.** Until
it was added, nothing here could change how much glucose a meal delivers —
`kcal`, `sieve`, `spread` and `fastShift` all move WHEN it arrives. So on an
episode where the drawn line was flat and reality arced up, the only reachable
lever was ISF, and the walk-forward duly pinned ISF to the floor of its measured
corridor on every window. That was read as «the loss wants weak insulin». It was
the loss reaching for the nearest knob to the one it needed.

**`activityDirect` — how much glucose activity removes.** An axis that was
missing despite the mechanism already existing. `backgroundDelta` computes
`-activityDirect * exposure * 5/30` per step, removal proportional to exposure.
The coefficient was zeroed in `personModelAt`, and two external reviews called
this out as a silent gap: exposure is recorded, the median line never uses it.

Measured on a corpus of episodes with activity, controlling for dose and carbs:
the bias at minute 180 grows with exposure by **+5.63 mmol per unit** (R² 0.374)
— the model overshoots reality more the more activity there was. Over the
typical exposure range this is on the order of +1 to +2 mmol.

The range ceiling was chosen FROM THE MEASUREMENT, not from a prior number in
the artifact. That number had been «fitted as part of a different
factorization» and did not pass the versioned gates; reviving it would mean
fitting to an answer nobody had checked.

**`carbTimeScale`** — time axis of the carb triangles; >1 makes food arrive
LATER. Reality peaks at ~125 min while insulin peaks at ~51, and a model whose
food peaks near its insulin cancels itself — which no amplitude can fix, because
raising both sides of a subtraction changes nothing.

**`foodTailScale`** — how long food keeps arriving, with its onset and peak
held. The axis the search was missing: see section 2.8.

**The twelve triangle axes.** The nine triangle landmarks and the three macro
shares used to be literals inside the engine, so the only handles on
carbohydrate SHAPE were `carbSpread` (how far fast and slow stand from medium)
and `carbTimeScale` (stretch all three at once). Both are functions OF these
numbers, so the fitter could scale a wrong shape but never change it — and
«wrong shape» is exactly what M-78/79 measured: beer, ice cream and a smoothie
drawn with one peak.

They make `carbSpread` and `carbTimeScale` REDUNDANT, exactly. Spread is
fast/slow relative to medium; time-scale is a common multiplier. Both are
reachable by moving these nine, so freeing all of them at once gives the search
two extra directions along which the loss is flat — the classic unidentifiable
fit, and the same failure the insulin/food ordering rule exists to prevent.
`fitOne` therefore locks spread and time-scale whenever any triangle axis is
free, and says so.

Their defaults are taken from the shipped triangles, not duplicated. Twelve
literals used to live in the fitter — 5/25/75, 10/55/180, 15/95/330 and macro
shares 0.45/0.75/1.00 — and those were values from before two later tuning
passes. The copy drifted from the original, and any bench that did not set the
triangles explicitly was fitting a model the device does not run (discipline #7).
`CarbTrianglesV1.SHIPPED` is now the single source.

**The macro shares** say how strongly each carb type yields to the gastric terms
— fat, protein and fibre. All three are 0.48 since M-120 — equalising them
scored -0.004 with 12 days better against 5 — and the per-type field stays so
they can diverge again without a migration. The wording said «Shipped 0.45 /
0.75 / 1.00» at one point, which was the duplicate's value, not the shipped one.

They are fittable because they MOVE the curve on real meals and are not
reachable from any other axis: measured on a 40 g meal with 20 g fat, 20 g
protein and 5 g fibre, taking the medium share from 0 to 1.5 shifts
90%-delivered from 134 to 213 minutes. On a lean dish the axis does nothing at
all — the term it scales is zero — so it is self-limiting.

### 4.4 The corridor, and why it is not one size

By the user's ruling: "timing is a safety question, let auto-fit work within
these timing bounds... the rest can be tuned, but should still come out
physiologically plausible. And ISF matters most."

That is three different kinds of knob and they deserve three different freedoms,
which a flat range table did not give them:

- **insulin timing is MEASURED**, segment by segment, from real doses
  (`SegmentLandmarkReaderV1`). A fit is allowed to nudge it, never to invent a
  different pharmacology — and it tried: on the wide table the search reached
  100-120 min for full speed, which real top-up practice refutes outright
  (M-82).
- **the queue and the carb spread are PHYSIOLOGY WITH SLACK.** They may move,
  but not to values no stomach has: a sieve of 0 means no carbohydrate ever
  appears, a spread of 0 means fast and slow sugars are the same substance.
- **ISF is the VARIABLE.** It genuinely drifts with activity, site and the day,
  the app exists partly to catch that drift, and applying a computed value has
  already shown a glucose improvement. It stays wide.

`boundsAround` builds the timing band as ±20% of the measured landmark with a
five-minute floor on the half-width. `PLAUSIBLE_PHYSIOLOGY` is the table of
values no stomach has — deliberately not the widest table that still parses: a
fit that lands outside it is telling us the LOSS is wrong, not that the user's
physiology is unusual. Note that the ±20% corridor around a *measured* tail is
what audit finding M1 flags: with the instrument capped, Auto-fit will propose
pulling the tail back unless the knob is locked.

Two bounds carry their own story.

*Gastric emptying, bounded where it stops mattering — measured directly.* The
upper bound used to be 400, and above roughly 180 the shape does not move at
all: 180, 250, 300 and 400 all return the same score to four decimals. That is
not a defect. It is the queue's own invariant (section 2.2) — so once the pipe is
wide enough the triangle governs and the stomach rate becomes irrelevant. It is
NOT the 30 g/h cap in `caloricCarbRateGPerHourV1`; that lives in the legacy gram
branch and never runs on this arm, and the first version of this note said
otherwise and was wrong. Leaving the bound at 400 let the search wander across a
flat plateau and REPORT a value from it: the median of the daily optima settled
at 189, which reads like a measurement and is the middle of a shelf. Below the
release point the knob is real and strong — 60 scores 4.837 against 180's 2.949
— so the lower bound stays.

*The triangles, bounded by what a stomach and a gut can do* rather than by what
parses. Fast carbohydrate that peaks at two hours is not fast; slow carbohydrate
done in forty minutes is not slow. The bands overlap on purpose — the ORDER of
the three types is not something this fit is entitled to assume, only something
it may report.

### 4.5 The scoring window

One definition, because two callers share the search. The phone was fitting on
0..240 at 15-minute steps while the bench handed the SAME function 0..480 at 10.
Sharing the fitter and then handing it different grids is the A-33 failure
re-created one level up: the two surfaces run identical code on different
problems and neither says so.

**Six hours, and it is measured rather than chosen.** On a 22-episode corpus the
old four-hour window had not returned to baseline in 16 of them, and several
peaked at +220..+240 — at or past the edge, so the fit was judging a truncated
rise and could pay for the missing top by making the food smaller or the insulin
different. Nothing was diluted: not one episode finished early and left a flat
tail, so the window was too SHORT in one direction only. Six covers the return in
twenty of the twenty-two. The two that run to +380 and +450 stay truncated;
extending for them would drag most episodes deep into unrelated territory, and a
window that ends is honest as long as it is the same window everywhere.

### 4.6 What opens an episode

There used to be exactly one kind: a candidate opened on a FOOD RECORD,
requiring 25 g of carbs and a bolus inside the window. The consequence was
measured on the corpus: of its episodes NOT ONE started at night — all fell
between 10:00 and 21:00. So everything ever fitted on this corpus (ISF, insulin
shape, food amplitude, the gastric queue) optimized the daytime post-meal regime
and was blind to the night.

And the night carries all of this user's hypoglycemia events, the worst cases in
the corpus, and the whole complaint about insulin tail length: the nadir arrives
roughly 3-4 hours after the last bolus, beyond the modeled curve.

`Kind.INSULIN` is the second, dual kind: it opens on a DOSE, requires the absence
of food in the window, and is judged by the DROP rather than the rise. It is the
same identifiability trick used elsewhere: insulin is measured where food is not
acting.

`Episode.activityExposure` defaults to zero so every existing caller compiles
unchanged — and that default is exactly the bug it fixes, so a caller that
leaves it is declaring «this episode had no movement», not forgetting to ask.
Before the field existed the fit state omitted it, so every fit and every
walk-forward scored a body that never moved.

### 4.7 Terms with a handle but no axis

`SweepKnobs` collects terms that are swept by hand, never fitted. All six were
measured "no effect" at one point and that verdict is now void: it was taken
while `foodAmp` was fitted invisibly and while insulin timing and the ramp were
free, so any one of them could be absorbed elsewhere. The same sweep also
cleared the carb triangles, which turned out to move median shape from 2.487 to
1.293 once the corset held the rest to account. Defaults are exactly what ships.

### 4.8 Two rules the fitter cannot drop

**A fresh engine per candidate.** `clusterMassCache` is keyed on
«id:startMs:grams:kcal» and carries NO SHAPE, so reusing an engine across two
knob sets hands the second one the first one's decomposition. That is M-42, and
it has been walked into twice — once returning thirteen episodes identical to the
hundredth, which nearly shipped as a real zero.

**Neutral means absent, not «1.0 written down».** `PhysioTuningTest` pins that
the settings applier and the fitter build the SAME model — the whole reason a
knob set found on one screen draws the same curve on the other. Writing an
explicit 1.0 where the applier leaves null broke that, and the test said so.
`null` is the contract for «exactly what ships», so a fit that moved nothing must
leave the model object untouched rather than produce a look-alike that compares
unequal.

`tuned` returns null when the four landmarks do not form a valid shape. The
caller must then SKIP the candidate, never fall back to a neighbour: a silent
substitution once made a refused warp print the previous arm's numbers under the
new arm's name (M-59). The same class of bug is why `Knobs.triangles()` enforces
delay < peak < end: coordinate descent moves one axis at a time and would
otherwise propose a peak before its own onset, a shape the engine then coerces
silently, so the fit would report a number it never actually ran.

---

## 5. Serving the artifact on the device

`PhysioRuntime.artifact` is immutable for a concrete model-input revision and
causal minute. Foreground and chart code ask for it repeatedly during a single
render; rebuilding it used to repeat the promotion and checkpoint queries dozens
of times. Every component of `ArtifactCacheKey` was added after a bug, and audit
finding A4 records that the key having nine components is itself a symptom —
this is the history behind it.

**`manualIdentity`.** Hand-entered parameters are model input like any other. A
cache that ignored them would keep serving the curve the user just replaced.

**`tuningIdentity` — the settings-screen tuning, and it was missing.**
`HybridModelStore` installs `PhysioTuning.apply(model)` under the ARTIFACT'S OWN
sha, because the tuning is applied at install rather than at read. So
`modelSha256()` does not move when the user changes a knob, `manualIdentity`
tracks a different feature entirely (`ManualInsulinRuntime`), and no ledger
watermark shifts either — every component of this key stayed put while the model
underneath it changed. The cache then served the PREVIOUS artifact, so the
screen said "applied", Hybrid V11 obeyed, and PHYSIO quietly did not. A comment
in `HybridDomain` claimed the overrides «enter the artifact hash». They do not —
they ride on the model, which is a different thing, and this field is what makes
the claim true for the cache.

**`causalWatermark` — the causal watermark, not the raw cutoff:** the newest
known-at that is at or before `asOfMs` across the ledgers this artifact reads.
Keyed on the exact millisecond, the cache missed on every call, and the
closed-episode rebuild asks for the artifact once per FOOD and BOLUS event —
~457 rebuilds per pass, each running the promotion state of 27 hypotheses plus
checkpoints, global CS and maintenance. Measured: a couple of seconds per
episode window, over two minutes for a full pass.

Bucketing to the day was tried first and is WRONG: it lets a later revision
answer an earlier replay, which is precisely what
`artifactIdentityIsAsOfStateAndLateRevisionCannotRewriteEarlierRun` exists to
forbid — and that test caught it. The watermark is exact instead of approximate:
two cutoffs with no ledger write between them cannot produce different
artifacts, so sharing one is not a relaxation, it is the same answer computed
once.

**The artifact id carries the food shape's generation.** The model JSON's hash
and the learned state hash do not move when an engine constant changes, so at
one point editing a carb triangle wrote a different model into
`physio_parallel_runs` under an unchanged artifact id, and the paired A/B
averaged two food models without saying so.

### 5.1 The carbohydrate-sensitivity prior, scaled by body weight

`STAGE8_GLOBAL_CS_PRIOR` used to be one number for every body. One gram of
glucose distributes through a volume proportional to mass, so that constant was
silently assuming one — see `CarbSensitivityPriorV1` for the derivation and for
the three-tier design this is tier one of.

The BAND keeps its previous width relative to the median (0.73x..1.37x around
the point value) rather than being re-derived: nothing was measured about the
spread, and pretending otherwise would smuggle a second change in beside the
first. With no weight recorded the whole thing reproduces the old constants to
the digit — and that is bit-for-bit, not approximately. The first version
reproduced the old constant by converting it to an implied weight and back, and
floating point returned a value one ulp away from it; two integration tests that
pin the bundled artifact's exact contract caught it.

Audit finding M4 is open here: the weight field exists and the prior exists, but
nothing calls the prior with a weight yet.

---

## 6. Caches inside the engine

These are performance notes, not physiology, and they are kept in the code
because a reader of the code needs them there. Two are worth repeating for the
record.

**`featureShapesCache` was profiled, not guessed,** and the profiling took three
wrong turns first (M-114, M-115). The twin build costs 39 s on a representative
phone and 36 of those are the deconvolution corpus. A stack sampler over the
corpus build in the phone's own configuration put `physioFeatureShapes` at 20%
of self-samples: `NeighbourContributionV1` tabulates each neighbour's appearance
over eight hours in five-minute cells — 97 calls to `foodCdf` per event — and
every one rebuilt the SAME mixture from scratch. It is keyed on IDENTITY, not
equality: `HybridFoodEvent` is a wide data class carrying a component map and a
nested feature record, so hashing one costs more than recomputing its shapes, and
an early version keyed on equality measured SLOWER. Measured on the stand that
reproduces the phone, cold factory per run: 3976 ms -> 2492 ms, corpus identical
row for row.

**`foodCdfSeries` is exact, not an approximation.** The queue behind `foodCdf`
integrates minute by minute from the meal's own start, so asking it for a curve
re-walks the same prefix once per point; `NeighbourContributionV1` asks for 97
points per neighbour, turning 480 minute-steps into ~23 000 — a third of the
corpus build's self-time. The result is identical to calling `foodCdf` once per
age: the queue is a forward accumulation whose state at an instant does not
depend on whether the walk stops there.

---

## 7. Index — what was tried and rejected

| Tag | Question | Verdict |
|---|---|---|
| M-01 | can CGM see the last 5–15% of insulin action? | no — below the instrument's resolution; the tail stays openly thin |
| M-02 | is «action begins» the same as «the line turns down»? | no — both are carried, only the first builds the curve |
| M-22 | can the tail median ignore censored doses? | no — dropping them drops the long tails; a lower bound is recorded instead |
| M-42 | can one engine score two knob sets? | no — the cluster cache carries no shape; a fresh engine per candidate |
| M-53 | where does action really end? | 240-300 min by direct horizon reading |
| M-54 | is «back to the pre-dose slope» the end of action? | no — a rising background meets it early; the plateau reading is computed alongside |
| M-59 | may a refused shape fall back to a neighbour? | no — it printed the previous arm's numbers under the new arm's name |
| M-74 | which food lever is strongest? | the mixture weights — because the bases stand far apart, hence `carbSpread` |
| M-78/79 | can scaling fix a wrong carb shape? | no — beer, ice cream and a smoothie were drawn with one peak; the triangles became axes |
| M-82 | may the fit invent a 100-120 min full speed? | no — real top-up practice refutes it; the timing corridor narrowed |
| M-83 | does whole-meal «fast» move the two-hour delivery? | by about one point — small, not zero |
| M-86 | can ISF be read off the bias arm? | partly a food deficit wearing an insulin label |
| M-114/115 | where does the twin build spend its time? | the deconvolution corpus; `physioFeatureShapes` at 20% of self-samples |
| M-120 | should the three macro shares differ? | equalising them at 0.48 scored -0.004 with 12 days better against 5 |
| A-18 | is there a meal-size gradient in amplitude? | no |
| A-33 | can two surfaces share code but not the problem? | the failure mode; one `SCORING_GRID` now |
| A-45 | what is left after amplitude, queue and carb type? | the base triangles being slower than the user's gut |
| A-46 | does the fat/protein prior fix the shortfall? | no — it trades fatty episodes against lean ones |
| F-03 | is food amplitude off? | opened, closed on the phone corpus, re-measured; only +25% on large meals via the tail |

Rejected outright, and why:

- **Dropping the fat/protein gastric prior** (section 2.3) — better in aggregate,
  but it makes fatty meals peak earlier than lean ones.
- **Bucketing the causal watermark to the day** (section 5) — lets a later
  revision answer an earlier replay.
- **Duplicating the triangle literals in the fitter** (section 4.3) — the copy
  drifted from the shipped values.
- **Deciding the conditioning arm per landmark** (section 3.6) — the profile
  refused itself for being out of order, then jumped 21 -> 11.
- **Keying the gastric prior on `appearance.caloric`** (section 2.3) — sieving
  1.0 is still a caloric queue.
- **Writing an explicit 1.0 where the applier leaves null** (section 4.8) — the
  fitter and the applier then build models that compare unequal.
