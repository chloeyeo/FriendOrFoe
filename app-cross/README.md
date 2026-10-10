# app-cross — FriendOrFoe observer in React Native (fallback)

A second build of the observer app (phone A), written in React Native / Expo, kept ready in case the native
Kotlin app (`app/`) misses the H4 or H10 checkpoint. It runs the same session logic on the same contracts, renders
the UI of the judges' web page (`web/`), and still runs RT-DETR v2 **in-process through the ClikaRT 0.6.4 AAR** on
the phone, so the SC1 claim ("app built with ClikaRT") holds for this build too.

Nothing here changes the native app. The package id is `com.chloeyeo.friendorfoe.cross`, so both apps can be
installed on phone A side by side.

## What is in it, and where it came from

The session logic is ported line by line from the native app's files on `main`, which stay the source of truth.
Chloe's planned work (`.planning/phases/02-on-device-detection/02-0x-PLAN.md`, Phase 5) is implemented here in
the same shape.

| Piece | Native app (owner, state on `main`) | Here |
|---|---|---|
| Contracts (`Detection`, `ObserverUiState`, `SignalEvent`) | `detect/`, `session/`, `signal/` (Phase 1, done) | `src/core/types.ts` |
| Class map, thresholds, IoU de-dup, `/v1/detections` parser, dual drone + person detector | `LabelMap`, `ClassMap`, `Dedup`, `DetectionsJson`, `DualDetector` (done) | `src/detect/` + `ClikaEngine.kt` |
| Sticky tracker (drone 5 s, person 1.5 s, wall clock) | `Tracker` (02-03, done) | `src/track/tracker.ts` |
| 10 fps motion tracking between detections, late-detection fast-forward | `MotionTracker`, `GrayFrame`, `GraySampler`, `IffCoordinator.motionTick` (done) | `src/track/motionTracker.ts`, `grayFrame.ts`, `GraySampler.kt`, the iPhone gray sampler |
| 10 s box lock, lock-aware tick | `IffCoordinator.challenge` (02-03, planned) | `src/session/iffCoordinator.ts` |
| Tap → gadget → re-check → verdict → ring | `IffCoordinator` (Phase 5 / #23, planned) | `src/session/iffCoordinator.ts` |
| Re-checks (nonce ledger, RTT budget, RSSI floor) | `ReChecks` (stream 2, done) | `src/signal/reChecks.ts` |
| Gadget line codec, simulated gadget | `GadgetLineCodec`, `FakeSignalSource` (done) | `src/signal/` |
| ClikaRT bring-up + license probe, RT-DETR open/detect | `FofApplication.loadRuntime`, `ClikaRtDetector` (02-02, stubs) | `modules/fof-native/.../ClikaEngine.kt` |
| CameraX Preview + RGBA analysis at about 1 fps | `CameraController` (02-04, stub) | `modules/fof-native/.../FofCameraView.kt` |
| Laptop `clikart-cli serve` detector (H4 contingency) | `HttpDetector` (02-02, stub) | `FofCameraView.postToServe` + `src/detect/detectionsJson.ts` |
| Reader gadget over USB CDC serial | `UsbGadgetSignalSource` (#16, stub) | `modules/fof-native/.../UsbGadgetLink.kt` + `src/signal/usbGadgetSignalSource.ts` |
| OFFLINE badge rule | `OfflineRule` (02-01, done) | `src/app/offline.ts` |
| Dashboard mirror (events, tracks, thumbnail) | `EventClient`, `EventJson`, `EventDiff` (stream 3, done) | `src/net/` + the camera view's thumbnail post |
| UI | Compose `ui/` | `src/ui/`, following `web/src/lib` (palette, square layout, result list) |

**Tracking between detections (ported from main's 10 fps motion work).** Phone A needs seconds per detection, so
the camera pane also sends a 160 px gray frame every 100 ms (`onGray`). `src/track/motionTracker.ts` (a port of
`track/MotionTracker.kt`, with its tests) moves every ring by template matching on those frames. A detection is
fast-forwarded from its capture time to now before it is matched, so rings follow the object instead of jumping to
where it was seconds ago. Detection runs on its own thread, so the gray frames never wait for inference.

**Demo mode differs from the native app.** In this app, "simulated" means only the fake detector and the fake
gadget running through the real session. There is no scripted `DemoScenario` screen. The detector and the signal
source are chosen separately in Settings, so the on-device camera can run with the simulated gadget.

## Status

- **Verified on this machine**
  - The TypeScript logic: 42 jest tests in `src/__tests__/`, ported from the native unit tests.
  - `tsc --noEmit` and `npx expo-doctor` (21/21 checks).
  - `npx expo prebuild -p android`: the generated project has minSdk 28, Kotlin 2.2.10, arm64-v8a only, the ClikaRT
    Maven repo, jitpack, cleartext HTTP and landscape.
  - The whole UI and the challenge flow on the web build, with the simulated detector and gadget.
- **Not verified yet**
  - The Kotlin in `modules/fof-native` has never been compiled: this Mac has no JDK, no Android SDK and no ClikaRT
    Maven repo. The first `expo run:android` on the integration laptop is its first compile.
  - Nothing has run on a phone yet.
  - The ClikaRT calls in `ClikaEngine.kt` are the same calls, with the same arguments, as the native app's
    `ClikaRtDetector` and `FofApplication.loadRuntime`. Those compiled against the 0.6.4 AAR and ran on phone A at
    checkpoint 1. The `ClikaRtAndroid` signatures match the Kotlin API reference on docs.clika.io.
  - Not checked yet: whether the AAR accepts RN 0.86's Android Gradle plugin 8.12. The native app builds with AGP
    9.2.1, and the docs name 9.3.2 for the current SDK. If Gradle refuses the AAR's minimum AGP, this is where.
  - The calls follow plan 02-02 and the Expo Modules API as installed (SDK 57). The usb-serial-for-android 3.9.0
    calls are written from its README, which is not verified.

## Build and install (Windows integration laptop)

You need Node 20+, JDK 17, the Android SDK, phone A over USB with USB debugging, and the same gitignored
`local.properties` at the repo root that the native app uses:

```properties
clikaRtMavenRepo=C:/Users/C/clika
clika.runtimeLicenseFile=C:/Users/C/clika/clikart.license
```

You can set the env vars `CLIKA_RT_MAVEN_REPO` and `CLIKA_RT_LICENSE_FILE` instead. Only the license file path
goes into the generated `android/gradle.properties`; the native module reads the file and embeds its first line in
`BuildConfig`. Without a license file, the app looks for `clika_rt_license` in its files directory.

```bash
cd app-cross
npm install
npx expo prebuild -p android
npx expo run:android --variant release
```

The release variant bundles the JavaScript into the APK, so it runs in airplane mode with no Metro server. Use
`npx expo run:android` (debug, with Metro) only while developing.

### ClikaRT license on phone A (offline)

Use the **Offline** bundle, the same one the native app uses (`clika.runtimeLicenseFile`). It is verified on the
phone with no network at all. The Online key needs a route to platform.clika.io and fails in airplane mode with
`LICENSE_SERVER_UNREACHABLE`.

The team's offline license in the platform's FriendOrFoe project has one hardware seat, held by another unit, not
phone A. The native app's checkpoint 1 (root README section 10) found that the offline bundle is accepted with no
network. The moment phone A is online, the runtime checks in and is refused with `LICENSE_STATE_BLOCKED`, and it is
accepted again after a cold start offline.

**On stage, phone A runs with airplane mode on and Wi-Fi off.** Samsung keeps Wi-Fi on in airplane mode once it has
been turned on there. A Wi-Fi network with internet makes the phone online, and it gets blocked. The dashboard link
needs a hotspot without internet. The open seat item is Chloe's (platform.clika.io → Licenses).

On Android, ClikaRT identifies the hardware by `Settings.Secure.ANDROID_ID`, which Android scopes to the
app-signing key (docs.clika.io → platform/concepts/runtime-licenses). So `plugins/withFofAndroid.js` signs this
app, debug and release, with Android Studio's default `~/.android/debug.keystore`. That is the key the native app's
`installDebug` uses, so once phone A gets a seat, one seat covers both apps. Build both on the same machine, or point
`FOF_SIGNING_KEYSTORE` at the native app's keystore.

Push the two model snapshots once. They are the native app's dual detector, the RahulKate drone RT-DETR v2 and
D-FINE S for people, both at 320x320 input, placed at this app's own path:

```bash
adb push <rtdetr_v2_drone_rahul_320>/. /sdcard/Android/data/com.chloeyeo.friendorfoe.cross/files/models/rtdetr_v2_drone_rahul_320/
adb push <dfine_small_coco_320>/. /sdcard/Android/data/com.chloeyeo.friendorfoe.cross/files/models/dfine_small_coco_320/
```

Each directory needs `config.json`, `model.safetensors` and `preprocessor_config.json`. Settings → Drone model
directory and Person model directory override the paths.

## iPhone build (on-device Core ML, not ClikaRT)

ClikaRT has no iOS runtime (docs.clika.io → system requirements: Linux, Android, Windows, macOS). The iPhone build
therefore runs Core ML detectors through Vision in the same camera pane, and everything after the detector is the
same session code. This is a second screen, not the SC1 path: phone A's ClikaRT is.

| Model | Source | Gives |
|---|---|---|
| `FofCoco` (YOLO11n, COCO) | Ultralytics `yolo11n.pt` | person, bird |
| `FofDrone` (YOLO11s) | [sapoepsilon/yolov11s-drone-detector](https://huggingface.co/sapoepsilon/yolov11s-drone-detector) (Apache-2.0) | drone |

How the models were chosen, measured on 13 real drone photos (DJI Phantom 4, Inspire, Mavic 2/Pro/Mini, FPV racers,
a VTOL fixed-wing) and 40 drone-free COCO val2017 images:

| Detector | Drones found | Drone-free images that fired |
|---|---|---|
| FofDrone at 0.78 (used) | 9/13 | 1/40 (a real airplane) |
| FofDrone at 0.6 | 12/13 | 4/40 (a small potted plant, a real airplane, a bat and a skateboard in the air) |
| FofDrone at 0.3 | 12/13 | 13/40 |
| TomSmail/drone-yolo-v1 at 0.6 | 8/13 | 6/40 |
| COCO airplane/kite proxy at 0.5 | 8/13 | 1/40, and none of the drones FofDrone missed |
| flying-objects YOLOv8m (rendered sky scenes) | 0/13 | it called every real drone an "airplane" |

At 0.6 the phone still drew drones on everyday objects, so the floor is 0.78. On top of it, a drone is drawn
only once it has been matched in two frames. Frames come every 0.5 s on
the iPhone, so a one-frame false box never shows. Settings → Drone threshold trades misses for false drones.
Birds are a class of their own in this
app (`BIRD`): drawn, logged, never challengeable. The native app still counts COCO bird as a drone proxy.

Build on a Mac with **Xcode 27 or newer**. Expo SDK 57's `expo-modules-jsi` does not compile with Xcode 26.3 /
Swift 6.2 (`SWIFT_RETURNS_RETAINED` on a constructor). You also need CocoaPods and the iPhone in developer mode:

```bash
scripts/export-coreml.sh
APPLE_TEAM_ID=<your team id> npx expo run:ios --device --configuration Release
```

`scripts/export-coreml.sh` builds the compiled models into `modules/fof-native/ios/Models/`. The models are
gitignored: about 23 MB in total. If `pod install` crawls on the React Native core download (repo1.maven.org can drop to
about 25 KB/s on one connection), fetch the three release tarballs yourself and point `RCT_TESTONLY_RNCORE_TARBALL_PATH`,
`RCT_USE_LOCAL_RN_DEP` and `HERMES_ENGINE_TARBALL_PATH` at them.

## Layout on the phone

The screen follows the window size (`src/ui/layout.ts`). The breakpoints come from phone A in the native app's
checkpoint 1: a Galaxy A06, 20:9, about 853 x 384 dp held landscape.

- **Phone, landscape** (height < 520 dp)
  - The camera takes the full height. The brand and the status (OFFLINE, detector timing, gadget) float on its top
    edge, and the object summary sits on its bottom edge.
  - The results column is 32 % of the width (250-340 dp), with short tabs: Results · Activity · Gadget.
- **Upright** (height > width): masthead, camera pane, then results, stacked like the web page's narrow layout.
- **Tablet or desktop**: the web page's masthead plus two columns.
- **Every layout**
  - Tap targets are at least 44 dp: tabs and inputs 44, actions and gadget modes 48.
  - A drone tap gets 36 dp of slop, because at about 3.5 s per frame on phone A the drone may have moved since its
    box was drawn.
  - Ring tags are at least 13 dp, and a tag near the right edge is anchored to the box's right edge.
  - The phone vibrates when a challenge starts, when a tap is refused, and on the verdict (success for FRIEND, error
    for FOE/UNKNOWN).
  - The Android navigation bar is hidden (swipe from the edge to bring it back), the back button closes Settings, and
    number fields open a numeric keypad.

## On stage

- **Masthead:** detector and timing (`On-device ClikaRT · 840 ms · 1.1 fps`), `OFFLINE`, and the gadget link.
  Amber means something needs attention.
- **Detector** (Settings):
  - On-device ClikaRT is the product.
  - Laptop serve is the H4 contingency: `clikart-cli … serve --port 8000`, then set the serve URL.
  - Simulated runs with no camera.

  A ClikaRT refusal shows its code name at the bottom of the observation pane, for example `LICENSE_FAILED`,
  `model dir missing: model.safetensors` or `no license in build`.
- **Signal source** (Settings):
  - Reader gadget (USB) asks for USB permission once. The Gadget tab has a Connect button.
  - Simulated gadget: the Gadget tab switches the drone-side mode (Honest, Replay, Relay, Late, Silent, Impostor),
    the same as the README demo script.
- **Tap a drone ring** to identify it. The ring counts down for 10 s, then turns green `FRIEND · RQ-07` or red
  with the reason. Silence ends in `UNKNOWN · no response`.
- **Dashboard URL** (Settings), for example `http://192.168.43.100:8081`, mirrors the log, the drawn tracks and a
  preview thumbnail to `laptop/dashboard`.
- **Reset** needs a second tap within 3 s.

## Develop

```bash
npm test                         # jest: tracker, de-dup, parser, re-checks, codec, coordinator, overlay, dashboard JSON
npm run typecheck
npx expo start --web --port 8090 # the UI with the simulated detector and gadget (8081 is the dashboard API)
```

Native code changes need a rebuild (`npx expo run:android`). `android/` is generated (`expo prebuild`) and
gitignored; configure it through `app.config.ts` and `plugins/`, never by hand.

## Known differences and risks

- The laptop-serve detector is available in every build. The native app allows it only in debug builds; this app
  is the fallback, so it keeps the H4 path open. The pitch must still say the on-device path is the product.
- Kotlin 2.2.10 is raised from RN 0.86's default 2.1.20, because the ClikaRT AAR's stdlib 2.3.0 metadata cannot be
  read by a 2.1 compiler. If the build breaks on the Kotlin version, this is the first thing to check.
- Frames are rotated upright and boxes are mapped for FILL_CENTER, assuming the activity is landscape-locked
  (`orientation: landscape`), as in the native plan.
