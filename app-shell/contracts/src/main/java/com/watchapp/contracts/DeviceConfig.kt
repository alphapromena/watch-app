package com.watchapp.contracts

/**
 * Snapshot of device identity + server target. Built by app-shell from
 * SettingsStore and handed to core-networking on construction.
 */
data class DeviceConfig(
    /** Synthetic 15-digit IMEI, prefixed with '9' (unused by GSMA). */
    val imei: String,
    val serverHost: String,
    val serverPort: Int,
    val model: String = "GalaxyWatchUltra",
    val firmwareVersion: String = "1.0.0"
)
