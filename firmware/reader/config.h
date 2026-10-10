#pragma once

// 양쪽의 ID와 USB로 설정한 비밀키가 같아야 합니다. 상대 MAC/채널 설정은 없습니다.
constexpr uint16_t READER_ID = 1;
constexpr uint16_t DRONE_ID = 1;

// 응답 여부와 관계없이 질의 시작부터 3초간 추가 질의를 BUSY로 거절합니다.
// 정상 응답의 판정은 즉시 출력하며, 무응답은 3초 후 완료합니다. 자동 재시도는 없습니다.
constexpr uint32_t MIN_QUERY_INTERVAL_MS = 3000;
constexpr uint32_t RESPONSE_TIMEOUT_MS = 3000;
