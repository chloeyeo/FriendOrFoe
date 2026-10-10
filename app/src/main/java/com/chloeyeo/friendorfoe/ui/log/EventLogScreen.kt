package com.chloeyeo.friendorfoe.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chloeyeo.friendorfoe.session.DemoScenario
import com.chloeyeo.friendorfoe.session.EventUi
import com.chloeyeo.friendorfoe.ui.ChromeButton
import com.chloeyeo.friendorfoe.ui.ResetButton
import com.chloeyeo.friendorfoe.ui.VerdictChip
import com.chloeyeo.friendorfoe.ui.overlay.millis
import com.chloeyeo.friendorfoe.ui.theme.FriendOrFoeTheme
import com.chloeyeo.friendorfoe.ui.theme.IffAmber
import com.chloeyeo.friendorfoe.ui.theme.IffGreen
import com.chloeyeo.friendorfoe.ui.theme.IffNeutral
import com.chloeyeo.friendorfoe.ui.theme.IffRed
import com.chloeyeo.friendorfoe.ui.theme.MonoStyle
import com.chloeyeo.friendorfoe.ui.theme.OnSurface
import com.chloeyeo.friendorfoe.ui.theme.OnSurfaceMuted
import com.chloeyeo.friendorfoe.ui.theme.Surface0
import com.chloeyeo.friendorfoe.ui.theme.Surface1
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every detection and verdict {t, class, score, box, verdict, reason, roundTripMs}, newest first, with counts;
 * plain status lines ("challenge sent …") show their text across the row.
 */
@Composable
fun EventLogScreen(
    events: List<EventUi>,
    onBack: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val counts = remember(events) { EventCounts.from(events) }
    val clockFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column(
        modifier
            .fillMaxSize()
            .background(Surface0)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChromeButton("← Camera", onBack)
            Spacer(Modifier.width(16.dp))
            Text("Event log", color = OnSurface, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ResetButton(onReset)
        }
        CountsRow(counts)
        Header()
        HorizontalDivider(color = OnSurfaceMuted.copy(alpha = 0.3f))
        if (events.isEmpty()) {
            Text("Nothing logged yet.", color = OnSurfaceMuted, style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(events) { EventRow(it, clockFormat) }
        }
    }
}

@Composable
private fun CountsRow(c: EventCounts) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Count("drones", c.drones, IffAmber)
        Count("people", c.people, IffNeutral)
        Count("friend", c.friend, IffGreen)
        Count("foe", c.foe, IffRed)
        Count("unknown", c.unknown, IffRed)
    }
}

@Composable
private fun RowScope.Count(label: String, n: Int, accent: Color) {
    Column(
        Modifier
            .weight(1f)
            .background(Surface1, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text("$n", color = accent, style = MaterialTheme.typography.titleLarge)
        Text(label, color = OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
    }
}

private object Col {
    val time = 96.dp
    val cls = 80.dp
    val score = 64.dp
    val box = 220.dp
    val verdict = 116.dp
    val rtt = 96.dp
}

@Composable
private fun Header() {
    val style = MaterialTheme.typography.labelLarge
    Row(Modifier.fillMaxWidth()) {
        Cell("time", Col.time, style, OnSurfaceMuted)
        Cell("class", Col.cls, style, OnSurfaceMuted)
        Cell("score", Col.score, style, OnSurfaceMuted)
        Cell("box", Col.box, style, OnSurfaceMuted)
        Cell("verdict", Col.verdict, style, OnSurfaceMuted)
        Text("reason", color = OnSurfaceMuted, style = style, modifier = Modifier.weight(1f))
        Cell("round trip", Col.rtt, style, OnSurfaceMuted)
    }
}

@Composable
private fun EventRow(e: EventUi, clockFormat: SimpleDateFormat) {
    val body = MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Cell(clockFormat.format(Date(e.t)), Col.time, MonoStyle, OnSurface)
        if (e.cls == null && e.verdict == null) {
            Text(e.text, color = OnSurfaceMuted, style = body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Cell(e.cls?.name?.lowercase() ?: "—", Col.cls, body, OnSurface)
            Cell(e.score?.let { String.format(Locale.US, "%.2f", it) } ?: "—", Col.score, MonoStyle, OnSurface)
            Cell(e.box?.let(::boxText) ?: "—", Col.box, MonoStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), OnSurfaceMuted)
            if (e.verdict != null) {
                VerdictChip(e.verdict, Modifier.width(Col.verdict - 12.dp))
                Spacer(Modifier.width(12.dp))
            } else {
                Cell("detection", Col.verdict, body, OnSurfaceMuted)
            }
            Text(
                listOfNotNull(e.droneId, e.reason).joinToString(" · ").ifEmpty { "—" },
                color = OnSurface,
                style = body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Cell(e.roundTripMs?.let(::millis) ?: "—", Col.rtt, MonoStyle, OnSurface)
        }
    }
}

@Composable
private fun Cell(text: String, width: Dp, style: TextStyle, color: Color) {
    Text(text, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(width))
}

@Preview(device = "spec:parent=pixel_5,orientation=landscape")
@Composable
private fun EventLogScreenPreview() {
    FriendOrFoeTheme {
        EventLogScreen(DemoScenario.states(1_760_000_000_000L).last().events, onBack = {}, onReset = {})
    }
}
