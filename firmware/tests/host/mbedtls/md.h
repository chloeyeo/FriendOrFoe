#pragma once

// 호스트 시험 전용 어댑터입니다. 실제 보드는 ESP32의 mbedTLS를 사용합니다.
// 실제 FofProtocol.h의 바이트 인코딩/검증 코드를 OpenSSL HMAC으로 실행하고,
// Python hashlib/hmac으로 별도 계산한 벡터와 대조합니다.
#include <openssl/hmac.h>
#include <stddef.h>

using mbedtls_md_info_t = EVP_MD;
constexpr int MBEDTLS_MD_SHA256 = 1;
inline const mbedtls_md_info_t* mbedtls_md_info_from_type(int type) {
  return type == MBEDTLS_MD_SHA256 ? EVP_sha256() : nullptr;
}
inline int mbedtls_md_hmac(const mbedtls_md_info_t* info, const unsigned char* key,
                          size_t keyLength, const unsigned char* data, size_t length,
                          unsigned char* digest) {
  unsigned int digestLength = 0;
  return HMAC(info, key, static_cast<int>(keyLength), data, length, digest, &digestLength)
             && digestLength == 32 ? 0 : -1;
}
