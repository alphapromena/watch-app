# Integration Notes — core-networking → app-shell / core-sensors

This file is the coordination surface for cross-stream decisions that
`core-networking` cannot make on its own. Anything here is a request to,
or a heads-up for, the `app-shell` and `core-sensors` streams.

## 1. `:contracts` module — shared types live here, not in `:app-shell`

CONTRACTS.md states the contract types are located at
`app-shell/src/main/java/com/watchapp/contracts/`. A literal Gradle
dependency from `:core-networking` on `:app-shell` would be circular,
since `:app-shell` will depend on `:core-networking` for the `Streamer`
implementation.

**Resolution (agreed with user 2026-05-09):** The shared contract types
have been extracted into a dedicated, pure-Kotlin/JVM module
`:contracts`. Both `:core-networking` and `:app-shell` depend on it.

Files now living in `contracts/src/main/kotlin/com/watchapp/contracts/`:
- `SensorEvent.kt`
- `SensorCollector.kt`
- `Streamer.kt` (incl. `StreamerState`)
- `DeviceConfig.kt`

**Action for `app-shell` stream:** depend on `project(":contracts")`
and re-export / use the types directly. Do NOT redefine them under
`com.watchapp.contracts` inside `:app-shell` — drift will break the
interface. If you need to add a new public type, add it in `:contracts`
and update CONTRACTS.md (with both other stream owners' approval).

**Action for `core-sensors` stream:** same — depend on `:contracts`.

## 2. `DeviceConfig` provider, not a static instance

`Streamer.connect()` needs `DeviceConfig` to build the `upLogin` frame.
The streamer takes a `() -> DeviceConfig` provider in its constructor
rather than a single immutable `DeviceConfig`. Reasons:
- IMEI may not be known until app-shell has read / generated the
  synthetic 15-digit value.
- Server host / port may be re-pointed at runtime (e.g. for staging).

**Action for `app-shell` stream:** in your `Container`, pass
`{ deviceConfigStateFlow.value }` (or similar) into `TcpStreamer`.
Re-`connect()` is required for changes to take effect — we do not hot-
swap the connection.

## 3. `ConnectivityManager` lives in `app-shell`, signals into `Streamer`

To keep `:core-networking` free of `android.content.Context`, the
streamer exposes two no-arg methods:

```kotlin
fun onNetworkAvailable()
fun onNetworkLost()
```

**Action for `app-shell` stream:** register a
`ConnectivityManager.NetworkCallback` and forward the signals.
The streamer uses these to (a) break out of the >5-minute slow-mode
backoff and immediately retry, and (b) tag the state as RECONNECTING
when network goes away.

## 4. `ref` field values

I'm using the exact strings from CONTRACTS.md, with no per-event ID:
`w:login`, `w:hb`, `w:hr`, `w:bo`, `w:temp`, `w:loc`. If the server
needs unique correlation IDs, raise it before v1 ships.

## 5. `ts` is epoch SECONDS, not millis

`SensorEvent.timestampMs` is millis (per CONTRACTS.md type definitions).
The wire format `ts` is epoch seconds (per CONTRACTS.md examples).
ProtocolBuilder divides by 1000 — confirm with server team this matches
existing FCAF v2 expectations from the Chinese trackers.

## 6. Heartbeat scheduling

Per CONTRACTS.md and the per-stream prompt, app-shell drives a 30s
timer and calls `Streamer.sendHeartbeat()`. The streamer does not own
the timer. Open question: should heartbeats be SUPPRESSED when other
data is flowing (the contract phrasing says "every 30s when no other
data is flowing")? Current behavior: I send whatever you ask me to
send. If suppression is needed, suppress in app-shell.

## 7. Offline buffer cap

10,000 rows. Oldest dropped when full. Confirm this is acceptable —
roughly 10k × ~150 bytes = ~1.5 MB on disk worst case.

## 8. Commit boundaries

`core-networking` will land as one commit per concept:
1. scaffold (root + `:contracts` + module gradle)
2. codec (FCAF v2 frame encode/decode)
3. protocol (SensorEvent → JSON shapes)
4. buffer (Room offline queue)
5. reconnect (ReconnectPolicy)
6. tcp (TcpStreamer impl glueing the above)

## 9. Gradle wrapper

I have not committed a Gradle wrapper. To bootstrap, run from the
project root:

```
gradle wrapper --gradle-version 8.10
```

Either stream (or you, the user) can do this once.

---

# Notes from `core-sensors` (Stream 2)

## 10. Permissions app-shell must declare in the merged manifest

| Permission | Why core-sensors needs it |
|---|---|
| `android.permission.BODY_SENSORS` | HR / SpO2 / skin temp via Health Services |
| `android.permission.BODY_SENSORS_BACKGROUND` | Continuous HR with screen off (Wear OS 4 / API 33+) |
| `android.permission.ACCESS_FINE_LOCATION` | `LocationManager.GPS_PROVIDER` |
| `android.permission.ACCESS_BACKGROUND_LOCATION` | GPS while service is foreground but UI is not visible |
| `android.permission.ACTIVITY_RECOGNITION` | Required by `ExerciseClient` |

Plus `<uses-feature android:name="android.hardware.location.gps" android:required="false" />`
so the watch model gates correctly without making the app uninstallable on
non-GPS Wear devices.

The foreground-service type (`health|location`) is on app-shell per the brief.
`core-sensors` does not start a service, hold wake locks, or open sockets.

## 11. `kotlinx-coroutines-guava` declared inline in `:core-sensors`

`androidx.health` returns `ListenableFuture`s; `await()` requires
`org.jetbrains.kotlinx:kotlinx-coroutines-guava`. I added it inline in
`core-sensors/build.gradle.kts` (version `1.9.0`, matching the `coroutines`
catalog version) rather than editing `gradle/libs.versions.toml`, so my
dependency change stays inside my module. Promote to the catalog if
`:core-networking` ever needs it.

## 12. Open coordination questions for app-shell

1. **SpO2 pause/resume of exercise.** `Spo2Collector` calls `pauseExercise()`
   / `resumeExercise()` around each `MeasureClient` spot read, per the brief
   ("temporarily pause exercise data flow if Health Services requires it").
   On Galaxy Watch Ultra this is likely unnecessary and costs a brief HR
   gap every 60 s. If you can confirm concurrent measurement is supported,
   I'll flip a flag in `HealthServicesAdapter` to skip the pause.
2. **Wall-clock timestamps.** Collectors stamp every event with
   `System.currentTimeMillis()` at emit time, not by converting Health
   Services' `timeDurationFromBoot` to wall-clock. Skew is sub-second,
   well within FCAF v2's second-resolution `ts`. Flag if higher fidelity
   is needed.
3. **Permission denial mid-session.** Collectors that hit a
   `SecurityException` or a pre-flight `checkSelfPermission` failure log
   once and complete their Flow silently — they do not retry or surface
   a typed error. I assume your permission flow guarantees grants before
   construction; if a user revokes mid-session, collectors will go quiet.
4. **`MeasureClient` foreground requirement.** SpO2 spot reads assume
   the foreground service is alive. If suspended, Health Services will
   reject the measurement; `Spo2Collector` logs and skips that cycle.
5. **Foreground-service type.** Wear OS 4 (API 33+) requires
   `dataSync|health|location` on the service tag for the combination of
   live HR + GPS streaming. App-shell owns this declaration.
