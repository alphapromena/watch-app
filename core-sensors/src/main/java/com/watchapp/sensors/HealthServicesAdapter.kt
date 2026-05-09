package com.watchapp.sensors

import android.content.Context
import android.util.Log
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.WarmUpConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the Wear OS Health Services connection and the WALKING [ExerciseClient]
 * lifecycle that powers continuous heart rate (and, when the device supports
 * them as exercise samples, SpO2 / skin temperature).
 *
 * **Why an exercise session?** [ExerciseClient] is the only public Health
 * Services API that delivers continuous HR with the screen off. Passive
 * monitoring is too laggy for live streaming and [MeasureClient] cannot run
 * in the background. WALKING is the lightest [ExerciseType] that still
 * guarantees ~1 Hz HR delivery on Galaxy Watch.
 *
 * The exercise lifecycle is reference-counted via [acquireExercise] and
 * [releaseExercise] so multiple collectors (HR + skin temp) can share one
 * session without tearing it down between subscribers.
 */
class HealthServicesAdapter(context: Context) {

    private val client = HealthServices.getClient(context.applicationContext)
    private val exerciseClient: ExerciseClient = client.exerciseClient

    /** Exposed for collectors that take spot readings (e.g. SpO2). */
    val measureClient: MeasureClient = client.measureClient

    private val mutex = Mutex()
    private var subscribers = 0
    private var enabledDataTypes: Set<DataType<*, *>> = emptySet()

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

    private val requestedDataTypes: Set<DataType<*, *>> = setOf(
        DataType.HEART_RATE_BPM,
        DataType.LOCATION,
        DataType.SPO2,
        DataType.SKIN_TEMPERATURE
    )

    /** True iff Health Services confirmed [type] is supported by the WALKING profile. */
    fun isExerciseDataTypeEnabled(type: DataType<*, *>): Boolean =
        enabledDataTypes.contains(type)

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

    /** Pause exercise data flow without ending the session (used around SpO2 spot reads). */
    suspend fun pauseExercise() {
        runCatching { exerciseClient.pauseExerciseAsync().await() }
            .onFailure { Log.w(TAG, "pauseExercise failed (likely already paused)", it) }
    }

    /** Resume exercise data flow after a [pauseExercise]. */
    suspend fun resumeExercise() {
        runCatching { exerciseClient.resumeExerciseAsync().await() }
            .onFailure { Log.w(TAG, "resumeExercise failed (likely not paused)", it) }
    }

    /** Returns the set of WALKING-supported sample data types. */
    suspend fun supportedExerciseDataTypes(): Set<DataType<*, *>> {
        val caps = exerciseClient.getCapabilitiesAsync().await()
        return caps.getExerciseTypeCapabilities(ExerciseType.WALKING).supportedDataTypes
    }

    /** Returns true if [type] can be measured one-shot via [MeasureClient]. */
    suspend fun isMeasureSupported(type: DataType<*, *>): Boolean {
        val caps = measureClient.getCapabilitiesAsync().await()
        return caps.supportedDataTypesMeasure.contains(type)
    }

    private suspend fun startExerciseInternal() {
        val supported = supportedExerciseDataTypes()
        enabledDataTypes = requestedDataTypes.intersect(supported)
        if (enabledDataTypes.isEmpty()) {
            Log.w(TAG, "No requested data types are supported by WALKING on this device")
            return
        }

        // Warm up the sensors before binding the exercise — this gives the HR
        // sensor a head start so the first samples after start are usable.
        runCatching {
            exerciseClient.prepareExerciseAsync(
                WarmUpConfig(ExerciseType.WALKING, enabledDataTypes)
            ).await()
        }.onFailure { Log.w(TAG, "prepareExercise failed (continuing)", it) }

        exerciseClient.setUpdateCallback(callback)

        // GPS is intentionally OFF: LocationCollector talks to LocationManager
        // directly so the app can detect "no fix" (Health Services hides that).
        // Auto-pause is OFF: we want continuous HR even when the user stands still.
        val config = ExerciseConfig.builder(ExerciseType.WALKING)
            .setDataTypes(enabledDataTypes)
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .build()
        exerciseClient.startExerciseAsync(config).await()
    }

    private suspend fun endExerciseInternal() {
        runCatching { exerciseClient.endExerciseAsync().await() }
        runCatching { exerciseClient.clearUpdateCallbackAsync(callback).await() }
        enabledDataTypes = emptySet()
    }

    companion object {
        const val TAG = "HealthServicesAdapter"
    }
}
