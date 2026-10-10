#!/usr/bin/env python3
"""BLE IFF reader bridge: the reader on this laptop's USB port, reachable from a phone on the same LAN.

    python3 app-cross/tools/reader_bridge.py                  # the ESP32-C3 reader is found by its USB id
    python3 app-cross/tools/reader_bridge.py --serial COM5    # or name the port
    python3 app-cross/tools/reader_bridge.py --simulate       # no hardware: a reader that always gets a FRIEND

The phone app (Settings > Signal source > Reader via laptop, URL http://<laptop-ip>:8766) long-polls
GET /lines?after=<seq>&wait=<ms> for the reader's JSON lines and sends commands with POST /line {"line": "..."}.
Only QUERY, STATUS and HELP are forwarded: KEY never crosses the network, set the key over USB with the serial
monitor (firmware/README.md). The serial port is owned by one worker thread, like firmware/tools/reader_backend.py.
"""
import argparse
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import queue
import re
import socket
import sys
import threading
import time
import uuid

TOOLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "firmware", "tools")
ALLOWED = re.compile(r"^(QUERY [A-Za-z0-9_-]{1,32}|STATUS( [A-Za-z0-9_-]{1,32})?|HELP)$")
ESP_USB_JTAG = (0x303A, 0x1001)
MAX_WAIT_MS = 5000


class Lines:
    """The reader's output, numbered, for any number of long-polling phones."""

    def __init__(self):
        self.lines = deque(maxlen=1000)
        self.seq = 0
        self.cond = threading.Condition()
        self.serial = dict(port=None, connected=False, error="starting")

    def add(self, text):
        with self.cond:
            self.seq += 1
            self.lines.append((self.seq, text))
            self.cond.notify_all()

    def set_serial(self, **state):
        with self.cond:
            self.serial = {**self.serial, **state}
            self.cond.notify_all()

    def after(self, seq, wait_s):
        with self.cond:
            if seq is not None:
                self.cond.wait_for(lambda: self.seq > seq, timeout=wait_s)
            lines = [] if seq is None else [text for n, text in self.lines if n > seq]
            return self.seq, lines, dict(self.serial)


class SerialWorker(threading.Thread):
    """Opens the reader (again after a cable pull), writes queued commands, reads lines."""

    def __init__(self, lines, port):
        super().__init__(name="reader-serial", daemon=True)
        self.lines, self.port = lines, port
        self.commands = queue.Queue(maxsize=16)

    def find_port(self):
        if self.port:
            return self.port
        from serial.tools.list_ports import comports
        found = [p.device for p in comports() if (p.vid, p.pid) == ESP_USB_JTAG]
        return found[0] if found else None

    def run(self):
        from serial_connection import SerialLines, open_serial
        while True:
            port = self.find_port()
            if not port:
                self.lines.set_serial(port=None, connected=False, error="no ESP32-C3 reader on the laptop USB ports")
                time.sleep(2)
                continue
            try:
                connection = open_serial(port)
            except Exception as error:
                self.lines.set_serial(port=port, connected=False, error=f"cannot open {port}: {error}")
                time.sleep(2)
                continue
            print(f"reader on {port}", flush=True)
            self.lines.set_serial(port=port, connected=True, error=None)
            splitter = SerialLines()
            try:
                while True:
                    try:
                        while True:
                            line = self.commands.get_nowait()
                            connection.write((line + "\n").encode("ascii"))
                            print(f"TX {line}", flush=True)
                    except queue.Empty:
                        pass
                    for text in splitter.feed(connection.read(4096)):
                        print(f"RX {text}", flush=True)
                        self.lines.add(text)
            except Exception as error:
                self.lines.set_serial(connected=False, error=f"reader lost: {error}")
                print(f"reader lost: {error}", flush=True)
                try:
                    connection.close()
                except Exception:
                    pass
                time.sleep(1)

    def send(self, line):
        self.commands.put_nowait(line)


class SimulatedReader:
    """The reader firmware's v2 lines without a radio (reader.ino): ACK, STATE, packet lines, RESULT, 3 s cooldown."""

    def __init__(self, lines, mode):
        self.lines, self.mode = lines, mode
        self.lock = threading.Lock()
        self.boot = uuid.uuid4().hex[:16]
        self.state, self.current, self.last, self.started, self.query = "IDLE", "", None, 0.0, 0
        lines.set_serial(port=f"simulated reader ({mode})", connected=True, error=None)
        self.emit(dict(event="STATE", boot_id=self.boot, request_id="", state="IDLE", reason="BOOT"))

    def emit(self, event):
        text = json.dumps(event, separators=(",", ":"))
        print(f"RX {text}", flush=True)
        self.lines.add(text)

    def send(self, line):
        print(f"TX {line}", flush=True)
        with self.lock:
            command, _, rid = line.partition(" ")
            if command == "STATUS":
                self.emit(dict(event="STATUS", request_id=rid, role="reader", interface_version=2, boot_id=self.boot,
                               manual_query=True, response_timeout_ms=3000, key_configured=True, radio_ready=True,
                               state=self.state, active_request_id=self.current if self.state != "IDLE" else "",
                               last_result=self.last))
            elif command == "HELP":
                self.emit(dict(event="HELP", commands=["QUERY <request_id>", "STATUS [request_id]", "HELP"]))
            elif rid in (self.current, (self.last or {}).get("request_id")):
                self.emit(dict(event="ACK", request_id=rid, accepted=True, reason="DUPLICATE", state=self.state))
                if self.last and self.last["request_id"] == rid:
                    self.emit({**self.last, "replay": True})
            elif self.state != "IDLE":
                self.emit(dict(event="ACK", request_id=rid, accepted=False, reason="BUSY", state=self.state))
            else:
                self.begin(rid)

    def begin(self, rid):
        self.current, self.query, self.started, self.state = rid, self.query + 1, time.monotonic(), "QUERYING"
        nonce = os.urandom(16).hex()
        self.emit(dict(event="ACK", request_id=rid, accepted=True, reason="ACCEPTED", state="QUERYING"))
        self.emit(dict(event="STATE", boot_id=self.boot, request_id=rid, state="QUERYING", reason="CHANGED"))
        self.emit(dict(event="TX_CHALLENGE", request_id=rid, query=self.query, at_ms=0, elapsed_ms=0, bytes=38, version=1,
                       type=1, reader_id=1, drone_id=1, nonce=nonce, tag="", raw="", result="BROADCAST_STARTED"))
        delay = 3.0 if self.mode == "silent" else 0.34
        threading.Timer(delay, self.finish, args=(rid, nonce)).start()

    def finish(self, rid, nonce):
        with self.lock:
            answered = self.mode != "silent"
            reason = {"friend": "NONE", "impostor": "AUTH_FAILED"}.get(self.mode, "NO_RESPONSE")
            ms = int((time.monotonic() - self.started) * 1000)
            if answered:
                self.emit(dict(event="RX_RESPONSE", request_id=rid, query=self.query, at_ms=ms, elapsed_ms=ms, bytes=38,
                               version=1, type=2, reader_id=1, drone_id=1, nonce=nonce, tag="", raw="",
                               result="FRIEND" if reason == "NONE" else reason))
            self.last = dict(event="RESULT", request_id=rid, query=self.query, response_received=answered,
                             verdict="FRIEND" if reason == "NONE" else "UNKNOWN", reason=reason,
                             response_ms=ms if answered else None, elapsed_ms=ms, replay=False)
            self.emit(self.last)
            self.state = "COOLDOWN" if ms < 3000 else "IDLE"
            self.emit(dict(event="STATE", boot_id=self.boot, request_id=rid, state=self.state, reason="CHANGED"))
            if self.state == "COOLDOWN":
                threading.Timer(3.0 - ms / 1000, self.release).start()

    def release(self):
        with self.lock:
            self.state = "IDLE"
            self.emit(dict(event="STATE", boot_id=self.boot, request_id=self.current, state="IDLE", reason="CHANGED"))


def make_handler(lines, reader, bridge_id, allow_origin):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def reply(self, value, status=200):
            data = json.dumps(value).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            if allow_origin and self.headers.get("Origin") == allow_origin:
                self.send_header("Access-Control-Allow-Origin", allow_origin)
                self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass  # the phone gave up on a long poll (app closed, settings changed); nothing to tell it

        def do_OPTIONS(self):
            if not allow_origin or self.headers.get("Origin") != allow_origin:
                return self.reply({"error": "origin not allowed"}, 403)
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", allow_origin)
            self.send_header("Access-Control-Allow-Methods", "GET, POST")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.end_headers()

        def do_GET(self):
            path, _, query = self.path.partition("?")
            if path != "/lines":
                return self.reply({"error": "not found"}, 404)
            params = dict(part.split("=", 1) for part in query.split("&") if "=" in part)
            try:
                after = int(params["after"]) if "after" in params else None
                wait_ms = min(max(int(params.get("wait", "0")), 0), MAX_WAIT_MS)
            except ValueError:
                return self.reply({"error": "after and wait must be integers"}, 400)
            seq, out, serial = lines.after(after, wait_ms / 1000)
            if after is None:
                print(f"phone {self.client_address[0]} connected", flush=True)
            self.reply(dict(bridge=bridge_id, seq=seq, lines=out, serial=serial))

        def do_POST(self):
            if self.path != "/line":
                return self.reply({"error": "not found"}, 404)
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 256:
                    raise ValueError("body must be 1-256 bytes")
                line = json.loads(self.rfile.read(length)).get("line")
                if not isinstance(line, str):
                    raise ValueError('expected {"line": "..."}')
            except (ValueError, AttributeError) as error:
                return self.reply({"error": str(error)}, 400)
            line = line.strip()
            if not ALLOWED.match(line):
                return self.reply({"error": "only QUERY, STATUS and HELP are forwarded"}, 403)
            if not lines.serial.get("connected"):
                return self.reply({"error": lines.serial.get("error") or "reader not connected"}, 503)
            try:
                reader.send(line)
            except queue.Full:
                return self.reply({"error": "reader command queue full"}, 503)
            self.reply({"ok": True}, 202)

    return Handler


def lan_addresses():
    addresses = set()
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("10.255.255.255", 1))  # no packet is sent; this only picks the outgoing interface
        addresses.add(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    return sorted(a for a in addresses if not a.startswith("127."))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", help="reader serial port (default: the first ESP32-C3 USB-Serial/JTAG, 303a:1001)")
    parser.add_argument("--host", default="0.0.0.0", help="HTTP bind address (default: all interfaces, for the phone)")
    parser.add_argument("--port", type=int, default=8766, help="HTTP port (default 8766; the reader GUI uses 8765)")
    parser.add_argument("--simulate", nargs="?", const="friend", choices=["friend", "silent", "impostor"],
                        help="no hardware: a simulated reader whose drone answers as FRIEND, stays silent, or fails the HMAC")
    parser.add_argument("--allow-origin", help="let this browser origin call the bridge, e.g. http://localhost:8090 for the web build")
    args = parser.parse_args()

    lines = Lines()
    if args.simulate:
        reader = SimulatedReader(lines, args.simulate)
    else:
        sys.path.insert(0, os.path.abspath(TOOLS))
        try:
            import serial  # noqa: F401
        except ImportError:
            parser.exit(1, "pySerial is missing: python3 -m pip install -r firmware/tools/requirements.txt\n")
        reader = SerialWorker(lines, args.serial)
        reader.start()

    bridge_id = uuid.uuid4().hex[:8]
    try:
        server = ThreadingHTTPServer((args.host, args.port), make_handler(lines, reader, bridge_id, args.allow_origin))
    except OSError as error:
        parser.exit(1, f"cannot listen on {args.host}:{args.port}: {error}\n")
    server.daemon_threads = True
    urls = [f"http://{a}:{args.port}" for a in lan_addresses()] or [f"http://<laptop-ip>:{args.port}"]
    print(f"reader bridge {bridge_id} on {args.host}:{args.port}; in the phone app use {' or '.join(urls)}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
