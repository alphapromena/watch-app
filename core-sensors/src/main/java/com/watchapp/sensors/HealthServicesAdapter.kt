package com.watchapp.sensors

import android.content.Context
import android.util.Log
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the Wear OS Health Services connection and the WALKING [ExerciseClient]
 * lifecycle that powers continuous heart rate.
 *
 * **Why an exercise session?** [ExerciseClient] is the only public Health
 * Services API that delivers continuous HR with the screen off. Passive
 * monitoring is too laggy for live streaming and `MeasureClient` cannot run
 * in the background. WALKING is the lightest [ExerciseType] that still
 * guarantees ~1 Hz HR delivery on Galaxy Watch.
 *
 * Only `HEART_RATE_BPM` is enabled. The other data types from the original
 * brief are not in the public surface of `health-services-client:1.1.0-alpha04`:
 * `SPO2` and `SKIN_TEMPERATURE` aren't there at all, and `LOCATION` is
 * served by [com.watchapp.sensors.collectors.LocationCollector] going
 * straight to `LocationManager` so the app can detect "no fix".
 *
 * The exercise lifecycle is reference-counted via [acquireExercise] and
 * [releaseExercise] so that future collectors which need exercise samples
 * can share a single session without tearing it down between subscribers.
 */
class HealthServicesAdapter(context: Context) {

    private val client = HealthServices.getClient(context.applicationContext)
    private val exerciseClient: ExerciseClient = client.exerciseClient

    private val mutex = Mutex()
    private var subscribers = 0

    private val _exerciseUpdates =
        MutableSharedFlow<ExerciseUpdate>(extraBufferCapacity = 64)

    /** Hot stream of exercise updates while the session is active. */
    val exerciseUpdates: SharedFlow<ExerciseUpdate> = _exerciseUpdates.asSharedFlow()

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            _exerciseUpdates.tryEmit(update)
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onAvailabilityChanged(
            dataType: DataType<*, *>,
            availability: Availability
        ) = Unit

        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Exercise update callback registration failed", throwable)
        }
    }

    /** Acquire a reference on the WALKING exercise. Starts it on first acquire. */
    suspend fun acquireExercise() {
        mutex.withLock {
            subscribers++
            if (subscribers == 1) {
                runCatching { startExerciseInternal() }
                    .onFailure { t ->
                        Log.e(TAG, "Failed to start WALKING exercise", t)
                        subscribers = 0
                    }
            }
        }
    }

    /** Release a reference. Ends the exercise once the last consumer releases. */
    suspend fun releaseExercise() {
        mutex.withLock {
            if (subscribers <= 0) return@withLock
            subscribers--
            if (subscribers == 0) {
                runCatching { endExerciseInternal() }
                    .onFailure { Log.w(TAG, "Failed to end exercise cleanly", it) }
            }
        }
    }

    private suspend fun startExerciseInternal() {
        // Register the callback before starting so we don't miss the first samples.
        exerciseClient.setUpdateCallback(callback)

        // GPS is intentionally OFF: LocationCollector talks to LocationManager
        // directly so the app can detect "no fix" (Health Services hides that).
        // Auto-pause is OFF: we want continuous HR even when the user stands still.
        val config = ExerciseConfig.builder(ExerciseType.WALKING)
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .build()
        exerciseClient.startExerciseAsync(config).await()
    }

    private suspend fun endExerciseInternal() {
        runCatching { exerciseClient.endExerciseAsync().await() }
        runCatching { exerciseClient.clearUpdateCallbackAsync(callback).await() }
    }

    companion object {
        const val TAG = "HealthServicesAdapter"
    }
}
