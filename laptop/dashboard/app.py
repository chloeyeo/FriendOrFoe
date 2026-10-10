"""FriendOrFoe laptop dashboard API (FastAPI; no CDN, no external assets — the demo runs with the network blocked).

    python -m uvicorn app:app --host 0.0.0.0 --port 8081          (run from laptop/dashboard)
    python laptop/dashboard/app.py                                (same, from anywhere)

The judges' page is the Svelte app in `web/` (`npm run dev`, http://localhost:5180/dashboard), which proxies /api
here; /basic is the single-file fallback page that needs nothing but this process.

Routes:
    GET  /dashboard, /  redirect to the Svelte page (FOF_DASHBOARD_UI, default http://localhost:5180/dashboard)
    GET  /basic         the inline fallback page (counts + latest events), polls /api/state every second
    GET  /api/state     {"counts", "events" (newest first), "frame", "thumbnail", "received", "updatedAt", ...}
    POST /api/events    one event or a JSON list: {"t", "class", "box", "iffStatus", "droneId", "reason"} plus the
                        optional "text", "trackId", "score", "rssiDbm", "roundTripMs" (app/.../net/EventJson)
    POST /api/frame     {"t", "width", "height", "tracks": [{"id", "class", "box", "tag", "tone", "detail"}]}
    POST /api/thumbnail raw image/jpeg of the preview the boxes are measured in (optional)
    POST /api/reset     clear everything
    GET  /api/thumbnail.jpg
    GET  /health        {"ok": true}

Counts follow the phone's event log: drones and people are distinct track ids (an event without one counts on
its own), friend / foe / unknown count verdicts. Port 8081 because 8080 is taken on the integration lead's laptop.
Owner: stream 3 (Jongho); the phone side is app/.../net/EventClient.
"""

from __future__ import annotations

import json
import os
import threading
import time
from collections import deque
from typing import Any

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import HTMLResponse, RedirectResponse

PORT = int(os.environ.get("FOF_DASHBOARD_PORT", "8081"))
UI_URL = os.environ.get("FOF_DASHBOARD_UI", "http://localhost:5180/dashboard")
MAX_EVENTS = 500        # kept in memory; counts stay exact beyond this
STATE_EVENTS = 100      # sent per poll
MAX_THUMBNAIL = 2_000_000
VERDICTS = ("FRIEND", "FOE", "UNKNOWN")


def now_ms() -> int:
    return int(time.time() * 1000)


class Store:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.reset()

    def reset(self) -> None:
        with self._lock:
            self.events: deque[dict[str, Any]] = deque(maxlen=MAX_EVENTS)
            self.objects: dict[str, set[Any]] = {"drone": set(), "person": set()}
            self.untracked = {"drone": 0, "person": 0}
            self.verdicts = {v: 0 for v in VERDICTS}
            self.frame: dict[str, Any] | None = None
            self.thumbnail: bytes | None = None
            self.thumbnail_at: int | None = None
            self.received = 0
            self.updated_at = 0.0

    def add(self, items: list[dict[str, Any]]) -> int:
        with self._lock:
            for e in items:
                cls = str(e.get("class") or "").lower()
                if cls in self.objects:
                    if e.get("trackId") is None:
                        self.untracked[cls] += 1
                    else:
                        self.objects[cls].add(e["trackId"])
                status = str(e.get("iffStatus") or e.get("verdict") or "").upper()
                if status in self.verdicts:
                    self.verdicts[status] += 1
                self.received += 1
                event = dict(e)
                event.setdefault("t", now_ms())
                # "id" is unique until the next reset (the phone has no sequence number); the page keys on it.
                event["id"] = self.received
                event["receivedAt"] = now_ms()
                self.events.appendleft(event)
            if items:
                self.updated_at = time.time()
            return len(items)

    def set_frame(self, frame: dict[str, Any]) -> None:
        with self._lock:
            self.frame = {**frame, "receivedAt": now_ms()}

    def set_thumbnail(self, jpeg: bytes) -> None:
        with self._lock:
            self.thumbnail = jpeg
            self.thumbnail_at = now_ms()

    def get_thumbnail(self) -> bytes | None:
        with self._lock:
            return self.thumbnail

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return {
                "counts": {
                    "drones": len(self.objects["drone"]) + self.untracked["drone"],
                    "people": len(self.objects["person"]) + self.untracked["person"],
                    "friend": self.verdicts["FRIEND"],
                    "foe": self.verdicts["FOE"],
                    "unknown": self.verdicts["UNKNOWN"],
                    "events": self.received,
                },
                "events": list(self.events)[:STATE_EVENTS],
                "frame": self.frame,
                "thumbnail": None
                if self.thumbnail_at is None
                else {"receivedAt": self.thumbnail_at, "url": f"/api/thumbnail.jpg?v={self.thumbnail_at}"},
                "received": self.received,
                "updatedAt": self.updated_at,
                "serverTime": now_ms(),
            }


store = Store()
app = FastAPI(title="FriendOrFoe dashboard", docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/health")
def health() -> dict[str, Any]:
    return {"ok": True}


@app.post("/api/events")
async def post_events(request: Request) -> dict[str, Any]:
    body = await request.json()
    items = body if isinstance(body, list) else [body]
    if not all(isinstance(e, dict) for e in items):
        raise HTTPException(422, "expected one event object or a list of them")
    return {"ok": True, "accepted": store.add(items)}


@app.post("/api/frame")
async def post_frame(request: Request) -> dict[str, Any]:
    body = await request.json()
    if not isinstance(body, dict) or not isinstance(body.get("tracks"), list):
        raise HTTPException(422, "expected {t, width, height, tracks:[...]}")
    store.set_frame(body)
    return {"ok": True}


@app.post("/api/thumbnail")
async def post_thumbnail(request: Request) -> dict[str, Any]:
    jpeg = await request.body()
    if not jpeg.startswith(b"\xff\xd8") or len(jpeg) > MAX_THUMBNAIL:
        raise HTTPException(422, "expected one image/jpeg body under 2 MB")
    store.set_thumbnail(jpeg)
    return {"ok": True}


@app.post("/api/reset")
def reset() -> dict[str, Any]:
    store.reset()
    return {"ok": True}


@app.get("/api/state")
def get_state() -> Response:
    return Response(json.dumps(store.snapshot(), ensure_ascii=False), media_type="application/json",
                    headers={"Cache-Control": "no-store"})


@app.get("/api/thumbnail.jpg")
def get_thumbnail() -> Response:
    jpeg = store.get_thumbnail()
    if jpeg is None:
        raise HTTPException(404, "no thumbnail yet")
    return Response(jpeg, media_type="image/jpeg", headers={"Cache-Control": "no-store"})


@app.get("/")
@app.get("/dashboard")
def dashboard() -> RedirectResponse:
    return RedirectResponse(UI_URL)


@app.get("/basic", response_class=HTMLResponse)
def basic() -> str:
    return BASIC_HTML


BASIC_HTML = """<!doctype html>
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
    <div class="tile drones"><div class="n" id="c-drones">0</div><div class="l">drones</div></div>
    <div class="tile"><div class="n" id="c-people">0</div><div class="l">people</div></div>
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


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=PORT)
