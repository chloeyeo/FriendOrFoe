package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.ObjClass

/**
 * The scripted sequence of [ObserverUiState]s the UI stream builds and screenshots against (README section 2):
 * a drone appears unchallenged, it is challenged with a countdown, then every verdict in demo order. Pure data,
 * unit-tested; boxes are laid out in a [previewWidth] x [previewHeight] preview, so the screen passes its own
 * canvas size and the rings land where they would with a camera.
 */
object DemoScenario {
    /** One title per element of [states], for the "next state" button. */
    val titles: List<String> = listOf(
        "drone appears",
        "challenged · countdown",
        "FRIEND · RQ-07",
        "FOE · replayed nonce",
        "FOE · position mismatch",
        "FOE · late response",
        "UNKNOWN · no response",
    )

    private const val PERSON_ID = 1
    private const val DRONE_ID = 2
    private const val DRONE_NAME = "RQ-07"

    fun states(
        now: Long = System.currentTimeMillis(),
        previewWidth: Int = 1920,
        previewHeight: Int = 1080,
    ): List<ObserverUiState> {
        val w = previewWidth.toFloat()
        val h = previewHeight.toFloat()
        val droneBox = BoxF(w * 0.30f, h * 0.22f, w * 0.42f, h * 0.40f)
        val personBox = BoxF(w * 0.66f, h * 0.30f, w * 0.80f, h * 0.98f)
        val status = StatusUi(
            detectorName = "fake",
            msPerFrame = 180,
            fps = 1.0f,
            modelReady = true,
            offline = true,
            gadgetConnected = true,
            rssiDbm = -58,
        )

        fun tracks(iff: IffState) = listOf(
            TrackUi(PERSON_ID, ObjClass.PERSON, personBox, IffState.Unchallenged),
            TrackUi(DRONE_ID, ObjClass.DRONE, droneBox, iff),
        )

        val events = ArrayList<EventUi>()
        var t = now - 12_000L
        fun log(text: String, event: (Long) -> EventUi = { EventUi(it, text) }): List<EventUi> {
            t += 1_500L
            events.add(0, event(t))
            return events.toList()
        }
        fun detected(id: Int, cls: ObjClass, score: Float, box: BoxF): (Long) -> EventUi =
            { EventUi(it, "${cls.name.lowercase()} detected · box $id", id, cls, score, box) }

        val signal = ArrayList<SignalUi>()
        fun answered(droneId: String?, verdict: VerdictKind, reason: String?, rssi: Int?, ms: Long?): List<SignalUi> {
            signal.add(0, SignalUi(droneId, verdict, reason, rssi, ms, ageMs = 400L))
            return signal.toList()
        }

        val out = ArrayList<ObserverUiState>(7)

        log("person detected · box $PERSON_ID", detected(PERSON_ID, ObjClass.PERSON, 0.91f, personBox))
        val appeared = log("drone detected · box $DRONE_ID", detected(DRONE_ID, ObjClass.DRONE, 0.42f, droneBox))
        out += ObserverUiState(tracks(IffState.Unchallenged), status, emptyList(), appeared)

        val challenged = log("challenge sent · box $DRONE_ID · nonce 3f9a…")
        out += ObserverUiState(tracks(IffState.Challenged(until = now + 7_000L)), status, signal.toList(), challenged)

        fun verdictState(kind: VerdictKind, reason: String?, droneId: String?, rssi: Int?, ms: Long?, line: String): ObserverUiState {
            val sig = answered(droneId, kind, reason, rssi, ms)
            val ev = log(line) {
                EventUi(it, line, DRONE_ID, ObjClass.DRONE, 0.42f, droneBox, kind, reason, droneId, rssi, ms)
            }
            return ObserverUiState(
                tracks(IffState.Verdict(kind, reason, droneId, rssi, ms)),
                status.copy(rssiDbm = rssi ?: status.rssiDbm),
                sig,
                ev,
            )
        }

        out += verdictState(VerdictKind.FRIEND, null, DRONE_NAME, -58, 42L, "FRIEND · $DRONE_NAME · -58 dBm · 42 ms")
        out += verdictState(VerdictKind.FOE, "replayed nonce", DRONE_NAME, -61, 40L, "FOE · replayed nonce · $DRONE_NAME")
        out += verdictState(VerdictKind.FOE, "position mismatch", DRONE_NAME, -66, 180L, "FOE · position mismatch · $DRONE_NAME")
        out += verdictState(VerdictKind.FOE, "late response", DRONE_NAME, -60, 3450L, "FOE · late response · $DRONE_NAME · 3450 ms")
        out += verdictState(VerdictKind.UNKNOWN, "no response", null, null, null, "UNKNOWN · no response · box $DRONE_ID")
        return out
    }
}
