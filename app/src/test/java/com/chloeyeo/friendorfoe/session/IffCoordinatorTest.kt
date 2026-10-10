package com.chloeyeo.friendorfoe.session

import com.chloeyeo.friendorfoe.detect.FakeDetector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.signal.FakeSignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IffCoordinatorTest {
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
    fun `challenge is the integration lead's TODO`() {
        val coordinator = IffCoordinator(FakeDetector(), FakeSignalSource())
        assertThrows(NotImplementedError::class.java) { coordinator.challenge(1) }
    }
}
