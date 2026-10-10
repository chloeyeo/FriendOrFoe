#pragma once

// 고정 크기 USB 출력 큐. 이벤트가 없으면 출력하지 않습니다.
namespace fof {
template<size_t N, size_t Width> struct Lines {
  char data[N][Width] = {};
  size_t head = 0, count = 0, offset = 0;
  bool wakePending = false;
  bool push(const char* line) {
    if (count == N || strlen(line) >= Width) return false;
    strcpy(data[(head + count) % N], line);
    ++count; wakePending = true;
    return true;
  }
  void drain() {
    if (!count) return;
    if (!Serial) {
      // ESP32-C3 USB-Serial/JTAG는 호스트가 IN 데이터를 읽어야 연결 플래그가
      // 복구됩니다. bool(Serial)만 기다리면 재연결 뒤 영구 대기할 수 있습니다.
      // 제어 데이터를 소모하지 않고 빈 줄로 USB만 깨웁니다(RF 송신 없음).
#if !defined(ARDUINO_ARCH_NRF52)
      // 새 이벤트가 큐에 들어왔을 때 한 번만 USB 연결 플래그를 깨웁니다.
      if (wakePending) {
        wakePending = false;
        Serial.write(reinterpret_cast<const uint8_t*>("\n"), 1);
      }
#endif
      return;
    }
    const size_t remaining = strlen(data[head]) - offset;
    const int space = Serial.availableForWrite();
    if (space <= 0) return;
    const size_t amount = remaining < static_cast<size_t>(space) ? remaining : space;
    offset += Serial.write(reinterpret_cast<const uint8_t*>(data[head] + offset), amount);
    if (offset == strlen(data[head])) { head = (head + 1) % N; --count; offset = 0; }
  }
};
} // namespace fof
