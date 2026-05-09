package com.watchapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.watchapp.permissions.PermissionsHelper

@Composable
fun PermissionsScreen(onAllGranted: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(PermissionsHelper.currentStep(context)) }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        step = PermissionsHelper.currentStep(context)
        if (step == PermissionsHelper.Step.ALL_GRANTED) onAllGranted()
    }

    // Re-check on each ON_RESUME — the user may have just returned from the
    // system Settings page after toggling a background permission.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                step = PermissionsHelper.currentStep(context)
                if (step == PermissionsHelper.Step.ALL_GRANTED) onAllGranted()
            }
        }
        lifecycle.addObserver(observer)
    }

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (step) {
                PermissionsHelper.Step.STEP_1_FOREGROUND -> {
                    Text(
                        "Allow access to body sensors, location, activity and notifications.",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                    )
                    Chip(
                        onClick = { foregroundLauncher.launch(PermissionsHelper.STEP_1_FOREGROUND) },
                        label = { Text("Allow") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
                PermissionsHelper.Step.STEP_2_BACKGROUND -> {
                    Text(
                        "Open Settings to allow body sensors and location while the app is in the background.",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                    )
                    Chip(
                        onClick = {
                            context.startActivity(
                                PermissionsHelper.appDetailsIntent(context.packageName),
                            )
                        },
                        label = { Text("Open Settings") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
                PermissionsHelper.Step.ALL_GRANTED -> {
                    Text(
                        "All permissions granted.",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                    )
                    LaunchedEffect(Unit) { onAllGranted() }
                }
            }
        }
    }
}
