package com.watchapp

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import com.watchapp.contracts.SensorCollector
import com.watchapp.di.Container
import com.watchapp.identity.DeviceIdGenerator
import com.watchapp.networking.tcp.TcpStreamer
import com.watchapp.networking.tcp.create
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
        val collector: SensorCollector = buildCollector()
        val streamer = buildStreamer(settingsStore)

        container = Container(
            deviceIdGenerator = deviceIdGenerator,
            settingsStore = settingsStore,
            collector = collector,
            streamer = streamer,
        )

        // Wake the streamer out of slow-mode backoff when connectivity returns
        // (INTEGRATION_NOTES item #3). Registered for the process lifetime —
        // no unregister needed.
        registerNetworkCallback(streamer)
    }

    private fun buildCollector(): SensorCollector {
        val adapter = HealthServicesAdapter(this)
        return CompositeCollector(
            heartRate = HeartRateCollector(adapter),
            location = LocationCollector(this),
            spo2 = Spo2Collector(),
            temperature = TemperatureCollector(applicationContext),
        )
    }

    private fun buildStreamer(settingsStore: SettingsStore): TcpStreamer =
        // Provider, not a snapshot — the user can re-point host/port from the
        // debug screen and the next reconnect picks up the new config
        // (INTEGRATION_NOTES item #2). The factory hides Room from app-shell
        // (INTEGRATION_NOTES item #16).
        TcpStreamer.create(this, settingsStore::deviceConfig)

    private fun registerNetworkCallback(streamer: TcpStreamer) {
        val cm = getSystemService<ConnectivityManager>() ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = streamer.onNetworkAvailable()
                override fun onLost(network: Network) = streamer.onNetworkLost()
            },
        )
    }
}

/** Sugar so any Context (Activity, Service) can reach the singletons. */
val Context.container: Container
    get() = (applicationContext as App).container
