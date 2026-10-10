#pragma once

// 실제 .ino의 상태 전이를 보드 없이 실행하기 위한 USB/무선/시계 대역입니다.
// 이 디렉터리는 Arduino 빌드의 --library 경로에 들어가지 않습니다.
#include <FofBleFraming.h>
#include <FofSerialLine.h>
#include <array>
#include <deque>
#include <cstdio>
#include <string>
#include <vector>

inline uint32_t fakeNow = 0;
inline bool ledLevel = true;
inline std::vector<bool> ledChanges;
inline unsigned int nonceCounter = 0;
constexpr int LOW = 0;
constexpr int HIGH = 1;
constexpr int OUTPUT = 1;
using esp_err_t = int;
constexpr esp_err_t ESP_OK = 0;
constexpr esp_err_t ESP_FAIL = -1;
inline const char* esp_err_to_name(esp_err_t) { return "MOCK_ERROR"; }
inline uint32_t millis() { return fakeNow; }
inline void delay(uint32_t ms) { fakeNow += ms; }
inline void pinMode(uint8_t, int) {}
inline void digitalWrite(uint8_t, int level) {
  ledLevel = level != LOW;
  ledChanges.push_back(ledLevel);
}
inline void esp_fill_random(void* destination, size_t length) {
  auto* bytes = static_cast<uint8_t*>(destination);
  for (size_t i = 0; i < length; ++i) bytes[i] = static_cast<uint8_t>(i);
  bytes[0] = static_cast<uint8_t>(++nonceCounter);
}
struct FakeSerial {
  std::string output, input;
  int space = 4096;
  bool connected = true;
  operator bool() const { return connected; }
  int availableForWrite() const { return space; }
  int available() const { return static_cast<int>(input.size()); }
  int read() { const unsigned char c = input.front(); input.erase(0, 1); return c; }
  void begin(int) {}
  void println(const char* text) { output += text; output += '\n'; }
  template<class... Args> void printf(const char* format, Args... args) {
    char buffer[1024];
    const int count = snprintf(buffer, sizeof(buffer), format, args...);
    if (count > 0) output.append(buffer, static_cast<size_t>(count));
  }
  size_t write(const uint8_t* data, size_t length) {
    if (!connected && length == 1 && data[0] == '\n') connected = true;
    output.append(reinterpret_cast<const char*>(data), length);
    return length;
  }
};
inline FakeSerial Serial;

namespace fof {
inline void fillRandom(uint8_t* out, size_t length) { esp_fill_random(out, length); }
struct KeyStore {
  uint8_t key[kKeySize] = {};
  bool ready = true;
  void begin() { for (size_t i = 0; i < kKeySize; ++i) key[i] = static_cast<uint8_t>(i); }
  SerialLine input;
  void poll(bool (*handler)(const char*) = nullptr, bool (*capacity)() = nullptr) {
    input.poll(Serial, [&](const char* line) { if (handler) handler(line); },
               [&]() { return !capacity || capacity(); });
  }
};
struct Radio {
  std::deque<ReceivedPacket> incoming;
  std::vector<std::array<uint8_t, kPacketSize>> outgoing;
  esp_err_t sendResult = ESP_OK;
  bool overflow = false;
  bool beginResult = true, error = false;
  bool begin(uint8_t) { return beginResult; }
  void poll() {}
  void diagnosticsJson(char* out, size_t size) const { snprintf(out, size, "{}"); }
  void stopTransmit() {}
  esp_err_t send(const uint8_t* data) {
    if (sendResult == ESP_OK) {
      std::array<uint8_t, kPacketSize> packet;
      memcpy(packet.data(), data, packet.size());
      outgoing.push_back(packet);
    }
    return sendResult;
  }
  bool receive(ReceivedPacket& packet) {
    if (incoming.empty()) return false;
    packet = incoming.front();
    incoming.pop_front();
    return true;
  }
  bool takeRadioError() { const bool value = error; error = false; return value; }
  bool takeQueueOverflow() { const bool value = overflow; overflow = false; return value; }
};
}  // namespace fof
