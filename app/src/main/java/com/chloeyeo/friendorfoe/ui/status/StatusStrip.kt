package com.chloeyeo.friendorfoe.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chloeyeo.friendorfoe.session.StatusUi
import com.chloeyeo.friendorfoe.ui.overlay.dbm
import com.chloeyeo.friendorfoe.ui.theme.FriendOrFoeTheme
import com.chloeyeo.friendorfoe.ui.theme.IffAmber
import com.chloeyeo.friendorfoe.ui.theme.IffGreen
import com.chloeyeo.friendorfoe.ui.theme.IffRed
import com.chloeyeo.friendorfoe.ui.theme.Ink
import com.chloeyeo.friendorfoe.ui.theme.MonoStyle
import com.chloeyeo.friendorfoe.ui.theme.OnSurface
import com.chloeyeo.friendorfoe.ui.theme.OnSurfaceMuted
import com.chloeyeo.friendorfoe.ui.theme.Scrim
import java.util.Locale

private val GadgetOff = Color(0xFF6B7280)

/**
 * One line across the top of the camera screen: OFFLINE badge, detector, model state, ms/frame,
 * fps and the gadget dot with the last RSSI. Renders from [StatusUi] only.
 */
@Composable
fun StatusStrip(status: StatusUi, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Scrim, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkBadge(status.offline)
        if (status.fakeSignal) FakeBadge()
        Text(
            status.detectorName,
            color = OnSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        ModelState(status.modelReady, status.modelNote)
        // 0 means "not measured yet" in StatusUi.
        Metric(status.msPerFrame.takeIf { it > 0 }?.toString(), "ms/frame")
        Metric(status.fps.takeIf { it > 0f }?.let { String.format(Locale.US, "%.1f", it) }, "fps")
        GadgetDot(status.gadgetConnected, status.rssiDbm)
    }
}

/** Green OFFLINE when airplane mode is on with no validated network; amber ONLINE as a pre-demo warning. */
@Composable
private fun NetworkBadge(offline: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    if (offline) {
        Text(
            "✈ OFFLINE",
            color = Ink,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
            modifier = Modifier.background(IffGreen, shape).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    } else {
        Text(
            "ONLINE",
            color = IffAmber,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.border(2.dp, IffAmber, shape).padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/** Red outline `FAKE SIGNAL`: taps go to the scripted source, not the gadget (Settings → Gadget). */
@Composable
private fun FakeBadge() {
    Text(
        "FAKE SIGNAL",
        color = IffRed,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
        modifier = Modifier.border(2.dp, IffRed, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
private fun ModelState(ready: Boolean, note: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (ready) IffGreen else IffAmber)
        Spacer(Modifier.width(6.dp))
        // The loader's note ("model loading… 12 s", a license code name, "model dir missing") wins over the default.
        Text(note ?: if (ready) "model ready" else "model loading…", color = OnSurface, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Metric(value: String?, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value ?: "–", color = OnSurface, style = MonoStyle)
        Spacer(Modifier.width(4.dp))
        Text(unit, color = OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
    }
}

/** Grey: no gadget on USB. Green: USB serial open, with the last RSSI the gadget reported. */
@Composable
private fun GadgetDot(connected: Boolean, rssiDbm: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (connected) IffGreen else GadgetOff)
        Spacer(Modifier.width(6.dp))
        Text("gadget", color = if (connected) OnSurface else OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
        if (connected && rssiDbm != null) {
            Spacer(Modifier.width(6.dp))
            Text(dbm(rssiDbm), color = OnSurface, style = MonoStyle)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(12.dp).background(color, CircleShape))
}

@Preview(widthDp = 891)
@Composable
private fun StatusStripLivePreview() {
    FriendOrFoeTheme {
        StatusStrip(
            StatusUi("clikart:rtdetr_v2_r18vd", 840, 1.1f, modelReady = true, offline = true, gadgetConnected = true, rssiDbm = -58),
        )
    }
}

@Preview(widthDp = 891)
@Composable
private fun StatusStripStartingPreview() {
    FriendOrFoeTheme {
        StatusStrip(StatusUi("clikart:rtdetr_v2_r18vd", offline = false))
    }
}
