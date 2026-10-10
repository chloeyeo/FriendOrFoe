package com.chloeyeo.friendorfoe.net

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.session.EventUi
import com.chloeyeo.friendorfoe.session.TrackUi
import com.chloeyeo.friendorfoe.ui.overlay.tagFor
import java.util.Locale

/**
 * JSON bodies for the laptop dashboard (`laptop/dashboard/app.py`). Hand-built so nothing on the posting path
 * needs a JSON library, and the exact output is unit-tested on the JVM.
 *
 * Event: {t, class, box, iffStatus, droneId, reason} (the dashboard's schema) plus text, trackId, score, rssiDbm,
 * roundTripMs, source (`USB_GADGET` / `FAKE`); `class`/`box`/`iffStatus`/`source` are null on a plain status line.
 * Frame: {t, width, height, tracks:[{id, class, box, tag, tone, detail}]}: the tracks as the phone draws them,
 * boxes in preview pixels of a width x height preview, with the phone's own tag text.
 */
object EventJson {
    fun event(e: EventUi): String = obj(
        "t" to e.t,
        "class" to e.cls?.name?.lowercase(),
        "box" to e.box?.let(::box),
        "iffStatus" to e.verdict?.name,
        "droneId" to e.droneId,
        "reason" to e.reason,
        "text" to e.text,
        "trackId" to e.trackId,
        "score" to e.score,
        "rssiDbm" to e.rssiDbm,
        "roundTripMs" to e.roundTripMs,
        "source" to e.source?.name,
    )

    /** Several events as one JSON array (the dashboard accepts a list). */
    fun events(events: List<EventUi>): String = events.joinToString(",", "[", "]") { event(it) }

    fun frame(t: Long, tracks: List<TrackUi>, width: Int, height: Int): String = obj(
        "t" to t,
        "width" to width,
        "height" to height,
        "tracks" to Raw(tracks.joinToString(",", "[", "]") { track(it, t) }),
    )

    private fun track(tr: TrackUi, now: Long): String {
        val tag = tagFor(tr, now)
        return obj(
            "id" to tr.id,
            "class" to tr.cls.name.lowercase(),
            "box" to box(tr.box),
            "tag" to tag.text,
            "tone" to tag.tone.name.lowercase(),
            "detail" to tag.detail,
        )
    }

    private class Raw(val json: String)

    private fun box(b: BoxF) = Raw("[${num(b.left)},${num(b.top)},${num(b.right)},${num(b.bottom)}]")

    private fun num(f: Float) = String.format(Locale.US, "%.1f", f)

    private fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${value(v)}" }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Raw -> v.json
        is String -> quote(v)
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Number, is Boolean -> v.toString()
        else -> quote(v.toString())
    }

    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format(Locale.US, "\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
