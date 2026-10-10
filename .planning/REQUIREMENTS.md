# Requirements: FriendOrFoe

**Defined:** 2026-10-10
**Core Value:** Point the phone at a drone and, within seconds and with no network, see a green ring or a red ring with a reason on that exact object — while the drone transmits nothing until asked, and its answer only works for the one observer who asked, at the place it is actually flying.

Categories follow `.planning/research/FEATURES.md` (DET, IFF, RSP, OBS, JDG, EVL, SEC, PIT). IDs are kept identical to FEATURES.md where the feature exists there so the research and the requirements can be cross-read. Hour estimates come from FEATURES.md and are for one developer.

**Cut order if behind schedule** (user's order, kept; the research's suggested amendment is noted in brackets): DET-08 audio alert → DET-09 OWLv2 option → IFF-06/RSP-07 Relay + position gate → EVL-01/02 eval table. [Research recommends cutting the JDG-02/03 laptop dashboard before any of these, and keeping Relay above the eval table because "resist MITM" is in the T1-04 wording. The user chose the dashboard explicitly on 2026-10-10, so it stays in v1 until the user cuts it.] The PHY checks added on 2026-10-10 at the user's request sit above the cut line: PHY-01 timing and PHY-03 optical consistency are cheap and stay; PHY-02 signal strength is cut only after the eval table.

**Three layers of evidence (user's framing, 2026-10-10):** the crypto layer (IFF) proves *who* answered; the geometry layer (position gate, box attribution) proves *where*; the physical layer (PHY: round-trip timing, radio signal strength, optical size/persistence) proves *when and how near*. A verdict is FRIEND only when every enabled layer passes.

## v1 Requirements

Requirements for the hackathon submission. Each maps to one roadmap phase.

### DET — Detection & alerting

- [ ] **DET-01**: Observer sees a live back-camera preview with one continuous analysis stream (CameraX Preview + ImageAnalysis, KEEP_ONLY_LATEST, 1280x720 or 1920x1080, orientation locked), so boxes always refer to the current frame.
- [ ] **DET-02**: Observer gets drone and person detections on the phone itself from the ClikaRT AAR at roughly 1 fps, with the RT-DETR v2 r18 snapshot loaded from device storage with `offline = true`, all load/infer calls on one runtime lane off the main thread, a visible "model loading…" state, and no network access at any point.
- [ ] **DET-03**: Detections are mapped by `labelId` to `person` (0) and `drone` (aeroplane 4, bird 14, kite 33) with per-class score thresholds (drone proxies ≈ 0.25–0.3, person 0.5); every other class is ignored and `cell phone` is never mapped to drone.
- [ ] **DET-04**: Boxes are drawn on the preview in correct preview coordinates (analysis-frame → PreviewView mapping including rotation and FILL_CENTER crop) with a class colour, label and score, and visibly sit on the object they belong to.
- [ ] **DET-05**: The detector sits behind a `Detector` interface; `HttpDetector` against `clikart-cli serve` (`POST /v1/detections`) is selectable with one config change as the H4 contingency and dev loop, and nothing above the interface changes.
- [ ] **DET-06**: When a drone box is tapped it is locked (id + rect) for the 10 s challenge window, so QR decode and verification happen only inside that box even if the detector loses the object when the responder's screen changes.
- [ ] **DET-08**: Observer hears a chime (bundled sound, not TTS) and sees a banner "DRONE · bearing 045 · high" on the first drone detection of a run. *(stretch; cut order 1)*
- [ ] **DET-09**: Observer can select the OWLv2 zero-shot detector in settings (labels "drone", "quadcopter", "uav") as the accuracy option; promoted to table stakes if PIT-04 shows COCO cannot see the demo target. *(cut order 2)*

### IFF — Challenge/response & verdicts (pure Kotlin module)

- [ ] **IFF-01**: A pure-Kotlin JVM module `:iff` with no Android dependencies implements FOF-1: HMAC-SHA256 key derivation (master → day key by YYYYMMDD → per-drone key), fixed binary codecs for the challenge {v, nonce16, t, challengerId, cmac8} and the response {v, droneId, nonce8, posQ, tag16}, with every MAC computed over the fixed byte layout and never over JSON text.
- [ ] **IFF-02**: A nonce ledger tracks issued → outstanding → consumed nonces with one outstanding challenge per box and an age limit of 10 s; any reuse of a nonce yields the verdict `FOE · replayed nonce`.
- [ ] **IFF-03**: The verifier runs its checks in a fixed order — read inside the box → nonce issued, unused and fresh → tag recomputes with the drone key for today ±1 → position gate — and returns a verdict with the first failing reason; a 10 s timeout yields `UNKNOWN · no response` and silence can never yield FRIEND.
- [ ] **IFF-04**: The day-key window accepts yesterday, today and tomorrow so two phones with drifting clocks in airplane mode do not flip a FRIEND to FOE at midnight.
- [ ] **IFF-05**: Unit tests and JSON test vectors (committed under `iff/src/test/resources`) cover FRIEND, replayed nonce, bad tag, position mismatch and no response, and `./gradlew :iff:test` passes on the laptop without a device.
- [ ] **IFF-06**: The position gate accepts a claimed position only if it is within R_max (3 km) of the observer and either within 30 m (near-field, same room) or within ±20° bearing/elevation of the box's cone computed from the configured observer heading, camera HFOV and box position; a position 5 km away yields `FOE · position mismatch`. *(cut order 3, together with RSP-07)*
- [ ] **IFF-08**: A response computed with a wrong key yields `FOE · bad tag`.

### PHY — Physical-layer evidence (timing, signal strength, optical consistency)

- [ ] **PHY-01**: The observer timestamps the challenge send and the first QR decode inside the locked box, decodes the box crop on every analysis frame (≥ 5 fps during the window), and rejects an answer that arrives after the calibrated round-trip budget (default 2.5 s, configurable) as `FOE · late response`; the measured round-trip time is shown on the verdict and in the event log. *(the "time difference" check: a relayed answer costs an extra hop and re-display, so it arrives late)*
- [ ] **PHY-02**: With phone B hosting the hotspot, the observer samples the link RSSI (WifiManager) at verify time; the RSSI must be above the configured floor (default −70 dBm) and the RSSI-derived range must agree with the near-field position claim, otherwise `FOE · signal mismatch`; when the observer is not attached to the responder's access point the check shows "RSSI n/a" and is skipped unless "strict physical checks" is on in settings. *(cut order 5, after the eval table)*
- [ ] **PHY-03**: The QR must decode inside the locked box in at least two consecutive analysis frames and its pixel size must lie between a configured minimum and the box size; a single-frame decode or an implausible size is rejected as `FOE · optical mismatch`, and the QR's apparent size yields a range estimate logged next to the RSSI range and the claimed distance.

### RSP — Responder (drone-side, phone B)

- [ ] **RSP-01**: At launch the user picks Observer or Responder mode; the Responder screen shows its LAN IP:port, droneId, "listening" state and the last challenge result, so phone B can be paired in seconds.
- [ ] **RSP-02**: The responder listens for `POST /challenge` on the hotspot LAN, verifies the challenge MAC, builds the FOF-1 response and shows the QR; an unauthenticated challenge produces no QR and the log line "ignored: bad challenge MAC".
- [ ] **RSP-03**: The QR is shown full-screen with the raw response bytes in QR byte mode (version ≤ 4, EC level L or M, 4-module quiet zone, maximum brightness) and auto-hides after 10 s.
- [ ] **RSP-04**: The responder has the attack buttons Honest, Replay (re-show the previous response for the new challenge) and Silent (ignore), each producing the matching observer verdict.
- [ ] **RSP-05**: The responder's position comes from a configured demo position by default and from a GPS fix when one is available, so the response layout is complete indoors.
- [ ] **RSP-06**: The responder's idle screen shows the full-screen demo target image (the quadcopter the observer "sees"), chosen at H4 per PIT-04.
- [ ] **RSP-07**: The Relay button answers with a position 5 km away so the observer shows `FOE · position mismatch`. *(cut order 3, together with IFF-06)*
- [ ] **RSP-08**: The Impostor button answers with a wrong key so the observer shows `FOE · bad tag`.
- [ ] **RSP-09**: The responder screen shows the RF-silence counters "transmissions initiated: 0 · answered authentic challenges: N · ignored: M".
- [ ] **RSP-12**: The Late button shows the correct QR only after a 3 s delay so the observer shows `FOE · late response` (the relay-latency demo for PHY-01).
- [ ] **RSP-13**: The responder phone hosts the demo hotspot (mobile data off) so the observer can measure the link RSSI for PHY-02; the Responder screen shows the hotspot name and its own IP.

### OBS — Observer UI

- [ ] **OBS-01**: Every tracked object carries a ring and a tag: `drone · ?` amber before a challenge, `FRIEND · <droneId>` green, `FOE · replayed nonce` / `FOE · position mismatch` / `FOE · bad tag` red, `UNKNOWN · no response` red, `person` neutral; rings and fonts are legible from 2 m.
- [ ] **OBS-02**: Tapping a drone box sends exactly one authenticated challenge (HTTP POST to the responder IP, 2 s connect timeout) and draws a 10 s countdown on the box; nothing is transmitted until the user taps.
- [ ] **OBS-03**: The QR is decoded only inside the locked box (crop with a 10 % margin) using ML Kit bundled barcode-scanning restricted to QR_CODE, taking the raw bytes into the verifier; a QR elsewhere in the frame is ignored.
- [ ] **OBS-04**: A status strip shows the detector name, ms/frame, fps, model-ready state, an OFFLINE badge (airplane mode on and no validated internet) and a responder-reachability dot.
- [ ] **OBS-05**: A settings screen holds responder IP:port, master key (hex, masked), challengerId, accepted droneIds, detector choice, demo position and heading, and per-class thresholds, persisted on the phone.
- [ ] **OBS-06**: An on-phone event log lists every detection and verdict {t, class, score, box, verdict, reason, roundTripMs} with counts (drones / people / friend / foe / unknown).
- [ ] **OBS-10**: A reset button clears tracks, nonces, verdicts and the log so rehearsals and the live run start clean.

### JDG — Judge view and laptop

- [ ] **JDG-01**: Phone A is mirrored to the laptop screen over USB (scrcpy) with no network, and the mirror can record the backup video.
- [ ] **JDG-02**: The laptop serves a dashboard page (`GET /dashboard`, FastAPI, no CDN assets) showing a live list of events, counts (drones / people / friend / foe / unknown) and the latest frame thumbnail with rings. *(user-chosen; first cut if the research's amendment is accepted)*
- [ ] **JDG-03**: The observer posts every detection and verdict to `POST /api/events` fire-and-forget (bounded queue, 500 ms timeout) and the UI never blocks on the dashboard.
- [ ] **JDG-04**: `clikart-cli … serve --offline --device cpu` runs on the laptop as the second ClikaRT device type and answers a curl with one image on `POST /v1/detections`, with the `--offline` flag visible in the terminal during the demo.

### EVL — Eval & evidence

- [ ] **EVL-01**: A test set of at least 10 images (quadcopters near/far/sky, birds, kites, an airliner, people, the actual demo target photographed with phone A) with JSON ground truth lives in `eval/`. *(cut order 4)*
- [ ] **EVL-02**: `eval/run_eval.py` runs the test set against the served detector(s) and prints precision/recall per detector (RT-DETR proxy classes vs OWLv2 prompts) as a markdown table that decides which detector ships. *(cut order 4)*
- [ ] **EVL-03**: The README lists measured on-device numbers: model load time, ms/frame, fps, APK size, RAM and the phone model.

### SEC — Security hygiene

- [ ] **SEC-01**: No secret is in the repository: the ClikaRT license is read from `local.properties` into BuildConfig or entered once into app-private storage, and `.gitignore` covers license files, key files, `local.properties` and model weights.
- [ ] **SEC-02**: The master key is entered once per phone, stored app-private and displayed masked.
- [ ] **SEC-03**: Logs never contain key bytes, the license text or full nonces (8-hex prefix only), including the ClikaRT log bridge.
- [ ] **SEC-04**: Nonces come from SecureRandom and tag comparison is constant-time.

### PIT — Pitch & judging artifacts

- [ ] **PIT-01**: README states what pre-existed (ClikaRT 0.6.4, RT-DETR v2 / OWLv2 weights, Android template, ML Kit, ZXing) versus what was built during the hackathon, and gives setup, model push, serve and offline demo steps a judge could reproduce, plus the verdict table.
- [ ] **PIT-02**: `docs/PROTOCOL.md` specifies FOF-1 (messages, key derivation, verify order), the threat table (beacon exposure, harvest, replay, relay/MITM, attribution, key capture, clock drift, GPS-denied degradation), its position against Mode 4/5, Remote ID, BCIS, IR beacons and FAFOS, and the production path (Ed25519, IR on-off keying, key distribution).
- [ ] **PIT-03**: A backup video of one full airplane-mode run exists (scrcpy recording plus a phone-camera shot of both devices).
- [ ] **PIT-04**: The demo target is decided at H4 by testing which option RT-DETR boxes reliably as aeroplane/bird/kite (phone B full-screen quadcopter image, printed photo, or a toy with phone B behind it); if none works, DET-09 is promoted and EVL is cut first.
- [ ] **PIT-05**: The 3-minute demo script is run end to end three times in airplane mode on both phones with the hotspot, including the failure path (what to say if the QR does not read).
- [ ] **PIT-06**: A pitch script and a Q&A card with ten likely questions and one-line answers exist in `docs/`.
- [ ] **PIT-07**: The code repository exists on GitHub under the user's account and is pushed at the end of every phase, with the planning docs included.

## v2 Requirements

Deferred past the hackathon. Tracked but not in the current roadmap.

### Detection & UI polish

- **DET-07**: N-of-M track persistence (IoU-matched ids, a drone box shown after 2 of 3 frames) to suppress one-frame bird/kite flicker.
- **DET-10**: SAHI-style tiling or sky ROI for small distant drones.
- **OBS-08**: Verdict toast listing the checks in order with ✓/✗ and the round-trip time.

### Optical channel and crypto

- **PHY-04**: Challenge-bound liveness flash: the responder flashes its screen at a nonce-derived delay before the QR and the observer checks the box's mean luminance spike at the expected frame (a one-symbol on-off-keying liveness proof; the production IR path).
- **PHY-05**: BLE advertisement RSSI as the signal-strength source when the responder does not host the Wi-Fi link (a short burst only in reply to an authenticated challenge).
- **RSP-10**: On-off-keyed LED/screen blink response decoded from the box's mean luminance (the production IR beacon path).
- **IFF-09**: Ed25519 per-drone signatures and multi-observer key distribution.
- **RSP-11**: Raspberry Pi / ESP32 responder implementations checked against the JSON test vectors.

### Tooling

- **JDG-05**: Dashboard live updates over SSE/WebSocket with persistence.
- **OBS-11**: mDNS/NSD auto-discovery of the responder.

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Real RF link to a drone, BLE or optical challenge | Demo uses HTTP over the hotspot for the challenge and QR for the response; the pitch states QR stands in for a modulated optical beacon |
| Training or fine-tuning a drone detector | No data, GPUs or hours; COCO proxies + OWLv2 prompts, honestly reported |
| Mapping `cell phone` → drone | Judges would read the label; PIT-04 picks a target COCO actually boxes |
| Multi-object tracker library (ByteTrack/DeepSORT) | 1 fps detection makes motion models useless; box lock + N-of-M suffice |
| Periodic beacon or heartbeat from the responder | Exactly the beacon exposure the pitch argues against |
| HTTP (RF) fallback for the response if the QR cannot be read | Destroys the radio-silence and attribution claims; show UNKNOWN honestly |
| TLS on the hotspot link | Offline certificate provisioning costs hours; the challenge is authenticated, not encrypted, by design |
| NTP/time sync between phones | No internet; the 10 s window and ±1 day key window absorb drift; set clocks manually |
| Streaming live video to the laptop dashboard | scrcpy over USB gives the judges the live view |
| COCO mAP tooling | 10–20 images do not support mAP; precision/recall per class is enough |
| Flight control, engagement or weapon logic; thermal/acoustic/RF sensors; iOS; map/AR views; login; slides | Off-brief or zero judge value in 3 minutes |

## Traceability

Which phases cover which requirements. Populated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| (filled by the roadmapper) | | |

**Coverage:**
- v1 requirements: 59 total
- Mapped to phases: 0
- Unmapped: 59 ⚠️ (roadmap pending)

---
*Requirements defined: 2026-10-10*
*Last updated: 2026-10-10 after initial definition*
