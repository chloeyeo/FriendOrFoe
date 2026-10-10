package com.chloeyeo.friendorfoe.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always dark and never dynamic: the colours judges see must not depend on the phone's wallpaper.
private val FofColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    secondary = IffAmber,
    onSecondary = Ink,
    tertiary = IffGreen,
    error = IffRed,
    onError = Ink,
    background = Surface0,
    onBackground = OnSurface,
    surface = Surface1,
    onSurface = OnSurface,
    surfaceVariant = Surface2,
    onSurfaceVariant = OnSurfaceMuted,
)

@Composable
fun FriendOrFoeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FofColorScheme,
        typography = Typography,
        content = content,
    )
}
