package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LoadKnobsTest {
    @Test
    fun `device extra accepts cpu and vulkan only`() {
        assertEquals("cpu", LoadKnobs.parseDevice(null))
        assertEquals("cpu", LoadKnobs.parseDevice(""))
        assertEquals("vulkan:0", LoadKnobs.parseDevice(" Vulkan:0 "))
        assertEquals("cpu", LoadKnobs.parseDevice("cpu"))
        assertEquals(LoadKnobs.DEFAULT_DEVICE, LoadKnobs.parseDevice("cuda:0"))
        assertEquals(LoadKnobs.DEFAULT_DEVICE, LoadKnobs.parseDevice("vulkan:1"))
    }

    @Test
    fun `dtype extra accepts the two half precisions and float32 as the checkpoint's own`() {
        assertEquals(LoadKnobs.DEFAULT_DTYPE, LoadKnobs.parseDtype(null))
        assertEquals("float16", LoadKnobs.parseDtype("float16"))
        assertEquals("bfloat16", LoadKnobs.parseDtype(" BFLOAT16"))
        assertNull(LoadKnobs.parseDtype("float32"))
        assertNull(LoadKnobs.parseDtype("none"))
        assertEquals(LoadKnobs.DEFAULT_DTYPE, LoadKnobs.parseDtype("int8"))
    }

    @Test
    fun `input extras pick the snapshot dirs`() {
        val files = File("/x/files")
        assertNull(ModelDirs.dualFor(files, null, null))
        assertNull(ModelDirs.dualFor(files, 999, 160)) // 160 is a drone-only size
        assertEquals(
            DetectorConfig.Dual(File(files, "models/rtdetr_v2_drone_rahul_224").path, File(files, "models/dfine_small_coco_${ModelDirs.DEFAULT_PERSON_INPUT}").path),
            ModelDirs.dualFor(files, 224, null),
        )
        assertEquals(
            DetectorConfig.Dual(File(files, "models/rtdetr_v2_drone_rahul_${ModelDirs.DEFAULT_DRONE_INPUT}").path, File(files, "models/dfine_small_coco_256").path),
            ModelDirs.dualFor(files, 1234, 256),
        )
        assertEquals(
            DetectorConfig.Dual(File(files, "models/rtdetr_v2_drone_rahul_160").path, File(files, "models/dfine_small_coco_224").path),
            ModelDirs.dualFor(files, 160, 224),
        )
    }
}
