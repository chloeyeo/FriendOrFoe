package com.chloeyeo.friendorfoe.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionBenchTest {
    @Test
    fun `the bench tracks its object for every box size`() {
        val lines = MotionBench.run(frames = 12)
        assertEquals(3, lines.size)
        lines.forEach { println(it) }
        assertTrue(lines.joinToString("\n"), lines.all { it.endsWith("lost=false") })
    }
}
