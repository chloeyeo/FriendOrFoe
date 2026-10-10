package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.ObjClass

/**
 * Contract 3 of 3 (README section 3): the session produces it, stream 3 renders it and nothing else. Every field
 * is plain data so the UI can be built and screenshotted from [DemoScenario] without a camera, a model or a gadget.
 *
 * @property tracks every live box in preview (overlay) pixel coordinates with its IFF state
 * @property status the status strip
 * @property signal the latest answers, newest first
 * @property events the event log rows, newest first
 */
data class ObserverUiState(
    val tracks: List<TrackUi> = emptyList(),
    val status: StatusUi = StatusUi(),
    val signal: List<SignalUi> = emptyList(),
    val events: List<EventUi> = emptyList(),
)

/**
 * One drawn box: the tracker id (the id a tap challenges), its class, its box in preview pixels, its IFF state.
 *
 * @property score the detector score of the track's latest detection (0..1), shown on the box label; `0f` when not
 *   known (a lock whose object was lost, or a [DemoScenario] row that does not set it)
 */
data class TrackUi(val id: Int, val cls: ObjClass, val box: BoxF, val iff: IffState, val score: Float = 0f)

/**
 * The status strip: detector and timing numbers, runtime and link health.
 *
 * @property modelNote free text from the model loader for the strip — `model loading… 3 s`,
 *   `load 3.2 s · warm 1400 ms`, or a ClikaRT code name such as `LICENSE_FAILED` on failure; never the credential
 */
data class StatusUi(
    val detectorName: String = "none",
    val msPerFrame: Long = 0L,
    val fps: Float = 0f,
    val modelReady: Boolean = false,
    val offline: Boolean = true,
    val gadgetConnected: Boolean = false,
    val rssiDbm: Int? = null,
    val modelNote: String? = null,
)

/** One row of the signal/gadget panel: the gadget's latest answer after the phone's re-checks. */
data class SignalUi(
    val droneId: String?,
    val verdict: VerdictKind?,
    val reason: String?,
    val rssiDbm: Int?,
    val roundTripMs: Long?,
    val ageMs: Long,
)

/** One event log row; [t] is wall-clock milliseconds. */
data class EventUi(val t: Long, val text: String)

/** The IFF state drawn on a drone box. */
sealed interface IffState {
    /** Seen, not yet challenged: amber ring, `drone · ?`. */
    data object Unchallenged : IffState

    /**
     * A challenge is in flight: amber ring with a countdown arc.
     * @property until wall-clock milliseconds when the window closes
     * @property windowMs the window length, for the arc fraction (10 s)
     */
    data class Challenged(val until: Long, val windowMs: Long = DEFAULT_WINDOW_MS) : IffState

    /** The verdict after the phone's re-checks: green `FRIEND · id · dBm · ms`, red `FOE · reason`, red `UNKNOWN · no response`. */
    data class Verdict(
        val kind: VerdictKind,
        val reason: String?,
        val droneId: String?,
        val rssiDbm: Int?,
        val roundTripMs: Long?,
    ) : IffState

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 10_000L
    }
}
