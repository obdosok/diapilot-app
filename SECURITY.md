# Security Policy

DiaPilot is a personal, one-person project. This document explains how to
report a security problem and what to expect.

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
  integrations): anything reachable beyond loopback, missing authentication,
  or unsafe handling of requests.
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
