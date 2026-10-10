package com.chloeyeo.friendorfoe.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Sizes are chosen for reading the phone (or its scrcpy mirror) from about 2 m.
val Typography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
)

/** Overlay tag on a ring: the one piece of text that must be readable at a distance. */
val TagTextStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp)

/** Second line under a red tag (droneId · RSSI · round trip). */
val TagDetailStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp)

/** Numbers that update (ms/frame, fps, RSSI): tabular, so they do not jitter. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
