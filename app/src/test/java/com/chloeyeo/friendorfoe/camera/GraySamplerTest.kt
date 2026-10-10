package com.chloeyeo.friendorfoe.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class GraySamplerTest {
    /** A w x h RGBA plane with rowStride [stride]; pixel (x, y) = (r, g, b) from [rgb]; padding bytes are 0x55. */
    private fun plane(w: Int, h: Int, stride: Int, rgb: (Int, Int) -> Triple<Int, Int, Int>): ByteBuffer {
        val bytes = ByteArray(stride * (h - 1) + w * 4) { 0x55 }
        for (y in 0 until h) for (x in 0 until w) {
            val (r, g, b) = rgb(x, y)
            val i = y * stride + x * 4
            bytes[i] = r.toByte(); bytes[i + 1] = g.toByte(); bytes[i + 2] = b.toByte(); bytes[i + 3] = 0xFF.toByte()
        }
        return ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); position(0) }
    }

    private val pattern: (Int, Int) -> Triple<Int, Int, Int> = { x, y -> Triple((x * 7 + y * 3) % 256, (x * 2 + y * 11) % 256, (x + y) % 256) }

    @Test
    fun `1280x720 samples to 320x180 at step 4 with the frame size kept`() {
        val g = GraySampler.sample(plane(1280, 720, 1280 * 4) { _, _ -> Triple(100, 100, 100) }, 1280, 720, 1280 * 4, 4, 0, 42L)
        assertEquals(320, g.width)
        assertEquals(180, g.height)
        assertEquals(4, g.step)
        assertEquals(1280, g.frameWidth)
        assertEquals(720, g.frameHeight)
        assertEquals(42L, g.timestampNanos)
        assertEquals(100, g.at(17, 33)) // (77 + 150 + 29) * 100 >> 8 = 100
    }

    @Test
    fun `a padded rowStride gives the same gray frame as a tight plane`() {
        val tight = GraySampler.sample(plane(64, 36, 64 * 4, pattern), 64, 36, 64 * 4, 4, 0, 0L, targetWidth = 16)
        val padded = GraySampler.sample(plane(64, 36, 64 * 4 + 48, pattern), 64, 36, 64 * 4 + 48, 4, 0, 0L, targetWidth = 16)
        assertArrayEquals(tight.luma, padded.luma)
        assertEquals(16, padded.width)
        assertEquals(9, padded.height)
    }

    @Test
    fun `each gray pixel is the mean luma of the 2x2 block at its cell start`() {
        val g = GraySampler.sample(plane(64, 36, 64 * 4 + 16, pattern), 64, 36, 64 * 4 + 16, 4, 0, 0L, targetWidth = 16)
        fun luma(x: Int, y: Int) = pattern(x, y).let { (r, gg, b) -> (77 * r + 150 * gg + 29 * b) shr 8 }
        val gx = 5
        val gy = 3
        val expected = (luma(20, 12) + luma(21, 12) + luma(20, 13) + luma(21, 13)) / 4
        assertEquals(expected, g.at(gx, gy))
    }

    @Test
    fun `rotation 180 mirrors the gray frame and 90 swaps its size`() {
        // A left-bright, right-dark plane: after 180 the bright side is on the right.
        val src = plane(64, 32, 64 * 4) { x, _ -> if (x < 32) Triple(200, 200, 200) else Triple(20, 20, 20) }
        val up = GraySampler.sample(src, 64, 32, 64 * 4, 4, 0, 0L, targetWidth = 16)
        val flipped = GraySampler.sample(src, 64, 32, 64 * 4, 4, 180, 0L, targetWidth = 16)
        assertEquals(200, up.at(0, 0))
        assertEquals(20, flipped.at(0, 0))
        assertEquals(200, flipped.at(15, 7))
        val side = GraySampler.sample(src, 64, 32, 64 * 4, 4, 90, 0L, targetWidth = 8)
        assertEquals(32, side.frameWidth)
        assertEquals(64, side.frameHeight)
        assertEquals(8, side.width)
        assertEquals(16, side.height)
    }

    @Test
    fun `the plane position is untouched`() {
        val src = plane(32, 18, 32 * 4, pattern)
        src.position(7)
        GraySampler.sample(src, 32, 18, 32 * 4, 4, 0, 0L, targetWidth = 8)
        assertEquals(7, src.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a too-short plane is refused`() {
        GraySampler.sample(ByteBuffer.allocate(100), 32, 18, 32 * 4, 4, 0, 0L)
    }
}
