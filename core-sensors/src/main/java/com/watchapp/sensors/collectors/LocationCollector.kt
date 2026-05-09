package com.watchapp.sensors.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.watchapp.contracts.SensorCollector
import com.watchapp.contracts.SensorEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streams GPS fixes via [LocationManager] using the raw `GPS_PROVIDER`.
 *
 * Talks to the platform LocationManager directly rather than to
 * `FusedLocationProviderClient` because the FLP smooths over outages with
 * cached fixes — we need the watch to actively report "no GPS fix" so the
 * server's LBS fallback can take over.
 *
 * Behaviour:
 * - Fresh fix → emits [SensorEvent.Location] with speed (m/s) and accuracy
 *   (m) when available, null otherwise.
 * - No fix within [noFixWarningDelayMs] of starting (or since the last fix)
 *   → emits [SensorEvent.NoGpsFix] once, then again every
 *   [noFixRepeatPeriodMs] until a fix arrives.
 */
class LocationCollector(
    private val context: Context,
    private val minTimeMs: Long = 5_000L,
    private val noFixWarningDelayMs: Long = 30_000L,
    private val noFixRepeatPeriodMs: Long = 60_000L
) : SensorCollector {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override fun start(scope: CoroutineScope): Flow<SensorEvent> = callbackFlow {
        if (!hasFineLocationPermission()) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted; LocationCollector is a no-op")
            close()
            return@callbackFlow
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        _isRunning.value = true
        val startedAt = System.currentTimeMillis()

        // Volatile across the watchdog coroutine and the listener callback.
        // Both run on this Flow's collection scope; reads of a Long on a
        // single coroutine context don't need a fence, but we mark the field
        // so a future reader can't assume safety on a different scheduler.
        @Volatile var lastFixAt: Long = 0L
        @Volatile var lastNoFixEmittedAt: Long = 0L

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastFixAt = System.currentTimeMillis()
                trySend(
                    SensorEvent.Location(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        speedMps = if (location.hasSpeed()) location.speed else null,
                        timestampMs = lastFixAt
                    )
                )
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Required by the LocationListener interface on older API levels.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                /* minDistance = */ 0f,
                listener,
                Looper.getMainLooper()
            )
        } catch (se: SecurityException) {
            // Permission revoked between the pre-flight check and the call.
            Log.w(TAG, "SecurityException requesting GPS updates", se)
            close(se)
            return@callbackFlow
        } catch (iae: IllegalArgumentException) {
            // Device has no GPS provider at all (rare on a watch but possible).
            Log.w(TAG, "GPS_PROVIDER unavailable on this device", iae)
            close()
            return@callbackFlow
        }

        val watchdog = launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val now = System.currentTimeMillis()
                val sinceLastFix = if (lastFixAt == 0L) now - startedAt else now - lastFixAt
                if (sinceLastFix < noFixWarningDelayMs) continue

                val firstEmit = lastNoFixEmittedAt == 0L
                val sinceEmit = now - lastNoFixEmittedAt
                if (firstEmit || sinceEmit >= noFixRepeatPeriodMs) {
                    trySend(SensorEvent.NoGpsFix(timestampMs = now))
                    lastNoFixEmittedAt = now
                }
            }
        }

        awaitClose {
            watchdog.cancel()
            runCatching { lm.removeUpdates(listener) }
            _isRunning.value = false
        }
    }

    override suspend fun stop() {
        _isRunning.value = false
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "LocationCollector"
        private const val WATCHDOG_TICK_MS = 5_000L
    }
}
