# Security Policy

DiaPilot is a personal, one-person project. This document explains the threat
model the app is built against, what a reader may rely on, how to report a
security problem, and what to expect.

## The threat model

DiaPilot has almost no remote surface: no server, no account, no login. Data
leaves the device only on an explicit action, and only in the three ways listed
in the README's "Privacy by construction". The surfaces that matter are
therefore the local ones:

1. **Other apps on the same phone.** Any app holding `INTERNET` can reach a
   loopback port, or bind a loopback port another app is expected to serve;
   any app can send a broadcast that looks like a glucose reading. The app
   that spoofs a value does not need any permission the user would notice
   granting.
2. **The media a backup lands on.** A database copy in public Downloads, in a
   third-party cloud folder, or on a self-hosted companion server is readable by
   whatever else has access to that place.
3. **Untrusted input parsed on the device**: broadcasts, NFC and BLE frames
   from a sensor or a pen, imported backups and photos, and third-party API
   responses.

**The worst outcome is not a false alarm — it is a suppressed real one.** A
forged reading, an injected dose, or a rewritten stretch of history becomes the
anchor the forecast and the low alert are computed from, and a wrong anchor can
hide a hypoglycaemia the user needed to be told about. Integrity of the stored
history therefore ranks with confidentiality, not below it.

## What the app relies on

Stated as the model rather than as the implementation, so it stays true as the
code changes. Each of these is a property the code is expected to have; a way
around one is a vulnerability worth reporting.

- **Nothing writes medical data into the app without authorisation.** On the
  local HTTP surface, recording a treatment requires a POST and a token the
  user's own device issues, and so does the event journal a companion app
  reads history from. An app that has not been given that token cannot write
  a dose or read the journal.
- **One endpoint on that surface is knowingly open.** The watch-face feed
  `/info.json` answers without a token, because it is a fixed contract an
  existing watch face depends on, and with `?graph=1` it returns about three
  hours of glucose history. Any app on the phone holding `INTERNET` can read
  that much. It is recorded as an open finding (S2 in
  [`docs/audit.md`](docs/audit.md)), not as a protection.
- **Every value that enters is bounded before it is stored.** A glucose reading
  must be physiologically possible and must be timestamped inside a window
  around now; an insulin amount must be a plausible dose. This holds for every
  input path — the xDrip broadcast, the polled web service, the OOPAlgorithm2
  minute stream, a NovoPen scan, a hand-typed entry, a command parsed from
  language, and a restored backup file — and not just for the one that
  happened to be reviewed last. The web-service poll and the minute-stream
  receiver are also skipped altogether while the app that is supposed to be
  behind them is not installed.
- **One source cannot silently rewrite another's history.** Stored readings
  and stored doses are attributed, and a late arrival from a different source
  does not overwrite what was recorded. A dose the user edited by hand is not
  overwritten by any re-sync.
- **The forecast anchor is checked for plausibility by default.** The check is
  on unless the user turns it off, not the other way round.
- **A dose is never given automatically, and never suggested.** The app
  suggests carbohydrates, never insulin; a voice or typed command can only
  *record* a dose the user already took, and only after a hard validator and an
  explicit confirmation tap.
- **Secrets stay in the platform keystore.** The Anthropic API key, any
  companion-server token, the optional backup password and the Libre 2
  pairing state are encrypted with a key held by the Android keystore, bound
  to the secret's own name, and never logged.
- **Everything the app shares with the rest of the phone is off until the user
  switches it on**, and the defaults are listed in the README's setup table.

Known gaps are not hidden: [`docs/audit.md`](docs/audit.md) lists them by
number with file references, and [`CHANGELOG.md`](CHANGELOG.md) records which
release closed which. Two are worth naming here: a backup is encrypted only
when the user has set a backup password in Settings, the default is still a
plain SQLite file (S6); and the release build is signed with a real key only
once the maintainer has created one — until then the build falls back to the
debug key and says so (S8).

## Not a medical device

DiaPilot computes observations from the user's own data. It never gives
insulin dosing instructions, and it must not be used to make treatment
decisions. This applies to security issues too: a bug here is a software bug,
not a clinical one, but a vulnerability that lets data or displayed values be
tampered with could still mislead a user who is managing type 1 diabetes.
Please treat reports involving the forecast, alerts, or data integrity with
that context in mind.

## Reporting a vulnerability

Please report suspected vulnerabilities privately, using GitHub's private
vulnerability reporting for this repository:

1. Go to the **Security** tab of this repository.
2. Choose **Report a vulnerability** to open a private advisory.

This keeps the report and the discussion out of public issues and pull
requests until a fix is available. Please do not open a public issue for a
suspected vulnerability, and please do not include real patient data (glucose
readings, doses, photos, or any other personal health data) in a report —
describe the issue with synthetic values instead.

There is no email address for reports; the GitHub advisory flow above is the
only channel.

## Scope

In scope:

- Data leaving the device: the Anthropic API calls, barcode lookups against
  Open Food Facts, and pushes to a self-hosted companion server — anything
  that could send more than intended, send it somewhere unintended, or send
  it without the user's action.
- Secrets storage: how the Anthropic API key and any companion-server token
  are stored and used.
- The local loopback HTTP server (used for the watch bridge and companion
  integrations): anything reachable beyond loopback, a way past the token,
  a side effect reachable without it, or unsafe handling of requests.
- Data integrity: any way to write, replace, or delete a stored reading, dose,
  or note that the bounds above should have refused — see "The worst outcome"
  in the threat model.
- Exported Android components (activities, services, receivers, providers)
  declared in `AndroidManifest.xml`: anything another app on the same device
  could use to read, write, or trigger something it should not.
- Parsing of untrusted input: xDrip and OOPAlgorithm2 broadcasts, NFC/BLE
  frames from the Libre 2 and the NovoPen, imported files (backups, exports,
  photos), and barcode/API responses.

Out of scope:

- The absence of clinical validation, or disagreement with a forecast,
  calibration, or attribution result — that is a modelling limitation, not a
  vulnerability (see "Not a medical device" above).
- Attacks that require a rooted/jailbroken device, a device already
  compromised by other malware, or physical access with the device unlocked.
- Issues in third-party apps this integrates with (xDrip, OOPAlgorithm2,
  Health Connect) — please report those upstream.
- Denial of service against the user's own loopback server from the same
  device.

## Response expectations

This is a one-person, unpaid, spare-time project. There is no SLA. As a
rough guide:

- Acknowledgement: best effort within a couple of weeks.
- Fix or mitigation timeline: depends on severity and complexity; shared with
  you once the report is triaged.
- Credit: happy to credit reporters in the advisory, on request.

Thank you for reporting responsibly and giving time to fix an issue before
any public disclosure.
