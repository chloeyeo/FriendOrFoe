package com.chloeyeo.friendorfoe.net

import com.chloeyeo.friendorfoe.session.EventUi

/**
 * Which event-log rows still have to go to the dashboard. The log is newest first and the phone remembers the
 * newest row it sent; everything above that row is new. If that row is gone (Reset, or a new demo run) the
 * dashboard is reset first and gets the whole log again, so its counts always match the phone's.
 */
object EventDiff {
    data class Plan(val reset: Boolean, val send: List<EventUi>)

    /** [send] is oldest first, the order the rows happened. */
    fun plan(events: List<EventUi>, lastSent: EventUi?): Plan {
        if (lastSent == null) return Plan(reset = false, send = events.asReversed())
        val i = events.indexOf(lastSent)
        return if (i < 0) Plan(reset = true, send = events.asReversed()) else Plan(reset = false, send = events.subList(0, i).asReversed())
    }
}
