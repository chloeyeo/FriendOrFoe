# Pitfalls Research

**Domain:** Offline on-device drone detection (ClikaRT 0.6.4 AAR, RT-DETR v2) + phone-to-phone QR challenge-response IFF + 20-hour hackathon live demo on connected Android devices
**Researched:** 2026-10-10
**Confidence:** MEDIUM-HIGH overall. ClikaRT behaviour is HIGH (read from the local binding sources, sample apps, AAR contents and the CLI on this laptop today). CameraX / ML Kit / AGP / Android platform behaviour is HIGH-MEDIUM (official docs fetched today). Hotspot and airplane-mode behaviour on specific phones is LOW (community sources only) and must be tested at H0.

**Demo framing that governs every pitfall below.** Per the user's correction, the demo runs on the connected devices: observer phone A detects and verifies, responder phone B answers. The laptop is a dashboard and the eval backend, never the thing being demonstrated. Any pitfall whose "fallback" would move detection or verification to the laptop is therefore a demo failure, not a mitigation.

**Phase names used in this file** (from the hour plan in PROJECT.md):

| Phase | Hours | Scope |
|-------|-------|-------|
| P0 Foundation | H0–1 | project, AAR, repo, models pushed, laptop serve answers curl, LAN topology test |
| P1 Detector | H1–4 | CameraX + ClikaRtDetector + box overlay; hard checkpoint at H4 |
| P2 IFF module | H4–7 | pure-Kotlin `iff` module, tests and vectors first |
| P3 Responder | H7–10 | Responder mode on phone B, HTTP link, laptop dashboard |
| P4 QR | H10–12 | QR decode inside the box, tags, reasons, event posting |
| P5 Eval + rehearsal | H14–17 | eval set, detector choice, airplane-mode run-throughs, backup video, README |
| P6 Pitch | H17–20 | buffer, PROTOCOL.md, pitch rehearsal, submission |

---

## Critical Pitfalls

### Area 1 — ClikaRT on the phone

### Pitfall 1: The stored credential is an online license key and dies in airplane mode

**What goes wrong:**
Every model call on phone A fails with code name `LICENSE_SERVER_UNREACHABLE` the moment the phone is in airplane mode, even though everything worked all day on Wi-Fi. The runtime does not cache a grace period: the README says calls "fail with `LICENSE_SERVER_UNREACHABLE` and the retryable `Unavailable` status instead, and calls succeed again once the server answers" (`C:/Users/C/Downloads/ClikaRT_windows_x86_64-0.6.4/README.md` lines 92–98; same wording in `ClikaRtAndroid.kt` lines 72–76).

**Why it happens:**
CLIKA issues two credential kinds that look alike: "an online license key, or an offline license, which needs no network" (README.md line 79–80). Both are `CLIKA1-…` text; nothing in the CLI (`clikart-cli --help`, `clikart-license-init --help`) reports which kind is stored, and `--version`/`list` succeed without any license, so "the CLI works" proves nothing. The per-user file on this laptop (`~/.clika/runtime/license`, 297 bytes) has never been exercised with the network off.

**How to avoid:**
Two tests, both before H1:
1. Laptop: turn Wi-Fi off (and unplug Ethernet), then run `clikart-cli --offline --cache-dir C:/Users/C/clika/models PekingU/rtdetr_v2_r18vd detect C:/Users/C/clika/reference/examples-0.6.4/chat_with_pictures/samples/scene.png --device cpu`. Exit 0 with a detections JSON = offline-capable credential. Exit 1 with `LICENSE_SERVER_UNREACHABLE` on stderr = online key. Request an offline license from CLIKA immediately; do not plan around it.
2. Phone A in airplane mode, first detection on device. This is a different test because the Android runtime hands the app's `ANDROID_ID` to the license layer ("the per-device identity a license binds", `ClikaRtAndroid.kt` lines 213–223). A credential that passes on the laptop can still be refused on the phone if it is device- or seat-bound.

In code, branch on `ClikaRtException.codeName` / `ModelverseException.codeName` (`LICENSE_FAILED`, `LICENSE_EXPIRED`, `LICENSE_SERVER_UNREACHABLE`) and show the code name on screen, as the hello sample does (`examples-0.6.4/android/hello/.../MainActivity.kt` line 99: `"refused (${e.codeName}): ${e.message}"`). Never log the credential text.

**Warning signs:**
Detection works on hotel Wi-Fi and fails the first time you toggle airplane mode; stderr/logcat (`adb logcat -s ClikaRT`) shows `LICENSE_SERVER_UNREACHABLE`.

**Phase to address:** P0 (laptop test), P1 (phone airplane-mode test, before the H4 checkpoint).

---

### Pitfall 2: Loading the runtime before the credential is known, then being unable to fix it

**What goes wrong:**
`Modelverse.load(context, license)` returns immediately on a second call: "if (Modelverse.isLoaded) return" (`androidMain/io/clika/modelverse/ModelverseAndroid.kt` lines 17–30; `Modelverse.load` in `commonMain/.../Modelverse.kt` lines 52–73). The license is placed into the process environment only inside that first load (`ClikaRtAndroid.placeLicense`, `ClikaRtAndroid.kt` lines 234–252). If the first load ran with an empty or wrong credential, every later call fails with `LICENSE_FAILED` until the process is killed, no matter what you pass afterwards.

**Why it happens:**
It is tempting to call `Modelverse.load` in `Application.onCreate` to hide first-load latency, before the BuildConfig/stored credential is resolved.

**How to avoid:**
Resolve the credential first (`BuildConfig.CLIKA_RT_LICENSE` from `local.properties` as in `examples-0.6.4/android/hello/build.gradle.kts` lines 19–25 and 37, else the stored text as in `shared/LicenseStore.kt`), then load once on a single runtime thread (`RuntimeLane.kt`: one single-thread dispatcher for every load/close/detect). Only Observer mode loads the runtime; Responder mode on phone B must never call `Modelverse.load` (no license needed, no model files needed, no seat consumed).

**Warning signs:**
`LICENSE_FAILED` that persists after pasting the right credential; logcat line "license credential: none".

**Phase to address:** P1.

---

### Pitfall 3: The pushed model directory has the wrong shape, is missing files, or cannot be pushed where you think

**What goes wrong:**
`AutoModelForObjectDetection.fromPretrained(source, VisionLoadOptions(offline = true, …))` throws on the phone although the same model runs on the laptop: wrong path, half-copied files, or the hub-id resolution looking for `refs/main` that was not pushed.

**Why it happens:**
There are two valid shapes and they are easy to mix:
- Direct directory: `source` = absolute path of the snapshot directory. The binding treats a source starting with `/`, `.` or a drive letter as local and "A local source ignores the hub knobs" (`Types.kt` lines 5–13; `isLocal` in `chat_with_pictures/.../ModelverseEngine.kt` line 120). For RT-DETR the directory must contain exactly what the laptop snapshot holds: `config.json`, `model.safetensors`, `preprocessor_config.json` (`C:/Users/C/clika/models/models--PekingU--rtdetr_v2_r18vd/snapshots/5650961749fa93567c0d46fc7f43ea4f9e914107/`). OWLv2 additionally needs its tokenizer files (`vocab.json`, `merges.txt`, `tokenizer_config.json`, `special_tokens_map.json`, `added_tokens.json`).
- Hub-cache shape: `source` = `PekingU/rtdetr_v2_r18vd`, `cacheDir` = a directory containing the whole `models--PekingU--rtdetr_v2_r18vd/` tree including `refs/main` and `snapshots/<sha>/`, with `offline = true` ("A later load of `Snapshot.loadPath` with `offline = true` runs from the cache alone", `Modelverse.kt` lines 139–159).

Pushing adds its own traps: `adb push dir dest` nests `dest/dir` when `dest` already exists; the HF cache normally uses symlinks into `blobs/` (this laptop's snapshot holds real files, which is why pushing works); and `adb push` into `/sdcard/Android/data/<pkg>/files` is reported as permission-denied on some Android 13+ builds (community reports, LOW confidence: https://forums.beamdog.com/discussion/comment/1200750/).

**How to avoid:**
Use the direct-directory shape (fewer moving parts). Push with `adb push <snapshot-dir>/. /sdcard/Android/data/com.chloeyeo.friendorfoe/files/models/rtdetr_v2_r18vd/` and verify with `adb shell ls -l` that all three files and their byte sizes match the laptop. If the push is refused, use the debuggable-app route: `adb push <snapshot-dir> /data/local/tmp/rtdetr && adb shell run-as com.chloeyeo.friendorfoe sh -c 'mkdir -p files/models && cp -r /data/local/tmp/rtdetr files/models/'` and load from `context.filesDir/models/rtdetr`. In the app, check the three files exist before calling `fromPretrained` and show the missing name on screen. Never `adb uninstall` the app: both `getExternalFilesDir` and `filesDir` are deleted with it, and the 78 MB (RT-DETR) or 593 MB (OWLv2) push must be redone. Reinstall with `installDebug` / `adb install -r` only.

**Warning signs:**
`ModelverseException` naming a file; a load that takes 0 ms and fails; `adb shell ls` shows `models/rtdetr_v2_r18vd/rtdetr_v2_r18vd/`.

**Phase to address:** P0 (push and verify), P1 (load path in code).

---

### Pitfall 4: Loading or detecting on the main thread

**What goes wrong:**
ANR after 5 s, or a frozen preview. `VisionModel.open` "Blocks for the load; call it off the main thread" (`VisionModel.kt` lines 195–220; `AutoModelForObjectDetection.fromPretrained` is a one-line alias for it, `Auto.kt` lines 283–287), and `ObjectDetectionPipeline.invoke` blocks the caller on a `CountDownLatch` until the detection finishes (`Pipelines.kt` lines 296–317 and the `Await` class, lines 102–119). Detection itself runs on the model's own single worker thread "modelverse-vision" (`VisionModel.kt` lines 26–33 and 183), one request at a time per model.

**How to avoid:**
Copy `RuntimeLane` (`examples-0.6.4/android/shared/.../RuntimeLane.kt`): one `Executors.newSingleThreadExecutor().asCoroutineDispatcher()` for load, detect and close. Call `pipeline(image, options)` from the CameraX analyzer thread or from the lane, never from a composable or a click handler. Hold the loaded `VisionModel`/pipeline in a process singleton (not a ViewModel tied to an Activity) and close it only when the process ends.

**Warning signs:**
"Skipped N frames" in logcat, Compose UI stutter during the first detection, ANR dialog.

**Phase to address:** P1.

---

### Pitfall 5: Expecting Vulkan to speed up vision, or letting a Vulkan probe cost you memory and time

**What goes wrong:**
Detection is slower than planned and the first "fix" tried is `device = "vulkan:0"`, which either is refused or crashes the demo build. The `VisionModel` class is documented as "A vision model loaded from its files on disk, on the CPU" and `open` loads "on the CPU" (`VisionModel.kt` lines 10–18 and 195–209), even though `VisionLoadOptions.device` exists (`Types.kt` lines 832–845). The CLI's `--device auto` "falling back down that order when a load fails, else cpu" is a CLI behaviour (`clikart-cli … detect --help`), not something the Kotlin `fromPretrained` promises.

Separately, `Backends.isAvailable(VULKAN)` "Brings it up on the first call" (`Devices.kt` lines 362–367), which maps the 139 MB `libClikaRT_vulkan.so` into the process; `Backends.isLoaded` is the read that "never brings a backend up" (lines 369–378).

**How to avoid:**
Use `device = "cpu"` explicitly for the demo build. If you want a data point, probe once in a debug screen the way the hello sample does (`SampleRuntime.backends()` returns refusals as values with code names `BACKEND_DRIVER_ABSENT` / `BACKEND_NOT_LOADED` / `BACKEND_UNAVAILABLE`, `Devices.kt` lines 330–347) and only then try `vulkan:0` on a copy of the detector, after the H4 checkpoint, never on demo day. Set the CPU worker count before load if measurements favour it: `Os.setenv("CLIKA_RT_NUM_THREADS", "4", true)` before `Modelverse.load` ("read once at the first compute … A phone with a few large cores and several small ones runs best at the large cores' count; measure on the device", `ClikaRtAndroid.kt` lines 41–44).

**Warning signs:**
Logcat "Vulkan declined: …", a sudden +140 MB in `adb shell dumpsys meminfo`, a crash only on one of the two phones.

**Phase to address:** P1 (device = cpu, thread-count measurement), P5 (optional Vulkan experiment only if time remains).

---

### Pitfall 6: Memory pressure kills the app or the model mid-demo

**What goes wrong:**
With the camera pipeline (a 1920×1080 RGBA frame is 8.3 MB), Compose, ML Kit and ~160 MB of mapped native code, a low-RAM phone evicts the app when it goes to the background or when the OWLv2 experiment (593 MB fp32 weights plus 960×960 activations) is attempted on device.

**How to avoid:**
Call `ClikaRtAndroid.trimOnMemoryPressure(context)` once after load (it registers one `ComponentCallbacks2` listener and releases the runtime's idle cached memory at running-low and above, `ClikaRtAndroid.kt` lines 89–104); the chat_with_pictures sample calls it in `MainActivity.onCreate` (line 21) and the hello/chat/serve samples in their `Application.onCreate`. Keep OWLv2 off the phone (see Pitfall 18). Keep only one RGBA copy per frame alive (copy out, close the `ImageProxy`, hand the array to the detector).

**Warning signs:**
`onTrimMemory` levels ≥ `TRIM_MEMORY_RUNNING_LOW` in logcat; the responder or the dashboard browser gets killed when phone A's app is in the foreground.

**Phase to address:** P1.

---

### Pitfall 7: First-load latency and Activity recreation reload the model at the worst moment

**What goes wrong:**
The judges see a black preview for several seconds; or rotating the phone (or the camera-permission dialog) recreates the Activity and a ViewModel-held model is dropped and reloaded. Measured on this laptop today (12 threads): RT-DETR load ≈ 0.4 s, OWLv2 load ≈ 0.7 s plus 9.8 s per detection; on a phone CPU expect several times longer.

**How to avoid:**
Load in a process singleton on the runtime lane as soon as the credential is known, while the camera permission dialog is up; show "model loading… N s" and a disabled Challenge button until the first dummy detection (warm-up on a black 640×640 frame) has returned. Lock the observer Activity orientation (`android:screenOrientation="landscape"`, see Pitfall 11) so recreation does not happen on stage.

**Warning signs:**
A second "model loaded" log line after rotation; the first real detection taking 3× longer than the second.

**Phase to address:** P1.

---

### Pitfall 8: A ~290 MB APK turns every rebuild into a 2–3 minute install, and signing changes the device identity

**What goes wrong:**
The AAR's native payload is 99.7 MB `libClikaRT.so` + 139.3 MB `libClikaRT_vulkan.so` + 19.1 MB modelverse + 11.9 MB + 14.3 MB JNI bridges = 284 MB uncompressed (`unzip -l` of `C:/Users/C/.gradle/caches/modules-2/files-2.1/io.clika/clika-runtime-android/0.6.4/.../clika-runtime.aar`). AGP stores native libs uncompressed for minSdk ≥ 23, so every `installDebug` pushes ~290 MB over USB. On a USB 2 port that is minutes per iteration during P1, the phase with the tightest checkpoint. There is no emulator path (arm64-v8a only, minSdk 28 per the AAR manifest). Additionally, `ANDROID_ID` "is unique for each combination of app-signing key, user, and device" (Android 8.0 changes, https://developer.android.com/about/versions/oreo/android-8.0-changes), and the ClikaRT runtime reads it for license binding (`ClikaRtAndroid.kt` lines 213–223): switching from the debug-signed APK to a release-signed one on demo day presents a new device identity to the license.

**How to avoid:**
Use a USB 3 cable and port; iterate UI/IFF logic with unit tests (P2) rather than reinstalls; add `ndk { abiFilters += "arm64-v8a" }` as the samples do; demo with the same debug-signed build you tested with (`isMinifyEnabled = false` already). Never flip to a release build after H14.

**Warning signs:**
`adb install` > 90 s; "INSTALL_FAILED_INSUFFICIENT_STORAGE" on a phone with < 2 GB free (APK + extracted + models).

**Phase to address:** P0 (cable, storage check), P5 (freeze the build type).

---

### Pitfall 9: Turning on R8/minify late and breaking JNI by name

**What goes wrong:**
A last-hour "make the APK smaller" with `isMinifyEnabled = true` strips or renames classes the two JNI bridges resolve by name.

**Why it is only moderate here:**
The AAR ships consumer keep rules (`proguard.txt`, 9.2 KB, inside the AAR: `-keepclasseswithmembernames … class io.clika.runtime.** { native <methods>; }`, listener interfaces, value classes "constructed by the bridge by signature"), and AGP applies consumer rules automatically. Minify does not shrink the 284 MB of native code anyway, so it buys nothing.

**How to avoid:**
Leave `isMinifyEnabled = false` (current `app/build.gradle.kts`). If ever enabled, test a full detection on device before trusting it.

**Phase to address:** P0 (decision), P5 (do not touch).

---

### Area 2 — CameraX frames into ClikaRT

### Pitfall 10: Treating the RGBA_8888 buffer as `width*4` bytes per row (silent corruption)

**What goes wrong:**
Detections degrade or vanish with no exception. `ImageInput.Rgba8888(pixels, width, height)` expects "four bytes per pixel in the order red, green, blue, alpha, row by row, width × height pixels" (`Types.kt` lines 801–807), and the native call `nativeDetect(handle, requestId, pixels, width, height, encoded, …)` has no stride parameter (`VisionModel.kt` lines 236–246). CameraX packs RGBA into `planes[0]` ("the other two planes are not used", https://developer.android.com/media/camera/camerax/analyze); the official page does not document `rowStride` for the RGBA output, but `PlaneProxy.getRowStride()` is part of the contract and can exceed `width*4` on some devices and resolutions (MEDIUM; the classic symptom is diagonal "green lines" and skewed images, community report: https://forum.deepar.ai/t/android-frameavailable-rgba-format-send-image-with-green-lines/84). Passing the raw buffer gives the model a sheared picture whenever the stride is padded, so always honour the stride. Channel order is a second trap: the CameraX guide lists the bytes as "alpha, red, green, blue" while the ClikaRT binding wants R,G,B,A "as `Bitmap.copyPixelsToBuffer` writes them"; in practice CameraX RGBA output is what `Bitmap.copyPixelsFromBuffer` into an `ARGB_8888` bitmap accepts, which is byte order R,G,B,A — but verify, do not assume (MEDIUM).

**How to avoid:**
```kotlin
val plane = image.planes[0]
val rowStride = plane.rowStride; val w = image.width; val h = image.height
val out = ByteArray(w * h * 4)
val buf = plane.buffer
if (rowStride == w * 4) { buf.get(out) } else {
    val row = ByteArray(rowStride)
    for (y in 0 until h) { buf.position(y * rowStride); buf.get(row, 0, rowStride); System.arraycopy(row, 0, out, y * w * 4, w * 4) }
}
```
Two-minute verification at H2: hold a plain red object, dump one frame via `Bitmap.createBitmap(w, h, ARGB_8888).copyPixelsFromBuffer(ByteBuffer.wrap(out))` to PNG, pull it with adb and look at it. If the object is blue, swap channels before calling the detector. Also feed the same PNG to `clikart-cli … detect` on the laptop: identical boxes = the phone pipeline is faithful.

**Warning signs:**
Boxes only on the left part of the frame, skewed overlay, detections that depend on the chosen analysis resolution.

**Phase to address:** P1.

---

### Pitfall 11: Rotation — sideways frames for the detector, wrong boxes for the overlay

**What goes wrong:**
With the phone in portrait, the analysis frame arrives in sensor orientation (`imageInfo.rotationDegrees` = 90 on most phones). A COCO detector trained on upright pictures sees a sideways person and sideways drone and loses recall; and the box coordinates are in the unrotated frame, so the overlay is rotated against the preview. `ImageAnalysis.Builder.setOutputImageRotationEnabled(true)` rotates RGBA buffers for you, but CameraX 1.7.0-alpha01 "Fixed a bug in `ImageAnalysis` where images were not correctly rotated when output image rotation is enabled and the initial relative rotation is 0 degrees" (https://developer.android.com/jetpack/androidx/releases/camera), so on 1.4.x–1.6.x this path has a known bug. The 1.5.0-beta01 note "Fix ML Kit Analyzer incorrect bounding box coordinates issue when using `CameraController`" means `MlKitAnalyzer` view-referenced coordinates are also unreliable on the project's 1.4.1.

**How to avoid:**
Lock the observer Activity to landscape so `rotationDegrees` is 0 on the back camera of most phones, and handle the remaining cases with a 20-line manual RGBA rotation (90/180/270) before detection; always read `rotationDegrees` and assert it is 0 in the demo configuration (log once). Do not use `MlKitAnalyzer` or `CameraController`; decode the QR from your own cropped bitmap (Pitfall 22) so there is exactly one coordinate system: the rotated analysis frame. Back camera only (`CameraSelector.DEFAULT_BACK_CAMERA`); the front camera adds mirroring you do not need.

**Warning signs:**
People detected only when the phone is tilted; boxes that are correct in landscape and 90° off in portrait.

**Phase to address:** P1.

---

### Pitfall 12: Mapping analysis boxes onto PreviewView with FILL_CENTER cropping

**What goes wrong:**
Rings float 10–30% away from the object. `PreviewView` defaults to `FILL_CENTER`, which crops the preview to the view's aspect ratio (a 20:9 screen versus a 16:9 or 4:3 analysis frame), and "If you use `setTargetResolution()`, then you can receive a buffer whose aspect ratio doesn't match other use cases" (https://developer.android.com/media/camera/camerax/configuration). A naive `x * viewW / frameW` is wrong whenever the aspect ratios differ.

**How to avoid:**
Bind Preview and ImageAnalysis in one `UseCaseGroup` with a `ViewPort` built from the PreviewView so "the crop rects of all the use cases in the group point to the same area in the camera sensor" (same page), request the same aspect ratio for both (16:9), and map with the documented `getCorrectionMatrix(imageProxy, previewView)` that uses `imageProxy.cropRect` and `rotationDegrees` (https://developer.android.com/media/camera/camerax/transform-output), or the explicit FILL_CENTER formula: `scale = max(viewW / imgW, viewH / imgH)`, `dx = (viewW − imgW*scale)/2`, `dy = (viewH − imgH*scale)/2`, `vx = dx + x*scale`, where `imgW/imgH` are the rotated frame dimensions. Draw rings in a Compose `Canvas` sized exactly like the `AndroidView { PreviewView }`. Verify at H3 with a box drawn around a static object at the frame edge, not only at the centre.

**Warning signs:**
Rings correct at the centre and wrong at the edges (scale), or correct in size and shifted (offset).

**Phase to address:** P1.

---

### Pitfall 13: Backpressure, `ImageProxy.close()` and the 1 fps throttle

**What goes wrong:**
Either the analyzer runs exactly once and never again (a forgotten `close()`), or it runs 30 times per second and queues 30 blocking detections (no throttle), or the preview stalls while a detection holds the frame. The guide: "Release the `ImageProxy` to CameraX by calling `ImageProxy.close()`", and with `STRATEGY_KEEP_ONLY_LATEST` (default) "the executor always caches the latest image into an image buffer (similar to a queue with a depth of one)" (https://developer.android.com/media/camera/camerax/analyze).

**How to avoid:**
In `analyze(image)`: if a detection is in flight or `now − lastRun < 1000 ms`, `image.close()` and return; else copy the pixels out (Pitfall 10), `image.close()`, then run the blocking detection on that same single-thread analyzer executor (`setAnalyzer(singleThreadExecutor, …)`). Use `try/finally` around `close()`. While a challenge is outstanding, let the QR scan run on every frame instead (Pitfall 22), pausing detection to free the CPU.

**Warning signs:**
Exactly one log line from the analyzer; CPU at 100% with preview frozen; a growing detection queue.

**Phase to address:** P1.

---

### Pitfall 14: The default 640×480 analysis resolution is too small for the QR (and for a distant drone)

**What goes wrong:**
"The default target resolution setting is 640x480" for ImageAnalysis (https://developer.android.com/media/camera/camerax/configuration). At 640 px across a 70° field of view, a 64 mm QR at 1 m is ~29 px wide for ~41 modules (version 4 plus quiet zone): under one pixel per module, undecodable (ML Kit needs at least 2 px per module, Pitfall 20).

**How to avoid:**
Request 1920×1080 (16:9) for ImageAnalysis with a `ResolutionSelector` (`ResolutionStrategy(Size(1920,1080), FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)` + `AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY`) and "always check the returned image sizes on the use case output in your code" (same page). RT-DETR resizes internally to 640×640 (`preprocessor_config.json`: `"size": {"height": 640, "width": 640}`), so the larger frame costs only the 8 MB copy, not model time.

**Warning signs:**
`image.width` logs 640; QR never decodes beyond 40 cm.

**Phase to address:** P1 (resolution), P4 (QR distance test).

---

### Area 3 — Detection

### Pitfall 15: The label allowlist says "airplane" but the model says "aeroplane" (zero drone boxes, forever)

**What goes wrong:**
PROJECT.md maps `airplane, bird, kite → drone`. The actual `id2label` of the fetched snapshot (`C:/Users/C/clika/models/models--PekingU--rtdetr_v2_r18vd/snapshots/…/config.json`) uses Pascal-VOC spellings: `4: "aeroplane"`, `3: "motorbike"`, `62: "tvmonitor"`, plus `14: "bird"`, `33: "kite"`, `0: "person"`, `67: "cell phone"`. A string allowlist with "airplane" silently never matches, and every drone box is dropped as "not a class we show".

**How to avoid:**
Map by `labelId` (`Detection.labelId`, `Types.kt` line 885) using the integers `{4, 14, 33} → drone`, `{0} → person`, and at load time read `model.capabilities.labels` (the detector's own label table in id order, `Types.kt` lines 247–252) and assert `labels[4] == "aeroplane"`, failing loudly otherwise. Keep the display name "drone" separate from the model's class string.

**Warning signs:**
Persons are boxed, a visible aircraft photo is not; the eval script and the app disagree because the Python side matched on strings.

**Phase to address:** P1.

---

### Pitfall 16: Phone B held up as "the drone" is detected as a cell phone, and the QR replaces the drone picture

**What goes wrong:**
The demo script points phone A at phone B showing a drone image. RT-DETR has `cell phone`, `laptop` and `tvmonitor` classes and will box the phone itself with high confidence; the drone picture inside the 70 mm screen is a 40 px object after the 640×640 resize when B is 0.6 m away (the distance the QR needs, Pitfall 20), too small for a COCO detector to call it an aeroplane/bird/kite. Then, when B switches its screen from the drone picture to the QR, the drone disappears from the camera's point of view, the "drone" box is lost, and "decode only inside the challenged box" has no box.

**How to avoid:**
Decide the prop at H3 with a 10-minute test, not at H15. Two workable options: (a) phone B as a tablet-sized or printed drone: print an A3/A4 drone photo (or use a toy quadcopter), tape phone B at its body centre so the QR appears inside the drone box while the drone stays visible; (b) a tablet as B showing a large drone image with the QR drawn as an overlay in the image centre on challenge, never replacing it. In both cases: show only allowlisted classes (drop `cell phone` boxes), de-duplicate overlapping boxes by IoU keeping the drone-class one, and keep the last drone box "sticky" for 5 s after it disappears so the QR read still has a box. Use per-class thresholds (drone classes 0.25–0.35, person 0.5).

**Warning signs:**
A "cell phone" box where the drone should be; the IFF tag vanishing the moment the QR appears.

**Phase to address:** P1 (prop test at H3), P4 (sticky box, overlay QR).

---

### Pitfall 17: Thresholds, small objects, lighting and screen flicker

**What goes wrong:**
`DetectOptions.scoreThreshold` defaults to 0.5 (`Types.kt` lines 851–868); RT-DETR's drone-like classes on real drones score lower, so recall is poor. Dim venue light raises noise; a bright phone screen in a dark room makes the camera underexpose the teammate (person recall drops); OLED PWM dimming at low brightness produces banding in the camera's rolling shutter; the sensor grid against the display grid produces moiré. On the laptop today RT-DETR detected the sample's stop sign at 0.61, which shows the scale of "confident" scores for this model.

**How to avoid:**
Per-class thresholds (Pitfall 16); drone object ≥ 5% of the frame width (stand B/prop 0.5–0.7 m away, the person 2–3 m); venue lights on; B at maximum brightness (PWM-free on most panels at 100%) with auto-brightness off and dark mode off; a slight angle between the two screens if moiré appears. Pause detection while a QR read is in progress.

**Warning signs:**
Scores oscillating around the threshold frame to frame; the person box flickering when B's screen lights up.

**Phase to address:** P1 (thresholds), P5 (lighting rehearsal in the venue).

---

### Pitfall 18: Planning OWLv2 as a live option on the phone

**What goes wrong:**
OWLv2 at 960×960 (`preprocessor_config.json`: `"size": {"height": 960, "width": 960}`, `do_pad: true`) ran in 9.8 s per 640×480 image on this laptop's 12-thread CPU today (total wall 10.6 s including 0.7 s load, measured via `clikart-cli … google/owlv2-base-patch16-ensemble detect … --device cpu --labels "drone,quadcopter,person,stop sign"`), and returned zero detections at the CLI default confidence 0.3; the model card's own example uses `threshold=0.1` (https://huggingface.co/google/owlv2-base-patch16-ensemble). Weights are 593 MB fp32. On a phone CPU this is tens of seconds per frame and a memory risk (Pitfall 6). Community reports agree on the order of magnitude (OWLv2 ≈ 7× slower than OWL-ViT patch32 on CPU: https://discuss.huggingface.co/t/owlv2-significantly-slower-than-owlvit/73223).

**How to avoid:**
OWLv2 is laptop-only, for the eval table and as the SC1 second device type through `clikart-cli … serve --offline --device cpu`. Never push it to the phone; never wire it into the Observer UI. Scores from the two detectors are not comparable (RT-DETR default 0.5 vs OWLv2 0.1–0.2), so the eval reports precision/recall per detector at its own fixed threshold. Honour the cut order (audio alert → OWLv2 option → Relay → eval table).

**Warning signs:**
Anyone typing `owlv2` into the Android project.

**Phase to address:** P0 (decision), P5 (eval).

---

### Pitfall 19: A dishonest 10-image eval set

**What goes wrong:**
Ten drone photos, threshold tuned on those same ten, "100% recall" reported. Judges in a defence track will ask about false positives on birds and planes, and the number means nothing.

**How to avoid:**
Compose the set at H14 from what the demo will actually see plus hard negatives: 4 drone photos (different sizes/backgrounds, including one photographed from B's screen), 2 birds, 1 real aircraft, 1 kite, 2 scenes with people and no drone. Label `drone` = any of ids {4,14,33}; fix thresholds before running the set (RT-DETR 0.3 for drone classes, OWLv2 0.1); report precision/recall per detector and state that the set is small and that aeroplane/bird/kite are proxies. Keep the eval script's label mapping identical to the app's (ids, not strings, Pitfall 15), and run it through `POST /v1/detections` on the laptop (`serve_api.h` line 27) so the same engine answers.

**Phase to address:** P5.

---

### Area 4 — QR optical channel

### Pitfall 20: The QR is physically too small at the planned 1–2 m

**What goes wrong:**
PROJECT.md says "QR version 3, readable at 1–2 m". ML Kit's requirement: "the smallest meaningful barcode unit should be at least 2 pixels wide, and 2 pixels tall for 2D codes" and it recommends 1280×720 or 1920×1080 input (https://developers.google.com/ml-kit/vision/barcode-scanning/android). A QR cannot be wider than phone B's short side (~64 mm including the mandatory quiet zone). With a ~70° horizontal field of view the frame is 1.4 m wide per metre of distance, so (computed, MEDIUM — depends on the phone's lens):

| Distance | QR width in a 1920 px frame | px/module for v4 + quiet zone (41 modules) | Decodable? |
|----------|------------------------------|---------------------------------------------|------------|
| 1.5 m | ~59 px | ~1.4 | no |
| 1.0 m | ~88 px | ~2.1 | borderline |
| 0.6 m | ~146 px | ~3.6 | yes |
| 0.5 m | ~175 px | ~4.3 | yes |

A 12 MP still (≈4000 px) doubles these distances; a tablet-sized QR doubles them again.

**How to avoid:**
Rehearse at 0.5–0.7 m with 1920×1080 analysis frames (Pitfall 14), or use a tablet as B, or capture a high-resolution still for the QR read when the challenge is sent (`ImageCapture` cropped to the box, slower but 2× the reach). Keep the QR at EC level M, not H (H adds modules without helping a clean screen-to-screen read). Make B render the QR with the ZXing default quiet zone (margin 4) on a white background. Phone cameras focus down to ~10 cm, so close is safe; far is not.

**Warning signs:**
Decoding works on the desk and fails on the stage layout; the QR occupies < 8% of the frame width.

**Phase to address:** P4 (implementation and distance test), P5 (stage layout rehearsal).

---

### Pitfall 21: Payload does not fit the planned QR version

**What goes wrong:**
PROJECT.md estimates ~40 bytes → base64url ≈ 54 chars → "version 3". Byte-mode capacities (https://www.thonky.com/qr-code-tutorial/character-capacities): v3 = 53/42/32/24 bytes at L/M/Q/H; v4 = 78/62/46/34; v5 = 106/84/60/44. 54 base64url characters do not fit v3 at any level ≥ M, so the encoder silently picks v4 or v5, and your module budget (Pitfall 20) is off by 10–20%.

**How to avoid:**
Follow the PROJECT.md decision: the QR carries the raw binary response in QR byte mode (no base64url) and the observer reads `Barcode.getRawBytes()` ("Returns raw bytes as it was encoded in the barcode", ML Kit `Barcode` reference). Fix the response layout to 37 bytes (`v` 1 + `droneId` 2 + `nonce[0:8]` 8 + `lat_q` int32 4 + `lon_q` int32 4 + `alt_q` int16 2 + `tag` 16; or drop `v` from the payload and keep it in the MAC input → 36) so it fits v3-M (42 bytes) with headroom, or v3-L (53 bytes) as PROJECT.md states. Render with ZXing (`com.google.zxing:core:3.5.4`, latest on Maven Central, 2025-11-11) using `EncodeHintType.CHARACTER_SET = ISO-8859-1` and a `String` whose chars are the bytes 0–255, so ZXing stays in byte mode; base64url is only a fallback (46–50 chars → v4-M). Write a unit test in the `iff` module that asserts the encoded length and the chosen version/EC via ZXing's `Encoder`, and a round-trip test that decodes the rendered matrix back to the same bytes.

**Warning signs:**
The rendered QR has 37 modules per side when you planned 29.

**Phase to address:** P2 (layout), P4 (rendering).

---

### Pitfall 22: Decode cadence, attribution and tracking drift

**What goes wrong:**
If the QR is only looked for at the 1 fps detection tick, a read takes 1–2 s per attempt and misses the 10 s window after a couple of failures; if it is decoded on the whole frame, a QR anywhere in the picture counts as "this drone" (attribution broken); if the box jitters, the crop misses part of the code.

**How to avoid:**
State machine on A: `IDLE → CHALLENGED(nonce, boxId, issuedAt)`. In CHALLENGED, every camera frame (not 1 fps) crops the last known box expanded by 25%, builds `InputImage.fromBitmap(crop, 0)` and runs the bundled scanner restricted to `FORMAT_QR_CODE`; detection is paused. A `Barcode.boundingBox` is accepted only if it lies inside the (unexpanded) box; the decoded nonce prefix must equal the outstanding nonce for that `boxId`. Per PROJECT.md's physical-layer evidence, the same QR must also be read inside the box on two consecutive frames at a plausible size (else `FOE · optical mismatch`), and the challenge-to-first-valid-read round trip must stay under the 2.5 s budget (else `FOE · late response`; the Late button adds a 3 s delay to trigger it), which is one more reason to scan every frame rather than at the 1 fps tick. Boxes are re-associated between detection ticks by IoU > 0.3 with the previous box of the same class, and the last box is kept for 5 s after loss (Pitfall 16). The bundled model (`com.google.mlkit:barcode-scanning:17.3.0`, still the latest per the ML Kit release notes) is "statically linked" and needs no Play Services download; warm it up at start (first `process()` is slow).

**Warning signs:**
FRIEND appears when a second QR is shown outside the box; the ring turns green 3 s after the QR appeared.

**Phase to address:** P4.

---

### Pitfall 23: Screen-to-screen capture conditions

**What goes wrong:**
Reflections on B's glass, a dim or dark-mode screen, A's auto-exposure fooled by the bright screen, or autofocus hunting between the screen and the background.

**How to avoid:**
B: brightness 100%, auto-brightness off, light theme, white QR background, `FLAG_KEEP_SCREEN_ON`, screen protector glare checked at the rehearsal. A: tap-to-focus on B once (CameraX `FocusMeteringAction`) or keep continuous AF with B not moving; venue light on (Pitfall 17). Vendor guidance agrees on brightness and glare (https://support.google.com/googlenest/answer/9235302, https://support.simplisafe.com/articles/app-support/why-wont-my-qr-code-scan).

**Phase to address:** P4, P5.

---

### Area 5 — Protocol and crypto implementation

### Pitfall 24: MAC over JSON text or a non-canonical layout

**What goes wrong:**
Two implementations (Kotlin app, Python eval/test-vector script, a future Pi) serialise `{"nonce": …, "t": …}` with different key order, spacing or number formatting, and tags never match; or one side uses milliseconds and the other seconds; or lat/lon quantisation differs in the negative hemisphere (Kotlin `toInt()` truncates toward zero, Python `//` floors).

**How to avoid:**
Define the MAC inputs as fixed-width big-endian byte strings and nothing else:
`cmacInput = "C" ‖ v(u8) ‖ nonce(16) ‖ t(u64 seconds) ‖ challengerId(u16)`;
`tagInput = "R" ‖ v(u8) ‖ nonce(16) ‖ t(u64) ‖ challengerId(u16) ‖ droneId(u16) ‖ latQ(i32) ‖ lonQ(i32) ‖ altQ(i16)` with `latQ = floor(lat * 1e4)`, `lonQ = floor(lon * 1e4)`, `altQ = floor(alt / 10)`. `cmac = HMAC-SHA256(dayKey, cmacInput)[0:8]`, `tag = HMAC-SHA256(droneKey, tagInput)[0:16]`. JSON is only the HTTP envelope and carries hex/base64url strings. Test vectors are produced by a 40-line Python script (`hmac`, `hashlib`, `struct`) in `eval/` and consumed by the Kotlin tests — never generated by the Kotlin code under test.

**Warning signs:**
Vectors "pass" only after you regenerate them from Kotlin; tags match in Korea and fail for a southern-hemisphere test coordinate.

**Phase to address:** P2.

---

### Pitfall 25: Non-constant-time tag comparison

**What goes wrong:**
`tag.contentEquals(expected)` or `==` on strings returns at the first differing byte. On a LAN demo nobody will exploit it, but a judge reading `PROTOCOL.md` will ask, and the fix is five lines.

**How to avoid:**
In the `iff` module: `fun ctEquals(a: ByteArray, b: ByteArray): Boolean { if (a.size != b.size) return false; var r = 0; for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt()); return r == 0 }`. `java.security.MessageDigest.isEqual` is also constant-time on modern JVMs (since Java 6u17, https://jira.mongodb.org/browse/JAVA-1942) but writing your own removes the dependency question. Compare the full 16-byte truncated tag.

**Phase to address:** P2.

---

### Pitfall 26: Nonces — RNG, map keys, store growth, the 10 s window

**What goes wrong:**
(a) `SecureRandom.getInstance("SHA1PRNG", "Crypto")` throws `NoSuchProviderException` on Android 9+ ("The Crypto Java Cryptography Architecture (JCA) provider has been removed", https://developer.android.com/about/versions/pie/android-9.0-changes-all). (b) `HashMap<ByteArray, …>` never finds a nonce again because `ByteArray.equals` is identity in Kotlin/JVM, so every replay looks fresh. (c) A wall-clock age check breaks when a phone's clock is adjusted; (d) the store grows unbounded in a long rehearsal.

**How to avoid:**
`java.security.SecureRandom()` (platform default) injected as a `() -> ByteArray` so tests are deterministic; key the store by `nonce.toHexString()` or a `data class NonceKey(val bytes: ByteArray)` overriding `equals/hashCode` with `contentEquals/contentHashCode`; inject a `Clock` interface with `epochSeconds()` for `t` and `monotonicMillis()` for ages (`SystemClock.elapsedRealtime()` on Android, `System.nanoTime()/1e6` in tests); prune entries older than 60 s on every insert; mark a nonce `used` on first successful verification and reject the second use with `FOE · replayed nonce`.

**Phase to address:** P2.

---

### Pitfall 27: Clock skew between the phones and the daily key boundary

**What goes wrong:**
Both phones in airplane mode have no NTP; a 2-minute skew is normal after a day. If the responder derives `dayKey` from its own date and the observer from its own, they disagree near midnight. Midnight UTC is 09:00 KST — a plausible demo hour. If `t` is sent in milliseconds on one side and seconds on the other, every tag fails.

**How to avoid:**
The responder derives the day from the challenge's `t` in UTC (`YYYYMMDD` of `t`), never from its local clock: `dayKey = HMAC(master, "FOF-day" ‖ "20261010")`. The observer keeps FOF-1's rule from PROJECT.md and accepts yesterday/today/tomorrow (it verifies the tag with the key of the day of its own `t` first, then the two neighbours; three HMACs cost nothing), so a responder that falls back to its local date near a day boundary still verifies. The responder rejects a challenge whose `t` differs from its own clock by more than 120 s (prevents an attacker steering the key day) and the observer judges the 10 s response window on its monotonic clock. Set both phones to automatic network time while still online before the demo. Put a `"skewSeconds"` field in the test vectors to exercise the ±120 s edge.

**Phase to address:** P2 (logic and vectors), P5 (clock sync in the checklist).

---

### Pitfall 28: challengerId binding, stale and duplicate challenges

**What goes wrong:**
If `challengerId` is not inside `tagInput`, a relay to a different observer succeeds. If a double tap issues two challenges and the responder answers the first, the observer's "latest nonce" check rejects an honest answer; if the observer accepts any outstanding nonce without tying it to the box, the attribution property is lost.

**How to avoid:**
`challengerId` is in both MAC inputs (Pitfall 24) and the observer verifies with its own id. The nonce store maps `nonce → (boxId, issuedAt, used)`; a response is accepted only if its prefix matches an entry whose `boxId` is the box it was read in and whose age < 10 s; tapping the same box again cancels the previous entry. No answer after 10 s → `UNKNOWN · no response`, never FRIEND; a valid answer that arrives after the 2.5 s round-trip budget → `FOE · late response` (PROJECT.md). The responder answers one challenge at a time (latest wins); its six demo buttons from PROJECT.md (Honest / Replay / Relay / Silent / Impostor / Late) must each exercise exactly one verdict path honestly — "Replay" re-shows the previous QR verbatim, "Impostor" signs with a wrong key (`FOE · bad tag`), "Late" delays the QR by 3 s.

**Phase to address:** P2 (verifier), P3 (responder buttons).

---

### Pitfall 29: Kotlin unit tests hitting android.jar stubs

**What goes wrong:**
In `:app` local unit tests, framework methods throw `Method … not mocked`, or with `unitTests.returnDefaultValues = true` return null/0 (https://developer.android.com/training/testing/local-tests): `android.util.Base64.encodeToString` returns null, `org.json.JSONObject` is a stub. The protocol tests then fail or, worse, pass vacuously.

**How to avoid:**
The `iff` module is a plain `kotlin("jvm")` module with no Android dependency: `java.util.Base64` (API 26+, fine for minSdk 28), `javax.crypto.Mac`, `java.security.SecureRandom`, `ByteBuffer` for the layout. Its main code does not parse JSON at all; it exposes `ByteArray`/data-class APIs and the app does the JSON envelope with Android's `org.json`. Test vectors are parsed in `iff` tests with `testImplementation("org.json:json:20260814")` (latest on Maven Central) — never add `org.json:json` at `implementation` scope or it ships duplicate `org.json` classes into the APK. Do not set `returnDefaultValues = true` anywhere.

**Phase to address:** P2.

---

### Area 6 — Position check at an indoor venue

### Pitfall 30: No GPS indoors and quantisation cell collision make every verdict "position mismatch" (or make the check vacuous)

**What goes wrong:**
Indoors phone B has no fix or a stale cached one; and 1e-4° is ~11 m of latitude (~8.8 m of longitude at 37.5° N), so two phones 0.5–2 m apart quantise to the same cell: the bearing from A to B is undefined (division by ~0) and the cone test fails or passes randomly.

**How to avoid:**
Implement the position gate exactly as decided in PROJECT.md: a range gate (`distance ≤ R_max = 3 km`) plus either the near-field rule (`distance < 30 m` → bearing undefined, gate passes on range alone) or the ±20° cone. Two phones in one room with real or configured positions then fall into the near-field branch deterministically, and the Relay button's substituted position (5 km away) always fails the range gate, so the cone never has to resolve a 1 m baseline. Keep configured coordinates as the demo-mode default on both phones (persisted settings screen: observer `lat0, lon0, alt0, heading0`; responder honest position = the observer's cell or a few metres away) because the venue has no GPS. For the cone branch compute `expectedBearing = atan2(Δeast, Δnorth)` from the quantised positions, `measuredBearing = heading0 + (cx/W − 0.5) × hFOV`, and pass when `|wrap(expected − measured)| ≤ 20°`. Unit-test all three branches (near-field honest, far cone honest, relayed 5 km) with the exact demo coordinates in the `iff` module. Show `expected / measured / delta` on A's debug line so a mismatch is explainable in seconds. Say in the pitch that positions are configured because the venue has no GPS.

**Warning signs:**
`FOE · position mismatch` on the Honest button; NaN in the bearing log.

**Phase to address:** P2 (cone math + tests), P3 (settings screen), P5 (verify in the venue).

---

### Pitfall 31: Compass noise and the box-offset bearing

**What goes wrong:**
Using the live magnetometer heading indoors (steel, speakers, laptops) gives ±10–30° errors and needs figure-8 calibration ("you can improve the sensor's accuracy by waving the device in a figure-8 pattern", https://developer.android.com/develop/sensors-and-location/sensors/sensors_position), plus magnetic declination (~−8° in Korea) if "true north" is implied. A drone box at the frame edge adds up to ±35° of offset. A ±20° cone cannot absorb both.

**How to avoid:**
Demo mode uses the configured `heading0` (compass shown for flavour only, with its accuracy flag). Keep the challenged box within the central third of the frame (a HUD hint "centre the target" when |cx/W − 0.5| > 0.17). Read `hFOV` from Camera2 characteristics (`SENSOR_INFO_PHYSICAL_SIZE`, `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`) or assume 70°. Ignore elevation in v1 except a sanity `|Δalt| < 100 m`. If the venue rehearsal still shows misses, widen to ±30° and say so.

**Phase to address:** P3 (settings), P5 (venue rehearsal).

---

### Area 7 — Networking on the hotspot LAN

### Pitfall 32: Cleartext HTTP is blocked by default on API 28+

**What goes wrong:**
Every `http://192.168.x.x` request from A (to B and to the laptop dashboard) fails with "Cleartext HTTP traffic to … not permitted". "Starting with Android 9 (API level 28), cleartext support is disabled by default" (https://developer.android.com/privacy-and-security/security-config). `<domain-config>` entries are documented for domain names only; the page says nothing about matching a numeric LAN IP that changes per DHCP lease (MEDIUM), so do not rely on a per-host entry — use `base-config`.

**How to avoid:**
Either `android:usesCleartextTraffic="true"` on `<application>` (honoured on a best-effort basis by `HttpURLConnection`/OkHttp; ignored on API 24+ only if a Network Security Config is present, https://developer.android.com/guide/topics/manifest/application-element) or a config with `<base-config cleartextTrafficPermitted="true"/>` referenced by `android:networkSecurityConfig`. Add `<uses-permission android:name="android.permission.INTERNET"/>` (missing today in `app/src/main/AndroidManifest.xml`). Server sockets (B's HTTP server) are not policed.

**Warning signs:**
`java.io.IOException: Cleartext HTTP traffic … not permitted`; `SecurityException: Permission denied (missing INTERNET permission?)`.

**Phase to address:** P3.

---

### Pitfall 33: Networking on the main thread, and timeouts that are infinite or too long

**What goes wrong:**
`NetworkOnMainThreadException` on the first tap; or, with B unreachable, the observer hangs: `HttpURLConnection` timeouts default to 0 = infinite (`java.net.URLConnection.setConnectTimeout`, https://developer.android.com/reference/java/net/URLConnection), and OkHttp's defaults are 10 s each for connect/read/write — exactly the whole IFF window.

**How to avoid:**
Send the challenge on `Dispatchers.IO`; set connect 2 s / read 3 s explicitly; treat a failed POST as `UNKNOWN · no response` immediately (do not wait 10 s for the QR that will never come). If you use OkHttp, 5.5.0 is the current release (Maven Central 2026-08-16); `HttpURLConnection` with explicit timeouts is enough for two POSTs.

**Phase to address:** P3.

---

### Pitfall 34: The LAN itself — Windows Mobile Hotspot needs an uplink, airplane mode kills hotspots, phones drop "no internet" Wi-Fi

**What goes wrong:**
(a) Windows refuses to start the hotspot with the Wi-Fi radio off or no connection to share: "We can't set up mobile hotspot because your PC doesn't have an Ethernet, Wi-Fi, or cellular data connection" (https://answers.microsoft.com/en-us/windows/forum/all/we-cant-set-up-mobile-hotspot/683338d1-3c66-43ed-8ae0-76626e183552; LOW-MEDIUM), so "laptop Wi-Fi off, laptop hotspot on" is self-contradictory and makes the laptop a critical-path network device, against the demo-on-devices decision. (b) Turning on airplane mode on a phone turns its hotspot off; whether it can be re-enabled under airplane mode differs per OEM (no authoritative source found; LOW). (c) Android routes away from or disconnects Wi-Fi without internet on some phones ("requests are not sent to a Wi-Fi network that lacks internet connectivity", https://android-developers.googleblog.com/2016/07/connecting-your-app-to-wi-fi-device.html; OEM "switch to mobile data"/"smart network switch"). (d) The hotspot host's DHCP lease changes B's IP after a reconnect. (e) Some hotspots isolate clients from each other.

**How to avoid:**
Decide and test the topology at H0, with all three devices, using the real demo settings:
- Host the hotspot on phone B (the responder) with mobile data off, as decided in PROJECT.md: A measures the RSSI of the access point it is attached to, so the responder must be the AP for the `FOE · signal mismatch` gate to mean anything. A is in airplane mode with Wi-Fi re-enabled; the laptop is a plain client. Fallbacks (a third phone or a pocket travel router) keep the HTTP link working but lose the RSSI gate, and the pitch must then drop that claim. Optional engineering fallback: `WifiManager.startLocalOnlyHotspot` in the responder app (shows SSID/password on B's screen; needs location permission; LOW confidence on OEM behaviour).
- On A, after joining: answer "Stay connected / don't ask again" to the no-internet prompt, disable "switch to mobile data automatically"/adaptive connectivity, and, belt and braces, `ConnectivityManager.bindProcessToNetwork(wifiNetwork)`.
- Disable the hotspot's idle auto-off and the host's battery saver.
- B shows its IPv4 (from `ConnectivityManager.getLinkProperties(activeNetwork)`), A persists it and polls `GET /health` every 2 s with a green/red indicator; the demo starts only on green.
- Verify client-to-client traffic (laptop → B `curl http://<B>:8080/health`, A → B via the indicator) and A → laptop dashboard before H1.

**Warning signs:**
The hotspot toggle greyed out; A's Wi-Fi icon with "no internet" then disconnecting; `connect timed out` while `ping` from the laptop works.

**Phase to address:** P0 (topology test), P3 (health indicator), P5 (checklist).

---

### Pitfall 35: The responder's HTTP server stops answering in the background

**What goes wrong:**
Phone B's owner switches to the camera or the screen times out; on Android 14 "the system freezes app processes 10 seconds after they enter the cached state" and suspends their threads (https://source.android.com/docs/core/perf/cached-apps-freezer); the embedded server stops accepting connections and A reports UNKNOWN. A second trap: the server bound in an Activity throws `Address already in use` after Activity recreation.

**How to avoid:**
Responder mode stays in the foreground with `window.addFlags(FLAG_KEEP_SCREEN_ON)`, screen timeout irrelevant; no foreground service is needed for a 3-minute demo. Hold the server (NanoHTTPD `org.nanohttpd:nanohttpd:2.3.1`, the last release, 2016-08-12, Java-only and unmaintained but tiny; or Ktor CIO `io.ktor:ktor-server-cio:3.6.0`, current on Maven Central 2026-09-16, heavier but maintained) in a process singleton started in `Application`/first `onCreate` and stopped only on explicit "Stop" so recreation does not rebind; synchronise responder state since NanoHTTPD serves each request on its own thread. If a foreground service is ever added, Android 14 requires a type: `connectedDevice` needs `FOREGROUND_SERVICE_CONNECTED_DEVICE` plus a network permission such as `CHANGE_NETWORK_STATE` (https://developer.android.com/develop/background-work/services/fg-service-types), exactly as the ClikaRT `serve` sample manifest declares.

**Phase to address:** P3.

---

### Area 8 — Gradle / AGP 9.2.1 / Kotlin 2.2.10

### Pitfall 36: Applying `kotlin-android`, or a Kotlin plugin version other than 2.2.10, in an AGP 9 build

**What goes wrong:**
AGP 9 has built-in Kotlin; applying `org.jetbrains.kotlin.android` fails with "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0" or "Cannot add extension with name 'kotlin'" (https://developer.android.com/build/migrate-to-built-in-kotlin). AGP 9.0 "has a runtime dependency on KGP 2.2.10"; a lower declared version is upgraded, a higher one needs an explicit `buildscript` classpath (https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes; the AGP 9.2.0 notes' compatibility table still lists KGP 2.2.10 as minimum and default, https://developer.android.com/build/releases/past-releases/agp-9-2-0-release-notes). Adding the `iff` JVM module with `kotlin("jvm") version "2.3.0"` therefore produces plugin-classpath conflicts or a silently different compiler for the app.

**How to avoid:**
Keep `kotlin = "2.2.10"` in `gradle/libs.versions.toml` and add `kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }`; root `build.gradle.kts`: `alias(libs.plugins.kotlin.jvm) apply false`; `iff/build.gradle.kts`: `plugins { alias(libs.plugins.kotlin.jvm) }` only (no Compose plugin, no Android plugin); `settings.gradle.kts`: `include(":iff")`; app: `implementation(project(":iff"))`. Never add `kotlin-android`. Configure Kotlin options in `kotlin { compilerOptions { … } }`, not `android.kotlinOptions`.

**Phase to address:** P0 (module skeleton with one passing test).

---

### Pitfall 37: JVM-target mismatch between the JVM module, the Gradle daemon (JDK 21) and the app

**What goes wrong:**
`gradle/gradle-daemon-jvm.properties` pins `toolchainVersion=21`. A bare `kotlin("jvm")` module then compiles Java at 21 and Kotlin at `jvmTarget` 21, and either KGP's check fails ("Inconsistent JVM-target compatibility detected for tasks 'compileJava' (21) and 'compileKotlin' (…)", https://kotlinlang.org/docs/gradle-configure-project.html#check-for-jvm-target-compatibility-of-related-compile-tasks) when you set only one side, or the app's D8 step meets class files newer than it accepts. `jvmToolchain(17)` would fix it but triggers a foojay toolchain download (the settings file applies `org.gradle.toolchains.foojay-resolver-convention`), which needs network.

**How to avoid:**
In `iff/build.gradle.kts`: `java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }` and `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` (no toolchain block). The app's Java 11 `compileOptions` can stay; built-in Kotlin's `jvmTarget` "defaults to `android.compileOptions.targetCompatibility`" (migration page). Verify with `./gradlew :iff:test :app:compileDebugKotlin` at H1.

**Phase to address:** P0.

---

### Pitfall 38: `buildConfigField` without `buildConfig = true`, repositories in the wrong file, and test task names

**What goes wrong:**
(a) The license field pattern from the samples (`buildConfigField("String", "CLIKA_RT_LICENSE", …)`) compiles to nothing unless `buildFeatures { buildConfig = true }` (AGP default false; the sample `hello/build.gradle.kts` sets it). (b) `settings.gradle.kts` uses `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so adding `repositories {}` to a module (e.g., for the local ClikaRT Maven repo) fails the build; the repo `maven(url = uri("C:/Users/C/clika"))` is already in settings. (c) With AGP 9's `android.onlyEnableUnitTestForTheTestedBuildType` there is no `testReleaseUnitTest`; the tasks are `:iff:test` and `:app:testDebugUnitTest`; `connectedDebugAndroidTest` needs a phone. (d) The template's `compileSdk { version = release(36) { minorApiLevel = 1 } }` is the AGP 9 DSL; do not "fix" it to `compileSdkVersion`.

**How to avoid:**
Copy the sample's `buildTimeLicense` block and `buildFeatures { buildConfig = true }` into `app/build.gradle.kts`; keep `clika.runtimeLicenseFile=<path>` in the gitignored `local.properties`. Put `./gradlew :iff:test` in the README as the one-line verification.

**Phase to address:** P0.

---

### Pitfall 39: Template version skew decided mid-hackathon

**What goes wrong:**
`libs.versions.toml` carries `activityCompose = "1.8.0"`, `coreKtx = "1.10.1"`, `lifecycleRuntimeKtx = "2.6.1"` next to `composeBom = "2026.02.01"`; the ClikaRT samples pair the sibling BOM `2026.02.00` with `activity-compose:1.13.0` (current stable, 2026-03-11) and `kotlinx-coroutines-android:1.10.2` (1.11.0 is current, 2026-05-07; either is fine). CameraX is pinned at 1.4.1 while the current stable is 1.6.2 (2026-08-26, https://developer.android.com/jetpack/androidx/releases/camera). Old `activity-compose` with a new BOM is a known source of runtime `NoSuchMethodError`s (MEDIUM); bumping CameraX at H9 to chase a bug means re-learning APIs under pressure.

**How to avoid:**
At H0, in one commit: `activityCompose = "1.13.0"`, add `kotlinx-coroutines-android:1.10.2`, and decide CameraX once (1.6.2 recommended: same `ImageAnalysis`/`PreviewView` API surface for everything this project uses, and it contains the 1.5.x fixes). After H1, versions are frozen.

**Phase to address:** P0.

---

### Area 9 — Demo day

### Pitfall 40: Airplane-mode semantics and the SC1 "network blocked" rule

**What goes wrong:**
Airplane mode turns Wi-Fi off; the operator forgets to turn Wi-Fi back on and the challenge fails; or the hotspot host is put into airplane mode and the LAN disappears (Pitfall 34); or a judge asks "isn't Wi-Fi a network?" and there is no crisp answer.

**How to avoid:**
Script the exact sequence on A: airplane mode on → Wi-Fi on → join hotspot "FOF" → indicator green. Pixel phones remember the wireless connections you turned back on in airplane mode ("The next time you turn on Airplane mode, your phone will remember your previous setting", https://support.google.com/pixelphone/answer/12639358); other OEMs may not (LOW). Define "network blocked" in the README and the pitch as "no internet path exists: the observer is in airplane mode, the LAN has no uplink, and the laptop's only link is that LAN; `clikart-cli … serve --offline` refuses to touch the network". Show the "no internet" badge on A's Wi-Fi icon as evidence.

**Phase to address:** P5 (checklist), P6 (pitch wording).

---

### Pitfall 41: Thermal throttling, battery, brightness and screen timeouts after 10 minutes

**What goes wrong:**
RT-DETR at 1 fps on the big cores warms the phone; after ~10 minutes clocks drop and the 1 fps becomes 0.4 fps right when the judges arrive. Screens time out during the Q&A. Notifications pop over the preview.

**How to avoid:**
A 10-minute soak test at H15 with latency logged per minute; if it degrades, drop to 0.5 fps or `CLIKA_RT_NUM_THREADS=4`; pause detection while CHALLENGED (Pitfall 22). Both phones ≥ 80% battery, chargers at hand, `FLAG_KEEP_SCREEN_ON` in both modes, Do Not Disturb on, auto-rotate off, B at 100% brightness. Start the app 2 minutes before the slot, not 20.

**Phase to address:** P5.

---

### Pitfall 42: No backup video, no reset button, no rehearsal

**What goes wrong:**
One flaky step (QR read, hotspot) costs the 3 minutes; the nonce store still holds the last run and "Honest" shows `replayed nonce`.

**How to avoid:**
A "Reset demo" action on both phones (clear nonce store, tags, outstanding challenge; B clears its last answer). Three complete airplane-mode run-throughs at H16, each timed under 3 minutes. Record a backup: `adb shell screenrecord` on A plus a third phone filming both devices, and keep it on the laptop and on a phone. Decide in advance the sentence that introduces the video if the live run fails.

**Phase to address:** P5.

---

### Pitfall 43: Pitch honesty — the QR stand-in and pre-existing work

**What goes wrong:**
A judge hears "QR code" and thinks "that is not an optical beacon"; or cannot tell what existed before the hackathon.

**How to avoid:**
One sentence, said once: "The QR stands in for a modulated optical beacon: directional, short-range, and read from inside the detected object's box, which is the attribution property no RF answer has." State the pre-existing parts explicitly: ClikaRT runtime and its Kotlin binding, the RT-DETR/OWLv2 checkpoints, the Android Studio template, CameraX/ML Kit/ZXing; everything in the repository was built today, and the first commit timestamp proves it. Put the same split in README.

**Phase to address:** P6.

---

### Pitfall 44: The demo drifts back to the laptop

**What goes wrong:**
At the H4 checkpoint the on-device detector is not yet stable and `HttpDetector` (laptop `clikart-cli serve`) becomes "the demo path"; or the laptop hosts the hotspot and must stay in the critical path. Both contradict the user's decision that the demo runs on the connected devices, and the first also weakens the SC1 claim "app built with ClikaRT".

**How to avoid:**
`HttpDetector` exists only so UI/IFF/QR work can continue while the on-device path is debugged; it is never selected in a demo build (a `BuildConfig` flag that fails the build if set for the demo). The judges' primary live view is a scrcpy USB mirror of phone A (PROJECT.md decision), which needs no network; the FastAPI dashboard is the first discretionary cut, so a dashboard failure must never stall the demo. At H4, if on-device detection is not working, spend the next hour on this ordered checklist instead of switching: license code name (Pitfall 1) → load path and files (Pitfall 3) → RGBA stride/channel dump compared with the laptop CLI on the same PNG (Pitfall 10) → label ids (Pitfall 15) → threshold (Pitfall 17). The laptop stays a client on a phone-hosted LAN (Pitfall 34).

**Phase to address:** P1 (checkpoint rule), P5.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Configured positions and heading instead of GPS/compass | Deterministic verdicts indoors | Position binding is only demonstrated, not sensed | Always for this demo; say so in the pitch |
| `android:usesCleartextTraffic="true"` | Two lines, LAN HTTP works | Would be wrong in a shipped app | Demo only; PROTOCOL.md notes TLS/BLE/optical as the production path |
| Demo build = debug-signed APK | No signing work, stable `ANDROID_ID` for the license | Not distributable | Always for the hackathon |
| QR replaces/overlays the drone image on B | No extra hardware | The optical channel is a stand-in | Acceptable; blink/OOK documented as the upgrade |
| Sticky box for 5 s after detection loss | QR read survives a lost frame | Could attribute to a moved object | Demo only; production would track |
| One shared master key on both phones | Zero key distribution | Captured responder leaks the day/drone key | Acceptable; Ed25519 per-drone documented |
| Skipping Vulkan entirely | Deterministic CPU path | Possibly slower than achievable | Always for the demo (Pitfall 5) |
| `HttpDetector` kept in the codebase | Unblocks UI work before H4 | Temptation to demo with it | Dev-only; must be impossible to select in the demo build |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| ClikaRT license | Assume a credential that works online works offline; load before the credential is known | Laptop Wi-Fi-off test and phone airplane-mode test before H1; load once after resolving the credential (`ModelverseAndroid.kt`, `ClikaRtAndroid.kt`) |
| ClikaRT model source | Push half a snapshot; mix hub-id and directory shapes | Absolute snapshot directory with all files, verified by `adb shell ls`; `offline = true` |
| ClikaRT detection | Pass CameraX plane[0] directly; match labels by string | Row-by-row copy to `width*4`; match by `labelId` {4,14,33,0}; assert `capabilities.labels[4] == "aeroplane"` |
| ClikaRT threading | Detect from a click handler | Single runtime lane; `invoke` blocks (`Pipelines.kt` Await) |
| CameraX | Default 640×480, `MlKitAnalyzer`, `setOutputImageRotationEnabled` on 1.4.x | 1920×1080 `ResolutionSelector`, own crop + `InputImage.fromBitmap`, landscape lock + manual rotation |
| ML Kit barcode | Unbundled `play-services-mlkit-barcode-scanning` (downloads a model) | Bundled `com.google.mlkit:barcode-scanning:17.3.0`, warmed up at start |
| ZXing encode | `MARGIN = 0`, EC level H, base64url text | Byte mode via ISO-8859-1, default margin 4, EC L/M, assert version 3 in a test; read with `Barcode.getRawBytes()` |
| HTTP client | `HttpURLConnection` with default (infinite) timeouts on the main thread | `Dispatchers.IO`, connect 2 s / read 3 s |
| HTTP server on B | Bound in the Activity; app backgrounded | Process singleton, foreground Activity, `FLAG_KEEP_SCREEN_ON` |
| Hotspot | Laptop Mobile Hotspot with Wi-Fi off; host phone in airplane mode | Phone B (responder) hosts the hotspot so A can read its RSSI; laptop as client; tested at H0 |
| Gradle | `kotlin-android`, `kotlin("jvm") 2.3.0`, `jvmToolchain(17)` | Built-in Kotlin, KGP 2.2.10 everywhere, explicit Java/Kotlin target 17 in `iff` |
| Unit tests | `org.json`/`android.util.Base64` in `:app` tests | Pure JVM `iff`, `java.util.Base64`, `org.json:json` as `testImplementation` only |
| Laptop serve | Letting `HttpDetector` become the demo | `clikart-cli … serve --offline --device cpu` is eval + SC1 second device only |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| OWLv2 on the phone | Tens of seconds per frame, memory kills | Laptop-only (9.8 s/frame measured on 12 laptop threads) | Immediately |
| Detection on every camera frame | CPU pegged, preview stalls, heat | 1 fps gate + close-before-detect; pause during CHALLENGED | Within a minute |
| All cores for inference | Little cores slow the pool; heat | `CLIKA_RT_NUM_THREADS` = big-core count, measured at H2 | After 5–10 minutes (throttling) |
| QR scan only at detection ticks | 1–2 s per attempt, misses in the 10 s window | Scan every frame while CHALLENGED | On stage |
| 640×480 analysis frames | QR undecodable beyond 40 cm | 1920×1080 | On stage |
| Reinstalling a 290 MB APK per change | 2–3 min per iteration during P1 | USB 3, unit-test the logic, install rarely | During P1 |
| Nonce store without pruning | Slow growth, confusion across rehearsals | Prune > 60 s; Reset button | Long rehearsals |
| Vulkan probe | +140 MB mapped, slow start | Never call `Backends.isAvailable(VULKAN)` in the demo build | On low-RAM phones |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| MAC over JSON text | Cross-implementation mismatch; ambiguous canonical form | Fixed binary layout (Pitfall 24) |
| `contentEquals` for tags | Timing side channel; judge question | `ctEquals` (Pitfall 25) |
| `challengerId` not in the response MAC | Relay to another observer succeeds | Include it; verify with own id |
| Day key from local date | Midnight-UTC (09:00 KST) disagreement | Derive from challenge `t` in UTC |
| Accepting a response with no outstanding nonce or outside the box | Attribution and replay properties lost | nonce → boxId map; in-box check first |
| `SecureRandom.getInstance("SHA1PRNG","Crypto")` | `NoSuchProviderException` on API 28+ | `SecureRandom()` |
| Committing `local.properties`, `*.license`, keys | Credential leak | Already gitignored; add `keys/` and `*.key`; never log credential text |
| `FRIEND` on timeout or on a parse error | Fails open | Any failure → FOE with reason; no answer → UNKNOWN |

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| No link indicator before tapping | Operator taps into a dead LAN | Health poll with green/red dot; Challenge disabled when red |
| Rings 0.5 s behind the object at 1 fps | Looks broken | Draw the last box with a subtle fade; label "scanning…" |
| Verdict text without the reason | Judges cannot follow | `FOE · replayed nonce`, `FOE · position mismatch (Δ 87°)`, `UNKNOWN · no response (10 s)` |
| Loading state hidden | Black preview, panic | "model loading… 3 s", first-frame warm-up |
| Settings buried | Cannot change B's IP under time pressure | IP field and demo positions on one settings sheet; persisted |
| Debug lines missing | A mismatch cannot be explained in Q&A | Compact HUD: fps, latency ms, expected/measured bearing |

## "Looks Done But Isn't" Checklist

- [ ] **Offline license:** Detection ran on phone A in airplane mode, not only on the laptop with Wi-Fi — verify the code name path shows nothing (Pitfall 1).
- [ ] **Model on device:** `adb shell ls -l` shows all snapshot files with laptop byte sizes; app loads from `getExternalFilesDir`/`filesDir` with no network (Pitfall 3).
- [ ] **Frame fidelity:** One dumped RGBA frame, run through `clikart-cli … detect`, gives the same boxes as the phone (Pitfall 10).
- [ ] **Labels:** `capabilities.labels[4] == "aeroplane"` asserted; drone ids {4,14,33} (Pitfall 15).
- [ ] **Prop:** The chosen drone prop produces a drone-class box at demo distance with the QR inside it, and the box survives the QR appearing (Pitfall 16).
- [ ] **QR geometry:** Decodes at the rehearsed distance with the rehearsed phone; version ≤ 4, EC M (Pitfalls 20–21).
- [ ] **Verdicts:** All six buttons (Honest, Replay, Relay, Silent, Impostor, Late) produce their verdict three times in a row after Reset (Pitfalls 26–28, 42).
- [ ] **Vectors:** Python-generated vectors pass in `:iff:test`; a deliberately flipped byte fails (Pitfall 24).
- [ ] **Position:** Honest = near-field (< 30 m) or inside cone, Relay = fails the 3 km range gate, with the configured demo coordinates, unit-tested (Pitfall 30).
- [ ] **LAN:** A (airplane mode + Wi-Fi) → B and A → laptop both answer; hotspot host is not the observer; idle auto-off disabled (Pitfall 34).
- [ ] **Background:** B keeps answering after 60 s untouched with the screen on (Pitfall 35).
- [ ] **Build:** `./gradlew :iff:test :app:installDebug` from a clean clone with only `local.properties` added (Pitfalls 36–38).
- [ ] **Demo build:** `HttpDetector` cannot be selected; the build type is the one rehearsed (Pitfalls 8, 44).
- [ ] **Backup:** Video recorded after a full airplane-mode run (Pitfall 42).

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Online license discovered late | HIGH | Request an offline credential from CLIKA; until it arrives, keep developing against the laptop `serve` but do not change the demo plan |
| Model load fails on the phone | LOW | Compare `adb shell ls -l` with the laptop snapshot; re-push the missing file; check the absolute path string |
| Garbled detections | LOW | Dump a frame to PNG; fix stride/channel order; compare with the CLI |
| No drone boxes | LOW | Log `capabilities.labels`; switch to id mapping; lower drone-class threshold to 0.25 |
| Phone detected as "cell phone" | MEDIUM | Switch prop (printed/tablet); drop non-allowlisted classes; sticky box |
| QR never decodes | MEDIUM | 1920×1080 frames; move B to 0.5 m; EC M; brightness 100%; check version in the test |
| Honest shows position mismatch | LOW | Read expected/measured on the HUD; fix configured heading/positions; widen to ±30° if needed |
| Cleartext/timeouts | LOW | Manifest flag + INTERNET; explicit timeouts; IO dispatcher |
| Hotspot cannot start | MEDIUM | Switch host to the other phone or a travel router; re-join; update B's IP on A |
| Responder frozen | LOW | Bring the app to the foreground; Reset; keep screen on |
| Gradle plugin conflict | LOW | Remove `kotlin-android`; pin KGP 2.2.10; explicit Java/Kotlin 17 in `iff` |
| Live demo fails | LOW | Play the backup video with the prepared sentence; show the laptop dashboard history |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| 1 Online license | P0 (laptop), P1 (phone) | Detection JSON with laptop Wi-Fi off; green ring in airplane mode |
| 2 Load before credential | P1 | Logcat "license credential: supplied by the app" exactly once |
| 3 Snapshot shape / push | P0, P1 | `adb shell ls -l` sizes match; load succeeds offline |
| 4 Main-thread load/detect | P1 | No ANR; analyzer thread name in logs |
| 5 Vulkan | P1 | `device = "cpu"` in config; no `libClikaRT_vulkan` in `dumpsys meminfo` |
| 6 Memory pressure | P1 | `trimOnMemoryPressure` registered; app survives backgrounding B |
| 7 First-load / recreation | P1 | One "model loaded" per process; landscape lock |
| 8 APK size / signing | P0, P5 | Install < 90 s on USB 3; same build type at demo |
| 9 Minify | P0 | `isMinifyEnabled = false` |
| 10 RGBA stride / channels | P1 | Frame dump matches CLI boxes |
| 11 Rotation | P1 | `rotationDegrees == 0` logged; upright frames |
| 12 Box mapping | P1 | Edge-object ring test |
| 13 Backpressure / close | P1 | Continuous analyzer logs; CPU < 100% |
| 14 Analysis resolution | P1 | `image.width == 1920` logged |
| 15 Label spelling | P1 | Assertion on `labels[4]` |
| 16 Phone-as-drone prop | P1 (H3), P4 | Drone-class box with QR inside at demo distance |
| 17 Thresholds / lighting | P1, P5 | Stable boxes in venue light |
| 18 OWLv2 on phone | P0, P5 | Not in the Android project |
| 19 Eval honesty | P5 | Negatives present; fixed thresholds; per-detector table |
| 20 QR size | P4, P5 | Decode at the rehearsed distance |
| 21 QR version | P2, P4 | Encoded-length test; version ≤ 4 |
| 22 Decode cadence / attribution | P4 | Green ring < 1 s after QR shows; second QR outside box ignored |
| 23 Screen capture conditions | P4, P5 | Rehearsal under venue light |
| 24 Canonical layout / vectors | P2 | Python vectors pass; flipped byte fails |
| 25 Constant-time compare | P2 | `ctEquals` used everywhere tags are compared |
| 26 Nonces / RNG / store | P2 | Replay test passes; store pruned |
| 27 Clock skew / day boundary | P2, P5 | Skew vectors; phones on automatic time |
| 28 challengerId / stale | P2, P3 | Vectors for other-observer and expired cases |
| 29 android.jar stubs | P2 | `:iff:test` green with no Android dependency |
| 30 Indoor positions | P2, P3, P5 | Honest passes near-field/cone, Relay fails range gate with demo coordinates |
| 31 Compass / box offset | P3, P5 | HUD delta < 20° for Honest at the venue |
| 32 Cleartext | P3 | POST succeeds to 192.168.x.x |
| 33 Main-thread / timeouts | P3 | Unreachable B → UNKNOWN within 3 s |
| 34 LAN topology | P0, P3, P5 | Three-device test; indicator green |
| 35 Responder background | P3 | 60 s idle test |
| 36 Kotlin plugin versions | P0 | Clean build with `iff` module |
| 37 JVM target | P0 | `:iff:test` and `:app:compileDebugKotlin` pass |
| 38 buildConfig / repos / tasks | P0 | `BuildConfig.CLIKA_RT_LICENSE` present; `:iff:test` runs |
| 39 Version skew | P0 | Versions frozen after H1 |
| 40 Airplane semantics | P5, P6 | Scripted sequence; wording in README |
| 41 Thermal / battery | P5 | 10-minute soak log |
| 42 Backup / reset / rehearsal | P5 | Three timed runs; video on two devices |
| 43 Pitch honesty | P6 | Sentences in the pitch script and README |
| 44 Laptop drift | P1, P5 | `HttpDetector` unselectable in the demo build |

## Sources

Local ground truth (HIGH):
- `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/androidMain/io/clika/runtime/ClikaRtAndroid.kt` — license placement, code names, `trimOnMemoryPressure`, `CLIKA_RT_NUM_THREADS`, `ANDROID_ID` binding note.
- `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/androidMain/io/clika/modelverse/ModelverseAndroid.kt` and `commonMain/io/clika/modelverse/Modelverse.kt` — idempotent `load`, `snapshot` offline semantics.
- `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/commonMain/io/clika/modelverse/VisionModel.kt` — CPU vision model (lines 10–18), blocking `open` (195–220), worker thread `modelverse-vision` (26–33, 183), `nativeDetect` signature without stride (236–246).
- `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/commonMain/io/clika/modelverse/Types.kt` — `SourceOptions`, `Rgba8888` byte order, `DetectOptions` defaults, `Capabilities.labels`, `Snapshot`.
- `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/commonMain/io/clika/modelverse/Pipelines.kt` — blocking `ObjectDetectionPipeline.invoke` (`Await`).
- `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/commonMain/io/clika/modelverse/Auto.kt` — `AutoModelForObjectDetection`, `AutoModelForZeroShotObjectDetection`.
- `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/commonMain/io/clika/runtime/Devices.kt` — `Backends` probe semantics and refusal code names.
- `C:/Users/C/clika/reference/examples-0.6.4/android/README.md`, `hello/build.gradle.kts`, `hello/.../MainActivity.kt`, `shared/{LicenseStore,SampleRuntime,RuntimeLane}.kt`, `serve/src/main/AndroidManifest.xml` — credential pattern, runtime lane, backend probe, foreground-service permissions.
- `C:/Users/C/clika/reference/examples-0.6.4/chat_with_pictures/src/shared/.../ModelverseEngine.kt` (`isLocal`, offline snapshot resolve), `Platform.android.kt`, `androidApp/.../MainActivity.kt` (`trimOnMemoryPressure`).
- `C:/Users/C/Downloads/ClikaRT_windows_x86_64-0.6.4/README.md` lines 75–99 (online vs offline license, code names), `README-Modelverse.md`.
- `clikart-cli --help`, `clikart-license-init --help`, `… PekingU/rtdetr_v2_r18vd detect --help`, `… google/owlv2-base-patch16-ensemble detect --help` (run today).
- Measurements today on this laptop (12 CPU threads): RT-DETR detect ≈ 1.5 s wall including load; OWLv2 detect 10.6 s wall (≈ 9.8 s inference), 0 detections at 0.3.
- `C:/Users/C/.gradle/caches/modules-2/files-2.1/io.clika/clika-runtime-android/0.6.4/…/clika-runtime.aar` — `unzip -l` (native lib sizes, `proguard.txt` consumer rules).
- `C:/Users/C/clika/models/models--PekingU--rtdetr_v2_r18vd/snapshots/…/{config.json,preprocessor_config.json}` — `id2label` spellings (`aeroplane`, `cell phone`, `tvmonitor`), 640×640; `models--google--owlv2-base-patch16-ensemble/snapshots/…/preprocessor_config.json` — 960×960.
- `C:/Users/C/clika/reference/modelverse-server-headers/serve_api.h` — `POST /v1/detections`, `/health`, `/dashboard`.
- `C:/Users/C/AndroidStudioProjects/FieldReport/{settings.gradle.kts,app/build.gradle.kts,gradle/libs.versions.toml,gradle/gradle-daemon-jvm.properties,app/src/main/AndroidManifest.xml}`.

Official documentation (HIGH–MEDIUM):
- CameraX analyze guide: https://developer.android.com/media/camera/camerax/analyze
- CameraX configuration (default 640×480, ViewPort/UseCaseGroup): https://developer.android.com/media/camera/camerax/configuration
- CameraX transform output (`getCorrectionMatrix`): https://developer.android.com/media/camera/camerax/transform-output
- CameraX ML Kit Analyzer: https://developer.android.com/media/camera/camerax/mlkitanalyzer
- CameraX releases (1.6.2 stable; 1.5.0-beta01 and 1.7.0-alpha01 fixes): https://developer.android.com/jetpack/androidx/releases/camera
- ML Kit barcode scanning (2 px/module, bundled 17.3.0, zoom suggestions): https://developers.google.com/ml-kit/vision/barcode-scanning/android ; release notes: https://developers.google.com/ml-kit/release-notes
- Network security config (cleartext default API 28+): https://developer.android.com/privacy-and-security/security-config ; `usesCleartextTraffic`: https://developer.android.com/guide/topics/manifest/application-element
- AGP 9.0 release notes (KGP 2.2.10, built-in Kotlin, newDsl): https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes ; migrate to built-in Kotlin: https://developer.android.com/build/migrate-to-built-in-kotlin ; current AGP page: https://developer.android.com/build/releases/gradle-plugin
- Kotlin JVM target consistency check: https://kotlinlang.org/docs/gradle-configure-project.html#check-for-jvm-target-compatibility-of-related-compile-tasks (MEDIUM, not fetched)
- Local unit tests and `returnDefaultValues`: https://developer.android.com/training/testing/local-tests
- Foreground service types (connectedDevice): https://developer.android.com/develop/background-work/services/fg-service-types
- Cached apps freezer (Android 14, 10 s): https://source.android.com/docs/core/perf/cached-apps-freezer
- Android 9 Crypto provider removal: https://developer.android.com/about/versions/pie/android-9.0-changes-all
- Android 8.0 `ANDROID_ID` scoping: https://developer.android.com/about/versions/oreo/android-8.0-changes
- Position sensors / compass calibration: https://developer.android.com/develop/sensors-and-location/sensors/sensors_position
- `URLConnection` timeouts (default 0 = infinite; MEDIUM, page body not retrievable via fetch): https://developer.android.com/reference/java/net/URLConnection
- Pixel airplane-mode Wi-Fi memory: https://support.google.com/pixelphone/answer/12639358
- OWLv2 model card (threshold 0.1): https://huggingface.co/google/owlv2-base-patch16-ensemble
- QR capacities: https://www.thonky.com/qr-code-tutorial/character-capacities ; module counts: https://www.qrcode.com/en/about/version.html
- Maven Central `maven-metadata.xml` (read 2026-10-10): ZXing core 3.5.4 (2025-11-11), NanoHTTPD 2.3.1 (2016-08-12, last release), OkHttp 5.5.0 (2026-08-16), org.json 20260814, kotlinx-serialization-json latest tag 1.12.0-RC (2026-09-04) with newest stable 1.11.0 (2026-04-09) — use 1.11.0 if JSON serialisation is ever added, kotlinx-coroutines-android 1.11.0 (2026-05-07; 1.10.2 is what the samples use), Ktor 3.6.0 (2026-09-16), KGP 2.2.10 (2025-08-13) and 2.3.0 (2025-12-16) both exist, newest stable KGP 2.4.21. Google Maven / androidx release notes: activity-compose 1.13.0 stable (2026-03-11), CameraX 1.6.2 stable (2026-08-26). ML Kit release notes: barcode-scanning 17.3.0 (2024-08-07) is still the latest bundled artifact. PyPI: fastapi 0.143.0 and uvicorn 0.51.0 exist (uvicorn latest 0.54.0).

Community sources (LOW–MEDIUM, flagged where used):
- RGBA row-stride "green lines" symptom: https://forum.deepar.ai/t/android-frameavailable-rgba-format-send-image-with-green-lines/84
- OWLv2 CPU slowness: https://discuss.huggingface.co/t/owlv2-significantly-slower-than-owlvit/73223 ; GPU encoder timing: https://forums.developer.nvidia.com/t/nanoowl-inference-takes-more-time-than-nanoowl-official-github-shows/293051
- Windows "We can't set up mobile hotspot": https://answers.microsoft.com/en-us/windows/forum/all/we-cant-set-up-mobile-hotspot/683338d1-3c66-43ed-8ae0-76626e183552 ; https://www.systweak.com/blogs/solved-we-cant-set-up-a-mobile-hotspot-error-on-windows-pc/
- Android Wi-Fi without internet / `bindProcessToNetwork`: https://android-developers.googleblog.com/2016/07/connecting-your-app-to-wi-fi-device.html ; https://canopas.com/android-connecting-your-app-to-specific-wi-fi-without-internet-connection-bae7850cef9a
- `adb` access to `Android/data` on Android 13+: https://forums.beamdog.com/discussion/comment/1200750/
- `MessageDigest.isEqual` constant time: https://jira.mongodb.org/browse/JAVA-1942 ; timing-attack background: https://codahale.com/a-lesson-in-timing-attacks
- Screen-to-screen QR scanning tips: https://support.google.com/googlenest/answer/9235302 ; https://support.simplisafe.com/articles/app-support/why-wont-my-qr-code-scan
- COCO detectors on UAVs (benchmarks fine-tune; generic classes are proxies): https://arxiv.org/pdf/2103.13933 ; https://arxiv.org/pdf/1706.05726

## Verification notes (2026-10-10)

Adversarial review of every concrete claim against the local ClikaRT 0.6.4 sources, the AAR, the real `clikart-cli.exe`, Maven Central / Google Maven / PyPI metadata, official Android docs, and PROJECT.md.

**Corrected in place**
- Line references into the ClikaRT sources: `VisionModel.open` is at lines 195–220 (not 245–262), `nativeDetect` at 236–246 (not 200–210), the worker thread name constant at line 183; hello `MainActivity.kt` shows `e.codeName` at line 99 (not 118–121); hello `build.gradle.kts` license block is lines 19–25 plus 37 (not 18–26). All other cited line ranges (`ClikaRtAndroid.kt` 41–44, 72–76, 89–104, 213–223, 234–252; `ModelverseAndroid.kt` 17–30; `Modelverse.kt` 51–73, 139–159; `Types.kt` 5–13, 247–252, 801–807, 833–845, 849–868, 885; `Pipelines.kt` 102–119, 296–317; `Devices.kt` 330–347, 361–378; `ModelverseEngine.kt` 120; `serve_api.h` 27) were confirmed within ±2 lines.
- Pitfall 10: the CameraX analyze page documents the RGBA byte sequence (alpha, red, green, blue in `planes[0]`) but not a row stride; the stride claim is now marked MEDIUM and the advice (honour `rowStride`) kept.
- Pitfall 21 and the ZXing row of Integration Gotchas: aligned with the PROJECT.md decision (raw bytes in QR byte mode, read with `Barcode.getRawBytes()`, which the ML Kit `Barcode` reference confirms; 37 bytes fits v3-M/v3-L) instead of recommending a base64url layout.
- Pitfall 22 and 28: added the PROJECT.md physical-layer gates (2.5 s round-trip budget → `FOE · late response`, two-frame optical persistence → `FOE · optical mismatch`) and the six demo buttons/verdicts (Impostor and Late were missing); checklist item "four buttons" → six.
- Pitfall 27: the file said the observer "needs no ±1-day search"; PROJECT.md's FOF-1 accepts yesterday/today/tomorrow. Reworded so the responder derives the day from the challenge `t` and the observer keeps the ±1-day acceptance.
- Pitfall 30 and its checklist/mapping rows: replaced the "100 m along heading0" cone-only demo layout with PROJECT.md's decided gate (range ≤ 3 km, near-field < 30 m or ±20° cone; Relay = 5 km fails the range gate).
- Pitfall 34 and the Hotspot gotcha row: "phone B or a third phone" → phone B hosts the hotspot (PROJECT.md: the RSSI gate requires the responder to be the AP); third phone / travel router are fallbacks that lose the RSSI gate.
- Pitfall 44: added the scrcpy USB mirror as the judges' primary live view and the dashboard as the first discretionary cut (PROJECT.md decision).
- Pitfall 32: `domain-config` vs IP statement downgraded to MEDIUM (the security-config page does not state it); cleartext default quote made exact.
- Pitfall 35: added Ktor CIO version 3.6.0 and NanoHTTPD's release date.
- Pitfall 36: added the AGP 9.2.0 release-notes compatibility table (KGP 2.2.10 min/default).
- Pitfall 39: the samples use Compose BOM 2026.02.00 (not 2026.02.01); noted coroutines 1.11.0 as current and activity-compose 1.13.0 as the current stable.
- Pitfall 40: the Pixel help page says the phone "will remember your previous setting"; it does not name Wi-Fi explicitly — quote corrected.
- Sources: kotlinx-serialization-json "1.12.0-RC, LOW" replaced by the verified stable 1.11.0; added verified dates for every library version.

**Confirmed as written (HIGH)**
ClikaRT: license semantics and code names (`LICENSE_FAILED`, `LICENSE_EXPIRED`, `LICENSE_SERVER_UNREACHABLE`), `--version`/`list` needing no license, idempotent `Modelverse.load`, `placeLicense` log lines, `ANDROID_ID` binding note, `trimOnMemoryPressure` semantics, `CLIKA_RT_NUM_THREADS`, `SourceOptions` local-source rule, `Snapshot.loadPath` offline note, `Rgba8888` byte order, `DetectOptions` defaults (0.5 / 100), `Capabilities.labels`, `Detection.labelId: Long`, `Task.OBJECT_DETECTION = "object-detection"`, `AutoModelForObjectDetection.fromPretrained(source, VisionLoadOptions)`, `Backends.isAvailable`/`isLoaded` semantics and the three `BACKEND_*` code names, `RuntimeLane` (`clika-runtime-lane` single thread), `SampleRuntime.backends()`, hello/chat/serve `ndk { abiFilters += "arm64-v8a" }` and `buildConfig = true`, serve sample manifest permissions, AAR minSdk 28 / arm64-v8a only / native sizes (99.7 + 139.3 + 19.1 + 11.9 + 14.3 MB = 284 MB) / `proguard.txt` 9222 bytes with the quoted keep rule; CLI `--offline`, `--cache-dir` global options, `--device auto` fallback wording, `detect --confidence` default 0.5 and `--max-detections` 100, `serve` default host 127.0.0.1 and port 8000, `rt-detr` and `owlvit` families runnable, `clikart-license-init` reports no credential kind; `id2label` spellings (`aeroplane`, `motorbike`, `tvmonitor`, `cell phone`; 80 labels), RT-DETR 640×640, OWLv2 960×960 with `do_pad: true`, snapshot files and sizes (80.9 MB / 619.9 MB), `refs/main` present. Project files: Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, toolchainVersion 21, foojay plugin applied, `FAIL_ON_PROJECT_REPOS`, local Maven repo in settings, CameraX 1.4.1 and ML Kit 17.3.0 declared, `INTERNET` permission absent, Java 11 `compileOptions`, `isMinifyEnabled = false`, the stale `activityCompose`/`coreKtx`/`lifecycleRuntimeKtx` versions. Official docs: CameraX default 640×480 and 4:3, `FILL_CENTER` default, ViewPort/UseCaseGroup quote, `getCorrectionMatrix`, `STRATEGY_KEEP_ONLY_LATEST` as the non-blocking default, `ImageProxy.close()`, 1.7.0-alpha01 rotation fix and 1.5.0-beta01 MlKitAnalyzer fix (quotes exact), ML Kit 2 px/module and 1280×720/1920×1080 recommendation, bundled model "statically linked", cleartext default API 28+, `usesCleartextTraffic` best-effort and ignored on API 24+ with an NSC present, AGP 9 built-in Kotlin error messages, KGP 2.2.10 runtime dependency, `jvmTarget` default from `targetCompatibility`, `onlyEnableUnitTestForTheTestedBuildType`, `returnDefaultValues` caution, Crypto provider removal, `ANDROID_ID` scoping, 10 s cached-apps freezer on Android 14+, `connectedDevice` permission list, OWLv2 model card `threshold=0.1`, QR byte capacities v3/v4/v5.

**Still unverified**
- Timing measurements on this laptop (RT-DETR 0.4 s load, OWLv2 9.8 s/frame, stop sign 0.61) — reported by the researcher, not re-run here (MEDIUM).
- CameraX `planes[0].rowStride > width*4` on real devices for RGBA output, and the R,G,B,A vs A,R,G,B ordering in practice — community/inference only; the H2 frame-dump test decides (MEDIUM).
- `ImageAnalysis.Builder.setOutputImageRotationEnabled` behaviour on 1.4.x–1.6.x beyond the 1.7.0-alpha01 note — not fetched from the reference page (MEDIUM).
- Kotlin JVM-target consistency check wording and whether `jvmToolchain(17)` triggers a foojay download on this laptop — not fetched (MEDIUM).
- `adb push` into `Android/data` on Android 13+, Windows Mobile Hotspot uplink requirement, airplane-mode hotspot behaviour per OEM, Wi-Fi-without-internet handling — community only (LOW), test at H0.
- The AGP 9.2.0 release notes list a fixed issue "Update Kotlin Gradle plugin dependency to 2.3.10" while their compatibility table says 2.2.10; the project builds with 2.2.10 today, so the pitfall stands, but the exact KGP that AGP 9.2.1 bundles was not confirmed (MEDIUM).
- `MessageDigest.isEqual` constant-time since 6u17 — community source only (MEDIUM); the file's own `ctEquals` avoids the question.
- Phone-specific hFOV (70°) and the QR distance table — computed, MEDIUM, confirmed only by the H4 distance test.

---
*Pitfalls research for: FriendOrFoe — offline on-device drone detection + phone-to-phone QR IFF, hackathon demo on connected Android devices*
*Researched: 2026-10-10; adversarially verified 2026-10-10*
