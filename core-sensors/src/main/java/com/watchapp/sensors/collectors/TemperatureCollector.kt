package com.watchapp.sensors.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.watchapp.contracts.SensorCollector
import com.watchapp.contracts.SensorEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import android.hardware.SensorEvent as AndroidSensorEvent

/**
 * Emits skin / ambient temperature readings via the Android [SensorManager],
 * down-sampled to one [SensorEvent.BodyTemperature] every [intervalMs].
 *
 * `androidx.health:health-services-client:1.1.0-alpha04` does not expose a
 * `DataType.SKIN_TEMPERATURE` symbol, so the original Health-Services-backed
 * design is unreachable. We fall back to [Sensor.TYPE_AMBIENT_TEMPERATURE],
 * which on Galaxy Watch reads the wrist-side body temperature sensor that
 * Samsung surfaces under the ambient-temperature type.
 *
 * If the device has no ambient-temperature sensor at runtime, the collector
 * logs once and completes its Flow immediately — same pattern as the
 * unsupported-device path elsewhere in this module.
 */
class TemperatureCollector(
    private val context: Context,
    private val intervalMs: Long = 60_000L
) : SensorCollector {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override fun start(scope: CoroutineScope): Flow<SensorEvent> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val ambient = sensorManager?.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        if (sensorManager == null || ambient == null) {
            Log.i(
                TAG,
                "TYPE_AMBIENT_TEMPERATURE unavailable on this device; collector is a no-op"
            )
            close()
            return@callbackFlow
        }

        _isRunning.value = true
        var lastEmittedAt = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: AndroidSensorEvent) {
                val now = System.currentTimeMillis()
                if (now - lastEmittedAt < intervalMs) return
                val celsius = event.values?.firstOrNull()?.toDouble() ?: return
                trySend(
                    SensorEvent.BodyTemperature(
                        celsius = celsius,
                        timestampMs = now
                    )
                )
                lastEmittedAt = now
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // SENSOR_DELAY_NORMAL ≈ 200 ms cadence — the listener throttles to
        // intervalMs internally, so we don't need a faster delivery rate.
        val registered = sensorManager.registerListener(
            listener,
            ambient,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        if (!registered) {
            Log.w(TAG, "registerListener returned false; collector is a no-op")
            close()
            return@callbackFlow
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
            _isRunning.value = false
        }
    }

    override suspend fun stop() {
        _isRunning.value = false
    }

    companion object {
        private const val TAG = "TemperatureCollector"
    }
}
