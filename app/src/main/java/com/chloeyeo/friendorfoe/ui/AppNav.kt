package com.chloeyeo.friendorfoe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.chloeyeo.friendorfoe.app.FofApplication
import com.chloeyeo.friendorfoe.net.EventClient
import com.chloeyeo.friendorfoe.net.EventDiff
import com.chloeyeo.friendorfoe.net.EventJson
import com.chloeyeo.friendorfoe.session.DemoScenario
import com.chloeyeo.friendorfoe.session.EventUi
import com.chloeyeo.friendorfoe.session.ObserverUiState
import com.chloeyeo.friendorfoe.ui.gadget.GadgetPanel
import com.chloeyeo.friendorfoe.ui.log.EventLogScreen
import com.chloeyeo.friendorfoe.ui.settings.SettingsScreen
import com.chloeyeo.friendorfoe.ui.settings.SettingsValues
import com.chloeyeo.friendorfoe.ui.status.OfflineMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private enum class Screen { CAMERA, LOG, SETTINGS }

/**
 * App root. In demo mode (Settings, on by default) or before the session passes [live], it steps through
 * [DemoScenario] over the demo backdrop ("next" button; tapping the drone in the first state challenges it), so
 * the whole UI runs without a camera, a model or a gadget. Otherwise it renders [live] (`IffCoordinator.state`)
 * over [cameraPreview]. [onReset] is `IffCoordinator.reset`; in demo mode Reset restarts the script.
 * [onSettingsSaved] lets the session rebuild its detector or signal source after Save. Whatever is on screen is
 * mirrored to the laptop dashboard when Settings has a dashboard URL.
 */
@Composable
fun FriendOrFoeApp(
    live: StateFlow<ObserverUiState>? = null,
    cameraPreview: (@Composable (Modifier) -> Unit)? = null,
    onTapTrack: (Int) -> Unit = {},
    onPreviewSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    onReset: () -> Unit = {},
    onSettingsSaved: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { FofApplication.from(context).prefs }
    var settings by remember(prefs) { mutableStateOf(SettingsValues.readFrom(prefs)) }
    val demo = settings.demoMode || live == null
    var size by remember { mutableStateOf(IntSize(1920, 1080)) }
    var step by remember { mutableIntStateOf(0) }
    // Rebuilt on every step with a fresh clock, so a challenge's countdown starts when it is shown.
    val demoStates = remember(size, step) { DemoScenario.states(System.currentTimeMillis(), size.width, size.height) }
    val liveState = live?.collectAsState()
    val sessionState = if (demo || liveState == null) demoStates[step % demoStates.size] else liveState.value

    // The OFFLINE badge always reflects this phone's real network, in demo mode too.
    val offline by remember(context) { OfflineMonitor.flow(context) }.collectAsState(initial = false)
    // FAKE SIGNAL badge: on the live camera, a tap right now would go to the scripted source (FAKE, or AUTO unplugged).
    val fakeSignal = !demo && settings.signalChoice.usesFake(sessionState.status.gadgetConnected)
    val state = sessionState.copy(status = sessionState.status.copy(offline = offline, fakeSignal = fakeSignal))
    DashboardMirror(state, size, settings.dashboardBaseUrl)

    val reset: () -> Unit = {
        onReset()
        if (demo) step = 0
    }
    var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
    var gadgetOpen by rememberSaveable { mutableStateOf(false) }
    val toCamera: () -> Unit = { screen = Screen.CAMERA }

    when (screen) {
        Screen.CAMERA -> {
            val demoTap: (Int) -> Unit = { if (step == 0) step = 1 }
            val backdrop: @Composable (Modifier) -> Unit = { DemoBackdrop(state.tracks, it) }
            CameraScreen(
                state,
                onTapTrack = if (demo) demoTap else onTapTrack,
                onPreviewSize = { w, h ->
                    if (w > 0 && h > 0) size = IntSize(w, h)
                    onPreviewSize(w, h)
                },
                preview = if (!demo && cameraPreview != null) cameraPreview else backdrop,
            ) {
                CameraChrome(
                    state = state,
                    gadgetOpen = gadgetOpen,
                    nextLabel = if (demo) "next: ${DemoScenario.titles[(step + 1) % DemoScenario.titles.size]}" else null,
                    onNext = { step = (step + 1) % DemoScenario.titles.size },
                    onToggleGadget = { gadgetOpen = !gadgetOpen },
                    onOpenLog = { screen = Screen.LOG },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onReset = reset,
                )
            }
        }
        Screen.LOG -> {
            BackHandler(onBack = toCamera)
            EventLogScreen(state.events, onBack = toCamera, onReset = reset)
        }
        Screen.SETTINGS -> {
            BackHandler(onBack = toCamera)
            SettingsScreen(
                settings,
                onSave = { saved ->
                    saved.writeTo(prefs)
                    settings = saved
                    onSettingsSaved()
                },
                onBack = toCamera,
            )
        }
    }
}

/**
 * Posts new event-log rows as they appear (a reset or a new demo run resets the dashboard too) and the drawn
 * tracks once a second, fire-and-forget through [EventClient]; nothing here ever waits on the laptop.
 */
@Composable
private fun DashboardMirror(state: ObserverUiState, size: IntSize, url: String) {
    if (url.isBlank()) return
    val client = remember(url) { EventClient(url) }
    DisposableEffect(client) { onDispose { client.close() } }

    var lastSent by remember(client) { mutableStateOf<EventUi?>(null) }
    LaunchedEffect(client, state.events) {
        val plan = EventDiff.plan(state.events, lastSent)
        if (plan.reset) client.reset()
        if (plan.send.isNotEmpty()) client.post(EventJson.events(plan.send))
        lastSent = state.events.firstOrNull()
    }

    val tracks by rememberUpdatedState(state.tracks)
    LaunchedEffect(client, size) {
        while (true) {
            client.postFrame(EventJson.frame(System.currentTimeMillis(), tracks, size.width, size.height))
            delay(1_000)
        }
    }
}

@Composable
private fun BoxScope.CameraChrome(
    state: ObserverUiState,
    gadgetOpen: Boolean,
    nextLabel: String?,
    onNext: () -> Unit,
    onToggleGadget: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onReset: () -> Unit,
) {
    if (gadgetOpen) {
        GadgetPanel(
            state.status,
            state.signal,
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(360.dp)
                .padding(top = 64.dp, bottom = 80.dp),
        )
    }
    Row(
        Modifier.align(Alignment.BottomEnd).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (nextLabel != null) ChromeButton(nextLabel, onNext)
        ChromeButton(if (gadgetOpen) "Hide gadget" else "Gadget", onToggleGadget)
        ChromeButton("Log", onOpenLog)
        ChromeButton("Settings", onOpenSettings)
        ResetButton(onReset)
    }
}
