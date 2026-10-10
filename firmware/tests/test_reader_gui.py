"""시리얼 대역/실제 HTTP API로 연결, 타임아웃, 중복 클릭을 검증합니다."""
import json
from pathlib import Path
import sys
import threading
import time
import unittest
from unittest.mock import patch
from types import ModuleType, SimpleNamespace
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from reader_backend import ReaderController, BenchController
from reader_gui import make_handler
from serial_connection import SerialLines, open_serial


class FakeSerial:
    def __init__(self):
        self.writes = []
        self.closed = False
        self.error = False
        self.owner = threading.get_ident()
        self.check_owner = False
        self.incoming = bytearray()

    def write(self, data):
        if self.check_owner:
            assert self.owner == threading.get_ident()
        self.writes.append(data.decode().strip())
        return len(data)

    def read(self, _count):
        if self.check_owner:
            assert self.owner == threading.get_ident()
        if self.error:
            raise OSError("USB disconnected")
        data = bytes(self.incoming)
        self.incoming.clear()
        return data

    def close(self):
        if self.check_owner:
            assert self.owner == threading.get_ident()
        self.closed = True


def status(controller, **changes):
    return dict(event="STATUS", request_id=controller.status_id, role="reader",
                interface_version=2, manual_query=True, boot_id="boot-one", state="IDLE",
                key_configured=True, radio_ready=True, last_result=None, **changes)


def result(request_id, verdict="FRIEND"):
    return dict(event="RESULT", request_id=request_id, query=1, verdict=verdict,
                response_received=verdict == "FRIEND", reason="NONE" if verdict == "FRIEND" else "NO_RESPONSE",
                response_ms=160 if verdict == "FRIEND" else None)


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.now = 0
        self.port = FakeSerial()
        self.c = ReaderController(lambda _: self.port, lambda: self.now, start=False)
        self.c.connect("COM7")
        self.action()
        self.line(status(self.c))

    def action(self):
        self.c._action(*self.c.commands.get_nowait())

    def line(self, event):
        self.c._handle_line(json.dumps(event))

    def query(self):
        rid = self.c.query()
        self.action()
        return rid

    def test_idle_never_polls_serial_or_queries(self):
        for tick in range(1, 10):
            self.now = tick
            self.c._tick()
            self.line(status(self.c))
            self.c.snapshot()
        self.assertTrue(self.c.snapshot()["can_query"])
        self.assertTrue(all(s.startswith("STATUS ") for s in self.port.writes))
        self.assertEqual(len(self.port.writes), 1)

    def test_one_click_one_command_fast_duplicate_click_rejected(self):
        rid = self.c.query()
        with self.assertRaises(ValueError):
            self.c.query()
        self.action()
        self.assertRegex(rid, r"^[a-f0-9]{32}$")
        self.assertEqual([s for s in self.port.writes if s.startswith("QUERY")], ["QUERY " + rid])
        self.line(dict(event="ACK", request_id=rid, accepted=True))
        self.line(result(rid))
        self.assertEqual(self.c.snapshot()["last_result"]["verdict"], "FRIEND")
        self.assertFalse(self.c.snapshot()["can_query"])
        self.line(dict(event="STATE", boot_id="boot-one", state="COOLDOWN"))
        self.assertFalse(self.c.snapshot()["can_query"])
        with self.assertRaises(ValueError):
            self.c.query()
        self.line(dict(event="STATE", boot_id="boot-one", state="IDLE"))
        self.assertTrue(self.c.snapshot()["can_query"])

    def test_stale_and_duplicate_results_do_not_replace_new_request(self):
        rid = self.query()
        self.line(result("stale"))
        self.assertIsNotNone(self.c.snapshot()["active_request"])
        self.line(result(rid))
        self.line(dict(event="STATE", boot_id="boot-one", state="IDLE"))
        second = self.query()
        self.line(result(rid))
        self.assertIsNone(self.c.snapshot()["last_result"])
        self.line(result(second, "UNKNOWN"))
        self.assertEqual(self.c.snapshot()["last_result"]["reason"], "NO_RESPONSE")

    def test_ack_and_result_timeouts_are_not_wireless_no_response(self):
        self.query()
        self.now = 2
        self.c._tick()
        self.assertEqual(self.c.snapshot()["last_result"]["reason"], "ACK_TIMEOUT")
        self.line(status(self.c))
        rid = self.query()
        self.line(dict(event="ACK", request_id=rid, accepted=True))
        self.now = 6
        self.c._tick()
        self.assertEqual(self.c.snapshot()["last_result"]["reason"], "RESULT_TIMEOUT")
        self.assertIsNone(self.c.snapshot()["last_result"]["response_received"])
        self.line(result(rid))
        self.assertEqual(self.c.snapshot()["last_result"]["reason"], "RESULT_TIMEOUT")

    def test_status_recovers_missing_ack_and_result(self):
        rid = self.query()
        event = status(self.c)
        event["last_result"] = result(rid)
        self.line(event)
        self.assertEqual(self.c.snapshot()["last_result"]["verdict"], "FRIEND")
        self.assertIsNone(self.c.snapshot()["active_request"])

    def test_status_result_recovery_preserves_cooldown_until_idle(self):
        rid = self.query()
        event = status(self.c)
        event.update(state="COOLDOWN", last_result=result(rid))
        self.line(event)
        self.assertEqual(self.c.snapshot()["last_result"]["verdict"], "FRIEND")
        self.assertFalse(self.c.snapshot()["can_query"])
        self.now = 3
        self.line(event)  # 컴퓨터 시각만으로 장치 잠금을 해제하지 않습니다.
        self.assertFalse(self.c.snapshot()["can_query"])
        event["state"] = "IDLE"
        self.line(event)
        self.assertTrue(self.c.snapshot()["can_query"])

    def test_reboot_cancels_query_and_requires_fresh_handshake(self):
        rid = self.query()
        self.line(dict(event="STATE", reason="BOOT", boot_id="boot-two", state="IDLE"))
        self.assertEqual(self.c.snapshot()["last_result"]["reason"], "READER_REBOOTED")
        self.assertFalse(self.c.snapshot()["can_query"])
        event = status(self.c); event["boot_id"] = "boot-two"
        self.line(event)
        self.assertTrue(self.c.snapshot()["can_query"])
        self.line(result(rid))
        self.assertEqual(self.c.snapshot()["last_result"]["reason"], "READER_REBOOTED")
        self.assertEqual(sum(s.startswith("QUERY") for s in self.port.writes), 1)

    def test_status_boot_change_and_incompatible_board(self):
        self.query()
        event = status(self.c); event["boot_id"] = "boot-two"
        self.line(event)
        self.assertEqual(self.c.snapshot()["last_result"]["reason"], "READER_REBOOTED")
        event = status(self.c); event["role"] = "drone"
        self.line(event)
        self.assertFalse(self.c.snapshot()["can_query"])
        self.assertTrue(self.port.closed)

    def test_busy_disconnect_and_reconnect_never_resend(self):
        rid = self.query()
        self.line(dict(event="ACK", request_id=rid, accepted=False, reason="BUSY"))
        self.assertEqual(self.c.snapshot()["last_result"]["category"], "REJECTED")
        self.line(status(self.c)); self.query()
        self.c.disconnect(); self.action()
        self.assertEqual(self.c.snapshot()["last_result"]["reason"], "DISCONNECTED")
        self.c.connect("COM7"); self.action(); self.line(status(self.c))
        self.assertEqual(sum(s.startswith("QUERY") for s in self.port.writes), 2)

    def test_missing_key_radio_error_disable_button_but_idle_silence_is_valid(self):
        for field in ("key_configured", "radio_ready"):
            event = status(self.c); event[field] = False
            self.line(event)
            with self.assertRaises(ValueError):
                self.c.query()
        self.line(status(self.c)); self.now = 5; self.c._tick()
        self.assertEqual(self.c.snapshot()["connection"], "connected")
        self.assertTrue(self.c.snapshot()["can_query"])

    def test_bootloader_text_disables_queries(self):
        self.c._handle_line("waiting for download")
        self.assertFalse(self.c.snapshot()["can_query"])
        self.assertIn("다운로드 모드", self.c.snapshot()["error"])

    def test_logs_bounded_and_key_echo_redacted(self):
        self.c._handle_line("KEY " + "ab" * 32)
        self.assertNotIn("ab" * 32, self.c.snapshot()["logs"][-1]["text"])
        for _ in range(700):
            self.c._handle_line("diagnostic")
        self.assertEqual(len(self.c.snapshot()["logs"]), 500)


class BenchTests(unittest.TestCase):
    def setUp(self):
        self.now = 0
        self.ports = {}
        def factory(name):
            self.ports[name] = FakeSerial()
            return self.ports[name]
        self.c = BenchController(factory, lambda: self.now, start=False)
        self.addCleanup(self.c.close)
        for role, port in (("reader", "COM7"), ("drone", "COM8")):
            self.c.connect(port, role)
            device = getattr(self.c, role)
            device._action(*device.commands.get_nowait())
            event = status(device)
            event.update(role=role, state="IDLE" if role == "reader" else "LISTENING")
            device._handle_line(json.dumps(event))

    def test_independent_ports_combined_logs_and_drone_cannot_query(self):
        state = self.c.snapshot()
        self.assertTrue(state["can_query"])
        self.assertTrue(state["drone"]["compatible"])
        self.assertFalse(state["drone"]["can_query"])
        self.assertEqual({e["source"] for e in state["logs"]}, {"reader", "drone"})
        self.assertEqual([e["received_at"] for e in state["logs"]], sorted(e["received_at"] for e in state["logs"]))
        rid = self.c.query()
        self.c.reader._action(*self.c.reader.commands.get_nowait())
        self.c.drone._handle_line(json.dumps(result(rid)))
        self.assertIsNotNone(self.c.snapshot()["active_request"])
        self.assertEqual(len(self.ports["COM8"].writes), 1)
        with self.assertRaises(ValueError):
            self.c.drone.query()

    def test_idle_no_poll_both_roles_and_manual_snapshot(self):
        for tick in range(1, 60):
            self.now = tick
            self.c.reader._tick()
            self.c.drone._tick()
        self.assertEqual([len(p.writes) for p in self.ports.values()], [1, 1])
        self.assertTrue(self.c.snapshot()["can_query"])
        self.c.request_status("drone")
        self.c.drone._action(*self.c.drone.commands.get_nowait())
        self.assertEqual(len(self.ports["COM8"].writes), 2)

    def test_drone_disconnect_does_not_cancel_reader_query(self):
        rid = self.c.query()
        self.c.drone._connection_error("USB disconnected")
        self.assertEqual(self.c.snapshot()["active_request"]["request_id"], rid)
        self.assertEqual(self.c.snapshot()["drone"]["connection"], "error")

    def test_port_collision_and_role_validation(self):
        self.c.disconnect("drone")
        self.c.drone._action(*self.c.drone.commands.get_nowait())
        with self.assertRaises(ValueError):
            self.c.connect("COM7", "drone")
        with self.assertRaises(ValueError):
            self.c.connect("COM9", "invalid")
        self.c.connect("COM8", "drone")
        self.c.drone._action(*self.c.drone.commands.get_nowait())
        wrong = status(self.c.drone)
        self.c.drone._handle_line(json.dumps(wrong))
        self.assertEqual(self.c.snapshot()["drone"]["connection"], "error")

    def test_recovery_is_bounded_to_one_snapshot_per_query(self):
        self.c.query()
        self.c.reader._action(*self.c.reader.commands.get_nowait())
        self.now = 1.6
        self.c.reader._tick()
        self.now = 1.8
        self.c.reader._tick()
        self.assertEqual(sum(line.startswith("STATUS") for line in self.ports["COM7"].writes), 2)


class TransportTests(unittest.TestCase):
    def test_usb_control_lines_native_esp_and_standard_cdc(self):
        serial = ModuleType("serial")
        tools = ModuleType("serial.tools")
        ports = ModuleType("serial.tools.list_ports")
        entries = [SimpleNamespace(device="COM7", vid=0x303a, pid=0x1001),
                   SimpleNamespace(device="COM8", vid=0x239a, pid=0x80ac)]
        ports.comports = lambda: entries
        def factory(**kwargs):
            self.assertEqual(kwargs["baudrate"], 115200)
            return SimpleNamespace(open=lambda: None)
        serial.Serial = factory
        with patch.dict(sys.modules, {"serial": serial, "serial.tools": tools,
                                      "serial.tools.list_ports": ports}):
            esp = open_serial("COM7")
            nrf = open_serial("COM8")
        self.assertFalse(esp.dtr)
        self.assertFalse(esp.rts)
        self.assertTrue(nrf.dtr)
        self.assertFalse(nrf.rts)

    def test_lines_crlf_partial_and_oversized_line(self):
        lines = SerialLines()
        self.assertEqual(lines.feed(b'{"x":'), [])
        self.assertEqual(lines.feed(b'1}\r\nnext\n'), ['{"x":1}', 'next'])
        self.assertEqual(lines.feed(b'x' * 9000 + b'\nvalid\n'), ['valid'])

    def test_worker_owns_port_and_usb_error_cancels_pending(self):
        created = []
        def factory(_):
            port = FakeSerial(); port.check_owner = True; created.append(port); return port
        c = ReaderController(factory)
        self.addCleanup(c.close)
        c.connect("COM8")
        deadline = time.monotonic() + 2
        while not created and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertTrue(created)
        with c.lock:
            created[0].incoming.extend((json.dumps(status(c)) + '\n').encode())
        while not c.snapshot()["can_query"] and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertTrue(c.snapshot()["can_query"])
        c.query()
        with c.lock:
            created[0].error = True
        while c.snapshot()["connection"] != "error" and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertEqual(c.snapshot()["last_result"]["category"], "COMMUNICATION_ERROR")
        self.assertTrue(created[0].closed)

    def test_connection_failure(self):
        def factory(_):
            raise OSError("Port already in use")
        c = ReaderController(factory); self.addCleanup(c.close); c.connect("COM8")
        deadline = time.monotonic() + 2
        while c.snapshot()["connection"] != "error" and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertIn("Port already in use", c.snapshot()["error"])

    def test_http_refresh_is_read_only_and_mutations_validate_origin(self):
        c = ReaderController(start=False)
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(c))
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
        base = f"http://127.0.0.1:{server.server_port}"
        for path in ("/", "/app.js", "/style.css", "/api/state", "/api/state"):
            with urlopen(base + path, timeout=5) as response:
                self.assertEqual(response.status, 200)
        self.assertTrue(c.commands.empty())
        request = Request(base + "/api/query", data=b"{}", headers={"Origin": "https://example.com"})
        with self.assertRaises(HTTPError) as error:
            urlopen(request, timeout=5)
        self.assertEqual(error.exception.code, 403)
        with self.assertRaises(HTTPError) as error:
            urlopen(Request(base + "/api/query", data=b"{}"), timeout=5)
        self.assertEqual(error.exception.code, 409)


if __name__ == "__main__":
    unittest.main(verbosity=2)
