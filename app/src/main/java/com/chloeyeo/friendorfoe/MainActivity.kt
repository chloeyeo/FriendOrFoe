package com.chloeyeo.friendorfoe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chloeyeo.friendorfoe.app.DebugHooks
import com.chloeyeo.friendorfoe.app.FofApplication
import com.chloeyeo.friendorfoe.app.LaunchOptions
import com.chloeyeo.friendorfoe.camera.CameraController
import com.chloeyeo.friendorfoe.detect.ClikaRtDetector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.ui.CameraScreen
import com.chloeyeo.friendorfoe.ui.theme.FriendOrFoeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The observer: asks for CAMERA, starts the runtime, binds the camera under the overlay and renders the
 * process-singleton coordinator's state (D-03). Kept small; the UI stream owns the screen layout.
 */
class MainActivity : ComponentActivity() {
    private lateinit var app: FofApplication
    private var cameraController: CameraController? = null
    @Volatile private var previewView: PreviewView? = null
    private val cameraStarted = AtomicBoolean(false)
    @Volatile private var cameraPermitted = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermitted = granted
        if (granted) maybeStartCamera() else app.coordinator.setStatus { it.copy(modelNote = "camera permission denied") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app = FofApplication.from(this)
        val launch = LaunchOptions.from(app, intent) // before app.coordinator: it may change the detector pref
        cameraController = CameraController(this, launch.analysisSize ?: Size(1280, 720))
        val coordinator = app.coordinator
        app.startRuntimeIfNeeded(launch.threads)
        if (launch.logRaw) (coordinator.detector as? ClikaRtDetector)?.rawListener = { DebugHooks.logRows("raw", it) }
        DebugHooks.dumpNextFrame = launch.dumpFrame
        launch.parityAsset?.let(app::runParity)
        // D-07: count the load seconds on the strip until the loader writes its own note.
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            while (app.runtimeLoading) {
                val n = (System.currentTimeMillis() - t0) / 1000
                coordinator.setStatus { s ->
                    if (s.modelNote == null || s.modelNote.startsWith("model loading")) s.copy(modelNote = "model loading… $n s") else s
                }
                delay(1000)
            }
        }
        setContent {
            FriendOrFoeTheme {
                val state by coordinator.state.collectAsState()
                CameraScreen(
                    state = state,
                    onTapTrack = coordinator::challenge,
                    onPreviewSize = coordinator::setPreviewSize,
                    preview = { mod ->
                        // If the preview hides the rings on a phone: implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                                    .also { previewView = it; maybeStartCamera() }
                            },
                            modifier = mod.fillMaxSize(),
                        )
                    },
                )
            }
        }
        cameraPermitted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraPermitted) maybeStartCamera() else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun maybeStartCamera() {
        val pv = previewView ?: return
        if (!cameraPermitted) return
        if (cameraStarted.compareAndSet(false, true)) cameraController?.start(this, pv, ::onFrame)
    }

    /** On the lane. Frames are dropped while the runtime loads or after it refused, so the strip keeps its note. */
    private fun onFrame(frame: Frame) {
        if (app.runtimeLoading) return
        if ((app.coordinator.detector as? ClikaRtDetector)?.isOpen == false) return
        app.runTick(frame)
    }

    override fun onDestroy() {
        cameraController?.stop()
        super.onDestroy()
    }
}
