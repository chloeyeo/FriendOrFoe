package com.chloeyeo.friendorfoe.ui.overlay

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.TrackUi
import com.chloeyeo.friendorfoe.ui.theme.IffAmber
import com.chloeyeo.friendorfoe.ui.theme.IffNeutral
import com.chloeyeo.friendorfoe.ui.theme.Ink
import com.chloeyeo.friendorfoe.ui.theme.Scrim
import com.chloeyeo.friendorfoe.ui.theme.TagDetailStyle
import com.chloeyeo.friendorfoe.ui.theme.TagTextStyle
import com.chloeyeo.friendorfoe.ui.theme.color

/** Draws one track's ring, tag and (while challenged) countdown badge; the box is in canvas (preview) pixels. */
fun DrawScope.drawTrack(track: TrackUi, tag: TrackTag, measurer: TextMeasurer) {
    val tone = tag.tone.color()
    val topLeft = Offset(track.box.left, track.box.top)
    val boxSize = Size(track.box.width, track.box.height)
    val ring = (if (track.cls == ObjClass.DRONE) 6.dp else 3.dp).toPx()
    val corner = CornerRadius(12.dp.toPx())

    // A dark halo under the ring keeps it visible against a bright sky.
    drawRoundRect(Color.Black.copy(alpha = 0.45f), topLeft, boxSize, corner, style = Stroke(ring + 4.dp.toPx()))
    drawRoundRect(tone, topLeft, boxSize, corner, style = Stroke(ring))

    tag.countdown?.let { drawCountdown(it, Offset(topLeft.x + boxSize.width, topLeft.y), measurer) }
    drawTag(tag, topLeft, boxSize, tone, measurer)
}

private fun DrawScope.drawTag(tag: TrackTag, boxTopLeft: Offset, boxSize: Size, tone: Color, measurer: TextMeasurer) {
    val filled = tag.tone != Tone.NEUTRAL
    val padH = 10.dp.toPx()
    val padV = 4.dp.toPx()
    val gap = 6.dp.toPx()

    val main = measureLine(measurer, tag.text, TagTextStyle, maxWidth = size.width - 2 * padH)
    val mainSize = Size(main.size.width + 2 * padH, main.size.height + 2 * padV)
    val detail = tag.detail?.let { measureLine(measurer, it, TagDetailStyle, maxWidth = size.width - 2 * padH) }
    val detailSize = detail?.let { Size(it.size.width + 2 * padH, it.size.height + 2 * padV) } ?: Size.Zero

    // Above the box when there is room, otherwise just inside its top edge; never off-canvas.
    val x = boxTopLeft.x.coerceIn(0f, (size.width - maxOf(mainSize.width, detailSize.width)).coerceAtLeast(0f))
    val above = boxTopLeft.y - gap - mainSize.height
    val y = if (above >= 0f) above else (boxTopLeft.y + gap).coerceAtMost(size.height - mainSize.height)
    val radius = CornerRadius(8.dp.toPx())

    drawRoundRect(if (filled) tone else Scrim, Offset(x, y), mainSize, radius)
    drawText(main, color = if (filled) Ink else IffNeutral, topLeft = Offset(x + padH, y + padV))

    if (detail != null) {
        // Under the box, so it never hides the main tag or the object.
        val below = boxTopLeft.y + boxSize.height + gap
        val dy = if (below + detailSize.height <= size.height) below else y + mainSize.height + 2.dp.toPx()
        drawRoundRect(Scrim, Offset(x, dy), detailSize, radius)
        drawText(detail, color = tone, topLeft = Offset(x + padH, dy + padV))
    }
}

/** Amber arc that empties over the 10 s window, with the seconds left, centred on the box's top-right corner. */
private fun DrawScope.drawCountdown(c: Countdown, corner: Offset, measurer: TextMeasurer) {
    val r = 28.dp.toPx()
    val center = Offset(
        corner.x.coerceIn(r, (size.width - r).coerceAtLeast(r)),
        corner.y.coerceIn(r, (size.height - r).coerceAtLeast(r)),
    )
    val stroke = 6.dp.toPx()
    drawCircle(Scrim, radius = r, center = center)
    val arcTopLeft = Offset(center.x - r + stroke / 2, center.y - r + stroke / 2)
    val arcSize = Size(2 * r - stroke, 2 * r - stroke)
    drawArc(IffAmber.copy(alpha = 0.25f), 0f, 360f, false, arcTopLeft, arcSize, style = Stroke(stroke))
    drawArc(
        IffAmber, -90f, 360f * c.fraction, false, arcTopLeft, arcSize,
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
    val label = measureLine(measurer, c.seconds.toString(), TagTextStyle, maxWidth = 2 * r)
    drawText(
        label,
        color = IffAmber,
        topLeft = Offset(center.x - label.size.width / 2f, center.y - label.size.height / 2f),
    )
}

private fun DrawScope.measureLine(measurer: TextMeasurer, text: String, style: TextStyle, maxWidth: Float): TextLayoutResult =
    measurer.measure(
        AnnotatedString(text),
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1)),
        density = this,
    )
