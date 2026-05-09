package com.watchapp.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.watchapp.contracts.DeviceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Encrypted, reactive store for user-mutable runtime settings:
 * server host/port and the streaming kill switch. The IMEI is constant for
 * the lifetime of the install and is injected by the [com.watchapp.di.Container]
 * from [com.watchapp.identity.DeviceIdGenerator].
 *
 * State changes are written through to disk synchronously and pushed onto
 * the corresponding [StateFlow]s so Compose can observe them.
 */
class SettingsStore internal constructor(
    /** Stable synthetic IMEI for this install. Sourced once from DeviceIdGenerator. */
    val imei: String,
    private val prefs: SharedPreferences,
) {

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _serverHost = MutableStateFlow(prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST)
    val serverHost: StateFlow<String> = _serverHost.asStateFlow()

    private val _serverPort = MutableStateFlow(prefs.getInt(KEY_PORT, DEFAULT_PORT))
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    fun setEnabled(value: Boolean) = write {
        putBoolean(KEY_ENABLED, value)
        _enabled.value = value
    }

    fun setServerHost(value: String) = write {
        putString(KEY_HOST, value)
        _serverHost.value = value
    }

    fun setServerPort(value: Int) = write {
        putInt(KEY_PORT, value)
        _serverPort.value = value
    }

    /** Snapshot of the values [com.watchapp.contracts.Streamer] needs. */
    fun deviceConfig(): DeviceConfig = DeviceConfig(
        imei = imei,
        serverHost = _serverHost.value,
        serverPort = _serverPort.value,
    )

    private inline fun write(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    companion object {
        const val PREFS_NAME = "watchapp_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_HOST = "serverHost"
        const val KEY_PORT = "serverPort"

        const val DEFAULT_ENABLED = false
        // TBD — the user sets this from the debug screen (long-press on MainScreen).
        // Empty string means "not configured"; the UI gates the streaming toggle on
        // serverHost.isNotBlank() so we never try to connect to "".
        const val DEFAULT_HOST = ""
        const val DEFAULT_PORT = 5088

        /** Production wiring: encrypted prefs backed by an Android keystore master key. */
        fun create(context: Context, imei: String): SettingsStore {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return SettingsStore(imei, prefs)
        }
    }
}
