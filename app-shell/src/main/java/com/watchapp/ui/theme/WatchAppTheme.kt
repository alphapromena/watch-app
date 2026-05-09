package com.watchapp.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme

/**
 * Default Wear OS Material theme. No custom palette for v1 — the system
 * colors look correct on the Galaxy Watch Ultra.
 */
@Composable
fun WatchAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
