package com.chloeyeo.friendorfoe.detect

import kotlin.math.max
import kotlin.math.min

/**
 * An axis-aligned box in pixel coordinates, top-left origin, edges inclusive of [left]/[top] and exclusive of
 * [right]/[bottom] by convention. Pure Kotlin on purpose: `android.graphics.RectF` is an empty stub in local
 * unit tests (its constructor leaves every field 0 and its methods throw "not mocked"), so the tracker, the
 * frame transform and the UI state carry this type instead and stay testable with `:app:testDebugUnitTest`.
 */
data class BoxF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = max(0f, width) * max(0f, height)

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Intersection over union with [other]; 0 when the boxes do not overlap or both are empty. */
    fun iou(other: BoxF): Float {
        val iw = min(right, other.right) - max(left, other.left)
        val ih = min(bottom, other.bottom) - max(top, other.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}
