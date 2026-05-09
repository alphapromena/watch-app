package com.watchapp.sensors.collectors

import android.util.Log
import com.watchapp.contracts.SensorCollector
import com.watchapp.contracts.SensorEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * SpO2 collector. **No-op in the current build.**
 *
 * The brief asked for one-shot SpO2 readings via `MeasureClient`, but
 * `androidx.health:health-services-client:1.1.0-alpha04` (the version pinned
 * in `gradle/libs.versions.toml`) does not expose a `DataType.SPO2` symbol
 * on either the exercise or measure surface — its public `DataType` set is
 * limited to HR-derived types in this version. The Android `Sensor`
 * framework also has no public `TYPE_SPO2` (SpO2 on Galaxy Watch is reached
 * via the Samsung Health SDK, which is out of scope per CONTRACTS.md).
 *
 * The collector therefore logs once and completes its [Flow] immediately —
 * the same shape as the original "device unsupported" path. App-shell and
 * `core-networking` see no `SensorEvent.BloodOxygen` events; the FCAF v2
 * `upBO` frame simply will not be sent.
 *
 * If a future bump of `health-services` or a Samsung-specific source makes
 * SpO2 available, replace this implementation.
 */
class Spo2Collector : SensorCollector {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override fun start(scope: CoroutineScope): Flow<SensorEvent> = flow {
        Log.i(
            TAG,
            "SpO2 unavailable in health-services-client:1.1.0-alpha04; " +
                "Spo2Collector is a no-op for this build"
        )
    }

    override suspend fun stop() = Unit

    companion object {
        private const val TAG = "Spo2Collector"
    }
}
