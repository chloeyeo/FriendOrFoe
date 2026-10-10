package com.chloeyeo.friendorfoe.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun snapshot(vararg names: String): File {
        val dir = tmp.newFolder("rtdetr_v2_r18vd")
        names.forEach { File(dir, it).writeText("x") }
        return dir
    }

    private fun messageOf(dir: File): String {
        try {
            ModelFiles.check(dir)
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        fail("expected IllegalArgumentException for $dir")
        return ""
    }

    @Test
    fun completeSnapshotPasses() {
        ModelFiles.check(snapshot(*ModelFiles.REQUIRED.toTypedArray()))
    }

    @Test
    fun missingWeightsNamesTheFile() {
        val dir = snapshot("config.json", "preprocessor_config.json")
        val msg = messageOf(dir)
        assertTrue(msg, msg.contains("model dir missing: model.safetensors"))
        assertFalse(msg, msg.contains(dir.parentFile!!.path))
    }

    @Test
    fun nonExistentDirectoryIsMissing() {
        val dir = File(tmp.root, "nope/rtdetr_v2_r18vd")
        val msg = messageOf(dir)
        assertTrue(msg, msg.contains("model dir missing"))
        assertFalse(msg, msg.contains(dir.parentFile!!.path))
    }

    @Test
    fun messagesNeverCarryTheParentPath() {
        for (skip in ModelFiles.REQUIRED) {
            val dir = File(tmp.root, "case-$skip").apply { mkdirs() }
            ModelFiles.REQUIRED.filter { it != skip }.forEach { File(dir, it).writeText("x") }
            val msg = messageOf(dir)
            assertEquals("model dir missing: $skip", msg)
            assertFalse(msg, msg.contains(dir.parentFile!!.path))
        }
    }

    @Test
    fun detectorConstructsWithoutNativeCode() {
        val d = ClikaRtDetector("/data/user/0/x/files/models/rtdetr_v2_r18vd")
        assertEquals("clikart:rtdetr_v2_r18vd", d.name)
        assertFalse(d.isOpen)
    }
}
