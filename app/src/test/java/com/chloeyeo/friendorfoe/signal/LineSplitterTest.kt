package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.signal.LineSplitter.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSplitterTest {
    private fun LineSplitter.feed(text: String): List<Piece> = feed(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `whole lines come out without their terminator`() {
        val s = LineSplitter()
        assertEquals(listOf(Piece.Line("""{"type":"status"}"""), Piece.Line("b")), s.feed("{\"type\":\"status\"}\nb\n"))
    }

    @Test
    fun `a line split across chunks is joined`() {
        val s = LineSplitter()
        assertTrue(s.feed("{\"type\":").isEmpty())
        assertTrue(s.feed("\"ans").isEmpty())
        assertEquals(listOf(Piece.Line("{\"type\":\"answer\"}")), s.feed("wer\"}\n"))
    }

    @Test
    fun `CRLF is tolerated, also when the CR and the LF arrive apart`() {
        val s = LineSplitter()
        assertEquals(listOf(Piece.Line("one")), s.feed("one\r\n"))
        assertTrue(s.feed("two\r").isEmpty())
        assertEquals(listOf(Piece.Line("two")), s.feed("\n"))
    }

    @Test
    fun `empty lines are passed through as empty text`() {
        assertEquals(listOf(Piece.Line(""), Piece.Line("")), LineSplitter().feed("\n\r\n"))
    }

    @Test
    fun `a line of exactly the limit is kept, with or without CR`() {
        val s = LineSplitter(maxBytes = 8)
        assertEquals(listOf(Piece.Line("12345678")), s.feed("12345678\n"))
        assertEquals(listOf(Piece.Line("12345678")), s.feed("12345678\r\n"))
    }

    @Test
    fun `an oversize line is dropped up to its newline and reported once, then framing recovers`() {
        val s = LineSplitter(maxBytes = 8)
        val pieces = s.feed("123456789abc") + s.feed("def\nok\n")
        assertEquals(listOf(Piece.Oversize(15), Piece.Line("ok")), pieces)
    }

    @Test
    fun `the default limit is 1024 bytes, room for the reader STATUS line`() {
        val s = LineSplitter()
        assertEquals(listOf(Piece.Line("x".repeat(1024))), s.feed("x".repeat(1024) + "\n"))
        assertTrue(s.feed("y".repeat(1025) + "\n").single() is Piece.Oversize)
    }

    @Test
    fun `utf-8 split inside a character is decoded once the line is complete`() {
        val bytes = "a·b\n".toByteArray(Charsets.UTF_8)
        val s = LineSplitter()
        assertTrue(s.feed(bytes, 0, 2).isEmpty()) // 'a' and the first byte of '·'
        assertEquals(listOf(Piece.Line("a·b")), s.feed(bytes, 2, bytes.size - 2))
    }

    @Test
    fun `reset forgets a partial line`() {
        val s = LineSplitter()
        s.feed("garbage")
        s.reset()
        assertEquals(listOf(Piece.Line("fresh")), s.feed("fresh\n"))
    }
}
