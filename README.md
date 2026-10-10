# FriendOrFoe

On-device drone detection + replay-proof friend-or-foe IFF on ClikaRT. D4D x EDTH Seoul 2026.

Android app that detects drones with the phone camera, on-device and offline (ClikaRT), and runs a
replay-proof, relay-resistant, radio-silent friend-or-foe check on each detected drone, drawing a green
ring (`FRIEND · id`) or a red ring (`FOE · reason`) on the exact object in the image. The challenge-response
"signal" comes from our own gadgets connected to the phone over USB: the gadget runs the radio
challenge-response and the crypto itself and reports the verdict with signal strength and response time;
the phone re-checks timing and signal strength and draws the result on the box the camera sees. A fake
signal source replays scripted answers while the hardware is not attached.

Hackathon entry for track **T1 UAS & CUAS** (guidance item **T1-04 Radio-silent IFF**) and sub-track
**SC1 CLIKA BYOD Offline AI Challenge** (app built with ClikaRT, live demo with the network blocked, code
repository, bonus for two or more device types). Everything in this repository was built during the
hackathon; see [What pre-existed vs. what we built](#what-pre-existed-vs-what-we-built).

---

## 1. Team and ownership

Three developers, three parallel streams, one integration lead. Every stream has its own folders, its own
branches and a fake implementation of the other streams' interfaces, so nobody waits for anybody.

| Stream | Owner | GitHub | Owns (folders) | First deliverable |
|---|---|---|---|---|
| **1 · Video object detection** | Chloe Yeo | @chloeyeo | `app/.../camera`, `app/.../detect`, `app/.../app` (runtime bring-up, license, models), Gradle/manifest, `eval/` | Live boxes on a person and a drone target, on the phone, airplane mode (H4) |
| **2 · Challenge-response (질의응답)** | Ihan | @angmusae2015 | `firmware/` — Arduino (C/C++): the `iff` library (codecs, HMAC-SHA256, key derivation, nonce ledger, verifier, position gate), the `reader` and `drone` sketches (radio exchange, attack modes, USB serial to the phone), the `selftest` sketch that replays the JSON vectors; `app/.../signal` (Kotlin: USB gadget link, phone-side timing/RSSI/nonce re-checks); `docs/PROTOCOL.md`, `docs/GADGET_LINK.md` | `selftest` prints PASS for every vector over USB-C; reader gadget answers a challenge over USB with verdict + RSSI + ms (H7) |
| **3 · UI frontend** | Yoo Jongho | @ujon | `app/.../ui` (camera overlay, status strip, signal/gadget panel, settings, event log), `laptop/dashboard`, screenshots and demo video | Overlay renders rings and tags from a fake scenario, no camera needed (H4) |
| **Integration lead** | Chloe | @chloeyeo | wiring in `app/.../session`, demo rehearsals, `.planning/` (GSD docs) | End-to-end green ring on the devices (H10) |
| **Design (optional)** | Dawud | @dawuddaj | `docs/design/` (visual language, mockups, overlay spec, dashboard layout, icon, pitch visuals) and the Compose theme tokens; the UI stream implements. Falls back to stream 3 if Dawud is not available | Palette, type scale and screen mockups (H4) |

If you want to swap streams, say so in the group chat before H1 and edit this table in a `docs:` PR.

**Work tracking:** every deliverable is a GitHub issue with acceptance criteria, labelled by stream
(`detect`, `iff`, `ui`, `design`, `shared`, `docs`, `integration`) and grouped into the milestones
*H4 live boxes* → *H10 MVP* → *H14 demo-ready* → *H20 submission*. Issue #36 is the tracking list: when
every issue in it is closed, the MVP is finished and the demo is rehearsed.

---

## 2. What the demo looks like (3 minutes)

1. Phone A (airplane mode, Wi-Fi off except our own LAN) points at a drone target with a teammate in
   frame. Two boxes: `drone · ?` (amber) and `person`. The laptop mirrors phone A over USB (scrcpy).
2. Tap the drone box. The phone tells the gadget over USB to challenge; the gadget sends one authenticated
   challenge (fresh 128-bit nonce) over the radio, verifies the drone's answer, and reports the verdict with
   signal strength and response time. The phone re-checks the round trip, the RSSI and that the nonce was
   never seen before. The ring turns **green: `FRIEND · RQ-07 · −58 dBm · 42 ms`**.
3. Switch the drone-side gadget to **Replay** (it re-sends an old answer): red ring, `FOE · replayed nonce`.
4. Switch it to **Relay / Late** (answer carries a far-away position or arrives late): red ring,
   `FOE · position mismatch` or `FOE · late response`.
5. Switch it to **Silent**: red ring, `UNKNOWN · no response`. Silence is never FRIEND.
6. Closing line: the drone transmitted nothing until asked, and its answer only works for the one observer
   who asked, at the place it is actually flying, within the time it should have taken.

Three layers of evidence back every verdict: **crypto** (HMAC tag with a fresh nonce) proves *who*
answered, **geometry** (position gate, the answer is attributed to the box the camera sees) proves *where*,
and **physics** (round-trip timing, radio signal strength, optical persistence) proves *when and how near*.

---

## 3. Architecture and base code modules

```
 phone A (observer)                                                     laptop
 ┌──────────────────────────────────────────────────────────────┐      ┌───────────────────────┐
 │ camera/   CameraX Preview + ImageAnalysis (RGBA + gray 10 fps)│      │ scrcpy mirror (USB)   │
 │    │                                                         │      │ laptop/dashboard      │
 │    ▼                                                         │ HTTP │   FastAPI /dashboard  │
 │ detect/   Detector ──► OnnxYoloDetector (YOLO11n, in-proc)   │─────►│   POST /api/events    │
 │             ├──► HttpDetector (laptop serve, contingency)    │      │ clikart-cli serve     │
 │             └──► FakeDetector (UI dev, scripted boxes)       │      │   --offline (eval,    │
 │    │ boxes                                                   │      │   2nd ClikaRT device) │
 │    ▼                                                         │      └───────────────────────┘
 │ track/    box ids, 10 fps motion follow, challenge lock      │
 │    │                                                         │        gadgets (USB OTG)
 │    ▼            challenge ▲   ▼ verdict + rssi + ms + nonce  │      ┌───────────────────────┐
 │ session/  IffCoordinator ──► signal/ SignalSource ───────────┼─USB─►│ reader gadget: radio  │
 │    │  verdict            ├──► UsbGadgetSignalSource          │      │ challenge-response +  │
 │    │                     └──► FakeSignalSource (demo script) │      │ crypto (Ihan)         │
 │    ▼                                                         │      └───────────┬───────────┘
 │ signal/ReChecks (Kotlin, unit-tested): nonce never seen,     │                  │ radio
 │       round trip ≤ budget, RSSI ≥ floor, attribution to the  │      ┌───────────▼───────────┐
 │       tapped box                                             │      │ drone-side gadget:    │
 │    ▼                                                         │      │ Honest / Replay /     │
 │ ui/   CameraScreen (rings, tags) · StatusStrip · GadgetPanel │      │ Relay / Late / Silent │
 │       SettingsScreen · EventLogScreen                        │      └───────────────────────┘
 └──────────────────────────────────────────────────────────────┘
                                                                       firmware/iff (Arduino C/C++ library,
                                                                       runs on both gadgets): fof1 codecs ·
                                                                       HMAC-SHA256 · keys · nonce ledger ·
                                                                       verifier · position gate; selftest
                                                                       sketch replays the JSON vectors
```

### Build units

| Unit | Kind | Purpose | Tests |
|---|---|---|---|
| `:app` (Gradle) | Android (Compose, CameraX, ClikaRT AAR, USB serial) | The phone app (observer): detection, gadget link, re-checks, UI | `:app:testDebugUnitTest` for pure logic (tracker, transforms, re-checks, state) |
| `firmware/` (Arduino IDE 2.x or `arduino-cli`) | Arduino sketches (`.ino`, C++ with C-compatible code) plus the `iff` Arduino library | `iff/` — FOF-1 once for both gadgets: codecs, HMAC-SHA256, key derivation, nonce ledger, verifier, position gate; `reader/` — the phone-side gadget (USB CDC serial to the phone, radio challenge, verify, report); `drone/` — the drone-side gadget (answer, attack modes Honest/Replay/Relay/Late/Silent/Impostor); `selftest/` — replays the JSON vectors and prints PASS/FAIL per vector | `selftest` over USB serial on the board (this is the test suite; it runs on Ihan's bench over USB-C without the phone); optional host build of `iff/` with any C++ compiler |

**About Arduino (for the rest of us):** Arduino is a board family plus a C/C++ dialect and toolchain, not a
separate language. A sketch is a `.ino` file with `setup()` and `loop()`, compiled by the board core's GCC
(avr-gcc, xtensa/riscv for ESP32) from the Arduino IDE 2.x or `arduino-cli`; libraries are plain C/C++ under
`src/` with a `library.properties`. `Serial` is the USB CDC port (native on ESP32-S3/RP2040/SAMD, through a
CP210x/CH34x bridge on classic boards) — the same port the phone opens with `usb-serial-for-android`.
Useful libraries: ArduinoJson (newline JSON lines), rweather `Crypto` (`SHA256`, `HMAC<SHA256>`) or ESP32's
built-in mbedtls for HMAC-SHA256, `esp_random()` / the board's hardware RNG for nonces, Preferences/NVS or
EEPROM for the pre-placed key. Docs: https://docs.arduino.cc and https://arduino.github.io/arduino-cli.

### Package map (`com.chloeyeo.friendorfoe`)

| Package | Stream | Contents |
|---|---|---|
| `app/` | 1 | `FofApplication` (ClikaRT bring-up on one runtime lane, license from `BuildConfig`/private storage, model directory), `Prefs` |
| `camera/` | 1 | `CameraController` (Preview + ImageAnalysis, KEEP_ONLY_LATEST, 1280×720 or 1920×1080, landscape locked), `FrameTransform` (analysis frame → preview coordinates) |
| `detect/` | 1 | `Detector` interface, `Detection`, `ObjClass` (PERSON, DRONE), `LabelMap` (RT-DETR `labelId` 0 → person, 4/14/33 → drone), `OnnxYoloDetector` (the default drone detector: a drone-trained YOLO ONNX through ClikaRT's graph runtime, section 11), `ClikaRtDetector` (RT-DETR / D-FINE snapshots through ClikaRT modelverse), `DualDetector`, `HttpDetector`, `FakeDetector`, `Detectors.create(config)` |
| `track/` | 1 | `Tracker` (IoU matching, stable ids), `BoxLock` (freeze the tapped box for the 10 s window) |
| `signal/` | 2 | `SignalSource` interface, `UsbGadgetSignalSource` (USB CDC serial, newline-delimited JSON), `ReChecks` (pure Kotlin: nonce-never-seen ledger, round-trip budget, RSSI floor; unit-tested), `FakeSignalSource` (scripted answers for every verdict) |
| `session/` | lead | `IffCoordinator` (tap → challenge → wait for answer → re-check → verdict → events; `reset()` for the Reset button), `ObserverUiState` (the only thing the UI renders), `EventLog` |
| `net/` | 1, 3 | `EventClient` + `EventJson` + `EventDiff` (fire-and-forget POSTs to the laptop dashboard; the UI mirrors the event log and the drawn tracks), `HttpDetector` transport |
| `ui/` | 3 | `FriendOrFoeApp` (app root: demo mode or live state, screens, dashboard mirror), `CameraScreen` (preview slot + `overlay/` rings, tags, countdown, hit test), `status/StatusStrip` + `OfflineMonitor`, `gadget/GadgetPanel`, `log/EventLogScreen` + counts, `settings/SettingsScreen` (over `Prefs`), `DemoBackdrop`, theme |

### The three contracts (frozen in code since the skeleton commit; change only via a `[shared]` PR)

The files on `main` are the source of truth; this is the summary. Boxes use `detect/BoxF` (a pure-Kotlin
rectangle) instead of `android.graphics.RectF`, because `RectF` is an empty stub in local unit tests.

```kotlin
// detect/Detector.kt — stream 1 provides, everyone consumes
interface Detector {
    fun detect(frame: Frame): List<Detection>   // frame pixels, score-descending, already filtered by LabelMap
    val name: String                            // "clikart:rtdetr_v2_r18vd", "http:…", "fake"
    fun close() {}
}
class Frame(val width: Int, val height: Int, val rgba: ByteArray, val rotationDegrees: Int = 0, val timestampNanos: Long = 0L)
data class Detection(val cls: ObjClass, val score: Float, val box: BoxF)
data class BoxF(val left: Float, val top: Float, val right: Float, val bottom: Float)   // + iou(), contains()
enum class ObjClass { PERSON, DRONE }

// signal/SignalSource.kt — stream 2 provides, the session consumes
interface SignalSource {
    suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent>   // Status lines, then at most one Answer
    val kind: SignalKind                                               // USB_GADGET, FAKE
    fun close() {}
}
data class ChallengeRequest(val boxId: Int, val challengerId: Int, val t: Long)
sealed interface SignalEvent {
    class Answer(                            // plain class (ByteArray field): equals/hashCode/copy by content
        val verdict: VerdictKind,            // FRIEND, FOE, UNKNOWN as decided by the gadget's crypto
        val reason: String?,                 // replayed nonce, bad tag, position mismatch, late response, no response
        val droneId: String?,
        val nonce: ByteArray,                // 16 bytes; the phone rejects any nonce seen before (nonceHex helper)
        val rssiDbm: Int?, val responseMs: Long, val source: SignalKind,
    ) : SignalEvent
    data class Status(val text: String) : SignalEvent   // "gadget connected", "challenge sent", ...
}

// session/ObserverUiState.kt — the session produces, stream 3 renders (every field has a default)
data class ObserverUiState(val tracks: List<TrackUi>, val status: StatusUi, val signal: List<SignalUi>, val events: List<EventUi>)
data class TrackUi(val id: Int, val cls: ObjClass, val box: BoxF /* preview pixels */, val iff: IffState)
data class StatusUi(val detectorName: String, val msPerFrame: Long, val fps: Float, val modelReady: Boolean,
                    val offline: Boolean, val gadgetConnected: Boolean, val rssiDbm: Int?)
data class SignalUi(val droneId: String?, val verdict: VerdictKind?, val reason: String?, val rssiDbm: Int?, val roundTripMs: Long?, val ageMs: Long)
data class EventUi(val t: Long, val text: String,                 // a status line needs only these two;
                   val trackId: Int? = null, val cls: ObjClass? = null, val score: Float? = null, val box: BoxF? = null,
                   val verdict: VerdictKind? = null, val reason: String? = null, val droneId: String? = null,
                   val rssiDbm: Int? = null, val roundTripMs: Long? = null)   // a detection / verdict row fills these
sealed interface IffState {
    data object Unchallenged : IffState
    data class Challenged(val until: Long, val windowMs: Long = 10_000L) : IffState   // countdown arc = remaining / windowMs
    data class Verdict(val kind: VerdictKind, val reason: String?, val droneId: String?, val rssiDbm: Int?, val roundTripMs: Long?) : IffState
}
enum class VerdictKind { FRIEND, FOE, UNKNOWN }
```

`DemoScenario` (in `session/`) produces a scripted `ObserverUiState` sequence (drone appears, challenge,
green, replay red, silent unknown) so the UI stream can build and screenshot every state without a camera,
a model or a gadget. `FakeSignalSource` plays the same answers through the real session path.

### Protocol FOF-1 in one paragraph (details in `docs/PROTOCOL.md`, owner: stream 2)

Keys: one pre-placed 32-byte master key → day key = HMAC(master, "FOF-day" ‖ YYYYMMDD) → per-drone key =
HMAC(dayKey, "FOF-drone" ‖ droneId); observer accepts yesterday/today/tomorrow. **Challenge** (observer →
responder, one burst): `{v=1, nonce16, t, challengerId, cmac8 = HMAC(dayKey, "C" ‖ nonce ‖ t ‖ challengerId)}`;
the responder answers only authenticated challenges, so an enemy cannot make our drone light up. **Response**
(responder → observer, directional, short range): `{v=1, droneId, nonce[0:8], pos quantized (lat/lon 1e-4°,
alt 10 m), tag16 = HMAC(droneKey, "R" ‖ nonce ‖ t ‖ challengerId ‖ droneId ‖ pos)}`; every MAC is over a fixed
binary layout, never JSON text. **Verify order on the gadget**: nonce issued, unused, under 10 s
(`replayed nonce`) → tag recomputes (`bad tag`) → position within the cone / near-field (`position mismatch`)
→ answer within the 2.5 s budget (`late response`). **Re-checks on the phone** (`signal/ReChecks`): the nonce
was never seen before (`replayed nonce`), the phone-measured round trip or the gadget's `responseMs` is within
the budget (`late response`), RSSI is above the floor, −70 dBm by default (`signal mismatch`; the near-field
consistency check is a later refinement), and the answer is attributed to the box that was tapped. The stricter
of the gadget's verdict and the re-check wins. Any failure is FOE with that reason; no answer in 10 s is
UNKNOWN; silence is never FRIEND. On the wire `droneId` is a 16-bit id; the display id (`RQ-07`) is looked up
from it. The `firmware/iff` Arduino library implements the gadget side once for both
gadgets and is checked by the `selftest` sketch against JSON vectors produced by an independent Python
generator (`eval/gen_vectors.py` also emits them as a `vectors.h` header); the phone-side re-checks are a
small unit-tested Kotlin class.

### Gadget link over USB (owner: stream 2, Ihan; file `docs/GADGET_LINK.md`)

Ihan's firmware is the source of truth (`firmware/README.md`, serial interface v2). The reader is an ESP32-C3 on
its built-in USB-Serial/JTAG (CDC ACM `303a:1001`), opened by the phone with `usb-serial-for-android` at 115200
with DTR and RTS off. Plain-text commands go in, one JSON event per line comes out; the reader generates the
nonce, runs the BLE exchange and the HMAC check itself.

```
phone → reader   QUERY k3v9x2qa-12-3                       (request id: launch prefix - seq - boxId)
                 STATUS k3v9x2qa-s1                        (handshake on connect, recovery during a query)
reader → phone   {"event":"ACK","request_id":"k3v9x2qa-12-3","accepted":true,"reason":"ACCEPTED","state":"QUERYING"}
                 {"event":"TX_CHALLENGE","request_id":"k3v9x2qa-12-3",…,"drone_id":1,"nonce":"<32 hex>",…,"result":"BROADCAST_STARTED"}
                 {"event":"RESULT","request_id":"k3v9x2qa-12-3","query":1,"response_received":true,"verdict":"FRIEND","reason":"NONE","response_ms":460,"elapsed_ms":460,"replay":false}
                 {"event":"RESULT","request_id":"k3v9x2qa-13-3","query":2,"response_received":false,"verdict":"UNKNOWN","reason":"NO_RESPONSE","response_ms":null,"elapsed_ms":3000,"replay":false}
                 {"event":"STATE","boot_id":"ae07effed99ac4d4","request_id":"k3v9x2qa-12-3","state":"IDLE","reason":"CHANGED"}
```

The phone matches every line to its challenge by `request_id` (unique across app restarts, so the reader never
re-sends an old verdict as `replay:true`), takes the nonce from `TX_CHALLENGE` for its replay ledger, and maps the
reader's FRIEND/UNKNOWN + reason to the ring: `NONE` → FRIEND, `NO_RESPONSE` → UNKNOWN · no response,
`AUTH_FAILED` → FOE · bad tag, `INVALID_MESSAGE` → UNKNOWN · invalid message, `RADIO_ERROR` → UNKNOWN · gadget
radio error, any `replay:true` → FOE · replayed nonce, a refused ACK → UNKNOWN · its reason (`gadget busy` during
the reader's 3 s cooldown). The full table, timing and recovery rules are in `docs/GADGET_LINK.md`. On the bench,
Ihan's `firmware/tools/reader_gui.py` and `serial_monitor.py` talk to the same reader from a laptop.

### How the phone, the reader gadget and the drone gadget work together

The phone is on the **asking** side, but it does not do the radio itself: the **reader gadget** plugged into
its USB-C port does. The drone side is answered by the **drone gadget**. (The earlier idea of a second phone
answering with a QR code is dropped.)

1. **Phone A detects the drone** with the camera, on-device and offline (Phase 2).
2. **You tap the drone box.** The phone locks that box for the 10 s challenge window and sends the reader
   `QUERY <request_id>` over USB.
3. **The reader gadget does the radio part.** It broadcasts one authenticated challenge with a fresh 128-bit
   nonce over BLE to the drone gadget, receives the answer and verifies the HMAC tag itself (Ihan's Arduino
   firmware, ESP32-C3 reader and NU40 nRF52840 drone).
4. **The reader gadget reports to the phone over USB:** `ACK`, the challenge's nonce (`TX_CHALLENGE`), then one
   `RESULT` with verdict, reason and response time, within 3 s. It reports no signal strength.
5. **The phone re-checks** the round-trip time and that the nonce was never seen before (the RSSI gate is
   skipped: no RSSI from this reader), then draws the green or red ring on the box that was tapped. The box lock
   keeps the answer on that box even if the detector loses the drone for a moment.

So the phone receives the IFF *result* over USB and decides what to show; the gadget handles the radio and
the crypto. This wiring is Phase 4 (gadget link, Kotlin USB signal source) and Phase 5 (tap → challenge →
verdict → ring). Without the hardware, `FakeSignalSource` replays scripted answers (tagged `FAKE`), and the unit
tests replay Ihan's captured reader lines through the real protocol code.

**How the phone is connected to the reader gadget**

- **Cable:** the gadget plugs into phone A's USB-C port (directly or through an OTG adapter). The phone is the
  USB host and powers the gadget.
- **Driver:** the C3 enumerates as CDC ACM `303a:1001`; the app opens it with `usb-serial-for-android`
  (`signal/UsbGadgetSignalSource`, with 303a:1001 also mapped to the CDC ACM driver explicitly). A USB device
  filter lets Android remember the permission ("always open").
- **Settings:** 115200 baud; DTR and RTS **off** for the C3 (DTR with an RTS change can put it into download
  mode), DTR on for any other CDC board, as Ihan's host does.
- **Protocol:** `QUERY <id>` / `STATUS <id>` lines in, JSON events out (the block above); a `STATUS` handshake on
  connect (`role reader · interface_version 2`). The phone times the USB round trip, from writing the `QUERY`
  line to reading the `RESULT` line, against a 2.5 s budget alongside the reader's own `response_ms`.

Two practical consequences:

- **The single USB-C port is taken by the gadget** during the demo, so adb and screen mirroring over USB
  cannot run at the same time: mirror over adb-over-Wi-Fi on the laptop LAN; the phone does not charge while
  it hosts the gadget.
- **The gadget can be tested without the phone:** Ihan's `firmware/tools/reader_gui.py` drives the reader from
  the laptop over the same serial interface.

Still to confirm on the hardware (`docs/GADGET_LINK.md`, last section): that Android's port open with DTR/RTS off
does not reset the C3 or put it into download mode, the permission flow, and real round trips over OTG.

---

## 4. Plan

The detailed plan lives in `.planning/` (GSD): `ROADMAP.md` (phases, success criteria, research flags),
`REQUIREMENTS.md` (51 requirement IDs), `research/` (stack, features, architecture, pitfalls with sources).
The roadmap was written for one developer in series; with three of us, Phases 2, 3 and 4 run **in parallel**
and meet at the integration checkpoints below.

| When | Stream 1 · Detection (Chloe) | Stream 2 · Challenge-response (Ihan) | Stream 3 · UI (Jongho) |
|---|---|---|---|
| **H0–1 Foundation** (lead) | Skeleton PR: package stubs, the three contracts, fakes, Gradle wiring (`clikaRtMavenRepo` property, arm64 only, BuildConfig license), `firmware/` skeleton (`iff` library stub, `selftest` sketch, `eval/gen_vectors.py`), `laptop/gadget_cli.py`, `local.properties.example` | Read `docs/PROTOCOL.md` draft and `docs/GADGET_LINK.md`; fix the board, radio and USB details | Read the contracts; sketch the four screens |
| **H1–4** | CameraX preview → `ClikaRtDetector` → boxes (`labelId` map, thresholds), `FrameTransform`, `Tracker`, `BoxLock`; **H4 hard checkpoint**: if the AAR does not run, flip `Detectors.create` to `HttpDetector` and keep going | `firmware/iff` in Arduino C/C++: codecs, HMAC-SHA256, key derivation, nonce ledger, verifier, position gate; JSON vectors from the Python generator; `selftest` green on the board over USB-C | `CameraScreen` + `OverlayPainter` from `DemoScenario`; `StatusStrip`; theme; big fonts for judges |
| **H4–7** | Demo target decided (what RT-DETR boxes reliably); status strip numbers; on-device latency tuning; model push script | Firmware challenge-response over the radio checked against the vectors; `UsbGadgetSignalSource` (USB CDC, newline JSON) + `ReChecks` in Kotlin; `FakeSignalSource` | `GadgetPanel`, `SettingsScreen`, `EventLogScreen` |
| **H7–10** | Integration: `IffCoordinator` wiring tap → challenge → answer → re-check → verdict → ring; `EventClient` | Gadget end-to-end over USB with verdict + RSSI + ms; drone-side attack modes (Replay, Relay/Late, Silent) in firmware | `laptop/dashboard` (FastAPI page, no CDN), scrcpy mirror, screenshots |
| **H10 checkpoint** | **End-to-end green ring on the devices** with the gadget attached, replay red, silent unknown | | |
| **H10–14** | Eval set + `eval/run_eval.py`, detector/threshold choice, OWLv2 option (bench first) | Impostor mode, physical gates tuned (timing budget, RSSI floor), `docs/PROTOCOL.md` threat table | Polish: countdown on the box, reasons legible at 2 m, reset button, demo mode switch |
| **H14 checkpoint** | **Three timed run-throughs of the 3-minute script with the network blocked**, backup video recorded | | |
| **H14–20** | README numbers (load time, ms/frame, APK size), final integration, buffer | `docs/GADGET_LINK.md` final, Q&A card answers on crypto | Pitch visuals, demo video cut, README screenshots |

**Cut order if behind:** audio alert → OWLv2 option → Relay mode + position gate → eval table → RSSI gate.
Never cut: live boxes, the green/red ring with a reason, the offline run, the `selftest` vectors, the fake
signal source (it is the on-stage fallback if the gadget misbehaves).

---

## 5. Workflow (branches, PRs, syncing)

- **`main` is always demo-able.** It must build (`./gradlew :app:testDebugUnitTest :app:assembleDebug`, and
  the `firmware/selftest` sketch compiles and passes on the board) at every commit.
  Nobody pushes directly to `main` once the skeleton PR has landed; the only exception is a one-line hotfix in
  the final two hours, announced in the chat first.
- **One personal branch per developer, already created on GitHub:** `chloe`, `ihan`, `jongho`, `dawud`.
  Start with `git fetch origin && git switch <your-name>`, work there, and keep it current with
  `git fetch origin && git rebase origin/main` before every push (`git push --force-with-lease origin <your-name>`).
  Open a PR from your branch to `main` whenever a slice works; after the merge, rebase your branch again.
  Optional topic branches off your own branch (`chloe/camerax`, `ihan/selftest`) are fine for experiments.
- **Codex users (Ihan, Jongho):** Codex reads `AGENTS.md` at the repo root (the project guide; `CLAUDE.md` just
  includes it). Point Codex at your own branch, let it commit there, and ask it to run the build command
  for your stream before each push (`./gradlew :app:testDebugUnitTest :app:assembleDebug` for app code,
  the `selftest` sketch for firmware). Never let an agent push to `main` directly.
- **Small PRs, merged fast.** Aim for under 400 changed lines and under three hours of life. Title
  `area: what it does` (`detect: map labelId to person/drone`). Description: what, how you tested (unit test,
  device, screenshot for UI). One review from another member; if nobody is free within 15 minutes, say so in
  the chat and self-merge. Squash merge, delete the branch.
- **Rebase before you open or update a PR:** `git fetch origin && git rebase origin/main`, resolve locally,
  `git push --force-with-lease`.
- **Shared files are special.** `settings.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`,
  `AndroidManifest.xml`, `MainActivity.kt`, `AppNav.kt` and the three contract files change only through a
  PR titled `[shared] …`, kept tiny and merged first. Everything else lives in your own folders, so conflicts
  are rare.
- **Commits:** imperative, prefixed by area (`iff: add nonce ledger`, `ui: draw countdown ring`). Never commit
  the ClikaRT license, keys, `local.properties`, model weights or APKs (`.gitignore` covers them, and a grep
  for `CLIKA1-` before pushing is cheap).
- **Sync points:** merge your branch at least every three hours; everyone is on the latest `main` at the H4,
  H10 and H14 checkpoints; the demo build is the `main` commit tagged `demo-N`.
- **Where things are discussed:** the group chat for anything blocking; GitHub issues only for things that
  survive the day. Planning docs in `.planning/` are maintained by Chloe through GSD; propose changes in the
  chat rather than editing them in a feature PR.

---

## 6. Setup for every developer

1. Clone and open in Android Studio (the project is Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, compileSdk 36,
   minSdk 28, JDK 21 toolchain is downloaded automatically).
2. Copy `local.properties.example` to `local.properties` and fill in:
   - `sdk.dir` — your Android SDK (Android Studio writes this one).
   - `clikaRtMavenRepo` — the folder where you extracted `clika-runtime-maven-0.6.4.zip` (it contains
     `io/clika/clika-runtime-android/0.6.4/*.aar`). Download it from the team project on platform.clika.io.
     Without it the app module does not resolve; the firmware and the Python tools need nothing from it.
   - `clika.runtimeLicenseFile` — optional, path to a file holding the `CLIKA1-…` credential. Only the phone
     that runs on-device detection needs it (the license must be an **Offline** bundle with hardware seats;
     an Online key stops working the moment the network is blocked).
3. Build the app: `./gradlew :app:testDebugUnitTest :app:assembleDebug`. The debug APK is about 300 MB
   because the ClikaRT native libraries are inside it; install over a USB 3 cable (`./gradlew :app:installDebug`),
   never over the air, and never `adb uninstall` (it deletes the pushed models).
   Gadget stream: Arduino IDE 2.x or `arduino-cli`, the board package for the gadget's MCU, the libraries
   ArduinoJson and rweather Crypto (or mbedtls on ESP32); open `firmware/selftest`, flash it over USB-C and
   watch the serial monitor print one PASS per vector.
4. Models on the phone (stream 1 only): the drone detector is `files/models/drone_yolo11n_480.onnx` (marie
   YOLO11n at 480; export and push in [section 11](#11-drone-detector-v2-marie-yolo11n-at-480-the-default-now)).
   The older RT-DETR snapshots (fallback and person model): fetch on the laptop with
   `clikart-cli --cache-dir <cache> fetch PekingU/rtdetr_v2_r18vd`, then push the snapshot directory contents
   to `/sdcard/Android/data/com.chloeyeo.friendorfoe/files/models/rtdetr_v2_r18vd/` with `adb push <snapshot>/. <dest>`.
5. Laptop extras: Python 3.13 with `fastapi`, `uvicorn`, `requests`, `pillow` for the dashboard and eval;
   `clikart-cli` from the ClikaRT Windows archive (`bin/` and `lib/` on PATH) for `serve --offline` and
   the eval; scrcpy for the mirror and the backup video.
6. UI stream without a phone: run the app on any arm64 phone with `FakeDetector` + `FakeSignalSource`
   (settings → demo mode), or run the Compose previews from `DemoScenario`.
7. Gadget stream: a USB OTG adapter for the observer phone; the gadget enumerates as a USB CDC serial device
   (`usb-serial-for-android` also drives FTDI, CP210x and CH34x bridges).

---

## 7. Decided and open

Decided (2026-10-10): the gadget runs the challenge-response and the crypto itself and reports verdict, RSSI
and response time; the phone re-checks timing, RSSI and nonce freshness. No second-phone QR responder; the
fake signal source is the simulator and the on-stage fallback. GitHub issues per stream track the work.

Open (answer in the chat before H1):

1. **The gadget details.** Exact Arduino board (native USB CDC or a serial bridge chip), radio module, USB
   VID/PID and baud, who flashes it, how the drone-side attack modes (Replay, Relay/Late, Silent, Impostor)
   are switched during the demo. Goes into `docs/GADGET_LINK.md`.
2. **Position gate indoors.** No GPS fix inside the venue: both sides use configured positions. Decide the
   Honest/Relay coordinates and whether the near-field rule (< 30 m passes without a bearing) is on.
3. **Licenses and phones.** Which phone carries the ClikaRT seat for detection, and who brings the USB OTG
   adapter.

---

## 8. What pre-existed vs. what we built

Pre-existing: ClikaRT 0.6.4 runtime and its Kotlin binding, the public RT-DETR v2 and OWLv2 weights, the
community drone-trained YOLO weights on Hugging Face (the default drone detector `marie-kjelberg/drone-detector`
YOLO11n, AGPL-3.0, and the benchmarked alternatives in section 11), Ultralytics (ONNX export only), the
Android Studio Compose template, CameraX, the usb-serial-for-android library, the Arduino core and the
ArduinoJson / Crypto libraries, FastAPI, scrcpy.
Built during the hackathon: everything in this repository — the detector integration (incl. running YOLO ONNX
graphs through ClikaRT's `OnnxModel` and the drone-model benchmark on our own drone), the tracker and box
lock, the FOF-1 protocol library with its vectors and selftest, the reader and drone firmware, the USB
gadget link, the observer UI, the dashboard, the eval, the docs.

---

## 9. Repository layout

```
app/        Android app (Compose, CameraX, ClikaRT AAR, USB serial) — packages listed above
firmware/   Arduino: iff/ (library: src/fof1.h, src/fof1.cpp, library.properties), reader/, drone/, selftest/
laptop/     dashboard/app.py (FastAPI), gadget_cli.py (talk to the gadget from the laptop), serve + model push scripts
eval/       10+ test images, ground truth JSON, run_eval.py, gen_vectors.py (JSON vectors + vectors.h)
docs/       PROTOCOL.md, GADGET_LINK.md, PITCH.md (script + Q&A card), DESIGN.md, design/ (mockups, specs)
.planning/  GSD planning docs (ROADMAP, REQUIREMENTS, research)
```

---

## 10. On-device detection numbers and the demo target (Phase 2)

### Checkpoint 1 — bring-up on phone A in airplane mode (H2)

Phone A = Samsung Galaxy A06 (SM-A065F, Android 16, arm64), USB-attached, airplane mode on
(`settings get global airplane_mode_on` = `1`), launched with
`am start -S -n com.chloeyeo.friendorfoe/.MainActivity --ez demo false --ez dump_frame true --ez log_raw true`.
Captured with `adb logcat -s ClikaRT FoF` (75 s).

| Fact | Value |
|------|-------|
| Date / time | 2026-10-10 17:33 phone time (08:33 UTC) |
| Build commit | `eff4bd5` (debug APK, `:app:installDebug --offline`) |
| License outcome | accepted (offline license; `runtime up`, no code name) |
| Model dir check | `files/models/rtdetr_v2_r18vd`: `config.json` 5437, `model.safetensors` 80904640, `preprocessor_config.json` 444; md5 `7037c890ae678aaba18f3555b3acf36d` |
| First-frame line (verbatim) | `first frame 1280x720 rotation=0 cropRect=Rect(0, 72 - 1280, 648) rowStride=5120 pixelStride=4 format=1` |
| Runtime line (verbatim) | `runtime up: load 844 ms warm 3853 ms threads 2` (two later cold starts: load 489 / 519 ms, warm 3748 / 3890 ms) |
| Load | 0.84 s (first start after install), 0.49-0.52 s on later starts |
| Warm-up | 3853 ms |
| Threads | 2 (`cpu thread pool: 2 participant(s)`) |
| Median ms/frame | 3497 ms (18 ticks, min 3335, max 3530) |
| fps | 0.28-0.29 (CPU, 2 threads, 1280x720; tuning in plan 02-06) |
| Analysis target | 1280x720 delivered as 1280x720, 16:9, rotation 0, tight rows (rowStride 5120 = 1280*4), no aspect/rotation warning |
| cropRect | ViewPort crop to the 20:9 screen: rows 72..648 of the frame are visible (= FrameTransform's 1.25 scale, -90 offset for 1600x720) |
| Parity, asset `parity/scene.png` | phone 3 rows / CLI 3 rows; same label ids in the same order (11 stop sign, 11 stop sign, 29 frisbee); max score delta < 0.001; max box delta < 0.001 px |
| Parity, live frame | dumped frame was an out-of-focus grey scene: phone 0 rows, CLI 0 rows (trivially equal; re-run on a person scene at checkpoint 2) |
| Dump colour check | no shearing, no green lines, no stride artefacts; scene is neutral grey, so the channel-order check is inconclusive on this frame |
| In-process rows on a live scene | yes: tick 3 of the capture logged 20 raw rows (person 0.913 / 0.909 / 0.871, bottle, chair, laptop, cell phone, …) |

Visual checks, 17:37-17:52 phone time. Chloe's report: "so it catches people alright but not the drone". The
executor ran the rest over adb, using four screenshots Chloe took on phone A (17:41:49, 17:43:07, 17:46:21-37) and
`screencap` captures. The phone locked at about 17:52 and no further captures were possible.

| Check | Observed |
|-------|----------|
| Boxes on objects: person | ok. `person` boxes sit on the seated and standing people in the hall, with ✈ OFFLINE on the strip (17:41:49 screenshot) and later online too |
| Boxes on objects: drone | **no box.** 17:43:07 screenshot: a real DJI-style folding quadcopter, arms out, held in hand at about 0.3-0.5 m in front of the laptop, airplane mode on: no box of any class (ticks report `tracks=0`/`1`). Chloe's report also covers a full-screen quadcopter photo on the laptop screen at about 0.5-0.7 m: no aeroplane (4) / bird (14) / kite (33) box. Raw rows for ids 4/14/33/67 were not captured: that session ran without `log_raw`, and the phone was locked before a re-run. Open for checkpoint 2 (PIT-04 / DET-09, plan 02-06) |
| Edge mapping | there was no drone box, so this was checked with person boxes. In the 17:46:37 screenshot the static seated people at the far left (x ≈ 0-65 px) and the far right (x ≈ 1535-1600 px) have their head and body boxes on them; no shift in one direction across the frame. People who are moving trail by one tick (4-7 s at 0.14-0.22 fps), as expected. Top edge: nothing near the top of the frame, so it was not checked. No `FrameTransform` fix applied |
| OFFLINE badge | airplane on + Wi-Fi off: `✈ OFFLINE` (17:41, 17:43). Airplane off with Wi-Fi + LTE validated: `ONLINE` (17:46, 17:48). Airplane on **with Wi-Fi left on**: `ONLINE` (17:49). Samsung keeps Wi-Fi on in airplane mode once you have turned it on there, so this is correct under D-12 (validated network) and a trap in the runbook |
| Tap lock | deferred: no drone box on device yet; the logic is covered by the `IffCoordinatorTest` lock cases |
| Preview over rings | ok: in every screenshot the rings and tags are drawn on top of the camera image (no `COMPATIBLE` mode fix needed). The tag shows the label only, with no score |
| Strip ms/frame · fps (observed) | at the desk, airplane on, 0-1 tracks: 4007-4984 ms, median 4489 ms, 0.22 fps (12 ticks; the strip read 4190 / 4433 ms, 0.2 fps). Crowded hall view, 8-13 tracks (online): 5309-8038 ms, median 7096 ms, 0.14 fps. Slower than the 3497 ms median of the bring-up capture; tuning is in plan 02-06 |
| License while online | **blocked.** At 17:47:33, about 3 min after the network came back, ClikaRT logged `clikart license invalid — LICENSE_STATE_BLOCKED: this credential's hardware seats are all held by other units; release a seat or raise its max_hardware to admit this one`. Every tick after that failed with `unknown failure inside the model library`. Cold starts while online: `runtime refused: LICENSE_FAILED` (17:49:19) and `runtime refused: unknown failure inside the model library` (17:49:58, airplane on with Wi-Fi still on) |
| License offline again | airplane on **and** Wi-Fi off (`svc wifi disable`), cold start 17:51:44: `runtime up: load 1631 ms warm 7555 ms threads 2`. The offline credential is accepted again, so the block is not cached on the phone |
| Live-frame parity | still open: the phone was locked before the `dump_frame` re-run. To do at checkpoint 2 on a scene with people and colour |
| Rotation | the 17:52:24 cold start logged `first frame 1280x720 rotation=90 …` while the portrait keyguard was up (expected; the landscape activity delivers 0) |

Criterion 1 (in-process boxes in airplane mode): met. In-process RT-DETR on phone A boxes people with ✈ OFFLINE on the
strip at 0.22 fps. The drone class proxy did not fire on the quadcopter (PIT-04 / DET-09, plan 02-06), and the
license is blocked whenever phone A is online (open item below). H4 flip: not applied.

**Open item (Chloe, platform.clika.io → Licenses):** all of the bundle's hardware seats are held by other units.
Release a seat (a teammate's device or an old install) or raise `max_hardware` so that phone A is admitted. Until then,
phone A must stay **strictly offline**: airplane mode on **and** Wi-Fi off, and any LAN it joins must have no upstream
internet. If the license check reaches the server, the runtime stops mid-demo.

### PIT-04 — demo target decision (H3)

_pending checkpoint 2 (plan 02-06)_

### Speed-up: 320x320 model input and 10 fps box tracking

Request (Chloe): halve the model resolution so the drone is recognised much faster, and run the video input at
about 10 frames per second, because the box followed a moving drone far too late. Two changes, measured on phone A
(Galaxy A06, airplane mode on, Wi-Fi off, 2 runtime threads, same session, same grey scene):

**1. Detector input 640x640 -> 320x320 (the default now).** Both models of the dual detector run on snapshot copies
whose `preprocessor_config.json` says `"size": {"height": 320, "width": 320}`; the weights are the same files
(`anchor_image_size` is null in both configs, so a smaller input is structurally fine). The binding reads the size
from the snapshot: the warm-up and every tick got about 3x faster.

| per frame on phone A | 640x640 (`--es detector dual640`) | 320x320 (default, `--es detector dual`) |
|---|---|---|
| drone model (RahulKate RT-DETR v2 R50) | 9.0-10.0 s (earlier run 7.5-8.5 s) | **2.6-3.4 s** |
| person model (D-FINE S) | 2.2-2.6 s (earlier run 1.9-2.2 s) | **0.86-1.1 s** |
| one detection, both models | 11.5-12.5 s, 0.08-0.09 fps | **3.6-4.3 s, 0.23-0.28 fps** |
| warm-up at load | drone 11.1 s, person 2.8 s | drone 2.8-4.2 s, person 0.8-1.2 s |

On the laptop (`clikart-cli`, 2 threads) the drone model still found 6 of 6 test drone photos at 320 (scores
0.32-0.87). A smaller input does lose small, far drones first; flip back with `--es detector dual640` to compare.

Phone setup for the 320 dirs (once; the safetensors are byte-identical to the 640 dirs, md5 checked):

```bash
# from the 640 snapshot dirs plus the 320 preprocessor_config.json
adb shell mkdir -p /data/local/tmp/models/rtdetr_v2_drone_rahul_320 /data/local/tmp/models/dfine_small_coco_320
adb push <m_drone_320>/config.json <m_drone_320>/preprocessor_config.json /data/local/tmp/models/rtdetr_v2_drone_rahul_320/
adb push <m_person_320>/config.json <m_person_320>/preprocessor_config.json /data/local/tmp/models/dfine_small_coco_320/
adb shell cp /data/local/tmp/models/rtdetr_v2_drone_rahul/model.safetensors /data/local/tmp/models/rtdetr_v2_drone_rahul_320/
adb shell cp /data/local/tmp/models/dfine_small_coco/model.safetensors /data/local/tmp/models/dfine_small_coco_320/
adb shell run-as com.chloeyeo.friendorfoe sh -c 'mkdir -p files/models/rtdetr_v2_drone_rahul_320 files/models/dfine_small_coco_320 && cp /data/local/tmp/models/rtdetr_v2_drone_rahul_320/* files/models/rtdetr_v2_drone_rahul_320/ && cp /data/local/tmp/models/dfine_small_coco_320/* files/models/dfine_small_coco_320/'
```

**2. Boxes follow the object at 10 fps between detections.** Even at 320 a detection takes about 4 s, so the rings
are now moved by a cheap motion tracker at 10 frames per second and corrected whenever a detection arrives:

- `camera/CameraController` takes one analysis frame per 100 ms (a deadline schedule, so a 24 or 30 fps camera both
  give 10 fps) and samples it into a 320x180 grayscale frame straight from the RGBA plane (`camera/GraySampler`,
  rowStride/pixelStride honoured, no second full-size copy), handed to its own `motion` thread. The preview itself
  stays at the camera's full rate. The full RGBA copy still goes to the runtime lane only when it is idle, with the
  capture timestamp of that image.
- `track/MotionTracker` (pure Kotlin) keeps the last 5 s of gray frames. Per track it holds a template patch and
  searches it in each new frame (sum of absolute differences, coarse on a half-resolution level, then every pixel
  within 2 px; radius max(12 px, half the box) around the last position plus the last velocity). A poor match does
  not move the box (it keeps its last position and resumes when the object is found again).
- Latency compensation: a detection made on the frame captured at t0 arrives about 4 s later. Its boxes are matched
  on the buffered gray frame nearest t0 and fast-forwarded through every frame since, so the corrected ring lands
  where the drone is now, not where it was 4 s ago. This runs outside the coordinator's lock.
- `session/IffCoordinator.motionTick` moves existing tracks only (same ids, same IFF state, and a tapped box keeps
  its challenge lock while it follows the object); creating and dropping tracks is still the detection tick's job.
- The strip shows both rates, for example `track 9.9 fps · detect 3.6 s (2.7+0.9)` (drone + person seconds).

| motion path on phone A | measured |
|---|---|
| motion rate | 9.7-10.3 fps, steady while the detector runs (`motion … fps` log line, once per second) |
| gray sample (camera thread) | 14-17 ms per frame (320x180 from 1280x720) |
| motion tick, nothing tracked | 0.13-0.23 ms |
| one tracked box, per frame (`--ez motion_bench true`, warm, beside the detector) | 0.46 ms (48 px box) · 1.1 ms (160 px) · 2.9 ms (400 px) |
| fast-forward of one detection over 3 s (30 frames) | 16 ms (48 px) · 34 ms (160 px) · 89 ms (400 px) |

Logcat: `adb logcat -s FoF` shows `motion F fps median M ms/tick sample S ms gray 320x180 tracks=K` once per second
and `tick N ms (D drone + P person) … motion F fps` per detection. Limits: the tracker only translates boxes (no
scale change between detections), and a drone against flat sky or a flat wall has little texture to match, so its
ring then waits for the next detection.

### Speed sweep: drone model at 224x224 input (superseded by section 11)

Request (Chloe): make drone and person detection much faster; lower image quality is fine. Measured on phone A
(airplane mode on, Wi-Fi off, 2 runtime threads, phone lying on a grey surface), median per tick over 45-60 s after
warm-up, with the debug launch extras `clika_device`, `clika_dtype`, `drone_input`, `person_input`, `analysis`
(see `app/LaunchOptions.kt`). The phone warms up over a session, so the same config varies by about 15 %.

| config (drone / person input, device, dtype) | drone ms | person ms | tick ms | load + warm (drone; person) | notes |
|---|---|---|---|---|---|
| 320 / 320, cpu, float32 (old default) | 2311-2710 | 784-887 | 3109-3589 | 2.4 s + 3.1 s; 0.6 s + 0.8 s | |
| 320 / 320, cpu, float16 | 2847 | 941 | 3787 | 1.9 s + 3.0 s; 0.6 s + 1.0 s | slower than float32 on this CPU |
| 320 / 320, `vulkan:0` | - | - | - | - | `BACKEND_NOT_LOADED` ("not shipped in this install"), falls back to cpu |
| 320 / 320, `vulkan:0`, native libs extracted | 15000 | 3520 | 18500 | 10.2 s + 17.5 s; 2.3 s + 3.6 s | loads on the Mali-G52, about 6x slower than the CPU |
| 320 / 320, `vulkan:0` + float16, libs extracted | 13826 | 3033 | 16860 | 10.8 s + 17.1 s; 2.7 s + 3.2 s | still about 5x slower |
| 256 / 320, cpu | 1639 | 783 | 2428 | 2.4 s + 1.8 s; 0.7 s + 0.8 s | |
| **224 / 320, cpu (new default)** | **1512** | **835** | **2333** | 1.9 s + 1.6 s; 0.5 s + 0.9 s | |
| 224 / 256, cpu | 1596 | 720 | 2313 | | |
| 224 / 224, cpu | 1378-1572 | 592-647 | 2183-2218 | 0.8 s + 1.8 s; 0.6 s + 0.7 s | person model loses most people (below) |
| 256 / 224, cpu | 1843-1890 | 627-651 | 2483-2537 | | |
| 224 / 224, cpu, analysis 640x360 | 1559 | 635 | 2198 | | the frame size does not matter (the model resizes) |
| 224 / 224, `num_queries` 300 -> 100 | 1312 | 480 | 1792 | | faster, but drone scores drop (0.80 -> 0.61) and 1 more photo missed |

Accuracy on the laptop (`clikart-cli`, same snapshots): the drone model finds 6/6 test drone photos at 320, 5/6 at
256 and 4/6 at 224 at the app's 0.30 drone floor (5/6 at 224 with 0.25). The person model on five room screenshots
(people with score >= 0.5): 320 finds 3, 1, 7, 4, 3; 224 finds 0, 1, 1, 1, 2. So the default pairs the drone model at
224 with the person model at 320. Vulkan and float16 stay off on this phone.

Phone setup for the extra sizes (same weights, only `preprocessor_config.json` `size` differs), for N = 224 (and
256/160 for the sweep):

```bash
adb push <sw_drone_N>/. /data/local/tmp/models/rtdetr_v2_drone_rahul_N/
adb shell run-as com.chloeyeo.friendorfoe sh -c 'mkdir -p files/models/rtdetr_v2_drone_rahul_N && cp /data/local/tmp/models/rtdetr_v2_drone_rahul_N/* files/models/rtdetr_v2_drone_rahul_N/'
```

A phone that already stored the 320 pair keeps it until it is launched once with `--es detector dual` (which stores
the new default), or with `--ei drone_input 320` for one run.

---

## 11. Drone detector v2: marie YOLO11n at 480 (the default now)

Request (Chloe, 2026-10-10 evening): recognise our actual drone (a grey DJI Mini/Air-class quadcopter) fast and
well, also while it moves. The RT-DETR v2 R50 drone model at 224 needed **1.3-4.4 s per frame** on phone A and
missed the hand-held drone in a screenshot.

**Decision: `marie-kjelberg/drone-detector` (YOLO11n, one class `drone`) at 480x480.** We benchmarked every
drone-trained YOLO we could find, then tried the best ones live on phone A against the real drone in airplane
mode. marie 480 was the most accurate in those live tests and is still quick: **275-345 ms per frame** on phone A,
about 3.7-16x faster than the RT-DETR it replaces, with the 10 fps motion tracker following the box between detections.
It also has the highest benchmark AP50 on our drone of every model/size that runs in about 40 ms on the laptop
(0.540, below).

### How it runs (still ClikaRT, still offline)

ClikaRT modelverse only loads its own model families (RT-DETR, D-FINE, OWLv2…), so the YOLO runs through ClikaRT's
low-level graph runtime from the same AAR (`io.clika.runtime`), in-process on the CPU:

1. `OnnxModel.open(file)` → `compile(CompileOptions(sampleInputs = images[1,3,H,W]))` →
   `graph.optimize(OptimizeOptions(DefaultsOptions(specialize = true)))` (2.3-4x faster on the CPU, same output) →
   `finalizeGraph()`, then `graph.run` per frame.
2. Pre-processing: letterbox the RGBA frame to H x W (grey 114), RGB, /255, NCHW float32.
3. Output `[1, 4 + nc, N]`: cx, cy, w, h and sigmoided class scores. `YoloDecode` keeps rows >= 0.15, runs greedy
   NMS (IoU 0.45), maps boxes back through the letterbox and **drops boxes over 60 % of the frame** (whole-frame
   false alarms on grey clutter in the benchmark; a drone at arm's length fills about 27 %). The app keeps drones
   >= **0.40** for marie (`ClassMap.DRONE_YOLO`), >= 0.20 for TomSmail files.
4. Every round is a full-frame scan (a crop round costs as much as a full scan with this model), and a drone box is
   dropped about 2.5 rounds after its last detection (1-5 s; about 1 s with marie 480) instead of a fixed 5 s.

Code: `detect/OnnxYoloDetector.kt` (detector + `YoloDecode`, unit-tested in `YoloDecodeTest`), `ClassMap.yolo`,
`DualDetector.yolo`, `Prefs.defaultDetectorConfig`, `RoundScheduler.droneStickyMs`. ClikaRT on the laptop
(`clika-runtime-jvm`) gives the same boxes and scores as onnxruntime for these fp32 files. float16 and int8 are
slower on the CPU (int8 3-4x), as with RT-DETR.

### Measured on phone A (Galaxy A06, airplane mode, 2 runtime threads)

| detector | ms per frame | detections/s | live test against our drone |
|---|---|---|---|
| RT-DETR v2 R50 drone, 224 (old default) | 1270-4400 | 0.2-0.8 | missed the hand-held drone |
| marie YOLO11n 320 | 130-140 | 5-6 | scores only 0.13 on our hand-held drone |
| **marie YOLO11n 480 (default)** | **275-345** | **about 3** | **best: most accurate in the live tests, still quick** |
| marie YOLO11n 640 | 480-550 | about 1.8 | |
| TomSmail YOLOv8n 256x416 | 152-172 | about 5 | weaker than marie 480 in the live test |
| TomSmail YOLOv8n 640 | 505-540 | about 1.8 | phantom boxes at its default 0.20 floor; worked well at `--ef yolo_thr 0.3` |

The phone heats up over a session and Samsung's security app scans every newly installed APK, so the same model
can run 2-3x slower for a minute after an install; the numbers above are steady-state.

### Benchmark: 11 drone YOLOs x 6 input sizes on our own drone

Candidates: every drone-trained detector on Hugging Face we could export (no drone model exists in
`onnx-community`; the COCO YOLOs only see drones as "kite"/"bird" at 0.24-0.43). Rejected outright: IRIS YOLOv8s
(non-commercial, AP50 ≤ 0.11 here), Tuzelkhan YOLOv8 (military classes), jenx-5, danivelikova YOLO26n (event
camera), alexandre196 (Shahed only), engdarwish YOLOv5s (0/4 real photos), all VisDrone/UAVDT models (they detect
objects seen *from* a drone).

- **Test set** (`evalset_user`, built from our drone): 2 real phone screenshots of our DJI drone (close-up on the
  desk, held in the hand) + their mirror images, **240 synthetic images** (our drone cut out of those photos and
  pasted at 480/240/120/60/30 px width with motion blur 0/9/21 px onto desk, hall, people and sky backgrounds),
  **40 drone-free crops of our own backgrounds** (desk, laptop, hands without the drone) and 86 public negatives
  (people, birds, kites, aeroplanes). Plus the earlier public set (110 drone boxes from `pathikg/drone-detection-dataset`).
- **Speed**: ClikaRT 0.6.4 JVM on the laptop, 2 threads, `optimize()` on, p50 of 30 runs on a quiet machine. Measured,
  the phone is about 7-10x slower than the laptop (marie 480: 35.8 ms laptop, 275-345 ms phone).
- **Accuracy**: onnxruntime (same outputs as ClikaRT), 60 % big-box drop applied, threshold = lowest ≥ 0.15 that
  keeps false alarms on our backgrounds ≤ 5 %. A hit is IoU ≥ 0.3 (or ≥ 0.2 with the box centre on the drone).

| model | input | laptop ms | AP50 our drone | real photos hit | recall at 480/240/120/60/30 px | blur 21 px | our backgrounds FP | public negatives FP |
|---|---|---|---|---|---|---|---|---|
| marie YOLO11n | 320 | 16.2 | 0.379 | 1/4 | .65/.40/.35/.25/.17 | .29 | 0 % | 15 % |
| marie YOLO11n | 416 | 27.7 | 0.467 | 0/4 | .83/.54/.48/.38/.23 | .34 | 5 % | 30 % |
| **marie YOLO11n** | **480** | **35.8** | **0.540** | 1/4 | .81/.56/.46/.40/.31 | .34 | 5 % | 26 % |
| marie YOLO11n | 640 | 60.8 | 0.614 | 3/4 | .88/.65/.52/.46/.35 | .38 | 2.5 % | 43 % |
| TomSmail YOLOv8n | 256x416 | 19.4-20.1 | 0.461 | 3/4 | -/.60/.50/-/- | .47 | 2.5 % | 33 % |
| TomSmail YOLOv8n | 320 | 19.1 | 0.440 | 3/4 | .88/.52/.44/.31/.23 | .47 | 5 % | 44 % |
| TomSmail YOLOv8n | 416 | 31.6 | 0.457 | 3/4 | .88/.65/.50/.31/.29 | .50 | 2.5 % | 56 % |
| TomSmail YOLOv8n | 480 | 41.4 | 0.498 | 2/4 | .81/.69/.56/.40/.33 | .49 | 5 % | 52 % |
| TomSmail YOLOv8n | 640 | 71.3 | 0.522 | 4/4 | .73/.65/.58/.35/.40 | .49 | 2.5 % | 49 % |
| SkyGuardAI YOLO11n | 480 | 36.5 | 0.484 | 2/4 | .81/.56/.48/.40/.23 | .41 | 5 % | 31 % |
| SkyGuardAI YOLO11n | 640 | 61.2 | 0.562 | 0/4 | .77/.60/.46/.44/.31 | .40 | 5 % | 29 % |
| sapoepsilon YOLO11s | 480 | 96.8 | 0.500 | 2/4 | .92/.56/.50/.35/.38 | .40 | 0 % | 34 % |
| sapoepsilon YOLO11s | 640 | 170.2 | 0.538 | 3/4 | .92/.62/.54/.48/.40 | .42 | 2.5 % | 41 % |
| RT-DETR v2 R50 (old) | 224 | 167.6 | 0.394 | 2/4 | .92/.54/.40/.29/.15 | .40 | 5 % | 73 % |
| RT-DETR v2 R50 (old) | 320 | - | 0.472 | 2/4 | .92/.56/.54/.44/.38 | .49 | 2.5 % | 71 % |

How to read it:
- **marie 480 is the accuracy/speed sweet spot.** Its AP50 on our drone (0.540) beats every model/size at a similar
  laptop cost (SkyGuard 480: 0.484, TomSmail 480: 0.498, TomSmail 416: 0.457), and it keeps false alarms on public
  negatives low (26 % vs 33-56 % for TomSmail and 73 % for the old RT-DETR). Only slower configs score higher
  (marie 640: 0.614, RT-DETR 640: 0.576, SkyGuard 640: 0.562).
- **"Real photos hit" rests on 2 screenshots** (with the app's own orange boxes drawn on them), so one photo flips
  1/4 to 3/4. The laptop benchmark ranked TomSmail first on that column; the live test on the phone, with the real
  drone moving in front of the camera, picked marie 480. That live test decided.
- **Small, far drones** (under about 60 px wide, roughly beyond 4-5 m with the 1280x720 frame) are unreliable for
  every model. Hands and people are the main false-alarm risk for every model.
- The full results (every model x size, threshold sweeps, drawn boxes) were produced in the session scratchpad
  (`bench/DECISION.md`, `decide/table.json`, `viz/`); the scripts are not in the repo.

### Run it and switch models

Export (laptop, once; Ultralytics 8.4, fixed input, fp32, no NMS in the graph):

```bash
python -c "from ultralytics import YOLO; YOLO('yolo11n_drone.pt').export(format='onnx', opset=17, imgsz=480, simplify=True, dynamic=False)"
```

Push (debug build, app installed; `MSYS_NO_PATHCONV=1` in Git Bash):

```bash
adb push yolo11n_drone.onnx /data/local/tmp/drone_yolo11n_480.onnx
adb shell run-as com.chloeyeo.friendorfoe sh -c 'mkdir -p files/models && cp /data/local/tmp/drone_yolo11n_480.onnx files/models/'
```

A fresh install uses `files/models/drone_yolo11n_480.onnx` when it is there, and the RT-DETR drone snapshot
otherwise. Launch extras (stored until changed):

| extra | runs |
|---|---|
| `--es detector yolo` | the default (marie 480) |
| `--es detector yolo320` / `yolo416` / `yolo480` / `yolo640` | marie at that size (`drone_yolo11n_<N>.onnx`) |
| `--es detector yolo:<name>` | any pushed `files/models/<name>.onnx`, e.g. `yolo:tom_640` |
| `--ef yolo_thr 0.3` | a different drone floor for this run (stricter = fewer phantom boxes) |
| `--es detector drone` | back to the RT-DETR drone model |

```bash
adb shell am start -S -n com.chloeyeo.friendorfoe/.MainActivity --ez demo false --es signal usb --es detector yolo
```

**Licences.** marie's weights are Ultralytics YOLO11, AGPL-3.0 (stated on the model card). TomSmail's card says
CC-BY-4.0 (attribution: TomSmail/drone-yolo-v1), SkyGuardAI's MIT and sapoepsilon's Apache-2.0, but all of them
are Ultralytics-trained weights carrying AGPL-3.0 metadata. No `.onnx` file is committed (`.gitignore` has
`*.onnx`); the weights are adb-pushed like the RT-DETR snapshots.
