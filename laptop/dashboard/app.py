"""FriendOrFoe laptop dashboard (FastAPI, no CDN, no external assets — the demo runs with the network blocked).

    python -m uvicorn app:app --host 0.0.0.0 --port 8081          (run from laptop/dashboard)
    python laptop/dashboard/app.py                                (same, from anywhere)

Routes:
    GET  /dashboard   the judges' page: counts, the latest events, polls GET /api/state every second (inline JS)
    GET  /api/state   {"counts": {...}, "events": [...newest first...], "updatedAt": ...}
    POST /api/events  one event from the phone: {"t", "class", "box", "iffStatus", "droneId", "reason"}
                      (a JSON list of such objects is accepted too). In-memory, bounded.
    GET  /health      {"ok": true}

Port 8081 because 8080 is taken on the integration lead's laptop. Owner: stream 3 (Jongho) with stream 1 for
the phone side (app/.../net/EventClient). TODO(stream 3): latest frame thumbnail with rings, nicer layout.
"""

from __future__ import annotations

import threading
import time
from collections import deque
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse

app = FastAPI(title="FriendOrFoe dashboard")

MAX_EVENTS = 500
_lock = threading.Lock()
_events: deque[dict[str, Any]] = deque(maxlen=MAX_EVENTS)
_counts = {"drones": 0, "people": 0, "friend": 0, "foe": 0, "unknown": 0, "events": 0}
_updated_at = 0.0


def _ingest(event: dict[str, Any]) -> None:
    cls = str(event.get("class", "")).lower()
    status = str(event.get("iffStatus", event.get("verdict", ""))).upper()
    with _lock:
        _counts["events"] += 1
        if cls == "drone":
            _counts["drones"] += 1
        elif cls == "person":
            _counts["people"] += 1
        if status == "FRIEND":
            _counts["friend"] += 1
        elif status == "FOE":
            _counts["foe"] += 1
        elif status == "UNKNOWN":
            _counts["unknown"] += 1
        event = dict(event)
        event.setdefault("t", int(time.time() * 1000))
        event["receivedAt"] = time.time()
        _events.appendleft(event)
        global _updated_at
        _updated_at = time.time()


@app.get("/health")
def health() -> dict[str, Any]:
    return {"ok": True}


@app.post("/api/events")
async def post_events(request: Request) -> JSONResponse:
    body = await request.json()
    items = body if isinstance(body, list) else [body]
    for item in items:
        if isinstance(item, dict):
            _ingest(item)
    return JSONResponse({"ok": True, "accepted": len(items)})


@app.get("/api/state")
def get_state() -> dict[str, Any]:
    with _lock:
        return {"counts": dict(_counts), "events": list(_events)[:50], "updatedAt": _updated_at}


@app.post("/api/reset")
def reset() -> dict[str, Any]:
    with _lock:
        _events.clear()
        for k in _counts:
            _counts[k] = 0
    return {"ok": True}


DASHBOARD_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>FriendOrFoe — live</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root { --bg:#0b0f14; --panel:#141b24; --fg:#e8eef5; --muted:#8a98a8; --amber:#ffb300; --green:#2ecc40; --red:#ff3b30; }
  body { margin:0; background:var(--bg); color:var(--fg); font:16px/1.4 system-ui, Segoe UI, Roboto, sans-serif; }
  header { padding:16px 24px; border-bottom:1px solid #22303f; display:flex; align-items:baseline; gap:16px; }
  header h1 { margin:0; font-size:22px; letter-spacing:.04em; }
  header .muted { color:var(--muted); font-size:14px; }
  main { padding:24px; display:grid; grid-template-columns: 1fr 2fr; gap:24px; }
  .tiles { display:grid; grid-template-columns:repeat(2, 1fr); gap:12px; }
  .tile { background:var(--panel); border-radius:12px; padding:16px; }
  .tile .n { font-size:40px; font-weight:700; line-height:1; }
  .tile .l { color:var(--muted); margin-top:6px; text-transform:uppercase; font-size:12px; letter-spacing:.08em; }
  .friend .n { color:var(--green); } .foe .n { color:var(--red); } .unknown .n { color:var(--red); } .drones .n { color:var(--amber); }
  table { width:100%; border-collapse:collapse; background:var(--panel); border-radius:12px; overflow:hidden; }
  th, td { text-align:left; padding:8px 12px; border-bottom:1px solid #22303f; font-size:14px; }
  th { color:var(--muted); font-weight:500; text-transform:uppercase; font-size:11px; letter-spacing:.08em; }
  .v-FRIEND { color:var(--green); font-weight:700; } .v-FOE, .v-UNKNOWN { color:var(--red); font-weight:700; }
  .v-UNCHALLENGED, .v- { color:var(--amber); }
  @media (max-width: 900px) { main { grid-template-columns:1fr; } }
</style>
</head>
<body>
<header><h1>FriendOrFoe</h1><span class="muted">observer phone → laptop · offline LAN · <span id="age">waiting…</span></span></header>
<main>
  <section class="tiles">
    <div class="tile drones"><div class="n" id="c-drones">0</div><div class="l">drone detections</div></div>
    <div class="tile"><div class="n" id="c-people">0</div><div class="l">person detections</div></div>
    <div class="tile friend"><div class="n" id="c-friend">0</div><div class="l">friend</div></div>
    <div class="tile foe"><div class="n" id="c-foe">0</div><div class="l">foe</div></div>
    <div class="tile unknown"><div class="n" id="c-unknown">0</div><div class="l">unknown</div></div>
    <div class="tile"><div class="n" id="c-events">0</div><div class="l">events</div></div>
  </section>
  <section>
    <table>
      <thead><tr><th>time</th><th>class</th><th>verdict</th><th>drone</th><th>reason</th><th>box</th></tr></thead>
      <tbody id="rows"></tbody>
    </table>
  </section>
</main>
<script>
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c])); }
  function fmtTime(t) { if (!t) return ""; const d = new Date(t > 1e12 ? t : t * 1000); return d.toLocaleTimeString(); }
  async function poll() {
    try {
      const r = await fetch("/api/state", {cache: "no-store"});
      const s = await r.json();
      for (const k of ["drones","people","friend","foe","unknown","events"]) {
        const el = document.getElementById("c-" + k); if (el) el.textContent = s.counts[k] ?? 0;
      }
      const rows = s.events.map(e => {
        const v = (e.iffStatus || e.verdict || "").toUpperCase();
        const box = Array.isArray(e.box) ? e.box.map(x => Math.round(x)).join(",") : (e.box ? JSON.stringify(e.box) : "");
        return `<tr><td>${esc(fmtTime(e.t))}</td><td>${esc(e.class)}</td><td class="v-${esc(v)}">${esc(v || "?")}</td><td>${esc(e.droneId)}</td><td>${esc(e.reason)}</td><td>${esc(box)}</td></tr>`;
      }).join("");
      document.getElementById("rows").innerHTML = rows || '<tr><td colspan="6" style="color:#8a98a8">no events yet — the phone posts to /api/events</td></tr>';
      const age = s.updatedAt ? Math.round(Date.now()/1000 - s.updatedAt) : null;
      document.getElementById("age").textContent = age == null ? "no events yet" : ("last event " + age + " s ago");
    } catch (e) {
      document.getElementById("age").textContent = "dashboard unreachable";
    }
  }
  poll(); setInterval(poll, 1000);
</script>
</body>
</html>
"""


@app.get("/dashboard", response_class=HTMLResponse)
def dashboard() -> str:
    return DASHBOARD_HTML


@app.get("/", response_class=HTMLResponse)
def root() -> str:
    return DASHBOARD_HTML


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8081)
