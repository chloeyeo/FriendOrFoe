package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropsTest {
    @Test
    fun `a square crop twice the box's longer side centred on the box`() {
        // 100x60 box centred at (500, 300) in a 1280x720 frame -> side 200
        assertEquals(CropRect(400, 200, 200), Crops.square(BoxF(450f, 270f, 550f, 330f), 1280, 720))
    }

    @Test
    fun `a small box gets at least 96 pixels`() {
        assertEquals(CropRect(452, 252, 96), Crops.square(BoxF(490f, 290f, 510f, 310f), 1280, 720))
    }

    @Test
    fun `a crop at an edge is shifted inside, not shrunk`() {
        // box at the top-left corner: the 96 crop starts at 0,0
        assertEquals(CropRect(0, 0, 96), Crops.square(BoxF(0f, 0f, 20f, 20f), 1280, 720))
        // box at the bottom-right corner: the crop ends at the frame edge
        assertEquals(CropRect(1280 - 120, 720 - 120, 120), Crops.square(BoxF(1240f, 680f, 1300f, 740f), 1280, 720))
    }

    @Test
    fun `a box larger than half the frame is clamped to the shorter side`() {
        assertEquals(CropRect(280, 0, 720), Crops.square(BoxF(340f, 60f, 940f, 660f), 1280, 720))
        assertEquals(CropRect(0, 0, 50), Crops.square(BoxF(10f, 10f, 40f, 40f), 50, 80))
    }

    @Test
    fun `no crop for an empty frame or a broken box`() {
        assertNull(Crops.square(BoxF(0f, 0f, 10f, 10f), 0, 720))
        assertNull(Crops.square(BoxF(Float.NaN, 0f, 10f, 10f), 1280, 720))
    }

    @Test
    fun `cut copies the crop's rows tightly and keeps the timestamp`() {
        val w = 5
        val h = 4
        val rgba = ByteArray(w * h * 4) { i -> (i / 4).toByte() } // every pixel's four bytes hold its index
        val frame = Frame(w, h, rgba, rotationDegrees = 0, timestampNanos = 77L)
        val crop = Crops.cut(frame, CropRect(2, 1, 2))
        assertEquals(2, crop.width)
        assertEquals(2, crop.height)
        assertEquals(77L, crop.timestampNanos)
        val pixels = (0 until 4).map { crop.rgba[it * 4].toInt() }
        assertEquals(listOf(7, 8, 12, 13), pixels) // (2,1) (3,1) (2,2) (3,2)
        assertArrayEquals(ByteArray(4) { 7 }, crop.rgba.copyOfRange(0, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cut refuses a rect outside the frame`() {
        Crops.cut(Frame(4, 4, ByteArray(64)), CropRect(2, 2, 3))
    }

    @Test
    fun `boxes map back to frame pixels and are clipped to the crop`() {
        val rect = CropRect(400, 200, 200)
        assertEquals(BoxF(450f, 270f, 550f, 330f), Crops.toFrame(BoxF(50f, 70f, 150f, 130f), rect))
        assertEquals(BoxF(400f, 200f, 600f, 400f), Crops.toFrame(BoxF(-5f, -1f, 230f, 201f), rect))
    }

    @Test
    fun `crop then back-map round-trips the tracked box at an edge`() {
        val box = BoxF(1250f, 10f, 1278f, 40f)
        val rect = Crops.square(box, 1280, 720)!!
        assertEquals(CropRect(1184, 0, 96), rect)
        val inCrop = BoxF(box.left - rect.left, box.top - rect.top, box.right - rect.left, box.bottom - rect.top)
        assertEquals(box, Crops.toFrame(inCrop, rect))
    }
}
