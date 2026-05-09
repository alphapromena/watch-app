package com.watchapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.watchapp.contracts.StreamerState
import com.watchapp.di.Container
import com.watchapp.permissions.PermissionsHelper
import com.watchapp.service.HealthStreamService
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    container: Container,
    onOpenDebug: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val enabled by container.settingsStore.enabled.collectAsState()
    val streamerState by container.streamer.state.collectAsState()
    val host by container.settingsStore.serverHost.collectAsState()
    val lastEventAt by container.lastEventAt.collectAsState()

    // Re-check permissions on every screen entry (user may have toggled them
    // in Settings, then swiped back to this screen).
    var permsStep by remember { mutableStateOf(PermissionsHelper.currentStep(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permsStep = PermissionsHelper.currentStep(context)
            }
        }
        lifecycle.addObserver(observer)
    }
    val permsOk = permsStep == PermissionsHelper.Step.ALL_GRANTED
    val canStream = permsOk && host.isNotBlank()

    // Drives the "Last: Ns ago" line; ticks once per second.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Scaffold(timeText = { TimeText() }) {
        Box(
            Modifier
                .fillMaxSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = onOpenDebug,
                ),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "WatchApp",
                    style = MaterialTheme.typography.title3,
                )
                Spacer(Modifier.height(6.dp))
                Chip(
                    onClick = {
                        val next = !enabled
                        container.settingsStore.setEnabled(next)
                        if (next) HealthStreamService.start(context)
                        else HealthStreamService.stop(context)
                    },
                    enabled = canStream,
                    colors = if (enabled) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    label = {
                        Text(if (enabled) "Streaming ON" else "Streaming OFF")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(streamerState.label(), style = MaterialTheme.typography.caption2)
                lastEventAt?.let { ts ->
                    val secs = ((nowMs - ts) / 1000L).coerceAtLeast(0L)
                    Text("Last: ${secs}s ago", style = MaterialTheme.typography.caption2)
                }
                if (!permsOk) {
                    Spacer(Modifier.height(6.dp))
                    Chip(
                        onClick = onOpenPermissions,
                        label = { Text("Grant permissions") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (host.isBlank() && permsOk) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No server host — long-press to set",
                        style = MaterialTheme.typography.caption3,
                    )
                }
            }
        }
    }
}

private fun StreamerState.label(): String = when (this) {
    StreamerState.DISCONNECTED -> "Disconnected"
    StreamerState.CONNECTING -> "Connecting…"
    StreamerState.CONNECTED -> "Connected"
    StreamerState.RECONNECTING -> "Reconnecting…"
    StreamerState.FAILED -> "Failed"
}
