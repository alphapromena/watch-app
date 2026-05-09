package com.watchapp.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Pure-logic helper for the sequenced runtime-permission flow.
 *
 * Step 1 — foreground perms requested via the standard runtime dialog:
 *   BODY_SENSORS, ACCESS_FINE_LOCATION, ACTIVITY_RECOGNITION, POST_NOTIFICATIONS
 *
 * Step 2 — background perms. On Android 13+ these have no runtime dialog;
 * the user must toggle them on the app's Settings page. The Compose screen
 * shows an explanation and uses [appDetailsIntent] to deep-link there.
 *   BODY_SENSORS_BACKGROUND, ACCESS_BACKGROUND_LOCATION
 *
 * Step 3 is a verification gate: the streaming toggle stays disabled until
 * [currentStep] returns [Step.ALL_GRANTED].
 */
object PermissionsHelper {

    enum class Step { STEP_1_FOREGROUND, STEP_2_BACKGROUND, ALL_GRANTED }

    val STEP_1_FOREGROUND: Array<String> = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    val STEP_2_BACKGROUND: Array<String> = arrayOf(
        Manifest.permission.BODY_SENSORS_BACKGROUND,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    )

    fun currentStep(context: Context): Step = when {
        !allGranted(context, STEP_1_FOREGROUND) -> Step.STEP_1_FOREGROUND
        !allGranted(context, STEP_2_BACKGROUND) -> Step.STEP_2_BACKGROUND
        else -> Step.ALL_GRANTED
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context, permissions: Array<String>): Boolean =
        permissions.all { isGranted(context, it) }

    /**
     * Intent that opens the system Settings page for this app — the only way
     * to grant background-sensor / background-location on Android 13+.
     */
    fun appDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
