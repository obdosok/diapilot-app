# DiaPilot Companion server

A personal mini server: a live dashboard (glucose, trend, twin forecast with
an uncertainty band, IOB, events) plus a receiver for the daily database
backup. Single user, token auth, no DB — state is `data/latest.json` and the
`data/backups/` folder.

## Running it

```bash
pip install fastapi uvicorn python-multipart
COMPANION_TOKEN=<long-random-string> uvicorn main:app --host 0.0.0.0 --port 8787
```

Windows (PowerShell):

```powershell
$env:COMPANION_TOKEN = "<token>"
uvicorn main:app --host 0.0.0.0 --port 8787
```

Then on the phone: Settings → Companion server → URL and the same token.
Dashboard: open the URL in a browser, enter the token once.

**The URL the phone uses must be `https://…`.** The app's network security
configuration (`app/src/main/res/xml/network_security_config.xml`) permits
cleartext only to loopback, so `http://<PC IP>:8787` is refused by the app
itself before a byte is sent — the server never sees the request. See
"Reaching it from the phone" below.

## API

| Method | Path | What it does |
|---|---|---|
| POST | `/api/push` (Bearer) | status snapshot from the phone |
| POST | `/api/backup` (Bearer, multipart `file`) | daily database backup (rotates 14 files) |
| GET | `/api/latest` (Bearer) | latest snapshot (feeds the page) |
| GET | `/` | dashboard |

## Reaching it from the phone (this is medical data — your own machines only)

uvicorn itself speaks plain HTTP. Something in front of it has to provide
the HTTPS the phone insists on:

- **VPS**: a systemd unit + caddy or nginx with a certificate in front of
  uvicorn (caddy obtains one from Let's Encrypt on its own). Phone URL:
  `https://your.domain`.
- **Home computer, Tailscale**: `tailscale serve --bg https / http://localhost:8787`
  publishes it inside your tailnet with a certificate Tailscale issues.
  Phone URL: `https://<machine>.<tailnet>.ts.net`. Nothing is exposed to
  the internet; token + tailnet membership are both required.
- **Home computer, Cloudflare Tunnel**: `cloudflared tunnel --url localhost:8787`
  gives an `https://…trycloudflare.com` URL (or a named tunnel on your
  domain). The tunnel terminates TLS; the token still gates every request.
- **Plain http on the LAN is not an option in the public build.** A
  maintainer of a private build can add the LAN host literally to
  `network_security_config.xml` as a `<domain-config cleartextTrafficPermitted="true">`
  entry — that is a source change and a rebuild, not a setting, and it
  sends the whole medical database over an unencrypted link on that
  network. This repository does not do it.

## Limits and lockout

- `/api/backup` refuses uploads over `COMPANION_MAX_BACKUP_BYTES` (default
  2 GiB) and `/api/push` refuses bodies over `COMPANION_MAX_PUSH_BYTES`
  (default 1 MiB) with `413`. A push whose body is not a JSON object gets
  `400`.
- An upload is written to a temporary file next to the day's backup and
  renamed over it only when complete, so a connection that drops halfway
  leaves yesterday's file — and today's, if there was one — intact.
- Ten wrong tokens from one address (`COMPANION_AUTH_FAILURES`) lock that
  address out for 60 s (`COMPANION_AUTH_LOCKOUT_S`) with `429` and a
  `Retry-After`. The counter is in memory and forgotten on restart; it
  slows a guesser down, the long random token is the actual defence.
  Behind a reverse proxy every client shares the proxy's address; set
  `COMPANION_TRUST_PROXY=1` to count by `X-Forwarded-For` — only when the
  proxy is yours and overwrites that header.
- With a backup password set on the phone, the uploaded file is an
  encrypted `DPBK1` container and is stored as `diapilot-YYYYMMDD.sqlite.enc`;
  the server never has the password and cannot open it.

Keep the token long; the page stores it only in the browser's localStorage.
