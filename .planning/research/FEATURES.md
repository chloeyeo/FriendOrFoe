# Feature Research

**Domain:** Camera-based counter-UAS detection + radio-silent optical IFF, demoed live on two Android phones (observer A, responder B) for a military hackathon (T1-04 Radio-silent IFF) and the CLIKA SC1 offline-AI sub-track
**Researched:** 2026-10-10
**Confidence:** MEDIUM-HIGH overall (ClikaRT / ML Kit / QR facts HIGH from local sources and official docs; IFF-system lineage MEDIUM from secondary sources; judge expectations LOW-MEDIUM, inferred from analogous defense-hackathon rubrics)

## How to read this file

- **Categories (stable, 8; requirement IDs derive from them):** `DET` Detection & alerting · `IFF` Challenge/response & verdicts · `RSP` Responder (drone-side) behaviour & attack buttons · `OBS` Observer UI · `JDG` Judge view / laptop dashboard · `EVL` Eval & evidence · `SEC` Security hygiene · `PIT` Pitch & judging artifacts.
- **Class:** TS = table stakes (demo fails or judges dismiss it without this), DF = differentiator, AF = anti-feature (deliberately not built).
- **Hours** = focused hours for one developer with AI-assisted coding, including on-device debugging. Risk multipliers are called out where hours can balloon.
- **The demo is the devices, not the laptop** (user correction 2026-10-10). Everything judges must see lives on phone A and phone B; the laptop is only (a) a big screen for phone A via USB mirroring and (b) the second ClikaRT device type for SC1 and the eval. This moves the FastAPI dashboard from table stakes to optional, and makes the on-phone event log and status strip table stakes.

## Feature Landscape

### Table Stakes (Users Expect These)

Features users assume exist. Missing these = product feels incomplete.

#### DET — Detection & alerting

| ID | Feature | Why Expected | Complexity | Hours | Depends on | Notes |
|----|---------|--------------|------------|-------|------------|-------|
| DET-01 | Live back-camera preview with an analysis stream (CameraX `Preview` + one `ImageAnalysis`, `KEEP_ONLY_LATEST`, 1280x720 or 1920x1080, landscape locked) | Every CUAS optical tool is a live picture with boxes; a still-image demo reads as a toy | LOW | 0.5 | — | One analysis stream; the detector gets a downscaled copy, the QR decoder (OBS-03) gets a crop at full analysis resolution. CameraX 1.6.2 is current stable; the declared 1.4.1 is fine, do not churn. |
| DET-02 | In-process ClikaRT detection at ~1 fps (`ClikaRtAndroid.load(context, license)` once, then `AutoModelForObjectDetection.fromPretrained(snapshotDir, VisionLoadOptions(offline=true))` → `VisionModel`, `ObjectDetectionPipeline`, RGBA8888 input, single load/infer lane off the main thread, "model loading…" state) | SC1 requires the app to be built with ClikaRT; T1 judges expect detection to run on the phone, offline | HIGH | 1.5 (risk x2-3: license offline, load time, per-frame latency on this phone) | offline `CLIKA1-…` license verified with Wi-Fi off; `rtdetr_v2_r18vd` snapshot pushed via adb | `DetectOptions.labels` must stay empty for RT-DETR ("refused on a detector with its own label table", Types.kt:856-862). Default `scoreThreshold` is 0.5 (`DEFAULT_SCORE_THRESHOLD`, Types.kt:867; `DEFAULT_MAX_RESULTS` 100); use ~0.25-0.3 for the drone proxy classes, 0.5 for person. `ObjectDetectionPipeline`'s constructor is `internal`: obtain it as `Modelverse.pipeline("object-detection", model) as ObjectDetectionPipeline` (Modelverse.kt:199) or call `VisionModel.detect(image, options, listener)` directly (VisionModel.kt:43); invoke is `pipeline(image, options, candidateLabels)`. `ImageInput.Rgba8888(pixels, w, h)` wants **tightly packed** R,G,B,A rows, `width × height` (Types.kt:801-807) — CameraX `OUTPUT_IMAGE_FORMAT_RGBA_8888` is single-plane R,G,B,A but `PlaneProxy.getRowStride()` may exceed `width*4`, so repack row-by-row when it does. License failures surface as `LICENSE_FAILED` / `LICENSE_EXPIRED` / `LICENSE_SERVER_UNREACHABLE` (ClikaRtAndroid.kt:70-76). RT-DETR preprocessor resizes to 640x640, so a 1080p frame loses 3x linear resolution on small targets. |
| DET-03 | Class map by `labelId`: `0 person` → person; `4 aeroplane`, `14 bird`, `33 kite` → drone candidate; everything else ignored | The app's object of interest is "drone", which COCO does not have | LOW | 0.25 | DET-02 | **The cached RT-DETR table spells it `aeroplane` (and `motorbike`), VOC-style — a map keyed on the string "airplane" never fires.** Key on `labelId`, or accept both spellings. Do not map `cell phone` (67) to drone even though phone B will be boxed as one (see PIT-04). |
| DET-04 | Box overlay in preview coordinates (analysis-frame → `PreviewView` mapping incl. rotation and FILL_CENTER crop), colour per class, label + score | Boxes on the exact object are the product's core visual; off-by-rotation boxes are the classic demo killer | MEDIUM | 0.75 | DET-01, DET-02 | Test once in the locked orientation; render with Compose `Canvas` over the `PreviewView`. |
| DET-05 | `Detector` interface with `HttpDetector` to `clikart-cli <model> serve --offline --host 0.0.0.0` (`POST /v1/detections`, multipart image) as the H4 contingency and dev loop | Protects the hard H4 checkpoint; nothing above the interface changes | LOW | 0.5 | laptop serve on the hotspot LAN | Contingency only; the pitch must say the phone path is the product. `serve` binds `127.0.0.1:8000` by default — a phone cannot reach it without `--host 0.0.0.0` (CLI `serve --help`). The app targets API ≥ 28, so **cleartext HTTP is off by default**: add `android:usesCleartextTraffic="true"` (or a network-security-config permitting the LAN) or every `http://` call from OkHttp/`HttpURLConnection` fails. |
| DET-06 | Challenge-window box lock: when a drone box is tapped, freeze that box (id, rect) for the 10 s window and decode/verify only inside it | When phone B switches from the drone image to the QR, the detector loses the "drone"; without a lock the attribution rule ("read inside the box") has no box to read inside | LOW | 0.5 | DET-04 | Cheapest form of tracking; expand to DET-07 persistence if time allows. |

#### IFF — Challenge/response & verdicts

| ID | Feature | Why Expected | Complexity | Hours | Depends on | Notes |
|----|---------|--------------|------------|-------|------------|-------|
| IFF-01 | Pure-Kotlin JVM `iff` module: day key / drone key derivation (HMAC-SHA256), fixed binary layouts, challenge `{v,nonce16,t,challengerId,cmac8}`, response `{v,droneId,nonce8,posQ,tag16}`, byte codec | T1-04 literally asks for "a cryptographic two-component IFF scheme"; judges will ask to see the code | MEDIUM | 1.5 | — | No Android imports so `./gradlew :iff:test` runs on the laptop. MAC over bytes, never JSON text. AGP 9 built-in Kotlin removes `org.jetbrains.kotlin.android` from the app module, but a pure JVM module still applies `org.jetbrains.kotlin.jvm` with an explicit version — use the project's Kotlin 2.2.10 (the official migration page does not cover JVM modules; MEDIUM). |
| IFF-02 | Nonce ledger: issued → outstanding → consumed; age < 10 s; one outstanding challenge per box; any reuse is `replayed nonce` | "Resist replay" is in the brief | LOW | 0.25 | IFF-01 | Store nonce prefix + issue time + boxId. |
| IFF-03 | Verdict engine with fixed check order and reasons: in-box → nonce (issued, unused, fresh) → tag recomputes with today±1 drone key → position gate; timeout → `UNKNOWN · no response`; never FRIEND on timeout | Judges remember a red ring *with a reason*; silence must never be green | LOW | 0.5 | IFF-01, IFF-02 | Expose `Verdict(kind, reason, checksRun)` so OBS-08 can show which check failed. |
| IFF-04 | Day window today±1 for clock drift | Two phones in airplane mode drift; a FRIEND that turns FOE at midnight is a demo risk | LOW | 0.1 | IFF-01 | |
| IFF-05 | Unit tests + JSON test vectors for FRIEND / replayed nonce / bad tag / position mismatch / no response | Technical Execution 25 %: tests that run without a device are the cheapest credible evidence; vectors let any other responder be checked | LOW | 0.5 | IFF-01..03 | Commit vectors under `iff/src/test/resources`. |

#### RSP — Responder (drone-side)

| ID | Feature | Why Expected | Complexity | Hours | Depends on | Notes |
|----|---------|--------------|------------|-------|------------|-------|
| RSP-01 | Mode picker at launch (Observer / Responder); responder screen shows its LAN IP:port, droneId, "listening", last challenge result | Phone B must be set up in seconds on stage; the observer types the IP once | LOW | 0.25 | — | Read the Wi-Fi IP from `ConnectivityManager`/`LinkProperties`. |
| RSP-02 | HTTP listener on phone B (`POST /challenge`, NanoHTTPD 2.3.1 or a 40-line `ServerSocket` loop): verify `cmac` → build response → show QR; **unauthenticated challenge → no QR, log line "ignored: bad challenge MAC"** | The authenticated challenge is what closes "ask tiger first to harvest lion" and stops an enemy from lighting the drone up | MEDIUM | 1.0 | IFF-01 | NanoHTTPD's last release is 2016 (Maven Central) but it is tiny and works on Android; Ktor 3.6.0 CIO also works but pulls more into a 300 MB APK. Either is fine; raw `ServerSocket` has zero deps. |
| RSP-03 | Full-screen QR (ZXing core 3.5.4): byte mode with the raw ~40 B response (fits version 3-L, 53 B) or Base32-uppercase alphanumeric (64 chars fits 3-L, 77); EC level L/M; 4-module quiet zone; max brightness; auto-hide after 10 s | The optical answer is the whole point of the radio-silent story | LOW | 0.5 | IFF-01 | Base64url (54 chars, mixed case) does **not** fit version 3 byte mode — it needs version 4-L (78 B). Prefer raw bytes + `Barcode.getRawBytes()` on the observer. |
| RSP-04 | Attack buttons **Honest / Replay / Silent** (Replay = re-show the previous response's QR for the new challenge; Silent = ignore) | The two red verdicts judges can grasp in seconds; without them the demo shows only a green ring | LOW | 0.5 | RSP-02, RSP-03 | Relay and Impostor are differentiators (RSP-07, IFF-08). |
| RSP-05 | Responder position source: fixed demo position from settings by default, GPS fix if available | Needed by the response layout even before the cone check exists; indoor GPS is unreliable | LOW | 0.25 | — | Keep the demo deterministic: both phones use a configured position. |
| RSP-06 | Idle screen shows a full-screen quadcopter image (the thing the observer "sees") | The responder phone is the drone in this demo | LOW | 0.1 | — | Which image COCO actually detects is a PIT-04 test at H4. |

#### OBS — Observer UI

| ID | Feature | Why Expected | Complexity | Hours | Depends on | Notes |
|----|---------|--------------|------------|-------|------------|-------|
| OBS-01 | Rings + tags: `drone · ?` amber; `FRIEND · RQ-07` green; `FOE · replayed nonce` / `FOE · position mismatch` / `FOE · bad tag` red; `UNKNOWN · no response` red; `person` neutral | The Core Value in one glance: a colour and a reason on the exact object | LOW | 0.5 | DET-04, IFF-03 | Thick ring, large tag font for judges 2 m away. |
| OBS-02 | Tap a drone box → one authenticated challenge (HTTP POST to the responder IP, 2 s connect timeout) → 10 s countdown drawn on the box | Operator-initiated interrogation is the radio-silence story: nothing is sent until asked | LOW | 0.5 | IFF-01, IFF-02, OBS-05 | OkHttp 5.5.0 or `HttpURLConnection`; the observer is the only transmitter. Plain `http://` to a LAN IP needs the cleartext opt-in from DET-05 (Android 9+ default is cleartext disabled; raw `ServerSocket` on the responder is not affected). |
| OBS-03 | QR decode only inside the locked box: crop the analysis frame to the box (+10 % margin), ML Kit **bundled** `barcode-scanning:17.3.0` with `setBarcodeFormats(FORMAT_QR_CODE)`, `getRawBytes()` → `iff.verify` | "Attribution" = the answer was read from inside the object the operator pointed at; decoding the whole frame would let any screen in view answer for the drone | MEDIUM | 1.0 (risk x2: readability distance) | DET-06, RSP-03, IFF-03 | ML Kit needs ≥ 2 px per module and recommends 1280x720-1920x1080 capture. My estimate: a version-3 QR ~6.5 cm wide (≈2 mm modules) at 1080p with a ~70° lens reads at ≤ ~1.2 m; use ≤ 1 m on stage, or decode from a one-shot full-res `ImageCapture` on tap for ~2.5 m. The bundled artifact ships the model in the APK (+2.4 MB); the unbundled one downloads via Play services and "requests before the download has completed produce no results" — unusable with the network blocked. |
| OBS-04 | Status strip: detector name, ms/frame, fps, model-ready, **OFFLINE** badge (airplane mode on, no validated internet), responder reachability dot | SC1 judges must see the network is blocked; T1 judges want to see it is on-device and how fast | LOW | 0.5 | DET-02 | `Settings.Global.AIRPLANE_MODE_ON` + `NetworkCapabilities.NET_CAPABILITY_VALIDATED == false`. |
| OBS-05 | Settings: responder IP:port, master key (hex), challengerId, accepted droneIds, detector choice, demo position, drone/person thresholds | Two phones must be paired without code changes on stage | LOW | 0.5 | — | Plain Compose form + DataStore/SharedPreferences. |
| OBS-06 | On-phone event log screen: `{t, class, score, box, verdict, reason, roundTripMs}` list + counts (drones / people / friend / foe / unknown) | With the devices as the demo, this is the judges' "dashboard"; also the Q&A evidence ("show me the replay event") | LOW | 0.5 | OBS-01 | In-memory list is enough; export to a text file is a 10-minute extra. |
| OBS-10 | Reset demo state button (clear tracks, nonces, verdicts, log) | Three rehearsals + the live run on the same phones; stale nonces or a stuck lock break the next run | LOW | 0.25 | IFF-02, DET-06 | |

#### JDG — Judge view

| ID | Feature | Why Expected | Complexity | Hours | Depends on | Notes |
|----|---------|--------------|------------|-------|------------|-------|
| JDG-01 | Mirror phone A to the laptop screen over USB with scrcpy (v5.0.1; no Wi-Fi, no internet; `--record` for the backup video) | Five judges cannot crowd around a phone; the laptop screen shows the observer live without any network | LOW | 0.25 | adb, USB debugging | This replaces the dashboard as the thing judges look at. Audio forwarding needs Android 11+. |

#### SEC — Security hygiene

| ID | Feature | Why Expected | Complexity | Hours | Depends on | Notes |
|----|---------|--------------|------------|-------|------------|-------|
| SEC-01 | `.gitignore`: `*.license`, `local.properties`, key files, `*.safetensors`/`*.gguf`/model snapshots; license via `local.properties` → `BuildConfig` or one-time entry into private storage (as the ClikaRT examples do) | SC1 requires a public repo; a committed license or key is an instant credibility loss | LOW | 0.25 | — | Same pattern as `examples-0.6.4/android/shared/src/main/kotlin/io/clika/examples/android/shared/LicenseStore.kt` (`local.properties` key `clika.runtimeLicenseFile=<path>`, first non-blank line → `BuildConfig.CLIKA_RT_LICENSE`; else pasted once into `filesDir/clika_rt_license`). |
| SEC-02 | Master key entered once per phone, stored app-private, shown masked | Pre-placed-key model of FOF-1 | LOW | 0.25 | OBS-05 | `EncryptedSharedPreferences` is optional polish. |
| SEC-03 | Logging policy: never log key bytes, license text or full nonces (8-hex prefix only); route ClikaRT logs with `ClikaRtAndroid.bridgeLogs()` but keep the credential out | Judges may read logcat during Q&A | LOW | 0.1 | — | |
| SEC-04 | `SecureRandom` nonces; constant-time tag compare (`MessageDigest.isEqual`) | Cheap, and the first thing a security-minded judge checks in the verifier | LOW | 0.1 | IFF-01 | |

#### PIT — Pitch & judging artifacts

| ID | Feature | Why Expected | Complexity | Hours | Depends on | Notes |
|----|---------|--------------|------------|-------|------------|-------|
| PIT-01 | README: what pre-existed (ClikaRT 0.6.4, RT-DETR v2 / OWLv2 weights, Android template, ML Kit, ZXing) vs what was built today; setup; model push; offline demo steps; verdict table | Judging rule: separate pre-existing from built; SC1 requires the repo | LOW | 0.75 | — | Include the exact adb/serve commands so a judge could reproduce. |
| PIT-03 | Backup video of one full airplane-mode run (scrcpy `--record` + a phone-camera shot of both devices) | Live demos fail; a 90 s video keeps the pitch alive | LOW | 0.5 | JDG-01, PIT-05 | |
| PIT-04 | Demo target decided at H4: phone B full-screen quadcopter image vs printed A3 quadcopter photo vs toy quadcopter with phone B behind it — pick the one RT-DETR boxes reliably as aeroplane/bird/kite | A COCO detector may box phone B as `cell phone` and not see the drone image at all; discovering this at H15 is fatal | LOW | 0.25 | DET-02, DET-03 | If none works with COCO, DET-09 (OWLv2 "drone" prompt) is promoted to table stakes and EVL-02 is cut. |
| PIT-05 | Three timed airplane-mode run-throughs of the 3-minute script on both phones + hotspot | The script has eight hand-offs (point, tap, QR, ring, Replay, Relay/Impostor, Silent, close) | LOW | 1.0 | everything | Rehearse the failure path too (what you say if the QR does not read). |
| PIT-06 | Pitch script + Q&A card (10 likely questions, 1-line answers) | 2-minute Q&A is 40 % of stage time; no slides allowed | LOW | 0.5 | PIT-02 if built | Questions listed in "What the judges expect" below. |

**Table-stakes total: ≈ 17.2 h** (DET 4.0 · IFF 2.85 · RSP 2.6 · OBS 3.75 · JDG 0.25 · SEC 0.7 · PIT 3.0) against 18 working hours in the plan (H0-12, H14-20). That leaves < 1 h for differentiators unless estimates are beaten, which is why the cut order below matters more than usual.

### Differentiators (Competitive Advantage)

Features that set the product apart. Not required, but valuable. Listed in recommended keep order (build top-down as hours allow).

| ID | Feature | Value Proposition | Complexity | Hours | Depends on | Notes |
|----|---------|-------------------|------------|-------|------------|-------|
| IFF-08 / RSP-08 | **Impostor** button → `FOE · bad tag` (responder answers with a wrong key) | The most intuitive FOE for judges: an enemy drone that *tries* to answer and is caught; completes the five-verdict story | LOW | 0.25 | IFF-03, RSP-04 | One more branch in the responder, one more test vector. |
| IFF-06 / RSP-07 | Position binding with a **range gate** + Relay button: accept only if claimed position is within the optical range R_max (e.g. 3 km) **and** (distance < 30 m near-field, or bearing/elevation within ±20° of the box's cone); Relay claims a position 5 km away → `FOE · position mismatch` | "Resist MITM" is in the T1-04 text; this is the only MITM/relay defence judges can *see* as a red ring with a reason. The range gate is what makes the demo deterministic: two phones in one room have an undefined bearing, so the near-field rule applies, while a relayed far position fails the range gate every time | MEDIUM | 1.5 (incl. 0.5 for DET-11 bearing) | DET-11, RSP-05, IFF-03 | Without the range gate a relayed answer can pass by chance when the fake bearing happens to match. |
| DET-11 / OBS-07 | Bearing/elevation per box from compass azimuth + camera HFOV (`SENSOR_INFO_PHYSICAL_SIZE`, `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, HFOV = 2·atan(w/2f)) + rotation-vector pitch; chip "045° +12°" | Tank crews want a direction, not a pixel; feeds IFF-06 and DET-08 | LOW | 0.5 | DET-04 | Magnetometer indoors is ±15-20° at best — fine for a ±20° cone, say so in the pitch. |
| PIT-02 | `docs/PROTOCOL.md`: FOF-1 spec, verify order, threat table (beacon exposure, harvest, replay, relay/MITM, attribution, key capture, clock drift, GPS-denied degradation), positioning vs Mode 4/5, Remote ID, BCIS, IR beacons, FAFOS; production path (Ed25519, IR OOK, key distribution) | Military Deployability 30 %: shows you know where this sits in the real IFF landscape and what the field version needs | LOW | 1.0 | IFF-01..06 | Source material is in "Competitor Feature Analysis" below. |
| OBS-08 | Verdict toast that lists the checks in order with ✓/✗ and the round-trip time ("in-box ✓ nonce ✗ replayed · 1.8 s") | Explains the protocol to judges inside the frame; no slides needed | LOW | 0.5 | IFF-03 | |
| DET-07 | N-of-M track persistence (IoU-matched ids, show a drone box after 2 of 3 frames) | Suppresses one-frame bird/kite flicker; stable tap targets; the Drone-vs-Bird literature's standard cheap fix is track-level filtering | LOW | 0.25 (on top of DET-06) | DET-06 | |
| EVL-03 | On-device numbers in README: load time, ms/frame, fps, APK size, RAM, phone model | SC1 Technical Execution evidence that costs nothing once OBS-04 exists | LOW | 0.25 | OBS-04 | |
| JDG-04 | `clikart-cli <model> serve --offline --device cpu --host 0.0.0.0` on the laptop as the **second ClikaRT device type** (SC1 bonus) and the eval backend | Already verified working; the bonus does not require the FastAPI dashboard | LOW | 0.25 | — | Show the `--offline` flag in the terminal during the demo. The serve also has its own `GET /dashboard` (server request stats, serve_api.h:31) — not our IFF events, but a free "second device is live" page. |
| EVL-01 | Test set: 10-20 images (quadcopters near/far/sky, birds, kites, an airliner, people, the actual demo target) + JSON ground truth | Lets the detector choice be a measured decision, not a guess | LOW | 0.75 | — | Photograph the demo target with phone A itself. |
| EVL-02 | `eval/run_eval.py` → precision/recall per detector (RT-DETR proxy classes vs OWLv2 prompts) → markdown table | Technical Execution; the honest "COCO has no drone class, here is what that costs" story | MEDIUM | 1.0 | JDG-04, EVL-01 | Last to cut per the plan. |
| DET-08 | First-drone alert: chime (bundled OGG via `SoundPool`, not TTS) + banner "DRONE · bearing 045 · high" | Problem Fit: crews are blind upward and loud; an audible cue is the operational payoff of detection | LOW | 1.0 | DET-07, DET-11 | TTS engines need downloaded voice data and may prompt for a Play Store download — avoid under airplane mode (LOW-MEDIUM confidence; forum evidence only). First to cut per the plan. |
| DET-09 | OWLv2 zero-shot detector option (`AutoModelForZeroShotObjectDetection`, labels `["drone","quadcopter","uav"]`, `labels` required) selectable in settings | Real recall on quadcopters that COCO never saw; the "accuracy option" story | MEDIUM | 1.5 (risk x2: speed) | DET-02 interface, model snapshot pushed | Cached config: 960x960 input, patch 16 → 3600 tokens through ViT-B/16 per frame; expect seconds per frame on a phone CPU (LOW confidence, must bench). Promoted to TS if PIT-04 finds COCO cannot see the demo target. |
| JDG-02 / JDG-03 | FastAPI dashboard (`/dashboard`, `POST /api/events`: live list, counts, latest thumbnail) + fire-and-forget event POST from the observer (bounded queue, 500 ms timeout, never blocks the UI) | Nice second screen; some SC1 judges like a cross-device view | MEDIUM | 2.0 | JDG-04 optional | **Lowest value after the user's correction**: scrcpy already gives judges the live view, and SC1's second device type is satisfied by JDG-04. First to cut in my order (see below). |
| RSP-09 | "RF silence" proof line on the responder: "transmissions initiated: 0 · answered authentic challenges: N · ignored: M" | Turns the radio-silence claim into a number the judges can read on phone B | LOW | 0.1 | RSP-02 | |
| RSP-10 | Harvest demo: `curl` from the laptop without the key → phone B shows "ignored: bad challenge MAC" | Shows the drone does not light up for strangers; costs nothing beyond RSP-02 | LOW | 0 | RSP-02 | One line in the demo script. |

**Differentiator total: ≈ 10.9 h** (row sum 10.85; corrected from 10.5). Realistic budget for them is 1-4 h, so expect to ship the first four to six rows.

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem good but create problems in a 20-hour, offline, two-phone demo.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Training or fine-tuning a drone detector | COCO has no drone class; recall on small quadcopters is poor | No data, no GPUs, no hours; the Drone-vs-Bird and Anti-UAV challenges show this is a research-scale problem | COCO proxy classes + OWLv2 prompts; eval table states the gap honestly; PROTOCOL.md lists fine-tuning as production work |
| Mapping `cell phone` → drone for the demo | Phone B is what the camera sees | Judges will read the label; it discredits the detection story | PIT-04: pick a target COCO actually boxes, or use OWLv2 |
| ByteTrack/DeepSORT-style multi-object tracker library | Stable ids, smooth boxes | Extra dependency and tuning; 1 fps detection makes motion models useless | DET-06 box lock + DET-07 IoU N-of-M |
| Motion / background-subtraction detector for tiny drones | Classic small-target trick | Needs a static camera; the observer phone is handheld | Document SAHI-style tiling / sky ROI as production path |
| SAHI tiling / 2x2 crops on the phone | +5-14 % AP on small objects in the literature | Multiplies per-frame latency on a CPU already at ~1 fps; the demo is indoors at 1-2 m | Production path only |
| Responder answers over HTTP (RF) if the QR cannot be read | Reliability | Destroys the radio-silence and attribution claims; judges will catch the fallback | Fix readability (distance, brightness, full-res capture); show UNKNOWN honestly |
| Periodic beacon / heartbeat from the responder | "Why not just announce yourself?" | This is exactly the Remote ID / Mode 5 Level 2 squitter exposure the pitch argues against | Respond only to authenticated challenges (RSP-02) |
| mDNS/NSD auto-discovery of the responder | Convenience | Flaky on hotspot LANs, Android NSD quirks, hours lost on stage | Type the IP once (RSP-01, OBS-05) |
| On-off-keyed LED/screen blink optical channel | Closer to the production IR beacon | Camera frame-rate sync, exposure, ambient light — a day of work | QR now; blink OOK in PROTOCOL.md as the production channel |
| Ed25519 per-drone signatures, PKI, multi-observer key distribution | "Real" crypto | Key management UI and distribution are the hard part; HMAC with pre-placed keys matches how Mode 4/5 keys are loaded anyway | HMAC-SHA256 now; Ed25519 documented as the upgrade when the drone must hold no shared secret |
| TLS / certificate pinning on the hotspot link | Security optics | Offline cert provisioning, hours; the challenge is already MAC-authenticated and carries no secret | Say "the challenge is authenticated, not encrypted, by design" |
| NTP/time sync between phones | Nonce age check | No internet; the 10 s window and the ±1 day key window already absorb drift | Set both phones' clocks manually before the demo |
| Streaming live video frames to the laptop dashboard | Judges' screen | Bandwidth, latency, hours | scrcpy over USB (JDG-01) |
| WebSocket/auth/persistence on the dashboard | "Production" dashboard | Hours for nobody | In-memory FastAPI if JDG-02 is built at all |
| COCO mAP tooling for the eval | Rigor | Hours; 10-20 images do not support mAP | Simple precision/recall per class, one table |
| Map view, AR 3D overlays, multi-language, theming, onboarding, login | Polish | Zero judge value in 3 minutes | Big-font demo layout, thick rings |
| Thermal/IR camera, acoustic or RF detection | Multi-sensor CUAS | Hardware; off-brief | Camera video is the primary sensor per the problem statement |
| Slides | Habit | Not allowed | The phones, the laptop mirror, the README |

## Feature Dependencies

```
PIT-05 rehearsals ──requires──> everything in P1

OBS-03 QR decode in box ──requires──> DET-06 box lock ──requires──> DET-04 overlay ──requires──> DET-01 preview + DET-02 detector
OBS-03 ──requires──> RSP-03 QR render ──requires──> IFF-01 module
OBS-03 ──requires──> IFF-03 verdict engine ──requires──> IFF-01, IFF-02 nonce ledger
OBS-02 tap → challenge ──requires──> IFF-01, IFF-02, OBS-05 settings (responder IP, key)
RSP-02 listener ──requires──> IFF-01 ; RSP-04 buttons ──requires──> RSP-02, RSP-03
OBS-01 rings ──requires──> DET-04, IFF-03
OBS-06 event log ──requires──> OBS-01 (verdict events)
DET-03 class map ──requires──> DET-02 (labelId semantics from the model table)
DET-05 HttpDetector ──requires──> JDG-04 serve (also the EVL-02 backend)

IFF-06 position gate + Relay ──requires──> DET-11 bearing, RSP-05 position, IFF-03
IFF-08 Impostor ──requires──> IFF-03, RSP-04
DET-08 alert ──requires──> DET-07 persistence (no alert on flicker), DET-11 bearing
DET-09 OWLv2 ──requires──> DET-02's Detector interface ; ──promoted-by──> PIT-04 outcome
EVL-02 eval ──requires──> JDG-04 serve, EVL-01 images
JDG-02 dashboard ──requires──> JDG-03 event POST ; JDG-01 scrcpy ──replaces──> JDG-02 for judge visibility
PIT-02 PROTOCOL.md ──enhances──> PIT-06 Q&A card
PIT-03 backup video ──requires──> JDG-01, one passing rehearsal

DET-08 TTS ──conflicts──> airplane mode (voice data download)   → bundled OGG instead
Base64url QR text ──conflicts──> version-3 QR capacity          → raw bytes / Base32
"cell phone → drone" map ──conflicts──> judge credibility         → PIT-04 target test
```

### Dependency Notes

- **OBS-03 requires DET-06:** the responder's screen changes from "drone image" to "QR" on challenge, so the detector will drop the drone box exactly when the QR must be read inside it. The lock keeps the box (and therefore attribution) alive for the 10 s window.
- **IFF-06 requires a range gate, not only a cone:** with observer and responder 1-2 m apart the GPS bearing is noise; the near-field rule (skip the bearing test under 30 m, keep attribution and the nonce) makes Honest deterministic, and the R_max rule makes Relay (5 km away) deterministic.
- **DET-09 is promoted by PIT-04:** if RT-DETR does not box the chosen demo target as aeroplane/bird/kite with score ≥ 0.25 at H4, OWLv2 becomes table stakes and EVL-02 is cut to pay for it.
- **JDG-01 replaces JDG-02 for visibility:** after the user's correction the laptop is not the demo; USB mirroring gives judges the live observer view without any network and without building anything.
- **DET-05 and JDG-04 share the serve process:** one `clikart-cli <model> serve --offline --host 0.0.0.0` on the laptop is the contingency detector, the eval backend and the SC1 second device type (default bind is loopback only).

## MVP Definition

### Launch With (v1) — the 3-minute demo cannot run without these

- [ ] DET-01..06 — live preview, in-process ClikaRT detection, labelId class map (`aeroplane`/`bird`/`kite` → drone), overlay, HttpDetector contingency, box lock
- [ ] IFF-01..05 — iff module with tests and vectors, nonce ledger, ordered verdicts, day window
- [ ] RSP-01..06 — responder mode, authenticated listener that ignores bad challenges, QR render, Honest/Replay/Silent, demo position, idle drone image
- [ ] OBS-01..06, OBS-10 — rings/tags, tap-to-challenge, in-box QR decode, status strip with OFFLINE, settings, event log, reset
- [ ] JDG-01 — scrcpy mirror
- [ ] SEC-01..04 — nothing secret in the repo or logs, SecureRandom, constant-time compare
- [ ] PIT-01, PIT-03..06 — README with built-vs-pre-existing, backup video, demo target decided at H4, three rehearsals, script + Q&A card

### Add After Validation (v1.x) — in this order, as hours allow

- [ ] IFF-08 Impostor verdict — trigger: P1 green at H10
- [ ] IFF-06 + DET-11 position gate and Relay button — trigger: ≥ 1.5 h left after P1
- [ ] PIT-02 PROTOCOL.md — trigger: H17 buffer exists
- [ ] OBS-08 verdict explanation, DET-07 persistence, EVL-03 numbers, JDG-04 serve shown in demo — trigger: each is ≤ 0.5 h
- [ ] EVL-01/02 eval table — trigger: serve answers curl by H1 and ≥ 1.75 h remain
- [ ] DET-08 audio alert — trigger: everything above shipped
- [ ] DET-09 OWLv2 option — trigger: PIT-04 failure (then it jumps to P1), otherwise last

### Future Consideration (v2+) — documented, not built

- [ ] IR LED on-off-keyed optical response, photodiode/camera decode (the FAFOS-style channel) — needs hardware
- [ ] Ed25519 per-drone signatures, multi-observer key distribution, key rotation UI
- [ ] Fine-tuned drone detector, SAHI tiling / sky ROI, thermal input, track-level bird rejection
- [ ] JDG-02 laptop dashboard — only if a second screen is ever needed beyond scrcpy

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| DET-02 in-process ClikaRT detection | HIGH | HIGH | P1 |
| DET-03 labelId class map (`aeroplane`/`bird`/`kite`) | HIGH | LOW | P1 |
| DET-06 box lock during challenge | HIGH | LOW | P1 |
| IFF-01..05 module, nonce ledger, verdicts, tests | HIGH | MEDIUM | P1 |
| RSP-02 authenticated listener (ignores bad challenges) | HIGH | MEDIUM | P1 |
| RSP-03 QR render (raw bytes, version ≤ 4) | HIGH | LOW | P1 |
| RSP-04 Honest / Replay / Silent | HIGH | LOW | P1 |
| OBS-03 in-box QR decode (ML Kit bundled) | HIGH | MEDIUM | P1 |
| OBS-04 OFFLINE badge + fps/ms | HIGH (SC1) | LOW | P1 |
| OBS-06 on-phone event log | MEDIUM | LOW | P1 |
| JDG-01 scrcpy mirror | HIGH | LOW | P1 |
| PIT-04 demo target decided at H4 | HIGH | LOW | P1 |
| PIT-05 three rehearsals, PIT-03 backup video | HIGH | LOW | P1 |
| IFF-08 Impostor → FOE · bad tag | HIGH | LOW | P2 |
| IFF-06 position gate + Relay | HIGH | MEDIUM | P2 |
| PIT-02 PROTOCOL.md with threat table | HIGH (Deployability) | LOW | P2 |
| OBS-08 verdict explanation toast | MEDIUM | LOW | P2 |
| DET-07 N-of-M persistence | MEDIUM | LOW | P2 |
| EVL-01/02 eval table | MEDIUM | MEDIUM | P2 |
| DET-08 audio alert | MEDIUM | MEDIUM | P2 |
| DET-09 OWLv2 option | MEDIUM (HIGH if COCO fails PIT-04) | MEDIUM | P2/P1 |
| JDG-02/03 FastAPI dashboard | LOW (after correction) | MEDIUM | P3 |
| DET-10 tiling, RSP blink OOK, Ed25519, PKI | LOW now | HIGH | P3 |

## Hour-plan fit and the cut order

The PROJECT.md cut order is **audio alert → OWLv2 option → Relay button → eval table**. I recommend amending it to:

**laptop dashboard (JDG-02/03) → audio alert (DET-08) → OWLv2 option (DET-09) → eval table (EVL-02) → Relay button (IFF-06)**, with one conditional: if PIT-04 shows COCO cannot see the demo target at H4, OWLv2 jumps to P1 and the eval table is the first thing cut.

Why:
1. **Dashboard first to cut.** The user's correction makes the devices the demo. scrcpy gives judges the live observer view in 15 minutes with no network; SC1's second device type is satisfied by `clikart-cli serve` (JDG-04), which is already verified and needed by the eval anyway. The dashboard's 2 h is the single biggest discretionary block in the plan.
2. **Relay last to cut, not second-to-last.** "Resist MITM" is in the T1-04 wording; `FOE · position mismatch` is the only relay/MITM defence that shows up as a red ring with a reason in the camera view, and it is what distinguishes FOF-1 from FAFOS/ReDroId-style optical schemes (see below). The range-gated version costs 1.5 h including bearing.
3. **Eval table below Relay.** The eval is Technical Execution evidence, but the judges' 3 minutes are on the phones; a one-line honest statement ("COCO has no drone class; OWLv2 found the quadcopter in N/10 images") plus EVL-03 numbers carries most of the value.
4. **Audio alert stays near the top of the cut list**, as the plan says: it is Problem-Fit flavour, 1 h, and it has an offline TTS trap.

Budget reality: table stakes ≈ 17.2 h at my estimates, differentiators ≈ 10.9 h, working hours = 18.

**Consistency with PROJECT.md (flagged, not silently changed):** PROJECT.md still lists the laptop dashboard as an Active requirement, puts it in the H7-10 slot, and has Relay in the 3-minute demo script. The cut-order amendment above is a *recommendation* pending the user's confirmation; until PROJECT.md is updated, JDG-02/03 remain a requirement (P2 at lowest), not a deleted feature, and Relay stays in the script with the hour plan's own "Relay button" cut as the fallback. PROJECT.md's decided topology (responder = second phone, HTTP over hotspot, QR response, laptop = dashboard + eval + `serve`, FOF-1 verify order) is what every row above assumes. The roadmap should gate every differentiator on an explicit "P1 green" check at H10 and H14.

## COCO classes, small drones and false positives (how others do it)

**COCO → drone mapping.** COCO's 80 classes have no drone/UAV class; the usual proxies are `airplane` (id 4 in the 0-indexed 80-class table), `bird` (14) and `kite` (33). The cached `PekingU/rtdetr_v2_r18vd` config (HIGH, local) uses the VOC-style spellings `aeroplane` and `motorbike`, and ClikaRT's `Detection` returns that string plus `labelId` (Types.kt:885), so **match on ids 4/14/33 and 0 (person)**. OWLv2 takes free-text queries through `DetectOptions.labels` (required on open-vocabulary detectors; the phrase index comes back as `labelId`) — use `drone`, `quadcopter`, `uav`.

**What the literature does about small drones and birds/kites/planes (MEDIUM):**
- *Tiling / slicing (SAHI):* run the detector on overlapping crops and merge; +6.8/5.1/5.3 AP on VisDrone/xView without retraining, +12.7-14.5 with slice-aware fine-tuning ([Akyon et al.](https://arxiv.org/pdf/2202.06934)); OWL-ViT + SAHI gave +6.8 mAP@50 on small objects ([VJS 2026](https://vjs.ac.vn/jcc/article/view/21899)). Cost: multiplies latency — production path only here.
- *Track-level filtering:* the Drone-vs-Bird challenge's recurring finding is that plain detectors fire on birds and that confidence filtering plus a tracker (e.g. YOLOv7 + CSRT) and temporal-consistency stages reduce false alarms ([Coluccia et al., Sensors 2021](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8072977/); [YOLOv7+CSRT entry](https://cs.paperswithcode.com/paper/drone-vs-bird-drone-detection-using-yolov7); [multi-scale + temporal entry, WOSDETC 2025](https://arxiv.org/html/2504.19347v2)). This is what DET-06/DET-07 approximate cheaply.
- *Motion/trajectory cues:* speed, acceleration, turning rate and wingbeat jitter separate birds from drones when pixels are scarce ([Sensors 2026, trajectory-based](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12899335/); [YOLOMG appearance+motion fusion](https://arxiv.org/html/2503.07115v1)). Needs a static camera or stabilisation — anti-feature on a handheld phone.
- *Datasets that define "hard":* Anti-UAV (RGB-T, 318 pairs), DUT Anti-UAV (visible, 10k images), TDTIV (IR, targets ≤ 20x20 px) ([Anti-UAV challenge report](https://arxiv.org/pdf/2305.07290); [DUT Anti-UAV](https://arxiv.org/abs/2205.10851v1); [TDTIV, ICCV 2025](https://openaccess.thecvf.com/content/ICCV2025/papers/Zhang_Tracking_Tiny_Drones_against_Clutter_Large-Scale_Infrared_Benchmark_with_Motion-Centric_ICCV_2025_paper.pdf)). State-of-the-art trackers still score ~36-68 % state accuracy on thermal tiny-drone sets — honest framing for the pitch: detection is the easy-to-demo, hard-to-perfect half; IFF is where the novelty is.
- *Planes vs drones:* an airliner is large, slow in angular terms and high; a kite is tethered and slow; both are better handled by the IFF step than by the detector — a plane will simply never answer a challenge and stays `?`/UNKNOWN, never FRIEND.

**Practical rule for this build:** per-class thresholds (drone proxies ~0.25-0.3, person 0.5), DET-06 lock, DET-07 N-of-M, and a demo target that COCO actually boxes (PIT-04). Everything else is a documented production path.

## What the judges expect (and which features score)

No public rubric uses the exact "Problem Fit / Military Deployability / Technical Execution / Creativity" wording, so this is inferred from analogous rubrics (LOW-MEDIUM): the NDIA Global Hackathon scores Mission Impact 30 %, Technical Innovation 25 %, Usability & Design 20 %, Security & Sustainability 15 % (readiness for DoD testing) ([hackathon.ndia.org](https://hackathon.ndia.org)); the Air Force "Hack the Ranch" graded functionality, usability, relevancy and *ability for rapid deployment*; generic guidance is "judge what they built, not what they planned".

| Criterion (weight) | What scores | Features |
|---|---|---|
| Problem Fit 25 % | A sharp operator story: crews are blind upward; soldiers "jam everything" or shoot at every drone because they cannot tell friend from foe ([Business Insider via Yahoo](https://www.yahoo.com/news/soldiers-ukraine-panic-jam-everything-090501576.html)); Ukraine's own answer, UA DroneID, is RF/network-based ([Cossack Labs](https://www.cossacklabs.com/news/press-release/cossack-labs-joins-ua-droneid-technology-development/)) | DET-01..04, OBS-01, DET-08, PIT-06 |
| Military Deployability 30 % | Works with the network blocked; COTS phone; pre-placed keys (same operational model as Mode 4/5 key loading, no server); degraded modes stated (GPS-denied → no cone check, attribution and nonce still hold; daylight washes a screen → IR LED path); honest about the hotspot being an RF emission from the observer; threat table | OBS-04, SEC-01..04, PIT-02, IFF-04, RSP-05 |
| Technical Execution 25 % | It runs live, twice; tests and vectors; numbers (ms/frame, APK size); the detector choice is measured | IFF-05, EVL-03, EVL-02, PIT-05, PIT-03 |
| Creativity 20 % | The *camera box is the attribution channel*; the answer only works for the one observer who asked, at the place it is flying; five verdicts with reasons on stage | OBS-03, IFF-06, IFF-08, OBS-08, RSP-09/10 |
| SC1 (ClikaRT, offline, repo, 2 device types) | AAR in-process, `offline=true`, OFFLINE badge visible, README with model/ license handling, laptop serve as second device | DET-02, OBS-04, PIT-01, JDG-04 |

**Likely Q&A (prepare 1-line answers, PIT-06):** Why QR and not an RF reply? · What if the enemy films the QR with a telephoto lens? (bound to this challenger's nonce; dead in 10 s; wrong observer = bad tag) · What if a drone is captured? (per-drone day key; leaks one drone, one day) · Why not Remote ID? (unauthenticated broadcast, reveals operator location) · Why not Mode 5? (aircraft-class certified crypto hardware, continuous Level 2 squitter, cost/size) · Does the observer radiate? (yes, a short challenge burst — and in this demo the hotspot; production uses a directional optical or burst RF challenge) · GPS-denied? (cone check off, attribution + nonce + tag remain; verdict can never be FRIEND on silence) · Night/daylight? (screen is self-luminous; sunlight washes it — IR LED is the production channel) · Several drones? (one challenge per box, one nonce per challenge, droneId in the answer) · What pre-existed? (ClikaRT, weights, Android template, ML Kit, ZXing; everything in the repo was built today).

## Competitor Feature Analysis

How existing systems handle the four failures the team framed (beacon exposure, replay/harvest, relay/MITM, attribution) — and where FOF-1 sits.

| System | Channel | Friendly emits before being asked? | Replay / harvest | Relay / MITM | Attribution to one object | Our approach (FOF-1) |
|---|---|---|---|---|---|---|
| IFF Mode 4 (Mark XII) | RF Q&A, encrypted interrogation, key changes once a day | Only when interrogated | Daily code; feared "more easily compromised"; being retired ([UKDJ](https://ukdefencejournal.org.uk/new-system-rolled-out-to-reduce-risk-of-friendly-fire/); [DSCA 18-14](https://samm.dsca.mil/sites/default/files/DSCA%2018-14.pdf)) | Not a design goal | Interrogator beam + range timing | Fresh 16-byte nonce per challenge, per-drone day key, 10 s window |
| IFF Mode 5 (Mark XIIA, STANAG 4193) | Spread-spectrum RF, time-dependent authentication, code changes every few seconds, Type 1 crypto (KIV-77); Level 2 adds periodic position squitter ([DSCA 18-14](https://samm.dsca.mil/sites/default/files/DSCA%2018-14.pdf); [Aviation Today](https://www.aviationtoday.com/2019/09/18/raytheon-preparing-military-aircraft-iff-mode-5-mandate-next-june/); [Indra MK XIIA](https://www.indracompany.com/sites/default/files/MK-XIIA%20IFF%20TRANSPONDERS.pdf)) | Level 1: on interrogation; Level 2: yes, squitter | Time-of-day authentication; nonce in the interrogation per a forum source (LOW: [metzdowd](https://www.metzdowd.com/pipermail/cryptography/2017-March/031752.html)) | Position reporting lets the interrogator correlate | Beam/range | Same idea (time + nonce + position) on a phone, with the answer optical and directional instead of RF; no certified hardware — stated as the trade-off |
| Mode S / ADS-B | 1090 MHz plaintext squitter | Yes, continuously | No authentication; SDR replay and spoofing are trivial ([survey, arXiv 2504.07358](https://arxiv.org/pdf/2504.07358); [Strohmeier et al.](https://arxiv.org/pdf/1307.3664)) | None | By reported position only | Nothing is broadcast; every answer is bound to a challenge |
| Civilian Remote ID (ASTM F3411 / FAA Part 89) | BLE / Wi-Fi broadcast of drone + operator position | Yes, continuously | Observers "have no way to detect whether the messages were sent by a UA" or "a laptop or smartphone"; F3411 "defines Authentication Message framing only" ([RFC 9575](https://www.rfc-editor.org/rfc/rfc9575)); open-source spoofers exist ([Jared Watkins / CYD Campus](https://www.jaredwatkins.com/research/drone-detection/open-source/drone-remoteid-spoofer/)); DJI DroneID let AeroScope locate Ukrainian operators ([C4ISRNET](https://c4isrnet.com/battlefield-tech/2022/10/17/how-ukraine-learned-to-cloak-its-drones-from-russian-surveillance)) | None | None (any radio in range) | Position is inside the MAC and checked against the camera cone; no operator position ever leaves the drone |
| BCIS / BTID (STANAG 4579) | 38 GHz mm-wave Q&A between vehicles before firing; encrypted; > 95 % ID within 1 s target; $30-40k per vehicle, cancelled then succeeded by BTID ([GlobalSecurity](https://www.globalsecurity.org/military/systems/ground/bcis.htm); [GAO NSIAD-95-153](https://govinfo.gov/content/pkg/GAOREPORTS-NSIAD-95-153/pdf/GAOREPORTS-NSIAD-95-153.pdf)) | Only when interrogated | Encrypted Q&A | Narrow beam limits relay | Narrow beam = the object you pointed at | Same "point and ask" ergonomics with a phone camera as the beam |
| IR glint tape / Phoenix IR beacon | Passive reflector or flashing IR LED seen in NVG ([Phoenix Jr.](https://offbase.co/products/cejay-engineering-phoenix-jr-infrared-ir-beacon); [Warrior GloTape](https://willsoptics.com/product/warrior-glotape/)) | Yes (beacon) / always (tape) | No crypto: anyone with the tape or the blink pattern is "friendly"; sensor mismatch caused a 1992 friendly-fire engagement ([CALL 92-4](https://www.globalsecurity.org/military/library/report/call/call_92-4_vign4.htm)) | n/a | Visual, good | FOF-1 keeps the "look at it" attribution but makes the mark a one-time cryptographic answer |
| Laser IFF / retroreflector patents | Laser interrogation, coded or delayed optical return ([US4361911](https://patents.google.com/patent/US4361911); [US5422645](https://patents.google.com/patent/US5422645)) | No | Weak codes | Directional | Directional | FOF-1 = the same geometry with real MACs |
| FAFOS (Allen Control Systems, Oct 2025) | Modulated NIR/SWIR pulse → drone photodiodes verify a public-key signature → optical flash back; < 200 ms, 2 km; "invisible, unjammable", works GPS/comms-denied; can command return-to-base ([press release](https://www.defence-industries.com/index.php/pressreleases/allen-control-systems-launches-friend); [Defense Post](https://thedefensepost.com/2025/10/31/acs-drone-optical-iff)) | No | Signature on the challenge; replay/relay resistance not stated | Not stated | Beam direction | Closest commercial analog — FOF-1 adds nonce-bound replies, position binding against relay, and camera-box attribution, on a COTS phone; cite it as validation that optical IFF is where the market is going |
| ReDroId (Nassi et al., 2021) | Drone LEDs flash an RSA-SecurID-style time token; camera + ML decode ([BGU](https://www.bgu.ac.il/researcher/asaf-shabtai/publications/80936637/)) | Yes (one-way token) | Replayable within the token period; no challenge | None | Camera, good | FOF-1 is challenge-bound, so the token cannot be pre-recorded |
| Blinking-LED swarm ID (OCC, 2023) / Ford anti-collision-light ID (2018) | Unique blink patterns; registration number blinked at 80 ft ([arXiv 2302.04770](https://arxiv.org/pdf/2302.04770); [Ford white paper](https://media.ford.com/content/dam/fordmedia/North%20America/US/2018/03/07/Ford-A-Zero-Cost-Solution-for-Remote-Identification.pdf)) | Yes | Identification, not authentication | None | Camera | Same channel, with crypto |
| UA DroneID (Aerorozvidka + Cossack Labs, 2023-25) / RPC Pulsar transponder | Secure RF transponder/telemetry integrated with DELTA; developers claim −90 % friendly fire; details not public ([Cossack Labs](https://www.cossacklabs.com/news/press-release/cossack-labs-joins-ua-droneid-technology-development/); [Defense Post on Pulsar](https://thedefensepost.com/2023/12/21/russia-drone-friend-foe/amp/)) | RF-based | Unknown | Unknown | Network, not per-object | FOF-1 is the radio-silent, per-object complement; positioning line: "DroneID tells the network; FOF-1 tells the gunner looking at it" |
| Dedrone DroneTracker / DroneShield DroneOptID / MITRE CARPE Dronvm | Detection stacks: sensor fusion, PTZ verification, make/model classification, alerts, distance/altitude estimates; the smartphone app photographs a drone and sends a report ([Dedrone 4.1](https://www.unmannedairspace.info/counter-uas-systems-and-policies/dedrone-launches-dronetracker-4-1-to-integrate-radar-and-cameras/); [DroneOptID](https://droneshield.com/droneoptid); [CARPE Dronvm](https://www.mitre.org/our-impact/intellectual-property/carpe-dronvm-enabling-everyone-be-counter-unmanned-aerial-system)) | n/a (sensors) | n/a | n/a | Camera tracks | They stop at "it is a DJI Mavic"; none answers "is it ours" — FOF-1's detection is table stakes, its IFF is the differentiator |

Academic framing for the attribution claim: the camera reading a 2D barcode is a *visual channel* that gives "demonstrative identification" of the device in view — Seeing-Is-Believing, IEEE S&P 2005 ([Duke Scholars](https://scholars.duke.edu/individual/pub1493989); [journal version](https://www.inderscience.com/filter.php?aid=23425)). Relay defences in the literature are distance bounding (round-trip timing) or verified position; FOF-1 uses the latter with a camera-derived bearing ([Hancke & Kuhn](https://www.rfidblog.org.uk/RFIDdistancebound-Securecomm2005.pdf)).

## Verified dependency versions (for the features above)

| Library | Current (verified 2026-10-10) | Project pin | Confidence | Note |
|---|---|---|---|---|
| CameraX | 1.6.2 stable (2026-08-26) | 1.4.1 | HIGH (release page) | Keep the pin; no feature here needs newer |
| ML Kit barcode-scanning (bundled) | 17.3.0 (unchanged since 2024-08-07; unbundled counterpart 18.3.1) | 17.3.0 | HIGH (release notes) | Bundled is mandatory for offline |
| ZXing core | 3.5.4 (2025-11-11) | — | HIGH (Maven Central) | QR generation on the responder |
| NanoHTTPD | 2.3.1 (2016-08-12) | — | HIGH (Maven Central) | Old but tiny; raw `ServerSocket` is the zero-dep alternative |
| Ktor server CIO | 3.6.0 (2026-09-16) | — | HIGH (Maven Central) | Heavier alternative |
| OkHttp | 5.5.0 (2026-08-16) | — | HIGH (Maven Central) | Or `HttpURLConnection` |
| kotlinx-serialization-json | **1.11.0 latest stable**; 1.12.0-RC (2026-09-04) is a pre-release — do not pin it | — | HIGH (Maven Central metadata) | Only needed for event JSON / vectors; `org.json` is already on Android |
| Kotlin Gradle plugin (`org.jetbrains.kotlin.jvm`, for `:iff`) | 2.4.21 latest stable (2.5.0-Beta1 is pre-release) | 2.2.10 (project) | HIGH (plugins.gradle.org) | Keep 2.2.10 to match the app's built-in Kotlin; do not churn |
| scrcpy | 5.0.1 (2026-10-08) | — | HIGH (GitHub releases) | USB mirror + `--record`; audio needs Android 11+ |
| FastAPI / uvicorn | latest on PyPI 0.143.0 / 0.54.0; laptop has 0.143 / 0.51 installed per PROJECT.md | — | HIGH (PyPI JSON) | Only if JDG-02 is built; the installed versions are fine |

## Sources

**Local ground truth (HIGH):** `C:/Users/C/clika/models/models--PekingU--rtdetr_v2_r18vd/snapshots/5650961749fa93567c0d46fc7f43ea4f9e914107/config.json` (80-class `id2label`, `aeroplane`=4, `bird`=14, `kite`=33, `person`=0; preprocessor 640x640) · `…/models--google--owlv2-base-patch16-ensemble/…/config.json` (image_size 960, patch 16) · `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/commonMain/io/clika/modelverse/Types.kt` (`DetectOptions` lines 847-870: `labels` required on open-vocabulary, refused on closed-set; `DEFAULT_SCORE_THRESHOLD = 0.5f` line 867; `ImageInput.Rgba8888` lines 801-807; `Detection(label, labelId, score, box)` line 885), `Pipelines.kt` (`ObjectDetectionPipeline`, lines 293-316, `internal constructor`), `Auto.kt` (`AutoModelForObjectDetection` line 283, `AutoModelForZeroShotObjectDetection` line 290, task strings `object-detection` / `zero-shot-object-detection` lines 20-21), `Modelverse.kt` (`pipeline(task, source, options)` line 177, `pipeline(task, model)` line 199), `androidMain/.../ClikaRtAndroid.kt` (`load(context, license)` line 82, `trimOnMemoryPressure` 101, `bridgeLogs` 173, license code names 70-76) · AAR `clika-runtime-android-0.6.4.aar`: `AndroidManifest.xml` `minSdkVersion="28"`, `jni/arm64-v8a` only (five `.so`, 284 MB) · `C:/Users/C/clika/reference/examples-0.6.4/android/README.md` (license handling, serve sample) · `C:/Users/C/clika/reference/modelverse-server-headers/serve_api.h` (`POST /v1/detections` multipart image + `prompt`, `/health`, `/v1/health`, `/dashboard`) · `clikart-cli.exe 0.6.4 --help`, `list --all`, `rt-detr --help`, `owlvit --help`, `rt-detr serve --help` (commands `detect`/`bench`/`serve`; global `--offline`, `--cache-dir`; serve `--host` default 127.0.0.1, `--port` 8000, `--device`, `--no-web-ui`).

**Official docs (HIGH):** ML Kit barcode scanning Android guide — https://developers.google.com/ml-kit/vision/barcode-scanning/android (bundled vs unbundled, QR-only formats, 2 px/module, 1280x720-1920x1080, auto-zoom) · `Barcode.getRawBytes()` — https://developers.google.com/android/reference/com/google/mlkit/vision/barcode/common/Barcode · ML Kit release notes — https://developers.google.com/ml-kit/release-notes · CameraX releases — https://developer.android.com/jetpack/androidx/releases/camera · QR module counts — https://www.qrcode.com/en/about/version.html · scrcpy — https://github.com/Genymobile/scrcpy · RFC 9575 — https://www.rfc-editor.org/rfc/rfc9575 · Maven Central metadata for ZXing, NanoHTTPD, Ktor, OkHttp, kotlinx-serialization.

**IFF lineage (MEDIUM unless noted):** DSCA 18-14 (Mode 5 features) — https://samm.dsca.mil/sites/default/files/DSCA%2018-14.pdf · UK Defence Journal (Mode 4 → 5) — https://ukdefencejournal.org.uk/new-system-rolled-out-to-reduce-risk-of-friendly-fire/ · Aviation Today (Mode 5 mandate) — https://www.aviationtoday.com/2019/09/18/raytheon-preparing-military-aircraft-iff-mode-5-mandate-next-june/ · Indra MK XIIA datasheet — https://www.indracompany.com/sites/default/files/MK-XIIA%20IFF%20TRANSPONDERS.pdf · metzdowd cryptography list on Mode 5 nonces (LOW) — https://www.metzdowd.com/pipermail/cryptography/2017-March/031752.html · ADS-B security survey — https://arxiv.org/pdf/2504.07358 and https://arxiv.org/pdf/1307.3664 · Remote ID spoofer — https://www.jaredwatkins.com/research/drone-detection/open-source/drone-remoteid-spoofer/ · DRIP auth draft — https://www.ietf.org/archive/id/draft-ietf-drip-auth-39.xml · AeroScope/Remote ID in Ukraine — https://c4isrnet.com/battlefield-tech/2022/10/17/how-ukraine-learned-to-cloak-its-drones-from-russian-surveillance · BCIS — https://www.globalsecurity.org/military/systems/ground/bcis.htm and https://govinfo.gov/content/pkg/GAOREPORTS-NSIAD-95-153/pdf/GAOREPORTS-NSIAD-95-153.pdf · IR beacon/tape — https://offbase.co/products/cejay-engineering-phoenix-jr-infrared-ir-beacon, https://willsoptics.com/product/warrior-glotape/, https://www.globalsecurity.org/military/library/report/call/call_92-4_vign4.htm · Laser IFF patents — https://patents.google.com/patent/US4361911, https://patents.google.com/patent/US5422645 · FAFOS — https://www.defence-industries.com/index.php/pressreleases/allen-control-systems-launches-friend, https://thedefensepost.com/2025/10/31/acs-drone-optical-iff · ReDroId — https://www.bgu.ac.il/researcher/asaf-shabtai/publications/80936637/ · OCC multi-UAV ID — https://arxiv.org/pdf/2302.04770 · Ford optical ID — https://media.ford.com/content/dam/fordmedia/North%20America/US/2018/03/07/Ford-A-Zero-Cost-Solution-for-Remote-Identification.pdf · UA DroneID — https://www.cossacklabs.com/news/press-release/cossack-labs-joins-ua-droneid-technology-development/, https://dou.ua/lenta/news/ua-droneid-presented/ · Russian drone IFF transponder — https://thedefensepost.com/2023/12/21/russia-drone-friend-foe/amp/ · Seeing-Is-Believing — https://scholars.duke.edu/individual/pub1493989, https://www.inderscience.com/filter.php?aid=23425 · Distance bounding — https://www.rfidblog.org.uk/RFIDdistancebound-Securecomm2005.pdf.

**Detection practice (MEDIUM):** SAHI — https://arxiv.org/pdf/2202.06934 · OWL-ViT + SAHI — https://vjs.ac.vn/jcc/article/view/21899 · Drone-vs-Bird 2020 — https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8072977/ · YOLOv7 + CSRT entry — https://cs.paperswithcode.com/paper/drone-vs-bird-drone-detection-using-yolov7 · multi-scale + temporal (WOSDETC 2025) — https://arxiv.org/html/2504.19347v2 · trajectory-based bird/drone — https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12899335/ · YOLOMG — https://arxiv.org/html/2503.07115v1 · Anti-UAV challenge — https://arxiv.org/pdf/2305.07290 · DUT Anti-UAV — https://arxiv.org/abs/2205.10851v1 · TDTIV — https://openaccess.thecvf.com/content/ICCV2025/papers/Zhang_Tracking_Tiny_Drones_against_Clutter_Large-Scale_Infrared_Benchmark_with_Motion-Centric_ICCV_2025_paper.pdf · COCO-pretrained confusion (drones as birds) — https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10440445/.

**CUAS products and operators (MEDIUM/LOW, vendor press):** Dedrone DroneTracker — https://www.unmannedairspace.info/counter-uas-systems-and-policies/dedrone-launches-dronetracker-4-1-to-integrate-radar-and-cameras/ · DroneShield DroneOptID — https://droneshield.com/droneoptid, https://www.unmannedairspace.info/latest-news-and-information/droneshield-releases-second-generation-droneoptid-2-0-drone-detection-software/ · MITRE CARPE Dronvm — https://www.mitre.org/our-impact/intellectual-property/carpe-dronvm-enabling-everyone-be-counter-unmanned-aerial-system, https://www.army.mil/article/268447/usarcent_tests_phone_app_that_detects_uas · "jam everything" — https://www.yahoo.com/news/soldiers-ukraine-panic-jam-everything-090501576.html.

**Judging analogs (LOW-MEDIUM):** NDIA Global Hackathon criteria — https://hackathon.ndia.org · Air Force Hack the Ranch (functionality, usability, relevancy, rapid deployment) and generic rubric guidance — https://brainbound.blog/hackathon-judge-guide. No public source for the T1/SC1 brief or the CLIKA SC1 rules was found; those facts come from PROJECT.md.

**Unverified / my own estimates (LOW):** QR readable distance (~1.2 m at 1080p for a 6.5 cm version-3 code, ~2.5 m from a full-res capture) — derived from ML Kit's 2 px/module rule and a ~70° lens, not measured · OWLv2 per-frame latency on a phone CPU · RT-DETR per-frame latency on the target phone · Android TTS behaviour under airplane mode (forum evidence only) · indoor compass accuracy ±15-20°.

---
*Feature research for: camera-based CUAS detection + radio-silent optical IFF demo (FriendOrFoe)*
*Researched: 2026-10-10*

## Verification notes (2026-10-10)

Adversarial pass against the local ClikaRT 0.6.4 sources/AAR/CLI, Maven Central, PyPI, official Android / ML Kit docs and PROJECT.md. Template structure kept; fixes made in place.

**Corrected**
- DET-02: `ObjectDetectionPipeline` has an `internal` constructor — the app must get it from `Modelverse.pipeline("object-detection", model)` or call `VisionModel.detect(...)`; added the invoke signature `(image, options, candidateLabels)`. `DEFAULT_SCORE_THRESHOLD` is on Types.kt:867 (was cited as 866). Added the tightly-packed `ImageInput.Rgba8888` contract vs CameraX `PlaneProxy.getRowStride()` padding (CameraX javadoc only guarantees single-plane R,G,B,A byte order, not `rowStride == width*4`). Added the three license code names from `ClikaRtAndroid.kt`.
- DET-05 / JDG-04 / dependency notes: `serve` is a *model* subcommand (`clikart-cli <model> serve …`) and binds `127.0.0.1:8000` by default — added `--host 0.0.0.0`, without which neither phone can reach the laptop.
- DET-05 / OBS-02: Android 9+ (API 28) disables cleartext HTTP by default (official security-config doc); the app needs `android:usesCleartextTraffic="true"` or a network-security-config. The file previously assumed plain `http://` would just work.
- IFF-01 + versions table: AGP 9 built-in Kotlin removes `org.jetbrains.kotlin.android` but a pure JVM `:iff` module still needs `org.jetbrains.kotlin.jvm` with an explicit version (keep 2.2.10; latest stable is 2.4.21, 2.5.0-Beta1 is pre-release).
- SEC-01: `LicenseStore.kt` path corrected to `shared/src/main/kotlin/io/clika/examples/android/shared/`; added the `local.properties` key and private-file name from the real source.
- Versions table: kotlinx-serialization-json latest **stable** is 1.11.0 (1.12.0-RC is a pre-release and must not be pinned); ML Kit 17.3.0 date is 2024-08-07 (not 07-08); FastAPI/uvicorn latest on PyPI are 0.143.0 / 0.54.0 (installed 0.143 / 0.51 are fine); scrcpy 5.0.1 dated 2026-10-08.
- Differentiator hour total: row sum is 10.85 h, not 10.5 h (table-stakes 17.15 ≈ 17.2 h re-added and holds).
- Added an explicit PROJECT.md consistency paragraph: the dashboard demotion and the cut-order change are recommendations pending user confirmation; PROJECT.md's topology (second-phone responder, HTTP over hotspot, QR response, laptop = dashboard + eval + serve, FOF-1 verify order) is what the file assumes.

**Verified as written (HIGH)**
- ClikaRT: `AutoModelForObjectDetection` / `AutoModelForZeroShotObjectDetection.fromPretrained(source, VisionLoadOptions(offline=…))` (Auto.kt:283/290); `DetectOptions(maxResults=100, scoreThreshold=0.5, labels=[])`, `labels` required on open-vocabulary and refused on closed-set (Types.kt:851-870); `Detection(label, labelId: Long, score, Box)` (Types.kt:885); `ClikaRtAndroid.load/trimOnMemoryPressure/bridgeLogs`; `Modelverse.load(context, license)`; task strings `object-detection` / `zero-shot-object-detection`; AAR `minSdkVersion=28`, `arm64-v8a` only, five `.so` ≈ 284 MB; CLI families `rt-detr` and `owlvit` expose `detect`/`bench`/`serve`; `serve` routes `POST /v1/detections` (multipart image + `prompt`), `GET /health`, `GET /v1/health`, `GET /dashboard`; global `--offline`, `--cache-dir`; `--device cpu`. Cached RT-DETR `id2label`: 0 person, 4 aeroplane, 14 bird, 33 kite, 67 cell phone; preprocessor 640x640. OWLv2 config image_size 960, patch 16.
- Versions: CameraX 1.6.2 stable (2026-08-26; 1.7.0-alpha03 is the only newer line), ML Kit bundled 17.3.0, ZXing core 3.5.4 (2025-11-11), NanoHTTPD 2.3.1 (2016-08-12, last release), Ktor server CIO 3.6.0 (2026-09-16), OkHttp 5.5.0 (2026-08-16).
- Platform: ML Kit bundled = "about 2.4 MB", unbundled "requests before the download has completed produce no results", ≥ 2 px/module, 1280x720-1920x1080 recommended; `Barcode.getRawBytes()` exists (may return null); QR v3-L byte 53 / alnum 77, v4-L byte 78 (so 54-char base64url does not fit v3); scrcpy audio requires Android 11+.

**Still unverified / estimates**
- QR read distance (~1.2 m at 1080p, ~2.5 m from a full-res capture) — derived, not measured (LOW).
- RT-DETR and OWLv2 per-frame latency on the target phone; model load time; offline-license behaviour on the phone itself (LOW until H1-H4 bench).
- Whether CameraX on the target phone actually pads RGBA_8888 rows — the API permits it; handle both (MEDIUM).
- Android TTS voice-data download under airplane mode (forum evidence only, LOW); indoor compass accuracy ±15-20° (LOW).
- Whether `org.jetbrains.kotlin.jvm` 2.2.10 coexists cleanly with AGP 9.2.1's built-in Kotlin in one build — official migration page is silent on JVM modules (MEDIUM; confirm at H0-1 with `./gradlew :iff:test`).
- Judge rubric weighting inferences (LOW-MEDIUM) and the IFF-lineage secondary sources (MEDIUM) were not re-fetched in this pass.
- PROJECT.md itself states "base64url ≈ 54 chars, QR version 3" — that is wrong per the capacity table (v3-L byte mode = 53 B); RSP-03 here is correct, and PROJECT.md should be amended to raw bytes / Base32 or QR version 4.
