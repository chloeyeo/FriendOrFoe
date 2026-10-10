#include <assert.h>
#include <FofBleFraming.h>

int main() {
  uint8_t key[fof::kKeySize] = {}, nonce[fof::kNonceSize] = {}, packet[fof::kPacketSize];
  nonce[0] = 0x32;
  assert(fof::makePacket(fof::kChallenge, 1, 1, nonce, key, packet));
  uint8_t advertisement[fof::kAdvertisementSize] = {};
  fof::makeAdvertisement(packet, advertisement);
  static_assert(sizeof(advertisement) == 45, "Whole 38-byte message + AD overhead");
  const uint8_t* body = fof::findPacket(advertisement, sizeof(advertisement), fof::kChallenge);
  assert(body == advertisement + 7);
  assert(memcmp(body, packet, fof::kPacketSize) == 0);
  assert(fof::verifyPacket(body, fof::kPacketSize, fof::kChallenge, 1, 1, key) == fof::Reason::None);
  assert(fof::findPacket(advertisement, sizeof(advertisement), fof::kResponse) == nullptr);
  for (size_t length = 0; length < sizeof(advertisement); ++length)
    assert(fof::findPacket(advertisement, length, fof::kChallenge) == nullptr);
  advertisement[0] = 255; // 길이가 버퍼를 넘어감
  assert(fof::findPacket(advertisement, sizeof(advertisement), fof::kChallenge) == nullptr);
  advertisement[0] = 0; // AD 종료
  assert(fof::findPacket(advertisement, sizeof(advertisement), fof::kChallenge) == nullptr);
  advertisement[0] = 2;
  advertisement[5] = 0; // 다른 company
  assert(fof::findPacket(advertisement, sizeof(advertisement), fof::kChallenge) == nullptr);
  advertisement[5] = 255;
  advertisement[sizeof(advertisement) - 1] ^= 1;
  body = fof::findPacket(advertisement, sizeof(advertisement), fof::kChallenge);
  assert(body != nullptr); // 전송 필터 통과 후 인증 검증에서 거부해야 함
  assert(fof::verifyPacket(body, fof::kPacketSize, fof::kChallenge, 1, 1, key) == fof::Reason::AuthFailed);
  advertisement[7] = 99; // 전송 필터가 버전 오류를 숨기지 않음
  body = fof::findPacket(advertisement, sizeof(advertisement), fof::kChallenge);
  assert(body != nullptr);
  assert(fof::verifyPacket(body, fof::kPacketSize, fof::kChallenge, 1, 1, key) == fof::Reason::InvalidMessage);
}
