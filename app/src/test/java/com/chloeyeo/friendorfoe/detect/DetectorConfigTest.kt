package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectorConfigTest {
    @Test
    fun `dual round-trips through encode and decode`() {
        val c = DetectorConfig.Dual(
            "/data/user/0/com.chloeyeo.friendorfoe/files/models/rtdetr_v2_drone_rahul",
            "/data/user/0/com.chloeyeo.friendorfoe/files/models/dfine_small_coco",
        )
        assertEquals(
            "dual:/data/user/0/com.chloeyeo.friendorfoe/files/models/rtdetr_v2_drone_rahul|" +
                "/data/user/0/com.chloeyeo.friendorfoe/files/models/dfine_small_coco",
            c.encode(),
        )
        assertEquals(c, DetectorConfig.decode(c.encode()))
    }

    @Test
    fun `the other configs still round-trip`() {
        val configs = listOf(DetectorConfig.Fake, DetectorConfig.ClikaRt("/x/rtdetr_v2_r18vd"), DetectorConfig.Http("http://10.0.0.5:8129"))
        for (c in configs) assertEquals(c, DetectorConfig.decode(c.encode()))
    }

    @Test
    fun `malformed dual falls back to fake`() {
        // "dual:/a|" (empty person dir) is drone-only, see the drone-only round-trip test.
        for (s in listOf("dual:", "dual:/a", "dual:|/b", "dual:/a|/b|/c")) {
            assertEquals(s, DetectorConfig.Fake, DetectorConfig.decode(s))
        }
    }

    @Test
    fun `drone-only dual config round-trips with an empty person dir`() {
        val c = DetectorConfig.Dual("/data/files/models/rtdetr_v2_drone_rahul_224", null)
        assertEquals("dual:/data/files/models/rtdetr_v2_drone_rahul_224|", c.encode())
        assertEquals(c, DetectorConfig.decode(c.encode()))
    }

    @Test
    fun `dual without a drone dir is unreadable`() {
        assertEquals(DetectorConfig.Fake, DetectorConfig.decode("dual:|/x/person"))
    }
}
