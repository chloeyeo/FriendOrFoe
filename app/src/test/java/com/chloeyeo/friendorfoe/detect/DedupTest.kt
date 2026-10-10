package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class DedupTest {
    private fun drone(l: Float, t: Float, r: Float, b: Float, score: Float = 0.6f) =
        Detection(ObjClass.DRONE, score, BoxF(l, t, r, b))

    private fun person(l: Float, t: Float, r: Float, b: Float, score: Float = 0.9f) =
        Detection(ObjClass.PERSON, score, BoxF(l, t, r, b))

    @Test
    fun `two overlapping drone boxes keep the higher score`() {
        val high = drone(0f, 0f, 100f, 100f, score = 0.6f)
        val low = drone(0f, 0f, 100f, 80f, score = 0.4f) // IoU 0.8
        val out = Dedup.apply(listOf(high, low))
        assertEquals(listOf(high), out)
    }

    @Test
    fun `a person box overlapping a drone box gives way to the drone (D-08)`() {
        val p = person(0f, 0f, 100f, 100f, score = 0.9f)
        val d = drone(0f, 0f, 100f, 80f, score = 0.3f)
        assertEquals(listOf(d), Dedup.apply(listOf(p, d)))
    }

    @Test
    fun `a person box after an overlapping drone box is dropped`() {
        val d = drone(0f, 0f, 100f, 80f, score = 0.9f)
        val p = person(0f, 0f, 100f, 100f, score = 0.6f)
        assertEquals(listOf(d), Dedup.apply(listOf(d, p)))
    }

    @Test
    fun `disjoint person and drone are untouched`() {
        val p = person(0f, 0f, 50f, 50f)
        val d = drone(200f, 200f, 260f, 240f, score = 0.5f)
        val out = Dedup.apply(listOf(p, d))
        assertEquals(2, out.size)
        assertEquals(listOf(p, d), out)
    }

    @Test
    fun `output is score descending and keeps the original objects`() {
        val p = person(0f, 0f, 100f, 100f, score = 0.9f)
        val far = drone(300f, 300f, 360f, 340f, score = 0.8f)
        val replacing = drone(0f, 0f, 100f, 80f, score = 0.3f) // replaces p, lands last by score
        val out = Dedup.apply(listOf(p, far, replacing))
        assertEquals(listOf(far, replacing), out)
    }

    @Test
    fun `boxes just below the IoU threshold both survive`() {
        val a = drone(0f, 0f, 100f, 100f, score = 0.7f)
        val b = drone(0f, 0f, 100f, 45f, score = 0.5f) // IoU 0.45
        assertEquals(listOf(a, b), Dedup.apply(listOf(a, b), iouThreshold = 0.5f))
    }

    @Test
    fun `toDetections maps classes, applies thresholds and de-dups`() {
        val rows = listOf(
            RawDetection(0L, 0.9f, BoxF(0f, 0f, 100f, 100f), "person"),
            RawDetection(4L, 0.30f, BoxF(0f, 0f, 100f, 80f), "aeroplane"), // replaces the person
            RawDetection(14L, 0.20f, BoxF(300f, 300f, 320f, 320f), "bird"), // below the drone floor
            RawDetection(67L, 0.95f, BoxF(500f, 0f, 560f, 90f), "cell phone"), // not our class
            RawDetection(0L, 0.4f, BoxF(600f, 0f, 700f, 200f), "person"), // below the person floor
        )
        val out = RawDetections.toDetections(rows)
        assertEquals(listOf(Detection(ObjClass.DRONE, 0.30f, BoxF(0f, 0f, 100f, 80f))), out)
    }
}
