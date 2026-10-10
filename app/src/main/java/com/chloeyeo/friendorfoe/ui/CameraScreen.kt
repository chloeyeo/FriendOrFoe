package com.chloeyeo.friendorfoe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.DemoScenario
import com.chloeyeo.friendorfoe.session.IffState
import com.chloeyeo.friendorfoe.session.ObserverUiState
import com.chloeyeo.friendorfoe.ui.overlay.droneAt
import com.chloeyeo.friendorfoe.ui.overlay.drawTrack
import com.chloeyeo.friendorfoe.ui.overlay.tagFor
import com.chloeyeo.friendorfoe.ui.status.StatusStrip
import com.chloeyeo.friendorfoe.ui.theme.FriendOrFoeTheme
import com.chloeyeo.friendorfoe.ui.theme.Ink
import kotlinx.coroutines.delay

/**
 * The observer screen: renders one [ObserverUiState] and nothing else. [preview] is the camera picture
 * (`AndroidView { PreviewView }`, FILL_CENTER) or the demo backdrop; the rings and tags are drawn over it on a
 * Canvas of the same size, whose pixel size goes to [onPreviewSize] (`IffCoordinator.setPreviewSize`). Tapping a
 * drone box calls [onTapTrack] with its id. [chrome] (buttons, panels) is laid over everything.
 */
@Composable
fun CameraScreen(
    state: ObserverUiState,
    modifier: Modifier = Modifier,
    onTapTrack: (Int) -> Unit = {},
    onPreviewSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    clock: () -> Long = System::currentTimeMillis,
    preview: @Composable (Modifier) -> Unit = { DemoBackdrop(state.tracks, it) },
    chrome: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(Ink)) {
        preview(Modifier.fillMaxSize())
        TrackOverlay(
            state,
            onTapTrack,
            clock,
            Modifier.fillMaxSize().onSizeChanged { onPreviewSize(it.width, it.height) },
        )
        StatusStrip(
            state.status,
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout),
        )
        chrome()
    }
}

@Composable
private fun TrackOverlay(
    state: ObserverUiState,
    onTapTrack: (Int) -> Unit,
    clock: () -> Long,
    modifier: Modifier = Modifier,
) {
    // Tick only while a countdown is on screen.
    val challenged = state.tracks.any { it.iff is IffState.Challenged }
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(challenged) {
        now = clock()
        while (challenged) {
            delay(100)
            now = clock()
        }
    }

    val measurer = rememberTextMeasurer()
    val tracks by rememberUpdatedState(state.tracks)
    val onTap by rememberUpdatedState(onTapTrack)
    // People first, so a drone ring is never painted over.
    val ordered = state.tracks.sortedBy { it.cls == ObjClass.DRONE }

    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { p -> droneAt(tracks, p.x, p.y, slop = 24.dp.toPx())?.let { onTap(it.id) } }
        },
    ) {
        for (track in ordered) drawTrack(track, tagFor(track, now), measurer)
    }
}

// ---- Previews: one per DemoScenario state; these are the screenshots attached to the PR. ----

private const val PREVIEW_NOW = 1_760_000_000_000L

private class DemoStateIndex : PreviewParameterProvider<Int> {
    override val values = DemoScenario.titles.indices.asSequence()
}

@Preview(device = "spec:parent=pixel_5,orientation=landscape", showBackground = true)
@Composable
private fun CameraScreenPreview(@PreviewParameter(DemoStateIndex::class) index: Int) {
    FriendOrFoeTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val state = DemoScenario.states(PREVIEW_NOW, constraints.maxWidth, constraints.maxHeight)[index]
            // 3 s into the window: a challenged box shows its countdown part-way.
            CameraScreen(state, clock = { PREVIEW_NOW + 3_000 })
        }
    }
}
