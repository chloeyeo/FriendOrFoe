package com.chloeyeo.friendorfoe.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.IffState
import com.chloeyeo.friendorfoe.session.TrackUi
import com.chloeyeo.friendorfoe.session.VerdictKind

/**
 * Rings, tags and the countdown arc for one [TrackUi] (README: unchallenged amber, FRIEND green, FOE red,
 * UNKNOWN red, person neutral). TODO(stream 3 / Jongho): this is the starting point — make the reasons legible
 * at 2 m, pick the final palette with the design track, animate the verdict.
 */
object OverlayPainter {
    val Amber = Color(0xFFFFB300)
    val Green = Color(0xFF2ECC40)
    val Red = Color(0xFFFF3B30)
    val Neutral = Color(0xB3FFFFFF)
    val TagBackground = Color(0xAA000000)

    fun ringColor(track: TrackUi): Color = when (track.cls) {
        ObjClass.PERSON -> Neutral
        ObjClass.DRONE -> when (val iff = track.iff) {
            IffState.Unchallenged -> Amber
            is IffState.Challenged -> Amber
            is IffState.Verdict -> when (iff.kind) {
                VerdictKind.FRIEND -> Green
                VerdictKind.FOE -> Red
                VerdictKind.UNKNOWN -> Red
            }
        }
    }

    fun tagText(track: TrackUi): String = when (track.cls) {
        ObjClass.PERSON -> "person"
        ObjClass.DRONE -> when (val iff = track.iff) {
            IffState.Unchallenged -> "drone · ?"
            is IffState.Challenged -> "drone · challenging…"
            is IffState.Verdict -> buildString {
                append(iff.kind.name)
                iff.droneId?.let { append(" · ").append(it) }
                iff.reason?.let { append(" · ").append(it) }
                iff.rssiDbm?.let { append(" · ").append(it).append(" dBm") }
                iff.roundTripMs?.let { append(" · ").append(it).append(" ms") }
            }
        }
    }

    /** Draws the ring, the countdown arc (while challenged) and the tag of [track]; coordinates are canvas pixels. */
    fun DrawScope.drawTrack(track: TrackUi, nowMillis: Long, textMeasurer: TextMeasurer, tagStyle: TextStyle) {
        val color = ringColor(track)
        val box = track.box
        val pad = 6.dp.toPx()
        val ringTopLeft = Offset(box.left - pad, box.top - pad)
        val ringSize = Size(box.width + 2 * pad, box.height + 2 * pad)
        val ring = Stroke(width = 4.dp.toPx())

        if (track.cls == ObjClass.DRONE) {
            drawOval(color = color, topLeft = ringTopLeft, size = ringSize, style = ring)
        } else {
            drawRoundRect(color = color, topLeft = ringTopLeft, size = ringSize, cornerRadius = CornerRadius(12.dp.toPx()), style = ring)
        }

        val iff = track.iff
        if (iff is IffState.Challenged) {
            val remaining = (iff.until - nowMillis).coerceIn(0L, iff.windowMs)
            val sweep = 360f * remaining / iff.windowMs
            val arcPad = pad + 6.dp.toPx()
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(box.left - arcPad, box.top - arcPad),
                size = Size(box.width + 2 * arcPad, box.height + 2 * arcPad),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        val layout = textMeasurer.measure(tagText(track), style = tagStyle)
        val hPad = 8.dp.toPx()
        val vPad = 4.dp.toPx()
        val tagHeight = layout.size.height + 2 * vPad
        val tagTop = (box.top - pad - tagHeight - 4.dp.toPx()).coerceAtLeast(0f)
        val tagLeft = box.left.coerceAtLeast(0f)
        drawRoundRect(
            color = TagBackground,
            topLeft = Offset(tagLeft, tagTop),
            size = Size(layout.size.width + 2 * hPad, tagHeight),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
        drawText(textLayoutResult = layout, color = color, topLeft = Offset(tagLeft + hPad, tagTop + vPad))
    }
}
