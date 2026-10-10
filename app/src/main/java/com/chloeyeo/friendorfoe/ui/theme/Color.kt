package com.chloeyeo.friendorfoe.ui.theme

import androidx.compose.ui.graphics.Color
import com.chloeyeo.friendorfoe.ui.overlay.Tone

// IFF colours: saturated, readable on a live camera picture and on a projector. Text on a filled
// tag is always Ink (near-black), which keeps contrast above 4.5:1 on every one of them.
val IffAmber = Color(0xFFFFB300)
val IffGreen = Color(0xFF00E676)
val IffRed = Color(0xFFFF3D3D)
val IffNeutral = Color(0xFFE6E6E6)
val Ink = Color(0xFF0B0D10)
val Scrim = Color(0xB3000000)        // 70 % black behind text drawn over the picture

// App surfaces (dark: the camera picture is the main content).
val Surface0 = Color(0xFF0B0D10)
val Surface1 = Color(0xFF161A20)
val Surface2 = Color(0xFF222831)
val OnSurface = Color(0xFFF2F4F7)
val OnSurfaceMuted = Color(0xFF9AA4B2)
val Accent = Color(0xFF4FC3F7)

fun Tone.color(): Color = when (this) {
    Tone.AMBER -> IffAmber
    Tone.GREEN -> IffGreen
    Tone.RED -> IffRed
    Tone.NEUTRAL -> IffNeutral
}
