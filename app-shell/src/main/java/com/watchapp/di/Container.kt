package com.watchapp.di

import com.watchapp.contracts.SensorCollector
import com.watchapp.contracts.Streamer
import com.watchapp.identity.DeviceIdGenerator
import com.watchapp.settings.SettingsStore

/**
 * Manual DI: a flat holder for the process-wide singletons.
 *
 * Constructed exactly once in [com.watchapp.App.onCreate]. No reflection,
 * no annotations, no service-locator pattern — the [com.watchapp.App]
 * does the wiring graph by hand and hands the result to this container.
 */
class Container(
    val deviceIdGenerator: DeviceIdGenerator,
    val settingsStore: SettingsStore,
    val collector: SensorCollector,
    val streamer: Streamer,
)
