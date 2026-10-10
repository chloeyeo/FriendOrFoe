package com.chloeyeo.friendorfoe.signal

import kotlinx.coroutines.channels.Channel

/**
 * Ties each gadget answer to the challenge it belongs to (SIG-03 attribution), for [UsbGadgetSignalSource]. Pure
 * Kotlin and thread-safe (every method is `@Synchronized`), so it is unit-tested without USB.
 *
 * The problem it solves: the gadget answers exactly once per challenge, but its own timeout (`UNKNOWN · no
 * response`, `responseMs` 10000) runs 10 s from the end of its radio transmission, so it lands just AFTER the
 * phone's 10 s window has closed. Without a guard that stale answer would go to the next tap's inbox and be drawn
 * on the wrong box. Three rules, strongest first:
 *
 * 1. **seq echo**: every challenge line carries the phone's [Open.seq]; an answer whose `seq` differs from the open
 *    challenge is dropped. An answer whose `boxId` echo differs is dropped too.
 * 2. **busy after silence**: a challenge that ends without its answer (window passed, Reset) leaves the gadget
 *    [Stale] until that answer arrives (it is then swallowed) or [gadgetTimeoutMs] + [graceMs] after the write;
 *    [begin] refuses a new challenge meanwhile with `gadget busy`.
 * 3. **age check** (firmware without the echo): an answer whose gadget-side `responseMs` is longer than the time
 *    since this challenge's write plus [slackMs] cannot belong to it and is dropped.
 *
 * @param clock monotonic milliseconds (`SystemClock.elapsedRealtime()` on the phone)
 */
class AnswerRouter(
    private val clock: () -> Long,
    private val gadgetTimeoutMs: Long = 10_000L,
    private val graceMs: Long = 1_500L,
    private val slackMs: Long = 300L,
) {
    /** The challenge in flight: its echo key, the box it is for, when its line was written, and its event inbox. */
    class Open internal constructor(val seq: Long, val boxId: Int, val writtenAt: Long) {
        val inbox: Channel<SignalEvent> = Channel(Channel.UNLIMITED)
        internal var answered = false
    }

    /** A challenge that ended without its answer; the gadget may still send it until [until]. */
    private class Stale(val seq: Long, val boxId: Int, val until: Long)

    /** Where [route] put a line. */
    sealed interface Route {
        /** Went to the open challenge's inbox. */
        data object Delivered : Route

        /** Belongs to no challenge (status replies, an answer with nothing open): for the listener / event log. */
        data object Unclaimed : Route

        /** An answer for an earlier challenge, discarded; [why] is for the event log. */
        data class Dropped(val why: String) : Route
    }

    private var open: Open? = null
    private var stale: Stale? = null
    private var nextSeq = 0L

    /**
     * Opens a challenge for [boxId] and stamps its write time; call right before writing the line with [Open.seq].
     * Throws [GadgetUnavailableException] (`gadget busy`) while the previous challenge's answer may still arrive.
     */
    @Synchronized
    fun begin(boxId: Int): Open {
        val now = clock()
        open?.let { o ->
            // A challenge whose flow was never collected (cancelled between call and collect) heals by timeout.
            if (now < o.writtenAt + gadgetTimeoutMs + graceMs) throw GadgetUnavailableException(BUSY)
            open = null
        }
        stale?.let { s -> if (now < s.until) throw GadgetUnavailableException(BUSY) else stale = null }
        return Open(++nextSeq, boxId, now).also { open = it }
    }

    /** The write failed: forget [o] (the gadget never saw it, so nothing stale is left behind). */
    @Synchronized
    fun abort(o: Open) {
        if (open === o) open = null
        o.inbox.close()
    }

    /** [o]'s flow ended (answer, window passed, Reset). Without its answer the gadget is [Stale] for a while. */
    @Synchronized
    fun finish(o: Open) {
        if (open !== o) return
        open = null
        if (!o.answered) stale = Stale(o.seq, o.boxId, o.writtenAt + gadgetTimeoutMs + graceMs)
        o.inbox.close()
    }

    /** One parsed line from the gadget. */
    @Synchronized
    fun route(line: GadgetLineCodec.Line): Route {
        val event = line.event
        val o = open
        if (event !is SignalEvent.Answer) {
            return if (o != null && o.inbox.trySend(event).isSuccess) Route.Delivered else Route.Unclaimed
        }
        if (o == null) {
            val s = stale ?: return Route.Unclaimed
            stale = null
            return Route.Dropped("late answer for box ${s.boxId} ignored")
        }
        val why = when {
            line.seq != null && line.seq != o.seq -> "answer for challenge ${line.seq}, not ${o.seq}"
            line.boxId != null && line.boxId != o.boxId -> "answer for box ${line.boxId}, not ${o.boxId}"
            line.seq == null && event.responseMs > clock() - o.writtenAt + slackMs ->
                "answer older than this challenge (gadget ${event.responseMs} ms)"
            else -> null
        }
        if (why != null) {
            // Most likely the stale challenge's answer: it has now arrived, so the gadget is free again.
            stale = null
            return Route.Dropped(why)
        }
        if (!o.inbox.trySend(event).isSuccess) return Route.Unclaimed
        o.answered = true
        return Route.Delivered
    }

    /** The port is gone: the open challenge ends with [cause]; a re-plugged gadget starts with nothing stale. */
    @Synchronized
    fun failAll(cause: Throwable) {
        open?.inbox?.close(cause)
        open = null
        stale = null
    }

    /** Whether a challenge would be refused right now (for tests and the log). */
    @Synchronized
    fun isBusy(): Boolean {
        val now = clock()
        return open?.let { now < it.writtenAt + gadgetTimeoutMs + graceMs } == true || stale?.let { now < it.until } == true
    }

    companion object {
        const val BUSY = "gadget busy"
    }
}
