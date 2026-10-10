# FriendOrFoe

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
 │ camera/   CameraX Preview + ImageAnalysis (RGBA, ~1 fps)      │      │ scrcpy mirror (USB)   │
 │    │                                                         │      │ laptop/dashboard      │
 │    ▼                                                         │ HTTP │   FastAPI /dashboard  │
 │ detect/   Detector ──► ClikaRtDetector (RT-DETR v2, in-proc) │─────►│   POST /api/events    │
 │             ├──► HttpDetector (laptop serve, contingency)    │      │ clikart-cli serve     │
 │             └──► FakeDetector (UI dev, scripted boxes)       │      │   --offline (eval,    │
 │    │ boxes                                                   │      │   2nd ClikaRT device) │
 │    ▼                                                         │      └───────────────────────┘
 │ track/    box ids, challenge-window lock                     │
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
| `detect/` | 1 | `Detector` interface, `Detection`, `ObjClass` (PERSON, DRONE), `LabelMap` (RT-DETR `labelId` 0 → person, 4/14/33 → drone), `ClikaRtDetector`, `HttpDetector`, `FakeDetector`, `Detectors.create(config)` |
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

### Gadget link over USB (draft, owner: stream 2, file `docs/GADGET_LINK.md`)

USB CDC serial (the phone is USB host over OTG; library `com.github.mik3y:usb-serial-for-android`),
115200 8N1, one JSON object per line. The gadget generates the nonce, runs the radio exchange and the crypto.

```
phone → gadget   {"cmd":"challenge","boxId":3,"challengerId":7,"t":1760000000}
                 {"cmd":"status"}
gadget → phone   {"type":"answer","verdict":"FRIEND","reason":null,"droneId":"RQ-07","nonce":"<32 hex>","rssiDbm":-58,"responseMs":42,"airtimeMs":1.2}
                 {"type":"answer","verdict":"FOE","reason":"replayed nonce","droneId":"RQ-07","nonce":"<32 hex>","rssiDbm":-61,"responseMs":40}
                 {"type":"answer","verdict":"UNKNOWN","reason":"no response","nonce":"<32 hex>","responseMs":10000}
                 {"type":"status","fw":"0.1","battery":3.9,"mode":"HONEST"}
```

Ihan decides what the gadget actually is (board, radio, how the drone-side attack modes are switched) and
keeps `docs/GADGET_LINK.md` current; the phone side only depends on the lines above. Because the gadget is
developed on the bench over USB-C to a laptop, the whole link can be exercised before the phone exists:
`laptop/gadget_cli.py` sends the same JSON lines from the laptop and prints the answers.

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
4. Models on the phone (stream 1 only): fetch on the laptop with
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
Android Studio Compose template, CameraX, the usb-serial-for-android library, the Arduino core and the
ArduinoJson / Crypto libraries, FastAPI, scrcpy.
Built during the hackathon: everything in this repository — the detector integration, the tracker and box
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
