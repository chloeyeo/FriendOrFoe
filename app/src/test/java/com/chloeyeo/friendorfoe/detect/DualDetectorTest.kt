package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DualDetectorTest {
    private class FakeModel(
        override val classMap: ClassMap,
        private val rows: List<RawDetection>,
        private val failOpen: String? = null,
    ) : ModelDetector {
        override val name: String = "fake:${classMap.tag}"
        override var rawListener: ((List<RawDetection>) -> Unit)? = null
        override var isOpen: Boolean = false
        var frames = 0
        var closed = false

        override fun open(): OpenTiming {
            failOpen?.let { throw IllegalStateException(it) }
            isOpen = true
            return OpenTiming(100, 10)
        }

        override fun detectRaw(frame: Frame): List<RawDetection> {
            frames++
            rawListener?.invoke(rows)
            return rows
        }

        override fun close() {
            closed = true
            isOpen = false
        }
    }

    private val frame = Frame(4, 4, ByteArray(4 * 4 * 4))
    private val droneBox = BoxF(100f, 100f, 200f, 200f)
    private val personBox = BoxF(400f, 100f, 500f, 400f)

    @Test
    fun `merges both models through their own class maps`() {
        val drone = FakeModel(
            ClassMap.DRONE_RAHUL,
            listOf(RawDetection(0L, 0.6f, droneBox, "drone"), RawDetection(0L, 0.2f, personBox, "drone")),
        )
        val person = FakeModel(
            ClassMap.DFINE_PERSON,
            listOf(RawDetection(0L, 0.9f, personBox, "person"), RawDetection(4L, 0.95f, droneBox, "aeroplane")),
        )
        val out = DualDetector(drone, person).detect(frame)
        // person 0.9 first (score-descending), then drone 0.6; the low drone row and the COCO aeroplane are dropped.
        assertEquals(listOf(Detection(ObjClass.PERSON, 0.9f, personBox), Detection(ObjClass.DRONE, 0.6f, droneBox)), out)
        assertEquals(1, drone.frames)
        assertEquals(1, person.frames)
    }

    @Test
    fun `dedup runs once over the merge and the drone wins an overlap`() {
        val drone = FakeModel(ClassMap.DRONE_RAHUL, listOf(RawDetection(0L, 0.5f, droneBox, "drone")))
        val person = FakeModel(ClassMap.DFINE_PERSON, listOf(RawDetection(0L, 0.8f, droneBox, "person")))
        val out = DualDetector(drone, person).detect(frame)
        assertEquals(listOf(Detection(ObjClass.DRONE, 0.5f, droneBox)), out)
    }

    @Test
    fun `records per model timings`() {
        val ticks = ArrayDeque(listOf(0L, 6_100_000_000L, 7_500_000_000L))
        val dual = DualDetector(
            FakeModel(ClassMap.DRONE_RAHUL, emptyList()),
            FakeModel(ClassMap.DFINE_PERSON, emptyList()),
            clockNanos = { ticks.removeFirst() },
        )
        dual.detect(frame)
        assertEquals(6100L, dual.lastDroneMs)
        assertEquals(1400L, dual.lastPersonMs)
        assertEquals("drone 6.1 s + person 1.4 s", dual.timingNote())
    }

    @Test
    fun `isOpen needs both models and close closes both`() {
        val drone = FakeModel(ClassMap.DRONE_RAHUL, emptyList())
        val person = FakeModel(ClassMap.DFINE_PERSON, emptyList())
        val dual = DualDetector(drone, person)
        assertFalse(dual.isOpen)
        val timing = dual.open()
        assertTrue(dual.isOpen)
        assertEquals(DualTiming(OpenTiming(100, 10), OpenTiming(100, 10)), timing)
        dual.close()
        assertTrue(drone.closed && person.closed)
        assertFalse(dual.isOpen)
        assertEquals("clikart:drone-rahul+dfine-s", dual.name)
    }

    @Test
    fun `an open failure names the model`() {
        val dual = DualDetector(
            FakeModel(ClassMap.DRONE_RAHUL, emptyList()),
            FakeModel(ClassMap.DFINE_PERSON, emptyList(), failOpen = "model dir missing: config.json"),
        )
        try {
            dual.open()
            fail("expected the person model to refuse")
        } catch (e: IllegalStateException) {
            assertEquals("person model: model dir missing: config.json", e.message)
        }
    }

    @Test
    fun `the production pair is named after its dirs and marks the 320 input`() {
        val d320 = DualDetector.clikaRt("/x/files/models/rtdetr_v2_drone_rahul_320", "/x/files/models/dfine_small_coco_320")
        assertEquals("clikart:drone-rahul+dfine-s @320", d320.name)
        assertEquals("clikart:rtdetr_v2_drone_rahul_320", d320.drone.name)
        assertEquals("clikart:dfine_small_coco_320", d320.person.name)
        assertEquals("clikart:drone-rahul+dfine-s @224", DualDetector.clikaRt("/x/rtdetr_v2_drone_rahul_224", "/x/dfine_small_coco_256").name)
        val d640 = DualDetector.clikaRt("/x/files/models/rtdetr_v2_drone_rahul", "/x/files/models/dfine_small_coco")
        assertEquals("clikart:drone-rahul+dfine-s", d640.name)
        assertEquals("clikart:rtdetr_v2_drone_rahul", d640.drone.name)
    }
}
