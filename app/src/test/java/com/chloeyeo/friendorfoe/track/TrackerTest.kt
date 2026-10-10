package com.chloeyeo.friendorfoe.track

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.Detection
import com.chloeyeo.friendorfoe.detect.ObjClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerTest {
    private fun drone(l: Float, t: Float, r: Float, b: Float, score: Float = 0.6f) =
        Detection(ObjClass.DRONE, score, BoxF(l, t, r, b))

    private fun person(l: Float, t: Float, r: Float, b: Float, score: Float = 0.9f) =
        Detection(ObjClass.PERSON, score, BoxF(l, t, r, b))

    @Test
    fun `iou of identical disjoint and half-overlapping boxes`() {
        val a = BoxF(0f, 0f, 10f, 10f)
        assertEquals(1f, a.iou(a), 1e-6f)
        assertEquals(0f, a.iou(BoxF(20f, 20f, 30f, 30f)), 0f)
        // a shifted right by half its width: intersection 50, union 150
        assertEquals(1f / 3f, a.iou(BoxF(5f, 0f, 15f, 10f)), 1e-6f)
    }

    @Test
    fun `a box seen again keeps its id`() {
        val tracker = Tracker()
        val first = tracker.update(listOf(drone(100f, 100f, 200f, 180f)))
        val second = tracker.update(listOf(drone(104f, 102f, 205f, 183f)))
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(first[0].id, second[0].id)
        assertEquals(BoxF(104f, 102f, 205f, 183f), second[0].box)
    }

    @Test
    fun `two moving objects keep their ids regardless of detection order`() {
        val tracker = Tracker()
        val frame1 = tracker.update(listOf(person(600f, 200f, 760f, 700f), drone(100f, 100f, 200f, 180f)))
        val personId = frame1.first { it.cls == ObjClass.PERSON }.id
        val droneId = frame1.first { it.cls == ObjClass.DRONE }.id
        assertNotEquals(personId, droneId)

        val frame2 = tracker.update(listOf(drone(110f, 104f, 210f, 184f), person(605f, 198f, 765f, 702f)))
        assertEquals(personId, frame2.first { it.cls == ObjClass.PERSON }.id)
        assertEquals(droneId, frame2.first { it.cls == ObjClass.DRONE }.id)
    }

    @Test
    fun `a different class at the same place is a new track`() {
        val tracker = Tracker()
        val a = tracker.update(listOf(drone(100f, 100f, 200f, 180f)))
        val b = tracker.update(listOf(person(100f, 100f, 200f, 180f)))
        assertNotEquals(a[0].id, b[0].id)
    }

    @Test
    fun `a box far away is a new track`() {
        val tracker = Tracker()
        val a = tracker.update(listOf(drone(100f, 100f, 200f, 180f)))
        val b = tracker.update(listOf(drone(900f, 500f, 1000f, 580f)))
        assertNotEquals(a[0].id, b[0].id)
    }

    @Test
    fun `a missed track coasts then is dropped after maxMisses`() {
        val tracker = Tracker(maxMisses = 2)
        val id = tracker.update(listOf(drone(100f, 100f, 200f, 180f)))[0].id

        assertTrue(tracker.update(emptyList()).isEmpty()) // miss 1: nothing seen this frame
        assertEquals(listOf(id), tracker.tracks.map { it.id }) // but still coasting
        tracker.update(emptyList()) // miss 2: still coasting
        assertEquals(1, tracker.tracks.size)
        tracker.update(emptyList()) // miss 3 > maxMisses: dropped
        assertTrue(tracker.tracks.isEmpty())

        // it comes back as a new object
        val again = tracker.update(listOf(drone(100f, 100f, 200f, 180f)))
        assertNotEquals(id, again[0].id)
    }

    @Test
    fun `a coasting track is re-acquired with the same id`() {
        val tracker = Tracker()
        val id = tracker.update(listOf(drone(100f, 100f, 200f, 180f)))[0].id
        tracker.update(emptyList())
        val back = tracker.update(listOf(drone(103f, 101f, 203f, 181f)))
        assertEquals(id, back[0].id)
        assertEquals(0, back[0].misses)
    }

    @Test
    fun `best overlap wins when two detections compete for one track`() {
        val tracker = Tracker()
        val id = tracker.update(listOf(drone(100f, 100f, 200f, 200f)))[0].id
        val next = tracker.update(listOf(drone(140f, 100f, 240f, 200f), drone(102f, 101f, 202f, 201f)))
        val kept = next.first { it.id == id }
        assertEquals(BoxF(102f, 101f, 202f, 201f), kept.box)
        assertEquals(2, next.size)
    }

    @Test
    fun `ids are never reused after reset`() {
        val tracker = Tracker()
        val id = tracker.update(listOf(drone(100f, 100f, 200f, 180f)))[0].id
        tracker.reset()
        assertTrue(tracker.tracks.isEmpty())
        assertNotEquals(id, tracker.update(listOf(drone(100f, 100f, 200f, 180f)))[0].id)
    }

    @Test
    fun `a drone track stays for 5 s of wall clock after it is lost`() {
        val tracker = Tracker()
        val id = tracker.update(listOf(drone(100f, 100f, 200f, 180f)), 1_000L)[0].id
        assertTrue(tracker.update(emptyList(), 2_000L).isEmpty()) // miss 1
        tracker.update(emptyList(), 4_000L) // miss 2
        tracker.update(emptyList(), 5_900L) // miss 3, 4 900 ms since last seen
        assertEquals(listOf(id), tracker.tracks.map { it.id })
        assertEquals(3, tracker.tracks.single().misses)
        tracker.update(emptyList(), 6_100L) // 5 100 ms > 5 000
        assertTrue(tracker.tracks.isEmpty())
    }

    @Test
    fun `a person track stays for about 1,5 s of wall clock`() {
        val tracker = Tracker()
        val id = tracker.update(listOf(person(600f, 200f, 760f, 700f)), 1_000L)[0].id
        tracker.update(emptyList(), 2_400L)
        assertEquals(listOf(id), tracker.tracks.map { it.id })
        tracker.update(emptyList(), 2_600L)
        assertTrue(tracker.tracks.isEmpty())
    }

    @Test
    fun `a sticky drone re-detected keeps its id and its last-seen time moves`() {
        val tracker = Tracker()
        val id = tracker.update(listOf(drone(100f, 100f, 200f, 180f)), 1_000L)[0].id
        tracker.update(emptyList(), 3_000L)
        val back = tracker.update(listOf(drone(104f, 102f, 204f, 182f)), 5_500L)
        assertEquals(1, back.size)
        assertEquals(id, back[0].id)
        assertEquals(0, back[0].misses)
        assertEquals(5_500L, back[0].lastSeenMillis)
    }

    @Test
    fun `the wall-clock update returns only the tracks seen in that call`() {
        val tracker = Tracker()
        tracker.update(listOf(drone(100f, 100f, 200f, 180f), person(600f, 200f, 760f, 700f)), 1_000L)
        val seen = tracker.update(listOf(person(602f, 201f, 762f, 701f), drone(900f, 500f, 1000f, 580f)), 2_000L)
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.misses == 0 && it.lastSeenMillis == 2_000L })
        assertEquals(3, tracker.tracks.size) // the first drone coasts
    }

    @Test
    fun `stickiness does not depend on the frame rate`() {
        val slow = Tracker()
        slow.update(listOf(drone(100f, 100f, 200f, 180f)), 0L)
        slow.update(emptyList(), 4_000L) // 0.25 fps: one miss in 4 s
        val fast = Tracker()
        fast.update(listOf(drone(100f, 100f, 200f, 180f)), 0L)
        for (t in 250L..4_000L step 250L) fast.update(emptyList(), t) // 4 fps: 16 misses in 4 s
        assertEquals(1, slow.tracks.size)
        assertEquals(1, fast.tracks.size)
    }
}
