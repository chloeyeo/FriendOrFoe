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
        val sizes = ArrayList<String>()

        override fun open(): OpenTiming {
            failOpen?.let { throw IllegalStateException(it) }
            isOpen = true
            return OpenTiming(100, 10)
        }

        override fun detectRaw(frame: Frame): List<RawDetection> {
            frames++
            sizes += "${frame.width}x${frame.height}"
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

    private val big = Frame(1280, 720, ByteArray(1280 * 720 * 4))

    @Test
    fun `a crop round runs the crop model on one square crop per drone and maps the best box back`() {
        val drone = FakeModel(ClassMap.DRONE_RAHUL, listOf(RawDetection(0L, 0.9f, droneBox, "drone")))
        val person = FakeModel(ClassMap.DFINE_PERSON, listOf(RawDetection(0L, 0.9f, personBox, "person")))
        // In crop pixels: a 0.27 box (kept by the crop floor 0.25, the best) and a 0.26 one; a 0.2 row is dropped.
        val crop = FakeModel(
            ClassMap.DRONE_RAHUL_CROP,
            listOf(
                RawDetection(0L, 0.27f, BoxF(50f, 70f, 150f, 130f), "drone"),
                RawDetection(0L, 0.26f, BoxF(0f, 0f, 20f, 20f), "drone"),
                RawDetection(0L, 0.2f, BoxF(10f, 10f, 30f, 30f), "drone"),
            ),
        )
        val dual = DualDetector(drone, person, cropDrone = crop)
        dual.open()
        assertTrue(dual.cropModelOpen)
        val tracked = BoxF(450f, 270f, 550f, 330f) // crop (400, 200) side 200
        val out = dual.detectRound(big, RoundPlan(full = false, crops = listOf(tracked), person = false))
        assertEquals(listOf(Detection(ObjClass.DRONE, 0.27f, BoxF(450f, 270f, 550f, 330f))), out)
        assertEquals(listOf("200x200"), crop.sizes)
        assertEquals(0, drone.frames) // the full-frame drone model and the person model were skipped
        assertEquals(0, person.frames)
        assertEquals(0L, dual.lastPersonMs)
        assertEquals("crop", dual.lastPlan?.kind)
    }

    @Test
    fun `a crop round with two drones and the person model merges everything`() {
        val person = FakeModel(ClassMap.DFINE_PERSON, listOf(RawDetection(0L, 0.8f, BoxF(900f, 100f, 1000f, 400f), "person")))
        val crop = FakeModel(ClassMap.DRONE_RAHUL_CROP, listOf(RawDetection(0L, 0.5f, BoxF(40f, 40f, 56f, 56f), "drone")))
        val dual = DualDetector(FakeModel(ClassMap.DRONE_RAHUL, emptyList()), person, cropDrone = crop)
        dual.open()
        val plan = RoundPlan(full = false, crops = listOf(BoxF(100f, 100f, 120f, 120f), BoxF(600f, 300f, 616f, 316f)), person = true)
        val out = dual.detectRound(big, plan)
        assertEquals(listOf("96x96", "96x96"), crop.sizes)
        assertEquals(
            listOf(
                Detection(ObjClass.PERSON, 0.8f, BoxF(900f, 100f, 1000f, 400f)),
                Detection(ObjClass.DRONE, 0.5f, BoxF(62f + 40f, 62f + 40f, 62f + 56f, 62f + 56f)),
                Detection(ObjClass.DRONE, 0.5f, BoxF(560f + 40f, 260f + 40f, 560f + 56f, 260f + 56f)),
            ),
            out,
        )
    }

    @Test
    fun `without an opened crop model the crops run on the full-frame drone model`() {
        val drone = FakeModel(ClassMap.DRONE_RAHUL, listOf(RawDetection(0L, 0.6f, BoxF(10f, 10f, 30f, 30f), "drone")))
        val crop = FakeModel(ClassMap.DRONE_RAHUL_CROP, emptyList(), failOpen = "model dir missing: config.json")
        val dual = DualDetector(drone, FakeModel(ClassMap.DFINE_PERSON, emptyList()), cropDrone = crop)
        val timing = dual.open()
        assertEquals(null, timing.crop)
        assertFalse(dual.cropModelOpen)
        assertEquals("crop model: model dir missing: config.json", dual.cropOpenFailure)
        dual.detectRound(big, RoundPlan(full = false, crops = listOf(BoxF(100f, 100f, 120f, 120f)), person = false))
        assertEquals(listOf("96x96"), drone.sizes)
        assertEquals(0, crop.frames)
    }

    @Test
    fun `a full round without the person model skips it`() {
        val person = FakeModel(ClassMap.DFINE_PERSON, listOf(RawDetection(0L, 0.9f, personBox, "person")))
        val dual = DualDetector(FakeModel(ClassMap.DRONE_RAHUL, listOf(RawDetection(0L, 0.6f, droneBox, "drone"))), person)
        val out = dual.detectRound(frame, RoundPlan(full = true, person = false))
        assertEquals(listOf(Detection(ObjClass.DRONE, 0.6f, droneBox)), out)
        assertEquals(0, person.frames)
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
