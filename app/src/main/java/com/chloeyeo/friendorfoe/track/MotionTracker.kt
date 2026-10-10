package com.chloeyeo.friendorfoe.track

import com.chloeyeo.friendorfoe.detect.BoxF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The 10 fps box follower between detections. The detector needs seconds per frame on phone A, so between two
 * detections every tracked box is moved by template matching on small [GrayFrame]s, and every detection is
 * fast-forwarded from its capture time to now before it is shown.
 *
 * - Ring buffer: the last [bufferNanos] (5 s) of gray frames, by sensor timestamp.
 * - Template: per track id, a luma patch cut from the gray frame at detection time (the inner part of the box,
 *   [innerFraction] trimmed per side, so less background is matched), sampled at most [maxSamples] per side.
 * - Search: on every new gray frame the patch is searched around its last position plus the last velocity, by
 *   sum of absolute differences, within a radius of max([minRadius], [radiusFraction] x box side) gray pixels:
 *   a coarse pass on the half-resolution pyramid level ([GrayFrame.half], every position up to a 6 px radius
 *   there, a grid of about 15x15 beyond), then every full-resolution position within 2 px of the coarse winner. A patch too
 *   small for the half level is searched position by position at full resolution. The cost per box per frame is
 *   bounded whatever the box size. Ties keep the smaller move, so a still
 *   object stays still. The box is translated by the found offset times the gray step.
 * - Quality: the mean absolute difference per sampled pixel ([Motion.mad]). Above [maxMad], above 0.9 x the
 *   patch's own texture, or when the patch has almost no texture ([minContrast]), the match is poor: the box is not
 *   moved ([Motion.lost] = true) and keeps its last position; later frames keep searching with the same patch, so
 *   a box that is found again resumes.
 * - Latency compensation ([fastForward]): a detection made on the frame captured at t0 arrives seconds later; its
 *   boxes are matched on the buffered gray frame nearest t0 and then stepped through every buffered frame after it,
 *   so the returned [Seed]s are where the objects are NOW.
 *
 * Pure Kotlin, not thread-safe: the owner (the coordinator) calls it under its own lock.
 */
class MotionTracker(
    private val bufferNanos: Long = 5_000_000_000L,
    private val minRadius: Int = 12,
    private val radiusFraction: Float = 0.5f,
    private val maxMad: Float = 28f,
    private val minContrast: Float = 4f,
    private val maxSamples: Int = 24,
    private val innerFraction: Float = 0.1f,
) {
    /** The box of one track as of the latest gray frame, in analysis-frame pixels. */
    data class Motion(val box: BoxF, val mad: Float, val lost: Boolean)

    /** A detection box carried to the latest buffered frame by [fastForward]; hand it to [assign]. */
    class Seed internal constructor(internal val state: State) {
        val box: BoxF get() = state.box
        val lost: Boolean get() = state.lost
    }

    internal class Patch(val w: Int, val h: Int, val data: ByteArray, val contrast: Float, val half: Patch?)

    internal class State(
        var box: BoxF,
        var gx: Int,
        var gy: Int,
        var patch: Patch?,
        var vx: Int = 0,
        var vy: Int = 0,
        var mad: Float = 0f,
        var lost: Boolean = false,
        var timestampNanos: Long = Long.MIN_VALUE,
    )

    private val frames = ArrayDeque<GrayFrame>()
    private val states = HashMap<Int, State>()

    /** The newest buffered frame's timestamp, or null when nothing is buffered. */
    val latestTimestampNanos: Long? get() = frames.lastOrNull()?.timestampNanos

    /** How many gray frames are buffered. */
    val bufferedFrames: Int get() = frames.size

    /** The ids that have a motion state. */
    val ids: Set<Int> get() = states.keys.toSet()

    /** Buffers [frame] and moves every tracked box to it. A frame not newer than the newest buffered one is ignored. */
    fun push(frame: GrayFrame) {
        val last = frames.lastOrNull()
        if (last != null) {
            if (frame.timestampNanos <= last.timestampNanos) return
            if (frame.width != last.width || frame.height != last.height || frame.step != last.step) {
                frames.clear()
                states.values.forEach { it.patch = null }
            }
        }
        frames.addLast(frame)
        while (frames.size > 1 && frame.timestampNanos - frames.first().timestampNanos > bufferNanos) frames.removeFirst()
        for (s in states.values) advance(s, frame)
    }

    /** A copy of the buffered frames (oldest first), for [fastForward] outside the owner's lock. */
    fun snapshot(): List<GrayFrame> = frames.toList()

    /**
     * One [Seed] per box of a detection made on the frame captured at [t0Nanos] (the gray frames' clock): the
     * template is cut on the frame of [frames] nearest [t0Nanos], then stepped through every newer one. With no
     * frames every seed keeps its box unchanged (no template; it will not move).
     *
     * With an explicit [frames] snapshot this touches no tracker state, so the owner may run it outside its lock
     * (it is the expensive part: seconds of frames per box) and then [catchUp] the seeds under the lock.
     */
    fun fastForward(t0Nanos: Long, boxes: List<BoxF>, frames: List<GrayFrame> = this.frames): List<Seed> {
        if (frames.isEmpty()) return boxes.map { Seed(State(it, 0, 0, null)) }
        var base = 0
        var bestGap = Long.MAX_VALUE
        for ((i, f) in frames.withIndex()) {
            val gap = abs(f.timestampNanos - t0Nanos)
            if (gap < bestGap) {
                bestGap = gap
                base = i
            }
        }
        val baseFrame = frames[base]
        val seeds = boxes.map { box -> Seed(seed(box, baseFrame)) }
        for (k in base + 1 until frames.size) {
            for (s in seeds) advance(s.state, frames[k])
        }
        return seeds
    }

    /** Steps [seeds] through the buffered frames newer than each seed (those pushed during an unlocked [fastForward]). */
    fun catchUp(seeds: List<Seed>) {
        for (f in frames) for (seed in seeds) advance(seed.state, f)
    }

    /** Makes [seed] the motion state of track [id], replacing any earlier one. */
    fun assign(id: Int, seed: Seed) {
        states[id] = seed.state
    }

    /** The current box of track [id], or null when it has no motion state. */
    fun box(id: Int): Motion? = states[id]?.let { Motion(it.box, it.mad, it.lost) }

    /** Forgets every id not in [keep]. */
    fun retain(keep: Set<Int>) {
        states.keys.retainAll(keep)
    }

    /** Forgets every track; the frame buffer is kept. */
    fun reset() {
        states.clear()
    }

    private fun seed(box: BoxF, frame: GrayFrame): State {
        val s = State(box, 0, 0, null, timestampNanos = frame.timestampNanos)
        cut(s, frame)
        return s
    }

    /** Cuts the template for [s]'s box from [frame]: the inner part of the box, in gray pixels, clamped. */
    private fun cut(s: State, frame: GrayFrame) {
        val step = frame.step.toFloat()
        val trimX = s.box.width * innerFraction
        val trimY = s.box.height * innerFraction
        val l = floor((s.box.left + trimX) / step).toInt().coerceIn(0, frame.width)
        val t = floor((s.box.top + trimY) / step).toInt().coerceIn(0, frame.height)
        val r = ceil((s.box.right - trimX) / step).toInt().coerceIn(0, frame.width)
        val b = ceil((s.box.bottom - trimY) / step).toInt().coerceIn(0, frame.height)
        val w = r - l
        val h = b - t
        if (w < MIN_PATCH || h < MIN_PATCH) {
            s.patch = null
            return
        }
        s.patch = template(frame, l, t, w, h)
        s.gx = l
        s.gy = t
    }

    /** The patch at (l, t) plus, when it is big enough, its half-resolution twin cut from [GrayFrame.half] at (l/2, t/2). */
    private fun template(frame: GrayFrame, l: Int, t: Int, w: Int, h: Int): Patch {
        val half = frame.half
        var hp: Patch? = null
        if (half != null && w >= 2 * MIN_PATCH && h >= 2 * MIN_PATCH) {
            val hl = l / 2
            val ht = t / 2
            val hw = min(w / 2, half.width - hl)
            val hh = min(h / 2, half.height - ht)
            if (hw >= MIN_PATCH && hh >= MIN_PATCH) hp = extract(half, hl, ht, hw, hh, null)
        }
        return extract(frame, l, t, w, h, hp)
    }

    private fun extract(frame: GrayFrame, l: Int, t: Int, w: Int, h: Int, half: Patch?): Patch {
        val data = ByteArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(frame.luma, (t + y) * frame.width + l, data, y * w, w)
        }
        var sum = 0L
        for (v in data) sum += v.toInt() and 0xFF
        val mean = sum.toFloat() / data.size
        var dev = 0f
        for (v in data) dev += abs((v.toInt() and 0xFF) - mean)
        return Patch(w, h, data, dev / data.size, half)
    }

    /**
     * Moves [s] to [frame]: a coarse search on the half-resolution pyramid level (when the patch has one; a small
     * patch is searched position by position at full resolution instead), a refinement at full resolution, then
     * translate and refresh the template on a good match.
     */
    private fun advance(s: State, frame: GrayFrame) {
        if (frame.timestampNanos <= s.timestampNanos) return
        s.timestampNanos = frame.timestampNanos
        val patch = s.patch ?: return
        if (patch.w > frame.width || patch.h > frame.height || patch.contrast < minContrast) {
            s.lost = true
            s.mad = 0f
            return
        }
        val fine = max(1, ceil(max(patch.w, patch.h).toFloat() / maxSamples).toInt())
        val boxGray = max(s.box.width, s.box.height) / frame.step
        val radius = max(minRadius, (radiusFraction * boxGray).roundToInt())
        val halfPatch = patch.half
        val halfFrame = frame.half
        val found = if (halfPatch != null && halfFrame != null) {
            // Coarse: every position up to a 6 px half-level radius, a grid of about 15x15 beyond (big boxes).
            val hr = (radius + 1) / 2
            val coarseStride = max(1, ceil(max(halfPatch.w, halfPatch.h).toFloat() / COARSE_SAMPLES).toInt())
            val c = search(halfFrame, halfPatch, s.gx / 2, s.gy / 2, s.vx / 2, s.vy / 2, hr, max(1, (hr + 7) / 8), coarseStride)
            // Fine: every position within 2 px of the coarse winner (a sharp texture has no wider basin), then polish.
            search(frame, patch, s.gx, s.gy, 2 * c[0].toInt(), 2 * c[1].toInt(), 2, 1, fine)
        } else {
            search(frame, patch, s.gx, s.gy, s.vx, s.vy, radius, 1, fine)
        }
        val bestDx = found[0].toInt()
        val bestDy = found[1].toInt()

        val mad = found[2].toFloat() / samplesOf(patch, fine)
        s.mad = mad
        if (mad > maxMad || mad > patch.contrast * POOR_VS_CONTRAST) {
            s.lost = true
            s.vx = 0
            s.vy = 0
            return
        }
        s.lost = false
        s.vx = bestDx
        s.vy = bestDy
        s.gx += bestDx
        s.gy += bestDy
        val step = frame.step.toFloat()
        s.box = BoxF(
            s.box.left + bestDx * step,
            s.box.top + bestDy * step,
            s.box.right + bestDx * step,
            s.box.bottom + bestDy * step,
        )
        // Refresh the template on the object's new look; keep the old one if the new patch went flat.
        s.patch = template(frame, s.gx, s.gy, patch.w, patch.h).takeIf { it.contrast >= minContrast } ?: patch
    }

    /**
     * The best displacement (dx, dy, SAD) of [patch] whose last top-left was (x0, y0) in [frame]: candidates no
     * move, the prediction (cx, cy), a grid of step [grid0] within [radius] of the prediction (none when 0), then
     * 3x3 refinement around the winner while the step halves down to 1. Ties keep the smaller move.
     */
    private fun search(frame: GrayFrame, patch: Patch, x0: Int, y0: Int, cx: Int, cy: Int, radius: Int, grid0: Int, stride: Int): LongArray {
        val maxX = frame.width - patch.w
        val maxY = frame.height - patch.h
        if (maxX < 0 || maxY < 0) return longArrayOf(0, 0, Long.MAX_VALUE / 4)
        fun clampX(dx: Int) = (x0 + dx).coerceIn(0, maxX) - x0
        fun clampY(dy: Int) = (y0 + dy).coerceIn(0, maxY) - y0

        var bestDx = clampX(0)
        var bestDy = clampY(0)
        var best = sad(frame, patch, x0 + bestDx, y0 + bestDy, stride, Long.MAX_VALUE)
        fun tryAt(dx0: Int, dy0: Int) {
            val dx = clampX(dx0)
            val dy = clampY(dy0)
            if (dx == bestDx && dy == bestDy) return
            val v = sad(frame, patch, x0 + dx, y0 + dy, stride, best)
            if (v < best || (v == best && abs(dx) + abs(dy) < abs(bestDx) + abs(bestDy))) {
                best = v
                bestDx = dx
                bestDy = dy
            }
        }
        tryAt(cx, cy)
        var grid = max(1, grid0)
        if (radius > 0) {
            var dy = -radius
            while (dy <= radius) {
                var dx = -radius
                while (dx <= radius) {
                    tryAt(cx + dx, cy + dy)
                    dx += grid
                }
                dy += grid
            }
        }
        while (true) {
            val ox = bestDx
            val oy = bestDy
            for (ry in -1..1) for (rx in -1..1) if (rx != 0 || ry != 0) tryAt(ox + rx * grid, oy + ry * grid)
            if (grid == 1) break
            grid = max(1, grid / 2)
        }
        return longArrayOf(bestDx.toLong(), bestDy.toLong(), best)
    }

    private fun samplesOf(patch: Patch, stride: Int): Int =
        ((patch.h + stride - 1) / stride) * ((patch.w + stride - 1) / stride)

    /** Sum of absolute differences of [patch] placed at (x, y) in [frame], every [stride]-th pixel; stops early past [limit]. */
    private fun sad(frame: GrayFrame, patch: Patch, x: Int, y: Int, stride: Int, limit: Long): Long {
        var sum = 0L
        val fw = frame.width
        val luma = frame.luma
        val data = patch.data
        var py = 0
        while (py < patch.h) {
            val fo = (y + py) * fw + x
            val po = py * patch.w
            var px = 0
            while (px < patch.w) {
                sum += abs((luma[fo + px].toInt() and 0xFF) - (data[po + px].toInt() and 0xFF))
                px += stride
            }
            if (sum > limit) return sum
            py += stride
        }
        return sum
    }

    private companion object {
        const val MIN_PATCH = 4

        /** Half-level patch pixels per side sampled in the coarse pass. */
        const val COARSE_SAMPLES = 12

        /** A match whose error exceeds this share of the patch's own texture is no better than a random place. */
        const val POOR_VS_CONTRAST = 0.9f
    }
}
