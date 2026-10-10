# Stack Research

**Domain:** Offline, on-device Android drone detection (ClikaRT) + optical friend-or-foe (IFF) check; observer phone A, responder phone B, laptop optional
**Researched:** 2026-10-10
**Confidence:** HIGH overall. Every Android/Kotlin recommendation below was compiled together in a throwaway Gradle project (AGP 9.2.1, built-in Kotlin 2.2.10, Gradle 9.4.1): `:app:compileDebugKotlin` against the real ClikaRT AAR + every library listed, and `:iff:test` (JUnit 4, HMAC RFC 4231 vector + JSON round trip) passed (`BUILD SUCCESSFUL`). The laptop `clikart-cli serve` request/response shapes were captured from a live server, not from docs.

**Scope correction (user, 2026-10-10):** the judges watch the two connected phones. Detection runs on phone A; the drone-plus-IFF scheme (phone B as the drone) is the thing being built. The laptop is a side-car: eval table and the SC1 "second device type" bonus. Nothing on the IFF path depends on it. PROJECT.md's cut order is audio alert → OWLv2 option → Relay button → eval table; the dashboard is discretionary (scrcpy over USB is the judges' primary live view per PROJECT.md), so it is the first laptop piece to skip, while the `clikart-cli serve` eval backend stays until the eval table itself is cut.

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| ClikaRT Kotlin binding `io.clika:clika-runtime` | 0.6.4 (AAR variant) | On-device RT-DETR v2 detection, in-process | SC1 hard requirement. The one AAR carries libClikaRT, the Vulkan backend, the model library and both JNI bridges (`examples-0.6.4/android/README.md` lines 33-38); verified in the Gradle cache: `jni/arm64-v8a/` = 5 `.so`, 271 MiB (284 MB). Resolves from the local Maven repo `C:/Users/C/clika` already in `settings.gradle.kts`. |
| Android Gradle Plugin (built-in Kotlin) | 9.2.1 / Kotlin 2.2.10 / Gradle 9.4.1 | Build | Already generated; do not touch. AGP 9 bundles KGP 2.2.10 and forbids `org.jetbrains.kotlin.android` ([AGP 9.0 notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes)). |
| Jetpack Compose (BOM 2026.02.01) + `camera-view` PreviewView in `AndroidView` | as declared | UI, overlay rings, responder QR screen | Already in the template; Compose `Canvas` draws the rings over a `PreviewView`. |
| CameraX | **1.6.2** (replace 1.4.1) | Preview + ImageAnalysis RGBA_8888 frames | 1.6.2 is the current stable (Google Maven metadata; release notes: Aug 26 2026). Same API as 1.4; 1.5 raised minSdk to 23 and needs compileSdk 35+, both already satisfied (minSdk 28 / compileSdk 36). Compile-verified incl. `ImageProxy.toBitmap()` and `ResolutionSelector`. |
| ML Kit Barcode Scanning (bundled) | 17.3.0 | Decode the QR answer inside the detected box; read `Barcode.rawBytes` (byte-mode payload, PROJECT.md decision), not `rawValue` | Latest version on Google Maven (Aug 7 2024 release note: 16 KB page support); the bundled artifact ships the model in the APK (+2.4 MB) so no Play download at demo time ([ML Kit docs](https://developers.google.com/ml-kit/vision/barcode-scanning/android)). See the offline caveat in section 3. |
| ZXing core | **3.5.4** | Encode the QR on phone B (and decode fallback) | Latest on Maven Central; pure Java, no transitive deps, 500 KB. |
| NanoHTTPD | 2.3.1 | Phone B's one HTTP endpoint (`POST /challenge`) | One class, pure Java 7, zero deps, latest release on Maven Central; `com.sun.net.httpserver` does not exist on Android. |
| OkHttp | **5.5.0** | Phone A → phone B challenge, phone A → laptop events, `HttpDetector` multipart | Latest stable on Maven Central; built with Kotlin 2.1.21 + okio 3.18.1 (POM), so it is readable by the 2.2.10 compiler. Non-null `response.body` in 5.x. Every one of these calls is plain `http://` to a LAN IP: with targetSdk 28+ Android blocks cleartext by default, so the manifest needs `android:usesCleartextTraffic="true"` (see the manifest section) or OkHttp throws `UnknownServiceException: CLEARTEXT communication to <ip> not permitted`. |
| kotlinx-serialization-json + plugin | **1.9.0** / plugin 2.2.10 | JSON for IFF messages and test vectors, inside the pure-Kotlin `iff` module | 1.9.0 is the release built with Kotlin 2.2.0 (release notes), the exact match for the 2.2.10 compiler; the plugin version must equal the Kotlin version. `org.json` is not available in plain-JVM unit tests. |
| kotlinx-coroutines-android | 1.10.2 | Runtime lane dispatcher, IO calls | The version the official ClikaRT Android samples pair with this AAR (`examples-0.6.4/android/shared/build.gradle.kts` line 32). |
| JUnit 4 | 4.13.2 | `iff` unit tests | Already in the TOML; JUnit 5 on Gradle 9 additionally needs `junit-platform-launcher` wiring. |
| `javax.crypto` / `java.security` / `java.util.Base64` | JDK built-ins | HMAC-SHA256, nonces, constant-time compare, base64url | Zero libraries; identical on Android (API 26+) and the JVM test runner. |
| Python (laptop, optional) | 3.13.3 at `C:\Python313\python.exe` with fastapi 0.143.0, uvicorn 0.51.0, requests 2.32.5, httpx 0.28.1, pillow 12.1.1, python-multipart 0.0.32 already installed (user site) | Dashboard + `eval/run_eval.py` | Nothing to install. Use plain `python`, not `python -I` (isolated mode hides the user site-packages). |
| `clikart-cli` 0.6.4 (laptop, optional) | `C:/Users/C/Downloads/ClikaRT_windows_x86_64-0.6.4/bin/clikart-cli.exe` | Second ClikaRT device type + eval backend | Verified live: `serve` answers `POST /v1/detections` for RT-DETR and OWLv2. |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-view` | 1.6.2 | Camera backend, lifecycle binding, PreviewView | Always (observer). `camera-core` comes transitively. |
| `com.google.zxing:core` (decode side: `QRCodeReader`, `RGBLuminanceSource`) | 3.5.4 | Fallback QR decoder | Only if ML Kit returns nothing in airplane mode on the test phone. |
| `org.json` (Android platform) | platform | App-side JSON for third-party shapes (`/v1/detections` response, dashboard events) | App module only, never in `iff`. |
| `android.speech.tts.TextToSpeech`, `ToneGenerator` | platform | Audio alert (stretch) | Needs the `<queries>` entry for API 30+. |
| `LocationManager`, `SensorManager` | platform | GPS fix, compass bearing for the position cone | No Play Services; see section 8. |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| `adb` (Android SDK platform-tools) | Install the ~300 MB APK over USB, push the model snapshot, logcat | `adb logcat -s ClikaRT` follows the runtime's own lines once `ClikaRtAndroid.bridgeLogs()` ran (`examples-0.6.4/android/README.md` line 63). |
| `clikart-cli` | Fetch/serve/detect on the laptop | Prepend `C:/Users/C/Downloads/ClikaRT_windows_x86_64-0.6.4/bin` to PATH (the DLLs sit beside the exe). Global flags `--offline --cache-dir` go BEFORE the model source. |
| Gradle 9.4.1 wrapper, JDK 21 daemon toolchain (`gradle/gradle-daemon-jvm.properties`) | Build | JDK 21 compiles the Java-11 targets fine (verified). |
| `python` 3.13 + uvicorn | Dashboard | `python -m uvicorn dashboard.app:app --host 0.0.0.0 --port 8000`; open Windows Firewall for 8000 and 8129. |

## Installation

### `gradle/libs.versions.toml` (append)

```toml
[versions]
clikaRt = "0.6.4"
camerax = "1.6.2"
mlkitBarcode = "17.3.0"
zxing = "3.5.4"
nanohttpd = "2.3.1"
okhttp = "5.5.0"
kotlinxSerialization = "1.9.0"
coroutines = "1.10.2"

[libraries]
clika-runtime = { group = "io.clika", name = "clika-runtime", version.ref = "clikaRt" }
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
androidx-camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
mlkit-barcode-scanning = { group = "com.google.mlkit", name = "barcode-scanning", version.ref = "mlkitBarcode" }
zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }
nanohttpd = { group = "org.nanohttpd", name = "nanohttpd", version.ref = "nanohttpd" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

(`kotlin = "2.2.10"` already exists and must stay equal to AGP's bundled KGP; the two new plugins reuse it.)

### `settings.gradle.kts`

Only one line changes: `include(":app")` becomes `include(":app", ":iff")`. The `maven(url = uri("C:/Users/C/clika"))` repository is already there and is what resolves `io.clika:clika-runtime:0.6.4` (the `.module` file redirects the Android variant to `clika-runtime-android-0.6.4.aar`, 135 MB compressed).

### Root `build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false            // new: pure-JVM iff module
    alias(libs.plugins.kotlin.serialization) apply false  // new: JSON in iff
}
```

Declaring the version once here (and never in a module) avoids Gradle's "plugin already on the classpath" error, because AGP 9 already puts KGP 2.2.10 on the build classpath.

### `app/build.gradle.kts` (deltas against the current file)

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Official pattern: examples-0.6.4/android/hello/build.gradle.kts lines 19-25 and 37.
// local.properties (gitignored):  clika.runtimeLicenseFile=C:/Users/C/.clika/runtime/license
val buildTimeLicense: String = run {
    val properties = Properties()
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { properties.load(it) }
    val path = properties.getProperty("clika.runtimeLicenseFile")?.trim().orEmpty()
    if (path.isEmpty()) "" else file(path).takeIf { it.exists() }?.readLines()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

android {
    // ... namespace / compileSdk / minSdk 28 / targetSdk 36 unchanged ...
    defaultConfig {
        // ...
        ndk { abiFilters += "arm64-v8a" }   // the AAR is arm64-only; also drops ML Kit's x86/armv7 .so from the APK
        buildConfigField("String", "CLIKA_RT_LICENSE", "\"" + buildTimeLicense.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true                   // buildConfigField needs it (AGP 8+ default is false)
    }
    // packaging { jniLibs { useLegacyPackaging = ... } }  -> leave UNSET (see section 1, packaging)
}

dependencies {
    // existing Compose/AndroidX lines unchanged, then:
    implementation(project(":iff"))
    implementation(libs.clika.runtime)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    implementation(libs.nanohttpd)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
```

Remove the four hard-coded `implementation("...")` lines that are in the file today (ClikaRT, CameraX 1.4.1 x3, ML Kit) in favour of the aliases above.

### `iff/build.gradle.kts` (new module, exactly as verified)

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
```

Sources in `iff/src/main/kotlin/...` and `iff/src/test/kotlin/...`; test vectors as `iff/src/test/resources/vectors/*.json`. `./gradlew :iff:test` runs on the laptop with no device.

### `app/src/main/AndroidManifest.xml` additions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />   <!-- RSSI gate, section 8 -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" /> <!-- ConnectivityManager callbacks -->
<uses-feature android:name="android.hardware.camera.any" />

<!-- API 30+ package visibility: without this TextToSpeech finds no engine
     (TextToSpeech.Engine docs: "Apps targeting Android 11 that use text-to-speech should declare
      INTENT_ACTION_TTS_SERVICE in the queries elements of their manifest") -->
<queries>
    <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
</queries>

<application
    android:name=".FofApplication"      <!-- onTrimMemory -> ClikaRtAndroid.trimOnMemoryPressure(this), as hello/HelloApplication.kt -->
    android:largeHeap="true"            <!-- as examples-0.6.4/chat_with_pictures androidApp manifest -->
    android:usesCleartextTraffic="true" <!-- REQUIRED: every link is http:// to a LAN IP; Android 9+ (API 28) refuses cleartext
                                             by default (network-security-config docs: base-config cleartextTrafficPermitted="false").
                                             Without it OkHttp fails with "CLEARTEXT communication to 192.168.x.x not permitted". -->
    ... />
```

## 1. ClikaRT 0.6.4 on Android

All claims cite `C:/Users/C/clika/reference/...` files. Confidence HIGH unless marked.

**Artifact facts (AAR unzipped from the Gradle cache).** `AndroidManifest.xml` declares only `<uses-sdk android:minSdkVersion="28" />` (hence minSdk 28). `jni/arm64-v8a/`: `libClikaRT.so` 95.1 MiB (99,683,136 B), `libClikaRT_vulkan.so` 132.8 MiB (139,271,256 B), `libClikaRT_modelverse.so` 18.2 MiB, `libclika_modelverse_jni.so` 11.4 MiB, `libclika_rt_jni.so` 13.7 MiB (271 MiB = 284 MB total; PROJECT.md quotes the same files in decimal MB; the APK is ~300 MB). `classes.jar` 0.9 MB. The AAR ships `proguard.txt` consumer rules (keeps for the JNI-bound classes), so no app-side ProGuard work; keep `isMinifyEnabled = false` anyway. The Gradle module metadata requires `kotlin-stdlib 2.3.0` (`clika-runtime-android-0.6.4.module`), which bumps the app's stdlib to 2.3.0 while the compiler is 2.2.10; Kotlin reads metadata one minor version ahead and the probe compiled against `DetectOptions`, `VisionLoadOptions`, `AutoModelForObjectDetection`, etc. without error. Do not pin stdlib down to 2.2.10.

**Native-lib packaging.** Leave `packaging.jniLibs.useLegacyPackaging` unset. Since AGP 3.6 the default stores `.so` files uncompressed and sets `extractNativeLibs=false`, so the 277 MB are mmapped straight from the APK: fastest install, no second copy on disk ([AGP 3.6 notes](https://developer.android.com/build/releases/agp-3-6-0-release-notes), [AGP 4.2 notes](https://developer.android.com/build/releases/agp-4-2-0-release-notes)). `useLegacyPackaging = true` would compress them (smaller APK) but extract 277 MB at install (slower install, ~2x disk) with no benefit for USB sideloading. All five `.so` have `LOAD` segments aligned to 16 KB or more (ELF program headers read locally: `p_align=0x4000`; `libClikaRT_modelverse.so` also carries one `0x10000` segment), so 16 KB-page phones are fine. Install only over USB (`adb install -r`, 1-2 min); no emulator (x86 has no libs).

**License.** Every process that runs a model needs the `CLIKA1-...` credential; only phone A (observer) loads ClikaRT, phone B in Responder mode never calls `Modelverse.load`, so only one phone needs the credential. Build-time embedding via `BuildConfig.CLIKA_RT_LICENSE` from `local.properties` (`clika.runtimeLicenseFile=<absolute path>`) is the official pattern (`hello/build.gradle.kts` lines 14-25, 37), with a paste-once fallback stored in private storage (`shared/LicenseStore.kt`, `shared/CredentialScreen.kt`, ~70 lines to copy). Refusal code names to branch on, never on message text: `LICENSE_FAILED`, `LICENSE_EXPIRED`, `LICENSE_SERVER_UNREACHABLE` (`androidMain/io/clika/runtime/ClikaRtAndroid.kt` lines 73-76). The runtime binds the license to `Settings.Secure.ANDROID_ID` via `attachApp` (same file, lines 214-223). Verify in airplane mode on phone A at H1 that the credential works without the license server; this is the single biggest demo risk and it is not a stack choice.

**Bring-up (one call, on the runtime lane).** `Modelverse.load(context, license)` is the Android extension (`androidMain/io/clika/modelverse/ModelverseAndroid.kt`): it pins `XDG_CACHE_HOME` to the app cache dir, sets `CLIKA_RT_LICENSE`, loads libClikaRT -> modelverse -> JNI bridge in order, idempotent. Then `ClikaRtAndroid.bridgeLogs()` (logcat tag `ClikaRT`) and `ClikaRtAndroid.trimOnMemoryPressure(context)` (`ClikaRtAndroid.kt` lines 100-104, 171-178). `CLIKA_RT_NUM_THREADS` is read at the first compute, so set it with `Os.setenv(...)` before load; a phone "runs best at the large cores' count; measure" (`ClikaRtAndroid.kt` lines 41-45).

**Runtime-lane threading rule.** One single-thread executor runs every load, detect and close, never the main thread (`shared/RuntimeLane.kt`; `VisionModel.open` "Blocks for the load; call it off the main thread", `commonMain/io/clika/modelverse/VisionModel.kt` lines 192-200). A pipeline's `invoke` blocks the caller until the model answers (`Pipelines.kt` lines 5-17); listener callbacks arrive on the model's own worker thread `modelverse-vision`, one request at a time per model (`VisionModel.kt` lines 10-18, 183; `Listeners.kt` lines 107-131). Use the lane's executor as the CameraX analysis executor too (section 2), so the frame copy, the blocking detect and the ~1 fps pacing all live on one thread.

**Loading a snapshot directory, offline.** Push the hub snapshot (3 files: `config.json`, `model.safetensors` 80,904,640 bytes, `preprocessor_config.json`) and pass its absolute path as `source`. `VisionLoadOptions(modelArgs, revision, cacheDir, token, offline, device, weights, dtype)` (`Types.kt` lines 831-845); "a local source ignores the hub knobs" (`Types.kt` lines 5-14), so `offline = true` is belt-and-braces and `cacheDir` is unnecessary for a directory source. The alternative, hub-id source + `cacheDir` pointing at a pushed copy of the whole `models--PekingU--rtdetr_v2_r18vd` tree + `offline = true`, also works (`Modelverse.snapshot` doc, `Modelverse.kt` lines 139-153) but pushes more files for nothing.

**Which loader.** `AutoModelForObjectDetection.fromPretrained(source, VisionLoadOptions)` and `AutoModelForZeroShotObjectDetection.fromPretrained(...)` are the same call: both are `VisionModel.open(source, options)` (`Auto.kt` lines 282-294); the checkpoint decides closed-set (RT-DETR, `Capabilities.labels` = COCO table, `openVocabulary = false`) vs open-vocabulary (OWLv2, `openVocabulary = true`, labels come from the request) (`Types.kt` lines 240-253). `Modelverse.pipeline("object-detection", source, LoadOptions(offline = true, device = "cpu"))` opens the model and wraps it in one call (`Modelverse.kt` lines 161-195; note it takes `LoadOptions`, converted internally by `AutoModel.fromPretrained`, `Auto.kt` lines 218-227); `"zero-shot-object-detection"` is an alias of the same door (`Pipelines.kt` line 77). `ObjectDetectionPipeline`'s constructor is `internal` (`Pipelines.kt` lines 297-300), so `ObjectDetectionPipeline(model)` does not compile; wrap an existing model with `Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline` (`Modelverse.kt` lines 197-202). Recommendation: load with `AutoModelForObjectDetection` (keeps `model.capabilities` in hand for the label table and `visionTasks` check), wrap with `Modelverse.pipeline(task, model)`.

**DetectOptions / results.** `DetectOptions(maxResults = 100, scoreThreshold = 0.5f, labels = emptyList())`; labels are REQUIRED for an open-vocabulary detector and REFUSED on a closed-set one (`Types.kt` lines 847-869); the pipeline's `candidateLabels` parameter overrides `options.labels` (`Pipelines.kt` lines 301-307). Result: `List<Detection(label, labelId, score, Box(left, top, right, bottom))>`, pixels of the input picture, top-left origin, score-descending (`Types.kt` lines 877-885). RT-DETR is NMS-free: at low thresholds the same box can appear under several labels (seen live at 0.05); at 0.4-0.5 this does not happen. COCO spellings in this checkpoint's table (`config.json` `id2label`, 80 entries, re-read 2026-10-10; the same list `/props` and `Capabilities.labels` expose): `0 person`, `3 motorbike`, `4 aeroplane` (not `airplane`), `14 bird`, `33 kite`, `67 cell phone`. Per PROJECT.md's decision, key the class map on `Detection.labelId` (a `Long`): `0L -> person`, `{4L, 14L, 33L} -> drone`; never on the label strings, which are VOC-style here and plain COCO elsewhere.

**ImageInput.** `ImageInput.Rgba8888(pixels, width, height)` expects "the pixels of an `ARGB_8888` bitmap as `Bitmap.copyPixelsToBuffer` writes them: four bytes per pixel in the order red, green, blue, alpha, row by row" (`Types.kt` lines 800-807); `ImageInput.Encoded(bytes)` takes PNG/JPEG bytes (line 810) and is the input for `HttpDetector`-style or eval paths. Tightly packed rows only, so never hand over a CameraX plane buffer with `rowStride > width*4` directly; go through a Bitmap (section 2).

**Device.** Spellings are `"cpu"` and `"vulkan:0"` (`Types.kt` lines 11-12; `Devices.kt` lines 184-190). Use `"cpu"`. Vulkan is compiled into the AAR (132 MB) but whether a given phone's driver is accepted is a runtime fact: probe with `Backends.deviceStatus(ComputeApi.VULKAN)` which returns the refusal as a value (`Devices.kt` lines 330-412; the hello sample prints it). Treat `"vulkan:0"` as a timed experiment after H4 with `catch (e: ModelverseException)` fallback to CPU. `dtype = "float16"` halves weight memory at load (`Types.kt` lines 31-40); leave null on CPU. Confidence on phone-side speed: LOW until measured; RT-DETR r18 (20 M params, 640x640) on a modern big core cluster is expected around 0.3-1.5 s/frame on CPU, which fits the 1 fps target. The report of each request (`VisionReport.latencyMs`, `accelerator`, `Types.kt` lines 897-905) is available through the listener API if you need numbers for the pitch.

**OWLv2 on the phone: not the default.** The snapshot is 620 MB fp32 (`C:/Users/C/clika/models/models--google--owlv2-base-patch16-ensemble/snapshots/cfd3195.../model.safetensors`), 8 files incl. tokenizer. On the laptop (12 CPU threads) one 640x480 request took ~5 s wall including the one-off prompt encode. On a phone CPU expect many seconds per frame (LOW confidence, unmeasured). Keep it as the laptop-side eval candidate and, if at all, a `dtype = "float16"` experiment after H14.

**Detector (verified-compiling shape; mirror of `Probe.kt` in the scratch build):**

```kotlin
// app: com.chloeyeo.friendorfoe.detect.ClikaRtDetector
import android.content.Context
import android.system.Os
import io.clika.modelverse.AutoModelForObjectDetection
import io.clika.modelverse.DetectOptions
import io.clika.modelverse.Detection
import io.clika.modelverse.ImageInput
import io.clika.modelverse.Modelverse
import io.clika.modelverse.ObjectDetectionPipeline
import io.clika.modelverse.Task
import io.clika.modelverse.VisionLoadOptions
import io.clika.modelverse.VisionTask
import io.clika.modelverse.load                 // Android extension Modelverse.load(context, license)
import io.clika.runtime.ClikaRtAndroid

interface Detector {
    fun detect(image: ImageInput, scoreThreshold: Float): List<Detection>
}

class ClikaRtDetector(private val candidateLabels: List<String> = emptyList()) : Detector {
    private lateinit var pipeline: ObjectDetectionPipeline
    lateinit var labelTable: List<String>; private set

    /** Blocks for seconds. Call on RuntimeLane only. */
    fun load(context: Context, license: String, snapshotDir: String, device: String = "cpu") {
        Os.setenv("CLIKA_RT_NUM_THREADS", "4", true)          // before load; tune to the big-core count
        Modelverse.load(context.applicationContext, license)   // idempotent
        ClikaRtAndroid.bridgeLogs()                            // adb logcat -s ClikaRT
        ClikaRtAndroid.trimOnMemoryPressure(context)
        val model = AutoModelForObjectDetection.fromPretrained(
            snapshotDir, VisionLoadOptions(offline = true, device = device),
        )
        check(VisionTask.DETECT in model.capabilities.visionTasks) { "not a detector: ${model.config.family}" }
        labelTable = model.capabilities.labels                 // COCO names for RT-DETR; empty for OWLv2
        pipeline = Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline
    }

    override fun detect(image: ImageInput, scoreThreshold: Float): List<Detection> =
        pipeline(image, DetectOptions(maxResults = 20, scoreThreshold = scoreThreshold), candidateLabels)

    fun close() = pipeline.close()                             // closes the model too (Pipelines.kt line 26)
}

object RuntimeLane {
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "clika-runtime-lane") }
    val dispatcher = executor.asCoroutineDispatcher()                      // import kotlinx.coroutines.asCoroutineDispatcher
}
```

Snapshot path in the app: `File(context.getExternalFilesDir(null), "models/rtdetr_v2_r18vd").absolutePath`. Errors: `ModelverseException` (`codeName`) and `ClikaRtException` (`codeName`), both `RuntimeException`s; `ClikaRtException.codeName` carries `LICENSE_*` (`commonMain/io/clika/runtime/ClikaRt.kt` lines 25-39).

## 2. CameraX 1.6.2: Preview + ImageAnalysis -> `ImageInput.Rgba8888`

Facts verified from the androidx sources: `OUTPUT_IMAGE_FORMAT_RGBA_8888` is "a single-plane with the order of R, G, B, A in increasing byte index"; `STRATEGY_KEEP_ONLY_LATEST` delivers one image at a time and drops the rest until `ImageProxy.close()`; `setOutputImageRotationEnabled(true)` rotates the output so `getRotationDegrees()` returns 0, API 23+, ~10-15 ms per 640x480 frame, default off (`ImageAnalysis.java` Javadoc). `ImageProxy.toBitmap()` for RGBA_8888 uses `ImageProcessingUtil.copyByteBufferToBitmap(bitmap, buffer, rowStride)`, i.e. it is row-stride aware (`camera-core/.../ImageUtil.java`). `setTargetResolution` is deprecated; use `ResolutionSelector`.

Recommendations:

- Resolution: `1280x720` (ML Kit's guidance is 1280x720 or 1920x1080, ~2 MP max). The RT-DETR preprocessor resizes to 640x640 internally, so a bigger frame costs only the copy; the QR read needs pixels: a 7 cm QR (version 3, 29x29 modules, for the ~40-byte raw payload; section 3) seen at 1 m with a ~70 deg lens is ~64 px wide at 1280 (about 2.2 px/module, just above ML Kit's 2 px floor). Demo the QR read at 0.5-0.7 m, or go to `1920x1080` if the phone copes.
- Rotation: rotate the Bitmap yourself with the `rotationDegrees` from `imageInfo` (3 lines, deterministic across CameraX versions) rather than `setOutputImageRotationEnabled`; boxes then come back in upright-frame pixels.
- Throttle: do NOT return early and `close()` to skip frames (CameraX would still convert ~30 fps to RGBA). Hold the `ImageProxy` until the one-second tick, then close; with `KEEP_ONLY_LATEST` nothing is converted or queued meanwhile.
- Preview: `PreviewView` inside `AndroidView`, `scaleType = FIT_CENTER`, same 16:9 aspect for Preview and ImageAnalysis (`AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY`), so box -> screen is one uniform scale plus a letterbox offset: `s = min(viewW / frameW, viewH / frameH)`, `dx = (viewW - frameW*s)/2`, `dy = (viewH - frameH*s)/2`. Skip `camera-compose` (`CameraXViewfinder`): more plumbing, no gain here.

```kotlin
val analysis = ImageAnalysis.Builder()
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build(),
    )
    .build()

analysis.setAnalyzer(RuntimeLane.executor) { proxy ->                 // same thread as load/detect
    val started = SystemClock.elapsedRealtime()
    try {
        val raw = proxy.toBitmap()                                      // RGBA_8888 -> ARGB_8888, stride-aware
        val rot = proxy.imageInfo.rotationDegrees
        val upright = if (rot == 0) raw else Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val bytes = ByteArray(upright.width * upright.height * 4)
        upright.copyPixelsToBuffer(ByteBuffer.wrap(bytes))              // RGBA order, exactly what Rgba8888 wants
        val detections = detector.detect(ImageInput.Rgba8888(bytes, upright.width, upright.height), 0.4f)
        onFrame(upright, detections)                                     // post to the UI state; keep `upright` for the QR crop
    } finally {
        val remaining = 1000L - (SystemClock.elapsedRealtime() - started)
        if (remaining > 0) SystemClock.sleep(remaining)                 // ~1 fps; frames are dropped while the proxy is open
        proxy.close()
    }
}
cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
```

## 3. QR: ML Kit decode on phone A, ZXing encode on phone B

**Decode (phone A), ML Kit `barcode-scanning:17.3.0`.** Crop the challenged box (expand 15 %) from the upright frame Bitmap and scan only that crop, which is what makes "read from inside the box" an attribution check rather than a convenience:

```kotlin
private val scanner = BarcodeScanning.getClient(
    BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())

fun decodeInside(frame: Bitmap, box: Box, onResult: (String?) -> Unit) {
    val mw = 0.15f * (box.right - box.left); val mh = 0.15f * (box.bottom - box.top)
    val l = (box.left - mw).toInt().coerceIn(0, frame.width - 1)
    val t = (box.top - mh).toInt().coerceIn(0, frame.height - 1)
    val r = (box.right + mw).toInt().coerceIn(l + 1, frame.width)
    val b = (box.bottom + mh).toInt().coerceIn(t + 1, frame.height)
    val crop = Bitmap.createBitmap(frame, l, t, r - l, b - t)
    scanner.process(InputImage.fromBitmap(crop, 0))                     // Task; callbacks on the main thread
        .addOnSuccessListener { codes -> onResult(codes.firstOrNull()?.rawBytes) }   // ByteArray?: the raw FOF-1 response
        .addOnFailureListener { onResult(null) }
}
// signature: fun decodeInside(frame: Bitmap, box: Box, onResult: (ByteArray?) -> Unit)
```

PROJECT.md decision (2026-10-10): the QR carries the raw ~40-byte response in QR byte mode and the observer reads `Barcode.getRawBytes()`, because base64url text (54 mixed-case chars) does not fit QR version 3 byte mode (3-M holds 42 bytes, 3-L 53) while the raw bytes fit version 3 at either ECC level. `rawValue` would try to decode the bytes as text and may mangle them; `rawBytes` is the field to use.

Offline caveat (MEDIUM): the 17.3.0 POM declares `com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1` and `play-services-basement:18.4.0` as dependencies, so the bundled SDK is no longer Play-free in the dependency graph. The bundled model still ships in the APK (ML Kit docs), and the release notes for 17.3.0 only mention 16 KB page support; nothing documents a delegation to Play. Test the scan once in airplane mode at H10. If it returns nothing, switch to the ZXing decoder below (same crop, 6 lines, no Play anywhere):

```kotlin
fun zxingDecode(crop: Bitmap): ByteArray? {
    val px = IntArray(crop.width * crop.height); crop.getPixels(px, 0, crop.width, 0, 0, crop.width, crop.height)
    val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(crop.width, crop.height, px)))
    return try {
        val r = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))
        // Result.rawBytes still carries the mode/length bit headers; the payload bytes are the byte segments.
        @Suppress("UNCHECKED_CAST")
        (r.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>)?.firstOrNull()
            ?: r.text.toByteArray(Charsets.ISO_8859_1)
    } catch (e: ReaderException) { null }
}
```

**Encode (phone B), ZXing `core:3.5.4`, byte mode with the raw bytes:**

```kotlin
fun qrBitmap(payload: ByteArray, sizePx: Int): Bitmap {
    // ISO-8859-1 maps every byte 0..255 to one char, so ZXing emits exactly these bytes in byte mode.
    val text = String(payload, Charsets.ISO_8859_1)
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        EncodeHintType.MARGIN to 1,
    )
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val px = IntArray(sizePx * sizePx) { i -> if (m.get(i % sizePx, i / sizePx)) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(px, sizePx, sizePx, Bitmap.Config.RGB_565)
}
```

Payload (PROJECT.md FOF-1 + the raw-bytes decision): the ~40-byte binary response goes in as is -> QR version 3 (29x29 modules; 3-M holds 42 bytes, 3-L 53). Do NOT base64url it for the QR: 54 mixed-case chars need version 4 (33x33) and buy nothing optically. base64url stays for the JSON test vectors and log lines only. Render it as large as the screen allows (full width, `FLAG_KEEP_SCREEN_ON`, brightness up); ZXing picks the smallest version that fits. The 2 px/module arithmetic in section 2 improves slightly with 29 modules instead of 33. Phone B's camera is never used, so it needs no CameraX permission at runtime.

## 4. Responder endpoint on phone B: NanoHTTPD 2.3.1

Why: one Java class, no transitive deps, ~50 KB, binds all interfaces, `POST` body available as a string, 20 lines total. Ktor would add `ktor-server-core`/`-cio`/`-content-negotiation`/`-serialization-kotlinx-json` (3.6.0, built with Kotlin 2.3.x) and a second coroutine-heavy server stack for one route; Java's `com.sun.net.httpserver` is not in the Android SDK; a hand-written `ServerSocket` parser is where hackathon hours go to die.

```kotlin
class ResponderServer(port: Int, private val onChallenge: (bodyJson: String) -> Boolean) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.POST || session.uri != "/challenge")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        val files = HashMap<String, String>()
        session.parseBody(files)                              // for application/json the raw body lands under "postData"
        val accepted = onChallenge(files["postData"] ?: "")  // iff verifies cmac; on success the UI shows the QR
        return newFixedLengthResponse(if (accepted) Response.Status.OK else Response.Status.FORBIDDEN, MIME_PLAINTEXT, "")
    }
}
// start: server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)   stop: server.stop()
```

The HTTP reply is only an ack (200/403): the authenticated answer travels optically as the QR, which is the point of the protocol. Show phone B's IPv4 from `NetworkInterface.getNetworkInterfaces()` (first non-loopback site-local address; works for hotspot host and client alike). Keep it in the Activity with `FLAG_KEEP_SCREEN_ON`; a foreground Service is not worth the hour.

## 5. Observer HTTP client and JSON

**OkHttp 5.5.0** (POM: Kotlin 2.1.21, okio 3.18.1; compile-verified). `HttpURLConnection` needs no dependency but its error-stream/`doOutput`/timeout gotchas cost more than the 1 MB OkHttp adds; coding agents also know OkHttp cold.

```kotlin
private val client = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()

suspend fun postJson(url: String, json: String): Int? = withContext(Dispatchers.IO) {
    try {
        client.newCall(Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType())).build())
            .execute().use { it.code }                       // OkHttp 5: response.body is non-null
    } catch (e: IOException) { null }                        // phone B unreachable -> UNKNOWN after the 10 s window
}
```

Challenge: `POST http://<phoneB>:8088/challenge` with the iff module's `encodeChallenge(...)` JSON. Record `SystemClock.elapsedRealtime()` when the POST is sent and again when the QR is read inside the box: that difference is the round-trip the PROJECT.md timing gate judges (2.5 s budget -> `FOE · late response`). Dashboard events: `POST http://<laptop>:8000/api/events` with `{t, class, box, iffStatus, droneId, reason, thumbJpegBase64?}` (PROJECT.md's field names) built with `org.json` in the app. `HttpDetector` (contingency, section 9) posts multipart `image` to the laptop. All three targets are cleartext `http://`, so the manifest's `android:usesCleartextTraffic="true"` is mandatory (API 28+ default refuses it).

**JSON rule.** The `iff` module owns every IFF message (challenge, QR payload layout, verdict) and the test vectors, using `kotlinx-serialization-json 1.9.0` with `@Serializable` DTOs (plugin applied in `iff` only; the app just calls `iff` functions). The app uses `org.json` (platform, nothing to add) for shapes it merely relays (`/v1/detections` responses, dashboard events). Never import `org.json` in `iff`: a plain JVM test has no Android stubs on the classpath. Never apply the serialization plugin to `app` (not needed, one less AGP/compiler-plugin interaction). The MAC inputs are fixed binary layouts (PROJECT.md FOF-1), never JSON text, so JSON formatting cannot affect verdicts.

## 6. Crypto: JDK built-ins only

`javax.crypto.Mac("HmacSHA256")` + `SecretKeySpec`, `java.security.SecureRandom` (16-byte nonces), `java.security.MessageDigest.isEqual(a, b)` for the constant-time tag compare, `java.util.Base64.getUrlEncoder().withoutPadding()` for the JSON test vectors and log lines (API 26+, so fine for minSdk 28 and the JVM; do not use `android.util.Base64` in `iff`). The QR itself carries the raw bytes (section 3), so `iff` exposes `encodeResponse(...): ByteArray` / `decodeResponse(ByteArray)` and base64url never touches the optical path. Verified in the scratch build with RFC 4231 test case 2 (`key="Jefe"`, `data="what do ya want for nothing?"`, tag `5bdcc146...64ec3843`). Inject a `Clock` (`() -> Long`) and a `Random` source into the verifier so the JSON test vectors are deterministic. No BouncyCastle, no Tink.

## 7. Pure-Kotlin `iff` module: toolchain facts

- Plugin: `org.jetbrains.kotlin.jvm` **2.2.10** (same `kotlin` version ref as the Compose plugin; AGP 9.2.1 bundles KGP 2.2.10 per the AGP 9.0 notes, and the root `./gradlew buildEnvironment` of this project shows exactly one KGP, `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10`, beside `com.android.tools.build:gradle:9.2.1`). JetBrains' table lists KGP 2.2.x as fully supported only up to Gradle 8.14 ([kotlinlang.org](https://kotlinlang.org/docs/gradle-configure-project.html)), but AGP 9 requires Gradle 9.1+ with that same KGP, and the scratch build (`:iff:compileKotlin`, `:iff:test`) ran clean on Gradle 9.4.1. Expect at most a compatibility warning. Do not bump Kotlin to 2.3.x to "match" the AAR: it would require bumping the Compose compiler plugin and AGP's built-in Kotlin together, hours of risk for no feature.
- JVM target: **11** in both `iff` (`java.targetCompatibility` + `kotlin.compilerOptions.jvmTarget = JVM_11`) and `app` (`compileOptions` already 11; built-in Kotlin defaults its jvmTarget to `compileOptions.targetCompatibility` per the [built-in Kotlin migration guide](https://developer.android.com/build/migrate-to-built-in-kotlin)). Two reasons: KGP fails the build on a Kotlin/Java target mismatch inside a module (`kotlin.jvm.target.validation.mode=error` is the Gradle 8+ default), and Gradle refuses a library variant whose `org.gradle.jvm.version` is higher than the consumer's. Verified: `app` (11) consumed `iff` (11).
- Tests: JUnit 4.13.2 via `testImplementation(libs.junit)`; `./gradlew :iff:test` needs no device, no Android SDK. JUnit 5 is a fine framework but on Gradle 9 needs `useJUnitPlatform()` plus `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`, two more things to get wrong at 3 a.m.
- Sources: `iff/src/main/kotlin`, `iff/src/test/kotlin`, vectors under `iff/src/test/resources`. Keep the module Android-free (`java.*`, `javax.crypto.*`, kotlinx.serialization only).

## 8. Sensors, location, audio: platform APIs, no Play Services

- GPS: `context.getSystemService(LocationManager::class.java).requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)` after the `ACCESS_FINE_LOCATION` runtime grant; also read `getLastKnownLocation`. Indoors there will be no fix: add a settings field for a manual observer position (lat/lon) so the position-cone check still runs; the Relay button sends a far-away position, so the FOE-position-mismatch verdict works regardless.
- Compass: `SensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)` at `SENSOR_DELAY_UI`; `getRotationMatrixFromVector` -> `remapCoordinateSystem(R, AXIS_X, AXIS_Z, out)` (the augmented-reality example in the `SensorManager.remapCoordinateSystem` docs) -> `getOrientation(out, o)`; azimuth `= (toDegrees(o[0]) + 360) % 360`, pitch from `o[1]`. Verify the sign on the real phone against a known landmark (a 180 deg flip is the classic mistake); the bearing of a box = azimuth + (boxCenterX/frameW - 0.5) * horizontalFov (~70 deg, read `CameraCharacteristics` if you want precision). This is the stretch feature; budget 30 minutes, cut first.
- Wi-Fi RSSI gate (PROJECT.md physical-layer requirement, `FOE · signal mismatch`): phone A is attached to phone B's hotspot, so the AP's signal IS the responder's. Read it with `ConnectivityManager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build(), callback)` and in `onCapabilitiesChanged` take `(caps.transportInfo as? WifiInfo)?.rssi` (dBm; API 29+ path, needs `ACCESS_WIFI_STATE` + `ACCESS_NETWORK_STATE`). `WifiManager.connectionInfo.rssi` is the one-liner alternative, deprecated since API 31 but still functional ("purely cosmetic" per the AOSP commit). SSID/BSSID are location-redacted on Android 12+ unless the callback is created with `FLAG_INCLUDE_LOCATION_INFO` and fine location is granted; RSSI itself is not redacted (MEDIUM, confirm on the test phone at H10). Calibrate the near-field threshold at demo distance (e.g. accept > -60 dBm at 1-2 m) and let the Relay button report a far-field claim so the gate has something to reject.
- Audio: `TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= 0 }`, then `tts.speak("Drone, bearing zero four five, high", TextToSpeech.QUEUE_FLUSH, null, "alert")`; `shutdown()` in `onDestroy`. Requires the manifest `<queries>` entry for `android.intent.action.TTS_SERVICE` on API 30+ ([TextToSpeech.Engine docs](https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeech.Engine)). The default Google TTS English voice is on-device on most phones, but check once in airplane mode; fallback `ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)` always works.

## 9. Laptop (optional): `clikart-cli serve` and the dashboard

**Serve commands (verified live on this laptop, 2026-10-10):**

```bat
set PATH=C:\Users\C\Downloads\ClikaRT_windows_x86_64-0.6.4\bin;%PATH%
clikart-cli --offline --cache-dir C:/Users/C/clika/models PekingU/rtdetr_v2_r18vd serve --device cpu --host 0.0.0.0 --port 8129
clikart-cli --offline --cache-dir C:/Users/C/clika/models google/owlv2-base-patch16-ensemble serve --device cpu --host 0.0.0.0 --port 8130
```

`--offline` and `--cache-dir` are global options and go before the model source; `--device`, `--host`, `--port`, `--api-key`, `--no-web-ui`, `--cors` belong to `serve` (`clikart-cli rt-detr serve --help`). Both models loaded in under a second from the cache (`model loaded on CPU:-1`); the log line to show the judges is `serving on http://0.0.0.0:8129 (model=rt-detr)` right after `--offline`. Use `--host 0.0.0.0` for the phones and allow TCP 8129/8130/8000 inbound in Windows Firewall (`netsh advfirewall firewall add rule name="FoF" dir=in action=allow protocol=TCP localport=8000,8129,8130`).

**API (from `serve_api.h` lines 27-32 and the live server):**

| Route | Request | Response |
|-------|---------|----------|
| `GET /health` | - | `{"status":"ok"}` (503 `license_blocked` / `device_lost` when broken, `serve_api.h` lines 266-285) |
| `GET /props` | - | `model_id`, `family`, `model_type`, `device`, `id2label` (closed-set only), `open_vocabulary`, `controls[]` with the form keys (`confidence` 0-1 default 0.5, `max_detections` 1-1000, and `prompt` for OWLv2) |
| `GET /v1/models` | - | OpenAI-style list incl. the checkpoint's license facts |
| `POST /v1/detections` | `multipart/form-data`; field **`image`** = the file (PNG/JPEG); optional text fields `confidence` (string float), `max_detections`; OWLv2 REQUIRES `prompt` = comma-separated phrases, e.g. `drone,quadcopter,person` | see below |

Wrong field name -> 400 `{"error":{"message":"multipart/form-data with an 'image' field is required","type":"invalid_request_error",...}}`. OWLv2 without `prompt` -> 400 `"this detection model requires a 'prompt' text field naming the classes to find"`.

Response body (RT-DETR, 640x480 test image, `confidence=0.05`), boxes in original-image pixels, score-descending:

```json
{"model":"rt-detr","image":{"width":640,"height":480},
 "detections":[{"box":{"xmin":380.6,"ymin":61.9,"xmax":580.4,"ymax":260.8},"score":0.6086,"label_id":11,"label":"stop sign"}, ...]}
```

OWLv2 echoes the prompt and labels by phrase index: `{"model":"owlvit","prompt":"stop sign,traffic light,drone","image":{...},"detections":[{"box":{...},"score":0.1456,"label_id":0,"label":"stop sign"}]}` (that request took ~5 s on the 12-thread CPU; RT-DETR answered with no perceptible delay, not timed). The CLI's `detect` verb prints the same rows to stdout (`{"image":{...},"detections":[...],"model":"PekingU/rtdetr_v2_r18vd"}`), with `--confidence`, `--max-detections` and, for OWLv2, `--labels "a,b"`, so `run_eval.py` can shell out instead of using HTTP if the server is down.

**Eval client (`eval/run_eval.py`): `requests` (installed), `pillow` (installed) for image size/drawing; no new packages.**

```python
import requests
from pathlib import Path

def detect(base: str, img: Path, confidence: float = 0.3, prompt: str | None = None) -> list[dict]:
    data = {"confidence": str(confidence)}
    if prompt:
        data["prompt"] = prompt                       # OWLv2 only: "drone,quadcopter,person"
    with img.open("rb") as fh:
        r = requests.post(f"{base}/v1/detections", files={"image": (img.name, fh, "image/jpeg")}, data=data, timeout=120)
    r.raise_for_status()
    return r.json()["detections"]                     # [{"box":{"xmin","ymin","xmax","ymax"},"score","label_id","label"}]
```

**Dashboard: FastAPI 0.143.0 + uvicorn 0.51.0 (installed), one file, inline HTML/JS, no CDN.** `POST /api/events` (JSON body, so `python-multipart` is not needed) appends to a `collections.deque(maxlen=500)`; `GET /api/events` returns it; `GET /dashboard` returns a `HTMLResponse` whose inline `<script>` polls every second and sets the latest thumbnail from a base64 JPEG field. Run `python -m uvicorn dashboard.app:app --host 0.0.0.0 --port 8000` with `C:\Python313\python.exe` (the packages live in its user site: `C:\Users\C\AppData\Roaming\Python\Python313\site-packages`). The observer phone itself already shows counts and verdicts, so if time is short the dashboard goes first (PROJECT.md flags it as discretionary; scrcpy mirroring phone A over USB, `scrcpy --max-size 1280`, is the judges' primary live view and needs no network).

## 10. Tooling: model push, install, logs

```bash
# build + install (APK ~300 MB; 1-2 min over USB; uninstall first if the signature changed)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# model snapshot -> the app's external files dir (readable by the app, writable by adb shell on Android 11+)
adb shell mkdir -p /sdcard/Android/data/com.chloeyeo.friendorfoe/files/models
adb push "C:/Users/C/clika/models/models--PekingU--rtdetr_v2_r18vd/snapshots/5650961749fa93567c0d46fc7f43ea4f9e914107" \
         /sdcard/Android/data/com.chloeyeo.friendorfoe/files/models/rtdetr_v2_r18vd
adb shell ls -l /sdcard/Android/data/com.chloeyeo.friendorfoe/files/models/rtdetr_v2_r18vd
#   config.json (5437)  model.safetensors (80904640)  preprocessor_config.json (444)

# logs: the runtime's own channel plus the app's tag
adb logcat -s ClikaRT FoF
```

App side: `File(context.getExternalFilesDir(null), "models/rtdetr_v2_r18vd")`. Alternative if the push step is a demo-day liability: ship the 3 files under `app/src/main/assets/models/rtdetr_v2_r18vd/` (gitignored by `*.safetensors`) and copy them to `filesDir` on first launch (+77 MB APK, one more minute of install, zero adb steps).

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| CameraX 1.6.2 | Stay on 1.4.1 | Only if the 1.6.2 download fails at H0 (both compile against the same code); 1.4.1 is two stable lines behind. |
| `AutoModelForObjectDetection` + `Modelverse.pipeline(task, model)` | `Modelverse.pipeline("object-detection", source, LoadOptions(...))` one-liner | Equivalent; use it in `HelloClika`-style smoke tests where you do not need `capabilities`. |
| Blocking pipeline call on the lane | `model.detect(image, options, listener)` + `CountDownLatch` | When you want `VisionReport.latencyMs`/`accelerator` for the pitch or `VisionHandle.cancel()`. |
| CPU device | `"vulkan:0"` | Timed experiment after H4 if CPU is > 1.5 s/frame; keep the CPU fallback in the catch. |
| ML Kit bundled 17.3.0 | ZXing `QRCodeReader` on the same crop | If ML Kit returns nothing in airplane mode (one-line swap in the decoder). |
| NanoHTTPD 2.3.1 | Ktor 3.6.0 CIO | Never for one route in 20 hours. |
| OkHttp 5.5.0 | `HttpURLConnection` | If you refuse any dependency; budget the error-stream and timeout boilerplate. |
| kotlinx.serialization 1.9.0 in `iff` | `org.json:json` as `testImplementation` + hand-rolled strings | If the serialization plugin misbehaves (it did not in the scratch build). |
| JUnit 4 | JUnit 5 | Not during this hackathon. |
| adb push to external files dir | Assets copy on first run | If re-pushing after every reinstall becomes a hazard. |
| RT-DETR v2 r18 on the phone | OWLv2 on the phone | Only as a measured experiment after H14 with `dtype = "float16"`; it is 620 MB fp32. |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| `org.jetbrains.kotlin.android` plugin | Incompatible with AGP 9 built-in Kotlin (AGP 9.0 notes) | Nothing; AGP compiles Kotlin itself |
| Kotlin 2.3.x / 2.4.x upgrade "to match the AAR" | Forces Compose compiler plugin + built-in KGP changes; compiler 2.2.10 already reads the 2.3.0 stdlib metadata (verified) | Keep 2.2.10 |
| `kotlinx-serialization-json` 1.10.0 / 1.11.0 | Built with Kotlin 2.3.0 / 2.3.20; readable but no benefit and off the 2.2 plugin's test matrix | 1.9.0 |
| `com.google.android.gms:play-services-mlkit-barcode-scanning` as the app dependency | Unbundled: model downloaded via Play, "requests before the download finishes return no results" | `com.google.mlkit:barcode-scanning:17.3.0` |
| `com.sun.net.httpserver.HttpServer` | Not in the Android SDK | NanoHTTPD |
| `org.json` inside `iff` | Not on the JVM unit-test classpath | kotlinx.serialization in `iff` |
| `android.util.Base64` in `iff` | Android-only | `java.util.Base64` url encoder |
| `packaging.jniLibs.useLegacyPackaging = true` | Compresses 277 MB of `.so`, extracted at install: slower install, 2x disk | Leave unset (uncompressed, `extractNativeLibs=false`) |
| `ImageAnalysis.Builder.setTargetResolution` | Deprecated since 1.3 | `ResolutionSelector` |
| Handing CameraX's RGBA plane buffer straight to `ImageInput.Rgba8888` | `rowStride` may exceed `width*4`; Rgba8888 wants tight rows | `toBitmap()` then `copyPixelsToBuffer` |
| `ObjectDetectionPipeline(model)` | Constructor is `internal` (`Pipelines.kt` 297-300) | `Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline` |
| Any ClikaRT load/detect on the main thread | Blocks for seconds -> ANR; the binding says so (`VisionModel.kt` 192-200) | `RuntimeLane` single thread |
| Hub download inside the app (`fromPretrained("PekingU/...")` without the files present) | No network at demo time; `offline = true` refuses, `offline = false` hangs | adb-push the snapshot |
| Android emulator | x86/x86_64 image, AAR has arm64 only | Real arm64 phone over USB |
| Vulkan as the default device | Driver acceptance is per phone; unverified | CPU default, Vulkan experiment |
| `camera-compose` `CameraXViewfinder` | More plumbing for the same preview | `PreviewView` in `AndroidView` |
| base64url text as the QR content | 54 mixed-case chars need QR version 4; PROJECT.md decided raw bytes (version 3) | ZXing byte mode via ISO-8859-1, ML Kit `Barcode.rawBytes` |
| Plain `http://` without the manifest opt-in | API 28+ blocks cleartext; OkHttp throws on the first challenge | `android:usesCleartextTraffic="true"` |
| `Barcode.rawValue` for the response | Text decoding of binary bytes is lossy | `Barcode.rawBytes` |
| `python -I` for the laptop scripts | Isolated mode hides the user-site packages where fastapi/uvicorn/requests/pillow live | plain `python` (`C:\Python313\python.exe`) |

## Stack Patterns by Variant

**If on-device CPU latency at 1280x720 is above 1.5 s/frame (measure at H3):**
- Drop the analysis resolution to 960x540 (QR read then needs ~0.5 m), set `CLIKA_RT_NUM_THREADS` to the big-core count, then try `device = "vulkan:0"` with `ModelverseException` fallback.
- Because `KEEP_ONLY_LATEST` + hold-then-close paces the pipeline, slower inference degrades to lower fps, never to a queue.

**If the H4 checkpoint fails (no on-device detections):**
- `HttpDetector : Detector` posts the frame as JPEG (`Bitmap.compress(JPEG, 80)`) via OkHttp `MultipartBody` with parts `image` (file), `confidence`, optional `prompt`, to `http://<laptop>:8129/v1/detections`; parse `detections[].box.{xmin,ymin,xmax,ymax}`, `score`, `label_id`, `label` with `org.json`; map to the same `Detection`/`Box` types (`io.clika.modelverse.Detection` is a plain data class, constructible from app code). One config line swaps the implementation; UI, iff, QR, eval never change.

**If ML Kit finds nothing in airplane mode:**
- Replace `scanner.process(...)` by `zxingDecode(crop)` (section 3). Same crop, same callback.

**LAN topology for the demo (verify at H0 on the real phones):**
- Phone B (responder) hosts the hotspot, phone A and the laptop join. Android 11+ allows a Wi-Fi hotspot with airplane mode on and Wi-Fi re-enabled on most OEMs; a Windows mobile hotspot often refuses to start without an upstream connection, so do not make the laptop the hub. Phone B shows its IP (hotspot host is the gateway, e.g. `192.168.x.1`); phone A stores it once. Windows Firewall must allow 8000/8129/8130 inbound for the optional laptop pieces.

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|-----------------|-------|
| Kotlin compiler 2.2.10 (AGP 9.2.1 built-in) | `io.clika:clika-runtime:0.6.4` (requires kotlin-stdlib 2.3.0) | Compiled in the scratch build; stdlib resolves to 2.3.0 app-wide (also seen in `:app:dependencies`); one-minor-ahead metadata is within Kotlin's forward-compat rule. |
| KGP 2.2.10 (`kotlin.jvm`, `plugin.serialization`) | Gradle 9.4.1 | Works (verified build + tests); JetBrains' "fully supported" table stops at 8.14 for 2.2.x, so a warning is possible. AGP 9 itself requires Gradle >= 9.1 with KGP 2.2.10. |
| CameraX 1.6.2 | minSdk 28, compileSdk 36, Kotlin 2.2.10 | 1.5+ needs minSdk 23 and compileSdk 35+; POMs: camera-view uses kotlin-stdlib 2.1.20, camera-core coroutines 1.9.0. |
| OkHttp 5.5.0 | Kotlin 2.2.10, minSdk 28 | POM: kotlin-stdlib 2.1.21, okio 3.18.1. |
| kotlinx-serialization-json 1.9.0 | plugin `org.jetbrains.kotlin.plugin.serialization:2.2.10` | 1.9.0 was built with Kotlin 2.2.0 (release notes); plugin version must equal the compiler version. |
| kotlinx-coroutines-android 1.10.2 | Kotlin 2.2.10, Compose BOM 2026.02.01 | POM: stdlib 2.1.0; the template already pulls coroutines 1.9.0 transitively, 1.10.2 wins. |
| ML Kit barcode-scanning 17.3.0 | minSdk 28 | Pulls `play-services-mlkit-barcode-scanning:18.3.1`, `play-services-basement:18.4.0`, `mlkit:common:18.11.0` transitively; builds fine; test offline once. |
| ZXing core 3.5.4, NanoHTTPD 2.3.1 | anything | Pure Java, no dependencies. |
| `iff` JVM target 11 | `app` `compileOptions` 11 | Equal targets avoid the `org.gradle.jvm.version` variant error and KGP's target validation; verified. |
| JDK 21 daemon toolchain | Java 11 targets | Verified. |
| `clikart-cli` 0.6.4 (Windows x86_64) | Kotlin binding 0.6.4 | Same release; mixing releases is refused at bind time (`BINDING_VERSION_MISMATCH`, `ClikaRt.kt` lines 91-98). |

## Sources

Local ground truth (ClikaRT 0.6.4), all under `C:/Users/C/clika/reference/`:
- `clika-runtime-0.6.4-sources/commonMain/io/clika/modelverse/Types.kt` — `SourceOptions`/`VisionLoadOptions` (lines 5-14, 831-845), `ImageInput` (800-811), `DetectOptions` (847-869), `Box`/`Detection` (877-885), `VisionReport` (897-905), `Capabilities.labels/openVocabulary/visionTasks` (240-253), `dtype` (31-40)
- `.../modelverse/Auto.kt` — `AutoModelForObjectDetection`/`AutoModelForZeroShotObjectDetection` both call `VisionModel.open` (282-294); `AutoModel.fromPretrained` conversion to `VisionLoadOptions` (218-227)
- `.../modelverse/VisionModel.kt` — `open` blocks, off the main thread (192-218); worker thread `modelverse-vision` (183); `detect` (36-52)
- `.../modelverse/Pipelines.kt` — pipelines block the caller (5-17); `ObjectDetectionPipeline` internal constructor + `candidateLabels` (297-318); `zero-shot-object-detection` alias (77)
- `.../modelverse/Modelverse.kt` — `pipeline(task, source, LoadOptions)` (161-195), `pipeline(task, model)` (197-202), `snapshot`/offline (139-159)
- `.../modelverse/Listeners.kt` — `VisionListener`/`VisionHandle` threading (107-144)
- `.../androidMain/io/clika/modelverse/ModelverseAndroid.kt` — `Modelverse.load(context, license)`
- `.../androidMain/io/clika/runtime/ClikaRtAndroid.kt` — `CLIKA_RT_NUM_THREADS` (41-45), `load` and license code names (59-87), `trimOnMemoryPressure` (89-104), `bridgeLogs` (164-178), `attachApp`/ANDROID_ID (214-223)
- `.../commonMain/io/clika/runtime/Devices.kt` — `Device.parse` spellings (184-195), `Backends.deviceStatus` (330-412); `ClikaRt.kt` — `ClikaRtException.codeName`, `BINDING_VERSION_MISMATCH` (25-39, 91-98)
- `examples-0.6.4/android/README.md` (requirements, license forms, `adb logcat -s ClikaRT`), `android/hello/build.gradle.kts` (license `BuildConfig` field, `abiFilters`, `buildConfig = true`), `android/gradle.properties` (`clikaMinSdk=28`), `android/shared/{RuntimeLane,SampleRuntime,LicenseStore,CredentialScreen}.kt`, `android/shared/build.gradle.kts` (coroutines 1.10.2 pairing), `android/hello/src/main/kotlin/.../HelloApplication.kt`, `chat_with_pictures/src/androidApp/{build.gradle.kts, src/main/AndroidManifest.xml}` (`largeHeap`), `chat_with_pictures/src/shared/src/androidMain/.../Platform.android.kt`
- `modelverse-server-headers/serve_api.h` (routes 10-32, limits 125-131, health states 266-285), `detection_engine.h` (request/row semantics 41-77, open-vocabulary prompt rule 17-26)
- Gradle cache AAR `C:/Users/C/.gradle/caches/modules-2/files-2.1/io.clika/clika-runtime-android/0.6.4/.../clika-runtime.aar` (manifest, `jni/arm64-v8a` sizes, `proguard.txt`); `C:/Users/C/clika/io/clika/clika-runtime*/0.6.4/*.module|*.pom` (stdlib 2.3.0 requirement, Gradle 9.8 publisher)
- Live runs 2026-10-10: `clikart-cli … serve` for `PekingU/rtdetr_v2_r18vd` and `google/owlv2-base-patch16-ensemble` (`/health`, `/props`, `/v1/models`, `POST /v1/detections` success and error bodies), `clikart-cli … detect` stdout, `clikart-cli --help`, `rt-detr serve --help`, `detect --help` for both families
- Scratch verification build (`…/scratchpad/kgp-test`): AGP 9.2.1 + kotlin.jvm/serialization 2.2.10 + Gradle 9.4.1; `:app:compileDebugKotlin` over ClikaRT 0.6.4, CameraX 1.6.2, ML Kit 17.3.0, ZXing 3.5.4, NanoHTTPD 2.3.1, OkHttp 5.5.0, coroutines 1.10.2, `project(":iff")`; `:iff:test` 2/2 green; `python -m pip list` for the laptop packages

External (versions and semantics):
- Google Maven / Maven Central `maven-metadata.xml` for androidx.camera (1.6.2 latest stable), com.google.mlkit:barcode-scanning (17.3.0), com.google.zxing:core (3.5.4), org.nanohttpd (2.3.1), com.squareup.okhttp3:okhttp (5.5.0), kotlinx-serialization-json (1.11.0 latest stable, 1.12.0-RC pre-release, 1.9.0 chosen), kotlinx-coroutines-android (1.11.0 latest, 1.10.2 chosen), AGP (9.4.1 latest stable, 9.5.0-alpha09 pre-release, 9.2.1 kept), Ktor ktor-server-core 3.6.0 latest (POM: kotlin-stdlib 2.3.21, coroutines 1.11.0); POMs of okhttp 5.5.0 / 4.12.0, camera-core/camera-view 1.6.2, camera-core 1.5.3, coroutines 1.10.2 / 1.11.0, barcode-scanning 17.3.0 — HIGH
- [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera) — 1.6.2 stable Aug 26 2026; 1.5 minSdk 23 / compileSdk 35 — HIGH
- androidx sources `camera-core/.../ImageAnalysis.java` (RGBA layout, `setOutputImageRotationEnabled`, `STRATEGY_KEEP_ONLY_LATEST`) and `.../internal/utils/ImageUtil.java` (`toBitmap` stride handling) — HIGH
- [ML Kit barcode scanning Android guide](https://developers.google.com/ml-kit/vision/barcode-scanning/android) (bundled vs unbundled, `InputImage.fromBitmap`, 2 px/module, 1280x720 guidance) and [ML Kit release notes](https://developers.google.com/ml-kit/release-notes) (17.3.0 = 16 KB page support only) — HIGH; offline behaviour of 17.3.0 with its Play dependency — MEDIUM (test required)
- [AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes) (built-in Kotlin, KGP 2.2.10, Gradle 9.1+/JDK 17 minimums) and [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) (jvmTarget defaults to `compileOptions.targetCompatibility`) — HIGH
- [Kotlin Gradle plugin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html) (KGP 2.2.x table, target validation) — HIGH for the table, the Gradle 9.4.1 pairing is verified empirically instead
- [kotlinx.serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases) (1.9.0 = Kotlin 2.2.0; 1.10.0 = 2.3.0; 1.11.0 = 2.3.20) — HIGH
- [AGP 3.6.0 notes](https://developer.android.com/build/releases/agp-3-6-0-release-notes) / [AGP 4.2.0 notes](https://developer.android.com/build/releases/agp-4-2-0-release-notes) (`extractNativeLibs=false` default, `useLegacyPackaging`) — HIGH
- [TextToSpeech.Engine](https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeech.Engine) (`<queries>` for `INTENT_ACTION_TTS_SERVICE` on Android 11+) — HIGH
- PyPI JSON API (fastapi 0.143.0 current, uvicorn 0.54.0 current vs 0.51.0 installed, requests 2.34.2 vs 2.32.5 installed, pillow 12.3.0 vs 12.1.1 installed) — HIGH; no upgrade needed for any of them

Confidence summary: ClikaRT API and wiring HIGH (sources + compile); CameraX/ML Kit/ZXing/NanoHTTPD/OkHttp versions HIGH (metadata + compile); on-device inference speed and Vulkan availability LOW (unmeasured, phone-specific); ML Kit offline behaviour MEDIUM; compass remap sign MEDIUM (verify on device); laptop serve API HIGH (live).

## Verification notes (2026-10-10)

Adversarial re-check of every concrete claim against the local ClikaRT 0.6.4 sources/headers/examples, the real `clikart-cli.exe` (`--help`, `list --all`, `rt-detr --help`, `rt-detr serve --help`, `rt-detr detect --help`, `owlvit detect --help`, `--version` = 0.6.4, plus a live `serve` on 127.0.0.1 for `/health`, `/props`, and the `/v1/detections` 400 body), the Gradle-cache AAR, Google Maven / Maven Central / PyPI metadata and POMs, and the official Android docs.

**Corrected in place:**
- **Cleartext HTTP was missing (would have broken the first challenge).** Android 9+ (API 28, the project's minSdk/targetSdk) refuses `http://` by default; added `android:usesCleartextTraffic="true"` to the manifest section, a note in the OkHttp row and section 5, and a "What NOT to use" row.
- **QR payload contradicted PROJECT.md.** Sections 3 and 6 encoded base64url text (version 4, ECC M, `rawValue`); PROJECT.md's decision is raw ~40 bytes in byte mode read via `Barcode.getRawBytes()` (version 3). Rewrote the encode (ISO-8859-1 byte-mode trick, `CHARACTER_SET` hint), the ML Kit decode (`rawBytes`), the ZXing fallback (`BYTE_SEGMENTS` metadata), the payload paragraph and section 6's Base64 scope.
- **Class map.** Section 1 keyed the drone proxy set on label strings; PROJECT.md keys on `labelId` 0/4/14/33. Re-read `config.json` `id2label` (80 entries): `0 person, 3 motorbike, 4 aeroplane, 14 bird, 33 kite, 67 cell phone` confirmed; text now says `labelId` (`Long`).
- **Wi-Fi RSSI gate was absent** although PROJECT.md lists it as a verdict layer (`FOE · signal mismatch`). Added the API path (`NetworkCapabilities.transportInfo as WifiInfo`, `ACCESS_WIFI_STATE`/`ACCESS_NETWORK_STATE`) to section 8 and the manifest; `WifiManager.getConnectionInfo()` deprecation at API 31 confirmed via the API-31 diff.
- **Round-trip timing gate** (`FOE · late response`, 2.5 s) and PROJECT.md's event key `class` (was `cls`) added to section 5.
- **Native-lib sizes** were decimal/binary mixes ("277 MB"): actual 99,683,136 / 139,271,256 / 19,055,305 / 11,913,576 / 14,319,784 bytes = 271 MiB = 284 MB; relabelled. `p_align` claim refined (one `0x10000` segment in `libClikaRT_modelverse.so`, still 16 KB-safe).
- **Cut order** wording aligned with PROJECT.md (audio → OWLv2 → Relay → eval; dashboard discretionary, scrcpy primary view).
- **"Latest" wording** for kotlinx-serialization (1.12.0-RC exists as a pre-release; 1.11.0 is the latest stable) and AGP (9.5.0-alpha09 pre-release); Ktor 3.6.0 POM facts added.

**Confirmed as written (HIGH):** every cited line range in `Types.kt`, `Auto.kt`, `Pipelines.kt`, `Modelverse.kt`, `VisionModel.kt`, `Listeners.kt`, `ModelverseAndroid.kt`, `ClikaRtAndroid.kt`, `ClikaRt.kt`, `Devices.kt`; `ObjectDetectionPipeline` internal constructor; `Task.OBJECT_DETECTION`/`ZERO_SHOT_OBJECT_DETECTION` alias; `VisionTask.DETECT`; `PretrainedConfig.family`; `DetectOptions` defaults 100 / 0.5f; `Detection.labelId: Long`; `Modelverse.load(context, license)` extension; license code names; `BINDING_VERSION_MISMATCH`; AAR manifest `minSdkVersion=28`, arm64-v8a only, `proguard.txt`, `classes.jar` 0.9 MB, `.module` requires kotlin-stdlib 2.3.0 (published by Gradle 9.8.0); examples README lines 33-38 and 63, `hello/build.gradle.kts` lines 14-25/36-37/41, `shared/build.gradle.kts` line 32 (coroutines 1.10.2), `gradle.properties` `clikaMinSdk=28`, `HelloApplication.kt`, `largeHeap` in chat_with_pictures; `serve_api.h` routes (lines 27-31), limits (125-131), `license_blocked`/`device_lost` (266-285); `detection_engine.h` prompt rule and row shape; CLI: `--offline`/`--cache-dir` global, `serve` has `--host` (default 127.0.0.1), `--port` (default 8000), `--device`, `--api-key`, `--no-web-ui`, `--cors`; `detect` has `--confidence`, `--max-detections`, owlvit `--labels`; live `/props`: `id2label`, `open_vocabulary=false`, `controls[]` = `confidence` slider 0-1 default 0.5 and `max_detections` 1-1000; snapshot files 5437 / 80,904,640 / 444 bytes at the cited hash; OWLv2 `model.safetensors` 619,918,824 bytes (8 files). Versions: CameraX 1.6.2 stable (Aug 26 2026; 1.7.0-alpha03 pre-release), 1.5 minSdk 23 / compileSdk 35; ML Kit barcode-scanning 17.3.0 latest, POM deps `play-services-mlkit-barcode-scanning:18.3.1`, `play-services-basement:18.4.0`, `mlkit:common:18.11.0`, `barcode-scanning-common:17.0.0`, `vision-common:17.3.0`; ZXing core 3.5.4 latest; NanoHTTPD 2.3.1 latest; OkHttp 5.5.0 latest (POM: kotlin-stdlib 2.1.21, okio 3.18.1); kotlinx-serialization 1.9.0 = Kotlin 2.2.0, 1.10.0 = 2.3.0, 1.11.0 = 2.3.20; coroutines 1.11.0 latest; KGP 2.2.10 exists (2.2.21 is the last 2.2.x); AGP 9.2.1 and 9.4.1 exist; Compose BOM 2026.02.01 exists (2026.09.00 latest); fastapi 0.143.0 current, uvicorn 0.54.0 current vs 0.51.0 installed, requests 2.34.2 vs 2.32.5, pillow 12.3.0 vs 12.1.1 (local `pip list` confirmed the installed set and Python 3.13.3). Platform: `OUTPUT_IMAGE_FORMAT_RGBA_8888` single-plane RGBA, `STRATEGY_KEEP_ONLY_LATEST`, `setOutputImageRotationEnabled` default off / API 23 / 10-15 ms at 640x480 (androidx `ImageAnalysis.java`); `ImageUtil.createBitmapFromRgbaImage` passes `getRowStride()` to `copyByteBufferToBitmap`; ML Kit bundled model statically linked, 2 px/module, 1280x720 guidance; AGP 9.0 bundles KGP 2.2.10, `org.jetbrains.kotlin.android` incompatible with the new DSL, Gradle 9.1 / JDK 17 minimums; built-in Kotlin `jvmTarget` defaults to `compileOptions.targetCompatibility`; KGP 2.2.x fully supported up to Gradle 8.14 (2.3.0 up to 9.0.0), `kotlin.jvm.target.validation.mode=error` default on Gradle 8+; AGP 3.6 `extractNativeLibs=false` default. The scratch build `…/scratchpad/kgp-test` exists on disk.

**Still unverified / lower confidence:**
- ML Kit 17.3.0 bundled artifact behaviour in airplane mode given its `play-services-mlkit-barcode-scanning` POM dependency — MEDIUM; no official statement found either way; the H10 airplane-mode test and the ZXing fallback stay.
- `/props` `prompt` control for OWLv2 — not re-run live here (only RT-DETR was served); from the researcher's earlier run — MEDIUM-HIGH.
- `TextToSpeech.Engine` `<queries>` requirement for Android 11+ — the reference page could not be fetched in full; the claim matches the documented `INTENT_ACTION_TTS_SERVICE` note — HIGH from memory, not re-quoted.
- RSSI not being location-redacted in `WifiInfo` from `transportInfo` on Android 12+ — MEDIUM; confirm on the test phone.
- Hotspot with airplane mode on (Android 11+, "most OEMs") — LOW/OEM-specific, unverified; test at H0.
- On-device CPU latency, Vulkan driver acceptance, compass sign — LOW, phone-specific, as the file already says.
- Scratch build results (`BUILD SUCCESSFUL`, `:iff:test` 2/2) — the project exists in the scratchpad but was not re-run here.

---
*Stack research for: offline on-device Android drone detection + optical IFF (FriendOrFoe)*
*Researched: 2026-10-10; verified 2026-10-10*
