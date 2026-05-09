# Watch App — Inter-Module Contracts (DO NOT MODIFY without all 3 streams agreeing)

## Project context
A Wear OS app for Samsung Galaxy Watch Ultra (2025) that streams health and
GPS data over raw TCP to an existing Railway server on port 5088, using the
**FCAF v2 protocol** already running in production for Chinese HW20 PRO
trackers. The Galaxy Watch is offered as a SECOND device option alongside
the Chinese watches — the server stays unchanged. The watch is identified by
a synthetic 15-digit IMEI prefixed with `9` (unused by GSMA), and the LOGIN
payload includes a `model` field for server-side disambiguation.

## Shared module boundary
- `core-networking/`  — TCP, framing, reconnect, buffering
- `core-sensors/`     — Health Services, GPS, sensor collection
- `app-shell/`        — Foreground service, UI, permissions, glue, DI

Any code outside its own module is read-only. Cross-module communication
goes through the public interfaces below — nothing else.

## Wire protocol — FCAF v2

Frame layout (big-endian):
```
[0xFC][0xAF][uint16 length][JSON payload UTF-8]
```
- `length` = byte length of the JSON payload only.
- One frame per message; no fragmentation.

### Frame types this app sends (uplink)

#### upLogin (sent once per TCP connect, before anything else)
```json
{
  "type": "upLogin",
  "imei": "900000000000123",
  "model": "GalaxyWatchUltra",
  "fw": "1.0.0",
  "ref": "w:login"
}
```

#### upHeartbeat (every 30s when no other data is flowing)
```json
{
  "type": "upHeartbeat",
  "imei": "900000000000123",
  "ref": "w:hb"
}
```

#### upHeartRate
```json
{
  "type": "upHeartRate",
  "imei": "900000000000123",
  "value": 78,
  "ts": 1746789012,
  "ref": "w:hr"
}
```

#### upBO (blood oxygen / SpO2, 0-100)
```json
{
  "type": "upBO",
  "imei": "900000000000123",
  "value": 98,
  "ts": 1746789012,
  "ref": "w:bo"
}
```

#### upBodyTemperature (Celsius, 1 decimal)
```json
{
  "type": "upBodyTemperature",
  "imei": "900000000000123",
  "value": 36.7,
  "ts": 1746789012,
  "ref": "w:temp"
}
```

#### upLocation (GPS or LBS-deferred)
```json
{
  "type": "upLocation",
  "imei": "900000000000123",
  "ts": 1746789012,
  "gps": { "lat": 32.0123, "lon": 35.7456, "acc": 8.5, "speed": 0.5 },
  "ref": "w:loc"
}
```
If no GPS fix: omit `gps`, send empty object `"gps": {}`. The server
already does LBS fallback — the watch does NOT need to provide cell info.

### Frame types this app receives (downlink) — minimum to handle
- `downAck` — server acknowledgment, log only.
- `downSetConfig` — server pushes config (`measureFreqSec`, `locFreqSec`).
  Update local config, log, ack-back. Out of scope for v1; just log.

## Public interfaces between modules (Kotlin)

Located in `app-shell/src/main/java/com/watchapp/contracts/` and re-exported.
ALL THREE STREAMS depend on these. Do not add or rename without consensus.

```kotlin
// SensorEvent — what core-sensors emits
sealed class SensorEvent {
    abstract val timestampMs: Long
    
    data class HeartRate(val bpm: Int, override val timestampMs: Long) : SensorEvent()
    data class BloodOxygen(val percent: Int, override val timestampMs: Long) : SensorEvent()
    data class BodyTemperature(val celsius: Double, override val timestampMs: Long) : SensorEvent()
    data class Location(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val speedMps: Float?,
        override val timestampMs: Long
    ) : SensorEvent()
    data class NoGpsFix(override val timestampMs: Long) : SensorEvent()
}

// SensorCollector — what core-sensors exposes
interface SensorCollector {
    fun start(scope: CoroutineScope): Flow<SensorEvent>
    suspend fun stop()
    val isRunning: StateFlow<Boolean>
}

// Streamer — what core-networking exposes
interface Streamer {
    /** Idempotent. Connects, sends upLogin, then is ready for events. */
    suspend fun connect()
    /** Enqueues an event to be sent. Never blocks. Buffers if disconnected. */
    fun enqueue(event: SensorEvent)
    /** Sends a heartbeat frame (called by app-shell on a 30s timer). */
    fun sendHeartbeat()
    suspend fun disconnect()
    val state: StateFlow<StreamerState>
}

enum class StreamerState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

// DeviceConfig — provided by app-shell, read by both other modules
data class DeviceConfig(
    val imei: String,           // synthetic 15-digit, prefixed with '9'
    val serverHost: String,
    val serverPort: Int,
    val model: String = "GalaxyWatchUltra",
    val firmwareVersion: String = "1.0.0"
)
```

## Tech stack (locked)
- Kotlin 2.0+, Coroutines, Flow
- Jetpack Compose for Wear OS (UI in app-shell only)
- Health Services on Wear OS (`androidx.health:health-services-client`)
- Room (in core-networking for the offline buffer)
- OkHttp NOT used for TCP — plain `java.net.Socket` is fine and lighter
- Min SDK 33 (Wear OS 4 / Android 13)
- Target SDK 34 (Android 14) — keeps us off the Android 16 granular health
  permissions migration for v1
- Gradle Kotlin DSL
- No Hilt — manual DI via a simple `Container` class in app-shell

## Working agreements (apply to ALL streams)
- One concept per commit, scoped to its own module only.
- Don't touch files outside your module. If you think you must, write a
  note in `INTEGRATION_NOTES.md` instead and let app-shell pick it up.
- Don't add dependencies without writing the rationale in your commit.
- Reuse the FCAF v2 patterns from the existing server's protocol/v2 code
  conceptually (don't copy code — this is a fresh project).
- All public APIs documented with KDoc.
- Tests: focus on the codec (core-networking) and the synthetic IMEI
  generator (app-shell). Sensors are hard to unit test on JVM; that's OK.
