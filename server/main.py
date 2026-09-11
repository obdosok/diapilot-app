"""
DiaPilot Companion — a tiny personal server: live dashboard + backup target.

The phone pushes a status snapshot every minute or two and the full SQLite
backup once a day; this serves a single always-fresh page. Personal use,
single user, token auth. No database — the state is one JSON file and a
directory of backups. Deployment: any box with Python — VPS, home server
behind a Cloudflare Tunnel / Tailscale, or a desktop on the LAN.

Run:
    pip install fastapi uvicorn python-multipart
    COMPANION_TOKEN=<long-random-string> uvicorn main:app --host 0.0.0.0 --port 8787

Privacy: this is MEDICAL data. Keep the token long, prefer HTTPS (tunnel or
reverse proxy), and host only on machines you control.
"""
from __future__ import annotations

import hmac
import json
import os
import time
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse

TOKEN = os.environ.get("COMPANION_TOKEN", "")
DATA = Path(os.environ.get("COMPANION_DATA", "./data"))
DATA.mkdir(parents=True, exist_ok=True)
(DATA / "backups").mkdir(exist_ok=True)
LATEST = DATA / "latest.json"
KEEP_BACKUPS = 14

app = FastAPI(title="DiaPilot Companion", docs_url=None, redoc_url=None)


def _auth(authorization: str | None) -> None:
    if not TOKEN:
        raise HTTPException(500, "server has no COMPANION_TOKEN configured")
    # Constant-time comparison: a plain != returns sooner the earlier the first
    # mismatching character, which leaks the token one character at a time.
    expected = f"Bearer {TOKEN}".encode()
    if not hmac.compare_digest((authorization or "").encode(), expected):
        raise HTTPException(401, "bad token")


@app.post("/api/push")
async def push(request: Request, authorization: str | None = Header(None)):
    _auth(authorization)
    body = await request.json()
    body["received_at_ms"] = int(time.time() * 1000)
    tmp = LATEST.with_suffix(".tmp")
    tmp.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
    tmp.replace(LATEST)
    return {"ok": True}


@app.post("/api/backup")
async def backup(file: UploadFile, authorization: str | None = Header(None)):
    _auth(authorization)
    name = time.strftime("diapilot-%Y%m%d.sqlite")
    dest = DATA / "backups" / name
    with dest.open("wb") as out:
        while chunk := await file.read(1 << 20):
            out.write(chunk)
    # Rotate: keep the newest KEEP_BACKUPS files.
    files = sorted((DATA / "backups").glob("diapilot-*.sqlite"))
    for old in files[:-KEEP_BACKUPS]:
        old.unlink()
    return {"ok": True, "stored": name, "bytes": dest.stat().st_size}


@app.get("/api/latest")
def latest(authorization: str | None = Header(None)):
    # The token travels in a header, never in the query string: a URL ends up
    # in the server's access log, in proxy logs and in browser history.
    _auth(authorization)
    if not LATEST.exists():
        return JSONResponse({"empty": True})
    return JSONResponse(json.loads(LATEST.read_text(encoding="utf-8")))


@app.get("/", response_class=HTMLResponse)
def index():
    # The token is entered once in the browser and kept in localStorage;
    # the page itself carries no data.
    return HTML_PAGE


HTML_PAGE = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>DiaPilot</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; background:#0f1115; color:#e6e9ef;
         font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }
  .wrap { max-width:720px; margin:0 auto; padding:16px; }
  .big { font-size:64px; font-weight:700; line-height:1; }
  .big small { font-size:24px; font-weight:400; color:#9aa3b2; }
  .nuance { color:#b9a7e6; }
  .row { color:#9aa3b2; margin-top:6px; }
  .age-warn { color:#ef5350; }
  #chart { width:100%; height:280px; margin-top:12px; }
  .card { background:#171a21; border-radius:12px; padding:14px; margin-top:12px; }
  input { background:#171a21; color:#e6e9ef; border:1px solid #333; border-radius:8px;
          padding:8px 10px; width:100%; box-sizing:border-box; }
  .muted { color:#6b7280; font-size:12px; margin-top:10px; }
</style></head><body><div class="wrap">
<div id="login" class="card" style="display:none">
  <p>Access token:</p>
  <input id="tok" type="password" placeholder="COMPANION_TOKEN">
  <p class="muted">Stored only in this browser.</p>
</div>
<div id="main" style="display:none">
  <div class="big"><span id="bg">—</span> <span id="arrow"></span>
    <small id="delta"></small></div>
  <div class="nuance" id="nuance"></div>
  <div class="row" id="status"></div>
  <div class="row" id="insulin"></div>
  <svg id="chart" preserveAspectRatio="none"></svg>
  <div class="card" id="events"></div>
  <div class="muted" id="age"></div>
</div>
<script>
const $ = id => document.getElementById(id);
let token = localStorage.getItem('dp_token') || '';
function askToken() {
  $('login').style.display = 'block';
  $('tok').addEventListener('change', () => {
    token = $('tok').value.trim();
    localStorage.setItem('dp_token', token);
    $('login').style.display = 'none';
    tick();
  });
}
function fmtAge(min) { return min < 60 ? min + ' min' : Math.floor(min/60) + 'h ' + (min%60) + 'm'; }
async function tick() {
  if (!token) { askToken(); return; }
  let r;
  try { r = await fetch('/api/latest', { headers: { 'Authorization': 'Bearer ' + token } }); }
  catch (e) { setTimeout(tick, 15000); return; }
  if (r.status === 401) { localStorage.removeItem('dp_token'); token=''; askToken(); return; }
  const d = await r.json();
  $('main').style.display = 'block';
  if (d.empty) { $('bg').textContent = 'no data'; setTimeout(tick, 15000); return; }
  const mgdl = !!d.mgdl, u = v => mgdl ? Math.round(v*18.0182) : (Math.round(v*10)/10).toFixed(1);
  $('bg').textContent = u(d.bg_mmol);
  $('arrow').textContent = d.arrow || '';
  $('delta').textContent = (d.delta_mmol!=null ? (d.delta_mmol>0?'+':'') + u(Math.abs(d.delta_mmol))*(d.delta_mmol<0?-1:1) : '') + (mgdl?' mg/dL':' mmol/L');
  $('nuance').textContent = d.nuance || '';
  $('status').textContent = d.status_line || '';
  $('insulin').textContent = d.insulin_line || '';
  // Event lines are note text typed on the phone: build them as text nodes,
  // never as HTML, so a note containing markup cannot run script here.
  const events = $('events');
  events.replaceChildren();
  for (const e of (d.events || [])) {
    const line = document.createElement('div');
    line.textContent = e;
    events.append(line);
  }
  if (!events.childElementCount) {
    const none = document.createElement('span');
    none.className = 'muted';
    none.textContent = 'no events';
    events.append(none);
  }
  const ageMin = Math.floor((Date.now() - d.bg_ts_ms)/60000);
  $('age').textContent = 'data: ' + fmtAge(ageMin) + ' ago · pushed from the phone at ' +
      new Date(d.received_at_ms).toLocaleTimeString();
  $('age').className = ageMin >= 10 ? 'age-warn' : 'muted';
  drawChart(d, mgdl);
  setTimeout(tick, 30000);
}
function drawChart(d, mgdl) {
  const hist = d.history || [], pred = d.forecast || [];
  if (!hist.length) return;
  const svg = $('chart'); const W = svg.clientWidth, H = 280;
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  const all = hist.concat(pred);
  const t0 = all[0].t, t1 = all[all.length-1].t;
  let lo = Math.min(...all.map(p=>p.lo!=null?p.lo:p.m), 3.5), hi = Math.max(...all.map(p=>p.hi!=null?p.hi:p.m), 12);
  const x = t => (t-t0)/(t1-t0)*W, y = v => H - (v-lo)/(hi-lo)*(H-20) - 10;
  const path = pts => pts.map((p,i)=>(i?'L':'M')+x(p.t).toFixed(1)+','+y(p.m).toFixed(1)).join(' ');
  const band = (pts,k1,k2) => pts.map((p,i)=>(i?'L':'M')+x(p.t).toFixed(1)+','+y(p[k1]).toFixed(1)).join(' ')
      + ' ' + pts.slice().reverse().map(p=>'L'+x(p.t).toFixed(1)+','+y(p[k2]).toFixed(1)).join(' ') + ' Z';
  let s = '';
  const rl = d.range_lo || 3.9, rh = d.range_hi || 10.0;
  s += `<rect x="0" y="${y(rh)}" width="${W}" height="${y(rl)-y(rh)}" fill="#81c78420"/>`;
  if (pred.length > 1) {
    s += `<path d="${band(pred,'hi','lo')}" fill="#ffffff10"/>`;
    if (pred[0].hm != null) s += `<path d="${band(pred.map(p=>({t:p.t,hi:p.hm,lo:p.lm})),'hi','lo')}" fill="#ffffff1d"/>`;
    s += `<path d="${path(pred)}" fill="none" stroke="#cfd8ff" stroke-width="2" stroke-dasharray="6 5"/>`;
  }
  s += `<path d="${path(hist)}" fill="none" stroke="#e6e9ef" stroke-width="2.5"/>`;
  const nowX = x(hist[hist.length-1].t);
  s += `<line x1="${nowX}" y1="0" x2="${nowX}" y2="${H}" stroke="#ffffff30"/>`;
  svg.innerHTML = s;
}
tick();
</script></div></body></html>
"""
