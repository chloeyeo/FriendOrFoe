"""GUI/터미널 공통 pySerial 연결. 포트는 호출한 작업 스레드만 사용합니다."""
import os


def open_serial(port):
    import serial
    from serial.tools.list_ports import comports
    # open 이전에 RTS/DTR를 설정합니다. OS/USB 드라이버가 열 때 보드를
    # 재시작할 수도 있으므로 GUI는 반드시 새 STATUS로 역할을 확인합니다.
    connection = serial.Serial(port=None, baudrate=115200, timeout=0.03,
                               write_timeout=0.5, exclusive=True if os.name == "posix" else None)
    # ESP32 USB-Serial/JTAG(303a:1001)에서 DTR을 켜면 OS의 RTS 변경과
    # 조합되어 다운로드 모드로 들어갈 수 있습니다. 이 장치는 DTR이
    # 없어도 송수신합니다. 일반 USB CDC(NU40 등)는 DTR을 활성화합니다.
    native_esp = any(item.device.lower() == port.lower() and
                     (item.vid, item.pid) == (0x303A, 0x1001) for item in comports())
    connection.dtr = not native_esp
    connection.rts = False
    connection.port = port
    connection.open()
    return connection


def list_ports():
    from serial.tools.list_ports import comports
    return [{"port": item.device, "description": item.description,
             "serial_number": item.serial_number} for item in sorted(comports())]


class SerialLines:
    """읽기 경계에 걸친 줄을 합칩니다. 초과 줄은 LF까지 버려 JSON 혼입을 막습니다."""
    def __init__(self):
        self.buffer = bytearray()
        self.discarding = False

    def feed(self, data):
        lines = []
        for byte in data:
            if byte == 10:
                if not self.discarding:
                    text = self.buffer.decode("utf-8", errors="replace").strip()
                    if text:
                        lines.append(text)
                self.buffer.clear()
                self.discarding = False
            elif not self.discarding:
                self.buffer.append(byte)
                if len(self.buffer) > 8192:
                    self.buffer.clear()
                    self.discarding = True
        return lines
