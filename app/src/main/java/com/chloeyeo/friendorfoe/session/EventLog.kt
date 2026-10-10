package com.chloeyeo.friendorfoe.session

/** A bounded, newest-first log of [EventUi] rows; the oldest row falls off when [capacity] is exceeded. */
class EventLog(private val capacity: Int = 200) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val items = ArrayDeque<EventUi>()

    val size: Int get() = synchronized(items) { items.size }

    fun add(t: Long, text: String): EventUi = add(EventUi(t, text))

    /** Adds a structured row (detection or verdict, see [EventUi]). */
    fun add(event: EventUi): EventUi = synchronized(items) {
        items.addFirst(event)
        while (items.size > capacity) items.removeLast()
        event
    }

    /** Newest first. */
    fun snapshot(): List<EventUi> = synchronized(items) { items.toList() }

    fun clear() = synchronized(items) { items.clear() }
}
