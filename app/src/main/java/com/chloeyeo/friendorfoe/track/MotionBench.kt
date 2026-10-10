package com.chloeyeo.friendorfoe.track

import com.chloeyeo.friendorfoe.detect.BoxF
import java.util.Locale
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug micro-benchmark of [MotionTracker] on the device CPU (`--ez motion_bench true`): synthetic 320x180 gray
 * frames (step 4, as on phone A) with a smooth textured object moving 3 gray px per frame, for several box sizes.
 * Reports the median cost of one motion step with one tracked box and the cost of one 3 s fast-forward (30
 * frames). Pure Kotlin; returns the log lines. On the phone it runs warm (several rounds) while the detector runs,
 * which is the condition the motion executor really works in.
 */
object MotionBench {
    /** Runs [rounds] rounds (the first ones warm the JIT up) and returns the last round's lines. */
    fun run(sidesGray: List<Int> = listOf(12, 40, 100), frames: Int = 40, rounds: Int = 1): List<String> {
        var lines = emptyList<String>()
        repeat(rounds.coerceAtLeast(1)) { lines = sidesGray.map { side -> bench(side, frames) } }
        return lines
    }

    private fun bench(side: Int, frames: Int): String {
        val r = Random(side)
        val tex = Array(side) { y -> IntArray(side) { x -> (128 + 60 * sin(x * 0.7 + y * 0.3) + r.nextInt(-30, 31)).toInt().coerceIn(0, 255) } }
        fun frame(i: Int): GrayFrame {
            val ox = 10 + 3 * i % (320 - side - 20)
            val oy = (180 - side) / 2
            val luma = ByteArray(320 * 180)
            for (y in 0 until 180) for (x in 0 until 320) {
                val v = if (x in ox until ox + side && y in oy until oy + side) tex[y - oy][x - ox] else 90 + (x + y) / 8
                luma[y * 320 + x] = v.toByte()
            }
            return GrayFrame(320, 180, luma, 4, 1280, 720, i * 100_000_000L)
        }
        val all = (0 until frames).map(::frame)
        all.forEach { it.half } // build the pyramid level up front (the camera path pays it once per frame, not per box)
        val oy = (180 - side) / 2
        val box0 = BoxF(10 * 4f, oy * 4f, (10 + side) * 4f, (oy + side) * 4f)

        val m = MotionTracker()
        m.push(all[0])
        m.assign(1, m.fastForward(0L, listOf(box0)).single())
        val stepMs = ArrayList<Double>()
        for (i in 1 until frames) {
            val t0 = System.nanoTime()
            m.push(all[i])
            stepMs += (System.nanoTime() - t0) / 1e6
        }
        val lost = m.box(1)?.lost
        val ffFrames = minOf(31, frames)
        val ff = MotionTracker()
        for (i in 0 until ffFrames) ff.push(all[i])
        val t1 = System.nanoTime()
        ff.fastForward(0L, listOf(box0))
        val ffMs = (System.nanoTime() - t1) / 1e6
        val med = stepMs.sorted()[stepMs.size / 2]
        return String.format(
            Locale.US,
            "motion bench box %d gray px (%d frame px): %.2f ms/step median, fast-forward %d frames %.1f ms, lost=%s",
            side, side * 4, med, ffFrames - 1, ffMs, lost,
        )
    }
}
