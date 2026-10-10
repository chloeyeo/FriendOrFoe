#!/usr/bin/env python3
"""Mac/Windows USB 시리얼 모니터 (pySerial 3.5)."""
import argparse
import datetime
import json
import sys
import time
from serial_connection import open_serial, SerialLines


def display(line, raw_json=False):
    """JSON 이벤트는 읽기 쉽게, 기존 상태 로그는 원문으로 표시합니다."""
    if raw_json:
        print(line, flush=True)
        return
    stamp = datetime.datetime.now().strftime("%H:%M:%S")
    try:
        event = json.loads(line)
        if not isinstance(event, dict) or event.get("event") not in ("TX_CHALLENGE", "RX_RESPONSE"):
            raise ValueError("Not a packet event")
        direction = "TX 챌린지" if event["event"] == "TX_CHALLENGE" else "RX 응답"
        print(f"[{stamp}] {direction} #{event['query']}  {event['result']}"
              f"  board={event['at_ms']}ms  elapsed={event['elapsed_ms']}ms", flush=True)
        if "nonce" in event:
            print(f"  reader={event['reader_id']}  drone={event['drone_id']}"
                  f"  version={event['version']}  type={event['type']}  bytes={event['bytes']}")
            print(f"  nonce : {event['nonce']}")
            print(f"  tag   : {event['tag']}")
        print(f"  raw   : {event['raw']}", flush=True)
    except (ValueError, KeyError, TypeError):
        print(f"[{stamp}] {line}", flush=True)


def monitor(port, seconds=None, raw_json=False, command=None):
    connection = open_serial(port)
    try:
        print(f"Monitoring {port} at 115200 baud. Stop: Ctrl+C", file=sys.stderr, flush=True)
        if command:
            connection.write((command + "\n").encode("ascii"))
        lines = SerialLines()
        end = None if seconds is None else time.monotonic() + seconds
        while end is None or time.monotonic() < end:
            for line in lines.feed(connection.read(4096)):
                display(line, raw_json)
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, help="reader 포트, 예: /dev/cu.usbmodem1101")
    parser.add_argument("--seconds", type=float, help="지정 시간 후 종료 (기본값: 계속 표시)")
    parser.add_argument("--json", action="store_true", help="가공하지 않은 JSONL/상태 로그 표시")
    parser.add_argument("--command", help="연결 후 명령 한 번 전송 (예: STATUS 또는 QUERY demo1)")
    args = parser.parse_args()
    if args.seconds is not None and args.seconds <= 0:
        parser.error("--seconds must be positive")
    try:
        monitor(args.port, args.seconds, args.json, args.command)
    except KeyboardInterrupt:
        print("\nMonitor stopped.", file=sys.stderr)
    except (OSError, ImportError) as error:
        print(f"Serial error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
