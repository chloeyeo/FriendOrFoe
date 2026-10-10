"""명령 소유권/시리얼 요청 수명 관리. HTTP 스레드는 포트를 직접 열지 않습니다."""
import copy
from collections import deque
import json
import queue
import re
import threading
import time
import uuid

from serial_connection import SerialLines, open_serial

INTERFACE_VERSION = 2


class ReaderController:
    def __init__(self, factory=open_serial, clock=time.monotonic, start=True, role="reader"):
        self.role = role
        self.factory, self.clock = factory, clock
        self.lock = threading.RLock()
        self.commands = queue.Queue(maxsize=16)
        self.stop_event = threading.Event()
        self.logs = deque(maxlen=500)
        self.sequence = 0
        self.state = dict(role=role, connection="disconnected", port=None, compatible=False,
                          reader=None, active_request=None, last_result=None, error=None)
        self.serial = None  # 작업 스레드만 접근합니다.
        self.lines = SerialLines()
        self.status_id = None
        self.last_status_at = 0
        self.next_status_at = 0
        self.handshake_deadline = 0
        self.thread = threading.Thread(target=self._run, name=role + "-serial", daemon=True)
        if start:
            self.thread.start()

    def snapshot(self):
        with self.lock:
            state = copy.deepcopy(self.state)
            state["logs"] = list(self.logs)
            state["device"] = state["reader"]
            state["can_query"] = (self.role == "reader" and state["connection"] == "connected" and state["compatible"]
                                  and not state["active_request"] and state["reader"] is not None
                                  and state["reader"].get("state") == "IDLE"
                                  and state["reader"].get("key_configured") is True
                                  and state["reader"].get("radio_ready") is True)
            return state

    def connect(self, port):
        if not isinstance(port, str) or not port or len(port) > 256:
            raise ValueError("유효한 시리얼 포트를 선택해 주세요.")
        with self.lock:
            if self.state["connection"] in ("connecting", "connected", "disconnecting"):
                raise ValueError("기존 연결을 먼저 해제해 주세요.")
            self.state.update(connection="connecting", port=port, compatible=False,
                              reader=None, error=None)
            self.commands.put_nowait(("connect", port))

    def disconnect(self):
        with self.lock:
            if self.state["connection"] == "disconnecting":
                return
            if self.state["active_request"]:
                self._fail_request("DISCONNECTED", "연결을 해제했습니다.")
            else:
                self.state["error"] = None
            self.state.update(connection="disconnecting", compatible=False)
            self.commands.put_nowait(("disconnect", None))

    def query(self):
        with self.lock:
            if not self.snapshot()["can_query"]:
                raise ValueError("reader가 연결되어 대기 중일 때만 질의할 수 있습니다.")
            request_id = uuid.uuid4().hex
            # HTTP에서 먼저 예약하여 동시에 들어온 클릭을 한 번만 수락합니다.
            self.state["active_request"] = dict(request_id=request_id, phase="QUEUED", sent_at=None)
            self.state["last_result"] = None
            self.state["error"] = None
            self.commands.put_nowait(("query", request_id))
            return request_id

    def request_status(self):
        with self.lock:
            if self.serial and self.state["connection"] == "connected":
                self.commands.put_nowait(("status", None))

    def _log(self, direction, text):
        # KEY 입력 기능은 없습니다. 예기치 않은 에코도 키 값은 보관하지 않습니다.
        text = re.sub(r"(?i)(KEY\s+)[0-9a-f]{64}", r"\1[REDACTED]", text)
        self.sequence += 1
        self.logs.append(dict(sequence=self.sequence, direction=direction, text=text,
                              time=time.strftime("%H:%M:%S"), source=self.role, received_at=time.monotonic_ns()))

    def _fail_request(self, reason, message):
        pending = self.state["active_request"]
        if pending:
            self.state["last_result"] = dict(request_id=pending["request_id"],
                category="COMMUNICATION_ERROR", reason=reason, message=message,
                response_received=None, verdict=None, response_ms=None)
            self.state["active_request"] = None
        self.state["error"] = message

    def _write(self, text):
        data = (text + "\n").encode("ascii")
        if self.serial.write(data) != len(data):
            raise OSError("시리얼 명령을 완전히 전송하지 못했습니다.")
        self._log("TX", text)

    def _request_status(self):
        self.status_id = uuid.uuid4().hex
        self._write("STATUS " + self.status_id)
        self.next_status_at = self.clock() + 1

    def _close_port(self):
        if self.serial:
            try:
                self.serial.close()
            except OSError:
                pass
        self.serial = None

    def _connection_error(self, message):
        self._fail_request("SERIAL_ERROR", message)
        self.state.update(connection="error", compatible=False, reader=None)
        self._close_port()

    def _result(self, event):
        pending = self.state["active_request"]
        if not pending or event.get("request_id") != pending["request_id"]:
            return  # 상세 로그에는 남기되 이전/완료 요청이 화면의 새 결과를 바꾸지 않습니다.
        if event.get("verdict") not in ("FRIEND", "UNKNOWN") or type(event.get("response_received")) is not bool:
            return
        if not isinstance(event.get("reason"), str) or not isinstance(event.get("query"), int):
            return
        self.state["last_result"] = {**event, "category": "RADIO_RESULT"}
        self.state["active_request"] = None
        # RESULT는 판정만 완료합니다. 재질의 허용은 reader의 STATE/STATUS가
        # IDLE을 알릴 때까지 기다립니다(정상 응답 후에도 3초 잠금 유지).

    def _handle_line(self, line):
        self._log("RX", line)
        if "waiting for download" in line.lower():
            self._connection_error("C3가 다운로드 모드입니다. BOOT 버튼을 놓고 RESET 또는 USB 재연결 후 다시 연결해 주세요.")
            return
        try:
            event = json.loads(line)
        except (ValueError, TypeError):
            return
        if not isinstance(event, dict):
            return
        kind = event.get("event")
        old_reader = self.state["reader"]
        if kind == "STATUS" and event.get("reason") == "BOOT" and old_reader and event.get("boot_id") != old_reader.get("boot_id"):
            self._fail_request("READER_REBOOTED", self.role + "가 재부팅되어 상태를 다시 확인합니다.")
            self.state.update(compatible=False, reader=None)
            self.handshake_deadline = self.clock() + 5
            self._request_status()
            return
        if kind == "STATE" and event.get("reason") == "BOOT" and old_reader:
            if event.get("boot_id") != old_reader.get("boot_id"):
                self._fail_request("READER_REBOOTED", "reader가 재부팅되어 진행 중 질의를 취소했습니다.")
                self.state.update(compatible=False, reader=None)
                self.handshake_deadline = self.clock() + 5
                self._request_status()
            return
        if kind == "STATUS" and event.get("request_id") == self.status_id:
            compatible = (event.get("role") == self.role and event.get("interface_version") == INTERFACE_VERSION
                          and (self.role != "reader" or event.get("manual_query") is True)
                          and isinstance(event.get("boot_id"), str))
            if not compatible:
                self._connection_error(f"선택한 포트가 {self.role} 인터페이스 v2가 아닙니다. 보드와 펌웨어를 확인해 주세요.")
                return
            if old_reader and old_reader.get("boot_id") != event["boot_id"]:
                self._fail_request("READER_REBOOTED", "reader가 재부팅되어 진행 중 질의를 취소했습니다.")
            self.last_status_at = self.clock()
            self.state.update(connection="connected", compatible=True, reader=event)
            if isinstance(event.get("last_result"), dict):
                self._result(event["last_result"])  # RESULT 유실 시 STATUS 스냅샷으로 복구
        elif kind == "ACK" and self.role == "reader":
            pending = self.state["active_request"]
            if pending and event.get("request_id") == pending["request_id"]:
                if event.get("accepted") is True:
                    pending["phase"] = "WAIT_RESULT"
                elif event.get("accepted") is False:
                    self.state["last_result"] = dict(request_id=pending["request_id"], category="REJECTED",
                        reason=event.get("reason", "REJECTED"), response_received=None,
                        verdict=None, response_ms=None)
                    self.state["active_request"] = None
        elif kind == "RESULT" and self.role == "reader":
            self._result(event)
        elif kind == "STATE" and self.state["reader"]:
            if event.get("boot_id") == self.state["reader"].get("boot_id"):
                if event.get("state") in ("IDLE", "QUERYING", "COOLDOWN"):
                    self.state["reader"]["state"] = event["state"]

    def _tick(self):
        now = self.clock()
        pending = self.state["active_request"]
        if pending and pending["sent_at"] is not None:
            age = now - pending["sent_at"]
            # 진행 중 요청에서만 유실 복구용 스냅샷을 한 번 요청합니다.
            if ((pending["phase"] == "WAIT_ACK" and age >= 1.5) or age >= 3.5) and not pending.get("recovery_requested"):
                pending["recovery_requested"] = True
                self._request_status()
            if age >= 4:
                self._fail_request("RESULT_TIMEOUT", "시리얼 최종 판정이 4초 내 도착하지 않았습니다. 무선 무응답 판정과 다릅니다.")
            elif pending["phase"] == "WAIT_ACK" and age >= 2:
                self._fail_request("ACK_TIMEOUT", "시리얼 명령 수락이 2초 내 확인되지 않았습니다. 자동 재전송하지 않습니다.")
        if self.serial:
            if not self.state["compatible"] and now >= self.handshake_deadline:
                self._connection_error("reader STATUS 확인 시간이 초과되었습니다.")
            elif not self.state["compatible"] and now >= self.next_status_at:
                self._request_status()

    def _action(self, action, value):
        if action == "connect":
            self._close_port()
            self.serial = self.factory(value)
            self.lines = SerialLines()
            self.handshake_deadline = self.clock() + 5
            self._request_status()
        elif action == "disconnect":
            self._close_port()
            self.state.update(connection="disconnected", compatible=False, reader=None, port=None)
        elif action == "status":
            if self.serial:
                self._request_status()
        elif action == "query":
            pending = self.state["active_request"]
            if pending and pending["request_id"] == value and self.serial:
                self._write("QUERY " + value)
                pending.update(phase="WAIT_ACK", sent_at=self.clock())
                self.state["reader"]["state"] = "QUERYING"

    def _run(self):
        while not self.stop_event.is_set():
            try:
                with self.lock:
                    try:
                        action, value = self.commands.get_nowait()
                    except queue.Empty:
                        pass
                    else:
                        self._action(action, value)
                    if self.serial:
                        for line in self.lines.feed(self.serial.read(4096)):
                            self._handle_line(line)
                    self._tick()
            except Exception as error:
                with self.lock:
                    self._connection_error(str(error))
            self.stop_event.wait(0.01)
        with self.lock:
            self._close_port()

    def close(self):
        self.stop_event.set()
        if self.thread.is_alive():
            self.thread.join(timeout=2)


class BenchController:
    """양쪽 포트를 독립적으로 소유하고 로그를 컴퓨터 수신 순서로 합칩니다."""
    def __init__(self, factory=open_serial, clock=time.monotonic, start=True):
        self.reader = ReaderController(factory, clock, start, role="reader")
        self.drone = ReaderController(factory, clock, start, role="drone")
        self.lock = threading.RLock()

    def _device(self, role):
        if role not in ("reader", "drone"):
            raise ValueError("role은 reader 또는 drone이어야 합니다.")
        return getattr(self, role)

    def connect(self, port, role="reader"):
        with self.lock:
            target = self._device(role)
            other = self._device("drone" if role == "reader" else "reader")
            state = other.snapshot()
            if state["port"] and isinstance(port, str) and state["port"].lower() == port.lower() and state["connection"] in ("connecting", "connected", "disconnecting"):
                raise ValueError("같은 시리얼 포트를 두 역할에 연결할 수 없습니다.")
            target.connect(port)

    def disconnect(self, role="reader"):
        self._device(role).disconnect()

    def request_status(self, role="reader"):
        self._device(role).request_status()

    def query(self):
        return self.reader.query()

    def snapshot(self):
        reader, drone = self.reader.snapshot(), self.drone.snapshot()
        logs = sorted(reader["logs"] + drone["logs"], key=lambda entry: entry["received_at"])[-500:]
        return {**reader, "drone": drone, "logs": logs}

    def close(self):
        self.reader.close()
        self.drone.close()
