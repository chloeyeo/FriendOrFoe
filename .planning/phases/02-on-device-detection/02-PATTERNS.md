# Phase 02: On-device detection - Pattern Map

**Mapped:** 2026-10-10
**Files analyzed:** 23 (16 main-source files, 1 asset, 6 test files)
**Analogs found:** 21 / 23 (2 partial: the OkHttp request in `HttpDetector`, the Android API half of `OfflineProbe`)

All repo paths are relative to `C:/Users/C/AndroidStudioProjects/FieldReport/`; `SRC` = `app/src/main/java/com/chloeyeo/friendorfoe/`, `TEST` = `app/src/test/java/com/chloeyeo/friendorfoe/`.
External analog roots: `EX` = `C:/Users/C/clika/reference/examples-0.6.4/`, `BIND` = `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/`.
Line numbers were read this session from the files as they are on disk (skeleton commit `2ffed89`).

House style in one sentence (verified across every real skeleton file): pure Kotlin types (`BoxF`, `Frame`, `Detection`) instead of Android graphics classes, `require`/`check` guards with a message that names the value, one KDoc per class that states the thread it runs on, JUnit 4 tests with backtick names and an injectable `clock`, stubs replaced by keeping the signature and filling the body.

## File Classification

| New/Modified File | Action | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|--------|------|-----------|----------------|---------------|
| `SRC/app/FofApplication.kt` | REPLACE `loadRuntime`, ADD singleton coordinator | application / provider | request-response (blocking bring-up on the lane) | `EX/android/shared/.../SampleRuntime.kt:28-34` + `EX/vision_lens/.../Platform.android.kt:59-72` + `EX/android/hello/.../HelloApplication.kt:11-16` | exact (official) |
| `SRC/app/OfflineProbe.kt` (+ pure `OfflineRule`) | NEW | utility / probe | request-response (polled from `tick`) | rule: `SRC/detect/LabelMap.kt:15-27` (pure `object`); Android half: none in repo | role-match / partial |
| `SRC/app/Prefs.kt` | EDIT default | config | CRUD (SharedPreferences) | itself `:12-18` + `SRC/detect/DetectorConfig.kt:21-30` | exact |
| `SRC/app/RuntimeLane.kt` | keep (KDoc `:9-12` now wrong: analyzer is NOT on the lane under D-02) | config / executor | — | `EX/android/shared/.../RuntimeLane.kt:13-16` | exact |
| `SRC/camera/CameraController.kt` | REPLACE | controller (camera) | streaming (30 fps in, ~1 fps out) | `EX/vision_lens/.../CameraFrames.kt:54-104` | exact |
| `SRC/camera/RgbaPacker.kt` | NEW | utility (pure transform) | transform (ByteBuffer -> `Frame`) | `SRC/detect/Frame.kt:13-25` (guards) + `SRC/camera/FrameTransform.kt:12-24` (pure, tested) + `CameraFrames.kt:72-79` (byte source) | role-match |
| `SRC/camera/FrameTransform.kt` | keep (add 1600x720 test) | utility | transform | itself | exact |
| `SRC/detect/ClikaRtDetector.kt` | REPLACE | service (inference) | request-response (blocking) | `EX/vision_lens/.../ModelverseEngine.kt:21-40, 74-91` + `SRC/detect/FakeDetector.kt:8-12, 34` | exact |
| `SRC/detect/Dedup.kt` | NEW | utility (pure) | transform (list -> list) | `SRC/track/Tracker.kt:28-48` (greedy IoU pairing) + `SRC/detect/BoxF.kt:22-29` | role-match |
| `SRC/detect/DetectionsJson.kt` | NEW | utility (parser, pure) | transform (JSON -> `Detection`) | `SRC/signal/GadgetLineCodec.kt:43-56, 89-91` | exact |
| `SRC/detect/HttpDetector.kt` | REPLACE | service (HTTP client) | request-response | Detector shape: `FakeDetector.kt`; parse: `DetectionsJson`; OkHttp call: none in repo (RESEARCH Code Example 6) | partial |
| `SRC/session/IffCoordinator.kt` | EDIT (`tick`, `challenge`, `setStatus`, `offlineProbe`) | service (session) | event-driven (lane `tick`, main `challenge`) | itself `:31-95` + `SRC/session/EventLog.kt:9-21` (synchronized) + `SRC/track/BoxLock.kt:10-18` | exact |
| `SRC/session/ObserverUiState.kt` | EDIT `[shared]` additive `StatusUi.modelNote` | model (contract) | — | itself `:26-34`; constructor users `SRC/session/DemoScenario.kt:37-45` | exact |
| `SRC/track/Tracker.kt` | EDIT (wall-clock sticky per class) | service (pure) | transform | itself `:14-17, 50-66` | exact |
| `SRC/ui/CameraScreen.kt` | EDIT (preview slot, size report, `modelNote` chip) | component | — | `EX/vision_lens/.../LivePane.android.kt:29-38` + itself `:52-92, 96-112` | exact |
| `SRC/MainActivity.kt` | REPLACE | controller (activity) | event-driven | `EX/vision_lens/src/androidApp/.../MainActivity.kt:45-96` + `EX/android/hello/.../MainActivity.kt:60-74` | exact |
| parity harness (debug-only code in `MainActivity`/`FofApplication`, discretionary placement) | NEW | utility (self-test) | file-I/O + request-response | `EX/vision_lens/src/androidApp/.../SelfTestRunner.kt:21-74` + `Platform.android.kt:94-101, 120-130` | exact |
| `app/src/main/assets/parity/scene.png` | NEW asset | asset | — | copy of `EX/vision_lens/samples/scene.png` (2851 B; `assets/` dir does not exist yet) | exact |
| `TEST/camera/RgbaPackerTest.kt` | NEW | test | — | `TEST/camera/FrameTransformTest.kt:8-18, 67-70` | exact |
| `TEST/detect/DedupTest.kt` | NEW | test | — | `TEST/track/TrackerTest.kt:11-16` (helpers) | exact |
| `TEST/detect/DetectionsJsonTest.kt` | NEW | test | — | `TEST/signal/GadgetLineCodecTest.kt:12-13, 32-43` (raw JSON, real org.json) | exact |
| `TEST/app/OfflineRuleTest.kt` | NEW | test | — | `TEST/detect/LabelMapTest.kt:7-16` | exact |
| `TEST/track/TrackerTest.kt` | EDIT (sticky-by-time cases) | test | — | itself `:67-82` | exact |
| `TEST/session/IffCoordinatorTest.kt` | EDIT (lock cases; delete `:45-49`) | test | — | itself `:12-33` (injectable clock) | exact |

## Pattern Assignments

### `SRC/app/FofApplication.kt` (application / provider, blocking bring-up)

**Analogs:** `EX/android/shared/src/main/kotlin/io/clika/examples/android/shared/SampleRuntime.kt`, `EX/vision_lens/src/shared/src/androidMain/kotlin/io/clika/examples/visionlens/Platform.android.kt`, `EX/android/hello/src/main/kotlin/io/clika/examples/android/hello/{HelloApplication.kt,MainActivity.kt}`. Current stub: `FofApplication.kt:40-42` (`fun loadRuntime(license: String?): Nothing = throw …`), `onTrimMemory` TODO `:34-38`, `from(context)` helper `:44-46` (keep).

**Imports pattern** (`SampleRuntime.kt:3-5, 7-8`; `Backends` at `:6` is not needed):
```kotlin
import android.content.Context
import io.clika.modelverse.Modelverse
import io.clika.modelverse.load          // the Android extension: Modelverse.load(context, license)
import io.clika.runtime.ClikaRtAndroid
import io.clika.runtime.ClikaRtException
```
Plus for the probe (`hello/MainActivity.kt:28-30`): `import io.clika.runtime.ClikaRtException`, `import io.clika.runtime.Ops`, `import io.clika.runtime.Tensors`; and `import android.system.Os` for `CLIKA_RT_NUM_THREADS` (D-06).

**Core pattern: idempotent load once per process** (`SampleRuntime.kt:28-34`):
```kotlin
object SampleRuntime {
    /** Loads once; a refusal (no credential, a library that did not load) throws with its code name. */
    fun load(context: Context, license: String) {
        if (Modelverse.isLoaded) return
        Modelverse.load(context.applicationContext, license)
        ClikaRtAndroid.bridgeLogs()
    }
```
Binding facts behind it: `BIND/androidMain/io/clika/modelverse/ModelverseAndroid.kt:17-30` (`fun Modelverse.load(context: Context, license: String? = null)`, `if (Modelverse.isLoaded) return`, wraps `UnsatisfiedLinkError` into `ModelverseException`); `BIND/androidMain/io/clika/runtime/ClikaRtAndroid.kt:80-87` (`load` = `pinCacheRoot` -> `placeLicense` -> `ClikaRt.load()` -> `attachApp`), `:41-44` (`CLIKA_RT_NUM_THREADS` "read once at the first compute … `Os.setenv` before load"), `:100-104` (`trimOnMemoryPressure(context)`: registers one listener, second call no-op, "Call it after load"), `:171-178` (`bridgeLogs(tag = "ClikaRT", level = LogLevel.INFO)`).

**Error-mapping pattern: both exception types, code name first** (`Platform.android.kt:59-72`):
```kotlin
    actual fun bringUp(credential: String?) {
        val ctx = requireNotNull(this.context) { "attach the app first" }
        ...
        try {
            Modelverse.load(ctx, credential)
            ClikaRtAndroid.bridgeLogs()
        } catch (e: UnsatisfiedLinkError) {
            throw PlatformException("LIBRARY_MISSING", "the app ships libClikaRT.so … : ${e.message}")
        } catch (e: ClikaRtException) {
            throw PlatformException(e.codeName, e.message ?: e.codeName)
        } catch (e: ModelverseException) {
            throw PlatformException(e.codeName, e.message ?: e.codeName)
        }
    }
```
In FoF the equivalent sink is `coordinator.setStatus { it.copy(modelReady = false, modelNote = codeName.ifEmpty { message.take(80) }) }` (RESEARCH Pattern 6); never the credential text.

**License probe pattern (surfaces `LICENSE_*` as a clean `codeName`)** (`hello/MainActivity.kt:91-102`):
```kotlin
        // One operator: two tensors on the CPU, their sum read back as text.
        Tensors.of(floatArrayOf(1f, 2f, 3f), longArrayOf(3)).use { a ->
            Tensors.of(floatArrayOf(10f, 20f, 30f), longArrayOf(3)).use { b ->
                Ops.add(a, b).use { sum -> out += "a + b = ${sum.summary()}" }
            }
        }
    } catch (e: ClikaRtException) {
        // The code name is the machine channel: LICENSE_FAILED names the one thing to fix first.
        out += "refused (${e.codeName}): ${e.message}"
    } catch (e: RuntimeException) {
        out += "failed: ${e.message}"
    }
```
Signatures: `Tensors.of(data: FloatArray, shape: LongArray, where: Placement? = null): Tensor` (`BIND/commonMain/io/clika/runtime/Tensors.kt:219`), `Tensor : AutoCloseable` (`:67`), `summary(): String` (`:150`), `Ops.add(input: Tensor, other: Tensor, alpha: Double = 1.0, …): Tensor` (`Ops.kt:646`). Why it is needed: the first operator is where the license is checked (`BIND/commonMain/io/clika/runtime/ClikaRt.kt:56-62`), and through the pipeline door the failure arrives as `ModelverseException(message)` with an empty `codeName` (`BIND/commonMain/io/clika/modelverse/Pipelines.kt:115-119`).

**Memory pattern** (`HelloApplication.kt:11-16`):
```kotlin
class HelloApplication : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ClikaRtAndroid.trimOnMemoryPressure(this)
    }
}
```
Keep the skeleton's `onCreate` light (`FofApplication.kt:29-32`, prefs only); call `trimOnMemoryPressure` inside `loadRuntime` after `bridgeLogs` (it registers its own callbacks; `onTrimMemory` can then stay as is).

**Credential source** (`app/build.gradle.kts:13-19, 40`): `BuildConfig.CLIKA_RT_LICENSE` is the first non-blank line of the file named by `local.properties` -> `clika.runtimeLicenseFile`, `""` when absent. Pass `license.ifBlank { null }` (Pitfall A2: an empty first load sticks because `Modelverse.load` is idempotent). The `LicenseStore` fallback (`EX/android/shared/.../LicenseStore.kt:15-22`, `filesDir/clika_rt_license`) is optional; the KDoc at `FofApplication.kt:12-14` already names it.

**Singleton pattern (D-03, Pattern 3):** hold `val coordinator: IffCoordinator by lazy { IffCoordinator(Detectors.create(prefs.detectorConfig), FakeSignalSource()) }` on the Application (constructor at `IffCoordinator.kt:31-38`; `Detectors.create` at `Detectors.kt:11-20` already refuses `Http` outside debug). `MainActivity` never closes it.

---

### `SRC/app/OfflineProbe.kt` + `OfflineRule` (utility / probe, polled)

**Analogs:** rule object -> `SRC/detect/LabelMap.kt:15-27`; context-holding helper -> `SRC/app/Prefs.kt:12-13`. No `ConnectivityManager`/`Settings.Global` usage exists in the repo (grep verified) -> Android half from RESEARCH Code Example 5.

**Pure-object pattern to copy for `OfflineRule`** (`LabelMap.kt:15-27`):
```kotlin
object LabelMap {
    const val DEFAULT_PERSON_THRESHOLD = 0.5f
    ...
    /** The class of a raw RT-DETR/COCO [labelId], or null when it is none of ours. */
    fun classOf(labelId: Long): ObjClass? = when {
        labelId == PERSON_ID -> ObjClass.PERSON
        labelId in DRONE_IDS -> ObjClass.DRONE
        else -> null
    }
```
-> `object OfflineRule { fun isOffline(airplaneOn: Boolean, validated: Boolean): Boolean = airplaneOn && !validated }` (D-12), tested like `LabelMapTest`.

**Context-holder pattern** (`Prefs.kt:12-13`):
```kotlin
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("fof_prefs", Context.MODE_PRIVATE)
```
-> `class OfflineProbe(context: Context) : () -> Boolean` holding `context.applicationContext` and `getSystemService(ConnectivityManager::class.java)`; constants `Settings.Global.AIRPLANE_MODE_ON`, `NetworkCapabilities.NET_CAPABILITY_VALIDATED` (RESEARCH §O, verified against android.jar 36). Permissions already in the manifest (`AndroidManifest.xml:9-10`).

**Injection point:** `IffCoordinator(..., offlineProbe: () -> Boolean = { true })` following the existing default-parameter style of `clock: () -> Long = { System.currentTimeMillis() }` (`IffCoordinator.kt:37`).

---

### `SRC/app/Prefs.kt` (config, SharedPreferences)

**Analog:** itself. Current getter (`Prefs.kt:15-18`):
```kotlin
    /** Which detector runs; `fake` until stream 1 switches the default to ClikaRT. */
    var detectorConfig: DetectorConfig
        get() = DetectorConfig.decode(sp.getString(KEY_DETECTOR, null))
        set(value) = sp.edit().putString(KEY_DETECTOR, value.encode()).apply()
```
`DetectorConfig.decode` (`DetectorConfig.kt:23-30`) returns `Fake` for `null`, so the D-11 default must be applied in the getter: `get() = sp.getString(KEY_DETECTOR, null)?.let(DetectorConfig::decode) ?: DetectorConfig.ClikaRt(defaultModelDir)` where `defaultModelDir = File(context.filesDir, "models/rtdetr_v2_r18vd").path` is captured in the constructor (the class currently keeps only `sp`; add a `private val defaultModelDir: String` field). Keys live in the `private companion object` (`:70-82`); no new key is needed.

---

### `SRC/camera/CameraController.kt` (controller, streaming)

**Analog:** `EX/vision_lens/src/shared/src/androidMain/kotlin/io/clika/examples/visionlens/CameraFrames.kt` (exact role + flow: CameraX RGBA_8888 analysis + optional preview, bound on the main executor). Current stub: `CameraController.kt:26-33` (`class CameraController(private val context: Context)`, `start(lifecycleOwner, previewView, onFrame: (Frame) -> Unit)`, `stop()`); keep both signatures. Its KDoc `:16-19` (`setOutputImageRotationEnabled`, analyzer on the lane, hold the proxy) is superseded by D-01/D-02 — rewrite the KDoc.

**Imports pattern** (`CameraFrames.kt:8-23`, trimmed to what FoF needs):
```kotlin
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
```
Add for D-01: `androidx.camera.core.UseCaseGroup`, `androidx.camera.core.resolutionselector.AspectRatioStrategy`, `androidx.core.view.doOnLayout`, `java.util.concurrent.atomic.AtomicBoolean`.

**Own single camera thread, provider held volatile** (`CameraFrames.kt:54-56`):
```kotlin
class CameraFrames(private val context: Context, private val owner: LifecycleOwner) : FrameSource {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, THREAD_NAME).apply { isDaemon = true } }
    @Volatile private var provider: ProcessCameraProvider? = null
```
(`THREAD_NAME = "camera-frames"`, `:98`; FoF: `"camera-analysis"`.)

**Core pattern: get provider on the main executor, build analysis, bind** (`CameraFrames.kt:58-71, 80-86`):
```kotlin
    override fun start(onFrame: (Picture, Long) -> Unit) {
        val future = ProcessCameraProvider.getInstance(this.context)
        future.addListener({
            val provider = future.get()
            this.provider = provider
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(FRAME_WIDTH, FRAME_HEIGHT), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build(),
                )
                .build()
            ...
            val cases = ArrayList<UseCase>()
            CameraRoad.previewView?.let { view -> cases.add(Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }) }
            cases.add(analysis)
            provider.unbindAll()
            provider.bindToLifecycle(this.owner, CameraSelector.DEFAULT_BACK_CAMERA, *cases.toTypedArray())
        }, ContextCompat.getMainExecutor(this.context))
    }
```
FoF deltas (D-01): 1280x720 with `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`, `AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY` on **both** use cases, and `UseCaseGroup.Builder().setViewPort(requireNotNull(previewView.viewPort)).addUseCase(preview).addUseCase(analysis).build()` passed to `bindToLifecycle(owner, selector, group)`; wrap the whole thing in `previewView.doOnLayout { … }` because `getViewPort()` is null before layout (Pitfall C1). Full assembled form: RESEARCH Code Example 4.

**Analyzer pattern: proxy closed in `finally`, rotation from `imageInfo`** (`CameraFrames.kt:72-79`):
```kotlin
            analysis.setAnalyzer(this.executor) { proxy ->
                val degrees = proxy.imageInfo.rotationDegrees
                val bitmap = try { proxy.toBitmap() } finally { proxy.close() }
                val upright = if (degrees == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
                val rgba = ByteArray(upright.width * upright.height * CHANNELS)
                upright.copyPixelsToBuffer(ByteBuffer.wrap(rgba))
                onFrame(Picture(upright.width, upright.height, upright.asImageBitmap(), ByteArray(0), FRAME_NAME, rgba), System.nanoTime())
            }
```
FoF replaces `toBitmap()`/`copyPixelsToBuffer` by `RgbaPacker.pack(plane.buffer, proxy.width, proxy.height, plane.rowStride, plane.pixelStride, proxy.imageInfo.rotationDegrees)` (D-02, same bytes, JVM-testable) and adds the busy gate before the copy:
```kotlin
                try {
                    if (!busy.compareAndSet(false, true)) return@setAnalyzer   // drop; never queue
                    val frame = RgbaPacker.pack(...)
                    RuntimeLane.executor.execute { try { onFrame(frame) } finally { busy.set(false) } }
                } finally { proxy.close() }
```
The official equivalent of "at most one frame in flight" is `Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)` in `EX/vision_lens/.../Live.kt:98, 116-122`; the `AtomicBoolean` is the same guarantee without a coroutine.

**Stop pattern** (`CameraFrames.kt:89-95`):
```kotlin
    override fun close() {
        ContextCompat.getMainExecutor(this.context).execute {
            this.provider?.unbindAll()
            ...
        }
        this.executor.shutdown()
    }
```

**First-frame log (RESEARCH Pattern 2):** no `android.util.Log` call exists in the app yet; follow `SelfTestRunner.kt:22` (`const val TAG = "VisionLens"`) with `private const val TAG = "FoF"` and log `width x height`, `cropRect`, `rotationDegrees`, `planes[0].rowStride/pixelStride` once (`AtomicBoolean logged`), then `adb logcat -s ClikaRT FoF`.

---

### `SRC/camera/RgbaPacker.kt` (utility, pure transform)

**Analogs:** `SRC/detect/Frame.kt` (the guard style and the target layout), `SRC/camera/FrameTransform.kt` (pure arithmetic + unit test), `CameraFrames.kt:76-77` (the byte layout the model wants).

**Target layout it must produce** (`Frame.kt:13-25`):
```kotlin
class Frame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val rotationDegrees: Int = 0,
    val timestampNanos: Long = 0L,
) {
    init {
        require(width > 0 && height > 0) { "frame size must be positive: ${width}x$height" }
        require(rgba.size == width * height * 4) {
            "rgba must be tightly packed (${width}x$height x 4 = ${width * height * 4} bytes), got ${rgba.size}"
        }
    }
```
Binding contract: `ImageInput.Rgba8888(pixels: ByteArray, width: Int, height: Int)` = "four bytes per pixel in the order red, green, blue, alpha, row by row" (`BIND/commonMain/io/clika/modelverse/Types.kt:800-807`); the native call has no stride parameter (`VisionModel.kt:235-245`).

**Guard style to copy** (`FrameTransform.kt:19-24`):
```kotlin
    init {
        require(frameWidth > 0 && frameHeight > 0 && viewWidth > 0 && viewHeight > 0) {
            "sizes must be positive: frame ${frameWidth}x$frameHeight, view ${viewWidth}x$viewHeight"
        }
        require(rotationDegrees == 0) { "TODO(stream 1): only rotation 0 is implemented, got $rotationDegrees" }
    }
```
-> `require(pixelStride == 4) { "RGBA_8888 expects pixelStride 4, got $pixelStride" }`, `require(rowStride >= width * 4) { … }`; signature `fun pack(src: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, rotationDegrees: Int, timestampNanos: Long = 0L): Frame` (RESEARCH Code Example 3). Output always `rotationDegrees = 0` so `FrameTransform.kt:23` keeps its `require`. Use `src.duplicate()` so the plane buffer's position is untouched.

---

### `SRC/detect/ClikaRtDetector.kt` (service, blocking inference)

**Analogs:** `EX/vision_lens/src/shared/src/commonMain/kotlin/io/clika/examples/visionlens/ModelverseEngine.kt` (one pipeline reused per process, `guarded` error mapping, close); `SRC/detect/FakeDetector.kt` (how a `Detector` is written in this repo). Current stub `ClikaRtDetector.kt:29-37` (`class ClikaRtDetector(private val modelDir: String) : Detector`, `name = "clikart:rtdetr_v2_r18vd"`, `detect` throws at `:32`, empty `close()` `:34-36`); its KDoc `:9-27` is the verified call sequence — keep it, fix step 2's "externalFilesDir" to `filesDir` (D-04) and delete the Vulkan sentence (D-06).

**Imports pattern** (subset of `ModelverseEngine.kt:4-14`):
```kotlin
import io.clika.modelverse.DetectOptions
import io.clika.modelverse.ImageInput
import io.clika.modelverse.Modelverse
import io.clika.modelverse.ModelverseException
import io.clika.modelverse.ObjectDetectionPipeline
import io.clika.modelverse.Task
import io.clika.runtime.ClikaRtException
```
Add `io.clika.modelverse.AutoModelForObjectDetection`, `io.clika.modelverse.VisionLoadOptions`, `io.clika.modelverse.VisionTask`. Name clash: the binding's `io.clika.modelverse.Detection`/`Box` vs the app's `com.chloeyeo.friendorfoe.detect.Detection`/`BoxF` — refer to the binding's by FQN (`List<io.clika.modelverse.Detection>` as `detectRaw`'s return type) and never wildcard-import `io.clika.modelverse.*` in this file.

**House `Detector` shape** (`FakeDetector.kt:8-12, 34`):
```kotlin
class FakeDetector(private val sweepTicks: Int = 60) : Detector {
    override val name: String = "fake"
    private var ticks = 0

    override fun detect(frame: Frame): List<Detection> {
        ...
        return listOf(person, drone) // score-descending, as the contract says
```
Contract: `Detector.kt:8-21` (`detect(frame): List<Detection>` already filtered through `LabelMap`, score-descending, blocking, lane only; `close()` default no-op).

**Core pattern: one pipeline, opened once, candidates empty for a closed-set model** (`ModelverseEngine.kt:21-22, 32-40`):
```kotlin
class ModelverseEngine(private val spec: ModelSpec) : VisionEngine {
    private var detector: ObjectDetectionPipeline? = null
    ...
    override fun detect(picture: Picture, phrases: List<String>): List<Found> = guarded {
        val pipeline = this.detector ?: (Modelverse.pipeline(Task.OBJECT_DETECTION, this.spec.detector, options()) as ObjectDetectionPipeline).also { this.detector = it }
        // An open-vocabulary detector takes the phrases; a detector with its own label table refuses any, so it gets none.
        val open = pipeline.model.capabilities.openVocabulary
        val candidates = if (open) phrases else emptyList()
        pipeline(image(picture), DetectOptions(maxResults = MAX_OBJECTS, scoreThreshold = if (open) PHRASE_THRESHOLD else SCORE_THRESHOLD), candidates).map {
            Found(it.label, it.score, it.box.left, it.box.top, it.box.right, it.box.bottom)
        }
    }
```
FoF opens the model explicitly (D-03/D-05) instead of `pipeline(task, source, options)`: `AutoModelForObjectDetection.fromPretrained(dir, VisionLoadOptions(offline = true, device = "cpu"))` then `Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline`. Binding signatures: `AutoModelForObjectDetection.fromPretrained(source: String, options: VisionLoadOptions = VisionLoadOptions()): VisionModel` (`BIND/commonMain/io/clika/modelverse/Auto.kt:283-287`); `VisionModel.open` "Blocks for the load; call it off the main thread", calls `Modelverse.requireLoaded()` first (`VisionModel.kt:192-204`; `Modelverse.kt:317-319` throws `ModelverseException("Modelverse.load has not succeeded in this process")`); `val capabilities: Capabilities` (`VisionModel.kt:23`) with `visionTasks`, `labels` ("the detector's own label table, in id order"), `openVocabulary` (`Types.kt:246-253`), `VisionTask.DETECT = "detect"` (`Types.kt:814-816`); `Modelverse.pipeline(task: String, model: PreTrainedModel): Pipeline` (`Modelverse.kt:197-202`); `Task.OBJECT_DETECTION = "object-detection"` (`Auto.kt:20`); `ObjectDetectionPipeline internal constructor` + `operator fun invoke(image: ImageInput, options: DetectOptions = DetectOptions(), candidateLabels: List<String> = emptyList()): List<Detection>` (`Pipelines.kt:297-317`); `data class DetectOptions(maxResults: Int = 100, scoreThreshold: Float = 0.5f, labels: List<String> = emptyList())` — labels "refused on a detector with its own label table" (`Types.kt:851-869`); `data class VisionLoadOptions(modelArgs = emptyMap(), revision = "main", cacheDir = null, token = null, offline = false, device = null, weights = null, dtype = null)` (`Types.kt:832-845`); outputs `data class Detection(label: String, labelId: Long, score: Float, box: Box)`, `data class Box(left, top, right, bottom)` in input pixels (`Types.kt:878, 885`).

**Mapping rows into the contract** (`ModelverseEngine.kt:38` shape + `LabelMap.accept`, `LabelMap.kt:39-51`):
```kotlin
    override fun detect(frame: Frame): List<Detection> = Dedup.apply(
        detectRaw(frame).mapNotNull { d ->
            LabelMap.accept(d.labelId, d.score)?.let { cls -> Detection(cls, d.score, BoxF(d.box.left, d.box.top, d.box.right, d.box.bottom)) }
        },
    )
```
(RESEARCH Code Example 2; the binding answers score-descending, `VisionModel.kt:37-39`, and `mapNotNull` keeps the order.)

**Close pattern** (`ModelverseEngine.kt:74-83`): `this.detector?.model?.close()` then null the field; `Pipeline.close()` closes the model (`Pipelines.kt:26`), `VisionModel.close()` cancels in-flight work, is idempotent (`VisionModel.kt:92-103`). Never called during the demo (D-03).

**Error-handling pattern** (`ModelverseEngine.kt:85-91`):
```kotlin
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: ClikaRtException) {
        throw PlatformException(e.codeName, e.message ?: e.codeName)
    } catch (e: ModelverseException) {
        throw PlatformException(e.codeName, e.message ?: e.codeName)
    }
```
Both are `RuntimeException` siblings: `class ClikaRtException(val status: Status, val codeName: String, message: String)` (`BIND/commonMain/io/clika/runtime/ClikaRt.kt:31-39`), `class ModelverseException(message: String, val codeName: String = "", cause: Throwable? = null)` — "empty for a failure of the binding itself" (`ModelverseException.kt:14-18`). FoF has no `PlatformException`; let `open()` throw and let `FofApplication.loadRuntime` map to `modelNote`.

**Warm-up (D-06):** one call of the pipeline on `ImageInput.Rgba8888(ByteArray(640 * 640 * 4), 640, 640)` inside `open()`; zero rows is expected (Pitfall D2). Capability assert (D-05): `check(labels.size == 80 && labels[4] == "aeroplane" && labels[0] == "person")` — the message must list `labels.take(5)`, never the model path's parent.

---

### `SRC/detect/Dedup.kt` (utility, pure)

**Analogs:** `SRC/track/Tracker.kt:28-48` (greedy best-IoU-first pairing with taken flags), `SRC/detect/BoxF.kt:22-29` (`iou`).

**Pairing pattern to copy** (`Tracker.kt:29-48`):
```kotlin
        val candidates = ArrayList<Triple<Float, Int, Int>>() // iou, track index, detection index
        for ((ti, track) in live.withIndex()) {
            for ((di, det) in detections.withIndex()) {
                if (det.cls != track.cls) continue
                val iou = track.box.iou(det.box)
                if (iou >= iouThreshold) candidates += Triple(iou, ti, di)
            }
        }
        candidates.sortByDescending { it.first }

        val trackTaken = BooleanArray(live.size)
        val detectionTaken = BooleanArray(detections.size)
```
`Dedup.apply(list: List<Detection>, iouThreshold: Float = 0.5f): List<Detection>` walks the (already score-descending) list, keeps a box unless it overlaps an already-kept box at or above the threshold, **except** that a DRONE box replaces an overlapping kept PERSON box (D-08 "keep the drone-class one"); same class -> higher score (first in order) wins. Keep it an `object` like `LabelMap`. Pitfall D1: RT-DETR is NMS-free, one prop can come back as aeroplane *and* kite.

---

### `SRC/detect/DetectionsJson.kt` (utility, pure parser) and `SRC/detect/HttpDetector.kt` (service, HTTP)

**Analogs:** `SRC/signal/GadgetLineCodec.kt` (pure `org.json` parser, tolerant of extra fields, loud on malformed input, unit-tested with the real jar); `FakeDetector.kt` for the `Detector` shape. Current stub `HttpDetector.kt:19-24` (`class HttpDetector(private val baseUrl: String) : Detector`, `name = "http:" + baseUrl.removePrefix("http://").trimEnd('/')` at `:20` — keep). KDoc `:5` and `:17` say port 8129; the project standard is 8000 (Pitfall D4) — fix both.

**Parser pattern** (`GadgetLineCodec.kt:43-56, 89-91`):
```kotlin
    fun parseLine(line: String): SignalEvent? {
        val text = line.trim()
        if (text.isEmpty()) return null
        val obj = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IllegalArgumentException("gadget line is not a JSON object: $text", e)
        }
        return when (obj.optString("type", "")) {
            TYPE_ANSWER -> parseAnswer(obj, text)
            ...
    /** `isNull` covers both an absent key and a JSON null on Android's and on Maven's org.json alike. */
    private fun stringOrNull(obj: JSONObject, key: String): String? =
        if (obj.isNull(key)) null else obj.get(key).toString()
```
-> `object DetectionsJson { fun parse(body: String): List<RawRow> }` reading `detections[]` -> `box.{xmin,ymin,xmax,ymax}` (`getDouble`), `score`, `label_id` (`getLong`), `label` (`optString`), tolerating unknown fields; return raw rows (`labelId`, `score`, `BoxF`) so `HttpDetector.detect` applies the same `LabelMap.accept` + `Dedup` as `ClikaRtDetector` (one mapping function shared by both detectors is the cleanest). Imports: `org.json.JSONException`, `org.json.JSONObject` (`GadgetLineCodec.kt:4-5`); the real jar is `testImplementation` (`app/build.gradle.kts:85`, `gradle/libs.versions.toml:44-45`).

**HTTP call:** no OkHttp code exists in the repo (`okhttp3` appears only in the `HttpDetector` KDoc), so the request is RESEARCH Code Example 6 (`MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("image", "frame.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))…`, `callTimeout(2, SECONDS)`, field names `image`/`confidence`/`max_detections`, `POST $baseUrl/v1/detections`). `Frame` -> JPEG via `Bitmap.createBitmap(w, h, ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(frame.rgba)) }` is the same `copyPixelsFromBuffer` call as `Platform.android.kt:120-124`. Dependency alias `libs.okhttp` = `com.squareup.okhttp3:okhttp-jvm:5.5.0` (`gradle/libs.versions.toml:42`); manifest already has `usesCleartextTraffic="true"` (`AndroidManifest.xml:30`) and INTERNET (`:7`). Guard stays in `Detectors.create` (`Detectors.kt:14-19`, `BuildConfig.ALLOW_HTTP_DETECTOR` from `app/build.gradle.kts:42, 47`).

---

### `SRC/session/IffCoordinator.kt` (service / session, event-driven)

**Analog:** itself (`tick` is real), plus `SRC/session/EventLog.kt` for the `synchronized` style and `SRC/track/BoxLock.kt` for the lock type. Current: constructor `:31-38`, `_state`/`state` `:39-42`, `previewWidth/Height` `:44-45`, `iffById` `:47`, `lock` with private setter `:49-51`, `setPreviewSize` `:54-57`, `tick` `:60-86`, `log` `:89-92`, `challenge` stub `:95` (`fun challenge(boxId: Int): Nothing = throw NotImplementedError("integration lead")`), `close` `:97-100`.

**Core `tick` pattern (keep, extend)** (`IffCoordinator.kt:60-86`):
```kotlin
    fun tick(frame: Frame) {
        val t0 = clock()
        val detections = detector.detect(frame)
        val t1 = clock()
        tracker.update(detections)

        val transform = if (previewWidth > 0 && previewHeight > 0) {
            FrameTransform(frame.width, frame.height, previewWidth, previewHeight)
        } else {
            FrameTransform.identity(frame.width, frame.height)
        }
        val live = tracker.tracks
        iffById.keys.retainAll(live.map { it.id }.toSet())
        val trackUis = live.map { track ->
            TrackUi(track.id, track.cls, transform.toPreview(track.box), iffById[track.id] ?: IffState.Unchallenged)
        }
        val fps = if (lastTickMillis > 0L && t1 > lastTickMillis) 1000f / (t1 - lastTickMillis) else 0f
        lastTickMillis = t1

        _state.update { s ->
            s.copy(
                tracks = trackUis,
                status = s.status.copy(msPerFrame = t1 - t0, fps = fps, modelReady = true),
                events = eventLog.snapshot(),
            )
        }
    }
```
Phase 2 edits (RESEARCH Patterns 5–6): `tracker.update(detections, t1)` (wall-clock sticky); exempt `lock?.boxId` from the `retainAll` at `:72` (Pitfall B3); append a synthetic `TrackUi(lock.boxId, ObjClass.DRONE, transform.toPreview(lock.rect), IffState.Challenged(lock.untilMillis))` when the lock is active and no live track carries its id; drop lock + state on expiry; `status.copy(…, offline = offlineProbe())`; keep `modelReady = true` on a successful tick (the loader sets it earlier via `setStatus`).

**`challenge` (step 1 of KDoc `:19-21` only):** `BoxLock(boxId, track.box, clock() + BoxLock.WINDOW_MS)` (`BoxLock.kt:10-18`: `isActive(now)`, `remainingMillis(now)`, `WINDOW_MS = 10_000L`); `iffById[boxId] = IffState.Challenged(until = lock.untilMillis)` (`ObserverUiState.kt:59`, `windowMs` defaults to 10 s); `log("challenge · box $boxId")` (`log` at `:89-92`); refuse while `lock?.isActive(clock()) == true`. The tracker exposes frame-pixel boxes through `tracker.tracks` (`Tracker.kt:23`).

**Thread-safety pattern** (`EventLog.kt:9-21`):
```kotlin
    private val items = ArrayDeque<EventUi>()

    val size: Int get() = synchronized(items) { items.size }

    fun add(t: Long, text: String): EventUi = synchronized(items) {
        ...
    /** Newest first. */
    fun snapshot(): List<EventUi> = synchronized(items) { items.toList() }
```
-> one `private val stateLock = Any()`; `synchronized(stateLock)` around the post-detect section of `tick` and all of `challenge`/`setStatus` (the blocking `detector.detect` stays outside); `@Volatile private var previewWidth/previewHeight` (`FakeSignalSource.kt:28` shows the repo's `@Volatile` usage). Update the class KDoc `:29` ("Not thread-safe") accordingly.

**`setStatus` (new):** `fun setStatus(update: (StatusUi) -> StatusUi) = _state.update { it.copy(status = update(it.status)) }` — the same `_state.update { }` idiom as `:79-85` and `:91`.

---

### `SRC/session/ObserverUiState.kt` (model, `[shared]` contract)

**Analog:** itself. Current `StatusUi` (`ObserverUiState.kt:26-34`):
```kotlin
data class StatusUi(
    val detectorName: String = "none",
    val msPerFrame: Long = 0L,
    val fps: Float = 0f,
    val modelReady: Boolean = false,
    val offline: Boolean = true,
    val gadgetConnected: Boolean = false,
    val rssiDbm: Int? = null,
)
```
Add `val modelNote: String? = null` as the **last** parameter. Every existing construction uses named arguments or defaults (`IffCoordinator.kt:39` `StatusUi(detectorName = detector.name)`; `DemoScenario.kt:37-45` fully named), so the change is source-compatible. Commit it alone as `[shared] StatusUi.modelNote` and push (README §3 rule; RESEARCH constraints table).

---

### `SRC/track/Tracker.kt` (service, pure)

**Analog:** itself. Current `Track` (`Tracker.kt:17`: `data class Track(val id: Int, val cls: ObjClass, val box: BoxF, val score: Float, val misses: Int = 0)`), constructor `:14` (`iouThreshold = 0.3f`, `maxMisses = 3`), coasting rule `:50-58`:
```kotlin
        for ((ti, track) in live.withIndex()) {
            val u = updated[ti]
            if (u != null) {
                next += u
            } else if (track.misses + 1 <= maxMisses) {
                next += track.copy(misses = track.misses + 1)
            }
        }
```
D-09 edit: add `lastSeenMillis: Long` to `Track` (default `0L` keeps `TrackerTest` constructor-free), add `fun update(detections: List<Detection>, nowMillis: Long): List<Track>` where a missed track survives while `nowMillis - lastSeenMillis <= stickyMs(cls)` (DRONE 5000 ms, PERSON ~1500 ms), and keep `update(detections)` delegating with the frame-count rule so the 9 existing tests (`TrackerTest.kt:27-111`) stay green. `misses` keeps counting (the `:65` return filter `live.filter { it.misses == 0 }` depends on it).

---

### `SRC/ui/CameraScreen.kt` (component)

**Analogs:** `EX/vision_lens/src/shared/src/androidMain/kotlin/io/clika/examples/visionlens/LivePane.android.kt` (preview under a Canvas); itself (`CameraScreen` `:52-93`, `StatusStrip` `:96-113`). The `[shared]` ownership note at `:48-50` says the preview slot is stream 3's — Phase 2 adds the minimal slot only (RESEARCH Pattern 7).

**Preview-under-Canvas pattern** (`LivePane.android.kt:29-38`):
```kotlin
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; CameraRoad.previewView = this } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { CameraRoad.previewView = null },
            )
            ...
            if (answer != null && frame != null) Canvas(modifier = Modifier.fillMaxSize()) { this.drawAnswer(answer, this.fit(frame.width, frame.height), measurer) }
        }
```
(Imports `androidx.camera.view.PreviewView`, `androidx.compose.ui.viewinterop.AndroidView`, `LivePane.android.kt:3, 13`.) FoF uses `FILL_CENTER` (D-10, matches `FrameTransform`) and keeps the `AndroidView` out of `ui/` by taking it as a slot:

**Slot insertion point** (`CameraScreen.kt:52-59, 71-72`):
```kotlin
@Composable
fun CameraScreen(
    state: ObserverUiState,
    modifier: Modifier = Modifier,
    onTapTrack: (Int) -> Unit = {},
    onNextState: (() -> Unit)? = null,
    nextStateLabel: String = "next state",
) {
    ...
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
```
Add `preview: (@Composable BoxScope.() -> Unit)? = null` and `onSizeChanged: ((Int, Int) -> Unit)? = null`; call `preview?.invoke(this)` as the first child of the `Box` and put `Modifier.onSizeChanged { onSizeChanged?.invoke(it.width, it.height) }` on the `Box` so `IffCoordinator.setPreviewSize` receives the overlay's pixel size. `DemoCameraScreen` (`:145-159`) and the `@Preview`s (`:161-181`) compile unchanged.

**Status chip pattern** (`CameraScreen.kt:102-111`): the `chips` list — append `status.modelNote ?: ""` (or render "model loading… N s" in place of the `"model loading"` chip at `:106`).

---

### `SRC/MainActivity.kt` (controller / activity, event-driven)

**Analogs:** `EX/vision_lens/src/androidApp/src/main/kotlin/io/clika/examples/visionlens/android/MainActivity.kt` (permission contract, intent extras, Application attach), `EX/android/hello/.../MainActivity.kt:60-74` (runtime work on the lane, lines read from state). Current `MainActivity.kt:14-24` (`ComponentActivity`, `enableEdgeToEdge()`, `setContent { FriendOrFoeTheme { DemoCameraScreen() } }`) — keep the theme wrapper.

**Permission pattern** (`vision_lens MainActivity.kt:54-56, 82-87`):
```kotlin
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) this.model.toggleLive() else this.model.status.value = "Without the camera permission the Live button cannot see."
    }
    ...
        this.model.liveGate = {
            if (CameraSource.file != null || CameraRoad.permitted(this)) true
            else { this.askCamera.launch(Manifest.permission.CAMERA); false }
        }
```
with `CameraRoad.permitted` = `ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED` (`CameraFrames.kt:38`). Imports: `android.Manifest`, `androidx.activity.result.contract.ActivityResultContracts`, `androidx.core.content.ContextCompat`.

**Intent-extra pattern (parity harness, thread count, detector flip)** (`vision_lens MainActivity.kt:65-67, 113-116`):
```kotlin
        this.intent?.getStringExtra("camera_file")?.let { CameraSource.file = it }
        this.intent?.getIntExtra("camera_fps", 0)?.takeIf { it > 0 }?.let { CameraSource.fps = it }
        val picture = this.intent?.getStringExtra(EXTRA_PICTURE)
    ...
    companion object {
        const val EXTRA_PICTURE = "picture"
        const val EXTRA_TASK = "task"
        const val EXTRA_OUT = "out"
```
FoF extras (debug builds only, `BuildConfig.DEBUG`): `clika_threads` (Int, default 2), `parity` (asset path), `dump_frame` (Boolean), `detector` (encoded `DetectorConfig`, e.g. `http:http://192.168.x.y:8000`, written through `Prefs.detectorConfig`). Launch: `adb shell am start -n com.chloeyeo.friendorfoe/.MainActivity --es parity parity/scene.png` (RESEARCH Pattern 8).

**Application attach + trim** (`vision_lens MainActivity.kt:24-34`):
```kotlin
class VisionLensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Platform.attach(this, BuildConfig.CLIKA_RT_LICENSE)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ClikaRtAndroid.trimOnMemoryPressure(this)
    }
}
```
(FoF: the license is read in `FofApplication.loadRuntime`, started from `MainActivity.onCreate` via `RuntimeLane.executor.execute { … }` so `onCreate` stays light.)

**Lane dispatch + state read pattern** (`hello/MainActivity.kt:64-66`):
```kotlin
    LaunchedEffect(attempt) {
        lines = withContext(RuntimeLane.dispatcher) { helloLines(context.applicationContext, license) }
    }
```
FoF: `val state by coordinator.state.collectAsState()` (NOT `collectAsStateWithLifecycle`, Pitfall C6 — `lifecycle-runtime-compose` is not declared, `app/build.gradle.kts:67-92`); `RuntimeLane.executor`/`dispatcher` at `SRC/app/RuntimeLane.kt:14-19`. The "model loading… N s" ticker is a `lifecycleScope.launch { while (!ready) { coordinator.setStatus { … }; delay(1000) } }` (RESEARCH Pattern 6). `onDestroy` -> `cameraController.stop()` only.

---

### Parity harness (debug-only, discretionary placement) + `assets/parity/scene.png`

**Analogs:** `EX/vision_lens/src/androidApp/.../SelfTestRunner.kt` (self-test on its own thread, each line to logcat under one TAG and to a file, `PASS`/`FAIL` marker), `EX/vision_lens/.../Platform.android.kt:94-101, 120-130` (asset decode to ARGB_8888, RGBA <-> Bitmap, file write).

**Runner pattern** (`SelfTestRunner.kt:21-22, 38-40, 49-54, 67-72`):
```kotlin
object SelfTestRunner {
    const val TAG = "VisionLens"
    ...
    fun start(model: AppModel, picture: String, ...) {
        thread(name = "self-test") { run(model, picture, tasks, outDir, spec, phrases, labels, liveSeconds) }
    }
    ...
            result = SelfTest.run(SelfTest.Options(...), render = null) { line ->
                Log.i(TAG, line)
                lines.append(line).append('\n')
                model.status.value = line
            }
    ...
        } finally {
            model.phase.value = Phase.Idle
            File(outDir).mkdirs()
            File(outDir, "self_test.out").writeText(lines.toString())
            File(outDir, "self_test.done").writeText(if (result?.ok == true) "PASS\n" else "FAIL\n")
        }
```
FoF: run on `RuntimeLane.executor` (not a new thread; the model is lane-only), `Log.i("FoF", "$labelId $score $left $top $right $bottom")` per raw row from `ClikaRtDetector.detectRaw`, optional `filesDir/dumps/parity.out`. The CLI reference command for the same image is in RESEARCH Pattern 8.

**Decode pattern** (`Platform.android.kt:100-101, 120-124`):
```kotlin
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw PlatformException("PICTURE", "$name is not a picture the app can decode")
    ...
    actual fun rgbaBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        return bitmap.asImageBitmap()
    }
```
Asset -> `Frame`: `context.assets.open("parity/scene.png").readBytes()` -> `BitmapFactory.decodeByteArray(..., ARGB_8888)` -> `ByteArray(w*h*4)` + `bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba))` (`CameraFrames.kt:76-77`) -> `Frame(w, h, rgba)`. Frame dump (PNG): the `rgbaBitmap` pattern above + `compress(Bitmap.CompressFormat.PNG, 100, out)` (`Platform.android.kt:115-116`) + `writeFile` (`:126-130`, `parentFile?.mkdirs()` then `writeBytes`). Asset source: `EX/vision_lens/samples/scene.png` (2851 B) -> `app/src/main/assets/parity/scene.png` (`assets/` must be created; AGP picks it up with no Gradle change).

---

### Tests (`TEST/…`, JUnit 4, `:app:testDebugUnitTest --offline`)

**`RgbaPackerTest.kt` <- `FrameTransformTest.kt:8-18, 67-70`:**
```kotlin
class FrameTransformTest {
    private val eps = 1e-3f

    @Test
    fun `identity when frame and view match`() {
        val t = FrameTransform(1280, 720, 1280, 720)
        assertEquals(1f, t.scale, eps)
    ...
    @Test
    fun `rotation other than 0 is not implemented yet`() {
        assertThrows(IllegalArgumentException::class.java) { FrameTransform(1280, 720, 2400, 1080, rotationDegrees = 90) }
    }
```
Cases: tight plane copied as-is; padded rows (`rowStride = width*4 + 8`) stripped; 180° reverses the pixel sequence (3x2 synthetic frame, distinct byte per pixel); `pixelStride != 4` and `rowStride < width*4` throw `IllegalArgumentException`; `ByteBuffer.wrap` builds the input. Also add the 1280x720 -> 1600x720 case (A06 screen) to `FrameTransformTest` (`scale = 1.25f`, `offsetY = -90f`).

**`DedupTest.kt` <- `TrackerTest.kt:11-16`:**
```kotlin
class TrackerTest {
    private fun drone(l: Float, t: Float, r: Float, b: Float, score: Float = 0.6f) =
        Detection(ObjClass.DRONE, score, BoxF(l, t, r, b))

    private fun person(l: Float, t: Float, r: Float, b: Float, score: Float = 0.9f) =
        Detection(ObjClass.PERSON, score, BoxF(l, t, r, b))
```
Cases: two drone boxes IoU 0.8 -> one (higher score); drone + person overlapping -> drone kept even with the lower score; disjoint boxes untouched; order preserved.

**`DetectionsJsonTest.kt` <- `GadgetLineCodecTest.kt:12-13, 32-43`:** raw JSON string literals (`"""{"detections":[{"box":{"xmin":1,"ymin":2,"xmax":30,"ymax":40},"score":0.61,"label_id":4,"label":"aeroplane"},…]}"""`), `assertEquals` on `labelId`/`score`/`BoxF`, extra fields tolerated, empty `detections` -> empty list, malformed body -> `IllegalArgumentException` via `assertThrows` (`GadgetLineCodec.kt:46-50`). Real `org.json` is on the test classpath (`app/build.gradle.kts:85`).

**`OfflineRuleTest.kt` <- `LabelMapTest.kt:7-16`:** four truth-table cases of `OfflineRule.isOffline(airplane, validated)`.

**`TrackerTest.kt` sticky cases <- `:67-82` (`a missed track coasts then is dropped after maxMisses`):** drive `update(detections, nowMillis)` with explicit millis: a DRONE missed at +1000, +3000, +4900 still in `tracks`, gone at +5100; a PERSON gone after ~1500 ms; the frame-count overload unchanged.

**`IffCoordinatorTest.kt` lock cases <- `:15-33` (injectable `clock`):**
```kotlin
        var clock = 1_000L
        val coordinator = IffCoordinator(FakeDetector(), FakeSignalSource(), clock = { clock })
        ...
        clock += 180
        coordinator.tick(frame())
```
Delete `:45-49` (`challenge is the integration lead's TODO`, asserts `NotImplementedError`) and add: tap on the drone id -> `Challenged` with `until = clock + 10_000`; a detector that returns nothing next tick (a tiny `Detector` stub inside the test, like `FakeDetector`) still yields a `TrackUi` with the locked id and rect mapped through the transform; after `clock += 10_001` the lock is gone; a second `challenge` during the window is refused; `offlineProbe` value lands in `status.offline`; `setStatus` publishes `modelNote`.

## Shared Patterns

### Runtime lane: every ClikaRT call off the main thread
**Source:** `SRC/app/RuntimeLane.kt:14-19` (= `EX/android/shared/.../RuntimeLane.kt:13-16`)
**Apply to:** `FofApplication.loadRuntime`, `ClikaRtDetector.open/detect/detectRaw/close`, `IffCoordinator.tick`, parity harness
```kotlin
object RuntimeLane {
    val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "clika-runtime-lane") }

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
}
```
Binding reasons: `VisionModel.open` "Blocks for the load; call it off the main thread" (`VisionModel.kt:192-196`); pipelines block the caller on a `CountDownLatch` (`Pipelines.kt:14-15, 103-120`). The camera analyzer gets its **own** executor (D-02); fix the `RuntimeLane` KDoc `:9-12` that still says the lane "doubles as the CameraX analysis executor".

### Exception handling: catch both, branch on `codeName`, never on text
**Source:** `ModelverseEngine.kt:85-91`, `Platform.android.kt:62-71`, `hello/MainActivity.kt:97-102`; types at `ClikaRt.kt:31-39`, `ModelverseException.kt:14-18`
**Apply to:** `FofApplication.loadRuntime`, `ClikaRtDetector`, parity harness, `HttpDetector` (`IOException` + `IllegalStateException` for non-2xx)
Rule: `catch (e: ClikaRtException) { note(e.codeName) } catch (e: ModelverseException) { note(e.codeName.ifEmpty { e.message.orEmpty().take(80) }) }`; the credential is never in any message (the runtime "never logs" it, `ClikaRtAndroid.kt:38-39`), and `BuildConfig.CLIKA_RT_LICENSE` is referenced by name only.

### Pure Kotlin logic, Android only at the edges
**Source:** `SRC/detect/BoxF.kt:6-11` (why `RectF` is avoided), `Frame.kt:13-25`, `FrameTransform.kt`, `LabelMap.kt`
**Apply to:** `RgbaPacker` (takes `ByteBuffer`, not `ImageProxy`), `Dedup`, `DetectionsJson` (takes `String`), `OfflineRule` (takes two booleans), `Tracker` (takes millis)
```kotlin
 * An axis-aligned box in pixel coordinates … Pure Kotlin on purpose: `android.graphics.RectF` is an empty stub in local
 * unit tests (its constructor leaves every field 0 and its methods throw "not mocked"), so the tracker, the
 * frame transform and the UI state carry this type instead and stay testable with `:app:testDebugUnitTest`.
```
Guards use `require(...) { "…: $value" }` / `check(...) { "…" }` with the offending value in the message (`Frame.kt:21-24`, `FrameTransform.kt:20-23`, `EventLog.kt:6`, `Detectors.kt:15-17`).

### Injectable clock and `StateFlow` publication
**Source:** `IffCoordinator.kt:37-42, 79-85`
**Apply to:** `IffCoordinator` edits, `Tracker.update(detections, nowMillis)`, tests
```kotlin
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(ObserverUiState(status = StatusUi(detectorName = detector.name)))
    val state: StateFlow<ObserverUiState> = _state.asStateFlow()
    ...
        _state.update { s -> s.copy(...) }
```
Wall-clock millis everywhere (`BoxLock.untilMillis`, `IffState.Challenged.until`, `EventUi.t`); never `System.nanoTime` in session state (the `Frame.timestampNanos` is only for pacing).

### `org.json` usage
**Source:** `GadgetLineCodec.kt:4-5, 43-56, 89-91`; test classpath `app/build.gradle.kts:85`
**Apply to:** `DetectionsJson` (+ later `EventClient`)
Platform `org.json` in `app` only, never in `iff`; `isNull` for absent-or-null; wrap `JSONException` into `IllegalArgumentException` with the offending text.

### JUnit 4 test style
**Source:** `TEST/track/TrackerTest.kt`, `TEST/camera/FrameTransformTest.kt`, `TEST/session/IffCoordinatorTest.kt`
**Apply to:** all six test files
`org.junit.Assert.*` static imports, backtick sentence names, private helper factories at the top of the class, `assertEquals(expected, actual, eps)` for floats, `assertThrows(X::class.java) { … }` for guards, no mocking library (fakes are hand-written like `FakeDetector`).

### Secrets and build flags
**Source:** `app/build.gradle.kts:8-19, 40-48`; `.gitignore` covers `*.license`, `local.properties`
**Apply to:** `FofApplication`, `Detectors`, `MainActivity` extras
```kotlin
        buildConfigField("String", "CLIKA_RT_LICENSE", "\"" + buildTimeLicense.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        // HttpDetector (laptop `clikart-cli serve`) is a development/contingency path, never in a release build.
        buildConfigField("boolean", "ALLOW_HTTP_DETECTOR", "false")
    ...
        debug {
            buildConfigField("boolean", "ALLOW_HTTP_DETECTOR", "true")
        }
```
Intent extras that change behaviour are honoured only when `BuildConfig.DEBUG`/`ALLOW_HTTP_DETECTOR`.

### Logging tag
**Source:** `SelfTestRunner.kt:22` (`const val TAG = "VisionLens"`), `ClikaRtAndroid.kt:56` (`"ClikaRT"`)
**Apply to:** `CameraController` first-frame line, `FofApplication.loadRuntime` timings, parity rows
`private const val TAG = "FoF"`; `adb logcat -s ClikaRT FoF`. No `Log` call may include the license, the model path's parent directories, or raw frame bytes.

### Binding call sequence (the one order that works)
**Source:** `ClikaRtDetector.kt:9-24` (skeleton KDoc, verified) + the signatures cited above
**Apply to:** `FofApplication.loadRuntime` -> `ClikaRtDetector.open`
`Os.setenv("CLIKA_RT_NUM_THREADS", n, true)` -> `Modelverse.load(appContext, license)` -> `ClikaRtAndroid.bridgeLogs()` -> `ClikaRtAndroid.trimOnMemoryPressure(appContext)` -> tensor probe -> dir check (`config.json`, `model.safetensors`, `preprocessor_config.json`) -> `AutoModelForObjectDetection.fromPretrained(dir, VisionLoadOptions(offline = true, device = "cpu"))` -> capability asserts -> `Modelverse.pipeline(Task.OBJECT_DETECTION, model) as ObjectDetectionPipeline` -> warm-up -> `modelReady`. Anything before `Modelverse.load` throws `ModelverseException("Modelverse.load has not succeeded in this process")` (`Modelverse.kt:317-319`); `Backends.isAvailable(VULKAN)` is never called (maps 139 MB).

## No Analog Found

| File / part | Role | Data Flow | Reason | Use instead |
|-------------|------|-----------|--------|-------------|
| `HttpDetector` — the OkHttp multipart request | service (HTTP) | request-response | No OkHttp code in the repo (`EventClient.kt:14-20` is a stub; `okhttp3` appears only in KDoc) and none in the official samples | RESEARCH Code Example 6 + `laptop/README.md` contract (`image`, `confidence`, `max_detections`; port 8000) |
| `OfflineProbe` — the `Settings.Global` / `ConnectivityManager` calls | utility (probe) | polled | No connectivity code anywhere in the repo or samples | RESEARCH Code Example 5 and §O (constants verified against android.jar 36) |

## Suggested `<read_first>` lists per plan (from RESEARCH §H waves)

| Plan | Read first (exact ranges) |
|------|---------------------------|
| Wave 0 — tests, `[shared]` `modelNote`, `Prefs` default | `TEST/camera/FrameTransformTest.kt:1-70`, `TEST/track/TrackerTest.kt:1-30, 67-82`, `TEST/session/IffCoordinatorTest.kt:1-50`, `TEST/signal/GadgetLineCodecTest.kt:1-45`, `SRC/session/ObserverUiState.kt:26-34`, `SRC/session/DemoScenario.kt:37-45`, `SRC/app/Prefs.kt:12-18, 70-72`, `SRC/detect/DetectorConfig.kt:21-30` |
| Wave 1 — runtime + `ClikaRtDetector` + `Dedup` + `HttpDetector` + extras | `SRC/app/FofApplication.kt:1-47`, `SRC/detect/ClikaRtDetector.kt:1-37`, `SRC/detect/FakeDetector.kt:8-35`, `SRC/detect/Detector.kt:8-21`, `SRC/detect/LabelMap.kt:39-51`, `SRC/track/Tracker.kt:28-48`, `SRC/signal/GadgetLineCodec.kt:43-56, 89-91`, `EX/android/shared/.../SampleRuntime.kt:1-34`, `EX/android/hello/.../MainActivity.kt:76-104`, `EX/android/hello/.../HelloApplication.kt`, `EX/vision_lens/.../Platform.android.kt:59-72`, `EX/vision_lens/.../ModelverseEngine.kt:1-40, 74-101`, `BIND/commonMain/io/clika/modelverse/Auto.kt:11-20, 282-287`, `VisionModel.kt:20-23, 36-52, 85-103, 192-218`, `Pipelines.kt:19-26, 102-120, 293-318`, `Types.kt:240-253, 797-885`, `Modelverse.kt:197-202, 317-319`, `ModelverseException.kt`, `BIND/androidMain/io/clika/modelverse/ModelverseAndroid.kt:17-30`, `BIND/androidMain/io/clika/runtime/ClikaRtAndroid.kt:41-44, 80-87, 100-104, 171-178`, `BIND/commonMain/io/clika/runtime/ClikaRt.kt:25-39, 56-62` |
| Wave 2 — `RgbaPacker`, `CameraController`, `MainActivity`, preview slot, `OfflineProbe`, lock + sticky | `SRC/camera/CameraController.kt:1-33`, `SRC/camera/FrameTransform.kt:12-33`, `SRC/detect/Frame.kt:13-25`, `SRC/session/IffCoordinator.kt:31-101`, `SRC/session/EventLog.kt:9-21`, `SRC/track/BoxLock.kt:10-18`, `SRC/track/Tracker.kt:14-17, 50-66`, `SRC/ui/CameraScreen.kt:43-113`, `SRC/MainActivity.kt:1-24`, `SRC/app/RuntimeLane.kt:1-19`, `app/src/main/AndroidManifest.xml:7-10, 32-38`, `EX/vision_lens/.../CameraFrames.kt:47-104`, `EX/vision_lens/.../LivePane.android.kt:20-41`, `EX/vision_lens/.../Live.kt:80-123`, `EX/vision_lens/src/androidApp/.../MainActivity.kt:23-34, 45-96, 113-116` |
| Wave 3 — parity harness, dump, prop test, README numbers | `EX/vision_lens/src/androidApp/.../SelfTestRunner.kt:14-75`, `EX/vision_lens/.../Platform.android.kt:94-130`, `EX/vision_lens/.../CameraFrames.kt:72-79`, RESEARCH Pattern 8 (CLI command), `EX/vision_lens/samples/scene.png` |

## Metadata

**Analog search scope:** `app/src/main/java/com/chloeyeo/friendorfoe/**` (38 files), `app/src/test/java/com/chloeyeo/friendorfoe/**` (7 files), `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `gradle/libs.versions.toml` (grep), `C:/Users/C/clika/reference/examples-0.6.4/android/{shared,hello}/**`, `C:/Users/C/clika/reference/examples-0.6.4/vision_lens/src/{shared,androidApp}/**`, `C:/Users/C/clika/reference/clika-runtime-0.6.4-sources/{commonMain,androidMain}/io/clika/{modelverse,runtime}/**` (targeted ranges only)
**Files read in full:** 31 skeleton sources + 6 tests (partial: 2) + 11 sample files; binding files read by targeted `sed -n` ranges (`Auto.kt`, `VisionModel.kt`, `Pipelines.kt`, `Types.kt`, `Modelverse.kt`, `ModelverseException.kt`, `ModelverseAndroid.kt`, `ClikaRtAndroid.kt`, `ClikaRt.kt`; `Tensors.kt`/`Ops.kt` by grep)
**Repo-wide greps:** `okhttp3` (KDoc only), `ConnectivityManager|Settings.Global|getSystemService` (USB KDoc only), `android.util.Log` (none), `AtomicBoolean|synchronized(|@Volatile` (`EventLog`, `FakeSignalSource`)
**Not read, by rule:** the license file and any credential text (the prefix the orchestrator named); no gradle or adb was run; no source file was modified
**Pattern extraction date:** 2026-10-10
