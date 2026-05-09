package com.watchapp.networking.codec

import com.watchapp.contracts.DeviceConfig
import com.watchapp.contracts.SensorEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Builds FCAF v2 uplink JSON payloads from [SensorEvent]s and heartbeat triggers.
 *
 * The output is a UTF-8 JSON string that the caller passes through [FrameCodec.encode]
 * to obtain a wire frame. This class owns the protocol/data-class layer; it is the
 * single source of truth for field names, defaults, and `ref` values.
 *
 * @param deviceConfig provider, called per build so app-shell can swap IMEI / host /
 *   port without rebuilding the streamer. Read CONTRACTS.md and INTEGRATION_NOTES.md
 *   for the rationale.
 * @param clock returns wall-clock millis; injected for deterministic tests. Not used
 *   on most paths because [SensorEvent.timestampMs] already carries the event time;
 *   reserved for future frame types that the watch generates without an event.
 */
class ProtocolBuilder(
    private val deviceConfig: () -> DeviceConfig,
    @Suppress("unused") private val clock: () -> Long = System::currentTimeMillis,
) {

    private val json = Json {
        // Default-valued fields (`type`, `ref`) MUST be on the wire.
        encodeDefaults = true
        // Null GPS components on a [SensorEvent.NoGpsFix] must serialize as `"gps": {}`.
        explicitNulls = false
    }

    /** Builds the `upLogin` frame sent once per TCP connection, before any data frames. */
    fun upLogin(): String {
        val cfg = deviceConfig()
        return json.encodeToString(
            UpLogin(imei = cfg.imei, model = cfg.model, fw = cfg.firmwareVersion)
        )
    }

    /** Builds the periodic `upHeartbeat` frame. */
    fun upHeartbeat(): String {
        val cfg = deviceConfig()
        return json.encodeToString(UpHeartbeat(imei = cfg.imei))
    }

    /**
     * Builds the appropriate uplink frame for a [SensorEvent].
     * Returns the JSON payload as a UTF-8 string.
     */
    fun fromEvent(event: SensorEvent): String {
        val imei = deviceConfig().imei
        val ts = event.timestampMs / MS_PER_SECOND
        return when (event) {
            is SensorEvent.HeartRate -> json.encodeToString(
                UpHeartRate(imei = imei, value = event.bpm, ts = ts)
            )
            is SensorEvent.BloodOxygen -> json.encodeToString(
                UpBO(imei = imei, value = event.percent, ts = ts)
            )
            is SensorEvent.BodyTemperature -> json.encodeToString(
                UpBodyTemperature(
                    imei = imei,
                    value = roundTo1Decimal(event.celsius),
                    ts = ts,
                )
            )
            is SensorEvent.Location -> json.encodeToString(
                UpLocation(
                    imei = imei,
                    ts = ts,
                    gps = GpsFix(
                        lat = event.latitude,
                        lon = event.longitude,
                        acc = event.accuracyMeters?.toDouble(),
                        speed = event.speedMps?.toDouble(),
                    )
                )
            )
            is SensorEvent.NoGpsFix -> json.encodeToString(
                UpLocation(imei = imei, ts = ts, gps = GpsFix())
            )
        }
    }

    private fun roundTo1Decimal(d: Double): Double = Math.round(d * 10.0) / 10.0

    private companion object {
        const val MS_PER_SECOND: Long = 1000L
    }
}

// region wire-format data classes — internal: they are the protocol's private shape.

@Serializable
internal data class UpLogin(
    val type: String = "upLogin",
    val imei: String,
    val model: String,
    val fw: String,
    val ref: String = "w:login",
)

@Serializable
internal data class UpHeartbeat(
    val type: String = "upHeartbeat",
    val imei: String,
    val ref: String = "w:hb",
)

@Serializable
internal data class UpHeartRate(
    val type: String = "upHeartRate",
    val imei: String,
    val value: Int,
    val ts: Long,
    val ref: String = "w:hr",
)

@Serializable
internal data class UpBO(
    val type: String = "upBO",
    val imei: String,
    val value: Int,
    val ts: Long,
    val ref: String = "w:bo",
)

@Serializable
internal data class UpBodyTemperature(
    val type: String = "upBodyTemperature",
    val imei: String,
    val value: Double,
    val ts: Long,
    val ref: String = "w:temp",
)

@Serializable
internal data class UpLocation(
    val type: String = "upLocation",
    val imei: String,
    val ts: Long,
    val gps: GpsFix,
    val ref: String = "w:loc",
)

@Serializable
internal data class GpsFix(
    val lat: Double? = null,
    val lon: Double? = null,
    val acc: Double? = null,
    val speed: Double? = null,
)

// endregion
