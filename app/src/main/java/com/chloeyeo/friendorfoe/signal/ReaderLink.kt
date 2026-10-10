package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.signal.GadgetLineCodec.ReaderEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.SecureRandom

/**
 * Ihan's reader protocol over any byte link (docs/GADGET_LINK.md); [UsbGadgetSignalSource] is the USB transport,
 * tests drive it through a fake [Port]. Pure Kotlin, no Android.
 *
 * - **Handshake** ([attach], [tick]): `STATUS <id>` at once, again every 1 s, give up after 5 s (as Ihan's host).
 *   The reader is usable once a STATUS says `role reader · interface_version 2 · manual_query true` with a
 *   `boot_id`; STATE lines then keep [reader] current. A STATE `BOOT` or a new `boot_id` means the board rebooted:
 *   the query in flight ends `UNKNOWN · gadget rebooted` and the handshake starts again.
 * - **One challenge** ([challenge]): waits (up to 3.2 s) for the reader to be IDLE (it refuses a QUERY for 3 s from
 *   the previous query's start, even after FRIEND), writes `QUERY <request_id>`, and ends with exactly one
 *   [SignalEvent.Answer] mapped by [ReaderVerdicts], or a [GadgetUnavailableException]: ACK refused -> its reason;
 *   no ACK in 2 s -> `gadget no ack`; no RESULT 5 s after the ACK -> `gadget no result` (one `STATUS` at 3.5 s first,
 *   whose `last_result` recovers a lost RESULT; its round trip is then the reader's `response_ms` / `elapsed_ms`,
 *   not the STATUS arrival time).
 * - **Attribution**: the request id is `<launch prefix>-<seq>-<boxId>` (at most 32 of `[A-Za-z0-9_-]`), unique
 *   across app restarts, so the reader never takes it for a duplicate. Only lines carrying the open query's id reach
 *   it; a RESULT for one of this phone's earlier ids goes to [Listener.onStaleAnswer] and is never drawn.
 * - **Nonce**: RESULT carries none; it is taken from the query's `TX_CHALLENGE` (or the first counted
 *   `RX_RESPONSE`). Those sit in the reader's lossy queue and may come after the RESULT, so the answer waits up to
 *   300 ms for one, then goes out with `nonce = null` (the phone's replay check is then skipped with a note).
 * - **RSSI**: Ihan's reader reports none; an `rssi_dbm` / `rssi` field is read if a later build adds one.
 *
 * Threading: [feed] from one reader thread, [tick] from a timer, [challenge] from a coroutine; state is under one
 * lock, and listener calls and port writes happen outside it.
 *
 * @param clock monotonic milliseconds (`SystemClock.elapsedRealtime()` on the phone)
 * @param idPrefix per-launch random prefix of every request id
 */
class ReaderLink(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val idPrefix: String = newPrefix(),
    val timing: Timing = Timing(),
) : SignalSource {
    override val kind: SignalKind = SignalKind.USB_GADGET

    /** The byte sink to the reader (the USB serial port). A failed write throws [IOException]. */
    fun interface Port {
        @Throws(IOException::class)
        fun write(bytes: ByteArray)
    }

    interface Listener {
        /** A line for the event log (already redacted). */
        fun onLine(text: String) {}

        /** The reader became usable ([ready]) or stopped being so, with a note for the log. */
        fun onReader(ready: Boolean, note: String) {}

        /** A RESULT for one of this phone's earlier queries, after its challenge ended: logged, never drawn. */
        fun onStaleAnswer(answer: SignalEvent.Answer, why: String) {
            onLine("$why · ${answer.verdict}${answer.reason?.let { " · $it" } ?: ""}")
        }
    }

    /** Phone-side timing (milliseconds); Ihan's host uses 2 s for the ACK and 4 s for the RESULT. */
    data class Timing(
        val ackMs: Long = 2_000L,
        val resultAfterAckMs: Long = 5_000L,
        val recoveryStatusMs: Long = 3_500L,
        val idleWaitMs: Long = 3_200L,
        val nonceGraceMs: Long = 300L,
        val readyWaitMs: Long = 1_500L,
        val handshakeRetryMs: Long = 1_000L,
        val handshakeGiveUpMs: Long = 5_000L,
    )

    /** What the last compatible STATUS (and the STATE lines since) said about the reader. */
    data class ReaderInfo(val bootId: String, val state: String, val keyConfigured: Boolean, val radioReady: Boolean)

    @Volatile var listener: Listener? = null

    private val lock = Any()
    private val splitter = LineSplitter()
    private var port: Port? = null
    private val _reader = MutableStateFlow<ReaderInfo?>(null)

    /** The handshaken reader, null while none is (no port, handshake pending or failed, reboot, download mode). */
    val reader: StateFlow<ReaderInfo?> = _reader.asStateFlow()

    private var handshakeId: String? = null
    private var handshakeDeadline = 0L
    private var nextHandshakeAt = 0L
    private var downloadMode = false
    private var open: Query? = null
    private var querySeq = 0L
    private var statusSeq = 0L

    /** This phone's recent request ids -> box id, for telling a late RESULT of ours from someone else's. */
    private val issued = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 16
    }

    private class Query(val id: String, val boxId: Int) {
        val inbox: Channel<ReaderEvent> = Channel(Channel.UNLIMITED)
        @Volatile var writtenAt = 0L
    }

    val isAttached: Boolean get() = synchronized(lock) { port != null }

    // ---- transport side ----

    /** A port opened: forget the old reader and start the STATUS handshake. */
    fun attach(p: Port) {
        val line = synchronized(lock) {
            port = p
            synchronized(splitter) { splitter.reset() }
            _reader.value = null
            downloadMode = false
            startHandshakeLocked()
        }
        send(p, line)
    }

    /** The port is gone ([why]: `gadget unplugged`, ...): the query in flight ends with it. */
    fun detach(why: String) {
        synchronized(lock) {
            port = null
            handshakeId = null
            downloadMode = false
            _reader.value = null
            open?.inbox?.close(GadgetUnavailableException(why))
        }
    }

    /** Bytes read from the port, in any chunking. */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        val pieces = synchronized(splitter) { splitter.feed(bytes, offset, length) }
        for (piece in pieces) {
            try {
                handle(piece)
            } catch (e: Exception) {
                val text = (piece as? LineSplitter.Piece.Line)?.text ?: ""
                listener?.onLine("gadget line skipped (${e.javaClass.simpleName}): ${Redact.line(text)}")
            }
        }
    }

    /** Handshake retries; call every few hundred ms while a port is attached. */
    fun tick() {
        var write: Pair<Port, String>? = null
        var note: String? = null
        synchronized(lock) {
            val p = port ?: return
            if (_reader.value != null || handshakeId == null) return
            val now = clock()
            if (now >= handshakeDeadline) {
                handshakeId = null
                note = "reader did not answer STATUS in ${timing.handshakeGiveUpMs / 1000} s (not a FoF reader v2?)"
            } else if (now >= nextHandshakeAt) {
                val id = nextStatusIdLocked()
                handshakeId = id
                nextHandshakeAt = now + timing.handshakeRetryMs
                write = p to GadgetLineCodec.encodeStatus(id)
            }
        }
        note?.let { listener?.onReader(false, it) }
        write?.let { (p, line) -> send(p, line) }
    }

    // ---- challenge side ----

    override suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent> {
        synchronized(lock) {
            if (port == null) throw GadgetUnavailableException(NO_GADGET)
            if (downloadMode) throw GadgetUnavailableException(DOWNLOAD_MODE)
        }
        return flow { run(req) }
    }

    private suspend fun FlowCollector<SignalEvent>.run(req: ChallengeRequest) {
        var info = _reader.value
        if (info == null) {
            emit(SignalEvent.Status("reader not ready · asking STATUS"))
            val retry = synchronized(lock) { port?.takeIf { handshakeId == null }?.let { it to startHandshakeLocked() } }
            retry?.let { (p, line) -> send(p, line) }
            info = withTimeoutOrNull(timing.readyWaitMs) { _reader.filterNotNull().first() }
                ?: throw GadgetUnavailableException(NOT_READY)
        }
        if (!info.keyConfigured) throw GadgetUnavailableException(ReaderVerdicts.ackReason("KEY_NOT_CONFIGURED"))
        if (!info.radioReady) throw GadgetUnavailableException(ReaderVerdicts.RADIO_ERROR)
        if (info.state != IDLE) {
            emit(SignalEvent.Status("reader ${info.state.lowercase()} · waiting for idle"))
            withTimeoutOrNull(timing.idleWaitMs) { _reader.first { it == null || it.state == IDLE } }
        }
        val (q, p) = begin(req.boxId)
        try {
            q.writtenAt = clock()
            if (!send(p, GadgetLineCodec.encodeQuery(q.id))) throw GadgetUnavailableException(WRITE_FAILED)
            emit(SignalEvent.Status("QUERY ${q.id} · box ${req.boxId}"))
            emit(await(q, p))
        } finally {
            finish(q)
        }
    }

    private suspend fun FlowCollector<SignalEvent>.await(q: Query, p: Port): SignalEvent.Answer {
        var ackedAt = -1L
        var result: ReaderEvent.Result? = null
        var resultAt = 0L
        var recovered = false
        var recoverySent = false
        var nonce: ByteArray? = null
        var droneId: Int? = null
        var rssi: Int? = null
        while (true) {
            val now = clock()
            val r = result
            if (r != null) {
                if (nonce != null || now >= resultAt + timing.nonceGraceMs) {
                    if (nonce == null) emit(SignalEvent.Status("reader did not log the challenge nonce"))
                    // A recovered result arrived with the STATUS at 3.5 s, not when the reader decided: its round
                    // trip is the reader's own timing, never the STATUS arrival time.
                    val roundTrip = if (recovered) r.responseMs ?: r.elapsedMs else resultAt - q.writtenAt
                    if (recovered) emit(SignalEvent.Status("round trip from the reader's timing · $roundTrip ms"))
                    return ReaderVerdicts.answer(r, nonce, droneId, rssi, roundTrip)
                }
            } else {
                if (!recoverySent && now >= q.writtenAt + timing.recoveryStatusMs) {
                    recoverySent = true
                    send(p, GadgetLineCodec.encodeStatus(synchronized(lock) { nextStatusIdLocked() }))
                    emit(SignalEvent.Status("no RESULT yet · asking STATUS"))
                    continue
                }
                if (ackedAt < 0 && now >= q.writtenAt + timing.ackMs) throw GadgetUnavailableException(NO_ACK)
                if (ackedAt >= 0 && now >= ackedAt + timing.resultAfterAckMs) throw GadgetUnavailableException(NO_RESULT)
            }
            val wakeAt = if (r != null) {
                resultAt + timing.nonceGraceMs
            } else {
                minOf(
                    if (recoverySent) Long.MAX_VALUE else q.writtenAt + timing.recoveryStatusMs,
                    if (ackedAt < 0) q.writtenAt + timing.ackMs else ackedAt + timing.resultAfterAckMs,
                )
            }
            val ev = withTimeoutOrNull((wakeAt - now).coerceAtLeast(1L)) { q.inbox.receive() } ?: continue
            when (ev) {
                is ReaderEvent.Ack -> when {
                    ev.accepted -> if (ackedAt < 0) {
                        ackedAt = clock()
                        if (ev.reason == "DUPLICATE") emit(SignalEvent.Status("reader: duplicate request id"))
                    }
                    ackedAt < 0 -> throw GadgetUnavailableException(ReaderVerdicts.ackReason(ev.reason))
                }
                is ReaderEvent.Packet -> if (ev.tx) {
                    if (ev.nonce != null) nonce = ev.nonce
                    droneId = ev.droneId ?: droneId
                    emit(SignalEvent.Status("challenge on air · nonce ${Redact.nonce(ev.nonce)}"))
                    if (ev.result != "BROADCAST_STARTED") emit(SignalEvent.Status("reader tx ${ev.result.lowercase()}"))
                } else if (!ev.ignored) {
                    if (nonce == null) nonce = ev.nonce
                    ev.rssiDbm?.let { rssi = it }
                    emit(SignalEvent.Status("reader rx ${ev.result.lowercase()} · ${ev.elapsedMs ?: "?"} ms"))
                }
                is ReaderEvent.Result -> if (result == null) {
                    result = ev
                    resultAt = clock()
                    if (ackedAt < 0) ackedAt = resultAt
                }
                is ReaderEvent.Status -> {
                    val last = ev.lastResult
                    if (result == null && last != null && last.requestId == q.id) {
                        // STATUS always marks last_result replay:true; for this query's own id it is the verdict.
                        result = last.copy(replay = false)
                        recovered = true
                        resultAt = clock()
                        if (ackedAt < 0) ackedAt = resultAt
                        emit(SignalEvent.Status("result recovered from STATUS"))
                    }
                }
                is ReaderEvent.Diag -> emit(
                    SignalEvent.Status("reader radio diag · error ${ev.radioError} · overflow ${ev.queueOverflow}"),
                )
                else -> Unit
            }
        }
    }

    private fun begin(boxId: Int): Pair<Query, Port> = synchronized(lock) {
        val p = port ?: throw GadgetUnavailableException(NO_GADGET)
        if (open != null) throw GadgetUnavailableException(BUSY)
        val seq = ++querySeq
        val id = "$idPrefix-$seq-$boxId".takeIf(GadgetLineCodec::isValidRequestId) ?: "$idPrefix-$seq"
        issued[id] = boxId
        Query(id, boxId).also { open = it } to p
    }

    private fun finish(q: Query) {
        synchronized(lock) { if (open === q) open = null }
        q.inbox.close()
    }

    // ---- line handling ----

    private fun handle(piece: LineSplitter.Piece) {
        val text = when (piece) {
            is LineSplitter.Piece.Oversize -> {
                listener?.onLine("gadget line over ${LineSplitter.MAX_LINE_BYTES} bytes skipped (${piece.bytes} bytes)")
                return
            }
            is LineSplitter.Piece.Line -> piece.text
        }
        val ev = try {
            GadgetLineCodec.parse(text)
        } catch (e: IllegalArgumentException) {
            listener?.onLine("gadget line skipped: ${Redact.line(text)}")
            return
        } ?: return
        val after = ArrayList<(Listener) -> Unit>(1)
        var write: Pair<Port, String>? = null
        synchronized(lock) {
            when (ev) {
                is ReaderEvent.Text -> if (ev.downloadMode) {
                    downloadMode = true
                    _reader.value = null
                    handshakeId = null
                    failOpenLocked(DOWNLOAD_MODE)
                    after += { it.onReader(false, "reader in download mode · release BOOT, then press RESET or re-plug") }
                } else {
                    after += { it.onLine("gadget: ${Redact.line(ev.text)}") }
                }
                is ReaderEvent.Status -> onStatusLocked(ev, after)
                is ReaderEvent.State -> {
                    val prev = _reader.value
                    if (ev.reason == "BOOT" || (prev != null && prev.bootId != ev.bootId)) {
                        _reader.value = null
                        if (prev != null) {
                            failOpenLocked(REBOOTED)
                            after += { it.onReader(false, "reader rebooted · checking STATUS") }
                        }
                        val p = port
                        if (p != null && handshakeId == null) write = p to startHandshakeLocked()
                    } else if (prev != null) {
                        _reader.value = prev.copy(state = ev.state)
                    }
                }
                is ReaderEvent.Ack -> {
                    val q = open
                    ev.state?.let { s -> _reader.value?.let { _reader.value = it.copy(state = s) } }
                    if (q != null && (ev.requestId == q.id || (ev.requestId.isEmpty() && !ev.accepted))) {
                        q.inbox.trySend(ev)
                    } else {
                        after += { it.onLine("gadget ack ${ev.reason.lowercase()} for ${ev.requestId.ifEmpty { "-" }} ignored") }
                    }
                }
                is ReaderEvent.Result -> {
                    val q = open
                    if (q != null && ev.requestId == q.id) {
                        q.inbox.trySend(ev)
                    } else {
                        val box = issued[ev.requestId]
                        if (box != null) {
                            val answer = ReaderVerdicts.answer(ev, null, null, null, null)
                            after += { it.onStaleAnswer(answer, "late result for box $box ignored") }
                        } else {
                            after += { it.onLine("gadget result for another request ignored · ${ev.verdict} ${ev.reason}") }
                        }
                    }
                }
                is ReaderEvent.Packet -> open?.takeIf { it.id == ev.requestId }?.inbox?.trySend(ev)
                is ReaderEvent.Diag -> {
                    after += {
                        it.onLine(
                            ev.droppedPacketLogs?.let { n -> "gadget dropped $n packet log lines" }
                                ?: "gadget radio diag · error ${ev.radioError} · overflow ${ev.queueOverflow}",
                        )
                    }
                    if (ev.radioError || ev.queueOverflow) open?.inbox?.trySend(ev)
                }
                is ReaderEvent.Help -> after += { it.onLine("gadget help · ${ev.commands.joinToString(", ")}") }
                is ReaderEvent.Other -> after += { it.onLine("gadget event ${ev.event.ifEmpty { "?" }} ignored") }
            }
        }
        listener?.let { l -> after.forEach { it(l) } }
        write?.let { (p, line) -> send(p, line) }
    }

    private fun onStatusLocked(ev: ReaderEvent.Status, after: MutableList<(Listener) -> Unit>) {
        if (!ev.isCompatibleReader) {
            _reader.value = null
            handshakeId = null
            failOpenLocked(NOT_READY)
            after += { it.onReader(false, "USB device is not a FoF reader v2 (role ${ev.role ?: "?"}, interface ${ev.interfaceVersion ?: "?"})") }
            return
        }
        val prev = _reader.value
        val info = ReaderInfo(ev.bootId!!, ev.state ?: IDLE, ev.keyConfigured, ev.radioReady)
        if (prev != null && prev.bootId != info.bootId) failOpenLocked(REBOOTED)
        _reader.value = info
        handshakeId = null
        if (prev == null || prev.bootId != info.bootId) {
            val note = "reader ready · boot ${info.bootId.take(8)} · key ${if (info.keyConfigured) "set" else "MISSING"} · " +
                "radio ${if (info.radioReady) "ok" else "ERROR"} · ${info.state.lowercase()}"
            after += { it.onReader(true, note) }
        }
        open?.inbox?.trySend(ev)
    }

    private fun failOpenLocked(why: String) {
        open?.inbox?.close(GadgetUnavailableException(why))
    }

    private fun startHandshakeLocked(): String {
        val now = clock()
        val id = nextStatusIdLocked()
        handshakeId = id
        handshakeDeadline = now + timing.handshakeGiveUpMs
        nextHandshakeAt = now + timing.handshakeRetryMs
        return GadgetLineCodec.encodeStatus(id)
    }

    private fun nextStatusIdLocked(): String = "$idPrefix-s${++statusSeq}"

    /** Writes one ASCII command line; false when the port refused it (the transport drops the port itself). */
    private fun send(p: Port, line: String): Boolean = try {
        p.write((line + "\n").toByteArray(Charsets.US_ASCII))
        true
    } catch (e: IOException) {
        false
    }

    companion object {
        const val IDLE = "IDLE"
        const val NO_GADGET = "no gadget"
        const val BUSY = "gadget busy"
        const val NO_ACK = "gadget no ack"

        /** The phone heard the ACK but never a RESULT (lost on the link); not the reader's radio NO_RESPONSE. */
        const val NO_RESULT = "gadget no result"
        const val NOT_READY = "gadget not ready"
        const val REBOOTED = "gadget rebooted"
        const val WRITE_FAILED = "gadget write failed"
        const val DOWNLOAD_MODE = "gadget in download mode"

        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** 8 random `[a-z0-9]`: a request id from this launch never equals one from an earlier launch. */
        fun newPrefix(random: SecureRandom = SecureRandom()): String = String(CharArray(8) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }
}
