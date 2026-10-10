package com.chloeyeo.friendorfoe.ui.gadget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chloeyeo.friendorfoe.session.DemoScenario
import com.chloeyeo.friendorfoe.session.SignalUi
import com.chloeyeo.friendorfoe.signal.SignalKind
import com.chloeyeo.friendorfoe.ui.overlay.FAKE_MARK
import com.chloeyeo.friendorfoe.session.StatusUi
import com.chloeyeo.friendorfoe.ui.StatusDot
import com.chloeyeo.friendorfoe.ui.VerdictChip
import com.chloeyeo.friendorfoe.ui.log.ageText
import com.chloeyeo.friendorfoe.ui.overlay.TAG_SEP
import com.chloeyeo.friendorfoe.ui.overlay.dbm
import com.chloeyeo.friendorfoe.ui.overlay.millis
import com.chloeyeo.friendorfoe.ui.theme.FriendOrFoeTheme
import com.chloeyeo.friendorfoe.ui.theme.IffGreen
import com.chloeyeo.friendorfoe.ui.theme.MonoStyle
import com.chloeyeo.friendorfoe.ui.theme.OnSurface
import com.chloeyeo.friendorfoe.ui.theme.OnSurfaceMuted
import com.chloeyeo.friendorfoe.ui.theme.Surface1

private val GadgetOff = Color(0xFF6B7280)

/**
 * The reader gadget at a glance: connection state, last RSSI, and its answers newest first with verdict,
 * droneId, reason, RSSI, round trip and age.
 */
@Composable
fun GadgetPanel(status: StatusUi, answers: List<SignalUi>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Surface1.copy(alpha = 0.94f), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Gadget", color = OnSurface, style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (status.gadgetConnected) IffGreen else GadgetOff)
            Spacer(Modifier.width(8.dp))
            Text(
                if (status.gadgetConnected) "USB serial open" else "not attached",
                color = OnSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (status.gadgetConnected && status.rssiDbm != null) {
            Text("last RSSI ${dbm(status.rssiDbm)}", color = OnSurface, style = MonoStyle)
        }
        HorizontalDivider(color = OnSurfaceMuted.copy(alpha = 0.3f))
        if (answers.isEmpty()) {
            Text("No answers yet. Tap a drone box to challenge.", color = OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(answers) { AnswerRow(it) }
        }
    }
}

@Composable
private fun AnswerRow(a: SignalUi) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a.verdict != null) {
                VerdictChip(a.verdict)
            } else {
                Text("waiting…", color = OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(a.droneId, a.reason, if (a.source == SignalKind.FAKE) FAKE_MARK else null).joinToString(TAG_SEP).ifEmpty { "—" },
                color = OnSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row {
            Text(
                listOfNotNull(a.rssiDbm?.let(::dbm), a.roundTripMs?.let(::millis)).joinToString(TAG_SEP).ifEmpty { "—" },
                color = OnSurface,
                style = MonoStyle,
                modifier = Modifier.weight(1f),
            )
            Text(ageText(a.ageMs), color = OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(widthDp = 360, heightDp = 411)
@Composable
private fun GadgetPanelPreview() {
    FriendOrFoeTheme {
        BoxWithConstraints {
            val state = DemoScenario.states(0L, constraints.maxWidth, constraints.maxHeight).last()
            GadgetPanel(state.status, state.signal, Modifier.fillMaxHeight())
        }
    }
}
