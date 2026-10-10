#!/usr/bin/env python
"""Talk to the reader gadget from the laptop with the same newline-JSON lines the phone sends (docs/GADGET_LINK.md).

    python laptop/gadget_cli.py --port COM5 status
    python laptop/gadget_cli.py --port COM5 challenge --box-id 3 --challenger-id 7
    python laptop/gadget_cli.py --fake challenge                 # canned answers, no hardware
    python laptop/gadget_cli.py --fake --mode REPLAY challenge

The serial path needs pyserial (`python -m pip install pyserial`); it is imported lazily so `--fake` works without it.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

BAUD = 115200
ANSWER_TIMEOUT_S = 12.0  # the gadget's own window is 10 s

FAKE_NONCE = "3f9a1c7e5b2d4680aa55cc11ee22dd33"

FAKE_ANSWERS = {
    "HONEST": {"type": "answer", "verdict": "FRIEND", "reason": None, "droneId": "RQ-07", "nonce": FAKE_NONCE,
               "rssiDbm": -58, "responseMs": 42, "airtimeMs": 1.2},
    "REPLAY": {"type": "answer", "verdict": "FOE", "reason": "replayed nonce", "droneId": "RQ-07", "nonce": FAKE_NONCE,
               "rssiDbm": -61, "responseMs": 40},
    "RELAY": {"type": "answer", "verdict": "FOE", "reason": "position mismatch", "droneId": "RQ-07", "nonce": FAKE_NONCE,
              "rssiDbm": -66, "responseMs": 180},
    "LATE": {"type": "answer", "verdict": "FRIEND", "reason": None, "droneId": "RQ-07", "nonce": FAKE_NONCE,
             "rssiDbm": -60, "responseMs": 3450},
    "SILENT": {"type": "answer", "verdict": "UNKNOWN", "reason": "no response", "nonce": FAKE_NONCE, "responseMs": 10000},
    "IMPOSTOR": {"type": "answer", "verdict": "FOE", "reason": "bad tag", "droneId": "RQ-07", "nonce": FAKE_NONCE,
                 "rssiDbm": -59, "responseMs": 45},
}


def build_line(args: argparse.Namespace) -> str:
    if args.command == "challenge":
        t = args.t if args.t is not None else int(time.time())
        obj = {"cmd": "challenge", "boxId": args.box_id, "challengerId": args.challenger_id, "t": t}
    else:
        obj = {"cmd": "status"}
    return json.dumps(obj, separators=(",", ":"))


def run_fake(args: argparse.Namespace) -> int:
    line = build_line(args)
    print(f"> {line}")
    status = {"type": "status", "fw": "0.1-fake", "battery": 3.9, "mode": args.mode}
    print(f"< {json.dumps(status, separators=(',', ':'))}")
    if args.command == "challenge":
        answer = dict(FAKE_ANSWERS[args.mode])
        if args.mode == "LATE":
            time.sleep(0.5)  # a token delay; the real LATE mode answers after 3.5 s
        print(f"< {json.dumps(answer, separators=(',', ':'))}")
        print(f"verdict: {answer['verdict']}" + (f" | {answer['reason']}" if answer.get("reason") else "")
              + (f" | {answer['droneId']}" if answer.get("droneId") else "")
              + (f" | {answer['rssiDbm']} dBm" if answer.get("rssiDbm") is not None else "")
              + f" | {answer['responseMs']} ms")
    return 0


def run_serial(args: argparse.Namespace) -> int:
    try:
        import serial  # pyserial, imported lazily
    except ImportError:
        print("pyserial is not installed: python -m pip install pyserial", file=sys.stderr)
        return 2
    line = build_line(args)
    with serial.Serial(args.port, args.baud, timeout=0.2) as port:
        time.sleep(0.3)  # native-USB boards may reset on open
        port.reset_input_buffer()
        print(f"> {line}")
        port.write((line + "\n").encode("ascii"))
        port.flush()
        deadline = time.monotonic() + (ANSWER_TIMEOUT_S if args.command == "challenge" else 3.0)
        while time.monotonic() < deadline:
            raw = port.readline()
            if not raw:
                continue
            text = raw.decode("utf-8", errors="replace").strip()
            if not text:
                continue
            print(f"< {text}")
            try:
                obj = json.loads(text)
            except json.JSONDecodeError:
                continue
            kind = obj.get("type")
            if kind == "answer":
                return 0
            if kind == "status" and args.command == "status":
                return 0
    print("timeout: no answer from the gadget", file=sys.stderr)
    return 1


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("command", choices=["challenge", "status"])
    ap.add_argument("--port", help="serial port, e.g. COM5 or /dev/ttyACM0")
    ap.add_argument("--baud", type=int, default=BAUD)
    ap.add_argument("--fake", action="store_true", help="print canned answers without hardware")
    ap.add_argument("--mode", default="HONEST", choices=sorted(FAKE_ANSWERS), help="fake mode (with --fake)")
    ap.add_argument("--box-id", type=int, default=3)
    ap.add_argument("--challenger-id", type=int, default=7)
    ap.add_argument("--t", type=int, default=None, help="unix seconds (default: now)")
    args = ap.parse_args(argv)
    if args.fake:
        return run_fake(args)
    if not args.port:
        ap.error("--port COMx is required unless --fake is given")
    return run_serial(args)


if __name__ == "__main__":
    raise SystemExit(main())
