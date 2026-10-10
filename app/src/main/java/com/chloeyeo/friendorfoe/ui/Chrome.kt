package com.chloeyeo.friendorfoe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chloeyeo.friendorfoe.session.VerdictKind
import com.chloeyeo.friendorfoe.ui.theme.IffGreen
import com.chloeyeo.friendorfoe.ui.theme.IffRed
import com.chloeyeo.friendorfoe.ui.theme.Ink
import com.chloeyeo.friendorfoe.ui.theme.OnSurface
import com.chloeyeo.friendorfoe.ui.theme.Surface2

/** A large button that reads over the camera picture. */
@Composable
fun ChromeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Surface2.copy(alpha = 0.9f), contentColor = OnSurface),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Reset with a confirmation, so it cannot fire by accident on stage. [onReset] must clear tracks, nonces,
 * verdicts and the event log (OBS-10, `IffCoordinator.reset`).
 */
@Composable
fun ResetButton(onReset: () -> Unit, modifier: Modifier = Modifier) {
    var asking by remember { mutableStateOf(false) }
    ChromeButton("Reset", onClick = { asking = true }, modifier = modifier)
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Reset the session?") },
            text = { Text("Clears tracks, nonces, verdicts and the event log, so the next run starts clean.") },
            confirmButton = {
                TextButton(onClick = { asking = false; onReset() }) { Text("Reset", color = IffRed) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text("Cancel") }
            },
        )
    }
}

fun VerdictKind.color(): Color = when (this) {
    VerdictKind.FRIEND -> IffGreen
    VerdictKind.FOE, VerdictKind.UNKNOWN -> IffRed   // silence is never friendly
}

/** FRIEND / FOE / UNKNOWN on a filled chip, the same colours as the rings. */
@Composable
fun VerdictChip(kind: VerdictKind, modifier: Modifier = Modifier) {
    Text(
        kind.name,
        color = Ink,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(kind.color(), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).background(color, CircleShape))
}
