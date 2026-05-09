package com.watchapp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.watchapp.container
import com.watchapp.permissions.PermissionsHelper
import com.watchapp.ui.theme.WatchAppTheme

/**
 * Single Wear OS activity. Hosts the entire Compose tree (MainScreen,
 * DebugScreen, PermissionsScreen) under [AppNav]. If permissions are not
 * fully granted on launch we surface that on MainScreen — the user can
 * tap into PermissionsScreen from there.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touch the helper once so a future static-init regression fails fast.
        PermissionsHelper.STEP_1_FOREGROUND
        setContent {
            WatchAppTheme {
                AppNav(container = container)
            }
        }
    }
}
