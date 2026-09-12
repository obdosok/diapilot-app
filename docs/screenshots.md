# Screenshots — what to capture, and what must never be in frame

The README carries a small gallery. This file says which screens go into it, in
which language, what must be scrubbed first, and how to take the images so they
look like one set rather than ten.

Files live in `docs/screenshots/` and are referenced from
[`README.md`](../README.md) by the names in the table below. The directory is
not yet populated — the README slots are placeholders until it is.

## The rule that comes first

**A screenshot of this app is a page of medical history.** The device it is
taken on holds real glucose readings, real doses, real meal notes and possibly
an API key. Nothing in the list below is worth publishing a single real
reading, so **capture on a device seeded with the synthetic example person**, or
scrub every screen against the checklist and look at it once more at full size
before it is committed.

Must not be visible in any image:

- **Glucose values and the glucose curve from real use** — the chart, the value
  chip, the trend arrow, time in range, the day decomposition, the AGP-style
  strips. A forecast drawn over a synthetic history is fine; a forecast drawn
  over a real night is not.
- **Doses, insulin totals and the insulin curve read from real injections** —
  including the "measured from N doses" counts, which say how many real doses
  the device holds.
- **Notes in any form**: meal text, photos of real food, injection-site notes,
  illness, alcohol, sleep-debt or stress notes, voice transcripts. Context
  notes are the most personal text in the app.
- **Keys and endpoints**: the Anthropic API key field (even masked — do not
  show it focused), the companion-server URL and token, any LAN address, any
  Nightscout URL.
- **Chat content** from a real conversation, and any LLM answer that quotes real
  readings back.
- **File paths and backup names** that carry the device owner's account name,
  and the Downloads listing in a file picker.
- **Device identity**: the status-bar carrier name, Wi-Fi network name,
  notifications from other apps in the shade, the lock-screen wallpaper, and
  the account row in system dialogs.
- **Anything that pins a date to a person.** A relative clock ("2 h ago") is
  fine; a screen full of calendar dates next to medical values is not.

If a screen cannot be shown without one of these, it does not go in the README.

## Language

**English.** The app's UI language is chosen in Settings → Language; set it to
English (not System) before capturing, so notifications and the widget follow.
The gallery in the README is English-only — one screenshot per screen, no
paired translations.

Russian versions are welcome as a second set under
`docs/screenshots/ru/` if a Russian readme ever lands, captured the same way
with Settings → Language → Russian. Do not mix languages inside one gallery
row: a half-translated set reads as a bug.

## The set

| # | File | Screen | Must show | Watch out for |
|---|---|---|---|---|
| 1 | `today.png` | Today | the current value, the trend, the three-hour forecast line with its band, insulin on board | the whole point of the app; also the easiest screen to leak a real night from |
| 2 | `chart-whatif.png` | Today, What-if open | a hypothetical dose or meal moving the forecast | the What-if input row must show a round number, not a real dose |
| 3 | `hypo-alert.png` | the predictive low alert | the notification with its carbohydrate suggestion and the minutes-ahead figure | capture from the shade with no other app's notification in frame |
| 4 | `label-meal.png` | the meal-labelling notification or the composer | how little a meal costs to log: the reply field, the frequent-dish chips | use synthetic dish names; no photos of real meals |
| 5 | `history.png` | History | the day list with meal and dose markers | collapse or scrub the note text |
| 6 | `analysis.png` | Analysis | the insulin action curve as a measured fact, and time in range | the "measured from N doses" line; prefer the example-person device |
| 7 | `settings-model.png` | More → the model section | manual ISF, the insulin timings, the calibration status, Auto-fit's proposals | this is the honest screen about what is measured and what is entered |
| 8 | `widget-lockscreen.png` | home screen and lock screen | the widget and the glucose chip | wallpaper, other app icons, carrier name |
| 9 | `chat.png` | Chat | one quick prompt and a short answer | the API-key row must be off screen; ask something that does not quote readings |
| 10 | `watch.png` | a Zepp OS watch face reading the local endpoint | the value and the forecast hint on the wrist | optional — a photo, not a screencap; keep the wrist out of frame |

One to four is the core set; the README gallery leads with them. Everything
after that is optional and can land later.

## How to capture

`adb exec-out screencap` writes a PNG on stdout, which keeps the file off the
phone's own storage:

```bash
adb devices                       # one device attached, authorised
adb exec-out screencap -p > docs/screenshots/today.png
```

On Windows use `git bash` or another POSIX shell for that redirect; PowerShell
mangles the bytes unless the stream is written with
`-Encoding Byte` / `Set-Content -AsByteStream`.

For the notification screens, pull the shade down first:

```bash
adb shell cmd statusbar expand-notifications
adb exec-out screencap -p > docs/screenshots/hypo-alert.png
adb shell cmd statusbar collapse
```

A short screen recording, when a still cannot show an interaction:

```bash
adb shell screenrecord --time-limit 12 /sdcard/dp.mp4
adb pull /sdcard/dp.mp4 && adb shell rm /sdcard/dp.mp4
```

Keep the whole set consistent:

- **one device, one session, one theme** (dark, unless a screen reads badly in
  it) — a gallery mixing light and dark looks accidental;
- **one display size.** Portrait, no split screen, no font-scale change. If the
  emulator is used, a Pixel-class profile at its native density;
- **status bar cleaned up** before the first capture:
  `adb shell settings put global sysui_demo_allowed 1` then
  `adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1030`
  and `-e command notifications -e visible false`, plus `-e command battery -e
  level 100 -e plugged false`. Reset with
  `adb shell am broadcast -a com.android.systemui.demo -e command exit`;
- **no cropping to different aspect ratios.** Scale, do not crop, so the
  gallery rows line up.

## Before committing

1. Open every file at full size and read every string in it, including the
   status bar and any partially visible card.
2. Re-check against the "must not be visible" list above.
3. Confirm the images are PNG, under about 300 KB each after `oxipng`/`pngcrush`
   or an equivalent, and that no file name carries a personal detail.
4. Remember that a committed screenshot cannot be recalled: the snapshot
   history is public. A rewrite is the only remedy, and it is not one.
