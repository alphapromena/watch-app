package com.watchapp.sensors.collectors

import android.util.Log
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import com.watchapp.contracts.SensorCollector
import com.watchapp.contracts.SensorEvent
import com.watchapp.sensors.HealthServicesAdapter
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Takes a one-shot SpO2 reading every [intervalMs] using
 * [androidx.health.services.client.MeasureClient].
 *
 * `MeasureClient` does not work in the background; the foreground service
 * owned by app-shell keeps the app "in use" so this collector can run.
 *
 * The exercise data flow is paused around each spot read because some
 * Wear OS devices reject a `MeasureClient` request while an exercise is
 * delivering samples. Stream 0 may eventually flip this off for Galaxy
 * Watch — see INTEGRATION_NOTES.md.
 *
 * If the device reports SPO2 unsupported, the collector logs once and
 * completes its Flow immediately (no-op).
 */
class Spo2Collector(
    private val adapter: HealthServicesAdapter,
    private val intervalMs: Long = 60_000L,
    private val measurementTimeoutMs: Long = 30_000L
) : SensorCollector {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override fun start(scope: CoroutineScope): Flow<SensorEvent> = flow {
        if (!adapter.isMeasureSupported(DataType.SPO2)) {
            Log.i(TAG, "SPO2 not supported by MeasureClient on this device; collector is a no-op")
            return@flow
        }
        _isRunning.value = true
        try {
            while (currentCoroutineContext().isActive) {
                val percent = takeSpotReading()
                if (percent != null) {
                    emit(
                        SensorEvent.BloodOxygen(
                            percent = percent,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                }
                delay(intervalMs)
            }
        } finally {
            _isRunning.value = false
        }
    }

    override suspend fun stop() {
        _isRunning.value = false
    }

    /**
     * Pauses the shared exercise, fires a single MeasureClient request,
     * then resumes. Returns null on timeout or sensor failure.
     */
    private suspend fun takeSpotReading(): Int? {
        adapter.pauseExercise()
        return try {
            withTimeoutOrNull(measurementTimeoutMs) { awaitOneSpo2Sample() }
        } catch (t: Throwable) {
            Log.w(TAG, "SpO2 spot read failed", t)
            null
        } finally {
            adapter.resumeExercise()
        }
    }

    private suspend fun awaitOneSpo2Sample(): Int =
        suspendCancellableCoroutine { cont ->
            val callback = object : MeasureCallback {
                override fun onAvailabilityChanged(
                    dataType: DataType<*, *>,
                    availability: Availability
                ) = Unit

                override fun onDataReceived(data: DataPointContainer) {
                    val sample = data.getData(DataType.SPO2).firstOrNull() ?: return
                    val percent = sample.value.toInt().coerceIn(0, 100)
                    resumeOnce(cont, percent)
                    runCatching {
                        adapter.measureClient.unregisterMeasureCallbackAsync(DataType.SPO2, this)
                    }
                }
            }
            adapter.measureClient.registerMeasureCallback(DataType.SPO2, callback)
            cont.invokeOnCancellation {
                runCatching {
                    adapter.measureClient.unregisterMeasureCallbackAsync(DataType.SPO2, callback)
                }
            }
        }

    private fun resumeOnce(cont: CancellableContinuation<Int>, value: Int) {
        if (cont.isActive) cont.resume(value)
    }

    companion object {
        private const val TAG = "Spo2Collector"
    }
}
