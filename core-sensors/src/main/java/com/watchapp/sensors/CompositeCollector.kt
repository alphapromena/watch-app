package com.watchapp.sensors

import com.watchapp.contracts.SensorCollector
import com.watchapp.contracts.SensorEvent
import com.watchapp.sensors.collectors.HeartRateCollector
import com.watchapp.sensors.collectors.LocationCollector
import com.watchapp.sensors.collectors.Spo2Collector
import com.watchapp.sensors.collectors.TemperatureCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * The single [SensorCollector] that app-shell consumes.
 *
 * Merges the four underlying collector Flows (HR, location, SpO2, skin
 * temperature) into one [SensorEvent] stream. Each underlying collector
 * manages its own backing resources; this class only forwards events and
 * mirrors the running state.
 *
 * Note: [Spo2Collector] is a no-op in the current build (see its KDoc),
 * so the merged stream effectively covers HR + location + temperature.
 */
class CompositeCollector(
    private val collectors: List<SensorCollector>
) : SensorCollector {

    constructor(
        heartRate: HeartRateCollector,
        location: LocationCollector,
        spo2: Spo2Collector,
        temperature: TemperatureCollector
    ) : this(listOf(heartRate, location, spo2, temperature))

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override fun start(scope: CoroutineScope): Flow<SensorEvent> {
        val merged = merge(*collectors.map { it.start(scope) }.toTypedArray())
        return merged
            .onStart { _isRunning.value = true }
            .onCompletion { _isRunning.value = false }
    }

    override suspend fun stop() {
        collectors.forEach { it.stop() }
        _isRunning.value = false
    }
}
