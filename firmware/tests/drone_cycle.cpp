#include <assert.h>
#include "../drone/drone.ino"

fof::ReceivedPacket challenge(uint8_t nonceValue, uint32_t now) {
  fof::ReceivedPacket packet;
  packet.length = fof::kPacketSize;
  uint8_t nonce[fof::kNonceSize] = {};
  nonce[0] = nonceValue;
  packet.arrivedAt = now;
  assert(fof::makePacket(fof::kChallenge, READER_ID, DRONE_ID, nonce, keys.key, packet.bytes));
  return packet;
}

int main() {
  setup();
  assert(ready);
  for (int i = 0; i < 80; ++i) loop();
  const std::string idle = Serial.output;
  fakeNow += 10000;
  for (int i = 0; i < 80; ++i) loop();
  assert(Serial.output == idle); // 대기 중 USB 출력도 없음
  assert(idle.find("\"role\":\"drone\"") != std::string::npos);
  assert(radio.outgoing.empty());  // drone은 질의 없이 송신하지 않음
  auto packet = challenge(1, millis());
  auto bad = packet;
  bad.bytes[fof::kBodySize] ^= 1;
  radio.incoming.push_back(bad);
  loop();
  assert(radio.outgoing.empty());  // 인증 실패에는 오류 응답도 없음
  radio.incoming.push_back(packet);
  loop();
  assert(radio.outgoing.size() == 1);
  assert(fof::verifyPacket(radio.outgoing.back().data(), fof::kPacketSize,
      fof::kResponse, READER_ID, DRONE_ID, keys.key) == fof::Reason::None);
  const auto initialResponse = radio.outgoing.back();
  for (int i = 0; i < 3; ++i) {
    radio.incoming.push_back(packet);
    loop();
  }
  assert(radio.outgoing.size() == 3);  // 최초 포함 최대 세 번
  for (const auto& response : radio.outgoing) assert(response == initialResponse);

  fakeNow = packet.arrivedAt + 1000;
  packet.arrivedAt = fakeNow;
  radio.incoming.push_back(packet);
  loop();
  assert(radio.outgoing.size() == 3);  // 응답 기한 이후 중복 무시
  fakeNow += 1;
  auto fresh = challenge(2, fakeNow);
  radio.incoming.push_back(fresh);
  loop();
  assert(radio.outgoing.size() == 4);  // 기억 중이어도 새로운 질의는 처리
  auto staleQueue = challenge(3, fakeNow - 1000);
  radio.incoming.push_back(staleQueue);
  loop();
  assert(radio.outgoing.size() == 4);
  auto wrongId = challenge(4, fakeNow);
  wrongId.bytes[3] ^= 1;
  radio.incoming.push_back(wrongId);
  loop();
  assert(radio.outgoing.size() == 4);
  for (int i = 0; i < 100; ++i) loop();
  assert(Serial.output.find("AUTH_FAILED") != std::string::npos);
  assert(Serial.output.find("TX_RESPONSE") != std::string::npos);
  Serial.input += "STATUS inspect\n";
  for (int i = 0; i < 100; ++i) loop();
  assert(Serial.output.find("\"request_id\":\"inspect\"") != std::string::npos);
  Serial.space = 0;
  auto backpressure = challenge(5, millis());
  radio.incoming.push_back(backpressure);
  loop();
  assert(radio.outgoing.size() == 5); // USB 정체가 무선 응답을 막지 않음
  Serial.space = 13;
  for (int i = 0; i < 500; ++i) loop();
  assert(!controls.count && !diagnostics.count);
  printf("%s", Serial.output.c_str());
}
