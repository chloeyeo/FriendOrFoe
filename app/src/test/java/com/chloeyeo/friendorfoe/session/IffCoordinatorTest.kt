package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.Detection
import com.chloeyeo.friendorfoe.detect.Detector
import com.chloeyeo.friendorfoe.detect.FakeDetector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.signal.FakeSignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IffCoordinatorTest {
    /** Returns the next scripted list on every call, an empty list once the script is exhausted. */
    private class ScriptedDetector(private val frames: ArrayDeque<List<Detection>>) : Detector {
        override val name: String = "scripted"
        override fun detect(frame: Frame): List<Detection> = frames.removeFirstOrNull() ?: emptyList()
    }

    private fun drone(l: Float, t: Float, r: Float, b: Float) = Detection(ObjClass.DRONE, 0.6f, BoxF(l, t, r, b))

    private fun frame() = Frame(width = 128, height = 72, rgba = ByteArray(128 * 72 * 4))

    @Test
    fun `tick publishes the fake detector's boxes as tracks with stable ids`() {
        var clock = 1_000L
        val coordinator = IffCoordinator(FakeDetector(), FakeSignalSource(), clock = { clock })
        assertEquals("fake", coordinator.state.value.status.detectorName)

        clock += 180
        coordinator.tick(frame())
        val first = coordinator.state.value
        assertEquals(2, first.tracks.size)
        assertEquals(setOf(ObjClass.PERSON, ObjClass.DRONE), first.tracks.map { it.cls }.toSet())
        assertTrue(first.tracks.all { it.iff == IffState.Unchallenged })
        assertTrue(first.status.modelReady)

        clock += 1000
        coordinator.tick(frame())
        val second = coordinator.state.value
        assertEquals(first.tracks.map { it.id }, second.tracks.map { it.id })
    }

    @Test
    fun `tracks are mapped into the preview size`() {
        val coordinator = IffCoordinator(FakeDetector(), FakeSignalSource())
        coordinator.setPreviewSize(2560, 1440) // frame 128x72 scaled 20x
        coordinator.tick(frame())
        val person = coordinator.state.value.tracks.single { it.cls == ObjClass.PERSON }
        assertEquals(128f * 0.66f * 20f, person.box.left, 0.01f)
        assertEquals(72f * 0.98f * 20f, person.box.bottom, 0.01f)
    }

    @Test
    fun `setStatus publishes modelNote and modelReady without a tick`() {
        val coordinator = IffCoordinator(FakeDetector(), FakeSignalSource())
        coordinator.setStatus { it.copy(modelReady = false, modelNote = "model loading… 3 s") }
        val status = coordinator.state.value.status
        assertEquals("model loading… 3 s", status.modelNote)
        assertFalse(status.modelReady)
        assertEquals("fake", status.detectorName)
    }

    @Test
    fun `offlineProbe value lands in status offline on every tick`() {
        val online = IffCoordinator(FakeDetector(), FakeSignalSource(), offlineProbe = { false })
        online.tick(frame())
        assertFalse(online.state.value.status.offline)

        val byDefault = IffCoordinator(FakeDetector(), FakeSignalSource())
        byDefault.tick(frame())
        assertTrue(byDefault.state.value.status.offline)
    }

    @Test
    fun `challenge on a drone id locks it and publishes Challenged immediately`() {
        val clock = 1_000L
        val coordinator = IffCoordinator(FakeDetector(), FakeSignalSource(), clock = { clock })
        coordinator.tick(frame())
        val id = coordinator.state.value.tracks.single { it.cls == ObjClass.DRONE }.id

        coordinator.challenge(id)

        val lock = coordinator.lock
        assertEquals(id, lock?.boxId)
        assertEquals(11_000L, lock?.untilMillis)
        // no tick in between: the ring changes on the tap, not a second later
        val state = coordinator.state.value
        assertEquals(IffState.Challenged(until = 11_000L), state.tracks.single { it.id == id }.iff)
        assertEquals(IffState.Unchallenged, state.tracks.single { it.cls == ObjClass.PERSON }.iff)
        assertTrue(state.events.first().text.contains("challenge · box $id"))
    }

    @Test
    fun `lock survives a lost detection and is mapped into preview pixels`() {
        var clock = 1_000L
        val detector = ScriptedDetector(ArrayDeque(listOf(listOf(drone(10f, 10f, 50f, 40f)), emptyList())))
        val coordinator = IffCoordinator(detector, FakeSignalSource(), clock = { clock })
        coordinator.setPreviewSize(256, 144) // frame 128x72 -> scale 2
        coordinator.tick(frame())
        val live = coordinator.state.value.tracks.single()
        assertEquals(0.6f, live.score, 0f)
        coordinator.challenge(live.id)

        clock += 6_000 // past the tracker's 5 s drone window: only the lock keeps the box
        coordinator.tick(frame())

        val ui = coordinator.state.value.tracks.single()
        assertEquals(live.id, ui.id)
        assertEquals(ObjClass.DRONE, ui.cls)
        assertEquals(BoxF(20f, 20f, 100f, 80f), ui.box)
        assertTrue(ui.iff is IffState.Challenged)
        assertEquals(0.6f, ui.score, 0f) // the tapped track's last score
        assertEquals(live.id, coordinator.lock?.boxId)
    }

    @Test
    fun `lock expires at 10 s`() {
        var clock = 1_000L
        val detector = ScriptedDetector(ArrayDeque(listOf(listOf(drone(10f, 10f, 50f, 40f)), emptyList())))
        val coordinator = IffCoordinator(detector, FakeSignalSource(), clock = { clock })
        coordinator.setPreviewSize(256, 144)
        coordinator.tick(frame())
        val id = coordinator.state.value.tracks.single().id
        coordinator.challenge(id)

        clock += 10_001
        coordinator.tick(frame())

        assertTrue(coordinator.state.value.tracks.none { it.id == id })
        assertNull(coordinator.lock)
        assertTrue(coordinator.state.value.events.first().text.contains("challenge window closed · box $id"))
    }

    @Test
    fun `a second challenge during the window is refused`() {
        val clock = 1_000L
        val detector = ScriptedDetector(
            ArrayDeque(listOf(listOf(drone(10f, 10f, 30f, 25f), drone(80f, 40f, 110f, 60f)))),
        )
        val coordinator = IffCoordinator(detector, FakeSignalSource(), clock = { clock })
        coordinator.tick(frame())
        val (a, b) = coordinator.state.value.tracks.map { it.id }

        coordinator.challenge(a)
        coordinator.challenge(b)

        assertEquals(a, coordinator.lock?.boxId)
        val tracks = coordinator.state.value.tracks
        assertTrue(tracks.single { it.id == a }.iff is IffState.Challenged)
        assertEquals(IffState.Unchallenged, tracks.single { it.id == b }.iff)
    }

    @Test
    fun `challenge on a person id or an unknown id is ignored`() {
        val coordinator = IffCoordinator(FakeDetector(), FakeSignalSource(), clock = { 1_000L })
        coordinator.tick(frame())
        val personId = coordinator.state.value.tracks.single { it.cls == ObjClass.PERSON }.id

        coordinator.challenge(personId)
        assertNull(coordinator.lock)
        coordinator.challenge(9_999)
        assertNull(coordinator.lock)
        assertTrue(coordinator.state.value.tracks.all { it.iff == IffState.Unchallenged })
    }
}
