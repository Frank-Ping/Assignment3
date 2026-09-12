package com.example.wear.presentation.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.example.wear.presentation.data.AccelerometerRecord
import com.example.wear.presentation.data.SensorStatus
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import com.example.wear.presentation.data.HeartRateRecord
import com.example.wear.presentation.data.HeartRateSourceType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class SensorManagerAccelerometerSource(
    context: Context
) : AccelerometerSource, SensorEventListener {

    private val sensorManager =
        context.applicationContext
            .getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isCollecting = false
    private var sequence = 0L

    private var recordCallback: ((AccelerometerRecord) -> Unit)? = null
    private var statusCallback: ((SensorStatus) -> Unit)? = null

    private var currentStatus = SensorStatus.NOT_STARTED

    override fun start(
        onRecord: (AccelerometerRecord) -> Unit,
        onStatusChanged: (SensorStatus) -> Unit
    ) {
        recordCallback = onRecord
        statusCallback = onStatusChanged

        // Avoid registering the listener more than once.
        if (isCollecting) {
            onStatusChanged(currentStatus)
            return
        }

        val sensor = accelerometer
        if (sensor == null) {
            updateStatus(SensorStatus.UNAVAILABLE)
            return
        }

        updateStatus(SensorStatus.WAITING_FOR_DATA)

        try {
            isCollecting = sensorManager.registerListener(
                this,
                sensor,
                SAMPLING_PERIOD_US,
                mainHandler
            )

            if (!isCollecting) {
                updateStatus(SensorStatus.DATA_ERROR)
            }
        } catch (_: SecurityException) {
            isCollecting = false
            updateStatus(SensorStatus.PERMISSION_REQUIRED)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isCollecting ||
            event.sensor.type != Sensor.TYPE_ACCELEROMETER
        ) {
            return
        }

        if (event.values.size < 3) {
            updateStatus(SensorStatus.DATA_ERROR)
            return
        }

        // Do not save the array that the system may reuse.
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            updateStatus(SensorStatus.DATA_ERROR)
            return
        }

        sequence += 1

        val record = AccelerometerRecord(
            x = x,
            y = y,
            z = z,
            timestampNanos = event.timestamp,
            sequence = sequence
        )

        updateStatus(SensorStatus.ACTIVE)
        recordCallback?.invoke(record)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

    }

    override fun stop() {
        isCollecting = false
        sensorManager.unregisterListener(this)

        updateStatus(SensorStatus.STOPPED)

        recordCallback = null
        statusCallback = null
    }

    private fun updateStatus(status: SensorStatus) {
        // Notify the observer only when the status changes.
        if (currentStatus == status) return

        currentStatus = status
        statusCallback?.invoke(status)
    }

    companion object {
        // Request one sample at a rate of 25hz
        private const val SAMPLING_PERIOD_US = 40_000
    }
}

class HealthServicesHeartRateSource(
    context: Context
) : HeartRateSource {

    private val appContext = context.applicationContext
    private val exerciseClient =
        HealthServices.getClient(appContext).exerciseClient

    private val mainExecutor =
        ContextCompat.getMainExecutor(appContext)

    // Keep cleanup independent of Activity lifecycle cancellation.
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val operationMutex = Mutex()

    private var startRequested = false
    private var ownsExercise = false
    private var sequence = 0L
    private var lastTimestampNanos = -1L

    private var recordCallback: ((HeartRateRecord) -> Unit)? = null
    private var statusCallback: ((SensorStatus) -> Unit)? = null

    private var currentStatus = SensorStatus.NOT_STARTED
    private var registeredCallback: ExerciseUpdateCallback? = null

    override fun start(
        onRecord: (HeartRateRecord) -> Unit,
        onStatusChanged: (SensorStatus) -> Unit
    ) {
        if (startRequested || registeredCallback != null) {
            onStatusChanged(currentStatus)
            return
        }

        recordCallback = onRecord
        statusCallback = onStatusChanged

        if (!hasPermission()) {
            updateStatus(SensorStatus.PERMISSION_REQUIRED)
            return
        }

        startRequested = true
        updateStatus(SensorStatus.WAITING_FOR_DATA)

        scope.launch {
            operationMutex.withLock {
                if (!startRequested) return@withLock

                try {
                    val capabilities =
                        exerciseClient.getCapabilitiesAsync().await()

                    if (!startRequested) return@withLock

                    if (ExerciseType.WORKOUT !in
                        capabilities.supportedExerciseTypes
                    ) {
                        startRequested = false
                        updateStatus(SensorStatus.UNAVAILABLE)
                        return@withLock
                    }

                    val workoutCapabilities =
                        capabilities.getExerciseTypeCapabilities(
                            ExerciseType.WORKOUT
                        )

                    if (DataType.HEART_RATE_BPM !in
                        workoutCapabilities.supportedDataTypes
                    ) {
                        startRequested = false
                        updateStatus(SensorStatus.UNAVAILABLE)
                        return@withLock
                    }

                    val info =
                        exerciseClient.getCurrentExerciseInfoAsync().await()

                    if (!startRequested) return@withLock

                    // Do not replace an existing workout.
                    if (info.exerciseTrackedStatus !=
                        ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS
                    ) {
                        startRequested = false
                        Log.w(TAG, "An exercise is already in progress")
                        updateStatus(SensorStatus.DATA_ERROR)
                        return@withLock
                    }

                    val registration = CompletableDeferred<Unit>()
                    val callback = createCallback(registration)

                    registeredCallback = callback
                    exerciseClient.setUpdateCallback(
                        mainExecutor,
                        callback
                    )

                    withTimeout(10_000L) {
                        registration.await()
                    }

                    if (!startRequested) {
                        clearCallback()
                        return@withLock
                    }

                    val config = ExerciseConfig(
                        exerciseType = ExerciseType.WORKOUT,
                        dataTypes = setOf(DataType.HEART_RATE_BPM),
                        isAutoPauseAndResumeEnabled = false,
                        isGpsEnabled = false
                    )

                    exerciseClient.startExerciseAsync(config).await()
                    ownsExercise = true

                    Log.d(TAG, "Exercise start request completed")

                    // A stop request may arrive while startup is pending.
                    if (!startRequested) {
                        endOwnedExercise()
                    }
                } catch (error: Exception) {
                    startRequested = false
                    Log.e(TAG, "Failed to start exercise", error)

                    updateStatus(
                        if (hasPermission()) {
                            SensorStatus.DATA_ERROR
                        } else {
                            SensorStatus.PERMISSION_REQUIRED
                        }
                    )

                    if (!ownsExercise) {
                        clearCallback()
                    }
                }
            }
        }
    }

    private fun createCallback(
        registration: CompletableDeferred<Unit>
    ): ExerciseUpdateCallback {
        return object : ExerciseUpdateCallback {

            override fun onRegistered() {
                registration.complete(Unit)
                Log.d(TAG, "Exercise callback registered")
            }

            override fun onRegistrationFailed(throwable: Throwable) {
                registration.completeExceptionally(throwable)
            }

            override fun onExerciseUpdateReceived(
                update: ExerciseUpdate
            ) {
                if (registeredCallback !== this) return

                val state = update.exerciseStateInfo.state
                Log.d(TAG, "exerciseState=$state")

                if (state.isEnded) {
                    ownsExercise = false
                    startRequested = false
                    updateStatus(SensorStatus.STOPPED)

                    scope.launch {
                        operationMutex.withLock {
                            clearCallback()
                        }
                    }
                    return
                }

                if (!startRequested) return

                val points = update.latestMetrics
                    .getData(DataType.HEART_RATE_BPM)
                    .sortedBy { it.timeDurationFromBoot }

                for (point in points) {
                    val bpm = point.value
                    val timestamp =
                        point.timeDurationFromBoot.toNanos()

                    if (!bpm.isFinite() || bpm <= 0.0) {
                        updateStatus(SensorStatus.DATA_ERROR)
                        continue
                    }

                    // Avoid emitting duplicate or older readings.
                    if (timestamp <= lastTimestampNanos) continue

                    lastTimestampNanos = timestamp
                    sequence += 1

                    val record = HeartRateRecord(
                        bpm = bpm,
                        timestampNanos = timestamp,
                        sequence = sequence,
                        source = HeartRateSourceType.REAL
                    )

                    updateStatus(SensorStatus.ACTIVE)
                    recordCallback?.invoke(record)
                }
            }

            override fun onAvailabilityChanged(
                dataType: DataType<*, *>,
                availability: Availability
            ) {
                if (registeredCallback !== this || !startRequested) {
                    return
                }

                if (dataType == DataType.HEART_RATE_BPM) {
                    Log.d(TAG, "availability=$availability")

                    if (availability != DataTypeAvailability.AVAILABLE) {
                        updateStatus(SensorStatus.WAITING_FOR_DATA)
                    }
                }
            }

            override fun onLapSummaryReceived(
                lapSummary: ExerciseLapSummary
            ) {
                // Lap metrics are not used in this heart-rate test.
            }
        }
    }

    override fun stop() {
        startRequested = false

        scope.launch {
            operationMutex.withLock {
                try {
                    if (ownsExercise) {
                        endOwnedExercise()
                    } else {
                        clearCallback()
                        updateStatus(SensorStatus.STOPPED)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to end exercise", error)
                    updateStatus(SensorStatus.DATA_ERROR)
                }
            }
        }
    }

    private suspend fun endOwnedExercise() {
        exerciseClient.endExerciseAsync().await()

        // Keep the callback until the ENDED update arrives.
        Log.d(TAG, "End requested; waiting for ENDED")
    }

    private suspend fun clearCallback() {
        val callback = registeredCallback ?: return

        try {
            exerciseClient.clearUpdateCallbackAsync(callback).await()
            registeredCallback = null
            Log.d(TAG, "Exercise callback cleared")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to clear exercise callback", error)
            updateStatus(SensorStatus.DATA_ERROR)
        }
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            requiredPermission()
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateStatus(status: SensorStatus) {
        // Notify the observer only when the status changes.
        if (currentStatus == status) return

        currentStatus = status
        statusCallback?.invoke(status)
    }

    companion object {
        private const val TAG = "HeartRateCheck"

        fun requiredPermission(): String {
            return if (Build.VERSION.SDK_INT >= 36) {
                "android.permission.health.READ_HEART_RATE"
            } else {
                Manifest.permission.BODY_SENSORS
            }
        }
    }
}