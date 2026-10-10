package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.camera.FrameTransform
import com.chloeyeo.friendorfoe.detect.Detector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.signal.ReChecks
import com.chloeyeo.friendorfoe.signal.SignalSource
import com.chloeyeo.friendorfoe.track.BoxLock
import com.chloeyeo.friendorfoe.track.Tracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The session: frames in, [ObserverUiState] out (integration lead). [tick] is real — detections go through the
 * [Tracker] and come out as [TrackUi]s in preview coordinates. [challenge] is the H7-H10 integration work:
 *
 * TODO(integration lead / Chloe): challenge(boxId):
 * 1. refuse while a challenge is in flight; `lock = BoxLock(boxId, track.box, now + 10 s)` and set the track's
 *    state to [IffState.Challenged] (the ring shows the countdown);
 * 2. `req = ChallengeRequest(boxId, prefs.challengerId, now / 1000)`; `t0 = SystemClock.elapsedRealtime()`;
 *    launch `signalSource.challenge(req)` on a coroutine scope with a 10 s timeout; each [SignalEvent.Status]
 *    goes to the event log; the [SignalEvent.Answer] goes through `reChecks.check(answer, roundTripMs)` into
 *    [IffState.Verdict] on the locked box, a [SignalUi] row (newest first) and an event line; no answer in time
 *    -> `Verdict(UNKNOWN, "no response", ...)`;
 * 3. post the verdict to the laptop dashboard through `net/EventClient` (fire-and-forget), then release the lock.
 *
 * Not thread-safe: [tick] runs on the runtime lane; [state] is read from the UI.
 */
class IffCoordinator(
    val detector: Detector,
    val signalSource: SignalSource,
    val reChecks: ReChecks = ReChecks(),
    private val tracker: Tracker = Tracker(),
    private val eventLog: EventLog = EventLog(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(ObserverUiState(status = StatusUi(detectorName = detector.name)))

    /** The only thing the UI renders. */
    val state: StateFlow<ObserverUiState> = _state.asStateFlow()

    private var previewWidth = 0
    private var previewHeight = 0
    private var lastTickMillis = 0L
    private val iffById = HashMap<Int, IffState>()

    /** The tapped box frozen for the challenge window; null when no challenge is in flight. */
    var lock: BoxLock? = null
        private set

    /** The overlay's size in pixels, so [tick] can map frame boxes into preview coordinates (FILL_CENTER). */
    fun setPreviewSize(width: Int, height: Int) {
        previewWidth = width
        previewHeight = height
    }

    /** Runs the detector on [frame] (blocking; runtime lane), updates the tracker and publishes the tracks. */
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

    /** Appends an event row and publishes it. */
    fun log(text: String) {
        eventLog.add(clock(), text)
        _state.update { it.copy(events = eventLog.snapshot()) }
    }

    /** Tap -> challenge -> answer -> re-check -> verdict -> events; see the class KDoc for the plan. */
    fun challenge(boxId: Int): Nothing = throw NotImplementedError("integration lead")

    fun close() {
        detector.close()
        signalSource.close()
    }
}
