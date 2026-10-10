package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelMapTest {
    @Test
    fun `labelId 0 is a person`() {
        assertEquals(ObjClass.PERSON, LabelMap.classOf(0L))
    }

    @Test
    fun `aeroplane bird and kite are drones`() {
        for (id in listOf(4L, 14L, 33L)) assertEquals("labelId $id", ObjClass.DRONE, LabelMap.classOf(id))
    }

    @Test
    fun `other ids are dropped`() {
        for (id in listOf(-1L, 1L, 3L, 5L, 13L, 15L, 32L, 34L, 67L, 79L, 80L)) assertNull("labelId $id", LabelMap.classOf(id))
    }

    @Test
    fun `default thresholds are 0_5 for person and 0_28 for drone`() {
        assertEquals(0.5f, LabelMap.threshold(ObjClass.PERSON), 0f)
        assertEquals(0.28f, LabelMap.threshold(ObjClass.DRONE), 0f)
    }

    @Test
    fun `accept applies the class threshold`() {
        assertEquals(ObjClass.PERSON, LabelMap.accept(0L, 0.5f))
        assertNull(LabelMap.accept(0L, 0.49f))
        assertEquals(ObjClass.DRONE, LabelMap.accept(14L, 0.28f))
        assertNull(LabelMap.accept(14L, 0.27f))
        assertNull(LabelMap.accept(67L, 0.99f))
    }

    @Test
    fun `accept honours custom thresholds`() {
        assertEquals(ObjClass.DRONE, LabelMap.accept(33L, 0.15f, droneThreshold = 0.1f))
        assertNull(LabelMap.accept(0L, 0.6f, personThreshold = 0.7f))
    }
}
