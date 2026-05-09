package com.watchapp

import android.app.Application
import android.content.Context
import com.watchapp.contracts.SensorCollector
import com.watchapp.contracts.Streamer
import com.watchapp.di.Container
import com.watchapp.di.NoOpStreamer
import com.watchapp.identity.DeviceIdGenerator
import com.watchapp.sensors.CompositeCollector
import com.watchapp.sensors.HealthServicesAdapter
import com.watchapp.sensors.collectors.HeartRateCollector
import com.watchapp.sensors.collectors.LocationCollector
import com.watchapp.sensors.collectors.Spo2Collector
import com.watchapp.sensors.collectors.TemperatureCollector
import com.watchapp.settings.SettingsStore

/**
 * Process entry point. Builds the [Container] once and exposes it via
 * the [container] extension on any [Context].
 */
class App : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()

        val deviceIdGenerator = DeviceIdGenerator.create(this)
        val imei = deviceIdGenerator.getOrCreate()
        val settingsStore = SettingsStore.create(this, imei)

        // Sensor pipeline (core-sensors).
        val collector: SensorCollector = buildCollector()

        // Streamer impl is in :core-networking. Until TcpStreamer lands we
        // run the rest of the pipeline against a no-op sink so the app
        // installs and the UI / service / collectors can be exercised on
        // hardware. See di/NoOpStreamer.kt.
        val streamer: Streamer = NoOpStreamer()

        container = Container(
            deviceIdGenerator = deviceIdGenerator,
            settingsStore = settingsStore,
            collector = collector,
            streamer = streamer,
        )
    }

    private fun buildCollector(): SensorCollector {
        val adapter = HealthServicesAdapter(this)
        return CompositeCollector(
            heartRate = HeartRateCollector(adapter),
            location = LocationCollector(this),
            spo2 = Spo2Collector(adapter),
            temperature = TemperatureCollector(adapter),
        )
    }
}

/** Sugar so any Context (Activity, Service) can reach the singletons. */
val Context.container: Container
    get() = (applicationContext as App).container
