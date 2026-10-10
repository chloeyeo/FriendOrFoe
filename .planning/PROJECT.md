# FriendOrFoe

## What This Is

An Android app for tank and vehicle crews that detects drones with the phone camera, on-device and offline (ClikaRT), and runs a replay-proof, relay-resistant, radio-silent friend-or-foe (IFF) check on each detected drone, drawing a green ring (FRIEND · id) or a red ring (FOE · reason) on the exact object in the image. A second Android phone plays the friendly drone (responder) and answers the challenge optically with a QR code; a laptop shows a live dashboard for the judges and runs the same detector through `clikart-cli serve` for the eval table.

Built during a hackathon (today, 2026-10-10, 20 hours serial, one developer) for main track **T1 UAS & CUAS** (guidance item **T1-04 Radio-silent IFF**: "a cryptographic two-component IFF scheme that identifies friendly and enemy drones with minimal RF emission; resist MITM and replay") and the independent sub-track **SC1 CLIKA BYOD Offline AI Challenge** (app built with ClikaRT, live demo with network blocked, code repository; bonus for two or more device types).

## Core Value

Point the phone at a drone and, within seconds and with no network, see a green ring or a red ring with a reason on that exact object — while the drone itself transmits nothing until it is asked, and its answer only works for the one observer who asked, at the place it is actually flying.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Observer phone detects drones and people in the live camera preview, on-device via the ClikaRT Kotlin AAR, fully offline (airplane mode), at roughly 1 fps, with labelled bounding boxes (COCO person → person; airplane, bird, kite → drone; OWLv2 zero-shot "drone"/"quadcopter" as the accuracy option).
- [ ] Each drone box carries an IFF tag: `?` until challenged, then `FRIEND · id` (green ring) or `FOE · reason` (red ring) or `UNKNOWN · no response` (red ring).
- [ ] Tapping a drone box sends one authenticated challenge (fresh 128-bit nonce, time, challengerId, challenge MAC) over the hotspot LAN (HTTP) to the responder.
- [ ] Responder (second phone, same app in Responder mode) answers only authentic challenges, and answers optically: a QR code on its screen carrying {droneId, nonce prefix, quantized position, 16-byte HMAC tag}. It never transmits RF on its own initiative.
- [ ] Observer decodes the QR only inside the challenged object's bounding box (ML Kit barcode), then verifies: tag recomputes (authentic, bound to this challenger), nonce issued/unused/under 10 s (replay), claimed position inside the box's bearing/elevation cone, ±20° (relay/MITM), read from inside the box (attribution).
- [ ] Responder has demo buttons: Honest / Replay last answer / Relay (far-away position) / Silent / Impostor (wrong key) / Late (3 s delay), producing the verdicts FRIEND, FOE·replayed nonce, FOE·position mismatch, UNKNOWN·no response, FOE·bad tag, FOE·late response.
- [ ] Physical-layer evidence (added 2026-10-10 at the user's request): the observer measures the challenge-to-QR round-trip time and rejects late answers (relay latency), reads the Wi-Fi link RSSI of the responder's hotspot and rejects answers whose signal strength disagrees with the near-field claim, and requires the QR to persist inside the box for two consecutive frames at a plausible size. Crypto proves who, geometry proves where, physics proves when and how near.
- [ ] The IFF protocol lives in a pure Kotlin module with no Android dependencies, unit-tested with deterministic JSON test vectors for all four verdicts, so any other responder implementation can be checked against it.
- [ ] Observer posts every detection and verdict ({t, class, box, iffStatus, droneId, reason}) to the laptop dashboard over the LAN; the dashboard shows a live list, counts (drones / people / friend / foe / unknown) and the latest frame thumbnail with rings.
- [ ] The same detector runs on the laptop through `clikart-cli … serve --offline` (`POST /v1/detections`) as the second ClikaRT device type and as the backend of the eval script.
- [ ] Eval: 10 test images, `eval/run_eval.py` against the served detector, precision/recall per detector (RT-DETR v2 r18 vs OWLv2); the eval decides which detector ships.
- [ ] `Detector` interface with `ClikaRtDetector` (production) and `HttpDetector` (dev + contingency); swapping is one config line and nothing else changes.
- [ ] Audio/visual alert on first drone detection ("Drone, bearing 045, high"; bearing from box position + compass) — stretch, first to cut.
- [ ] README (setup, models, offline demo steps, what was built during the hackathon) and docs/PROTOCOL.md (the IFF spec with the threat table: beacon exposure, replay, relay, attribution).
- [ ] Airplane-mode run-through of the full demo script three times, plus a recorded backup video.

### Out of Scope

- Real RF link to a drone — the demo uses a phone hotspot (HTTP) for the challenge and a QR code for the optical response; the pitch states that QR stands in for a modulated optical beacon.
- Flight control, engagement or weapon logic — detection and identification only.
- Multi-observer key management, PKI, Ed25519 per-drone signatures — documented as the production upgrade path in PROTOCOL.md, not built.
- A trained drone-specific detector — COCO classes and OWLv2 zero-shot queries only; no training in 20 hours.
- iOS — Android only (ClikaRT AAR is the SC1 requirement).
- On-off-keyed LED/screen blink optical channel — stretch only, after everything else works.
- Laptop drone simulator web page — replaced by the second phone in Responder mode (decision 2026-10-10).
- Raspberry Pi / ESP32 responders — not in v1; the JSON test vectors make a later port cheap.
- Any integration with the teammate's "H-Beacon" handoff (Supabase backend, tag vocabulary, risk score, signal fusion) — the user decided on 2026-10-10 to not use that handoff at all and to keep the previous plan; do not re-add it.

## Context

**Hackathon framing.** Stage 1 judging is a 3-minute pitch plus 2-minute Q&A, no slides: Problem Fit 25%, Military Deployability 30%, Technical Execution 25%, Creativity 20%. The pitch must separate what existed before the hackathon from what was built during it; everything in this repo is built during. Submission is at the track level (T1); SC1 is judged independently.

**Problem as the team framed it.** Classic IFF (challenge "tiger", answer "lion") has four failures: constant beaconing reveals position; replay (record a friendly's answer, or ask "tiger" first to harvest "lion"); with several drones in the air an RF answer cannot be attributed to one drone; RF beacons give no direction. Tanks are blind upward and loud, crews cannot see drones coming, so camera-based detection alone has value. Camera video is the primary sensor; RF and audio are noisy.

**Demo topology (decided 2026-10-10 after the user's correction that the demo runs on connected devices, not a laptop simulator).**
- Observer: Android phone A, the tank-crew app. Airplane mode, hotspot/Wi-Fi only.
- Responder: Android phone B, the same app in Responder mode, standing in for the friendly drone. Receives the challenge over HTTP on the hotspot LAN, shows the QR answer on its screen, has the four attack buttons. Shows its own IP on screen; the observer enters it once.
- Laptop (Windows 11): the FastAPI dashboard (`/dashboard`, `/api/events`) judges watch next to the phones, and `clikart-cli … serve --offline --device cpu` for the eval script and as the second ClikaRT device type (SC1 bonus). Not in the critical path of the IFF demo.
- All three on one LAN with no internet. Default: **phone B (responder) hosts the hotspot** with mobile data off, phone A (airplane mode, Wi-Fi re-enabled) and the laptop join it, so phone A can read the link RSSI for the signal-strength check. The pitch states honestly that in the demo the drone phone carries the Wi-Fi link so strength can be measured; in production the strength measurement rides the challenge-return burst or the optical channel.

**Demo script (3 minutes).** Airplane mode on the phone, laptop Wi-Fi off except the hotspot, show `--offline` in the serve command. Point phone A at phone B (showing a drone image) with a teammate in frame: two boxes, "drone · ?" and "person"; the dashboard lists both. Tap the drone box → challenge → phone B shows the QR → phone A reads it inside the box → green ring "FRIEND · RQ-07"; dashboard updates. Press Replay → red "replayed nonce". Press Relay → red "position mismatch". Press Silent → red "no response" (unknown). Closing line: the drone transmitted nothing until asked, and its answer only works for the one observer who asked, at the place it is actually flying.

**IFF protocol FOF-1 (proposed 2026-10-10, pending the user's final confirmation; to be written up in docs/PROTOCOL.md).**
- Keys: one pre-placed 32-byte master key. Daily key = HMAC-SHA256(master, "FOF-day" ‖ YYYYMMDD). Per-drone key = HMAC-SHA256(dayKey, "FOF-drone" ‖ droneId). Observer accepts yesterday/today/tomorrow (clock drift). A captured drone leaks only its own key for one day.
- Challenge (observer → responder, one burst): {v=1, nonce (16 random bytes), t (unix s), challengerId (2 bytes), cmac = HMAC(dayKey, "C" ‖ nonce ‖ t ‖ challengerId)[0:8]}. The responder answers only authentic challenges, which closes "ask tiger first to harvest lion" and stops an enemy from making the drone light up.
- Response (responder → observer, optical, directional, short range): {v=1, droneId, nonce[0:8], pos quantized (lat/lon 1e-4 deg, alt 10 m), tag = HMAC(droneKey, "R" ‖ nonce ‖ t ‖ challengerId ‖ droneId ‖ pos)[0:16]}. The MAC input is a fixed binary layout, never JSON text; about 40 bytes, base64url ≈ 54 chars, QR version 3, readable at 1–2 m.
- Verify order on the observer: read from inside the box (attribution) → nonce issued, unused, age < 10 s (replay) → tag recomputes with the drone key for today ±1 (authentic, bound to this challenger) → position inside the box's bearing/elevation cone ±20° (relay/MITM). Any failure → FOE with that reason. No answer within 10 s → UNKNOWN, never FRIEND.
- Production path (documented only): Ed25519 per-drone signatures when the drone must hold no shared secret; on-off-keyed blink instead of QR; multi-observer key distribution.

**ClikaRT facts verified locally on 2026-10-10.**
- Kotlin artifact `io.clika:clika-runtime:0.6.4` resolves from the local Maven repo `C:/Users/C/clika` (already wired in settings.gradle.kts); the Gradle cache holds the AAR. AAR floor is minSdk 28, arm64-v8a only; native libs are large (libClikaRT.so 100 MB, libClikaRT_vulkan.so 139 MB, modelverse 19 MB, two JNI bridges 26 MB) — the APK will be roughly 300 MB uncompressed.
- API (from the sources jar): `Modelverse.load(context, license)` (Android extension in `io.clika.modelverse`), `AutoModelForObjectDetection.fromPretrained(source, VisionLoadOptions(offline=true, cacheDir=…, device=…))` → `VisionModel`; `AutoModelForZeroShotObjectDetection.fromPretrained(...)` for OWLv2; `ObjectDetectionPipeline(model)(ImageInput.Rgba8888(pixels, w, h), DetectOptions(scoreThreshold, labels))` → `List<Detection(label, labelId, score, Box(left, top, right, bottom))>` in input-pixel coordinates, score-descending; or `Modelverse.pipeline("object-detection", source, options)`. `ImageInput.Encoded(bytes)` takes PNG/JPEG. Load blocks; call off the main thread. `ClikaRtAndroid.bridgeLogs()` routes runtime logs to logcat; `ClikaRtAndroid.trimOnMemoryPressure(context)` is recommended.
- License: a `CLIKA1-…` credential is required for every process that runs a model. On the laptop it is stored per-user (`~/.clika/runtime/license`, 297 chars, not committed). On Android the app passes it to `Modelverse.load(context, license)`; the examples read it from `local.properties` (`clika.runtimeLicenseFile=<path>`) into `BuildConfig.CLIKA_RT_LICENSE`, or ask once and store it in private storage. Per the CLIKA onboarding deck (`C:/Users/C/Downloads/Clicka onboarding.pdf`, read 2026-10-10) there are two kinds: an **Online key** needs a route to the Platform at run time (fails with `PHONE_HOME_STALLED` / `LICENSE_SERVER_UNREACHABLE` without network, revocable within minutes) and an **Offline bundle** that is verified locally against CLIKA's signature, needs nothing at run time, is shown only once at issue time, and carries an expiry and hardware seats. Issue it at platform.clika.io → Licenses → + Issue License → Offline. The kind of the stored credential is not yet confirmed: check the Licenses page ("Active, Offline") or re-run `clikart-license-init --force`, which prints the kind. The demo needs the Offline kind with enough seats for the laptop and phone A; phone B's responder mode must not load the runtime so it never consumes a seat. Also noted: `python -I` cannot see user-site packages such as fastapi; run the dashboard without `-I`.
- CLI: `C:/Users/C/Downloads/ClikaRT_windows_x86_64-0.6.4/bin/clikart-cli.exe` (put `bin` and `lib` on PATH). Families `rt-detr` (rt_detr_v2) and `owlvit` (owlv2) are runnable with `detect`, `bench`, `serve`. `serve` exposes `POST /v1/detections` (multipart image, plus `prompt` for open-vocabulary) → box/score/label rows, `GET /health`, `GET /dashboard`. `--offline` and `--cache-dir` are global options. Laptop compute is CPU (12 threads); Vulkan via Mesa D3D12 fails on this laptop.
- Models: `C:/Users/C/clika/models` is the hub cache (`--cache-dir`). `PekingU/rtdetr_v2_r18vd` fetched 2026-10-10 (77 MB, snapshot `models--PekingU--rtdetr_v2_r18vd/snapshots/5650961749fa93567c0d46fc7f43ea4f9e914107`). `google/owlv2-base-patch16-ensemble` fetched 2026-10-10. The earlier belief that both were already fetched was wrong; the folder only held Qwen2.5 and Whisper. On the phone the snapshot directory is pushed with adb to the app's external files dir and loaded with `offline = true`.
- Reference material (outside the repo, durable): `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources` (Kotlin binding sources), `C:/Users/C/clika/reference/examples-0.6.4/android` (hello/chat/serve sample apps plus `shared/` with LicenseStore, SampleRuntime, RuntimeLane) and `examples-0.6.4/chat_with_pictures` (vision on Android), `C:/Users/C/clika/reference/modelverse-server-headers` (serve API routes).

**Project state.** Android Studio template project renamed from FieldReport to FriendOrFoe (`com.chloeyeo.friendorfoe`) on 2026-10-10; git initialized on `main`; `:app:compileDebugKotlin` passes. Folder on disk is still `C:\Users\C\AndroidStudioProjects\FieldReport`. Toolchain: AGP 9.2.1, Kotlin 2.2.10, Gradle 9.4.1, compileSdk 36, Compose BOM 2026.02.01, CameraX 1.4.1, ML Kit barcode-scanning 17.3.0 already declared. Laptop has Python 3.13 with fastapi 0.143 and uvicorn 0.51, uv 0.11, Node 22, gh CLI authenticated as chloeyeo, adb at the Android SDK. No phone was attached at init time.

**Team (2026-10-10 afternoon).** Three developers work in parallel: Chloe Yeo (@chloeyeo) owns video object detection, the app shell and integration; Ihan (@angmusae2015, invite pending) owns the challenge-response ("질의응답": the `:iff` module, the USB gadget link and its protocol, the responder fallback logic, PROTOCOL.md); Yoo Jongho (@ujon) owns the UI frontend (observer overlay and screens, responder screen, laptop dashboard, demo visuals). The signal is planned to come from the team's own gadgets attached to the phone over USB (OTG, CDC serial, newline-delimited JSON); phone B's QR answer is the fallback simulator. README.md is the team-facing plan with the module contracts (`Detector`, `SignalSource`, `ObserverUiState`), ownership and the branch/PR workflow. Open hardware questions (what the gadget is, whether it runs the crypto, OTG cable, seats) are listed in README section 7.

**Hour plan (the phases).** H0–1 foundation (project, AAR, repo on GitHub, models, laptop serve answering a curl). H1–4 CameraX + ClikaRtDetector + box overlay; hard checkpoint at H4 → HttpDetector fallback. H4–7 iff module with tests first. H7–10 responder mode on phone B + laptop dashboard. H10–12 QR decode in-box, tags, reasons, event posting, sound. H12–14 sleep. H14–17 eval, detector choice, airplane-mode run-throughs, backup video, README. H17–20 buffer, PROTOCOL.md, pitch rehearsal, submission. Cut order if behind: audio alert → OWLv2 option → Relay button → eval table.

## Constraints

- **Tech stack**: ClikaRT Kotlin AAR 0.6.4 in-process on the phone — SC1 requires the app to be built with ClikaRT; the laptop path only counts as the second device type.
- **Offline**: the whole demo runs with network blocked (airplane mode, hotspot LAN only) — SC1 requirement; implies offline license, models on device, no CDN at demo time.
- **Timeline**: 20 hours serial, one developer, hard checkpoint at H4 for on-device detection — every step must be minimal and runnable; cut order is fixed.
- **Devices**: arm64-v8a phones, minSdk 28 (AAR floor), large APK (~300 MB native libs) — install over USB, not over the air; no emulator (x86 has no native libs).
- **Security**: HMAC-SHA256 with pre-placed keys, fresh nonces, position binding — keys and the ClikaRT license are never committed (`*.license`, `local.properties` gitignored; `*.gguf`, `*.safetensors` excluded).
- **Compatibility**: Gradle 9.4.1 / AGP 9.2.1 / Kotlin 2.2.10 as generated by Android Studio — do not downgrade; the pure Kotlin `iff` module must build as a plain JVM module so unit tests run without a device.
- **Judging**: no slides, 3 minutes, must state what pre-existed (ClikaRT, models, Android template) versus what was built.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Rename FieldReport → FriendOrFoe, package `com.chloeyeo.friendorfoe` | User instruction; done before anything else | ✓ Good |
| Responder = second Android phone running the same app in Responder mode (not a laptop web page) | User correction 2026-10-10: demo on connected devices; shares the Kotlin iff module, no port, fits the hour plan | — Pending |
| Challenge link = HTTP over the hotspot LAN | Simplest, debuggable; the drone's Wi-Fi acks are acceptable RF for the demo; BLE/optical documented as alternatives | — Pending |
| Laptop = dashboard + eval (`clikart-cli serve`), not in the IFF critical path | Judges' live view and SC1 second device type; HttpDetector kept as contingency only | — Pending |
| Response channel v1 = QR on the responder's screen, decoded by ML Kit inside the box | Hour-planned; blink OOK is a stretch; pitch states QR stands in for a modulated optical beacon | — Pending |
| Protocol FOF-1: HMAC-SHA256, daily + per-drone derived keys, authenticated challenge, 16-byte tag, 10 s nonce window, ±20° cone | Replay/relay/harvest resistance with zero key distribution; symmetric crypto fits 20 h; Ed25519 is the documented upgrade | — Pending (user to confirm) |
| `Detector` interface with `ClikaRtDetector` + `HttpDetector` | One-line swap protects the H4 checkpoint; UI/iff/QR/eval never change | — Pending |
| RT-DETR v2 r18 (COCO) default, OWLv2 zero-shot as accuracy option; eval decides | Speed vs recall on drones; both families verified runnable in ClikaRT 0.6.4 | — Pending |
| `iff` as a separate pure-Kotlin Gradle JVM module with JSON test vectors | Tests run without a device; vectors let any responder implementation be checked | — Pending |
| Sequential plan execution in GSD | One Gradle project, one developer; parallel agent builds would fight over the Gradle daemon | — Pending |
| minSdk raised 26 → 28 | ClikaRT AAR declares minSdkVersion 28; manifest merge would fail | ✓ Good |
| QR carries the raw ~40-byte response in QR byte mode; observer reads `Barcode.getRawBytes()` | Research 2026-10-10: base64url text (54 mixed-case chars) does not fit QR version 3 byte mode; raw bytes fit version 3-L | — Pending |
| Position gate = range gate (R_max 3 km) plus near-field (< 30 m) or ±20° cone | Two phones in one room have an undefined bearing; the near-field rule makes the indoor demo deterministic while a relayed 5 km position always fails | — Pending |
| Impostor button (`FOE · bad tag`) added to the demo; dashboard kept per user choice but flagged as the research's first cut; scrcpy USB mirror added as the judges' primary live view | Impostor is 0.25 h and completes the five-verdict story; scrcpy needs no network; the dashboard is 2 h of discretionary work | — Pending |
| Class map keyed on `labelId` (0 person; 4, 14, 33 drone), not on label strings | The cached RT-DETR config spells `aeroplane`/`motorbike` VOC-style; a map on "airplane" never fires | — Pending |
| Physical-layer evidence added as a third verdict layer: round-trip timing gate (2.5 s budget, `FOE · late response`), Wi-Fi RSSI gate against the responder's hotspot (`FOE · signal mismatch`), optical persistence/size gate (`FOE · optical mismatch`) | User request 2026-10-10 ("identify by physical signal strength, use time differences to trust the incoming signal, use physical factors"); timing bounds relay latency, RSSI bounds proximity, both are cheap and visible; frame-level timing cannot bound range at light speed and the pitch says so | — Pending |
| Phone B hosts the demo hotspot | Android exposes RSSI only for the access point the phone is attached to; the observer must measure the responder, so the responder is the AP | — Pending |
| Working prototype first | User 2026-10-10: "We need a working prototype ASAP" — phases are ordered so Phase 2 already shows live boxes and Phase 3+4 give a green ring end to end; polish, eval and docs come after | — Pending |
| H-Beacon handoff not used | The user reviewed the teammate's handoff on 2026-10-10 and decided to keep the previous plan without it; the "physical signal strength + time difference" idea stays, implemented by our own PHY checks (round-trip timing, Wi-Fi RSSI of the responder's hotspot, optical persistence) | ✓ Decided |
| Team of three in parallel streams: 1 video detection (Chloe, @chloeyeo), 2 challenge-response (Ihan, @angmusae2015), 3 UI frontend (Jongho, @ujon); Chloe is integration lead | User 2026-10-10 afternoon; the repo github.com/chloeyeo/FriendOrFoe has both collaborators (one invite pending). Phases 2, 3, 4 of the roadmap run in parallel; integration checkpoints at H4, H10, H14. README.md holds the plan, module map, ownership and branch/PR workflow | — Pending |
| Signal comes from the team's own gadgets connected to the phone over USB (`SignalSource` abstraction: `UsbGadgetSignalSource`, `OpticalQrSignalSource` fallback with phone B, `FakeSignalSource`) | User 2026-10-10: "use our phone (ClikaRT app) with the phone camera, connected by USB to the gadgets we have, to do object detection and get the signal". Detection and UI are independent of which source delivers the answer; the gadget protocol (USB CDC, newline JSON, draft in README) is owned by the challenge-response stream | — Pending (gadget details open) |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-10-10 after initialization*
