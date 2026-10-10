#include <FofProtocol.h>
#include <assert.h>
#include <stdio.h>

int main() {
  uint8_t key[fof::kKeySize], nonce[fof::kNonceSize];
  for (size_t i = 0; i < sizeof(key); ++i) key[i] = static_cast<uint8_t>(i);
  for (size_t i = 0; i < sizeof(nonce); ++i) nonce[i] = static_cast<uint8_t>(i);
  uint8_t challenge[fof::kPacketSize], response[fof::kPacketSize];
  assert(fof::makePacket(fof::kChallenge, 0x1234, 0x5678, nonce, key, challenge));
  assert(fof::makePacket(fof::kResponse, 0x1234, 0x5678, nonce, key, response));
  assert(fof::verifyPacket(challenge, sizeof(challenge), fof::kChallenge, 0x1234, 0x5678, key) == fof::Reason::None);
  assert(fof::verifyPacket(response, sizeof(response), fof::kResponse, 0x1234, 0x5678, key) == fof::Reason::None);

  // 각 필드의 1비트 변조를 모두 거부하는지 확인합니다.
  for (size_t i = 0; i < sizeof(response); ++i) {
    response[i] ^= 1;
    assert(fof::verifyPacket(response, sizeof(response), fof::kResponse, 0x1234, 0x5678, key) != fof::Reason::None);
    response[i] ^= 1;
  }
  for (size_t length = 0; length < fof::kPacketSize; ++length) {
    assert(fof::verifyPacket(response, length, fof::kResponse, 0x1234, 0x5678, key) == fof::Reason::InvalidMessage);
  }
  assert(fof::verifyPacket(response, fof::kPacketSize + 1, fof::kResponse, 0x1234, 0x5678, key) == fof::Reason::InvalidMessage);
  assert(fof::verifyPacket(challenge, sizeof(challenge), fof::kResponse, 0x1234, 0x5678, key) == fof::Reason::InvalidMessage);
  key[0] ^= 1;
  assert(fof::verifyPacket(response, sizeof(response), fof::kResponse, 0x1234, 0x5678, key) == fof::Reason::AuthFailed);
  key[0] ^= 1;

  // 메시지 타입만 응답으로 바꿔도 기존 질의 태그는 유효하지 않습니다.
  challenge[1] = fof::kResponse;
  assert(fof::verifyPacket(challenge, sizeof(challenge), fof::kResponse, 0x1234, 0x5678, key) == fof::Reason::AuthFailed);
  challenge[1] = fof::kChallenge;

  fof::ReplyCache cache;
  auto* entry = cache.freeEntry();
  assert(entry != nullptr);
  entry->used = true;
  entry->createdAt = UINT32_MAX - 500;
  memcpy(entry->nonce, nonce, sizeof(nonce));
  assert(cache.find(nonce) == entry);
  assert(fof::ReplyCache::mayReply(*entry, entry->createdAt + 999));
  assert(!fof::ReplyCache::mayReply(*entry, entry->createdAt + 1000));
  entry->sends = 3;
  assert(!fof::ReplyCache::mayReply(*entry, entry->createdAt));
  cache.expire(entry->createdAt + 5999);
  assert(entry->used);
  cache.expire(entry->createdAt + 6000);
  assert(!entry->used);
  assert(fof::elapsed(499, UINT32_MAX - 500) == 1000);
  for (auto& item : cache.entries) item.used = true;
  assert(cache.freeEntry() == nullptr);

  // Python 시험이 독립적으로 계산한 두 패킷과 정확히 대조합니다.
  for (uint8_t byte : challenge) printf("%02x", byte);
  printf("\n");
  for (uint8_t byte : response) printf("%02x", byte);
  printf("\n");
}
