/* 명령 기반 벤치 데모 reader. 부팅/STATUS는 전파를 송신하지 않습니다.
 * USB: 115200 baud, QUERY <id> / STATUS [id] / HELP / KEY <64 hex>.
 * BLE 38바이트 규격은 그대로이며, 하나의 질의를 최대 600ms 반복 광고합니다.
 */
#include <FofBoard.h>
#include <FofEventLog.h>
#include "config.h"

fof::KeyStore keys;
fof::Radio radio;
bool ready = false;
enum class ReaderMode { Idle, Querying, Cooldown };
ReaderMode mode = ReaderMode::Idle;
bool pending = false, haveStarted = false, responseReceived = false;
uint8_t currentNonce[fof::kNonceSize] = {};
uint32_t queryStartedAt = 0, queryNumber = 0, responseMs = 0;
fof::Reason observedReason = fof::Reason::NoResponse;
char currentId[33] = {}, bootId[17] = {};

// USB가 느려도 RF/응답 기한를 멈추지 않습니다. 제어 큐가 차면 입력 처리를 잠시
// 멈춰 완료 판정을 위한 여유를 확보합니다. 진단 큐만 손실을 허용합니다.
fof::Lines<16, 768> controls;
fof::Lines<8, 640> diagnostics;
uint32_t droppedPacketLogs = 0;
void flushLogs() {
  // 이미 일부 출력한 JSON 줄은 끝낸 뒤 우선순위를 전환합니다.
  if (diagnostics.offset) diagnostics.drain();
  else if (controls.count) controls.drain();
  else if (droppedPacketLogs) {
    char line[96];
    snprintf(line, sizeof(line), "{\"event\":\"DIAG\",\"dropped_packet_logs\":%lu}\n",
             static_cast<unsigned long>(droppedPacketLogs));
    if (controls.push(line)) droppedPacketLogs = 0;
  } else diagnostics.drain();
}
bool commandCapacity() { return controls.count <= 9 && !controls.offset && !diagnostics.offset; }
const char* modeName() {
  if (mode == ReaderMode::Idle) return "IDLE";
  return mode == ReaderMode::Querying ? "QUERYING" : "COOLDOWN";
}
void bytesToHex(const uint8_t* bytes, size_t length, char* out) {
  constexpr char digits[] = "0123456789abcdef";
  for (size_t i = 0; i < length; ++i) {
    out[i * 2] = digits[bytes[i] >> 4]; out[i * 2 + 1] = digits[bytes[i] & 15];
  }
  out[length * 2] = 0;
}
struct Completed {
  bool valid = false, received = false;
  char id[33] = {};
  uint32_t query = 0, elapsedMs = 0;
  fof::Reason reason = fof::Reason::NoResponse;
  void json(char* out, size_t size, bool replay = false) const {
    if (!valid) { snprintf(out, size, "null"); return; }
    snprintf(out, size,
      "{\"event\":\"RESULT\",\"request_id\":\"%s\",\"query\":%lu,"
      "\"response_received\":%s,\"verdict\":\"%s\",\"reason\":\"%s\","
      "\"response_ms\":%s,\"elapsed_ms\":%lu,\"replay\":%s}",
      id, static_cast<unsigned long>(query), received ? "true" : "false",
      reason == fof::Reason::None ? "FRIEND" : "UNKNOWN", fof::reasonName(reason),
      responseText(), static_cast<unsigned long>(elapsedMs), replay ? "true" : "false");
  }
  const char* responseText() const {
    static char value[16];
    if (!received) return "null";
    snprintf(value, sizeof(value), "%lu", static_cast<unsigned long>(responseMs));
    return value;
  }
  uint32_t responseMs = 0;
} lastResult;
void emitResult(bool replay = false) {
  char line[512]; lastResult.json(line, sizeof(line) - 2, replay); strcat(line, "\n"); controls.push(line);
}
void stateEvent(const char* reason = "CHANGED") {
  char line[256];
  snprintf(line, sizeof(line),
    "{\"event\":\"STATE\",\"boot_id\":\"%s\",\"request_id\":\"%s\",\"state\":\"%s\",\"reason\":\"%s\"}\n",
    bootId, currentId, modeName(), reason); controls.push(line);
}
void ack(const char* id, bool accepted, const char* reason) {
  char line[256];
  snprintf(line, sizeof(line),
    "{\"event\":\"ACK\",\"request_id\":\"%s\",\"accepted\":%s,\"reason\":\"%s\",\"state\":\"%s\"}\n",
    id, accepted ? "true" : "false", reason, modeName()); controls.push(line);
}
void status(const char* id) {
  char result[400], line[768]; lastResult.json(result, sizeof(result), true);
  snprintf(line, sizeof(line),
    "{\"event\":\"STATUS\",\"request_id\":\"%s\",\"role\":\"reader\",\"interface_version\":2,"
    "\"boot_id\":\"%s\",\"manual_query\":true,\"response_timeout_ms\":%lu,\"key_configured\":%s,\"radio_ready\":%s,"
    "\"state\":\"%s\",\"active_request_id\":\"%s\",\"last_result\":%s}\n",
    id, bootId, static_cast<unsigned long>(RESPONSE_TIMEOUT_MS),
    keys.ready ? "true" : "false", ready ? "true" : "false", modeName(),
    mode != ReaderMode::Idle ? currentId : "", result); controls.push(line);
}
void logPacket(const char* event, const uint8_t* bytes, int length, uint32_t at, const char* result) {
  char raw[77], nonce[33] = {}, tag[33] = {}, fields[240] = {}, line[640];
  const size_t captured = length < 0 ? 0 : length > 38 ? 38 : length;
  bytesToHex(bytes, captured, raw);
  if (length == 38) {
    bytesToHex(bytes + fof::kNonceOffset, fof::kNonceSize, nonce);
    bytesToHex(bytes + fof::kBodySize, fof::kTagSize, tag);
    snprintf(fields, sizeof(fields),
      ",\"version\":%u,\"type\":%u,\"reader_id\":%u,\"drone_id\":%u,\"nonce\":\"%s\",\"tag\":\"%s\"",
      bytes[0], bytes[1], fof::readU16(bytes + 2), fof::readU16(bytes + 4), nonce, tag);
  }
  snprintf(line, sizeof(line),
    "{\"event\":\"%s\",\"request_id\":\"%s\",\"query\":%lu,\"at_ms\":%lu,\"elapsed_ms\":%lu,"
    "\"bytes\":%d%s,\"raw\":\"%s\",\"result\":\"%s\"}\n", event, currentId,
    static_cast<unsigned long>(queryNumber), static_cast<unsigned long>(at),
    static_cast<unsigned long>(haveStarted ? fof::elapsed(at, queryStartedAt) : 0), length, fields, raw, result);
  if (!diagnostics.push(line)) ++droppedPacketLogs;
}
void rememberReason(fof::Reason reason) {
  if (reason == fof::Reason::RadioError ||
      (reason == fof::Reason::AuthFailed && observedReason != fof::Reason::RadioError) ||
      (reason == fof::Reason::InvalidMessage && observedReason == fof::Reason::NoResponse)) observedReason = reason;
}
void finish(fof::Reason reason, uint32_t at) {
  if (!pending) return; // nonce 하나당 최종 판정은 한 번만 확정합니다.
  radio.stopTransmit(); pending = false;
  lastResult.valid = true; strcpy(lastResult.id, currentId);
  lastResult.query = queryNumber; lastResult.received = responseReceived;
  lastResult.reason = reason; lastResult.elapsedMs = fof::elapsed(at, queryStartedAt);
  lastResult.responseMs = responseMs;
  emitResult();
  // 결과는 즉시 알리되, 다음 질의는 질의 시작부터 3초가 지난 뒤 허용합니다.
  // 수신 시각과 루프 처리 시각이 다를 수 있으므로 잠금 해제는 현재 시각 기준입니다.
  mode = fof::elapsed(millis(), queryStartedAt) >= MIN_QUERY_INTERVAL_MS
      ? ReaderMode::Idle : ReaderMode::Cooldown;
  stateEvent();
}
void releaseCooldown() {
  if (mode == ReaderMode::Cooldown && fof::elapsed(millis(), queryStartedAt) >= MIN_QUERY_INTERVAL_MS) {
    mode = ReaderMode::Idle;
    stateEvent();
  }
}
void beginChallenge(const char* id) {
  strcpy(currentId, id); ++queryNumber; haveStarted = true;
  fof::fillRandom(currentNonce, sizeof(currentNonce));
  queryStartedAt = millis(); pending = true; responseReceived = false; responseMs = 0;
  observedReason = fof::Reason::NoResponse; mode = ReaderMode::Querying;
  ack(id, true, "ACCEPTED"); stateEvent();
  uint8_t packet[fof::kPacketSize] = {};
  if (!fof::makePacket(fof::kChallenge, READER_ID, DRONE_ID, currentNonce, keys.key, packet)) {
    finish(fof::Reason::RadioError, millis()); return;
  }
  const int result = radio.send(packet);
  logPacket("TX_CHALLENGE", packet, sizeof(packet), queryStartedAt,
            result == fof::kSendOk ? "BROADCAST_STARTED" : "RADIO_ERROR");
  if (result != fof::kSendOk) finish(fof::Reason::RadioError, millis());
}
bool validId(const char* id) {
  const size_t n = strlen(id);
  if (n < 1 || n > 32) return false;
  for (size_t i = 0; i < n; ++i) {
    const char c = id[i];
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
          (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
  }
  return true;
}
bool command(const char* line) {
  if (!line) { ack("", false, "COMMAND_TOO_LONG"); return true; }
  if (!strcmp(line, "STATUS")) { status(""); return true; }
  if (!strncmp(line, "STATUS ", 7)) {
    if (validId(line + 7)) status(line + 7); else ack("", false, "INVALID_REQUEST_ID");
    return true;
  }
  if (!strcmp(line, "HELP")) {
    controls.push("{\"event\":\"HELP\",\"commands\":[\"QUERY <request_id>\",\"STATUS [request_id]\",\"HELP\",\"KEY <64 hex digits>\"]}\n"); return true;
  }
  if (!strncmp(line, "KEY ", 4)) {
    if (mode != ReaderMode::Idle) { ack("", false, "BUSY"); return true; }
    return false; // 기존 KeyStore만 키를 읽고 저장합니다. 절대 키를 로그에 복사하지 않습니다.
  }
  if (strncmp(line, "QUERY ", 6)) { ack("", false, "INVALID_COMMAND"); return true; }
  const char* id = line + 6;
  if (!validId(id)) { ack("", false, "INVALID_REQUEST_ID"); return true; }
  if ((haveStarted && !strcmp(id, currentId)) || (lastResult.valid && !strcmp(id, lastResult.id))) {
    ack(id, true, "DUPLICATE"); stateEvent();
    if (lastResult.valid && !strcmp(id, lastResult.id)) emitResult(true);
  } else if (mode != ReaderMode::Idle) ack(id, false, "BUSY");
  else if (!keys.ready) ack(id, false, "KEY_NOT_CONFIGURED");
  else if (!ready) ack(id, false, "RADIO_ERROR");
  else beginChallenge(id);
  return true;
}
void acceptResponse(const fof::ReceivedPacket& packet) {
  if (!pending || mode != ReaderMode::Querying) {
    const bool late = haveStarted && packet.length == 38 &&
      fof::equalBytes(packet.bytes + fof::kNonceOffset, currentNonce, fof::kNonceSize) &&
      fof::elapsed(packet.arrivedAt, queryStartedAt) >= RESPONSE_TIMEOUT_MS;
    logPacket("RX_RESPONSE", packet.bytes, packet.length, packet.arrivedAt,
              late ? "IGNORED_LATE_RESPONSE" : "IGNORED_NOT_WAITING"); return;
  }
  if (fof::elapsed(packet.arrivedAt, queryStartedAt) >= RESPONSE_TIMEOUT_MS) {
    logPacket("RX_RESPONSE", packet.bytes, packet.length, packet.arrivedAt, "IGNORED_LATE_RESPONSE"); return;
  }
  if (packet.length != 38) {
    logPacket("RX_RESPONSE", packet.bytes, packet.length, packet.arrivedAt, "INVALID_MESSAGE");
    rememberReason(fof::Reason::InvalidMessage); return;
  }
  if (!fof::equalBytes(packet.bytes + fof::kNonceOffset, currentNonce, fof::kNonceSize)) {
    logPacket("RX_RESPONSE", packet.bytes, packet.length, packet.arrivedAt, "IGNORED_NONCE_MISMATCH"); return;
  }
  // 수신 여부와 인증 성공은 별개입니다. 현재 nonce 응답만 수신으로 집계합니다.
  responseReceived = true; responseMs = fof::elapsed(packet.arrivedAt, queryStartedAt);
  const auto reason = fof::verifyPacket(packet.bytes, packet.length, fof::kResponse, READER_ID, DRONE_ID, keys.key);
  logPacket("RX_RESPONSE", packet.bytes, packet.length, packet.arrivedAt,
            reason == fof::Reason::None ? "FRIEND" : fof::reasonName(reason));
  if (reason == fof::Reason::None) finish(reason, packet.arrivedAt);
  else rememberReason(reason);
}
void setup() {
  Serial.begin(115200); // GPIO8 LED를 제어하지 않습니다.
#if defined(ARDUINO_USB_CDC_ON_BOOT) && ARDUINO_USB_CDC_ON_BOOT
#if ARDUINO_USB_MODE == 1
  Serial.setTxBufferSize(2048);
#endif
  Serial.setTxTimeoutMs(0);
#endif
  delay(1000);
  keys.begin(); ready = radio.begin(fof::kResponse);
  uint8_t randomBoot[8]; fof::fillRandom(randomBoot, sizeof(randomBoot)); bytesToHex(randomBoot, 8, bootId);
  stateEvent("BOOT"); status(""); // 수신 대기만 시작합니다. 질의는 QUERY에서만 시작합니다.
}
void loop() {
  releaseCooldown(); // 3초 경계에서 도착한 명령도 새 질의로 수락할 수 있습니다.
  flushLogs(); keys.poll(command, commandCapacity);
  if (ready) {
    radio.poll();
    fof::ReceivedPacket packet;
    for (int i = 0; i < 16 && radio.receive(packet); ++i) acceptResponse(packet);
    const bool radioError = radio.takeRadioError();
    const bool overflow = radio.takeQueueOverflow();
    if (radioError || overflow) {
      char line[128];
      snprintf(line, sizeof(line), "{\"event\":\"DIAG\",\"role\":\"reader\",\"radio_error\":%s,\"receive_queue_overflow\":%s}\n",
               radioError ? "true" : "false", overflow ? "true" : "false");
      if (!diagnostics.push(line)) ++droppedPacketLogs;
      if (pending) rememberReason(fof::Reason::RadioError);
    }
  }
  const uint32_t now = millis();
  if (pending && fof::elapsed(now, queryStartedAt) >= RESPONSE_TIMEOUT_MS) finish(observedReason, now);
  releaseCooldown();
  flushLogs(); delay(1);
}
