# laptop/ — side-car tools (dashboard, gadget CLI, serve)

Nothing here is on the IFF critical path: the judges watch the phone (mirrored with scrcpy over USB). The laptop
adds the second ClikaRT device type (`clikart-cli serve --offline`), the eval backend and a live dashboard.

```
dashboard/app.py       FastAPI API on :8081 (events, frame, thumbnail, state, reset) + /basic fallback page
dashboard/feed_demo.py plays the scripted demo run into the API without a phone
../web/                the judges' dashboard page (Svelte, Vite dev server on :5180, proxies /api to :8081)
mirror.ps1, mirror.sh  scrcpy mirror of phone A over USB, optional --record for the backup video
gadget_cli.py          send the phone's JSON lines to the reader gadget over USB serial; --fake prints canned answers
```

## Python

Python 3.13 with `fastapi`, `uvicorn` already installed in the user site (run plain `python`, **not** `python -I`,
which hides user-site packages). `pyserial` is needed only for the real gadget: `python -m pip install pyserial`.

## Dashboard (API on 8081 — 8080 is taken on Chloe's laptop; page on 5180)

```
python laptop/dashboard/app.py          # or, from laptop/dashboard: python -m uvicorn app:app --host 0.0.0.0 --port 8081
cd web && npm install && npm run dev    # npm install once, while online; then http://localhost:5180/dashboard
python laptop/dashboard/feed_demo.py    # optional: the scripted demo run, no phone needed (--once, --url)
```

`http://localhost:8081/dashboard` redirects to the Svelte page; `http://localhost:8081/basic` is a single-file
fallback page (counts + latest events) for a laptop without Node. The page polls `GET /api/state` once a second
through the Vite proxy; all assets are local (system fonts, no CDN).

On the phone: Settings → Laptop dashboard → `http://<laptop-ip>:8081`. `FriendOrFoeApp` mirrors the event log
(`POST /api/events`, a JSON list of `{t, class, box, iffStatus, droneId, reason}` plus `text`, `trackId`, `score`,
`rssiDbm`, `roundTripMs`) and the drawn tracks once a second (`POST /api/frame`, `{t, width, height, tracks:[{id,
class, box, tag, tone, detail}]}`, boxes in preview pixels, the phone's own tag text). The camera path may add
`POST /api/thumbnail` (raw JPEG of the preview); the page shows it only while it is fresh. Everything is
fire-and-forget with a 500 ms timeout; a reset on the phone resets the dashboard too. Counts follow the phone's
event log: drones and people are distinct tracks, friend / foe / unknown count verdicts.
Tests: `python -m pytest laptop/dashboard` (needs `httpx` besides fastapi). Smoke test without the phone:

```
curl -X POST http://127.0.0.1:8081/api/events -H "Content-Type: application/json" ^
     -d "{\"t\":1760000000000,\"class\":\"drone\",\"box\":[380,61,580,260],\"iffStatus\":\"FRIEND\",\"droneId\":\"RQ-07\",\"reason\":null}"
```

## Gadget CLI

```
python laptop/gadget_cli.py --fake challenge            # canned FRIEND answer, no hardware
python laptop/gadget_cli.py --fake --mode REPLAY challenge
python laptop/gadget_cli.py --port COM5 status          # real gadget: {"cmd":"status"}
python laptop/gadget_cli.py --port COM5 challenge --box-id 3 --challenger-id 7
```

## `clikart-cli serve --offline` (second ClikaRT device type, eval backend)

`clikart-cli` from the ClikaRT Windows archive (`bin/` and `lib/` on PATH); the models were fetched into
`C:/Users/C/clika/models` on 2026-10-10. `--offline` and `--cache-dir` are global options and go before the model;
`--host/--port/--device` belong to `serve`:

```
clikart-cli --offline --cache-dir C:/Users/C/clika/models PekingU/rtdetr_v2_r18vd serve --device cpu --host 0.0.0.0 --port 8000
```

The line to show the judges is `serving on http://0.0.0.0:8000 (model=rt-detr)` right after `--offline`.
`POST /v1/detections` (multipart field `image`, optional `confidence`) answers
`{"detections":[{"box":{"xmin","ymin","xmax","ymax"},"score","label_id","label"}]}`; `GET /health`, `GET /props`.
`HttpDetector` on the phone (debug builds only) and `eval/run_eval.py` both talk to this. Port 8000 is the plan
here; the research notes used 8129/8130 for RT-DETR/OWLv2 — pick one and put it in the phone settings.

## scrcpy (the judges' live view)

```
.\laptop\mirror.ps1            # Windows; -Record also writes recordings\demo-<time>.mp4 (the backup video)
laptop/mirror.sh               # macOS / Linux; --record likewise
```

scrcpy uses adb over the USB cable, so it works in airplane mode. The scripts turn on `--show-touches` (the
audience sees the tap that triggers the challenge), `--stay-awake` and `--no-audio`; `recordings/` is gitignored.

## Windows Firewall

Allow the laptop ports inbound on the hotspot LAN (PowerShell as admin):

```
netsh advfirewall firewall add rule name="FriendOrFoe" dir=in action=allow protocol=TCP localport=8000,8081
```

Check from the phone's browser: `http://<laptop-ip>:8081/health` → `{"ok":true}`.
