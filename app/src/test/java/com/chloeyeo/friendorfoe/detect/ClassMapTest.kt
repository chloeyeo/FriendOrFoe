package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClassMapTest {
    private val box = BoxF(0f, 0f, 10f, 10f)

    @Test
    fun `drone model maps 0 to DRONE at 0_30 and drops everything else`() {
        val m = ClassMap.DRONE_RAHUL
        assertEquals(ObjClass.DRONE, m.accept(0L, 0.30f))
        assertNull(m.accept(0L, 0.29f))
        assertNull(m.accept(1L, 0.99f))
    }

    @Test
    fun `person model maps 0 to PERSON at 0_50 and drops the COCO drone proxies`() {
        val m = ClassMap.DFINE_PERSON
        assertEquals(ObjClass.PERSON, m.accept(0L, 0.50f))
        assertNull(m.accept(0L, 0.49f))
        for (id in listOf(4L, 14L, 33L, 67L)) assertNull("labelId $id", m.accept(id, 0.99f))
    }

    @Test
    fun `r18 map keeps the COCO proxy set and agrees with LabelMap`() {
        val m = ClassMap.COCO_R18
        for (id in -1L..80L) {
            for (score in listOf(0.27f, 0.28f, 0.49f, 0.5f, 0.9f)) {
                assertEquals("labelId $id score $score", LabelMap.accept(id, score), m.accept(id, score))
            }
        }
    }

    @Test
    fun `map keeps order and drops unmapped rows`() {
        val rows = listOf(
            RawDetection(0L, 0.9f, box, "person"),
            RawDetection(67L, 0.8f, box, "cell phone"),
            RawDetection(0L, 0.4f, box, "person"),
        )
        assertEquals(listOf(Detection(ObjClass.PERSON, 0.9f, box)), ClassMap.DFINE_PERSON.map(rows))
    }

    @Test
    fun `label check accepts the right tables`() {
        assertNull(ClassMap.DRONE_RAHUL.labelMismatch(listOf("drone")))
        val coco = List(80) { "x$it" }.toMutableList().apply { this[0] = "person"; this[4] = "aeroplane" }
        assertNull(ClassMap.DFINE_PERSON.labelMismatch(coco))
        assertNull(ClassMap.COCO_R18.labelMismatch(coco))
    }

    @Test
    fun `label check names the model on a mismatch`() {
        val coco = List(80) { "x$it" }.toMutableList().apply { this[0] = "person"; this[4] = "airplane" }
        assertEquals("r18 model: label 4 is airplane, expected aeroplane", ClassMap.COCO_R18.labelMismatch(coco))
        assertEquals("drone model: 80 labels, expected 1", ClassMap.DRONE_RAHUL.labelMismatch(coco))
        assertNotNull(ClassMap.DRONE_RAHUL.labelMismatch(listOf("bird")))
        assertEquals("person model: 1 labels, expected 80", ClassMap.DFINE_PERSON.labelMismatch(listOf("drone")))
    }
}
