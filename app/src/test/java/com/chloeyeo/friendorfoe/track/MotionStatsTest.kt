package com.chloeyeo.friendorfoe.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MotionStatsTest {
    @Test
    fun `one summary per second with the tick rate and the median costs`() {
        val stats = MotionStats()
        var summary: MotionStats.Summary? = null
        for (i in 0..10) {
            val s = stats.add(1_000L + i * 100L, tickCostMs = if (i == 3) 50.0 else 2.0, sampleCostMs = 1.5)
            if (i < 10) assertNull(s) else summary = s
        }
        assertNotNull(summary)
        assertEquals(11, summary!!.ticks)
        assertEquals(11f, summary.fps, 0.01f)
        assertEquals(2.0, summary.medianTickMs, 1e-9)
        assertEquals(1.5, summary.medianSampleMs, 1e-9)
        assertEquals(11f, stats.fps, 0.01f)
    }
}
