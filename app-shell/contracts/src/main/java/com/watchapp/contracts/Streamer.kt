package com.watchapp.contracts

import kotlinx.coroutines.flow.StateFlow

/**
 * Sink for [SensorEvent]s. Implemented by core-networking. Owns the TCP socket,
 * the FCAF v2 framing, the offline buffer, and reconnect/backoff.
 */
interface Streamer {
    /** Idempotent. Connects, sends `upLogin`, then is ready for events. */
    suspend fun connect()

    /** Enqueues an event to be sent. Never blocks. Buffers if disconnected. */
    fun enqueue(event: SensorEvent)

    /** Sends an `upHeartbeat` frame (called by app-shell on a 30s idle timer). */
    fun sendHeartbeat()

    suspend fun disconnect()

    val state: StateFlow<StreamerState>
}

enum class StreamerState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }
