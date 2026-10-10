package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.camera.FrameTransform
import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.Detector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.signal.ChallengeRequest
import com.chloeyeo.friendorfoe.signal.GadgetUnavailableException
import com.chloeyeo.friendorfoe.signal.ReChecks
import com.chloeyeo.friendorfoe.signal.Redact
import com.chloeyeo.friendorfoe.signal.SignalEvent
import com.chloeyeo.friendorfoe.signal.SignalKind
import com.chloeyeo.friendorfoe.signal.SignalSource
import com.chloeyeo.friendorfoe.track.BoxLock
import com.chloeyeo.friendorfoe.track.GrayFrame
import com.chloeyeo.friendorfoe.track.MotionTracker
import com.chloeyeo.friendorfoe.track.Tracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The session: frames in, [ObserverUiState] out (integration lead). [tick] is real — detections go through the
 * [Tracker] (wall-clock sticky windows, D-09) and come out as [TrackUi]s in preview coordinates. [challenge] is the
 * tap → signal → re-check → verdict path (DET-06, OBS-02, PHY-01/02, SIG-03):
 *
 * 1. refuse while a challenge is in flight (or the tapped box still shows its verdict);
 *    `lock = BoxLock(boxId, track.box, now + 10 s)` and set the track's state to [IffState.Challenged] (the ring
 *    shows the countdown) at once; [tick] keeps the locked box on screen at its last motion position / frozen rect
 *    when the tracker loses the object;
 * 2. `req = ChallengeRequest(boxId, challengerId(), now / 1000)` goes to [signalSource] on [scope]; the phone-side
 *    round trip is measured with [elapsedMillis] from the call (the USB source writes the line there) to the
 *    [SignalEvent.Answer]; each [SignalEvent.Status] goes to the event log;
 * 3. the answer goes through `reChecks.check(answer, roundTripMs)` into [IffState.Verdict] on the LOCKED box (the
 *    lock's id, whatever is under the tap point now), a [SignalUi] row (newest first) and a structured [EventUi];
 *    the lock is released. No answer inside the window -> `UNKNOWN · no response`; a source that cannot run the
 *    challenge ([GadgetUnavailableException]) -> `UNKNOWN · <reason>` at once (`no gadget`). An answer for a
 *    challenge that is no longer open (window passed, reset) is logged and dropped, its nonce still recorded;
 * 4. the verdict stays drawn for [VERDICT_HOLD_MS] (on the frozen box if the tracker lost the object), then the
 *    box is back to `drone · ?` and can be challenged again. The UI mirrors the event log to the laptop dashboard
 *    (`FriendOrFoeApp` → `net/EventClient`), so nothing is posted from here.
 *
 * 10 fps between detections: [motionTick] (the camera controller's `motion` executor) moves every tracked box by
 * template matching on a small gray frame ([MotionTracker]); [tick] fast-forwards each detection from its capture
 * time to now before it is matched, so a moving drone's ring stays on the drone although a detection takes seconds.
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
 * @param challengerId this observer's id for the challenge line, read on every tap (`Prefs.challengerId`)
 * @param elapsedMillis a monotonic clock for the round trip (`SystemClock.elapsedRealtime()` semantics)
 * @param scope where challenges run; tests pass an unconfined scope so an answer lands synchronously
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
    private val challengerId: () -> Int = { DEFAULT_CHALLENGER_ID },
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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

    /**
     * The challenge in flight (matches [lock]); [seq] tells a stale answer from the current one; [job] is its
     * coroutine (cancelled on Reset and when the window passes, which also closes the source's side); [source] is
     * the source it went to, once known. Under stateLock.
     */
    private class Pending(val seq: Long, val req: ChallengeRequest) {
        var job: Job? = null
        var source: SignalKind? = null
    }

    private var pending: Pending? = null
    private var seqCounter = 0L

    /** A verdict kept on screen for [VERDICT_HOLD_MS]; [rect] (frame px) is drawn if the tracker lost the object. */
    private class Hold(val rect: BoxF, val until: Long, val score: Float)

    private val holds = HashMap<Int, Hold>()

    /** The gadget panel rows, newest first, with the wall-clock time they arrived (for [SignalUi.ageMs]). */
    private val signalRows = ArrayDeque<Pair<Long, SignalUi>>()

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
        val detections = detector.detect(frame)
        val t1 = clock()
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
            // The locked box and the held verdicts stay in the tracker, so the drone's next detection lands on them
            // (windows and holds that are over are closed first, so their tracks time out normally).
            expireLocked(t1)
            val pinned = holds.keys + setOfNotNull(lock?.boxId)
            val seen = tracker.update(moved, t1, pinned)
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
            if (motion.ids.isEmpty() && lock == null && holds.isEmpty()) return
            publishLocked(clock(), null)
        }
    }

    /** Frame pixels -> preview pixels for the current sizes (identity until both are known). Under stateLock. */
    private fun transformLocked(): FrameTransform =
        if (previewWidth > 0 && previewHeight > 0 && frameWidth > 0 && frameHeight > 0) {
            FrameTransform(frameWidth, frameHeight, previewWidth, previewHeight)
        } else {
            FrameTransform.identity(frameWidth.coerceAtLeast(1), frameHeight.coerceAtLeast(1))
        }

    /**
     * The boxes as the user sees them: the motion position when there is one, else the tracker's box; the locked
     * box and every held verdict are drawn even when the tracker has lost the object. Closes a passed window
     * (`UNKNOWN · no response`) and drops verdicts whose hold is over first. Under stateLock.
     */
    private fun publishLocked(now: Long, status: ((StatusUi) -> StatusUi)?) {
        expireLocked(now)
        val transform = transformLocked()
        val live = tracker.tracks
        val keep = live.map { it.id }.toMutableSet()
        lock?.let { keep += it.boxId }
        keep += holds.keys
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
        val liveIds = live.map { it.id }.toSet()
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
        for ((id, hold) in holds) {
            if (id in liveIds || id == lock?.boxId) continue
            val iff = iffById[id] ?: continue
            uis += TrackUi(id, ObjClass.DRONE, transform.toPreview(motion.box(id)?.box ?: hold.rect), iff, hold.score)
        }
        val signal = signalRows.map { (t, row) -> row.copy(ageMs = (now - t).coerceAtLeast(0L)) }
        _state.update { s ->
            s.copy(
                tracks = uis,
                status = status?.invoke(s.status) ?: s.status,
                signal = signal,
                events = eventLog.snapshot(),
            )
        }
    }

    /** A passed window becomes `UNKNOWN · no response` on the locked box; verdicts past their hold are cleared. */
    private fun expireLocked(now: Long) {
        val current = lock
        if (current != null && !current.isActive(now)) {
            val p = pending
            applyVerdictLocked(now, current, IffState.Verdict(VerdictKind.UNKNOWN, NO_RESPONSE, null, null, null, p?.source), null, emptyList())
            p?.job?.cancel()
        }
        val held = holds.entries.iterator()
        while (held.hasNext()) {
            val (id, hold) = held.next()
            if (now >= hold.until) {
                held.remove()
                if (iffById[id] is IffState.Verdict) iffById.remove(id)
            }
        }
    }

    /**
     * Puts [verdict] on the locked box [forLock], releases the lock, holds the verdict for [VERDICT_HOLD_MS] and
     * writes the gadget panel row and the structured event row. [answer] is null when nothing answered. Under
     * stateLock; the caller publishes.
     */
    private fun applyVerdictLocked(
        now: Long,
        forLock: BoxLock,
        verdict: IffState.Verdict,
        answer: SignalEvent.Answer?,
        notes: List<String>,
    ) {
        val id = forLock.boxId
        val liveTrack = tracker.tracks.firstOrNull { it.id == id }
        val rect = motion.box(id)?.box ?: liveTrack?.box ?: forLock.rect
        val score = liveTrack?.score ?: lockScore
        lock = null
        pending = null
        iffById[id] = verdict
        holds[id] = Hold(rect, now + VERDICT_HOLD_MS, score)

        signalRows.addFirst(now to SignalUi(verdict.droneId, verdict.kind, verdict.reason, verdict.rssiDbm, verdict.roundTripMs, 0L, verdict.source))
        while (signalRows.size > MAX_SIGNAL_ROWS) signalRows.removeLast()

        val fake = verdict.source == SignalKind.FAKE
        val parts = ArrayList<String>(10)
        parts += verdict.kind.name
        (verdict.reason ?: verdict.droneId)?.let { parts += it }
        parts += "box $id"
        verdict.roundTripMs?.let { parts += "rtt $it ms" }
        if (answer != null) {
            parts += "gadget ${answer.responseMs} ms"
            parts += verdict.rssiDbm?.let { "$it dBm" } ?: "RSSI n/a"
            parts += "nonce ${Redact.nonce(answer.nonce)}"
        }
        parts += notes.filter { it.startsWith("phone") }
        val text = parts.joinToString(" · ") + if (fake) " (fake)" else ""
        eventLog.add(
            EventUi(
                t = now,
                text = text,
                trackId = id,
                cls = ObjClass.DRONE,
                score = score,
                box = transformLocked().toPreview(rect),
                verdict = verdict.kind,
                reason = verdict.reason,
                droneId = verdict.droneId,
                rssiDbm = verdict.rssiDbm,
                roundTripMs = verdict.roundTripMs,
                source = verdict.source,
            ),
        )
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
     * A tap on box [boxId] (main thread): steps 1-2 of the class KDoc. Locks a live DRONE track for
     * [BoxLock.WINDOW_MS], marks it [IffState.Challenged], logs `challenge · box <id>` and publishes at once, so the
     * ring changes on the tap and not on the next tick; then runs the challenge on [scope]. Ignored while another
     * challenge is in flight, while this box still shows its verdict, and for a person or an unknown id.
     */
    fun challenge(boxId: Int) {
        val req: ChallengeRequest
        val seq: Long
        val until: Long
        val open: Pending
        synchronized(stateLock) {
            val now = clock()
            expireLocked(now)
            if (lock != null || boxId in holds) return
            val track = tracker.tracks.firstOrNull { it.id == boxId && it.cls == ObjClass.DRONE } ?: return
            until = now + BoxLock.WINDOW_MS
            lock = BoxLock(boxId, motion.box(boxId)?.box ?: track.box, until)
            lockScore = track.score
            seq = ++seqCounter
            req = ChallengeRequest(boxId, challengerId(), now / 1000L)
            open = Pending(seq, req)
            pending = open
            iffById[boxId] = IffState.Challenged(until = until)
            eventLog.add(now, "challenge · box $boxId")
            _state.update { s ->
                s.copy(
                    tracks = s.tracks.map { it.copy(iff = iffById[it.id] ?: IffState.Unchallenged) },
                    events = eventLog.snapshot(),
                )
            }
        }
        val job = scope.launch { runChallenge(seq, req, until) }
        synchronized(stateLock) {
            // An unconfined scope may already have finished it; a Reset in between gets the job cancelled here.
            if (pending === open) open.job = job else if (!job.isCompleted) job.cancel()
        }
    }

    /** Steps 2-3 off the main thread: send, wait for the answer inside the window, hand it to [onAnswer]. */
    private suspend fun runChallenge(seq: Long, req: ChallengeRequest, until: Long) {
        val t0 = elapsedMillis()
        try {
            val answer = withTimeoutOrNull((until - clock()).coerceAtLeast(1L)) {
                val events = try {
                    signalSource.challenge(req)
                } finally {
                    noteSource(seq, signalSource.kind)
                }
                events
                    .onEach { if (it is SignalEvent.Status) log(Redact.line(it.text, max = 120)) }
                    .firstOrNull { it is SignalEvent.Answer } as SignalEvent.Answer?
            }
            if (answer != null) {
                // A source that waited for the gadget before writing measures its own write-to-result time.
                onAnswer(seq, answer, answer.linkRoundTripMs ?: (elapsedMillis() - t0).coerceAtLeast(0L))
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GadgetUnavailableException) {
            onFailure(seq, e.reason)
            return
        } catch (e: Exception) {
            log("signal error · box ${req.boxId} · ${e.javaClass.simpleName}")
            onFailure(seq, LINK_ERROR)
            return
        }
        // Nothing answered (a silent source completes at once): the window closes on the clock, here or on a tick.
        val left = until - clock()
        if (left > 0) delay(left + 1)
        synchronized(stateLock) { publishLocked(clock(), null) }
    }

    /** Which source challenge [seq] went to, for the `FAKE` tag on a verdict without an answer (silent, failed). */
    private fun noteSource(seq: Long, kind: SignalKind) {
        synchronized(stateLock) { pending?.takeIf { it.seq == seq }?.source = kind }
    }

    /** The answer to challenge [seq], [roundTripMs] measured on the phone; dropped (and logged) if no longer open. */
    private fun onAnswer(seq: Long, answer: SignalEvent.Answer, roundTripMs: Long) {
        synchronized(stateLock) {
            val now = clock()
            val open = pending?.takeIf { it.seq == seq }
            val current = lock
            if (open == null || current == null || !current.isActive(now)) {
                expireLocked(now)
                reChecks.check(answer, roundTripMs) // the nonce is spent even when the answer comes too late
                eventLog.add(now, "late answer ignored · ${answer.verdict} · nonce ${Redact.nonce(answer.nonce)} · rtt $roundTripMs ms")
                publishLocked(now, null)
                return
            }
            val result = reChecks.check(answer, roundTripMs)
            val verdict = IffState.Verdict(result.verdict, result.reason, answer.droneId, answer.rssiDbm, roundTripMs, answer.source)
            applyVerdictLocked(now, current, verdict, answer, result.notes)
            publishLocked(now) { st -> answer.rssiDbm?.let { st.copy(rssiDbm = it) } ?: st }
        }
    }

    /**
     * An answer the signal source received for no open challenge (it arrived after its window closed or after a
     * Reset, or a result for an earlier request id, see `ReaderLink`): never drawn, but logged and its nonce spent in the phone's ledger, so the same
     * nonce is refused if it is ever replayed.
     */
    fun lateAnswer(answer: SignalEvent.Answer, why: String) {
        synchronized(stateLock) {
            val now = clock()
            reChecks.check(answer, 0L)
            eventLog.add(now, "$why · ${answer.verdict} · nonce ${Redact.nonce(answer.nonce)}")
            _state.update { it.copy(events = eventLog.snapshot()) }
        }
    }

    /** The source could not run challenge [seq] at all: `UNKNOWN · <reason>` at once. */
    private fun onFailure(seq: Long, reason: String) {
        synchronized(stateLock) {
            val now = clock()
            val current = lock
            val open = pending
            if (open == null || open.seq != seq || current == null) return
            applyVerdictLocked(now, current, IffState.Verdict(VerdictKind.UNKNOWN, reason, null, null, null, open.source), null, emptyList())
            publishLocked(now, null)
        }
    }

    /**
     * The Reset button (OBS-10): forgets the tracks, the nonce ledger, every verdict, the lock, the gadget panel
     * rows and the event log, so a rehearsal and the live run start clean. The challenge in flight is cancelled
     * (the USB source then waits for the reader's STATE IDLE before the next QUERY, see `ReaderLink`), and an answer
     * that still reaches the session is dropped as late. Track ids keep counting up (see [Tracker.reset]).
     */
    fun reset() {
        synchronized(stateLock) {
            pending?.job?.cancel()
            tracker.reset()
            motion.reset()
            reChecks.reset()
            eventLog.clear()
            iffById.clear()
            holds.clear()
            signalRows.clear()
            pending = null
            lock = null
            _state.update { it.copy(tracks = emptyList(), signal = emptyList(), events = emptyList()) }
        }
    }

    fun close() {
        scope.cancel()
        detector.close()
        signalSource.close()
    }

    companion object {
        /** How long a verdict stays drawn before the box is `drone · ?` again. */
        const val VERDICT_HOLD_MS: Long = 8_000L
        const val NO_RESPONSE = "no response"
        const val LINK_ERROR = "link error"
        const val DEFAULT_CHALLENGER_ID = 7
        private const val MAX_SIGNAL_ROWS = 20
    }
}
