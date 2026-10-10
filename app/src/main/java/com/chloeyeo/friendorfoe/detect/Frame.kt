package com.chloeyeo.friendorfoe.detect

/**
 * One camera frame handed to a [Detector]: tightly packed RGBA_8888 pixels, four bytes per pixel in the order
 * red, green, blue, alpha, row by row with no row padding, [width] x [height] pixels — the layout
 * `Bitmap.copyPixelsToBuffer` writes and ClikaRT's `ImageInput.Rgba8888` reads. A CameraX RGBA plane may carry
 * `rowStride > width * 4`; the camera controller copies it row by row before building a Frame.
 *
 * @property rotationDegrees the rotation still to apply to show the buffer upright (0 when the controller
 *   already rotated it; stream 1 keeps frames upright in landscape)
 * @property timestampNanos the sensor timestamp, for pacing and the ms/frame numbers
 */
class Frame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val rotationDegrees: Int = 0,
    val timestampNanos: Long = 0L,
) {
    init {
        require(width > 0 && height > 0) { "frame size must be positive: ${width}x$height" }
        require(rgba.size == width * height * 4) {
            "rgba must be tightly packed (${width}x$height x 4 = ${width * height * 4} bytes), got ${rgba.size}"
        }
    }

    override fun toString(): String = "Frame(${width}x$height, rot=$rotationDegrees, t=$timestampNanos)"
}
