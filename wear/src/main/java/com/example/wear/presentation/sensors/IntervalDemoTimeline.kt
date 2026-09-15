package com.example.wear.presentation.sensors

/** Deterministic exercise/rest/exercise inputs, independent of detected session phases. */
object IntervalDemoTimeline {
    fun heartRateAt(seconds: Double): Double = when {
        seconds < 35 -> 72.0
        seconds < 55 -> 72.0 + (seconds - 35) * 3.9
        seconds < 80 -> 150.0
        seconds < 110 -> 150.0 - (seconds - 80) * 2.0
        seconds < 135 -> 90.0 + (seconds - 110) * 2.6
        seconds < 160 -> 155.0
        seconds < 235 -> 155.0 - (seconds - 160) * (83.0 / 75.0)
        else -> 72.0
    }

    fun isMovingAt(seconds: Double): Boolean =
        (seconds >= 35 && seconds < 80) || (seconds >= 110 && seconds < 160)
}
