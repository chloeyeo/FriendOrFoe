package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.camera.FrameTransform
import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.Detector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.detect.RoundDetector
import com.chloeyeo.friendorfoe.detect.RoundPlan
import com.chloeyeo.friendorfoe.detect.RoundScheduler
import com.chloeyeo.friendorfoe.signal.ReChecks
import com.chloeyeo.friendorfoe.signal.SignalSource
import com.chloeyeo.friendorfoe.track.BoxLock
import com.chloeyeo.friendorfoe.track.GrayFrame
import com.chloeyeo.friendorfoe.track.MotionTracker
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
 * 10 fps between detections: [motionTick] (the camera controller's `motion` executor) moves every tracked box by
 * template matching on a small gray frame ([MotionTracker]); [tick] fast-forwards each detection from its capture
 * time to now before it is matched, so a moving drone's ring stays on the drone although a detection takes seconds.
 *
 * Detection rounds: with a [RoundDetector] (the dual detector) the [RoundScheduler] picks each round under the lock
 * from the tracked drone boxes (their current motion positions): a crop round re-detects only around the tracked
 * drones, a full round scans the whole frame (every few rounds, and whenever nothing is tracked), and the person
 * model runs only every few rounds; the tracker's person coast time follows the measured gap between person rounds.
 * A crop round's detections go through the same fast-forward and match as a full round's.
 *
 * Threading: [tick] runs on the runtime lane, [motionTick] on the motion executor; [challenge] and [setPreviewSize]
 * are called from the main thread (a tap, a layout pass); [state] is read from the UI. The tracker, the motion
 * tracker, the lock and the per-track IFF states are touched only inside `synchronized(stateLock)`; the inference
 * and the fast-forward in [tick] run OUTSIDE that lock, so a tap never waits for the detector (at most for one
 * motion step, milliseconds). The preview size is `@Volatile`. [setStatus] and [log] are safe from any thread because they only
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
    private val motion: MotionTracker = MotionTracker(),
    private val scheduler: RoundScheduler = RoundScheduler(),
) {
    private val _state = MutableStateFlow(ObserverUiState(status = StatusUi(detectorName = detector.name)))

    /** The only thing the UI renders. */
    val state: StateFlow<ObserverUiState> = _state.asStateFlow()

    @Volatile private var previewWidth = 0
    @Volatile private var previewHeight = 0
    private val stateLock = Any()
    private var lastTickMillis = 0L

    /** The analysis frame size the boxes live in (from the last detection frame or gray frame). Under stateLock. */
    private var frameWidth = 0
    private var frameHeight = 0
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
     * Runs the detector on [frame] (blocking; runtime lane, outside the state lock), carries every detection from
     * the frame's capture time ([Frame.timestampNanos]) to the newest gray frame ([MotionTracker.fastForward], the
     * latency compensation), moves the tracker's boxes to their current motion positions so the IoU match compares
     * like with like, updates the tracker with the wall clock and publishes the tracks. A matched or new track takes
     * its detection's fresh motion seed (the correction); a coasting track keeps following its old template. While
     * a [lock] is active its box is always published: as the live track while the tracker still has it, then at its
     * last motion position (or the frozen [BoxLock.rect]) once the object is lost. A lock whose window has passed is
     * cleared here and in [motionTick].
     */
    fun tick(frame: Frame) {
        val t0 = clock()
        val rounds = detector as? RoundDetector
        val plan = if (rounds != null) synchronized(stateLock) { scheduler.plan(droneBoxesLocked(), t0) } else null
        val detections = if (rounds != null && plan != null) rounds.detectRound(frame, plan) else detector.detect(frame)
        val t1 = clock()
        lastPlan = plan
        val offline = offlineProbe()
        // The fast-forward (seconds of gray frames per box) runs on a snapshot OUTSIDE the lock, so the 10 fps
        // motion ticks and taps do not wait for it; frames pushed meanwhile are caught up under the lock.
        val buffered = synchronized(stateLock) { motion.snapshot() }
        val seeds = motion.fastForward(frame.timestampNanos, detections.map { it.box }, buffered)
        synchronized(stateLock) {
            frameWidth = frame.width
            frameHeight = frame.height
            motion.catchUp(seeds)
            val moved = detections.mapIndexed { i, d -> d.copy(box = seeds[i].box) }
            for (track in tracker.tracks) motion.box(track.id)?.let { tracker.moveTo(track.id, it.box) }
            if (plan != null) {
                scheduler.done(plan, t1)
                tracker.personStickyMs = scheduler.personStickyMs()
            }
            val seen = tracker.update(moved, t1)
            val seedByBox = HashMap<BoxF, MotionTracker.Seed>()
            moved.forEachIndexed { i, d -> seedByBox.putIfAbsent(d.box, seeds[i]) }
            for (track in seen) seedByBox.remove(track.box)?.let { motion.assign(track.id, it) }

            val fps = if (lastTickMillis > 0L && t1 > lastTickMillis) 1000f / (t1 - lastTickMillis) else 0f
            lastTickMillis = t1
            publishLocked(t1) { it.copy(msPerFrame = t1 - t0, fps = fps, modelReady = true, offline = offline) }
        }
    }

    /**
     * One 10 fps motion step (the `motion` executor, never the runtime lane): buffers [gray], moves every tracked
     * box to it ([MotionTracker.push]) and republishes the tracks with the same ids and IFF states. Track creation
     * and removal stay with [tick]. Cheap when nothing is tracked (the frame is only buffered).
     */
    fun motionTick(gray: GrayFrame) {
        synchronized(stateLock) {
            motion.push(gray)
            frameWidth = gray.frameWidth
            frameHeight = gray.frameHeight
            if (motion.ids.isEmpty() && lock == null) return
            publishLocked(clock(), null)
        }
    }

    /** The plan of the last [tick] (null for a detector without rounds); written on the runtime lane. */
    @Volatile
    var lastPlan: RoundPlan? = null
        private set

    /** The tracked drones' boxes for the next round's crops: the motion position when there is one. Under stateLock. */
    private fun droneBoxesLocked(): List<BoxF> =
        tracker.tracks.filter { it.cls == ObjClass.DRONE }.map { motion.box(it.id)?.box ?: it.box }

    /** The boxes as the user sees them: the motion position when there is one, else the tracker's box. Under stateLock. */
    private fun publishLocked(now: Long, status: ((StatusUi) -> StatusUi)?) {
        val transform = if (previewWidth > 0 && previewHeight > 0 && frameWidth > 0 && frameHeight > 0) {
            FrameTransform(frameWidth, frameHeight, previewWidth, previewHeight)
        } else {
            FrameTransform.identity(frameWidth.coerceAtLeast(1), frameHeight.coerceAtLeast(1))
        }
        val current = lock
        if (current != null && !current.isActive(now)) {
            lock = null
            iffById.remove(current.boxId)
            // Phase 5 replaces this line with Verdict(UNKNOWN, "no response") on the locked box.
            eventLog.add(now, "challenge window closed · box ${current.boxId}")
        }
        val live = tracker.tracks
        val keep = live.map { it.id }.toMutableSet()
        lock?.let { keep += it.boxId }
        iffById.keys.retainAll(keep)
        motion.retain(keep)

        val uis = live.map { track ->
            TrackUi(
                id = track.id,
                cls = track.cls,
                box = transform.toPreview(motion.box(track.id)?.box ?: track.box),
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
                    box = transform.toPreview(motion.box(lock.boxId)?.box ?: lock.rect),
                    iff = iffById[lock.boxId] ?: IffState.Challenged(lock.untilMillis),
                    score = lockScore,
                )
            }
        }
        _state.update { s ->
            s.copy(
                tracks = uis,
                status = status?.invoke(s.status) ?: s.status,
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
            lock = BoxLock(boxId, motion.box(boxId)?.box ?: track.box, now + BoxLock.WINDOW_MS)
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
            motion.reset()
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
