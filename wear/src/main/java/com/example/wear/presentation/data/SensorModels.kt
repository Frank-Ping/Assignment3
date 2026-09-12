package com.example.wear.presentation.data

// Store the status of the data source
enum class SensorStatus {
    NOT_STARTED,
    ACTIVE,
    STOPPED,
    PERMISSION_REQUIRED,
    WAITING_FOR_DATA,
    UNAVAILABLE,
    DATA_ERROR
}

// Heart rate is whether real collection or simulation generation
enum class HeartRateSourceType {
    REAL,
    DEMO
}

// The result of a three-axis acceleration collection, with the unit of m/s²
data class AccelerometerRecord(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestampNanos: Long,
    val sequence: Long
)

// The result of a heart rate collection, with the unit of bpm
data class HeartRateRecord(
    val bpm: Double,
    val timestampNanos: Long,
    val sequence: Long,
    val source: HeartRateSourceType
)