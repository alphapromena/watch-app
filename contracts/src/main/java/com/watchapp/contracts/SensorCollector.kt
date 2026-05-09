package com.watchapp.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Source of [SensorEvent]s. Implemented by core-sensors and consumed by app-shell's
 * foreground service, which forwards every emitted event into [Streamer.enqueue].
 */
interface SensorCollector {
    /**
     * Begins emitting events on the returned cold [Flow]. The provided [scope] bounds
     * the lifetime of any internal collection; cancelling the scope stops collection.
     */
    fun start(scope: CoroutineScope): Flow<SensorEvent>

    /** Halts all active sensor sources. Idempotent. */
    suspend fun stop()

    val isRunning: StateFlow<Boolean>
}
