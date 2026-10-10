"""Feed the dashboard the same scripted run as the phone's DemoScenario, without a phone.

    python laptop/dashboard/feed_demo.py                      # http://127.0.0.1:8081, loops
    python laptop/dashboard/feed_demo.py --url http://192.168.43.100:8081 --once

Standard library only, so it runs on any laptop Python 3.9+. Boxes are preview pixels of a 1920x1080 preview,
laid out like app/.../session/DemoScenario.kt.
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.request

W, H = 1920, 1080
PERSON = {"id": 1, "class": "person", "score": 0.91, "box": [W * 0.66, H * 0.30, W * 0.80, H * 0.98]}
DRONE = {"id": 2, "class": "drone", "score": 0.42, "box": [W * 0.30, H * 0.22, W * 0.42, H * 0.40]}

# (iffStatus, reason, droneId, rssiDbm, roundTripMs) in demo order, as in DemoScenario
OUTCOMES = [
    ("FRIEND", None, "RQ-07", -58, 42),
    ("FOE", "replayed nonce", "RQ-07", -61, 40),
    ("FOE", "position mismatch", "RQ-07", -66, 180),
    ("FOE", "late response", "RQ-07", -60, 3450),
    ("UNKNOWN", "no response", None, None, None),
]


def post(base: str, path: str, body: object) -> None:
    req = urllib.request.Request(base + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=2) as r:
        r.read()


def dbm(v: int) -> str:
    return f"{v} dBm".replace("-", "−")


def tag(status, reason, drone_id, rssi, ms):
    """Same text and tone as the phone's tagFor() (ui/overlay/OverlayStyle.kt); keep in sync."""
    if status is None:
        return "drone · ?", "amber", None
    evidence = " · ".join(x for x in (drone_id, rssi is not None and dbm(rssi), ms is not None and f"{ms} ms") if x)
    if status == "FRIEND":
        return " · ".join(x for x in ("FRIEND", evidence) if x), "green", None
    if status == "FOE":
        return " · ".join(x for x in ("FOE", reason) if x), "red", evidence or None
    return f"UNKNOWN · {reason or 'no response'}", "red", evidence or None


def now_ms() -> int:
    return int(time.time() * 1000)


def frame(drone_tag) -> dict:
    text, tone, detail = drone_tag
    return {
        "t": now_ms(), "width": W, "height": H,
        "tracks": [
            {"id": 1, "class": "person", "box": PERSON["box"], "tag": "person", "tone": "neutral", "detail": None},
            {"id": 2, "class": "drone", "box": DRONE["box"], "tag": text, "tone": tone, "detail": detail},
        ],
    }


def detected(obj: dict) -> dict:
    return {"t": now_ms(), "class": obj["class"], "box": obj["box"], "iffStatus": None, "droneId": None,
            "reason": None, "text": f"{obj['class']} detected · box {obj['id']}", "trackId": obj["id"],
            "score": obj["score"], "rssiDbm": None, "roundTripMs": None}


def run(base: str, step_s: float) -> None:
    post(base, "/api/reset", {})
    post(base, "/api/events", [detected(PERSON), detected(DRONE)])
    post(base, "/api/frame", frame(tag(None, None, None, None, None)))
    time.sleep(step_s)
    for status, reason, drone_id, rssi, ms in OUTCOMES:
        post(base, "/api/events", {"t": now_ms(), "class": None, "box": None, "iffStatus": None,
                                   "text": "challenge sent · box 2"})
        post(base, "/api/frame", frame(("drone · challenge 7 s", "amber", None)))
        time.sleep(step_s / 2)
        line = " · ".join(str(x) for x in (status, reason, drone_id) if x)
        post(base, "/api/events", {**detected(DRONE), "iffStatus": status, "reason": reason, "droneId": drone_id,
                                   "rssiDbm": rssi, "roundTripMs": ms, "text": line})
        post(base, "/api/frame", frame(tag(status, reason, drone_id, rssi, ms)))
        print(line, flush=True)
        time.sleep(step_s)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://127.0.0.1:8081")
    ap.add_argument("--step", type=float, default=4.0, help="seconds per verdict")
    ap.add_argument("--once", action="store_true")
    args = ap.parse_args()
    base = args.url.rstrip("/")
    while True:
        run(base, args.step)
        if args.once:
            break


if __name__ == "__main__":
    main()
