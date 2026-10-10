package com.chloeyeo.friendorfoe.track

/**
 * Per-second numbers for the motion path: how many motion ticks ran in the last window ([fps]) and the median
 * cost of one tick and of one gray sample. [add] is called from one thread (the motion executor); [fps] may be
 * read from any thread. Pure Kotlin.
 */
class MotionStats(private val windowMs: Long = 1_000L) {
    /** One closed window. */
    data class Summary(val fps: Float, val medianTickMs: Double, val medianSampleMs: Double, val ticks: Int)

    private val tickMs = ArrayList<Double>()
    private val sampleMs = ArrayList<Double>()
    private var windowStart = Long.MIN_VALUE

    /** The motion tick rate of the last closed window; 0 before the first one closes. */
    @Volatile
    var fps: Float = 0f
        private set

    /** Records one tick at [nowMs]; returns the window's [Summary] once [windowMs] have passed, else null. */
    fun add(nowMs: Long, tickCostMs: Double, sampleCostMs: Double): Summary? {
        if (windowStart == Long.MIN_VALUE) windowStart = nowMs
        tickMs += tickCostMs
        sampleMs += sampleCostMs
        val span = nowMs - windowStart
        if (span < windowMs) return null
        val summary = Summary(tickMs.size * 1000f / span, median(tickMs), median(sampleMs), tickMs.size)
        fps = summary.fps
        tickMs.clear()
        sampleMs.clear()
        windowStart = nowMs
        return summary
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }
}
