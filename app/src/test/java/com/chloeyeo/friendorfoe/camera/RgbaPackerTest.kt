package com.chloeyeo.friendorfoe.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class RgbaPackerTest {
    private val w = 3
    private val h = 2

    /** Pixel (x, y) is the four bytes (y*3+x)*4 + 0..3. */
    private val tight = ByteArray(w * h * 4) { it.toByte() }

    private fun pixel(bytes: ByteArray, width: Int, x: Int, y: Int): List<Byte> =
        (0 until 4).map { bytes[(y * width + x) * 4 + it] }

    private fun padded(rowStride: Int): ByteBuffer {
        val out = ByteArray(rowStride * (h - 1) + w * 4) { 0x7F }
        for (y in 0 until h) System.arraycopy(tight, y * w * 4, out, y * rowStride, w * 4)
        return ByteBuffer.wrap(out)
    }

    @Test
    fun `tight plane packs byte-identical`() {
        val f = RgbaPacker.pack(ByteBuffer.wrap(tight), w, h, rowStride = 12, pixelStride = 4, rotationDegrees = 0, timestampNanos = 42L)
        assertEquals(3, f.width)
        assertEquals(2, f.height)
        assertEquals(0, f.rotationDegrees)
        assertEquals(42L, f.timestampNanos)
        assertArrayEquals(tight, f.rgba)
    }

    @Test
    fun `padded plane - 8 junk bytes per row are stripped`() {
        val f = RgbaPacker.pack(padded(20), w, h, rowStride = 20, pixelStride = 4, rotationDegrees = 0)
        assertEquals(24, f.rgba.size)
        assertArrayEquals(tight, f.rgba)
    }

    @Test
    fun `rotation 180 reverses the pixel sequence and keeps each pixel's bytes`() {
        val f = RgbaPacker.pack(ByteBuffer.wrap(tight), w, h, 12, 4, 180)
        assertEquals(3, f.width)
        assertEquals(2, f.height)
        assertEquals(0, f.rotationDegrees)
        val expected = (5 downTo 0).flatMap { p -> (0 until 4).map { (p * 4 + it).toByte() } }.toByteArray()
        assertArrayEquals(expected, f.rgba)
    }

    @Test
    fun `rotation 180 on a padded plane equals rotation 180 on the tight plane`() {
        val a = RgbaPacker.pack(padded(20), w, h, 20, 4, 180)
        val b = RgbaPacker.pack(ByteBuffer.wrap(tight), w, h, 12, 4, 180)
        assertArrayEquals(b.rgba, a.rgba)
    }

    @Test
    fun `rotation 90 clockwise - out(x, y) == src(y, height-1-x) in a 2x3 frame`() {
        val f = RgbaPacker.pack(ByteBuffer.wrap(tight), w, h, 12, 4, 90)
        assertEquals(2, f.width)
        assertEquals(3, f.height)
        assertEquals(0, f.rotationDegrees)
        for (y in 0 until 3) for (x in 0 until 2) {
            assertEquals("out($x,$y)", pixel(tight, w, y, h - 1 - x), pixel(f.rgba, 2, x, y))
        }
    }

    @Test
    fun `rotation 270 clockwise - out(x, y) == src(width-1-y, x) in a 2x3 frame`() {
        val f = RgbaPacker.pack(ByteBuffer.wrap(tight), w, h, 12, 4, 270)
        assertEquals(2, f.width)
        assertEquals(3, f.height)
        for (y in 0 until 3) for (x in 0 until 2) {
            assertEquals("out($x,$y)", pixel(tight, w, w - 1 - y, x), pixel(f.rgba, 2, x, y))
        }
    }

    @Test
    fun `pixelStride 1 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RgbaPacker.pack(ByteBuffer.wrap(tight), w, h, 12, 1, 0) }
    }

    @Test
    fun `rowStride below width times 4 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RgbaPacker.pack(ByteBuffer.wrap(tight), w, h, 8, 4, 0) }
    }

    @Test
    fun `rotation 45 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RgbaPacker.pack(ByteBuffer.wrap(tight), w, h, 12, 4, 45) }
    }

    @Test
    fun `a plane shorter than its geometry is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RgbaPacker.pack(ByteBuffer.wrap(ByteArray(20)), w, h, 12, 4, 0) }
    }

    @Test
    fun `source buffer position is unchanged`() {
        val tightBuf = ByteBuffer.wrap(tight)
        RgbaPacker.pack(tightBuf, w, h, 12, 4, 0)
        assertEquals(0, tightBuf.position())
        val pad = padded(20)
        pad.position(5)
        RgbaPacker.pack(pad, w, h, 20, 4, 90)
        assertEquals(5, pad.position())
    }
}
