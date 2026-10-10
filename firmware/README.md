# 두 보드의 BLE 인증 통신 데모

현재 구성은 **ESP32-C3 = reader**, **NU40DK(nRF52840) = drone 역할의 responder**입니다.
개발 보드 두 개에서 인증 질의·응답을 시연하는 벤치 데모입니다.
NU40에는 Wi-Fi/ESP-NOW가 없어 기존 ESP-NOW 유니캐스트 대신 공통 지원되는 BLE를 사용합니다.

## 동작

- reader는 부팅 후 대기하며 자동 질의하지 않습니다. `QUERY <request_id>` 하나당 새 16바이트 nonce로 질의 한 번을 시작합니다.
- responder는 passive scan으로 대기하다 올바른 키/ID/HMAC의 질의에만 응답을 브로드캐스트합니다.
- reader는 현재 nonce의 응답을 최대 3초 기다립니다. 자동 재시도하지 않습니다.
- 인증 성공 시 `FRIEND`를 즉시 출력합니다. 응답 여부와 관계없이 질의 시작부터 3초간 재질의를 막습니다. LED 점멸은 없습니다.
- 실패 시 `UNKNOWN`과 `NO_RESPONSE`, `AUTH_FAILED`, `INVALID_MESSAGE`, `RADIO_ERROR` 중 사유를 출력합니다.
- 질의 대기·재질의 대기 중 추가 명령은 `BUSY`이며 예약하지 않습니다. 같은 현재/직전 완료 요청 ID는 기존 상태·결과만 반환합니다.
- 늦은/중복/이전 nonce 응답은 상세 로그에 남으며 완료 판정을 바꾸지 않습니다.

양쪽 모두 연결 불가·스캔 응답 불가 광고를 사용하고 수신은 passive scan입니다.
상대 MAC 사전 등록, BLE 연결 및 페어링, Wi-Fi AP가 필요하지 않습니다.
BLE 5 확장 광고를 사용합니다. 기본 광고 채널 37/38/39에서 보조 채널 위치를 알리고,
보조 채널 0~36에서 데이터를 수신합니다. 채널 이동은 BLE 스택이 처리합니다.
무선 송신 주소는 BLE 프레임에 존재하지만 인증 수단으로 사용하지 않습니다.

## 개발 환경

- ESP32-C3/S3: Arduino-ESP32 **3.3.12**, **NimBLE-Arduino 2.5.0**, Preferences/mbedTLS.
- NU40: 제조사 **NUBoards nRF52 1.0.2**, 보드 `NU40DK nRF52840`.
- NU40 패키지 내 Bluefruit52, TinyUSB, InternalFS/LittleFS, Adafruit nRFCrypto 사용.
- 공통 Arduino 라이브러리: 이 저장소의 `firmware/iff`.
- reader의 `build_opt.h`가 `CONFIG_BT_NIMBLE_EXT_ADV=1`을 모든 소스에 적용합니다.
  `.ino`에만 `#define`하면 라이브러리 소스에는 적용되지 않으므로 이 파일도 함께 유지하십시오.

Arduino IDE의 추가 보드 관리자 URL에 다음 제조사 주소를 추가한 뒤 `NUBoards nRF52`를 설치합니다.

```text
https://raw.githubusercontent.com/Nucode01/Adafruit_nRF52_Arduino/refs/heads/master/package_nuduino_index.json
```

이 컴퓨터에는 위 코어와 ARM GCC/CMSIS 도구를 설치했고 IDE 설정에도 URL을 등록했습니다.
실행 중인 IDE에 새 보드 목록이 안 보이면 IDE를 다시 시작하십시오.
제조사 1.0.2 ZIP의 macOS `adafruit-nrfutil` 실행 권한 누락은 `chmod u+x`로 복구했습니다.
패키지의 `USE_TINYUSB redefined` 경고는 제조사 variant와 빌드 플래그의 중복 정의입니다.

CLI 빌드 예시(arduino-cli가 PATH에 있는 환경):

```sh
arduino-cli lib install "NimBLE-Arduino@2.5.0"
arduino-cli compile --fqbn esp32:esp32:esp32c3:CDCOnBoot=cdc,FlashSize=4M,FlashMode=dio --library firmware/iff firmware/reader
# S3로 교체할 때:
# arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc,USBMode=hwcdc,FlashSize=8M,FlashMode=dio --library firmware/iff firmware/reader
arduino-cli compile --fqbn nucode:nrf52:nu40dk --library firmware/iff firmware/drone
```

업로드할 때 연결된 포트를 `arduino-cli board list`로 다시 확인하십시오.
NU40은 기존 Nordic DK 부트로더를 사용하면 자동 보드 식별이 되지 않을 수 있으나
`nucode:nrf52:nu40dk`를 명시하여 업로드할 수 있습니다. 정상 동작하는 부트로더를 덮어쓸 필요는 없습니다.

## 설정할 값

- 양쪽 `config.h`의 `READER_ID`, `DRONE_ID`: 현재 각각 1. 양쪽에서 같아야 합니다.
- 공유 비밀키: USB Serial 115200 baud, Newline으로 `KEY <64자리 hex>`를 양쪽에 입력합니다.
  키는 소스에 넣지 않습니다. ESP32는 NVS, NU40은 내부 LittleFS에 저장합니다.
- reader의 `MIN_QUERY_INTERVAL_MS=3000`: 응답 여부와 관계없이 질의 시작부터 3초 후 다음 질의를 허용합니다.
- `RESPONSE_TIMEOUT_MS=3000`: 무응답 판정 기한입니다.
  인증 실패 패킷을 받았다는 이유로 잠금을 해제하지 않으며, 기한 내 정상 응답이 없으면 실패 사유로 완료합니다.

설정하지 않아도 되는 값: 상대 MAC, SSID, Wi-Fi 채널, BLE 페어링 키.
소스의 비밀키 기본값은 없으며 저장된 키가 없으면 질의/응답을 시작하지 않습니다.
`STATUS` 명령은 역할, 키 저장 여부, 동작 상태, 최근 결과를 출력하며 키 값은 출력하지 않습니다.
두 플래시 저장소는 기본적으로 암호화 저장이 아닙니다.

## Mac · Windows 브라우저 GUI

Python 3.10 이상과 **pySerial 3.5**를 사용합니다 ([공식 API 문서](https://pyserial.readthedocs.io/en/latest/pyserial_api.html)). 서버와 화면은 로컬 파일만 사용하며 CDN/인터넷 연결이 필요하지 않습니다.
최초 패키지 설치에는 인터넷이 필요합니다. 데모 전에 설치를 마치십시오.
저장소 루트에서 실행합니다.

macOS:

```sh
python3 -m venv firmware/.venv
firmware/.venv/bin/python -m pip install -r firmware/tools/requirements.txt
firmware/.venv/bin/python firmware/tools/reader_gui.py
```

Windows PowerShell (가상환경 활성화/실행 정책 변경 불필요):

```powershell
py -3 -m venv firmware/.venv
.\firmware\.venv\Scripts\python.exe -m pip install -r firmware/tools/requirements.txt
.\firmware\.venv\Scripts\python.exe firmware/tools/reader_gui.py
```

브라우저가 `http://127.0.0.1:8765`로 열립니다. C3 포트(macOS 예: `/dev/cu.usbmodem1101`, Windows 예: `COM7`)를
선택하고 **연결 → 질의 1회**를 누릅니다. 포트 이름은 재연결 시 바뀔 수 있습니다.
역할 `reader`, 인터페이스 v2, 키·무선 준비, `IDLE`을 확인해야 버튼이 활성화됩니다.
Drone 연결 영역에서 NU40 포트를 별도로 선택하여 두 보드를 동시에 연결합니다. 각 영역은 역할/버전을 확인하며 Drone 포트로 QUERY를 보내지 않습니다.
응답 수신과 인증 판정·사유·응답 시간을 각각 표시하며 nonce/태그/전체 패킷/중복 응답은 상세 로그에 있습니다.
GUI에는 비밀키 편집 기능이 없습니다. KEY 설정은 기존 Arduino 시리얼 모니터를 사용하십시오.

- HTTP 포트 변경: `reader_gui.py --port 9000`.
- 브라우저 자동 열기 생략: `reader_gui.py --no-browser`.
- 서버 종료: 실행 터미널에서 `Ctrl+C`. 업로드/터미널 모니터 실행 전에는 GUI의 **해제**로 포트를 비우십시오.
- 보드별 작업 스레드가 각 포트를 소유하며 GUI는 250ms마다 서버 메모리 상태만 조회합니다. 대기 중 STATUS 폴링/하트비트는 없습니다. 연결 확인, 재부팅, 진행 중 질의의 유실 복구(한 번), 수동 **상태 확인**에만 STATUS를 요청합니다.
- ACK는 전송 후 2초, 최종 판정은 4초까지 기다립니다. `ACK_TIMEOUT`/`RESULT_TIMEOUT`/USB 단절은 **통신 오류**입니다.
  reader가 판정한 무선 `UNKNOWN / NO_RESPONSE`와 다릅니다. STATUS의 최근 결과로 유실된 RESULT를 복구합니다.
- USB 단절/reader 재부팅은 진행 중 요청을 취소합니다. 재연결·페이지 새로고침은 QUERY를 재전송하지 않습니다.
  시리얼 포트를 여는 과정에서 OS/드라이버에 따라 보드가 재부팅될 수 있으므로 항상 STATUS로 재확인합니다.
  ESP32 USB-Serial/JTAG(303a:1001)는 DTR/RTS를 비활성으로 열고, NU40 등 일반 CDC는 DTR을 활성화합니다.
  C3가 다운로드 모드라면 BOOT 버튼을 놓고 RESET 또는 USB 재연결 후 다시 연결하십시오.
- 최근 로그 500개를 메모리에 보관합니다. GUI 서버 재시작 시 지워집니다. 서버는 `127.0.0.1`에만 바인딩합니다.

## 시리얼 인터페이스 v2

USB Serial **115200 baud**, ASCII 명령 한 줄 뒤 LF (`\n`), CRLF도 허용합니다.
입력은 단일 파서가 읽어 KEY/질의/상태 명령으로 분기합니다. 최대 79바이트이며 초과/NUL 입력은 다음 LF까지 버립니다.
잘린 줄은 LF가 올 때까지 실행하지 않습니다. request_id는 `[A-Za-z0-9_-]{1,32}`이며 GUI는 UUID 32자리 hex를 생성합니다.

| 명령 | 동작 |
|---|---|
| `QUERY demo_1` | 새 nonce 질의 한 번. 수락/거절 ACK |
| `STATUS` / `STATUS status_1` | 역할, 인터페이스 버전, boot_id, response_timeout_ms, 키·무선 상태, 동작 상태, last_result |
| `HELP` | 명령 안내 |
| `KEY <64자리 hex>` | 기존 키 저장 및 재부팅. 질의 대기·재질의 대기 중에는 BUSY. 키는 출력하지 않음 |

제어 출력은 JSONL이며 키 설정과 무선 라이브러리 초기화 안내는 기존 텍스트 줄일 수 있습니다.

```json
{"event":"ACK","request_id":"demo_1","accepted":true,"reason":"ACCEPTED","state":"QUERYING"}
{"event":"RESULT","request_id":"demo_1","query":1,"response_received":true,"verdict":"FRIEND","reason":"NONE","response_ms":337,"elapsed_ms":337,"replay":false}
{"event":"STATE","boot_id":"b1f8b86d5ad40779","request_id":"demo_1","state":"IDLE","reason":"CHANGED"}
```

- 정상 응답 시 `IDLE → QUERYING → COOLDOWN → IDLE`, 무응답 시 `IDLE → QUERYING → IDLE`입니다.
  정상 응답 판정은 즉시 알리지만 질의 시작부터 3초가 지나야 새 ID의 QUERY를 허용합니다.
  `COOLDOWN`에도 새 질의는 BUSY이며 예약하지 않습니다. GUI는 RESULT만으로 잠금을 해제하지 않고 STATE/STATUS의 IDLE을 기다립니다.
- ACK 사유: `ACCEPTED`, `DUPLICATE`, `BUSY`, `INVALID_REQUEST_ID`, `INVALID_COMMAND`, `COMMAND_TOO_LONG`, `KEY_NOT_CONFIGURED`, `RADIO_ERROR`.
- `response_received`는 기한 내 현재 nonce의 38바이트 응답을 받았다는 뜻이며 인증 성공과 별개입니다.
  `response_ms`는 마지막 현재-nonce 응답까지의 시간(ms), 수신 없으면 null입니다. `elapsed_ms`는 최종 판정까지의 시간입니다.
- 최종 판정은 질의당 한 번 확정됩니다. 같은 ID 요청 시 ACK DUPLICATE와 상태, 완료했다면 `replay:true`인 기존 RESULT를 반환합니다.
  재송신/재판정하지 않습니다. STATUS의 `last_result`도 동일한 스냅샷입니다.
- 제어 큐 16줄, 진단 큐 8줄로 크기를 제한합니다. 제어 로그가 우선이며 USB 출력 공간이 없으면 루프에서 이어서 전송합니다.
  제어 큐가 차면 입력을 잠시 중단해 최종 판정 공간을 확보합니다.
  C3 USB 연결 플래그 복구를 위해 새 이벤트가 큐에 들어올 때 한 번만 빈 줄이 포함될 수 있습니다. 빈 줄은 무시하며 RF 질의가 아닙니다. 무선 수신과 응답 기한은 계속 처리합니다.
  진단 큐 손실은 `DIAG / dropped_packet_logs`로 알립니다. 컴퓨터 측 전송량은 USB 수신 버퍼 한도를 넘기지 마십시오.
- `TX_CHALLENGE`/`RX_RESPONSE`에는 `request_id`, `query`, `at_ms`, `elapsed_ms`, `bytes`, `raw`, `result`가 있습니다.
  38바이트면 `version`, `type`, `reader_id`, `drone_id`, `nonce`, `tag`도 포함합니다.
  RX의 요청 ID/순번은 수신 당시 관리하던 요청입니다. 이전 nonce 응답의 실제 대응은 nonce로 구분합니다.
- `at_ms`는 보드 수신 콜백 시각입니다. USB 로그 표시 시각은 무선 지연 측정에 사용하지 않습니다.
- RX 무시 사유: `IGNORED_NONCE_MISMATCH`, `IGNORED_LATE_RESPONSE`, `IGNORED_NOT_WAITING`.
  `BROADCAST_STARTED`는 송신 시작 성공이며 인증 성공은 RESULT FRIEND로 확인합니다.
- 인터페이스 버전 2는 USB 제어 규격입니다. 무선 패킷 버전 1/38바이트/HMAC은 변경하지 않았습니다.

## Drone 수신 누락 진단

NU40의 스캔 interval/window는 각각 **10ms**입니다. Reader 스캔은 100ms, 질의 반복 광고는 최대 600ms입니다.
Drone의 수동 `STATUS` 또는 GUI **상태 확인** 응답에는 `radio_diag`가 포함됩니다. 대기 중 추가 로그/STATUS 요청은 없습니다.

- `scan_interval_us`: 실행 중인 스캔 설정.
- `reports`, `extended`: BLE 스택이 전달한 전체/확장 광고 보고 누적 수.
- `matched`: 완전한 예상 타입의 FOF 메시지를 발견한 누적 수. 인증 성공 수는 아닙니다.
- `resume_errors`, `last_resume_error`: 스캔 재개 실패 수와 마지막 SoftDevice 오류 코드.
- `missed`, `truncated`: 스택이 보고한 불완전 수신/잘림 수. 0이어도 전파에서의 손실이 없다는 뜻은 아닙니다.
- `peer_*`: 처음 발견한 FOF 송신 주소의 누적 보고/최근 RSSI/데이터 길이. 진단에만 사용하며 MAC으로 수신이나 인증을 제한하지 않습니다.
- `max_handler_us`: 수신 콜백 소요 시간의 관측 최댓값. 기본 NU40 타이머 해상도는 약 1ms이므로 0을 실제 0μs로 해석하지 마십시오.

측정 결과와 한계는 [수신 누락 분석](diagnostics/2026-10-10/analysis.md)에 정리했습니다.

## 터미널 모니터

GUI와 같은 pySerial 연결 코드를 사용합니다. GUI를 먼저 해제하고 실행하십시오.
macOS 예시(Windows에서는 가상환경 Python 경로 및 포트 COM 번호를 바꿉니다):

```sh
firmware/.venv/bin/python firmware/tools/serial_monitor.py --port /dev/cu.usbmodem1101
firmware/.venv/bin/python firmware/tools/serial_monitor.py --port /dev/cu.usbmodem1101 --command "QUERY demo_1" --seconds 5
```

기본은 수신만 합니다. `--command`로 명령 하나만 보내며 자동 재전송하지 않습니다.
원문 JSONL은 `--json`, 제한 시간은 `--seconds 20`, 종료는 `Ctrl+C`입니다.
macOS에서는 pySerial exclusive 잠금으로 GUI/모니터의 동시 사용을 거절하고 Windows에서도 OS가 포트를 단독으로 엽니다.
Arduino IDE 등 다른 도구의 포트 점유도 먼저 해제하십시오.

## 메시지와 BLE 확장 광고

원래 38바이트 메시지와 HMAC 규칙은 유지합니다.

| 오프셋 | 크기 | 값 |
|---|---:|---|
| 0 | 1 | 버전 1 |
| 1 | 1 | CHALLENGE=1, RESPONSE=2 |
| 2 | 2 | reader ID, big-endian |
| 4 | 2 | drone ID, big-endian |
| 6 | 16 | 새 질의의 난수 |
| 22 | 16 | HMAC-SHA256의 앞 16바이트 |

HMAC 입력은 ASCII `CHALLENGE` 또는 `RESPONSE`(NULL 제외) 뒤에 메시지 앞 22바이트를 붙인 값입니다.
ESP32는 mbedTLS, NU40은 CryptoCell HMAC 드라이버로 동일하게 계산합니다.

한 광고 데이터는 Flags 3바이트 + Manufacturer AD 헤더 2바이트 + company 2바이트 +
인증 메시지 38바이트로 총 **45바이트**입니다. Manufacturer 데이터의 company는 `FF FF`입니다.
`0xFFFF`는 로컬 시제품 시험용이며 제품 배포용 회사 식별자 등록을 의미하지 않습니다.
일반 BLE 광고의 31바이트 한도를 넘으므로 BLE 5 **Extended Advertising**을 사용합니다.
38바이트 메시지 전체가 한 광고 데이터에 들어가며 애플리케이션 조각 번호, 조립 버퍼,
조립 타임아웃은 없습니다. 기본 채널의 안내와 보조 채널의 데이터라는 BLE 무선 절차는 존재합니다.

양쪽 모두 1M PHY, 연결 불가·스캔 응답 불가·비지정 광고, passive scan입니다.
송신 간격은 50ms이고 질의와 응답은 모두 최대 600ms 동안 같은 전체 메시지를 반복합니다.
이는 한 명령의 동일 메시지를 반복하는 무선 손실 보완이며 새 nonce의 질의/자동 재시도가 아닙니다.
reader는 검증 성공 즉시 남은 질의 광고를 중단합니다.
reader의 USB 출력이 정체되어도 응답 기한 처리는 계속 진행합니다.
새 메시지가 오면 이전 메시지의 남은 반복 광고를 중단하고 새 메시지를 우선 송신합니다.

ESP32 reader는 NimBLE 확장 광고 원시 보고를 사용하여 주변 장치/서비스 목록을 누적하지 않습니다.
NU40은 현재 Bluefruit 래퍼의 31바이트 배열 대신 SoftDevice 확장 광고 API와
255바이트 고정 수신 버퍼를 사용합니다. 완전 수신 보고만 고정 큐에 복사하고,
실제 수신 시각으로 기한을 판정합니다. 검증은 메인 루프에서 수행합니다.

## 중복 처리와 범위

responder는 최근 8개 난수를 RAM에 보관합니다. 같은 질의는 최초 수신부터 1초 안에만
최대 3회 응답을 요청할 수 있고, 이후 5초는 기억만 합니다. 같은 응답을 이미 광고 중이면 중복 송신 요청을 건너뜁니다.
재부팅/6초 캐시 만료 이후의 옛 질의를 영구 차단하는 방식은 아닙니다.
reader는 현재 질의의 난수만 받으므로 이전 응답 재전송으로 현재 인증을 성공시킬 수 없습니다.
이 데모에는 위치 확인, 거리 측정, 전파 중계 방지, 여러 responder 간 응답 충돌 제어가 없습니다.

## 검증

```sh
python3 firmware/tests/test_firmware.py
python3 firmware/tests/test_reader_gui.py
```

현재 회귀 시험은 펌웨어 5개, GUI/HTTP 22개가 통과했습니다.
첫 시험은 C++17/Clang 또는 GCC 및 OpenSSL 개발 라이브러리가 필요합니다.
실제 프로토콜/스케치 로직을 호스트 대역으로 실행하고 Python HMAC 독립 벡터와 비교하며 ASan·UBSan을 사용합니다.
무명령/STATUS 무송신, 명령당 한 번, BUSY, 현재/직전 ID 중복, 잘린 입력/CRLF/초과,
키 누락/무선 오류, 인증 성공·실패·무응답·늦은 응답, LED 출력 없음, 3초 동안 BUSY, 정상 응답 후 COOLDOWN과 3초 경계에서 IDLE, millis 래핑,
출력 정체/분할 출력, 응답 캐시와 BLE 전체 패킷의 길이/변조를 검사합니다.
GUI 시험은 모의 시리얼로 중복 클릭, 이전 결과 혼입, STATUS 결과 복구, ACK/결과 타임아웃,
재부팅·USB 단절·재연결·포트 오류·역할 불일치 및 HTTP 읽기 요청 무송신을 검사합니다.

실제 Mac 검증(2026-10-10):

- C3 reader 컴파일/업로드 및 플래시 해시 검증 성공. NU40 drone은 공통 헤더 변경 후 컴파일 성공.
  저장된 키는 유지했으며 NU40의 기존 응답 펌웨어로 통신했습니다.
- 부팅 후 STATUS 포함 대기 관찰에서 TX 없음. 동시 추가 질의 BUSY, 현재/완료 ID DUPLICATE,
  중복 요청의 재송신 없음, CRLF/초과 입력 거절을 확인했습니다.
- USB 재연결 후 기존 1초 기한 GUI에서 10개 명령/10개 TX/10개 최종 판정,
  FRIEND 8회(73~578ms), NO_RESPONSE 2회를 확인했습니다. 실제 USB 재연결 후 STATUS와 GUI 재연결도 확인했습니다.
- 페이지 새로고침 후 연결·결과 유지 및 재송신 없음, 빠른 중복 클릭의 단일 명령 처리를 확인했습니다.
- 무선 수신은 간헐적으로 누락됩니다. 200ms 질의 광고의 수신 성공률 개선은 남아 있습니다.
  자동 재시도는 없으며 무선 NO_RESPONSE와 컴퓨터/시리얼 통신 오류를 구분합니다.
- LED 제거/3초 기한 펌웨어를 C3에 업로드하고 해시를 검증했습니다. 실제 4회 질의에서
  FRIEND 2회(230ms, 411ms), NO_RESPONSE 2회(각 3000ms)를 확인했습니다.
  당시 응답 후 바로 다음 명령을 수락했으며, 무응답 1500ms 시점에는 BUSY, 3000ms 종료 후 IDLE을 확인했습니다.
  이후 요구 변경으로 응답 후에도 3초 잠금을 유지하도록 변경했습니다(아래 최신 검증 참고).
  무명령 3초 이상 관찰에서도 TX가 없었습니다. GPIO 출력 없음은 호스트 시험과 소스로 확인했습니다.
- 변경 후 GUI의 두 질의는 FRIEND(120ms, 350ms)였으며 응답 대기 중 버튼 비활성,
  판정 후 버튼 활성, 빠른 중복 클릭의 단일 QUERY, 새로고침 후 결과 유지와 추가 송신 없음을 확인했습니다.
- 최신 고정 3초 제한 펌웨어 업로드/해시 검증과 회귀 시험 22개가 통과했습니다.
  실제 정상 응답은 19ms에 확정됐지만 COOLDOWN에서 추가 QUERY가 BUSY였으며,
  질의 시작 후 약 3.002초에 IDLE로 복귀했습니다(컴퓨터 수신 시각).
  무응답도 3000ms에 판정하고 약 3.005초에 IDLE을 수신했습니다.
  최신 GUI 실기 3회에서는 FRIEND 1회(613ms), NO_RESPONSE 2회를 확인했습니다.
  정상 응답 후 잠금 유지와 STATUS 복구 시 COOLDOWN 유지도 모의 GUI 시험으로 검증했습니다.
- Windows 실기 검증과 진행 중 요청에서의 실제 USB 분리 시험은 수행하지 않았습니다.
  진행 중 USB 단절·재연결 오류 처리는 모의 시리얼로 검증했습니다.

포트 이름은 재연결 시 바뀔 수 있습니다. KEY 저장 후에는 재부팅이 끝날 때까지
시리얼 모니터를 약 2초 유지한 뒤 재연결하십시오.


## 양쪽 이벤트 로그 (2026-10-10)

- Reader와 Drone은 부팅, 명령 처리, 상태 변화, 무선 수신/송신, 오류가 있을 때만 USB 로그를 출력합니다. 대기 중 주기 출력은 없습니다.
- Drone은 JSONL `STATUS`로 `role=drone`, 인터페이스 v2, boot_id, 키 저장 여부, 무선 준비 여부, LISTENING 상태를 알립니다. `STATUS [request_id]`, `HELP`, 기존 `KEY` 명령을 지원합니다.
- Drone의 `RX_CHALLENGE`에는 수신 시각, 원문 패킷, nonce/tag, 인증/무시 사유가 있고 `TX_RESPONSE`에는 동일 nonce의 응답 패킷 및 송신 시작/중복 광고/무선 오류가 기록됩니다. 키 자체는 출력하지 않습니다.
- 두 보드의 USB 로그는 고정 크기 큐에서 부분 전송합니다. USB가 없거나 느려도 RF 처리와 판정 기한을 막지 않습니다. 로그가 넘치면 DIAG로 누락 수를 알립니다.
- GUI의 Reader/Drone 연결을 각각 사용합니다. 같은 포트 중복 연결을 거절하며 한쪽 연결 오류가 다른 쪽을 끊지 않습니다. 통합 상세 로그에 `[READER]`/`[DRONE]`과 방향을 표시하고 보드별 필터·복사를 지원합니다.
- GUI는 컴퓨터 수신 순서로 최근 500개 이벤트를 합칩니다. 두 보드의 `at_ms`는 각 보드의 독립 부팅 시계이므로 서로 직접 빼지 마십시오. 양쪽 패킷의 nonce로 동일 질의를 대응시킵니다.
- 조용한 대기는 정상이며, USB 오류는 포트 읽기 실패로 감지합니다. 이벤트가 유실된 경우 **상태 확인**을 누릅니다. 연결 초기 확인은 최대 5초 동안 재시도하며 QUERY는 재전송하지 않습니다.
- 회귀 시험에서 양쪽 대기 중 무출력, Drone 인증 실패/응답/중복 로그, USB 출력 정체 중 RF 응답, 독립 포트와 통합 로그, 잘못된 역할/포트 충돌, 유실 복구 1회 제한을 검증합니다.


최신 실기 검증 (이벤트 로그 적용 후):

- NU40 Drone DFU 업로드/활성화 성공, C3 Reader 업로드/플래시 해시 검증 성공. 양쪽 저장 키를 유지했습니다.
- GUI에서 두 포트를 동시 연결하고 역할·키·무선 준비 상태를 확인했습니다. 무명령 6초 관찰 중 양쪽 USB 로그 추가가 없었습니다.
- 수동 질의 3회 중 1회 FRIEND(278ms), 2회 NO_RESPONSE(3000ms)였습니다. 성공 회차는 동일 nonce의 Reader TX_CHALLENGE → Drone RX_CHALLENGE AUTHENTICATED → Drone TX_RESPONSE BROADCAST_STARTED → Reader RX_RESPONSE FRIEND가 기록됐습니다.
- 무응답 2회는 Reader의 송신 시작 로그는 있었으나 Drone RX_CHALLENGE 로그가 없었습니다. 간헐적인 질의 광고 수신 누락은 남아 있으며, 이벤트 로그 구현 완료와 별개로 RF 수신 신뢰성 개선이 필요합니다.
