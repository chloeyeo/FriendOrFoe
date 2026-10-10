package com.chloeyeo.friendorfoe.ui.overlay

import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.IffState
import com.chloeyeo.friendorfoe.session.TrackUi
import com.chloeyeo.friendorfoe.session.VerdictKind
import com.chloeyeo.friendorfoe.signal.SignalKind
import kotlin.math.ceil

/** Ring and tag colour family; the painter maps it to a theme colour. */
enum class Tone { AMBER, GREEN, RED, NEUTRAL }

/**
 * What the overlay draws for one track, decided without any drawing code so it can be unit-tested.
 * [detail] is an optional second, smaller line; [countdown] is non-null while a challenge is out.
 */
data class TrackTag(
    val text: String,
    val tone: Tone,
    val detail: String? = null,
    val countdown: Countdown? = null,
)

/** [fraction] runs from 1 (just challenged) to 0 (window closed); [seconds] is what the badge shows. */
data class Countdown(val fraction: Float, val seconds: Int)

const val TAG_SEP = " · "

fun tagFor(track: TrackUi, nowMs: Long): TrackTag {
    if (track.cls == ObjClass.PERSON) return TrackTag("person", Tone.NEUTRAL)
    return when (val iff = track.iff) {
        IffState.Unchallenged -> TrackTag("drone${TAG_SEP}?", Tone.AMBER)
        is IffState.Challenged -> {
            val c = countdown(iff.until, nowMs, iff.windowMs)
            TrackTag("drone${TAG_SEP}challenge ${c.seconds} s", Tone.AMBER, countdown = c)
        }
        is IffState.Verdict -> verdictTag(iff)
    }
}

/** A verdict from the scripted FakeSignalSource ends its tag with ` · FAKE`, so it is never mistaken for the gadget's. */
const val FAKE_MARK = "FAKE"

private fun verdictTag(v: IffState.Verdict): TrackTag {
    val fake = if (v.source == SignalKind.FAKE) FAKE_MARK else null
    return when (v.kind) {
        VerdictKind.FRIEND -> TrackTag(
            text = listOfNotNull("FRIEND", v.droneId, v.rssiDbm?.let(::dbm), v.roundTripMs?.let(::millis), fake).joinToString(TAG_SEP),
            tone = Tone.GREEN,
        )
        VerdictKind.FOE -> TrackTag(
            text = listOfNotNull("FOE", v.reason, fake).joinToString(TAG_SEP),
            tone = Tone.RED,
            detail = evidence(v),
        )
        VerdictKind.UNKNOWN -> TrackTag(
            text = listOfNotNull("UNKNOWN", v.reason ?: "no response", fake).joinToString(TAG_SEP),
            tone = Tone.RED,
            detail = evidence(v),
        )
    }
}

/** droneId, RSSI and round trip under a red tag, so a late or relayed answer shows its numbers. */
private fun evidence(v: IffState.Verdict): String? =
    listOfNotNull(v.droneId, v.rssiDbm?.let(::dbm), v.roundTripMs?.let(::millis))
        .takeIf { it.isNotEmpty() }
        ?.joinToString(TAG_SEP)

fun countdown(untilMs: Long, nowMs: Long, windowMs: Long = IffState.DEFAULT_WINDOW_MS): Countdown {
    val window = windowMs.coerceAtLeast(1)
    val remaining = (untilMs - nowMs).coerceIn(0L, window)
    return Countdown(
        fraction = remaining.toFloat() / window,
        seconds = ceil(remaining / 1000.0).toInt(),
    )
}

/** `-58` → `−58 dBm` (typographic minus reads better at a distance). */
fun dbm(rssi: Int): String = "${rssi.toString().replace('-', '−')} dBm"

fun millis(ms: Long): String = "$ms ms"
