package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.Detection
import com.chloeyeo.friendorfoe.detect.Detector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.signal.FakeSignalSource
import com.chloeyeo.friendorfoe.track.GrayFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The 10 fps motion path through the coordinator: [IffCoordinator.motionTick] and the fast-forward in [IffCoordinator.tick]. */
class IffCoordinatorMotionTest {
    private class ScriptedDetector(private val frames: ArrayDeque<List<Detection>>) : Detector {
        override val name: String = "scripted"
        override fun detect(frame: Frame): List<Detection> = frames.removeFirstOrNull() ?: emptyList()
    }

    // Gray 80x45 at step 4 = a 320x180 analysis frame.
    private val gw = 80
    private val gh = 45
    private val texture = Random(3).let { r -> Array(SIDE) { IntArray(SIDE) { 30 + r.nextInt(210) } } }

    private fun gray(ts: Long, x: Int, y: Int = 15): GrayFrame {
        val luma = ByteArray(gw * gh)
        for (yy in 0 until gh) for (xx in 0 until gw) {
            val v = if (xx in x until x + SIDE && yy in y until y + SIDE) texture[yy - y][xx - x] else 100 + xx / 10
            luma[yy * gw + xx] = v.toByte()
        }
        return GrayFrame(gw, gh, luma, 4, gw * 4, gh * 4, ts)
    }

    private fun frame(ts: Long) = Frame(320, 180, ByteArray(320 * 180 * 4), timestampNanos = ts)

    private fun box(x: Int, y: Int = 15) = BoxF(x * 4f, y * 4f, (x + SIDE) * 4f, (y + SIDE) * 4f)

    private fun drone(b: BoxF) = Detection(ObjClass.DRONE, 0.7f, b)

    private fun ms(n: Long) = n * 1_000_000L

    @Test
    fun `motionTick moves the boxes without changing ids or IFF states`() {
        var clock = 10_000L
        val det = ScriptedDetector(ArrayDeque(listOf(listOf(drone(box(10))))))
        val c = IffCoordinator(det, FakeSignalSource(), clock = { clock })
        c.motionTick(gray(ms(0), 10))
        c.tick(frame(ms(0)))
        val id = c.state.value.tracks.single().id
        assertEquals(box(10), c.state.value.tracks.single().box)

        c.challenge(id)
        val challenged = c.state.value.tracks.single().iff
        assertTrue(challenged is IffState.Challenged)

        var x = 10
        for (i in 1..8) {
            x += 3
            clock += 100
            c.motionTick(gray(ms(100L * i), x))
            val t = c.state.value.tracks.single()
            assertEquals(id, t.id)
            assertEquals(challenged, t.iff)
            assertEquals(box(x).left, t.box.left, 4.01f)
            assertEquals(box(x).top, t.box.top, 4.01f)
        }
        // The lock rect was frozen at the tap; the drawn box follows the object.
        assertEquals(box(10), c.lock!!.rect)
    }

    @Test
    fun `a late detection is fast-forwarded to where the object is now and keeps its id`() {
        var clock = 10_000L
        val det = ScriptedDetector(ArrayDeque(listOf(listOf(drone(box(10))), listOf(drone(box(22))))))
        val c = IffCoordinator(det, FakeSignalSource(), clock = { clock })
        c.motionTick(gray(ms(0), 10))
        c.tick(frame(ms(0)))
        val id = c.state.value.tracks.single().id

        // The object moves 2 gray px per 100 ms; frames up to 1.0 s are buffered (x = 30 at the end).
        for (i in 1..10) c.motionTick(gray(ms(100L * i), 10 + 2 * i))
        // The second detection was made on the 0.6 s frame (x = 22) and only now returns.
        clock += 3_000
        c.tick(frame(ms(600)))
        val t = c.state.value.tracks.single()
        assertEquals(id, t.id)
        assertEquals(box(30).left, t.box.left, 4.01f)
        assertEquals(box(30).right, t.box.right, 4.01f)
    }

    @Test
    fun `motionTick with no tracks only buffers and publishes nothing`() {
        val c = IffCoordinator(ScriptedDetector(ArrayDeque()), FakeSignalSource())
        val before = c.state.value
        c.motionTick(gray(ms(0), 10))
        assertEquals(before, c.state.value)
    }

    private companion object {
        const val SIDE = 12
    }
}
