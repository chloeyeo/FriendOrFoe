# laptop/ — side-car tools (dashboard, gadget CLI, serve)

Nothing here is on the IFF critical path: the judges watch the phone (mirrored with scrcpy over USB). The laptop
adds the second ClikaRT device type (`clikart-cli serve --offline`), the eval backend and a live dashboard.

```
dashboard/app.py   FastAPI: GET /dashboard (judges' page, polls /api/state every second, no CDN), POST /api/events
gadget_cli.py      send the phone's JSON lines to the reader gadget over USB serial; --fake prints canned answers
```

## Python

Python 3.13 with `fastapi`, `uvicorn` already installed in the user site (run plain `python`, **not** `python -I`,
which hides user-site packages). `pyserial` is needed only for the real gadget: `python -m pip install pyserial`.

## Dashboard (port 8081 — 8080 is taken on Chloe's laptop)

```
cd laptop/dashboard
python -m uvicorn app:app --host 0.0.0.0 --port 8081
# or: python laptop/dashboard/app.py
```

Open `http://<laptop-ip>:8081/dashboard`. The phone posts `{t, class, box, iffStatus, droneId, reason}` to
`http://<laptop-ip>:8081/api/events` (settings → dashboard URL). Smoke test without the phone:

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

`scrcpy --max-size 1280 --stay-awake --window-title FriendOrFoe` over the USB cable; also records the backup video
(`scrcpy --record demo.mp4`). No network involved.

## Windows Firewall

Allow the laptop ports inbound on the hotspot LAN (PowerShell as admin):

```
netsh advfirewall firewall add rule name="FriendOrFoe" dir=in action=allow protocol=TCP localport=8000,8081
```

Check from the phone's browser: `http://<laptop-ip>:8081/health` → `{"ok":true}`.
