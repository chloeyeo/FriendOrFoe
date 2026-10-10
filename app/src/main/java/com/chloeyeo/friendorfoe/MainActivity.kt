package com.chloeyeo.friendorfoe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.chloeyeo.friendorfoe.ui.DemoCameraScreen
import com.chloeyeo.friendorfoe.ui.theme.FriendOrFoeTheme

/**
 * Shows the observer screen directly (no mode picker). Phase 1: the demo scenario on the overlay.
 * TODO(integration lead): CAMERA permission request, CameraController + IffCoordinator wiring, settings entry.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FriendOrFoeTheme {
                DemoCameraScreen()
            }
        }
    }
}
