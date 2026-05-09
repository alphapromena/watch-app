package com.watchapp.contracts

/**
 * A discrete reading produced by [SensorCollector]. Each variant maps 1:1 to an
 * FCAF v2 uplink frame in [Streamer]. See CONTRACTS.md for the wire format.
 */
sealed class SensorEvent {
    abstract val timestampMs: Long

    data class HeartRate(
        val bpm: Int,
        override val timestampMs: Long
    ) : SensorEvent()

    data class BloodOxygen(
        val percent: Int,
        override val timestampMs: Long
    ) : SensorEvent()

    data class BodyTemperature(
        val celsius: Double,
        override val timestampMs: Long
    ) : SensorEvent()

    data class Location(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val speedMps: Float?,
        override val timestampMs: Long
    ) : SensorEvent()

    /** Emitted when a location attempt produced no GPS fix; server falls back to LBS. */
    data class NoGpsFix(
        override val timestampMs: Long
    ) : SensorEvent()
}
