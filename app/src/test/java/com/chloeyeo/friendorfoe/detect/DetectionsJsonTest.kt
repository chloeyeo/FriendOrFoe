package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionsJsonTest {
    @Test
    fun `two rows parse with their values and unknown fields are ignored`() {
        val body = """
            {"image":{"width":1280,"height":720},"model":"PekingU/rtdetr_v2_r18vd",
             "detections":[
              {"box":{"xmin":1,"ymin":2,"xmax":30,"ymax":40},"score":0.61,"label_id":4,"label":"aeroplane","extra":true},
              {"box":{"xmin":100,"ymin":50,"xmax":300,"ymax":400},"score":0.9,"label_id":0,"label":"person"}
             ]}
        """.trimIndent()
        val rows = DetectionsJson.parse(body)
        assertEquals(2, rows.size)
        val (a, p) = rows
        assertEquals(4L, a.labelId)
        assertEquals("aeroplane", a.label)
        assertEquals(0.61f, a.score, 1e-4f)
        assertEquals(1f, a.box.left, 1e-4f)
        assertEquals(2f, a.box.top, 1e-4f)
        assertEquals(30f, a.box.right, 1e-4f)
        assertEquals(40f, a.box.bottom, 1e-4f)
        assertEquals(0L, p.labelId)
        assertEquals("person", p.label)
        assertEquals(0.9f, p.score, 1e-4f)
        assertEquals(BoxF(100f, 50f, 300f, 400f), p.box)
    }

    @Test
    fun `an empty detections array is an empty list`() {
        assertTrue(DetectionsJson.parse("""{"detections":[]}""").isEmpty())
    }

    @Test
    fun `a body that is not JSON or has no detections array throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { DetectionsJson.parse("not json") }
        assertThrows(IllegalArgumentException::class.java) { DetectionsJson.parse("""{"foo":1}""") }
    }

    @Test
    fun `inverted boxes and out-of-range scores are dropped, valid rows kept`() {
        val body = """
            {"detections":[
              {"box":{"xmin":50,"ymin":0,"xmax":10,"ymax":40},"score":0.8,"label_id":4,"label":"aeroplane"},
              {"box":{"xmin":0,"ymin":0,"xmax":10,"ymax":40},"score":1.5,"label_id":4,"label":"aeroplane"},
              {"box":{"xmin":0,"ymin":60,"xmax":10,"ymax":40},"score":0.5,"label_id":0,"label":"person"},
              {"box":{"xmin":0,"ymin":0,"xmax":10,"ymax":40},"score":0.7,"label_id":33,"label":"kite"},
              {"score":0.7,"label_id":33,"label":"kite"}
            ]}
        """.trimIndent()
        val rows = DetectionsJson.parse(body)
        assertEquals(listOf(RawDetection(33L, 0.7f, BoxF(0f, 0f, 10f, 40f), "kite")), rows)
    }

    @Test
    fun `a row without label parses with an empty label`() {
        val rows = DetectionsJson.parse("""{"detections":[{"box":{"xmin":0,"ymin":0,"xmax":5,"ymax":5},"score":0.4,"label_id":14}]}""")
        assertEquals(listOf(RawDetection(14L, 0.4f, BoxF(0f, 0f, 5f, 5f), "")), rows)
    }
}
