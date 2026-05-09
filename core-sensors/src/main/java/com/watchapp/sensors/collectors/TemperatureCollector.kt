package com.watchapp.sensors.collectors

import android.util.Log
import androidx.health.services.client.data.DataType
import com.watchapp.contracts.SensorCollector
import com.watchapp.contracts.SensorEvent
import com.watchapp.sensors.HealthServicesAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Emits skin temperature readings sourced from the shared
 * [androidx.health.services.client.ExerciseClient] update stream,
 * down-sampled to one [SensorEvent.BodyTemperature] every [intervalMs].
 *
 * Skin temperature is delivered as exercise samples on devices that
 * support it; this collector piggy-backs on the WALKING session that
 * [HeartRateCollector] keeps alive via [HealthServicesAdapter].
 *
 * If the device does not list `SKIN_TEMPERATURE` among supported exercise
 * data types, the collector logs once and completes its Flow immediately.
 */
class TemperatureCollector(
    private val adapter: HealthServicesAdapter,
    private val intervalMs: Long = 60_000L
) : SensorCollector {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override fun start(scope: CoroutineScope): Flow<SensorEvent> = flow {
        adapter.acquireExercise()
        try {
            val supported = adapter.supportedExerciseDataTypes()
                .contains(DataType.SKIN_TEMPERATURE)
            if (!supported) {
                Log.i(TAG, "SKIN_TEMPERATURE unsupported on this device; collector is a no-op")
                return@flow
            }
            _isRunning.value = true

            var lastEmittedAt = 0L
            adapter.exerciseUpdates.collect { update ->
                val sample = update.latestMetrics
                    .getData(DataType.SKIN_TEMPERATURE)
                    .lastOrNull() ?: return@collect
                val now = System.currentTimeMillis()
                if (now - lastEmittedAt < intervalMs) return@collect

                emit(
                    SensorEvent.BodyTemperature(
                        celsius = sample.value,
                        timestampMs = now
                    )
                )
                lastEmittedAt = now
            }
        } finally {
            _isRunning.value = false
            adapter.releaseExercise()
        }
    }

    override suspend fun stop() {
        _isRunning.value = false
    }

    companion object {
        private const val TAG = "TemperatureCollector"
    }
}
