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

        // 已经采集时，不重复注册监听。
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
        currentStatus = status
        statusCallback?.invoke(status)
    }

    companion object {
        // Request one sample at a rate of 25hz
        private const val SAMPLING_PERIOD_US = 40_000
    }
}