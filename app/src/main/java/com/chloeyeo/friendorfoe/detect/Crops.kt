package com.chloeyeo.friendorfoe.detect

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A square region of an analysis [Frame], in whole frame pixels: [left], [top] inclusive, [size] pixels per side. */
data class CropRect(val left: Int, val top: Int, val size: Int) {
    val right: Int get() = left + size
    val bottom: Int get() = top + size
}

/**
 * The crop re-detection geometry: a tracked drone box becomes a square crop of the analysis frame (the box side
 * times [DEFAULT_SCALE], at least [MIN_SIDE] pixels, clamped inside the frame), the crop is cut as its own tightly
 * packed [Frame], and the crop model's boxes are mapped back to frame pixels. Pure Kotlin; runs on the runtime lane.
 */
object Crops {
    /** The crop side is the box's longer side times this (the drone fills about half of the crop). */
    const val DEFAULT_SCALE = 2f

    /** The smallest crop side in analysis pixels (a tiny far drone still gets some context). */
    const val MIN_SIDE = 96

    /**
     * The square crop centred on [box]: side `max(ceil(scale * longer side), minSide)`, at most the frame's shorter
     * side, shifted (never shrunk) to stay inside the [frameWidth] x [frameHeight] frame. Null for an empty frame or
     * a box with no finite size.
     */
    fun square(box: BoxF, frameWidth: Int, frameHeight: Int, scale: Float = DEFAULT_SCALE, minSide: Int = MIN_SIDE): CropRect? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val longer = max(box.width, box.height)
        if (!longer.isFinite() || !box.centerX.isFinite() || !box.centerY.isFinite()) return null
        val wanted = max(ceil(scale * max(0f, longer)).toInt(), minSide)
        val side = min(wanted, min(frameWidth, frameHeight))
        val left = (box.centerX - side / 2f).roundToInt().coerceIn(0, frameWidth - side)
        val top = (box.centerY - side / 2f).roundToInt().coerceIn(0, frameHeight - side)
        return CropRect(left, top, side)
    }

    /** [rect] of [frame] as its own tightly packed RGBA frame, same rotation and timestamp. [rect] must lie inside. */
    fun cut(frame: Frame, rect: CropRect): Frame {
        require(rect.size > 0 && rect.left >= 0 && rect.top >= 0 && rect.right <= frame.width && rect.bottom <= frame.height) {
            "crop $rect outside the ${frame.width}x${frame.height} frame"
        }
        val rowBytes = rect.size * 4
        val out = ByteArray(rowBytes * rect.size)
        for (y in 0 until rect.size) {
            System.arraycopy(frame.rgba, ((rect.top + y) * frame.width + rect.left) * 4, out, y * rowBytes, rowBytes)
        }
        return Frame(rect.size, rect.size, out, frame.rotationDegrees, frame.timestampNanos)
    }

    /** A box in crop pixels back in frame pixels, clipped to the crop. */
    fun toFrame(box: BoxF, rect: CropRect): BoxF {
        val s = rect.size.toFloat()
        return BoxF(
            rect.left + box.left.coerceIn(0f, s),
            rect.top + box.top.coerceIn(0f, s),
            rect.left + box.right.coerceIn(0f, s),
            rect.top + box.bottom.coerceIn(0f, s),
        )
    }
}
