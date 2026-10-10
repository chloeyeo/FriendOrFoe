package com.chloeyeo.friendorfoe.track

import com.chloeyeo.friendorfoe.detect.BoxF

/**
 * The tapped box, frozen for the challenge window: while a challenge is in flight the verdict is attributed to
 * track [boxId] and its ring stays where it was tapped ([rect], frame pixels) even if the tracker loses the box
 * for a frame. [untilMillis] is wall-clock time (`System.currentTimeMillis()`) when the window closes (10 s).
 */
data class BoxLock(val boxId: Int, val rect: BoxF, val untilMillis: Long) {
    fun isActive(nowMillis: Long): Boolean = nowMillis < untilMillis

    /** Milliseconds left in the window, never negative. */
    fun remainingMillis(nowMillis: Long): Long = (untilMillis - nowMillis).coerceAtLeast(0L)

    companion object {
        const val WINDOW_MS: Long = 10_000L
    }
}
