#pragma once
#include "FofProtocol.h"

namespace fof {
// BLE 5 확장 광고: Flags(3) + Manufacturer AD 헤더(2) + company(2) + 메시지(38).
// 45바이트 전체를 한 광고 데이터로 전달합니다. 애플리케이션 분할/재조립은 없습니다.
// 0xFFFF는 로컬 시제품 시험용 company 값입니다.
constexpr size_t kAdvertisementSize = 7 + kPacketSize;
constexpr int kSendOk = 0;
constexpr int kSendBusy = 1;
constexpr int kSendError = -1;

struct ReceivedPacket {
  uint8_t bytes[kPacketSize] = {};
  int length = 0;
  uint32_t arrivedAt = 0; // 전체 광고를 실제로 받은 콜백 시각
};

inline void makeAdvertisement(const uint8_t* packet, uint8_t* out) {
  out[0] = 2; out[1] = 1; out[2] = 6; // Flags AD
  out[3] = 1 + 2 + kPacketSize;      // AD type + company + 메시지의 길이
  out[4] = 0xFF;                    // Manufacturer Specific Data
  out[5] = out[6] = 0xFF;           // 시험용 company ID, little-endian
  memcpy(out + 7, packet, kPacketSize);
}

// 주변의 무관한 광고는 걸러냅니다. HMAC/버전/ID 검증은 loop()에서 합니다.
// 각 AD 길이를 먼저 확인하여 잘리거나 손상된 광고에서 범위를 벗어나지 않습니다.
inline const uint8_t* findPacket(const uint8_t* raw, size_t length, uint8_t expectedType) {
  for (size_t offset = 0; offset < length;) {
    const size_t fieldLength = raw[offset];
    if (fieldLength == 0) break;
    if (fieldLength > length - offset - 1) return nullptr;
    if (fieldLength == 1 + 2 + kPacketSize && raw[offset + 1] == 0xFF &&
        raw[offset + 2] == 0xFF && raw[offset + 3] == 0xFF &&
        raw[offset + 5] == expectedType) return raw + offset + 4;
    offset += fieldLength + 1;
  }
  return nullptr;
}
} // namespace fof
