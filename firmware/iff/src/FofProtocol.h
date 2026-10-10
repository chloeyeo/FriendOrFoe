#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#if defined(ARDUINO_ARCH_NRF52)
#include <Adafruit_nRFCrypto.h>
#else
#include <mbedtls/md.h>
#endif

// reader와 drone이 반드시 같은 규칙으로 계산해야 하는 공통 부분입니다.
// Arduino의 String, 구조체 정렬, JSON 표현에 의존하지 않는 바이트 규격입니다.
namespace fof {

constexpr uint8_t kVersion = 1;
constexpr uint8_t kChallenge = 1;
constexpr uint8_t kResponse = 2;
constexpr size_t kKeySize = 32;
constexpr size_t kNonceSize = 16;
constexpr size_t kTagSize = 16;
constexpr size_t kBodySize = 22;  // version(1) + type(1) + IDs(4) + nonce(16)
constexpr size_t kPacketSize = kBodySize + kTagSize;  // 항상 38바이트
constexpr size_t kNonceOffset = 6;

// 인증 실패 패킷 하나가 먼저 도착했다고 질의를 중단하지 않습니다.
// 유효한 응답이 기한 내 도착하면 FRIEND이며, 없으면 대표 사유를 보고합니다.
enum class Reason { None, NoResponse, InvalidMessage, AuthFailed, RadioError };

inline const char* reasonName(Reason reason) {
  switch (reason) {
    case Reason::None: return "NONE";
    case Reason::NoResponse: return "NO_RESPONSE";
    case Reason::InvalidMessage: return "INVALID_MESSAGE";
    case Reason::AuthFailed: return "AUTH_FAILED";
    case Reason::RadioError: return "RADIO_ERROR";
  }
  return "INVALID_MESSAGE";
}

// MCU의 메모리 배치와 관계없이 높은 바이트부터 보내는 big-endian입니다.
inline void writeU16(uint8_t* out, uint16_t value) {
  out[0] = static_cast<uint8_t>(value >> 8);
  out[1] = static_cast<uint8_t>(value);
}

inline uint16_t readU16(const uint8_t* in) {
  return static_cast<uint16_t>((uint16_t(in[0]) << 8) | in[1]);
}

// millis()는 약 49.7일 후 0으로 돌아옵니다. 뺄셈으로 비교하면 그 경계도
// 처리할 수 있습니다. 이 코드의 모든 시간 제한은 uint32_t 범위보다 짧습니다.
inline uint32_t elapsed(uint32_t now, uint32_t since) {
  return now - since;
}

// 중간에 다른 바이트를 찾았다고 즉시 return하지 않고 끝까지 비교합니다.
// volatile은 컴파일러가 조기 종료 비교로 바꾸지 않게 하기 위한 것입니다.
inline bool equalBytes(const uint8_t* a, const uint8_t* b, size_t length) {
  volatile uint8_t difference = 0;
  for (size_t i = 0; i < length; ++i) {
    difference = static_cast<uint8_t>(difference | (a[i] ^ b[i]));
  }
  return difference == 0;
}

inline bool calculateTag(const uint8_t* key, const uint8_t* body, uint8_t* tag) {
  // CHALLENGE/RESPONSE의 구분 문자열은 ASCII이며 끝의 NULL은 포함하지 않습니다.
  // type도 body에 포함됩니다. 질의 태그를 응답 태그로 재사용할 수 없습니다.
  const char* domain;
  size_t domainSize;
  if (body[1] == kChallenge) {
    domain = "CHALLENGE";
    domainSize = 9;
  } else if (body[1] == kResponse) {
    domain = "RESPONSE";
    domainSize = 8;
  } else {
    return false;
  }

  uint8_t input[9 + kBodySize] = {};
  memcpy(input, domain, domainSize);
  memcpy(input + domainSize, body, kBodySize);
#if defined(ARDUINO_ARCH_NRF52)
  // NU40의 CryptoCell 드라이버는 32비트 정렬된 출력 버퍼를 요구합니다.
  // 전역 암호 초기화는 Radio::begin()에서 한 번만 합니다.
  uint32_t digest[16] = {};
  uint8_t mutableKey[kKeySize];
  memcpy(mutableKey, key, kKeySize);
  nRFCrypto_Hmac hmac;
  const bool ok = hmac.begin(CRYS_HASH_SHA256_mode, mutableKey, kKeySize) &&
                  hmac.update(input, domainSize + kBodySize) && hmac.end(digest) == 32;
  memset(mutableKey, 0, sizeof(mutableKey));
  if (!ok) return false;
  memcpy(tag, digest, kTagSize);
#else
  uint8_t digest[32] = {};
  const mbedtls_md_info_t* sha256 = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (sha256 == nullptr ||
      mbedtls_md_hmac(sha256, key, kKeySize, input, domainSize + kBodySize, digest) != 0) {
    return false;
  }
  memcpy(tag, digest, kTagSize);
#endif
  return true;
}

inline bool makePacket(uint8_t type, uint16_t readerId, uint16_t droneId,
                       const uint8_t* nonce, const uint8_t* key, uint8_t* out) {
  out[0] = kVersion;
  out[1] = type;
  writeU16(out + 2, readerId);
  writeU16(out + 4, droneId);
  memcpy(out + kNonceOffset, nonce, kNonceSize);
  return calculateTag(key, out, out + kBodySize);
}

inline Reason verifyPacket(const uint8_t* packet, size_t length, uint8_t type,
                           uint16_t readerId, uint16_t droneId, const uint8_t* key) {
  // 길이를 먼저 검사하므로 짧은 패킷에서 범위 밖 메모리를 읽지 않습니다.
  if (length != kPacketSize || packet[0] != kVersion || packet[1] != type ||
      readU16(packet + 2) != readerId || readU16(packet + 4) != droneId) {
    return Reason::InvalidMessage;
  }
  uint8_t expected[kTagSize] = {};
  if (!calculateTag(key, packet, expected)) return Reason::RadioError;
  return equalBytes(expected, packet + kBodySize, kTagSize)
             ? Reason::None : Reason::AuthFailed;
}

// drone 측의 최근 질의 기록입니다. reader의 1초 간격에 맞춰 8개면
// 6초 보관 구간의 정상 질의가 들어갑니다. 가득 차면 기존 기록을 밀어내지
// 않고 새 질의를 무시합니다(오래된 질의를 새 질의로 처리하지 않기 위함).
struct ReplyCache {
  struct Entry {
    bool used = false;
    uint32_t createdAt = 0;
    uint8_t sends = 0;
    uint8_t nonce[kNonceSize] = {};
    uint8_t response[kPacketSize] = {};
  };
  static constexpr uint32_t kReplyWindowMs = 1000;
  static constexpr uint32_t kRememberMs = 6000;  // 응답 1초 + 추가 기억 5초
  static constexpr uint8_t kMaxSends = 3;
  Entry entries[8] = {};

  void expire(uint32_t now) {
    for (auto& entry : entries) {
      if (entry.used && elapsed(now, entry.createdAt) >= kRememberMs) entry.used = false;
    }
  }
  Entry* find(const uint8_t* nonce) {
    for (auto& entry : entries) {
      if (entry.used && equalBytes(entry.nonce, nonce, kNonceSize)) return &entry;
    }
    return nullptr;
  }
  Entry* freeEntry() {
    for (auto& entry : entries) if (!entry.used) return &entry;
    return nullptr;
  }
  static bool mayReply(const Entry& entry, uint32_t now) {
    return elapsed(now, entry.createdAt) < kReplyWindowMs && entry.sends < kMaxSends;
  }
};

}  // namespace fof
