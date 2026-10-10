# Project Research Summary

**Project:** FriendOrFoe (package `com.chloeyeo.friendorfoe`; folder on disk `C:/Users/C/AndroidStudioProjects/FieldReport`)
**Domain:** Offline, on-device camera drone detection (ClikaRT 0.6.4 AAR, RT-DETR v2 r18) plus a radio-silent, replay- and relay-resistant optical IFF between two Android phones; a 20-hour, one-developer hackathon demo on connected devices for track T1-04 (Radio-silent IFF) and sub-track SC1 (CLIKA BYOD Offline AI)
**Researched:** 2026-10-10 (STACK, FEATURES, ARCHITECTURE, PITFALLS; each adversarially re-verified the same day, corrections applied in place)
**Confidence:** MEDIUM-HIGH. HIGH on what to build and how: the stack was compiled in a scratch Gradle project against the real ClikaRT AAR, ClikaRT behaviour was read from the local binding sources and samples, and the laptop serve API was captured live. MEDIUM on execution: four device-empirical unknowns (offline licence on the phone, hotspot under airplane mode, on-device latency, the drone prop) can only be settled by the H0-H4 tests, not by more research.

## Executive Summary

FriendOrFoe is a two-phone, network-blocked counter-UAS demo. Phone A (observer, the tank-crew app) runs RT-DETR v2 r18 in-process through the ClikaRT Kotlin AAR, boxes people and drone proxies (COCO ids 0 person; 4 aeroplane, 14 bird, 33 kite), and on a tap sends one authenticated challenge over a hotspot LAN to phone B (responder, standing in for the friendly drone). B answers optically with a QR code that A decodes only inside the challenged box and verifies with FOF-1 (HMAC-SHA256, fresh 16-byte nonce, per-drone day key, position gate, round-trip and signal-strength gates). The research confirms the team's framing: no COCO detector has a drone class (proxy classes plus track-level filtering are the standard cheap fix; fine-tuning is research-scale work), optical IFF is where the field is moving (FAFOS Oct 2025, ReDroId, blink-ID schemes), and FOF-1's distinguishing property is a combination none of the compared systems has: a challenge-bound answer, attribution by the camera box, and relay resistance by position binding plus timing. **Demo surface (user correction, 2026-10-10): detection runs on the Android phone, the drone-plus-IFF scheme is what the team builds, and the demo connects those devices and runs on them, not on a laptop.** The laptop is a side-car (eval table, `clikart-cli serve` as the SC1 second device type, optional dashboard); nothing on the IFF path may depend on it, and the judges' big screen is a scrcpy USB mirror of phone A.

The recommended build is fully specified and compile-verified: ClikaRT 0.6.4 AAR + CameraX 1.6.2 + ML Kit bundled barcode-scanning 17.3.0 + ZXing core 3.5.4 + NanoHTTPD 2.3.1 + OkHttp 5.5.0, with the protocol in a pure-JVM `:iff` Gradle module (KGP 2.2.10, JVM target 11, JUnit 4, JDK crypto only) whose tests run without a phone. Architecturally: one single-thread runtime lane for every ClikaRT call; a two-method `Detector` interface whose factory is the H4 one-line swap; a tracker that coasts a challenged box while the QR hides the drone picture; QR decoding restricted to the crop of that box (attribution by construction); the same APK in two roles selected by a setting; fire-and-forget telemetry. The build order maps one-to-one onto the hour plan (H0-1, H1-4 with the hard H4 checkpoint, H4-7, H7-10, H10-12, H14-17, H17-20). Because table-stakes features alone cost about 17.2 of the 18 working hours, and H1-10 is overloaded by roughly 3 h while H14-20 holds the slack, the roadmap needs explicit go/no-go gates at H4, H10 and H14 and a fixed cut order: laptop dashboard first, then audio alert, OWLv2 option, eval table, and the Relay button last.

The risks that can sink the demo are all empirical and all front-loaded. (1) The ClikaRT credential may be an online key that dies with `LICENSE_SERVER_UNREACHABLE` in airplane mode: test on the laptop with Wi-Fi off before H1 and on phone A in airplane mode at H1; if it is online, request an offline credential immediately and keep building. (2) Whether phone B can host the hotspot while phone A sits in airplane mode is OEM-specific and unverified: run the three-device topology test at H0. (3) RT-DETR may box phone B as `cell phone` and never see the drone picture: decide the prop at H3 (printed drone photo with B taped at its centre, or a tablet with the QR overlaid in the picture); OWLv2 on the phone is not a fallback (5-10 s per frame on a 12-thread laptop CPU). (4) A version-3 QR on a phone screen decodes at about 0.5-0.7 m with 1280x720 frames: rehearse at that distance. (5) Cleartext HTTP is blocked by default on API 28+ and the manifest lacks `INTERNET` today: fix at H0. No further desk research retires any of these; tests do.

## Key Findings

### Recommended Stack

Every Android/Kotlin line below compiled together in a throwaway AGP 9.2.1 / Kotlin 2.2.10 / Gradle 9.4.1 project against the real AAR, and `:iff:test` passed (STACK.md). Do not touch the generated toolchain (AGP 9 bundles KGP 2.2.10 and forbids `org.jetbrains.kotlin.android`; the AAR pulls `kotlin-stdlib 2.3.0`, which the 2.2.10 compiler reads fine; do not bump Kotlin to "match"). Phone B never loads ClikaRT, so only phone A needs the credential, and B can be any arm64 phone. The APK is about 300 MB (284 MB of arm64 native libs, uncompressed by default); install over USB only, no emulator.

**Core technologies:**
- `io.clika:clika-runtime:0.6.4` (AAR, local Maven `C:/Users/C/clika`): in-process RT-DETR detection, the SC1 hard requirement. Load with `AutoModelForObjectDetection.fromPretrained(snapshotDir, VisionLoadOptions(offline = true, device = "cpu"))`, wrap with `Modelverse.pipeline(Task.OBJECT_DETECTION, model)` (the pipeline constructor is `internal`), input `ImageInput.Rgba8888` with tightly packed rows, results in input pixels keyed by `labelId: Long`.
- CameraX 1.6.2 (`camera-camera2`, `-lifecycle`, `-view`): Preview + one ImageAnalysis in RGBA_8888, `KEEP_ONLY_LATEST`, 16:9 `ResolutionSelector` at 1280x720, hold-then-close pacing to about 1 fps. Bump from 1.4.1 at H0 in one commit (compile-verified; carries the 1.5.x fixes), then freeze.
- ML Kit `com.google.mlkit:barcode-scanning:17.3.0` (bundled model, QR only): decode the answer from a crop of the challenged box, read `Barcode.getRawBytes()`. ZXing `QRCodeReader` on the same crop is the one-line fallback if the bundled artifact misbehaves in airplane mode.
- ZXing `core:3.5.4`: encode the raw 37-byte response in QR byte mode via the ISO-8859-1 trick (version 3-M), EC level M, margin kept, rendered full-width at 100 % brightness.
- NanoHTTPD 2.3.1: phone B's one endpoint (`POST /challenge`, plus `GET /status`), 20 lines; `com.sun.net.httpserver` does not exist on Android, Ktor is too heavy for one route.
- OkHttp 5.5.0: challenge POST (connect 2 s / read 3 s), event POST (fire-and-forget), `HttpDetector` multipart. Every call is `http://` to a LAN IP, so the manifest needs `android:usesCleartextTraffic="true"` and `INTERNET`.
- Pure-JVM `:iff` module: `org.jetbrains.kotlin.jvm` 2.2.10, Java/Kotlin target 11 (equal to the app's `compileOptions`; a higher target breaks Gradle's variant check), JUnit 4.13.2, `javax.crypto.Mac`, `java.security.SecureRandom`, `java.util.Base64`. No Android imports, no `org.json`, no `android.util.Base64`.
- Platform APIs for sensors: `LocationManager` (GPS, unusable indoors, so positions are configured), `SensorManager` rotation vector (compass, flavour only), `ConnectivityManager`/`WifiInfo.rssi` for the signal gate (`ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`), `ToneGenerator`/bundled OGG for the alert (not TTS: voice data may need a download).
- Laptop (optional, Tier 2): `clikart-cli` 0.6.4 `serve --offline --device cpu --host 0.0.0.0` (verified live: `POST /v1/detections` multipart `image`, `GET /health`, `/props`, `/dashboard`; OWLv2 requires `prompt`); Python 3.13 with fastapi 0.143 / uvicorn 0.51 / requests / pillow already installed; scrcpy 5.0.1 for the USB mirror and `--record`.

### Expected Features

FEATURES.md costs the table stakes at about 17.2 h and the differentiators at about 10.9 h against 18 working hours (H0-12, H14-20). The demo cannot run without the first list; the second is built top-down only as gates pass. The user's correction moved the laptop dashboard from table stakes to the first discretionary cut and made the on-phone event log and status strip table stakes instead.

**Must have (table stakes):**
- DET-01..06: live back-camera preview with an analysis stream; in-process ClikaRT detection at about 1 fps with a "model loading" state; `labelId` class map (0 person; 4/14/33 drone); box overlay in preview coordinates; `Detector` interface with `HttpDetector` as the H4 contingency only; a challenge-window box lock so the QR can be read inside a box that the detector may lose when B's screen changes.
- IFF-01..05: the `:iff` module with fixed binary layouts (31-byte challenge, 37-byte response), nonce ledger (issued, outstanding, consumed; age < 10 s), ordered verdict engine with reasons (never FRIEND on timeout), day window today +/-1, unit tests plus JSON test vectors for every verdict.
- RSP-01..06: mode picker and responder screen showing its IP, authenticated listener that ignores bad challenge MACs (closes "ask tiger first to harvest lion"), full-screen QR in byte mode, Honest / Replay / Silent buttons, configured demo position, idle drone picture with the QR drawn over its centre.
- OBS-01..06, OBS-10: rings and tags (`drone · ?`, `FRIEND · RQ-07`, `FOE · reason`, `UNKNOWN · no response`), tap-to-challenge with a 10 s countdown, in-box QR decode, status strip with ms/frame and an OFFLINE badge (SC1 evidence), settings (responder IP, master key, ids, positions, thresholds), on-phone event log with counts, reset button.
- JDG-01 scrcpy mirror; SEC-01..04 (nothing secret in the repo or logs, `SecureRandom`, constant-time compare); PIT-01, PIT-03..06 (README with pre-existing vs built, backup video, prop decided at H4, three airplane-mode rehearsals, pitch script and Q&A card).

**Should have (competitive), in keep order:**
- IFF-08 / RSP-08 Impostor button -> `FOE · bad tag` (0.25 h; completes the six-verdict story with PROJECT.md's Late button).
- IFF-06 / RSP-07 position gate + Relay button -> `FOE · position mismatch`. "Resist MITM" is in the T1-04 text and this is the only relay defence visible as a red ring. Cheaper than FEATURES' 1.5 h once `PositionCheck` exists: the indoor demo only exercises the range gate (5 km > R_max 3 km) and the near-field branch (< 30 m), so DET-11 compass bearing is the deferrable part (about 0.5 h without it).
- PROJECT.md's physical-layer gates (RTT 2.5 s -> `late response`, two-frame QR persistence -> `optical mismatch`, hotspot RSSI band -> `signal mismatch`): an Active requirement that FEATURES.md did not cost; budget about 0.75 h in H10-12.
- PIT-02 `docs/PROTOCOL.md` with the threat table and production path (Military Deployability 30 %).
- OBS-08 verdict toast listing the checks (explains the protocol without slides); DET-07 N-of-M track persistence; EVL-03 on-device numbers in the README; JDG-04 `clikart-cli serve` shown during the demo as the SC1 second device type; RSP-09 "transmissions initiated: 0" counter; RSP-10 harvest demo with a key-less curl.
- EVL-01/02 eval set and `run_eval.py` (RT-DETR vs OWLv2, precision/recall per detector at fixed thresholds, hard negatives included).
- DET-08 first-drone chime (bundled OGG, not TTS); DET-09 OWLv2 option (laptop-side only).

**Defer (v2+, documented in PROTOCOL.md, not built):**
- IR LED on-off-keyed optical response; Ed25519 per-drone signatures and multi-observer key distribution; fine-tuned drone detector, SAHI tiling, thermal input; JDG-02/03 FastAPI dashboard (only if a second screen beyond scrcpy is ever needed); anything in the anti-feature list (RF fallback for the answer, periodic beacon, mDNS discovery, TLS on the hotspot, NTP, mAP tooling, slides).

### Architecture Approach

Two phones run the same APK in two roles chosen by `Settings.mode`; the laptop is Tier 2 and reachable only through fire-and-forget calls. Phone A's pipeline is `camera/` (CameraX, stride-aware RGBA copy, close the proxy at once, 1 fps detector gate behind a busy flag, QR crop loop every frame while a track is CHALLENGED) -> `detect/` (`Detector` interface, `ClikaRtDetector` on the single runtime lane, `HttpDetector` for development, `LabelMap` by id, `Detectors.create(settings)` as the only place that knows which implementation exists) -> `track/` (greedy IoU matching on normalized boxes, EMA, coast a CHALLENGED track for the full 10 s window) -> `session/` (`IffCoordinator` state machine UNCHALLENGED -> CHALLENGED -> FRIEND/FOE/UNKNOWN, `PhysicalGates` for RTT, persistence and RSSI) -> `ui/` (Compose `Canvas` rings over a `PreviewView`, VerdictLog, settings, debug harness). Phone B runs `responder/` (NanoHTTPD, `ResponderState` with the six modes, ZXing QR renderer, own-IP display) and never touches `io.clika`. Both call the same `:iff` code (`Challenger`/`Verifier`/`NonceStore` on A, `Responder` on B), so one bug fix fixes both and the JSON vectors prove interoperability. Five named coordinate systems (frame F, crop, normalized N, view V, angles A) with one transform class keep boxes, taps and QR crops consistent; MAC inputs are fixed big-endian byte layouts, never JSON.

**Major components:**
1. `:iff` (pure JVM): `Bytes`, `Hmac`, `Keys` (day key, drone key, today +/-1), `Codec` (31/37-byte layouts), injected `Clock`, `NonceStore`, `Challenger`, `Responder` (verifies cmac, |t - now| <= 120 s, derives the day from the challenge `t`), `Verifier` (ordered reasons), `Geo`, `PositionCheck` (3 km range gate; near-field < 30 m or +/-20 deg cone).
2. `app/` runtime bring-up: `AppRuntime.load` once on `RuntimeLane` after the credential is resolved (`Os.setenv("CLIKA_RT_NUM_THREADS")` before load, `bridgeLogs`, `trimOnMemoryPressure`), `LicenseStore` (`BuildConfig.CLIKA_RT_LICENSE` from `local.properties`, else pasted once), `ModelLocator` (external files dir, then `filesDir`), `Settings`.
3. `detect/` + `camera/` + `track/`: the frame-to-boxes path described above; `FrameTransform` is the only class that knows view pixels.
4. `session/` + `optical/`: tap -> challenge -> QR bytes read from the box crop (ML Kit `rawBytes`, `boundingBox` inside the box) -> `PhysicalGates` -> `Verifier` -> verdict on the track -> VerdictLog -> optional event POST.
5. `responder/` + `ui/ResponderScreen`: `POST /challenge` (401 on bad cmac, no QR), six buttons (Honest, Replay, Relay = honest tag over a position 5 km away, Silent, Impostor = wrong key, Late = 3 s delay), QR drawn over the centre of the drone picture so detection usually continues.
6. `net/`: `ChallengeClient` (the one awaited call), `EventClient` (never awaited, dropped on failure).
7. Laptop (Tier 2): `clikart-cli serve` for `HttpDetector`, `eval/run_eval.py` and the SC1 second device type; optional FastAPI polling dashboard; scrcpy for the judges' view.

### Critical Pitfalls

1. **Online licence dies in airplane mode (Pitfall 1, 2).** Both credential kinds look alike and the CLI's `--version`/`list` prove nothing. Run `clikart-cli --offline ... detect` on the laptop with Wi-Fi off before H1, then the first detection on phone A in airplane mode at H1 (the Android runtime binds the licence to `ANDROID_ID`, so the laptop test is not sufficient). Resolve the credential before the single idempotent `Modelverse.load`; branch on `codeName`; never log the credential; keep the debug-signed build for the demo (a new signing key changes `ANDROID_ID`).
2. **The demo drifts to the laptop (Pitfall 44, 34).** `HttpDetector` must be unselectable in the demo build; at a failed H4 checkpoint spend one hour on the ordered checklist (licence code name -> model path and files -> RGBA stride/channel dump compared with the CLI on the same PNG -> label ids -> threshold) before switching the development path. Phone B hosts the hotspot (the RSSI gate needs the responder to be the AP; Windows refuses a hotspot without an uplink); laptop joins as a client; A disables "switch to mobile data"; a health poll gates the Challenge button.
3. **Frame fidelity and labels (Pitfalls 10-15).** Honour `rowStride` (or use the stride-aware `ImageProxy.toBitmap()`), dump one frame to PNG at H2 and compare boxes with `clikart-cli detect`; lock the observer Activity to landscape and rotate the bitmap yourself from `rotationDegrees` (do not rely on `setOutputImageRotationEnabled` on 1.4-1.6, known bug); bind Preview and ImageAnalysis in one `UseCaseGroup`/`ViewPort` with the same 16:9 aspect and test a ring at the frame edge; request 1280x720 (the 640x480 default cannot resolve a QR); map classes by `labelId` and assert `capabilities.labels[4] == "aeroplane"` at load.
4. **Phone-as-drone prop and QR geometry (Pitfalls 16, 20-23).** RT-DETR will box phone B as `cell phone`; the drone picture inside a 70 mm screen is about 40 px after the 640x640 resize. Decide the prop at H3; show only allowlisted classes; de-duplicate overlapping boxes keeping the drone-class one; keep the box sticky through the QR window. A 37-byte payload fits version 3 (29 modules); ML Kit needs >= 2 px per module, so decode at 0.5-0.7 m with 720p frames (or 1080p / a full-res `ImageCapture` for more reach); B at 100 % brightness, light theme, auto-brightness off; scan every frame while CHALLENGED, accept only a `boundingBox` inside the box and the nonce prefix of that box.
5. **Protocol correctness traps (Pitfalls 24-29).** MAC over fixed byte layouts only; vectors generated by a 40-line Python script (never by the Kotlin under test); `ctEquals`/`MessageDigest.isEqual`; `SecureRandom()` (the "Crypto" provider is gone on API 28+); nonce store keyed by hex, pruned, with `boxId`; day derived from the challenge `t` in UTC (midnight UTC is 09:00 KST) while the observer keeps today +/-1; `challengerId` inside both MAC inputs; `:iff` free of `android.jar` stubs.
6. **Build and demo-day traps (Pitfalls 32-33, 36-43).** Cleartext opt-in and `INTERNET` permission; IO dispatcher with explicit timeouts; KGP 2.2.10 everywhere, `buildConfig = true` for the licence field, no `kotlin-android`, versions frozen after H1; responder kept in the foreground with `FLAG_KEEP_SCREEN_ON` and a process-singleton server; scripted airplane-mode sequence (airplane on -> Wi-Fi on -> join -> green); 10-minute soak for thermal throttling; Reset button; backup video; one honesty sentence each for "QR stands in for a modulated optical beacon" and "what pre-existed vs what was built today".

## Implications for Roadmap

The phases are the hour plan in PROJECT.md; PITFALLS.md's P0-P6 labels are given for cross-reference. H12-14 is sleep and is not a phase. Each phase lists a **core** (must ship inside the slot) and a **tail** (ships only if the slot has time left); when the slot ends, the tail is cut without discussion. Hour costs are FEATURES.md estimates.

### Phase 1 (H0-1): Foundation (P0)

**Rationale:** everything downstream rests on three facts only tests can establish (the credential works offline on the phone, the model directory loads from the phone, the three-device LAN works with phone B as the AP) and on Gradle wiring that costs hours later if wrong (KGP version, JVM target, `buildConfig`, cleartext). One hour is tight; the licence and LAN tests are the non-negotiables, the Debug screen may slip into H1-2 without harming H4.
**Delivers:** `settings.gradle.kts` with `:iff`; version catalog entries (ClikaRT, CameraX 1.6.2, ML Kit, ZXing, NanoHTTPD, OkHttp, coroutines 1.10.2, `kotlin-jvm` plugin at 2.2.10); `:iff` skeleton with one passing test (`./gradlew :iff:test`); `abiFilters arm64-v8a`; `buildConfig = true` + `BuildConfig.CLIKA_RT_LICENSE` from `local.properties`; manifest (`CAMERA`, `INTERNET`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `ACCESS_FINE_LOCATION`, `usesCleartextTraffic`, `largeHeap`, TTS `<queries>`); `AppRuntime`, `RuntimeLane`, `LicenseStore`, `ModelLocator`; "hello runtime" Debug screen (version, backends, codeName on refusal); RT-DETR snapshot pushed and verified with `adb shell ls -l` (three files, laptop byte sizes); laptop `clikart-cli ... serve --offline --host 0.0.0.0` answering `curl -F image=@x.jpg`; Windows firewall rule; GitHub repo with `.gitignore` for licence, keys, `local.properties`, model files; `activityCompose` bumped to 1.13.0 in the same commit.
**Addresses:** SEC-01, JDG-04, the prerequisites of DET-05 and RSP-01.
**Avoids:** Pitfalls 1 (laptop Wi-Fi-off test), 3, 8, 9, 18 (OWLv2 stays on the laptop), 32, 34 (topology test), 36, 37, 38, 39, 44 (the checkpoint rule is written down now).
**Uses:** STACK.md "Installation" and section 10 verbatim.
**Exit criterion:** ClikaRT loads on phone A in airplane mode without `LICENSE_SERVER_UNREACHABLE`; the model dir is found; `:iff:test` green; serve answers curl; A -> B and laptop -> B reachable on B's hotspot with A in airplane mode + Wi-Fi; a first RSSI reading at demo distance written down.
**Core / tail:** core = licence test, model push, LAN test, Gradle wiring, repo; tail = Debug screen polish, firewall for the dashboard port.

### Phase 2 (H1-4): On-device detection, hard checkpoint at H4 (P1)

**Rationale:** live boxes on the phone are the SC1 proof and the base of every later feature (box lock, in-box QR). All CameraX pitfalls and the label map live here, and the prop decision must happen at H3, not H15. The slot is over-subscribed (DET-01..06 + OBS-04 + PIT-04 = about 4.75 h nominal in 3 h), so `HttpDetector` is built only if the checkpoint fails, the status strip ships minimal, and the box lock is the tracker's coast rule.
**Delivers:** `Detector` + `ClikaRtDetector` (load on the lane, `CLIKA_RT_NUM_THREADS` = big-core count, warm-up on a black frame) + `LabelMap` + `Detectors.create`; `CameraController` (Preview + ImageAnalysis RGBA_8888, `KEEP_ONLY_LATEST`, 16:9 at 1280x720, `UseCaseGroup` with the PreviewView's `ViewPort`, landscape lock, manual rotation from `rotationDegrees`); `FrameAnalyzer` (stride-aware copy, close at once, busy flag, about 1 fps); `FrameTransform`; `Tracker` (IoU, EMA, coast while CHALLENGED); `CameraScreen` + `OverlayPainter` (`drone · ?`, `person`, per-class thresholds 0.25-0.35 drone / 0.5 person); status strip with ms/frame, fps, OFFLINE badge; Debug static-image harness (`ImageInput.Encoded(asset)`, parity with `clikart-cli detect`); JVM tests for `Tracker` and `FrameTransform`.
**Addresses:** DET-01, DET-02, DET-03, DET-04, DET-06 (as coasting), OBS-04, PIT-04 (prop decided at H3); DET-05 only on checkpoint failure.
**Avoids:** Pitfalls 1 (phone airplane test), 2, 4, 5 (cpu), 6, 7, 10, 11, 12, 13, 14, 15, 16 (H3 prop test), 17, 44.
**Uses:** ClikaRT AAR, CameraX 1.6.2, coroutines.
**Implements:** `app/`, `detect/`, `camera/`, `track/`, `ui/CameraScreen`.
**Exit criterion (H4 checkpoint):** live boxes on a person and on the chosen drone prop at about 1 fps in airplane mode; rings aligned at the frame edge; `rotationDegrees` logged as 0; one dumped frame gives the same boxes as the CLI; measured ms/frame recorded. If not met: the ordered one-hour checklist (Pitfall 44), then `Detectors.create` -> `HttpDetector` for development only; the demo stays on-device. If ms/frame > 1500: drop to 960x540, tune threads, try `vulkan:0` with CPU fallback as a timed experiment after H4.
**Core / tail:** core = DET-01..04, prop test, airplane-mode detection; tail = status strip detail, Debug harness, `HttpDetector`.

### Phase 3 (H4-7): IFF protocol module, tests first (P2)

**Rationale:** pure JVM, no phone needed (runs while APKs install); the protocol is the T1-04 deliverable and the vectors are the cheapest credible Technical Execution evidence; phone B will reuse the exact code in Phase 4. Protocol correctness traps (24-29) are all prevented here, and the only open design question (key hierarchy) is decided here because byte layouts do not change either way.
**Delivers:** `:iff` complete as listed under Architecture; `Responder` with the six mode semantics; `PositionCheck` with the three branches unit-tested on the exact demo coordinates (near-field honest, far cone honest, relayed 5 km fails the range gate); `NonceStore` keyed by hex with pruning and `boxId`; injected `Clock` and random source; test vectors `friend`, `foe_replay`, `foe_relay`, `foe_bad_tag`, `unknown_silent` plus a `skewSeconds` case, generated by `eval/gen_vectors.py` (hmac/struct, 40 lines) and replayed by `TestVectorsTest`; a QR-capacity test asserting the 37-byte response encodes to version 3; a one-line `./gradlew :iff:test` in the README.
**Addresses:** IFF-01..05, the logic half of IFF-06 and IFF-08, SEC-04.
**Avoids:** Pitfalls 21 (layout), 24, 25, 26, 27, 28, 29, 30 (math).
**Uses:** JDK crypto, JUnit 4; `:iff` main keeps zero dependencies, JSON only at test scope (kotlinx-serialization-json 1.9.0 tree API without the plugin, or `org.json:json` as `testImplementation`).
**Decision required here (user):** ARCHITECTURE.md's flag: because the responder must hold `dayKey` to verify challenge MACs and every `droneKey` derives from it, a captured drone leaks all drones' keys for that day, not "only its own key for one day" as PROJECT.md claims. Either reword the claim or adopt the one-line variant (`chKey = HMAC(dayKey, "FOF-chal")` on drones, per-drone keys placed by the observer). PROTOCOL.md must state whichever is true.
**Research flag:** none; standard HMAC/nonce/distance-bounding patterns, fully specified.
**Core / tail:** core = Codec, Keys, NonceStore, Challenger, Responder, Verifier, four vectors; tail = PositionCheck cone branch, skew vector, QR-capacity test.

### Phase 4 (H7-10): Responder mode on phone B + observer wiring (P3)

**Rationale:** first time the protocol crosses the LAN; both roles exist in one APK and share Phase 3's code. The slot is over-subscribed (RSP + OBS-02/05/10 + SEC-02/03 is about 4.2 h nominal in 3 h) and PROJECT.md also places the 2 h dashboard here, which cannot fit: the dashboard is the tail of the tail.
**Delivers:** `ResponderServer` (NanoHTTPD on :8129, `POST /challenge` -> 200 and QR, 401 on bad cmac and no QR, `GET /status`, `GET /debug/last-response` in debug builds), `ResponderState` (six modes), `QrRenderer`, `LocalIp`; `ResponderScreen` (IP:port, drone picture, QR over its centre, six buttons, "transmissions initiated: 0" counter); `ChallengeClient` (OkHttp, IO dispatcher, 2 s / 3 s, one retry); `IffCoordinator` (tap -> fresh nonce -> POST -> CHALLENGED with countdown; HTTP failure -> UNKNOWN unreachable within 3 s; 10 s -> UNKNOWN no response, never FRIEND); mode switch; `Settings` (responder URL, master key masked, challengerId, droneIds, observer/responder positions and heading, thresholds, detector kind); health poll with a green/red dot gating the Challenge button; Reset; one-phone loopback test (observer at `http://127.0.0.1:8129`, verdict through the Debug screen). Then, only if the exit criterion is met with time left: `EventClient` + FastAPI dashboard.
**Addresses:** RSP-01..06, RSP-09, RSP-10, OBS-02, OBS-05, OBS-10, SEC-02, SEC-03, IFF-08 (responder branch), the responder half of IFF-06; JDG-02/03 optional.
**Avoids:** Pitfalls 28 (buttons each exercise one path honestly), 31 (configured heading), 32, 33, 34 (health indicator), 35.
**Uses:** NanoHTTPD, ZXing, OkHttp, SharedPreferences.
**Implements:** `responder/`, `net/ChallengeClient`, `session/IffCoordinator`, `ui/ResponderScreen`, `ui/SettingsScreen`.
**Exit criterion:** phone A challenges phone B over the hotspot and B's QR appears on Honest; a key-less curl from the laptop is answered 401 and B logs "ignored: bad challenge MAC"; Silent -> `UNKNOWN · no response` after 10 s; B switched off -> UNKNOWN within 3 s.
**Core / tail:** core = server, QR render, Honest/Replay/Silent, settings, coordinator, health dot, reset; tail = Relay/Impostor/Late buttons (cheap, do them if the core is green by H9), counters, dashboard (last).

### Phase 5 (H10-12): Optical verdicts (P4)

**Rationale:** closes the loop: QR read inside the box -> verify -> ring with a reason. This is the phase with the most MEDIUM-confidence items (ML Kit offline, QR distance, RSSI API) and the one PROJECT.md requirement nobody costed (physical gates, about 0.75 h), inside a 2 h slot that already holds about 2 h of table stakes. Hence the H10 gate below.
**Delivers:** `Crop` + `QrReader` (ML Kit bundled, `FORMAT_QR_CODE`, `rawBytes`, `boundingBox`, executor callbacks, warmed up at start); QR loop on every frame while a track is CHALLENGED with detection paused; `Verifier` wired; `PhysicalGates` (RTT from POST sent to first valid read > 2.5 s -> `FOE · late response`; same bytes in two consecutive crops at 15-90 % of the box width -> else `FOE · optical mismatch`; hotspot RSSI outside the calibrated near-field band with a near-field claim -> `FOE · signal mismatch`); rings/tags with reasons; verdict toast listing checks and round-trip time; on-phone VerdictLog with counts; ZXing decoder fallback ready behind the same callback; event POST if the dashboard exists; audio chime as the last stretch.
**Addresses:** OBS-01, OBS-03, OBS-06, OBS-08, DET-06/07 (lock and persistence), IFF-06 Relay and IFF-08 Impostor on stage, the physical-layer requirement; DET-08 stretch.
**Avoids:** Pitfalls 14 (distance), 16 (sticky box, QR over the picture), 20, 21, 22, 23.
**Uses:** ML Kit 17.3.0 bundled, ZXing fallback, `WifiInfo.rssi` via `NetworkCapabilities.transportInfo`.
**Implements:** `optical/`, `session/PhysicalGates`, `ui/OverlayPainter` verdict states, VerdictLog, `net/EventClient`.
**Exit criterion:** the full six-verdict demo (FRIEND, replayed nonce, position mismatch, no response, bad tag, late response) on two phones in airplane mode, three times in a row after Reset; green ring within 1 s of the QR appearing; a second QR shown outside the box is ignored.
**H10 gate:** if Phase 2 and Phase 4 are not green at H10, this phase ships only FRIEND / Replay / Silent plus rings and the log; Relay, Impostor, Late, physical gates, toast and chime move to the H14-17 slack in that order.
**Core / tail:** core = in-box decode, Verifier wiring, rings with reasons, log, three verdicts; tail = physical gates, six verdicts, toast, chime.

### Phase 6 (H14-17): Eval, hardening, rehearsal (P5)

**Rationale:** after sleep the demo exists; now make it repeatable and measured. This slot has slack (about 2.5 h of table stakes in 3 h) and absorbs whatever Phase 5 deferred; the eval table is the first thing cut inside it.
**Delivers:** 10-20 image eval set with hard negatives (4 drones incl. one photographed from B's screen, 2 birds, 1 aircraft, 1 kite, 2 people-only scenes) and JSON ground truth; `eval/run_eval.py` against `serve` (RT-DETR at 0.3 vs OWLv2 at 0.1 with prompt `drone,quadcopter,uav`; precision/recall per detector at IoU >= 0.5; label mapping by id identical to the app); thresholds tuned into Settings; EVL-03 numbers (load time, ms/frame, fps, APK size, RAM, phone model); 10-minute soak test with per-minute latency; three timed airplane-mode run-throughs of the 3-minute script with the failure path rehearsed; scrcpy mirror plus `--record` backup video and a phone-filmed video of both devices; README (pre-existing vs built today, setup, exact adb push and serve commands, offline demo steps, verdict table, `./gradlew :iff:test`); build type frozen (debug-signed); demo-day checklist (both phones on automatic time before going offline, 100 % brightness on B, DND, auto-rotate off, >= 80 % battery, hotspot idle-off disabled, "network blocked" defined as no internet path exists).
**Addresses:** EVL-01, EVL-02, EVL-03, JDG-01, JDG-04 shown in the demo, PIT-01, PIT-03, PIT-05, plus anything deferred from Phase 5.
**Avoids:** Pitfalls 8 (freeze), 17 (venue light), 19, 20/23 (stage layout), 27 (clock sync), 30/31 (venue positions), 34 (checklist), 40, 41, 42, 44.
**H14 gate:** if the six-verdict demo is not green at H14, the eval table is cut first and its 1.75 h go to the demo; EVL-03 numbers and the one-line honest statement ("COCO has no drone class; the proxies are aeroplane/bird/kite") carry most of the eval's pitch value.
**Research flag:** none; plain `requests` against a live-captured API.

### Phase 7 (H17-20): Buffer, PROTOCOL.md, pitch, submission (P6)

**Rationale:** about 2 h of documented work in a 3 h slot; the remaining hour is the plan's only true buffer and must stay unallocated until H17.
**Delivers:** `docs/PROTOCOL.md` (FOF-1 spec with byte layouts, verify order, threat table: beacon exposure, harvest, replay, relay/MITM, attribution, key capture as actually true after the Phase 3 decision, clock drift, GPS-denied degradation; positioning against Mode 4/5, Remote ID, BCIS/BTID, IR beacons and tape, laser IFF, FAFOS, ReDroId; production path: Ed25519 per-drone signatures, IR OOK optical channel, key distribution, fine-tuned detector); pitch script with the two honesty sentences and the closing line; Q&A card with the ten likely questions from FEATURES.md; final rehearsal; submission with the repo link and video.
**Addresses:** PIT-02, PIT-06, submission.
**Avoids:** Pitfalls 40 (wording), 43.
**Research flag:** none; FEATURES.md's competitor table is the source material.

### Phase Ordering Rationale

- **Dependencies force the order:** OBS-03 (in-box QR) needs DET-06 (box lock) needs DET-04 (overlay) needs DET-01/02 (preview + detector); OBS-03 also needs RSP-03 (QR render) and IFF-03 (verdicts), both of which need IFF-01. Detection first, protocol second, responder third, optical last is the only topological order, and it is the hour plan.
- **The `:iff` module sits at H4-7 because it needs no phone:** it fills the slot while 300 MB APKs install and lets a failed H4 checkpoint be debugged in parallel without blocking protocol work; Phase 4 then reuses it on phone B unchanged.
- **Pitfalls are front-loaded by kind:** licence, install, LAN and Gradle traps at H0-1; every CameraX and label trap at H1-4; every crypto trap at H4-7; network traps at H7-10; optical traps at H10-12; demo-day traps at H14-20. Each phase's exit criterion is the verification column of PITFALLS.md's phase mapping.
- **The budget shape dictates the gates:** table stakes are about 17.2 h of 18; H1-4 and H7-10 are each about 1.5 h over their slots while H14-20 holds about 3 h of slack. Gates at H4 (on-device detection), H10 (two phones exchange a verdict) and H14 (six verdicts green) decide what the slack buys. Differentiators are built in keep order only after a gate passes.
- **Cut order (recommendation, needs the user's confirmation and a PROJECT.md amendment):** laptop dashboard (JDG-02/03) -> audio alert (DET-08) -> OWLv2 option (DET-09) -> eval table (EVL-02) -> Relay button (IFF-06). The dashboard is first because the user's correction makes the devices the demo, scrcpy gives the judges a live view with no code, and `clikart-cli serve` already satisfies the SC1 second device type; Relay is last because "resist MITM" is in the T1-04 wording and the range-gated version costs about 0.5 h once `PositionCheck` exists. One conditional: if the H3 prop test finds no COCO-boxable target, the fix is a different prop and lower thresholds, never OWLv2 on the phone.

### Research Flags

Which flags belong to which hour-plan phase:

| Hour-plan phase | `/gsd:plan-phase --research-phase`? | Why | Empirical gate that replaces research |
|---|---|---|---|
| Phase 1 (H0-1) Foundation | No | Stack compile-verified; wiring copied from the ClikaRT samples; serve API captured live | Licence offline on laptop and phone; model push verified by byte size; three-device hotspot test with B as AP (airplane-mode coexistence is LOW confidence, OEM-specific); first RSSI reading |
| Phase 2 (H1-4) Detection | No | ClikaRT API read from the binding sources; CameraX patterns from official docs; remaining unknowns are phone-specific | H2 frame dump vs CLI (stride, channel order); ms/frame measurement; H3 prop test; `rotationDegrees == 0` and `cropRect` semantics; H4 checkpoint |
| Phase 3 (H4-7) IFF module | No | Standard HMAC/nonce/range-gate patterns, byte layouts fixed, vectors specified | User decision on the key-hierarchy claim |
| Phase 4 (H7-10) Responder + wiring | No | NanoHTTPD/OkHttp are trivial; hotspot already tested at H0 | A -> B challenge over the hotspot; key-less curl refused; Silent -> UNKNOWN; health dot |
| Phase 5 (H10-12) Optical verdicts | Conditional | ML Kit 17.3.0 offline behaviour (MEDIUM: its POM pulls Play dependencies), RSSI availability per OEM (MEDIUM), QR distance (computed, not measured). Fallbacks are already specified (ZXing decoder; drop the signal-mismatch claim), so research only if both fail | Airplane-mode scan at H10; QR distance test at the stage layout; six verdicts three times after Reset |
| Phase 6 (H14-17) Eval + rehearsal | No | Plain Python against a live-captured API; rehearsal is procedure | Soak test; three timed runs; backup video exists |
| Phase 7 (H17-20) Pitch | No | Threat table and competitor positioning already researched in FEATURES.md | Rehearsal under 3 minutes |

Phases with standard, well-documented patterns (skip research-phase): Phases 3, 4, 6, 7. Phases 1 and 2 need test-first plans, not research. Phase 5 is the only conditional flag.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Scratch build compiled every library against the real AAR and `:iff:test` passed; versions from Maven metadata and POMs; serve API live. Adversarial pass found and fixed four real omissions (cleartext opt-in, base64url vs raw-bytes QR, string-keyed class map, missing RSSI gate). Unverified: ML Kit bundled artifact under airplane mode (MEDIUM), on-device latency / Vulkan / compass sign (LOW), hotspot under airplane mode (LOW). |
| Features | MEDIUM-HIGH | ClikaRT, ML Kit, QR and version facts HIGH (local sources, official docs); IFF-system lineage MEDIUM (secondary sources); judge-rubric weighting LOW-MEDIUM (inferred from analogous defence hackathons); hour estimates are estimates; the physical-layer gates were not costed. Adversarial pass fixed `serve --host`, cleartext, the `internal` pipeline constructor and the hour total. |
| Architecture | HIGH (ClikaRT), MEDIUM (CameraX coordinates), LOW (hotspot/adb on specific phones) | Adversarial pass corrected five drifts from PROJECT.md (QR payload, four vs six buttons, missing physical gates, 1 km/50 m/2 km vs 3 km/30 m/5 km, silently changed key derivation), which is why the file now matches PROJECT.md and why its one remaining security flag (key hierarchy) deserves a decision. `cropRect` under output rotation unverified on a device. |
| Pitfalls | MEDIUM-HIGH | ClikaRT behaviour HIGH (sources, AAR, CLI run today); CameraX / ML Kit / AGP / platform HIGH-MEDIUM (official docs); hotspot, airplane mode, `adb push` into `Android/data`, RGBA row padding in practice LOW-MEDIUM (community only). Line references and the raw-bytes / six-button / +/-1-day points were corrected in place. |

**Overall confidence:** MEDIUM-HIGH. Design and wiring are as verified as desk research can make them; what remains is phone-specific and is covered by the gates above.

### Conflicts between the research files, resolved here

The roadmapper should take these as decided unless the user overrides:

- **`:iff` JVM target:** 11 (STACK, ARCHITECTURE; compile-verified with the app at 11), not 17 (PITFALLS). A library variant with a higher `org.gradle.jvm.version` than the consumer fails Gradle's variant check.
- **CameraX version:** 1.6.2 at H0 (STACK compile-verified; PITFALLS recommends it), not "stay on 1.4.1" (FEATURES, ARCHITECTURE). Same API surface, carries the 1.5.x fixes; frozen after H1; fall back to 1.4.1 only if resolution fails at H0.
- **Rotation:** landscape lock plus manual bitmap rotation from `rotationDegrees` (PITFALLS, STACK), not `setOutputImageRotationEnabled(true)` (ARCHITECTURE), which has a known bug on 1.4-1.6.
- **Analysis resolution:** 1280x720 default (STACK, ARCHITECTURE, ML Kit guidance) with 1920x1080 as the Phase 5 escalation if the QR distance test demands it (PITFALLS' recommendation); demo at 0.5-0.7 m either way.
- **JSON inside `:iff`:** main source has zero dependencies and exposes `ByteArray`/data-class APIs (ARCHITECTURE); JSON only at test scope for the vectors; vectors generated by Python (PITFALLS 24), not by the Kotlin under test. STACK's kotlinx-serialization-plugin-in-`iff` layout is compile-verified and remains an acceptable alternative.
- **Challenge over HTTP:** `text/plain` base64url of the 31 fixed bytes (ARCHITECTURE) rather than a JSON envelope (STACK); fewer moving parts, MAC input unchanged.
- **Ports:** responder :8129, laptop serve :8000 (RT-DETR) and :8001 (OWLv2), dashboard :8080; one choice, written into Settings, README and the firewall rule (the files disagree only because each picked arbitrarily).
- **Cut order:** FEATURES' amended order (dashboard first, Relay last) over PROJECT.md's; pending user confirmation.
- **Hotspot host:** phone B (all files after correction); the third-phone / travel-router fallback keeps HTTP working but loses the RSSI gate, and the pitch must then drop that claim.

### Gaps to Address

- **Offline licence on phone A (LOW until tested):** laptop Wi-Fi-off `detect` before H1, phone A airplane-mode detection at H1; if online-only, request an offline credential from CLIKA at once and keep building against the laptop `serve` without changing the demo plan.
- **Hotspot under airplane mode, client isolation, DHCP lease changes (LOW, OEM-specific):** H0 three-device test with the real demo settings; A answers "stay connected" to the no-internet prompt and disables adaptive connectivity; B shows its IP and A polls `/status`.
- **On-device RT-DETR latency and thread count (LOW):** measure at H2-3; variants in STACK.md (960x540, big-core thread count, `vulkan:0` experiment after H4 with CPU fallback). Slower inference degrades to lower fps, never to a queue.
- **Demo prop (Pitfall 16 / PIT-04):** H3 ten-minute test of printed photo vs tablet vs toy quadcopter; whichever RT-DETR boxes as aeroplane/bird/kite at score >= 0.25 at 0.5-0.7 m with the QR inside the box wins. OWLv2 on the phone is not the fallback.
- **RGBA row stride and channel order (MEDIUM):** H2 dump of one frame to PNG, visual check with a red object, CLI parity on the same PNG.
- **`cropRect`/rotation semantics on the target phone (MEDIUM):** landscape lock; assert `rotationDegrees == 0` once per session; edge-ring test at H3.
- **ML Kit 17.3.0 bundled in airplane mode (MEDIUM):** one scan test at H10; ZXing decoder on the same crop is the swap.
- **QR read distance (computed, not measured):** Phase 5 distance test; levers are 1080p frames, a tablet as B, or a full-res `ImageCapture` on tap.
- **RSSI availability and near-field band (MEDIUM):** first reading at H0, calibration at the rehearsal distance; if the API returns nothing on the OEM, drop `FOE · signal mismatch` from the demo and the pitch.
- **Key-hierarchy claim (design):** user decision at H4-7 (see Phase 3); affects PROTOCOL.md and the "captured drone" Q&A answer.
- **PROJECT.md amendments implied by the research:** dashboard demoted to optional and removed from the demo script's critical path; FOF-1 text "base64url about 54 chars, QR version 3" corrected to raw 37 bytes in byte mode (the Key Decisions table already says so); cut order; `INTERNET` permission and cleartext opt-in listed as H0 work; physical gates costed at about 0.75 h in H10-12.
- **Hour budget (estimates):** H1-4 and H7-10 are each about 1.5 h over; the roadmap must carry the core/tail split per phase and the three gates, and must not pre-spend the H14-20 slack on the eval table or the dashboard.
- **Compass sign and indoor accuracy (LOW):** configured `heading0` is the demo default; the live compass is flavour with its accuracy flag; the cone branch is never exercised indoors.
- **KGP 2.2.10 on Gradle 9.4.1:** verified empirically in the scratch build though JetBrains' support table stops at 8.14; expect at most a warning; confirm with `./gradlew :iff:test :app:compileDebugKotlin` at H0-1.
- **`adb push` into `Android/data` on Android 13+ (LOW):** `run-as` copy into `filesDir` is the fallback; shipping the three model files as assets copied on first launch is the zero-adb alternative.

## Sources

### Primary (HIGH confidence)
- Local ClikaRT 0.6.4 binding sources `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources` (`Types.kt`, `Auto.kt`, `Pipelines.kt`, `Modelverse.kt`, `VisionModel.kt`, `Listeners.kt`, `Devices.kt`, `ClikaRt.kt`, `androidMain/.../ModelverseAndroid.kt`, `ClikaRtAndroid.kt`): load/detect/threading contract, `ImageInput.Rgba8888` layout, `DetectOptions` defaults, `Detection.labelId`, licence code names, `CLIKA_RT_NUM_THREADS`, `ANDROID_ID` binding, backend probe semantics.
- ClikaRT samples `C:/Users/C/clika/reference/examples-0.6.4/android` (`RuntimeLane`, `SampleRuntime`, `LicenseStore`, hello/chat/serve apps, `hello/build.gradle.kts` licence field and `abiFilters`) and `chat_with_pictures`; `modelverse-server-headers/serve_api.h`, `detection_engine.h`; the Windows CLI README (online vs offline licence).
- The AAR in the Gradle cache (manifest minSdk 28, arm64-v8a only, five `.so` = 284 MB, consumer `proguard.txt`, `.module` requiring kotlin-stdlib 2.3.0); the RT-DETR snapshot `config.json` (`id2label`: 0 person, 4 aeroplane, 14 bird, 33 kite, 67 cell phone) and `preprocessor_config.json` (640x640); the OWLv2 snapshot (960x960, 620 MB).
- Live runs on 2026-10-10 of `clikart-cli` 0.6.4 (`--help`, `serve --help`, `detect --help`, `serve` for RT-DETR and OWLv2: `/health`, `/props`, `/v1/models`, `POST /v1/detections` success and error bodies; RT-DETR 0.37 s and OWLv2 about 5-10 s per image on 12 CPU threads).
- Scratch verification build (AGP 9.2.1 + KGP 2.2.10 + Gradle 9.4.1): `:app:compileDebugKotlin` over ClikaRT 0.6.4, CameraX 1.6.2, ML Kit 17.3.0, ZXing 3.5.4, NanoHTTPD 2.3.1, OkHttp 5.5.0, coroutines 1.10.2, `project(":iff")`; `:iff:test` green (RFC 4231 HMAC vector); the project's own `./gradlew buildEnvironment` (AGP 9.2.1 -> KGP 2.2.10).
- Official docs: CameraX (`ImageAnalysis` Javadoc, configuration, transform-output, analyze, releases), ML Kit barcode scanning (bundled vs unbundled, 2 px/module, 1280x720 guidance, `Barcode.getRawBytes`), Android network security config (cleartext off at API 28+), AGP 9.0/9.2 release notes and built-in Kotlin migration, Kotlin Gradle compatibility, `TextToSpeech.Engine` queries, Android 9 Crypto provider removal, Android 8 `ANDROID_ID`, cached-apps freezer, foreground service types, position sensors; QR capacity tables; Google Maven / Maven Central / PyPI metadata and POMs for every pinned version.

### Secondary (MEDIUM confidence)
- IFF lineage and positioning: DSCA 18-14 and Indra datasheet (Mode 5), UK Defence Journal (Mode 4 retirement), ADS-B security surveys, RFC 9575 and Remote ID spoofers, BCIS/BTID reports, IR beacon and tape vendors, laser-IFF patents, FAFOS press (Oct 2025), ReDroId, OCC/Ford optical ID papers, UA DroneID press, Seeing-Is-Believing, Hancke-Kuhn distance bounding.
- Detection practice: SAHI, Drone-vs-Bird challenge papers, trajectory-based bird/drone separation, Anti-UAV / DUT Anti-UAV / TDTIV benchmarks, COCO-pretrained drone-as-bird confusion.
- Judging analogs: NDIA Global Hackathon criteria, Air Force Hack the Ranch; no public source for the T1/SC1 brief (facts from PROJECT.md).
- Platform details not re-fetched in the verification passes: `MessageDigest.isEqual` constant time, `setOutputImageRotationEnabled` behaviour on 1.4-1.6 beyond the 1.7.0-alpha01 note, `URLConnection` default timeouts, `domain-config` for numeric IPs.

### Tertiary (LOW confidence, needs validation)
- Hotspot under airplane mode per OEM; Windows Mobile Hotspot uplink requirement; Android dropping Wi-Fi without internet; hotspot client isolation (community forums).
- `adb push` refused into `Android/data` on Android 13+ (forum report).
- RGBA row padding "green lines" symptom (forum report); CameraX guide's A,R,G,B wording vs the binding's R,G,B,A.
- OWLv2 CPU slowness order of magnitude (HF forum); Android TTS voice-data download under airplane mode (forum).
- QR readable-distance table and 70 deg horizontal FOV (computed from ML Kit's 2 px/module rule, not measured); indoor compass accuracy +/-15-20 deg; RT-DETR latency on the target phone (unmeasured).

---
*Research completed: 2026-10-10*
*Ready for roadmap: yes*
