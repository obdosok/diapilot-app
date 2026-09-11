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

Then on the phone: Settings → Companion server → URL (`http://<host>:8787`)
and the same token. Dashboard: open the URL in a browser, enter the token
once.

## API

| Method | Path | What it does |
|---|---|---|
| POST | `/api/push` (Bearer) | status snapshot from the phone |
| POST | `/api/backup` (Bearer, multipart `file`) | daily database backup (rotates 14 files) |
| GET | `/api/latest?token=` | latest snapshot (feeds the page) |
| GET | `/` | dashboard |

## Deployment (this is medical data — your own machines only)

- **VPS**: a systemd unit + caddy/nginx with HTTPS in front of uvicorn.
- **Home computer**: Cloudflare Tunnel (`cloudflared tunnel --url localhost:8787`)
  or Tailscale (then it's token + tailnet, nothing exposed externally).
- **LAN testing**: run it on the PC, point the phone at `http://<PC IP>:8787`.

Keep the token long; the page stores it only in the browser's localStorage.
