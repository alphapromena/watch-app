package com.watchapp.networking

import com.watchapp.contracts.DeviceConfig
import com.watchapp.contracts.SensorEvent
import com.watchapp.networking.codec.ProtocolBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolBuilderTest {

    private val cfg = DeviceConfig(
        imei = "900000000000123",
        serverHost = "stream.example.com",
        serverPort = 5088,
        model = "GalaxyWatchUltra",
        firmwareVersion = "1.0.0",
    )
    private val builder = ProtocolBuilder(deviceConfig = { cfg })

    @Test
    fun `upLogin matches CONTRACTS shape`() {
        assertEquals(
            """{"type":"upLogin","imei":"900000000000123","model":"GalaxyWatchUltra","fw":"1.0.0","ref":"w:login"}""",
            builder.upLogin(),
        )
    }

    @Test
    fun `upHeartbeat matches CONTRACTS shape`() {
        assertEquals(
            """{"type":"upHeartbeat","imei":"900000000000123","ref":"w:hb"}""",
            builder.upHeartbeat(),
        )
    }

    @Test
    fun `fromEvent of HeartRate produces upHeartRate with ts in seconds`() {
        val event = SensorEvent.HeartRate(bpm = 78, timestampMs = 1746789012345L)
        assertEquals(
            """{"type":"upHeartRate","imei":"900000000000123","value":78,"ts":1746789012,"ref":"w:hr"}""",
            builder.fromEvent(event),
        )
    }

    @Test
    fun `fromEvent of BloodOxygen produces upBO`() {
        val event = SensorEvent.BloodOxygen(percent = 98, timestampMs = 1746789012000L)
        assertEquals(
            """{"type":"upBO","imei":"900000000000123","value":98,"ts":1746789012,"ref":"w:bo"}""",
            builder.fromEvent(event),
        )
    }

    @Test
    fun `fromEvent of BodyTemperature rounds celsius to one decimal`() {
        val event = SensorEvent.BodyTemperature(celsius = 36.74999, timestampMs = 1746789012000L)
        assertEquals(
            """{"type":"upBodyTemperature","imei":"900000000000123","value":36.7,"ts":1746789012,"ref":"w:temp"}""",
            builder.fromEvent(event),
        )
    }

    @Test
    fun `fromEvent of BodyTemperature rounds half-up`() {
        val event = SensorEvent.BodyTemperature(celsius = 36.75, timestampMs = 1746789012000L)
        assertEquals(
            """{"type":"upBodyTemperature","imei":"900000000000123","value":36.8,"ts":1746789012,"ref":"w:temp"}""",
            builder.fromEvent(event),
        )
    }

    @Test
    fun `fromEvent of Location with full fix populates gps`() {
        val event = SensorEvent.Location(
            latitude = 32.0123,
            longitude = 35.7456,
            accuracyMeters = 8.5f,
            speedMps = 0.5f,
            timestampMs = 1746789012000L,
        )
        assertEquals(
            """{"type":"upLocation","imei":"900000000000123","ts":1746789012,"gps":{"lat":32.0123,"lon":35.7456,"acc":8.5,"speed":0.5},"ref":"w:loc"}""",
            builder.fromEvent(event),
        )
    }

    @Test
    fun `fromEvent of NoGpsFix yields empty gps object`() {
        val event = SensorEvent.NoGpsFix(timestampMs = 1746789012000L)
        assertEquals(
            """{"type":"upLocation","imei":"900000000000123","ts":1746789012,"gps":{},"ref":"w:loc"}""",
            builder.fromEvent(event),
        )
    }

    @Test
    fun `fromEvent of Location with null accuracy and speed omits those keys`() {
        val event = SensorEvent.Location(
            latitude = 32.0,
            longitude = 35.0,
            accuracyMeters = null,
            speedMps = null,
            timestampMs = 1746789012000L,
        )
        assertEquals(
            """{"type":"upLocation","imei":"900000000000123","ts":1746789012,"gps":{"lat":32.0,"lon":35.0},"ref":"w:loc"}""",
            builder.fromEvent(event),
        )
    }

    @Test
    fun `ts is truncated to whole seconds, not rounded`() {
        // 999 ms over the whole second must NOT bump ts up.
        val event = SensorEvent.HeartRate(bpm = 60, timestampMs = 1746789012999L)
        val payload = builder.fromEvent(event)
        assertTrue("expected ts=1746789012 in $payload", payload.contains("\"ts\":1746789012,"))
    }

    @Test
    fun `DeviceConfig provider is consulted on every build`() {
        var current = cfg.copy(imei = "900000000000111")
        val b = ProtocolBuilder(deviceConfig = { current })
        val first = b.upHeartbeat()
        current = current.copy(imei = "900000000000222")
        val second = b.upHeartbeat()
        assertTrue(first.contains("900000000000111"))
        assertTrue(second.contains("900000000000222"))
    }
}
