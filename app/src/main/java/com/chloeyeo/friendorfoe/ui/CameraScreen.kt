package com.chloeyeo.friendorfoe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.DemoScenario
import com.chloeyeo.friendorfoe.session.IffState
import com.chloeyeo.friendorfoe.session.ObserverUiState
import com.chloeyeo.friendorfoe.session.SignalUi
import com.chloeyeo.friendorfoe.session.StatusUi
import com.chloeyeo.friendorfoe.ui.OverlayPainter.drawTrack
import com.chloeyeo.friendorfoe.ui.theme.FriendOrFoeTheme
import kotlinx.coroutines.delay

/**
 * The observer screen: renders one [ObserverUiState] and nothing else — rings and tags on a Canvas, the status
 * strip on top, the latest answers bottom-left. Tapping a drone box calls [onTapTrack] with its id (the
 * coordinator turns that into a challenge). With [onNextState] set, a button steps the demo scenario.
 *
 * [preview] is the camera picture (`AndroidView { PreviewView }`, FILL_CENTER), drawn under the Canvas; the
 * Canvas's pixel size goes to [onPreviewSize] (`IffCoordinator.setPreviewSize`). Slot names match the UI branch.
 * TODO(stream 3 / Jongho): the rest of the screen is theirs (GadgetPanel, SettingsScreen, EventLogScreen,
 * big fonts for the judges, reset button, demo-mode switch).
 */
@Composable
fun CameraScreen(
    state: ObserverUiState,
    modifier: Modifier = Modifier,
    onTapTrack: (Int) -> Unit = {},
    onNextState: (() -> Unit)? = null,
    nextStateLabel: String = "next state",
    onPreviewSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    preview: @Composable (Modifier) -> Unit = {},
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val challenged = state.tracks.any { it.iff is IffState.Challenged }
    LaunchedEffect(challenged) {
        while (challenged) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val tagStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        preview(Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { onPreviewSize(it.width, it.height) }
                .pointerInput(state.tracks) {
                    detectTapGestures { p ->
                        state.tracks
                            .firstOrNull { it.cls == ObjClass.DRONE && it.box.contains(p.x, p.y) }
                            ?.let { onTapTrack(it.id) }
                    }
                },
        ) {
            for (track in state.tracks) drawTrack(track, now, textMeasurer, tagStyle)
        }
        StatusStrip(state.status, Modifier.align(Alignment.TopStart).fillMaxWidth())
        SignalPanel(state.signal, Modifier.align(Alignment.BottomStart).padding(12.dp))
        if (onNextState != null) {
            Button(onClick = onNextState, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Text(nextStateLabel)
            }
        }
    }
}

/** Detector name, ms/frame, fps, model, offline, gadget, RSSI — one line across the top. */
@Composable
fun StatusStrip(status: StatusUi, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val chips = listOf(
            status.detectorName,
            "${status.msPerFrame} ms",
            "%.1f fps".format(status.fps),
            status.modelNote ?: if (status.modelReady) "model ready" else "model loading",
            if (status.offline) "OFFLINE" else "online",
            if (status.gadgetConnected) "gadget connected" else "no gadget",
            status.rssiDbm?.let { "$it dBm" } ?: "rssi —",
        )
        for (chip in chips) Text(chip, color = Color.White, fontSize = 14.sp)
    }
}

/** The latest answers, newest first (at most three). */
@Composable
fun SignalPanel(signal: List<SignalUi>, modifier: Modifier = Modifier) {
    if (signal.isEmpty()) return
    Column(modifier.background(Color(0x99000000)).padding(8.dp)) {
        for (row in signal.take(3)) {
            val color = when (row.verdict) {
                com.chloeyeo.friendorfoe.session.VerdictKind.FRIEND -> OverlayPainter.Green
                com.chloeyeo.friendorfoe.session.VerdictKind.FOE,
                com.chloeyeo.friendorfoe.session.VerdictKind.UNKNOWN -> OverlayPainter.Red
                null -> Color.White
            }
            val text = buildString {
                append(row.verdict?.name ?: "…")
                row.droneId?.let { append(" · ").append(it) }
                row.reason?.let { append(" · ").append(it) }
                row.rssiDbm?.let { append(" · ").append(it).append(" dBm") }
                row.roundTripMs?.let { append(" · ").append(it).append(" ms") }
            }
            Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * [CameraScreen] fed by [DemoScenario]: the "next state" button steps through drone appears -> challenged ->
 * FRIEND -> FOE (replayed nonce, position mismatch, late response) -> UNKNOWN; tapping the drone box from the
 * first state also starts the challenge. MainActivity now shows the live camera; this stays for the UI stream.
 */
@Composable
fun DemoCameraScreen(modifier: Modifier = Modifier) {
    var index by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        val states = remember(width, height, index) { DemoScenario.states(System.currentTimeMillis(), width, height) }
        val count = states.size
        CameraScreen(
            state = states[index],
            onTapTrack = { if (index == 0) index = 1 },
            onNextState = { index = (index + 1) % count },
            nextStateLabel = "next: " + DemoScenario.titles[(index + 1) % count],
        )
    }
}

@Preview(name = "demo · FRIEND", widthDp = 960, heightDp = 480, showBackground = true)
@Composable
private fun CameraScreenFriendPreview() {
    FriendOrFoeTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val states = DemoScenario.states(0L, constraints.maxWidth, constraints.maxHeight)
            CameraScreen(states[2])
        }
    }
}

@Preview(name = "demo · FOE replayed nonce", widthDp = 960, heightDp = 480, showBackground = true)
@Composable
private fun CameraScreenFoePreview() {
    FriendOrFoeTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val states = DemoScenario.states(0L, constraints.maxWidth, constraints.maxHeight)
            CameraScreen(states[3])
        }
    }
}
