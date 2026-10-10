package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class YoloDecodeTest {
    @Test
    fun letterboxMapsAWideFrameIntoTheMiddleRows() {
        val w = 8
        val h = 4
        val rgba = ByteArray(w * h * 4) { if (it % 4 == 0) 255.toByte() else 0 }
        val dst = FloatArray(3 * 8 * 8)
        val lb = YoloDecode.letterbox(rgba, w, h, 8, 8, dst)
        assertEquals(Letterbox(1f, 0f, 2f), lb)
        assertEquals(114f / 255f, dst[0], 1e-6f) // top padding row
        assertEquals(1f, dst[2 * 8], 1e-6f) // first image row, red
        assertEquals(0f, dst[64 + 2 * 8], 1e-6f) // green plane
    }

    @Test
    fun decodeKeepsTheBestOfOverlappingRowsAndRemovesTheLetterbox() {
        val n = 3
        // rows: cx, cy, w, h, score (one class), column per candidate
        val o = floatArrayOf(
            100f, 102f, 300f,
            120f, 120f, 50f,
            40f, 40f, 20f,
            20f, 20f, 10f,
            0.9f, 0.6f, 0.1f,
        )
        val rows = YoloDecode.decode(o, n, Letterbox(0.5f, 0f, 80f), 0.15f, 1280, 720)
        assertEquals(1, rows.size)
        val b = rows[0].box
        assertEquals(160f, b.left, 1e-3f)
        assertEquals(60f, b.top, 1e-3f)
        assertEquals(240f, b.right, 1e-3f)
        assertEquals(100f, b.bottom, 1e-3f)
        assertEquals(0.9f, rows[0].score, 1e-6f)
    }

    @Test
    fun decodeDropsWholeFrameBoxesAndFoldsClassesIntoDrone() {
        val n = 2
        // two classes: column 0 is a whole-frame box, column 1 a small fixed-wing box
        val o = floatArrayOf(
            640f, 100f,
            360f, 100f,
            1280f, 20f,
            720f, 20f,
            0.9f, 0.1f,
            0.1f, 0.7f,
        )
        val rows = YoloDecode.decode(o, n, Letterbox(1f, 0f, 0f), 0.15f, 1280, 720, maxArea = 0.6f)
        assertEquals(1, rows.size)
        assertEquals(0L, rows[0].labelId)
        assertEquals(0.7f, rows[0].score, 1e-6f)
    }

    @Test
    fun letterboxCentresARectangularInput() {
        val dst = FloatArray(3 * 4 * 8)
        val lb = YoloDecode.letterbox(ByteArray(16 * 4 * 4), 16, 4, 8, 4, dst)
        assertEquals(Letterbox(0.5f, 0f, 1f), lb)
    }
}
