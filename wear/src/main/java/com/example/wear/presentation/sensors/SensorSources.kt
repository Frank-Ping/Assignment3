package com.example.wear.presentation.sensors

import com.example.wear.presentation.data.AccelerometerRecord
import com.example.wear.presentation.data.HeartRateRecord
import com.example.wear.presentation.data.SensorStatus

interface AccelerometerSource {
    fun start(
        onRecord: (AccelerometerRecord) -> Unit,
        onStatusChanged: (SensorStatus) -> Unit
    )

    fun stop()
}

interface HeartRateSource {
    fun start(
        onRecord: (HeartRateRecord) -> Unit,
        onStatusChanged: (SensorStatus) -> Unit
    )

    fun stop()
}