package com.watchapp.di

import com.watchapp.contracts.SensorEvent
import com.watchapp.contracts.Streamer
import com.watchapp.contracts.StreamerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Placeholder [Streamer] used while :core-networking is still landing TcpStreamer.
 * Drops every event on the floor and stays [StreamerState.DISCONNECTED].
 *
 * TODO(stream-1): delete this file and wire TcpStreamer in [com.watchapp.App].
 */
internal class NoOpStreamer : Streamer {
    private val _state = MutableStateFlow(StreamerState.DISCONNECTED)
    override val state: StateFlow<StreamerState> = _state.asStateFlow()

    override suspend fun connect() = Unit
    override fun enqueue(event: SensorEvent) = Unit
    override fun sendHeartbeat() = Unit
    override suspend fun disconnect() = Unit
}
