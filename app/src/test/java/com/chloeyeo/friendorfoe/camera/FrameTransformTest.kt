package com.chloeyeo.friendorfoe.camera

import com.chloeyeo.friendorfoe.detect.BoxF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameTransformTest {
    private val eps = 1e-3f

    @Test
    fun `identity when frame and view match`() {
        val t = FrameTransform(1280, 720, 1280, 720)
        assertEquals(1f, t.scale, eps)
        assertEquals(0f, t.offsetX, eps)
        assertEquals(0f, t.offsetY, eps)
        assertEquals(BoxF(10f, 20f, 30f, 40f), t.toPreview(BoxF(10f, 20f, 30f, 40f)))
    }

    @Test
    fun `wider view - frame fills the width and is cropped top and bottom`() {
        // 1280x720 frame into a 2400x1080 phone screen (FILL_CENTER)
        val t = FrameTransform(1280, 720, 2400, 1080)
        assertEquals(1.875f, t.scale, eps)
        assertEquals(0f, t.offsetX, eps)
        assertEquals(-135f, t.offsetY, eps) // (1080 - 720 * 1.875) / 2

        val full = t.toPreview(BoxF(0f, 0f, 1280f, 720f))
        assertEquals(0f, full.left, eps)
        assertEquals(2400f, full.right, eps)
        assertEquals(-135f, full.top, eps)
        assertEquals(1215f, full.bottom, eps)

        // the frame center lands on the view center
        val center = t.toPreview(BoxF(640f, 360f, 640f, 360f))
        assertEquals(1200f, center.left, eps)
        assertEquals(540f, center.top, eps)
    }

    @Test
    fun `taller view - frame fills the height and is cropped left and right`() {
        val t = FrameTransform(1280, 720, 1600, 1200)
        assertEquals(1200f / 720f, t.scale, eps)
        assertEquals((1600f - 1280f * (1200f / 720f)) / 2f, t.offsetX, eps)
        assertEquals(0f, t.offsetY, eps)
        val center = t.toPreview(BoxF(640f, 360f, 640f, 360f))
        assertEquals(800f, center.left, eps)
        assertEquals(600f, center.top, eps)
    }

    @Test
    fun `A06 screen - 1280x720 into 1600x720`() {
        val t = FrameTransform(1280, 720, 1600, 720)
        assertEquals(1.25f, t.scale, eps)
        assertEquals(0f, t.offsetX, eps)
        assertEquals(-90f, t.offsetY, eps) // (720 - 720 * 1.25) / 2

        // a box at the left frame edge, full height (D-10 edge check)
        val edge = t.toPreview(BoxF(0f, 0f, 100f, 720f))
        assertEquals(0f, edge.left, eps)
        assertEquals(125f, edge.right, eps)
        assertEquals(-90f, edge.top, eps)
        assertEquals(810f, edge.bottom, eps)
    }

    @Test
    fun `toFrame inverts toPreview`() {
        val t = FrameTransform(1280, 720, 2400, 1080)
        val box = t.toPreview(BoxF(300f, 200f, 400f, 260f))
        val (x, y) = t.toFrame(box.left, box.top)
        assertEquals(300f, x, eps)
        assertEquals(200f, y, eps)
    }

    @Test
    fun `scaled box keeps its aspect ratio`() {
        val t = FrameTransform(1280, 720, 2400, 1080)
        val box = t.toPreview(BoxF(100f, 100f, 300f, 200f))
        assertEquals(2f, box.width / box.height, eps)
    }

    @Test
    fun `rotation other than 0 is not implemented yet`() {
        assertThrows(IllegalArgumentException::class.java) { FrameTransform(1280, 720, 2400, 1080, rotationDegrees = 90) }
    }
}
