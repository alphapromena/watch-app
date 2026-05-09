package com.watchapp.sensors.collectors

import android.util.Log
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.HrAccuracy
import com.watchapp.contracts.SensorCollector
import com.watchapp.contracts.SensorEvent
import com.watchapp.sensors.HealthServicesAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Streams heart-rate readings from the Wear OS [androidx.health.services.client.ExerciseClient].
 *
 * Filters out samples whose [HrAccuracy] is below `ACCURACY_MEDIUM` —
 * NO_CONTACT / UNRELIABLE / ACCURACY_LOW samples are noisy enough that the
 * downstream stream would just be misleading the server. Acquires a
 * reference on the shared exercise session via [HealthServicesAdapter] so
 * other collectors (skin temperature) can share it.
 */
class HeartRateCollector(
    private val adapter: HealthServicesAdapter
) : SensorCollector {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override fun start(scope: CoroutineScope): Flow<SensorEvent> = flow {
        adapter.acquireExercise()
        _isRunning.value = true
        try {
            adapter.exerciseUpdates.collect { update ->
                val samples = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
                for (sample in samples) {
                    val acc = sample.accuracy as? HrAccuracy
                    if (acc != null && !acc.sensorStatus.isAtLeastMedium()) continue
                    val bpm = sample.value.toInt()
                    if (bpm <= 0) continue
                    emit(
                        SensorEvent.HeartRate(
                            bpm = bpm,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                }
            }
        } finally {
            _isRunning.value = false
            adapter.releaseExercise()
        }
    }.onCompletion { cause ->
        if (cause != null) Log.w(TAG, "HR flow completed with error", cause)
    }

    override suspend fun stop() {
        // No-op: lifecycle is bounded by the scope passed to start().
        // Cancelling that scope triggers the finally block above.
        _isRunning.value = false
    }

    private fun HrAccuracy.SensorStatus.isAtLeastMedium(): Boolean = when (this) {
        HrAccuracy.SensorStatus.ACCURACY_MEDIUM,
        HrAccuracy.SensorStatus.ACCURACY_HIGH -> true
        else -> false
    }

    companion object {
        private const val TAG = "HeartRateCollector"
    }
}
