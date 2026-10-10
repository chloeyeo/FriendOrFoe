package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.camera.FrameTransform
import com.chloeyeo.friendorfoe.detect.Detector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.detect.ObjClass
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
 * [Tracker] (wall-clock sticky windows, D-09) and come out as [TrackUi]s in preview coordinates. [challenge] is the
 * H7-H10 integration work; step 1 is real (Phase 2, DET-06), steps 2-3 are Phase 5:
 *
 * 1. (done) refuse while a challenge is in flight; `lock = BoxLock(boxId, track.box, now + 10 s)` and set the
 *    track's state to [IffState.Challenged] (the ring shows the countdown); [tick] keeps the locked box on screen
 *    at its frozen rect when the tracker loses the object and closes the window at 10 s;
 *
 * TODO(integration lead / Chloe, Phase 5): challenge(boxId) steps 2-3:
 * 2. `req = ChallengeRequest(boxId, prefs.challengerId, now / 1000)`; `t0 = SystemClock.elapsedRealtime()`;
 *    launch `signalSource.challenge(req)` on a coroutine scope with a 10 s timeout; each [SignalEvent.Status]
 *    goes to the event log; the [SignalEvent.Answer] goes through `reChecks.check(answer, roundTripMs)` into
 *    [IffState.Verdict] on the locked box, a [SignalUi] row (newest first) and an event line; no answer in time
 *    -> `Verdict(UNKNOWN, "no response", ...)`;
 * 3. log the verdict as a structured [EventUi] (cls, box, verdict, reason, …) and release the lock; the UI mirrors
 *    the event log to the laptop dashboard (`FriendOrFoeApp` → `net/EventClient`), so do not post it here too.
 *
 * Threading: [tick] runs on the runtime lane; [challenge] and [setPreviewSize] are called from the main thread (a
 * tap, a layout pass); [state] is read from the UI. The tracker, the lock and the per-track IFF states are touched
 * only inside `synchronized(stateLock)`; the inference in [tick] runs OUTSIDE that lock, so a tap never waits for
 * the detector. The preview size is `@Volatile`. [setStatus] and [log] are safe from any thread because they only
 * go through `_state.update` (an atomic compare-and-set on the state flow) and the self-synchronized [EventLog].
 *
 * @param offlineProbe evaluated once per [tick] into [StatusUi.offline] (D-12; `app/OfflineProbe` on the phone,
 *   a constant in tests); the default `{ true }` keeps the badge OFFLINE when nothing is injected
 */
class IffCoordinator(
    val detector: Detector,
    val signalSource: SignalSource,
    val reChecks: ReChecks = ReChecks(),
    private val tracker: Tracker = Tracker(),
    private val eventLog: EventLog = EventLog(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val offlineProbe: () -> Boolean = { true },
) {
    private val _state = MutableStateFlow(ObserverUiState(status = StatusUi(detectorName = detector.name)))

    /** The only thing the UI renders. */
    val state: StateFlow<ObserverUiState> = _state.asStateFlow()

    @Volatile private var previewWidth = 0
    @Volatile private var previewHeight = 0
    private val stateLock = Any()
    private var lastTickMillis = 0L
    private val iffById = HashMap<Int, IffState>()

    /** The tapped track's latest score, shown on the synthetic lock box once the tracker has lost the object. */
    private var lockScore = 0f

    /** The tapped box frozen for the challenge window; null when no challenge is in flight. Written under stateLock. */
    @Volatile
    var lock: BoxLock? = null
        private set

    /** The overlay's size in pixels, so [tick] can map frame boxes into preview coordinates (FILL_CENTER). */
    fun setPreviewSize(width: Int, height: Int) {
        previewWidth = width
        previewHeight = height
    }

    /**
     * Runs the detector on [frame] (blocking; runtime lane, outside the state lock), updates the tracker with the
     * wall clock and publishes the tracks. While a [lock] is active its box is always published: as the live track
     * while the tracker still has it, then at the frozen [BoxLock.rect] once the object is lost. A lock whose window
     * has passed is cleared here.
     */
    fun tick(frame: Frame) {
        val t0 = clock()
        val detections = detector.detect(frame)
        val t1 = clock()
        val trackUis: List<TrackUi>
        val fps: Float
        synchronized(stateLock) {
            tracker.update(detections, t1)

            val transform = if (previewWidth > 0 && previewHeight > 0) {
                FrameTransform(frame.width, frame.height, previewWidth, previewHeight)
            } else {
                FrameTransform.identity(frame.width, frame.height)
            }
            val current = lock
            if (current != null && !current.isActive(t1)) {
                lock = null
                iffById.remove(current.boxId)
                // Phase 5 replaces this line with Verdict(UNKNOWN, "no response") on the locked box.
                eventLog.add(t1, "challenge window closed · box ${current.boxId}")
            }
            val live = tracker.tracks
            val keep = live.map { it.id }.toMutableSet()
            lock?.let { keep += it.boxId }
            iffById.keys.retainAll(keep)

            val uis = live.map { track ->
                TrackUi(
                    id = track.id,
                    cls = track.cls,
                    box = transform.toPreview(track.box),
                    iff = iffById[track.id] ?: IffState.Unchallenged,
                    score = track.score,
                )
            }.toMutableList()
            val lock = lock
            if (lock != null) {
                val seen = live.firstOrNull { it.id == lock.boxId }
                if (seen != null) {
                    lockScore = seen.score
                } else {
                    uis += TrackUi(
                        id = lock.boxId,
                        cls = ObjClass.DRONE,
                        box = transform.toPreview(lock.rect),
                        iff = iffById[lock.boxId] ?: IffState.Challenged(lock.untilMillis),
                        score = lockScore,
                    )
                }
            }
            trackUis = uis
            fps = if (lastTickMillis > 0L && t1 > lastTickMillis) 1000f / (t1 - lastTickMillis) else 0f
            lastTickMillis = t1
        }

        _state.update { s ->
            s.copy(
                tracks = trackUis,
                status = s.status.copy(msPerFrame = t1 - t0, fps = fps, modelReady = true, offline = offlineProbe()),
                events = eventLog.snapshot(),
            )
        }
    }

    /** Appends an event row and publishes it. */
    fun log(text: String) {
        eventLog.add(clock(), text)
        _state.update { it.copy(events = eventLog.snapshot()) }
    }

    /** Publishes status fields without a frame (model loading note, detector name, link health); see the class KDoc. */
    fun setStatus(update: (StatusUi) -> StatusUi) {
        _state.update { it.copy(status = update(it.status)) }
    }

    /**
     * A tap on box [boxId] (main thread): step 1 of the class KDoc. Locks a live DRONE track for
     * [BoxLock.WINDOW_MS], marks it [IffState.Challenged], logs `challenge · box <id>` and publishes at once, so the
     * ring changes on the tap and not on the next tick. Ignored while another lock is active, and for a person or an
     * unknown id. Steps 2-3 (signal, re-checks, verdict) are Phase 5.
     */
    fun challenge(boxId: Int) {
        synchronized(stateLock) {
            val now = clock()
            val previous = lock
            if (previous?.isActive(now) == true) return
            val track = tracker.tracks.firstOrNull { it.id == boxId && it.cls == ObjClass.DRONE } ?: return
            if (previous != null) iffById.remove(previous.boxId) // expired, not yet cleared by a tick
            lock = BoxLock(boxId, track.box, now + BoxLock.WINDOW_MS)
            lockScore = track.score
            iffById[boxId] = IffState.Challenged(until = now + BoxLock.WINDOW_MS)
            eventLog.add(now, "challenge · box $boxId")
            _state.update { s ->
                s.copy(
                    tracks = s.tracks.map { it.copy(iff = iffById[it.id] ?: IffState.Unchallenged) },
                    events = eventLog.snapshot(),
                )
            }
        }
    }

    /**
     * The Reset button (OBS-10): forgets the tracks, the nonce ledger, every verdict, the lock and the event log,
     * so a rehearsal and the live run start clean. Track ids keep counting up (see [Tracker.reset]).
     */
    fun reset() {
        synchronized(stateLock) {
            tracker.reset()
            reChecks.reset()
            eventLog.clear()
            iffById.clear()
            lock = null
            _state.update { it.copy(tracks = emptyList(), signal = emptyList(), events = emptyList()) }
        }
    }

    fun close() {
        detector.close()
        signalSource.close()
    }
}
