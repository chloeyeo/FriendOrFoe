package com.chloeyeo.friendorfoe.camera

import com.chloeyeo.friendorfoe.detect.Frame
import java.nio.ByteBuffer

/**
 * The D-02 copy: repacks a CameraX RGBA_8888 plane into a tight, upright [Frame].
 *
 * Why: CameraX's single RGBA plane may carry `rowStride > width * 4` (row padding), while ClikaRT's
 * `ImageInput.Rgba8888` has no stride parameter and reads "four bytes per pixel in the order red, green, blue,
 * alpha, row by row". So the plane is copied row by row (one `get` per row; one bulk `get` when already tight).
 * The display rotation is fixed (landscape-locked activity), so `rotationDegrees` is a per-device constant; it is
 * applied here (0/90/180/270, clockwise) and the returned [Frame.rotationDegrees] is always 0, which keeps
 * [FrameTransform] on its rotation-0 path.
 *
 * Runs on the camera-analysis thread, before the `ImageProxy` is closed. Pure Kotlin (`java.nio` only); the plane
 * buffer's position is never touched (a duplicate is read).
 */
object RgbaPacker {
    fun pack(src: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, rotationDegrees: Int, timestampNanos: Long = 0L): Frame {
        require(width > 0 && height > 0) { "frame size must be positive: ${width}x$height" }
        require(pixelStride == 4) { "RGBA_8888 expects pixelStride 4, got $pixelStride" }
        require(rowStride >= width * 4) { "rowStride $rowStride < width*4 (${width * 4})" }
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            "rotation $rotationDegrees"
        }
        val rowBytes = width * 4
        val needed = rowStride.toLong() * (height - 1) + rowBytes
        require(src.capacity() >= needed) { "plane has ${src.capacity()} bytes, needs $needed" }

        val tight = ByteArray(rowBytes * height)
        val buf = src.duplicate()
        if (rowStride == rowBytes) {
            buf.position(0)
            buf.get(tight)
        } else {
            for (y in 0 until height) {
                buf.position(y * rowStride)
                buf.get(tight, y * rowBytes, rowBytes)
            }
        }
        return when (rotationDegrees) {
            0 -> Frame(width, height, tight, 0, timestampNanos)
            180 -> Frame(width, height, reversePixels(tight), 0, timestampNanos)
            else -> Frame(height, width, rotate90(tight, width, height, clockwise = rotationDegrees == 90), 0, timestampNanos)
        }
    }

    /** 180 degrees: the pixel sequence reversed, each pixel's four bytes kept in order. */
    private fun reversePixels(src: ByteArray): ByteArray {
        val out = ByteArray(src.size)
        val pixels = src.size / 4
        for (i in 0 until pixels) {
            System.arraycopy(src, i * 4, out, (pixels - 1 - i) * 4, 4)
        }
        return out
    }

    /**
     * 90 degrees into a [height] x [width] buffer. Clockwise: out(x, y) = src(y, height - 1 - x);
     * counter-clockwise (270 clockwise): out(x, y) = src(width - 1 - y, x).
     */
    private fun rotate90(src: ByteArray, width: Int, height: Int, clockwise: Boolean): ByteArray {
        val out = ByteArray(src.size)
        val outW = height
        val outH = width
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val sx = if (clockwise) y else width - 1 - y
                val sy = if (clockwise) height - 1 - x else x
                System.arraycopy(src, (sy * width + sx) * 4, out, (y * outW + x) * 4, 4)
            }
        }
        return out
    }
}
