package com.chloeyeo.friendorfoe.track

import com.chloeyeo.friendorfoe.detect.BoxF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MotionTrackerTest {
    private val step = 4
    private val gw = 320
    private val gh = 180

    /** A fixed texture for the synthetic object (a drone-like blob with structure, not a flat square). */
    private val texture: Array<IntArray> = Random(7).let { r -> Array(SIDE) { IntArray(SIDE) { 40 + r.nextInt(200) } } }

    /**
     * A 320x180 gray frame (step 4, so frame pixels = 4 x gray pixels) with a smooth background gradient and the
     * textured object's top-left at gray (x, y); null x draws no object.
     */
    private fun frame(ts: Long, x: Int?, y: Int = 60, noise: Int = 0, seed: Int = 0): GrayFrame {
        val r = Random(seed)
        val luma = ByteArray(gw * gh)
        for (yy in 0 until gh) for (xx in 0 until gw) {
            var v = 90 + (xx + yy) / 8
            if (x != null && xx in x until x + SIDE && yy in y until y + SIDE) v = texture[yy - y][xx - x]
            if (noise > 0) v += r.nextInt(-noise, noise + 1)
            luma[yy * gw + xx] = v.coerceIn(0, 255).toByte()
        }
        return GrayFrame(gw, gh, luma, step, gw * step, gh * step, ts)
    }

    /** The object's box in frame pixels for a gray top-left (x, y). */
    private fun box(x: Int, y: Int = 60) = BoxF(x * 4f, y * 4f, (x + SIDE) * 4f, (y + SIDE) * 4f)

    private fun ms(n: Long) = n * 1_000_000L

    private fun assertBox(expected: BoxF, actual: BoxF, tol: Float = 4.01f) {
        assertEquals("left", expected.left, actual.left, tol)
        assertEquals("top", expected.top, actual.top, tol)
        assertEquals("right", expected.right, actual.right, tol)
        assertEquals("bottom", expected.bottom, actual.bottom, tol)
    }

    @Test
    fun `follows a moving object at 5 to 20 frame pixels per frame`() {
        val m = MotionTracker()
        var x = 40
        m.push(frame(ms(0), x))
        m.assign(1, m.fastForward(ms(0), listOf(box(x))).single())
        // Gray steps of 2..5 px = 8..20 frame px per frame, plus a 5 frame px (1.25 gray) creep.
        val moves = listOf(2, 3, 5, 5, 4, 2, 1, 5, 5, 5, 3)
        for ((i, d) in moves.withIndex()) {
            x += d
            m.push(frame(ms(100L * (i + 1)), x, noise = 3, seed = i))
            val got = m.box(1)!!
            assertFalse("lost at frame $i", got.lost)
            assertBox(box(x), got.box)
        }
    }

    @Test
    fun `follows a fast object with a steady velocity of 20 gray pixels per frame`() {
        val m = MotionTracker()
        var x = 10
        m.push(frame(ms(0), x))
        m.assign(1, m.fastForward(ms(0), listOf(box(x))).single())
        // Accelerate inside the search radius (12 gray px), then hold 20 gray px (80 frame px) per frame.
        for ((i, d) in listOf(8, 14, 20, 20, 20, 20, 20, 20, 20, 20, 20).withIndex()) {
            x += d
            m.push(frame(ms(100L * (i + 1)), x, seed = i))
            assertBox(box(x), m.box(1)!!.box)
        }
    }

    @Test
    fun `a still object stays exactly still`() {
        val m = MotionTracker()
        m.push(frame(ms(0), 100))
        m.assign(1, m.fastForward(ms(0), listOf(box(100))).single())
        for (i in 1..20) m.push(frame(ms(100L * i), 100))
        val got = m.box(1)!!
        assertEquals(box(100), got.box)
        assertFalse(got.lost)
        assertEquals(0f, got.mad, 0.001f)
    }

    @Test
    fun `stops on a poor match and keeps the last position, then resumes when the object is back`() {
        val m = MotionTracker()
        m.push(frame(ms(0), 100))
        m.assign(1, m.fastForward(ms(0), listOf(box(100))).single())
        m.push(frame(ms(100), 104))
        assertBox(box(104), m.box(1)!!.box)

        m.push(frame(ms(200), null)) // the object is gone
        val gone = m.box(1)!!
        assertTrue("match should be poor, mad=${gone.mad}", gone.lost)
        assertEquals(box(104), gone.box)
        m.push(frame(ms(300), null))
        assertEquals(box(104), m.box(1)!!.box)

        m.push(frame(ms(400), 107))
        val back = m.box(1)!!
        assertFalse(back.lost)
        assertBox(box(107), back.box)
    }

    @Test
    fun `fast-forward from t0 lands on the current position`() {
        val m = MotionTracker()
        // 3 s of frames at 10 fps while the object crosses the frame (4 gray px = 16 frame px per frame).
        for (i in 0..30) m.push(frame(ms(100L * i), 20 + 4 * i, seed = i, noise = 2))
        // The detection was made on the frame at t0 = 0.5 s (object at gray x 40) and arrives at 3 s.
        val seed = m.fastForward(ms(500), listOf(box(40))).single()
        assertFalse(seed.lost)
        assertBox(box(20 + 4 * 30), seed.box)
    }

    @Test
    fun `fast-forward on a snapshot then catchUp covers frames pushed meanwhile`() {
        val m = MotionTracker()
        for (i in 0..10) m.push(frame(ms(100L * i), 20 + 3 * i))
        val snapshot = m.snapshot()
        // Two more frames arrive while the (unlocked) fast-forward runs on the snapshot.
        m.push(frame(ms(1100), 53))
        m.push(frame(ms(1200), 56))
        val seeds = m.fastForward(ms(0), listOf(box(20)), snapshot)
        assertBox(box(50), seeds.single().box)
        m.catchUp(seeds)
        assertBox(box(56), seeds.single().box)
    }

    @Test
    fun `fast-forward picks the buffered frame nearest t0`() {
        val m = MotionTracker()
        for (i in 0..10) m.push(frame(ms(100L * i), 20 + 3 * i))
        // t0 = 0.52 s is nearest to frame 5 (x = 35); the box drawn there must be carried to x = 50.
        val seed = m.fastForward(ms(520), listOf(box(35))).single()
        assertBox(box(50), seed.box)
    }

    @Test
    fun `without buffered frames a seed keeps its box`() {
        val m = MotionTracker()
        val b = BoxF(10f, 20f, 30f, 40f)
        val seed = m.fastForward(123L, listOf(b)).single()
        assertEquals(b, seed.box)
        m.assign(3, seed)
        assertEquals(b, m.box(3)!!.box)
    }

    @Test
    fun `the buffer keeps about five seconds and retain forgets ids`() {
        val m = MotionTracker()
        for (i in 0..80) m.push(frame(ms(100L * i), null))
        assertEquals(51, m.bufferedFrames) // 3.0 s .. 8.0 s inclusive
        m.assign(1, m.fastForward(ms(8000), listOf(box(10))).single())
        m.assign(2, m.fastForward(ms(8000), listOf(box(100))).single())
        m.retain(setOf(2))
        assertEquals(setOf(2), m.ids)
        assertNotNull(m.box(2))
    }

    private companion object {
        const val SIDE = 24
    }
}
