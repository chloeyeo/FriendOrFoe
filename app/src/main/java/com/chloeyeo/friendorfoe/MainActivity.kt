package com.chloeyeo.friendorfoe

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.chloeyeo.friendorfoe.ui.FriendOrFoeApp
import com.chloeyeo.friendorfoe.ui.theme.FriendOrFoeTheme

/**
 * Shows the observer screen directly (no mode picker): [FriendOrFoeApp] in demo mode until the session is wired.
 * TODO(integration lead): CAMERA permission request, CameraController + IffCoordinator wiring — pass
 * `coordinator.state`, the PreviewView, `coordinator::challenge` and `coordinator::setPreviewSize` to FriendOrFoeApp.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On stage: the screen never sleeps and the picture uses the whole display.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            FriendOrFoeTheme {
                FriendOrFoeApp()
            }
        }
    }
}
