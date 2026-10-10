package com.chloeyeo.friendorfoe.signal

import java.io.ByteArrayOutputStream

/**
 * Turns the gadget's byte stream into lines (docs/GADGET_LINK.md framing): UTF-8, terminated by `\n`, a `\r` right
 * before the `\n` is dropped, at most [maxBytes] bytes per line (without the terminator; the reader's STATUS line is about 500 bytes, up to 533). Bytes arrive in arbitrary
 * USB chunks, so a line may span several [feed] calls. A line longer than [maxBytes] is discarded up to its `\n`
 * and reported once as [Piece.Oversize], so a runaway console never grows the buffer. Pure, not thread-safe (one
 * reader thread owns it).
 */
class LineSplitter(private val maxBytes: Int = MAX_LINE_BYTES) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    sealed interface Piece {
        /** One complete line, terminator stripped. */
        data class Line(val text: String) : Piece

        /** A line that exceeded the limit; [bytes] is how many bytes were dropped (terminator excluded). */
        data class Oversize(val bytes: Int) : Piece
    }

    private val buf = ByteArrayOutputStream(maxBytes + 1)
    private var dropping = false
    private var dropped = 0

    /** Feeds [length] bytes of [data] from [offset]; returns the pieces completed by them, in order. */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Piece> {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "bad range" }
        var out: ArrayList<Piece>? = null
        for (i in offset until offset + length) {
            val b = data[i]
            if (b == NL) {
                val piece: Piece = if (dropping) {
                    Piece.Oversize(dropped)
                } else {
                    val bytes = buf.toByteArray()
                    val end = if (bytes.isNotEmpty() && bytes.last() == CR) bytes.size - 1 else bytes.size
                    Piece.Line(String(bytes, 0, end, Charsets.UTF_8))
                }
                (out ?: ArrayList<Piece>(2).also { out = it }).add(piece)
                buf.reset()
                dropping = false
                dropped = 0
                continue
            }
            if (dropping) {
                dropped++
                continue
            }
            buf.write(b.toInt())
            // One byte of slack for a trailing '\r' that belongs to the terminator.
            if (buf.size() > maxBytes + 1 || (buf.size() == maxBytes + 1 && b != CR)) {
                dropping = true
                dropped = buf.size()
                buf.reset()
            }
        }
        return out ?: emptyList()
    }

    /** Forgets a partial line (after a reconnect the first bytes belong to a new stream). */
    fun reset() {
        buf.reset()
        dropping = false
        dropped = 0
    }

    companion object {
        const val MAX_LINE_BYTES = 1024
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}
