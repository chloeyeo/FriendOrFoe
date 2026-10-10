/* NU40 responder: RF는 인증된 질의에만 응답하고 USB는 이벤트만 전송합니다. */
#include <FofBoard.h>
#include <FofEventLog.h>
#include "config.h"

fof::KeyStore keys;
fof::Radio radio;
fof::ReplyCache cache;
bool ready = false;
char bootId[17] = {};
fof::Lines<16, 768> controls;
fof::Lines<16, 640> diagnostics;
uint32_t droppedPacketLogs = 0;

void hexBytes(const uint8_t* bytes, size_t length, char* out) {
  constexpr char digits[] = "0123456789abcdef";
  for (size_t i = 0; i < length; ++i) {
    out[2*i] = digits[bytes[i] >> 4]; out[2*i+1] = digits[bytes[i] & 15];
  }
  out[2*length] = 0;
}
void flushLogs() {
  if (diagnostics.offset) diagnostics.drain();
  else if (controls.count) controls.drain();
  else if (droppedPacketLogs) {
    char line[128];
    snprintf(line, sizeof(line), "{\"event\":\"DIAG\",\"role\":\"drone\",\"dropped_packet_logs\":%lu}\n",
             static_cast<unsigned long>(droppedPacketLogs));
    if (controls.push(line)) droppedPacketLogs = 0;
  } else diagnostics.drain();
}
bool commandCapacity() { return controls.count <= 12 && !controls.offset && !diagnostics.offset; }
void status(const char* id, const char* reason = "REQUESTED") {
  char metrics[420], line[768];
  radio.diagnosticsJson(metrics, sizeof(metrics));
  snprintf(line, sizeof(line),
    "{\"event\":\"STATUS\",\"role\":\"drone\",\"interface_version\":2,\"boot_id\":\"%s\","
    "\"request_id\":\"%s\",\"reason\":\"%s\",\"key_configured\":%s,\"radio_ready\":%s,"
    "\"state\":\"%s\",\"reader_id\":%u,\"drone_id\":%u,\"radio_diag\":%s}\n", bootId, id, reason,
    keys.ready ? "true" : "false", ready ? "true" : "false",
    !ready ? "RADIO_ERROR" : keys.ready ? "LISTENING" : "KEY_NOT_CONFIGURED", READER_ID, DRONE_ID, metrics);
  controls.push(line);
}
void logPacket(const char* event, const fof::ReceivedPacket& packet, const char* result) {
  char raw[77], nonce[33] = {}, tag[33] = {}, fields[240] = {}, line[640];
  const size_t captured = packet.length < 0 ? 0 : packet.length > 38 ? 38 : packet.length;
  hexBytes(packet.bytes, captured, raw);
  if (packet.length == 38) {
    hexBytes(packet.bytes + fof::kNonceOffset, fof::kNonceSize, nonce);
    hexBytes(packet.bytes + fof::kBodySize, fof::kTagSize, tag);
    snprintf(fields, sizeof(fields),
      ",\"version\":%u,\"type\":%u,\"reader_id\":%u,\"drone_id\":%u,\"nonce\":\"%s\",\"tag\":\"%s\"",
      packet.bytes[0], packet.bytes[1], fof::readU16(packet.bytes + 2), fof::readU16(packet.bytes + 4), nonce, tag);
  }
  snprintf(line, sizeof(line),
    "{\"event\":\"%s\",\"role\":\"drone\",\"at_ms\":%lu,\"bytes\":%d%s,\"raw\":\"%s\",\"result\":\"%s\"}\n",
    event, static_cast<unsigned long>(packet.arrivedAt), packet.length, fields, raw, result);
  if (!diagnostics.push(line)) ++droppedPacketLogs;
}
void diagnostic(const char* reason) {
  char line[160];
  snprintf(line, sizeof(line), "{\"event\":\"DIAG\",\"role\":\"drone\",\"reason\":\"%s\",\"at_ms\":%lu}\n",
           reason, static_cast<unsigned long>(millis()));
  if (!diagnostics.push(line)) ++droppedPacketLogs;
}
bool validId(const char* id) {
  const size_t length = strlen(id);
  if (!length || length > 32) return false;
  for (size_t i = 0; i < length; ++i) {
    const char c = id[i];
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
          (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
  }
  return true;
}
bool command(const char* line) {
  if (!line) { diagnostic("COMMAND_TOO_LONG"); return true; }
  if (!strcmp(line, "STATUS")) { status(""); return true; }
  if (!strncmp(line, "STATUS ", 7)) {
    if (validId(line + 7)) status(line + 7); else diagnostic("INVALID_REQUEST_ID");
    return true;
  }
  if (!strcmp(line, "HELP")) {
    controls.push("{\"event\":\"HELP\",\"role\":\"drone\",\"commands\":[\"STATUS [request_id]\",\"HELP\",\"KEY <64 hex digits>\"]}\n");
    return true;
  }
  if (!strncmp(line, "KEY ", 4)) return false;
  diagnostic("INVALID_COMMAND"); return true;
}
void answerChallenge(const fof::ReceivedPacket& packet) {
  if (!keys.ready) { logPacket("RX_CHALLENGE", packet, "KEY_NOT_CONFIGURED"); return; }
  const fof::Reason result = fof::verifyPacket(packet.bytes, packet.length,
      fof::kChallenge, READER_ID, DRONE_ID, keys.key);
  if (result != fof::Reason::None) {
    logPacket("RX_CHALLENGE", packet, fof::reasonName(result)); return;
  }
  const uint32_t now = millis();
  if (fof::elapsed(now, packet.arrivedAt) >= fof::ReplyCache::kReplyWindowMs) {
    logPacket("RX_CHALLENGE", packet, "IGNORED_EXPIRED_IN_QUEUE"); return;
  }
  cache.expire(now);
  const uint8_t* nonce = packet.bytes + fof::kNonceOffset;
  fof::ReplyCache::Entry* entry = cache.find(nonce);
  const bool duplicate = entry != nullptr;
  if (duplicate) {
    if (!fof::ReplyCache::mayReply(*entry, now)) {
      logPacket("RX_CHALLENGE", packet, "IGNORED_DUPLICATE_LIMIT"); return;
    }
  } else {
    entry = cache.freeEntry();
    if (!entry) { logPacket("RX_CHALLENGE", packet, "IGNORED_CACHE_FULL"); return; }
    if (!fof::makePacket(fof::kResponse, READER_ID, DRONE_ID, nonce, keys.key, entry->response)) {
      logPacket("RX_CHALLENGE", packet, "HMAC_ERROR"); return;
    }
    memcpy(entry->nonce, nonce, fof::kNonceSize);
    entry->createdAt = packet.arrivedAt; entry->sends = 0; entry->used = true;
  }
  logPacket("RX_CHALLENGE", packet, duplicate ? "AUTHENTICATED_DUPLICATE" : "AUTHENTICATED");
  const int sent = radio.send(entry->response);
  if (sent == fof::kSendOk) ++entry->sends;
  fof::ReceivedPacket response;
  memcpy(response.bytes, entry->response, fof::kPacketSize);
  response.length = fof::kPacketSize; response.arrivedAt = millis();
  logPacket("TX_RESPONSE", response, sent == fof::kSendOk ? "BROADCAST_STARTED" :
            sent == fof::kSendBusy ? "ALREADY_ADVERTISING" : "RADIO_ERROR");
}
void setup() {
  Serial.begin(115200);
  delay(1000);
  keys.begin();
  // 키가 없더라도 무선 초기화/수신과 USB 진단은 계속합니다.
  ready = radio.begin(fof::kChallenge);
  uint8_t randomBoot[8]; fof::fillRandom(randomBoot, sizeof(randomBoot)); hexBytes(randomBoot, 8, bootId);
  status("", "BOOT");
}
void loop() {
  flushLogs(); keys.poll(command, commandCapacity);
  if (ready) {
    radio.poll();
    fof::ReceivedPacket packet;
    for (int i = 0; i < 16 && radio.receive(packet); ++i) answerChallenge(packet);
    if (radio.takeRadioError()) diagnostic("RADIO_ERROR");
    if (radio.takeQueueOverflow()) diagnostic("RECEIVE_QUEUE_OVERFLOW");
  }
  flushLogs(); delay(1);
}
