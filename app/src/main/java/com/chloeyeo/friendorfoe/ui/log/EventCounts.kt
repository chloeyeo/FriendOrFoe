package com.chloeyeo.friendorfoe.ui.log

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.EventUi
import com.chloeyeo.friendorfoe.session.VerdictKind
import kotlin.math.roundToInt

/**
 * The counts row of the event log and the dashboard. Drones and people are distinct tracks (one object seen in
 * many frames counts once; a row without a track id counts on its own); friend / foe / unknown are verdicts, one
 * per challenge. Plain status lines count for nothing.
 */
data class EventCounts(val drones: Int, val people: Int, val friend: Int, val foe: Int, val unknown: Int) {
    companion object {
        fun from(events: List<EventUi>) = EventCounts(
            drones = objects(events, ObjClass.DRONE),
            people = objects(events, ObjClass.PERSON),
            friend = events.count { it.verdict == VerdictKind.FRIEND },
            foe = events.count { it.verdict == VerdictKind.FOE },
            unknown = events.count { it.verdict == VerdictKind.UNKNOWN },
        )

        private fun objects(events: List<EventUi>, cls: ObjClass): Int {
            val rows = events.filter { it.cls == cls }
            return rows.mapNotNull { it.trackId }.distinct().size + rows.count { it.trackId == null }
        }
    }
}

/** `now`, `12 s ago`, `3 min ago`, `2 h ago`. */
fun ageText(ageMs: Long): String {
    val s = (ageMs / 1000).coerceAtLeast(0)
    return when {
        s < 1 -> "now"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}

/** A preview-pixel box as `l,t → r,b`, rounded to whole pixels. */
fun boxText(box: BoxF): String =
    "${box.left.roundToInt()},${box.top.roundToInt()} → ${box.right.roundToInt()},${box.bottom.roundToInt()}"
