package com.chloeyeo.friendorfoe.camera

import com.chloeyeo.friendorfoe.detect.BoxF
import kotlin.math.max

/**
 * Maps analysis-frame pixels to preview-view pixels for `PreviewView.ScaleType.FILL_CENTER`: the frame is scaled
 * uniformly until it covers the view, then centered, so the overflow is cropped equally on both sides of the
 * longer axis. Pure arithmetic, unit-tested. Rotation 0 only (the activity is landscape-locked and frames arrive
 * upright); TODO(stream 1): add 90/180/270 if `setOutputImageRotationEnabled(true)` is not used.
 */
data class FrameTransform(
    val frameWidth: Int,
    val frameHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    val rotationDegrees: Int = 0,
) {
    init {
        require(frameWidth > 0 && frameHeight > 0 && viewWidth > 0 && viewHeight > 0) {
            "sizes must be positive: frame ${frameWidth}x$frameHeight, view ${viewWidth}x$viewHeight"
        }
        require(rotationDegrees == 0) { "TODO(stream 1): only rotation 0 is implemented, got $rotationDegrees" }
    }

    /** The uniform scale factor frame -> view. */
    val scale: Float = max(viewWidth / frameWidth.toFloat(), viewHeight / frameHeight.toFloat())

    /** Offset of the scaled frame's left edge in view pixels (zero or negative). */
    val offsetX: Float = (viewWidth - frameWidth * scale) / 2f

    /** Offset of the scaled frame's top edge in view pixels (zero or negative). */
    val offsetY: Float = (viewHeight - frameHeight * scale) / 2f

    /** A box in frame pixels -> the same box in view pixels. */
    fun toPreview(box: BoxF): BoxF = BoxF(
        left = box.left * scale + offsetX,
        top = box.top * scale + offsetY,
        right = box.right * scale + offsetX,
        bottom = box.bottom * scale + offsetY,
    )

    /** A point in view pixels (a tap) -> frame pixels. */
    fun toFrame(viewX: Float, viewY: Float): Pair<Float, Float> =
        Pair((viewX - offsetX) / scale, (viewY - offsetY) / scale)

    companion object {
        /** No scaling: frame and view share a coordinate space (used before the preview size is known). */
        fun identity(width: Int, height: Int): FrameTransform = FrameTransform(width, height, width, height)
    }
}
