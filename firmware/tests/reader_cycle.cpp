#include <assert.h>
#include "../reader/reader.ino"

void pump(unsigned n = 30) { for (unsigned i = 0; i < n; ++i) loop(); }
void send(const std::string& line) { Serial.input += line; pump(); }
void query(const char* id) { send(std::string("QUERY ") + id + "\n"); }
fof::ReceivedPacket responseForCurrent() {
  fof::ReceivedPacket packet; packet.length = fof::kPacketSize; packet.arrivedAt = millis();
  assert(fof::makePacket(fof::kResponse, READER_ID, DRONE_ID, currentNonce, keys.key, packet.bytes));
  return packet;
}
int main(int argc, char** argv) {
  const bool noRadio = argc > 1 && !strcmp(argv[1], "--no-radio");
  const bool noKey = argc > 1 && !strcmp(argv[1], "--no-key");
  if (noRadio) radio.beginResult = false;
  if (noKey) keys.ready = false;
  setup(); pump();
  if (noRadio || noKey) {
    query("unavailable");
    assert(radio.outgoing.empty() && !pending && mode == ReaderMode::Idle);
    assert(Serial.output.find(noRadio ? "RADIO_ERROR" : "KEY_NOT_CONFIGURED") != std::string::npos);
    return 0;
  }
  assert(ready && mode == ReaderMode::Idle && ledChanges.empty());
  fakeNow += 10000; send("STATUS\r\nHELP\nSTATUS test-id\n");
  assert(radio.outgoing.empty());
  Serial.connected = false; send("STATUS reconnect_probe\n");
  assert(Serial.connected && controls.count == 0 && radio.outgoing.empty());
  send("QUERY first"); assert(radio.outgoing.empty());
  send("\r\n"); assert(pending && radio.outgoing.size() == 1);
  query("first"); query("busy"); assert(radio.outgoing.size() == 1);
  // 1초가 지나도 대기를 해제하지 않습니다. 3초까지 추가 요청은 BUSY입니다.
  fakeNow = queryStartedAt + 1500; query("still-busy"); assert(pending && radio.outgoing.size() == 1);
  fakeNow = queryStartedAt + 2999; loop(); assert(pending);
  fakeNow = queryStartedAt + 3000; pump();
  assert(!pending && mode == ReaderMode::Idle && lastResult.reason == fof::Reason::NoResponse);
  assert(!lastResult.received);
  query("first"); assert(radio.outgoing.size() == 1);
  query("second"); assert(radio.outgoing.size() == 2 && pending);
  assert(radio.outgoing[0] != radio.outgoing[1]);
  query("first"); assert(radio.outgoing.size() == 2);
  auto bad = responseForCurrent(); bad.bytes[fof::kBodySize] ^= 1;
  radio.incoming.push_back(bad); pump(); assert(responseReceived && pending);
  auto malformed = responseForCurrent(); malformed.length = 2;
  radio.incoming.push_back(malformed); pump();
  auto old = responseForCurrent(); old.bytes[fof::kNonceOffset] ^= 1;
  radio.incoming.push_back(old); pump();
  assert(observedReason == fof::Reason::AuthFailed);
  // 정상 응답은 즉시 확정하지만 질의 시작 후 3초까지 잠금을 유지합니다.
  fakeNow = queryStartedAt + 1600;
  auto valid = responseForCurrent(); radio.incoming.push_back(valid); loop();
  assert(mode == ReaderMode::Cooldown && !pending && lastResult.reason == fof::Reason::None);
  assert(lastResult.responseMs == 1600 && ledChanges.empty());
  query("second"); assert(radio.outgoing.size() == 2);
  radio.incoming.push_back(valid); pump(); // 중복 응답은 재판정하지 않습니다.
  query("cooldown-busy"); assert(radio.outgoing.size() == 2 && mode == ReaderMode::Cooldown);
  send("STATUS cooldown-status\n");
  assert(Serial.output.find("\"state\":\"COOLDOWN\",\"active_request_id\":\"second\"") != std::string::npos);
  fakeNow = queryStartedAt + 2999; command("QUERY boundary-busy");
  assert(radio.outgoing.size() == 2 && mode == ReaderMode::Cooldown);
  fakeNow = queryStartedAt + 3000;
  query("third"); assert(pending && radio.outgoing.size() == 3);
  auto late = responseForCurrent(); late.arrivedAt = queryStartedAt + RESPONSE_TIMEOUT_MS;
  fakeNow = late.arrivedAt; radio.incoming.push_back(late); pump();
  assert(mode == ReaderMode::Idle && lastResult.reason == fof::Reason::NoResponse);
  radio.incoming.push_back(late); pump();
  query("auth-fail"); bad = responseForCurrent(); bad.bytes[fof::kBodySize] ^= 1;
  radio.incoming.push_back(bad); pump(); fakeNow = queryStartedAt + RESPONSE_TIMEOUT_MS; pump();
  assert(lastResult.received && lastResult.reason == fof::Reason::AuthFailed);
  keys.ready = false; query("no-key"); assert(!pending); keys.ready = true;
  ready = false; query("no-radio"); assert(!pending); ready = true;
  radio.sendResult = ESP_FAIL; query("send-fail");
  assert(!pending && lastResult.reason == fof::Reason::RadioError); radio.sendResult = ESP_OK;
  assert(mode == ReaderMode::Cooldown);
  const auto before = radio.outgoing.size();
  send("QUERY bad!\nQUERY \nQUERY " + std::string(33, 'a') + "\n" + std::string(90, 'x') + "\n");
  assert(radio.outgoing.size() == before);
  fakeNow = queryStartedAt + MIN_QUERY_INTERVAL_MS; pump();
  Serial.space = 0; query("backpressure"); fakeNow = queryStartedAt + RESPONSE_TIMEOUT_MS; pump();
  assert(!pending && lastResult.reason == fof::Reason::NoResponse && controls.count > 0);
  Serial.space = 17; pump(300); assert(!controls.count && !diagnostics.count);
  Serial.space = 4096;
  fakeNow = UINT32_MAX - 1500; query("wrap");
  fakeNow = queryStartedAt + RESPONSE_TIMEOUT_MS; pump(); assert(mode == ReaderMode::Idle);
  fakeNow = UINT32_MAX - 1500; query("wrap-success");
  fakeNow = queryStartedAt + 100; auto wrapped = responseForCurrent();
  radio.incoming.push_back(wrapped); pump(); assert(mode == ReaderMode::Cooldown);
  fakeNow = queryStartedAt + 2999; command("QUERY wrap-busy"); assert(mode == ReaderMode::Cooldown);
  fakeNow = queryStartedAt + 3000; pump(); assert(mode == ReaderMode::Idle);
  query("delayed-loop"); auto onTime = responseForCurrent();
  onTime.arrivedAt = queryStartedAt + 2999; fakeNow = queryStartedAt + 3001;
  radio.incoming.push_back(onTime); loop(); assert(mode == ReaderMode::Idle && !pending);
  pump(); fakeNow += 10000; pump();
  assert(ledChanges.empty()); // 부팅부터 종료까지 LED 점등/소등 호출이 없습니다.
  if (argc > 1 && strcmp(argv[1], "--serial") == 0) printf("%s", Serial.output.c_str());
}
