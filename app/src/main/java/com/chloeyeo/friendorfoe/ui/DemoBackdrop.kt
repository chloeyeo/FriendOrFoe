package com.chloeyeo.friendorfoe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.TrackUi
import com.chloeyeo.friendorfoe.ui.theme.OnSurfaceMuted

private val Silhouette = Color(0xFF0E1116)

/**
 * Stands in for the camera picture in demo mode: a sky with a drone or a person drawn under each track box,
 * and a label so nobody mistakes it for a live feed.
 */
@Composable
fun DemoBackdrop(tracks: List<TrackUi>, modifier: Modifier = Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF3A5068), Color(0xFF1A232E))))
            val horizon = size.height * 0.8f
            drawRect(Color(0xFF1C211B), topLeft = Offset(0f, horizon), size = Size(size.width, size.height - horizon))
            for (t in tracks) {
                when (t.cls) {
                    ObjClass.DRONE -> drawDrone(t.box)
                    ObjClass.PERSON -> drawPerson(t.box)
                }
            }
        }
        Text(
            "DEMO · scripted scenario, no camera",
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun DrawScope.drawDrone(b: BoxF) {
    val c = Offset(b.centerX, b.centerY)
    val arm = minOf(b.width, b.height) * 0.34f
    val rotor = arm * 0.42f
    val stroke = Stroke(width = arm * 0.12f, cap = StrokeCap.Round)
    for ((dx, dy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        val tip = Offset(c.x + dx * arm, c.y + dy * arm * 0.7f)
        drawLine(Silhouette, c, tip, strokeWidth = arm * 0.14f, cap = StrokeCap.Round)
        drawCircle(Silhouette.copy(alpha = 0.55f), radius = rotor, center = tip)
        drawCircle(Silhouette, radius = rotor, center = tip, style = stroke)
    }
    val body = Size(arm * 0.9f, arm * 0.55f)
    drawRoundRect(Silhouette, Offset(c.x - body.width / 2, c.y - body.height / 2), body, CornerRadius(body.height / 3))
}

private fun DrawScope.drawPerson(b: BoxF) {
    val head = b.width * 0.2f
    drawCircle(Silhouette, radius = head, center = Offset(b.centerX, b.top + head * 1.3f))
    val torsoTop = b.top + head * 2.6f
    val torso = Size(b.width * 0.62f, b.bottom - torsoTop - b.height * 0.02f)
    drawRoundRect(Silhouette, Offset(b.centerX - torso.width / 2, torsoTop), torso, CornerRadius(head))
}
