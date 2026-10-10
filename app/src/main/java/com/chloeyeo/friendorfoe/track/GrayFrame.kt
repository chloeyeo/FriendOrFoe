package com.chloeyeo.friendorfoe.track

/**
 * A small grayscale copy of one analysis frame for the 10 fps [MotionTracker] (pure Kotlin, no Android types).
 * One luma byte per pixel, row by row, [width] x [height]; gray pixel (x, y) covers the upright analysis-frame
 * pixels starting at (x * [step], y * [step]), so a frame-pixel box maps to gray pixels by dividing by [step].
 *
 * @property frameWidth the upright analysis frame's width in pixels (the box coordinate space of the detector)
 * @property frameHeight the upright analysis frame's height in pixels
 * @property timestampNanos the camera sensor timestamp, the same clock as `Frame.timestampNanos`
 * @property sampleNanos how long the camera thread took to sample it (for the motion log line)
 */
class GrayFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
    val step: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val timestampNanos: Long,
    val sampleNanos: Long = 0L,
) {
    init {
        require(width > 0 && height > 0 && step > 0) { "gray frame ${width}x$height step $step" }
        require(luma.size == width * height) { "luma must be ${width * height} bytes, got ${luma.size}" }
    }

    /**
     * The next pyramid level: half the size, each pixel the mean of a 2x2 block, [step] doubled; null when this
     * frame is under 16 px on a side. Computed once, on first use (thread-safe).
     */
    val half: GrayFrame? by lazy {
        if (width < 16 || height < 16) return@lazy null
        val hw = width / 2
        val hh = height / 2
        val out = ByteArray(hw * hh)
        for (y in 0 until hh) {
            val r0 = 2 * y * width
            val r1 = r0 + width
            for (x in 0 until hw) {
                val i = 2 * x
                val sum = (luma[r0 + i].toInt() and 0xFF) + (luma[r0 + i + 1].toInt() and 0xFF) +
                    (luma[r1 + i].toInt() and 0xFF) + (luma[r1 + i + 1].toInt() and 0xFF)
                out[y * hw + x] = (sum shr 2).toByte()
            }
        }
        GrayFrame(hw, hh, out, step * 2, frameWidth, frameHeight, timestampNanos)
    }

    /** The luma value 0..255 at (x, y). */
    fun at(x: Int, y: Int): Int = luma[y * width + x].toInt() and 0xFF

    override fun toString(): String = "GrayFrame(${width}x$height step=$step t=$timestampNanos)"
}
