# Requirements: FriendOrFoe

**Defined:** 2026-10-10
**Core Value:** Point the phone at a drone and, within seconds and with no network, see a green ring or a red ring with a reason on that exact object — while the drone transmits nothing until asked, and its answer only works for the one observer who asked, at the place it is actually flying.

Categories follow `.planning/research/FEATURES.md` (DET, IFF, OBS, JDG, EVL, SEC, PIT) plus three added on 2026-10-10: PHY (physical-layer evidence, at the user's request), FW (the firmware on the team's own gadgets) and SIG (the phone-side signal path). The RSP category (the second-phone optical responder), OBS-03 (in-box optical decode) and PHY-03 (optical persistence) were removed on 2026-10-10 when the user dropped the second-phone responder: the friendly drone is now the team's drone-side gadget (FW-06), the challenge-response and the crypto run on the gadgets in Arduino C/C++ (`firmware/iff`; the IFF and FW categories), the phone only re-checks (SIG-03, PHY-01/02), and `FakeSignalSource` (SIG-04) is the simulator and the on-stage fallback. There is no Kotlin `:iff` module. IDs are kept identical to FEATURES.md where the feature exists there so the research and the requirements can be cross-read; removed IDs are not reused. Hour estimates in FEATURES.md are for one developer; three developers now work in parallel streams (README section 1).

**Cut order if behind schedule** (user's order, fixed): DET-08 audio alert → DET-09 OWLv2 option → IFF-06 position gate together with FW-06's Relay mode → EVL-01/02 eval set and table → PHY-02 RSSI gate. Never cut: live boxes, the green/red ring with a reason, the offline run, the `selftest` vectors (FW-04) and `FakeSignalSource` (SIG-04 — it is the on-stage fallback if the gadget misbehaves). [Research recommended cutting the JDG-02/03 laptop dashboard before any of these; the user kept it on 2026-10-10, and it now belongs to the UI stream, off the critical path.]

**Three layers of evidence (user's framing, 2026-10-10):** the crypto layer (IFF, run on the reader gadget) proves *who* answered; the geometry layer (the position gate on the gadget, attribution to the tapped box on the phone) proves *where*; the physical layer (PHY: round-trip timing and the radio signal strength the gadget reports) proves *when and how near*. A verdict is FRIEND only when the gadget says FRIEND and every enabled re-check on the phone passes.

## v1 Requirements

Requirements for the hackathon submission. Each maps to one roadmap phase.

### DET — Detection & alerting

- [ ] **DET-01**: Observer sees a live back-camera preview with one continuous analysis stream (CameraX Preview + ImageAnalysis, KEEP_ONLY_LATEST, 1280x720 or 1920x1080, orientation locked), so boxes always refer to the current frame.
- [ ] **DET-02**: Observer gets drone and person detections on the phone itself from the ClikaRT AAR at roughly 1 fps, with the RT-DETR v2 r18 snapshot loaded from device storage with `offline = true`, all load/infer calls on one runtime lane off the main thread, a visible "model loading…" state, and no network access at any point.
- [x] **DET-03**: Detections are mapped by `labelId` to `person` (0) and `drone` (aeroplane 4, bird 14, kite 33) with per-class score thresholds (drone proxies ≈ 0.25–0.3, person 0.5); every other class is ignored and `cell phone` is never mapped to drone.
- [ ] **DET-04**: Boxes are drawn on the preview in correct preview coordinates (analysis-frame → PreviewView mapping including rotation and FILL_CENTER crop) with a class colour, label and score, and visibly sit on the object they belong to.
- [ ] **DET-05**: The detector sits behind a `Detector` interface; `HttpDetector` against `clikart-cli serve` (`POST /v1/detections`) is selectable with one config change as the H4 contingency and dev loop, and nothing above the interface changes.
- [x] **DET-06**: When a drone box is tapped it is locked (id + rect) for the 10 s challenge window, so the gadget's answer — which can arrive up to 10 s later — is attributed to that box even if the detector loses the object in between.
- [ ] **DET-08**: Observer hears a chime (bundled sound, not TTS) and sees a banner "DRONE · bearing 045 · high" on the first drone detection of a run. *(stretch; cut order 1)*
- [ ] **DET-09**: Observer can select the OWLv2 zero-shot detector in settings (labels "drone", "quadcopter", "uav") as the accuracy option; promoted to table stakes if PIT-04 shows COCO cannot see the demo target. *(cut order 2)*

### IFF — Challenge/response & verdicts (FOF-1, implemented on the gadgets in Arduino C/C++)

- [ ] **IFF-01**: FOF-1 is implemented on the gadget, in Arduino C/C++, in the `firmware/iff` library (there is no Kotlin `:iff` module): HMAC-SHA256 key derivation (master → day key by YYYYMMDD → per-drone key), fixed binary codecs for the challenge {v, nonce16, t, challengerId, cmac8} (31 B) and the response {v, droneId, nonce8, posQ, tag16} (37 B), with every MAC computed over the fixed byte layout and never over JSON text; the same library code runs on the reader and on the drone gadget.
- [ ] **IFF-02**: On the reader gadget a nonce ledger tracks issued → outstanding → consumed nonces with one outstanding challenge per box and an age limit of 10 s; any reuse of a nonce yields the verdict `FOE · replayed nonce`.
- [ ] **IFF-03**: The verifier on the reader gadget runs its checks in a fixed order — nonce issued, unused and fresh → tag recomputes with the drone key for today ±1 → position gate — and returns a verdict with the first failing reason; no answer within 10 s yields `UNKNOWN · no response` and silence can never yield FRIEND. Attribution (the answer belongs to the box that was tapped) is the phone's re-check (SIG-03).
- [ ] **IFF-04**: The day-key window accepts yesterday, today and tomorrow, with the day derived from the challenge's `t` (supplied by the phone) on both gadgets, so a drifting gadget clock does not flip a FRIEND to FOE at midnight.
- [ ] **IFF-06**: The position gate on the reader gadget accepts a claimed position only if it is within R_max (3 km) of the configured observer position and either within 30 m (near-field, same room) or within ±20° bearing/elevation of the box cone when the phone supplies the box bearing with the challenge (indoors it does not, and the near-field rule decides); a position 5 km away yields `FOE · position mismatch`. *(cut order 3, together with FW-06's Relay mode)*
- [ ] **IFF-08**: A response computed with a wrong key yields `FOE · bad tag`.

### FW — Firmware on the gadgets (Arduino C/C++, stream 2)

- [ ] **FW-01**: `firmware/iff` is an Arduino library (`library.properties`, `src/fof1.h`, `src/fof1.cpp`; the protocol code is plain C/C++ without Arduino headers so it also compiles on a host compiler) housing the FOF-1 codecs, HMAC-SHA256 (rweather `Crypto` or the ESP32's mbedtls), key derivation and byte layouts of IFF-01 behind a small C API used by both sketches; `arduino-cli compile` builds it for the chosen board.
- [ ] **FW-02**: The reader side of `firmware/iff` holds the nonce ledger and the verifier with ordered reasons of IFF-02/IFF-03 as C code with bounded RAM (fixed-size ledger table, pruning on insert), returning {verdict, reason, droneId, nonce} to the sketch.
- [ ] **FW-03**: The reader side of `firmware/iff` implements the position gate of IFF-06 and the physical-evidence fields: it measures the challenge-to-answer time on the board (`responseMs`, from the radio transmission of the challenge to the arrival of the answer frame) and captures the radio's RSSI for the answer frame (`rssiDbm`, `null` when the radio exposes none), and both are part of the verdict record together with the full 16-byte nonce. *(the position gate is cut order 3; the timing/RSSI fields are never cut)*
- [ ] **FW-04**: `firmware/selftest` replays every JSON test vector produced by `eval/gen_vectors.py` (Python `hmac`/`struct`, emitted as JSON under `eval/vectors/` and as a `vectors.h` header, never generated by the C code under test) — FRIEND, replayed nonce, bad tag, position mismatch, no response, plus a clock-skew case — and prints one PASS/FAIL line per vector over USB serial; every vector prints PASS on the board, and a deliberately flipped byte prints FAIL. *(absorbs IFF-05; this is the protocol's test suite and it runs on the bench over USB-C without the phone; never cut)*
- [ ] **FW-05**: `firmware/reader` is the phone-side gadget: USB CDC serial at 115200 8N1, one JSON object per line (README "Gadget link over USB", `docs/GADGET_LINK.md`); it accepts `{"cmd":"challenge","boxId":…,"challengerId":…,"t":…}` and `{"cmd":"status"}`, generates the nonce, sends one authenticated challenge over the radio, waits up to 10 s, verifies, and replies `{"type":"answer","verdict":…,"reason":…,"droneId":…,"nonce":"<32 hex>","rssiDbm":…,"responseMs":…}` or `{"type":"status","fw":…,"battery":…,"mode":…}`; it transmits nothing on the radio until a `challenge` command arrives and handles one challenge at a time (latest wins).
- [ ] **FW-06**: `firmware/drone` is the drone-side gadget: it answers only authenticated challenges (a bad cmac is ignored and counted), uses a configured demo position, and has the six attack modes Honest / Replay (re-send the previous answer) / Relay (far-away position) / Late (correct answer after a 3 s delay) / Silent (ignore) / Impostor (wrong key), switched by a button or a serial command and shown on an LED or the serial console; on the reader they produce FRIEND, `FOE · replayed nonce`, `FOE · position mismatch`, a FRIEND with `responseMs` ≥ 3000 that the phone turns into `FOE · late response`, `UNKNOWN · no response` and `FOE · bad tag`. It keeps the counters "transmissions initiated: 0 · answered authentic challenges: N · ignored: M".
- [ ] **FW-07**: `laptop/gadget_cli.py` (pyserial) opens the reader gadget's serial port from the laptop, sends the same `challenge` / `status` JSON lines the phone sends, prints the answer lines with the host-measured round trip and applies the phone's re-check rules (nonce seen before, 2.5 s budget, −70 dBm floor), so the whole gadget link can be exercised on the bench before the phone is attached; a `--loop` option repeats challenges while the drone gadget is cycled through its modes.

### SIG — Signal path on the phone (`app/.../signal`, stream 2)

- [ ] **SIG-01**: `signal/UsbGadgetSignalSource` (usb-serial-for-android) finds the reader gadget over USB OTG (CDC ACM, or FTDI/CP210x/CH34x bridges), obtains USB permission, opens 115200 8N1, writes one `challenge` line per request and turns the newline-delimited answers into `SignalEvent.Answer` / `SignalEvent.Status` on the `SignalSource` contract; it exposes `gadgetConnected`, survives unplug/replug, and never touches the network.
- [ ] **SIG-02**: `signal/GadgetLineCodec` is the pure-Kotlin encoder/decoder of the gadget line protocol: `challenge` and `status` commands → JSON lines, `answer` and `status` lines → typed events; unknown fields are tolerated, `null`/missing `rssiDbm` is accepted, malformed or non-JSON lines (boot banners) are skipped and logged with an 8-hex nonce prefix at most; unit-tested with the example lines from README "Gadget link over USB".
- [ ] **SIG-03**: `signal/ReChecks` is pure Kotlin, unit-tested, and re-checks every gadget answer: the nonce was never seen before by the phone (hex-keyed ledger, pruned) else `replayed nonce`; the phone-measured round trip and the gadget's `responseMs` are both within the budget (default 2.5 s) else `late response`; `rssiDbm` is above the floor (default −70 dBm) and consistent with the near-field claim else `signal mismatch`, skipped and shown as "n/a" when `rssiDbm` is missing unless strict physical checks is on; the answer's `boxId` equals the tapped box else the answer is discarded. A gadget FRIEND becomes FRIEND only when every enabled re-check passes; a gadget FOE/UNKNOWN passes through with its reason.
- [ ] **SIG-04**: `signal/FakeSignalSource` plays scripted answers for every verdict (FRIEND, replayed nonce, bad tag, position mismatch, late response, signal mismatch, no response) with plausible `rssiDbm` / `responseMs` and a fresh nonce per answer through the real session path (`IffCoordinator` → `ReChecks`), selectable in settings as demo mode (with a visible DEMO badge) and used by the `DemoScenario` the UI stream builds against; it is the simulator while the hardware is not attached and the on-stage fallback if the gadget misbehaves. *(never cut)*

### PHY — Physical-layer evidence (timing and signal strength, re-checked on the phone)

- [ ] **PHY-01**: The phone timestamps the `challenge` command it writes to the gadget and the `answer` line it reads back, and rejects an answer whose phone-measured round trip or whose gadget-reported `responseMs` exceeds the calibrated budget (default 2.5 s, configurable) as `FOE · late response`; both numbers are shown on the verdict and in the event log. *(the "time difference" check: a relayed answer costs an extra hop, so it arrives late)*
- [ ] **PHY-02**: The gadget reports the RSSI of the drone's radio answer (`rssiDbm`); the phone requires it to be above the configured floor (default −70 dBm) and consistent with the near-field position claim (a claimed < 30 m must not read like a far signal), otherwise `FOE · signal mismatch`; when the gadget reports no RSSI (`null`, e.g. a radio without an RSSI register) the check shows "RSSI n/a" and is skipped unless "strict physical checks" is on in settings. *(cut order 5, after the eval table)*

### OBS — Observer UI

- [ ] **OBS-01**: Every tracked object carries a ring and a tag: `drone · ?` amber before a challenge, `FRIEND · <droneId> · <rssi> dBm · <ms> ms` green, `FOE · replayed nonce` / `FOE · position mismatch` / `FOE · bad tag` / `FOE · late response` / `FOE · signal mismatch` red, `UNKNOWN · no response` red, `person` neutral; rings and fonts are legible from 2 m.
- [ ] **OBS-02**: Tapping a drone box sends exactly one `challenge` command to the gadget over USB ({boxId, challengerId, t}) and draws a 10 s countdown on the box; nothing is transmitted — by the phone or by the gadget — until the user taps; in demo mode the tap goes to `FakeSignalSource` instead, and with no gadget connected and demo mode off the tap yields `UNKNOWN · no gadget` immediately.
- [x] **OBS-04**: A status strip shows the detector name, ms/frame, fps, model-ready state, an OFFLINE badge (airplane mode on and no validated internet) and a gadget dot (green when the USB gadget answers `status`, amber in demo mode, red otherwise) with the latest RSSI.
- [ ] **OBS-05**: A settings screen holds the detector choice, the gadget (USB device and baud, challengerId, accepted droneIds), demo mode (`FakeSignalSource` and its scenario), positions (observer position and heading; the Honest/Relay demo coordinates for the HUD), per-class thresholds, the RTT budget, the RSSI floor and the strict-physical-checks switch, persisted on the phone; there is no key on the phone.
- [ ] **OBS-06**: An on-phone event log lists every detection and verdict {t, class, score, box, verdict, reason, roundTripMs, responseMs, rssiDbm, nonce prefix} with counts (drones / people / friend / foe / unknown).
- [ ] **OBS-10**: A reset button clears tracks, the phone's nonce ledger, verdicts and the log so rehearsals and the live run start clean.

### JDG — Judge view and laptop

- [ ] **JDG-01**: Phone A is mirrored to the laptop screen (scrcpy) with no internet — over USB when the port is free, over adb-over-Wi-Fi on the local LAN while the gadget occupies the phone's USB-C port — and the mirror can record the backup video.
- [ ] **JDG-02**: The laptop serves a dashboard page (`GET /dashboard` on port 8081 — 8080 is taken on the lead's laptop — FastAPI, no CDN assets) showing a live list of events, counts (drones / people / friend / foe / unknown) and the latest frame thumbnail with rings. *(user-chosen; owned by the UI stream, off the critical path)*
- [ ] **JDG-03**: The observer posts every detection and verdict to `POST /api/events` on port 8081 fire-and-forget (bounded queue, 500 ms timeout) and the UI never blocks on the dashboard; the demo looks identical with the laptop off.
- [ ] **JDG-04**: `clikart-cli … serve --offline --device cpu` runs on the laptop as the second ClikaRT device type and answers a curl with one image on `POST /v1/detections`, with the `--offline` flag visible in the terminal during the demo.

### EVL — Eval & evidence

- [ ] **EVL-01**: A test set of at least 10 images (quadcopters near/far/sky, birds, kites, an airliner, people, the actual demo target photographed with phone A) with JSON ground truth lives in `eval/`. *(cut order 4)*
- [ ] **EVL-02**: `eval/run_eval.py` runs the test set against the served detector(s) and prints precision/recall per detector (RT-DETR proxy classes vs OWLv2 prompts) as a markdown table that decides which detector ships. *(cut order 4)*
- [ ] **EVL-03**: The README lists measured on-device numbers: model load time, ms/frame, fps, APK size, RAM and the phone model.

### SEC — Security hygiene

- [ ] **SEC-01**: No secret is in the repository: the ClikaRT license is read from `local.properties` into BuildConfig or entered once into app-private storage, and `.gitignore` covers license files, key files (including `firmware/**/secrets.h`), `local.properties` and model weights.
- [ ] **SEC-02**: The master key is provisioned once per gadget (NVS/Preferences or EEPROM through a provisioning serial command, or a gitignored `secrets.h` with a committed `secrets.h.example`), never committed, never printed over serial and never shown on the phone; the phone holds no key.
- [ ] **SEC-03**: Logs never contain key bytes, the license text or full nonces (8-hex prefix only), including the ClikaRT log bridge and the gadgets' serial consoles (the full nonce travels only inside the `answer` line the phone needs for its ledger).
- [ ] **SEC-04**: Nonces come from the board's hardware RNG (`esp_random()` or the board's equivalent, never `random()` seeded from `millis()`) and tag comparison in `firmware/iff` is constant-time.

### PIT — Pitch & judging artifacts

- [ ] **PIT-01**: README states what pre-existed (ClikaRT 0.6.4, RT-DETR v2 / OWLv2 weights, Android template, CameraX, usb-serial-for-android, the Arduino core and the ArduinoJson / Crypto libraries, FastAPI, scrcpy) versus what was built during the hackathon, and gives setup, model push, gadget flashing, serve and offline demo steps a judge could reproduce, plus the verdict table.
- [ ] **PIT-02**: `docs/PROTOCOL.md` specifies FOF-1 as built in `firmware/iff` (messages with byte layouts, key derivation, the verify order on the gadget and the re-checks on the phone), the threat table (beacon exposure, harvest, replay, relay/MITM, attribution, key capture, clock drift, GPS-denied degradation), its position against Mode 4/5, Remote ID, BCIS, IR beacons and FAFOS, and the production path (Ed25519 per-drone signatures, a directional optical response channel, key distribution); `docs/GADGET_LINK.md` specifies the USB line protocol (board, radio, VID/PID, baud, the JSON lines).
- [ ] **PIT-03**: A backup video of one full airplane-mode run exists (scrcpy recording plus a phone-camera shot of phone A with the gadgets).
- [ ] **PIT-04**: The demo target is decided at H4 by testing which option RT-DETR boxes reliably as aeroplane/bird/kite (phone B full-screen quadcopter image, printed photo, or a toy with phone B behind it); if none works, DET-09 is promoted and EVL is cut first.
- [ ] **PIT-05**: The 3-minute demo script is run end to end three times in airplane mode with the reader gadget attached over USB OTG and the drone gadget cycled through its modes, including the failure path (switch to demo mode and say so if the gadget misbehaves).
- [ ] **PIT-06**: A pitch script and a Q&A card with ten likely questions and one-line answers exist in `docs/`.
- [ ] **PIT-07**: The code repository exists on GitHub under the user's account (github.com/chloeyeo/FriendOrFoe, both teammates as collaborators) and is pushed at the end of every phase, with the planning docs included; the work is tracked as GitHub issues under the milestones H4, H10, H14 and H20.

## v2 Requirements

Deferred past the hackathon. Tracked but not in the current roadmap.

### Detection & UI polish

- **DET-07**: N-of-M track persistence (IoU-matched ids, a drone box shown after 2 of 3 frames) to suppress one-frame bird/kite flicker.
- **DET-10**: SAHI-style tiling or sky ROI for small distant drones.
- **OBS-08**: Verdict toast listing the gadget's checks and the phone's re-checks in order with ✓/✗ and the round-trip time.

### Channel and crypto

- **PHY-04**: Challenge-bound liveness flash: the drone gadget blinks an LED at a nonce-derived delay and the phone checks the tapped box's mean luminance spike at the expected frame (a one-symbol on-off-keying liveness proof that ties the radio answer to the object the camera sees; the production IR path).
- **PHY-05**: A second, phone-side signal-strength source (a short BLE advertisement from the drone gadget in reply to an authenticated challenge, RSSI read on the phone) to cross-check the gadget's RSSI.
- **FW-08**: On-off-keyed IR LED response from the drone gadget decoded from the box's mean luminance (the production optical channel; the radio answer is the demo stand-in).
- **FW-09**: Two drone gadgets with different droneIds in the air at once, to show attribution with several drones.
- **IFF-09**: Ed25519 per-drone signatures and multi-observer key distribution.

### Tooling

- **JDG-05**: Dashboard live updates over SSE/WebSocket with persistence.
- **OBS-11**: mDNS/NSD auto-discovery of the laptop dashboard.

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| A real drone or a production radio link | The demo's radio link runs between the team's own reader and drone-side gadgets; the drone-side gadget stands in for the drone's transponder, and the pitch says so |
| Second-phone optical responder (a QR answer on a phone screen, ML Kit / ZXing decode, a NanoHTTPD listener, that phone hosting the hotspot) | Dropped by the user on 2026-10-10: the gadgets carry the challenge-response; `FakeSignalSource` is the simulator and the on-stage fallback |
| A Kotlin `:iff` module or any crypto on the phone | The protocol lives in `firmware/iff` (Arduino C/C++); the phone re-checks nonce freshness, timing, RSSI and attribution only |
| Training or fine-tuning a drone detector | No data, GPUs or hours; COCO proxies + OWLv2 prompts, honestly reported |
| Mapping `cell phone` → drone | Judges would read the label; PIT-04 picks a target COCO actually boxes |
| Multi-object tracker library (ByteTrack/DeepSORT) | 1 fps detection makes motion models useless; box lock + N-of-M suffice |
| Periodic beacon or heartbeat from the drone gadget | Exactly the beacon exposure the pitch argues against |
| A phone-side fallback that fabricates a verdict when the gadget is silent | Destroys the radio-silence and attribution claims; show UNKNOWN honestly — demo mode is labelled DEMO and is never substituted silently |
| TLS on the laptop LAN | The LAN carries telemetry only (dashboard POSTs, the HttpDetector contingency); offline certificate provisioning costs hours |
| NTP/time sync between the phone and the gadgets | No internet; the phone supplies `t` in the challenge command and the ±1 day key window absorbs drift |
| Streaming live video to the laptop dashboard | scrcpy gives the judges the live view |
| COCO mAP tooling | 10–20 images do not support mAP; precision/recall per class is enough |
| Flight control, engagement or weapon logic; thermal/acoustic/RF sensors; iOS; map/AR views; login; slides | Off-brief or zero judge value in 3 minutes |

## Traceability

Which phases cover which requirements. Populated during roadmap creation (2026-10-10) and revised the same day for the gadget/firmware decisions; see `.planning/ROADMAP.md`.

| Requirement | Phase | Status |
|-------------|-------|--------|
| DET-01 | Phase 2 | Pending |
| DET-02 | Phase 2 | Pending |
| DET-03 | Phase 2 | Complete |
| DET-04 | Phase 2 | Pending |
| DET-05 | Phase 1 | Pending |
| DET-06 | Phase 2 | Complete |
| DET-08 | Phase 5 | Pending |
| DET-09 | Phase 6 | Pending |
| IFF-01 | Phase 3 | Pending |
| IFF-02 | Phase 3 | Pending |
| IFF-03 | Phase 3 | Pending |
| IFF-04 | Phase 3 | Pending |
| IFF-06 | Phase 3 | Pending |
| IFF-08 | Phase 3 | Pending |
| FW-01 | Phase 3 | Pending |
| FW-02 | Phase 3 | Pending |
| FW-03 | Phase 3 | Pending |
| FW-04 | Phase 3 | Pending |
| FW-05 | Phase 4 | Pending |
| FW-06 | Phase 4 | Pending |
| FW-07 | Phase 4 | Pending |
| SIG-01 | Phase 4 | Pending |
| SIG-02 | Phase 4 | Pending |
| SIG-03 | Phase 4 | Pending |
| SIG-04 | Phase 4 | Pending |
| PHY-01 | Phase 5 | Pending |
| PHY-02 | Phase 5 | Pending |
| OBS-01 | Phase 5 | Pending |
| OBS-02 | Phase 5 | Pending |
| OBS-04 | Phase 2 | Complete |
| OBS-05 | Phase 5 | Pending |
| OBS-06 | Phase 5 | Pending |
| OBS-10 | Phase 5 | Pending |
| JDG-01 | Phase 4 | Pending |
| JDG-02 | Phase 4 | Pending |
| JDG-03 | Phase 5 | Pending |
| JDG-04 | Phase 1 | Pending |
| EVL-01 | Phase 6 | Pending |
| EVL-02 | Phase 6 | Pending |
| EVL-03 | Phase 6 | Pending |
| SEC-01 | Phase 1 | Pending |
| SEC-02 | Phase 3 | Pending |
| SEC-03 | Phase 5 | Pending |
| SEC-04 | Phase 3 | Pending |
| PIT-01 | Phase 6 | Pending |
| PIT-02 | Phase 7 | Pending |
| PIT-03 | Phase 6 | Pending |
| PIT-04 | Phase 2 | Pending |
| PIT-05 | Phase 6 | Pending |
| PIT-06 | Phase 7 | Pending |
| PIT-07 | Phase 1 | Pending |

**Per phase:** Phase 1 — SEC-01, JDG-04, PIT-07, DET-05 (4) · Phase 2 — DET-01, DET-02, DET-03, DET-04, DET-06, OBS-04, PIT-04 (7) · Phase 3 — IFF-01, IFF-02, IFF-03, IFF-04, IFF-06, IFF-08, FW-01, FW-02, FW-03, FW-04, SEC-02, SEC-04 (12) · Phase 4 — FW-05, FW-06, FW-07, SIG-01, SIG-02, SIG-03, SIG-04, JDG-01, JDG-02 (9) · Phase 5 — OBS-01, OBS-02, OBS-05, OBS-06, OBS-10, PHY-01, PHY-02, JDG-03, SEC-03, DET-08 (10) · Phase 6 — EVL-01, EVL-02, EVL-03, DET-09, PIT-01, PIT-03, PIT-05 (7) · Phase 7 — PIT-02, PIT-06 (2).

**Coverage:**
- v1 requirements: 51 total (DET 8, IFF 6, FW 7, SIG 4, PHY 2, OBS 6, JDG 4, EVL 3, SEC 4, PIT 7). Revision 2026-10-10: RSP-01..13, OBS-03 and PHY-03 removed; IFF-05 folded into FW-04; FW-01..07 and SIG-01..04 added; all other IDs unchanged.
- Mapped to phases: 51
- Unmapped: 0 ✓

---
*Requirements defined: 2026-10-10*
*Last updated: 2026-10-10 — revised for the gadget/firmware decisions (RSP removed, FW and SIG added, IFF on the gadget, PHY re-sourced to the gadget's radio report); traceability refilled*
