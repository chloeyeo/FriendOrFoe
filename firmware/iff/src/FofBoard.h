#pragma once
#include <Arduino.h>
#include <atomic>
#include "FofBleFraming.h"
#include "FofSerialLine.h"

#if defined(ARDUINO_ARCH_ESP32)
#include <Preferences.h>
#include <NimBLEDevice.h>
#if !CONFIG_BT_NIMBLE_EXT_ADV
#error "Enable CONFIG_BT_NIMBLE_EXT_ADV for all files using reader/build_opt.h."
#endif
#include <esp_random.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#elif defined(ARDUINO_ARCH_NRF52)
#include <Adafruit_TinyUSB.h> // NU40의 USB Serial 구현에 반드시 필요
#include <bluefruit.h>
#include <Adafruit_LittleFS.h>
#include <InternalFileSystem.h>
#include <rtos.h>
#else
#error "Select an ESP32 BLE board or NU40DK nRF52840."
#endif

namespace fof {
inline void restartBoard() {
#if defined(ARDUINO_ARCH_ESP32)
  ESP.restart();
#else
  NVIC_SystemReset();
#endif
}
inline void fillRandom(uint8_t* out, size_t length) {
#if defined(ARDUINO_ARCH_ESP32)
  esp_fill_random(out, length); // BLE가 켜진 뒤 호출: 하드웨어 엔트로피 사용
#else
  // 현재 NU40은 responder이므로 nonce 생성은 reader인 ESP32-C3/S3가 담당합니다.
  nRFCrypto.Random.generate(out, length);
#endif
}

// 키를 소스에 넣지 않습니다. 양쪽 USB에서 같은 KEY <64자리 hex>를
// 한 번씩 입력하면 ESP32는 NVS, NU40은 내부 LittleFS에 저장합니다.
// 두 저장소 모두 기본 설정에서는 암호화되지 않습니다. 키는 출력하지 않습니다.
class KeyStore {
 public:
  uint8_t key[kKeySize] = {};
  bool ready = false;
  void begin() {
#if defined(ARDUINO_ARCH_ESP32)
    Preferences prefs;
    if (prefs.begin("fof", true)) {
      ready = prefs.getBytesLength("pair-key") == kKeySize &&
              prefs.getBytes("pair-key", key, kKeySize) == kKeySize;
      prefs.end();
    }
#else
    fsReady_ = InternalFS.begin();
    Adafruit_LittleFS_Namespace::File file(InternalFS);
    if (fsReady_ && file.open("/fof-key", Adafruit_LittleFS_Namespace::FILE_O_READ)) {
      ready = file.size() == kKeySize && file.read(key, kKeySize) == kKeySize;
      file.close();
    }
#endif
    Serial.println(ready ? "KEY: stored" : "KEY: missing; enter KEY <64 hex digits>");
  }
  using CommandHandler = bool (*)(const char*);
  using Capacity = bool (*)();
  void poll(CommandHandler handler = nullptr, Capacity capacity = nullptr) {
    input_.poll(Serial, [&](const char* line) {
      if (handler && handler(line)) return;
      if (!line) { Serial.println("CONFIG: command too long"); return; }
      length_ = strlen(line);
      memcpy(line_, line, length_ + 1);
      processLine();
      memset(line_, 0, sizeof(line_));
    }, [&]() { return !capacity || capacity(); });
  }
 private:
  SerialLine input_;
  char line_[80] = {};
  size_t length_ = 0;
#if defined(ARDUINO_ARCH_NRF52)
  bool fsReady_ = false;
#endif
  static int hexValue(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
  }
  bool save(const uint8_t* candidate) {
#if defined(ARDUINO_ARCH_ESP32)
    Preferences prefs;
    if (!prefs.begin("fof", false)) return false;
    const bool ok = prefs.putBytes("pair-key", candidate, kKeySize) == kKeySize;
    prefs.end();
    return ok;
#else
    if (!fsReady_) return false;
    // 임시 파일에 완전히 기록한 뒤 rename하여 저장 중 전원 차단에 대비합니다.
    Adafruit_LittleFS_Namespace::File file(InternalFS);
    if (!file.open("/fof-key.tmp", Adafruit_LittleFS_Namespace::FILE_O_WRITE)) return false;
    const bool ok = file.truncate(0) && file.write(candidate, kKeySize) == kKeySize;
    file.flush();
    file.close();
    return ok && InternalFS.rename("/fof-key.tmp", "/fof-key");
#endif
  }
  void processLine() {
    if (length_ == 0) return;
    // STATUS는 키를 노출하지 않고 연결된 USB 포트와 역할을 확인하는 용도입니다.
    if (length_ == 6 && memcmp(line_, "STATUS", 6) == 0) {
      Serial.println(ready ? "STATUS: key stored" : "STATUS: key missing");
      return;
    }
    if (length_ != 68 || memcmp(line_, "KEY ", 4) != 0) {
      Serial.println("CONFIG: use KEY <64 hex digits> then newline");
      return;
    }
    uint8_t candidate[kKeySize] = {};
    for (size_t i = 0; i < kKeySize; ++i) {
      const int hi = hexValue(line_[4 + i * 2]), lo = hexValue(line_[5 + i * 2]);
      if (hi < 0 || lo < 0) {
        memset(candidate, 0, sizeof(candidate));
        Serial.println("CONFIG: invalid hex");
        return;
      }
      candidate[i] = static_cast<uint8_t>((hi << 4) | lo);
    }
    const bool saved = save(candidate);
    memset(candidate, 0, sizeof(candidate));
    memset(line_, 0, sizeof(line_));
    if (!saved) { Serial.println("CONFIG: key write failed"); return; }
    Serial.println("CONFIG: key saved; restarting");
    Serial.flush();
    delay(150);
    restartBoard();
  }
};

// 연결/페어링 없이 양쪽 모두 BLE 5 확장 광고를 브로드캐스트합니다.
// 38바이트 인증 메시지를 줄이거나 나누지 않습니다. scan은 passive라 응답 요청도 없습니다.
// 기본 채널에서 확장 광고를 발견하고 스택이 보조 채널의 전체 데이터를 수신합니다.
class Radio {
 public:
  bool begin(uint8_t expectedType) {
    expectedType_ = expectedType;
    queue_ = xQueueCreate(32, sizeof(ReceivedPacket));
    if (!queue_) return false;
    instance() = this;
#if defined(ARDUINO_ARCH_ESP32)
    if (!NimBLEDevice::init("FOF demo")) return false;
    advertising_ = NimBLEDevice::getAdvertising();
    // Coded PHY는 사용하지 않고 양쪽 모두 1M PHY로 광고/스캔합니다.
    // 고수준 스캐너의 주변 장치 목록 대신 원시 보고를 고정 큐에 복사합니다.
    ble_gap_ext_disc_params params = {};
    params.passive = 1;
    params.itvl = params.window = 160; // 100ms 연속 스캔
    if (ble_gap_ext_disc(BLE_OWN_ADDR_PUBLIC, 0, 0, 0, 0, 0,
                         &params, nullptr, scanGap, this) != 0) return false;
#else
    if (!Bluefruit.begin(0, 1)) { Serial.println("DIAG RADIO_ERROR: Bluefruit init failed"); return false; }
    if (!nRFCrypto.begin()) { Serial.println("DIAG RADIO_ERROR: CryptoCell init failed"); return false; }
    Bluefruit.autoConnLed(false);
    // 이 코어의 Bluefruit.Scanner/Advertising 래퍼는 31바이트 배열을 사용합니다.
    // SoftDevice 원시 API와 수명이 보장되는 255바이트 수신 버퍼를 사용합니다.
    // Bluefruit.Scanner.start()는 호출하지 않아 두 스캐너가 중복 동작하지 않습니다.
    Bluefruit.setEventCallback(softDeviceEvent);
    scanBuffer_.p_data = scanData_;
    scanBuffer_.len = sizeof(scanData_);
    ble_gap_scan_params_t params = {};
    params.extended = 1;
    params.active = 0;
    params.filter_policy = BLE_GAP_SCAN_FP_ACCEPT_ALL;
    params.scan_phys = BLE_GAP_PHY_1MBPS;
    // 0.625ms 단위. 짧은 질의의 수신 기회를 늘리도록 채널 순환 주기를 짧게 유지합니다.
    params.interval = params.window = kScanIntervalUnits;
    const uint32_t scanResult = sd_ble_gap_scan_start(&params, &scanBuffer_);
    if (scanResult != NRF_SUCCESS) {
      Serial.printf("DIAG RADIO_ERROR: extended scan init failed: %lu\n", static_cast<unsigned long>(scanResult));
      return false;
    }
#endif
    Serial.println("RADIO: BLE5 extended broadcast; whole 38-byte message; no peer MAC");
    return true;
  }
  int send(const uint8_t* bytes) {
    if (transmitting_) {
      if (memcmp(txPacket_, bytes, kPacketSize) == 0) return kSendBusy;
      stopTransmit(); // 새 난수의 메시지를 이전 반복 광고보다 우선합니다.
    }
    memcpy(txPacket_, bytes, kPacketSize);
    makeAdvertisement(bytes, txData_);
    if (!startAdvertising()) return kSendError;
    txStartedAt_ = millis();
    transmitting_ = true;
    return kSendOk; // 송신 시작 성공이며, 상대 수신/인증 성공은 아닙니다.
  }
  void poll() {
    // 동일한 전체 메시지를 50ms 광고 간격으로 반복하여 손실을 보완합니다.
    // reader 질의와 responder 응답 모두 최대 600ms 후 중단합니다.
    const uint32_t duration = 600;
    if (transmitting_ && elapsed(millis(), txStartedAt_) >= duration) stopTransmit();
  }
  void stopTransmit() {
    if (transmitting_) stopAdvertising();
    transmitting_ = false;
  }
  bool receive(ReceivedPacket& packet) {
    return xQueueReceive(queue_, &packet, 0) == pdTRUE;
  }
  // 명시적 STATUS에만 포함합니다. 주변 광고마다 USB 로그를 만들지 않습니다.
  void diagnosticsJson(char* out, size_t size) const {
#if defined(ARDUINO_ARCH_NRF52)
    snprintf(out, size,
      "{\"scan_interval_us\":%lu,\"reports\":%lu,\"extended\":%lu,\"missed\":%lu,\"truncated\":%lu,"
      "\"matched\":%lu,\"resume_errors\":%lu,\"last_resume_error\":%lu,"
      "\"peer_reports\":%lu,\"peer_complete\":%lu,\"peer_missed\":%lu,\"peer_truncated\":%lu,"
      "\"peer_rssi\":%ld,\"peer_last_bytes\":%lu,\"max_handler_us\":%lu}",
      (unsigned long)kScanIntervalUnits * 625, (unsigned long)reports_.load(), (unsigned long)extended_.load(),
      (unsigned long)missed_.load(), (unsigned long)truncated_.load(),
      (unsigned long)matched_.load(), (unsigned long)resumeErrors_.load(), (unsigned long)lastResumeError_.load(),
      (unsigned long)peerReports_.load(), (unsigned long)peerComplete_.load(),
      (unsigned long)peerMissed_.load(), (unsigned long)peerTruncated_.load(),
      (long)peerRssi_.load(), (unsigned long)peerBytes_.load(), (unsigned long)maxHandlerUs_.load());
#else
    snprintf(out, size, "{}");
#endif
  }
  bool takeRadioError() { return radioError_.exchange(false); }
  bool takeQueueOverflow() { return overflow_.exchange(false); }
 private:
  QueueHandle_t queue_ = nullptr;
  uint8_t expectedType_ = 0;
  std::atomic<bool> radioError_{false}, overflow_{false};
  uint8_t txPacket_[kPacketSize] = {};
  uint8_t txData_[kAdvertisementSize] = {}; // SoftDevice가 송신 중 참조하므로 멤버로 유지
  uint32_t txStartedAt_ = 0;
  bool transmitting_ = false;
  static Radio*& instance() { static Radio* self = nullptr; return self; }
  void enqueue(const uint8_t* data, size_t length) {
    const uint8_t* body = findPacket(data, length, expectedType_);
    if (!body) return;
    ReceivedPacket packet;
    memcpy(packet.bytes, body, kPacketSize);
    packet.length = kPacketSize;
    packet.arrivedAt = millis();
    // 콜백에서는 전체 메시지 복사만 합니다. HMAC은 loop()에서 검증합니다.
    if (xQueueSend(queue_, &packet, 0) != pdTRUE) overflow_ = true;
  }
#if defined(ARDUINO_ARCH_ESP32)
  NimBLEExtAdvertising* advertising_ = nullptr;
  static int scanGap(ble_gap_event* event, void* context) {
    Radio* self = static_cast<Radio*>(context);
    if (event->type == BLE_GAP_EVENT_EXT_DISC) {
      const auto& report = event->ext_disc;
      if (report.data_status == BLE_GAP_EXT_ADV_DATA_STATUS_COMPLETE)
        self->enqueue(report.data, report.length_data);
    } else if (event->type == BLE_GAP_EVENT_DISC_COMPLETE) self->radioError_ = true;
    return 0;
  }
#else
  static constexpr uint16_t kScanIntervalUnits = 16; // 10ms, window == interval
  uint8_t advHandle_ = BLE_GAP_ADV_SET_HANDLE_NOT_SET;
  uint8_t scanData_[BLE_GAP_SCAN_BUFFER_EXTENDED_MIN] = {};
  ble_data_t scanBuffer_ = {};
  std::atomic<uint32_t> reports_{0}, extended_{0}, missed_{0}, truncated_{0}, matched_{0};
  std::atomic<uint32_t> resumeErrors_{0}, lastResumeError_{0}, maxHandlerUs_{0};
  std::atomic<uint32_t> peerReports_{0}, peerComplete_{0}, peerMissed_{0}, peerTruncated_{0}, peerBytes_{0};
  std::atomic<int32_t> peerRssi_{0};
  uint8_t peerAddress_[6] = {};
  bool havePeer_ = false; // 이 주소는 진단 카운터에만 사용하며 수신/인증을 제한하지 않습니다.
  static void softDeviceEvent(ble_evt_t* event) {
    Radio* self = instance();
    if (!self || event->header.evt_id != BLE_GAP_EVT_ADV_REPORT) return;
    const uint32_t started = micros();
    const auto& report = event->evt.gap_evt.params.adv_report;
    const auto status = report.type.status;
    ++self->reports_;
    if (report.type.extended_pdu) ++self->extended_;
    if (status == BLE_GAP_ADV_DATA_STATUS_INCOMPLETE_MISSED) ++self->missed_;
    if (status == BLE_GAP_ADV_DATA_STATUS_INCOMPLETE_TRUNCATED) ++self->truncated_;
    if (status == BLE_GAP_ADV_DATA_STATUS_COMPLETE &&
        findPacket(report.data.p_data, report.data.len, self->expectedType_)) {
      ++self->matched_;
      if (!self->havePeer_) {
        memcpy(self->peerAddress_, report.peer_addr.addr, 6);
        self->havePeer_ = true;
      }
      self->enqueue(report.data.p_data, report.data.len);
    }
    if (self->havePeer_ && !memcmp(self->peerAddress_, report.peer_addr.addr, 6)) {
      ++self->peerReports_;
      if (status == BLE_GAP_ADV_DATA_STATUS_COMPLETE) ++self->peerComplete_;
      if (status == BLE_GAP_ADV_DATA_STATUS_INCOMPLETE_MISSED) ++self->peerMissed_;
      if (status == BLE_GAP_ADV_DATA_STATUS_INCOMPLETE_TRUNCATED) ++self->peerTruncated_;
      self->peerRssi_ = report.rssi;
      self->peerBytes_ = report.data.len;
    }
    if (status != BLE_GAP_ADV_DATA_STATUS_INCOMPLETE_MORE_DATA) {
      const uint32_t result = sd_ble_gap_scan_start(nullptr, &self->scanBuffer_);
      if (result != NRF_SUCCESS) {
        ++self->resumeErrors_; self->lastResumeError_ = result; self->radioError_ = true;
      }
    }
    const uint32_t duration = micros() - started;
    if (duration > self->maxHandlerUs_) self->maxHandlerUs_ = duration;
  }
#endif
  bool startAdvertising() {
#if defined(ARDUINO_ARCH_ESP32)
    NimBLEExtAdvertisement advertisement;
    advertisement.setLegacyAdvertising(false);
    advertisement.setConnectable(false);
    advertisement.setScannable(false);
    advertisement.setPrimaryPhy(BLE_HCI_LE_PHY_1M);
    advertisement.setSecondaryPhy(BLE_HCI_LE_PHY_1M);
    advertisement.setMinInterval(80); // 80 * 0.625ms = 50ms
    advertisement.setMaxInterval(80);
    advertisement.setTxPower(0);
    return advertisement.setData(txData_, sizeof(txData_)) &&
           advertising_->setInstanceData(0, advertisement) && advertising_->start(0);
#else
    ble_gap_adv_params_t params = {};
    params.properties.type = BLE_GAP_ADV_TYPE_EXTENDED_NONCONNECTABLE_NONSCANNABLE_UNDIRECTED;
    params.interval = 80;
    params.primary_phy = params.secondary_phy = BLE_GAP_PHY_1MBPS;
    params.filter_policy = BLE_GAP_ADV_FP_ANY;
    ble_gap_adv_data_t data = {};
    data.adv_data.p_data = txData_;
    data.adv_data.len = sizeof(txData_);
    return sd_ble_gap_adv_set_configure(&advHandle_, &data, &params) == NRF_SUCCESS &&
           sd_ble_gap_tx_power_set(BLE_GAP_TX_POWER_ROLE_ADV, advHandle_, 0) == NRF_SUCCESS &&
           sd_ble_gap_adv_start(advHandle_, BLE_CONN_CFG_TAG_DEFAULT) == NRF_SUCCESS;
#endif
  }
  void stopAdvertising() {
#if defined(ARDUINO_ARCH_ESP32)
    if (!advertising_->stop(0)) radioError_ = true;
#else
    if (sd_ble_gap_adv_stop(advHandle_) != NRF_SUCCESS) radioError_ = true;
#endif
  }
};
} // namespace fof
